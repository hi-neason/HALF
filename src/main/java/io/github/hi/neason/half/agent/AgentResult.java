package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.tool.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** 单次运行的不可变快照；非正常结束的历史可能包含尚未执行的工具调用。 */
public record AgentResult(StopReason stopReason, int modelCalls, List<ChatMessage> messages,
                          List<ToolResult> toolResults, Optional<ChatResponse> lastResponse,
                          Optional<ModelFailure> modelFailure) {
    /** MAX_TURNS 沿用原名，表示本次用户 turn 的模型请求预算耗尽，不限制 Agent 或会话寿命。 */
    public enum StopReason { COMPLETED, MAX_TURNS, MODEL_ERROR, INCOMPLETE_RESPONSE, INVALID_TOOL_CALLS }

    /** 仅保留异常类别与可选 HTTP 状态，不携带响应正文、密钥或异常链。 */
    public record ModelFailure(String type, OptionalInt httpStatus) {
        public ModelFailure {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(httpStatus, "httpStatus");
        }
    }

    public AgentResult {
        Objects.requireNonNull(stopReason, "stopReason");
        if (modelCalls < 0) throw new IllegalArgumentException("modelCalls must not be negative");
        messages = List.copyOf(messages);
        toolResults = List.copyOf(toolResults);
        Objects.requireNonNull(lastResponse, "lastResponse");
        Objects.requireNonNull(modelFailure, "modelFailure");
        if ((stopReason == StopReason.MODEL_ERROR) != modelFailure.isPresent()) {
            throw new IllegalArgumentException("Only MODEL_ERROR requires modelFailure");
        }
        if (stopReason == StopReason.COMPLETED && lastResponse.isEmpty()) {
            throw new IllegalArgumentException("Completed runs require a response");
        }
    }

    public boolean completed() { return stopReason == StopReason.COMPLETED; }

    /** 部分响应可从 lastResponse 查看，不能作为最终答案。 */
    public String text() { return completed() ? lastResponse.orElseThrow().text() : ""; }
}
