package io.github.hi.neason.half.examples;

import io.github.hi.neason.half.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StructuredOutputExampleTest {
    @ParameterizedTest
    @CsvSource(value = {
            "{\"ok\":true}|PASS",
            "{\"ok\":false}|VALUE_MISMATCH",
            "{\"ok\":\"true\"}|SCHEMA_MISMATCH",
            "{\"ok\":1}|SCHEMA_MISMATCH",
            "{}|SCHEMA_MISMATCH",
            "[]|SCHEMA_MISMATCH",
            "null|SCHEMA_MISMATCH",
            "{\"ok\":true,\"extra\":1}|SCHEMA_MISMATCH",
            "结果：{\"ok\":true}|INVALID_JSON",
            "```json {\"ok\":true} ```|INVALID_JSON",
            "{\"ok\":true} {}|INVALID_JSON",
            "{\"ok\":true,\"ok\":true}|INVALID_JSON"
    }, delimiter = '|')
    void distinguishesFormatSchemaAndValueFailures(String text, String expected) {
        assertEquals(expected, StructuredOutputExample.validate(new ChatResponse(text, "end_turn", Optional.empty())));
    }

    @Test void refusesTruncatedAndEmptyOutputEvenWhenHttpSucceeded() {
        assertEquals("INCOMPLETE_OR_NON_TEXT_STOP", StructuredOutputExample.validate(new ChatResponse("{\"ok\":true}", "max_tokens", Optional.empty())));
        assertEquals("EMPTY_TEXT", StructuredOutputExample.validate(new ChatResponse(" ", "end_turn", Optional.empty())));
    }

    @Test void comparisonChangesOnlyPromptAndSchemaAndDoesNotRetryFailures() throws Exception {
        var seen = new ArrayList<ChatRequest>();
        var results = StructuredOutputExample.run(request -> {
            seen.add(request);
            if (seen.size() == 2) throw new java.io.IOException("private response body");
            return new ChatResponse("{\"ok\":true}", "end_turn", Optional.empty());
        });
        assertEquals(3, seen.size());
        assertNull(seen.get(0).options().responseFormat());
        assertEquals(seen.get(0).messages(), seen.get(2).messages());
        assertEquals(seen.get(1).options(), seen.get(2).options());
        assertTrue(seen.stream().allMatch(request -> request.maxOutputTokens() == 2048));
        assertEquals(List.of("PASS", "TRANSPORT_ERROR:IOException", "PASS"), results.stream().map(StructuredOutputExample.Result::status).toList());
        assertFalse(results.toString().contains("private response body"));
    }
    @Test void distinguishesHttpStatusCodesWithoutRetryingRequests() throws Exception {
        var codes = List.of(401, 429, 500);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var results = StructuredOutputExample.run(request -> {
            throw new ModelHttpException(codes.get(calls.getAndIncrement()));
        });
        assertEquals(3, calls.get());
        assertEquals(List.of("HTTP_ERROR:401", "HTTP_ERROR:429", "HTTP_ERROR:500"),
                results.stream().map(StructuredOutputExample.Result::status).toList());
        assertTrue(results.stream().allMatch(result -> result.finishReason().isEmpty()));
    }

    @Test void distinguishesProtocolFailureWithoutPublishingItsMessage() throws Exception {
        var results = StructuredOutputExample.run(request -> {
            throw new ModelProtocolException("private response credential");
        });
        assertEquals(List.of("PROTOCOL_ERROR", "PROTOCOL_ERROR", "PROTOCOL_ERROR"),
                results.stream().map(StructuredOutputExample.Result::status).toList());
        assertFalse(results.toString().contains("private response credential"));
    }

    @Test void unexpectedRuntimeFailuresPropagateAndStopTheComparison() {
        for (RuntimeException failure : List.of(
                new IllegalArgumentException("invalid configuration"),
                new NullPointerException("unexpected state"))) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var thrown = assertThrows(failure.getClass(), () -> StructuredOutputExample.run(request -> {
                calls.incrementAndGet();
                throw failure;
            }));
            assertSame(failure, thrown);
            assertEquals(1, calls.get());
        }
    }

    @Test void responseValidationProgrammingErrorsDoNotBecomeRequestFailures() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(NullPointerException.class, () -> StructuredOutputExample.run(request -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(1, calls.get());
    }

}
