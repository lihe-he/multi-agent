# 每日计划运维恢复研究

日期：2026-11-27

本轮调研关注本机每日计划的启停、历史触发和失败恢复，不把自动回填、重试与运行中任务重放合并为一个宽泛的“恢复”操作。以下资料用于分析公开行为和运维边界；没有复制上游实现代码。

| 来源 | 查阅内容 | 对本项目的启发 |
| --- | --- | --- |
| [Temporal Schedules](https://docs.temporal.io/schedule) | pause/resume、manual trigger、backfill 和 overlap policy 分别定义 | 暂停未来触发、单次人工恢复和历史补跑应是独立操作。本机 daily occurrence 恢复只作用于一日记录，不引入批量回填或复杂 overlap policy |
| [Prefect Schedules](https://docs.prefect.io/v3/concepts/schedules) | active 状态控制新建 flow run；更新/重新部署保留已有 pause 状态 | 编辑名称、时区或模板时保留 `enabled` 状态；暂停是持久配置，不让普通编辑意外重新启用 |
| [Airflow DAG Runs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/dag-run.html) | UI 对历史任务实例检查与重跑的作用范围 | 界面展示单次 occurrence 和关联 task，让操作者知道重跑范围。不能把 Airflow 的清理后自动重试直接套到具有 UNKNOWN 外部效果的任务 |
| [db-scheduler README at `ff5e13176a1849d153339664216a616bbe515063`](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/README.md) | dead execution 与 unresolved task 的处置边界 | 对坏模板或失效引用保留失败快照，不要无条件自动重试；用户修复来源后显式选原模板或当前计划版本处理 |

## 本地决策

- `enabled=false` 只停止未来本地日期扫描，仍处理已经落盘的 `PENDING` occurrence；这与暂停调度而不取消已接纳动作的用户预期一致。
- 原 occurrence 模板恢复适用于依赖或现场暂时修复的情况；采用当前计划版本的恢复是独立显式操作，审计同时保留旧 taskTemplate、旧 scheduleVersion 与新模板来源版本。
- 不存在关联 task 时，单次重试先持久化 `PENDING` 和恢复凭据。发生重启时，原队列按 occurrence 唯一关联查找任务后再决定是否创建；SQLite 上现有 `entities_task_schedule_occurrence` 唯一索引维持第二道去重边界。
- 已存在关联 task 时，不允许再次创建。草稿任务先由用户在任务页确认；非草稿任务的核对只更正 occurrence 账本，不执行 task action，不改变 `UNKNOWN`、预算或队列。
- 每次人工恢复采用版本校验、规范 UUID 幂等请求编号、可选原因和原子持久审计；HTTP 重放只返回同一请求对应的当前 occurrence。

该增量仅补齐本机 F21 每日调度的运维界面与单条恢复路径，不声称解决跨核心/远程 owner、分布式锁、完整备份、长期负载或 M0–M3 退出条件。
