package io.github.hi.neason.half.model;

import io.github.hi.neason.half.model.anthropic.AnthropicMessagesModel;
import io.github.hi.neason.half.model.openai.OpenAiChatModel;
import io.github.hi.neason.half.model.openai.OpenAiResponsesModel;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ReplayStateTest {
    private static final List<ContentBlock> TEXT = List.of(new ContentBlock.Text("hello"));
    private static final String SNAPSHOT = """
            {"id":"msg_1","type":"message","role":"assistant","status":"completed",
             "content":[{"type":"output_text","text":"hello","annotations":[]}]}
            """;
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:1/v1/responses");

    @Test void explicitTagAndImmutableOpaqueSnapshots() {
        var source = new ArrayList<>(List.of("opaque, not parsed by core"));
        ReplayState state = ReplayState.responses(source);
        source.clear();
        assertEquals(ReplayState.Protocol.OPENAI_RESPONSES, state.protocol());
        assertEquals(List.of("opaque, not parsed by core"), state.snapshots());
        assertThrows(UnsupportedOperationException.class, () -> state.snapshots().clear());
        assertEquals(ReplayState.Protocol.NONE, ReplayState.none().protocol());
        assertEquals(ReplayState.none(), ReplayState.responses(List.of()));
        assertEquals(ReplayState.none(), new ReplayState(ReplayState.Protocol.OPENAI_RESPONSES, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayState(ReplayState.Protocol.NONE, List.of(SNAPSHOT)));
        assertThrows(NullPointerException.class, () -> ReplayState.responses(null));
    }

    @Test void legacyConstructorsAndDerivedSnapshotAccessorRemainAvailable() {
        var old = new ChatResponse(TEXT, "completed", Optional.empty(), List.of(SNAPSHOT));
        var explicit = new ChatResponse(TEXT, "completed", Optional.empty(), ReplayState.responses(List.of(SNAPSHOT)));
        assertEquals(explicit, old);
        assertEquals(List.of(SNAPSHOT), old.outputItemsJson());
        assertEquals(ReplayState.none(), new ChatResponse("hello", "stop", Optional.empty()).replayState());
        assertEquals(ReplayState.none(), new ChatResponse(TEXT, "stop", Optional.empty(), List.of()).replayState());
        var oldMessage = new ChatMessage(ChatMessage.Role.ASSISTANT, TEXT, null, List.of(SNAPSHOT));
        assertEquals(ChatMessage.assistantResponse(explicit), oldMessage);
        assertEquals(List.of(SNAPSHOT), oldMessage.outputItemsJson());
        assertEquals(ReplayState.none(), ChatMessage.user("hello").replayState());
        assertEquals(ReplayState.none(), new ChatMessage(ChatMessage.Role.USER, TEXT, null, List.of()).replayState());
    }

    @Test void explicitDropPreservesContentAndUsageAndLeavesOriginalUntouched() {
        var blocks = List.<ContentBlock>of(new ContentBlock.Text("hello"),
                new ContentBlock.Reasoning("r", List.of("summary"), List.of(), "encrypted"));
        var response = new ChatResponse(blocks, "completed", Optional.of(new TokenUsage(1, 2, 3)),
                ReplayState.responses(List.of("opaque")));
        var dropped = response.withoutReplayState();
        assertEquals(response.content(), dropped.content());
        assertEquals(response.usage(), dropped.usage());
        assertEquals(response.finishReason(), dropped.finishReason());
        assertEquals(ReplayState.none(), dropped.replayState());
        assertEquals(List.of("opaque"), response.outputItemsJson());
        var assistant = ChatMessage.assistantResponse(response);
        assertEquals(response.replayState(), assistant.replayState());
        assertEquals(blocks, assistant.withoutReplayState().content());
        assertEquals(ReplayState.none(), assistant.withoutReplayState().replayState());
        var result = ChatMessage.toolResult("call", "done");
        assertEquals(result, result.withoutReplayState());
    }

    @Test void onlyAssistantCanCarryNonemptyProtocolTaggedState() {
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatMessage.Role.USER, TEXT, null,
                ReplayState.responses(List.of(SNAPSHOT))));
        assertEquals(ReplayState.none(), new ChatMessage(ChatMessage.Role.USER, TEXT, null,
                ReplayState.responses(List.of())).replayState());
    }

    @Test void incompatibleProvidersRejectTaggedStateUntilExplicitlyDropped() {
        var tagged = new ChatMessage(ChatMessage.Role.ASSISTANT, TEXT, null, ReplayState.responses(List.of(SNAPSHOT)));
        var request = new ChatRequest(List.of(tagged));
        try (var chat = new OpenAiChatModel(ENDPOINT, "key", "model", Duration.ofSeconds(1));
             var anthropic = new AnthropicMessagesModel(ENDPOINT, "key", "model", Duration.ofSeconds(1))) {
            assertThrows(IllegalArgumentException.class, () -> encode(chat, request));
            assertThrows(IllegalArgumentException.class, () -> encode(anthropic, request));
            assertDoesNotThrow(() -> encode(chat, new ChatRequest(List.of(tagged.withoutReplayState()))));
        }
    }

    @Test void responsesStillValidatesSnapshotContentAndEmptyStateUsesTypedContent() {
        try (var model = new OpenAiResponsesModel(ENDPOINT, "key", "model", Duration.ofSeconds(1))) {
            var matching = new ChatMessage(ChatMessage.Role.ASSISTANT, TEXT, null, ReplayState.responses(List.of(SNAPSHOT)));
            assertDoesNotThrow(() -> encode(model, new ChatRequest(List.of(matching))));
            var mismatch = new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ContentBlock.Text("changed")), null,
                    ReplayState.responses(List.of(SNAPSHOT)));
            assertThrows(IllegalArgumentException.class, () -> encode(model, new ChatRequest(List.of(mismatch))));
            var empty = new ChatMessage(ChatMessage.Role.ASSISTANT, TEXT, null, ReplayState.responses(List.of()));
            assertDoesNotThrow(() -> encode(model, new ChatRequest(List.of(empty))));
        }
    }

    @Test void responsesDecoderNormalizesEmptyOutputAndTagsNonemptyOutput() throws Exception {
        try (var model = new OpenAiResponsesModel(ENDPOINT, "key", "model", Duration.ofSeconds(1))) {
            var decode = model.getClass().getDeclaredMethod("decodeResponse", String.class);
            decode.setAccessible(true);
            var decoded = (ChatResponse) decode.invoke(model,
                    "{\"object\":\"response\",\"status\":\"completed\",\"output\":[]}");
            assertEquals(ReplayState.responses(List.of()), decoded.replayState());
            assertEquals(decoded.replayState(), ChatMessage.assistantResponse(decoded).replayState());
            var nonempty = (ChatResponse) decode.invoke(model,
                    "{\"object\":\"response\",\"status\":\"completed\",\"output\":[" + SNAPSHOT + "]}");
            assertEquals(ReplayState.Protocol.OPENAI_RESPONSES, nonempty.replayState().protocol());
            assertEquals(TEXT, nonempty.content());
        }
    }
    private static String encode(ChatModel model, ChatRequest request) throws Throwable {
        // Exercise pure provider encoding without network or reliance on transport scheduling.
        var method = model.getClass().getDeclaredMethod("encodeRequest", ChatRequest.class, boolean.class);
        method.setAccessible(true);
        try { return (String) method.invoke(model, request, false); }
        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }

}
