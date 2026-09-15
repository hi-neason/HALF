package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ToolDefinition;

/** 宿主显式提供的 Java 工具；定义用于模型请求，执行代码留在宿主中。 */
public interface Tool {
    ToolDefinition definition();

    /**
     * 在产生副作用前校验字段及业务约束，不合法时抛出 ToolArgumentsException。
     * 参数已通过 JSON 对象格式检查，但尚未通过工具的语义校验。
     * 阻塞操作应支持中断；返回供模型读取的文本，不返回 null。
     */
    String execute(ObjectNode arguments) throws Exception;

    /** 执行器使用此入口；旧文本工具自动适配，需要上下文的工具实现 ContextualTool。 */
    default ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception {
        String text = execute(arguments);
        return text == null ? null : new ToolOutput(text);
    }
}
