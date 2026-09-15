package io.github.hi.neason.half.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.*;
import io.github.hi.neason.half.model.http.HttpChatModel;
import io.github.hi.neason.half.model.http.ModelStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Native Messages API. endpoint is the complete /v1/messages URL. */
public final class AnthropicMessagesModel extends HttpChatModel {
    private final AnthropicThinking thinking;

    public AnthropicMessagesModel(URI endpoint, String apiKey, String model, Duration timeout) {
        this(endpoint, apiKey, model, timeout, null);
    }

    public AnthropicMessagesModel(URI endpoint, String apiKey, String model, Duration timeout, AnthropicThinking thinking) {
        super(endpoint, apiKey, model, timeout, Map.of("x-api-key", Objects.requireNonNull(apiKey, "apiKey"), "anthropic-version", "2023-06-01"));
        this.thinking = thinking;
    }

    @Override protected ModelStream.EventDecoder newEventDecoder() { return new AnthropicEventDecoder(json)::accept; }

    @Override protected String encodeRequest(ChatRequest request, boolean streaming) throws IOException {
        validateToolHistory(request.messages());
        var root = json.createObjectNode().put("model", model).put("max_tokens", request.maxOutputTokens() == null ? 1024 : request.maxOutputTokens()).put("stream", streaming);
        var messages = root.putArray("messages");
        boolean conversation = false;
        Set<String> ids = new HashSet<>();
        for (var message : request.messages()) {
            if (!message.outputItemsJson().isEmpty()) throw new IllegalArgumentException("Responses output snapshots cannot be replayed as Messages");
            if (message.role() == ChatMessage.Role.SYSTEM || message.role() == ChatMessage.Role.DEVELOPER) {
                if (conversation) throw new IllegalArgumentException("System/developer instructions must precede conversation messages");
                for (var block : message.content()) {
                    if (!(block instanceof ContentBlock.Text text)) throw new IllegalArgumentException("System requires text");
                    root.withArray("system").addObject().put("type", "text").put("text", text.text());
                }
                continue;
            }
            conversation = true;
            String role = message.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
            // Consecutive tool results belong to the same user turn.
            ObjectNode item;
            if (!messages.isEmpty() && role.equals(messages.get(messages.size()-1).path("role").asText())) item = (ObjectNode) messages.get(messages.size()-1);
            else item = messages.addObject().put("role", role);
            var parts = item.withArray("content");
            if (message.role() == ChatMessage.Role.TOOL) {
                parts.addObject().put("type", "tool_result").put("tool_use_id", message.toolCallId()).put("content", message.text());
                continue;
            }
            for (var block : message.content()) {
                var part = parts.addObject();
                switch (block) {
                    case ContentBlock.Text text -> part.put("type", "text").put("text", text.text());
                    case ContentBlock.ToolCall tool -> {
                        if (!ids.add(tool.id())) throw new IllegalArgumentException("Duplicate tool call id");
                        part.put("type", "tool_use").put("id", tool.id()).put("name", tool.name()).set("input", object(tool.arguments()));
                    }
                    case ContentBlock.Thinking value -> part.put("type", "thinking").put("thinking", value.thinking()).put("signature", value.signature());
                    case ContentBlock.RedactedThinking value -> part.put("type", "redacted_thinking").put("data", value.data());
                    case ContentBlock.Image image -> {
                        if (image.detail() != null) throw new IllegalArgumentException("Messages does not support image detail");
                        part.put("type", "image");
                        var source = part.putObject("source");
                        if (image.url().startsWith("data:")) {
                            int comma = image.url().indexOf(',');
                            if (comma < 0 || !image.url().substring(0, comma).endsWith(";base64")) throw new IllegalArgumentException("Expected base64 image data URL");
                            String media = image.url().substring(5, comma - 7);
                            if (!List.of("image/jpeg", "image/png", "image/gif", "image/webp").contains(media)) throw new IllegalArgumentException("Unsupported image media type");
                            String data = image.url().substring(comma+1);
                            try { Base64.getDecoder().decode(data); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid base64 image data"); }
                            source.put("type", "base64").put("media_type", media).put("data", data);
                        } else source.put("type", "url").put("url", image.url());
                    }
                    default -> throw new IllegalArgumentException("Content block is not supported by Messages");
                }
            }
        }
        if (messages.isEmpty()) throw new IllegalArgumentException("Messages requires a conversation message");
        if (!request.tools().isEmpty()) {
            var tools = root.putArray("tools");
            for (var definition : request.tools()) {
                var tool = tools.addObject().put("name", definition.name());
                if (definition.description() != null) tool.put("description", definition.description());
                tool.set("input_schema", object(definition.parametersJson()));
                if (definition.strict() != null) tool.put("strict", definition.strict());
            }
        }
        var options = request.options();
        if (options.reasoningSummary() != null || options.verbosity() != null || options.includeEncryptedReasoning() != null) throw new IllegalArgumentException("Unsupported Messages model option");
        if (options.temperature() != null) {
            if (options.temperature() > 1) throw new IllegalArgumentException("Messages temperature must be between 0 and 1");
            root.put("temperature", options.temperature());
        }
        if (options.topP() != null) root.put("top_p", options.topP());
        if (options.toolChoice() != null || options.parallelToolCalls() != null) {
            var choice = root.putObject("tool_choice");
            ToolChoice value = options.toolChoice() == null ? ToolChoice.Mode.AUTO : options.toolChoice();
            switch (value) {
                case ToolChoice.Function tool -> {
                    if (request.tools().stream().noneMatch(t -> t.name().equals(tool.name()))) throw new IllegalArgumentException("Chosen tool must be declared");
                    choice.put("type", "tool").put("name", tool.name());
                }
                case ToolChoice.Mode mode -> {
                    if (mode == ToolChoice.Mode.REQUIRED && request.tools().isEmpty()) throw new IllegalArgumentException("Required tool choice needs tools");
                    choice.put("type", switch (mode) { case AUTO -> "auto"; case NONE -> "none"; case REQUIRED -> "any"; });
                }
            }
            if (options.parallelToolCalls() != null) {
                if (value == ToolChoice.Mode.NONE) throw new IllegalArgumentException("Parallel tool option cannot be combined with none");
                choice.put("disable_parallel_tool_use", !options.parallelToolCalls());
            }
        }
        if (options.responseFormat() instanceof ResponseFormat.JsonSchema schema) {
            if (Boolean.FALSE.equals(schema.strict())) throw new IllegalArgumentException("Messages JSON schema output is always strict");
            root.withObject("/output_config").putObject("format").put("type", "json_schema").set("schema", object(schema.schemaJson()));
        } else if (options.responseFormat() instanceof ResponseFormat.JsonObject) throw new IllegalArgumentException("Messages requires a JSON schema for structured output");
        if (options.reasoningEffort() != null) {
            if (!List.of(ModelOptions.ReasoningEffort.LOW, ModelOptions.ReasoningEffort.MEDIUM, ModelOptions.ReasoningEffort.HIGH, ModelOptions.ReasoningEffort.MAX).contains(options.reasoningEffort())) throw new IllegalArgumentException("Unsupported Messages effort");
            root.withObject("/output_config").put("effort", options.reasoningEffort().name().toLowerCase(Locale.ROOT));
        }
        if (thinking != null) {
            var node = root.putObject("thinking");
            if (thinking instanceof AnthropicThinking.Enabled enabled) {
                if (enabled.budgetTokens() >= root.path("max_tokens").asInt()) throw new IllegalArgumentException("Thinking budget must be less than max_tokens");
                node.put("type", "enabled").put("budget_tokens", enabled.budgetTokens());
            } else node.put("type", "adaptive");
            if (options.toolChoice() == ToolChoice.Mode.REQUIRED || options.toolChoice() instanceof ToolChoice.Function) throw new IllegalArgumentException("Thinking cannot force a tool");
        }
        return json.writeValueAsString(root);
    }

    private static void validateToolHistory(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (var message : messages) {
            if (message.role() == ChatMessage.Role.TOOL) {
                if (!pending.remove(message.toolCallId())) throw new IllegalArgumentException("Tool result must match the immediately preceding tool calls");
                if (message.content().stream().anyMatch(block -> !(block instanceof ContentBlock.Text))) throw new IllegalArgumentException("Tool result requires text");
            } else {
                if (!pending.isEmpty()) throw new IllegalArgumentException("All tool results must immediately follow their assistant tool calls");
                for (var block : message.content()) if (block instanceof ContentBlock.ToolCall tool) pending.add(tool.id());
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Missing tool results");
    }

    private JsonNode object(String value) throws ModelProtocolException { return AnthropicJson.object(json, value); }

    @Override protected ChatResponse decodeResponse(String body) throws ModelProtocolException {
        JsonNode root = object(body);
        AnthropicJson.message(root);
        var content = root.path("content");
        if (!content.isArray() || content.size() > 128) throw AnthropicJson.invalid("Invalid message content array");
        List<ContentBlock> blocks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        long chars = 0;
        for (var item : content) {
            chars += item.toString().length();
            if (chars > 4 * 1024 * 1024) throw AnthropicJson.invalid("Model output exceeds the 4 Mi character limit");
            var block = AnthropicJson.block(item);
            if (block instanceof ContentBlock.ToolCall tool && !ids.add(tool.id())) throw AnthropicJson.invalid("Duplicate tool call id");
            blocks.add(block);
        }
        String stop = AnthropicJson.text(root, "stop_reason");
        if (stop.isBlank()) throw AnthropicJson.invalid("Missing stop reason");
        return new ChatResponse(blocks, stop, Optional.of(AnthropicJson.usage(root.path("usage"))));
    }
}
