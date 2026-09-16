package io.github.hi.neason.half.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentPublisherTest {
    @Test
    void waitsForOnSubscribeToReturnBeforeStartingOrReportingInvalidDemand() throws Exception {
        for (long demand : new long[] {1, 0}) {
            var release = new CountDownLatch(1);
            var requested = new CountDownLatch(1);
            var runs = new AtomicInteger();
            var subscriber = new Probe() {
                @Override public void onSubscribe(Flow.Subscription value) {
                    super.onSubscribe(value);
                    value.request(demand);
                    requested.countDown();
                    awaitUninterruptibly(release);
                }
            };
            var stream = new AgentStream((cancelled, emit) -> {
                runs.incrementAndGet();
                return result();
            });
            Thread subscribing = Thread.startVirtualThread(() -> stream.subscribe(subscriber));
            try {
                await(requested);
                assertEquals(0, runs.get());
                assertEquals(1, subscriber.done.getCount());
                assertTrue(subscriber.events.isEmpty());
            } finally {
                release.countDown();
                subscribing.join(3000);
            }
            await(subscriber.done);
            assertEquals(demand > 0 ? 1 : 0, runs.get());
            if (demand == 0) assertInstanceOf(IllegalArgumentException.class, subscriber.error);
        }
    }

    @Test
    void startsOnlyOnDemandAndEachSubscriptionRunsIndependently() throws Exception {
        var runs = new AtomicInteger();
        var stream = new AgentStream((cancelled, emit) -> {
            runs.incrementAndGet();
            return result();
        });
        var first = new Probe();
        var second = new Probe();
        stream.subscribe(first);
        stream.subscribe(second);
        assertEquals(0, runs.get());
        first.subscription.request(1);
        await(first.done);
        assertEquals(1, runs.get());
        assertTrue(second.events.isEmpty());
        second.subscription.request(1);
        await(second.done);
        assertEquals(2, runs.get());
    }

    @Test
    void saturatesDemandAndEmitsCompletedBeforeTerminalSignal() throws Exception {
        var subscriber = new Probe() {
            @Override public void onSubscribe(Flow.Subscription value) {
                super.onSubscribe(value);
                value.request(Long.MAX_VALUE);
                value.request(Long.MAX_VALUE);
            }
        };
        new AgentStream((cancelled, emit) -> {
            for (int i = 1; i <= 20; i++) emit.accept(new AgentEvent.TurnStarted(i));
            return result();
        }).subscribe(subscriber);
        await(subscriber.done);
        assertNull(subscriber.error);
        assertEquals(21, subscriber.events.size());
        assertInstanceOf(AgentEvent.Completed.class, subscriber.events.getLast());
        assertEquals(1, subscriber.terminals.get());
    }

    @Test
    void invalidRequestDuringOnNextDeliversOneSerializedError() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inCallback = new AtomicBoolean();
        var overlapping = new AtomicBoolean();
        var subscriber = new Probe() {
            @Override public void onNext(AgentEvent event) {
                inCallback.set(true);
                entered.countDown();
                awaitUninterruptibly(release);
                inCallback.set(false);
            }
            @Override public void onError(Throwable error) {
                overlapping.set(inCallback.get());
                super.onError(error);
            }
        };
        new AgentStream((cancelled, emit) -> {
            emit.accept(new AgentEvent.TurnStarted(1));
            return result();
        }).subscribe(subscriber);
        subscriber.subscription.request(1);
        try {
            await(entered);
            subscriber.subscription.request(0);
            subscriber.subscription.request(-1);
            assertEquals(1, subscriber.done.getCount());
        } finally {
            release.countDown();
        }
        await(subscriber.done);
        assertFalse(overlapping.get());
        assertInstanceOf(IllegalArgumentException.class, subscriber.error);
        assertEquals(1, subscriber.terminals.get());
    }

    @Test
    void cancelInterruptsExecutionWithoutTerminalSignal() throws Exception {
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var subscriber = new Probe();
        new AgentStream((cancelled, emit) -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return result();
            } catch (InterruptedException error) {
                interrupted.set(true);
                throw error;
            } finally {
                finished.countDown();
            }
        }).subscribe(subscriber);
        subscriber.subscription.request(1);
        await(started);
        subscriber.subscription.cancel();
        await(finished);
        assertTrue(interrupted.get());
        assertEquals(0, subscriber.terminals.get());
        assertTrue(subscriber.events.isEmpty());
    }

    @Test
    void throwingSubscriberSilentlyCancelsTheRunner() throws Exception {
        var finished = new CountDownLatch(1);
        var cancelledAfterCallback = new AtomicBoolean();
        var subscriber = new Probe() {
            @Override public void onNext(AgentEvent event) { throw new IllegalStateException("callback"); }
        };
        new AgentStream((cancelled, emit) -> {
            try {
                emit.accept(new AgentEvent.TurnStarted(1));
                return result();
            } finally {
                cancelledAfterCallback.set(cancelled.getAsBoolean());
                finished.countDown();
            }
        }).subscribe(subscriber);
        subscriber.subscription.request(1);
        await(finished);
        assertTrue(cancelledAfterCallback.get());
        assertEquals(0, subscriber.terminals.get());
    }

    @Test
    void runnerExceptionIsDeliveredAsOnError() throws Exception {
        var failure = new IllegalStateException("runner");
        var subscriber = new Probe();
        new AgentStream((cancelled, emit) -> { throw failure; }).subscribe(subscriber);
        subscriber.subscription.request(1);
        await(subscriber.done);
        assertSame(failure, subscriber.error);
        assertEquals(1, subscriber.terminals.get());
    }

    private static AgentResult result() {
        return new AgentResult(AgentResult.StopReason.MAX_TURNS, 0,
                List.of(), List.of(), Optional.empty(), Optional.empty());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Timed out waiting for stream signal");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static class Probe implements Flow.Subscriber<AgentEvent> {
        Flow.Subscription subscription;
        final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        volatile Throwable error;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(AgentEvent event) { events.add(event); }
        @Override public void onError(Throwable value) {
            error = value;
            terminals.incrementAndGet();
            done.countDown();
        }
        @Override public void onComplete() { terminals.incrementAndGet(); done.countDown(); }
    }
}
