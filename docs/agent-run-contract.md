# Agent 运行协议

适用版本：0.2.0-alpha.6 起，alpha.7 新增关联的[模型请求追踪协议](./model-request-contract.md)。AgentRun 表示一次实际 Agent 阶段执行，独立于计划阶段、模型请求和根任务最终验收。当前固定阶段调度使用此实体；动态 SubAgent、Team、父子运行与远程节点尚未接入。

## 查询

- `GET /api/tasks/{taskId}/agent-runs?before=0&limit=30&state=`
- `GET /api/projects/{projectId}/sessions/{sessionId}/agent-runs?before=0&limit=30&state=`

两者返回 `{runs: AgentRun[], cursor: number, hasMore: boolean}`。按持久行号倒序，每页最多 100 项；`before` 使用上一页 `cursor`。状态过滤必须传协议中的精确状态。无效状态或负游标返回 400；任务不存在返回 404，项目会话归属不符沿用会话协议的 404。端点受现有启动令牌鉴权，不开放给项目 MCP 只读令牌。

## 字段

| 字段 | 含义 |
| --- | --- |
| `id`, `schemaVersion` | 不可复用的运行身份、实体版本 1 |
| `taskId`, `rootTaskId`, `projectId`, `sessionId` | 服务端从真实任务派生的归属；当前 rootTaskId=taskId |
| `stage`, `kind`, `title`, `role`, `profileVersions` | 调度阶段、角色及创建任务时固定的职责模板版本 |
| `attempt` | 同一任务/阶段的第几次运行；重跑不覆盖旧记录 |
| `routeId`, `routeVersion`, `routeName`, `engine`, `model`, `provider` | 允许列出的路由属性；不复制地址、密钥或整个路由快照 |
| `workspace`, `inputVersion`, `resultVersion` | 实际运行目录和内容标识；审查使用独立审查副本 |
| `readOnly`, `scope`, `reserved` | 阶段限制、任务修改范围、是否使用根验证预留；不是新的授权来源 |
| `calls`, `callUnit`, `usage` | 与根预算原子累计的调用承诺；API_REQUEST 或 CLI_SESSION。CLI 内部请求/费用仍未知；usage 仅保留运行器已有可观测数据 |
| `createdAt`, `startedAt`, `endedAt`, `elapsedMs`, `queueMs` | UTC 时间；总耗时包含排队和工具，queueMs 为首次取得执行位置前的时间，尚未结束不填结束耗时 |
| `operationId`, `evidenceId`, `reason` | 原操作意图与证据身份、脱敏结束原因；证据反向关联 agentRunId |

## 生命周期与恢复

先落库 `WAITING_RESOURCE`，取得容量后与操作意图同事务进入 `RUNNING`。MCP 执行时释放模型容量并进入 `WAITING_TOOL`，下次模型调用先等待资源。根取消传播到当前执行，不独立提供运行恢复、预算调整或权限放宽接口。

结束状态为 `SUCCEEDED`、`FAILED`、`UNKNOWN`、`CANCELLED`、`PAUSED`、`WAITING_INPUT`、`BUDGET_EXHAUSTED`。SUCCEEDED 表示本次执行返回且完整性校验结束；审查意见可以是请求修改，根任务仍须通过全部门禁与人工验收。结束记录不能覆盖或重新派发。

调用计数先与根预算同事务保存，再允许实际请求；未发出但已承诺的调用不退款。数据库失败时两项同时回滚并封闭新增派发。调用数字不是供应商计费凭证。

核心重启把所有未结束运行置为 UNKNOWN，保留操作及原证据，不自动重放。人工核对记录在根任务与操作账本，旧运行仍保留 UNKNOWN 事实；后续恢复创建新的运行记录。旧版本没有运行实体的数据不推测回填。

## 存储与范围

复用 SQLite `entities` 并新增局部索引，schema 3 和 API 1 不变。写证据与运行证据关联在同一事务，结束状态在执行后的内容核验完成时另行保存；两者之间崩溃会保守留下 UNKNOWN，已有证据继续保留。交付包新增 `agent-runs.json`。

alpha.7 在每次模型请求前把请求身份、运行 calls 和根预算同事务保存，AgentRun 结束时把缺少回执的请求记 UNKNOWN，已有请求终态保持原样；交付包另含 `model-requests.json`。Token 趋势、动态递归和 Team 消息仍待实现。不能用运行列表的绿色状态代替这些功能的验收。
