package io.github.hi.neason.half.model.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class SseFrameReaderTest {
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n"})
    void preservesEventFieldsAndMultilineDataAcrossLineEndings(String newline) throws IOException {
        String text = String.join(newline, "\uFEFF: heartbeat", "event: custom", "data: 第一行",
                "data:  第二行", "", "event: ignored", "", "data", "", "");
        var reader = new SseFrameReader(new StringReader(text), 256);
        assertEquals(new SseFrame("custom", "第一行\n 第二行"), reader.next());
        assertEquals(new SseFrame("message", ""), reader.next());
        assertNull(reader.next());
    }

    @Test
    void stripsOnlyOneInitialBomAndDoesNotDispatchPartialEventAtEof() throws IOException {
        var reader = new SseFrameReader(new StringReader(
                "\uFEFF\uFEFFdata: ignored\n\ndata: kept\n\ndata: unfinished\n"), 100);
        assertEquals(new SseFrame("message", "kept"), reader.next());
        assertNull(reader.next());
    }

    @Test
    void boundsWholeFramesIncludingCommentsAndTerminatorsAndResetsAtBlankLines() throws IOException {
        String frame = "data:x\n\n";
        var exact = new SseFrameReader(new StringReader(":abc\n\n" + frame.repeat(2)), frame.length());
        assertEquals(new SseFrame("message", "x"), exact.next());
        assertEquals(new SseFrame("message", "x"), exact.next());
        assertNull(exact.next());
        var oversized = new SseFrameReader(new StringReader(frame), frame.length() - 1);
        assertThrows(IOException.class, oversized::next);
        var comment = new SseFrameReader(new StringReader(":" + "x".repeat(20)), 8);
        assertThrows(IOException.class, comment::next);
        var multiline = new SseFrameReader(new StringReader("data:x\ndata:y\n\n"), 10);
        assertThrows(IOException.class, multiline::next);
    }
}
