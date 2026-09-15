# 第一步：LLM 接口协议与模型抽象

本文以 Chat Completions 为例；另一个已实现的协议见 [Responses](responses.md)。

## 一次模型调用发生了什么

```text
Java 消息 → ChatRequest → OpenAiChatModel → JSON → HTTP POST
                                                     ↓
ChatResponse ← 响应字段映射与校验 ← JSON ← HTTP 响应
```

LLM 服务对应用暴露的是 HTTP 接口。请求中的 `messages` 是有顺序的对话上下文，`model` 是服务端模型标识。服务端完成推理后返回 JSON，客户端从中提取模型文本、结束原因和用量。

当前实现不维护隐式历史。第二轮对话需要调用方显式传入第一轮的用户消息、助手响应和新的用户消息；复用一个 `ChatModel` 对象不会自动记住上一轮。

## 先区分协议与厂商

当前实现的是 OpenAI Chat Completions 协议的文本与 function 工具调用子集，支持完整响应和流式调用。本文先说明非流式请求；SSE 的增量、结束与取消语义见 [流式接口](streaming.md)。一个兼容服务可使用同一适配器并配置不同的完整接口 URL、密钥和模型 ID，但必须实际支持所发送的字段。

Anthropic Messages、Gemini generateContent、OpenAI Responses 是不同协议，应分别编写适配器；只修改地址并不能使当前实现自动支持它们。后续通过对比协议，逐步判断公共抽象是否需要扩展。

## 请求：Java 对象怎样变成线上字段

```http
POST /v1/chat/completions HTTP/1.1
Authorization: Bearer <API_KEY>
Content-Type: application/json
Accept: application/json
```

```json
{
  "model": "<服务支持的模型 ID>",
  "stream": false,
  "messages": [
    {"role": "system", "content": "请简洁回答。"},
    {"role": "user", "content": "什么是上下文？"}
  ]
}
```

| Java 输入 | 协议字段 | 谁负责 |
| --- | --- | --- |
| 构造适配器时指定的模型名 | `model` | 具体模型实现 |
| `ChatMessage.role` | 小写的 `messages[].role` | 协议映射 |
| `ChatMessage.text` | `messages[].content` | 协议映射，Jackson 处理转义 |
| `ChatRequest.maxOutputTokens` | `max_completion_tokens` | 仅在非 null 时发送 |
| 非流式调用方式 | `stream: false` | 当前适配器固定设置 |

`max_completion_tokens` 是生成 token 的上限，包含某些模型的推理 token，不等于可见字符数，也不是输入上下文上限。兼容服务可能仍要求不同参数名；当前实现不自动猜测或退回 `max_tokens`。不设置时不发送该字段，以服务端默认值为准。

支持的消息角色为 system、user、assistant；具体模型可能限制角色或可选参数。JSON 可以用 Jackson，角色映射、参数选择和协议语义由 HALF 自行实现。

## 响应：HTTP 200 不代表每种结果都能当成文本

以下是便于阅读的响应节选：

```json
{
  "choices": [{
    "message": {"role": "assistant", "content": "上下文是模型本次生成所依据的信息。"},
    "finish_reason": "stop"
  }],
  "usage": {"prompt_tokens": 20, "completion_tokens": 12, "total_tokens": 32}
}
```

`ChatResponse` 包含文本、原始 `finishReason` 和可选 `TokenUsage`。没有用量字段时使用 `Optional.empty()`，不编造零用量。输入用量、输出用量和总用量均取自服务端，不在本地估算。

- `stop`：正常停止。
- `length`：达到生成上限，返回的文本可能不完整。
- `content_filter`：受内容过滤影响，不能视作普通完成。
- 其他结束原因保留原值，调用方不要只判断“有没有文本”。

协议允许 `content: null`。当前实现将文本映射为 `ContentBlock.Text`，将 `tool_calls` 映射为 `ContentBlock.ToolCall`；只有工具调用或拒绝内容时文本可为 null。`ChatResponse.text()` 仍可提取拼接文本，`toolCalls()` 获取工具调用列表。拒绝响应映射为 `ContentBlock.Refusal`，通过 `refusal()` 读取；已弃用的 `function_call` 仍明确报错。流式 `delta.content` 缺省或 null 表示该事件没有新增文本。工具协议与结果回填见 [模型事件与工具内容块](model-events.md)。

适配器只接受一个 choice，不静默丢弃多个候选。允许额外的响应字段和空字符串文本；对缺失的必要字段、不合法的 JSON、错误类型或负数用量明确报错。

## 代码阅读顺序

1. [ChatMessage](../src/main/java/io/github/hi/neason/half/model/ChatMessage.java)、[ChatRequest](../src/main/java/io/github/hi/neason/half/model/ChatRequest.java)：消息顺序、输入校验与不可变列表。
2. [ChatModel](../src/main/java/io/github/hi/neason/half/model/ChatModel.java)：模型的最小接口，不关心 HTTP 或 JSON。
3. [OpenAiChatModel](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiChatModel.java)：`encodeRequest` → [OpenAiHttpModel](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiHttpModel.java) 的 `HttpClient.send` → `decodeResponse`。
4. [ChatResponse](../src/main/java/io/github/hi/neason/half/model/ChatResponse.java)、[TokenUsage](../src/main/java/io/github/hi/neason/half/model/TokenUsage.java)：把协议字段映射回 Java 对象。
5. [ChatExample](../src/main/java/io/github/hi/neason/half/examples/ChatExample.java)：配置供应商，随后通过 `ChatModel` 调用。

两个适配器通过共用的 `OpenAiHttpModel` 管理各自可复用的 `HttpClient`，使用完毕后关闭；示例通过 try-with-resources 完成释放。端点必须是完整 HTTP(S) URL，不包含 URL 用户凭据、查询参数或 fragment。远程服务使用 HTTPS，本地测试使用 HTTP。

## 错误与验证

| 情况 | Java 表达 |
| --- | --- |
| 空消息列表、非正数上限等无效输入 | `IllegalArgumentException`；null 必填值为 `NullPointerException` |
| 非 2xx，包括鉴权失败、限流、服务端错误和重定向 | `ModelHttpException`，保留 HTTP 状态码 |
| JSON 错误、结构不匹配或不支持的响应 | `ModelProtocolException` |
| 连接失败、超时 | JDK `IOException` 及其子类 |
| 调用线程被中断 | 向调用方传播 `InterruptedException` |

当前不实现应用层自动重试，也不跟随重定向。HTTP 错误不包含原始响应正文；JSON 解析异常也不携带可能泄露正文的底层异常。尚未对接供应商错误码、请求 ID 和 Retry-After 的完整诊断模型。

测试启动本地 HTTP 服务，实际接收 JDK 客户端的请求并返回固定响应。这能验证方法、路径、鉴权头、序列化、解析和错误分类；无法证明某个在线厂商或具体模型已联调通过。

运行 `mvn test`，然后尝试阅读测试中的 JSON，并预测它会得到哪种响应或异常。真实调用方式见 [README](../README.md)。

## 官方协议资料

字段核对日期：2026-09-14。

- [创建 Chat Completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [Chat 响应结构](https://developers.openai.com/api/reference/resources/chat)
- [API 鉴权与概览](https://developers.openai.com/api/reference/overview)
- [HTTP 错误说明](https://developers.openai.com/api/docs/guides/error-codes)
