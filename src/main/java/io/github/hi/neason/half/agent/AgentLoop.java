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
        var messages = new ArrayList<>(history);
        var seenCallIds = validateHistory(messages);
        var results = new ArrayList<ToolResult>();
        ChatResponse response = null;
        int modelCalls = 0;
        while (modelCalls < maxTurns) {
            checkCancelled(cancelled);
            var request = new ChatRequest(messages, maxOutputTokens, tools.definitions(), modelOptions);
            modelCalls++;
            int turn = modelCalls;
            if (events != null) events.accept(new AgentEvent.TurnStarted(turn));
            checkCancelled(cancelled);
            try {
                response = events == null ? model.chat(request)
                        : ModelTurnStream.call(model, request, event -> events.accept(new AgentEvent.Model(turn, event)));
            } catch (IOException error) {
                checkCancelled(cancelled);
                var status = error instanceof ModelHttpException http ? OptionalInt.of(http.statusCode()) : OptionalInt.empty();
                var failure = new AgentResult.ModelFailure(error.getClass().getSimpleName(), status);
                return new AgentResult(MODEL_ERROR, modelCalls, messages, results,
                        Optional.ofNullable(response), Optional.of(failure));
            }
            checkCancelled(cancelled);
            Objects.requireNonNull(response, "model response");
            messages.add(ChatMessage.assistantResponse(response));
            var calls = response.toolCalls();
            if (!isComplete(response.finishReason(), !calls.isEmpty())) {
                return result(INCOMPLETE_RESPONSE, modelCalls, messages, results, response);
            }
            if (calls.isEmpty()) return result(COMPLETED, modelCalls, messages, results, response);

            // 整批预检后才允许副作用，避免同一个调用在本次运行中重复执行。
            for (var call : calls) {
                if (!seenCallIds.add(call.id())) {
                    return result(INVALID_TOOL_CALLS, modelCalls, messages, results, response);
                }
            }
            if (modelCalls == maxTurns) return result(MAX_TURNS, modelCalls, messages, results, response);
            for (var call : calls) {
                checkCancelled(cancelled);
                if (events != null) events.accept(new AgentEvent.ToolStarted(turn, call));
                checkCancelled(cancelled);
                var toolResult = executor.execute(call, cancelled, progress -> {
                    onProgress.accept(progress);
                    if (events != null) events.accept(new AgentEvent.ToolProgressed(turn, progress));
                });
                results.add(toolResult);
                messages.add(toolResult.toMessage());
                if (events != null) events.accept(new AgentEvent.ToolCompleted(turn, toolResult));
            }
        }
        throw new IllegalStateException("Agent loop exhausted without a stop reason");
    }

    private static boolean isComplete(String finishReason, boolean hasTools) {
        return switch (finishReason) {
            case "completed" -> true;
            case "tool_calls", "tool_use" -> hasTools;
            case "stop", "end_turn" -> !hasTools;
            default -> false;
        };
    }

    private static AgentResult result(AgentResult.StopReason reason, int calls, List<ChatMessage> messages,
                                      List<ToolResult> results, ChatResponse response) {
        return new AgentResult(reason, calls, messages, results, Optional.of(response), Optional.empty());
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
