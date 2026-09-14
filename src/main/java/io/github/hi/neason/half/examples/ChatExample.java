package io.github.hi.neason.half.examples;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.openai.OpenAiChatModel;
import io.github.hi.neason.half.model.openai.OpenAiResponsesModel;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 显式执行本示例才会调用模型服务；测试使用本地 HTTP 服务。 */
public final class ChatExample {
    private ChatExample() {}

    public static void main(String[] args) throws Exception {
        URI endpoint = URI.create(requiredEnv("HALF_MODEL_ENDPOINT"));
        String key = requiredEnv("HALF_API_KEY"), model = requiredEnv("HALF_MODEL");
        String api = System.getenv().getOrDefault("HALF_MODEL_API", "chat-completions");
        switch (api) {
            case "chat-completions" -> {
                try (var provider = new OpenAiChatModel(endpoint, key, model, Duration.ofSeconds(60))) {
                    run(provider, args);
                }
            }
            case "responses" -> {
                try (var provider = new OpenAiResponsesModel(endpoint, key, model, Duration.ofSeconds(60))) {
                    run(provider, args);
                }
            }
            default -> throw new IllegalArgumentException("HALF_MODEL_API must be chat-completions or responses");
        }
    }

    private static void run(ChatModel model, String[] args) throws Exception {
        boolean streaming = args.length > 0 && "--stream".equals(args[0]);
        boolean events = args.length > 0 && "--events".equals(args[0]);
        String[] promptArgs = streaming || events ? Arrays.copyOfRange(args, 1, args.length) : args;
        String prompt = promptArgs.length == 0
                ? "用一句话解释 LLM 的 messages 参数。" : String.join(" ", promptArgs);
        ChatRequest request = new ChatRequest(List.of(ChatMessage.user(prompt)));
        ChatResponse response;
        if (events) {
            response = readEvents(model, request);
            System.out.println();
        } else if (streaming) {
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
        response.toolCalls().forEach(call -> System.out.println("tool_call=" + call));
    }

    private static ChatResponse readEvents(ChatModel model, ChatRequest request) throws Exception {
        var result = new CompletableFuture<ChatResponse>();
        var subscription = new AtomicReference<Flow.Subscription>();
        model.stream(request).subscribe(new Flow.Subscriber<>() {
            private ChatResponse response;
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
                value.request(1);
            }
            @Override public void onNext(ModelEvent event) {
                switch (event) {
                    case ModelEvent.TextDelta delta -> {
                        System.out.print(delta.text());
                        System.out.flush();
                    }
                    case ModelEvent.Completed completed -> response = completed.response();
                    case ModelEvent.ToolCallStarted started -> System.out.println("\ntool_started=" + started);
                    case ModelEvent.ToolCallDelta delta -> System.out.println("\ntool_arguments_delta=" + delta);
                    case ModelEvent.ToolCallCompleted completed -> System.out.println("\ntool_completed=" + completed.call());
                    case ModelEvent.Usage usage -> System.out.println("\nusage=" + usage.usage());
                }
                // 本事件处理完毕，再请求下一条；工具参数增量也占用一个需求量。
                subscription.get().request(1);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(response); }
        });
        try {
            return result.get(60, TimeUnit.SECONDS);
        } finally {
            subscription.get().cancel();
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
