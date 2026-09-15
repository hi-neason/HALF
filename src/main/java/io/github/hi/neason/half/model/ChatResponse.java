package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

/** 保留供应商的结束原因；usage 为空表示服务端未提供用量。 */
public record ChatResponse(List<ContentBlock> content, String finishReason, Optional<TokenUsage> usage, ReplayState replayState) {
    public ChatResponse {
        content = List.copyOf(content);
        Objects.requireNonNull(replayState, "replayState");
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
    }

    /** Responses 原始输出项用于回传 id/status/annotations 等协议字段；普通构造器不带它们。 */
    public ChatResponse(List<ContentBlock> content, String finishReason, Optional<TokenUsage> usage) {
        this(content, finishReason, usage, ReplayState.none());
    }

    /** 兼容原 Responses 快照构造器；空列表表示无回放状态。 */
    public ChatResponse(List<ContentBlock> content, String finishReason, Optional<TokenUsage> usage, List<String> outputItemsJson) {
        this(content, finishReason, usage, ReplayState.responses(outputItemsJson));
    }

    public List<String> outputItemsJson() { return replayState.snapshots(); }

    /** 显式放弃协议快照，不转换或删除供应商特定内容块。 */
    public ChatResponse withoutReplayState() {
        return new ChatResponse(content, finishReason, usage, ReplayState.none());
    }

    public ChatResponse(String text, String finishReason, Optional<TokenUsage> usage) {
        this(List.of(new ContentBlock.Text(text)), finishReason, usage);
    }

    public String text() {
        return content.stream().filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast).map(ContentBlock.Text::text).collect(Collectors.joining());
    }

    public String refusal() {
        return content.stream().filter(ContentBlock.Refusal.class::isInstance)
                .map(ContentBlock.Refusal.class::cast).map(ContentBlock.Refusal::text).collect(Collectors.joining());
    }

    public List<ContentBlock.Reasoning> reasoning() {
        return content.stream().filter(ContentBlock.Reasoning.class::isInstance)
                .map(ContentBlock.Reasoning.class::cast).toList();
    }

    public List<ContentBlock.ToolCall> toolCalls() {
        return content.stream().filter(ContentBlock.ToolCall.class::isInstance)
                .map(ContentBlock.ToolCall.class::cast).toList();
    }
}
