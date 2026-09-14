package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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

/** OpenAI Chat Completions 协议的文本和工具调用的完整响应与 SSE 实现，不依赖模型 SDK。 */
public final class OpenAiChatModel implements ChatModel, AutoCloseable {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<OpenAiStream> active = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "half-stream-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** endpoint 为完整 URL，例如 https://api.openai.com/v1/chat/completions。 */
    public OpenAiChatModel(URI endpoint, String apiKey, String model, Duration timeout) {
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
        this.apiKey = apiKey;
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
    }

    @Override
    public ChatResponse chat(ChatRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        // 1. 把模型无关的 Java 对象映射成该供应商的 JSON 请求。
        HttpRequest httpRequest = httpRequest(request, false);

        // 2. 网络传输使用 JDK；每次 chat 只发送一次请求，不自动重试。
        HttpResponse<String> response = http.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelHttpException(response.statusCode());
        }
        // 3. HTTP 成功不等于协议正确，验证并映射响应。
        return decodeResponse(response.body());
    }

    @Override
    public Flow.Publisher<ModelEvent> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            var stream = new OpenAiStream(json, subscriber, current -> startStream(request, current), active::remove);
            active.add(stream);
            if (closed.get()) stream.fail(new IOException("Model is closed"));
            stream.subscribe();
        };
    }

    private void startStream(ChatRequest request, OpenAiStream stream) {
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
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request, streaming), StandardCharsets.UTF_8))
                .build();
    }

    private String encodeRequest(ChatRequest request, boolean streaming) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("stream", streaming);
        if (streaming) {
            root.putObject("stream_options").put("include_usage", true);
        }
        var messages = root.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode item = messages.addObject();
            item.put("role", message.role().name().toLowerCase(Locale.ROOT));
            List<ContentBlock.ToolCall> calls = message.content().stream()
                    .filter(ContentBlock.ToolCall.class::isInstance).map(ContentBlock.ToolCall.class::cast).toList();
            if (calls.isEmpty() || !message.text().isEmpty()) item.put("content", message.text());
            else item.putNull("content");
            if (message.role() == ChatMessage.Role.TOOL) item.put("tool_call_id", message.toolCallId());
            if (!calls.isEmpty()) {
                var encoded = item.putArray("tool_calls");
                var ids = new HashSet<String>();
                for (ContentBlock.ToolCall call : calls) {
                    if (!ids.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
                    requireArguments(json, call.arguments());
                    var tool = encoded.addObject();
                    tool.put("id", call.id()).put("type", "function");
                    tool.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
            }
        }
        if (request.maxOutputTokens() != null) {
            root.put("max_completion_tokens", request.maxOutputTokens());
        }
        if (!request.tools().isEmpty()) {
            var encodedTools = root.putArray("tools");
            for (var definition : request.tools()) {
                JsonNode schema;
                try {
                    schema = json.readTree(definition.parametersJson());
                } catch (JsonProcessingException error) {
                    throw new IllegalArgumentException("Tool parameters must be valid JSON");
                }
                if (schema == null || !schema.isObject()) {
                    throw new IllegalArgumentException("Tool parameters must be an object JSON Schema");
                }
                if (!"object".equals(schema.path("type").asText())) {
                    throw new IllegalArgumentException("Tool parameters must be an object JSON Schema");
                }
                var function = encodedTools.addObject().put("type", "function").putObject("function");
                function.put("name", definition.name()).put("description", definition.description());
                function.set("parameters", schema);
            }
        }
        return json.writeValueAsString(root);
    }

    private ChatResponse decodeResponse(String body) throws ModelProtocolException {
        JsonNode root = decodeObject(json, body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw new ModelProtocolException("Expected exactly one completion choice");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!"assistant".equals(message.path("role").asText())) {
            throw new ModelProtocolException("Expected an assistant message");
        }
        rejectUnsupported(message);
        List<ContentBlock> content = new ArrayList<>();
        JsonNode calls = message.get("tool_calls");
        var ids = new HashSet<String>();
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray() || calls.size() > 64) throw new ModelProtocolException("Invalid tool calls array");
            for (JsonNode call : calls) {
                if (!"function".equals(call.path("type").asText())) throw new ModelProtocolException("Expected function tool");
                String id = requiredText(call, "id");
                String name = requiredText(call.path("function"), "name");
                String arguments = requiredText(call.path("function"), "arguments");
                if (id.isBlank() || name.isBlank() || !ids.add(id)) throw new ModelProtocolException("Invalid tool identity");
                requireArguments(json, arguments);
                content.add(new ContentBlock.ToolCall(id, name, arguments));
            }
        }
        JsonNode text = message.get("content");
        if (text != null && text.isTextual()) content.add(0, new ContentBlock.Text(text.textValue()));
        else if (content.isEmpty() || text != null && !text.isNull()) {
            throw new ModelProtocolException("Expected text or tool calls");
        }
        String finish = readFinishReason(choice);
        if ((!ids.isEmpty()) != "tool_calls".equals(finish)) throw new ModelProtocolException("Tool calls and finish reason do not match");
        return new ChatResponse(content, finish, readUsage(root));
    }

    static JsonNode decodeObject(ObjectMapper json, String body) throws ModelProtocolException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException exception) {
            // Jackson 的异常可能带有响应正文，避免把它连同敏感内容向外传播。
            throw new ModelProtocolException("Model response is not valid JSON");
        }
        if (root == null || !root.isObject() || root.hasNonNull("error")) {
            throw new ModelProtocolException("Expected a successful chat completion object");
        }
        return root;
    }

    static void rejectUnsupported(JsonNode message) throws ModelProtocolException {
        if (message.hasNonNull("function_call") || message.hasNonNull("refusal")) {
            throw new ModelProtocolException("Legacy function_call and refusal responses are not supported");
        }
    }

    static void requireArguments(ObjectMapper json, String arguments) throws ModelProtocolException {
        // JSON 语法与对象形状校验，不等于工具 Schema 校验或执行授权。
        try {
            JsonNode parsed = json.readTree(arguments);
            if (parsed == null || !parsed.isObject()) throw new ModelProtocolException("Tool arguments must be a JSON object");
        } catch (JsonProcessingException error) {
            throw new ModelProtocolException("Tool arguments must be a JSON object");
        }
    }

    static String readFinishReason(JsonNode choice) throws ModelProtocolException {
        String finishReason = requiredText(choice, "finish_reason");
        if (finishReason.isBlank()) {
            throw new ModelProtocolException("Expected a nonblank finish_reason");
        }
        if ("function_call".equals(finishReason)) {
            throw new ModelProtocolException("Legacy function_call completions are not supported");
        }
        return finishReason;
    }

    static Optional<TokenUsage> readUsage(JsonNode root) throws ModelProtocolException {
        JsonNode counts = root.get("usage");
        if (counts != null && !counts.isNull()) {
            return Optional.of(new TokenUsage(
                    requiredCount(counts, "prompt_tokens"),
                    requiredCount(counts, "completion_tokens"),
                    requiredCount(counts, "total_tokens")));
        }
        return Optional.empty();
    }

    private static String requiredText(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new ModelProtocolException("Expected text field: " + field);
        }
        return value.textValue();
    }

    private static long requiredCount(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new ModelProtocolException("Expected a nonnegative token count: " + field);
        }
        return value.longValue();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (OpenAiStream stream : List.copyOf(active)) stream.fail(new IOException("Model is closed"));
            deadlines.shutdownNow();
            http.close();
        }
    }
}
