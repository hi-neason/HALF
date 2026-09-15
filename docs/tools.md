# 工具注册、执行与结果回填

模型提出工具名和参数，宿主提供对应的 Java 实现。`tool` 包负责把一个完整的 `ContentBlock.ToolCall` 转换为可回填的结果；它复用模型层的工具声明和调用类型。

## 核心类型

| 类型 | 职责 |
| --- | --- |
| `Tool` | 提供 `definition()` 声明和兼容的文本执行入口 |
| `ContextualTool` | 接收调用上下文，返回 `ToolOutput` |
| `ToolContext` / `ToolProgress` | 调用标识、协作式取消与同步进度回调 |
| `ToolOutput` | 模型可见文本与宿主专用 JSON 详情 |
| `ToolRegistry` | 在构造时按名称绑定工具，保存定义快照，拒绝重名 |
| `ToolExecutor` | 检查 JSON 参数，查找工具，执行并分类错误 |
| `ToolResult` | 保存调用标识、状态、有界文本、宿主详情和截断标记 |
| `ToolArgumentsException` | 工具主动拒绝不满足字段或业务约束的参数 |

注册表固定绑定集合，并按注册顺序提供不可变的 `definitions()`。定义在注册时读取一次，后续模型声明与工具名称保持一致；工具对象自身的状态仍由宿主维护。

## 声明与校验

`ToolDefinition.parametersJson()` 是发送给模型的 JSON Schema。执行器会拒绝空正文、非对象、重复字段、尾随 JSON/文本，以及超过 1 Mi 个 Java 字符的参数；解析还受 Jackson 默认的嵌套深度和数值长度限制。小数用 `BigDecimal` 保存，避免在工具校验前丢失精度。

字段类型、必填项、额外字段和业务约束由工具在任何副作用之前检查，失败时抛出 `ToolArgumentsException`。声明 Schema 不会自动完成这些检查，当前没有通用 JSON Schema 校验引擎。

[AddTool](../src/main/java/io/github/hi/neason/half/examples/AddTool.java) 展示了完整工具实现：接收两个 32 位整数，拒绝缺失字段、额外字段和越界值，用 `long` 保存相加结果。数值为整数的 `1.0`、`2e0` 也符合其 Schema。

## 执行与回填

```java
var registry = new ToolRegistry(List.of(new AddTool()));
var executor = new ToolExecutor(registry);
var messages = new ArrayList<>(List.of(ChatMessage.user("2 加 3 等于多少？")));

ChatResponse response = model.chat(new ChatRequest(messages, 128, registry.definitions()));
messages.add(ChatMessage.assistantResponse(response));

List<ToolResult> results = executor.executeAll(response.toolCalls());
results.stream().map(ToolResult::toMessage).forEach(messages::add);

// 有工具调用时，宿主可将这份 messages 传给下一次模型请求。
```

使用 `assistantResponse` 保留完整模型消息及 `ReplayState`。工具执行器不解析回放快照，也不维护消息历史或自动推进下一轮模型调用。

流式入口应等整轮响应成功完成，再从最终 `ChatResponse` 取得工具调用。`ToolCallDelta` 和 `ToolCallCompleted` 可用于展示进度，不能仅凭它们开始执行；同一响应后续仍可能失败。

`executeAll` 在执行任何工具前检查同批调用 ID 是否重复，重复时抛出 `IllegalArgumentException`。通过检查后按列表顺序执行，每个返回的结果携带原调用 ID。调用列表和结果列表均使用不可变快照，空批次返回空列表。

## 结果与错误

| 状态 | 含义 | 同批后续调用 |
| --- | --- | --- |
| `SUCCESS` | 工具返回非 null 的文本，空字符串合法 | 继续 |
| `UNKNOWN_TOOL` | 工具未注册 | 继续 |
| `INVALID_ARGUMENTS` | JSON 格式错误或工具主动拒绝参数 | 继续 |
| `EXECUTION_FAILED` | 工具抛出其他 `Exception` 或返回 null | 继续 |

错误输出为固定说明，不把异常正文、参数或堆栈写入模型消息。工具成功返回的文本由工具作者负责选择。

`toMessage()` 生成带原 `toolCallId` 的 TOOL 消息，正文格式为：

