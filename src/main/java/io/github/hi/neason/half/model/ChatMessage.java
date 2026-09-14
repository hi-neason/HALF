package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

/** 消息包含文本或工具调用；工具结果通过 toolCallId 关联，历史由调用方维护。 */
public record ChatMessage(Role role, List<ContentBlock> content, String toolCallId) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        if (role != Role.ASSISTANT && content.stream().anyMatch(ContentBlock.ToolCall.class::isInstance)) {
            throw new IllegalArgumentException("Only assistant messages can contain tool calls");
        }
        if (role == Role.TOOL ? toolCallId == null || toolCallId.isBlank() : toolCallId != null) {
            throw new IllegalArgumentException("Only tool results require toolCallId");
        }
    }

    public ChatMessage(Role role, String text) {
        this(role, List.of(new ContentBlock.Text(text)), null);
    }

    public String text() {
        return content.stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text).collect(Collectors.joining());
    }

    public static ChatMessage assistantResponse(ChatResponse response) {
        return new ChatMessage(Role.ASSISTANT, response.content(), null);
    }

    public static ChatMessage toolResult(String callId, String text) {
        return new ChatMessage(Role.TOOL, List.of(new ContentBlock.Text(text)), callId);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
