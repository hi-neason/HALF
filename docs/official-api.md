# 官方协议覆盖与调用入口

Anthropic 的独立协议实现见 [Messages 协议](anthropic-messages.md)。

本文只讨论 OpenAI Chat Completions 和 Responses 的 HTTP/SSE 接口，不涉及工具执行或 Agent 行为。当前提供两种入口：

- `OpenAiChatModel` / `OpenAiResponsesModel`：将已支持的请求选项、内容和事件映射成 HALF 的 Java 类型。
- `OpenAiChatCompletionsClient` / `OpenAiResponsesClient`：按官方 JSON 调用资源端点，返回完整 JSON 或 SSE 数据，不将输出压缩成 `ChatResponse`。

后者可以携带模型适配器尚未映射的官方字段，例如 `background`、`previous_response_id`、内置工具、音频配置、多个候选和附加用量信息。框架负责发送请求、解析 JSON/SSE 和生命周期；字段组合、模型支持及权限由官方服务校验。JSON 透传能力不等于所有字段都有强类型封装或已真实联调。

## 已实现端点

API client 构造器使用 API 根地址，例如 `https://api.openai.com/v1`；模型适配器构造器仍使用完整端点。不要混用两种地址。

| 协议 | Java 方法 | HTTP |
| --- | --- | --- |
| Responses | `create(body)` / `stream(body)` | `POST /responses` |
| Responses | `retrieve(id)` / `retrieve(id, query)` | `GET /responses/{id}` |
| Responses | `retrieveStream(id, query)` | `GET /responses/{id}?stream=true` |
| Responses | `delete(id)` | `DELETE /responses/{id}` |
| Responses | `cancel(id)` | `POST /responses/{id}/cancel` |
| Responses | `listInputItems(id, query)` | `GET /responses/{id}/input_items` |
| Responses | `compact(body)` | `POST /responses/compact` |
| Responses | `countInputTokens(body)` | `POST /responses/input_tokens` |
| Chat Completions | `create(body)` / `stream(body)` | `POST /chat/completions` |
| Chat Completions | `retrieve(id)` | `GET /chat/completions/{id}` |
| Chat Completions | `update(id, body)` | `POST /chat/completions/{id}` |
| Chat Completions | `delete(id)` | `DELETE /chat/completions/{id}` |
| Chat Completions | `list(query)` | `GET /chat/completions` |
| Chat Completions | `listMessages(id, query)` | `GET /chat/completions/{id}/messages` |

分页方法返回单页原始 JSON，调用方读取 `has_more`、游标后继续请求，不自动遍历全部记录。查询参数接受 `Map<String, ?>`；列表值编码为重复数组参数，例如 `Map.of("include", List.of("message.input_image.image_url"))`。恢复流可传 `starting_after`。ID 和查询参数编码后拼接，不能改变目标路径结构。

存储后的资源才能按官方规则查询或管理；取消操作只适用于服务端允许取消的后台响应。方法存在不意味着每种响应都能被查询或取消。

## 官方 JSON 调用

```java
var json = new ObjectMapper();
var body = json.createObjectNode();
body.put("model", modelId).put("input", "解释 HTTP 流式响应");
body.put("store", false);
body.putObject("reasoning").put("effort", "medium");

try (var client = new OpenAiResponsesClient(
        URI.create("https://api.openai.com/v1"), apiKey, Duration.ofSeconds(60))) {
    JsonNode response = client.create(body);
    System.out.println(response.path("status"));
    // output 中的推理项、拒绝、工具输出和其他字段都保留在 JSON 中。
}
```

`create` 设置 `stream: false`，`stream` 设置 `stream: true`，不会修改调用方传入的 ObjectNode。其他官方字段不被删改；JSON 返回值是本次调用独立的树。服务端返回 204/205 时返回 Jackson `NullNode`，不尝试将空正文解析成 JSON。

```java
client.stream(body).subscribe(new Flow.Subscriber<OpenAiSseEvent>() {
    private Flow.Subscription subscription;

    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    public void onNext(OpenAiSseEvent event) {
        // event.event() 为 SSE event 字段；event.data() 是不可变的完整 data 字符串。
        // event.json() 解析独立 JSON 树；读取 type 后按需要处理。
        System.out.println(event.data());
        subscription.request(1);
    }

    public void onError(Throwable error) { /* 处理 HTTP、解析、超时等传输失败 */ }
    public void onComplete() { /* 传输到达协议终点；检查业务终止事件的 status */ }
});
```

宿主应保持 client 打开直到订阅结束。每个订阅有独立虚拟线程和状态，首次正需求量才发请求；交付事件遵守 `request(n)`，取消时关闭 HTTP 正文。单 SSE 帧上限为 4 Mi 字符，无无限事件队列。总时限包含等待消费者继续请求的时间。

