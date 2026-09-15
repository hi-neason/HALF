package io.github.hi.neason.half.model.openai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.ModelHttpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiApiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private URI base;
    private ExecutorService executor;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile String response = "{\"unknown\":{\"preserved\":true}}";
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private CountDownLatch hold;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : 0);
            try (var out = exchange.getResponseBody()) {
                if (status != 204) { out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush(); }
                if (hold != null) try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
    }
    @AfterEach void stop() {
        if (hold != null) hold.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
    private OpenAiResponsesClient responses(Duration timeout) { return new OpenAiResponsesClient(base, "test-key", timeout); }
    private OpenAiChatCompletionsClient chat() { return new OpenAiChatCompletionsClient(base, "test-key", Duration.ofSeconds(3)); }
    private ObjectNode object(String value) throws IOException { return (ObjectNode) JSON.readTree(value); }
    private Request received() throws InterruptedException { return java.util.Objects.requireNonNull(requests.poll(3, TimeUnit.SECONDS)); }
    private record Request(String method, String uri, String authorization, String body) { }

    @Test void responsesResourceMethodsAndLosslessJson() throws Exception {
        try (var client = responses(Duration.ofSeconds(3))) {
            ObjectNode body = object("{\"model\":\"m\",\"input\":[{\"type\":\"image\",\"new_field\":true}],\"reasoning\":{\"effort\":\"high\"},\"stream\":true}");
            assertTrue(client.create(body).at("/unknown/preserved").booleanValue());
            var create = received();
            assertEquals("POST", create.method());
            assertEquals("/v1/responses", create.uri());
            assertEquals("Bearer test-key", create.authorization());
            assertEquals(body.path("input"), JSON.readTree(create.body()).path("input"));
            assertFalse(JSON.readTree(create.body()).path("stream").booleanValue());
            assertTrue(body.path("stream").booleanValue());
            client.retrieve("resp/id?", Map.of("include", List.of("reasoning.encrypted_content", "x+y")));
            var get = received();
            assertEquals("GET", get.method());
            assertTrue(get.uri().startsWith("/v1/responses/resp%2Fid%3F?"));
            assertTrue(get.uri().contains("include%5B%5D=reasoning.encrypted_content"));
            assertTrue(get.uri().contains("include%5B%5D=x%2By"));
            client.cancel("r"); assertEquals(new Request("POST", "/v1/responses/r/cancel", "Bearer test-key", ""), received());
            client.listInputItems("r", Map.of("after", "a b", "limit", 3));
            var items = received(); assertTrue(items.uri().contains("after=a%20b")); assertEquals("GET", items.method());
            client.compact(body); assertEquals("/v1/responses/compact", received().uri());
            client.countInputTokens(body); assertEquals("/v1/responses/input_tokens", received().uri());
            status = 204;
            assertTrue(client.delete("r").isNull()); assertEquals("DELETE", received().method());
        }
    }

    @Test void chatStoredCompletionEndpoints() throws Exception {
        try (var client = chat()) {
            client.create(object("{\"messages\":[],\"response_format\":{\"type\":\"json_schema\"},\"n\":2}"));
            var create = received(); assertEquals("/v1/chat/completions", create.uri());
            assertEquals(2, JSON.readTree(create.body()).path("n").intValue());
            client.retrieve("c"); assertEquals("GET", received().method());
            client.update("c", object("{\"metadata\":{\"key\":\"value\"}}"));
            var update = received(); assertEquals("POST", update.method()); assertEquals("/v1/chat/completions/c", update.uri());
            client.list(Map.of("metadata[key]", "a&b")); assertEquals("/v1/chat/completions?metadata%5Bkey%5D=a%26b", received().uri());
            client.listMessages("c", Map.of("limit", 1)); assertEquals("/v1/chat/completions/c/messages?limit=1", received().uri());
            client.delete("c"); assertEquals("DELETE", received().method());
        }
    }

    @Test void errorsDoNotExposeResponseOrKeyAndInvalidIdsFailLocally() throws Exception {
        try (var client = chat()) {
            status = 401; response = "test-key secret";
            var error = assertThrows(ModelHttpException.class, () -> client.retrieve("x"));
            assertEquals(401, error.statusCode()); assertFalse(error.toString().contains("secret"));
            received();
            assertThrows(IllegalArgumentException.class, () -> client.retrieve(".."));
            assertTrue(requests.isEmpty());
            status = 200;
            var invalid = assertThrows(IOException.class, () -> client.retrieve("x"));
            assertFalse(invalid.toString().contains("test-key")); assertNull(invalid.getCause());
        }
    }

    @Test void rawResponsesStreamPreservesUnknownMultilineEventsAndIsColdAndIndependent() throws Exception {
        contentType = "text/event-stream; charset=utf-8";
        response = "\uFEFF: comment\r\nevent: response.future\r\ndata: {\"type\":\"response.future\",\r\ndata: \"value\":\"你好\"}\r\n\r\nevent: response.completed\r\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\r\n\r\n";
        try (var client = responses(Duration.ofSeconds(3))) {
            ObjectNode body = object("{\"model\":\"before\"}");
            var publisher = client.stream(body);
            body.put("model", "after");
            var first = new Sink(); publisher.subscribe(first);
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
            first.subscription.request(1);
            assertEquals("before", JSON.readTree(received().body()).path("model").asText());
            var event = first.next();
            assertEquals("response.future", event.event());
            assertEquals("你好", event.json().path("value").asText());
            assertTrue(event.data().contains("\n"));
            assertNull(first.events.poll(100, TimeUnit.MILLISECONDS));
            first.subscription.request(1); assertEquals("response.completed", first.next().event());
            first.done.get(3, TimeUnit.SECONDS);
            var second = new Sink(); publisher.subscribe(second); second.subscription.request(Long.MAX_VALUE);
            second.done.get(3, TimeUnit.SECONDS); received(); assertEquals(2, second.events.size());
        }
    }

    @Test void retrievalResumesViaGetAndPreservesFailedTerminalEvent() throws Exception {
        contentType = "text/event-stream";
        response = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"x\"}}}\n\n";
        try (var client = responses(Duration.ofSeconds(3))) {
            var sink = new Sink(); client.retrieveStream("r", Map.of("starting_after", 42)).subscribe(sink);
            sink.subscription.request(1);
            assertEquals("response.failed", sink.next().json().path("type").asText());
            sink.done.get(3, TimeUnit.SECONDS);
            var get = received(); assertEquals("GET", get.method());
            assertTrue(get.uri().contains("stream=true")); assertTrue(get.uri().contains("starting_after=42"));
        }
    }

    @Test void chatDoneCompletesAndUnknownChunkFieldsAreRetained() throws Exception {
        contentType = "text/event-stream";
        response = "data: {\"choices\":[{\"index\":1,\"delta\":{\"audio\":{\"data\":\"x\"}}}]}\n\ndata: [DONE]\n\n";
        try (var client = chat()) {
            var sink = new Sink(); client.stream(object("{}")).subscribe(sink); sink.subscription.request(Long.MAX_VALUE);
            sink.done.get(3, TimeUnit.SECONDS);
            assertEquals("x", sink.next().json().at("/choices/0/delta/audio/data").asText());
            assertTrue(sink.events.isEmpty());
        }
    }

    @Test void timeoutIncludesDemandWaitAndCancelBeforeDemandSendsNothing() throws Exception {
        contentType = "text/event-stream";
        response = "data: {\"type\":\"response.future\"}\n\n";
        hold = new CountDownLatch(1);
        try (var client = responses(Duration.ofMillis(400))) {
            var cancelled = new Sink(); client.stream(object("{}")).subscribe(cancelled); cancelled.subscription.cancel();
            cancelled.subscription.request(1); assertNull(requests.poll(100, TimeUnit.MILLISECONDS));
            assertFalse(cancelled.done.isDone());
            var sink = new Sink(); client.stream(object("{}")).subscribe(sink); sink.subscription.request(1); sink.next();
            assertInstanceOf(HttpTimeoutException.class, assertThrows(ExecutionException.class,
                    () -> sink.done.get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @SuppressWarnings("try")
    @Test void invalidDemandAndClientCloseTerminateExactlyOnceWithoutDemand() throws Exception {
        try (var client = responses(Duration.ofSeconds(3))) {
            var sink = new Sink(); client.stream(object("{}")).subscribe(sink);
            sink.subscription.request(0);
            assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                    () -> sink.done.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(requests.isEmpty());
            var idle = new Sink(); client.stream(object("{}")).subscribe(idle);
            client.close();
            assertInstanceOf(IOException.class, assertThrows(ExecutionException.class,
                    () -> idle.done.get(3, TimeUnit.SECONDS)).getCause());
            var late = new Sink(); client.stream(object("{}")).subscribe(late);
            assertThrows(ExecutionException.class, () -> late.done.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void rejectsTrailingJsonAndWhitespaceCredentials() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new OpenAiResponsesClient(base, "key secret", Duration.ofSeconds(1)));
        assertThrows(IOException.class, () -> new OpenAiSseEvent("message", "{} {}").json());
        try (var client = chat()) {
            response = "{} {}";
            assertThrows(IOException.class, () -> client.retrieve("r"));
        }
    }

    @Test void synchronousTimeoutCoversSlowBody() throws Exception {
        hold = new CountDownLatch(1);
        response = "{";
        try (var client = responses(Duration.ofMillis(200))) {
            Future<JsonNode> pending = executor.submit(() -> client.create(object("{}")));
            var error = assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, error.getCause());
        }
    }

    @SuppressWarnings("try")
    @Test void reentrantDemandSerializesCallbacksAndSubscriberFailureCancels() throws Exception {
        contentType = "text/event-stream";
        response = "data: {\"type\":\"response.future\"}\n\n".repeat(30)
                + "data: {\"type\":\"response.completed\"}\n\n";
        try (var client = responses(Duration.ofSeconds(3))) {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var complete = new CompletableFuture<Void>();
            client.stream(object("{}")).subscribe(new Flow.Subscriber<>() {
                Flow.Subscription subscription;
                boolean inCallback;
                public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
                public void onNext(OpenAiSseEvent event) {
                    assertFalse(inCallback); inCallback = true; count.incrementAndGet();
                    subscription.request(1); inCallback = false;
                }
                public void onError(Throwable error) { complete.completeExceptionally(error); }
                public void onComplete() { complete.complete(null); }
            });
            complete.get(3, TimeUnit.SECONDS); assertEquals(31, count.get());
            var called = new CountDownLatch(1);
            var signals = new java.util.concurrent.atomic.AtomicInteger();
            client.stream(object("{}")).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription value) { value.request(Long.MAX_VALUE); }
                public void onNext(OpenAiSseEvent event) { signals.incrementAndGet(); called.countDown(); throw new IllegalStateException(); }
                public void onError(Throwable error) { signals.incrementAndGet(); }
                public void onComplete() { signals.incrementAndGet(); }
            });
            assertTrue(called.await(3, TimeUnit.SECONDS));
            client.close();
            assertEquals(1, signals.get());
        }
    }

    @Test void malformedOversizedAndUnterminatedStreamsFail() throws Exception {
        contentType = "text/event-stream";
        try (var client = responses(Duration.ofSeconds(5))) {
            for (String malformed : List.of("data: secret\n\n", "data: {\"type\":\"response.future\"}\n\n",
                    "data: " + "a".repeat(4 * 1024 * 1024))) {
                response = malformed;
                var sink = new Sink(); client.stream(object("{}")).subscribe(sink); sink.subscription.request(Long.MAX_VALUE);
                var error = assertThrows(ExecutionException.class, () -> sink.done.get(5, TimeUnit.SECONDS)).getCause();
                assertInstanceOf(IOException.class, error); assertFalse(error.toString().contains("secret"));
            }
        }
    }

    private static final class Sink implements Flow.Subscriber<OpenAiSseEvent> {
        final BlockingQueue<OpenAiSseEvent> events = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(OpenAiSseEvent event) { events.add(event); }
        @Override public void onError(Throwable error) { done.completeExceptionally(error); }
        @Override public void onComplete() { done.complete(null); }
        OpenAiSseEvent next() throws InterruptedException { return java.util.Objects.requireNonNull(events.poll(3, TimeUnit.SECONDS)); }
    }
}
