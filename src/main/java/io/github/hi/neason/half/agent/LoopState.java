package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 单次 Agent 运行独享的状态，由 AgentLoop 创建和更新，不跨运行共享。
 * 负责初始历史校验与结果快照；模型调用、工具执行和停止策略由 AgentLoop 管理。
 */
final class LoopState {
    final List<ChatMessage> messages;
    final Set<String> seenCallIds;
    final List<ToolResult> toolResults = new ArrayList<>();
    ChatResponse response;
    int modelCalls;

    LoopState(List<ChatMessage> history) {
        messages = new ArrayList<>(history);
        seenCallIds = validateHistory(messages);
    }

    AgentResult result(AgentResult.StopReason reason, Optional<AgentResult.ModelFailure> failure) {
        return new AgentResult(reason, modelCalls, messages, toolResults, Optional.ofNullable(response), failure);
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
