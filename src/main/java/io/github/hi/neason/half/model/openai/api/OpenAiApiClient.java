package io.github.hi.neason.half.model.openai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.http.HttpCalls;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** 两类资源共用的原生 HTTP 传输，不承担模型内容映射。 */
abstract class OpenAiApiClient implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    final HttpClient http;
    final Duration timeout;
    final ScheduledThreadPoolExecutor timer;
    final Set<OpenAiRawStream> streams = ConcurrentHashMap.newKeySet();
    private final HttpCalls calls;
    private final String base;
    private final String key;
    volatile boolean closed;

    OpenAiApiClient(URI baseUri, String apiKey, Duration timeout) {
        Objects.requireNonNull(baseUri, "baseUri");
        if (!("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an HTTP(S) API base without credentials, query or fragment");
        }
        key = Objects.requireNonNull(apiKey, "apiKey");
        if (key.isBlank() || key.chars().anyMatch(c -> c <= 32 || c > 126))
            throw new IllegalArgumentException("Invalid API key");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        timeout.toNanos();
        base = baseUri.toASCIIString().replaceAll("/+$", "") + "/";
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        calls = new HttpCalls(http, timeout);
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("half-api-timeout").factory());
        timer.setRemoveOnCancelPolicy(true);
    }

    static ObjectNode mode(ObjectNode body, boolean stream) {
        return Objects.requireNonNull(body, "body").deepCopy().put("stream", stream);
    }

    static String segment(String value) {
        if (Objects.requireNonNull(value, "id").isBlank() || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Invalid resource id");
        return encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest request(String method, String path, Map<String, ?> query, ObjectNode body, boolean stream) {
        StringBuilder url = new StringBuilder(base).append(path);
        query.forEach((name, value) -> {
            if (value instanceof Iterable<?> values) {
                for (Object item : values) parameter(url, name.endsWith("[]") ? name : name + "[]", item);
            } else parameter(url, name, value);
        });
        var builder = HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout)
                .header("Authorization", "Bearer " + key)
                .header("Accept", stream ? "text/event-stream" : "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private static void parameter(StringBuilder url, String name, Object value) {
        Objects.requireNonNull(name, "query name");
        Objects.requireNonNull(value, "query value");
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
            throw new IllegalArgumentException("Query values must be scalar or iterable scalars");
        url.append(url.indexOf("?") < 0 ? '?' : '&').append(encode(name)).append('=').append(encode(value.toString()));
    }

    final JsonNode call(String method, String path, Map<String, ?> query, ObjectNode body)
            throws IOException, InterruptedException {
        if (closed) throw new IllegalStateException("Client is closed");
        var response = calls.send(request(method, path, query, body, false));
        if (response.statusCode() == 204 || response.statusCode() == 205) return NullNode.instance;
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node == null) throw new IOException("Empty JSON response");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IOException("Invalid JSON response");
        }
    }

    final Flow.Publisher<OpenAiSseEvent> streamRequest(String method, String path, Map<String, ?> query,
                                                     ObjectNode body, boolean responses) {
        // Snapshot the body and query now; every subscription gets independent transport and parsing state.
        HttpRequest request = request(method, path, query, body, true);
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            var stream = new OpenAiRawStream(this, request, subscriber, responses);
            streams.add(stream);
            try { subscriber.onSubscribe(stream); }
            catch (Throwable error) { stream.cancel(); return; }
            stream.start();
        };
    }

    static void closeBody(InputStream body) {
        if (body != null) try { body.close(); } catch (IOException ignored) { }
    }

    @Override public void close() {
        closed = true;
        streams.forEach(stream -> stream.fail(new IOException("Client is closed")));
        calls.close();
        timer.shutdownNow();
        http.shutdownNow();
    }
}
