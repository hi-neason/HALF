package io.github.hi.neason.half.tool;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 一次调用的能力；取消由宿主提供，进度只在调用存续期间同步交付。 */
public final class ToolContext {
    private final String callId;
    private final String toolName;
    private final BooleanSupplier cancellation;
    private final Consumer<ToolProgress> progress;
    private final int maximum;
    private boolean active = true;
    private Throwable observerFailure;

    ToolContext(String callId, String toolName, BooleanSupplier cancellation,
                Consumer<ToolProgress> progress, int maximum) {
        this.callId = callId;
        this.toolName = toolName;
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.maximum = maximum;
    }

    public String callId() { return callId; }
    public String toolName() { return toolName; }

    public void checkCancelled() throws InterruptedException {
        rethrowObserverFailure();
        if (Thread.interrupted()) throw new InterruptedException("Tool execution was interrupted");
        boolean cancelled;
        try {
            cancelled = cancellation.getAsBoolean();
        } catch (RuntimeException | Error error) {
            recordObserverFailure(error);
            throw error;
        }
        if (cancelled) throw new CancellationException("Tool execution was cancelled");
    }

    /** 回调应快速返回；异常传播给调用方，结束后的更新被忽略。 */
    public synchronized void reportProgress(String message) throws InterruptedException {
        if (!active) return;
        rethrowObserverFailure();
        checkCancelled();
        Objects.requireNonNull(message, "message");
        try {
            progress.accept(new ToolProgress(callId, toolName, ToolOutput.limit(message, maximum),
                    message.length() > maximum));
        } catch (RuntimeException | Error error) {
            recordObserverFailure(error);
            throw error;
        }
    }

    synchronized void rethrowObserverFailure() {
        if (observerFailure instanceof RuntimeException runtime) throw runtime;
        if (observerFailure instanceof Error error) throw error;
    }

    private synchronized void recordObserverFailure(Throwable error) {
        if (observerFailure == null) observerFailure = error;
    }

    synchronized void finish() { active = false; }
}
