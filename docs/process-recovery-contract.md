# 核心重启与原本机进程核验

从 `0.2.0-alpha.11` 起，SQLite schema 为 **5**，公开 API 与私有 Worker 协议仍为 **1**。schema 4 原记录保留，新建进程清单与索引；只支持 schema 4 的旧核心必须拒绝打开新库，避免旧入口忽略恢复门禁。实现边界及固定 OpenJDK 来源见[研究记录](./research/process-recovery-2026-11-08.md)。

## 持久来源

核心在启动实际 Worker 或 MCP stdio 前保存 `processSession`。会话保存类型、原核心 PID/出生时间、持锁开库的 `ownerCoreSessionId` 和执行来源。每次重新开库生成新的核心会话；同一 JVM 重开目录也不沿用旧身份。Worker 会话绑定原 Worker/epoch/节点，任务通过原操作租约关联它；MCP 会话绑定原服务身份及存在时的原操作/任务/项目。

启动阶段顺序为 `PREPARED → START_REQUESTED → PID_RECORDED`。实际根 PID/出生时间与 `ACTUAL_START / ROOT` 观察记录原子保存。重启仅将仍在 PREPARED、无任何观察的旧意图记为 `NOT_STARTED / CLOSED`；已保存 START_REQUESTED 却缺少 PID 回执的意图继续保留覆盖缺口，不能推断从未启动。

`processObservation` 保存原 PID、出生时间、ROOT/DESCENDANT、记录时间和观察来源。后代来源为 `WORKER_PIPE`、`CORE_SNAPSHOT`、`MCP_SNAPSHOT` 或 `RECOVERY_SNAPSHOT`，包含原观察者 PID/出生时间；旧记录迁移使用 `LEGACY_RECORD`。重复原 PID/出生对不覆盖最初观察。单会话超过 32,768 条时封闭执行；存储失败也封闭新增派发。

旧版本没有新清单的 LOCAL_PROCESS/CORE_PROCESS Worker 和未确认的非 HTTP MCP 动作建立 `LEGACY_WORKER` / `LEGACY_MCP` 会话，保存 `LEGACY_NO_LIST`。历史进程没有出生时间或 MCP 没有 PID 时明确保留未知字段。

## 停止与覆盖门禁

启动新核心可以创建空闲 Worker，让界面与查询继续工作。实际任务确认/恢复、Worker 派发与回调、MCP 启动及每个新 RPC 均检查旧会话门禁。发现仍未核对的原进程或覆盖缺口返回 409；持久存储不可用返回 503。

观察状态分别为：

| 状态 | 含义与停止行为 |
| --- | --- |
| RUNNING | 当前 PID 的已知出生与原出生一致；可以请求停止 |
| EXITED | 原 PID 当前未见；保存原身份，不补造退出原因 |
| PID_REUSED | 当前出生与原已知出生不同；保留两份出生，不终止新进程 |
| UNVERIFIABLE | 原出生或当前出生缺失；拒绝停止声明，不发送停止信号 |
| OBSERVATION_FAILED | 进程读取失败；继续保持未确认 |
| CURRENT_CORE | 仍是当前核心；恢复入口不能停止或勾选绕过 |

Windows 的句柄读取失败可能表现为空句柄或 isAlive=false；若 `allProcesses()` 中仍可见该 PID，保持 UNVERIFIABLE。一次核验、停止前的后代捕获、信号检查及每轮等待各自共享系统 PID 快照，避免为每条已退出历史记录重复枚举整个系统；下一轮重新观察，信号仍逐项核验原出生。快照不提供完整操作系统历史树。

Worker、MCP 和实际命令读取后代快照前后，均核对原父进程仍存活且出生时间一致。原身份更换、退出或缺失时不采用这份新增后代快照，继续按已记录的原 PID/出生清理；不能根据复用父 PID 的当前子树新增停止目标。原进程自然退出和被丢弃的快照仍不证明完整历史覆盖。

停止入口先保存用户请求并捕获可核验的当前后代，再处理后代与原根进程。发送信号前重新核验原 PID/出生，最多等待两秒后返回当时观察；请求本身不证明停止。当前在用的 RUNNING/LAUNCHING 会话使用任务取消。重复停止已核对且仍无未确认进程的会话不抹掉原核对记录。

