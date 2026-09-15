package io.github.hi.neason.half.model;

import java.util.List;
import java.util.Objects;

/** maxOutputTokens 为 null 时由适配器决定默认值；Anthropic Messages 使用 1024，OpenAI 不发送限制。 */
public record ChatRequest(List<ChatMessage> messages, Integer maxOutputTokens, List<ToolDefinition> tools, ModelOptions options) {
    public ChatRequest {
        Objects.requireNonNull(options, "options");
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

    public ChatRequest(List<ChatMessage> messages, Integer maxOutputTokens, List<ToolDefinition> tools) {
        this(messages, maxOutputTokens, tools, ModelOptions.defaults());
    }

    public ChatRequest(List<ChatMessage> messages) {
        this(messages, null);
    }

    public ChatRequest(List<ChatMessage> messages, Integer maxOutputTokens) {
        this(messages, maxOutputTokens, List.of());
    }
}
