package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.ReplayState;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 内容块到两种官方请求形状的映射；不处理网络或模型执行。 */
final class OpenAiContent {
    private OpenAiContent() {}

    static void chat(ObjectMapper json, ArrayNode messages, List<ChatMessage> history) throws ModelProtocolException {
        for (ChatMessage message : history) {
            if (message.replayState().protocol() != ReplayState.Protocol.NONE) {
                throw new IllegalArgumentException("Responses replay state cannot be encoded as Chat Completions; explicitly remove replay state first");
            }
            var item = messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
            if (message.role() == ChatMessage.Role.TOOL) item.put("tool_call_id", message.toolCallId());
            boolean media = message.content().stream().anyMatch(b -> b instanceof ContentBlock.Image || b instanceof ContentBlock.File);
            if (media) {
                var parts = item.putArray("content");
                for (ContentBlock block : message.content()) part(parts, block, false, message.role());
            } else if (message.content().stream().anyMatch(ContentBlock.Text.class::isInstance)) item.put("content", message.text());
            else item.putNull("content");
            StringBuilder refusal = new StringBuilder();
            var ids = new HashSet<String>();
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolCall call) {
                    if (!ids.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
                    OpenAiJson.requireArguments(json, call.arguments());
                    var tool = item.withArray("tool_calls").addObject().put("id", call.id()).put("type", "function");
                    tool.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                } else if (block instanceof ContentBlock.Refusal value) refusal.append(value.text());
                else if (block instanceof ContentBlock.Thinking || block instanceof ContentBlock.RedactedThinking) throw new IllegalArgumentException("Anthropic thinking blocks require Messages API");
                else if (block instanceof ContentBlock.Reasoning) throw new IllegalArgumentException("Reasoning item replay requires Responses");
            }
            if (!refusal.isEmpty()) item.put("refusal", refusal.toString());
        }
    }

    static void responses(ObjectMapper json, ArrayNode input, List<ChatMessage> history) throws ModelProtocolException {
        var callIds = new HashSet<String>();
        for (ChatMessage message : history) {
            if (message.replayState().protocol() == ReplayState.Protocol.OPENAI_RESPONSES) {
                // 保留输出消息的 id/status 以及注解等字段，不将输出项重建为不完整的输入 union。
                var decoded = new java.util.ArrayList<ContentBlock>();
                for (String snapshot : message.replayState().snapshots()) {
                    var value = OpenAiJson.decodeObject(json, snapshot);
                    decoded.addAll(ResponsesCodec.decodeItem(json, value));
                    if ("function_call".equals(value.path("type").asText()) && !callIds.add(value.path("call_id").asText())) {
                        throw new IllegalArgumentException("Duplicate tool call id");
                    }
                    input.add(value);
                }
                if (!decoded.equals(message.content())) throw new IllegalArgumentException("Output snapshots do not match message content");
                continue;
            }
            if (message.role() == ChatMessage.Role.TOOL) {
                input.addObject().put("type", "function_call_output").put("call_id", message.toolCallId()).put("output", message.text());
                continue;
            }
            if (message.content().size() == 1 && message.content().getFirst() instanceof ContentBlock.Text only) {
                input.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT)).put("content", only.text());
                continue;
            }
            // 连续普通内容放在同一消息中；推理和工具调用保持独立项与原始顺序。
            ArrayNode parts = null;
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolCall call) {
                    parts = null;
                    if (!callIds.add(call.id())) throw new IllegalArgumentException("Duplicate tool call id");
                    OpenAiJson.requireArguments(json, call.arguments());
                    input.addObject().put("type", "function_call").put("call_id", call.id()).put("name", call.name()).put("arguments", call.arguments());
                } else if (block instanceof ContentBlock.Reasoning reasoning) {
                    parts = null;
                    var value = input.addObject().put("type", "reasoning").put("id", reasoning.id());
                    var summary = value.putArray("summary");
                    reasoning.summary().forEach(text -> summary.addObject().put("type", "summary_text").put("text", text));
                    if (!reasoning.content().isEmpty()) {
                        var content = value.putArray("content");
                        reasoning.content().forEach(text -> content.addObject().put("type", "reasoning_text").put("text", text));
                    }
                    if (reasoning.encryptedContent() != null) value.put("encrypted_content", reasoning.encryptedContent());
                } else {
                    if (parts == null) {
                        var value = input.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
                        parts = value.putArray("content");
                    }
                    part(parts, block, true, message.role());
                }
            }
        }
    }

    private static void part(ArrayNode parts, ContentBlock block, boolean responses, ChatMessage.Role role) {
        ObjectNode part = parts.addObject();
        switch (block) {
            case ContentBlock.Text value -> part.put("type", responses ? "input_text" : "text").put("text", value.text());
            case ContentBlock.Refusal value -> {
                if (responses) throw new IllegalArgumentException("Refusal replay requires preserved Responses output items");
                part.put("type", "refusal").put("refusal", value.text());
            }
            case ContentBlock.Image value -> {
                part.put("type", responses ? "input_image" : "image_url");
                ObjectNode target = responses ? part : part.putObject("image_url");
                target.put(responses ? "image_url" : "url", value.url());
                if (value.detail() != null) target.put("detail", value.detail());
            }
            case ContentBlock.File value -> {
                part.put("type", responses ? "input_file" : "file");
                ObjectNode target = responses ? part : part.putObject("file");
                if (value.fileId() != null) target.put("file_id", value.fileId());
                else target.put("filename", value.filename()).put("file_data", value.fileData());
            }
            default -> throw new IllegalArgumentException("Content block cannot be encoded as a message part");
        }
    }
}
