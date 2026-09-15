package io.github.hi.neason.half.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesModelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUCCESS = """
            {"id":"msg_1","type":"message","role":"assistant","model":"test-model",
             "content":[{"type":"text","text":"你好"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":7,"output_tokens":3}}
            """;
    private HttpServer server;
    private URI endpoint;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> captured = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private AnthropicMessagesModel model() { return new AnthropicMessagesModel(endpoint, "test-secret", "test-model", Duration.ofSeconds(3)); }
    private ChatRequest request() { return new ChatRequest(List.of(ChatMessage.user("你好")), 128); }
    private void respond(int status, String type, String body) {
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            captured.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            headers.set(exchange.getRequestHeaders());
            exchange.getResponseHeaders().set("Content-Type", type);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    private void json(String body) { respond(200, "application/json", body); }

    @Test void sendsNativeHeadersSystemAndRequiredTokenBudget() throws Exception {
        json(SUCCESS);
        try (var model = model()) {
            var result = model.chat(new ChatRequest(List.of(ChatMessage.system("规则"), ChatMessage.developer("附加"), ChatMessage.user("你好")), 128));
            assertEquals("你好", result.text());
            assertEquals("end_turn", result.finishReason());
            assertEquals(new TokenUsage(7, 3, 10), result.usage().orElseThrow());
        }
        assertEquals("test-secret", headers.get().getFirst("x-api-key"));
        assertEquals("2023-06-01", headers.get().getFirst("anthropic-version"));
        assertNull(headers.get().getFirst("Authorization"));
        var body = captured.get();
        assertEquals("test-model", body.path("model").asText());
        assertEquals(128, body.path("max_tokens").asInt());
        assertFalse(body.path("stream").asBoolean(true));
        assertTrue(body.path("system").toString().contains("规则"));
        assertTrue(body.path("system").toString().contains("附加"));
        assertEquals(1, body.path("messages").size());
        assertEquals("user", body.at("/messages/0/role").asText());
    }

    @Test void suppliesDefaultMaxTokens() throws Exception {
        json(SUCCESS);
        try (var model = model()) { model.chat(new ChatRequest(List.of(ChatMessage.user("hello")))); }
        assertEquals(1024, captured.get().path("max_tokens").asInt());
    }

    @Test void encodesToolResultsAsUserBlocksAndPreservesCallIds() throws Exception {
        json(SUCCESS);
        var call = new ContentBlock.ToolCall("tool_1", "weather", "{\"city\":\"北京\"}");
        var tool = new ToolDefinition("weather", "测试", "{\"type\":\"object\"}");
        var options = ModelOptions.builder().toolChoice(ToolChoice.Mode.REQUIRED).parallelToolCalls(false).build();
        try (var model = model()) {
            model.chat(new ChatRequest(List.of(ChatMessage.user("天气"),
                    new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(call), null),
                    ChatMessage.toolResult("tool_1", "晴")), 128, List.of(tool), options));
        }
        var body = captured.get();
        assertEquals("object", body.at("/tools/0/input_schema/type").asText());
        assertEquals("any", body.at("/tool_choice/type").asText());
        assertTrue(body.at("/tool_choice/disable_parallel_tool_use").asBoolean());
        assertEquals("tool_use", body.at("/messages/1/content/0/type").asText());
        assertEquals("北京", body.at("/messages/1/content/0/input/city").asText());
        assertEquals("user", body.at("/messages/2/role").asText());
        assertEquals("tool_result", body.at("/messages/2/content/0/type").asText());
        assertEquals("tool_1", body.at("/messages/2/content/0/tool_use_id").asText());
    }

    @Test void completeToolCallsRequireResultsBeforeNextRequest() {
        json(SUCCESS);
        var call = new ChatMessage(ChatMessage.Role.ASSISTANT,
                List.of(new ContentBlock.ToolCall("a", "weather", "{}")), null);
        try (var model = model()) {
            assertThrows(IllegalArgumentException.class, () -> model.chat(new ChatRequest(List.of(ChatMessage.user("weather"), call), 128)));
            assertEquals(0, calls.get());
        }
    }

    @Test void parallelToolResultsStayTogetherInNextUserMessage() throws Exception {
        json(SUCCESS);
        try (var model = model()) {
            model.chat(new ChatRequest(List.of(ChatMessage.user("compare"),
                    new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                            new ContentBlock.ToolCall("a", "weather", "{}"),
                            new ContentBlock.ToolCall("b", "weather", "{}")), null),
                    ChatMessage.toolResult("a", "sun"), ChatMessage.toolResult("b", "rain")), 128));
        }
        assertEquals(3, captured.get().path("messages").size());
        assertEquals("a", captured.get().at("/messages/2/content/0/tool_use_id").asText());
        assertEquals("b", captured.get().at("/messages/2/content/1/tool_use_id").asText());
    }

    @Test void mapsStructuredOutputAndAdaptiveThinkingUsingNativeOptions() throws Exception {
        json(SUCCESS);
        var options = ModelOptions.builder().reasoningEffort(ModelOptions.ReasoningEffort.HIGH)
                .responseFormat(new ResponseFormat.JsonSchema("test", null,
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", true)).build();
        try (var model = new AnthropicMessagesModel(endpoint, "test-secret", "test-model", Duration.ofSeconds(3), new AnthropicThinking.Adaptive())) {
            model.chat(new ChatRequest(request().messages(), 2048, List.of(), options));
        }
        assertEquals("adaptive", captured.get().at("/thinking/type").asText());
        assertEquals("high", captured.get().at("/output_config/effort").asText());
        assertEquals("json_schema", captured.get().at("/output_config/format/type").asText());
        assertEquals("object", captured.get().at("/output_config/format/schema/type").asText());
        assertFalse(captured.get().has("response_format"));
    }

    @Test void explicitThinkingBudgetMustFitOutputBudget() throws Exception {
        json(SUCCESS);
        try (var model = new AnthropicMessagesModel(endpoint, "test-secret", "test-model", Duration.ofSeconds(3), new AnthropicThinking.Enabled(1024))) {
            assertThrows(IllegalArgumentException.class, () -> model.chat(request()));
            model.chat(new ChatRequest(request().messages(), 2048));
        }
        assertEquals(1024, captured.get().at("/thinking/budget_tokens").asInt());
        assertEquals("enabled", captured.get().at("/thinking/type").asText());
    }

    @Test void unsupportedOpenAiOptionsAreRejectedBeforeNetwork() {
        json(SUCCESS);
        try (var model = model()) {
            for (var options : List.of(ModelOptions.builder().temperature(1.5).build(),
                    ModelOptions.builder().verbosity(ModelOptions.Verbosity.HIGH).build(),
                    ModelOptions.builder().responseFormat(new ResponseFormat.JsonObject()).build())) {
                assertThrows(IllegalArgumentException.class, () -> model.chat(new ChatRequest(request().messages(), 128, List.of(), options)));
            }
            assertEquals(0, calls.get());
        }
    }

    @Test void toolArgumentsMayContainErrorNamedFields() throws Exception {
        respond(200, "text/event-stream", begin()
                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"report\",\"input\":{}}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"error\\\":\\\"x\\\",\\\"type\\\":\\\"error\\\"}\"}")
                + event("content_block_stop", "\"index\":0") + finish("tool_use"));
        try (var model = model()) {
            var result = model.stream(request(), text -> {});
            assertEquals("x", JSON.readTree(result.toolCalls().getFirst().arguments()).path("error").asText());
        }
    }

    @Test void decodesToolInputAsJsonObject() throws Exception {
        json(SUCCESS.replace("{\"type\":\"text\",\"text\":\"你好\"}", "{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"weather\",\"input\":{\"city\":\"北京\"}}")
                .replace("end_turn", "tool_use"));
        try (var model = model()) {
            var result = model.chat(request());
            assertEquals("tool_use", result.finishReason());
            assertEquals("北京", JSON.readTree(result.toolCalls().getFirst().arguments()).path("city").asText());
        }
    }

    @Test void streamAggregatesToolJsonBeforeCompletingCall() throws Exception {
        respond(200, "text/event-stream", begin()
                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"weather\",\"input\":{}}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"北京\\\"}\"}")
                + event("content_block_stop", "\"index\":0") + finish("tool_use"));
        try (var model = model()) {
            var events = collect(model.stream(request()));
            assertTrue(events.stream().anyMatch(ModelEvent.ToolCallStarted.class::isInstance));
            assertEquals(2, events.stream().filter(ModelEvent.ToolCallDelta.class::isInstance).count());
            var call = events.stream().filter(ModelEvent.ToolCallCompleted.class::isInstance)
                    .map(ModelEvent.ToolCallCompleted.class::cast).findFirst().orElseThrow().call();
            assertEquals("北京", JSON.readTree(call.arguments()).path("city").asText());
            assertEquals(List.of(call), ((ModelEvent.Completed) events.getLast()).response().toolCalls());
        }
    }

    @Test void thinkingAndRedactedBlocksReplayWithoutLosingSignatureOrData() throws Exception {
        json(SUCCESS.replace("{\"type\":\"text\",\"text\":\"你好\"}", """
                {"type":"thinking","thinking":"检查","signature":"opaque-signature"},
                {"type":"redacted_thinking","data":"opaque-data"},{"type":"text","text":"你好"}
                """));
        try (var model = model()) {
            var result = model.chat(request());
            assertEquals("你好", result.text());
            assertEquals(new ContentBlock.Thinking("检查", "opaque-signature"), result.content().get(0));
            assertEquals(new ContentBlock.RedactedThinking("opaque-data"), result.content().get(1));
            model.chat(new ChatRequest(List.of(ChatMessage.user("你好"), ChatMessage.assistantResponse(result), ChatMessage.user("继续")), 128));
            assertEquals("opaque-signature", captured.get().at("/messages/1/content/0/signature").asText());
            assertEquals("opaque-data", captured.get().at("/messages/1/content/1/data").asText());
        }
    }

    @Test void thinkingStreamKeepsSignatureSeparateFromVisibleText() throws Exception {
        respond(200, "text/event-stream", begin()
                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"检查\"}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"opaque\"}")
                + event("content_block_stop", "\"index\":0") + finish("end_turn"));
        try (var model = model()) {
            var events = collect(model.stream(request()));
            assertTrue(events.stream().anyMatch(ModelEvent.ThinkingDelta.class::isInstance));
            assertTrue(events.stream().anyMatch(ModelEvent.ThinkingCompleted.class::isInstance));
            assertFalse(events.stream().anyMatch(ModelEvent.TextDelta.class::isInstance));
            assertEquals(new ContentBlock.Thinking("检查", "opaque"),
                    ((ModelEvent.Completed) events.getLast()).response().content().getFirst());
        }
    }

    @Test void inputUsageIncludesCacheCreationAndReadTokens() throws Exception {
        json(SUCCESS.replace("\"input_tokens\":7", "\"input_tokens\":7,\"cache_creation_input_tokens\":11,\"cache_read_input_tokens\":13"));
        try (var model = model()) { assertEquals(new TokenUsage(31, 3, 34), model.chat(request()).usage().orElseThrow()); }
    }

    @Test void midConversationSystemMessagesAreNotSilentlyMovedToFront() {
        json(SUCCESS);
        try (var model = model()) {
            assertThrows(IllegalArgumentException.class, () -> model.chat(new ChatRequest(
                    List.of(ChatMessage.user("first"), ChatMessage.system("changed"), ChatMessage.user("next")))));
            assertEquals(0, calls.get());
        }
    }

    @Test void imageUrlsBecomeNativeImageSources() throws Exception {
        json(SUCCESS);
        try (var model = model()) {
            model.chat(new ChatRequest(List.of(new ChatMessage(ChatMessage.Role.USER, List.of(
                    new ContentBlock.Text("Describe"), new ContentBlock.Image("https://example.org/test.png")), null)), 128));
        }
        assertEquals("image", captured.get().at("/messages/0/content/1/type").asText());
        assertEquals("url", captured.get().at("/messages/0/content/1/source/type").asText());
        assertEquals("https://example.org/test.png", captured.get().at("/messages/0/content/1/source/url").asText());
    }

    @Test void base64ImagesPreserveMediaTypeAndEncodedData() throws Exception {
        json(SUCCESS);
        try (var model = model()) {
            model.chat(new ChatRequest(List.of(new ChatMessage(ChatMessage.Role.USER,
                    List.of(new ContentBlock.Image("data:image/png;base64,AQID")), null)), 128));
        }
        assertEquals("image", captured.get().at("/messages/0/content/0/type").asText());
        assertEquals("base64", captured.get().at("/messages/0/content/0/source/type").asText());
        assertEquals("image/png", captured.get().at("/messages/0/content/0/source/media_type").asText());
        assertEquals("AQID", captured.get().at("/messages/0/content/0/source/data").asText());
    }

    @Test void adjacentUserMessagesMergeContentInOriginalOrder() throws Exception {
        json(SUCCESS);
        try (var model = model()) {
            model.chat(new ChatRequest(List.of(ChatMessage.user("first"), ChatMessage.user("second")), 128));
        }
        assertEquals(1, captured.get().path("messages").size());
        assertEquals("user", captured.get().at("/messages/0/role").asText());
        assertEquals(2, captured.get().at("/messages/0/content").size());
        assertEquals("first", captured.get().at("/messages/0/content/0/text").asText());
        assertEquals("second", captured.get().at("/messages/0/content/1/text").asText());
    }

    @Test void thinkingDeltaAfterSignatureIsRejected() throws Exception {
        var decoder = new AnthropicEventDecoder(JSON);
        decoder.accept("{\"type\":\"message_start\",\"message\":"
                + SUCCESS.replace("{\"type\":\"text\",\"text\":\"你好\"}", "")
                        .replace("\"end_turn\"", "null") + "}");
        decoder.accept("""
                {"type":"content_block_start","index":0,
                 "content_block":{"type":"thinking","thinking":"","signature":""}}
                """);
        decoder.accept("""
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"signature_delta","signature":"opaque"}}
                """);
        var error = assertThrows(ModelProtocolException.class, () -> decoder.accept("""
                {"type":"content_block_delta","index":0,
                 "delta":{"type":"thinking_delta","thinking":"too late"}}
                """));
        assertEquals("Thinking after signature", error.getMessage());
    }

    @Test void textStreamPreservesCumulativeUsageAndFinishesAtMessageStop() throws Exception {
        respond(200, "text/event-stream; charset=utf-8", textStream());
        try (var model = model()) {
            var collected = collect(model.stream(request()));
            assertEquals(List.of("你", "好"), collected.stream().filter(ModelEvent.TextDelta.class::isInstance)
                    .map(ModelEvent.TextDelta.class::cast).map(ModelEvent.TextDelta::text).toList());
            var result = ((ModelEvent.Completed) collected.getLast()).response();
            assertEquals("你好", result.text());
            assertEquals(new TokenUsage(7, 3, 10), result.usage().orElseThrow());
            assertTrue(captured.get().path("stream").asBoolean());
        }
    }

    @Test void textCallbackUsesIncrementalOutput() throws Exception {
        respond(200, "text/event-stream", textStream());
        try (var model = model()) {
            var fragments = new ArrayList<String>();
            assertEquals("你好", model.stream(request(), fragments::add).text());
            assertEquals(List.of("你", "好"), fragments);
        }
    }

    @Test void publisherDoesNotSendUntilPositiveDemandAndCancellationStopsDelivery() throws Exception {
        respond(200, "text/event-stream", textStream());
        try (var model = model()) {
            var sub = new AtomicReference<Flow.Subscription>();
            var first = new CompletableFuture<ModelEvent>();
            var seen = new AtomicInteger();
            model.stream(request()).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription s) { sub.set(s); }
                public void onNext(ModelEvent e) { seen.incrementAndGet(); first.complete(e); }
                public void onError(Throwable e) { first.completeExceptionally(e); }
                public void onComplete() { }
            });
            assertEquals(0, calls.get());
            sub.get().request(1);
            first.get(3, TimeUnit.SECONDS);
            assertEquals(1, seen.get());
            sub.get().cancel();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"missing_stop", "wrong_index", "wrong_delta", "error", "missing_block_stop"})
    void rejectsBrokenStreams(String fault) throws Exception {
        String body = textStream();
        body = switch (fault) {
            case "missing_stop" -> body.substring(0, body.lastIndexOf("event: message_stop"));
            case "wrong_index" -> body.replace("\"index\":0,\"delta\"", "\"index\":5,\"delta\"");
            case "wrong_delta" -> body.replace("text_delta", "input_json_delta");
            case "error" -> event("error", "\"error\":{\"type\":\"overloaded_error\",\"message\":\"test-secret\"}");
            case "missing_block_stop" -> body.replace(event("content_block_stop", "\"index\":0"), "");
            default -> throw new AssertionError();
        };
        respond(200, "text/event-stream", body);
        try (var model = model()) {
            Exception failure = assertThrows(Exception.class, () -> model.stream(request(), s -> {}));
            assertFalse(failure.toString().contains("test-secret"));
        }
    }

    @Test void rejectsHttpErrorsWithoutLeakingResponseBody() {
        respond(401, "application/json", "{\"error\":\"test-secret\"}");
        try (var model = model()) {
            var error = assertThrows(ModelHttpException.class, () -> model.chat(request()));
            assertFalse(error.toString().contains("test-secret"));
        }
    }

    @Test void rejectsWrongStreamingContentType() {
        json(SUCCESS);
        try (var model = model()) { assertThrows(Exception.class, () -> model.stream(request(), s -> {})); }
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"type\":\"error\"}", "[]", "not json"})
    void rejectsMalformedResponses(String body) {
        json(body);
        try (var model = model()) { assertThrows(ModelProtocolException.class, () -> model.chat(request())); }
    }

    @Test void bodyReadHasTotalDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        try (var model = new AnthropicMessagesModel(endpoint, "test-secret", "test-model", Duration.ofMillis(150))) {
            assertThrows(HttpTimeoutException.class, () -> model.chat(request()));
        } finally { release.countDown(); }
    }

    static List<ModelEvent> collect(Flow.Publisher<ModelEvent> publisher) throws Exception {
        var result = new CompletableFuture<List<ModelEvent>>();
        var events = new ArrayList<ModelEvent>();
        publisher.subscribe(new Flow.Subscriber<>() {
            Flow.Subscription subscription;
            public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
            public void onNext(ModelEvent e) { events.add(e); subscription.request(1); }
            public void onError(Throwable e) { result.completeExceptionally(e); }
            public void onComplete() { result.complete(List.copyOf(events)); }
        });
        return result.get(4, TimeUnit.SECONDS);
    }

    static String event(String type, String fields) {
        return "event: " + type + "\ndata: {\"type\":\"" + type + "\"" + (fields.isEmpty() ? "" : "," + fields) + "}\n\n";
    }
    static String begin() {
        return event("message_start", "\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-model\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}");
    }
    static String finish(String reason) {
        return event("message_delta", "\"delta\":{\"stop_reason\":\"" + reason + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":3}")
                + event("message_stop", "");
    }
    static String textStream() {
        return begin() + event("ping", "") + event("future_event", "\"data\":{}")
                + event("content_block_start", "\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你\"}")
                + event("content_block_delta", "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}")
                + event("content_block_stop", "\"index\":0") + finish("end_turn");
    }
}
