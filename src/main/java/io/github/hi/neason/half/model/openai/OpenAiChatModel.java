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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** OpenAI Chat Completions 协议的完整响应与 SSE 纯文本实现，不依赖模型 SDK。 */
public final class OpenAiChatModel implements ChatModel, AutoCloseable {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;
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

    /** timeout 同时作为本次流式 HTTP 调用的总时限，包含响应体读取。 */
    @Override
    public ChatResponse stream(ChatRequest request, Consumer<String> onTextDelta)
            throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onTextDelta, "onTextDelta");
        if (Thread.interrupted()) {
            throw new InterruptedException("Streaming call was interrupted");
        }
        var stream = new OpenAiStream(json, onTextDelta);
        HttpRequest httpRequest = httpRequest(request, true);
        // 字节到 UTF-8 文本行由 JDK 处理，SSE 分帧与模型增量由 HALF 处理。
        var exchange = http.sendAsync(httpRequest, info -> {
            if (info.statusCode() < 200 || info.statusCode() >= 300) {
                stream.fail(new ModelHttpException(info.statusCode()));
            } else {
                String type = info.headers().firstValue("Content-Type").orElse("");
                if (!type.split(";", 2)[0].strip().equalsIgnoreCase("text/event-stream")) {
                    stream.fail(new ModelProtocolException("Expected text/event-stream response"));
                }
            }
            return HttpResponse.BodySubscribers.fromLineSubscriber(
                    stream, subscriber -> null, StandardCharsets.UTF_8, null);
        });
        exchange.whenComplete((response, error) -> {
            if (error != null) {
                while (error instanceof CompletionException && error.getCause() != null) {
                    error = error.getCause();
                }
                stream.fail(error);
            }
        });
        try {
            return stream.result().get(TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new HttpTimeoutException("Model stream exceeded its time limit");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Model stream failed", cause);
        } finally {
            // 取消不会保证产生 onComplete/onError，应用层结果必须自行结束。
            stream.cancel();
            exchange.cancel(true);
        }
    }

    private HttpRequest httpRequest(ChatRequest request, boolean streaming) throws JsonProcessingException {
        return HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(encodeRequest(request, streaming), StandardCharsets.UTF_8))
                .build();
    }

    private String encodeRequest(ChatRequest request, boolean streaming) throws JsonProcessingException {
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
            item.put("content", message.text());
        }
        if (request.maxOutputTokens() != null) {
            root.put("max_completion_tokens", request.maxOutputTokens());
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
        requireTextOnly(message);
        return new ChatResponse(requiredText(message, "content"), readFinishReason(choice), readUsage(root));
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

    static void requireTextOnly(JsonNode message) throws ModelProtocolException {
        JsonNode tools = message.get("tool_calls");
        if (tools != null && !tools.isNull() && !(tools.isArray() && tools.isEmpty())
                || message.hasNonNull("function_call") || message.hasNonNull("refusal")) {
            throw new ModelProtocolException("Tool calls and refusal responses require a richer model interface");
        }
    }

    static String readFinishReason(JsonNode choice) throws ModelProtocolException {
        String finishReason = requiredText(choice, "finish_reason");
        if (finishReason.isBlank()) {
            throw new ModelProtocolException("Expected a nonblank finish_reason");
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            throw new ModelProtocolException("Tool call completions are not supported by the text interface");
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
        http.close();
    }
}
