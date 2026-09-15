package io.github.hi.neason.half.examples;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelHttpException;
import io.github.hi.neason.half.model.ModelOptions;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.ResponseFormat;

import java.io.IOException;
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
            ChatResponse response;
            try {
                response = model.chat(requests.get(i));
            } catch (IOException error) {
                // 只记录失败类别和 HTTP 状态，不输出异常正文或认证信息。
                results.add(new Result(names.get(i), requestFailure(error), ""));
                continue;
            }
            results.add(new Result(names.get(i), validate(response), response.finishReason()));
        }
        return List.copyOf(results);
    }

    private static String requestFailure(IOException error) {
        if (error instanceof ModelHttpException http) {
            return "HTTP_ERROR:" + http.statusCode();
        }
        if (error instanceof ModelProtocolException) {
            return "PROTOCOL_ERROR";
        }
        return "TRANSPORT_ERROR:" + error.getClass().getSimpleName();
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
