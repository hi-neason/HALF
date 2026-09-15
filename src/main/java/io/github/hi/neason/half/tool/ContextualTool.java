package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 需要调用上下文或结构化详情的工具；通过 ToolExecutor 执行以获得有效上下文。 */
public interface ContextualTool extends Tool {
    @Override
    ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception;

    @Override
    default String execute(ObjectNode arguments) {
        throw new UnsupportedOperationException("Execute contextual tools through ToolExecutor");
    }
}
