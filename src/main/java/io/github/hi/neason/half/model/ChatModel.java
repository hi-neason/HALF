package io.github.hi.neason.half.model;

import java.io.IOException;

/** 模型的最小抽象：输入消息，得到一次完整响应；不维护会话或推进 Agent 循环。 */
@FunctionalInterface
public interface ChatModel {
    ChatResponse chat(ChatRequest request) throws IOException, InterruptedException;
}
