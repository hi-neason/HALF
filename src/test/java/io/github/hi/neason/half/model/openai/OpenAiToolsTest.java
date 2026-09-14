package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ToolDefinition WEATHER = new ToolDefinition("weather", "天气",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}");
    private static final String CALL = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_a","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"北京\\"}"}}
            ]},"finish_reason":"tool_calls"}]}
            """;
    private static final String STREAM = """
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"weather","arguments":"{\\"city\\":"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"北京\\"}"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}

            data: [DONE]

            """;
    private HttpServer server;
    private URI endpoint;
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
        server.start();
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void sendsDefinitionsAndRoundTripsAssistantToolCallsAndResults() throws Exception {
        respond("application/json", CALL);
        try (var model = model(Duration.ofSeconds(5))) {
            var request = new ChatRequest(List.of(ChatMessage.user("天气？")), 100, List.of(WEATHER));
            var response = model.chat(request);
            assertEquals("", response.text());
            assertEquals("tool_calls", response.finishReason());
            assertEquals(List.of(new ContentBlock.ToolCall("call_a", "weather", "{\"city\":\"北京\"}")), response.toolCalls());
            var next = new ChatRequest(List.of(ChatMessage.user("天气？"), ChatMessage.assistantResponse(response),
                    ChatMessage.toolResult("call_a", "晴")), null, List.of(WEATHER));
            model.chat(next);
            JsonNode body = received.get(1);
            assertEquals("weather", body.at("/tools/0/function/name").textValue());
            assertEquals("object", body.at("/tools/0/function/parameters/type").textValue());
            assertEquals("city", body.at("/tools/0/function/parameters/required/0").textValue());
            assertEquals("function", body.at("/tools/0/type").textValue());
            assertTrue(body.at("/messages/1/content").isNull());
            assertEquals("call_a", body.at("/messages/1/tool_calls/0/id").textValue());
            assertEquals("{\"city\":\"北京\"}", body.at("/messages/1/tool_calls/0/function/arguments").textValue());
            assertEquals("tool", body.at("/messages/2/role").textValue());
            assertEquals("call_a", body.at("/messages/2/tool_call_id").textValue());
            assertEquals("晴", body.at("/messages/2/content").textValue());
        }
    }

    @Test void coldPublisherDeliversOnlyRequestedEventsAndEachSubscriptionHasIndependentState() throws Exception {
        respond("text/event-stream", STREAM);
        try (var model = model(Duration.ofSeconds(5))) {
            var publisher = model.stream(new ChatRequest(List.of(ChatMessage.user("天气？")), null, List.of(WEATHER)));
            Probe first = new Probe();
            publisher.subscribe(first);
            assertEquals(0, received.size());
            first.subscription.request(1);
            assertEquals(new ModelEvent.ToolCallStarted(0, "call_a", "weather"), first.next());
            assertNull(first.events.poll(100, TimeUnit.MILLISECONDS), "no over-delivery without demand");
            first.subscription.request(Long.MAX_VALUE);
            assertTrue(first.done.await(3, TimeUnit.SECONDS));
            assertNull(first.failure);
            var rest = new ArrayList<ModelEvent>();
            first.events.drainTo(rest);
            assertEquals(new ModelEvent.ToolCallDelta(0, "{\"city\":"), rest.get(0));
            assertEquals(new ModelEvent.ToolCallDelta(0, "\"北京\"}"), rest.get(1));
            assertInstanceOf(ModelEvent.ToolCallCompleted.class, rest.get(2));
            assertEquals(new ModelEvent.Usage(new TokenUsage(2, 3, 5)), rest.get(3));
            var finalResponse = ((ModelEvent.Completed) rest.get(4)).response();
            assertEquals("{\"city\":\"北京\"}", finalResponse.toolCalls().getFirst().arguments());
            Probe second = new Probe();
            publisher.subscribe(second);
            second.subscription.request(Long.MAX_VALUE);
            assertTrue(second.done.await(3, TimeUnit.SECONDS));
            assertNull(second.failure);
            assertEquals(2, received.size());
            assertEquals(new ModelEvent.ToolCallStarted(0, "call_a", "weather"), second.next());
        }
    }

    @Test void legacyTextCallbackRetainsToolsInFinalResponse() throws Exception {
        respond("text/event-stream", STREAM);
        try (var model = model(Duration.ofSeconds(5))) {
            var response = model.stream(new ChatRequest(List.of(ChatMessage.user("天气？"))),
                    text -> fail("Tool argument fragments are not text deltas"));
            assertEquals(1, response.toolCalls().size());
            assertEquals(5, response.usage().orElseThrow().totalTokens());
        }
    }

    @Test void cancellationBeforeDemandDoesNotCallServer() throws Exception {
        respond("text/event-stream", STREAM);
        try (var model = model(Duration.ofSeconds(5))) {
            var probe = new Probe();
            model.stream(new ChatRequest(List.of(ChatMessage.user("x")))).subscribe(probe);
            probe.subscription.cancel();
            probe.subscription.request(Long.MAX_VALUE);
            assertEquals(0, received.size());
            assertNull(probe.failure);
            assertTrue(probe.events.isEmpty());
        }
    }

    @Test void timeoutTerminatesEvenWhenConsumerDoesNotRequestRemainingEvents() throws Exception {
        respond("text/event-stream", STREAM);
        try (var model = model(Duration.ofMillis(700))) {
            var probe = new Probe();
            model.stream(new ChatRequest(List.of(ChatMessage.user("x")))).subscribe(probe);
            probe.subscription.request(1);
            assertInstanceOf(ModelEvent.ToolCallStarted.class, probe.next());
            assertTrue(probe.done.await(3, TimeUnit.SECONDS));
            assertInstanceOf(HttpTimeoutException.class, probe.failure);
            assertTrue(probe.events.isEmpty());
        }
    }

    @Test void closedModelSignalsErrorAfterOnSubscribeEvenWithoutDemand() throws Exception {
        var model = model(Duration.ofSeconds(5));
        var publisher = model.stream(new ChatRequest(List.of(ChatMessage.user("x"))));
        var idle = new Probe();
        publisher.subscribe(idle);
        model.close();
        assertTrue(idle.done.await(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, idle.failure);
        var late = new Probe();
        publisher.subscribe(late);
        assertNotNull(late.subscription);
        assertTrue(late.done.await(3, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, late.failure);
        assertEquals(0, received.size());
    }

    @Test void rejectsInvalidToolParametersBeforeSendingRequest() throws Exception {
        respond("application/json", CALL);
        try (var model = model(Duration.ofSeconds(5))) {
            var bad = new ToolDefinition("bad", "", "{\"type\":\"array\"}");
            var request = new ChatRequest(List.of(ChatMessage.user("x")), null, List.of(bad));
            assertThrows(IllegalArgumentException.class, () -> model.chat(request));
            var probe = new Probe();
            model.stream(request).subscribe(probe);
            probe.subscription.request(1);
            assertTrue(probe.done.await(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, probe.failure);
            assertEquals(0, received.size());
        }
    }

    private void respond(String type, String body) {
        server.createContext(endpoint.getPath(), exchange -> {
            try (exchange) {
                received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private OpenAiChatModel model(Duration timeout) {
        return new OpenAiChatModel(endpoint, "test-key", "test-model", timeout);
    }

    private static final class Probe implements Flow.Subscriber<ModelEvent> {
        final BlockingQueue<ModelEvent> events = new LinkedBlockingQueue<>();
        final CountDownLatch done = new CountDownLatch(1);
        Flow.Subscription subscription;
        volatile Throwable failure;
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; }
        @Override public void onNext(ModelEvent event) { events.add(event); }
        @Override public void onError(Throwable error) { failure = error; done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
        ModelEvent next() throws InterruptedException {
            ModelEvent event = events.poll(3, TimeUnit.SECONDS);
            assertNotNull(event, "Expected model event; error=" + failure);
            return event;
        }
    }
}
