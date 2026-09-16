package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

/** Server 公布的工具描述；远端名称不受模型厂商的函数名格式约束。 */
public record McpTool(String name, String description, ObjectNode inputSchema, ObjectNode metadata) {
    public McpTool {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        Objects.requireNonNull(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
        metadata = Objects.requireNonNull(metadata, "metadata").deepCopy();
    }

    public McpTool(String name, String description, ObjectNode inputSchema) {
        this(name, description, inputSchema, JsonNodeFactory.instance.objectNode());
    }

    /** 原始工具元数据，包含 outputSchema、annotations 和 execution 等可选字段。 */
    @Override public ObjectNode metadata() { return metadata.deepCopy(); }

    public boolean requiresTask() {
        return metadata.path("execution").path("taskSupport").asText().equals("required");
    }

    @Override public ObjectNode inputSchema() { return inputSchema.deepCopy(); }
}
