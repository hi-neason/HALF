package io.github.hi.neason.half.agent;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 冷流：每个订阅独立运行，生产者等待需求量，不缓存事件队列。 */
final class AgentStream implements Flow.Publisher<AgentEvent> {
    @FunctionalInterface
    interface Runner {
        AgentResult run(BooleanSupplier cancelled, Consumer<AgentEvent> emit) throws InterruptedException;
    }

    private final Runner runner;

    AgentStream(Runner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super AgentEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        var subscription = new Subscription(subscriber);
        try {
            subscriber.onSubscribe(subscription);
        } catch (Throwable ignored) {
            subscription.cancel();
        } finally {
            synchronized (subscription.lock) { subscription.ready = true; }
            subscription.startIfNeeded();
        }
    }

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super AgentEvent> downstream;
        private final Object lock = new Object();
        private final Object signals = new Object();
        private long demand;
        private boolean ready, cancelled, terminated, errorScheduled;
        private Throwable failure;
        private Thread worker;

        private Subscription(Flow.Subscriber<? super AgentEvent> downstream) {
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            synchronized (lock) {
                if (cancelled || terminated || failure != null) return;
                if (n <= 0) {
                    failure = new IllegalArgumentException("request(n) requires n > 0");
                    if (worker != null) worker.interrupt();
                } else {
                    demand = n > Long.MAX_VALUE - demand ? Long.MAX_VALUE : demand + n;
                }
                lock.notifyAll();
            }
            startIfNeeded();
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                if (cancelled || terminated) return;
                cancelled = true;
                if (worker != null) worker.interrupt();
                lock.notifyAll();
            }
        }

        private void startIfNeeded() {
            synchronized (lock) {
                if (!ready || cancelled || terminated) return;
                if (failure != null && !errorScheduled) {
                    errorScheduled = true;
                    // 独立交付协议错误，即使运行器暂时没有响应中断也能结束订阅。
                    Thread.startVirtualThread(() -> finish(failure));
                } else if (failure == null && worker == null && demand > 0) {
                    worker = Thread.ofVirtual().unstarted(this::run);
                    worker.start();
                }
            }
        }

        private boolean stopped() {
            synchronized (lock) { return cancelled || terminated || failure != null; }
        }

        private void run() {
            try {
                if (stopped()) return;
                AgentResult result = runner.run(this::stopped, this::emit);
                emit(new AgentEvent.Completed(result));
                finish(null);
            } catch (Throwable error) {
                finish(error);
            }
        }

        private void emit(AgentEvent event) {
            Objects.requireNonNull(event, "event");
            try {
                for (;;) {
                    synchronized (lock) {
                        while (demand == 0 && !stopped()) lock.wait();
                    }
                    synchronized (signals) {
                        synchronized (lock) {
                            if (stopped()) throw new CancellationException("Agent stream stopped");
                            if (demand == 0) continue;
                            if (demand != Long.MAX_VALUE) demand--;
                        }
                        try {
                            downstream.onNext(event);
                        } catch (Throwable ignored) {
                            cancel();
                        }
                    }
                    break;
                }
                if (stopped()) throw new CancellationException("Agent stream stopped");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Agent stream interrupted");
            }
        }

        private void finish(Throwable error) {
            synchronized (signals) {
                synchronized (lock) {
                    if (cancelled || terminated) return;
                    terminated = true;
                    if (failure != null) error = failure;
                    lock.notifyAll();
                }
                try {
                    if (error == null) downstream.onComplete();
                    else downstream.onError(error);
                } catch (Throwable ignored) {
                    // 订阅者违反回调契约时，不再次调用它。
                }
            }
        }
    }
}
