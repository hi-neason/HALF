package io.github.hi.neason.half.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hi.neason.half.agent.Agent;
import io.github.hi.neason.half.model.ChatMessage;
import io.github.hi.neason.half.model.ChatModel;
import io.github.hi.neason.half.model.ChatResponse;
import io.github.hi.neason.half.model.ContentBlock;

import java.util.List;
import java.util.Optional;

/** 假模型演示完整 Agent 闭环；模型部分可替换为任一 ChatModel 适配器。 */
public final class AgentExample {
    private AgentExample() { }

    public static void main(String[] args) throws InterruptedException {
        var json = new ObjectMapper();
        ChatModel model = request -> {
            var last = request.messages().getLast();
            if (last.role() == ChatMessage.Role.TOOL) {
                var result = json.readTree(last.text());
                return new ChatResponse("结果是 " + result.path("output").asText() + "。", "stop", Optional.empty());
            }
            var call = new ContentBlock.ToolCall("call_add", "add", "{\"a\":2,\"b\":3}");
            return new ChatResponse(List.of(call), "tool_calls", Optional.empty());
        };
        var agent = Agent.builder()
                .model(model)
                .systemPrompt("使用工具完成计算，再给出答案。")
                .tool(new AddTool())
                .maxTurns(3)
                .build();
        var result = agent.run("2 加 3 等于多少？");
        if (!result.completed()) throw new IllegalStateException("Example stopped: " + result.stopReason());
        System.out.println(result.text());
        System.out.println("Model calls: " + result.modelCalls());
        System.out.println("Tool results: " + result.toolResults().size());
    }
}
