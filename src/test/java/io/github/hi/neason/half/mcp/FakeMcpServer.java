package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A real child process with deterministic protocol scenarios, without network or external runtimes. */
public final class FakeMcpServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String scenario;
    private boolean initialized;
    private boolean cancellationReceived;
    private JsonNode delayedId;
    private JsonNode reverseId;
    private int reverseReplies;

    private FakeMcpServer(String scenario) { this.scenario = scenario; }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        var server = new FakeMcpServer(args[0]);
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) server.receive(JSON.readTree(line));
        }
    }

    private void receive(JsonNode request) throws Exception {
        var id = request.get("id");
        var params = request.path("params");
        switch (request.path("method").asText()) {
            case "initialize" -> {
                if (!McpClient.PROTOCOL_VERSION.equals(params.path("protocolVersion").asText())
                        || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()) {
                    throw new IllegalStateException("Invalid client initialization");
                }
                var result = object().put("protocolVersion", scenario.equals("bad-version") ? "1900-01-01" : McpClient.PROTOCOL_VERSION);
                result.set("serverInfo", object().put("name", "fixture").put("version", "1").put("pid", ProcessHandle.current().pid()));
                result.set("capabilities", object().set("tools", object()));
                reply(id, result);
            }
            case "notifications/initialized" -> initialized = true;
            case "notifications/cancelled" -> {
                cancellationReceived = true;
                if (delayedId != null) reply(delayedId, text("late response"));
            }
            case "tools/list" -> {
                requireInitialized();
                var result = object();
                var tools = result.putArray("tools");
                if (scenario.equals("repeat-cursor")) result.put("nextCursor", "same");
                else if (scenario.equals("empty-page") && !params.has("cursor")) result.put("nextCursor", "next");
                else {
                    tools.add(tool(scenario.equals("alias") ? "remote/tool.with.dots" : scenario.equals("wait-tool") ? "wait" : "echo"));
                    if (scenario.equals("duplicate")) tools.add(tool("echo"));
                    if (scenario.equals("tasks-required")) {
                        var taskTool = tool("background");
                        taskTool.set("execution", object().put("taskSupport", "required"));
                        taskTool.set("annotations", object().put("readOnlyHint", true));
                        tools.add(taskTool);
                    }
                }
                reply(id, result);
            }
            case "ping" -> { requireInitialized(); reply(id, object()); }
            case "tools/call" -> {
                requireInitialized();
                String name = params.path("name").asText();
                switch (name) {
                    case "exit" -> System.exit(0);
                    case "wait" -> {
                        delayedId = id;
                        if (params.path("_meta").has("progressToken")) {
                            var progress = object().put("progress", 1).put("message", "started");
                            progress.set("progressToken", params.path("_meta").get("progressToken"));
                            notification("notifications/progress", progress);
                        }
                    }
                    case "cancelled" -> reply(id, text(Boolean.toString(cancellationReceived)));
                    case "rpc-error" -> {
                        var response = envelope(id);
                        response.set("error", object().put("code", -32602).put("message", "private-server-secret")
                                .set("data", object().put("token", "private-server-secret")));
                        send(response);
                    }
                    case "reverse" -> {
                        reverseId = id;
                        for (String method : new String[]{"ping", "roots/list"}) {
                            var reverse = envelope(JSON.getNodeFactory().textNode(method));
                            reverse.put("method", method).set("params", object());
                            send(reverse);
                        }
                    }
                    default -> {
                        var result = text(scenario.equals("tool-error") ? "business failure" : name + ":" + params.path("arguments"));
                        if (scenario.equals("tool-error")) result.put("isError", true);
                        if (scenario.equals("media")) {
                            result.withArray("content").add(object().put("type", "image").put("mimeType", "image/png").put("data", "aW1hZ2U="));
                            result.set("structuredContent", object().put("answer", 42));
                        }
                        if (scenario.equals("environment")) result = text(System.getenv("HALF_TEST_VALUE") + ":" + Path.of("").toAbsolutePath());
                        if (scenario.equals("immediate-progress") && params.path("_meta").has("progressToken")) {
                            var progress = object().put("progress", 1).put("message", "finished remote work");
                            progress.set("progressToken", params.path("_meta").get("progressToken"));
                            var notification = object().put("jsonrpc", "2.0").put("method", "notifications/progress");
                            notification.set("params", progress);
                            // Both lines share a flush, exercising progress immediately followed by the result.
                            System.out.print(notification + "\n" + envelope(id).set("result", result) + "\n");
                            System.out.flush();
                        } else reply(id, result);
                    }
                }
            }
            case "" -> {
                if (id.asText().equals("ping") && !request.path("result").isObject()) throw new IllegalStateException("Expected ping response");
                if (id.asText().equals("roots/list") && request.path("error").path("code").asInt() != -32601) throw new IllegalStateException("Expected method not found");
                if (++reverseReplies == 2) reply(reverseId, text("reverse requests handled"));
            }
            default -> throw new IllegalStateException("Unexpected request");
        }
    }

    private void requireInitialized() {
        if (!initialized) throw new IllegalStateException("Request before initialized notification");
    }

    private static ObjectNode tool(String name) {
        var result = object().put("name", name).put("description", "Test tool");
        result.set("inputSchema", object().put("type", "object"));
        return result;
    }

    private static ObjectNode text(String text) {
        var result = object();
        result.putArray("content").add(object().put("type", "text").put("text", text));
        return result;
    }

    private static ObjectNode object() { return JSON.createObjectNode(); }
    private static ObjectNode envelope(JsonNode id) { return object().put("jsonrpc", "2.0").set("id", id); }
    private static void reply(JsonNode id, ObjectNode result) { send(envelope(id).set("result", result)); }
    private static void notification(String method, ObjectNode params) { send(object().put("jsonrpc", "2.0").put("method", method).set("params", params)); }
    private static void send(ObjectNode message) { System.out.println(message); System.out.flush(); }
}