```json
{"status":"SUCCESS","output":"5"}
```

`output` 始终是字符串，即使工具返回 JSON 文本也会正确转义。错误状态使用同样的正文结构；这是 HALF 的通用结果格式，由模型适配器映射到供应商的工具结果消息，未增加协议专属错误标记。

## 上下文与进度

旧 `Tool.execute(ObjectNode)` 实现继续可用，执行器自动包装文本结果。新工具实现 `ContextualTool.execute(ObjectNode, ToolContext)`，通过上下文读取 `callId()`、`toolName()`，调用 `checkCancelled()` 检查取消，调用 `reportProgress(message)` 交付执行进度。直接调用 ContextualTool 的单参方法会明确报错，使用执行器才能获得有效上下文。

宿主可以为单次调用或批量调用传入 `BooleanSupplier` 取消源和 `Consumer<ToolProgress>` 回调：

```java
var cancelled = new AtomicBoolean();
var result = executor.execute(call, cancelled::get,
        progress -> System.out.println(progress.callId() + ": " + progress.message()));
```

取消源和回调应快速返回。进度同步交付，取消源和进度回调中的运行时异常或 Error 原样传播给执行器调用方，不能转成工具业务错误；工具捕获回调异常后返回也不会产生成功结果。进度回调串行化，调用结束后通过保留上下文发送的更新会被忽略。执行器不保存进度历史；宿主应自行控制收集量。

这些进度表示工具实际执行情况，与模型生成参数的 `ToolCallDelta` 分开；进度不会自动加入模型历史。

## 输出与宿主详情

`ToolOutput(text, details)` 的 `details` 是宿主专用 JSON 数据，`ToolResult.details()` 保留这些数据。详情仅接受普通 JSON 值，拒绝内嵌 POJO、二进制节点、非有限浮点数和 missing 节点，最大嵌套深度为 128。构造及读取时复制 JSON 树，`toMessage()` 只输出状态、文本和必要的截断标记，避免将宿主详情隐式发送给模型。无详情的文本工具使用 JSON null。

执行器默认将每次结果及每条进度限制为 16,000 个 UTF-16 字符，可用 `new ToolExecutor(registry, maximum)` 调整，最小为 64。超限时保留前缀并追加 `[truncated]`，标记计入限制且不切断 Unicode 代理对；结果或进度同时设置 `truncated=true`。短结果继续使用原有两字段消息格式，截断结果的模型正文增加 `truncated` 字段。

这是文本字段的预算，不是 JSON 转义后的线缆大小或 token 数限制。宿主详情不占模型文本预算，也未设置独立大小上限；工具产生完整文本之后才裁剪，因此工具仍须控制自身读取和内存开销。

`ToolResult` 保留旧四参构造器及 `output()` 访问器，新增组件会改变 record 的反射、序列化和二进制形状。

## 中断与执行边界

工具执行发生在调用线程中，一次批量调用按顺序进行。执行前后检查线程中断和宿主取消信号；`InterruptedException`、`CancellationException` 和 `Error` 向上传播，并停止剩余调用。遇到中断标志时，执行器清除标志并抛出 `InterruptedException`，与阻塞调用的异常处理方式一致。

已执行的副作用不会回滚，失败或取消后的重新调用可能再次执行工具；当前不提供全局幂等、超时调度或沙箱。宿主取消信号采用协作式检查，不自动中断阻塞线程。阻塞工具应自行支持中断并设置其 I/O 超时。宿主并发调用执行器时，工具对象的线程安全由宿主负责。

当前没有 AgentLoop。运行级轮次上限、事件和取消接口将在循环模块中设计。

## 离线验证

```bash
mvn test
mvn compile exec:java -Dexec.mainClass=io.github.hi.neason.half.examples.ToolExample
mvn compile exec:java -Dexec.mainClass=io.github.hi.neason.half.examples.ToolContextExample
```

工具测试覆盖注册快照、严格参数解析、结果关联、顺序执行、重复 ID 预检、错误分类以及中断取消。`ToolConversationTest` 用假模型完成两次请求，验证 Java 加法结果回填和 Responses 回放状态保留，无需真实模型或密钥。
