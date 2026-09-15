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

第一步是学习 LLM 的 HTTP 接口协议，已提供 Java 模型抽象和 OpenAI Chat Completions、Responses 两种文本与工具调用适配器，支持完整响应与 SSE 流式调用。使用 Java 21+、Maven、JDK `HttpClient` 和 Jackson；JUnit 仅用于测试。

当前支持文本/图片/文件输入、拒绝和推理内容、结构化输出配置、工具声明与结果回填、参数分片聚合，以及 `Flow.Publisher<ModelEvent>` 事件流。上层可控制需求量与取消；原文本回调接口继续可用。工具执行器及 Agent 循环尚未实现。“OpenAI 兼容”服务需符合当前适配器支持的字段，不能视为所有厂商都已验证。

另外提供官方 JSON/SSE 客户端及 Responses、Chat Completions 的资源查询、删除等配套端点，完整覆盖清单和限制见 [官方协议覆盖](docs/official-api.md)。

## 构建与运行

```bash
mvn test
mvn package
```

测试使用本地 HTTP 服务，不需要模型密钥。首次构建可能需要下载 Maven 依赖。

手动调用真实服务前，配置以下环境变量（密钥从本机环境安全注入）：

- `HALF_MODEL_API`：`chat-completions`（默认）或 `responses`。
- `HALF_MODEL_ENDPOINT`：完整接口地址，例如 `https://api.openai.com/v1/chat/completions`；Responses 使用 `https://api.openai.com/v1/responses`。
- `HALF_MODEL`：该服务实际支持的模型 ID。
- `HALF_API_KEY`：该服务的 API 密钥。

```bash
mvn compile exec:java -Dexec.args="用一句话解释 LLM 的消息历史"

# 边接收边输出文本，结束后打印结束原因与用量
mvn compile exec:java -Dexec.args="--stream 用一句话解释 SSE"

# 订阅结构化事件，每处理一条再 request(1)
mvn compile exec:java -Dexec.args="--events 用一句话解释背压"
```

该示例会实际调用配置的模型服务。仅用本地测试时无需设置上述变量。

## 框架定位

核心目标是打通“输入 → 模型响应 → 工具调用 → 结果回填 → 再次推理 → 结束”的执行循环，并提供明确的停止条件和运行结果。

初期聚焦单 Agent、单会话的最小闭环。模型供应商接入、持久化、MCP、多 Agent 协作和用户界面在核心接口稳定后逐步评估。

## 开发顺序

1. 学习 HTTP/JSON 协议，自行实现模型接口与首个协议适配器（当前阶段）。
2. 比较其他供应商协议，扩展适配器与流式响应，理解哪些能力可以统一抽象。
3. 在已有工具调用消息与结果回填的基础上，构建可测试的 Agent 循环。
4. 加入工具注册与执行、轮次上限和执行策略。
5. 根据学习需求扩展上下文管理、会话持久化和多 Agent 协作。

## 文档

- [开发约定](AGENTS.md)
- [架构草案](docs/architecture.md)
- [第一步：LLM 接口协议与模型抽象](docs/llm-api.md)
- [流式接口：SSE 分帧与文本增量](docs/streaming.md)

- [结构化事件、订阅需求量与工具内容块](docs/model-events.md)

- [Responses 协议与事件映射](docs/responses.md)

- [官方协议覆盖、资源端点与请求选项](docs/official-api.md)
