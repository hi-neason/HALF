package io.github.hi.neason.half.model.http;

import java.util.Optional;

/** 按行解析 SSE 数据事件；换行拆分和 UTF-8 解码由调用方负责。 */
public final class SseParser {
    private final StringBuilder data = new StringBuilder();
    private boolean firstLine;
    private boolean hasData;
    private String event = "message";

    public SseParser() {
        this(true);
    }

    SseParser(boolean stripInitialBom) {
        firstLine = stripInitialBom;
    }

    /** 仅空行触发派发；流结束时未完成的事件不派发。 */
    public Optional<String> accept(String line) {
        return acceptFrame(line).map(SseFrame::data);
    }

    /** 同时保留 event 名称，供完整 JSON/SSE 客户端使用。 */
    public Optional<SseFrame> acceptFrame(String line) {
        if (firstLine) {
            firstLine = false;
            if (line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
        }
        if (line.isEmpty()) {
            Optional<SseFrame> frame = hasData
                    ? Optional.of(new SseFrame(event, data.toString())) : Optional.empty();
            data.setLength(0);
            hasData = false;
            event = "message";
            return frame;
        }

        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if (field.equals("event")) {
            event = value.isEmpty() ? "message" : value;
        } else if (field.equals("data")) {
            if (hasData) {
                data.append('\n');
            }
            data.append(value);
            hasData = true;
        }
        return Optional.empty();
    }
}
