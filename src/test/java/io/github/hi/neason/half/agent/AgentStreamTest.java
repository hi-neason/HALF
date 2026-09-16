package io.github.hi.neason.half.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.*;
import io.github.hi.neason.half.tool.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.hi.neason.half.agent.AgentResult.StopReason.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentStreamTest {
    @Test
    void streamsTwoTurnsAndReplaysAssistantAndToolResult() throws Exception {
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        var replay = ReplayState.responses(List.of("{\"type\":\"function_call\",\"id\":\"item\"}"));
        var response = new ChatResponse(List.of(new ContentBlock.Text("working"), call("one")),
                "tool_calls", Optional.empty(), replay);
        var agent = Agent.builder().model(model(request -> {
            requests.add(request);
            return requests.size() == 1
                    ? events(List.of(new ModelEvent.TextDelta("working"), new ModelEvent.Completed(response)), null)
                    : events(List.of(new ModelEvent.TextDelta("done"), new ModelEvent.Completed(text("done"))), null);
        })).tool(tool(context -> {
            context.reportProgress("computing");
            return new ToolOutput("42");
        })).build();
        var probe = subscribe(agent.stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
        var result = probe.result();
        assertEquals("done", result.text());
        assertEquals(2, result.modelCalls());
        assertEquals(ChatMessage.assistantResponse(response), requests.get(1).messages().get(1));
        assertEquals(replay, requests.get(1).messages().get(1).replayState());
        assertEquals(result.toolResults().getFirst().toMessage(), requests.get(1).messages().get(2));
        assertEquals(List.of(AgentEvent.TurnStarted.class, AgentEvent.Model.class, AgentEvent.Model.class,
                AgentEvent.ToolStarted.class, AgentEvent.ToolProgressed.class, AgentEvent.ToolCompleted.class,
                AgentEvent.TurnStarted.class, AgentEvent.Model.class, AgentEvent.Model.class, AgentEvent.Completed.class),
                probe.events.stream().map(Object::getClass).toList());
        assertEquals(new ModelEvent.TextDelta("working"), ((AgentEvent.Model) probe.events.get(1)).event());
    }

    @Test
    void startsOnDemandAndDeliversOneEventPerRequestWithIndependentSubscriptions() throws Exception {
        var calls = new AtomicInteger();
        var agent = Agent.builder().model(model(request -> {
            calls.incrementAndGet();
            assertEquals(List.of(ChatMessage.user("question")), request.messages());
            return events(List.of(new ModelEvent.TextDelta("answer"), new ModelEvent.Completed(text("answer"))), null);
        })).build();
        var publisher = agent.stream("question");
        var first = subscribe(publisher);
        assertEquals(0, calls.get());
        assertTrue(first.events.isEmpty());
        for (int i = 1; i <= 4; i++) {
            first.subscription.request(1);
            assertNotNull(first.delivered.poll(5, TimeUnit.SECONDS));
            assertEquals(i, first.events.size());
        }
        assertNull(first.terminal.get(5, TimeUnit.SECONDS));
        var second = subscribe(publisher);
        second.subscription.request(Long.MAX_VALUE);
        assertNull(second.terminal.get(5, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        assertEquals(first.result().messages(), second.result().messages());
    }

    @Test
    void rejectsNonPositiveDemandBeforeCallingModel() throws Exception {
        for (long demand : List.of(0L, -1L)) {
            var calls = new AtomicInteger();
            var probe = subscribe(Agent.builder().model(model(request -> {
                calls.incrementAndGet();
                return events(List.of(new ModelEvent.Completed(text("unused"))), null);
            })).build().stream("question"));
            probe.subscription.request(demand);
            assertInstanceOf(IllegalArgumentException.class, probe.terminal.get(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
            assertTrue(probe.events.isEmpty());
        }
    }

    @Test
    void cancellationCancelsAnActiveModelSubscription() throws Exception {
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var probe = subscribe(Agent.builder().model(model(request -> subscriber ->
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { started.countDown(); }
                    @Override public void cancel() { cancelled.countDown(); }
                }))).build().stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        probe.subscription.cancel();
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertFalse(probe.terminal.isDone());
    }

    @Test
    void cancellationInterruptsRunningToolWithoutStartingAnotherTurn() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var probe = subscribe(Agent.builder().model(model(request -> {
            calls.incrementAndGet();
            return events(List.of(new ModelEvent.Completed(calls(call("one")))), null);
        })).tool(tool(context -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { interrupted.countDown(); throw failure; }
            return new ToolOutput("unreachable");
        })).build().stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        probe.subscription.cancel();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertFalse(probe.terminal.isDone());
    }

    @Test
    void waitsForModelOnCompleteBeforeExecutingTools() throws Exception {
        var completedEvent = new CountDownLatch(1);
        var upstream = new CompletableFuture<Flow.Subscriber<? super ModelEvent>>();
        var executed = new AtomicInteger();
        var turns = new AtomicInteger();
        var probe = subscribe(Agent.builder().model(model(request -> {
            if (turns.incrementAndGet() > 1) return events(List.of(new ModelEvent.Completed(text("done"))), null);
            return subscriber -> {
                upstream.complete(subscriber);
                subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean sent;
                    @Override public void request(long n) {
                        if (!sent) {
                            sent = true;
                            subscriber.onNext(new ModelEvent.Completed(calls(call("one"))));
                            completedEvent.countDown();
                        }
                    }
                    @Override public void cancel() { }
                });
            };
        })).tool(tool(context -> { executed.incrementAndGet(); return new ToolOutput("ok"); }))
                .build().stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(completedEvent.await(5, TimeUnit.SECONDS));
        assertEquals(0, executed.get());
        upstream.get(5, TimeUnit.SECONDS).onComplete();
        assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
        assertEquals(1, executed.get());
    }

    @Test
    void missingCompletionAndEventsAfterCompletionAreProtocolFailures() throws Exception {
        for (var sequence : List.of(List.<ModelEvent>of(new ModelEvent.TextDelta("partial")),
                List.<ModelEvent>of(new ModelEvent.Completed(calls(call("one"))), new ModelEvent.TextDelta("late")))) {
            var executed = new AtomicInteger();
            var probe = subscribe(Agent.builder().model(model(request -> events(sequence, null)))
                    .tool(tool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                    .build().stream("question"));
            probe.subscription.request(Long.MAX_VALUE);
            assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
            assertEquals(MODEL_ERROR, probe.result().stopReason());
            assertEquals(0, executed.get());
        }
    }

    @Test
    void terminalIoErrorAfterCompletedDoesNotExecuteToolsAndRedactsFailure() throws Exception {
        var executed = new AtomicInteger();
        var probe = subscribe(Agent.builder().model(model(request -> events(
                List.of(new ModelEvent.Completed(calls(call("one")))), new IOException("private-token"))))
                .tool(tool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                .build().stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
        assertEquals(MODEL_ERROR, probe.result().stopReason());
        assertEquals(1, probe.result().modelCalls());
        assertEquals(0, executed.get());
        assertFalse(probe.result().toString().contains("private-token"));
    }

    @Test
    void budgetAndDuplicatePreflightPreventToolSideEffects() throws Exception {
        for (boolean budget : List.of(false, true)) {
            var executed = new AtomicInteger();
            var response = budget ? calls(call("one")) : calls(call("one"), call("one"));
            var probe = subscribe(Agent.builder().maxTurns(budget ? 1 : 3)
                    .model(model(request -> events(List.of(new ModelEvent.Completed(response)), null)))
                    .tool(tool(context -> { executed.incrementAndGet(); return new ToolOutput("unexpected"); }))
                    .build().stream("question"));
            probe.subscription.request(Long.MAX_VALUE);
            assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
            assertEquals(budget ? MAX_TURNS : INVALID_TOOL_CALLS, probe.result().stopReason());
            assertEquals(0, executed.get());
        }
    }

    @Test
    void runtimeFailureAndUnsupportedStreamingReachOnErrorWithoutChatFallback() throws Exception {
        var failure = new IllegalStateException("runtime");
        var probe = subscribe(Agent.builder().model(model(request -> events(List.of(), failure)))
                .build().stream("question"));
        probe.subscription.request(Long.MAX_VALUE);
        assertSame(failure, probe.terminal.get(5, TimeUnit.SECONDS));
        var chatCalls = new AtomicInteger();
        var unsupported = subscribe(Agent.builder().model(request -> {
            chatCalls.incrementAndGet();
            return text("unused");
        }).build().stream("question"));
        unsupported.subscription.request(Long.MAX_VALUE);
        assertInstanceOf(UnsupportedOperationException.class, unsupported.terminal.get(5, TimeUnit.SECONDS));
        assertEquals(0, chatCalls.get());
    }

    @Test
    void cancellationInsideToolStartedPreventsExecutionAndFurtherSignals() throws Exception {
        var modelCalls = new AtomicInteger();
        var executed = new AtomicInteger();
        var worker = new CompletableFuture<Thread>();
        var probe = new Probe();
        Agent.builder().model(model(request -> {
            modelCalls.incrementAndGet();
            return events(List.of(new ModelEvent.Completed(calls(call("one")))), null);
        })).tool(tool(context -> {
            executed.incrementAndGet();
            return new ToolOutput("unexpected");
        })).build().stream("question").subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { probe.onSubscribe(subscription); }
            @Override public void onNext(AgentEvent event) {
                probe.onNext(event);
                if (event instanceof AgentEvent.ToolStarted) {
                    probe.subscription.cancel();
                    worker.complete(Thread.currentThread());
                }
            }
            @Override public void onError(Throwable error) { probe.onError(error); }
            @Override public void onComplete() { probe.onComplete(); }
        });
        probe.subscription.request(Long.MAX_VALUE);
        assertTrue(worker.get(5, TimeUnit.SECONDS).join(Duration.ofSeconds(5)));
        assertEquals(0, executed.get());
        assertEquals(1, modelCalls.get());
        assertInstanceOf(AgentEvent.ToolStarted.class, probe.events.getLast());
        assertFalse(probe.terminal.isDone());
    }

    @Test
    void snapshotsHistoryWithSystemPromptAndRejectsUnclosedCallsBeforeModelInvocation() throws Exception {
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        var agent = Agent.builder().systemPrompt("system").model(model(request -> {
            requests.add(request);
            return events(List.of(new ModelEvent.Completed(text("done"))), null);
        })).build();
        var history = new ArrayList<>(List.of(ChatMessage.user("original")));
        var publisher = agent.stream(history);
        history.clear();
        history.add(ChatMessage.user("changed"));
        var probe = subscribe(publisher);
        probe.subscription.request(Long.MAX_VALUE);
        assertNull(probe.terminal.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(ChatMessage.system("system"), ChatMessage.user("original")),
                requests.getFirst().messages());

        requests.clear();
        var invalid = subscribe(agent.stream(List.of(ChatMessage.user("question"),
                ChatMessage.assistantResponse(calls(call("unclosed"))))));
        invalid.subscription.request(Long.MAX_VALUE);
        assertInstanceOf(IllegalArgumentException.class, invalid.terminal.get(5, TimeUnit.SECONDS));
        assertTrue(requests.isEmpty());
    }

    private static Probe subscribe(Flow.Publisher<AgentEvent> publisher) {
        var probe = new Probe();
        publisher.subscribe(probe);
        assertNotNull(probe.subscription);
        return probe;
    }

    private static final class Probe implements Flow.Subscriber<AgentEvent> {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        private final BlockingQueue<AgentEvent> delivered = new LinkedBlockingQueue<>();
        private final CompletableFuture<Throwable> terminal = new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; }
        @Override public void onNext(AgentEvent event) { events.add(event); delivered.add(event); }
        @Override public void onError(Throwable error) { terminal.complete(error); }
        @Override public void onComplete() { terminal.complete(null); }
        AgentResult result() { return assertInstanceOf(AgentEvent.Completed.class, events.getLast()).result(); }
    }

    private static ChatModel model(Function<ChatRequest, Flow.Publisher<ModelEvent>> stream) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { throw new AssertionError("chat fallback"); }
            @Override public Flow.Publisher<ModelEvent> stream(ChatRequest request) { return stream.apply(request); }
        };
    }

    private static Flow.Publisher<ModelEvent> events(List<ModelEvent> events, Throwable failure) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private int index;
            private boolean done;
            @Override public synchronized void request(long n) {
                if (done) return;
                while (n-- > 0 && index < events.size() && !done) subscriber.onNext(events.get(index++));
                if (index == events.size() && !done) {
                    done = true;
                    if (failure == null) subscriber.onComplete();
                    else subscriber.onError(failure);
                }
            }
            @Override public synchronized void cancel() { done = true; }
        });
    }

    private static ChatResponse text(String text) { return new ChatResponse(text, "stop", Optional.empty()); }
    private static ChatResponse calls(ContentBlock.ToolCall... calls) {
        return new ChatResponse(List.of(calls), "tool_calls", Optional.empty());
    }
    private static ContentBlock.ToolCall call(String id) { return new ContentBlock.ToolCall(id, "test", "{}"); }
    private static ContextualTool tool(Action action) {
        return new ContextualTool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("test", "Test tool", "{\"type\":\"object\"}");
            }
            @Override public ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception {
                return action.execute(context);
            }
        };
    }
    @FunctionalInterface private interface Action { ToolOutput execute(ToolContext context) throws Exception; }
}
