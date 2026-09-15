package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.List;
import java.util.stream.Collectors;

/** 消息包含文本或工具调用；工具结果通过 toolCallId 关联，历史由调用方维护。 */
public record ChatMessage(Role role, List<ContentBlock> content, String toolCallId, List<String> outputItemsJson) {
    public enum Role { SYSTEM, DEVELOPER, USER, ASSISTANT, TOOL }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = List.copyOf(content);
        outputItemsJson = List.copyOf(outputItemsJson);
        if (role != Role.ASSISTANT && !outputItemsJson.isEmpty()) throw new IllegalArgumentException("Only assistant messages can replay output items");
        for (ContentBlock block : content) {
            if ((block instanceof ContentBlock.ToolCall || block instanceof ContentBlock.Refusal || block instanceof ContentBlock.Reasoning)
                    && role != Role.ASSISTANT) throw new IllegalArgumentException("Only assistant messages can contain model output blocks");
            if ((block instanceof ContentBlock.Image || block instanceof ContentBlock.File) && role != Role.USER) {
                throw new IllegalArgumentException("Images and files require a user message");
            }
        }
        if (role == Role.TOOL ? toolCallId == null || toolCallId.isBlank() : toolCallId != null) {
            throw new IllegalArgumentException("Only tool results require toolCallId");
        }
    }

    public ChatMessage(Role role, List<ContentBlock> content, String toolCallId) {
        this(role, content, toolCallId, List.of());
    }

    public ChatMessage(Role role, String text) {
        this(role, List.of(new ContentBlock.Text(text)), null);
    }

    public String text() {
        return content.stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text).collect(Collectors.joining());
    }

    public static ChatMessage assistantResponse(ChatResponse response) {
        return new ChatMessage(Role.ASSISTANT, response.content(), null, response.outputItemsJson());
    }

    public static ChatMessage toolResult(String callId, String text) {
        return new ChatMessage(Role.TOOL, List.of(new ContentBlock.Text(text)), callId);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, text);
    }

    public static ChatMessage developer(String text) {
        return new ChatMessage(Role.DEVELOPER, text);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, text);
    }
}
