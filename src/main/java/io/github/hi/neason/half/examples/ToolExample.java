package io.github.hi.neason.half.examples;

import io.github.hi.neason.half.model.ContentBlock;
import io.github.hi.neason.half.tool.ToolExecutor;
import io.github.hi.neason.half.tool.ToolRegistry;

import java.util.List;

/** 离线演示工具执行；调用内容模拟一轮已成功完成的模型响应。 */
public final class ToolExample {
    private ToolExample() { }

    public static void main(String[] args) throws InterruptedException {
        var registry = new ToolRegistry(List.of(new AddTool()));
        var executor = new ToolExecutor(registry);
        var call = new ContentBlock.ToolCall("call_1", "add", "{\"a\":2,\"b\":3}");
        var result = executor.execute(call);
        System.out.println(result.toMessage().text());
    }
}
