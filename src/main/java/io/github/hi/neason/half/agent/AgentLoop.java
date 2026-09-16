package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelOptions;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolProgress;
import io.github.hi.neason.half.tool.ToolRegistry;
import io.github.hi.neason.half.tool.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.github.hi.neason.half.agent.AgentResult.StopReason.*;

/** 同步与流式入口共用的循环；仅保存配置，运行状态留在单次调用中。 */
final class AgentLoop {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final ToolExecutor executor;
    private final int maxTurns;
    private final Integer maxOutputTokens;
    private final ModelOptions modelOptions;

    AgentLoop(ChatModel model, ToolRegistry tools, int maxTurns, Integer maxOutputTokens,
              ModelOptions modelOptions, int maxToolOutputCharacters) {
        this.model = model;
        this.tools = tools;
        this.maxTurns = maxTurns;
        this.maxOutputTokens = maxOutputTokens;
        this.modelOptions = modelOptions;
        executor = new ToolExecutor(tools, maxToolOutputCharacters);
    }

    AgentResult run(List<ChatMessage> history, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress)
            throws InterruptedException {
        return run(history, cancelled, onProgress, null);
    }

    AgentResult stream(List<ChatMessage> history, BooleanSupplier cancelled, Consumer<AgentEvent> events)
            throws InterruptedException {
        return run(history, cancelled, ignored -> { }, Objects.requireNonNull(events, "events"));
    }

    private AgentResult run(List<ChatMessage> history, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress,
                            Consumer<AgentEvent> events) throws InterruptedException {
        var state = new RunState(history);
        while (state.modelCalls < maxTurns) {
            try {
                // 组装message，调用大模型
                var response = callModel(state, cancelled, events);
                checkCancelled(cancelled);
                state.response = Objects.requireNonNull(response, "model response");
            } catch (IOException error) {
                checkCancelled(cancelled);
                // 只暴露安全的错误元数据；保留此前完成的响应和工具结果。
                var status = error instanceof ModelHttpException http
                        ? OptionalInt.of(http.statusCode()) : OptionalInt.empty();
                var failure = new AgentResult.ModelFailure(error.getClass().getSimpleName(), status);
                return state.result(MODEL_ERROR, Optional.of(failure));
            }

            // 即使本轮不能继续，也保留完整响应（含供应商回放数据），便于上层检查。
            state.messages.add(ChatMessage.assistantResponse(state.response));
            var stopReason = stopReason(state);
            if (stopReason.isPresent()) return state.result(stopReason.get(), Optional.empty());

            executeTools(state, cancelled, onProgress, events);
        }
        throw new IllegalStateException("Agent loop exhausted without a stop reason");
    }

    private ChatResponse callModel(RunState state, BooleanSupplier cancelled, Consumer<AgentEvent> events)
            throws IOException, InterruptedException {
        checkCancelled(cancelled);
        var request = new ChatRequest(state.messages, maxOutputTokens, tools.definitions(), modelOptions);
        // 预算按请求尝试计数，失败的模型调用也占用一轮。
        int turn = ++state.modelCalls;
        if (events != null) events.accept(new AgentEvent.TurnStarted(turn));
        // 订阅者可能在收到事件时取消，调用模型前必须再次检查。
        checkCancelled(cancelled);
        return events == null ? model.chat(request)
                : ModelTurnStream.call(model, request, event -> events.accept(new AgentEvent.Model(turn, event)));
    }

    /** 先检查响应完整性和调用合法性，再判断预算；只有整批通过才允许执行工具。 */
    private Optional<AgentResult.StopReason> stopReason(RunState state) {
        var calls = state.response.toolCalls();
        if (!isComplete(state.response.finishReason(), !calls.isEmpty())) return Optional.of(INCOMPLETE_RESPONSE);
        if (calls.isEmpty()) return Optional.of(COMPLETED);

        // 同时检查历史、本轮内部与此前轮次的重复 ID，避免部分执行后才发现无效调用。
        for (var call : calls) {
            if (!state.seenCallIds.add(call.id())) return Optional.of(INVALID_TOOL_CALLS);
        }
        // 没有下一轮模型预算时不执行工具，否则工具产生副作用后模型却无法消费结果。
        if (state.modelCalls == maxTurns) return Optional.of(MAX_TURNS);
        return Optional.empty();
    }

    private void executeTools(RunState state, BooleanSupplier cancelled, Consumer<ToolProgress> onProgress,
                              Consumer<AgentEvent> events) throws InterruptedException {
        int turn = state.modelCalls;
        for (var call : state.response.toolCalls()) {
            checkCancelled(cancelled);
            if (events != null) events.accept(new AgentEvent.ToolStarted(turn, call));
            // 与模型调用相同，事件回调后的取消必须先于工具副作用生效。
            checkCancelled(cancelled);
            var result = executor.execute(call, cancelled, progress -> {
                onProgress.accept(progress);
                if (events != null) events.accept(new AgentEvent.ToolProgressed(turn, progress));
            });
            // 串行执行并立即回填历史；完成事件发出时，对应结果已被记录。
            state.toolResults.add(result);
            state.messages.add(result.toMessage());
            if (events != null) events.accept(new AgentEvent.ToolCompleted(turn, result));
        }
    }

    /** 单次运行独享的上下文，避免同步与流式入口共享可变状态。 */
    private static final class RunState {
        private final List<ChatMessage> messages;
        private final Set<String> seenCallIds;
        private final List<ToolResult> toolResults = new ArrayList<>();
        private ChatResponse response;
        private int modelCalls;

        private RunState(List<ChatMessage> history) {
            messages = new ArrayList<>(history);
            seenCallIds = validateHistory(messages);
        }

        private AgentResult result(AgentResult.StopReason reason, Optional<AgentResult.ModelFailure> failure) {
            return new AgentResult(reason, modelCalls, messages, toolResults, Optional.ofNullable(response), failure);
        }
    }

    private static boolean isComplete(String finishReason, boolean hasTools) {
        return switch (finishReason) {
            case "completed" -> true;
            case "tool_calls", "tool_use" -> hasTools;
            case "stop", "end_turn" -> !hasTools;
            default -> false;
        };
    }

    private static Set<String> validateHistory(List<ChatMessage> messages) {
        var seen = new HashSet<String>();
        var pending = new HashSet<String>();
        for (var message : messages) {
            if (message.role() == ChatMessage.Role.TOOL) {
                if (!pending.remove(message.toolCallId())) {
                    throw new IllegalArgumentException("History contains an unmatched tool result");
                }
                continue;
            }
            if (!pending.isEmpty()) throw new IllegalArgumentException("History contains unresolved tool calls");
            for (var content : message.content()) {
                if (content instanceof ContentBlock.ToolCall call) {
                    if (!seen.add(call.id())) throw new IllegalArgumentException("History contains duplicate tool call ids");
                    pending.add(call.id());
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("History contains unresolved tool calls");
        return seen;
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Agent run was interrupted");
        if (cancelled.getAsBoolean()) throw new CancellationException("Agent run was cancelled");
    }
}
