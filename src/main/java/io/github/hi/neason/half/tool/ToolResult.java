package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hi.neason.half.model.ChatMessage;

import java.util.Objects;

/** 一次工具调用的结果；状态独立于输出文本，不通过匹配文本猜测成功或失败。 */
public record ToolResult(String callId, String toolName, Status status, String output, JsonNode details, boolean truncated) {
    public enum Status { SUCCESS, UNKNOWN_TOOL, INVALID_ARGUMENTS, EXECUTION_FAILED }

    public ToolResult {
        if (callId == null || callId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool call id and name must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(output, "output");
        details = ToolOutput.copyDetails(details);
    }

    public ToolResult(String callId, String toolName, Status status, String output) {
        this(callId, toolName, status, output, NullNode.instance, false);
    }

    @Override public JsonNode details() { return details.deepCopy(); }

    /** 统一为携带状态的 JSON 文本；供应商适配器仍负责 tool result 的协议映射。 */
    public ChatMessage toMessage() {
        var body = JsonNodeFactory.instance.objectNode()
                .put("status", status.name()).put("output", output);
        if (truncated) body.put("truncated", true);
        return ChatMessage.toolResult(callId, body.toString());
    }
}
