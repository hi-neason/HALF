package io.github.hi.neason.half.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.hi.neason.half.examples.AddTool;
import io.github.hi.neason.half.model.ModelEvent;
import io.github.hi.neason.half.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentHttpStreamTest {
    @Test
    void streamsAcrossHttpToolRoundTripWithOneEventDemand() throws Exception {
        var json = new ObjectMapper();
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String frames = requests.size() == 1 ? """
                    data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"add_1","type":"function","function":{"name":"add","arguments":"{\\"a\\":2,"}}]},"finish_reason":null}]}

                    data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"b\\":3}"}}]},"finish_reason":null}]}

                    data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """ : """
                    data: {"choices":[{"index":0,"delta":{"content":"结果是 "},"finish_reason":null}]}

                    data: {"choices":[{"index":0,"delta":{"content":"5。"},"finish_reason":null}]}

                    data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """;
            var body = frames.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var model = new OpenAiChatModel(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat"),
                "test-key", "test-model", Duration.ofSeconds(10))) {
            var done = new CompletableFuture<Void>();
            var events = new ArrayList<AgentEvent>();
            Agent.builder().model(model).tool(new AddTool()).build().stream("2 + 3").subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription subscription;
                @Override public void onSubscribe(Flow.Subscription value) {
                    subscription = value;
                    value.request(1);
                }
                @Override public void onNext(AgentEvent event) {
                    events.add(event);
                    subscription.request(1);
                }
                @Override public void onError(Throwable error) { done.completeExceptionally(error); }
                @Override public void onComplete() { done.complete(null); }
            });
            done.get(10, TimeUnit.SECONDS);
            var result = assertInstanceOf(AgentEvent.Completed.class, events.getLast()).result();
            assertTrue(result.completed(), () -> result.toString());
            assertEquals("结果是 5。", result.text());
            assertEquals(2, result.modelCalls());
            assertEquals(1, result.toolResults().size());
            assertEquals(List.of("结果是 ", "5。"), events.stream()
                    .filter(AgentEvent.Model.class::isInstance).map(AgentEvent.Model.class::cast)
                    .map(AgentEvent.Model::event).filter(ModelEvent.TextDelta.class::isInstance)
                    .map(ModelEvent.TextDelta.class::cast).map(ModelEvent.TextDelta::text).toList());
            assertEquals(2, requests.size());
            assertTrue(requests.stream().allMatch(request -> request.path("stream").asBoolean()));
            var toolMessage = requests.getLast().path("messages").get(2);
            assertEquals("tool", toolMessage.path("role").asText());
            assertEquals("add_1", toolMessage.path("tool_call_id").asText());
            assertEquals("5", json.readTree(toolMessage.path("content").asText()).path("output").asText());
        } finally {
            server.stop(0);
        }
    }
}
