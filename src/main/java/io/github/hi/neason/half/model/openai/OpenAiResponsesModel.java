package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.http.ModelStream;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.ToolDefinition;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/** Responses 文本与函数调用协议；宿主传入完整历史，不使用服务端会话。 */
public final class OpenAiResponsesModel extends OpenAiHttpModel {
    /** endpoint 为完整 URL，例如 https://api.openai.com/v1/responses。 */
    public OpenAiResponsesModel(URI endpoint, String apiKey, String model, Duration timeout) {
        super(endpoint, apiKey, model, timeout);
    }

    @Override
    protected ModelStream.EventDecoder newEventDecoder() { return new OpenAiResponsesEventDecoder(json)::accept; }

    @Override
    protected String encodeRequest(ChatRequest request, boolean streaming) throws IOException {
        var root = json.createObjectNode();
        root.put("model", model).put("stream", streaming).put("store", false);
        if (request.maxOutputTokens() != null) root.put("max_output_tokens", request.maxOutputTokens());
        OpenAiContent.responses(json, root.putArray("input"), request.messages());
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
                        .put("description", definition.description()).put("strict", Boolean.TRUE.equals(definition.strict())).set("parameters", schema);
            }
        }
        OpenAiRequestOptions.apply(json, root, request, true);
        return json.writeValueAsString(root);
    }

    @Override
    protected ChatResponse decodeResponse(String body) throws ModelProtocolException {
        return ResponsesCodec.decode(json, OpenAiJson.decodeObject(json, body));
    }

}
