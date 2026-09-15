package io.github.hi.neason.half.tool;

import io.github.hi.neason.half.model.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 一次构造的工具注册快照；定义与执行器绑定，按注册顺序提供模型声明。 */
public final class ToolRegistry {
    private final Map<String, Tool> tools;
    private final List<ToolDefinition> definitions;

    public ToolRegistry(List<? extends Tool> tools) {
        var byName = new HashMap<String, Tool>();
        var declarations = new ArrayList<ToolDefinition>();
        for (Tool tool : List.copyOf(tools)) {
            var definition = Objects.requireNonNull(tool.definition(), "tool definition");
            if (byName.putIfAbsent(definition.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
            }
            declarations.add(definition);
        }
        this.tools = Map.copyOf(byName);
        this.definitions = List.copyOf(declarations);
    }

    public List<ToolDefinition> definitions() { return definitions; }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(Objects.requireNonNull(name, "name")));
    }
}