实际 Worker 与 MCP 清理也使用原出生核验。MCP 即使在保存 PID 回执时失败，仍交还原传输给管理器；未确认退出时保留工具/进程容量。记录后代失败不会使实际清理提前结束，内存中已捕获的原身份继续用于清理。存储故障期间不能补造持久停止回执。

后代观察失败后，已捕获进程仍继续清理。即使已记录进程均停止，也不能把观察失败的 MCP 会话记为正常 CLOSED；存储仍可用时保留 UNKNOWN，由恢复入口分别核对缺失进程覆盖。实际退出可以释放原容量，覆盖未核对仍阻止所有新的派发。

进程观察来自当前快照与原管道，覆盖始终为 `OBSERVED_ONLY`。原 PID 全部消失仍不能证明启动间隙、已脱离快照的进程、文件写入或远端动作均已停止/撤销。用户须另存清单未覆盖进程的核对依据；已知仍活着、缺出生或读取失败的记录不能通过此声明绕过。

## API 与界面

| 接口 | 返回或请求 |
| --- | --- |
| `GET /api/process-recovery` | dispatchAllowed、完整 pendingCount、前 30 个 sessions、hasMore；仍检查全部会话 |
| `GET /api/process-recovery/{id}` | 原会话与当时观察；计数基于完整清单，最多显示 50 条记录 |
| `POST /api/process-recovery/{id}/stop` | 请求体 `{}`；请求停止原已核验身份后返回观察 |
| `POST /api/process-recovery/{id}/reconcile` | `{processesStopped:true,coverageChecked:true,note:"核对依据"}`；依据必填且最多 4,000 字符 |
| `GET /api/tasks/{id}/process-sessions` | 原任务租约关联的 Worker 及任务 MCP 会话，包含历史核对来源 |
| `GET /api/mcp/servers/{id}/process-sessions` | 原服务的实际 stdio 启动会话及历史记录 |

资源设置 → 执行节点与仓库展示原会话、PID/出生、Worker/MCP 来源、原任务和覆盖缺口，提供重新核验、请求停止与记录依据。弹窗输入随轮询保留；尚有已知未停止或无法核验记录时禁用提交，服务端在提交时仍重新检查全部原记录。

核对保存 USER 来源、原覆盖、时间及原依据，状态为 RECONCILED。同一原会话/原依据的显式重提去重；其他依据不能覆盖已经保存的记录。晚到后代观察重新标为 UNKNOWN、阻止新增派发，并保留此前核对历史；新核对追加历史。随后到达的已停止清理和重复停止不撤销已保存的核对结论。

任务的 `/reconcile` 仍单独要求文件和外部效果检查。进程覆盖核对不会退款、重放或把原模型/工具 UNKNOWN 改为成功。任务核对可以将原操作/租约标为 RECONCILED，但原模型回执状态、请求编号与已扣预算保留。

## 交付与验证范围

任务 ZIP 新增 `process-sessions.json` 和 **完整** `process-observations.json`。后者不使用界面显示上限，保存原 PID/出生/观察来源；领取摘要和凭据继续排除。

`npm run test:recovery` 使用最终 Jar、真实独立 Worker、HTTP 模型夹具、原 stdio 服务和生产 Web。只终止原核心，观察原 MCP 收到 EOF 后仍活着；重开同一目录检查 UNKNOWN、预算、原请求和新派发门禁，分别核对进程覆盖与任务效果，再次重启确认持久记录，执行新的任务并解压原任务证据。每次运行保存独立目录中的日志、原启动/EOF/调用事实及清理前失败记录。三端 CI 执行同一路径，实际结果按对应提交记录。

验收请求停止后有界等待旧 Worker/MCP 的全部已记录进程确认退出，保存等待中的原会话观察，再提交覆盖依据。请求返回、客户端超时或 409 不代表停止成功；真实无法核验记录继续阻止声明，最终核心和原夹具独立确认退出后才写入成功报告。

该验收是确定性本机夹具，不能替代外部供应商、远程进程、完整历史树、沙箱、持久拉取队列、PTY、角色/写入租约或长期负载的发布验收。
