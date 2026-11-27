# 持久每日调度契约

本文记录 alpha.31 候选实现中的每日计划 API 和触发记录语义。它覆盖 F21 的持久调度子集，不改变计划书的完整退出条件，也不意味着 UI、远程节点或长期负载已验收。

## API

核心提供本机 `/api/schedules`：

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/schedules` | 列出调度定义 |
| `POST /api/schedules` | 创建每日计划，并保存首个配置版本 |
| `PATCH /api/schedules/{id}` | 使用 `expectedVersion` 乐观并发更新 |
| `DELETE /api/schedules/{id}` | 以版本更新方式停用，不物理删除历史 |
| `GET /api/schedules/{id}/occurrences` | 读取最近 100 条触发记录 |

创建字段为 `name`、`timezone`、`localTime`、`enabled`、`task`。`timezone` 必须是 IANA 区域标识，`localTime` 使用 `HH:mm` 24 小时格式。任务模板限定为现有任务创建字段的白名单，必须引用有效项目和路由、满足硬锁，并至少包含一项验收条件和一条验证命令。未知字段拒绝。

### 任务模板边界

计划任务使用项目当前的默认执行节点和仓库绑定；模板不接受 `repositoryBindingId`、`bindingVersion` 或 `nodeVersion`，因此不能为单个计划选择项目的其他仓库绑定。`workflow` 和 `workspaceSelection` 在保存计划时只校验字段结构与类型；真实流程版本解析、Git 现场选择和当前执行授权检查发生在 occurrence 物化为任务时。若这些引用随后失效、被禁用或现场已变化，计划仍可保存，但对应 occurrence 会进入 `NEEDS_ATTENTION` 并保留错误摘要供处理。

更新省略的字段从当前版本继承，必须带有精确整数 `expectedVersion`；旧版本冲突返回 409。更改生效时间前的历史扫描仍按当时生效的版本计算，不把新时区或时间套用到过去日期。

## 持久化与状态

定义、版本化 revision、occurrence 和关联 task 保存在现有 SQLite `entities` 表中，schema 版本提升为 12。revision 的配置快照按生效区间回填，区间关闭时间与扫描游标会持久更新。唯一索引保证每个 schedule 的一个本地日期至多有一条 occurrence，任务的 `scheduleOccurrenceId` 唯一。插入 occurrence 使用 `INSERT OR IGNORE` 处理并发触发竞争；定义更新及旧、新 revision 写入使用比较版本的事务。

Occurrence 的主要状态为：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已落库，等待创建或恢复任务 |
| `TRIGGERED` | 独立任务已建立并通过确认排入执行 |
| `MISSED` | 预定时间超过 5 分钟窗口，按跳过策略关闭，不补跑 |
| `SKIPPED_DST_GAP` | 本地时刻落在夏令时春季缺口，审计后跳过 |
| `NEEDS_ATTENTION` | 任务恢复、创建或确认遇到错误，需人工处理 |

秋季重复时刻选择该本地时间的第一个有效 offset。每个 occurrence 保存本地日期、配置版本、时区、时间、预定瞬间及任务模板快照。触发后为该日期创建单独 task 和 session。核心启动时先处理持久的 `PENDING`，因此计划停用或核心重启不会清除已记录的待处理触发。

每轮每个 schedule 最多扫描 100 个本地日期，并把游标保存到 revision；重复 tick 从游标之后推进。Pending 恢复每轮最多取 100 条，出错记录转成 `NEEDS_ATTENTION`，避免单条失败永久阻塞后续记录。轮询顶层记录运行错误并继续后续周期。

当前 occurrences API 只提供读取；进入 `NEEDS_ATTENTION` 的记录没有修复或重试操作，也没有对应的调度管理 UI。操作者可查看错误摘要，但需通过后续版本提供的恢复能力处理这类 occurrence；本候选不承诺完整的人工恢复闭环。

## 来源与验证范围

数据库唯一领取、事务和并发边界沿用本仓库此前对 [db-scheduler](https://github.com/kagkarlsson/db-scheduler) 固定提交 `ff5e13176a1849d153339664216a616bbe515063` 的研究取舍；具体核对记录见[持久派发队列研究](./research/dispatch-queue-2026-11-08.md)。本实现复用本地 SQLite owner 和 `ExecutionStore` 事务，不添加调度器依赖，也没有复制上游代码。

本契约描述 API 和持久状态；计划模板编辑 UI、跨设备/远程调度、多核心 owner 协调、完整备份恢复、长期负载，以及计划书规定的完整 F01–F21 和 M0–M3 退出条件仍须单独交付和验证。具体测试与准确提交 CI 结果以 alpha.31 QA 回执为准。
