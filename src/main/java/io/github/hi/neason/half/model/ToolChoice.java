package io.github.hi.neason.half.model;

/** 函数工具选择策略；具体的协议 JSON 由模型实现负责。 */
public sealed interface ToolChoice {
    enum Mode implements ToolChoice { AUTO, NONE, REQUIRED }

    record Function(String name) implements ToolChoice {
        public Function {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid tool name");
            }
        }
    }
}
