package io.github.hi.neason.half.model;

/** null 表示不发送该选项；模型是否支持特定参数组合由服务端判断。 */
public record ModelOptions(Double temperature, Double topP, ToolChoice toolChoice,
                           Boolean parallelToolCalls, ResponseFormat responseFormat,
                           ReasoningEffort reasoningEffort, ReasoningSummary reasoningSummary,
                           Verbosity verbosity, Boolean includeEncryptedReasoning) {
    public enum ReasoningEffort { NONE, MINIMAL, LOW, MEDIUM, HIGH, XHIGH, MAX }
    public enum ReasoningSummary { AUTO, CONCISE, DETAILED }
    public enum Verbosity { LOW, MEDIUM, HIGH }

    public ModelOptions {
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be finite and between 0 and 2");
        }
        if (topP != null && (!Double.isFinite(topP) || topP < 0 || topP > 1)) {
            throw new IllegalArgumentException("topP must be finite and between 0 and 1");
        }
    }

    public static ModelOptions defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Double temperature;
        private Double topP;
        private ToolChoice toolChoice;
        private Boolean parallelToolCalls;
        private ResponseFormat responseFormat;
        private ReasoningEffort reasoningEffort;
        private ReasoningSummary reasoningSummary;
        private Verbosity verbosity;
        private Boolean includeEncryptedReasoning;

        private Builder() {}
        public Builder temperature(Double value) { temperature = value; return this; }
        public Builder topP(Double value) { topP = value; return this; }
        public Builder toolChoice(ToolChoice value) { toolChoice = value; return this; }
        public Builder parallelToolCalls(Boolean value) { parallelToolCalls = value; return this; }
        public Builder responseFormat(ResponseFormat value) { responseFormat = value; return this; }
        public Builder reasoningEffort(ReasoningEffort value) { reasoningEffort = value; return this; }
        public Builder reasoningSummary(ReasoningSummary value) { reasoningSummary = value; return this; }
        public Builder verbosity(Verbosity value) { verbosity = value; return this; }
        public Builder includeEncryptedReasoning(Boolean value) { includeEncryptedReasoning = value; return this; }
        public ModelOptions build() {
            return new ModelOptions(temperature, topP, toolChoice, parallelToolCalls, responseFormat,
                    reasoningEffort, reasoningSummary, verbosity, includeEncryptedReasoning);
        }
    }
}
