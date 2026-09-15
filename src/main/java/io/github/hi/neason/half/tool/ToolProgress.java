package io.github.hi.neason.half.tool;

import java.util.Objects;

/** 工具实际运行期间的进度；不作为模型最终结果回填。 */
public record ToolProgress(String callId, String toolName, String message, boolean truncated) {
    public ToolProgress {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(message, "message");
    }
}
