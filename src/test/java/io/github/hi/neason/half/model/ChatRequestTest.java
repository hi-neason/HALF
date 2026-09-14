package io.github.hi.neason.half.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestTest {
    @Test
    void snapshotsMessageHistoryAndExposesAnImmutableList() {
        var messages = new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user("first")));
        var request = new ChatRequest(messages);
        messages.clear();
        assertEquals(List.of(ChatMessage.system("system"), ChatMessage.user("first")), request.messages());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().add(ChatMessage.user("extra")));
        assertNull(request.maxOutputTokens());
    }

    @Test
    void rejectsMissingMessagesAndNonpositiveTokenLimits() {
        assertThrows(NullPointerException.class, () -> new ChatRequest(null));
        assertThrows(NullPointerException.class, () -> new ChatRequest(Arrays.asList(ChatMessage.user("hello"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(List.of(ChatMessage.user("hello")), 0));
        assertThrows(IllegalArgumentException.class, () -> new ChatRequest(List.of(ChatMessage.user("hello")), -1));
        assertEquals(1, new ChatRequest(List.of(ChatMessage.user("hello")), 1).maxOutputTokens());
    }

    @Test
    void requiresMessageRoleAndTextButAllowsEmptyText() {
        assertThrows(NullPointerException.class, () -> new ChatMessage(null, "hello"));
        assertThrows(NullPointerException.class, () -> ChatMessage.user(null));
        assertEquals("", ChatMessage.assistant("").text());
    }

    @Test
    void toolDefinitionsAndContentAreImmutableAndRoleBoundariesAreChecked() {
        var tool = new ToolDefinition("lookup", "", "{\"type\":\"object\"}");
        var tools = new ArrayList<>(List.of(tool));
        var request = new ChatRequest(List.of(ChatMessage.user("x")), null, tools);
        tools.clear();
        assertEquals(List.of(tool), request.tools());
        assertThrows(UnsupportedOperationException.class, () -> request.tools().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new ChatRequest(request.messages(), null, List.of(tool, tool)));
        var call = new ContentBlock.ToolCall("a", "lookup", "{}");
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage(ChatMessage.Role.USER, List.of(call), null));
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.toolResult("", "result"));
        assertThrows(IllegalArgumentException.class,
                () -> new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(call), "unexpected"));
        assertEquals("a", ChatMessage.toolResult("a", "result").toolCallId());
    }
}
