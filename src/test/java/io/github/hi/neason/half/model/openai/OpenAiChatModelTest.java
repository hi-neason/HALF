package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatModelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API_KEY = "test-only-secret";
    private static final String SUCCESS = """
            {"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}
            """;
    private HttpServer server;
    private URI endpoint;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/custom/chat/completions");
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsOrderedUtf8MessagesAndExplicitNonStreamingRequest() throws Exception {
        respond(200, SUCCESS);
        String userText = "解释 \"Agent\"\n路径 C:\\模型\t完成";
        try (var model = model()) {
            var response = model.chat(new ChatRequest(List.of(
                    ChatMessage.system("中文系统提示"), ChatMessage.user(userText),
                    ChatMessage.assistant("已有回答"), ChatMessage.user("继续")), 128));
            assertEquals("你好", response.text());
            assertEquals("stop", response.finishReason());
            assertTrue(response.usage().isEmpty());
        }
        CapturedRequest request = captured.get();
        assertEquals("POST", request.method());
        assertEquals("Bearer " + API_KEY, request.authorization());
        assertEquals("application/json", request.contentType());
        JsonNode body = JSON.readTree(request.body());
        assertEquals("test-model", body.path("model").textValue());
        assertEquals(false, body.path("stream").booleanValue());
        assertTrue(body.path("stream").isBoolean());
        assertEquals(128, body.path("max_completion_tokens").intValue());
        assertFalse(body.has("temperature"));
        assertFalse(body.has("max_tokens"));
        var messages = body.path("messages");
        assertEquals(4, messages.size());
        assertEquals(List.of("system", "user", "assistant", "user"),
                Stream.of(0, 1, 2, 3).map(i -> messages.get(i).path("role").textValue()).toList());
        assertEquals("中文系统提示", messages.get(0).path("content").textValue());
        assertEquals(userText, messages.get(1).path("content").textValue());
        assertEquals("已有回答", messages.get(2).path("content").textValue());
        assertEquals("继续", messages.get(3).path("content").textValue());
        assertEquals(1, calls.get());
    }

    @Test
    void omitsUnspecifiedOptionsAndPreservesUnknownFinishReasonAndLongUsage() throws Exception {
        respond(200, """
                {"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"future_reason"}],
                 "usage":{"prompt_tokens":3000000000,"completion_tokens":0,"total_tokens":3000000000}}
                """);
        try (var model = model()) {
            var response = model.chat(request());
            assertEquals("", response.text());
            assertEquals("future_reason", response.finishReason());
            assertEquals(new TokenUsage(3_000_000_000L, 0, 3_000_000_000L), response.usage().orElseThrow());
        }
        JsonNode body = JSON.readTree(captured.get().body());
        assertFalse(body.has("max_completion_tokens"));
        assertFalse(body.has("temperature"));
    }

    @Test
    void acceptsNullUsageAndEmptyToolList() throws Exception {
        respond(200, """
                {"choices":[{"message":{"role":"assistant","content":"text","tool_calls":[],
                "function_call":null,"refusal":null},"finish_reason":"length"}],"usage":null}
                """);
        try (var model = model()) {
            var response = model.chat(request());
            assertTrue(response.usage().isEmpty());
            assertEquals("length", response.finishReason());
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsMalformedOrUnsupportedResponsesWithoutExposingBody(String body) throws Exception {
        respond(200, body);
        try (var model = model()) {
            var error = assertThrows(ModelProtocolException.class, () -> model.chat(request()));
            assertFalse(error.toString().contains("body-secret"));
            assertNull(error.getCause());
        }
        assertEquals(1, calls.get());
    }

    static Stream<String> invalidResponses() {
        String choice = "{\"message\":{\"role\":\"assistant\",\"content\":\"body-secret\"},\"finish_reason\":\"stop\"}";
        return Stream.of(
                "", "not-json-body-secret", "{} {}", "null", "[]", "{}",
                "{\"error\":{\"message\":\"body-secret\"}}", "{\"choices\":[]}",
                "{\"choices\":[" + choice + "," + choice + "]}",
                completion("{\"role\":\"assistant\"}", "\"stop\""),
                completion("{\"role\":\"assistant\",\"content\":null}", "\"stop\""),
                completion("{\"role\":\"assistant\",\"content\":123}", "\"stop\""),
                completion("{\"role\":\"user\",\"content\":\"body-secret\"}", "\"stop\""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\"}", "null"),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\"}", "\" \""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\"}", "\"tool_calls\""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\"}", "\"function_call\""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\",\"tool_calls\":[{}]}", "\"stop\""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\",\"function_call\":{}}", "\"stop\""),
                completion("{\"role\":\"assistant\",\"content\":\"body-secret\",\"refusal\":\"refused\"}", "\"stop\""),
                "{\"choices\":[" + choice + "],\"usage\":{}}",
                withUsage("-1", "2", "1"), withUsage("1.5", "2", "3"),
                withUsage("\"1\"", "2", "3"), withUsage("1", "-1", "0"),
                withUsage("1", "2", "-3"), withUsage("9223372036854775808", "0", "0"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void reportsHttpStatusWithoutRetryingOrLeakingResponse(int status) throws Exception {
        respond(status, "body-secret " + API_KEY);
        try (var model = model()) {
            var error = assertThrows(ModelHttpException.class, () -> model.chat(request()));
            assertEquals(status, error.statusCode());
            assertFalse(error.toString().contains("body-secret"));
            assertFalse(error.toString().contains(API_KEY));
            assertNull(error.getCause());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        AtomicInteger destinationCalls = new AtomicInteger();
        server.createContext("/destination", exchange -> {
            destinationCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext(endpoint.getPath(), exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/destination");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        try (var model = model()) {
            var error = assertThrows(ModelHttpException.class, () -> model.chat(request()));
            assertEquals(307, error.statusCode());
        }
        assertEquals(1, calls.get());
        assertEquals(0, destinationCalls.get());
    }

    private void respond(int status, String body) {
        server.createContext(endpoint.getPath(), exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                captured.set(new CapturedRequest(exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private OpenAiChatModel model() {
        return new OpenAiChatModel(endpoint, API_KEY, "test-model", Duration.ofSeconds(5));
    }

    private static ChatRequest request() {
        return new ChatRequest(List.of(ChatMessage.user("hello")));
    }

    private static String completion(String message, String finishReason) {
        return "{\"choices\":[{\"message\":" + message + ",\"finish_reason\":" + finishReason + "}]}";
    }

    private static String withUsage(String input, String output, String total) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"text"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%s,"completion_tokens":%s,"total_tokens":%s}}
                """.formatted(input, output, total);
    }

    private record CapturedRequest(String method, String authorization, String contentType, String body) {}
}
