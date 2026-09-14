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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** OpenAI Chat Completions 协议的非流式纯文本实现，不依赖模型 SDK。 */
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
        String body = encodeRequest(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        // 2. 网络传输使用 JDK；每次 chat 只发送一次请求，不自动重试。
        HttpResponse<String> response = http.send(httpRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ModelHttpException(response.statusCode());
        }
        // 3. HTTP 成功不等于协议正确，验证并映射响应。
        return decodeResponse(response.body());
    }

    private String encodeRequest(ChatRequest request) throws JsonProcessingException {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
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
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw new ModelProtocolException("Expected exactly one completion choice");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!"assistant".equals(message.path("role").asText())) {
            throw new ModelProtocolException("Expected an assistant message");
        }
        JsonNode tools = message.get("tool_calls");
        if (tools != null && !tools.isNull() && !(tools.isArray() && tools.isEmpty())
                || message.hasNonNull("function_call") || message.hasNonNull("refusal")) {
            throw new ModelProtocolException("Tool calls and refusal responses require a richer model interface");
        }
        String text = requiredText(message, "content");
        String finishReason = requiredText(choice, "finish_reason");
        if (finishReason.isBlank()) {
            throw new ModelProtocolException("Expected a nonblank finish_reason");
        }
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
            throw new ModelProtocolException("Tool call completions are not supported by the text interface");
        }
        Optional<TokenUsage> usage = Optional.empty();
        JsonNode counts = root.get("usage");
        if (counts != null && !counts.isNull()) {
            usage = Optional.of(new TokenUsage(
                    requiredCount(counts, "prompt_tokens"),
                    requiredCount(counts, "completion_tokens"),
                    requiredCount(counts, "total_tokens")));
        }
        return new ChatResponse(text, finishReason, usage);
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
