package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatModelStreamTest {
    private static final String FINISH = event("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
    private static final String DONE = "data: [DONE]\n\n";
    private static final String USAGE = event("{\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}");
    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        server.start();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        server.stop(0);
        serverExecutor.shutdownNow();
        assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void deliversFirstDeltaBeforeServerReleasesRestAndReturnsAggregate() throws Exception {
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch releaseRest = new CountDownLatch(1);
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        serve(exchange -> {
            requestBody.set(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            headers(exchange, "text/event-stream");
            write(exchange, event("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}") + text("你好"));
            if (releaseRest.await(5, TimeUnit.SECONDS)) {
                write(exchange, text("，世界") + FINISH + USAGE + DONE);
            }
        });
        try (var model = model(Duration.ofSeconds(10)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> deltas = new ArrayList<>();
            var result = executor.submit(() -> model.stream(request(), delta -> {
                deltas.add(delta);
                firstDelta.countDown();
            }));
            try {
                assertTrue(firstDelta.await(5, TimeUnit.SECONDS), "first delta must arrive while the server is waiting");
                assertFalse(result.isDone(), "stream must still wait for remaining events");
                releaseRest.countDown();
                var response = result.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("你好", "，世界"), deltas);
                assertEquals("你好，世界", response.text());
                assertEquals("stop", response.finishReason());
                assertEquals(new TokenUsage(4, 2, 6), response.usage().orElseThrow());
                assertTrue(requestBody.get().path("stream").booleanValue());
                assertTrue(requestBody.get().path("stream_options").path("include_usage").booleanValue());
                assertEquals("text/event-stream", accept.get());
            } finally {
                releaseRest.countDown();
                result.cancel(true);
            }
        }
    }

    @Test
    void parsesBomCommentsMixedLineEndingsMultilineDataAndFragmentedUnicode() throws Exception {
        String body = "\ufeff: comment\r\n\r\n"
                + "event: message\rdata: {\"choices\":\rdata: [{\"index\":0,\"delta\":{\"content\":\"汉字😀\"},\"finish_reason\":null}]}\r\r"
                + FINISH.replace("\n", "\r\n") + DONE;
        serve(exchange -> {
            headers(exchange, "Text/Event-Stream; charset=UTF-8");
            for (byte value : body.getBytes(StandardCharsets.UTF_8)) {
                exchange.getResponseBody().write(value & 0xff);
                exchange.getResponseBody().flush();
            }
        });
        try (var model = model(Duration.ofSeconds(5))) {
            List<String> deltas = new ArrayList<>();
            var response = model.stream(request(), deltas::add);
            assertEquals(List.of("汉字😀"), deltas);
            assertEquals("汉字😀", response.text());
            assertTrue(response.usage().isEmpty());
        }
    }

    @Test
    void acceptsRoleOnlyNullContentAndUnknownFinishReasonWithoutEmittingEmptyDeltas() throws Exception {
        respond("text/event-stream", event("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}],\"usage\":null}")
                + FINISH.replace("stop", "future_reason") + DONE);
        try (var model = model(Duration.ofSeconds(5))) {
            List<String> deltas = new ArrayList<>();
            var response = model.stream(request(), deltas::add);
            assertTrue(deltas.isEmpty());
            assertEquals("", response.text());
            assertEquals("future_reason", response.finishReason());
            assertTrue(response.usage().isEmpty());
        }
    }

    @ParameterizedTest
    @MethodSource("badStreams")
    void rejectsIncompleteMalformedOrUnsupportedStreams(String body) throws Exception {
        respond("text/event-stream", body);
        try (var model = model(Duration.ofSeconds(5))) {
            assertThrows(ModelProtocolException.class, () -> model.stream(request(), ignored -> {}));
        }
    }

    static Stream<String> badStreams() {
        return Stream.of(DONE, text("partial"), FINISH, FINISH + "data: [DONE]", "data: not-json-secret\n\n" + DONE,
                event("{\"choices\":[]}") + DONE,
                event("{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"x\"}}]}") + DONE,
                event("{\"choices\":[{\"index\":0,\"delta\":{}},{\"index\":1,\"delta\":{}}]}") + DONE,
                event("{\"choices\":[{\"index\":0,\"delta\":{\"content\":42}}]}") + DONE,
                deltaField("tool_calls", "[{}]") + DONE,
                deltaField("function_call", "{}") + DONE,
                deltaField("refusal", "\"refused\"") + DONE,
                FINISH.replace("stop", "tool_calls") + DONE,
                FINISH.replace("stop", "function_call") + DONE,
                FINISH + text("too late") + DONE,
                FINISH + FINISH + DONE,
                USAGE + FINISH + DONE,
                FINISH + USAGE + USAGE + DONE,
                FINISH + USAGE.replace("\"prompt_tokens\":4", "\"prompt_tokens\":-1") + DONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/plain"})
    void rejectsIncorrectContentType(String contentType) throws Exception {
        respond(contentType, FINISH + DONE);
        try (var model = model(Duration.ofSeconds(5))) {
            assertThrows(ModelProtocolException.class, () -> model.stream(request(), ignored -> {}));
        }
    }

    @Test
    void reportsHttpErrorWithoutRetryOrBodyLeak() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        serve(exchange -> {
            calls.incrementAndGet();
            byte[] bytes = "private-body-secret".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        try (var model = model(Duration.ofSeconds(5))) {
            var error = assertThrows(ModelHttpException.class, () -> model.stream(request(), ignored -> fail("no delta expected")));
            assertEquals(429, error.statusCode());
            assertFalse(error.toString().contains("private-body-secret"));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void deadlineStillAppliesAfterResponseHeadersAndFirstDelta() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deltaArrived = new CountDownLatch(1);
        serve(exchange -> {
            headers(exchange, "text/event-stream");
            write(exchange, text("first"));
            release.await(5, TimeUnit.SECONDS);
        });
        try (var model = model(Duration.ofMillis(700)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> model.stream(request(), ignored -> deltaArrived.countDown()));
            try {
                assertTrue(deltaArrived.await(3, TimeUnit.SECONDS), "response has started before timeout");
                var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertInstanceOf(HttpTimeoutException.class, error.getCause());
            } finally {
                release.countDown();
                result.cancel(true);
            }
        }
    }

    @Test
    void callerInterruptStopsStalledReadAndPropagatesInterruptedException() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deltaArrived = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        serve(exchange -> {
            headers(exchange, "text/event-stream");
            write(exchange, text("first"));
            release.await(5, TimeUnit.SECONDS);
        });
        try (var model = model(Duration.ofSeconds(10))) {
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    model.stream(request(), ignored -> deltaArrived.countDown());
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            try {
                assertTrue(deltaArrived.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(3000);
                assertFalse(caller.isAlive(), "interruption must terminate the caller promptly");
                assertInstanceOf(InterruptedException.class, failure.get());
            } finally {
                release.countDown();
                caller.interrupt();
                caller.join(3000);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("callbackFailures")
    void callbackFailurePropagatesSameExceptionWithoutWaitingForServerEof(Throwable callbackError) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        serve(exchange -> {
            headers(exchange, "text/event-stream");
            write(exchange, text("first"));
            release.await(5, TimeUnit.SECONDS);
        });
        try (var model = model(Duration.ofSeconds(10)); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> model.stream(request(), ignored -> {
                if (callbackError instanceof Error error) {
                    throw error;
                }
                throw (RuntimeException) callbackError;
            }));
            try {
                var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertSame(callbackError, error.getCause());
            } finally {
                release.countDown();
                result.cancel(true);
            }
        }
    }

    static Stream<Throwable> callbackFailures() {
        return Stream.of(new IllegalStateException("callback failed"), new AssertionError("callback assertion failed"),
                new java.util.concurrent.CompletionException(new IllegalStateException("wrapped callback failure")));
    }

    @Test
    void chatOnlyModelDoesNotSilentlyEmulateStreaming() {
        io.github.hi.neason.half.model.ChatModel model = ignored -> {
            fail("stream must not invoke chat as a buffered fallback");
            return null;
        };
        assertThrows(UnsupportedOperationException.class, () -> model.stream(request(), ignored -> fail("no delta expected")));
    }

    private void respond(String contentType, String body) {
        serve(exchange -> {
            headers(exchange, contentType);
            write(exchange, body);
        });
    }

    private void serve(StreamHandler handler) {
        server.createContext(endpoint.getPath(), exchange -> {
            try (exchange) {
                handler.handle(exchange);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Client cancellation may close the response while this test server is writing.
            }
        });
    }

    private static void headers(HttpExchange exchange, String contentType) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, 0);
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private OpenAiChatModel model(Duration timeout) {
        return new OpenAiChatModel(endpoint, "test-only-key", "test-model", timeout);
    }

    private static ChatRequest request() {
        return new ChatRequest(List.of(ChatMessage.user("hello")));
    }

    private static String event(String json) {
        return "data: " + json + "\n\n";
    }

    private static String text(String text) {
        return deltaField("content", "\"" + text + "\"");
    }

    private static String deltaField(String name, String value) {
        return event("{\"choices\":[{\"index\":0,\"delta\":{\"" + name + "\":" + value + "},\"finish_reason\":null}]}");
    }

    @FunctionalInterface
    private interface StreamHandler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }
}
