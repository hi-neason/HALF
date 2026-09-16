# HALF

HALF: A lightweight Java framework for AI agent loops.

HALF 是一个计划使用 Java 开发的轻量级 harness agent 框架，负责组织模型推理、工具调用、上下文和运行生命周期，让应用能够构建可控、可测试的 AI Agent。

本项目主要用于学习 Agent 生态的技术原理，通过亲手实现各项机制，理解它们如何工作及如何协作。设计优先考虑代码清晰、执行过程可追踪和实验可验证。

## 技术原则

- AI 相关机制使用原生 Java 自行实现，包括模型协议适配、Agent 循环、工具调用、上下文管理，以及后续的记忆、检索增强和协作机制。
- 不引入封装上述机制的 AI 框架、Agent 框架或模型供应商 SDK；模型服务通过自行编写的 Java 适配器调用 HTTP API。
- 允许按需使用 Jackson、HTTP 客户端等通用基础库，优先使用 JDK 标准库，避免重型依赖。
- 通用库负责 JSON 编解码、网络传输等基础能力；AI 相关的协议映射、状态转换和执行策略保留在项目代码中。

这里的自行实现指 Agent 应用层机制，模型推理可以调用外部服务，无需自行训练或实现大模型。

## 当前状态

第一步是学习 LLM 的 HTTP 接口协议，已提供 Java 模型抽象和 OpenAI Chat Completions、Responses 和 Anthropic Messages 三种文本与工具调用适配器，支持完整响应与 SSE 流式调用。使用 Java 21+、Maven、JDK `HttpClient` 和 Jackson；JUnit 仅用于测试。

当前支持文本/图片/文件输入、拒绝和推理内容、结构化输出配置、工具声明与结果回填、参数分片聚合，以及 `Flow.Publisher<ModelEvent>` 事件流。上层可控制需求量与取消；原文本回调接口继续可用。工具模块已支持显式注册、参数解析、顺序执行、调用上下文与进度、错误分类、宿主结果详情及输出裁剪；已提供共用循环规则的同步／流式 AgentLoop、`Flow.Publisher<AgentEvent>` 和 `Agent.builder()` 门面。“OpenAI 兼容”服务需符合当前适配器支持的字段，不能视为所有厂商都已验证。

MCP 已提供原生 Java stdio、HTTP+SSE 和 Streamable HTTP 工具客户端，可发现 Server 工具并接入同步／流式 Agent；接入方式和版本边界见 [MCP 工具客户端](docs/mcp.md)。

另外提供官方 JSON/SSE 客户端及 Responses、Chat Completions 的资源查询、删除等配套端点，完整覆盖清单和限制见 [官方协议覆盖](docs/official-api.md)。

## 构建与运行

```bash
mvn test
mvn package
```

测试使用本地 HTTP 服务，不需要模型密钥。首次构建可能需要下载 Maven 依赖。

离线体验工具调用（模拟模型提出 `add(2, 3)`，由 Java 执行）：

```bash
mvn compile exec:java -Dexec.mainClass=io.github.hi.neason.half.examples.ToolExample
```

输出为 `{"status":"SUCCESS","output":"5"}`。将入口改为 `io.github.hi.neason.half.examples.ToolContextExample` 可体验进度和宿主详情分离。工具接口及回填用法见 [工具注册与执行](docs/tools.md)。

手动调用真实服务前，配置以下环境变量（密钥从本机环境安全注入）：

- `HALF_MODEL_API`：`chat-completions`（默认）、`responses` 或 `anthropic-messages`。
- `HALF_MODEL_ENDPOINT`：完整接口地址，例如 `https://api.openai.com/v1/chat/completions`；Responses 使用 `https://api.openai.com/v1/responses`；Anthropic 使用 `https://api.anthropic.com/v1/messages`。
- `HALF_MODEL`：该服务实际支持的模型 ID。
- `HALF_API_KEY`：该服务的 API 密钥。

