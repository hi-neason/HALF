package io.github.hi.neason.half.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.agent.Agent;
import io.github.hi.neason.half.agent.AgentEvent;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolRegistry;
import io.github.hi.neason.half.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class McpClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void initializesBeforeDiscoveryAndPreservesSnapshots() throws Exception {
        try (var client = builder("normal").connect()) {
            client.ping();
            assertEquals("fixture", client.serverInfo().path("name").asText());
            client.serverInfo().put("name", "changed");
            assertEquals("fixture", client.serverInfo().path("name").asText());
            var tools = client.listTools();
            assertEquals(List.of("echo"), tools.stream().map(McpTool::name).toList());
            tools.getFirst().inputSchema().removeAll();
            assertEquals("object", tools.getFirst().inputSchema().path("type").asText());
            assertThrows(UnsupportedOperationException.class, tools::clear);
        }
    }

    @Test
    void followsCursorEvenWhenFirstPageIsEmpty() throws Exception {
        try (var client = builder("empty-page").connect()) {
            assertEquals(List.of("echo"), client.listTools().stream().map(McpTool::name).toList());
        }
    }

    @Test
    void rejectsRepeatedPaginationCursors() throws Exception {
        try (var client = builder("repeat-cursor").connect()) {
            assertTrue(assertThrows(McpException.class, client::listTools).getMessage().contains("cursor"));
        }
    }

    @Test
    void rejectsDuplicateToolNames() throws Exception {
        try (var client = builder("duplicate").connect()) {
            assertTrue(assertThrows(McpException.class, client::listTools).getMessage().contains("Duplicate"));
        }
    }

    @Test
    void mapsProviderCompatibleAliasBackToOriginalRemoteName() throws Exception {
        try (var client = builder("alias").connect()) {
            var tools = client.tools("server");
            String alias = tools.getFirst().definition().name();
            assertTrue(alias.matches("[A-Za-z0-9_-]{1,64}"));
            assertTrue(alias.startsWith("server_"));
            var result = new ToolExecutor(new ToolRegistry(tools)).execute(new ContentBlock.ToolCall("call", alias, "{\"x\":1}"));
            assertEquals(ToolResult.Status.SUCCESS, result.status());
            assertEquals("remote/tool.with.dots:{\"x\":1}", result.output());
        }
    }

    @Test
    void preservesStructuredContentAndMediaWithoutPretendingMediaWasRead() throws Exception {
        try (var client = builder("media").connect()) {
            var result = client.callTool("echo", JSON.createObjectNode());
            assertTrue(result.text().contains("\"answer\":42"));
            assertTrue(result.text().contains("non-text content: image"));
            assertFalse(result.text().contains("aW1hZ2U="));
            assertEquals("aW1hZ2U=", result.value().path("content").get(1).path("data").asText());
            result.value().removeAll();
            assertEquals(42, result.value().path("structuredContent").path("answer").asInt());
        }
    }

    @Test
    void feedsToolBusinessErrorAndOriginalBodyBackToAgent() throws Exception {
        try (var client = builder("tool-error").connect()) {
            var turns = new AtomicInteger();
            var agent = Agent.builder().tools(client.tools("mcp")).model(request -> {
                if (turns.incrementAndGet() == 1) {
                    assertEquals("mcp_echo", request.tools().getFirst().name());
                    return new ChatResponse(List.of(new ContentBlock.ToolCall("remote-call", "mcp_echo", "{}")), "tool_calls", Optional.empty());
                }
                var message = request.messages().getLast();
                assertEquals("remote-call", message.toolCallId());
                assertTrue(message.text().contains("EXECUTION_FAILED"));
                assertTrue(message.text().contains("business failure"));
                return new ChatResponse("recovered", "stop", Optional.empty());
            }).build();
            var result = agent.run("use remote tool");
            assertTrue(result.completed());
            assertEquals(2, result.modelCalls());
            assertEquals("recovered", result.text());
            assertEquals(ToolResult.Status.EXECUTION_FAILED, result.toolResults().getFirst().status());
            assertTrue(result.toolResults().getFirst().details().path("isError").asBoolean());
            client.ping(); // Agent does not own or close its externally supplied client.
        }
    }

    @Test
    void completesTwoAgentTurnsWithRemoteToolResult() throws Exception {
        try (var client = builder("normal").connect()) {
            var turns = new AtomicInteger();
            var agent = Agent.builder().tools(client.tools("remote")).model(request -> {
                if (turns.incrementAndGet() == 1) {
                    return new ChatResponse(List.of(new ContentBlock.ToolCall("one", "remote_echo", "{\"value\":7}")),
                            "tool_calls", Optional.empty());
                }
                assertEquals("one", request.messages().getLast().toolCallId());
                assertTrue(request.messages().getLast().text().contains("SUCCESS"));
                return new ChatResponse("done", "stop", Optional.empty());
            }).build();
            var result = agent.run("echo seven");
            assertTrue(result.completed());
            assertEquals(2, result.modelCalls());
            assertEquals("echo:{\"value\":7}", result.toolResults().getFirst().output());
            assertEquals(ToolResult.Status.SUCCESS, result.toolResults().getFirst().status());
        }
    }

    @Test
    void correlatesConcurrentRequestsWithoutCrossingResults() throws Exception {
        try (var client = builder("normal").connect(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 12; index++) {
                int value = index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return client.callTool("echo", JSON.createObjectNode().put("value", value)).text();
                }));
            }
            start.countDown();
            for (int index = 0; index < futures.size(); index++) {
                assertEquals("echo:{\"value\":" + index + "}", futures.get(index).get(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void hidesServerErrorMessageAndDataButPreservesRpcCode() throws Exception {
        try (var client = builder("normal").connect()) {
            var error = assertThrows(McpException.class, () -> client.callTool("rpc-error", JSON.createObjectNode()));
            assertEquals(-32602, error.code().orElseThrow());
            assertFalse(error.toString().contains("private-server-secret"));
            assertNull(error.getCause());
            client.ping();
        }
    }

    @Test
    void timeoutCancelsRequestAndLateResponseCannotCompleteNextCall() throws Exception {
        try (var client = builder("normal").timeout(Duration.ofSeconds(2)).connect()) {
            var error = assertThrows(McpException.class, () -> client.callTool("wait", JSON.createObjectNode()));
            assertTrue(error.getMessage().contains("timed out"));
            assertEquals("true", client.callTool("cancelled", JSON.createObjectNode()).text());
            assertEquals("echo:{}", client.callTool("echo", JSON.createObjectNode()).text());
        }
    }

    @Test
    void forwardsProgressAndCooperativelyCancelsAnInFlightTool() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancelled = new AtomicBoolean();
            var started = new CountDownLatch(1);
            // The fixture exposes wait through this scenario's tool list.
            try (var waitingClient = builder("wait-tool").connect()) {
                var runner = new ToolExecutor(new ToolRegistry(waitingClient.tools("mcp")));
                var result = executor.submit(() -> assertThrows(CancellationException.class,
                        () -> runner.execute(new ContentBlock.ToolCall("waiting", "mcp_wait", "{}"), cancelled::get,
                                progress -> { assertEquals("started", progress.message()); started.countDown(); })));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                cancelled.set(true);
                result.get(5, TimeUnit.SECONDS);
                assertEquals("true", waitingClient.callTool("cancelled", JSON.createObjectNode()).text());
            }
        }
    }

    @Test
    void answersServerPingAndRejectsUnsupportedReverseRequest() throws Exception {
        try (var client = builder("normal").connect()) {
            assertEquals("reverse requests handled", client.callTool("reverse", JSON.createObjectNode()).text());
        }
    }

    @Test
    void serverExitFailsPendingRequest() throws Exception {
        try (var client = builder("normal").connect()) {
            assertThrows(McpException.class, () -> client.callTool("exit", JSON.createObjectNode()));
        }
    }

    @Test
    void closeReapsChildProcessAndRejectsFurtherRequests() throws Exception {
        var client = builder("normal").connect();
        long pid = client.serverInfo().path("pid").asLong();
        var process = ProcessHandle.of(pid).orElseThrow();
        try { assertTrue(process.isAlive()); } finally { client.close(); }
        process.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(process.isAlive());
        assertThrows(McpException.class, client::ping);
        client.close();
    }

    @Test
    void unsupportedVersionReapsFailedInitializationProcess() throws Exception {
        var pidFile = temporary.resolve("pid");
        var command = command("bad-version");
        command.add(pidFile.toString());
        assertThrows(McpException.class, () -> McpClient.stdio(command).connect());
        long pid = Long.parseLong(Files.readString(pidFile));
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) {
            process.get().onExit().get(5, TimeUnit.SECONDS);
            assertFalse(process.get().isAlive());
        }
    }

    @Test
    void appliesExplicitEnvironmentAndWorkingDirectory() throws Exception {
        try (var client = builder("environment").environment(Map.of("HALF_TEST_VALUE", "configured"))
                .directory(temporary).connect()) {
            assertEquals("configured:" + temporary.toRealPath(), client.callTool("echo", JSON.createObjectNode()).text());
        }
    }

    @Test
    void preservesTaskMetadataButDoesNotRegisterToolsRequiringTaskProtocol() throws Exception {
        try (var client = builder("tasks-required").connect()) {
            var discovered = client.listTools();
            assertEquals(List.of("echo", "background"), discovered.stream().map(McpTool::name).toList());
            var task = discovered.getLast();
            assertTrue(task.requiresTask());
            assertEquals("required", task.metadata().path("execution").path("taskSupport").asText());
            assertTrue(task.metadata().path("annotations").path("readOnlyHint").asBoolean());
            task.metadata().removeAll();
            assertTrue(task.requiresTask());
            assertEquals(List.of("remote_echo"), client.tools("remote").stream()
                    .map(tool -> tool.definition().name()).toList());
        }
    }

    @Test
    void streamingAgentDeliversRemoteProgressResultAndSecondModelTurn() throws Exception {
        try (var client = builder("immediate-progress").connect()) {
            var turns = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override public ChatResponse chat(ChatRequest request) {
                    throw new AssertionError("Agent must use streaming model entry point");
                }
                @Override public Flow.Publisher<ModelEvent> stream(ChatRequest request) {
                    int turn = turns.incrementAndGet();
                    var response = turn == 1
                            ? new ChatResponse(List.of(new ContentBlock.ToolCall("stream-call", "remote_echo", "{}")),
                                    "tool_calls", Optional.empty())
                            : new ChatResponse("stream finished", "stop", Optional.empty());
                    if (turn == 2) {
                        assertEquals("stream-call", request.messages().getLast().toolCallId());
                        assertTrue(request.messages().getLast().text().contains("SUCCESS"));
                    }
                    return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                        private boolean done;
                        @Override public void request(long demand) {
                            if (done) return;
                            done = true;
                            subscriber.onNext(new ModelEvent.Completed(response));
                            subscriber.onComplete();
                        }
                        @Override public void cancel() { done = true; }
                    });
                }
            };
            var events = new ArrayList<AgentEvent>();
            var terminal = new CompletableFuture<Void>();
            Agent.builder().model(model).tools(client.tools("remote")).build().stream("use remote tool")
                    .subscribe(new Flow.Subscriber<>() {
                        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                        @Override public void onNext(AgentEvent event) { events.add(event); }
                        @Override public void onError(Throwable error) { terminal.completeExceptionally(error); }
                        @Override public void onComplete() { terminal.complete(null); }
                    });
            terminal.get(5, TimeUnit.SECONDS);
            var progress = events.stream().filter(AgentEvent.ToolProgressed.class::isInstance)
                    .map(AgentEvent.ToolProgressed.class::cast).toList();
            assertEquals(1, progress.size());
            assertEquals("finished remote work", progress.getFirst().progress().message());
            assertEquals("stream-call", progress.getFirst().progress().callId());
            var toolCompleted = events.stream().filter(AgentEvent.ToolCompleted.class::isInstance)
                    .map(AgentEvent.ToolCompleted.class::cast).findFirst().orElseThrow();
            assertEquals(ToolResult.Status.SUCCESS, toolCompleted.result().status());
            assertEquals("echo:{}", toolCompleted.result().output());
            assertTrue(events.indexOf(progress.getFirst()) < events.indexOf(toolCompleted));
            var completed = assertInstanceOf(AgentEvent.Completed.class, events.getLast());
            assertTrue(completed.result().completed());
            assertEquals("stream finished", completed.result().text());
            assertEquals(2, completed.result().modelCalls());
        }
    }

    private static McpClient.Builder builder(String scenario) {
        return McpClient.stdio(command(scenario)).timeout(Duration.ofSeconds(5));
    }

    private static ArrayList<String> command(String scenario) {
        String classpath = Arrays.stream(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                        .split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, FakeMcpServer.class.getName(), scenario));
    }
}
