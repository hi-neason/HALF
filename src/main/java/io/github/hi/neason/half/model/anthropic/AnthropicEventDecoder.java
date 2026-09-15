package io.github.hi.neason.half.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.block;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.identity;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.invalid;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.message;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.object;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.text;
import static io.github.hi.neason.half.model.anthropic.AnthropicJson.usage;

/** One Messages SSE lifecycle, independent of HTTP and demand. */
final class AnthropicEventDecoder {
    private final ObjectMapper json;
    private final List<ContentBlock> blocks = new ArrayList<>();
    private final Set<String> toolIds = new HashSet<>();
    private ObjectNode usage;
    private boolean started;
    private boolean stopped;
    private boolean messageDelta;
    private Pending active;
    private String finish;
    private int characters;

    AnthropicEventDecoder(ObjectMapper json) {
        this.json = json;
    }

    List<ModelEvent> accept(String data) throws ModelProtocolException {
        if (stopped) {
            throw invalid("Event after message_stop");
        }
        JsonNode root = object(json, data);
        String type = text(root, "type");
        if ("error".equals(type)) {
            throw invalid("Messages API returned an error");
        }
        List<ModelEvent> events = new ArrayList<>();
        switch (type) {
            case "ping" -> { }
            case "message_start" -> startMessage(root);
            case "content_block_start" -> startBlock(root, events);
            case "content_block_delta" -> applyBlockDelta(root, events);
            case "content_block_stop" -> stopBlock(root, events);
            case "message_delta" -> applyMessageDelta(root);
            case "message_stop" -> stopMessage(events);
            default -> { /* Official SSE contract permits future top-level events. */ }
        }
        return events;
    }

    private void startMessage(JsonNode root) throws ModelProtocolException {
        if (started) {
            throw invalid("Duplicate message_start");
        }
        var message = root.path("message");
        message(message);
        if (!message.path("content").isArray() || !message.path("content").isEmpty()
                || message.hasNonNull("stop_reason")) {
            throw invalid("Invalid initial message");
        }
        if (!message.path("usage").isObject()) {
            throw invalid("Expected usage object");
        }
        usage = message.path("usage").deepCopy();
        usage(usage);
        started = true;
    }

    private void startBlock(JsonNode root, List<ModelEvent> events) throws ModelProtocolException {
        requireStarted();
        if (active != null || messageDelta || index(root) != blocks.size()
                || blocks.size() >= 128) {
            throw invalid("Invalid content block start order");
        }
        var block = root.path("content_block");
        String kind = text(block, "type");
        active = new Pending(kind, block.deepCopy());
        switch (kind) {
            case "text" -> {
                active.text.append(text(block, "text"));
                count(active.text.toString());
                if (!active.text.isEmpty()) {
                    events.add(new ModelEvent.TextDelta(active.text.toString()));
                }
            }
            case "thinking" -> {
                active.text.append(text(block, "thinking"));
                count(active.text.toString());
                if (block.has("signature")) {
                    active.signature.append(text(block, "signature"));
                    count(active.signature.toString());
                }
                if (!active.text.isEmpty()) {
                    events.add(new ModelEvent.ThinkingDelta(blocks.size(), active.text.toString()));
                }
            }
            case "redacted_thinking" -> count(text(block, "data"));
            case "tool_use" -> {
                String id = identity(block, "id");
                String name = identity(block, "name");
                if (!toolIds.add(id) || toolIds.size() > 64 || !block.path("input").isObject()) {
                    throw invalid("Invalid tool block");
                }
                count(id);
                count(name);
                count(block.path("input").toString());
                events.add(new ModelEvent.ToolCallStarted(blocks.size(), id, name));
            }
            default -> throw invalid("Unsupported Messages content block");
        }
    }

