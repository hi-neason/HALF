package io.github.hi.neason.half.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ToolDefinition;
import io.github.hi.neason.half.tool.Tool;
import io.github.hi.neason.half.tool.ToolArgumentsException;

/** 无外部副作用的示例工具；声明 Schema 的同时自行检查相同的参数约束。 */
public final class AddTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition("add", "Add two 32-bit integers", """
            {"type":"object","properties":{
              "a":{"type":"integer","minimum":-2147483648,"maximum":2147483647},
              "b":{"type":"integer","minimum":-2147483648,"maximum":2147483647}},
             "required":["a","b"],"additionalProperties":false}
            """, true);

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public String execute(ObjectNode arguments) throws ToolArgumentsException {
        if (arguments.size() != 2 || !isInteger(arguments.get("a")) || !isInteger(arguments.get("b"))) {
            throw new ToolArgumentsException();
        }
        long sum = (long) arguments.get("a").intValue() + arguments.get("b").intValue();
        return Long.toString(sum);
    }

    private static boolean isInteger(JsonNode value) {
        return value != null && value.isNumber() && value.canConvertToInt()
                && value.decimalValue().stripTrailingZeros().scale() <= 0;
    }
}
