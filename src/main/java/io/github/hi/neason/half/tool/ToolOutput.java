package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Objects;

/** 模型可见文本与宿主专用结构化详情；JSON 在输入和读取时均复制。 */
public record ToolOutput(String text, JsonNode details) {
    public ToolOutput {
        Objects.requireNonNull(text, "text");
        details = copyDetails(details);
    }

    public ToolOutput(String text) { this(text, NullNode.instance); }

    @Override public JsonNode details() { return details.deepCopy(); }

    static JsonNode copyDetails(JsonNode details) {
        validateDetails(Objects.requireNonNull(details, "details"), 0);
        return details.deepCopy();
    }

    private static void validateDetails(JsonNode value, int depth) {
        if (depth > 128 || value.isPojo() || value.isBinary() || value.isMissingNode()
                || ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue()))) {
            throw new IllegalArgumentException("Details must be a JSON tree with depth at most 128");
        }
        for (JsonNode child : value) validateDetails(child, depth + 1);
    }

    static String limit(String text, int maximum) {
        if (text.length() <= maximum) return text;
        String marker = "\n[truncated]";
        int end = maximum - marker.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end) + marker;
    }
}
