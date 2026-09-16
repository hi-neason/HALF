package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.agent.state.TurnOptions;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ModelOptions;
import io.github.hi.neason.half.tool.Tool;
import io.github.hi.neason.half.tool.ToolProgress;
import io.github.hi.neason.half.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 构建与运行 Agent 的门面；每次 run / 流订阅是一个独立 turn，预算由 TurnOptions 指定；模型和工具的生命周期归宿主所有。 */
public final class Agent {
    private final AgentLoop loop;
    private final String systemPrompt;

    private Agent(Builder builder) {
        var tools = new ToolRegistry(builder.tools);
        loop = new AgentLoop(builder.model, tools, builder.maxOutputTokens,
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

    public AgentResult run(String input, TurnOptions options) throws InterruptedException {
        return run(List.of(ChatMessage.user(input)), options);
    }

    public AgentResult run(List<ChatMessage> history, TurnOptions options) throws InterruptedException {
        return run(history, options, () -> false, ignored -> { });
    }

    public AgentResult run(String input, TurnOptions options, BooleanSupplier cancelled,
                           Consumer<ToolProgress> onProgress) throws InterruptedException {
        return run(List.of(ChatMessage.user(input)), options, cancelled, onProgress);
    }

    /** history 必须已完成所有工具调用/结果配对；本方法不会执行历史中的工具。 */
    public AgentResult run(List<ChatMessage> history, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress)
            throws InterruptedException {
        return run(history, TurnOptions.defaults(), cancelled, onProgress);
    }

    /** 本次 turn 使用独立预算；历史中的模型调用与工具结果不计入本次执行统计。 */
    public AgentResult run(List<ChatMessage> history, TurnOptions options, BooleanSupplier cancelled,
                           Consumer<ToolProgress> onProgress) throws InterruptedException {
        return loop.run(prepareHistory(history), Objects.requireNonNull(options, "options"),
                Objects.requireNonNull(cancelled, "cancelled"),
                Objects.requireNonNull(onProgress, "onProgress"));
    }

    /** 冷流：每次订阅独立运行；正数 request(n) 后启动，cancel 取消模型订阅并中断执行线程。 */
    public Flow.Publisher<AgentEvent> stream(String input) {
        return stream(List.of(ChatMessage.user(input)));
    }

    /** 输入在调用时复制；每次订阅都可能再次执行工具副作用，不自动共享或重放。 */
    public Flow.Publisher<AgentEvent> stream(List<ChatMessage> history) {
        return stream(history, TurnOptions.defaults());
    }

    public Flow.Publisher<AgentEvent> stream(String input, TurnOptions options) {
        return stream(List.of(ChatMessage.user(input)), options);
    }

    /** 每次订阅新建 turn 状态并重置预算；不可变 options 可在订阅之间安全复用。 */
    public Flow.Publisher<AgentEvent> stream(List<ChatMessage> history, TurnOptions options) {
        var messages = prepareHistory(history);
        Objects.requireNonNull(options, "options");
        return new AgentStream((cancelled, events) -> loop.stream(messages, options, cancelled, events));
    }

    private List<ChatMessage> prepareHistory(List<ChatMessage> history) {
        var messages = new ArrayList<>(List.copyOf(history));
        if (messages.isEmpty()) throw new IllegalArgumentException("history must not be empty");
        if (systemPrompt != null) {
            var system = ChatMessage.system(systemPrompt);
            if (!messages.getFirst().equals(system)) messages.addFirst(system);
        }
        return List.copyOf(messages);
    }

    public static final class Builder {
        private ChatModel model;
        private final List<Tool> tools = new ArrayList<>();
        private String systemPrompt;
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
