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
        assertEquals(List.of("PASS", "REQUEST_ERROR:IOException", "PASS"), results.stream().map(StructuredOutputExample.Result::status).toList());
        assertFalse(results.toString().contains("private response body"));
    }
}
