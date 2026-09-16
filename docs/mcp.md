# MCP 工具客户端

HALF 提供原生 Java 实现的 **MCP `2025-11-25` stdio 工具客户端**。Agent 应用作为 MCP Host，使用 `McpClient` 连接一个 Server；发现的工具适配为现有 `Tool`，同步 `run` 和流式 `stream` 都沿用同一套 AgentLoop。

```text
AgentLoop → ToolExecutor → MCP 工具适配 → McpClient → stdio → MCP Server
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
            .maxTurns(8)
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

## 工具发现与名字映射

`listTools()` 返回所有分页的 `McpTool` 快照，包含名称、描述、输入 Schema 与完整原始元数据。`metadata()` 保留 `outputSchema`、annotations、execution 等字段，JSON 输入和返回值均复制。

`tools("local")` 再次发现并适配工具。远端 `lookup` 对模型显示为 `local_lookup`，调用时仍发送原名 `lookup`。含点号等非法模型函数名字符或过长的名称，会转换成带稳定哈希后缀的名称；转换后的重名明确报错。给不同 Server 使用不同名称空间，也可以按 `listTools()` 的信息决定是否注册工具。

发现过程按不透明 `nextCursor` 分页，直到字段缺失；拒绝重复游标、重复工具名，最多 100 页和 10,000 个工具。每条 stdio 消息最多 1 MiB。这些是 HALF 的资源上限，不是协议规定的服务端上限。

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

当前只接受 `2025-11-25` 协商结果。官方 `2026-07-28` 协议已改为逐请求能力与版本协商，删除初始化握手；本实现不兼容只支持该新版的 Server，不宣称覆盖最新协议。

本轮未实现：Streamable HTTP、OAuth、MCP Resources/Prompts 接口、Sampling、Roots、Elicitation、Tasks、自动重连、自动更新工具表，以及完整多模态工具结果映射。客户端声明空能力对象；支持 Server 发起的 ping，其他未支持的反向请求返回 `-32601`。

输入 JSON Schema 传给模型并随工具定义保留，不在客户端实现完整 JSON Schema 2020-12 校验；字段与业务校验由 Server 负责。输出 Schema 保存在元数据中，目前不做本地输出 Schema 验证。

## 离线验证与规范

测试使用本地 Java 子进程模拟 MCP Server，验证真实 stdin/stdout JSON-RPC 交互和 Agent 工具回填，不需要网络或模型密钥：

```bash
mvn test
```

官方参考：[生命周期](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)、[stdio 传输](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[工具](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、[进度](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/progress)、[取消](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation)、[2026-07-28 版本变化](https://modelcontextprotocol.io/specification/2026-07-28/changelog)。
