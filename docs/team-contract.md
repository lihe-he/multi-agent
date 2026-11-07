# 动态副 Agent 与团队协议

适用版本：0.2.0-alpha.8。AgentDefinition 是可编辑、可版本化的职责与能力需求；Subtask 是调度器根据实际模型回执创建的副任务；AgentRun 是执行身份；Team 保存同一根任务的成员与通信。副任务使用独立 Git 基线和工作副本，模型与 MCP 调用均计入原根任务，不创建独立预算。

本版本用于本机 API 运行器。原生 CLI 无法确认逐请求挂起和容量释放，创建动态委派计划会返回 400；原有固定阶段任务仍可使用其既有运行路径。API 版本仍为 1，数据库 schema 仍为 3。

## 创建前固定定义与路由

`GET /api/agent-definitions?projectId=` 返回全局或该项目可选定义。`POST /api/agent-definitions` 接受：

```json
{
  "id": "agent-developer",
  "expectedVersion": 1,
  "name": "实现副 Agent",
  "role": "developer",
  "instructions": "在独立现场完成实现，保留原始证据，由父 Agent 核对采纳。",
  "capabilities": ["read", "search", "write"],
  "scope": ["src/"],
  "projectId": "",
  "routeId": "",
  "maxCalls": 8,
  "enabled": true
}
```

新建可省略 id 和 expectedVersion；修改必须携带当前整数版本，冲突返回 409。旧版本保存为修订记录，进行中的任务使用其创建时快照。停用定义或运行路由会停止相关执行新增调用；在途 HTTP 被停止等待时保留 UNKNOWN，不推测远端停止。

能力仅接受 read、search、write、verify、delegate、mcp。maxCalls 为 1–32 的整数。范围是 1–100 项项目内相对路径或 glob，反斜线规范为正斜线，拒绝绝对路径、盘符、`..` 和无效 glob。空 routeId 继承根路由，空 projectId 表示全局可选。字段不是命令或系统权限授权。

`POST /api/tasks` 增加可选 collaboration 对象：

```json
{
  "enabled": true,
  "definitionIds": ["agent-developer", "agent-reviewer"],
  "definitionVersions": {"agent-developer": 1, "agent-reviewer": 1},
  "allowedRouteIds": [],
  "maxActive": 6,
  "maxDepth": 3,
  "maxTotal": 24
}
```

界面提交所展示的全部所选版本，版本变化返回 409，刷新后重新核对。API 省略 definitionIds 时使用当时可用的内置/自定义定义；显式空清单会被拒绝。定义使用额外模型路由时，必须在 allowedRouteIds 中确认该路由，其引擎、模型、供应商和路由仍须满足根硬锁。任务保存完整定义、版本和 routeSnapshot；摘要状态省略完整定义，详情及执行前计划保留它们。

| 限制 | 默认值 | 范围与计数 |
| --- | --- | --- |
| maxActive | 6 | 2–6，含当前父执行者及所有活动/排队/等待后代 |
| maxDepth | 3 | 1–4，根阶段深度 0，直接副任务深度 1 |
| maxTotal | 24 | 1–64，根累计创建数；完成、取消或重建不会归零 |
| 定义 maxCalls | 8 | 1–32，一次副运行的调用上限，仍受原根预算约束 |

委派创建本身不消耗模型调用额度，但新副任务必须有可用根开发额度。后代不能使用验证与独立审查预留。全部模型请求的持久身份、AgentRun 调用计数和根额度在同一事务承诺。

## 实际工具权限

根完成约定的 scope 是修改范围；根执行者可读取项目资料，现有敏感文件和路径穿越保护继续生效。动态副任务的读取范围与修改范围分别保存为多层约束。

副任务能力是父运行实际能力、固定定义和本次委派请求的交集。每一个范围层都必须匹配，不能把多个层的 glob 合成并集。定义中的 verify 或 mcp 不会自动增加父运行缺失的权限；根必要验证由引擎独立执行。

窄读取或写入范围不能证明命令和外部 MCP 的文件访问边界，因此移除 verify 和 mcp；这些动作由获授权的根阶段处理。枚举和检索先过滤允许范围再应用返回上限。运行器同时裁剪工具定义并验证实际调用，模型伪造未开放工具名称仍会被拒绝。

已确认 collaboration 提供根阶段委派授权；已确认的 MCP 固定动作只在拥有写入能力的实现/修复执行者中开放。独立 PROPOSAL 和 REVIEW 没有共享团队工具和收件箱，持久层也拒绝其发布团队消息。

## 受控委派、等待与采纳

