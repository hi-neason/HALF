package io.github.hi.neason.half.agent;

import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.tool.ToolProgress;
import io.github.hi.neason.half.tool.ToolResult;

import java.util.Objects;

/** 单次 Agent 运行的事件；turn 从 1 开始，Completed 表示循环已停止，是否成功由 result 判断。 */
public sealed interface AgentEvent {
    record TurnStarted(int turn) implements AgentEvent {
        public TurnStarted { requireTurn(turn); }
    }

    /** 保留模型的全部事件类型；其中 ModelEvent.Completed 只代表一轮模型响应。 */
    record Model(int turn, ModelEvent event) implements AgentEvent {
        public Model {
            requireTurn(turn);
            Objects.requireNonNull(event, "event");
        }
    }

    /** 已通过循环预检、即将交给工具执行器；未知工具和非法参数仍可能产生错误结果。 */
    record ToolStarted(int turn, ContentBlock.ToolCall call) implements AgentEvent {
        public ToolStarted {
            requireTurn(turn);
            Objects.requireNonNull(call, "call");
        }
    }

    record ToolProgressed(int turn, ToolProgress progress) implements AgentEvent {
        public ToolProgressed {
            requireTurn(turn);
            Objects.requireNonNull(progress, "progress");
        }
    }

    record ToolCompleted(int turn, ToolResult result) implements AgentEvent {
        public ToolCompleted {
            requireTurn(turn);
            Objects.requireNonNull(result, "result");
        }
    }

    record Completed(AgentResult result) implements AgentEvent {
        public Completed { Objects.requireNonNull(result, "result"); }
    }

    private static void requireTurn(int turn) {
        if (turn <= 0) throw new IllegalArgumentException("turn must be positive");
    }
}
