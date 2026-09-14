package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;
import io.github.hi.neason.half.model.ToolDefinition;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Responses 文本与函数调用协议；宿主传入完整历史，不使用服务端会话。 */
public final class OpenAiResponsesModel extends OpenAiHttpModel {
    /** endpoint 为完整 URL，例如 https://api.openai.com/v1/responses。 */
    public OpenAiResponsesModel(URI endpoint, String apiKey, String model, Duration timeout) {
        super(endpoint, apiKey, model, timeout);
    }

    @Override
    OpenAiStream.EventDecoder newEventDecoder() { return new OpenAiResponsesEventDecoder(json)::accept; }

    @Override
    String encodeRequest(ChatRequest request, boolean streaming) throws IOException {
        var root = json.createObjectNode();
        root.put("model", model).put("stream", streaming).put("store", false);
        if (request.maxOutputTokens() != null) root.put("max_output_tokens", request.maxOutputTokens());
        var input = root.putArray("input");
        var callIds = new HashSet<String>();
        for (ChatMessage message : request.messages()) {
            if (message.role() == ChatMessage.Role.TOOL) {
                input.addObject().put("type", "function_call_output")
                        .put("call_id", message.toolCallId()).put("output", message.text());
                continue;
            }
            // 按内容块顺序写入消息与独立的 function_call 项。
            for (ContentBlock block : message.content()) {
                switch (block) {
                    case ContentBlock.Text text -> input.addObject()
                            .put("role", message.role().name().toLowerCase(Locale.ROOT))
                            .put("content", text.text());
                    case ContentBlock.ToolCall call -> {
                        if (!callIds.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
                        OpenAiJson.requireArguments(json, call.arguments());
                        input.addObject().put("type", "function_call").put("call_id", call.id())
                                .put("name", call.name()).put("arguments", call.arguments());
                    }
                }
            }
        }
        if (!request.tools().isEmpty()) {
            var tools = root.putArray("tools");
            for (ToolDefinition definition : request.tools()) {
                JsonNode schema;
                try { schema = json.readTree(definition.parametersJson()); }
                catch (JsonProcessingException error) { throw new IllegalArgumentException("Tool parameters must be valid JSON"); }
                if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
                    throw new IllegalArgumentException("Tool parameters must be an object JSON Schema");
                }
                // 保留宿主 Schema 的可选字段语义，不自动转成 strict Schema。
                tools.addObject().put("type", "function").put("name", definition.name())
                        .put("description", definition.description()).put("strict", false).set("parameters", schema);
            }
        }
        return json.writeValueAsString(root);
    }

    @Override
    ChatResponse decodeResponse(String body) throws ModelProtocolException {
        return decode(json, OpenAiJson.decodeObject(json, body));
    }

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
                } else characters += ((ContentBlock.Text) block).text().length();
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
        return new ChatResponse(content, finish, usage);
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
                    if (!"output_text".equals(text(part, "type"))) throw invalid("Only output_text content is supported");
                    blocks.add(new ContentBlock.Text(text(part, "text")));
                }
                return blocks;
            }
            case "function_call": {
                if (item.hasNonNull("status") && !"completed".equals(text(item, "status"))) throw invalid("Function call is not complete");
                String arguments = text(item, "arguments");
                OpenAiJson.requireArguments(json, arguments);
                return List.of(new ContentBlock.ToolCall(identity(item, "call_id"), identity(item, "name"), arguments));
            }
            // 当前抽象只表达可见文本和函数调用；推理项不转换为用户文本或可执行内容。
            case "reasoning": return List.of();
            default: throw invalid("Unsupported Responses output item type");
        }
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
