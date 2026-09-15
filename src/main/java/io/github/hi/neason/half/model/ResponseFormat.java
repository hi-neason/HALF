package io.github.hi.neason.half.model;

import java.util.Objects;

/** JSON Schema 使用原始 JSON 字符串保存；不把 JSON 库类型暴露给调用者。 */
public sealed interface ResponseFormat {
    record Text() implements ResponseFormat {}
    record JsonObject() implements ResponseFormat {}
    record JsonSchema(String name, String description, String schemaJson, Boolean strict) implements ResponseFormat {
        public JsonSchema {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new IllegalArgumentException("Invalid response schema name");
            }
            Objects.requireNonNull(schemaJson, "schemaJson");
        }
    }
}
