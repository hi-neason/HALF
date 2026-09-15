package io.github.hi.neason.half.model.openai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow;

/** Responses 官方 HTTP 资源；JSON 原样保留，服务端负责参数与模型能力校验。 */
public final class OpenAiResponsesClient extends OpenAiApiClient {
    public OpenAiResponsesClient(URI baseUri, String apiKey, Duration timeout) { super(baseUri, apiKey, timeout); }
    public JsonNode create(ObjectNode body) throws IOException, InterruptedException {
        return call("POST", "responses", Map.of(), mode(body, false));
    }
    public Flow.Publisher<OpenAiSseEvent> stream(ObjectNode body) {
        return streamRequest("POST", "responses", Map.of(), mode(body, true), true);
    }
    public JsonNode retrieve(String id) throws IOException, InterruptedException { return retrieve(id, Map.of()); }
    public JsonNode retrieve(String id, Map<String, ?> query) throws IOException, InterruptedException {
        var options = new LinkedHashMap<String, Object>(query);
        options.put("stream", false);
        return call("GET", "responses/" + segment(id), options, null);
    }
    /** 支持 starting_after / include 等官方查询参数；Iterable 值编码为重复的 key[]。 */
    public Flow.Publisher<OpenAiSseEvent> retrieveStream(String id, Map<String, ?> query) {
        var options = new LinkedHashMap<String, Object>(query);
        options.put("stream", true);
        return streamRequest("GET", "responses/" + segment(id), options, null, true);
    }
    public JsonNode delete(String id) throws IOException, InterruptedException {
        return call("DELETE", "responses/" + segment(id), Map.of(), null);
    }
    public JsonNode cancel(String id) throws IOException, InterruptedException {
        return call("POST", "responses/" + segment(id) + "/cancel", Map.of(), null);
    }
    public JsonNode listInputItems(String id, Map<String, ?> query) throws IOException, InterruptedException {
        return call("GET", "responses/" + segment(id) + "/input_items", query, null);
    }
    public JsonNode compact(ObjectNode body) throws IOException, InterruptedException {
        return call("POST", "responses/compact", Map.of(), body);
    }
    public JsonNode countInputTokens(ObjectNode body) throws IOException, InterruptedException {
        return call("POST", "responses/input_tokens", Map.of(), body);
    }
}