    private void applyBlockDelta(JsonNode root, List<ModelEvent> events) throws ModelProtocolException {
        requireActive(root);
        var delta = root.path("delta");
        switch (text(delta, "type")) {
            case "text_delta" -> {
                requireKind("text");
                String value = text(delta, "text");
                count(value);
                active.text.append(value);
                if (!value.isEmpty()) {
                    events.add(new ModelEvent.TextDelta(value));
                }
            }
            case "thinking_delta" -> {
                requireKind("thinking");
                if (active.signatureStarted) {
                    throw invalid("Thinking after signature");
                }
                String value = text(delta, "thinking");
                count(value);
                active.text.append(value);
                if (!value.isEmpty()) {
                    events.add(new ModelEvent.ThinkingDelta(blocks.size(), value));
                }
            }
            case "signature_delta" -> {
                requireKind("thinking");
                active.signatureStarted = true;
                String value = text(delta, "signature");
                count(value);
                active.signature.append(value);
            }
            case "input_json_delta" -> {
                requireKind("tool_use");
                if (!active.source.path("input").isEmpty()) {
                    throw invalid("Tool input cannot have both initial values and deltas");
                }
                String value = text(delta, "partial_json");
                count(value);
                active.text.append(value);
                active.argumentsStarted = true;
                if (!value.isEmpty()) {
                    events.add(new ModelEvent.ToolCallDelta(blocks.size(), value));
                }
            }
            default -> throw invalid("Unsupported content block delta");
        }
    }

    private void stopBlock(JsonNode root, List<ModelEvent> events) throws ModelProtocolException {
        requireActive(root);
        ObjectNode block = active.source;
        switch (active.kind) {
            case "text" -> block.put("text", active.text.toString());
            case "thinking" -> {
                if (active.signature.isEmpty()) {
                    throw invalid("Missing thinking signature");
                }
                block.put("thinking", active.text.toString()).put("signature", active.signature.toString());
            }
            case "tool_use" -> {
                if (active.argumentsStarted) {
                    block.set("input", object(json, active.text.toString()));
                }
            }
            default -> { }
        }
        ContentBlock complete = block(block);
        if (complete instanceof ContentBlock.ToolCall tool) {
            events.add(new ModelEvent.ToolCallCompleted(blocks.size(), tool));
        }
        if (complete instanceof ContentBlock.Thinking value) {
            events.add(new ModelEvent.ThinkingCompleted(blocks.size(), value));
        }
        blocks.add(complete);
        active = null;
    }

    private void applyMessageDelta(JsonNode root) throws ModelProtocolException {
        requireStarted();
        if (active != null) {
            throw invalid("Message delta before block stop");
        }
        var delta = root.path("delta");
        if (!delta.isObject()) {
            throw invalid("Expected message delta object");
        }
        if (delta.hasNonNull("stop_reason")) {
            String value = text(delta, "stop_reason");
            if (value.isBlank() || finish != null && !finish.equals(value)) {
                throw invalid("Invalid stop reason");
            }
            finish = value;
        }
        var counts = root.path("usage");
        if (!counts.isObject()) {
            throw invalid("Expected usage delta");
        }
        for (String field : List.of("input_tokens", "output_tokens",
                "cache_creation_input_tokens", "cache_read_input_tokens")) {
            if (counts.has(field)) {
                long value = AnthropicJson.count(counts, field, true);
                if (value < AnthropicJson.count(usage, field, false)) {
                    throw invalid("Cumulative usage decreased");
                }
                usage.put(field, value);
            }
        }
        usage(usage);
        messageDelta = true;
    }

    private void stopMessage(List<ModelEvent> events) throws ModelProtocolException {
        requireStarted();
        if (active != null || !messageDelta || finish == null) {
            throw invalid("Incomplete message lifecycle");
        }
        TokenUsage counts = usage(usage);
        events.add(new ModelEvent.Usage(counts));
        events.add(new ModelEvent.Completed(new ChatResponse(blocks, finish, Optional.of(counts))));
        stopped = true;
    }

    private void requireStarted() throws ModelProtocolException {
        if (!started) {
            throw invalid("Missing message_start");
        }
    }

    private void requireActive(JsonNode root) throws ModelProtocolException {
        requireStarted();
        if (active == null || index(root) != blocks.size()) {
            throw invalid("Unknown active content index");
        }
    }

    private void requireKind(String kind) throws ModelProtocolException {
        if (!kind.equals(active.kind)) {
            throw invalid("Delta does not match content block type");
        }
    }

    private int index(JsonNode node) throws ModelProtocolException {
        var value = node.path("index");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalid("Invalid content index");
        }
        return value.intValue();
    }

    private void count(String value) throws ModelProtocolException {
        if (value.length() > 4 * 1024 * 1024 - characters) {
            throw invalid("Model output exceeds the 4 Mi character limit");
        }
        characters += value.length();
    }

    private static final class Pending {
        final String kind;
        final ObjectNode source;
        final StringBuilder text = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        boolean signatureStarted;
        boolean argumentsStarted;

        Pending(String kind, ObjectNode source) {
            this.kind = kind;
            this.source = source;
        }
    }
}
