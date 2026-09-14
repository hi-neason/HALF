package io.github.hi.neason.half.model;

import java.util.Objects;

/** 工具能力声明，不包含执行代码；parametersJson 为 JSON Schema 对象。 */
public record ToolDefinition(String name, String description, String parametersJson) {
    public ToolDefinition {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid tool name");
        }
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
    }
}
