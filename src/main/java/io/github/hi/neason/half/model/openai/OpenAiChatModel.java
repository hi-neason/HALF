package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** OpenAI Chat Completions 协议的文本和工具调用适配器。 */
public final class OpenAiChatModel extends OpenAiHttpModel {
    /** endpoint 为完整的 /v1/chat/completions URL。 */
    public OpenAiChatModel(URI endpoint, String apiKey, String model, Duration timeout) {
        super(endpoint, apiKey, model, timeout);
    }

    @Override
    OpenAiStream.EventDecoder newEventDecoder() { return new OpenAiEventDecoder(json)::accept; }

    @Override
    String encodeRequest(ChatRequest request, boolean streaming) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("model", model);
        root.put("stream", streaming);
        if (streaming) {
            root.putObject("stream_options").put("include_usage", true);
        }
        OpenAiContent.chat(json, root.putArray("messages"), request.messages());
        if (request.maxOutputTokens() != null) {
            root.put("max_completion_tokens", request.maxOutputTokens());
        }
        if (!request.tools().isEmpty()) {
            var encodedTools = root.putArray("tools");
            for (var definition : request.tools()) {
                JsonNode schema;
                try {
                    schema = json.readTree(definition.parametersJson());
                } catch (JsonProcessingException error) {
                    throw new IllegalArgumentException("Tool parameters must be valid JSON");
                }
                if (schema == null || !schema.isObject()) {
                    throw new IllegalArgumentException("Tool parameters must be an object JSON Schema");
                }
                if (!"object".equals(schema.path("type").asText())) {
                    throw new IllegalArgumentException("Tool parameters must be an object JSON Schema");
                }
                var function = encodedTools.addObject().put("type", "function").putObject("function");
                function.put("name", definition.name()).put("description", definition.description());
                function.set("parameters", schema);
                if (definition.strict() != null) function.put("strict", definition.strict());
            }
        }
        OpenAiRequestOptions.apply(json, root, request, false);
        return json.writeValueAsString(root);
    }

    @Override
    ChatResponse decodeResponse(String body) throws ModelProtocolException {
        JsonNode root = OpenAiJson.decodeObject(json, body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            throw new ModelProtocolException("Expected exactly one completion choice");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!"assistant".equals(message.path("role").asText())) {
            throw new ModelProtocolException("Expected an assistant message");
        }
        rejectUnsupported(message);
        List<ContentBlock> content = new ArrayList<>();
        JsonNode calls = message.get("tool_calls");
        var ids = new HashSet<String>();
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray() || calls.size() > 64) throw new ModelProtocolException("Invalid tool calls array");
            for (JsonNode call : calls) {
                if (!"function".equals(call.path("type").asText())) throw new ModelProtocolException("Expected function tool");
                String id = requiredText(call, "id");
                String name = requiredText(call.path("function"), "name");
                String arguments = requiredText(call.path("function"), "arguments");
                if (id.isBlank() || name.isBlank() || !ids.add(id)) throw new ModelProtocolException("Invalid tool identity");
                OpenAiJson.requireArguments(json, arguments);
                content.add(new ContentBlock.ToolCall(id, name, arguments));
            }
        }
        if (message.hasNonNull("refusal")) content.add(new ContentBlock.Refusal(requiredText(message, "refusal")));
        JsonNode text = message.get("content");
        if (text != null && text.isTextual()) content.add(0, new ContentBlock.Text(text.textValue()));
        else if (content.isEmpty() || text != null && !text.isNull()) {
            throw new ModelProtocolException("Expected text or tool calls");
        }
        String finish = readFinishReason(choice);
        if ((!ids.isEmpty()) != "tool_calls".equals(finish)) throw new ModelProtocolException("Tool calls and finish reason do not match");
        return new ChatResponse(content, finish, readUsage(root));
    }

    static void rejectUnsupported(JsonNode message) throws ModelProtocolException {
        if (message.hasNonNull("function_call")) {
            throw new ModelProtocolException("Legacy function_call responses are not supported");
        }
    }

    static String readFinishReason(JsonNode choice) throws ModelProtocolException {
        String finishReason = requiredText(choice, "finish_reason");
        if (finishReason.isBlank()) {
            throw new ModelProtocolException("Expected a nonblank finish_reason");
        }
        if ("function_call".equals(finishReason)) {
            throw new ModelProtocolException("Legacy function_call completions are not supported");
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

}
