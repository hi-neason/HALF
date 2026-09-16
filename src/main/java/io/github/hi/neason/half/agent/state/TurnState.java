package io.github.hi.neason.half.agent.state;

import io.github.hi.neason.half.agent.AgentResult;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 每次 run 或流订阅新建的执行状态；请求计数、响应与工具结果不从历史中继承。 */
public final class TurnState {
    private final TurnOptions options;
    private final List<ToolResult> toolResults = new ArrayList<>();
    private ChatResponse response;
    private int modelCalls;

    public TurnState(TurnOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** 在请求发出前计数，失败的请求也占用本次 turn 的预算。 */
    public int beginModelCall() { return ++modelCalls; }

    public int modelCalls() { return modelCalls; }

    public boolean isModelCallLimitReached() {
        return options.isModelCallLimitReached(modelCalls);
    }

    /** 仅在模型成功返回后更新；后续请求失败时保留此前响应。 */
    public void recordResponse(ChatResponse response) {
        this.response = Objects.requireNonNull(response, "model response");
    }

    /** 尚未收到成功响应时为 null。 */
    public ChatResponse response() { return response; }

    public void recordToolResult(ToolResult result) {
        toolResults.add(Objects.requireNonNull(result, "result"));
    }

    public AgentResult result(SessionState session, AgentResult.StopReason reason,
                       Optional<AgentResult.ModelFailure> failure) {
        return new AgentResult(reason, modelCalls, session.messages(), toolResults,
                Optional.ofNullable(response), failure);
    }
}