官方 Responses 的 `response.failed`、`response.incomplete`、`error` 在此入口保留为原始事件；随后结束流。**这里的 `onComplete` 表示传输结束，不能单凭它判断响应成功。** Chat Completions 的 `[DONE]` 作为结束标记消费，不交付为 JSON；有限需求量耗尽后仍需请求下一条才能继续读取终止帧（示例逐条请求已覆盖这一点）；Responses 按终止事件类型结束。未支持或未来新增的普通事件仍完整交付，JSON 解析失败、EOF 提前到达、HTTP 错误通过 `onError`。

## 模型接口新增选项

```java
var options = ModelOptions.builder()
        .temperature(0.5)
        .topP(0.9)
        .parallelToolCalls(false)
        .toolChoice(ToolChoice.Mode.AUTO)
        .responseFormat(new ResponseFormat.JsonSchema(
                "answer", "回答结构",
                "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}",
                true))
        .build();
var request = new ChatRequest(List.of(ChatMessage.user("解释 SSE")), 256, List.of(), options);
```

旧构造器保持源码兼容；新增的 record 组件会改变序列化形状及相等性比较，不保证已编译调用方的二进制兼容。

选项可组合但不同模型可能不接受相同组合；例如部分推理模型限制采样参数。旧构造器继续可用，未配置的参数省略。

| 选项 | Chat Completions | Responses |
| --- | --- | --- |
| temperature / topP | `temperature` / `top_p` | 相同 |
| toolChoice | mode 字符串或嵌套 `function.name` | mode 字符串或直接 `name` |
| parallelToolCalls | `parallel_tool_calls` | 相同 |
| responseFormat | `response_format` | `text.format` |
| reasoningEffort | `reasoning_effort` | `reasoning.effort` |
| reasoningSummary | 不支持，显式报错 | `reasoning.summary` |
| includeEncryptedReasoning=true | 不支持，显式报错 | `include: ["reasoning.encrypted_content"]` |
| verbosity | `verbosity` | `text.verbosity` |

`ToolChoice.Function(name)` 强制选择已声明的函数。`ResponseFormat.Text`、`JsonObject`、`JsonSchema` 对应官方输出格式。`ToolDefinition` 的第四个可选参数为 `strict`；旧构造器保持原有默认行为。框架不执行完整 JSON Schema 验证，也不把严格输出当作必然成功；调用方仍要检查拒绝和结束原因。

## 模型内容与事件

- `ContentBlock.Image(url, detail)`：图片 URL 或 data URL；模型层只传引用，不自行下载。`original` 是否可用取决于模型；官方图像指南有 Chat 用例，而 Chat 字段参考的枚举尚不一致，最终由服务端校验。
- `ContentBlock.File.byId(id)` / `byData(filename, data)`：已有文件 ID 或编码后的文件数据；不会自动上传本地路径。
- `ChatMessage.developer(text)`：官方 developer 消息角色。
- `ContentBlock.Refusal` / `ModelEvent.RefusalDelta`：拒绝内容独立保留，`ChatResponse.refusal()` 可读取，不混入 `text()`。
- `ContentBlock.Reasoning`：Responses 的 ID、摘要文本、推理文本和不透明 `encrypted_content`；通过 `assistantResponse` 按顺序回传。
- `ModelEvent.ReasoningDelta` / `ReasoningCompleted`：推理分片和完成项；`summary` 标志区分摘要与推理正文。加密内容不解密，不转换成普通文本。

Responses 解码同时保留 `ChatResponse.outputItemsJson()` 原始输出项；`assistantResponse` 携带这些快照，回传时保留消息 ID、状态、注解等字段并核对内容一致性。纯文本可自行构造；拒绝项的 Responses 回传必须使用原始快照，不能仅凭拒绝文本重建完整的官方输出消息。

模型层对图片和文件限定 USER 角色，推理和拒绝限定 ASSISTANT 角色。Chat Completions 不接受 Responses 推理项回传，会显式报错。对于音频、原始注解、内置工具输出、多个候选等尚未映射为 `ContentBlock` 的官方结构，应使用保留 JSON 的 API client。

## 范围与验证边界

已实现上述 HTTP 路由、请求编码、JSON/SSE 读取和两种入口的本地协议测试。尚未真实厂商联调，不宣称覆盖 OpenAI 全部 API 产品。

仍未实现独立 Files 上传/管理、Conversations 资源管理、Realtime、Responses WebSocket、音视频专用 API、Batch 等接口；也没有自动重试、自动后台轮询或全量官方强类型 Schema。某字段能在 JSON 请求中发送，不代表本地已经对该字段所有语义做过专项验证。

## 官方资料

- [Responses 创建](https://developers.openai.com/api/reference/python/resources/responses/methods/create)
- [Responses 查询](https://developers.openai.com/api/reference/cli/resources/responses/methods/retrieve)
- [Chat Completions 创建](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)
- [图片输入](https://developers.openai.com/api/docs/guides/images-vision)
- [文件输入](https://developers.openai.com/api/docs/guides/file-inputs)
- [推理模型](https://developers.openai.com/api/docs/guides/reasoning)

核对日期：2026-09-14。实际模型能力与字段支持以官方端点响应为准。
