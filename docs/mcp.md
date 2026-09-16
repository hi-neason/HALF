# MCP 工具客户端

HALF 提供原生 Java 实现的 MCP 工具客户端，支持 **stdio、传统 HTTP+SSE 和 Streamable HTTP**。Agent 应用作为 MCP Host，使用 `McpClient` 连接一个 Server；发现的工具适配为现有 `Tool`，同步 `run` 和流式 `stream` 都沿用同一套 AgentLoop。

```text
AgentLoop → ToolExecutor → MCP 工具适配 → McpClient → 传输 → MCP Server
     ↑                        tools/call 的结果与进度             ↓
     └────────── 工具结果回填，继续请求模型 ────────────────────────┘
```

这里的 MCP Server 负责实际工具能力。模型只接收工具说明和参数 Schema，由 HALF 将模型提出的调用发送给 Server。

## 构建与接入

以下 `model` 是已经创建的 `ChatModel`，`serverCommand` 是宿主配置的 Server 命令和参数，例如 `List.of("java", "-jar", "/path/to/server.jar")`：

```java
try (var mcp = McpClient.stdio(serverCommand)
        .timeout(Duration.ofSeconds(30))
        .connect()) {
    var agent = Agent.builder()
            .model(model)
            .tools(mcp.tools("local"))
            .build();

    var result = agent.run("使用可用工具完成任务");
    System.out.println(result.stopReason());
    System.out.println(result.text());
}
```

需要导入 `io.github.hi.neason.half.mcp.McpClient`、`io.github.hi.neason.half.agent.Agent`、`java.time.Duration` 和 `java.util.List`。

- `stdio(List<String>)` 或 `stdio(String...)` 直接通过 `ProcessBuilder` 启动 argv，不经过 shell。
- `directory(Path)` 设置子进程工作目录；省略时使用当前目录。
- `environment(Map<String, String>)` 在继承的进程环境上覆盖变量，不把值写入协议或错误正文。
- `timeout(Duration)` 是每个请求的总等待预算，默认 30 秒，最大一天；进度通知不延长预算。
- `connect()` 启动进程、完成 `initialize` 与版本/能力检查，再发送 `notifications/initialized`。失败时清理进程。
- `close()` 关闭输入、有界等待退出，必要时终止子进程；不发送不存在的 `shutdown` RPC。

MCP Client 的生命周期由宿主管理，Agent 不关闭它。使用流式 Agent 时，需要等待订阅结束后再离开 `try`；否则关闭 Client 会使正在执行的 MCP 调用失败。一个 Client 可以被多个 Agent 使用，请求 ID 用于关联并发结果。

## HTTP 传输

两种 HTTP 入口复用 stdio 的握手、请求关联、工具发现与适配；Agent 无需按传输类型分支。使用完整 endpoint URI：

```java
// 单一 HTTP endpoint，可在 POST 中返回 JSON 或 SSE。
try (var mcp = McpClient.streamableHttp(URI.create("https://example.com/mcp"))
        .headers(Map.of("Authorization", "Bearer " + token))
        .timeout(Duration.ofSeconds(30))
        .connect()) {
    var agent = Agent.builder().model(model).tools(mcp.tools("remote")).build();
    var result = agent.run("使用远端工具完成任务");
}

// 传统 HTTP+SSE：GET 建立下行通道，从 endpoint 事件发现 POST 地址。
try (var mcp = McpClient.sse(URI.create("https://example.com/sse")).connect()) {
    System.out.println(mcp.protocolVersion());
    System.out.println(mcp.listTools());
}
```

这里的 `token` 来自宿主的凭据配置；需要导入 `java.net.URI` 与 `java.util.Map`。未配置认证的 Server 可省略 `headers`。自定义头用于宿主认证等用途；Accept、Content-Type、会话与协议版本等受管头不可覆盖，也不会跟随 HTTP 重定向转发凭据。没有 OAuth 自动登录流程。

| 入口 | 传输行为 | 接受的协商版本 |
| --- | --- | --- |
| `stdio(command)` | 子进程 stdin/stdout，换行 JSON-RPC | `2025-11-25` |
| `sse(uri)` | GET SSE 接收 endpoint/message 事件，POST 客户端消息 | `2024-11-05`、`2025-11-25` |
| `streamableHttp(uri)` | 单 endpoint POST，可返回 JSON 或 SSE，可选 GET SSE | `2025-11-25` |

客户端初始化均提出 `2025-11-25`；传统 SSE 允许回协商到实际支持的旧版。`protocolVersion()` 返回协商结果，不把传输名称当作协议版本。两种 HTTP 入口显式选择，不自动探测或降级。

传统 SSE 的 endpoint URI 按 SSE 地址解析，支持相对路径和查询参数。HALF 要求发现的地址同源，不将认证头发给其他源；这是本项目策略。POST 成功后仍等待 SSE 通道上对应的 JSON-RPC 响应；下行断开会使该连接和待处理请求失败。

Streamable HTTP 同时声明接受 `application/json` 和 `text/event-stream`，支持同一响应流上的进度及反向请求。`ping` 的回复通过独立 POST 发出，不等待原工具请求结束。通知及反向请求响应的 HTTP 接受结果必须为 `202` 空正文。

初始化响应可以创建 `Mcp-Session-Id`，也可以没有会话。初始化之后的 POST、GET、DELETE 携带已创建的会话及协商后的 `MCP-Protocol-Version`。初始化完成后尝试打开可选 GET 通知流；GET `405` 或该可选流正常 EOF 不影响后续 POST。正常关闭时尽力 DELETE 会话，DELETE `405` 表示 Server 不支持该能力。

