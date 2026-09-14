# 流式接口：SSE 分帧与文本增量

## Java 接口

```java
ChatResponse response = model.stream(request, delta -> {
    System.out.print(delta);
    System.out.flush();
});
// stream 返回后才有完整结果，包括结束原因和可选用量。
System.out.println(response.finishReason());
```

`ChatModel.stream` 阻塞至协议正常结束或发生错误。文本增量以非空字符串按顺序回调；角色、空 delta、用量块不会作为文本回调。返回的 `ChatResponse.text()` 是所有已交付增量的拼接。

`chat()` 仍返回一次完整响应。`ChatModel` 保留函数式接口能力，未实现流式方法的模型会抛出 `UnsupportedOperationException`，不会用一次普通调用模拟流式输出。

## 三层数据边界

```text
HTTP 字节片段
  → JDK UTF-8 解码与分行
  → SseParser：空行结束一个事件，多行 data 合并
  → OpenAiStream：解析 JSON delta、累计文本、记录结束原因与用量
  → onTextDelta 回调 / ChatResponse 完整结果
```

一次网络读取可能只有半个汉字，也可能包含多个 SSE 事件。一次 SSE 事件通常包含一个 JSON chunk，而一个文本增量不保证正好是一个 token 或一个词。不要按网络读取次数或 JSON 行数推断文本边界。

JDK `BodySubscribers.fromLineSubscriber` 显式使用 UTF-8 和默认分行规则；HALF 自行处理 SSE 事件语义。支持 LF、CRLF、单独 CR，去掉流开头的一个 BOM，忽略注释和非 data 字段。连续 data 行用换行拼接，只移除冒号后至多一个空格。EOF 不会把尚未以空行结束的事件强行派发。

## 请求和事件示例

流式方法发送 `Accept: text/event-stream`，请求中的 `stream` 改为 `true`，并加入 `stream_options: {"include_usage": true}`。消息、模型名与可选 token 上限的映射与 `chat()` 一致。服务必须支持该流式选项，当前没有参数降级或自动重试。

下面是省略无关字段后的事件序列，每个事件后都有一个空行：

```text
data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}

data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}

data: [DONE]

```

这里回调两次，分别得到 `你` 和 `好`，最终返回文本 `你好`。非流式读取 `message.content`，流式读取 `delta.content`；流中的 role 和 content 都可能不在每个 chunk 中出现。

`finish_reason` 表示这个候选的生成结束，随后还可能有用量块，所以不能在它出现时立即返回。`[DONE]` 不是 JSON，是协议的终止标记。只有先收到结束原因，再收到完整的 `[DONE]` 事件，HALF 才返回成功。

当前仅支持候选 `index: 0`。普通 chunk 的 usage 应缺省或为 null；用量从结束原因之后、`choices: []` 的单独尾块读取，最多一次。没有尾块时用量为空，不估算。结束后的重复候选、提前出现的用量、重复用量或无结束原因的 `[DONE]` 都明确报错。

## 错误、超时与取消

- HTTP 非 2xx 返回 `ModelHttpException`，与普通调用一致；成功响应必须声明 `text/event-stream`。
- 非法 JSON、错误字段、工具调用、拒绝响应等不支持的能力返回 `ModelProtocolException`。角色块、空 delta 与 null content 在流式协议中合法。
- EOF 前没有收到 `[DONE]` 判定为不完整流；即使已输出部分文本或已收到结束原因，也不伪造成功结果。
- 构造模型时的 `timeout` 用于流式调用总时限，包括服务端发完响应头后停止发送正文的情形。超时抛出 `HttpTimeoutException` 并取消 HTTP 交换。
- 中断调用 `stream` 的线程会传播 `InterruptedException` 并取消订阅；应用可以使用线程中断停止本次生成。
- 回调抛出的运行时异常或 Error 会原样传播到调用线程，并取消后续读取。

回调在 HTTP 读取线程上串行执行，应快速返回，不能等待 `stream()` 返回或在其中关闭同一个模型。超时或取消不能强制终止一个已经开始执行的用户回调；回调自行管理其阻塞和副作用。已经显示的增量不会因后续失败而撤回，只有方法成功返回才表示本次响应完整。

JDK Flow 取消是尽力而为，可能还有在途通知。实现显式结束应用层结果，并忽略结束后的通知，不依赖取消后一定收到 `onComplete` 或 `onError`。当前不自动重连，因为重发生成请求可能重复文本或产生额外调用。

## 运行与阅读

配置环境变量后运行示例（变量说明见 [README](../README.md)）：

```bash
mvn compile exec:java -Dexec.args="--stream 用一句话解释 SSE"
```

从 [ChatModel](../src/main/java/io/github/hi/neason/half/model/ChatModel.java) 的接口开始，依次阅读 [OpenAiChatModel](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiChatModel.java) 的 HTTP 调用、[SseParser](../src/main/java/io/github/hi/neason/half/model/openai/SseParser.java) 的分帧和 [OpenAiStream](../src/main/java/io/github/hi/neason/half/model/openai/OpenAiStream.java) 的状态转换。

测试使用本地 HTTP 服务，以握手信号证明首个增量在服务端发送后续事件前就已交付；另覆盖分片、UTF-8、多行 data、结束顺序、断流、超时和中断。运行 `mvn test` 无需模型密钥。

## 官方协议资料

字段与接口核对日期：2026-09-14。

- [OpenAI Chat Completions 请求与 stream_options](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [OpenAI Chat 流式 chunk](https://developers.openai.com/api/reference/resources/chat)
- [WHATWG SSE 分帧规则](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)
- [JDK 21 BodySubscribers](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscribers.html)
- [JDK 21 Flow.Subscription](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Flow.Subscription.html)
