package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Objects;

/** MCP 原始工具结果的不可变快照；isError 属于工具执行结果，不是 JSON-RPC 错误。 */
public record McpCallResult(ObjectNode value) {
    public McpCallResult {
        value = Objects.requireNonNull(value, "value").deepCopy();
        if (!value.path("content").isArray()) throw new IllegalArgumentException("Tool result requires content array");
        if (value.has("isError") && !value.get("isError").isBoolean()) {
            throw new IllegalArgumentException("isError must be boolean");
        }
        if (value.has("structuredContent") && !value.get("structuredContent").isObject()) {
            throw new IllegalArgumentException("structuredContent must be an object");
        }
        for (JsonNode block : value.path("content")) {
            if (!block.isObject() || !block.path("type").isTextual()) {
                throw new IllegalArgumentException("Invalid tool content block");
            }
            if (block.path("type").asText().equals("text") && !block.path("text").isTextual()) {
                throw new IllegalArgumentException("Invalid text content block");
            }
        }
    }

    @Override public ObjectNode value() { return value.deepCopy(); }

    public boolean isError() { return value.path("isError").asBoolean(false); }

    /** 当前工具通道为文本；非文本块保留在 value 中，模型收到明确占位，避免伪装为已读取的媒体。 */
    public String text() {
        var parts = new ArrayList<String>();
        for (JsonNode block : value.path("content")) {
            String type = block.path("type").asText();
            if (type.equals("text")) parts.add(block.path("text").asText());
            else if (type.equals("resource") && block.path("resource").path("text").isTextual()) {
                parts.add(block.path("resource").path("text").asText());
            } else if (type.equals("resource_link")) {
                parts.add(block.path("uri").asText("[MCP resource link]"));
            } else parts.add("[MCP non-text content: " + type + "; available in tool details]");
        }
        if (value.has("structuredContent")) parts.add(value.get("structuredContent").toString());
        return String.join("\n", parts);
    }
}
