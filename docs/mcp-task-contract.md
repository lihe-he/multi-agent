# MCP 任务固定动作接口

当前 `0.2.0-alpha.1` 状态：固定动作现已接入任务创建、Web 确认表单、API 模型工具回路、任务操作查询、未知结果核对及版本化重新授权。服务端自动回归覆盖模型工具循环、无参数别名、原子预算登记和重复动作去重；真实外部 MCP 服务的副作用及长期运行仍需单独验收。

本轮仅支持 API 运行器在 IMPLEMENT / REPAIR 阶段选择用户预先确认的**固定参数动作**。模型看到无参数工具别名，不能修改参数或服务目标。分析、圆桌、验证和独立审查阶段不开放外部 MCP；服务的 readOnlyHint 不产生权限。不支持通用动态参数、自动重试、原生 CLI MCP 注入或将原仓库 RPC 当作审查副本事实。旧任务和未选择动作的任务默认无 MCP 权限。

## 候选动作

`GET /api/mcp/task-tools?projectId=<id>` → `{tools:[],limitations:[]}`。只读取最近探测结果，不启动进程或业务调用；未探测时先使用现有 `/api/mcp/servers/{id}/probe`。任务创建表单仅允许用户选择可用候选并输入完整固定 JSON 参数；动作可能产生外部副作用的提示和开发阶段范围需在确认前可见。

每项：`{serverId,serverName,identity,configVersion,definitionVersion,name,description,inputSchema,available,disabledReason,mode:'FIXED_ARGUMENTS',allowedStageKinds:['IMPLEMENT','REPAIR']}`。未启用、未授权给当前项目/工具、未成功探测的工具不能选择。工具定义/配置在保存任务前变化会返回 409。

## 创建任务

在现有 `POST /api/tasks` payload 上新增（均可省略）：

```json
{
  "mcpBindings": [{
    "serverId": "server-id",
    "name": "tool-name",
    "configVersion": 1,
    "definitionVersion": "hash-from-candidate",
    "fixedArguments": {"query": "用户确认的完整参数"},
    "timeoutMs": 30000
  }],
  "mcpBudget": {"maxCalls": 8, "maxDurationMs": 240000}
}
```

最多 16 个动作。参数必须是 JSON 对象，大小最多 16 KB；候选 inputSchema 供填写与审阅。核心校验支持的 schema，无法验证的结构拒绝自动动作。timeoutMs 范围 1000–60000；maxCalls 范围 1–64；maxDurationMs 范围 1000–3600000。仅 API 路由允许非空动作。界面必须在任务确认卡中显示服务、动作、完整固定参数、超时和预算，并说明外部动作可产生副作用、仅开发阶段可调用。

Task 增加 `mcpAuthorization:{version,mode:'FIXED_ARGUMENTS',projectId,bindings:[],createdAt}`，bindings 包含上述字段及 `alias、actionKey、identity、allowedStageKinds`。`mcpBudget:{maxCalls,maxDurationMs,usedCalls,committedDurationMs}`：派发前登记一次调用及该动作的完整超时承诺；失败/UNKNOWN 不退还，去重回执不重复扣算。该保守工具预算独立于根模型预算，不能占用或增加模型验证预留；不承诺外部实际费用封顶。

同一任务、同一服务、同一动作及固定参数只自动派发一次。模型再次选择已确认动作返回已保存回执，不重复产生副作用。未知或已人工核对动作不自动重试；要再次执行须用户在任务外明确处理或创建新的任务。

## 状态与操作记录

`GET /api/tasks/{id}/mcp-operations` → `[{id,requestId,taskId,stage,serverId,tool,actionKey,authorizationVersion,state,dispatchState,stopState,argumentKeys,argumentHash,inputSummary,result?,error?,createdAt,completedAt?,canReconcile}]`。任务详情展示固定参数、独立预算及每项动作的回执状态。

- `state`: PREPARED / DISPATCHED / ACKNOWLEDGED / UNKNOWN / RECONCILED。
- `dispatchState`: NOT_SENT / SENT / ACKNOWLEDGED / UNKNOWN。
- `stopState`: CONFIRMED / UNCONFIRMED（本地进程/传输停止状态；不代表外部副作用撤销）。
- ACKNOWLEDGED 的 `result.isError=true` 仍是业务失败，不能显示“工具成功”。
- UNKNOWN 保留完整记录；任务进入 UNKNOWN，禁止继续模型或新工具。更换模型调用编号、恢复任务、重启核心都不能绕过。
- 配置/定义/授权变化、预算不足等派发前阻塞进入 WAITING_INPUT，`task.error` 给出原因，不自动扩大授权。

已有 `/api/tasks/{id}/reconcile` 保留请求体 `{processesStopped:true,sideEffectsChecked:true,note:'具体核对说明'}`，现在同时核对该任务 MCP 未确认操作，保留历史 UNKNOWN 与用户说明。只有确认本地进程已停止才能处理；HTTP 停止等待不表示远端效果已撤销。核对后任务 PAUSED，仍需手动恢复，不自动重放旧动作。

## 重新授权

`POST /api/tasks/{id}/mcp-authorization` 请求：`{expectedVersion:1,mcpBindings:[...],mcpBudget:{maxCalls,maxDurationMs}}`，字段与创建任务相同。仅 DRAFT / PAUSED / WAITING_INPUT / FAILED / AWAITING_ACCEPTANCE 可操作；存在未知操作时先核对。期望版本必须匹配，旧授权保存到历史，预算用量不重置；非 DRAFT 任务变为 PAUSED 并要求重新验证。API 是用户确认新完整动作清单，不由模型调用。

保存后授权版本递增，保留历史快照、工具消耗及操作账本，清除依赖旧授权的阶段交接并要求重新验证/审查；DRAFT 保持 DRAFT，其余进入 PAUSED，需手动恢复。预算不得低于已登记值。同一个 actionKey 的旧回执仍去重，不能借重新授权重复执行。

## 界面注意

不要显示或传递管理令牌/API 密钥到动作参数。固定参数会随任务持久保存，不能作为凭据输入。只展示服务器独立的会话凭据是否配置。网络失败与未知状态不得用成功样式；上述能力不意味着任意外部服务器具有操作系统级项目隔离。
