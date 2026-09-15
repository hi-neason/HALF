package io.github.hi.neason.half.model.openai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Objects;

/** 原始 SSE 信封；不丢弃未知事件或 JSON 字段。每次 json() 返回独立树。 */
public record OpenAiSseEvent(String event, String data) {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public OpenAiSseEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(data, "data");
    }
    public JsonNode json() throws IOException {
        try {
            JsonNode node = JSON.readTree(data);
            if (node == null) throw new IOException("Empty SSE JSON");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IOException("Invalid SSE JSON");
        }
    }
}
