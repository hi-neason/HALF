package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiResponsesModelTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final ChatRequest REQUEST = new ChatRequest(List.of(ChatMessage.user("你好")));
    static final ToolDefinition TOOL = new ToolDefinition("weather", "天气", "{\"type\":\"object\",\"properties\":{}}");
    HttpServer server;
    URI endpoint;
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void encodesResponsesHistoryToolsAndTokenLimit() throws Exception {
        respond(200, "application/json", response(message("m", "你好"), call("fc", "call_1", "{}" )).toString());
        try (var model = model()) {
            var first = model.chat(new ChatRequest(List.of(ChatMessage.system("简短"), ChatMessage.user("天气")), 42, List.of(TOOL)));
            assertEquals("你好", first.text());
            assertEquals("completed", first.finishReason());
            assertEquals(new TokenUsage(2, 3, 5), first.usage().orElseThrow());
            assertEquals("call_1", first.toolCalls().get(0).id());
            model.chat(new ChatRequest(List.of(ChatMessage.system("简短"), ChatMessage.user("天气"),
                    ChatMessage.assistantResponse(first), ChatMessage.toolResult("call_1", "晴")), 42, List.of(TOOL)));
            JsonNode body = requests.get(1);
            assertEquals("test-model", body.path("model").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertFalse(body.path("stream").asBoolean(true));
            assertEquals(42, body.path("max_output_tokens").asInt());
            assertFalse(body.has("messages")); assertFalse(body.has("stream_options"));
            assertEquals("system", body.at("/input/0/role").asText());
            assertEquals("assistant", body.at("/input/2/role").asText());
            assertEquals("function_call", body.at("/input/3/type").asText());
            assertEquals("call_1", body.at("/input/3/call_id").asText());
            assertFalse(body.at("/input/3").has("id"));
            assertEquals("function_call_output", body.at("/input/4/type").asText());
            assertEquals("call_1", body.at("/input/4/call_id").asText());
            assertEquals("晴", body.at("/input/4/output").asText());
            assertEquals("weather", body.at("/tools/0/name").asText());
            assertEquals("object", body.at("/tools/0/parameters/type").asText());
            assertFalse(body.at("/tools/0/strict").asBoolean(true));
            assertFalse(body.at("/tools/0").has("function"));
        }
    }

    @Test void publisherIsColdIndependentAndDemandControlled() throws Exception {
        respond(200, "text/event-stream; charset=utf-8", sse(textEvents()));
        try (var model = model()) {
            var publisher = model.stream(REQUEST);
            Probe a = new Probe(); publisher.subscribe(a);
            assertTrue(requests.isEmpty());
            a.subscription.request(1);
            assertEquals(new ModelEvent.TextDelta("你好"), a.next());
            assertNull(a.events.poll(70, TimeUnit.MILLISECONDS));
            a.subscription.request(2);
            assertInstanceOf(ModelEvent.Usage.class, a.next());
            assertEquals("你好", ((ModelEvent.Completed) a.next()).response().text());
            assertTrue(a.done.await(2, TimeUnit.SECONDS)); assertNull(a.error);
            Probe b = new Probe(); publisher.subscribe(b); b.subscription.request(Long.MAX_VALUE);
            assertTrue(b.done.await(2, TimeUnit.SECONDS)); assertNull(b.error);
            assertEquals(2, requests.size());
            assertTrue(requests.get(0).path("stream").asBoolean());
            assertEquals(3, b.events.size());
        }
    }

    @Test void textCallbackUsesResponsesAndReceivesIncrementBeforeTerminalFrame() throws Exception {
        CountDownLatch firstText = new CountDownLatch(1);
        List<ObjectNode> events = textEvents();
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(sse(events.subList(0, 4)).getBytes(StandardCharsets.UTF_8)); out.flush();
                if (!firstText.await(2, TimeUnit.SECONDS)) return;
                out.write(sse(events.subList(4, events.size())).getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try (var model = model()) {
            var text = new StringBuilder();
            ChatResponse result = model.stream(REQUEST, delta -> { text.append(delta); firstText.countDown(); });
            assertEquals("你好", text.toString()); assertEquals("你好", result.text());
        }
    }

    @Test void interleavedCallsUseCallIdAndFinishExactlyOnce() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        List<ModelEvent> actual = new ArrayList<>();
        actual.addAll(decoder.accept(added(0, call("fc_a", "call_a", "")).toString()));
        actual.addAll(decoder.accept(added(1, call("fc_b", "call_b", "")).toString()));
        actual.addAll(decoder.accept(toolEvent("delta", 1, "fc_b", "delta", "{\"b\":").toString()));
        actual.addAll(decoder.accept(toolEvent("delta", 0, "fc_a", "delta", "{}").toString()));
        actual.addAll(decoder.accept(toolEvent("delta", 1, "fc_b", "delta", "2}").toString()));
        for (int i = 0; i < 2; i++) {
            String suffix = i == 0 ? "a" : "b", args = i == 0 ? "{}" : "{\"b\":2}";
            actual.addAll(decoder.accept(toolEvent("done", i, "fc_" + suffix, "arguments", args).toString()));
            actual.addAll(decoder.accept(done(i, call("fc_" + suffix, "call_" + suffix, args)).toString()));
        }
        actual.addAll(decoder.accept(terminal(response(call("fc_a", "call_a", "{}"), call("fc_b", "call_b", "{\"b\":2}"))).toString()));
        assertEquals(new ModelEvent.ToolCallStarted(0, "call_a", "weather"), actual.get(0));
        assertEquals(2, actual.stream().filter(ModelEvent.ToolCallCompleted.class::isInstance).count());
        ChatResponse result = ((ModelEvent.Completed) actual.getLast()).response();
        assertEquals(List.of(new ContentBlock.ToolCall("call_a", "weather", "{}"),
                new ContentBlock.ToolCall("call_b", "weather", "{\"b\":2}")), result.toolCalls());
    }

    @Test void acceptsIncompleteTextButNeverIncompleteTools() throws Exception {
        ObjectNode partial = response(message("m", "半"));
        partial.put("status", "incomplete").putObject("incomplete_details").put("reason", "max_output_tokens");
        try (var model = model()) {
            assertEquals("max_output_tokens", model.decodeResponse(partial.toString()).finishReason());
            partial.withArray("output").add(call("fc", "c", "{}"));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(partial.toString()));
        }
        var stream = new OpenAiResponsesEventDecoder(JSON);
        List<ObjectNode> events = textEvents();
        for (ObjectNode event : events.subList(0, 4)) stream.accept(event.toString());
        ObjectNode end = response(message("m", "你好"));
        end.put("status", "incomplete").putObject("incomplete_details").put("reason", "max_output_tokens");
        var result = stream.accept(event("response.incomplete").set("response", end).toString());
        assertEquals("max_output_tokens", ((ModelEvent.Completed) result.getLast()).response().finishReason());
    }

    @Test void rejectsBadIdentityArgumentsAndFinalSnapshots() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        decoder.accept(added(0, call("fc", "c", "")).toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(toolEvent("delta", 0, "wrong", "delta", "{}").toString()));
        decoder.accept(toolEvent("delta", 0, "fc", "delta", "{}").toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(toolEvent("done", 0, "fc", "arguments", "{\"x\":1}").toString()));
        decoder.accept(toolEvent("done", 0, "fc", "arguments", "{}").toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(done(0, call("fc", "different", "{}")).toString()));
        var textDecoder = new OpenAiResponsesEventDecoder(JSON);
        for (ObjectNode e : textEvents().subList(0, 7)) textDecoder.accept(e.toString());
        assertThrows(ModelProtocolException.class, () -> textDecoder.accept(terminal(response(message("m", "替换"))).toString()));
    }

    @Test void rejectsLateDeltasDuplicateItemsAndMissingLifecycle() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        List<ObjectNode> events = textEvents();
        for (ObjectNode e : events.subList(0, 5)) decoder.accept(e.toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(events.get(3).toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(events.get(1).toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(events.getLast().toString()));
        assertThrows(ModelProtocolException.class, () -> new OpenAiResponsesEventDecoder(JSON).accept("[DONE]"));
    }

    @Test void rejectsFailuresRefusalsUnknownOutputAndMalformedUsage() throws Exception {
        try (var model = model()) {
            ObjectNode failed = response().put("status", "failed");
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(failed.toString()));
            ObjectNode refusal = message("m", "");
            ((ObjectNode) refusal.at("/content/0")).put("type", "refusal");
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(refusal).toString()));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(call("fc", "c", "[]")).toString()));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(call("a", "c", "{}"), call("b", "c", "{}")).toString()));
            ObjectNode badUsage = response(message("m", "x"));
            ((ObjectNode) badUsage.get("usage")).put("input_tokens", -1);
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(badUsage.toString()));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response().toString() + " {}"));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(JSON.createObjectNode().put("id", "w").put("type", "web_search_call")).toString()));
        }
        for (String type : List.of("error", "response.failed", "response.refusal.delta", "unknown")) {
            assertThrows(ModelProtocolException.class, () -> new OpenAiResponsesEventDecoder(JSON).accept(event(type).put("message", "secret").toString()));
        }
    }

    @Test void ignoresReasoningWithoutLeakingItIntoVisibleText() throws Exception {
        ObjectNode reasoning = JSON.createObjectNode().put("type", "reasoning").put("id", "r");
        reasoning.putArray("summary");
        try (var model = model()) { assertEquals("hi", model.decodeResponse(response(reasoning, message("m", "hi")).toString()).text()); }
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        assertTrue(decoder.accept(added(0, reasoning).toString()).isEmpty());
        assertTrue(decoder.accept(done(0, reasoning).toString()).isEmpty());
        assertInstanceOf(ModelEvent.Completed.class, decoder.accept(terminal(response(reasoning)).toString()).getLast());
    }

    @Test void checksHttpStatusAndDoesNotExposeResponseSecrets() throws Exception {
        respond(401, "application/json", "secret-body");
        try (var model = model()) {
            assertThrows(ModelHttpException.class, () -> model.chat(REQUEST));
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(1);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(ModelHttpException.class, p.error);
            assertFalse(p.error.toString().contains("secret-body"));
        }
    }

    @Test void rejectsWrongContentTypeAndTruncatedSse() throws Exception {
        respond(200, "application/json", response().toString());
        try (var model = model()) {
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(1);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(ModelProtocolException.class, p.error);
        }
        server.removeContext("/v1/responses");
        respond(200, "text/event-stream", sse(textEvents().subList(0, 4)));
        try (var model = model()) {
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(Long.MAX_VALUE);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(ModelProtocolException.class, p.error);
            assertTrue(p.events.stream().noneMatch(ModelEvent.Completed.class::isInstance));
        }
    }

    @Test void cancelBeforeDemandAndCloseIdleSubscriptions() throws Exception {
        respond(200, "text/event-stream", sse(textEvents()));
        var model = model();
        Probe cancelled = new Probe(); model.stream(REQUEST).subscribe(cancelled);
        cancelled.subscription.cancel(); cancelled.subscription.request(1);
        assertTrue(requests.isEmpty()); assertEquals(1, cancelled.done.getCount());
        Probe idle = new Probe(); model.stream(REQUEST).subscribe(idle);
        model.close();
        assertTrue(idle.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(IOException.class, idle.error);
        Probe late = new Probe(); model.stream(REQUEST).subscribe(late);
        assertTrue(late.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(IOException.class, late.error);
        assertThrows(IOException.class, () -> model.chat(REQUEST));
    }

    @Test void timeoutStillAppliesWhenSubscriberStopsRequesting() throws Exception {
        respond(200, "text/event-stream", sse(textEvents()));
        try (var model = new OpenAiResponsesModel(endpoint, "test-key", "test-model", Duration.ofMillis(300))) {
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(1);
            assertInstanceOf(ModelEvent.TextDelta.class, p.next());
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(HttpTimeoutException.class, p.error);
        }
    }

    @Test void rejectsInvalidSchemaBeforeNetworkAndInvalidDemand() throws Exception {
        try (var model = model()) {
            var bad = new ChatRequest(REQUEST.messages(), null, List.of(new ToolDefinition("x", "x", "[]")));
            assertThrows(IllegalArgumentException.class, () -> model.chat(bad));
            Probe p = new Probe(); model.stream(bad).subscribe(p); p.subscription.request(1);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(IllegalArgumentException.class, p.error);
            Probe invalid = new Probe(); model.stream(REQUEST).subscribe(invalid); invalid.subscription.request(0);
            assertTrue(invalid.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(IllegalArgumentException.class, invalid.error);
            assertTrue(requests.isEmpty());
        }
    }

    @Test void enforcesOutputAndStreamBounds() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        for (int i = 0; i < 64; i++) decoder.accept(added(i, call("f" + i, "c" + i, "")).toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(added(64, call("f64", "c64", "")).toString()));
        var textDecoder = new OpenAiResponsesEventDecoder(JSON);
        for (ObjectNode e : textEvents().subList(0, 3)) textDecoder.accept(e.toString());
        ObjectNode huge = textEvents().get(3).put("delta", "x".repeat(4 * 1024 * 1024));
        assertThrows(ModelProtocolException.class, () -> textDecoder.accept(huge.toString()));
    }

    @Test void streamsToolCallsOverHttpAndLegacyCallbackReturnsThem() throws Exception {
        var events = List.of(added(0, call("fc", "call_1", "")),
                toolEvent("delta", 0, "fc", "delta", "{}"),
                toolEvent("done", 0, "fc", "arguments", "{}"),
                done(0, call("fc", "call_1", "{}")), terminal(response(call("fc", "call_1", "{}"))));
        respond(200, "text/event-stream", sse(events));
        try (var model = model()) {
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(Long.MAX_VALUE);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertNull(p.error);
            assertEquals(5, p.events.size());
            assertEquals(new ModelEvent.ToolCallStarted(0, "call_1", "weather"), p.next());
            var text = new StringBuilder();
            ChatResponse result = model.stream(REQUEST, text::append);
            assertEquals("", text.toString());
            assertEquals(List.of(new ContentBlock.ToolCall("call_1", "weather", "{}")), result.toolCalls());
        }
    }

    @Test void preservesMultipleTextPartsWithoutDuplicatingDoneSnapshots() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        List<ModelEvent> received = new ArrayList<>();
        var events = textEvents();
        for (ObjectNode e : events.subList(0, 6)) received.addAll(decoder.accept(e.toString()));
        for (ObjectNode original : events.subList(2, 6)) {
            ObjectNode e = original.deepCopy().put("content_index", 1);
            if (e.has("delta")) e.put("delta", "world");
            if (e.has("text")) e.put("text", "world");
            if (e.has("part") && e.path("type").asText().endsWith("done")) ((ObjectNode) e.get("part")).put("text", "world");
            received.addAll(decoder.accept(e.toString()));
        }
        ObjectNode output = message("m", "你好");
        output.withArray("content").add(message("m", "world").at("/content/0"));
        decoder.accept(done(0, output).toString());
        received.addAll(decoder.accept(terminal(response(output)).toString()));
        assertEquals(2, received.stream().filter(ModelEvent.TextDelta.class::isInstance).count());
        ChatResponse result = ((ModelEvent.Completed) received.getLast()).response();
        assertEquals("你好world", result.text()); assertEquals(2, result.content().size());
    }

    @Test void streamFailureAfterPartialTextNeverSignalsSuccess() throws Exception {
        var events = new ArrayList<>(textEvents().subList(0, 4));
        events.add(event("response.failed").set("response", response().put("status", "failed")));
        respond(200, "text/event-stream", sse(events));
        try (var model = model()) {
            Probe p = new Probe(); model.stream(REQUEST).subscribe(p); p.subscription.request(Long.MAX_VALUE);
            assertTrue(p.done.await(2, TimeUnit.SECONDS)); assertInstanceOf(ModelProtocolException.class, p.error);
            assertEquals(List.of(new ModelEvent.TextDelta("你好")), new ArrayList<>(p.events));
        }
    }

    @Test void rejectsContradictoryStatusesAndChangedResponseIdentity() throws Exception {
        try (var model = model()) {
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(message("m", "x").put("status", "incomplete")).toString()));
        }
        var decoder = new OpenAiResponsesEventDecoder(JSON);
        for (ObjectNode event : textEvents().subList(0, 7)) decoder.accept(event.toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(terminal(response(message("m", "你好")).put("id", "other")).toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(terminal(response(message("m", "你好")).put("status", "incomplete")).toString()));
    }

    OpenAiResponsesModel model() { return new OpenAiResponsesModel(endpoint, "test-key", "test-model", Duration.ofSeconds(5)); }
    void respond(int status, String type, String body) {
        server.createContext("/v1/responses", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
    }
    static ObjectNode event(String type) { return JSON.createObjectNode().put("type", type); }
    static ObjectNode response(JsonNode... items) {
        var root = JSON.createObjectNode().put("object", "response").put("id", "resp").put("status", "completed");
        var output = root.putArray("output"); for (JsonNode item : items) output.add(item);
        root.putObject("usage").put("input_tokens", 2).put("output_tokens", 3).put("total_tokens", 5);
        return root;
    }
    static ObjectNode message(String id, String text) {
        var item = JSON.createObjectNode().put("type", "message").put("id", id).put("role", "assistant").put("status", "completed");
        item.putArray("content").addObject().put("type", "output_text").put("text", text).putArray("annotations");
        return item;
    }
    static ObjectNode call(String id, String callId, String args) {
        return JSON.createObjectNode().put("type", "function_call").put("id", id).put("call_id", callId).put("name", "weather").put("arguments", args);
    }
    static ObjectNode added(int index, JsonNode item) { return event("response.output_item.added").put("output_index", index).set("item", item); }
    static ObjectNode done(int index, JsonNode item) { return event("response.output_item.done").put("output_index", index).set("item", item); }
    static ObjectNode terminal(JsonNode response) { return event("response.completed").set("response", response); }
    static ObjectNode toolEvent(String suffix, int index, String id, String field, String value) {
        return event("response.function_call_arguments." + suffix).put("output_index", index).put("item_id", id).put(field, value);
    }
    static ObjectNode textEvent(String type) { return event(type).put("output_index", 0).put("content_index", 0).put("item_id", "m"); }
    static List<ObjectNode> textEvents() {
        var start = message("m", "").put("status", "in_progress"); start.putArray("content");
        return List.of(event("response.created").set("response", response().put("status", "in_progress")),
                added(0, start), textEvent("response.content_part.added").set("part", JSON.createObjectNode().put("type", "output_text").put("text", "")),
                textEvent("response.output_text.delta").put("delta", "你好"),
                textEvent("response.output_text.done").put("text", "你好"),
                textEvent("response.content_part.done").set("part", message("m", "你好").at("/content/0")),
                done(0, message("m", "你好")), terminal(response(message("m", "你好"))));
    }
    static String sse(List<ObjectNode> events) {
        var out = new StringBuilder();
        for (ObjectNode event : events) out.append("event: ").append(event.path("type").asText()).append("\r\ndata: ").append(event).append("\r\n\r\n");
        return out.toString();
    }
    static class Probe implements Flow.Subscriber<ModelEvent> {
        final BlockingQueue<ModelEvent> events = new LinkedBlockingQueue<>();
        final CountDownLatch done = new CountDownLatch(1);
        Flow.Subscription subscription;
        volatile Throwable error;
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; }
        public void onNext(ModelEvent event) { events.add(event); }
        public void onError(Throwable error) { this.error = error; done.countDown(); }
        public void onComplete() { done.countDown(); }
        ModelEvent next() throws Exception { ModelEvent event = events.poll(2, TimeUnit.SECONDS); assertNotNull(event); return event; }
    }
}
