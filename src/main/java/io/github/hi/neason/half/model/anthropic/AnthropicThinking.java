package io.github.hi.neason.half.model.anthropic;

/** Provider-specific thinking configuration; model support is checked by the service. */
public sealed interface AnthropicThinking {
    record Enabled(int budgetTokens) implements AnthropicThinking {
        public Enabled { if (budgetTokens < 1024) throw new IllegalArgumentException("Thinking budget must be at least 1024 tokens"); }
    }
    record Adaptive() implements AnthropicThinking {}
}
