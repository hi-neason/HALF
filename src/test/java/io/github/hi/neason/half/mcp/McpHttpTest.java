package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.agent.Agent;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class McpHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamableJsonCompletesAgentLoopAndSendsSessionHeaders() throws Exception {
        try (var server = new Server(false)) {
            try (var client = server.builder().headers(Map.of("Authorization", "Bearer fixture")).connect()) {
                var turns = new AtomicInteger();
                var agent = Agent.builder().tools(client.tools("remote")).model(request -> {
                    if (turns.incrementAndGet() == 1) return new ChatResponse(
                            List.of(new ContentBlock.ToolCall("one", "remote_echo", "{}")), "tool_calls", Optional.empty());
                    assertEquals("one", request.messages().getLast().toolCallId());
                    assertTrue(request.messages().getLast().text().contains("remote result"));
                    return new ChatResponse("done", "stop", Optional.empty());
                }).build();
                assertEquals("done", agent.run("use tool").text());
                assertEquals(2, turns.get());
                assertTrue(server.get.await(3, TimeUnit.SECONDS));
            }
            assertTrue(server.deleted.await(3, TimeUnit.SECONDS));
            assertTrue(server.requests.stream().allMatch(r -> "Bearer fixture".equals(r.authorization)));
            var initialize = server.requests.stream().filter(r -> r.rpc.equals("initialize")).findFirst().orElseThrow();
            assertNull(initialize.session);
            for (var request : server.requests) {
                if (request.rpc.equals("initialize")) continue;
                assertEquals("session-1", request.session, request.toString());
                assertEquals(McpClient.PROTOCOL_VERSION, request.version, request.toString());
                if (request.method.equals("POST")) {
                    assertTrue(request.accept.contains("application/json"));
                    assertTrue(request.accept.contains("text/event-stream"));
                }
            }
        }
    }

    @Test
    void legacySseUsesAdvertisedEndpointAndNegotiatesOldVersion() throws Exception {
        try (var server = new Server(true); var client = server.builder().connect()) {
            assertEquals("2024-11-05", client.protocolVersion());
            assertEquals("echo", client.listTools().getFirst().name());
            assertEquals("remote result", client.callTool("echo", JSON.createObjectNode()).text());
            client.ping();
            assertTrue(server.requests.stream().filter(r -> r.method.equals("POST"))
                    .allMatch(r -> r.path.equals("/messages?session=legacy")));
        }
    }

    @Test
    void postSseProgressAndReversePingUseIndependentRequests() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect()) {
            server.mode = "reverse";
            var progress = new CopyOnWriteArrayList<String>();
            var result = new ToolExecutor(new ToolRegistry(client.tools("remote"))).execute(
                    new ContentBlock.ToolCall("call", "remote_echo", "{}"), () -> false,
                    update -> progress.add(update.message()));
            assertEquals("remote result", result.output());
            assertEquals(List.of("working"), progress);
            assertEquals(0, server.reverse.getCount());
        }
    }

    @Test
    void streamableWithoutSessionDoesNotSendDelete() throws Exception {
        try (var server = new Server(false)) {
            server.session = false;
            try (var client = server.builder().connect()) { client.ping(); }
            assertEquals(1, server.deleted.getCount());
            assertTrue(server.requests.stream().allMatch(r -> r.session == null));
        }
    }

    @Test
    void optionalGetStreamAnswersServerRequest() throws Exception {
        try (var server = new Server(false)) {
            server.mode = "get-stream";
            try (var client = server.builder().connect()) {
                assertTrue(server.reverse.await(3, TimeUnit.SECONDS));
                client.ping();
            }
        }
    }

    @Test
    void followsToolPaginationOverBothTransports() throws Exception {
        for (boolean legacy : List.of(false, true)) {
            try (var server = new Server(legacy); var client = server.builder().connect()) {
                server.mode = "pagination";
                assertEquals(List.of("echo"), client.listTools().stream().map(McpTool::name).toList());
                assertEquals(2, server.requests.stream().filter(r -> r.rpc.equals("tools/list")).count());
            }
        }
    }

    @Test
    void httpFailureDoesNotExposeResponseBody() throws Exception {
        try (var server = new Server(false)) {
            server.mode = "http-error";
            var error = assertThrows(McpException.class, () -> server.builder().connect());
            assertTrue(error.getMessage().contains("503"));
            assertFalse(error.toString().contains("private-secret"));
            assertNull(error.getCause());
        }
    }

    @Test
    void numericSseIdDoesNotCompleteStringIdRequest() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect()) {
            server.mode = "numeric-sse-id";
            assertEquals("remote result", client.callTool("echo", JSON.createObjectNode()).text());
        }
    }

    @Test
    void numericJsonIdFailsImmediatelyRatherThanTimingOut() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect()) {
            server.mode = "numeric-json-id";
            var error = assertThrows(McpException.class, client::ping);
            assertFalse(error.getMessage().contains("timed out"));
            assertTrue(error.getMessage().contains("match"));
        }
    }

    @Test
    void optionalGetEofDoesNotInvalidatePostRequests() throws Exception {
        try (var server = new Server(false)) {
            server.mode = "get-eof";
            try (var client = server.builder().connect()) {
                assertTrue(server.getEnded.await(3, TimeUnit.SECONDS));
                client.ping();
                assertEquals("remote result", client.callTool("echo", JSON.createObjectNode()).text());
            }
        }
    }

    @Test
    void rejectsNonemptyNotificationResponse() throws Exception {
        try (var server = new Server(false)) {
            server.mode = "notification-body";
            assertThrows(McpException.class, () -> server.builder().connect());
        }
    }

    @Test
    void hidesHttpErrorBodyAndDoesNotReplayExpiredSession() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect()) {
            server.mode = "expired";
            var error = assertThrows(McpException.class, client::ping);
            assertFalse(error.toString().contains("private-secret"));
            assertNull(error.getCause());
            assertThrows(McpException.class, client::ping);
            assertEquals(1, server.requests.stream().filter(r -> r.rpc.equals("ping")).count());
            assertEquals(1, server.requests.stream().filter(r -> r.rpc.equals("initialize")).count());
        }
    }

    @Test
    void rejectsWrongResponseContentType() throws Exception {
        try (var server = new Server(false)) {
            server.mode = "wrong-type";
            assertThrows(McpException.class, () -> server.builder().connect());
        }
    }

    @Test
    void rejectsUnsupportedProtocolVersion() throws Exception {
        try (var server = new Server(false)) {
            server.version = "unknown-version";
            assertThrows(McpException.class, () -> server.builder().connect());
        }
    }

    @Test
    void rejectsCrossOriginLegacyEndpointBeforePostingCredentials() throws Exception {
        try (var server = new Server(true)) {
            server.endpointEvent = "http://127.0.0.1:1/messages";
            assertThrows(McpException.class, () -> server.builder().connect());
            assertTrue(server.requests.stream().noneMatch(r -> r.method.equals("POST")));
        }
    }

    @Test
    void cancellationSendsNotificationAndKeepsConnectionUsable() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var runner = new ToolExecutor(new ToolRegistry(client.tools("remote")));
            server.mode = "wait";
            var cancel = new AtomicBoolean();
            var task = workers.submit(() -> assertThrows(CancellationException.class, () -> runner.execute(
                    new ContentBlock.ToolCall("call", "remote_echo", "{}"), cancel::get, ignored -> {})));
            assertTrue(server.waiting.await(3, TimeUnit.SECONDS));
            cancel.set(true);
            task.get(3, TimeUnit.SECONDS);
            assertTrue(server.cancelled.await(3, TimeUnit.SECONDS));
            server.mode = "normal";
            client.ping();
        }
    }

    @Test
    void timeoutSendsCancellationAndNextRequestCanComplete() throws Exception {
        try (var server = new Server(false); var client = server.builder().timeout(Duration.ofMillis(700)).connect();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.mode = "wait";
            var slow = workers.submit(() -> assertThrows(McpException.class,
                    () -> client.callTool("echo", JSON.createObjectNode())));
            assertTrue(server.waiting.await(3, TimeUnit.SECONDS));
            client.ping(); // A separate exchange succeeds while the tool stream remains pending.
            assertTrue(slow.get(3, TimeUnit.SECONDS).getMessage().contains("timed out"));
            assertTrue(server.cancelled.await(3, TimeUnit.SECONDS));
            server.mode = "normal";
            client.ping();
        }
    }

    @Test
    void closeFailsPendingRequest() throws Exception {
        try (var server = new Server(false); var client = server.builder().connect();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.mode = "wait";
            var task = workers.submit(() -> assertThrows(McpException.class,
                    () -> client.callTool("echo", JSON.createObjectNode())));
            assertTrue(server.waiting.await(3, TimeUnit.SECONDS));
            client.close();
            task.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void legacyStreamEofFailsPendingRequest() throws Exception {
        try (var server = new Server(true); var client = server.builder().connect()) {
            server.mode = "eof";
            assertThrows(McpException.class, client::ping);
        }
    }

    private record Request(String method, String path, String rpc, String session, String version,
                           String accept, String authorization) { }

    private static final class Server implements AutoCloseable {
        private final HttpServer http;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final boolean legacy;
        private final CountDownLatch stop = new CountDownLatch(1);
        final CountDownLatch get = new CountDownLatch(1);
        final CountDownLatch getEnded = new CountDownLatch(1);
        final CountDownLatch deleted = new CountDownLatch(1);
        final CountDownLatch reverse = new CountDownLatch(1);
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final List<Request> requests = new CopyOnWriteArrayList<>();
        volatile String mode = "normal";
        volatile String version;
        volatile String endpointEvent = "/messages?session=legacy";
        volatile boolean session = true;
        private volatile OutputStream events;

        Server(boolean legacy) throws IOException {
            this.legacy = legacy;
            version = legacy ? "2024-11-05" : McpClient.PROTOCOL_VERSION;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/", this::handle);
            http.start();
        }

        McpClient.HttpBuilder builder() {
            var uri = URI.create("http://127.0.0.1:" + http.getAddress().getPort() + (legacy ? "/sse" : "/mcp"));
            return (legacy ? McpClient.sse(uri) : McpClient.streamableHttp(uri)).timeout(Duration.ofSeconds(4));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                var headers = exchange.getRequestHeaders();
                String method = exchange.getRequestMethod();
                JsonNode input = method.equals("POST") ? JSON.readTree(exchange.getRequestBody()) : JSON.createObjectNode();
                requests.add(new Request(method, exchange.getRequestURI().toString(), input.path("method").asText(),
                        headers.getFirst("Mcp-Session-Id"), headers.getFirst("MCP-Protocol-Version"),
                        headers.getFirst("Accept"), headers.getFirst("Authorization")));
                if (method.equals("GET")) {
                    get.countDown();
                    if (!legacy && !mode.equals("get-stream") && !mode.equals("get-eof")) { exchange.sendResponseHeaders(405, -1); return; }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    events = exchange.getResponseBody();
                    if (mode.equals("get-eof")) {
                        events.write(": end\n\n".getBytes(StandardCharsets.UTF_8));
                        events.close();
                        getEnded.countDown();
                        return;
                    }
                    if (legacy) frame(events, "endpoint", endpointEvent);
                    else frame(events, "message", "{\"jsonrpc\":\"2.0\",\"id\":\"server-ping\",\"method\":\"ping\"}");
                    stop.await();
                    return;
                }
                if (method.equals("DELETE")) { deleted.countDown(); exchange.sendResponseHeaders(405, -1); return; }
                String rpc = input.path("method").asText();
                if (rpc.equals("notifications/cancelled")) cancelled.countDown();
                if (input.path("id").asText().equals("server-ping")) reverse.countDown();
                if (!input.has("id") || !input.has("method")) {
                    if (mode.equals("notification-body")) respond(exchange, 202, "text/plain", "invalid body");
                    else exchange.sendResponseHeaders(202, -1);
                    return;
                }
                if (mode.equals("http-error")) { respond(exchange, 503, "text/plain", "private-secret"); return; }
                if (mode.equals("expired")) { respond(exchange, 404, "text/plain", "private-secret"); return; }
                if (mode.equals("eof")) { events.close(); exchange.sendResponseHeaders(202, -1); return; }
                var result = JSON.createObjectNode();
                if (rpc.equals("initialize")) {
                    result.put("protocolVersion", version);
                    result.putObject("serverInfo").put("name", "http-fixture").put("version", "1");
                    result.putObject("capabilities").putObject("tools");
                    if (!legacy && session) exchange.getResponseHeaders().set("Mcp-Session-Id", "session-1");
                } else if (rpc.equals("tools/list")) {
                    var tools = result.putArray("tools");
                    if (mode.equals("pagination") && !input.path("params").has("cursor")) result.put("nextCursor", "page-2");
                    else {
                        var tool = tools.addObject().put("name", "echo");
                        tool.putObject("inputSchema").put("type", "object");
                    }
                } else if (rpc.equals("tools/call")) {
                    result.putArray("content").addObject().put("type", "text").put("text", "remote result");
                }
                var response = JSON.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", input.get("id"));
                response.set("result", result);
                if (mode.equals("numeric-json-id")) response.put("id", Long.parseLong(input.path("id").asText()));
                if (mode.equals("numeric-sse-id")) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    var wrong = response.deepCopy().put("id", Long.parseLong(input.path("id").asText()));
                    wrong.putObject("result").putArray("content").addObject().put("type", "text").put("text", "wrong result");
                    frame(exchange.getResponseBody(), "message", wrong.toString());
                    frame(exchange.getResponseBody(), "message", response.toString());
                    return;
                }
                if (legacy) {
                    synchronized (this) { frame(events, "message", response.toString()); }
                    exchange.sendResponseHeaders(202, -1);
                } else if (rpc.equals("tools/call") && (mode.equals("reverse") || mode.equals("wait"))) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.sendResponseHeaders(200, 0);
                    var out = exchange.getResponseBody();
                    if (mode.equals("wait")) {
                        out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                        waiting.countDown(); stop.await(); return;
                    }
                    var progress = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/progress");
                    progress.putObject("params").put("progressToken", input.path("params").path("_meta").path("progressToken").asText())
                            .put("progress", 1).put("message", "working");
                    frame(out, "message", progress.toString());
                    frame(out, "message", "{\"jsonrpc\":\"2.0\",\"id\":\"server-ping\",\"method\":\"ping\"}");
                    if (!reverse.await(3, TimeUnit.SECONDS)) throw new IOException("client did not answer reverse ping");
                    frame(out, "message", response.toString());
                } else respond(exchange, 200, mode.equals("wrong-type") ? "text/plain" : "application/json", response.toString());
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }

        private static void frame(OutputStream out, String event, String data) throws IOException {
            out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static void respond(HttpExchange exchange, int status, String type, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override public void close() {
            stop.countDown();
            http.stop(0);
            executor.shutdownNow();
        }
    }
}
