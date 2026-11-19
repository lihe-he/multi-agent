# Workflow 协议（alpha.23）

本文说明 `0.2.0-alpha.23` 的版本化流程定义、候选发布、节点运行和导出边界。该增量提供本地 API 模型路由的基础 DAG 路径；M0–M3 的完整产品退出条件及三端发行验收仍未完成。

## 定义与版本

Workflow 使用版本 `1` 的 JSON DSL，仅接受 `dslVersion`、根输入、`maxParallel` 与 `nodes`。输入根来源限定为 `goal`、`contract`、`context` 和 `baseline`；节点数为 1 至 64，并声明类型、职责版本、依赖、输入输出、范围、权限、调用上限、时限、验收和终止条件。流程校验会拒绝未知字段、循环依赖、无祖先提供者的输入、重复逻辑产物和不支持的权限组合。

每个版本固定其规范 DSL 摘要、Agent 职责快照与项目归属。编辑产生新的版本，乐观并发检查防止覆盖；复制产生新身份，回退也作为新版本保存。JSON 导入/导出保留实际定义和摘要。项目只能读取本项目及全局定义。

任务创建时通过流程选择固定版本和摘要。对非根模型路由需在任务操作前明确批准；创建任务时还要保留该项批准。运行前会复核路由、根锁定、流程摘要、定义版本、任务授权和工作目录绑定。

## 执行与恢复

流程运行建立新的持久代次和基线副本。仅当声明依赖成功的节点进入 DAG 调度；并行度同时受 DSL 和任务协作上限约束。节点在独立 Worker 现场准备祖先输入，通过实际模型请求和逐轮根预算，保存脱敏证据、版本和原 Worker 回执。下游只收到声明输入和实际祖先产物。

写入节点的变更经过版本核对后才整合到根现场。流程只是阶段编排，不能跳过根任务既有的必要验证、独立审查或人工验收。成功节点通过原输入、请求、证据、Worker 租约、现场版本和摘要复核后才能在同一流程恢复时复用；新的尝试沿用根预算并获得新的尝试编号。

暂停或取消会停止后续节点。若原 Worker 调用已返回，根停止已确认，且能持久核对未发出模型请求、零请求消耗和无未知效果，该等待可记录已确认取消后恢复。已经发出的模型请求、外部工具或丢失的执行回执不据异常类型推断结果；保留 `UNKNOWN` 并禁止自动重放或续跑。

## 候选生成与导出

用户可以基于现有任务输入请求只读候选。每次生成固定原请求编号、定义版本、输入摘要、调用限制和实际任务来源。模型只能调查并返回 JSON 流程草案，平台按同一 DSL、项目、权限及职责版本校验；解析或校验失败仍保留原模型证据。

候选不会自动成为任务流程或已发布版本。用户可以查看原证据和摘要、修改名称及 DSL、重新校验，再按原来源摘要发布。发布采用版本冲突检查；相同原请求和发布参数的重试不会生成第二个版本。使用候选需要另行对发布版本做任务选择与授权确认。

任务 ZIP 含 `workflow-runs.json`、`workflow-node-attempts.json`、`workflow-proposals.json` 与 `workflow-generations.json`。归档保留流程运行、节点尝试、生成请求和候选之间的来源引用；任务导出不是数据库或完整配置备份。

## API

- `GET/POST /api/workflows`：列表与新版本发布。
- `POST /api/workflows/validate`、`/api/workflows/import`：校验、导入。
- `GET /api/workflows/{id}/versions[/{version}]`、`/{version}/export`、`/rollback`：历史、单版本和版本回退。
- `POST /api/tasks/{id}/workflow-selection`、`/workflow-restart`：固定新选择或明确建立新代次。
- `GET /api/tasks/{id}/workflow-runs`、`/workflow-runs/{runId}/attempts`：有界分页读取运行与节点记录。
- `POST/GET /api/tasks/{id}/workflow-generations`、`GET /workflow-proposals`：提交只读生成和读取来源历史。
- `GET /api/tasks/{id}/workflow-proposals/{proposalId}`、`POST /workflow-proposals/{proposalId}/publish`：查看候选并按摘要明确发布。

上述路由均遵循 API 原有认证、任务项目归属、原请求来源及并发控制。该 API/W​​orker 协议在 alpha.23 不改变版本号 `1`。

## 未完成验收

alpha.23 不代表流程调度完整满足计划书 M2：远程 Worker 节点、跨核心持久租约/资源等待环、真实 Codex/Claude CLI 的混合执行、全平台发行验收、长期负载、完整数据库备份恢复及 F01–F21 整体退出条件仍需单独完成。开发期的固定版本 OSS 参考、内容校验记录见任务运行目录，不进入产品依赖。
