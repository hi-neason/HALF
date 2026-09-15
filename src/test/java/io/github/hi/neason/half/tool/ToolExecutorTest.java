package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "private-token-must-not-leak";

    @Test
    void parsesObjectArgumentsAndPreservesOutputAndCallAssociation() throws Exception {
        String output = "quote: \"value\"\n中文\\path";
        var executor = executor(arguments -> {
            assertEquals("Alice", arguments.path("name").asText());
            assertEquals(2, arguments.path("nested").path("count").asInt());
            assertEquals(2, arguments.path("items").size());
            return output;
        });

        var result = executor.execute(call("call-7", "{\"name\":\"Alice\",\"nested\":{\"count\":2},\"items\":[1,2]}"));

        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("call-7", result.callId());
        assertEquals("test", result.toolName());
        assertEquals(output, result.output());
        ChatMessage message = result.toMessage();
        assertEquals(ChatMessage.Role.TOOL, message.role());
        assertEquals("call-7", message.toolCallId());
        var envelope = JSON.readTree(message.text());
        assertEquals(2, envelope.size());
        assertEquals("SUCCESS", envelope.path("status").asText());
        assertEquals(output, envelope.path("output").asText());
    }

    @Test
    void rejectsUnknownToolWithoutExecutingRegisteredCodeOrLeakingInput() throws Exception {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); return "ok"; });
        var first = executor.execute(new ContentBlock.ToolCall("unknown-1", SECRET, "{}"));
        var second = executor.execute(new ContentBlock.ToolCall("unknown-2", "other", "{}"));
        assertSafeError(first, ToolResult.Status.UNKNOWN_TOOL);
        assertEquals(first.output(), second.output());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsInvalidJsonObjectsBeforeAnyToolSideEffect() throws Exception {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); return "ok"; });
        List<String> invalid = List.of("", " \n\t", "null", "[]", "[{}]", "1", "true", "\"text\"",
                "{", "{\"secret\":\"" + SECRET + "\",}", "{} {}", "{} null", "{} trailing",
                "{\"a\":1,\"a\":2}", "{\"nested\":{\"a\":1,\"a\":2}}",
                "{\"items\":[{\"a\":1,\"a\":2}]}");
        String safeOutput = null;
        for (String arguments : invalid) {
            var result = executor.execute(call("bad", arguments));
            assertSafeError(result, ToolResult.Status.INVALID_ARGUMENTS);
            if (safeOutput == null) safeOutput = result.output();
            assertEquals(safeOutput, result.output(), arguments);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void acceptsAnEmptyObjectAndJsonWhitespace() throws Exception {
        var executor = executor(arguments -> {
            assertTrue(arguments.isEmpty());
            return "";
        });
        var result = executor.execute(call("empty", " \n {} \t\r\n"));
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("", result.output());
    }

    @Test
    void boundsArgumentCharactersBeforeParsingAndExecution() throws Exception {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); return "ok"; });
        int limit = 1024 * 1024;
        String atLimit = "{}" + " ".repeat(limit - 2);
        assertEquals(ToolResult.Status.SUCCESS, executor.execute(call("limit", atLimit)).status());
        assertSafeError(executor.execute(call("too-large", atLimit + " ")), ToolResult.Status.INVALID_ARGUMENTS);
        assertEquals(1, calls.get());
    }

    @Test
    void delegatesSemanticValidationToTheToolWithoutGenericSchemaValidation() throws Exception {
        var executor = executor(arguments -> {
            if (!arguments.path("accepted").asBoolean()) throw new ToolArgumentsException();
            return "accepted";
        });
        assertSafeError(executor.execute(call("rejected", "{}")), ToolResult.Status.INVALID_ARGUMENTS);
        assertEquals(ToolResult.Status.SUCCESS,
                executor.execute(call("accepted", "{\"accepted\":true,\"extra\":42}")).status());
    }

    @Test
    void hidesCheckedAndRuntimeFailuresAndTreatsNullOutputAsFailure() throws Exception {
        var checked = executor(arguments -> { throw new IOException(SECRET); }).execute(call("io", "{}"));
        var runtime = executor(arguments -> { throw new IllegalStateException(SECRET); }).execute(call("runtime", "{}"));
        var nullOutput = executor(arguments -> null).execute(call("null-output", "{}"));
        for (var result : List.of(checked, runtime, nullOutput)) {
            assertSafeError(result, ToolResult.Status.EXECUTION_FAILED);
            assertEquals(checked.output(), result.output());
        }
    }

    @Test
    void executesBatchesInOrderAndReturnsImmutableResults() throws Exception {
        var observed = new ArrayList<Integer>();
        var executor = executor(arguments -> {
            int number = arguments.path("number").asInt();
            observed.add(number);
            return Integer.toString(number);
        });
        var results = executor.executeAll(List.of(call("one", "{\"number\":1}"),
                call("two", "{\"number\":2}")));
        assertEquals(List.of(1, 2), observed);
        assertEquals(List.of("one", "two"), results.stream().map(ToolResult::callId).toList());
        assertEquals(List.of("1", "2"), results.stream().map(ToolResult::output).toList());
        assertThrows(UnsupportedOperationException.class, results::clear);
        assertTrue(executor.executeAll(List.of()).isEmpty());
    }

    @Test
    void validatesDuplicateCallIdsAcrossTheWholeBatchBeforeExecution() {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); return "ok"; });
        assertThrows(IllegalArgumentException.class, () -> executor.executeAll(List.of(
                call("first", "{}"), call("duplicate", "{}"),
                new ContentBlock.ToolCall("duplicate", "unknown", "{}"))));
        assertEquals(0, calls.get());
    }

    @Test
    void snapshotsBatchInputBeforeToolsCanMutateIt() throws Exception {
        var batch = new ArrayList<ContentBlock.ToolCall>();
        var calls = new AtomicInteger();
        var executor = executor(arguments -> {
            batch.clear();
            return Integer.toString(calls.incrementAndGet());
        });
        batch.add(call("first", "{}"));
        batch.add(call("second", "{}"));
        var results = executor.executeAll(batch);
        assertEquals(List.of("first", "second"), results.stream().map(ToolResult::callId).toList());
        assertEquals(2, calls.get());
    }

    @Test
    void continuesAfterRecoverableToolErrors() throws Exception {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> {
            calls.incrementAndGet();
            if (arguments.path("fail").asBoolean()) throw new IOException(SECRET);
            return "ok";
        });
        var results = executor.executeAll(List.of(new ContentBlock.ToolCall("unknown", "missing", "{}"),
                call("invalid", "[]"), call("failed", "{\"fail\":true}"), call("success", "{}")));
        assertEquals(List.of(ToolResult.Status.UNKNOWN_TOOL, ToolResult.Status.INVALID_ARGUMENTS,
                        ToolResult.Status.EXECUTION_FAILED, ToolResult.Status.SUCCESS),
                results.stream().map(ToolResult::status).toList());
        assertEquals(2, calls.get());
    }

    @Test
    void propagatesCancellationWithoutExecutingTheNextTool() {
        var cancellation = new CancellationException(SECRET);
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); throw cancellation; });
        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> executor.executeAll(List.of(call("first", "{}"), call("second", "{}")))));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesInterruptedExceptionWithoutExecutingTheNextTool() {
        var interruption = new InterruptedException(SECRET);
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); throw interruption; });
        try {
            assertSame(interruption, assertThrows(InterruptedException.class,
                    () -> executor.executeAll(List.of(call("first", "{}"), call("second", "{}")))));
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void checksThreadInterruptionBeforeAnyExecution() {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); return "ok"; });
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> executor.execute(call("first", "{}")));
            assertEquals(0, calls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void checksThreadInterruptionAfterToolReturnsBeforeContinuing() {
        var calls = new AtomicInteger();
        var executor = executor(arguments -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return "must not become a successful result";
        });
        try {
            assertThrows(InterruptedException.class,
                    () -> executor.executeAll(List.of(call("first", "{}"), call("second", "{}"))));
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionTakesPrecedenceOverRecoverableToolFailures() {
        for (Exception failure : List.of(new IOException(SECRET), new ToolArgumentsException())) {
            var calls = new AtomicInteger();
            var executor = executor(arguments -> {
                calls.incrementAndGet();
                Thread.currentThread().interrupt();
                throw failure;
            });
            try {
                assertThrows(InterruptedException.class,
                        () -> executor.executeAll(List.of(call("first", "{}"), call("second", "{}"))));
                assertEquals(1, calls.get());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void doesNotSwallowErrors() {
        var error = new AssertionError(SECRET);
        var calls = new AtomicInteger();
        var executor = executor(arguments -> { calls.incrementAndGet(); throw error; });
        assertSame(error, assertThrows(AssertionError.class,
                () -> executor.executeAll(List.of(call("first", "{}"), call("second", "{}")))));
        assertEquals(1, calls.get());
    }

    private static void assertSafeError(ToolResult result, ToolResult.Status status) throws Exception {
        assertEquals(status, result.status());
        assertNotNull(result.output());
        assertFalse(result.output().isBlank());
        assertFalse(result.output().contains(SECRET));
        var message = result.toMessage();
        assertEquals(result.callId(), message.toolCallId());
        assertFalse(message.text().contains(SECRET));
        var envelope = JSON.readTree(message.text());
        assertEquals(status.name(), envelope.path("status").asText());
        assertEquals(result.output(), envelope.path("output").asText());
        assertEquals(2, envelope.size());
    }

    private static ContentBlock.ToolCall call(String id, String arguments) {
        return new ContentBlock.ToolCall(id, "test", arguments);
    }

    private static ToolExecutor executor(Action action) {
        return new ToolExecutor(new ToolRegistry(List.of(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test", "Test tool", "{\"type\":\"object\",\"additionalProperties\":false}");
            }

            @Override
            public String execute(ObjectNode arguments) throws Exception {
                return action.execute(arguments);
            }
        })));
    }

    @FunctionalInterface
    private interface Action {
        String execute(ObjectNode arguments) throws Exception;
    }
}
