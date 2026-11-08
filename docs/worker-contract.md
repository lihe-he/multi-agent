# 本机执行节点、仓库绑定与 Worker 租约

适用版本：0.2.0-alpha.9。API 协议仍为 1；SQLite schema 为 4，新增租约唯一约束与节点/任务查询索引。结构升级与版本更新共用数据库事务，未来版本拒绝打开。历史操作保留原来源，不推测补造租约。

## 身份和目录

ExecutionNode 是实际执行主机的稳定身份，RepositoryBinding 是项目在该节点上的 Git 仓库目录，Worker 是一次实际核心 JVM 内的执行会话，WorkerLease 是一次操作的执行归属。项目编号、节点编号和仓库绑定编号彼此独立；绝对路径只在所在节点解释。

本轮执行方式是 `LOCAL / CORE_PROCESS`。节点编号保存在核心数据目录，普通重启保持相同编号；每次 Worker 保存新的编号、实际 JVM PID、进程开始时间与递增 epoch。它不是独立远程进程或已配对远程主机。复制整个数据目录到另一台主机的节点迁移与身份重置尚需另行设计，不能据此宣称同路径跨主机执行已经完成。

导入项目时原子建立默认本机仓库绑定。原 Project.path 为首页扫描及检索保留；任务实际执行从冻结的绑定 sourcePath 取目录。用户可以为已有项目添加其他本机 Git 仓库绑定；节点核验绝对路径、Git 根目录和项目归属。保存后的路径使用真实路径，拒绝相对路径和运行中解析目标变化。

## 配置 API

| 接口 | 数据及边界 |
| --- | --- |
| `GET /api/execution-nodes` | 当前节点配置、版本、平台、执行代次及依据 Worker 心跳得到的状态 |
| `POST /api/execution-nodes` | `{id,expectedVersion,name,enabled}`，只能编辑当前本机节点；不接受客户端指定 epoch、PID 或能力 |
| `GET /api/repository-bindings?projectId=` | 项目在节点上的仓库绑定；指定项目时核验项目存在 |
| `POST /api/repository-bindings` | `{id?,expectedVersion?,projectId,executionNodeId,name,path,enabled}`；修改必须匹配当前版本，不能改变已有绑定的项目或节点身份 |
| `GET /api/workers` | 最近 30 条实际执行会话，包含 PID、开始时间、心跳、期限和结束事实 |
| `GET /api/tasks/{id}/worker-leases?before=0&limit=30&state=` | 按稳定数据库序号倒序分页，最多 100 项；状态为 ACTIVE、RELEASED、UNKNOWN、RECONCILED |

所有接口沿用认证管理令牌，只读项目 MCP 令牌不能访问。节点和绑定开关必须为布尔值，版本必须为正整数，未知配置字段拒绝。冲突返回 409，客户端保留输入并重新核对。

任务创建接受可选 `repositoryBindingId`、`bindingVersion`、`nodeVersion`；UI 提交所展示版本，省略绑定时使用当前项目默认绑定。任务保存 execution 快照，包括协议/授权版本、节点身份/名称/版本、绑定身份/名称/版本、实际 sourcePath 和执行方式。

更改节点或绑定版本立即阻止旧授权的任务新增调用。已有在途调用受控停止，收到的结果保留原始观测，失效归属不能将其确认为成功。`POST /api/tasks/{id}/execution-authorization` 接受 `{expectedVersion,repositoryBindingId,bindingVersion,nodeVersion}`，只在可停止状态且没有未知效果时更新授权。原快照进入 executionHistory，必要验证和审查重新执行；非草稿任务保持 PAUSED，继续需要恢复。

已有成果的任务只允许更新同节点、同源目录配置；切换源仓库新建任务。草稿切换仓库绑定时，项目规则同时按新源目录重新读取，原规则进入 executionRuleHistory。规则文件拒绝链接、越界和超过 128 KB 的内容。历史没有 execution 快照的任务仍保留历史语义；新操作记录实际本机 Worker，不伪造旧授权版本。

## 派发、心跳与回执

核心先保存原操作意图，再由本机 Worker 核验项目/目录、节点/绑定版本、当前 epoch 和根未知效果。操作 `DISPATCHED`、租约 `ACTIVE` 和实际 AgentRun 归属在同一 SQLite 事务中保存。唯一索引限制 `(operationKind,operationId)` 一次租约，`dispatchCount=1`；编号变化也不能绕过根未知效果。

一次租约固定 taskId、agentRunId、workerId、executionNodeId、epoch、repositoryBindingId、bindingVersion、authorizationVersion 和实际 workingDirectory。随机领取凭据只在 Worker 内存保存，数据库保存 SHA-256 摘要；查询与 ZIP 均移除摘要。公共管理 API 没有客户端领取或回执入口。

每三秒续约一次，期限为三十秒，心跳独立于客户端连接和模型资源槽。当前进程同时用单调时间限制三十秒无心跳窗口，系统时间回退超过一秒也会失去原归属，不能延长旧领取期限或确认迟到结果。模型、依赖、等待、采纳继续遵守原分类资源限制；租约表示执行归属，不表示占用模型容量。正常结果与证据、原操作 ACK、租约 RELEASED 共用事务；团队文件采纳另外把 integrations 写入同一事务。

RELEASED/CONFIRMED 表示本机执行路径返回并收到回执，不证明远端模型停止计费或任意外部效果被撤销。UNKNOWN 保留原操作及收到的原始输出，迟到结果记录 observedStatus，正式 status 及副任务终态仍为 UNKNOWN。租约收尾不会覆盖任务此前保存的原始错误。

本轮覆盖根快照、固定/动态模型阶段、必要验证、审查快照、子快照/依赖和子文件采纳；模型请求及固定 MCP 来源保留实际 Worker/租约字段。原文件工具磁盘操作记录和 MCP 传输停止账本继续保留各自明细，本轮不宣称已经将全部工具动作迁移到独立 Worker 进程。

## 失效、停止与恢复

期限过期或 epoch 改变后不能续约、确认成功或自动重新领取。新 Worker 启动与旧在途租约变为 UNKNOWN 在同一事务保存，不因新进程存在就认定旧进程已停止。根任务进入 UNKNOWN 并保留目录、请求、证据和旧归属。

根停止与定义/路由/节点/绑定撤回仍向后代传播。人工核对同时要求根执行已完成收尾、所有实际副任务已返回、Worker 在途领取已结束以及 MCP 停止已确认；用户检查旧进程和外部效果后，租约另记录 RECONCILED / USER_CONFIRMED。原执行、请求和未知结果不会被恢复覆盖，后续执行使用新的操作编号与运行身份。

父命令退出后仍有已观察子进程存活时，结果继续保留 UNKNOWN 并请求停止。原 PID 和可取得的开始时间同时进入命令结果与事件，输出截断后仍保留身份；最多列出 50 个并保留总数，缺失开始时间明确保存为 UNKNOWN_START_TIME。该时点的观察不代表完整历史进程树或跨重启停止证明。

用户停止请求与 PAUSING/CANCELLING 在同一存储锁内发布，停止回执随后保存。快速返回和交付收尾均不能以迟写的等待状态覆盖已停止事实，待停止期间不能进入等待验收。

数据库故障立即封闭新派发，无法保存的回执不计为成功。完整独立 Worker 进程、任务持久队列拉取、远程配对及网络重连、角色/写入租约、PTY、动态 workflow、完整备份与长期故障矩阵仍继续实施。

证据与实际来源见[实施记录](./implementation-status.md)和[固定开源来源与审查](./research/worker-leases-2026-11-08.md)。