单个请求超时会释放对应 HTTP 读取资源并尽力发送 MCP 取消通知，不中断其他正常请求。会话 `404` 或会话标识异常会使连接失效；宿主应新建 Client 重新初始化，不自动重放旧工具调用。HTTP 状态错误保留状态码，异常不含 URL、认证头或服务端正文。

当前不实现 `Last-Event-ID` 恢复、`retry` 重连或自动重新建会话。POST SSE 在对应响应到达前 EOF 时报告请求失败；对于依赖断开后 GET 恢复才能返回结果的 Server，当前尚不兼容。可选 GET 通知流结束后也不自动重连。

## 工具发现与名字映射

`listTools()` 返回所有分页的 `McpTool` 快照，包含名称、描述、输入 Schema 与完整原始元数据。`metadata()` 保留 `outputSchema`、annotations、execution 等字段，JSON 输入和返回值均复制。

`tools("local")` 再次发现并适配工具。远端 `lookup` 对模型显示为 `local_lookup`，调用时仍发送原名 `lookup`。含点号等非法模型函数名字符或过长的名称，会转换成带稳定哈希后缀的名称；转换后的重名明确报错。给不同 Server 使用不同名称空间，也可以按 `listTools()` 的信息决定是否注册工具。

发现过程按不透明 `nextCursor` 分页，直到字段缺失；拒绝重复游标、重复工具名，最多 100 页和 10,000 个工具。每条 JSON 消息最多 1 MiB，HTTP SSE 每帧还受 1 Mi 字符限制。这些是 HALF 的资源上限，不是协议规定的服务端上限。

工具表是快照。`notifications/tools/list_changed` 不会自动改变已构建的 Agent；宿主可以重新调用 `tools(namespace)` 并构建新的 Agent。`execution.taskSupport: "required"` 的工具会被排除出适配列表，因为首版不执行 Tasks。

## 直接调用

```java
var tools = mcp.listTools();
var arguments = new ObjectMapper().createObjectNode().put("query", "example");
var result = mcp.callTool("lookup", arguments);
System.out.println(result.isError());
System.out.println(result.text());
var originalResult = result.value();
```

`callTool` 使用远端原始名称；不做任务扩展调用，也不自动重试。可用 `ping()` 检查连接。

## 错误、内容与进度

MCP 有两种不同的错误：

1. JSON-RPC `error`、进程退出、协议损坏或请求超时：直接调用抛出 `McpException`；通过 Agent 执行时，由 `ToolExecutor` 转为普通 `EXECUTION_FAILED` 结果。异常不保留服务端的 message、data、stderr 或原始响应。
2. `tools/call` 返回 `isError: true`：这是 Server 主动返回的工具执行结果。适配为 `ToolOutput.isError()`，执行器产生 `EXECUTION_FAILED`，保留 Server 提供的文本和结构化详情，让模型有机会调整下一次调用。

`ToolOutput` 原有的一参、两参构造保持可用，默认 `isError=false`；工具可通过三参构造明确返回失败结果。模型可见文本仍受 `maxToolOutputCharacters` 限制。

`McpCallResult.value()` 保存原始结果；工具适配后放在 `ToolResult.details()` 中，供宿主读取。当前模型侧工具通道是文本：

- `text` 内容和内嵌文本资源转为文本。
- `structuredContent` JSON 对象附加为 JSON 文本，同时保留原始结构。
- 资源链接转为 URI 文本，不自动下载。
- 图片、音频等非文本内容保留在详情中，模型侧仅显示明确占位，不声称模型已经读取媒体。

通过 `ToolExecutor` 调用时，每个请求附带唯一的 `_meta.progressToken`。Server 的 `notifications/progress` 转成 `ToolContext.reportProgress`，因此同步回调与 `AgentEvent.ToolProgressed` 都可以接收。读取线程只路由消息，回调在调用线程执行；高频进度采用单槽合并，保留最新更新，进度不是完整事件日志。没有 message 时使用进度数值作为文本。

线程中断或 `ToolContext` 取消会停止等待并尽力发送 `notifications/cancelled`，迟到结果不再使用；取消初始化请求时直接关闭连接，不发送初始化取消通知。取消不能保证 Server 已停止工具副作用，不应自动重试。宿主进度回调应及时返回；阻塞的用户回调不能靠请求超时强制终止。

## 版本与首版边界

支持的协商版本见上表。官方 `2026-07-28` 协议已改为逐请求能力与版本协商，删除初始化握手；本实现不兼容只支持该新版的 Server，不宣称覆盖最新协议。

本轮未实现：OAuth、MCP Resources/Prompts 接口、Sampling、Roots、Elicitation、Tasks、自动重连、自动更新工具表，以及完整多模态工具结果映射。客户端声明空能力对象；支持 Server 发起的 ping，其他未支持的反向请求返回 `-32601`。

输入 JSON Schema 传给模型并随工具定义保留，不在客户端实现完整 JSON Schema 2020-12 校验；字段与业务校验由 Server 负责。输出 Schema 保存在元数据中，目前不做本地输出 Schema 验证。

## 离线验证与规范

测试使用本地 Java 子进程和 JDK HTTP Server 模拟 MCP Server，验证真实 stdio、HTTP/SSE 交互及 Agent 工具回填，不需要网络或模型密钥：

```bash
mvn test
```

官方参考：[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[stdio 与 Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[传统 HTTP+SSE](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)、[工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[进度](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress)、[取消](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)、[2026-07-28 版本变化](https://modelcontextprotocol.io/specification/2026-07-28/changelog)。
