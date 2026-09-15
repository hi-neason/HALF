package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiRequestOptionsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<ChatMessage> MESSAGES = List.of(ChatMessage.user("Return JSON"));
    private static final ToolDefinition TOOL = new ToolDefinition("lookup", "", "{\"type\":\"object\"}");

    @Test
    void defaultsKeepExistingRequestBodiesAndConstructors() throws Exception {
        for (boolean responses : List.of(false, true)) {
            var body = encode(responses, new ChatRequest(MESSAGES, null, List.of(TOOL)));
            for (var key : List.of("temperature", "top_p", "tool_choice", "parallel_tool_calls",
                    "response_format", "text", "reasoning", "reasoning_effort", "verbosity", "include")) {
                assertFalse(body.has(key), key);
            }
            var tool = responses ? body.path("tools").get(0) : body.path("tools").get(0).path("function");
            if (responses) assertEquals(false, tool.path("strict").booleanValue());
            else assertFalse(tool.has("strict"));
        }
    }

    @Test
    void commonOptionsHaveProtocolSpecificShapesInBothStreamingModes() throws Exception {
        var schema = new ResponseFormat.JsonSchema("result", "A result", "{\"type\":\"object\"}", true);
        var options = ModelOptions.builder().temperature(0.3).topP(0.9)
                .toolChoice(new ToolChoice.Function("lookup")).parallelToolCalls(false)
                .responseFormat(schema).reasoningEffort(ModelOptions.ReasoningEffort.HIGH)
                .verbosity(ModelOptions.Verbosity.LOW).build();
        var request = new ChatRequest(MESSAGES, 100, List.of(new ToolDefinition("lookup", "", TOOL.parametersJson(), true)), options);
        for (boolean responses : List.of(false, true)) {
            for (boolean streaming : List.of(false, true)) {
                var body = encode(responses, request, streaming);
                assertEquals(streaming, body.path("stream").booleanValue());
                assertEquals(0.3, body.path("temperature").doubleValue());
                assertEquals(0.9, body.path("top_p").doubleValue());
                assertFalse(body.path("parallel_tool_calls").booleanValue());
                assertEquals("lookup", (responses ? body.path("tool_choice") : body.path("tool_choice").path("function")).path("name").textValue());
                assertEquals("high", responses ? body.path("reasoning").path("effort").textValue() : body.path("reasoning_effort").textValue());
                assertEquals("low", (responses ? body.path("text") : body).path("verbosity").textValue());
                var format = responses ? body.path("text").path("format") : body.path("response_format");
                assertEquals("json_schema", format.path("type").textValue());
                var spec = responses ? format : format.path("json_schema");
                assertEquals("result", spec.path("name").textValue());
                assertEquals("A result", spec.path("description").textValue());
                assertEquals(JSON.readTree(schema.schemaJson()), spec.path("schema"));
                assertTrue(spec.path("strict").booleanValue());
                var tool = responses ? body.path("tools").get(0) : body.path("tools").get(0).path("function");
                assertTrue(tool.path("strict").booleanValue());
            }
        }
    }

    @Test
    void supportsModeChoicesAndPlainAndJsonObjectFormats() throws Exception {
        for (boolean responses : List.of(false, true)) {
            for (var mode : ToolChoice.Mode.values()) {
                var body = encode(responses, request(ModelOptions.builder().toolChoice(mode).build()));
                assertEquals(mode.name().toLowerCase(java.util.Locale.ROOT), body.path("tool_choice").textValue());
            }
            for (ResponseFormat format : List.of(new ResponseFormat.Text(), new ResponseFormat.JsonObject())) {
                var body = encode(responses, request(ModelOptions.builder().responseFormat(format).build()));
                var encoded = responses ? body.path("text").path("format") : body.path("response_format");
                assertEquals(format instanceof ResponseFormat.Text ? "text" : "json_object", encoded.path("type").textValue());
            }
        }
    }

    @Test
    void responsesReasoningSummaryAndEncryptedContentAreExplicit() throws Exception {
        var options = ModelOptions.builder().reasoningSummary(ModelOptions.ReasoningSummary.DETAILED)
                .includeEncryptedReasoning(true).build();
        var body = encode(true, request(options));
        assertEquals("detailed", body.path("reasoning").path("summary").textValue());
        assertFalse(body.path("reasoning").has("effort"));
        assertEquals("reasoning.encrypted_content", body.path("include").get(0).textValue());
        assertThrows(IllegalArgumentException.class, () -> encode(false, request(options)));
        var disabled = request(ModelOptions.builder().includeEncryptedReasoning(false).build());
        assertFalse(encode(true, disabled).has("include"));
        assertThrows(IllegalArgumentException.class, () -> encode(false, disabled));
    }

    @Test
    void validatesParameterRangesAndToolSelection() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 2.1}) {
            assertThrows(IllegalArgumentException.class, () -> ModelOptions.builder().temperature(invalid).build());
        }
        for (double invalid : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, -0.1, 1.1}) {
            assertThrows(IllegalArgumentException.class, () -> ModelOptions.builder().topP(invalid).build());
        }
        assertDoesNotThrow(() -> ModelOptions.builder().temperature(0.0).topP(0.0).build());
        assertDoesNotThrow(() -> ModelOptions.builder().temperature(2.0).topP(1.0).build());
        assertThrows(IllegalArgumentException.class, () -> new ToolChoice.Function("bad name"));
        assertThrows(IllegalArgumentException.class, () -> new ResponseFormat.JsonSchema("bad name", null, "{}", null));
        assertThrows(NullPointerException.class, () -> new ChatRequest(MESSAGES, null, List.of(), null));
        for (boolean responses : List.of(false, true)) {
            assertThrows(IllegalArgumentException.class, () -> encode(responses,
                    request(ModelOptions.builder().toolChoice(new ToolChoice.Function("missing")).build())));
            assertThrows(IllegalArgumentException.class, () -> encode(responses,
                    new ChatRequest(MESSAGES, null, List.of(), ModelOptions.builder().toolChoice(ToolChoice.Mode.REQUIRED).build())));
        }
    }

    @Test
    void rejectsMalformedSchemasAndDoesNotInventStrictMode() throws Exception {
        for (boolean responses : List.of(false, true)) {
            for (String schema : List.of("invalid", "[]", "null", "{} {}")) {
                assertThrows(IllegalArgumentException.class, () -> encode(responses, request(ModelOptions.builder()
                        .responseFormat(new ResponseFormat.JsonSchema("result", null, schema, null)).build())));
            }
            var body = encode(responses, request(ModelOptions.builder().responseFormat(
                    new ResponseFormat.JsonSchema("result", null, "{}", null)).build()));
            var schema = responses ? body.path("text").path("format") : body.path("response_format").path("json_schema");
            assertFalse(schema.has("strict"));
            assertFalse(schema.has("description"));
        }
    }

    private static ChatRequest request(ModelOptions options) {
        return new ChatRequest(MESSAGES, null, List.of(TOOL), options);
    }

    private static JsonNode encode(boolean responses, ChatRequest request) throws Exception {
        return encode(responses, request, false);
    }

    private static JsonNode encode(boolean responses, ChatRequest request, boolean streaming) throws Exception {
        try (OpenAiHttpModel model = responses
                ? new OpenAiResponsesModel(URI.create("http://localhost/v1/responses"), "unused", "model", Duration.ofSeconds(1))
                : new OpenAiChatModel(URI.create("http://localhost/v1/chat/completions"), "unused", "model", Duration.ofSeconds(1))) {
            return JSON.readTree(model.encodeRequest(request, streaming));
        }
    }
}
