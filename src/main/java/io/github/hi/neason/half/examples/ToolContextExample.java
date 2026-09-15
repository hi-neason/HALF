package io.github.hi.neason.half.examples;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.model.ToolDefinition;
import io.github.hi.neason.half.tool.ContextualTool;
import io.github.hi.neason.half.tool.ToolContext;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolOutput;
import io.github.hi.neason.half.tool.ToolRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 离线展示取消信号、进度与宿主详情；不调用模型或外部服务。 */
public final class ToolContextExample {
    private ToolContextExample() { }

    public static void main(String[] args) throws InterruptedException {
        ContextualTool add = new ContextualTool() {
            private final AddTool delegate = new AddTool();

            @Override public ToolDefinition definition() { return delegate.definition(); }

            @Override
            public ToolOutput execute(ObjectNode arguments, ToolContext context) throws Exception {
                context.checkCancelled();
                String sum = delegate.execute(arguments);
                context.reportProgress("Calculation finished");
                var details = JsonNodeFactory.instance.objectNode().put("operation", "addition");
                return new ToolOutput(sum, details);
            }
        };
        var executor = new ToolExecutor(new ToolRegistry(List.of(add)));
        var cancelled = new AtomicBoolean();
        var call = new ContentBlock.ToolCall("call_1", "add", "{\"a\":2,\"b\":3}");
        var result = executor.execute(call, cancelled::get,
                progress -> System.out.println("Progress: " + progress.callId() + " " + progress.message()));
        System.out.println("Model: " + result.toMessage().text());
        System.out.println("Host: " + result.details());
    }
}
