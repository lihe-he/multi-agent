# 模型请求追踪协议

适用版本：0.2.0-alpha.7 起，alpha.8 增加动态团队来源。`ModelRequest` 表示一次已承诺根预算的 API 请求或 CLI 会话，归属一个实际 AgentRun。请求收到结果、Agent 阶段完成、独立审查意见和根任务人工验收分别记录。

## 查询与交付

`GET /api/tasks/{taskId}/model-requests?runId=&before=0&limit=30&state=&q=` 返回 `{requests: ModelRequest[], cursor: number, hasMore: boolean}`。

默认每页 30 项，最多 100 项，按持久行号倒序；读取更早记录时传上一页 `cursor`。`runId` 可缩小到此任务内的一次运行。`state` 使用下面的精确状态；`q` 在已保存的脱敏 JSON 中做 Unicode 大小写折叠后的连续字面匹配，最多 200 个 UTF-16 字符，`%`、`_` 不作为通配符。负游标、无效状态或过长查询返回 400；任务不存在或运行不属于此任务返回 404。

接口受核心启动令牌鉴权，项目 MCP 只读令牌不能访问。任务详情提供独立“模型请求”页，每次 AgentRun 也可展开所属请求。三秒轮询保留当前历史游标，返回最新由用户主动触发。交付 ZIP 包含任务范围的 `model-requests.json`。

## 字段

| 字段 | 含义 |
| --- | --- |
| `id`, `schemaVersion` | 核心生成的不可复用身份、实体版本 1 |
| `taskId`, `rootTaskId`, `projectId`, `sessionId`, `agentRunId`, `stage` | 从实际运行派生的归属；客户端不能指定或改写 |
| `teamId`, `subtaskId`, `parentRunId`, `definitionId`, `definitionVersion`, `depth` | alpha.8 动态运行存在时从实际 AgentRun 派生的团队、父子与固定定义来源；没有该关系的旧请求不补造 |
| `sequence`, `round`, `attempt` | 运行内已承诺调用的序号、运行器协议轮次、请求尝试号；当前不自动重试，attempt 固定为 1 |
| `routeId`, `routeVersion`, `engine`, `model`, `unit` | 运行的固定路由和调用单位；unit 为 API_REQUEST 或 CLI_SESSION，不复制密钥和整个路由配置 |
| `state`, `createdAt`, `endedAt`, `elapsedMs` | 派发状态、UTC 开始/结束时间及本地耗时；请求结束前没有结束字段 |
| `inputBytes`, `inputDigest`, `messageCount`, `toolCount` | API 序列化请求体或 CLI 提示词的 UTF-8 字节数和 SHA-256，API 消息及工具定义数量；不包含 Authorization 头 |
| `inputPreview`, `inputTruncated` | 在核心脱敏后最多保存 6000 个 UTF-16 字符的输入片段，截断标记按脱敏后的实际长度决定 |
| `httpStatus`, `providerRequestId`, `responseId`, `responseModel`, `finishReason` | 已收到时保存的 HTTP 状态、x-request-id、响应身份/模型及首个 choice 的结束原因；未提供时不推测 |
| `outputPreview`, `outputTruncated` | 首个响应 message 或 CLI 最终输出的脱敏片段，使用与输入相同的长度规则；不是完整响应体 |
| `usage` | 已观测输入、输出、缓存读取、推理 Token 和费用；具体未知口径见下文 |
| `errorType`, `reason`, `remoteOutcome` | 脱敏失败类型与原因；API 完整回执为 RESPONSE_RECEIVED，缺少远端确认时为 UNKNOWN |

## 生命周期与事务

构造可发送请求后，核心在同一事务写入根预算、AgentRun 调用数、请求身份及事件，然后才允许派发。预算拒绝不创建请求；存储失败全部回滚，并封闭新增派发。已经承诺但未成功发送的额度不退款，调用数字不能当作供应商计费凭证。

| 状态 | 可证明的事实 |
| --- | --- |
| `DISPATCHING` | 预算与身份已保存，等待派发或回执；不能据此证明供应商已经执行 |
| `SUCCEEDED` | API 收到并通过基本结构校验的模型 message，或 CLI 会话成功退出；后续工具失败不覆盖这一请求事实 |
| `FAILED` | 收到 HTTP 错误/无效协议响应，或 CLI 返回失败；不自动重试 |
| `CANCELLED` | 本地 CLI 执行报告取消；内部远端执行与费用仍未知 |
| `UNKNOWN` | 传输中断、本地停止等待、重启或运行结束后回执缺失；不推测成功，也不自动重放 |

结束记录不可覆盖。AgentRun 结束时，同一事务把仍处于 DISPATCHING 的请求记为 UNKNOWN/MISSING_OBSERVATION；核心重启沿用此规则。已有成功或失败记录保持原状。人工核对在根任务及操作账本中记录，继续执行创建新的 AgentRun 和请求身份。

API 追踪围住每轮传输和协议回执，工具执行另走原权限、预算与证据路径。取消在途 HTTP 等待会取消本地 future，但请求仍记 UNKNOWN/LOCAL_CANCEL_WAIT，不能证明供应商停止执行或取消费用。当前没有请求重试或单独的请求恢复接口。

## 用量与内容边界

API 将 `prompt_tokens`、`completion_tokens`、`prompt_tokens_details.cached_tokens` 和 `completion_tokens_details.reasoning_tokens` 分别映射到 inputTokens、outputTokens、cachedInputTokens、reasoningTokens。缺失、负数或非整数值为字符串 `UNKNOWN`；明确返回的 0 保留为数字 0。费用始终 UNKNOWN，尚未接入供应商账单或价格换算。

AgentRun 的运行器汇总只有在每次已承诺请求都提供相应 Token 字段时才显示总值；否则总值为 UNKNOWN，`observedInputTokens` 等字段只保存已知部分的累计。已知部分为 0 不能证明总用量为 0。缓存读取和推理分别保留，不能再次加到供应商的总输入/总输出中。

CLI 只观测一次会话，内部模型请求数、Token 和费用均未知，不使用一条会话记录推测其内部调用。CLI 的本地耗时包含整个会话；API 请求耗时包含本地等待和解析，不包含后续工具执行。

已配置路由凭据在截断前经现有脱敏器移除，身份和异常文本也走同一规则；原始 HTTP 错误体不进入请求记录。片段以纯文本呈现。当前本机工作台保存这些片段，用于任务内输入输出核对；脱敏只覆盖已知凭据，不代表删除了项目内容或全部个人信息。记录没有发送到遥测服务，内容采集开关、保留治理与完整敏感内容识别仍待实现。

复用 SQLite `entities` 的 modelRequest 类型及局部索引，数据库 schema 仍为 3，API 仍为 1。旧版本不存在的内部请求不推测补造。alpha.8 的[动态团队协议](./team-contract.md)使用该次成功请求的持久身份关联委派、消息、采纳和 MCP 原来源，全部后代仍使用原根预算。本协议完成 F16 的逐轮定位基础；Token 趋势、供应商计费核对和远程 Worker 继续实施。
