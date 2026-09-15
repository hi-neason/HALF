package io.github.hi.neason.half.model.http;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/** 有界字符读取；与行订阅入口共用 SSE 字段解析，避免 readLine 先分配无界字符串。 */
public final class SseFrameReader {
    private final Reader reader;
    private final int maxFrameCharacters;
    private final SseParser parser = new SseParser(false);
    private boolean first = true;
    private boolean afterCr;

    public SseFrameReader(Reader reader, int maxFrameCharacters) {
        this.reader = Objects.requireNonNull(reader, "reader");
        if (maxFrameCharacters <= 0) throw new IllegalArgumentException("Frame limit must be positive");
        this.maxFrameCharacters = maxFrameCharacters;
    }

    public SseFrame next() throws IOException {
        var line = new StringBuilder();
        int count = 0;
        for (;;) {
            int value = reader.read();
            if (value < 0) return null;
            if (first) {
                first = false;
                if (value == '\uFEFF') continue;
            }
            if (afterCr) {
                afterCr = false;
                if (value == '\n') continue;
            }
            if (++count > maxFrameCharacters) throw new IOException("SSE frame exceeds character limit");
            if (value != '\n' && value != '\r') {
                line.append((char) value);
                continue;
            }
            afterCr = value == '\r';
            var frame = parser.acceptFrame(line.toString());
            if (line.isEmpty()) count = 0;
            line.setLength(0);
            if (frame.isPresent()) return frame.get();
        }
    }
}
