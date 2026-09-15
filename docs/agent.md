# Agent 门面与最小执行循环

`Agent` 提供构建和运行入口，包内的 `AgentLoop` 负责同步模型请求、工具执行及消息回填。`AgentResult` 保存一次运行的结果与历史快照。首版使用内存状态，不自动重试、持久化或恢复运行。

## 构建与运行

```java
import io.github.hi.neason.half.agent.Agent;
import io.github.hi.neason.half.examples.AddTool;

// model 为宿主已创建的 ChatModel，例如现有的三种协议适配器。
var agent = Agent.builder()
        .model(model)
        .systemPrompt("使用工具完成计算，再给出答案。")
        .tool(new AddTool())
        .maxTurns(4)
        .maxOutputTokens(1024)
        .build();

var result = agent.run("2 加 3 等于多少？");
if (result.completed()) {
    System.out.println(result.text());
} else {
    System.out.println(result.stopReason());
}
```

`model` 必须配置；工具可以省略，或通过 `tool(tool)` / `tools(list)` 追加。构建时检查重复工具名并保存配置快照，之后修改 builder 不影响已经构建的 Agent。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `systemPrompt` | 无 | 前置系统消息，首条已经相同时不重复添加 |
| `maxTurns` | 8 | 一次 run 最多发起的模型调用次数，必须为正 |
| `maxOutputTokens` | 未配置 | 每次模型请求的输出预算，沿用模型适配器默认值 |
| `modelOptions` | `ModelOptions.defaults()` | 每次请求使用的模型选项 |
| `maxToolOutputCharacters` | 16,000 | 单次工具文本及进度的 UTF-16 字符预算，最小为 64 |

模型选项中的 `parallelToolCalls` 不改变本地执行顺序：当前工具仍按模型返回顺序逐个执行。

Agent 不关闭传入的模型或工具，生命周期由宿主管理。Agent 本身只保存配置，每次 run 使用独立历史；并发使用同一个 Agent 时，模型、工具和宿主回调仍须支持相应并发访问。

## 执行流程

```text
检查输入历史与取消
  → 构造 ChatRequest
  → model.chat
  → 保存 assistantResponse（包括 ReplayState）
  → 检查结束原因和工具调用 ID
      ├ 无工具调用：返回 COMPLETED
      ├ 轮次耗尽：返回 MAX_TURNS
      └ 顺序执行工具 → 每个结果立即回填 → 下一次模型请求
```

执行器仅处理本轮模型新提出的调用，不执行输入历史中的调用。普通工具错误仍形成工具结果，模型可在后续轮次根据错误调整请求；没有工具层或 Agent 层的自动重试。

核心循环使用 `ChatModel.chat()` 获得完整响应，尚未提供 Agent 级 `Flow.Publisher`。已有模型流式接口可以独立使用，工具进度通过 run 的回调交付。

## 轮次上限与停止原因

`maxTurns` 计算模型调用尝试次数，包括抛出 IOException 的请求。预算检查发生在请求之前；最后一次允许的模型响应若包含工具调用，记录响应后直接停止，不执行无法再交回模型处理的工具。例如 `maxTurns(1)` 可以完成一次直接回答，但不会执行模型提出的工具。

| `stopReason` | 含义 |
| --- | --- |
| `COMPLETED` | 模型正常结束，且没有工具调用 |
| `MAX_TURNS` | 最后一轮仍提出工具，预算已经耗尽 |
| `MODEL_ERROR` | 模型调用产生 IOException，包括 HTTP、协议或传输错误 |
| `INCOMPLETE_RESPONSE` | 不完整、未知或与工具内容不一致的模型结束原因 |
| `INVALID_TOOL_CALLS` | 模型调用 ID 在本批、输入历史或前面轮次中重复 |

当前完整性规则对应现有模型契约：无工具时接受 `stop`、`end_turn`、`completed`；有工具时接受 `tool_calls`、`tool_use`、`completed`。`length`、`max_tokens`、`pause_turn` 及其他未支持结束原因均停止，不继续执行工具。适配器仍保留原始 `finishReason`，新协议需要明确其循环语义后再扩展这一规则。

`COMPLETED` 表示循环正常结束，不保证答案正确或没有拒绝内容；调用方可以检查 `lastResponse()` 中的完整内容。

## 结果与历史

- `text()`：只在正常完成时返回最后模型响应的文本，其他状态返回空字符串。
- `lastResponse()`：最后一次成功返回的模型响应，可能为空；模型随后失败时，这里可能是上一轮响应。
- `messages()`：本次运行的不可变消息快照，包含系统消息、输入历史、新模型消息及已回填的工具结果。
- `toolResults()`：仅本次运行产生的工具结果，包括宿主专用详情。
- `modelFailure()`：仅 MODEL_ERROR 时存在，保留异常类型和可选 HTTP 状态码，不含异常正文或异常链。

要显式传入已有历史，使用 `run(List<ChatMessage>)`。Agent 复制输入列表并按同一规则前置系统消息，不修改调用方列表。输入的工具调用与结果必须全部配对，调用 ID 不得重复，孤立结果或尚未完成的调用在模型请求前报 `IllegalArgumentException`。

停止快照不是通用恢复令牌。`MAX_TURNS`、`INVALID_TOOL_CALLS`、`INCOMPLETE_RESPONSE` 的消息可能含未执行或无效工具调用，不能直接传回 run；调用方需要理解停止原因并显式处理历史。正常完成时，可复制 `messages()`，追加用户消息后发起新一轮独立运行。

## 取消与工具进度

```java
var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
var result = agent.run("执行任务", cancelled::get,
        progress -> System.out.println(progress.callId() + ": " + progress.message()));
```

取消信号在模型请求前后、工具执行前后检查，并传给工具上下文。它不会自动打断一个正在阻塞的同步模型请求；使用线程中断及模型、工具自己的 I/O 超时控制阻塞操作。

`InterruptedException`、`CancellationException` 和 `Error` 向上传播，不转成正常运行结果。模型和宿主回调的运行时异常也原样传播；工具执行异常则沿用工具执行器的分类规则，转换为错误结果。

已经发生的工具副作用不会回滚。中断、取消或回调异常导致运行退出时，没有可保证完整记录的 AgentResult，不应因此自动重放工具。

## 离线验证

```bash
mvn test
mvn compile exec:java -Dexec.mainClass=io.github.hi.neason.half.examples.AgentExample
```

示例使用假模型提出 `add(2, 3)`，由 Java 工具计算并回填，假模型读取实际结果后回答。预期两次模型调用、一个工具结果，无需密钥或联网。测试覆盖预算边界、历史配对、调用 ID、错误回填、取消、配置快照和 ReplayState 保留。
