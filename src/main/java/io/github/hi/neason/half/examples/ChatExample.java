package io.github.hi.neason.half.examples;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.openai.OpenAiChatModel;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** 显式执行本示例才会调用模型服务；测试使用本地 HTTP 服务。 */
public final class ChatExample {
    private ChatExample() {}

    public static void main(String[] args) throws Exception {
        boolean streaming = args.length > 0 && "--stream".equals(args[0]);
        String[] promptArgs = streaming ? Arrays.copyOfRange(args, 1, args.length) : args;
        String prompt = promptArgs.length == 0
                ? "用一句话解释 LLM 的 messages 参数。" : String.join(" ", promptArgs);
        try (var provider = new OpenAiChatModel(
                URI.create(requiredEnv("HALF_MODEL_ENDPOINT")),
                requiredEnv("HALF_API_KEY"),
                requiredEnv("HALF_MODEL"), Duration.ofSeconds(60))) {
            // 应用依赖 ChatModel 接口；供应商协议只在具体实现中出现。
            ChatModel model = provider;
            ChatRequest request = new ChatRequest(List.of(ChatMessage.user(prompt)));
            ChatResponse response;
            if (streaming) {
                response = model.stream(request, delta -> {
                    System.out.print(delta);
                    System.out.flush();
                });
                System.out.println();
            } else {
                response = model.chat(request);
                System.out.println(response.text());
            }
            System.out.println("finish_reason=" + response.finishReason());
            response.usage().ifPresent(usage -> System.out.println("usage=" + usage));
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set environment variable " + name);
        }
        return value;
    }
}
