package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ToolDefinition;
import io.github.hi.neason.half.tool.ContextualTool;
import io.github.hi.neason.half.tool.Tool;
import io.github.hi.neason.half.tool.ToolContext;
import io.github.hi.neason.half.tool.ToolOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 宿主持有的 stdio MCP Client；负责协议与工具适配，不持有或推进 Agent 会话。 */
public final class McpClient implements AutoCloseable {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private final McpConnection connection;
    private final ObjectNode serverInfo;
    private final ObjectNode capabilities;

    private McpClient(McpConnection connection, ObjectNode serverInfo, ObjectNode capabilities) {
        this.connection = connection;
        this.serverInfo = serverInfo.deepCopy();
        this.capabilities = capabilities.deepCopy();
    }

    public static Builder stdio(List<String> command) { return new Builder(command); }

    public static Builder stdio(String... command) { return stdio(List.of(command)); }

    public ObjectNode serverInfo() { return serverInfo.deepCopy(); }

    public ObjectNode capabilities() { return capabilities.deepCopy(); }

    /** 拉取完整分页快照；刷新不会自动替换已经构建的 Agent 的工具注册表。 */
    public List<McpTool> listTools() throws IOException, InterruptedException {
        requireTools();
        var tools = new ArrayList<McpTool>();
        var names = new HashSet<String>();
        var cursors = new HashSet<String>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            var params = object();
            if (cursor != null) params.put("cursor", cursor);
            var result = connection.request("tools/list", params, null);
            if (!result.isObject() || !result.path("tools").isArray()) throw protocol("Invalid tools/list result");
            for (JsonNode tool : result.path("tools")) {
                if (!tool.isObject() || !tool.path("name").isTextual() || tool.path("name").asText().isBlank()
                        || !(tool.get("inputSchema") instanceof ObjectNode schema)
                        || !schema.path("type").asText().equals("object")
                        || tool.has("description") && !tool.get("description").isTextual()) {
                    throw protocol("Invalid MCP tool definition");
                }
                String name = tool.path("name").asText();
                if (!names.add(name)) throw protocol("Duplicate MCP tool name");
                if (tools.size() >= 10_000) throw protocol("MCP tool limit exceeded");
                tools.add(new McpTool(name, tool.path("description").asText(""), schema, (ObjectNode) tool));
            }
            if (!result.has("nextCursor")) return List.copyOf(tools);
            if (!result.get("nextCursor").isTextual()) throw protocol("Invalid MCP pagination cursor");
            cursor = result.get("nextCursor").asText();
            if (!cursors.add(cursor)) throw protocol("Repeated MCP pagination cursor");
        }
        throw protocol("MCP page limit exceeded");
    }

    /** 为一个 Server 设置名称空间；模型函数名与远端原始名称分开保存。 */
    public List<Tool> tools(String namespace) throws IOException, InterruptedException {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_-]{1,24}")) {
            throw new IllegalArgumentException("namespace must contain 1 to 24 letters, digits, underscores or hyphens");
        }
        var result = new ArrayList<Tool>();
        var names = new HashSet<String>();
        for (var tool : listTools()) {
            if (tool.requiresTask()) continue; // 首版不支持任务扩展，不能作为普通工具暴露。
            String name = modelName(namespace, tool.name());
            if (!names.add(name)) throw protocol("MCP model tool name collision");
            var definition = new ToolDefinition(name, tool.description(), tool.inputSchema().toString());
            result.add(new ContextualTool() {
                @Override public ToolDefinition definition() { return definition; }
                @Override public ToolOutput execute(ObjectNode arguments, ToolContext context)
                        throws IOException, InterruptedException {
                    var output = callTool(tool.name(), arguments, context);
                    return new ToolOutput(output.text(), output.value(), output.isError());
                }
            });
        }
        return List.copyOf(result);
    }

    public McpCallResult callTool(String name, ObjectNode arguments) throws IOException, InterruptedException {
        return callTool(name, arguments, null);
    }

    private McpCallResult callTool(String name, ObjectNode arguments, ToolContext context)
            throws IOException, InterruptedException {
        requireTools();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        var params = object().put("name", name);
        params.set("arguments", Objects.requireNonNull(arguments, "arguments").deepCopy());
        var result = connection.request("tools/call", params, context);
        if (!(result instanceof ObjectNode object)) throw protocol("Invalid tools/call result");
        try { return new McpCallResult(object); }
        catch (IllegalArgumentException error) { throw protocol("Invalid tools/call result"); }
    }

    public void ping() throws IOException, InterruptedException {
        var result = connection.request("ping", object(), null);
        if (!result.isObject()) throw protocol("Invalid ping result");
    }

    @Override public void close() { connection.close(); }

    private void requireTools() throws McpException {
        if (!capabilities.has("tools")) throw protocol("MCP server does not advertise tools");
    }

    private static String modelName(String namespace, String remote) {
        String name = namespace + "_" + remote;
        if (name.matches("[A-Za-z0-9_-]{1,64}")) return name;
        String normalized = name.replaceAll("[^A-Za-z0-9_-]", "_");
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
            return normalized.substring(0, Math.min(51, normalized.length())) + "_" + digest;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    private static McpException protocol(String message) { return new McpException(message); }

    public static final class Builder {
        private final List<String> command;
        private Path directory;
        private Map<String, String> environment = Map.of();
        private Duration timeout = Duration.ofSeconds(30);

        private Builder(List<String> command) {
            this.command = List.copyOf(command);
            if (command.isEmpty() || command.getFirst().isBlank()) {
                throw new IllegalArgumentException("Server command must not be empty");
            }
        }

        public Builder directory(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
            return this;
        }

        /** 在继承的进程环境上覆盖指定变量；不把环境值写入协议消息或异常。 */
        public Builder environment(Map<String, String> environment) {
            this.environment = Map.copyOf(environment);
            return this;
        }

        /** 单个请求的总时限；工具进度不会延长它。 */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("timeout must be positive and at most one day");
            }
            this.timeout = timeout;
            return this;
        }

        public McpClient connect() throws IOException, InterruptedException {
            if (Thread.interrupted()) throw new InterruptedException("MCP connection interrupted");
            var connection = new McpConnection(command, directory, environment, timeout);
            try {
                var params = object().put("protocolVersion", PROTOCOL_VERSION);
                params.set("capabilities", object());
                params.set("clientInfo", object().put("name", "half").put("version", "0.1.0"));
                var result = connection.request("initialize", params, null);
                if (!result.isObject() || !PROTOCOL_VERSION.equals(result.path("protocolVersion").asText())) {
                    throw protocol("Unsupported MCP protocol version");
                }
                if (!(result.get("serverInfo") instanceof ObjectNode info)
                        || !info.path("name").isTextual() || !info.path("version").isTextual()
                        || !(result.get("capabilities") instanceof ObjectNode capabilities)
                        || capabilities.has("tools") && !capabilities.get("tools").isObject()) {
                    throw protocol("Invalid MCP initialize result");
                }
                connection.notify("notifications/initialized", object());
                return new McpClient(connection, info, capabilities);
            } catch (IOException | InterruptedException | RuntimeException | Error error) {
                connection.close();
                throw error;
            }
        }
    }
}
