package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

/** 保留供应商的结束原因；usage 为空表示服务端未提供用量。 */
public record ChatResponse(List<ContentBlock> content, String finishReason, Optional<TokenUsage> usage) {
    public ChatResponse {
        content = List.copyOf(content);
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
    }

    public ChatResponse(String text, String finishReason, Optional<TokenUsage> usage) {
        this(List.of(new ContentBlock.Text(text)), finishReason, usage);
    }

    public String text() {
        return content.stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text).collect(Collectors.joining());
    }

    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream().filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast).toList();
    }
}
