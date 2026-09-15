package io.github.hi.neason.half.model.http;

import io.github.hi.neason.half.model.ModelHttpException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 同步 HTTP 调用的总时限与取消管理；JSON 解码和 HttpClient 关闭由调用方负责。 */
public final class HttpCalls implements AutoCloseable {
    private final HttpClient http;
    private final long timeoutNanos;
    private final Set<CompletableFuture<?>> pending = new HashSet<>();
    private boolean closed;

    public HttpCalls(HttpClient http, Duration timeout) {
        this.http = Objects.requireNonNull(http, "http");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        timeoutNanos = TimeUnit.NANOSECONDS.convert(timeout);
    }

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<String>> exchange;
        // 注册与关闭互斥，确保 close 不会漏掉正在建立的请求。
        synchronized (this) {
            if (closed) throw new IOException("HTTP calls are closed");
            exchange = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            pending.add(exchange);
        }
        try {
            // JDK 的请求 timeout 不保证覆盖响应正文，对完整响应另设总时限。
            var response = exchange.get(timeoutNanos, TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelHttpException(response.statusCode());
            }
            return response;
        } catch (TimeoutException error) {
            throw new HttpTimeoutException("HTTP request exceeded its time limit");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw new IOException("HTTP request failed: " + error.getCause().getClass().getSimpleName());
        } catch (CancellationException error) {
            throw new IOException("HTTP request cancelled");
        } finally {
            exchange.cancel(true);
            synchronized (this) { pending.remove(exchange); }
        }
    }

    @Override
    public void close() {
        List<CompletableFuture<?>> active;
        synchronized (this) {
            if (closed) return;
            closed = true;
            active = List.copyOf(pending);
            pending.clear();
        }
        active.forEach(call -> call.cancel(true));
    }
}
