package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ModelEventStreamTest {
    private static final String FINISH = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";

    @Test
    void doesNotStartUntilDemandAndStopsReadingWhenDemandRunsOut() {
        var h = new Harness();
        assertEquals(0, h.started.get());
        h.output.subscription.request(1);
        assertEquals(1, h.started.get());
        h.event(text("A"));
        assertEquals(List.of(new ModelEvent.TextDelta("A")), h.output.events);
        assertEquals(0, h.permits.get(), "no SSE read-ahead without event demand");
        h.output.subscription.request(1);
        h.event(text("B"));
        assertEquals(2, h.output.events.size());
        assertEquals(0, h.permits.get());
        h.output.subscription.request(1);
        h.event(FINISH); // 控制块不消耗 ModelEvent 需求量。
        h.event("[DONE]");
        assertEquals("AB", ((ModelEvent.Completed) h.output.events.get(2)).response().text());
        assertEquals(1, h.output.completions);
        assertEquals(1, h.released.get());
        assertTrue(h.inputCancelled);
    }

    @Test
    void oneFrameCanYieldSeveralEventsButEachNeedsSeparateDemand() {
        var h = new Harness();
        h.output.subscription.request(1);
        h.event(tool(0, "a", "lookup", "{\"city\":\"北京\"}"));
        assertInstanceOf(ModelEvent.ToolCallStarted.class, h.output.events.getFirst());
        assertEquals(1, h.output.events.size());
        assertEquals(0, h.permits.get());
        h.output.subscription.request(1);
        assertEquals(new ModelEvent.ToolCallDelta(0, "{\"city\":\"北京\"}"), h.output.events.get(1));
        assertEquals(0, h.permits.get());
        h.output.subscription.request(1);
        h.event(FINISH.replace("stop", "tool_calls"));
        assertInstanceOf(ModelEvent.ToolCallCompleted.class, h.output.events.get(2));
        h.output.subscription.request(1);
        h.event("[DONE]");
        var result = ((ModelEvent.Completed) h.output.events.getLast()).response();
        assertEquals(List.of(new ContentBlock.ToolCall("a", "lookup", "{\"city\":\"北京\"}")), result.toolCalls());
        assertEquals(1, h.output.completions);
    }

    @Test
    void rejectsNonpositiveDemandOnceWithoutStartingHttp() {
        for (long n : new long[]{0, -1, Long.MIN_VALUE}) {
            var h = new Harness();
            h.output.subscription.request(n);
            assertInstanceOf(IllegalArgumentException.class, h.output.failure);
            h.output.subscription.request(1);
            h.output.subscription.request(0);
            assertEquals(1, h.output.errors);
            assertEquals(0, h.started.get());
            assertEquals(1, h.released.get());
        }
    }

    @Test
    void cancellationDropsBufferedEventsAndLaterSignals() {
        var h = new Harness();
        h.output.subscription.request(1);
        h.event(tool(0, "a", "lookup", "{}"));
        h.output.subscription.cancel();
        h.output.subscription.request(Long.MAX_VALUE);
        h.stream.onNext("data: " + text("late"));
        h.stream.onComplete();
        h.stream.onError(new IllegalStateException("late"));
        assertEquals(1, h.output.events.size());
        assertEquals(0, h.output.completions);
        assertEquals(0, h.output.errors);
        assertEquals(1, h.released.get());
        assertTrue(h.inputCancelled);
    }

    @Test
    void errorsAreDeliveredWithoutDemandAndWithoutDuplicateTerminalSignals() {
        var h = new Harness();
        h.output.subscription.request(1);
        h.event(text("partial"));
        var failure = new IllegalStateException("transport");
        h.stream.fail(failure);
        h.stream.onComplete();
        assertSame(failure, h.output.failure);
        assertEquals(1, h.output.errors);
        assertEquals(0, h.output.completions);
        assertTrue(h.inputCancelled);
    }

    @Test
    void demandSaturatesInsteadOfOverflowing() {
        var h = new Harness();
        h.output.subscription.request(Long.MAX_VALUE - 1);
        h.output.subscription.request(100);
        for (int i = 0; i < 100; i++) h.event(text("x"));
        h.event(FINISH);
        h.event("[DONE]");
        assertEquals(101, h.output.events.size());
        assertEquals(1, h.output.completions);
    }

    @Test
    void rejectsOversizedSseFramesBeforeDecoding() {
        var h = new Harness();
        h.output.subscription.request(1);
        h.line("data: " + "x".repeat(1024 * 1024));
        assertInstanceOf(ModelProtocolException.class, h.output.failure);
        assertTrue(h.inputCancelled);
    }

    @Test
    void reentrantAndConcurrentRequestsKeepSignalsSerialized() throws Exception {
        var output = new Probe();
        var lines = new ArrayList<String>();
        for (int i = 0; i < 500; i++) { lines.add("data: " + text("x")); lines.add(""); }
        lines.add("data: " + FINISH); lines.add("");
        lines.add("data: [DONE]"); lines.add("");
        var sourceIndex = new AtomicInteger();
        var depth = new AtomicInteger();
        var maxDepth = new AtomicInteger();
        var calls = new AtomicInteger();
        output.onEvent = event -> {
            int current = depth.incrementAndGet();
            maxDepth.accumulateAndGet(current, Math::max);
            output.subscription.request(1);
            depth.decrementAndGet();
        };
        var stream = new OpenAiStream(new ObjectMapper(), output, current -> {
            calls.incrementAndGet();
            current.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    // 模拟在 request 内同步返回数据的上游，暴露递归或并发 drain 错误。
                    current.onNext(lines.get(sourceIndex.getAndIncrement()));
                }
                @Override public void cancel() {}
            });
        }, ignored -> {});
        stream.subscribe();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) tasks.add(executor.submit(() -> output.subscription.request(100)));
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, maxDepth.get());
        assertEquals(1, calls.get());
        assertEquals(501, output.events.size());
        assertEquals(1, output.completions);
        assertEquals(0, output.errors);
    }

    @Test
    void subscriberThrowingFromOnNextIsCancelledWithoutFurtherSignals() {
        var h = new Harness();
        h.output.onEvent = event -> { throw new IllegalStateException("subscriber bug"); };
        h.output.subscription.request(Long.MAX_VALUE);
        h.event(text("first"));
        assertTrue(h.inputCancelled);
        assertEquals(1, h.released.get());
        assertEquals(0, h.output.errors);
        assertEquals(0, h.output.completions);
    }

    @Test
    void decoderAggregatesInterleavedToolsAndPublishesImmutableSnapshots() throws Exception {
        var decoder = decoder();
        var first = decoder.accept(tool(0, "call0", "weather", "{\"city\":"));
        decoder.accept(tool(1, "call1", "time", "{"));
        decoder.accept(toolDelta(0, "\"杭州\"}"));
        decoder.accept(toolDelta(1, "}"));
        var finished = decoder.accept(FINISH.replace("stop", "tool_calls"));
        assertEquals(2, finished.size());
        var result = ((ModelEvent.Completed) decoder.accept("[DONE]").getFirst()).response();
        assertEquals(List.of(new ContentBlock.ToolCall("call0", "weather", "{\"city\":\"杭州\"}"),
                new ContentBlock.ToolCall("call1", "time", "{}")), result.toolCalls());
        assertEquals(new ModelEvent.ToolCallDelta(0, "{\"city\":"), first.get(1));
        assertThrows(UnsupportedOperationException.class, () -> result.content().clear());
    }

    @Test
    void rejectsToolIdentityChangesMalformedArgumentsAndTruncation() throws Exception {
        var changed = decoder();
        changed.accept(tool(0, "a", "lookup", "{"));
        assertThrows(ModelProtocolException.class, () -> changed.accept(tool(0, "b", "lookup", "}")));
        var badJson = decoder();
        badJson.accept(tool(0, "a", "lookup", "{"));
        assertThrows(ModelProtocolException.class, () -> badJson.accept(FINISH.replace("stop", "tool_calls")));
        var truncated = decoder();
        truncated.accept(tool(0, "a", "lookup", "{}"));
        assertThrows(ModelProtocolException.class, () -> truncated.accept(FINISH.replace("stop", "length")));
        var duplicate = decoder();
        duplicate.accept(tool(0, "a", "lookup", "{}"));
        assertThrows(ModelProtocolException.class, () -> duplicate.accept(tool(1, "a", "other", "{}")));
    }

    private static OpenAiEventDecoder decoder() {
        return new OpenAiEventDecoder(new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    private static String text(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}";
    }

    private static String tool(int index, String id, String name, String arguments) {
        try {
            return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + index
                    + ",\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name
                    + "\",\"arguments\":" + new ObjectMapper().writeValueAsString(arguments) + "}}]},\"finish_reason\":null}]}";
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static String toolDelta(int index, String arguments) {
        try {
            return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + index
                    + ",\"id\":null,\"type\":null,\"function\":{\"name\":null,\"arguments\":"
                    + new ObjectMapper().writeValueAsString(arguments) + "}}]},\"finish_reason\":null}]}";
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private static final class Probe implements Flow.Subscriber<ModelEvent> {
        final List<ModelEvent> events = new ArrayList<>();
        Flow.Subscription subscription;
        Throwable failure;
        java.util.function.Consumer<ModelEvent> onEvent = ignored -> {};
        int errors, completions;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(ModelEvent event) { events.add(event); onEvent.accept(event); }
        @Override public void onError(Throwable error) { failure = error; errors++; }
        @Override public void onComplete() { completions++; }
    }

    private static final class Harness {
        final Probe output = new Probe();
        final AtomicInteger started = new AtomicInteger(), released = new AtomicInteger();
        final AtomicLong permits = new AtomicLong();
        boolean inputCancelled;
        final OpenAiStream stream = new OpenAiStream(new ObjectMapper(), output, value -> {
            started.incrementAndGet();
            value.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { permits.addAndGet(n); }
                @Override public void cancel() { inputCancelled = true; }
            });
        }, ignored -> released.incrementAndGet());
        Harness() { stream.subscribe(); }
        void line(String value) {
            assertTrue(permits.getAndDecrement() > 0, "source must have line demand");
            stream.onNext(value);
        }
        void event(String data) { line("data: " + data); line(""); }
    }
}
