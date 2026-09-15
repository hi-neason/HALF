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

## 火山方舟 Coding：Messages

验证日期：2026-09-15。

- Base URL：`https://ark.cn-beijing.volces.com/api/coding`
- 实际端点：`https://ark.cn-beijing.volces.com/api/coding/v1/messages`
- 请求模型：`ark-code-latest`；通过 `AnthropicMessagesModel` 调用，使用 `x-api-key` 和 `anthropic-version: 2023-06-01`。
- 密钥通过临时进程标准输入传入；未写入工程。输出预算为 2048 token。

| 验证项 | 结果 |
| --- | --- |
| 普通文本响应 | 通过，正文为 `HALF联调成功`，结束原因为 `end_turn` |
| SSE 事件订阅，每处理一条再 `request(1)` | 通过，6 个 `TextDelta`，拼接与最终正文一致 |
| Thinking 与签名聚合 | 通过，17 个 `ThinkingDelta` 和 1 个 `ThinkingCompleted` |
| 流式工具调用与 JSON 参数聚合 | 通过，`get_test_weather` 参数为 `{"city":"北京"}`，结束原因为 `tool_use` |
| 工具结果及推理历史回填 | 通过，下一轮返回 `北京的天气为晴（测试数据）。`，结束原因为 `end_turn` |
| JSON Schema 输出约束 | 未通过：返回了说明文字和 Markdown 代码块，整个正文不是合法 JSON |

当次首个正文片段约 2.8 秒到达，只代表单次观测。工具结果使用合成数据，没有实际查询天气。

结构化输出请求使用官方 Messages 字段 `output_config.format`，Schema 要求对象包含布尔属性 `ok`。虽然 HTTP 请求成功，正文却带有说明和代码围栏，严格 JSON 解析失败。因此不能将此服务的 Messages 结构化输出记为已验证支持，也不通过剥离 Markdown 掩盖格式不符合的问题。此前 Chat Completions / Responses 的结构化输出通过，不代表 Messages 路由也支持相同能力。

本次没有验证图片输入、显式 thinking 预算或 adaptive 配置、隐藏推理块及其他高级端点；也没有调用官方 Anthropic 服务。

### 结构化输出对照复测

2026-09-15 使用同一 Messages 端点、模型和 2048 token 上限，各执行一次独立请求。可通过 `ChatExample --structured-output` 重复运行；不自动重试。

| 组别 | 提示词 | Schema | 结果 |
| --- | --- | --- | --- |
| `prompt_only` | 明确要求只有一个 JSON 对象、只有布尔属性 ok=true、无说明和代码围栏 | 无 | PASS |
| `schema_only` | 返回 ok 为 true 的 JSON 对象 | 有 | INVALID_JSON |
| `prompt_and_schema` | 与 prompt_only 完全相同 | 与 schema_only 完全相同 | PASS |

三组均正常结束（`end_turn`）。PASS 必须同时满足：正常结束、非空正文、整个正文可严格解析为单个 JSON 对象、只有 `ok` 字段、类型为 boolean 且值为 true。不转换字符串/数字类型，不提取代码块，拒绝重复字段与尾随 JSON。失败区分请求错误、非正常结束、意外内容、空正文、JSON 格式错误、Schema 不匹配和业务值不匹配。这里的字段检查只针对固定测试 Schema，不是通用校验器。

结论：本次加强提示词后可以得到合规输出，但无 Schema 的同提示词对照组也通过，仅 Schema 组仍失败。因此这是提示词引导有效的证据，不能当作厂商强制 Schema 约束生效的证据，也不以一次通过宣称稳定支持。原始失败记录保留。
