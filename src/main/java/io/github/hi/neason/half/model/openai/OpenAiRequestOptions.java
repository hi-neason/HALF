package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ResponseFormat;
import io.github.hi.neason.half.model.ToolChoice;

import java.util.Locale;

/** 两种官方端点的选项映射；不隐式改写调用方的 Schema 或参数。 */
final class OpenAiRequestOptions {
    private OpenAiRequestOptions() {}

    static void apply(ObjectMapper json, ObjectNode root, ChatRequest request, boolean responses) {
        var options = request.options();
        if (!responses && (options.reasoningSummary() != null || options.includeEncryptedReasoning() != null)) {
            throw new IllegalArgumentException("Reasoning summary and encrypted reasoning options require Responses API");
        }
        if (options.temperature() != null) root.put("temperature", options.temperature());
        if (options.topP() != null) root.put("top_p", options.topP());
        if (options.parallelToolCalls() != null) root.put("parallel_tool_calls", options.parallelToolCalls());
        if (options.toolChoice() != null) {
            switch (options.toolChoice()) {
                case ToolChoice.Mode mode -> {
                    if (mode == ToolChoice.Mode.REQUIRED && request.tools().isEmpty()) {
                        throw new IllegalArgumentException("Required tool choice needs at least one tool");
                    }
                    root.put("tool_choice", value(mode));
                }
                case ToolChoice.Function function -> {
                    if (request.tools().stream().noneMatch(tool -> tool.name().equals(function.name()))) {
                        throw new IllegalArgumentException("Chosen function must be declared in tools");
                    }
                    var choice = root.putObject("tool_choice").put("type", "function");
                    if (responses) choice.put("name", function.name());
                    else choice.putObject("function").put("name", function.name());
                }
            }
        }
        if (options.reasoningEffort() != null || options.reasoningSummary() != null) {
            if (responses) {
                var reasoning = root.putObject("reasoning");
                if (options.reasoningEffort() != null) reasoning.put("effort", value(options.reasoningEffort()));
                if (options.reasoningSummary() != null) reasoning.put("summary", value(options.reasoningSummary()));
            } else root.put("reasoning_effort", value(options.reasoningEffort()));
        }
        if (responses && Boolean.TRUE.equals(options.includeEncryptedReasoning())) {
            root.putArray("include").add("reasoning.encrypted_content");
        }
        ObjectNode text = null;
        if (responses && (options.responseFormat() != null || options.verbosity() != null)) {
            text = root.putObject("text");
        }
        if (options.verbosity() != null) {
            (responses ? text : root).put("verbosity", value(options.verbosity()));
        }
        if (options.responseFormat() != null) {
            var format = responses ? text.putObject("format") : root.putObject("response_format");
            switch (options.responseFormat()) {
                case ResponseFormat.Text ignored -> format.put("type", "text");
                case ResponseFormat.JsonObject ignored -> format.put("type", "json_object");
                case ResponseFormat.JsonSchema schema -> {
                    format.put("type", "json_schema");
                    var target = responses ? format : format.putObject("json_schema");
                    target.put("name", schema.name());
                    if (schema.description() != null) target.put("description", schema.description());
                    if (schema.strict() != null) target.put("strict", schema.strict());
                    try {
                        var parsed = json.readTree(schema.schemaJson());
                        if (parsed == null || !parsed.isObject()) {
                            throw new IllegalArgumentException("Response schema must be a JSON object");
                        }
                        target.set("schema", parsed);
                    } catch (JsonProcessingException error) {
                        throw new IllegalArgumentException("Response schema must be valid JSON");
                    }
                }
            }
        }
    }

    private static String value(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