模型使用以下受控工具，核心从该次成功模型回执派生 parentRunId、requestId 和 toolCallId。工具参数不能指定根身份、路由、预算或增加授权。

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| delegate_agent | definitionId、title、goal、scope；可选 capabilities、dependsOn、maxCalls | 原子创建 Subtask、AgentRun 和根累计计数；以原父模型回执和工具调用身份去重 |
| wait_agent | subtaskId | 等待自己的直接副任务，返回原证据、成果版本与 agentState；等待期间不占模型容量 |
| adopt_agent_changes | subtaskId | 有写入能力的父运行核对并采纳成功的直接写入副任务；只读结果留作证据 |
| send_team_message | content，可选 recipient | 保存实际发件运行、原模型请求和工具调用；空收件人广播 |
| list_team_agents | 无 | 读取该根任务下的实际副任务状态 |

dependsOn 最多 12 项，只能引用同一父运行中已经创建的副任务。依赖关系因此不能回指父执行者、后代或未来节点。依赖成功后，调度器把允许读取的实际文件变化整合到新副任务副本，再保存更新后的输入基线和来源；依赖失败不运行后续副任务。

模型/路由/平台进程容量只包围实际 HTTP 传输和协议回执；文件工具、快照准备、等待和采纳发生在回执后。工具与必要写入资源另行申请，父子没有共用可写现场。同一路由仍有其并发限制，增加 Agent 数量不会增加路由容量。

采纳先核对副成果版本与全部待变更文件，再检查父文件是否仍等于委派基线或目标成果。冲突会在任何写入前拒绝；无冲突后逐项再次核对，保全父现场其他修改及已暂存 blob，交接增加、删除、Git 模式和 POSIX 执行位。写入中断不能回滚为“未执行”，操作保留 UNKNOWN。单次交接最多 100 个文件，每个变更文件不超过 256 KB。

采纳操作的 ACKNOWLEDGED 回执与副任务 integrations 在同一数据库事务追加。DISPATCHED 或 UNKNOWN 不能重放，新工具编号也不能绕过未核对效果。子任务的 SUCCEEDED 只表示本次执行结束并核对范围，根验证、独立审查与人工验收分别完成。

## 查询、消息与来源

`GET /api/tasks/{id}/team` 返回 `{team, agents, runs, limits}`。未启用委派时 team 为 null。每个副任务保留根/团队/项目/会话身份、父运行与可选父副任务、实际运行、原请求/工具来源、定义版本、路由、深度、依赖、权限层、状态、输入/成果版本、证据及采纳回执。运行中的副任务状态来自实际 AgentRun。

`GET /api/tasks/{id}/team/messages?after=0&before=0&limit=40` 返回 `{messages, cursor, hasMore}`，最多 100 项。默认最新倒序，before 读取更早页，after 读取更新记录并正序返回；两种游标不能同时为正，负值返回 400。界面读取历史时不跳回最新，模型收件箱从序号零正向分页，不漏掉较早消息。

`POST /api/tasks/{id}/team/messages` 仅接受用户 requestId、content 和可选 recipient 作为业务输入。用户 requestId 匹配 `[A-Za-z0-9_-]{8,100}`，content 为 1–6000 个 UTF-16 字符。发件人为认证 USER，原模型请求与工具来源为空，客户端提供的 sender 等字段不能伪造模型身份。相同编号与正文返回原消息，改变正文或收件人返回 409。团队最多保存 2000 条消息。

消息只作为带来源的资料，活动收件人在下一次模型请求读取；已结束成员的定向消息留档。收件人必须属于当前 Team。UI 保存草稿和待确认编号到会话存储，切换页面和重载后可沿原编号重试；明确结束重试再发送会生成新消息。

所有端点受本机管理令牌鉴权，项目 MCP 只读令牌不能调用。实际子运行的请求可通过[模型请求协议](./model-request-contract.md)按 runId 查询。子执行固定 MCP 动作时保存实际 agentRunId、modelRequestId、toolCallId、rootTaskId、teamId、subtaskId 和定义来源；父运行引用已确认动作返回原来源，并不重复派发或扣预算。

交付 ZIP 增加 team.json、team-messages.json、team-operations.json、mcp-operations.json，保留已有 agent-runs.json、model-requests.json、operations.json、task.json、补丁及事件。未知状态、原证据和原来源随交付保留。

## 停止、重启与范围

根取消、暂停、时限、存储故障以及相关定义/路由撤回向所有后代传播。副运行默认总时限十五分钟，实际在途 HTTP 停止本地等待时仍为 UNKNOWN。新模型请求、工具和委派在发现任何根 UNKNOWN 操作时停止；人工核对前确认后代与 MCP 已停止。

重启把未结束运行、请求、快照/依赖准备及采纳操作保留为 UNKNOWN，已结束事实不覆盖，不自动重新启动。人工核对后恢复使用新的运行身份，原副任务和原回执继续保存。

本版本不提供动态 workflow DSL、远程 Worker、任意跨父依赖、外部参数化 MCP、原生 CLI 动态委派或独立副任务恢复。隔离副本不是操作系统沙箱，依赖本机可信项目和已确认的验证命令；历史未启用 collaboration 的固定阶段保持原路径，不能据此声称全部历史工具已执行新能力交集。完整 F01–F21 和计划 M0–M3 继续实施。
