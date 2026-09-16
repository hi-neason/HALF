package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.tool.ToolContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 单个 stdio 会话。读取线程只做路由，进度回调在请求调用线程执行。 */
final class McpConnection implements AutoCloseable {
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Process process;
    private final long timeoutNanos;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Outbound> writes = new ArrayBlockingQueue<>(64);
    private final Thread writer;
    private final AtomicReference<Outbound> activeWrite = new AtomicReference<>();

    McpConnection(List<String> command, Path directory, Map<String, String> environment,
                  Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
        var builder = new ProcessBuilder(List.copyOf(command));
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().putAll(Map.copyOf(environment));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new McpException("Unable to start MCP server");
        }
        writer = Thread.ofVirtual().name("half-mcp-writer").unstarted(this::writeLoop);
        writer.start();
        Thread.ofVirtual().name("half-mcp-write-deadline").start(this::watchWrites);
        Thread.ofVirtual().name("half-mcp-reader").start(this::readLoop);
    }

    JsonNode request(String method, ObjectNode params, ToolContext context)
            throws IOException, InterruptedException {
        checkCancelled(context);
        String id = Long.toString(nextId.incrementAndGet());
        long started = System.nanoTime();
        var call = new Pending();
        pending.put(id, call);
        Outbound outbound = null;
        try {
            var message = message(method, params);
            message.put("id", id);
            if (context != null) {
                ObjectNode arguments = (ObjectNode) message.get("params");
                arguments.withObject("_meta").put("progressToken", id);
            }
            outbound = enqueue(message, started);
            await(outbound.written(), started, context, call);
            return await(call.response, started, context, call);
        } catch (InterruptedException | RuntimeException | Error error) {
            abandon(id, method, outbound);
            throw error;
        } catch (IOException error) {
            if (!call.response.isDone()) abandon(id, method, outbound);
            throw error;
        } finally {
            pending.remove(id, call);
        }
    }

    void notify(String method, ObjectNode params) throws IOException, InterruptedException {
        long started = System.nanoTime();
        Outbound outbound = enqueue(message(method, params), started);
        try {
            await(outbound.written(), started, null, null);
        } catch (IOException | InterruptedException error) {
            close();
            throw error;
        }
    }

    private static ObjectNode message(String method, ObjectNode params) {
        var message = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        message.set("params", params == null ? JSON.createObjectNode() : params.deepCopy());
        return message;
    }

    private Outbound enqueue(ObjectNode message, long started) throws IOException {
        if (closed.get()) throw new McpException("MCP connection is closed");
        byte[] bytes = JSON.writeValueAsBytes(message);
        if (bytes.length > MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
        var outbound = new Outbound(bytes, started, new CompletableFuture<>());
        if (!writes.offer(outbound)) {
            var failure = new McpException("MCP write queue is full");
            close(failure);
            throw failure;
        }
        if (closed.get()) outbound.written().completeExceptionally(new McpException("MCP connection is closed"));
        return outbound;
    }

    private <T> T await(CompletableFuture<T> future, long started, ToolContext context, Pending call)
            throws IOException, InterruptedException {
        while (true) {
            checkCancelled(context);
            reportProgress(call, context);
            long remaining = timeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw new McpException("MCP request timed out");
            try {
                T result = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
                checkCancelled(context);
                reportProgress(call, context);
                return result;
            } catch (TimeoutException ignored) {
                // Periodically check caller cancellation even when the server is silent.
            } catch (ExecutionException error) {
                throw (IOException) error.getCause();
            }
        }
    }

    private static void reportProgress(Pending call, ToolContext context) throws InterruptedException {
        if (call == null || context == null) return;
        JsonNode progress = call.progress.getAndSet(null);
        if (progress != null) context.reportProgress(progress.path("message").isTextual()
                ? progress.get("message").textValue() : progress.get("progress").asText());
    }

    private static void checkCancelled(ToolContext context) throws InterruptedException {
        if (context != null) context.checkCancelled();
        else if (Thread.interrupted()) throw new InterruptedException("MCP request interrupted");
    }

    private void abandon(String id, String method, Outbound outbound) {
        pending.remove(id);
        if ("initialize".equals(method) || outbound == null || !outbound.written().isDone()) {
            close();
            return;
        }
        try {
            enqueue(message("notifications/cancelled", JSON.createObjectNode().put("requestId", id)),
                    System.nanoTime());
        } catch (IOException ignored) {
            close();
        }
    }

    private void writeLoop() {
        Outbound active = null;
        try {
            while (!closed.get()) {
                active = writes.take();
                activeWrite.set(active);
                if (closed.get()) throw new McpException("MCP connection is closed");
                if (System.nanoTime() - active.started() >= timeoutNanos) {
                    throw new McpException("MCP write timed out");
                }
                process.getOutputStream().write(active.bytes());
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
                active.written().complete(null);
                activeWrite.set(null);
                active = null;
            }
        } catch (McpException error) {
            close(error);
        } catch (IOException error) {
            close(new McpException("MCP write failed"));
        } catch (InterruptedException error) {
            close(new McpException("MCP writer interrupted"));
        } finally {
            close();
        }
    }

    private void watchWrites() {
        try {
            while (!closed.get()) {
                Outbound active = activeWrite.get();
                if (active != null && System.nanoTime() - active.started() >= timeoutNanos) {
                    close(new McpException("MCP write timed out"));
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    private void readLoop() {
        try (var input = process.getInputStream()) {
            var line = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\n') {
                        String text = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(line.toByteArray())).toString();
                        JsonNode message = JSON.readTree(text);
                        line.reset();
                        receive(message);
                    } else {
                        if (line.size() >= MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
                        line.write(buffer[index]);
                    }
                }
            }
            throw new McpException(line.size() == 0 ? "MCP server closed its output" : "Incomplete MCP message at end of stream");
        } catch (McpException error) {
            close(error);
        } catch (CharacterCodingException error) {
            close(new McpException("Invalid MCP UTF-8 message"));
        } catch (JsonProcessingException error) {
            close(new McpException("Invalid MCP JSON message"));
        } catch (IOException error) {
            close(new McpException("MCP read failed"));
        } catch (RuntimeException error) {
            close(new McpException("Invalid MCP message"));
        }
    }

    private void receive(JsonNode message) throws IOException {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            throw new McpException("Invalid MCP JSON-RPC message");
        }
        JsonNode id = message.get("id");
        if (message.has("method")) {
            if (!message.get("method").isTextual()) throw new McpException("Invalid MCP method");
            if (id != null) {
                if (!validId(id)) throw new McpException("Invalid MCP request ID");
                var reply = JSON.createObjectNode().put("jsonrpc", "2.0");
                reply.set("id", id);
                if ("ping".equals(message.get("method").textValue())) reply.set("result", JSON.createObjectNode());
                else reply.set("error", JSON.createObjectNode().put("code", -32601).put("message", "Method not found"));
                enqueue(reply, System.nanoTime());
            } else if ("notifications/progress".equals(message.get("method").textValue())) {
                JsonNode params = message.path("params");
                JsonNode token = params.get("progressToken");
                if (token != null && token.isTextual() && params.path("progress").isNumber()) {
                    Pending call = pending.get(token.textValue());
                    if (call != null) call.progress.set(params);
                }
            }
            return;
        }
        if (id == null || !validId(id)) throw new McpException("Invalid MCP response ID");
        // Client IDs are strings. A numeric response is not the same JSON-RPC ID.
        Pending call = id.isTextual() ? pending.get(id.textValue()) : null;
        if (call == null) return;
        if (message.has("result") == message.has("error")) {
            call.response.completeExceptionally(new McpException("Invalid MCP response"));
        } else if (message.has("error")) {
            JsonNode error = message.get("error");
            if (!error.isObject() || !error.path("code").isIntegralNumber()
                    || !error.path("code").canConvertToInt() || !error.path("message").isTextual()) {
                call.response.completeExceptionally(new McpException("Invalid MCP error response"));
            } else {
                call.response.completeExceptionally(new McpException("MCP server returned an error", error.get("code").intValue()));
            }
        } else {
            call.response.complete(message.get("result"));
        }
    }

    private static boolean validId(JsonNode id) {
        return id.isTextual() || id.isIntegralNumber();
    }

    @Override
    public void close() {
        close(new McpException("MCP connection is closed"));
    }

    private void close(McpException failure) {
        if (closed.compareAndSet(false, true)) {
            pending.values().forEach(call -> call.response.completeExceptionally(failure));
            Outbound queued;
            while ((queued = writes.poll()) != null) queued.written().completeExceptionally(failure);
            Outbound active = activeWrite.get();
            if (active != null) active.written().completeExceptionally(failure);
            if (Thread.currentThread() != writer) writer.interrupt();
            // Closing a pipe can wait for an active writer. Keep that wait off the caller.
            Thread.ofVirtual().name("half-mcp-close-input").start(() -> {
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            });
        }
        boolean interrupted = false;
        try {
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.toHandle().destroy();
                if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    process.toHandle().destroyForcibly();
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            process.toHandle().destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class Pending {
        private final CompletableFuture<JsonNode> response = new CompletableFuture<>();
        private final AtomicReference<JsonNode> progress = new AtomicReference<>();
    }

    private record Outbound(byte[] bytes, long started, CompletableFuture<Void> written) { }
}
