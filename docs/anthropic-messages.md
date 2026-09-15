# Anthropic Messages 协议

`AnthropicMessagesModel` 使用 JDK `HttpClient` 和 Jackson 实现 Anthropic 的消息协议，接入现有 `ChatModel`、`ChatResponse` 和 `Flow.Publisher<ModelEvent>`。

## 调用入口

构造器接收完整端点、密钥、模型 ID 和总超时时间。官方端点为 `https://api.anthropic.com/v1/messages`；认证使用 `x-api-key` 和 `anthropic-version: 2023-06-01`。

```java
try (var model = new AnthropicMessagesModel(
        URI.create("https://api.anthropic.com/v1/messages"),
        System.getenv("HALF_API_KEY"), System.getenv("HALF_MODEL"), Duration.ofSeconds(60))) {
    var request = new ChatRequest(List.of(
            ChatMessage.system("请用中文简短回答。"), ChatMessage.user("解释 SSE。")), 1024);
    ChatResponse result = model.stream(request, System.out::print);
    System.out.println(result.finishReason());
}
```

也可使用项目示例：设置 `HALF_MODEL_API=anthropic-messages`，并设置 `HALF_MODEL_ENDPOINT`、`HALF_MODEL`、`HALF_API_KEY`，然后运行：

```bash
mvn compile exec:java -Dexec.args="--events 解释 Messages 协议"
```

Messages 要求 `max_tokens`。请求未指定 `maxOutputTokens` 时，适配器使用 1024；这不是厂商默认值。调用方应按任务和推理预算显式设置。

## 消息与内容映射

| HALF 类型 | Messages 表示 |
| --- | --- |
| 开头连续的 SYSTEM / DEVELOPER 消息 | 顶层 `system` 文本块 |
| USER / ASSISTANT | `messages` 中的 `user` / `assistant` |
| `ContentBlock.Text` | `text` |
| `ContentBlock.Image` | `image` 及 URL / base64 source |
| `ContentBlock.ToolCall` | `tool_use`，参数解析成 `input` 对象 |
| TOOL 消息 | 下一条用户消息内的 `tool_result`，按 `tool_use_id` 关联 |
| `ContentBlock.Thinking` | `thinking` 与 `signature` |
| `ContentBlock.RedactedThinking` | `redacted_thinking.data` |

中途插入的系统消息会被拒绝，以免把它移动到开头而改变历史语义。工具定义的 JSON Schema 放在 `input_schema`；REQUIRED 映射为 `tool_choice.type=any`，指定函数映射为 `type=tool`。工具结果只做协议回填，不自动执行函数。

`ChatMessage.assistantResponse(result)` 保留推理块、签名和隐藏推理数据；签名与隐藏数据是不透明值，回填时不能重写。普通正文通过 `result.text()` 获取，推理不会混入正文。

## 请求选项

`ModelOptions` 的 `temperature`（0–1）、`topP`、工具选择和并行工具开关会映射为原生参数。JSON Schema 输出使用 `output_config.format`；无 Schema 的 `JsonObject` 模式、OpenAI 专用的 verbosity 和推理摘要选项会明确报错。

第五个构造参数可选择 `new AnthropicThinking.Enabled(1024)` 或 `new AnthropicThinking.Adaptive()`。前者显式设置推理预算，预算至少为 1024 且必须小于请求的 `maxOutputTokens`；后者让支持它的模型调整推理量。未传该参数时不主动启用推理。通用 `reasoningEffort` 的 LOW、MEDIUM、HIGH、MAX 映射到 `output_config.effort`。具体模型支持哪些组合由服务端决定，不应为所有模型套用同一个配置。

## 流式事件

每次订阅独立发起请求，正数 `request(n)` 后启动；支持取消和总时限。协议状态机读取：

```text
message_start
  content_block_start → content_block_delta* → content_block_stop
  ...
message_delta → message_stop
```

文本增量映射为 `TextDelta`；工具参数分片映射为 `ToolCallDelta`，块结束后校验完整 JSON 对象，再交付 `ToolCallCompleted`。推理映射为 `ThinkingDelta` 和 `ThinkingCompleted`，签名增量单独聚合。`message_stop` 才产生最终 `Completed`。

`ping` 与未知顶层事件可忽略；流内 `error`、已知事件格式错误和提前断流会使订阅失败。`message_delta.usage` 是累计值，不能逐次相加；总输入量包含普通输入、缓存创建和缓存读取 token。

## 验证范围与边界

自动化测试使用本地 HTTP 服务，验证请求格式、事件顺序、工具/推理回填、订阅需求量、超时及失败路径。2026-09-15 已通过 Ark Messages 路由验证普通响应、流式文本、Thinking 和工具回填；该路由的 JSON Schema 输出测试未通过，详见[真实服务联调记录](provider-verification.md)。尚未使用官方 Anthropic 服务实测。

本适配器聚焦一次 Messages 生成及其流式映射，不包含 `count_tokens`、Messages Batches、Files 上传、文档内容块、服务端工具执行、Agent 循环，也不声明覆盖所有模型专有参数或内容类型。

## 官方依据

核对日期：2026-09-15。

- [创建消息与请求字段](https://platform.claude.com/docs/en/api/messages/create)
- [API 鉴权](https://platform.claude.com/docs/en/api/overview)
- [流式消息与累计用量](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [缓存 token 的统计](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)
- [Thinking 与签名回传](https://platform.claude.com/docs/en/build-with-claude/thinking)
- [结构化输出](https://platform.claude.com/docs/en/build-with-claude/structured-outputs)
- [工具调用结果](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
