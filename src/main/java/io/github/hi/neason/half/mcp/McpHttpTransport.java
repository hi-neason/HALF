package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.http.SseFrameReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** HTTP exchanges are independent: server requests can be answered while a POST SSE stream is open. */
final class McpHttpTransport implements McpTransport {
    private static final Set<String> MANAGED_HEADERS = Set.of("accept", "content-type", "host", "content-length",
            "mcp-session-id", "mcp-protocol-version", "connection", "expect", "upgrade", "last-event-id");
    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final boolean legacySse;
    private final HttpClient http;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("half-mcp-http-deadline").factory());
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();
    private final Map<String, Exchange> requests = new ConcurrentHashMap<>();
    private final CompletableFuture<URI> legacyEndpoint = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile URI postEndpoint;
    private volatile String sessionId;
    private volatile String protocolVersion;
    private Consumer<JsonNode> receive;
    private BiConsumer<String, McpException> failure;

    McpHttpTransport(URI endpoint, Map<String, String> headers, Duration timeout, boolean legacySse) {
        this.endpoint = validateEndpoint(endpoint);
        this.headers = Map.copyOf(headers);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("MCP timeout must be positive");
        this.legacySse = legacySse;
        this.postEndpoint = endpoint;
        var validation = HttpRequest.newBuilder(endpoint);
        this.headers.forEach((name, value) -> {
            if (MANAGED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("MCP managed HTTP header cannot be overridden");
            }
            try { validation.header(name, value); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid MCP HTTP header"); }
        });
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static URI validateEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                || value.getHost() == null || value.getRawUserInfo() != null || value.getRawFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must be an absolute HTTP(S) URI without user info or fragment");
        }
        return value;
    }

    @Override
    public void start(Consumer<JsonNode> receive, BiConsumer<String, McpException> failure) throws IOException, InterruptedException {
        this.receive = Objects.requireNonNull(receive, "receive");
        this.failure = Objects.requireNonNull(failure, "failure");
        if (!legacySse) return;
        openEventStream(true);
        try { postEndpoint = legacyEndpoint.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException error) { close(); throw new McpException("MCP SSE endpoint discovery timed out"); }
        catch (ExecutionException error) {
            close();
            if (error.getCause() instanceof McpException safe) throw safe;
            throw new McpException("MCP SSE endpoint discovery failed");
        }
        catch (InterruptedException error) { close(); throw error; }
    }

    @Override
    public CompletableFuture<Void> send(ObjectNode message) {
        if (closed.get() || failed.get()) return CompletableFuture.failedFuture(new McpException("MCP HTTP transport is closed"));
        boolean request = message.has("id") && message.has("method");
        String id = request ? message.path("id").asText() : null;
        var exchange = new Exchange(id);
        exchanges.add(exchange);
        if (id != null) requests.put(id, exchange);
        if (closed.get() || failed.get()) { exchange.cancel(); return exchange.accepted; }
        try {
            exchange.deadline = deadlines.schedule(() -> {
                exchange.error(new McpException("MCP HTTP request timed out"));
                exchange.cancel();
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
            var body = McpJson.encode(message);
            var builder = request(postEndpoint).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.register(http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()));
            Thread.ofVirtual().name("half-mcp-http-post").start(() -> {
                try {
                    var response = exchange.future.get();
                    if (!exchange.attach(response.body())) return;
                    checkSessionStatus(response);
                    int status = response.statusCode();
                    if (legacySse) {
                        if (status < 200 || status >= 300) throw statusError(status);
                        exchange.accepted.complete(null);
                    } else if (!request) {
                        if (status != 202) throw statusError(status);
                        updateSession(response, false);
                        if (response.body().read() != -1) throw new McpException("MCP HTTP 202 response must have an empty body");
                        exchange.accepted.complete(null);
                    } else {
                        if (status != 200) throw statusError(status);
                        updateSession(response, "initialize".equals(message.path("method").asText()));
                        String contentType = mediaType(response);
                        if (!contentType.equals("application/json") && !contentType.equals("text/event-stream")) {
                            throw new McpException("Unsupported MCP HTTP response content type");
                        }
                        exchange.accepted.complete(null);
                        if (contentType.equals("application/json")) {
                            var value = McpJson.parse(response.body().readNBytes(McpJson.MAX_MESSAGE_BYTES + 1));
                            if (!isResponse(value, id)) throw new McpException("MCP HTTP response does not match request");
                            receive.accept(value);
                        } else readEvents(exchange, false, id);
                    }
                } catch (Exception error) { exchange.error(error); }
                finally { exchange.finish(); }
            });
        } catch (Exception error) { exchange.error(error); exchange.finish(); }
        return exchange.accepted;
    }

    private HttpRequest.Builder request(URI uri) {
        var builder = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach(builder::header);
        if (!legacySse) {
            if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
            if (protocolVersion != null) builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return builder;
    }

    private void openEventStream(boolean legacy) {
        var exchange = new Exchange(null);
        exchanges.add(exchange);
        if (closed.get() || failed.get()) { exchange.cancel(); return; }
        try {
            exchange.register(http.sendAsync(request(endpoint).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream()));
            Thread.ofVirtual().name("half-mcp-http-events").start(() -> {
                try {
                    var response = exchange.future.get();
                    if (!exchange.attach(response.body())) return;
                    checkSessionStatus(response);
                    if (!legacy && response.statusCode() == 405) return;
                    if (response.statusCode() != 200) throw statusError(response.statusCode());
                    if (!legacy) updateSession(response, false);
                    if (!mediaType(response).equals("text/event-stream")) {
                        throw new McpException("MCP event stream requires text/event-stream");
                    }
                    readEvents(exchange, legacy, null);
                } catch (Exception error) { exchange.error(error); }
                finally { exchange.finish(); }
            });
        } catch (Exception error) { exchange.error(error); exchange.finish(); }
    }

    private void readEvents(Exchange exchange, boolean legacy, String requestId) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var frames = new SseFrameReader(new InputStreamReader(exchange.body, decoder), McpJson.MAX_MESSAGE_BYTES);
        for (;;) {
            var frame = frames.next();
            if (frame == null) {
                if (!legacy && requestId == null) return; // The optional GET stream may end independently.
                throw new McpException(requestId == null
                        ? "MCP event stream closed" : "MCP response stream ended before a response");
            }
            if (legacy && "endpoint".equals(frame.event())) {
                URI discovered;
                try { discovered = validateEndpoint(endpoint.resolve(frame.data())); }
                catch (IllegalArgumentException error) { throw new McpException("Invalid MCP SSE endpoint"); }
                if (!sameOrigin(endpoint, discovered)) throw new McpException("MCP SSE endpoint must have the same origin");
                if (legacyEndpoint.isDone() && !discovered.equals(legacyEndpoint.getNow(null))) {
                    throw new McpException("MCP SSE endpoint changed");
                }
                legacyEndpoint.complete(discovered);
                continue;
            }
            if (frame.data().isEmpty()) continue;
            if (frame.event() != null && !frame.event().isEmpty() && !"message".equals(frame.event())) continue;
            var value = McpJson.parse(frame.data());
            receive.accept(value);
            if (requestId != null && isResponse(value, requestId)) return;
        }
    }

    private static boolean isResponse(JsonNode value, String id) {
        return value.path("id").isTextual() && id.equals(value.path("id").textValue()) && !value.has("method")
                && (value.has("result") || value.has("error"));
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String mediaType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private void checkSessionStatus(HttpResponse<?> response) throws McpException {
        if (!legacySse && sessionId != null && response.statusCode() == 404) {
            throw fatal("MCP HTTP session expired; create a new client connection");
        }
    }

    private void updateSession(HttpResponse<?> response, boolean initialize) throws McpException {
        var values = response.headers().allValues("Mcp-Session-Id");
        if (values.isEmpty()) return;
        if (values.size() != 1 || values.getFirst().isEmpty()
                || !values.getFirst().chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
            throw fatal("Invalid MCP session header");
        }
        String value = values.getFirst();
        if (initialize && sessionId == null) sessionId = value;
        else if (!value.equals(sessionId)) throw fatal("MCP session header changed unexpectedly");
    }

    private McpException fatal(String message) {
        var error = new McpException(message);
        failConnection(error);
        return error;
    }

    private void failConnection(McpException error) {
        legacyEndpoint.completeExceptionally(error);
        if (failed.compareAndSet(false, true)) {
            exchanges.forEach(exchange -> {
                exchange.accepted.completeExceptionally(error);
                exchange.cancel();
            });
            failure.accept(null, error);
        }
    }

    private static McpException statusError(int status) { return new McpException("MCP HTTP status " + status); }

    @Override public void protocolVersion(String version) { protocolVersion = Objects.requireNonNull(version, "version"); }
    @Override public void initialized() { if (!legacySse && !closed.get() && !failed.get()) openEventStream(false); }
    @Override public void cancelRequest(String id) { var exchange = requests.remove(id); if (exchange != null) exchange.cancel(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        exchanges.forEach(Exchange::cancel);
        if (!legacySse && sessionId != null && !failed.get()) {
            try {
                http.sendAsync(request(endpoint).DELETE().build(), HttpResponse.BodyHandlers.discarding())
                        .get(Math.min(timeout.toMillis(), 1000), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* Session termination is best effort. */ }
        }
        deadlines.shutdownNow();
        http.shutdownNow();
    }

    private final class Exchange {
        private final String id;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean errorReported = new AtomicBoolean();
        private final CompletableFuture<Void> accepted = new CompletableFuture<>();
        private volatile CompletableFuture<HttpResponse<InputStream>> future;
        private volatile InputStream body;
        private volatile ScheduledFuture<?> deadline;

        private Exchange(String id) { this.id = id; }

        private void register(CompletableFuture<HttpResponse<InputStream>> value) {
            future = value;
            value.whenComplete((response, error) -> {
                if (response != null && (cancelled.get() || closed.get() || failed.get())) {
                    try { response.body().close(); } catch (IOException ignored) { }
                }
            });
            if (cancelled.get() || closed.get() || failed.get()) cancel();
        }

        private boolean attach(InputStream value) throws IOException {
            body = value;
            if (cancelled.get() || closed.get() || failed.get()) { value.close(); return false; }
            return true;
        }

        private void error(Exception error) {
            if (cancelled.get() || closed.get() || failed.get()) { cancel(); return; }
            if (finished.get() || !errorReported.compareAndSet(false, true)) return;
            var safe = error instanceof McpException mcp ? mcp : new McpException("MCP HTTP exchange failed");
            accepted.completeExceptionally(safe);
            if (id == null) failConnection(safe);
            else failure.accept(id, safe);
        }

        private void cancel() {
            cancelled.set(true);
            accepted.completeExceptionally(new McpException("MCP HTTP request cancelled"));
            if (future != null) future.cancel(true);
            finish();
        }

        private void finish() {
            finished.set(true);
            if (deadline != null) deadline.cancel(false);
            if (body != null) try { body.close(); } catch (IOException ignored) { }
            exchanges.remove(this);
            if (id != null) requests.remove(id, this);
        }
    }
}
