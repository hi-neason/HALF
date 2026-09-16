package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** 单轮模型流的有界交接；用户回调只在 Agent 调用线程执行。 */
final class ModelTurnStream {
    private ModelTurnStream() {}

    static ChatResponse call(ChatModel model, ChatRequest request, Consumer<ModelEvent> emit)
            throws IOException, InterruptedException {
        Objects.requireNonNull(emit, "emit");
        if (Thread.interrupted()) throw new InterruptedException("Model stream was interrupted");
        var receiver = new Receiver();
        Throwable failure = null;
        try {
            model.stream(request).subscribe(receiver);
            ChatResponse response = null;
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException("Model stream was interrupted");
                receiver.requestNext();
                ModelEvent event = receiver.take();
                if (event == null) {
                    if (response == null) throw new ModelProtocolException("Missing model completion event");
                    return response;
                }
                emit.accept(event);
                if (event instanceof ModelEvent.Completed completed) response = completed.response();
            }
        } catch (Throwable error) {
            failure = error;
            if (error instanceof InterruptedException interrupted) throw interrupted;
            throw propagate(error);
        } finally {
            try {
                receiver.close();
            } catch (RuntimeException | Error cancellationFailure) {
                if (failure == null) throw cancellationFailure;
                if (failure != cancellationFailure) failure.addSuppressed(cancellationFailure);
            }
        }
    }

    private static IOException propagate(Throwable error) {
        if (error instanceof IOException io) return io;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        return new IOException("Model stream failed", error);
    }

    private static final class Receiver implements Flow.Subscriber<ModelEvent> {
        private Flow.Subscription subscription;
        private ModelEvent pending;
        private Throwable failure;
        private boolean requested;
        private boolean completedEvent;
        private boolean terminated;
        private boolean closed;

        @Override
        public void onSubscribe(Flow.Subscription value) {
            Objects.requireNonNull(value, "subscription");
            synchronized (this) {
                if (!closed && !terminated && subscription == null) {
                    subscription = value;
                    notifyAll();
                    return;
                }
            }
            value.cancel();
        }

        @Override
        public synchronized void onNext(ModelEvent event) {
            if (closed || terminated) return;
            if (event == null || !requested || pending != null || completedEvent) {
                fail(new ModelProtocolException("Unexpected model stream event"));
                return;
            }
            requested = false;
            completedEvent = event instanceof ModelEvent.Completed;
            pending = event;
            notifyAll();
        }

        @Override
        public synchronized void onError(Throwable error) {
            if (!closed && !terminated) fail(Objects.requireNonNull(error, "error"));
        }

        @Override
        public synchronized void onComplete() {
            if (closed || terminated) return;
            terminated = true;
            notifyAll();
        }

        private void fail(Throwable error) {
            failure = error;
            terminated = true;
            notifyAll();
        }

        void requestNext() throws InterruptedException {
            Flow.Subscription value;
            synchronized (this) {
                while (subscription == null && !terminated) wait();
                if (terminated) return;
                requested = true;
                value = subscription;
            }
            // request 可以同步调用 onNext；不持锁，也不在回调线程等待下游需求。
            value.request(1);
        }

        synchronized ModelEvent take() throws IOException, InterruptedException {
            while (pending == null && !terminated) wait();
            if (failure != null) throw propagate(failure);
            ModelEvent event = pending;
            pending = null;
            return event;
        }

        void close() {
            Flow.Subscription value;
            synchronized (this) {
                closed = true;
                pending = null;
                value = subscription;
            }
            if (value != null) value.cancel();
        }
    }
}
