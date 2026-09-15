package io.github.hi.neason.half.model.openai.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Flow;

/** Chat Completions 官方 HTTP 资源，包含存储完成项的管理接口。 */
public final class OpenAiChatCompletionsClient extends OpenAiApiClient {
    public OpenAiChatCompletionsClient(URI baseUri, String apiKey, Duration timeout) { super(baseUri, apiKey, timeout); }
    public JsonNode create(ObjectNode body) throws IOException, InterruptedException {
        return call("POST", "chat/completions", Map.of(), mode(body, false));
    }
    public Flow.Publisher<OpenAiSseEvent> stream(ObjectNode body) {
        return streamRequest("POST", "chat/completions", Map.of(), mode(body, true), false);
    }
    public JsonNode retrieve(String id) throws IOException, InterruptedException {
        return call("GET", "chat/completions/" + segment(id), Map.of(), null);
    }
    public JsonNode update(String id, ObjectNode body) throws IOException, InterruptedException {
        return call("POST", "chat/completions/" + segment(id), Map.of(), body);
    }
    public JsonNode delete(String id) throws IOException, InterruptedException {
        return call("DELETE", "chat/completions/" + segment(id), Map.of(), null);
    }
    public JsonNode list(Map<String, ?> query) throws IOException, InterruptedException {
        return call("GET", "chat/completions", query, null);
    }
    public JsonNode listMessages(String id, Map<String, ?> query) throws IOException, InterruptedException {
        return call("GET", "chat/completions/" + segment(id) + "/messages", query, null);
    }
}
