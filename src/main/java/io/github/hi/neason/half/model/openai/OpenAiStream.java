package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 一个订阅一个状态机：把模型事件需求量映射到 SSE 行读取，不预读下一事件。 */
final class OpenAiStream implements Flow.Subscriber<String>, Flow.Subscription {
    private final Flow.Subscriber<? super ModelEvent> downstream;
    private final Consumer<OpenAiStream> starter;
    private final Consumer<OpenAiStream> release;
    private final SseParser frames = new SseParser();
    private final EventDecoder decoder;
    private final Object lock = new Object();
    private final AtomicInteger draining = new AtomicInteger();
    private final ArrayDeque<ModelEvent> pending = new ArrayDeque<>();
    private Flow.Subscription input;
    private CompletableFuture<?> exchange;
    private ScheduledFuture<?> alarm;
    private long demand;
    private int frameCharacters;
    private boolean ready, started, reading, sourceDone, cancelled, terminated;
    private Throwable failure;

    OpenAiStream(ObjectMapper json, Flow.Subscriber<? super ModelEvent> downstream,
                 Consumer<OpenAiStream> starter, Consumer<OpenAiStream> release) {
        this(new OpenAiEventDecoder(json)::accept, downstream, starter, release);
    }

    @FunctionalInterface
    interface EventDecoder {
        List<ModelEvent> accept(String data) throws ModelProtocolException;
    }

    OpenAiStream(EventDecoder decoder, Flow.Subscriber<? super ModelEvent> downstream,
                 Consumer<OpenAiStream> starter, Consumer<OpenAiStream> release) {
        this.decoder = decoder;
        this.downstream = downstream;
        this.starter = starter;
        this.release = release;
    }

    void subscribe() {
        try {
            downstream.onSubscribe(this);
        } catch (Throwable ignored) {
            cancel(); // 订阅者违反回调契约时，不再次调用它的 onError。
        } finally {
            synchronized (lock) { ready = true; }
            drain();
        }
    }

    @Override
    public void request(long n) {
        synchronized (lock) {
            if (cancelled || terminated) return;
            if (n <= 0) {
                failure = new IllegalArgumentException("request(n) requires n > 0");
            } else {
                long sum = demand + n;
                demand = sum < 0 ? Long.MAX_VALUE : sum;
            }
        }
        drain();
    }

    @Override
    public void cancel() {
        synchronized (lock) { cancelled = true; pending.clear(); }
        stopTransport();
        drain();
    }

    void setExchange(CompletableFuture<?> value) {
        boolean stop;
        synchronized (lock) {
            exchange = value;
            stop = cancelled || terminated || failure != null || sourceDone;
        }
        if (stop) value.cancel(true);
    }

    void setAlarm(ScheduledFuture<?> value) {
        boolean stop;
        synchronized (lock) { alarm = value; stop = cancelled || terminated; }
        if (stop) value.cancel(false);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        boolean stop;
        synchronized (lock) {
            stop = input != null || cancelled || terminated || failure != null;
            if (!stop) input = subscription;
        }
        if (stop) subscription.cancel();
        else drain();
    }

    @Override
    public void onNext(String line) {
        synchronized (lock) {
            if (cancelled || terminated || sourceDone || failure != null) return;
        }
        try {
            // JDK 已进行 UTF-8 解码和分行；此限制用于 HALF 的 SSE 事件缓冲。
            if (line.length() > 1024 * 1024 - frameCharacters) {
                throw new ModelProtocolException("SSE event exceeds the 1 Mi character limit");
            }
            frameCharacters += line.length();
            var data = frames.accept(line);
            if (line.isEmpty()) frameCharacters = 0;
            List<ModelEvent> events = data.isPresent() ? decoder.accept(data.get()) : List.of();
            synchronized (lock) {
                reading = false;
                if (cancelled || terminated || failure != null) return;
                if (pending.size() + events.size() > 256) {
                    throw new ModelProtocolException("Too many events in one SSE frame");
                }
                pending.addAll(events);
                sourceDone = events.stream().anyMatch(ModelEvent.Completed.class::isInstance);
            }
            if (sourceDone) stopTransport();
            drain();
        } catch (Throwable error) {
            fail(error);
        }
    }

    @Override
    public void onError(Throwable error) { transportError(error); }

    @Override
    public void onComplete() {
        transportError(new ModelProtocolException("Model stream closed before its terminal event"));
    }

    void transportError(Throwable error) {
        synchronized (lock) { if (sourceDone) return; }
        fail(error);
    }

    void fail(Throwable error) {
        synchronized (lock) {
            if (cancelled || terminated || failure != null) return;
            failure = error;
            pending.clear();
        }
        stopTransport();
        drain();
    }

    /** 单个 drain 执行者保证信号串行；不在锁内调用用户或 HTTP 回调。 */
    private void drain() {
        synchronized (lock) { if (!ready) return; }
        if (draining.getAndIncrement() != 0) return;
        int missed = 1;
        for (;;) {
            ModelEvent event = null;
            Throwable error = null;
            Flow.Subscription read = null;
            boolean start = false, end = false, silent = false;
            synchronized (lock) {
                if (terminated) return;
                if (cancelled || failure != null || sourceDone && pending.isEmpty()) {
                    terminated = true;
                    end = true;
                    silent = cancelled;
                    error = failure;
                    pending.clear();
                } else if (demand > 0 && !pending.isEmpty()) {
                    event = pending.removeFirst();
                    if (demand != Long.MAX_VALUE) demand--;
                } else if (demand > 0 && !started) {
                    started = true;
                    start = true;
                } else if (demand > 0 && pending.isEmpty() && input != null && !reading && !sourceDone) {
                    reading = true;
                    read = input;
                }
            }
            if (end) {
                stopTransport();
                ScheduledFuture<?> timer;
                synchronized (lock) { timer = alarm; }
                if (timer != null) timer.cancel(false);
                release.accept(this);
                if (!silent) {
                    try {
                        if (error == null) downstream.onComplete();
                        else downstream.onError(error);
                    } catch (Throwable ignored) { /* 不对已终止的订阅者重复发信号。 */ }
                }
                return;
            }
            try {
                if (event != null) {
                    try { downstream.onNext(event); }
                    catch (Throwable ignored) { cancel(); }
                    continue;
                }
                if (start) { starter.accept(this); continue; }
                if (read != null) read.request(1);
            } catch (Throwable problem) {
                fail(problem);
            }
            missed = draining.addAndGet(-missed);
            if (missed == 0) return;
        }
    }

    private void stopTransport() {
        Flow.Subscription upstream;
        CompletableFuture<?> request;
        synchronized (lock) { upstream = input; request = exchange; }
        if (upstream != null) upstream.cancel();
        if (request != null) request.cancel(true);
    }
}
