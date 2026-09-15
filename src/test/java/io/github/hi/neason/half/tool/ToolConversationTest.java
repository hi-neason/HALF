package io.github.hi.neason.half.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.examples.AddTool;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatRequest;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ReplayState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolConversationTest {
    @Test
    void hostCanFeedExecutionResultBackToModelWithoutLosingReplayState() throws Exception {
        var registry = new ToolRegistry(List.of(new AddTool()));
        var executor = new ToolExecutor(registry);
        var count = new AtomicInteger();
        var call = new ContentBlock.ToolCall("call_add", "add", "{\"a\":2,\"b\":3}");
        var replay = ReplayState.responses(List.of("""
                {"type":"function_call","id":"fc_1","call_id":"call_add","name":"add",
                 "arguments":"{\"a\":2,\"b\":3}","status":"completed"}
                """));
        ChatModel model = request -> {
            assertEquals(registry.definitions(), request.tools());
            if (count.getAndIncrement() == 0) {
                assertEquals(List.of(ChatMessage.user("2 加 3 等于多少？")), request.messages());
                return new ChatResponse(List.of(call), "completed", Optional.empty(), replay);
            }
            assertEquals(3, request.messages().size());
            assertEquals(List.of(call), request.messages().get(1).content());
            assertEquals(replay, request.messages().get(1).replayState());
            var resultMessage = request.messages().get(2);
            assertEquals(ChatMessage.Role.TOOL, resultMessage.role());
            assertEquals("call_add", resultMessage.toolCallId());
            var result = new ObjectMapper().readTree(resultMessage.text());
            assertEquals("SUCCESS", result.path("status").asText());
            assertEquals("5", result.path("output").asText());
            return new ChatResponse("2 加 3 等于 5。", "stop", Optional.empty());
        };

        var messages = new ArrayList<>(List.of(ChatMessage.user("2 加 3 等于多少？")));
        var response = model.chat(new ChatRequest(messages, 128, registry.definitions()));
        messages.add(ChatMessage.assistantResponse(response));
        executor.executeAll(response.toolCalls()).stream().map(ToolResult::toMessage).forEach(messages::add);
        var answer = model.chat(new ChatRequest(messages, 128, registry.definitions()));
        assertEquals("2 加 3 等于 5。", answer.text());
        assertEquals(2, count.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", "{\"a\":1,\"b\":2,\"c\":3}",
            "{\"a\":\"1\",\"b\":2}", "{\"a\":null,\"b\":2}", "{\"a\":1.5,\"b\":2}",
            "{\"a\":2147483648,\"b\":2}", "{\"a\":1.00000000000000000001,\"b\":2}"})
    void addToolChecksItsOwnSchemaConstraints(String arguments) throws InterruptedException {
        var executor = new ToolExecutor(new ToolRegistry(List.of(new AddTool())));
        var result = executor.execute(new ContentBlock.ToolCall("a", "add", arguments));
        assertEquals(ToolResult.Status.INVALID_ARGUMENTS, result.status());
    }

    @Test
    void addingBoundaryIntegersDoesNotOverflow() throws InterruptedException {
        var executor = new ToolExecutor(new ToolRegistry(List.of(new AddTool())));
        var result = executor.execute(new ContentBlock.ToolCall("a", "add", "{\"a\":2147483647,\"b\":2147483647}"));
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("4294967294", result.output());
    }

    @Test
    void acceptsMathematicallyIntegralJsonNumbers() throws InterruptedException {
        var executor = new ToolExecutor(new ToolRegistry(List.of(new AddTool())));
        var result = executor.execute(new ContentBlock.ToolCall("a", "add", "{\"a\":1.0,\"b\":2e0}"));
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("3", result.output());
    }
}
