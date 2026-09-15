package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ModelOptions;
import io.github.hi.neason.half.tool.Tool;
import io.github.hi.neason.half.tool.ToolProgress;
import io.github.hi.neason.half.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 构建与运行 Agent 的门面；每次 run 独立维护历史，模型和工具的生命周期归宿主所有。 */
public final class Agent {
    private final AgentLoop loop;
    private final String systemPrompt;

    private Agent(Builder builder) {
        var tools = new ToolRegistry(builder.tools);
        loop = new AgentLoop(builder.model, tools, builder.maxTurns, builder.maxOutputTokens,
                builder.modelOptions, builder.maxToolOutputCharacters);
        systemPrompt = builder.systemPrompt;
    }

    public static Builder builder() { return new Builder(); }

    public AgentResult run(String input) throws InterruptedException {
        return run(List.of(ChatMessage.user(input)));
    }

    public AgentResult run(List<ChatMessage> history) throws InterruptedException {
        return run(history, () -> false, ignored -> { });
    }

    public AgentResult run(String input, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress)
            throws InterruptedException {
        return run(List.of(ChatMessage.user(input)), cancelled, onProgress);
    }

    /** history 必须已完成所有工具调用/结果配对；本方法不会执行历史中的工具。 */
    public AgentResult run(List<ChatMessage> history, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress)
            throws InterruptedException {
        var messages = new ArrayList<>(List.copyOf(history));
        if (messages.isEmpty()) throw new IllegalArgumentException("history must not be empty");
        if (systemPrompt != null) {
            var system = ChatMessage.system(systemPrompt);
            if (!messages.getFirst().equals(system)) messages.addFirst(system);
        }
        return loop.run(messages, Objects.requireNonNull(cancelled, "cancelled"),
                Objects.requireNonNull(onProgress, "onProgress"));
    }

    public static final class Builder {
        private ChatModel model;
        private final List<Tool> tools = new ArrayList<>();
        private String systemPrompt;
        private int maxTurns = 8;
        private Integer maxOutputTokens;
        private ModelOptions modelOptions = ModelOptions.defaults();
        private int maxToolOutputCharacters = 16_000;

        private Builder() { }

        public Builder model(ChatModel model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        public Builder tool(Tool tool) {
            tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        /** 追加工具；重复名称在 build 时拒绝。 */
        public Builder tools(List<? extends Tool> tools) {
            this.tools.addAll(List.copyOf(tools));
            return this;
        }

        public Builder systemPrompt(String prompt) {
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("systemPrompt must not be blank");
            systemPrompt = prompt;
            return this;
        }

        /** 模型调用次数上限，包含第一次请求以及失败的请求尝试。 */
        public Builder maxTurns(int maxTurns) {
            if (maxTurns <= 0) throw new IllegalArgumentException("maxTurns must be positive");
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder maxOutputTokens(int maxOutputTokens) {
            if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder modelOptions(ModelOptions options) {
            modelOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder maxToolOutputCharacters(int maximum) {
            if (maximum < 64) throw new IllegalArgumentException("Tool output limit must be at least 64 characters");
            maxToolOutputCharacters = maximum;
            return this;
        }

        public Agent build() {
            if (model == null) throw new IllegalStateException("A model is required");
            return new Agent(this);
        }
    }
}
