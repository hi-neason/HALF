package io.github.hi.neason.half.model;

import java.util.Objects;

/** 模型事件是不可变快照；流失败通过 Subscriber.onError 传播。 */
public sealed interface ModelEvent {
    record TextDelta(String text) implements ModelEvent {
        public TextDelta { Objects.requireNonNull(text, "text"); }
    }

    record ThinkingDelta(int index, String text) implements ModelEvent {
        public ThinkingDelta {
            if (index < 0) throw new IllegalArgumentException("index must not be negative");
            Objects.requireNonNull(text, "text");
        }
    }

    record ThinkingCompleted(int index, ContentBlock.Thinking thinking) implements ModelEvent {
        public ThinkingCompleted {
            if (index < 0) throw new IllegalArgumentException("index must not be negative");
            Objects.requireNonNull(thinking, "thinking");
        }
    }

    record RefusalDelta(String text) implements ModelEvent {
        public RefusalDelta { Objects.requireNonNull(text, "text"); }
    }

    record ReasoningDelta(int outputIndex, int partIndex, boolean summary, String text) implements ModelEvent {
        public ReasoningDelta {
            if (outputIndex < 0 || partIndex < 0) throw new IllegalArgumentException("Indices must be nonnegative");
            Objects.requireNonNull(text, "text");
        }
    }

    record ReasoningCompleted(int outputIndex, ContentBlock.Reasoning reasoning) implements ModelEvent {
        public ReasoningCompleted {
            if (outputIndex < 0) throw new IllegalArgumentException("Index must be nonnegative");
            Objects.requireNonNull(reasoning, "reasoning");
        }
    }

    /** 元数据通常只在此调用的第一个分片出现。 */
    record ToolCallStarted(int index, String id, String name) implements ModelEvent {
        public ToolCallStarted {
            if (index < 0 || id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Invalid tool call start");
            }
        }
    }

    /** argumentsDelta 可能不是合法 JSON；只在参数全部到达后解析。 */
    record ToolCallDelta(int index, String argumentsDelta) implements ModelEvent {
        public ToolCallDelta {
            if (index < 0) throw new IllegalArgumentException("index must not be negative");
            Objects.requireNonNull(argumentsDelta, "argumentsDelta");
        }
    }

    record ToolCallCompleted(int index, ContentBlock.ToolCall call) implements ModelEvent {
        public ToolCallCompleted {
            if (index < 0) throw new IllegalArgumentException("index must not be negative");
            Objects.requireNonNull(call, "call");
        }
    }

    record Usage(TokenUsage usage) implements ModelEvent {
        public Usage { Objects.requireNonNull(usage, "usage"); }
    }

    /** 仅在收到协议终止标记后产生，随后发出 onComplete。 */
    record Completed(ChatResponse response) implements ModelEvent {
        public Completed { Objects.requireNonNull(response, "response"); }
    }
}
