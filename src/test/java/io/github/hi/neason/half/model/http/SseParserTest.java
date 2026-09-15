package io.github.hi.neason.half.model.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SseParserTest {
    @Test
    void dispatchesMultilinePayloadOnlyAtBlankLineAndResetsBetweenEvents() {
        var parser = new SseParser();
        assertTrue(parser.accept("data: 第一行").isEmpty());
        assertTrue(parser.accept("data: 第二行").isEmpty());
        assertEquals(Optional.of("第一行\n第二行"), parser.accept(""));
        assertTrue(parser.accept("").isEmpty());
        assertTrue(parser.accept("data: next").isEmpty());
        assertEquals(Optional.of("next"), parser.accept(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "data:", "data: "})
    void emptyDataIsAnEvent(String line) {
        var parser = new SseParser();
        assertTrue(parser.accept(line).isEmpty());
        assertEquals(Optional.of(""), parser.accept(""));
    }

    @Test
    void preservesEmptyDataLinesAndWhitespaceExceptOneLeadingSpace() {
        var parser = new SseParser();
        parser.accept("data:");
        parser.accept("data:  indented: value  ");
        parser.accept("data:\ttab");
        parser.accept("data");
        assertEquals(Optional.of("\n indented: value  \n\ttab\n"), parser.accept(""));
    }

    @Test
    void ignoresCommentsAndMetadataWithoutBreakingPendingData() {
        var parser = new SseParser();
        for (String line : new String[]{": heartbeat", "event: message", "id: 1", "retry: 1000",
                "unknown: value", "DATA: ignored", " data: ignored", " "}) {
            assertTrue(parser.accept(line).isEmpty());
        }
        assertTrue(parser.accept("").isEmpty());
        parser.accept("data: a");
        parser.accept(": heartbeat");
        parser.accept("event: custom");
        parser.accept("data: b");
        assertEquals(Optional.of("a\nb"), parser.accept(""));
    }

    @Test
    void removesOneBomOnlyFromTheFirstLineOfTheStream() {
        var parser = new SseParser();
        parser.accept("\uFEFFdata: first");
        assertEquals(Optional.of("first"), parser.accept(""));
        parser.accept("\uFEFFdata: ignored");
        assertTrue(parser.accept("").isEmpty());
        parser.accept("data: \uFEFFpreserved");
        assertEquals(Optional.of("\uFEFFpreserved"), parser.accept(""));

        var doubleBom = new SseParser();
        doubleBom.accept("\uFEFF\uFEFFdata: ignored");
        assertTrue(doubleBom.accept("").isEmpty());
    }

    @Test
    void firstLineMayBeAnEmptyBomLine() {
        var parser = new SseParser();
        assertTrue(parser.accept("\uFEFF").isEmpty());
        parser.accept("\uFEFFdata: ignored");
        assertTrue(parser.accept("").isEmpty());
    }

    @Test
    void leavesApplicationPayloadInterpretationToCaller() {
        var parser = new SseParser();
        parser.accept("data: [DONE]");
        assertEquals(Optional.of("[DONE]"), parser.accept(""));
        parser.accept("data: not-json");
        assertEquals(Optional.of("not-json"), parser.accept(""));
    }

    @Test
    void unfinishedEventDoesNotDispatchOrLeakIntoAnotherParser() {
        var unfinished = new SseParser();
        assertTrue(unfinished.accept("data: unfinished").isEmpty());
        var nextStream = new SseParser();
        assertTrue(nextStream.accept("").isEmpty());
        nextStream.accept("data: complete");
        assertEquals(Optional.of("complete"), nextStream.accept(""));
    }
}
