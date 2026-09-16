package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.tool.ToolContext;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 跨传输共享的 JSON-RPC 会话；调用线程执行进度回调，传输线程只路由消息。 */
final class McpConnection implements AutoCloseable {
    private final McpTransport transport;
    private final long timeoutNanos;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    McpConnection(List<String> command, Path directory, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        this(new StdioMcpTransport(command, directory, environment, timeout), timeout);
    }

    McpConnection(McpTransport transport, Duration timeout) throws IOException, InterruptedException {
        this.transport = transport;
        timeoutNanos = timeout.toNanos();
        try {
            transport.start(this::receive, this::failed);
            if (closed.get()) throw new McpException("MCP connection failed during startup");
        } catch (IOException | InterruptedException | RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    void protocolVersion(String version) { transport.protocolVersion(version); }
    void initialized() { transport.initialized(); }

    JsonNode request(String method, ObjectNode params, ToolContext context)
            throws IOException, InterruptedException {
        checkCancelled(context);
        String id = Long.toString(nextId.incrementAndGet());
        long started = System.nanoTime();
        var call = new Pending();
        pending.put(id, call);
        CompletableFuture<Void> outbound = null;
        try {
            var message = message(method, params);
            message.put("id", id);
            if (context != null) {
                ObjectNode arguments = (ObjectNode) message.get("params");
                arguments.withObject("_meta").put("progressToken", id);
            }
            outbound = send(message);
            await(outbound, started, context, call);
            return await(call.response, started, context, call);
        } catch (InterruptedException | RuntimeException | Error error) {
            abandon(id, method, outbound);
            throw error;
        } catch (IOException error) {
            if (!call.response.isDone() || call.transportFailure) abandon(id, method, outbound);
            throw error;
        } finally {
            pending.remove(id, call);
            transport.cancelRequest(id);
        }
    }

    void notify(String method, ObjectNode params) throws IOException, InterruptedException {
        long started = System.nanoTime();
        var outbound = send(message(method, params));
        try {
            await(outbound, started, null, null);
        } catch (IOException | InterruptedException error) {
            close();
            throw error;
        }
    }

    private static ObjectNode message(String method, ObjectNode params) {
        var message = McpJson.object().put("jsonrpc", "2.0").put("method", method);
        message.set("params", params == null ? McpJson.object() : params.deepCopy());
        return message;
    }

    private CompletableFuture<Void> send(ObjectNode message) throws McpException {
        if (closed.get()) throw new McpException("MCP connection is closed");
        return transport.send(message);
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
                if (error.getCause() instanceof McpException safe) throw safe;
                throw new McpException("MCP transport failed");
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

    private void abandon(String id, String method, CompletableFuture<Void> outbound) {
        pending.remove(id);
        transport.cancelRequest(id);
        if ("initialize".equals(method) || outbound == null) {
            close();
            return;
        }
        try {
            send(message("notifications/cancelled", McpJson.object().put("requestId", id)))
                    .whenComplete((ignored, error) -> {
                        if (error != null) close(new McpException("MCP cancellation could not be sent"));
                    });
        } catch (IOException ignored) {
            close();
        }
    }

    private void receive(JsonNode message) {
        if (closed.get()) return;
        try { route(message); }
        catch (McpException error) { close(error); }
        catch (RuntimeException error) { close(new McpException("Invalid MCP message")); }
    }

    private void route(JsonNode message) throws McpException {
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
            throw new McpException("Invalid MCP JSON-RPC message");
        }
        JsonNode id = message.get("id");
        if (message.has("method")) {
            if (!message.get("method").isTextual()) throw new McpException("Invalid MCP method");
            if (id != null) {
                if (!validId(id)) throw new McpException("Invalid MCP request ID");
                var reply = McpJson.object().put("jsonrpc", "2.0");
                reply.set("id", id);
                if ("ping".equals(message.get("method").textValue())) reply.set("result", McpJson.object());
                else reply.set("error", McpJson.object().put("code", -32601).put("message", "Method not found"));
                send(reply).whenComplete((ignored, error) -> {
                    if (error != null) close(new McpException("MCP server response could not be sent"));
                });
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

    private void failed(String id, McpException failure) {
        if (id == null) {
            close(failure);
        } else {
            var call = pending.get(id);
            if (call != null) {
                call.transportFailure = true;
                call.response.completeExceptionally(failure);
            }
        }
    }

    @Override
    public void close() { close(new McpException("MCP connection is closed")); }

    private void close(McpException failure) {
        if (closed.compareAndSet(false, true)) {
            pending.values().forEach(call -> call.response.completeExceptionally(failure));
        }
        transport.close();
    }

    private static final class Pending {
        private volatile boolean transportFailure;
        private final CompletableFuture<JsonNode> response = new CompletableFuture<>();
        private final AtomicReference<JsonNode> progress = new AtomicReference<>();
    }

}
