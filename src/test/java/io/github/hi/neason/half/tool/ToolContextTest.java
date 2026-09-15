package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolContextTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "\n[truncated]";

    @Test
    void associatesContextAndProgressWithEachCall() throws Exception {
        var progress = new ArrayList<ToolProgress>();
        var contexts = new ArrayList<ToolContext>();
        var executor = executor(context -> {
            contexts.add(context);
            assertEquals("contextual", context.toolName());
            context.reportProgress("running " + context.callId());
            return new ToolOutput("finished");
        });

        var results = executor.executeAll(List.of(call("first"), call("second")), () -> false, progress::add);

        assertNotSame(contexts.get(0), contexts.get(1));
        assertEquals(List.of(new ToolProgress("first", "contextual", "running first", false),
                new ToolProgress("second", "contextual", "running second", false)), progress);
        assertEquals(List.of("first", "second"), results.stream().map(ToolResult::callId).toList());
        assertTrue(results.stream().allMatch(result -> result.output().equals("finished")));
    }

    @Test
    void bridgesLegacyToolsThroughTheContextAwareExecutor() throws Exception {
        var invocations = new AtomicInteger();
        Tool legacy = new Tool() {
            @Override public ToolDefinition definition() { return definitionFor("legacy"); }
            @Override public String execute(ObjectNode arguments) {
                invocations.incrementAndGet();
                return arguments.path("text").asText();
            }
        };
        var executor = new ToolExecutor(new ToolRegistry(List.of(legacy)));
        var result = executor.execute(new ContentBlock.ToolCall("legacy-call", "legacy", "{\"text\":\"hello\"}"),
                () -> false, ignored -> fail("Legacy tool should not emit progress"));

        assertEquals(1, invocations.get());
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("hello", result.output());
        assertTrue(result.details().isNull());
        assertFalse(result.truncated());
    }

    @Test
    void copiesNestedOutputDetailsOnConstructionAndRead() throws Exception {
        var source = JSON.readTree("{\"items\":[{\"value\":1}]}");
        var output = new ToolOutput("public", source);
        ((ObjectNode) source.path("items").get(0)).put("value", 2);
        ((ObjectNode) output.details().path("items").get(0)).put("value", 3);

        assertEquals(1, output.details().path("items").get(0).path("value").asInt());
        var result = executor(context -> output).execute(call("details"));
        assertEquals(output.details(), result.details());
        assertEquals("public", result.output());
    }

    @Test
    void copiesResultDetailsAndKeepsThemOutOfModelMessages() throws Exception {
        var source = JSON.readTree("{\"private\":{\"token\":\"host-only-secret\"}}");
        var result = new ToolResult("details", "contextual", ToolResult.Status.SUCCESS, "public", source, true);
        ((ObjectNode) source.path("private")).put("token", "changed-input");
        ((ObjectNode) result.details().path("private")).put("token", "changed-read");

        assertEquals("host-only-secret", result.details().path("private").path("token").asText());
        var message = result.toMessage();
        assertEquals("details", message.toolCallId());
        assertEquals(JSON.readTree("{\"status\":\"SUCCESS\",\"output\":\"public\",\"truncated\":true}"),
                JSON.readTree(message.text()));
        assertFalse(message.text().contains("host-only-secret"));
    }

    @Test
    void boundsDefaultOutputAndProgressWithAnExplicitTruncationMarker() throws Exception {
        String text = "x".repeat(16_001);
        var progress = new ArrayList<ToolProgress>();
        var result = executor(context -> {
            context.reportProgress(text);
            return new ToolOutput(text);
        }).execute(call("default-limit"), () -> false, progress::add);

        String expected = "x".repeat(16_000 - MARKER.length()) + MARKER;
        assertEquals(expected, result.output());
        assertTrue(result.truncated());
        assertEquals(List.of(new ToolProgress("default-limit", "contextual", expected, true)), progress);
    }

    @Test
    void configuredOutputLimitPreservesExactFitsAndSurrogatePairs() throws Exception {
        int limit = 64;
        int prefixLength = limit - MARKER.length() - 1;
        String overflowing = "x".repeat(prefixLength) + "\uD83D\uDE00" + "y".repeat(limit);
        var truncated = executor(context -> new ToolOutput(overflowing), limit).execute(call("unicode"));
        assertEquals("x".repeat(prefixLength) + MARKER, truncated.output());
        assertTrue(truncated.truncated());
        assertTrue(truncated.output().length() <= limit);

        String exact = "z".repeat(limit - 2) + "\uD83D\uDE00";
        var unchanged = executor(context -> new ToolOutput(exact), limit).execute(call("exact"));
        assertEquals(exact, unchanged.output());
        assertFalse(unchanged.truncated());
    }

    @Test
    void progressUsesConfiguredLimitWithoutSplittingSurrogatePairs() throws Exception {
        int limit = 64;
        int prefixLength = limit - MARKER.length() - 1;
        String exact = "x".repeat(limit);
        String overflowing = "x".repeat(prefixLength) + "\uD83D\uDE00" + "y".repeat(limit);
        var progress = new ArrayList<ToolProgress>();
        executor(context -> {
            context.reportProgress(exact);
            context.reportProgress(overflowing);
            return new ToolOutput("done");
        }, limit).execute(call("progress-limit"), () -> false, progress::add);

        assertEquals(List.of(new ToolProgress("progress-limit", "contextual", exact, false),
                new ToolProgress("progress-limit", "contextual", "x".repeat(prefixLength) + MARKER, true)), progress);
    }

    @Test
    void ignoresLateProgressAfterSuccessAndFailure() throws Exception {
        for (boolean fail : List.of(false, true)) {
            var captured = new AtomicReference<ToolContext>();
            var cancelled = new AtomicBoolean();
            var progress = new ArrayList<ToolProgress>();
            var result = executor(context -> {
                captured.set(context);
                context.reportProgress("active");
                if (fail) throw new IOException("tool failed");
                return new ToolOutput("done");
            }).execute(call("lifecycle"), cancelled::get, progress::add);

            assertEquals(fail ? ToolResult.Status.EXECUTION_FAILED : ToolResult.Status.SUCCESS, result.status());
            cancelled.set(true);
            assertDoesNotThrow(() -> captured.get().reportProgress("late"));
            assertEquals(List.of(new ToolProgress("lifecycle", "contextual", "active", false)), progress);
        }
    }

    @Test
    void supplierCancellationBeforeExecutionPreventsSideEffects() {
        var calls = new AtomicInteger();
        var executor = executor(context -> {
            calls.incrementAndGet();
            return new ToolOutput("unexpected");
        });
        assertThrows(CancellationException.class,
                () -> executor.execute(call("cancelled"), () -> true, ignored -> fail("No progress expected")));
        assertEquals(0, calls.get());
    }

    @Test
    void supplierCancellationAfterExecutionOverridesReturnedOutputAndToolFailures() {
        for (boolean fail : List.of(false, true)) {
            var cancelled = new AtomicBoolean();
            var executor = executor(context -> {
                cancelled.set(true);
                if (fail) throw new IOException("recoverable failure");
                return new ToolOutput("must not become success");
            });
            assertThrows(CancellationException.class,
                    () -> executor.execute(call("post-cancel"), cancelled::get, ignored -> { }));
        }
    }

    @Test
    void supplierCancellationStopsTheRemainingBatch() {
        var cancelled = new AtomicBoolean();
        var calls = new ArrayList<String>();
        var executor = executor(context -> {
            calls.add(context.callId());
            context.reportProgress("started");
            return new ToolOutput("done");
        });
        assertThrows(CancellationException.class, () -> executor.executeAll(
                List.of(call("first"), call("second")), cancelled::get, ignored -> cancelled.set(true)));
        assertEquals(List.of("first"), calls);
    }

    @Test
    void contextChecksCancellationBeforeDeliveringProgress() throws Exception {
        var cancelled = new AtomicBoolean();
        var progress = new ArrayList<ToolProgress>();
        var executor = executor(context -> {
            context.reportProgress("before cancellation");
            cancelled.set(true);
            assertThrows(CancellationException.class, context::checkCancelled);
            context.reportProgress("must not be delivered");
            fail("Cancelled progress must interrupt the tool");
            return new ToolOutput("unreachable");
        });
        assertThrows(CancellationException.class,
                () -> executor.execute(call("cooperative"), cancelled::get, progress::add));
        assertEquals(List.of(new ToolProgress("cooperative", "contextual", "before cancellation", false)), progress);
    }

    @Test
    void propagatesTheOriginalProgressObserverFailureAndClosesContext() throws Exception {
        var failure = new IllegalStateException("observer failed");
        var captured = new AtomicReference<ToolContext>();
        var invocations = new AtomicInteger();
        var executor = executor(context -> {
            captured.set(context);
            context.reportProgress("running");
            return new ToolOutput("unreachable");
        });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> executor.executeAll(List.of(call("first"), call("second")), () -> false, ignored -> {
                    invocations.incrementAndGet();
                    throw failure;
                })));
        assertDoesNotThrow(() -> captured.get().reportProgress("late"));
        assertEquals(1, invocations.get());
    }

    @Test
    void preservesObserverFailureEvenWhenTheToolCatchesItAndReturns() {
        var failure = new IllegalArgumentException("observer failed");
        var returned = new AtomicBoolean();
        var executor = executor(context -> {
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> context.reportProgress("running")));
            returned.set(true);
            return new ToolOutput("must not hide observer failure");
        });
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> executor.execute(call("caught"), () -> false, ignored -> { throw failure; })));
        assertTrue(returned.get());
    }

    @Test
    void rejectsNonJsonDetailsAndTreesBeyondTheDepthLimit() {
        for (var value : List.of(new POJONode(new ArrayList<String>()),
                BinaryNode.valueOf(new byte[]{1, 2}), MissingNode.getInstance(),
                DoubleNode.valueOf(Double.NaN), DoubleNode.valueOf(Double.POSITIVE_INFINITY),
                DoubleNode.valueOf(Double.NEGATIVE_INFINITY))) {
            var details = JSON.createObjectNode();
            details.putArray("nested").add(value);
            assertThrows(IllegalArgumentException.class, () -> new ToolOutput("public", details));
            assertThrows(IllegalArgumentException.class, () -> new ToolResult(
                    "invalid-details", "contextual", ToolResult.Status.SUCCESS, "public", details, false));
        }

        var details = JSON.createObjectNode();
        var nested = details;
        for (int depth = 0; depth < 128; depth++) nested = nested.putObject("child");
        assertDoesNotThrow(() -> new ToolOutput("public", details));
        assertDoesNotThrow(() -> new ToolResult(
                "at-limit", "contextual", ToolResult.Status.SUCCESS, "public", details, false));
        nested.putObject("too-deep");
        assertThrows(IllegalArgumentException.class, () -> new ToolOutput("public", details));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(
                "too-deep", "contextual", ToolResult.Status.SUCCESS, "public", details, false));
    }

    @Test
    void preservesObserverErrorEvenWhenTheToolCatchesErrorAndReturns() {
        var failure = new AssertionError("observer failed");
        var returned = new AtomicBoolean();
        var executor = executor(context -> {
            try {
                context.reportProgress("running");
            } catch (Error error) {
                assertSame(failure, error);
            }
            returned.set(true);
            return new ToolOutput("must not hide observer error");
        });
        assertSame(failure, assertThrows(AssertionError.class,
                () -> executor.execute(call("caught-error"), () -> false, ignored -> { throw failure; })));
        assertTrue(returned.get());
    }

    @Test
    void preservesCancellationSupplierFailureEvenWhenTheToolCatchesItAndReturns() {
        var failure = new IllegalStateException("cancellation supplier failed");
        var checks = new AtomicInteger();
        var returned = new AtomicBoolean();
        var executor = executor(context -> {
            assertSame(failure, assertThrows(IllegalStateException.class, context::checkCancelled));
            returned.set(true);
            return new ToolOutput("must not hide supplier failure");
        });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> executor.execute(call("caught-supplier"), () -> {
                    if (checks.incrementAndGet() == 3) throw failure;
                    return false;
                }, ignored -> { })));
        assertTrue(returned.get());
        assertEquals(3, checks.get());
    }

    @Test
    void waitsForAnActiveProgressCallbackBeforeClosingTheContext() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var toolReturning = new CountDownLatch(1);
        var captured = new AtomicReference<ToolContext>();
        var progressTask = new AtomicReference<Future<?>>();
        var callbacks = new AtomicInteger();
        var executor = executor(context -> {
            captured.set(context);
            progressTask.set(pool.submit(() -> {
                context.reportProgress("background progress");
                return null;
            }));
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS), "Callback did not start");
            toolReturning.countDown();
            return new ToolOutput("done");
        });
        try {
            var execution = pool.submit(() -> executor.execute(call("concurrent"), () -> false, ignored -> {
                callbacks.incrementAndGet();
                callbackEntered.countDown();
                try {
                    assertTrue(releaseCallback.await(3, TimeUnit.SECONDS), "Callback was not released");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Callback interrupted", error);
                }
            }));
            assertTrue(toolReturning.await(3, TimeUnit.SECONDS), "Tool did not reach its return");
            assertFalse(execution.isDone(), "Execution finished while its callback was active");
            releaseCallback.countDown();
            var result = execution.get(3, TimeUnit.SECONDS);
            progressTask.get().get(3, TimeUnit.SECONDS);
            assertEquals(ToolResult.Status.SUCCESS, result.status());
            assertEquals("done", result.output());
            captured.get().reportProgress("late progress");
            assertEquals(1, callbacks.get());
        } finally {
            releaseCallback.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS), "Background tasks did not terminate");
        }
    }

    private static ContentBlock.ToolCall call(String id) {
        return new ContentBlock.ToolCall(id, "contextual", "{}");
    }

    private static ToolDefinition definitionFor(String name) {
        return new ToolDefinition(name, "Test tool", "{\"type\":\"object\"}");
    }

    private static ToolExecutor executor(Action action) {
        return new ToolExecutor(new ToolRegistry(List.of(tool(action))));
    }

    private static ToolExecutor executor(Action action, int limit) {
        return new ToolExecutor(new ToolRegistry(List.of(tool(action))), limit);
    }

    private static ContextualTool tool(Action action) {
        return new ContextualTool() {
            @Override public ToolDefinition definition() { return definitionFor("contextual"); }
            @Override public ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception {
                return action.execute(context);
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolOutput execute(ToolContext context) throws Exception;
    }
}
