# 真实服务联调记录

验证日期：2026-09-14。以下结果仅代表当次服务配置与请求，不表示所有模型或全部官方端点都兼容。

## 火山方舟 Coding

- Base URL：`https://ark.cn-beijing.volces.com/api/coding/v3`
- 请求模型：`ark-code-latest`
- 使用 HALF 的原生 Java HTTP/SSE 实现；认证信息通过进程标准输入临时传入，没有写入工程或本记录。
- 请求只包含合成测试文本；工具结果使用本地构造的测试数据，没有查询真实天气。

| 验证项 | Chat Completions | Responses |
| --- | --- | --- |
| 非流式文本响应 | 通过 | 通过 |
| SSE 文本增量与最终正文一致 | 通过，6 个文本片段 | 通过，6 个 `TextDelta` |
| 流式工具调用与 JSON 参数聚合 | 通过 | 通过 |
| 工具结果回填后再次请求 | 通过 | 通过，包含推理输出项回传 |
| 严格 JSON Schema 输出 | 通过，返回 `{"ok": true}` | 通过，返回 `{"ok": true}` |
| 结构化推理事件 | 未验证映射，厂商扩展字段不作正文处理 | 13 个 `ReasoningDelta`、1 个 `ReasoningCompleted` |

Responses 事件订阅每次处理后执行 `request(1)`，最终还收到 `Usage` 和 `Completed`。当次首个正文片段约 2.4 秒到达；Chat Completions 文本回调约 3.1 秒。这些是单次观测，不是性能基准。

## 联调发现及修复

Responses 服务在 `response.reasoning_summary_part.added` 中可能省略空 `text`，在 `response.output_item.added` 的 `function_call` 中可能省略尚未生成的 `arguments`。适配器允许这两处开始事件省略字段；显式错误类型、结束事件缺失字段及增量与结束内容不一致仍会报错。两种情况均新增本地回归测试，并通过真实请求复测。

最初一次 Chat Completions 请求的 512 个输出 token 全部用于推理，最终 `finish_reason=length` 且无正文。该请求不算文本流验证成功；改用简短提示与 2048 token 上限后收到非空正文，且拼接结果与最终响应一致。调用方应同时检查结束原因与内容，不能仅凭 HTTP 200 判断任务完成。

本次未实测图片、文件、拒绝输出，以及查询、删除、取消、压缩等资源端点，也未验证官方 OpenAI 服务。自动化测试继续使用本地模拟服务，不依赖此厂商、外网或密钥。
