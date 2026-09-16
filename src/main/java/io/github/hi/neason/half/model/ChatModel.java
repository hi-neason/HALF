package io.github.hi.neason.half.model;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.concurrent.Flow;

/** 模型的最小抽象：输入消息，得到一次完整响应；不维护会话或推进 Agent 循环。 */
@FunctionalInterface
public interface ChatModel {

    /** 基础的同步方式单次大模型调用 */
    ChatResponse chat(ChatRequest request) throws IOException, InterruptedException;

    /** 冷流：每次订阅独立调用模型，正数 request(n) 后才开始请求；取消停止该次调用。 */
    default Flow.Publisher<ModelEvent> stream(ChatRequest request) {
        throw new UnsupportedOperationException("This model does not support streaming");
    }

    /**
     * 阻塞至流结束，按序回调非空文本增量并返回完整响应。
     * 回调可能在读取线程执行，应快速返回；失败前已交付的增量不能撤回。
     * 未实现流式能力的模型明确报错，不用完整响应模拟流式输出。
     */
    default ChatResponse stream(ChatRequest request, Consumer<String> onTextDelta)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("This model does not support streaming");
    }
}
