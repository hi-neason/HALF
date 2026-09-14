package io.github.hi.neason.half.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelProtocolException;
import io.github.hi.neason.half.model.TokenUsage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** 每次请求独立的状态机：文本增量 → 结束原因 → 可选用量 → [DONE]。 */
final class OpenAiStream implements Flow.Subscriber<String> {
    private final ObjectMapper json;
    private final Consumer<String> onTextDelta;
    private final SseParser frames = new SseParser();
    private final StringBuilder text = new StringBuilder();
    private final CompletableFuture<ChatResponse> result = new CompletableFuture<>();
    private volatile Flow.Subscription subscription;
    private String finishReason;
    private Optional<TokenUsage> usage = Optional.empty();

    OpenAiStream(ObjectMapper json, Consumer<String> onTextDelta) {
        this.json = json;
        this.onTextDelta = onTextDelta;
    }

    CompletableFuture<ChatResponse> result() {
        return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        subscription = incoming;
        if (result.isDone()) {
            incoming.cancel();
        } else {
            incoming.request(1);
        }
    }

    @Override
    public void onNext(String line) {
        if (result.isDone()) {
            return;
        }
        try {
            Optional<String> data = frames.accept(line);
            if (data.isPresent()) {
                acceptEvent(data.get());
            }
            if (!result.isDone()) {
                subscription.request(1);
            }
        } catch (Throwable error) {
            // 协议异常或用户回调失败都必须唤醒等待线程并取消订阅。
            fail(error);
        }
    }

    private void acceptEvent(String data) throws ModelProtocolException {
        if ("[DONE]".equals(data)) {
            if (finishReason == null) {
                throw new ModelProtocolException("Stream ended without a finish reason");
            }
            result.complete(new ChatResponse(text.toString(), finishReason, usage));
            stopReading();
            return;
        }
        JsonNode root = OpenAiChatModel.decodeObject(json, data);
        JsonNode choices = root.path("choices");
        if (!choices.isArray()) {
            throw new ModelProtocolException("Expected streaming choices array");
        }
        if (choices.isEmpty()) {
            Optional<TokenUsage> counts = OpenAiChatModel.readUsage(root);
            if (finishReason == null || usage.isPresent() || counts.isEmpty()) {
                throw new ModelProtocolException("Expected a single usage chunk after the finish reason");
            }
            usage = counts;
            return;
        }
        if (finishReason != null || choices.size() != 1 || root.hasNonNull("usage")) {
            throw new ModelProtocolException("Unexpected choice or usage in model stream");
        }
        JsonNode choice = choices.get(0);
        JsonNode index = choice.path("index");
        if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() != 0) {
            throw new ModelProtocolException("Only streaming choice index 0 is supported");
        }
        JsonNode delta = choice.path("delta");
        if (!delta.isObject()) {
            throw new ModelProtocolException("Expected a delta object");
        }
        if (delta.has("role") && !"assistant".equals(delta.path("role").asText())) {
            throw new ModelProtocolException("Expected assistant delta role");
        }
        OpenAiChatModel.requireTextOnly(delta);
        JsonNode content = delta.get("content");
        if (content != null && !content.isNull() && !content.isTextual()) {
            throw new ModelProtocolException("Expected a text delta");
        }
        // 校验完整个 chunk 再交付文本，避免把工具调用或错误结构当作有效增量。
        if (choice.hasNonNull("finish_reason")) {
            finishReason = OpenAiChatModel.readFinishReason(choice);
        }
        if (content != null && content.isTextual() && !content.textValue().isEmpty()) {
            text.append(content.textValue());
            if (!result.isDone()) {
                onTextDelta.accept(content.textValue());
            }
        }
    }

    @Override
    public void onError(Throwable error) {
        fail(error);
    }

    @Override
    public void onComplete() {
        // SSE 的 EOF 不派发未以空行结束的事件，缺少 [DONE] 也不能假装成功。
        fail(new ModelProtocolException("Model stream closed before [DONE]"));
    }

    void fail(Throwable error) {
        // get() 会拆掉一层 CompletionException；额外包装以保留用户回调的原始异常。
        result.completeExceptionally(new CompletionException(error));
        stopReading();
    }

    void cancel() {
        result.cancel(false);
        stopReading();
    }

    private void stopReading() {
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }
}
