package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.hi.neason.half.model.openai.OpenAiResponsesModelTest.*;

class OpenAiContentProtocolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:1/test");

    @Test void imagesFilesAndDeveloperRoleMapToEachProtocol() throws Exception {
        var request = new ChatRequest(List.of(ChatMessage.developer("brief"), new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.Text("Explain"), new ContentBlock.Image("https://example.org/a.png", "high"),
                        ContentBlock.File.byId("file_1"), ContentBlock.File.byData("a.pdf", "data:application/pdf;base64,AA==")), null)));
        try (var chat = chat(); var responses = responses()) {
            JsonNode a = MAPPER.readTree(chat.encodeRequest(request, false));
            JsonNode b = MAPPER.readTree(responses.encodeRequest(request, false));
            assertEquals("developer", a.at("/messages/0/role").asText());
            assertEquals("developer", b.at("/input/0/role").asText());
            assertEquals("text", a.at("/messages/1/content/0/type").asText());
            assertEquals("input_text", b.at("/input/1/content/0/type").asText());
            assertEquals("https://example.org/a.png", a.at("/messages/1/content/1/image_url/url").asText());
            assertEquals("high", a.at("/messages/1/content/1/image_url/detail").asText());
            assertEquals("https://example.org/a.png", b.at("/input/1/content/1/image_url").asText());
            assertEquals("file_1", a.at("/messages/1/content/2/file/file_id").asText());
            assertEquals("file_1", b.at("/input/1/content/2/file_id").asText());
            assertEquals("data:application/pdf;base64,AA==", b.at("/input/1/content/3/file_data").asText());
            assertEquals("a.pdf", a.at("/messages/1/content/3/file/filename").asText());
        }
    }

    @Test void validatesMediaAndAssistantOnlyOutputBlocks() {
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock.Image("file:///secret"));
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock.File("id", "name", "data"));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatMessage.Role.TOOL,
                List.of(new ContentBlock.Image("https://example.org/a")), "call"));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.Refusal("no")), null));
        var fragments = new ArrayList<>(List.of("summary"));
        var reasoning = new ContentBlock.Reasoning("r", fragments, List.of(), "cipher");
        fragments.clear(); assertEquals(List.of("summary"), reasoning.summary());
        assertThrows(UnsupportedOperationException.class, () -> reasoning.summary().clear());
    }

    @Test void chatRefusalHasItsOwnBlockAndRoundTrips() throws Exception {
        try (var model = chat()) {
            var response = model.decodeResponse("""
                    {"choices":[{"message":{"role":"assistant","content":null,"refusal":"不能回答"},"finish_reason":"stop"}]}
                    """);
            assertEquals("", response.text()); assertEquals("不能回答", response.refusal());
            var request = new ChatRequest(List.of(ChatMessage.assistantResponse(response)));
            JsonNode body = MAPPER.readTree(model.encodeRequest(request, false));
            assertEquals("不能回答", body.at("/messages/0/refusal").asText());
            assertTrue(body.at("/messages/0/content").isNull());
        }
    }

    @Test void chatRefusalStreamsWithoutContaminatingText() throws Exception {
        var decoder = new OpenAiEventDecoder(MAPPER);
        var events = decoder.accept("""
                {"choices":[{"index":0,"delta":{"refusal":"no"},"finish_reason":null}]}
                """);
        assertEquals(List.of(new ModelEvent.RefusalDelta("no")), events);
        decoder.accept("""
                {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                """);
        var result = ((ModelEvent.Completed) decoder.accept("[DONE]").getFirst()).response();
        assertEquals("no", result.refusal()); assertEquals("", result.text());
    }

    @Test void responsesRefusalStreamsAndRoundTrips() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(MAPPER);
        var message = message("m", ""); message.putArray("content"); message.put("status", "in_progress");
        decoder.accept(added(0, message).toString());
        var part = MAPPER.createObjectNode().put("type", "refusal").put("refusal", "");
        decoder.accept(textEvent("response.content_part.added").set("part", part).toString());
        assertEquals(List.of(new ModelEvent.RefusalDelta("no")), decoder.accept(textEvent("response.refusal.delta").put("delta", "no").toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(textEvent("response.output_text.delta").put("delta", "bad").toString()));
        decoder.accept(textEvent("response.refusal.done").put("refusal", "no").toString());
        part.put("refusal", "no");
        decoder.accept(textEvent("response.content_part.done").set("part", part).toString());
        message.put("status", "completed").withArray("content").add(part);
        decoder.accept(done(0, message).toString());
        var result = ((ModelEvent.Completed) decoder.accept(terminal(response(message)).toString()).getLast()).response();
        assertEquals("no", result.refusal()); assertEquals("", result.text());
        try (var model = responses()) {
            JsonNode body = MAPPER.readTree(model.encodeRequest(new ChatRequest(List.of(ChatMessage.assistantResponse(result))), false));
            assertEquals("refusal", body.at("/input/0/content/0/type").asText());
            assertEquals("no", body.at("/input/0/content/0/refusal").asText());
        }
    }

    @Test void reasoningIsPreservedAndReplayedWithEncryptedContent() throws Exception {
        ObjectNode reasoning = reasoning("summary", "cipher");
        try (var model = responses(); var chat = chat()) {
            ChatResponse result = model.decodeResponse(response(reasoning, message("m", "answer")).toString());
            assertEquals("answer", result.text());
            assertEquals(new ContentBlock.Reasoning("r", List.of("summary"), List.of(), "cipher"), result.reasoning().getFirst());
            var request = new ChatRequest(List.of(ChatMessage.assistantResponse(result), ChatMessage.user("next")));
            JsonNode body = MAPPER.readTree(model.encodeRequest(request, false));
            assertEquals("reasoning", body.at("/input/0/type").asText());
            assertEquals("cipher", body.at("/input/0/encrypted_content").asText());
            assertEquals("summary", body.at("/input/0/summary/0/text").asText());
            assertEquals("answer", body.at("/input/1/content/0/text").asText());
            assertThrows(IllegalArgumentException.class, () -> chat.encodeRequest(request, false));
        }
    }

    @Test void reasoningSummaryDeltaAndCompletedAreTypedAndChecked() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(MAPPER);
        var start = reasoning("", null); start.putArray("summary");
        decoder.accept(added(0, start).toString());
        var part = MAPPER.createObjectNode().put("type", "summary_text").put("text", "");
        decoder.accept(reasonEvent("response.reasoning_summary_part.added").set("part", part).toString());
        assertEquals(List.of(new ModelEvent.ReasoningDelta(0, 0, true, "thinking")), decoder.accept(reasonEvent("response.reasoning_summary_text.delta").put("delta", "thinking").toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(reasonEvent("response.reasoning_summary_text.done").put("text", "changed").toString()));
        decoder.accept(reasonEvent("response.reasoning_summary_text.done").put("text", "thinking").toString());
        part.put("text", "thinking");
        decoder.accept(reasonEvent("response.reasoning_summary_part.done").set("part", part).toString());
        ObjectNode finished = reasoning("thinking", "opaque");
        var result = decoder.accept(done(0, finished).toString());
        assertInstanceOf(ModelEvent.ReasoningCompleted.class, result.getFirst());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(terminal(response(reasoning("thinking", "changed"))).toString()));
        var complete = ((ModelEvent.Completed) decoder.accept(terminal(response(finished)).toString()).getLast()).response();
        assertEquals("opaque", complete.reasoning().getFirst().encryptedContent());
        assertEquals("", complete.text());
    }

    @Test void reasoningSummaryStartMayOmitEmptyTextButCompletionStillRequiresIt() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(MAPPER);
        var start = reasoning("", null); start.putArray("summary");
        decoder.accept(added(0, start).toString());
        var part = MAPPER.createObjectNode().put("type", "summary_text");
        for (var invalidText : List.of(MAPPER.nullNode(), MAPPER.getNodeFactory().numberNode(1),
                MAPPER.getNodeFactory().textNode("unexpected"))) {
            part.set("text", invalidText);
            assertThrows(ModelProtocolException.class, () -> decoder.accept(
                    reasonEvent("response.reasoning_summary_part.added").set("part", part).toString()));
        }
        part.remove("text");
        decoder.accept(reasonEvent("response.reasoning_summary_part.added").set("part", part).toString());
        assertEquals(List.of(new ModelEvent.ReasoningDelta(0, 0, true, "thinking")), decoder.accept(
                reasonEvent("response.reasoning_summary_text.delta").put("delta", "thinking").toString()));
        assertThrows(ModelProtocolException.class, () -> decoder.accept(
                reasonEvent("response.reasoning_summary_text.done").toString()));
        decoder.accept(reasonEvent("response.reasoning_summary_text.done").put("text", "thinking").toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(
                reasonEvent("response.reasoning_summary_part.done").set("part", part).toString()));
        part.put("text", "thinking");
        decoder.accept(reasonEvent("response.reasoning_summary_part.done").set("part", part).toString());
        var finished = reasoning("thinking", "opaque");
        decoder.accept(done(0, finished).toString());
        var result = ((ModelEvent.Completed) decoder.accept(terminal(response(finished)).toString()).getLast()).response();
        assertEquals(List.of("thinking"), result.reasoning().getFirst().summary());
    }

    @Test void functionCallStartMayOmitArgumentsButCompletionStillRequiresThem() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(MAPPER);
        var start = MAPPER.createObjectNode().put("type", "function_call").put("id", "fc")
                .put("call_id", "call").put("name", "weather").put("status", "in_progress");
        assertEquals(List.of(new ModelEvent.ToolCallStarted(0, "call", "weather")),
                decoder.accept(added(0, start).toString()));
        assertTrue(decoder.accept(toolEvent("delta", 0, "fc", "delta", "").toString()).isEmpty());
        assertEquals(List.of(new ModelEvent.ToolCallDelta(0, "{}")),
                decoder.accept(toolEvent("delta", 0, "fc", "delta", "{}").toString()));
        var completion = toolEvent("done", 0, "fc", "arguments", "{}");
        completion.remove("arguments");
        assertThrows(ModelProtocolException.class, () -> decoder.accept(completion.toString()));
        completion.put("arguments", "{}");
        decoder.accept(completion.toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(done(0, start).toString()));
        var finished = start.deepCopy().put("status", "completed").put("arguments", "{}");
        assertInstanceOf(ModelEvent.ToolCallCompleted.class, decoder.accept(done(0, finished).toString()).getFirst());
        var result = ((ModelEvent.Completed) decoder.accept(terminal(response(finished)).toString()).getLast()).response();
        assertEquals(List.of(new ContentBlock.ToolCall("call", "weather", "{}")), result.toolCalls());
        for (var invalidText : List.of(MAPPER.nullNode(), MAPPER.getNodeFactory().numberNode(1))) {
            var invalidStart = start.deepCopy().set("arguments", invalidText);
            assertThrows(ModelProtocolException.class, () -> new OpenAiResponsesEventDecoder(MAPPER)
                    .accept(added(0, invalidStart).toString()));
        }
    }

    @Test void malformedReasoningAndOversizedCipherAreRejected() throws Exception {
        try (var model = responses()) {
            ObjectNode bad = reasoning("x", "cipher"); ((ObjectNode) bad.at("/summary/0")).put("type", "wrong");
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(bad).toString()));
            assertThrows(ModelProtocolException.class, () -> model.decodeResponse(response(reasoning("x", "a".repeat(4 * 1024 * 1024))).toString()));
        }
    }

    @Test void modelRequestDeadlineIncludesResponseBody() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try (var model = new OpenAiResponsesModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "test", "test", Duration.ofMillis(300))) {
            assertThrows(java.net.http.HttpTimeoutException.class,
                    () -> model.chat(new ChatRequest(List.of(ChatMessage.user("hello")))));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test void replaysEntireOutputMessageInsteadOfReconstructingIncompleteUnion() throws Exception {
        ObjectNode output = message("msg_original", "answer");
        ((ObjectNode) output.at("/content/0")).putArray("logprobs");
        ((ObjectNode) output.at("/content/0")).withArray("annotations").addObject()
                .put("type", "url_citation").put("url", "https://example.org").put("title", "source")
                .put("start_index", 0).put("end_index", 6);
        output.withArray("content").addObject().put("type", "refusal").put("refusal", "declined detail");
        try (var model = responses()) {
            ChatResponse decoded = model.decodeResponse(response(output).toString());
            var history = ChatMessage.assistantResponse(decoded);
            JsonNode encoded = MAPPER.readTree(model.encodeRequest(new ChatRequest(List.of(history)), false));
            assertEquals(output, encoded.at("/input/0"), "all official output item fields must survive replay");
            var altered = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.Text("changed")), null, decoded.outputItemsJson());
            assertThrows(IllegalArgumentException.class, () -> model.encodeRequest(new ChatRequest(List.of(altered)), false));
            assertThrows(IllegalArgumentException.class, () -> model.encodeRequest(new ChatRequest(List.of(
                    new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.Refusal("no metadata")), null))), false));
            var synthetic = new ChatMessage(ChatMessage.Role.ASSISTANT,
                    List.of(new ContentBlock.Text("one"), new ContentBlock.Text("two")), null);
            var syntheticBody = MAPPER.readTree(model.encodeRequest(new ChatRequest(List.of(synthetic)), false));
            assertEquals("input_text", syntheticBody.at("/input/0/content/0/type").asText());
        }
    }

    @Test void observedReasoningPartsMustFinishUnlessResponseIsIncomplete() throws Exception {
        var decoder = new OpenAiResponsesEventDecoder(MAPPER);
        var start = reasoning("", null); start.putArray("summary");
        decoder.accept(added(0, start).toString());
        decoder.accept(reasonEvent("response.reasoning_summary_part.added").set("part",
                MAPPER.createObjectNode().put("type", "summary_text").put("text", "")).toString());
        decoder.accept(reasonEvent("response.reasoning_summary_text.delta").put("delta", "partial").toString());
        assertThrows(ModelProtocolException.class, () -> decoder.accept(done(0, reasoning("partial", "cipher")).toString()));
        ObjectNode partial = reasoning("partial", "cipher").put("status", "incomplete");
        assertTrue(decoder.accept(done(0, partial).toString()).isEmpty());
        ObjectNode incomplete = response(partial).put("status", "incomplete");
        incomplete.putObject("incomplete_details").put("reason", "max_output_tokens");
        var result = decoder.accept(event("response.incomplete").set("response", incomplete).toString());
        assertEquals("max_output_tokens", ((ModelEvent.Completed) result.getLast()).response().finishReason());
    }

    private static ObjectNode reasonEvent(String type) { return event(type).put("item_id", "r").put("output_index", 0).put("summary_index", 0); }
    private static ObjectNode reasoning(String summary, String cipher) {
        var value = MAPPER.createObjectNode().put("type", "reasoning").put("id", "r");
        value.putArray("summary").addObject().put("type", "summary_text").put("text", summary);
        if (cipher != null) value.put("encrypted_content", cipher);
        return value;
    }
    private static OpenAiChatModel chat() { return new OpenAiChatModel(ENDPOINT, "test", "test", Duration.ofSeconds(1)); }
    private static OpenAiResponsesModel responses() { return new OpenAiResponsesModel(ENDPOINT, "test", "test", Duration.ofSeconds(1)); }
}
