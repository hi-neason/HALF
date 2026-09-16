# Agent 门面与最小执行循环

`Agent` 提供构建和运行入口，包内的 `AgentLoop` 负责同步或流式模型请求、工具执行及消息回填。`AgentResult` 保存一次运行的结果与历史快照。当前使用内存状态，不自动重试、持久化或恢复运行。

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
  → model.chat / model.stream
  → 保存 assistantResponse（包括 ReplayState）
  → 检查结束原因和工具调用 ID
      ├ 无工具调用：返回 COMPLETED
      ├ 轮次耗尽：返回 MAX_TURNS
      └ 顺序执行工具 → 每个结果立即回填 → 下一次模型请求
```

执行器仅处理本轮模型新提出的调用，不执行输入历史中的调用。普通工具错误仍形成工具结果，模型可在后续轮次根据错误调整请求；没有工具层或 Agent 层的自动重试。

同步 `run` 使用 `ChatModel.chat()`；`stream` 使用 `ChatModel.stream()` 并实时交付模型事件。两种入口共用结束原因、工具校验、消息回填和轮次预算规则。工具执行必须等到完整模型响应以及模型流的 `onComplete`，不会在参数分片到达时提前执行。

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

## Agent 流式订阅

`stream(String)` 和 `stream(List<ChatMessage>)` 返回原生 JDK `Flow.Publisher<AgentEvent>`，不需要 Reactor 或 Mono。示例中的 `agent` 使用支持流式请求的模型：

```java
var finished = new java.util.concurrent.CompletableFuture<Void>();
agent.stream("2 加 3 等于多少？").subscribe(new java.util.concurrent.Flow.Subscriber<AgentEvent>() {
    private java.util.concurrent.Flow.Subscription subscription;

    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override public void onNext(AgentEvent event) {
        switch (event) {
            case AgentEvent.Model model -> {
                if (model.event() instanceof ModelEvent.TextDelta delta) {
                    System.out.print(delta.text());
                }
            }
            case AgentEvent.ToolProgressed progress ->
                System.out.println(progress.progress().message());
            case AgentEvent.Completed completed ->
                System.out.println("\n停止原因：" + completed.result().stopReason());
            default -> { }
        }
        subscription.request(1);
    }

    @Override public void onError(Throwable error) { finished.completeExceptionally(error); }
    @Override public void onComplete() { finished.complete(null); }
});
finished.join(); // 示例主线程等待；UI 或服务端可异步处理 finished。
```

上例需导入 `io.github.hi.neason.half.agent.AgentEvent` 和 `io.github.hi.neason.half.model.ModelEvent`。

| 事件 | 含义 |
| --- | --- |
| `TurnStarted(turn)` | 即将开始第 turn 次模型请求，从 1 计数 |
| `Model(turn, event)` | 原样保留文本、推理、工具参数、用量等所有 ModelEvent |
| `ToolStarted(turn, call)` | 调用通过循环预检，即将交给工具执行器；仍可能返回未知工具或参数错误 |
| `ToolProgressed(turn, progress)` | 工具执行中的进度，不回填给模型 |
| `ToolCompleted(turn, result)` | 工具返回结果且已经追加到历史，包含成功或错误状态 |
| `Completed(result)` | 整个循环已停止，随后发出 Flow `onComplete` |

`ModelEvent.Completed` 只代表单轮模型响应；`AgentEvent.Completed` 才携带整个运行的结果。后者也可能包含 `MAX_TURNS` 或 `MODEL_ERROR`，应检查 `result.completed()`，不能把 Flow 的正常结束直接当作任务成功。部分文本已经交付后仍可能失败，展示层应根据最终结果标明状态。

每次订阅独立运行，第一次正数 `request(n)` 后启动一个虚拟线程；订阅本身不会调用模型。输入列表在 `stream(history)` 时复制，历史配对在运行开始时校验。重复订阅可能再次执行工具副作用，不能把它当成同一次运行的回放。

需求量约束每条 AgentEvent。运行线程等待下游需求，模型事件通过单槽交接逐条拉取，不建立无界队列；慢订阅者会延缓模型消费和工具进度。模型适配器自己的总超时仍生效。回调应快速返回，不应在回调中阻塞等待后续事件。

`subscription.cancel()` 使本次运行停止交付事件、中断执行线程并取消当前模型订阅；取消不产生 `onComplete`、`onError` 或最终结果。工具仍需响应线程中断或 `ToolContext.checkCancelled()`，无法强制停止忽略取消的工具，也不会回滚既有副作用。取消不关闭共享模型。

模型流 IOException 沿用 `MODEL_ERROR` 和安全错误元数据；模型缺少完成事件、完成事件之后仍返回数据都作为协议错误停止，不能执行该轮工具。未实现 streaming 的模型通过 `onError(UnsupportedOperationException)` 明确报错，不使用同步调用模拟流式输出。运行时异常通过 `onError` 交付；非正数需求通过 `onError(IllegalArgumentException)` 结束订阅。订阅者自身回调抛错则取消，不再回调该订阅者。
