package io.github.hi.neason.half.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.agent.state.TurnOptions;
import io.github.hi.neason.half.model.*;
import io.github.hi.neason.half.tool.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.hi.neason.half.agent.AgentResult.StopReason.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void completesWithoutToolsAndReturnsImmutableHistory() throws Exception {
        var response = text("answer");
        var agent = Agent.builder().model(request -> response).build();
        var result = agent.run("question");
        assertEquals(COMPLETED, result.stopReason());
        assertTrue(result.completed());
        assertEquals("answer", result.text());
        assertEquals(1, result.modelCalls());
        assertEquals(Optional.of(response), result.lastResponse());
        assertTrue(result.modelFailure().isEmpty());
        assertEquals(List.of(ChatMessage.user("question"), ChatMessage.assistantResponse(response)), result.messages());
        assertThrows(UnsupportedOperationException.class, () -> result.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.toolResults().clear());
    }

    @Test
    void executesCallsInOrderAndReplaysTheEntireAssistantResponse() throws Exception {
        var replay = ReplayState.responses(List.of("{\"type\":\"function_call\",\"id\":\"item\"}"));
        var response = new ChatResponse(List.of(new ContentBlock.Text("working"), call("a"), call("b")),
                "tool_calls", Optional.empty(), replay);
        var requests = new ArrayList<ChatRequest>();
        var executed = new ArrayList<String>();
        var agent = Agent.builder().tool(contextTool(context -> {
            executed.add(context.callId());
            return new ToolOutput("result-" + context.callId());
        })).model(request -> {
            requests.add(request);
            return switch (requests.size()) {
                case 1 -> response;
                case 2 -> text("done");
                default -> throw new AssertionError("Must stop after the final answer despite remaining turn budget");
            };
        }).build();
        var result = agent.run("question");
        assertEquals(COMPLETED, result.stopReason());
        assertEquals(2, requests.size());
        assertEquals(List.of("a", "b"), executed);
        assertEquals(2, result.modelCalls());
        assertEquals("done", result.text());
        var second = requests.get(1).messages();
        assertEquals(ChatMessage.assistantResponse(response), second.get(1));
        assertEquals(replay, second.get(1).replayState());
        assertEquals(List.of("a", "b"), second.subList(2, 4).stream().map(ChatMessage::toolCallId).toList());
        assertEquals(result.toolResults().stream().map(ToolResult::toMessage).toList(), second.subList(2, 4));
    }

    @Test
    void feedsRecoverableToolErrorsBackAndContinues() throws Exception {
        var turns = new AtomicInteger();
        var agent = Agent.builder().tool(contextTool(context -> { throw new IOException("private failure"); }))
                .model(request -> turns.incrementAndGet() == 1
                        ? calls(new ContentBlock.ToolCall("unknown", "missing", "{}"),
                        new ContentBlock.ToolCall("invalid", "test", "[]"), call("failed")) : text("recovered"))
                .build();
        var result = agent.run("question");
        assertEquals(COMPLETED, result.stopReason());
        assertEquals("recovered", result.text());
        assertEquals(List.of(ToolResult.Status.UNKNOWN_TOOL, ToolResult.Status.INVALID_ARGUMENTS,
                ToolResult.Status.EXECUTION_FAILED), result.toolResults().stream().map(ToolResult::status).toList());
        assertFalse(result.messages().toString().contains("private failure"));
    }

    @Test
    void retainsLastRoundAssistantButDoesNotExecuteItsPendingTools() throws Exception {
        var executed = new AtomicInteger();
        var response = calls(call("pending"));
        var agent = Agent.builder().model(request -> response)
                .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("done"); })).build();
        var result = agent.run("question", TurnOptions.limited(1));
        assertEquals(MAX_TURNS, result.stopReason());
        assertFalse(result.completed());
        assertEquals("", result.text());
        assertEquals(1, result.modelCalls());
        assertEquals(0, executed.get());
        assertTrue(result.toolResults().isEmpty());
        assertEquals(ChatMessage.assistantResponse(response), result.messages().getLast());
    }

    @Test
    void defaultsToEightModelTurnsAndSevenExecutableToolRounds() throws Exception {
        var turns = new AtomicInteger();
        var executed = new AtomicInteger();
        var agent = Agent.builder().model(request -> calls(call("call-" + turns.incrementAndGet())))
                .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("done"); })).build();
        var result = agent.run("question");
        assertEquals(MAX_TURNS, result.stopReason());
        assertEquals(8, result.modelCalls());
        assertEquals(8, turns.get());
        assertEquals(7, executed.get());
        assertEquals(7, result.toolResults().size());
    }

    @Test
    void acceptsACompletedAnswerOnTheLastPermittedTurn() throws Exception {
        var turns = new AtomicInteger();
        var result = Agent.builder().model(request -> turns.incrementAndGet() == 1
                        ? calls(call("first")) : text("last-turn answer"))
                .tool(contextTool(context -> new ToolOutput("done"))).build().run("question", TurnOptions.limited(2));
        assertEquals(COMPLETED, result.stopReason());
        assertEquals(2, result.modelCalls());
        assertEquals("last-turn answer", result.text());
    }

    @Test
    void refusesIncompleteResponsesWithoutExecutingTools() throws Exception {
        for (String reason : List.of("length", "max_tokens", "content_filter", "unknown", "")) {
            for (boolean withTools : List.of(false, true)) {
                var executed = new AtomicInteger();
                var response = new ChatResponse(withTools ? List.of(call("partial"))
                        : List.of(new ContentBlock.Text("partial answer")), reason, Optional.empty());
                var result = Agent.builder().model(request -> response)
                        .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                        .build().run("question");
                assertEquals(INCOMPLETE_RESPONSE, result.stopReason(), reason);
                assertEquals("", result.text());
                assertEquals(0, executed.get());
                assertEquals(Optional.of(response), result.lastResponse());
            }
        }
    }

    @Test
    void acceptsProtocolSpecificCompleteFinishReasons() throws Exception {
        for (String toolReason : List.of("tool_calls", "tool_use", "completed")) {
            for (String finalReason : List.of("stop", "end_turn", "completed")) {
                var turns = new AtomicInteger();
                var result = Agent.builder().model(request -> turns.incrementAndGet() == 1
                                ? new ChatResponse(List.of(call("one")), toolReason, Optional.empty())
                                : new ChatResponse("done", finalReason, Optional.empty()))
                        .tool(contextTool(context -> new ToolOutput("ok"))).build().run("question");
                assertTrue(result.completed());
                assertEquals(1, result.toolResults().size());
            }
        }
    }

    @Test
    void prevalidatesTheWholeBatchForDuplicateIdsBeforeAnySideEffect() throws Exception {
        var executed = new AtomicInteger();
        var result = Agent.builder().model(request -> calls(call("unique"), call("duplicate"), call("duplicate")))
                .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                .build().run("question");
        assertEquals(INVALID_TOOL_CALLS, result.stopReason());
        assertEquals(0, executed.get());
        assertTrue(result.toolResults().isEmpty());
        assertEquals("", result.text());
    }

    @Test
    void reportsDuplicateCallsBeforeTheLastTurnBudgetLimit() throws Exception {
        var executed = new AtomicInteger();
        var result = Agent.builder()
                .model(request -> calls(call("duplicate"), call("duplicate")))
                .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                .build().run("question", TurnOptions.limited(1));
        assertEquals(INVALID_TOOL_CALLS, result.stopReason());
        assertEquals(1, result.modelCalls());
        assertEquals(0, executed.get());
        assertTrue(result.toolResults().isEmpty());
    }

    @Test
    void rejectsFinishReasonsThatDisagreeWithToolContent() throws Exception {
        for (var response : List.of(
                new ChatResponse(List.of(call("one")), "stop", Optional.empty()),
                new ChatResponse("no tool call", "tool_calls", Optional.empty()))) {
            var executed = new AtomicInteger();
            var result = Agent.builder().model(request -> response)
                    .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                    .build().run("question");
            assertEquals(INCOMPLETE_RESPONSE, result.stopReason());
            assertEquals(0, executed.get());
            assertEquals(Optional.of(response), result.lastResponse());
        }
    }

    @Test
    void rejectsCrossRoundDuplicatesBeforeExecutingAnyOfTheNewBatch() throws Exception {
        var turns = new AtomicInteger();
        var executed = new ArrayList<String>();
        var result = Agent.builder().model(request -> turns.incrementAndGet() == 1 ? calls(call("used"))
                        : calls(call("fresh"), call("used")))
                .tool(contextTool(context -> { executed.add(context.callId()); return new ToolOutput("ok"); }))
                .build().run("question");
        assertEquals(INVALID_TOOL_CALLS, result.stopReason());
        assertEquals(List.of("used"), executed);
        assertEquals(1, result.toolResults().size());
        assertEquals(2, result.modelCalls());
    }

    @Test
    void rejectsIdsAlreadyPresentInClosedInputHistory() throws Exception {
        var executed = new AtomicInteger();
        var history = List.of(ChatMessage.user("old"), ChatMessage.assistantResponse(calls(call("used"))),
                ChatMessage.toolResult("used", "old result"), ChatMessage.user("continue"));
        var result = Agent.builder().model(request -> calls(call("fresh"), call("used")))
                .tool(contextTool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                .build().run(history);
        assertEquals(INVALID_TOOL_CALLS, result.stopReason());
        assertEquals(0, executed.get());
        assertTrue(result.toolResults().isEmpty());
    }

    @Test
    void rejectsOrphanDuplicateMissingAndReusedHistoricalCallsBeforeRequestingTheModel() {
        var invoked = new AtomicInteger();
        var agent = Agent.builder().model(request -> { invoked.incrementAndGet(); return text("unexpected"); }).build();
        var assistant = ChatMessage.assistantResponse(calls(call("old")));
        var result = ChatMessage.toolResult("old", "done");
        var invalid = List.of(List.of(result), List.of(assistant), List.of(assistant, result, result),
                List.of(assistant, result, assistant, result),
                List.of(ChatMessage.assistantResponse(calls(call("old"), call("old"))), result),
                List.of(assistant, ChatMessage.user("interleaved"), result));
        for (var history : invalid) assertThrows(IllegalArgumentException.class, () -> agent.run(history));
        assertEquals(0, invoked.get());
    }

    @Test
    void prependsSystemPromptWithoutDuplicatingAnIdenticalLeadingMessage() throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var agent = Agent.builder().systemPrompt("system").model(request -> {
            requests.add(request);
            return text("done");
        }).build();
        agent.run("first");
        agent.run(List.of(ChatMessage.system("system"), ChatMessage.user("second")));
        assertEquals(List.of(ChatMessage.system("system"), ChatMessage.user("first")), requests.get(0).messages());
        assertEquals(List.of(ChatMessage.system("system"), ChatMessage.user("second")), requests.get(1).messages());
    }

    @Test
    void snapshotsBuilderInputsAndKeepsRunsIndependent() throws Exception {
        var requests = new ArrayList<ChatRequest>();
        var tools = new ArrayList<Tool>();
        tools.add(contextTool(context -> new ToolOutput("done")));
        var builder = Agent.builder().tools(tools).systemPrompt("original").model(request -> {
            requests.add(request);
            return request.messages().getLast().role() == ChatMessage.Role.TOOL ? text("done") : calls(call("same-id"));
        });
        var agent = builder.build();
        tools.clear();
        builder.systemPrompt("changed");
        var history = new ArrayList<>(List.of(ChatMessage.user("first")));
        var first = agent.run(history);
        history.clear();
        var second = agent.run("second");
        assertTrue(first.completed());
        assertTrue(second.completed());
        assertEquals(2, second.modelCalls());
        assertEquals(1, second.toolResults().size());
        assertEquals(ChatMessage.user("first"), first.messages().get(1));
        assertEquals(ChatMessage.user("second"), second.messages().get(1));
        assertEquals(ChatMessage.system("original"), requests.get(2).messages().getFirst());
        assertEquals(1, requests.get(2).tools().size());
        assertEquals(2, requests.get(2).messages().size());
    }

    @Test
    void forwardsModelOptionsAndBoundsToolOutputAndProgress() throws Exception {
        var options = ModelOptions.builder().temperature(0.25).build();
        var turns = new AtomicInteger();
        var progress = new ArrayList<ToolProgress>();
        var agent = Agent.builder().maxOutputTokens(123).modelOptions(options).maxToolOutputCharacters(64)
                .tool(contextTool(context -> {
                    context.reportProgress("p".repeat(80));
                    return new ToolOutput("o".repeat(80));
                })).model(request -> {
                    assertEquals(123, request.maxOutputTokens());
                    assertEquals(options, request.options());
                    assertEquals(List.of("test"), request.tools().stream().map(ToolDefinition::name).toList());
                    return turns.incrementAndGet() == 1 ? calls(call("bounded")) : text("done");
                }).build();
        var result = agent.run("question", () -> false, progress::add);
        assertEquals(64, result.toolResults().getFirst().output().length());
        assertTrue(result.toolResults().getFirst().truncated());
        assertEquals(64, progress.getFirst().message().length());
        assertTrue(progress.getFirst().truncated());
        assertEquals("bounded", progress.getFirst().callId());
        assertTrue(JSON.readTree(result.toolResults().getFirst().toMessage().text()).path("truncated").asBoolean());
    }

    @Test
    void convertsIoFailuresToSafeMetadataAndCountsFailedAttempts() throws Exception {
        for (IOException failure : List.of(new IOException("private-token"), new ModelHttpException(429))) {
            var turns = new AtomicInteger();
            var previous = calls(call("before-error"));
            var result = Agent.builder().model(request -> {
                if (turns.incrementAndGet() == 1) return previous;
                throw failure;
            }).tool(contextTool(context -> new ToolOutput("ok"))).build().run("question");
            assertEquals(MODEL_ERROR, result.stopReason());
            assertEquals(2, result.modelCalls());
            assertEquals("", result.text());
            var metadata = result.modelFailure().orElseThrow();
            assertEquals(failure.getClass().getSimpleName(), metadata.type());
            if (failure instanceof ModelHttpException) assertEquals(429, metadata.httpStatus().orElseThrow());
            else assertTrue(metadata.httpStatus().isEmpty());
            assertFalse(result.toString().contains("private-token"));
            assertEquals(1, result.toolResults().size());
            assertEquals(Optional.of(previous), result.lastResponse());
            assertEquals(List.of(ChatMessage.user("question"), ChatMessage.assistantResponse(previous),
                    result.toolResults().getFirst().toMessage()), result.messages());
        }
    }

    @Test
    void propagatesModelRuntimeErrorsAndInterruptionWithoutWrapping() {
        for (Throwable failure : List.of(new IllegalStateException("runtime"), new AssertionError("error"),
                new InterruptedException("interrupted"), new CancellationException("cancelled"))) {
            var agent = Agent.builder().model(request -> {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw (InterruptedException) failure;
            }).build();
            assertSame(failure, assertThrows(failure.getClass(), () -> agent.run("question")));
        }
    }

    @Test
    void checksCancellationBeforeAndAfterModelCalls() {
        var calls = new AtomicInteger();
        var cancelled = new AtomicBoolean(true);
        var agent = Agent.builder().model(request -> {
            calls.incrementAndGet();
            cancelled.set(true);
            return text("must not become completion");
        }).build();
        assertThrows(CancellationException.class, () -> agent.run("question", cancelled::get, ignored -> { }));
        assertEquals(0, calls.get());
        cancelled.set(false);
        assertThrows(CancellationException.class, () -> agent.run("question", cancelled::get, ignored -> { }));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesToolCancellationAndObserverFailureWithoutAnotherModelCall() {
        var cancellation = new CancellationException("tool cancelled");
        var observerFailure = new IllegalStateException("observer failed");
        for (boolean cancelTool : List.of(false, true)) {
            var calls = new AtomicInteger();
            var agent = Agent.builder().model(request -> { calls.incrementAndGet(); return calls(call("one")); })
                    .tool(contextTool(context -> {
                        if (cancelTool) throw cancellation;
                        context.reportProgress("running");
                        return new ToolOutput("unreachable");
                    })).build();
            RuntimeException expected = cancelTool ? cancellation : observerFailure;
            assertSame(expected, assertThrows(expected.getClass(), () -> agent.run("question", () -> false,
                    ignored -> { throw observerFailure; })));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void leavesTheExternallyOwnedModelOpenAcrossRuns() throws Exception {
        class OwnedModel implements ChatModel, AutoCloseable {
            private boolean closed;
            @Override public ChatResponse chat(ChatRequest request) {
                assertFalse(closed);
                return text("done");
            }
            @Override public void close() { closed = true; }
        }
        var model = new OwnedModel();
        var agent = Agent.builder().model(model).build();
        assertTrue(agent.run("first").completed());
        assertFalse(model.closed);
        assertTrue(agent.run("second").completed());
        assertFalse(model.closed);
        model.close();
        assertTrue(model.closed);
    }

    @Test
    void appliesBudgetsPerTurnAndKeepsSubsequentTurnsIndependent() throws Exception {
        var agent = Agent.builder().model(request ->
                request.messages().getLast().role() == ChatMessage.Role.TOOL
                        ? text("done") : calls(call("same-id")))
                .tool(contextTool(context -> new ToolOutput("done"))).build();

        var limited = agent.run("first", TurnOptions.limited(1));
        var completed = agent.run(List.of(ChatMessage.user("second")), TurnOptions.limited(2));
        var defaulted = agent.run("third");

        assertEquals(MAX_TURNS, limited.stopReason());
        assertEquals(1, limited.modelCalls());
        assertTrue(limited.toolResults().isEmpty());
        assertTrue(completed.completed());
        assertEquals(2, completed.modelCalls());
        assertEquals(1, completed.toolResults().size());
        assertEquals(ChatMessage.user("second"), completed.messages().getFirst());
        assertTrue(defaulted.completed());
        assertEquals(2, defaulted.modelCalls());
    }

    @Test
    void continuesSixUserTurnsWithIndependentBudgetsAndGrowingHistory() throws Exception {
        var attempts = new AtomicInteger();
        var agent = Agent.builder().model(request -> {
            attempts.incrementAndGet();
            return request.messages().getLast().role() == ChatMessage.Role.TOOL
                    ? text("done") : calls(call("call-" + request.messages().size()));
        }).tool(contextTool(context -> new ToolOutput("done"))).build();
        List<ChatMessage> history = List.of();

        for (int turn = 1; turn <= 6; turn++) {
            var input = new ArrayList<>(history);
            input.add(ChatMessage.user("question-" + turn));
            var result = agent.run(input, TurnOptions.limited(2));

            assertTrue(result.completed());
            assertEquals(2, result.modelCalls());
            assertEquals(1, result.toolResults().size());
            assertEquals(history, result.messages().subList(0, history.size()));
            assertEquals(turn * 4, result.messages().size());
            history = result.messages();
        }
        assertEquals(12, attempts.get());
    }

    @Test
    void continuesClosedHistoryWithoutCarryingPreviousTurnCountersOrResults() throws Exception {
        var agent = Agent.builder().model(request ->
                request.messages().getLast().role() == ChatMessage.Role.TOOL
                        ? text("done") : calls(call("first-tool")))
                .tool(contextTool(context -> new ToolOutput("done"))).build();
        var first = agent.run("first", TurnOptions.limited(2));
        var continued = new ArrayList<>(first.messages());
        continued.add(ChatMessage.user("continue"));
        var second = agent.run(continued, TurnOptions.limited(1));

        // 历史中的调用 ID 仍有效，但当前 turn 的计数和结果从零开始。
        assertEquals(INVALID_TOOL_CALLS, second.stopReason());
        assertEquals(1, second.modelCalls());
        assertTrue(second.toolResults().isEmpty());
        assertEquals(first.messages(), second.messages().subList(0, first.messages().size()));
    }

    @Test
    void unlimitedTurnCanFinishBeyondTheDefaultBudget() throws Exception {
        var attempts = new AtomicInteger();
        var agent = Agent.builder().model(request -> {
            int attempt = attempts.incrementAndGet();
            return attempt <= 10 ? calls(call("call-" + attempt)) : text("done");
        }).tool(contextTool(context -> new ToolOutput("done"))).build();
        var result = agent.run("long task", TurnOptions.unlimited());
        assertTrue(result.completed());
        assertEquals(11, result.modelCalls());
        assertEquals(10, result.toolResults().size());
    }

    @Test
    void unlimitedTurnStillHonorsCancellationAndProgressCallbacks() {
        var attempts = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var progress = new ArrayList<ToolProgress>();
        var agent = Agent.builder().model(request -> calls(call("call-" + attempts.incrementAndGet())))
                .tool(contextTool(context -> {
                    context.reportProgress("running");
                    if (attempts.get() == 10) cancelled.set(true);
                    return new ToolOutput("done");
                })).build();
        assertThrows(CancellationException.class, () -> agent.run(
                List.of(ChatMessage.user("long task")), TurnOptions.unlimited(), cancelled::get, progress::add));
        assertEquals(10, attempts.get());
        assertEquals(10, progress.size());
    }

    private static ChatResponse text(String text) {
        return new ChatResponse(text, "stop", Optional.empty());
    }

    private static ChatResponse calls(ContentBlock.ToolCall... calls) {
        return new ChatResponse(List.of(calls), "tool_calls", Optional.empty());
    }

    private static ContentBlock.ToolCall call(String id) {
        return new ContentBlock.ToolCall(id, "test", "{}");
    }

    private static ContextualTool contextTool(Action action) {
        return new ContextualTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("test", "Test tool", "{\"type\":\"object\"}");
            }
            @Override public ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception {
                return action.execute(context);
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolOutput execute(ToolContext context) throws Exception;
    }
}
