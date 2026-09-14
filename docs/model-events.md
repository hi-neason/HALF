# 结构化模型事件、需求量与工具调用

## 从文本回调到事件流

```java
Flow.Publisher<ModelEvent> publisher = model.stream(request);
publisher.subscribe(new Flow.Subscriber<ModelEvent>() {
    private Flow.Subscription subscription;

    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    public void onNext(ModelEvent event) {
        System.out.println(event);
        subscription.request(1); // 处理完本事件，再请求下一条
    }

    public void onError(Throwable error) {
        System.err.println(error.getMessage());
    }

    public void onComplete() {
        System.out.println("流已完成");
    }
});
```

`stream(request)` 立即返回一个冷 Publisher。每次订阅都有独立的 HTTP 调用和聚合状态；订阅者第一次请求正数需求量后才发出网络请求。重复订阅会再次调用模型，也可能再次计费。

| 事件 | 表达的信息 |
| --- | --- |
| `TextDelta` | 新增文本 |
| `ToolCallStarted` | 工具调用的 index、id 和 name |
| `ToolCallDelta` | 某个 index 对应的参数 JSON 字符串片段 |
| `ToolCallCompleted` | 参数已拼接并通过 JSON 对象语法校验的工具调用 |
| `Usage` | 服务端报告的本次用量 |
| `Completed` | 收到协议终止事件后的 `ChatResponse`，文本可能因 token 上限而截断 |

所有事件都是不可变值，没有随着后续分片改变的共享 partial 对象。错误走 `Subscriber.onError`，不会再作为另一种成功事件发送。`Completed` 是占用需求量的数据事件；它交付后再发送不占需求量的 `onComplete`。

两个适配器共用的 `stream(request, onTextDelta)` 会订阅这个事件流并请求全部事件，将 `TextDelta` 转交回调，最终返回 `Completed` 中的响应。非流式 `chat()` 仍单独调用 `stream: false`，便于对照普通与流式调用。

## 需求量与取消的实际边界

- `request(n)` 的单位是 **ModelEvent 数量**，不是字节、SSE 行或 token。需求量可以累加，溢出时饱和至 `Long.MAX_VALUE`。
- 没有需求量就不向上层发送 `onNext`；待交付事件用尽后，没有剩余需求也不再申请下一条 SSE 行。
- 一个 SSE JSON chunk 可产生多条事件，例如工具开始和参数增量。HALF 只缓存当前帧产生的待交付事件，最多 256 条，不使用无界事件队列。
- 流式语义解析另限制单 SSE 帧为 1 Mi 个 Java 字符、累计文本和工具参数等内容为 4 Mi 个字符、工具调用最多 64 个，超限报错。JDK 自身的字节解码和单行缓冲不由这些限制控制。
- 暂停读取会限制本地消费速度，但不能保证远程模型立刻暂停生成；HTTP、TCP 和服务器可能已有缓冲或正在推理。
- `cancel()` 取消该订阅并停止 HTTP 交换；不再发送完成或错误通知，已开始的用户回调可能仍在执行。
- 非正数需求触发一次 `onError(IllegalArgumentException)`。正常完成与错误最多出现一种终止信号；错误无需等待需求量。
- 从第一次正数请求开始计算总超时，包含等待消费者追加需求量的时间。模型关闭时，尚未结束的订阅也会收到错误。

回调在派发线程上串行执行，线程不固定，应快速返回。不要在回调中等待本流结束或关闭同一个模型。订阅者从 `onNext` 抛异常违反 Flow 回调约定，HALF 会取消订阅，不再次调用该订阅者；文本便捷接口则捕获用户文本回调异常并传回阻塞调用方。

## 文本和工具调用使用内容块

`ChatResponse.content()` 是不可变 `List<ContentBlock>`，当前包含 `Text` 与 `ToolCall`。`text()` 拼接文本块，`toolCalls()` 提取工具调用。旧的文本消息工厂、请求构造方式和 `ChatResponse(String, ...)` 构造方式保留源码兼容；修改后的 record 不承诺旧二进制或反射结构兼容。

工具声明示例：

```java
var weather = new ToolDefinition(
    "weather",
    "查询城市天气",
    """
    {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
    """
);

var request = new ChatRequest(
    List.of(ChatMessage.user("北京天气如何？")),
    null,
    List.of(weather)
);
```

适配器将它编码到 `tools[].type = function` 及 `tools[].function`。JSON Schema 只检查基本对象形状，不在当前阶段实现完整 Schema 验证器。

模型可能返回：

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_a",
        "type": "function",
        "function": {"name": "weather", "arguments": "{\"city\":\"北京\"}"}
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

流式协议中，`delta.tool_calls[].index` 区分并行调用。首个分片需包含 id、function 类型和 name；后续可以省略或为 null。HALF 保留元数据，并按 index 累加 `function.arguments`，不同工具的参数可以交错到达。重复元数据必须一致，调用 ID 必须唯一。

参数片段通常不是合法 JSON。仅收到 `finish_reason: tool_calls` 后，才校验拼接出的参数是 JSON 对象并产生 `ToolCallCompleted`；截断、身份冲突或非法参数均失败。`ToolCallCompleted` 不等于整个流已成功，执行工具前仍应等待最终完成并进行宿主校验。

当前最终响应按文本在前、工具 index 升序整理，不保留文本与工具事件的时间交错；需要交错展示时使用事件流。

## 工具结果如何回填

宿主取得完整响应、校验调用并自行产生结果后，可以这样构建下一次请求：

```java
var messages = new ArrayList<>(request.messages());
messages.add(ChatMessage.assistantResponse(response));
messages.add(ChatMessage.toolResult("call_a", "晴，25°C"));
var next = new ChatRequest(messages, null, request.tools());
```

`assistantResponse` 保留工具调用，Chat Completions 将工具结果编码为 `role: tool`、对应的 `tool_call_id` 和文本 `content`；Responses 使用 `function_call_output`、`call_id` 和 `output`。有多个调用时，宿主需要为各调用添加结果。

这里实现的是声明、解析、分片聚合与回填协议，没有自动执行工具、完整参数 Schema 校验、权限策略或 Agent 循环。模型建议的工具名、参数和值仍由宿主校验；结构合法不代表可以执行。

## 阅读与验证

- 数据定义：[ModelEvent](../src/main/java/io/github/hi/neason/half/model/ModelEvent.java)、[ContentBlock](../src/main/java/io/github/hi/neason/half/model/ContentBlock.java)、[ToolDefinition](../src/main/java/io/github/hi/neason/half/model/ToolDefinition.java)。
- 需求量与生命周期：[OpenAiStream](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiStream.java)。
- 分片与聚合：[OpenAiEventDecoder](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiEventDecoder.java)。
- Responses 分片与聚合：[OpenAiResponsesEventDecoder](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiResponsesEventDecoder.java)，详见 [Responses 协议](responses.md)。
- HTTP：[OpenAiHttpModel](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiHttpModel.java)。
- Chat Completions 请求映射：[OpenAiChatModel](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiChatModel.java)。

运行 `mvn test` 验证本地协议。配置模型环境变量后，`mvn compile exec:java -Dexec.args="--events 解释背压"` 展示逐事件消费；该命令会调用配置的服务。

协议资料：[OpenAI function calling](https://developers.openai.com/api/docs/guides/function-calling)、[JDK 21 Flow.Subscription](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.Subscription.html)。核对日期：2026-09-14。
