package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ModelProtocolException;

/** 两个协议共用的 JSON 形状校验；异常不包含响应正文。 */
final class OpenAiJson {
    private OpenAiJson() {}

    static JsonNode decodeObject(ObjectMapper json, String body) throws ModelProtocolException {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JsonProcessingException exception) {
            // Jackson 的异常可能带有响应正文，避免把它连同敏感内容向外传播。
            throw new ModelProtocolException("Model response is not valid JSON");
        }
        if (root == null || !root.isObject() || root.hasNonNull("error")) {
            throw new ModelProtocolException("Expected a successful model response object");
        }
        return root;
    }

    static void requireArguments(ObjectMapper json, String arguments) throws ModelProtocolException {
        // JSON 语法与对象形状校验，不等于工具 Schema 校验或执行授权。
        try {
            JsonNode parsed = json.readTree(arguments);
            if (parsed == null || !parsed.isObject()) throw new ModelProtocolException("Tool arguments must be a JSON object");
        } catch (JsonProcessingException error) {
            throw new ModelProtocolException("Tool arguments must be a JSON object");
        }
    }

}
