package io.github.hi.neason.half.model.http;

import java.util.Optional;

/** 按行解析 SSE 数据事件；换行拆分和 UTF-8 解码由调用方负责。 */
public final class SseParser {
    private final StringBuilder data = new StringBuilder();
    private boolean firstLine = true;
    private boolean hasData;

    /** 仅空行触发派发；流结束时未完成的事件不派发。 */
    public Optional<String> accept(String line) {
        if (firstLine) {
            firstLine = false;
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
        }
        if (line.isEmpty()) {
            if (!hasData) {
                return Optional.empty();
            }
            String payload = data.toString();
            data.setLength(0);
            hasData = false;
            return Optional.of(payload);
        }

        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        if (!field.equals("data")) {
            return Optional.empty();
        }
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if (hasData) {
            data.append('\n');
        }
        data.append(value);
        hasData = true;
        return Optional.empty();
    }
}
