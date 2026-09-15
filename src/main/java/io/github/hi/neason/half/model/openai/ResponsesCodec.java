package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/** Responses 输出的纯协议解析；完整响应、事件终态和历史回放共用。 */
final class ResponsesCodec {
    private ResponsesCodec() { }

    static ChatResponse decode(ObjectMapper json, JsonNode root) throws ModelProtocolException {
        if (!"response".equals(text(root, "object")) || root.hasNonNull("error")) throw invalid("Expected a successful response object");
        String status = text(root, "status");
        if (!status.equals("completed") && !status.equals("incomplete")) throw invalid("Response did not complete successfully");
        JsonNode output = root.path("output");
        if (!output.isArray() || output.size() > 128) throw invalid("Expected at most 128 output items");
        List<ContentBlock> content = new ArrayList<>();
        var callIds = new HashSet<String>();
        var itemIds = new HashSet<String>();
        long characters = 0;
        for (JsonNode item : output) {
            if (!itemIds.add(identity(item, "id"))) throw invalid("Duplicate output item id");
            if (status.equals("completed") && item.hasNonNull("status") && !"completed".equals(text(item, "status"))) {
                throw invalid("Completed response contains unfinished output");
            }
            List<ContentBlock> blocks = decodeItem(json, item);
            for (ContentBlock block : blocks) {
                if (block instanceof ContentBlock.ToolCall call) {
                    if (!status.equals("completed")) throw invalid("Incomplete tool calls cannot be delivered");
                    if (!callIds.add(call.id()) || callIds.size() > 64) throw invalid("Invalid tool call identities or count");
                    characters += (long) call.id().length() + call.name().length() + call.arguments().length();
                } else if (block instanceof ContentBlock.Text text) characters += text.text().length();
                else if (block instanceof ContentBlock.Refusal refusal) characters += refusal.text().length();
                else if (block instanceof ContentBlock.Reasoning reasoning) {
                    characters += reasoning.id().length();
                    for (String fragment : reasoning.summary()) characters += fragment.length();
                    for (String fragment : reasoning.content()) characters += fragment.length();
                    if (reasoning.encryptedContent() != null) characters += reasoning.encryptedContent().length();
                }
                if (characters > 4 * 1024 * 1024) throw invalid("Model output exceeds the 4 Mi character limit");
                content.add(block);
            }
        }
        String finish = status.equals("incomplete") ? identity(root.path("incomplete_details"), "reason") : status;
        Optional<TokenUsage> usage = Optional.empty();
        if (root.hasNonNull("usage")) {
            JsonNode counts = root.get("usage");
            usage = Optional.of(new TokenUsage(count(counts, "input_tokens"), count(counts, "output_tokens"), count(counts, "total_tokens")));
        }
        List<String> snapshots = new ArrayList<>();
        for (JsonNode item : output) snapshots.add(item.toString());
        return new ChatResponse(content, finish, usage, snapshots);
    }

    static List<ContentBlock> decodeItem(ObjectMapper json, JsonNode item) throws ModelProtocolException {
        switch (text(item, "type")) {
            case "message": {
                if (!"assistant".equals(text(item, "role"))) throw invalid("Expected an assistant output message");
                if (item.hasNonNull("status") && !List.of("completed", "incomplete").contains(text(item, "status"))) {
                    throw invalid("Output message is not finished");
                }
                JsonNode parts = item.path("content");
                if (!parts.isArray() || parts.size() > 128) throw invalid("Invalid output message content");
                List<ContentBlock> blocks = new ArrayList<>();
                for (JsonNode part : parts) {
                    switch (text(part, "type")) {
                        case "output_text" -> blocks.add(new ContentBlock.Text(text(part, "text")));
                        case "refusal" -> blocks.add(new ContentBlock.Refusal(text(part, "refusal")));
                        default -> throw invalid("Unsupported output message content");
                    }
                }
                return blocks;
            }
            case "function_call": {
                if (item.hasNonNull("status") && !"completed".equals(text(item, "status"))) throw invalid("Function call is not complete");
                String arguments = text(item, "arguments");
                OpenAiJson.requireArguments(json, arguments);
                return List.of(new ContentBlock.ToolCall(identity(item, "call_id"), identity(item, "name"), arguments));
            }
            case "reasoning": return List.of(new ContentBlock.Reasoning(identity(item, "id"),
                    reasoningParts(item, "summary", "summary_text"), reasoningParts(item, "content", "reasoning_text"),
                    item.hasNonNull("encrypted_content") ? text(item, "encrypted_content") : null));
            default: throw invalid("Unsupported Responses output item type");
        }
    }

    private static List<String> reasoningParts(JsonNode item, String field, String type) throws ModelProtocolException {
        if (!item.hasNonNull(field)) return List.of();
        JsonNode parts = item.get(field);
        if (!parts.isArray() || parts.size() > 128) throw invalid("Invalid reasoning parts");
        List<String> result = new ArrayList<>();
        for (JsonNode part : parts) {
            if (!type.equals(text(part, "type"))) throw invalid("Invalid reasoning part type");
            result.add(text(part, "text"));
        }
        return List.copyOf(result);
    }

    static String text(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isTextual()) throw invalid("Expected text field: " + field);
        return value.textValue();
    }

    static String identity(JsonNode node, String field) throws ModelProtocolException {
        String value = text(node, field);
        if (value.isBlank()) throw invalid("Expected nonblank field: " + field);
        return value;
    }

    private static long count(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid("Invalid token count: " + field);
        return value.longValue();
    }

    static ModelProtocolException invalid(String message) { return new ModelProtocolException(message); }
}
