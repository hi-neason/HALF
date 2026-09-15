package io.github.hi.neason.half.model.openai;

import io.github.hi.neason.half.model.http.HttpChatModel;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** OpenAI 的认证配置；HTTP 和 Flow 生命周期由供应商无关传输层实现。 */
abstract class OpenAiHttpModel extends HttpChatModel {
    @Override protected abstract String encodeRequest(io.github.hi.neason.half.model.ChatRequest request, boolean streaming) throws java.io.IOException;

    OpenAiHttpModel(URI endpoint, String apiKey, String model, Duration timeout) {
        super(endpoint, apiKey, model, timeout, Map.of("Authorization", "Bearer " + apiKey));
    }
}
