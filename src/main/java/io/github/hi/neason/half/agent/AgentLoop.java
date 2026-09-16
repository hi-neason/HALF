package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.agent.state.SessionState;
import io.github.hi.neason.half.agent.state.TurnOptions;
import io.github.hi.neason.half.agent.state.TurnState;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelOptions;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolProgress;
import io.github.hi.neason.half.tool.ToolRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.github.hi.neason.half.agent.AgentResult.StopReason.*;

/** 同步与流式入口共用的循环；仅保存配置，运行状态留在单次调用中。 */
final class AgentLoop {
    private final ChatModel model;
    private final ToolRegistry tools;
    private final ToolExecutor executor;
    private final Integer maxOutputTokens;
    private final ModelOptions modelOptions;

    AgentLoop(ChatModel model, ToolRegistry tools, Integer maxOutputTokens,
              ModelOptions modelOptions, int maxToolOutputCharacters) {
        this.model = model;
        this.tools = tools;
        this.maxOutputTokens = maxOutputTokens;
        this.modelOptions = modelOptions;
        executor = new ToolExecutor(tools, maxToolOutputCharacters);
    }

    AgentResult run(List<ChatMessage> history, TurnOptions options, BooleanSupplier cancelled,
                    Consumer<ToolProgress> onProgress) throws InterruptedException {
        return run(history, options, cancelled, onProgress, null);
    }

    AgentResult stream(List<ChatMessage> history, TurnOptions options, BooleanSupplier cancelled,
                       Consumer<AgentEvent> events) throws InterruptedException {
        return run(history, options, cancelled, ignored -> { }, Objects.requireNonNull(events, "events"));
    }

    private AgentResult run(List<ChatMessage> history, TurnOptions options, BooleanSupplier cancelled,
                            Consumer<ToolProgress> onProgress, Consumer<AgentEvent> events) throws InterruptedException {
        var session = new SessionState(history);
        var turn = new TurnState(options);
        while (true) {
            try {
                // 组装message，调用大模型
                var response = callModel(session, turn, cancelled, events);
                checkCancelled(cancelled);
                turn.recordResponse(response);
            } catch (IOException error) {
                checkCancelled(cancelled);
                // 只暴露安全的错误元数据；保留此前完成的响应和工具结果。
                var status = error instanceof ModelHttpException http
                        ? OptionalInt.of(http.statusCode()) : OptionalInt.empty();
                var failure = new AgentResult.ModelFailure(error.getClass().getSimpleName(), status);
                return turn.result(session, MODEL_ERROR, Optional.of(failure));
            }

            // 即使本轮不能继续，也保留完整响应（含供应商回放数据），便于上层检查。
            session.appendMessage(ChatMessage.assistantResponse(turn.response()));

            // loop的跳出检查
            var stopReason = evaluateStopReason(session, turn);
            if (stopReason.isPresent()) {
                return turn.result(session, stopReason.get(), Optional.empty());
            } else {
                executeTools(session, turn, cancelled, onProgress, events);
            }
        }
    }

    private ChatResponse callModel(SessionState session, TurnState turn, BooleanSupplier cancelled,
                                   Consumer<AgentEvent> events) throws IOException, InterruptedException {
        checkCancelled(cancelled);
        var request = new ChatRequest(session.messages(), maxOutputTokens, tools.definitions(), modelOptions);
        // 预算按请求尝试计数，失败的模型调用也占用一轮。
        int step = turn.beginModelCall();
        if (events != null) events.accept(new AgentEvent.TurnStarted(step));
        // 订阅者可能在收到事件时取消，调用模型前必须再次检查。
        checkCancelled(cancelled);
        return events == null ? model.chat(request)
                : ModelTurnStream.call(model, request, event -> events.accept(new AgentEvent.Model(step, event)));
    }

    /**
     * 每次模型响应后的统一停止判断；返回空值表示允许执行本轮工具，然后继续下一轮。
     * 判断顺序：响应完整性 → 无工具则完成 → 工具调用合法性 → 轮数上限。
     * 模型请求次数限制是上限而非目标：正常完成立即停止，即使仍有预算；末轮完成也优先于预算耗尽。
     * 模型异常、取消和线程中断由调用边界处理，不依赖本方法。
     */
    private Optional<AgentResult.StopReason> evaluateStopReason(SessionState session, TurnState turn) {
        var calls = turn.response().toolCalls();
        if (!isComplete(turn.response().finishReason(), !calls.isEmpty())) return Optional.of(INCOMPLETE_RESPONSE);
        if (calls.isEmpty()) return Optional.of(COMPLETED);

        // 同时检查历史、本轮内部与此前轮次的重复 ID，避免部分执行后才发现无效调用。
        for (var call : calls) {
            if (!session.registerCallId(call.id())) return Optional.of(INVALID_TOOL_CALLS);
        }
        // 没有下一轮模型预算时不执行工具，否则工具产生副作用后模型却无法消费结果。
        if (turn.isModelCallLimitReached()) return Optional.of(MAX_TURNS);
        return Optional.empty();
    }

    private void executeTools(SessionState session, TurnState turn, BooleanSupplier cancelled,
                              Consumer<ToolProgress> onProgress, Consumer<AgentEvent> events) throws InterruptedException {
        int step = turn.modelCalls();
        for (var call : turn.response().toolCalls()) {
            checkCancelled(cancelled);
            if (events != null) events.accept(new AgentEvent.ToolStarted(step, call));
            // 与模型调用相同，事件回调后的取消必须先于工具副作用生效。
            checkCancelled(cancelled);
            var result = executor.execute(call, cancelled, progress -> {
                onProgress.accept(progress);
                if (events != null) events.accept(new AgentEvent.ToolProgressed(step, progress));
            });
            // 串行执行并立即回填历史；完成事件发出时，对应结果已被记录。
            turn.recordToolResult(result);
            session.appendMessage(result.toMessage());
            if (events != null) events.accept(new AgentEvent.ToolCompleted(step, result));
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

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("Agent run was interrupted");
        if (cancelled.getAsBoolean()) throw new CancellationException("Agent run was cancelled");
    }
}
