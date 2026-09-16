package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** stdio 的进程、换行分帧、串行写入和有界关闭。 */
final class StdioMcpTransport implements McpTransport {
    private static final int MAX_MESSAGE_BYTES = McpJson.MAX_MESSAGE_BYTES;
    private final Process process;
    private final long timeoutNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayBlockingQueue<Outbound> writes = new ArrayBlockingQueue<>(64);
    private Thread writer;
    private Consumer<JsonNode> receiver;
    private BiConsumer<String, McpException> onFailure = (id, error) -> { };
    private final Map<String, CompletableFuture<Void>> requestWrites = new ConcurrentHashMap<>();
    private final AtomicReference<Outbound> activeWrite = new AtomicReference<>();

    StdioMcpTransport(List<String> command, Path directory, Map<String, String> environment,
                  Duration timeout) throws IOException {
        Objects.requireNonNull(timeout, "timeout");
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
        var builder = new ProcessBuilder(List.copyOf(command));
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().putAll(Map.copyOf(environment));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            process = builder.start();
        } catch (IOException error) {
            throw new McpException("Unable to start MCP server");
        }
    }

    @Override
    public void start(Consumer<JsonNode> receive, BiConsumer<String, McpException> failure) {
        receiver = receive;
        onFailure = failure;
        writer = Thread.ofVirtual().name("half-mcp-writer").unstarted(this::writeLoop);
        writer.start();
        Thread.ofVirtual().name("half-mcp-write-deadline").start(this::watchWrites);
        Thread.ofVirtual().name("half-mcp-reader").start(this::readLoop);
    }

    @Override
    public CompletableFuture<Void> send(ObjectNode message) {
        try {
            var written = enqueue(message, System.nanoTime()).written();
            if (message.has("id") && message.has("method")) requestWrites.put(message.get("id").textValue(), written);
            return written;
        }
        catch (IOException error) { return CompletableFuture.failedFuture(error); }
    }

    @Override
    public void cancelRequest(String id) {
        var written = requestWrites.remove(id);
        // 未写出的工具请求不得在取消后继续排队发送；正在阻塞的写入也必须终止进程。
        if (written != null && !written.isDone()) close();
    }

    private Outbound enqueue(ObjectNode message, long started) throws IOException {
        if (closed.get()) throw new McpException("MCP connection is closed");
        byte[] bytes = McpJson.encode(message);
        if (bytes.length > MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
        var outbound = new Outbound(bytes, started, new CompletableFuture<>());
        if (!writes.offer(outbound)) {
            var failure = new McpException("MCP write queue is full");
            close(failure);
            throw failure;
        }
        if (closed.get()) outbound.written().completeExceptionally(new McpException("MCP connection is closed"));
        return outbound;
    }

    private void writeLoop() {
        Outbound active = null;
        try {
            while (!closed.get()) {
                active = writes.take();
                activeWrite.set(active);
                if (closed.get()) throw new McpException("MCP connection is closed");
                if (System.nanoTime() - active.started() >= timeoutNanos) {
                    throw new McpException("MCP write timed out");
                }
                process.getOutputStream().write(active.bytes());
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
                active.written().complete(null);
                activeWrite.set(null);
                active = null;
            }
        } catch (McpException error) {
            close(error);
        } catch (IOException error) {
            close(new McpException("MCP write failed"));
        } catch (InterruptedException error) {
            close(new McpException("MCP writer interrupted"));
        } finally {
            close();
        }
    }

    private void watchWrites() {
        try {
            while (!closed.get()) {
                Outbound active = activeWrite.get();
                if (active != null && System.nanoTime() - active.started() >= timeoutNanos) {
                    close(new McpException("MCP write timed out"));
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    private void readLoop() {
        try (var input = process.getInputStream()) {
            var line = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                for (int index = 0; index < count; index++) {
                    if (buffer[index] == '\n') {
                        JsonNode message = McpJson.parse(line.toByteArray());
                        line.reset();
                        receiver.accept(message);
                    } else {
                        if (line.size() >= MAX_MESSAGE_BYTES) throw new McpException("MCP message exceeds size limit");
                        line.write(buffer[index]);
                    }
                }
            }
            throw new McpException(line.size() == 0 ? "MCP server closed its output" : "Incomplete MCP message at end of stream");
        } catch (McpException error) {
            close(error);
        } catch (IOException error) {
            close(new McpException("MCP read failed"));
        } catch (RuntimeException error) {
            close(new McpException("Invalid MCP message"));
        }
    }

    @Override
    public void close() {
        close(new McpException("MCP connection is closed"));
    }

    private void close(McpException failure) {
        if (closed.compareAndSet(false, true)) {
            Outbound queued;
            while ((queued = writes.poll()) != null) queued.written().completeExceptionally(failure);
            Outbound active = activeWrite.get();
            if (active != null) active.written().completeExceptionally(failure);
            if (Thread.currentThread() != writer) writer.interrupt();
            // Closing a pipe can wait for an active writer. Keep that wait off the caller.
            Thread.ofVirtual().name("half-mcp-close-input").start(() -> {
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            });
            onFailure.accept(null, failure);
        }
        boolean interrupted = false;
        try {
            if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                process.toHandle().destroy();
                if (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    process.toHandle().destroyForcibly();
                    process.waitFor(100, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException error) {
            interrupted = true;
            process.toHandle().destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record Outbound(byte[] bytes, long started, CompletableFuture<Void> written) { }
}
