package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

import static io.github.hi.neason.half.model.openai.OpenAiResponsesModel.*;

/** 每次订阅独立的 Responses 状态机；type 决定创建哪种 ModelEvent。 */
final class OpenAiResponsesEventDecoder {
    private final ObjectMapper json;
    private final TreeMap<Integer, Item> items = new TreeMap<>();
    private final HashSet<String> ids = new HashSet<>(), callIds = new HashSet<>();
    private String responseId;
    private int characters;
    private boolean terminal;

    OpenAiResponsesEventDecoder(ObjectMapper json) { this.json = json; }

    List<ModelEvent> accept(String data) throws ModelProtocolException {
        if (terminal) throw invalid("Event after terminal response");
        JsonNode root = OpenAiJson.decodeObject(json, data);
        String type = text(root, "type");
        if (root.hasNonNull("response_id")) checkResponseId(identity(root, "response_id"));
        List<ModelEvent> events = new ArrayList<>();
        switch (type) {
            case "response.created", "response.in_progress" -> {
                checkResponseId(identity(root.path("response"), "id"));
                if (!"in_progress".equals(text(root.path("response"), "status"))) throw invalid("Invalid response lifecycle status");
            }
            case "response.output_item.added" -> {
                int index = index(root, "output_index");
                JsonNode value = root.path("item");
                String id = identity(value, "id"), kind = text(value, "type");
                if (items.size() >= 128 || items.containsKey(index) || !ids.add(id)) throw invalid("Duplicate or excessive output item");
                Item item = new Item(id, kind);
                count(id);
                switch (kind) {
                    case "message" -> {
                        if (!"assistant".equals(text(value, "role")) || !value.path("content").isArray()
                                || !value.path("content").isEmpty()) throw invalid("Expected empty assistant message start");
                    }
                    case "function_call" -> {
                        item.callId = identity(value, "call_id");
                        item.name = identity(value, "name");
                        if (!callIds.add(item.callId) || callIds.size() > 64) throw invalid("Invalid tool call identities or count");
                        count(item.callId); count(item.name);
                        events.add(new ModelEvent.ToolCallStarted(index, item.callId, item.name));
                        // A start item may arrive before any arguments have been generated.
                        String initial = value.has("arguments") ? text(value, "arguments") : "";
                        if (!initial.isEmpty()) {
                            count(initial); item.arguments.append(initial);
                            events.add(new ModelEvent.ToolCallDelta(index, initial));
                        }
                    }
                    case "reasoning" -> { /* 不把推理项暴露为用户可见文本。 */ }
                    default -> throw invalid("Unsupported Responses output item type");
                }
                items.put(index, item);
            }
            case "response.content_part.added" -> {
                Item item = item(root, "message");
                int partIndex = index(root, "content_index");
                JsonNode part = root.path("part");
                String kind = text(part, "type");
                if (!List.of("output_text", "refusal").contains(kind) || !text(part, kind.equals("refusal") ? "refusal" : "text").isEmpty()
                        || item.parts.size() >= 128 || partIndex != item.parts.size()) throw invalid("Invalid content part start");
                item.parts.put(partIndex, new Part(kind));
            }
            case "response.output_text.delta", "response.refusal.delta" -> {
                Part part = part(root);
                boolean refusal = type.equals("response.refusal.delta");
                if (!part.kind.equals(refusal ? "refusal" : "output_text")) throw invalid("Delta type does not match content part");
                if (part.textDone || part.done) throw invalid("Text delta after completion");
                String delta = text(root, "delta");
                count(delta); part.text.append(delta);
                if (!delta.isEmpty()) events.add(refusal ? new ModelEvent.RefusalDelta(delta) : new ModelEvent.TextDelta(delta));
            }
            case "response.output_text.done", "response.refusal.done" -> {
                Part part = part(root);
                boolean refusal = type.equals("response.refusal.done");
                if (!part.kind.equals(refusal ? "refusal" : "output_text")) throw invalid("Completion type does not match content part");
                if (part.textDone || part.done || !part.text.toString().equals(text(root, refusal ? "refusal" : "text"))) throw invalid("Text completion does not match deltas");
                part.textDone = true;
            }
            case "response.content_part.done" -> {
                Part part = part(root);
                JsonNode value = root.path("part");
                if (part.done || !part.textDone || !part.kind.equals(text(value, "type"))
                        || !part.text.toString().equals(text(value, part.kind.equals("refusal") ? "refusal" : "text"))) throw invalid("Invalid text part completion");
                part.done = true;
            }
            case "response.function_call_arguments.delta" -> {
                Item item = item(root, "function_call");
                if (item.argumentsDone) throw invalid("Arguments delta after completion");
                String delta = text(root, "delta");
                count(delta); item.arguments.append(delta);
                if (!delta.isEmpty()) events.add(new ModelEvent.ToolCallDelta(index(root, "output_index"), delta));
            }
            case "response.function_call_arguments.done" -> {
                Item item = item(root, "function_call");
                if (item.argumentsDone || !item.arguments.toString().equals(text(root, "arguments"))) throw invalid("Arguments completion does not match deltas");
                // incomplete 响应可能带有截断参数；只有 output_item.done 的 completed 调用才可交付。
                item.argumentsDone = true;
            }
            case "response.output_item.done" -> {
                int index = index(root, "output_index");
                Item item = items.get(index);
                if (item == null || item.done) throw invalid("Output item was not started or is already done");
                JsonNode value = root.path("item");
                validateItem(item, value);
                if (item.kind.equals("function_call")) {
                    if (!item.argumentsDone) throw invalid("Missing arguments completion");
                    events.add(new ModelEvent.ToolCallCompleted(index, (ContentBlock.ToolCall) item.content().get(0)));
                } else if (item.kind.equals("message") && item.parts.values().stream().anyMatch(p -> !p.done)) {
                    throw invalid("Missing text part completion");
                }
                if (item.kind.equals("reasoning")) {
                    boolean incomplete = "incomplete".equals(value.path("status").asText());
                    if (!incomplete) {
                        if (item.summary.values().stream().anyMatch(p -> !p.done)
                                || item.reasoningText.values().stream().anyMatch(p -> !p.textDone)) throw invalid("Missing reasoning part completion");
                        if (value.hasNonNull("status") && !"completed".equals(text(value, "status"))) throw invalid("Reasoning item is not complete");
                        events.add(new ModelEvent.ReasoningCompleted(index, item.reasoning));
                    }
                }
                item.done = true;
            }
            case "response.completed", "response.incomplete" -> {
                JsonNode response = root.path("response");
                checkResponseId(identity(response, "id"));
                if (!type.equals("response." + text(response, "status"))) throw invalid("Terminal event and response status disagree");
                ChatResponse result = decode(json, response);
                JsonNode output = response.path("output");
                if (output.size() != items.size()) throw invalid("Terminal output does not match streamed items");
                for (int i = 0; i < output.size(); i++) {
                    Item item = items.get(i);
                    if (item == null || type.equals("response.completed") && !item.done) throw invalid("Missing output item completion");
                    validateItem(item, output.get(i));
                }
                result.usage().ifPresent(usage -> events.add(new ModelEvent.Usage(usage)));
                events.add(new ModelEvent.Completed(result));
                terminal = true;
            }
            case "response.failed", "error" -> throw invalid("Responses stream reported failure");
            case "response.output_text.annotation.added" -> { part(root); /* 当前内容抽象不保留引用注解。 */ }
            case "response.reasoning_summary_part.added" -> {
                Item item = item(root, "reasoning");
                int index = index(root, "summary_index");
                JsonNode part = root.path("part");
                // Some compatible providers omit the empty text at part start; done events still require it.
                if (index != item.summary.size() || !"summary_text".equals(text(part, "type"))
                        || part.has("text") && !text(part, "text").isEmpty()) {
                    throw invalid("Invalid reasoning summary start");
                }
                item.summary.put(index, new Part("summary_text"));
            }
            case "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                Item item = item(root, "reasoning");
                boolean summary = type.equals("response.reasoning_summary_text.delta");
                int index = index(root, summary ? "summary_index" : "content_index");
                var parts = summary ? item.summary : item.reasoningText;
                if (summary && !parts.containsKey(index)) throw invalid("Reasoning summary was not started");
                Part part = parts.computeIfAbsent(index, ignored -> new Part("reasoning_text"));
                if (part.textDone || part.done) throw invalid("Reasoning delta after completion");
                String delta = text(root, "delta");
                count(delta); part.text.append(delta);
                if (!delta.isEmpty()) events.add(new ModelEvent.ReasoningDelta(index(root, "output_index"), index, summary, delta));
            }
            case "response.reasoning_summary_text.done", "response.reasoning_text.done" -> {
                Item item = item(root, "reasoning");
                boolean summary = type.equals("response.reasoning_summary_text.done");
                int index = index(root, summary ? "summary_index" : "content_index");
                var parts = summary ? item.summary : item.reasoningText;
                Part part = parts.get(index);
                // 空推理文本可能只有 done，仍校验索引并记录其空片段。
                if (part == null && !summary && text(root, "text").isEmpty()) {
                    part = new Part("reasoning_text"); parts.put(index, part);
                }
                if (part == null || part.textDone || !part.text.toString().equals(text(root, "text"))) throw invalid("Reasoning completion does not match deltas");
                part.textDone = true;
            }
            case "response.reasoning_summary_part.done" -> {
                Item item = item(root, "reasoning");
                Part part = item.summary.get(index(root, "summary_index"));
                JsonNode value = root.path("part");
                if (part == null || part.done || !part.textDone || !"summary_text".equals(text(value, "type"))
                        || !part.text.toString().equals(text(value, "text"))) throw invalid("Invalid reasoning summary completion");
                part.done = true;
            }
            default -> throw invalid("Unsupported Responses stream event type");
        }
        return events;
    }

    private void validateItem(Item item, JsonNode value) throws ModelProtocolException {
        if (!item.id.equals(identity(value, "id")) || !item.kind.equals(text(value, "type"))) throw invalid("Output item identity changed");
        List<ContentBlock> decoded = decodeItem(json, value);
        if (item.kind.equals("reasoning")) {
            ContentBlock.Reasoning reasoning = (ContentBlock.Reasoning) decoded.getFirst();
            checkReasoning(item.summary, reasoning.summary());
            checkReasoning(item.reasoningText, reasoning.content());
            if (item.reasoning != null && !item.reasoning.equals(reasoning)) throw invalid("Reasoning output changed after completion");
            if (item.reasoning == null) {
                // 最终快照包含加密内容；这些字符也计入每订阅的输出上限。
                if (reasoning.encryptedContent() != null) count(reasoning.encryptedContent());
                for (int i = 0; i < reasoning.summary().size(); i++) if (!item.summary.containsKey(i)) count(reasoning.summary().get(i));
                for (int i = 0; i < reasoning.content().size(); i++) if (!item.reasoningText.containsKey(i)) count(reasoning.content().get(i));
                item.reasoning = reasoning;
            }
        } else if (!item.content().equals(decoded)) throw invalid("Output item does not match streamed content");
    }

    private static void checkReasoning(TreeMap<Integer, Part> streamed, List<String> complete) throws ModelProtocolException {
        for (var entry : streamed.entrySet()) {
            if (entry.getKey() >= complete.size() || !entry.getValue().text.toString().equals(complete.get(entry.getKey()))) {
                throw invalid("Reasoning output does not match streamed content");
            }
        }
    }

    private Item item(JsonNode root, String kind) throws ModelProtocolException {
        Item item = items.get(index(root, "output_index"));
        if (item == null || item.done || !item.kind.equals(kind) || !item.id.equals(identity(root, "item_id"))) {
            throw invalid("Event does not match an active output item");
        }
        return item;
    }

    private Part part(JsonNode root) throws ModelProtocolException {
        Part part = item(root, "message").parts.get(index(root, "content_index"));
        if (part == null) throw invalid("Text part was not started");
        return part;
    }

    private void checkResponseId(String id) throws ModelProtocolException {
        if (responseId != null && !responseId.equals(id)) throw invalid("Response identity changed");
        responseId = id;
    }

    private void count(String fragment) throws ModelProtocolException {
        if (fragment.length() > 4 * 1024 * 1024 - characters) throw invalid("Model output exceeds the 4 Mi character limit");
        characters += fragment.length();
    }

    private static int index(JsonNode node, String field) throws ModelProtocolException {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() >= 128) {
            throw invalid("Invalid output or content index");
        }
        return value.intValue();
    }

    private static final class Part {
        final String kind;
        Part(String kind) { this.kind = kind; }
        final StringBuilder text = new StringBuilder();
        boolean textDone, done;
    }

    private static final class Item {
        final String id, kind;
        final TreeMap<Integer, Part> parts = new TreeMap<>();
        final TreeMap<Integer, Part> summary = new TreeMap<>(), reasoningText = new TreeMap<>();
        ContentBlock.Reasoning reasoning;
        final StringBuilder arguments = new StringBuilder();
        String callId, name;
        boolean argumentsDone, done;
        Item(String id, String kind) { this.id = id; this.kind = kind; }
        List<ContentBlock> content() {
            if (kind.equals("function_call")) return List.of(new ContentBlock.ToolCall(callId, name, arguments.toString()));
            if (kind.equals("reasoning")) return reasoning == null ? List.of() : List.of(reasoning);
            return parts.values().stream().<ContentBlock>map(p -> p.kind.equals("refusal")
                    ? new ContentBlock.Refusal(p.text.toString()) : new ContentBlock.Text(p.text.toString())).toList();
        }
    }
}
