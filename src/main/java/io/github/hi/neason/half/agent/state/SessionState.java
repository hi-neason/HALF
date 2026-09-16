package io.github.hi.neason.half.agent.state;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ContentBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 会话历史及其工具调用 ID 索引，与单次 turn 的预算和结果分离。
 * 从宿主传入的历史复制创建；延续会话时，宿主传回已完成 turn 的历史。
 * 不作为 Agent 的共享字段，避免并发调用和多次订阅互相修改历史。
 */
public final class SessionState {
    private final List<ChatMessage> messages;
    private final Set<String> seenCallIds;

    public SessionState(List<ChatMessage> history) {
        messages = new ArrayList<>(history);
        seenCallIds = validateHistory(messages);
    }

    /** 返回当前历史的不可变快照。 */
    public List<ChatMessage> messages() { return List.copyOf(messages); }

    public void appendMessage(ChatMessage message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    /** 注册新调用 ID；重复时返回 false，供循环在执行工具前拒绝本批调用。 */
    public boolean registerCallId(String callId) {
        return seenCallIds.add(Objects.requireNonNull(callId, "callId"));
    }

    private static Set<String> validateHistory(List<ChatMessage> messages) {
        var seen = new HashSet<String>();
        var pending = new HashSet<String>();
        for (var message : messages) {
            if (message.role() == ChatMessage.Role.TOOL) {
                if (!pending.remove(message.toolCallId())) {
                    throw new IllegalArgumentException("History contains an unmatched tool result");
                }
                continue;
            }
            if (!pending.isEmpty()) throw new IllegalArgumentException("History contains unresolved tool calls");
            for (var content : message.content()) {
                if (content instanceof ContentBlock.ToolCall call) {
                    if (!seen.add(call.id())) throw new IllegalArgumentException("History contains duplicate tool call ids");
                    pending.add(call.id());
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("History contains unresolved tool calls");
        return seen;
    }
}
