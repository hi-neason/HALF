package io.github.hi.neason.half.model;

import java.util.List;

/** maxOutputTokens 为 null 时不发送限制，采用服务端默认值。 */
public record ChatRequest(List<ChatMessage> messages, Integer maxOutputTokens, List<ToolDefinition> tools) {
    public ChatRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        if (tools.stream().map(ToolDefinition::name).distinct().count() != tools.size()) {
            throw new IllegalArgumentException("Duplicate tool names");
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }

    public ChatRequest(List<ChatMessage> messages) {
        this(messages, null);
    }

    public ChatRequest(List<ChatMessage> messages, Integer maxOutputTokens) {
        this(messages, maxOutputTokens, List.of());
    }
}
