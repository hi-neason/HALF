package io.github.hi.neason.half.model.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelProtocolException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 模型 HTTP 协议共用的传输、订阅与生命周期；不解释供应商 JSON。 */
public abstract class HttpChatModel implements ChatModel, AutoCloseable {
    private final URI endpoint;
    private final java.util.Map<String, String> headers;
    protected final String model;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ModelStream> active = ConcurrentHashMap.newKeySet();
    private final HttpCalls calls;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "half-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });
    protected final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** endpoint 为完整协议端点，不自动拼接路径。 */
    protected HttpChatModel(URI endpoint, String apiKey, String model, Duration timeout, java.util.Map<String, String> headers) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!("https".equalsIgnoreCase(endpoint.getScheme())
                || "http".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null || endpoint.getQuery() != null) {
            throw new IllegalArgumentException("endpoint must be an HTTP(S) URL without credentials, query or fragment");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new IllegalArgumentException("apiKey must be a nonblank ASCII token without whitespace");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.headers = java.util.Map.copyOf(headers);
        this.model = model;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        deadlines.setRemoveOnCancelPolicy(true);
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        calls = new HttpCalls(http, timeout);
    }

    @Override
    public ChatResponse chat(ChatRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        if (closed.get()) throw new IOException("Model is closed");
        // 1. 把模型无关的 Java 对象映射成该供应商的 JSON 请求。
        HttpRequest httpRequest = httpRequest(request, false);

        // 2. 网络传输使用 JDK；每次 chat 只发送一次请求，不自动重试。
        var response = calls.send(httpRequest);
        // 3. HTTP 成功不等于协议正确，验证并映射响应。
        return decodeResponse(response.body());
    }

    @Override
    public Flow.Publisher<ModelEvent> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            var stream = new ModelStream(newEventDecoder(), subscriber, current -> startStream(request, current), active::remove);
            active.add(stream);
            if (closed.get()) stream.fail(new IOException("Model is closed"));
            stream.subscribe();
        };
    }

    private void startStream(ChatRequest request, ModelStream stream) {
        try {
            if (closed.get()) throw new IOException("Model is closed");
            stream.setAlarm(deadlines.schedule(
                    () -> stream.fail(new HttpTimeoutException("Model stream exceeded its time limit")),
                    TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS));
            var exchange = http.sendAsync(httpRequest(request, true), info -> {
                if (info.statusCode() < 200 || info.statusCode() >= 300) {
                    stream.fail(new ModelHttpException(info.statusCode()));
                } else {
                    String type = info.headers().firstValue("Content-Type").orElse("");
                    if (!type.split(";", 2)[0].strip().equalsIgnoreCase("text/event-stream")) {
                        stream.fail(new ModelProtocolException("Expected text/event-stream response"));
                    }
                }
                return HttpResponse.BodySubscribers.fromLineSubscriber(
                        stream, ignored -> null, StandardCharsets.UTF_8, null);
            });
            stream.setExchange(exchange);
            exchange.whenComplete((response, error) -> {
                if (error != null) {
                    while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
                    stream.transportError(error);
                }
            });
        } catch (Throwable error) {
            stream.fail(error);
        }
    }

    /** 文本便捷入口复用事件流；使用 request(MAX_VALUE) 接收全部事件并聚合最终结果。 */
    @Override
    public ChatResponse stream(ChatRequest request, Consumer<String> onTextDelta)
            throws IOException, InterruptedException {
        Objects.requireNonNull(onTextDelta, "onTextDelta");
        if (Thread.interrupted()) throw new InterruptedException("Streaming call was interrupted");
        var result = new CompletableFuture<ChatResponse>();
        var subscription = new AtomicReference<Flow.Subscription>();
        stream(request).subscribe(new Flow.Subscriber<>() {
            private ChatResponse response;
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ModelEvent event) {
                try {
                    if (event instanceof ModelEvent.TextDelta delta) onTextDelta.accept(delta.text());
                    if (event instanceof ModelEvent.Completed complete) response = complete.response();
                } catch (Throwable error) {
                    result.completeExceptionally(new CompletionException(error));
                    subscription.get().cancel();
                }
            }
            @Override public void onError(Throwable error) {
                result.completeExceptionally(new CompletionException(error));
            }
            @Override public void onComplete() {
                if (response == null) onError(new ModelProtocolException("Missing completion event"));
                else result.complete(response);
            }
        });
        try {
            return result.get(TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("Model stream exceeded its time limit");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("Model stream failed", cause);
        } finally {
            Flow.Subscription value = subscription.get();
            if (value != null) value.cancel();
        }
    }

    private HttpRequest httpRequest(ChatRequest request, boolean streaming) throws IOException {
        var builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request, streaming), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder.build();
    }

    protected abstract String encodeRequest(ChatRequest request, boolean streaming) throws IOException;
    protected abstract ChatResponse decodeResponse(String body) throws ModelProtocolException;
    protected abstract ModelStream.EventDecoder newEventDecoder();

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (ModelStream stream : List.copyOf(active)) stream.fail(new IOException("Model is closed"));
            calls.close();
            deadlines.shutdownNow();
            http.close();
        }
    }
}
