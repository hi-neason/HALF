# Responses 协议

`OpenAiResponsesModel` 实现 `/v1/responses` 的普通 JSON 响应、SSE 文本和函数调用，使用 JDK HTTP、Jackson 与原生 Java 状态机。它和 `OpenAiChatModel` 实现同一个 `ChatModel`，上层继续消费 `ModelEvent`。

## 使用

```java
try (var model = new OpenAiResponsesModel(
        URI.create("https://api.openai.com/v1/responses"),
        apiKey, modelId, Duration.ofSeconds(60))) {
    var request = new ChatRequest(List.of(ChatMessage.user("解释 SSE")));
    ChatResponse response = model.chat(request);
    // 或 model.stream(request, System.out::print);
    // 或 model.stream(request).subscribe(subscriber);
}
```

示例入口共用 `ChatExample`，默认协议仍为 `chat-completions`。设置 `HALF_MODEL_API=responses`，将 `HALF_MODEL_ENDPOINT` 设为完整的 Responses 地址，并配置 `HALF_MODEL`、`HALF_API_KEY` 后执行：

```bash
mvn compile exec:java -Dexec.args="--events 解释模型事件"
```

`--stream` 使用文本回调，不带选项使用普通响应。上层 `onNext` 的完整类型分支见 [ChatExample](../src/main/java/io/github/hi/neason/half/examples/ChatExample.java)。

## 请求和响应映射

| HALF | Responses JSON |
| --- | --- |
| 消息历史 | `input` 中的 system/user/assistant 消息 |
| 助手工具调用 | 独立的 `function_call` 项：`call_id`、`name`、`arguments` |
| 工具结果 | `function_call_output`：`call_id`、文本 `output` |
| 工具声明 | `tools[]` 内直接放 `type: function`、`name`、`description`、`parameters` |
| `maxOutputTokens` | `max_output_tokens` |
| 文本内容块 | `output[]` 的 message 项中的 `output_text` |
| 工具调用内容块 | `output[]` 的 function_call 项 |
| 用量 | `input_tokens`、`output_tokens`、`total_tokens` |

工具声明默认使用 `strict: false`，保留现有 Schema 的可选字段语义；可通过 `ToolDefinition.strict` 显式覆盖。框架只校验参数为 JSON 对象，不替代完整 Schema 校验。

每次调用发送 `store: false`，由宿主传入历史，不维护服务端会话。`ChatMessage.assistantResponse(response)` 与 `ChatMessage.toolResult(callId, result)` 可直接用于下一轮请求。

Responses 的输出项 ID（如 `fc_...`）与工具调用关联 ID（如 `call_...`）不同。解码器用 `output_index` 和项 ID 匹配分片，交付的 `ContentBlock.ToolCall.id` 是 `call_id`，回填结果也使用它。

## 如何实例化事件

[OpenAiResponsesEventDecoder.accept](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiResponsesEventDecoder.java) 解析 SSE 的 JSON `type` 字段，显式创建 Java record：

| 协议事件 | HALF 处理 |
| --- | --- |
| `response.output_item.added`，项类型为 function_call | `new ModelEvent.ToolCallStarted(...)` |
| `response.output_text.delta` | `new ModelEvent.TextDelta(...)` |
| `response.refusal.delta` | `new ModelEvent.RefusalDelta(...)` |
| 推理摘要/正文 delta | `new ModelEvent.ReasoningDelta(...)` |
| 推理项 `response.output_item.done` | `new ModelEvent.ReasoningCompleted(...)` |
| `response.function_call_arguments.delta` | `new ModelEvent.ToolCallDelta(...)` |
| `response.function_call_arguments.done` | 核对完整参数与累计分片，不重复追加 |
| `response.output_item.done`，项类型为 function_call | 校验身份、参数和状态，创建 `ToolCallCompleted` |
| `response.completed` | 核对最终快照，按需创建 `Usage`，然后创建 `Completed` |
| `response.incomplete` | 文本可返回部分结果；包含工具调用则报错 |
| `response.failed`、`error` | `Subscriber.onError`，不暴露服务端错误正文 |

消息和内容块的 added/done 事件管理生命周期；重复项、身份变化、done 后的增量、最终快照与分片不一致都会报协议错误。并行工具调用按 `output_index` 分开聚合。

Responses 不等待 `[DONE]`；读取到合法的终止事件后停止 HTTP 读取。`Completed` 仍遵守订阅需求量，随后发送 `onComplete`。EOF 先于终止事件视为失败，即使已交付部分文本。`ToolCallCompleted` 仅表示该调用参数收集完毕，不表示整个响应成功或工具已执行。

`ChatResponse.finishReason` 在成功时为 Responses 的 `completed`；文本截断时为 `incomplete_details.reason`（例如 `max_output_tokens`）。它不会伪装成 Chat Completions 的 `stop` 或 `tool_calls`，判断是否存在工具调用使用 `response.toolCalls()`。

## 共用部分与边界

[HttpChatModel](../src/main/java/io/github/hi/neason/half/model/http/HttpChatModel.java) 共用 HTTP、总时限、关闭与文本回调；[ModelStream](../src/main/java/io/github/hi/neason/half/model/http/ModelStream.java) 共用 SSE 分帧、冷订阅、`request(n)` 和取消。每个订阅有独立的协议解码器，只有首次正需求量才发请求。

单 SSE 帧最多 1 Mi 字符，累计可见文本、工具参数和身份字段最多 4 Mi 字符；最多 128 个输出项、每条消息 128 个文本块、64 个工具调用。上层背压限制交付和继续读取，不保证远端模型暂停生成。

模型入口已支持图片/文件输入、拒绝和推理内容；推理 ID、摘要、正文与加密内容可通过 `assistantResponse` 回传，仍不混入 `text()`。引用注解、音频、内置工具等未映射的结构使用保留 JSON 的官方 API client；资源端点、后台响应查询及请求选项见 [官方协议覆盖](official-api.md)。当前模型抽象不保留所有官方扩展字段，不承诺整个响应的无损往返。

测试使用本地 HTTP 服务与协议状态机，不需要真实密钥；没有宣称已完成真实厂商联调。

## 协议资料

- [OpenAI Responses 迁移指南](https://developers.openai.com/api/docs/guides/migrate-to-responses)
- [OpenAI 流式响应](https://developers.openai.com/api/docs/guides/streaming-responses)
- [OpenAI 函数调用](https://developers.openai.com/api/docs/guides/function-calling)

核对日期：2026-09-14。
