package io.github.hi.neason.half.model;

import java.util.Objects;

/** 不可变内容块；工具参数仍是不可信输入，执行前由宿主校验。 */
public sealed interface ContentBlock {
    record Text(String text) implements ContentBlock {
        public Text { Objects.requireNonNull(text, "text"); }
    }

    record ToolCall(String id, String name, String arguments) implements ContentBlock {
        public ToolCall {
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Tool call id and name must not be blank");
            }
            Objects.requireNonNull(arguments, "arguments");
        }
    }
}
