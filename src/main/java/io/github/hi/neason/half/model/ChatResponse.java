package io.github.hi.neason.half.model;

import java.util.Objects;
import java.util.Optional;

/** 保留供应商的结束原因；usage 为空表示服务端未提供用量。 */
public record ChatResponse(String text, String finishReason, Optional<TokenUsage> usage) {
    public ChatResponse {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
    }
}