```bash
mvn compile exec:java -Dexec.args="用一句话解释 LLM 的消息历史"

# 边接收边输出文本，结束后打印结束原因与用量
mvn compile exec:java -Dexec.args="--stream 用一句话解释 SSE"

# 订阅结构化事件，每处理一条再 request(1)
mvn compile exec:java -Dexec.args="--events 用一句话解释背压"

# 三组独立真实请求：仅明确提示词、仅 Schema、明确提示词 + Schema
mvn compile exec:java -Dexec.args="--structured-output"
```

该示例会实际调用配置的模型服务。仅用本地测试时无需设置上述变量。

结构化输出对照用例严格校验整个正文，不剥离代码围栏；区分 JSON 格式、固定 Schema、业务值和结束原因。任何组失败都会以失败退出；提示词组通过不代表服务端强制执行了 Schema。该校验仅针对示例的 `{"ok": true}`，不是通用 JSON Schema 校验器。

请求失败分别显示 `HTTP_ERROR:状态码`、`PROTOCOL_ERROR` 或 `TRANSPORT_ERROR:异常类型`，不打印异常正文；请求中的运行时异常与本地验证错误会直接抛出并停止后续组，避免掩盖代码问题。

## 构建 Agent

`model` 为宿主已经创建的任一 `ChatModel` 实现：

```java
import io.github.hi.neason.half.agent.state.TurnOptions;

var agent = Agent.builder()
        .model(model)
        .systemPrompt("使用工具完成计算，再给出答案。")
        .tool(new AddTool())
        .build();
var result = agent.run("2 加 3 等于多少？", TurnOptions.limited(4));
if(result.

completed())System.out.

println(result.text());
        else System.out.

println(result.stopReason());
```

每次 `run` 或流式订阅代表一个独立 turn，可包含多次模型请求。Agent 自动记录模型消息、执行工具并回填结果，再发起下一次请求。`TurnOptions` 配置本次 turn 的请求预算，省略时默认 8 次；显式 `unlimited()` 可用于单次长链任务。长期会话通过传入历史延续，每个 turn 独立计数。模型和工具的关闭由宿主负责。可通过 `agent.stream(input)` 订阅模型增量、工具进度和最终结果，详细约定见 [Agent 门面与循环](docs/agent.md)。

离线体验完整闭环（假模型与真实 Java 工具）：

```bash
mvn compile exec:java -Dexec.mainClass=io.github.hi.neason.half.examples.AgentExample
```

## 框架定位

核心目标是打通“输入 → 模型响应 → 工具调用 → 结果回填 → 再次推理 → 结束”的执行循环，并提供明确的停止条件和运行结果。

初期聚焦单 Agent、单会话的最小闭环。模型供应商接入、工具执行、Agent 循环和 MCP 工具接入已实现；持久化、多 Agent 协作和用户界面继续按需求扩展。

## 开发顺序

1. 自行实现模型接口与协议适配器（已实现）。
2. 扩展多协议及流式响应，理解统一模型抽象（已实现当前三种协议）。
3. 实现工具注册、参数校验与执行，连接调用和结果（已实现）。
4. 构建同步／流式 Agent 循环与门面，提供轮次上限、明确停止原因、事件订阅及取消（已实现）。
5. 实现 MCP 工具客户端（已实现 stdio、传统 HTTP+SSE 与 Streamable HTTP，版本范围见 MCP 文档）。
6. 根据学习需求扩展上下文管理、会话持久化和多 Agent 协作。

## 文档

- [开发约定](AGENTS.md)
- [架构草案](docs/architecture.md)
- [第一步：LLM 接口协议与模型抽象](docs/llm-api.md)
- [流式接口：SSE 分帧与文本增量](docs/streaming.md)

- [结构化事件、订阅需求量与工具内容块](docs/model-events.md)

- [Responses 协议与事件映射](docs/responses.md)

- [官方协议覆盖、资源端点与请求选项](docs/official-api.md)

- [Anthropic Messages 协议与流式映射](docs/anthropic-messages.md)

- [工具注册、执行与结果回填](docs/tools.md)

- [Agent 门面与最小执行循环](docs/agent.md)

- [MCP 工具客户端](docs/mcp.md)
