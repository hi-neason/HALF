package io.github.hi.neason.half.examples;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.hi.neason.half.model.*;

import java.util.ArrayList;
import java.util.List;

/** 显式运行才调用真实服务；比较提示词效果与 Schema 请求效果，不修补模型正文。 */
public final class StructuredOutputExample {
    private static final String PROMPT = "返回 ok 为 true 的 JSON 对象。";
    private static final String STRICT_PROMPT = "只输出一个 JSON 对象，且只有 ok 一个字段，值必须为布尔值 true。"
            + "不要输出解释、Markdown、代码围栏或其他内容。";
    private static final String SCHEMA = """
            {"type":"object","properties":{"ok":{"type":"boolean"}},
             "required":["ok"],"additionalProperties":false}
            """;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private StructuredOutputExample() { }

    public record Result(String testCase, String status, String finishReason) { }

    /** 每组独立请求一次，均使用相同模型、输出预算和业务要求，不自动重试。 */
    public static List<Result> run(ChatModel model) throws InterruptedException {
        var schema = ModelOptions.builder().responseFormat(
                new ResponseFormat.JsonSchema("health", null, SCHEMA, true)).build();
        var requests = List.of(
                new ChatRequest(List.of(ChatMessage.user(STRICT_PROMPT)), 2048),
                new ChatRequest(List.of(ChatMessage.user(PROMPT)), 2048, List.of(), schema),
                new ChatRequest(List.of(ChatMessage.user(STRICT_PROMPT)), 2048, List.of(), schema));
        var names = List.of("prompt_only", "schema_only", "prompt_and_schema");
        var results = new ArrayList<Result>();
        for (int i = 0; i < requests.size(); i++) {
            try {
                var response = model.chat(requests.get(i));
                results.add(new Result(names.get(i), validate(response), response.finishReason()));
            } catch (java.io.IOException | RuntimeException error) {
                // 不输出异常正文，避免把服务端响应或认证信息写进测试报告。
                results.add(new Result(names.get(i), "REQUEST_ERROR:" + error.getClass().getSimpleName(), ""));
            }
        }
        return List.copyOf(results);
    }

    /** 只校验本用例的固定 Schema，不宣称实现通用 JSON Schema 校验器。 */
    static String validate(ChatResponse response) {
        if (!List.of("stop", "completed", "end_turn").contains(response.finishReason())) return "INCOMPLETE_OR_NON_TEXT_STOP";
        if (!response.toolCalls().isEmpty() || !response.refusal().isEmpty()) return "UNEXPECTED_CONTENT";
        if (response.text().isBlank()) return "EMPTY_TEXT";
        try {
            var value = JSON.readTree(response.text());
            if (!value.isObject() || value.size() != 1 || !value.path("ok").isBoolean()) return "SCHEMA_MISMATCH";
            return value.path("ok").booleanValue() ? "PASS" : "VALUE_MISMATCH";
        } catch (java.io.IOException error) {
            return "INVALID_JSON";
        }
    }

}
