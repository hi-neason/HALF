package io.github.hi.neason.half.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.*;

final class AnthropicJson {
    private AnthropicJson() {}
    static ModelProtocolException invalid(String message) { return new ModelProtocolException(message); }
    static JsonNode object(ObjectMapper json, String body) throws ModelProtocolException {
        try {
            var value = json.readTree(body);
            if (value == null || !value.isObject()) throw invalid("Expected JSON object");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("Invalid Messages JSON"); }
    }
    static String text(JsonNode node, String field) throws ModelProtocolException {
        var value = node.path(field);
        if (!value.isTextual()) throw invalid("Expected text field: " + field);
        return value.textValue();
    }
    static String identity(JsonNode node, String field) throws ModelProtocolException {
        String value = text(node, field);
        if (value.isBlank()) throw invalid("Expected nonblank identity");
        return value;
    }
    static void message(JsonNode node) throws ModelProtocolException {
        if (!"message".equals(text(node, "type")) || !"assistant".equals(text(node, "role"))) throw invalid("Expected assistant message");
        identity(node, "id");
    }
    static ContentBlock block(JsonNode node) throws ModelProtocolException {
        return switch (text(node, "type")) {
            case "text" -> new ContentBlock.Text(text(node, "text"));
            case "thinking" -> new ContentBlock.Thinking(text(node, "thinking"), text(node, "signature"));
            case "redacted_thinking" -> new ContentBlock.RedactedThinking(text(node, "data"));
            case "tool_use" -> {
                if (!node.path("input").isObject()) throw invalid("Tool input must be an object");
                yield new ContentBlock.ToolCall(identity(node, "id"), identity(node, "name"), node.get("input").toString());
            }
            default -> throw invalid("Unsupported Messages content block");
        };
    }
    static long count(JsonNode node, String field, boolean required) throws ModelProtocolException {
        var value = node.get(field);
        if (value == null || value.isNull()) { if (!required) return 0; throw invalid("Missing token count"); }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid("Invalid token count");
        return value.longValue();
    }
    static TokenUsage usage(JsonNode node) throws ModelProtocolException {
        try {
            long input = Math.addExact(count(node,"input_tokens",true), Math.addExact(count(node,"cache_creation_input_tokens",false),count(node,"cache_read_input_tokens",false)));
            long output = count(node,"output_tokens",true);
            return new TokenUsage(input, output, Math.addExact(input, output));
        } catch (ArithmeticException e) { throw invalid("Token count overflow"); }
    }
}
