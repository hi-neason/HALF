package io.github.hi.neason.half.model;

/** 供应商报告的用量，不在客户端估算 token 数。 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
