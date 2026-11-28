# 持久每日调度契约

本文记录 alpha.32 候选实现中的每日计划 API、触发记录和人工恢复语义。它覆盖 F21 的持久调度子集，不改变计划书的完整退出条件，也不意味着远程节点或长期负载已验收。

## API

核心提供本机 `/api/schedules`：

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/schedules` | 列出调度定义 |
| `POST /api/schedules` | 创建每日计划，并保存首个配置版本 |
| `PATCH /api/schedules/{id}` | 使用 `expectedVersion` 乐观并发更新 |
| `DELETE /api/schedules/{id}` | 以版本更新方式停用，不物理删除历史 |
| `GET /api/schedules/{id}/occurrences` | 读取最近 100 条触发记录 |
| `POST /api/schedules/{id}/occurrences/{occurrenceId}/recover` | 对单条 `NEEDS_ATTENTION` 触发执行版本保护的恢复或关联 |

创建字段为 `name`、`timezone`、`localTime`、`enabled`、`task`。`timezone` 必须是 IANA 区域标识，`localTime` 使用 `HH:mm` 24 小时格式。任务模板限定为现有任务创建字段的白名单，必须引用有效项目和路由、满足硬锁，并至少包含一项验收条件和一条验证命令。未知字段拒绝。

### 任务模板边界

计划任务使用项目当前的默认执行节点和仓库绑定；模板不接受 `repositoryBindingId`、`bindingVersion` 或 `nodeVersion`，因此不能为单个计划选择项目的其他仓库绑定。`workflow` 和 `workspaceSelection` 在保存计划时只校验字段结构与类型；真实流程版本解析、Git 现场选择和当前执行授权检查发生在 occurrence 物化为任务时。若这些引用随后失效、被禁用或现场已变化，计划仍可保存，但对应 occurrence 会进入 `NEEDS_ATTENTION` 并保留错误摘要供处理。

更新省略的字段从当前版本继承，必须带有精确整数 `expectedVersion`；旧版本冲突返回 409。更改生效时间前的历史扫描仍按当时生效的版本计算，不把新时区或时间套用到过去日期。

## 持久化与状态

定义、版本化 revision、occurrence、恢复审计和关联 task 保存在现有 SQLite `entities` 表中，schema 版本为 13。revision 的配置快照按生效区间回填，区间关闭时间与扫描游标会持久更新。唯一索引保证每个 schedule 的一个本地日期至多有一条 occurrence，任务的 `scheduleOccurrenceId` 唯一；恢复记录以 occurrence 与规范化 UUID 组成唯一实体编号，并有 occurrence/时间索引。插入 occurrence 使用 `INSERT OR IGNORE` 处理并发触发竞争；定义更新及旧、新 revision 写入使用比较版本的事务。

Occurrence 的主要状态为：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已落库，等待创建或恢复任务 |
| `TRIGGERED` | 独立任务已建立并通过确认排入执行 |
| `MISSED` | 预定时间超过 5 分钟窗口，按跳过策略关闭，不补跑 |
| `SKIPPED_DST_GAP` | 本地时刻落在夏令时春季缺口，审计后跳过 |
| `NEEDS_ATTENTION` | 任务恢复、创建或确认遇到错误，需人工处理；每次状态变化递增 occurrence `version` |

alpha.31 遗留 occurrence 尚无 `version` 字段时，查询响应按版本 1 投影，首次恢复会持久化递增后的版本；读取历史不会改写原记录。

秋季重复时刻选择该本地时间的第一个有效 offset。每个 occurrence 保存本地日期、配置版本、时区、时间、预定瞬间及任务模板快照。触发后为该日期创建单独 task 和 session。核心启动时先处理持久的 `PENDING`，因此计划停用或核心重启不会清除已记录的待处理触发。

每轮每个 schedule 最多扫描 100 个本地日期，并把游标保存到 revision；重复 tick 从游标之后推进。Pending 恢复每轮最多取 100 条，出错记录转成 `NEEDS_ATTENTION`，避免单条失败永久阻塞后续记录。轮询顶层记录运行错误并继续后续周期。

### 人工恢复

恢复请求必须包含当前 `expectedVersion`、`action` 和 UUID `requestId`，可选提供最多 500 字的原因。requestId 的 UUID 字母大小写先规范化；相同 occurrence、requestId、原 `expectedVersion`、操作和原因重放时返回当前记录而不重做副作用。版本校验和恢复审计写入同一事务。若接口响应丢失，客户端可安全重放同一请求。早期 alpha.32 开发版本写入但未保存原版本号的审计记录不能证明请求正文相同；升级后重放这类 requestId 会返回 409，不会重复执行副作用。

`action` 仅接受以下值：

| 操作 | 前置条件 | 效果 |
| --- | --- | --- |
| `RETRY_SNAPSHOT` | 状态为 `NEEDS_ATTENTION` 且无 `scheduleOccurrenceId` 关联任务 | 只重试该 occurrence 保存的 taskTemplate；记录 `PENDING` 与审计后通过原流程物化并排入一次执行 |
| `RETRY_CURRENT_VERSION` | 同上 | 将快照更新为计划当前版本的模板后单次重试；审计保留旧模板、旧计划版本和新版本 |
| `RECONCILE_EXISTING_TASK` | 有关联任务且任务不再是 `DRAFT` | 记录现有 taskId/state 并把 occurrence 关联为 `TRIGGERED`；不改变任务状态、不执行 approve/resume、不向队列再次派发 |

任一重试都拒绝已有任务的 occurrence，保证同一日期不会创建第二个 task/session。任务仍为 `DRAFT` 时，操作者先通过任务详情执行计划确认，再回到触发历史关联现有任务。`UNKNOWN` 任务的状态仍保留为 `UNKNOWN`；关联操作只修复 occurrence 到 task 的账本关系，不能证明或改变未知的外部效果。恢复审计记录 actor `LOCAL_USER`、时间、操作、请求编号、原因和适用版本/任务身份；历史可从 occurrences 响应读取。

每日计划管理 UI 支持新建、编辑、启用/暂停、最近 100 条触发历史、任务跳转和逐 occurrence 人工处理。编辑时未被基础表单呈现的高级模板字段保持原值。暂停只阻止未来日期生成 occurrence，不取消已落库的 `PENDING` 触发。UI 重试需要显式勾选确认，会立即让该日期任务进入队列。

## 来源与验证范围

数据库唯一领取、事务和并发边界沿用本仓库此前对 [db-scheduler](https://github.com/kagkarlsson/db-scheduler) 固定提交 `ff5e13176a1849d153339664216a616bbe515063` 的研究取舍；具体核对记录见[持久派发队列研究](./research/dispatch-queue-2026-11-08.md)。本实现复用本地 SQLite owner 和 `ExecutionStore` 事务，不添加调度器依赖，也没有复制上游代码。

本契约描述 API、持久状态和本机运维 UI；跨设备/远程调度、多核心 owner 协调、完整备份恢复、长期负载，以及计划书规定的完整 F01–F21 和 M0–M3 退出条件仍须单独交付和验证。具体测试与准确提交 CI 结果以 alpha.32 QA 回执为准。
