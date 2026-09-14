package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** 单次流的协议状态；与订阅需求量、HTTP 传输分离。 */
final class OpenAiEventDecoder {
    private final ObjectMapper json;
    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, PendingTool> tools = new TreeMap<>();
    private String finishReason;
    private Optional<TokenUsage> usage = Optional.empty();
    private int accumulatedCharacters;

    OpenAiEventDecoder(ObjectMapper json) { this.json = json; }

    List<ModelEvent> accept(String data) throws ModelProtocolException {
        if (data.equals("[DONE]")) {
            if (finishReason == null) throw invalid("Stream ended without a finish reason");
            List<ContentBlock> content = new ArrayList<>();
            if (!text.isEmpty()) content.add(new ContentBlock.Text(text.toString()));
            for (PendingTool tool : tools.values()) content.add(tool.completed());
            return List.of(new ModelEvent.Completed(new ChatResponse(content, finishReason, usage)));
        }
        JsonNode root = OpenAiChatModel.decodeObject(json, data);
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) throw invalid("Expected streaming choices array");
        if (choices.isEmpty()) {
            Optional<TokenUsage> counts = OpenAiChatModel.readUsage(root);
            if (finishReason == null || usage.isPresent() || counts.isEmpty()) {
                throw invalid("Expected a single usage chunk after the finish reason");
            }
            usage = counts;
            return List.of(new ModelEvent.Usage(counts.orElseThrow()));
        }
        if (finishReason != null || choices.size() != 1 || root.hasNonNull("usage")) {
            throw invalid("Unexpected choice or usage in model stream");
        }
        JsonNode choice = choices.get(0);
        if (index(choice, "index") != 0) throw invalid("Only choice index 0 is supported");
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) throw invalid("Expected a delta object");
        if (delta.has("role") && !"assistant".equals(delta.path("role").asText())) {
            throw invalid("Expected assistant delta role");
        }
        OpenAiChatModel.rejectUnsupported(delta);
        List<ModelEvent> events = new ArrayList<>();
        String fragment = optionalText(delta, "content");
        if (fragment != null && !fragment.isEmpty()) {
            count(fragment);
            text.append(fragment);
            events.add(new ModelEvent.TextDelta(fragment));
        }
        JsonNode calls = delta.get("tool_calls");
        if (calls != null && !calls.isNull()) {
            if (!calls.isArray() || calls.size() > 64) throw invalid("Invalid tool call delta array");
            for (JsonNode call : calls) acceptTool(call, events);
        }
        if (choice.hasNonNull("finish_reason")) {
            finishReason = OpenAiChatModel.readFinishReason(choice);
            if ((!tools.isEmpty()) != "tool_calls".equals(finishReason)) {
                throw invalid("Tool calls and finish reason do not match");
            }
            for (var entry : tools.entrySet()) {
                ContentBlock.ToolCall complete = entry.getValue().completed();
                OpenAiChatModel.requireArguments(json, complete.arguments());
                events.add(new ModelEvent.ToolCallCompleted(entry.getKey(), complete));
            }
        }
        // 整个 chunk 校验完成后，才向上层交付其事件。
        return events;
    }

    private void acceptTool(JsonNode call, List<ModelEvent> events) throws ModelProtocolException {
        int index = index(call, "index");
        if (index >= 64) throw invalid("At most 64 tool calls are supported");
        JsonNode function = call.path("function");
        if (!function.isObject()) throw invalid("Expected function delta");
        String id = optionalText(call, "id");
        String type = optionalText(call, "type");
        String name = optionalText(function, "name");
        String arguments = optionalText(function, "arguments");
        PendingTool tool = tools.get(index);
        if (tool == null) {
            if (id == null || id.isBlank() || name == null || name.isBlank() || !"function".equals(type)) {
                throw invalid("First tool delta requires id, function type and name");
            }
            if (tools.values().stream().anyMatch(existing -> existing.id.equals(id))) {
                throw invalid("Duplicate tool call id");
            }
            count(id);
            count(name);
            tool = new PendingTool(id, name);
            tools.put(index, tool);
            events.add(new ModelEvent.ToolCallStarted(index, id, name));
        } else if (id != null && !id.equals(tool.id) || name != null && !name.equals(tool.name)
                || type != null && !"function".equals(type)) {
            throw invalid("Tool call metadata changed during streaming");
        }
        if (arguments != null && !arguments.isEmpty()) {
            count(arguments);
            tool.arguments.append(arguments);
            events.add(new ModelEvent.ToolCallDelta(index, arguments));
        }
    }

    private void count(String fragment) throws ModelProtocolException {
        if (fragment.length() > 4 * 1024 * 1024 - accumulatedCharacters) {
            throw invalid("Model output exceeds the 4 Mi character limit");
        }
        accumulatedCharacters += fragment.length();
    }

    private static int index(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid("Expected nonnegative index");
        }
        return value.intValue();
    }

    private static String optionalText(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("Expected text field: " + field);
        return value.textValue();
    }

    private static ModelProtocolException invalid(String message) {
        return new ModelProtocolException(message);
    }

    private static final class PendingTool {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        PendingTool(String id, String name) { this.id = id; this.name = name; }

        ContentBlock.ToolCall completed() {
            return new ContentBlock.ToolCall(id, name, arguments.toString());
        }
    }
}
