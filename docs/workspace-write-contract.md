# 根现场写入保护协议

alpha.14 为本机 clone/worktree 增加整次根执行的写入归属。SQLite schema 为 8，公开 API、实体和 Worker 帧协议仍为 1。启动提交与 READY 归属继续遵循[启动现场协议](./workspace-selection-contract.md)，根尝试和动作继续遵循[派发协议](./task-dispatch-contract.md)。

## 领取与真实锁

根动作领取时，task、taskExecution、dispatchAction 与 workspaceWriteLease 在同一 SQLite 事务保存。PENDING 尚未领取时没有写入编号；领取失败或事务回滚不会留下可执行的写入意图。同一根尝试只能有一个写入记录，同一 scopeKey 只能有一个 protectionReleased=false 的记录。

scopePath 固定为核心数据目录的实际路径下 `workspaces/<taskId>/work`。Windows scopeKey 忽略大小写；已经保存的 Windows 短路径或其他目录别名必须解析到这一物理现场。预期现场及其父目录出现外国解析目标、特殊文件或目录类型变化时拒绝领取和后续调用。公开目录字符串不能单独授予写入权。

领取产生 PREPARED 意图。实际独立 Worker 在准备前取得 `writer.lock` 的 FileChannel/FileLock，并原子保存相邻 `writer.json`。锁和清单位于任务元数据父目录，不进入交付工作目录。HELD 回执与原操作 ACK、Worker 操作租约的结束同事务落库，之前不能派发模型或验证。

完整 READY 已存在的后续根尝试先取得新的写入编号，再核验原现场和 Git 归属；使用 WORKSPACE_WRITE 操作取得本次真实保护，不重新创建 clone/worktree，也不重放已结束的模型阶段。没有选择快照的旧 clone 任务保留原准备规则，并使用相同根保护。

## 固定身份

workspaceWriteLease 保留以下原来源：

| 字段 | 含义 |
| --- | --- |
| id / schemaVersion | 独立写入编号 / 实体版本 1 |
| taskId / projectId | 原任务和项目 |
| taskExecutionId / dispatchActionId / attempt | 原根尝试、动作和累计尝试号 |
| scopePath / scopeKey | 实际根现场和库内唯一范围键 |
| workerId / executionNodeId / workerEpoch | 原执行者、节点和代次 |
| executorPid / processStartedAt | 原 Worker PID 和出生身份 |
| ownerCoreSessionId / ownerCorePid / ownerCoreStartedAt | 领取核心的独立会话、PID 和出生身份 |
| authorizationVersion / workspaceSelectionVersion | 冻结的执行授权与启动选择版本 |
| strategy / baseline | 本次策略与固定提交；旧任务没有选择快照时保留原已知字段 |
| state / protectionReleased | 原结果状态 / 是否已取得释放保护回执 |
| grant / release / reconciliation | 实际锁、释放和用户核对依据，分别保存 |

私有领取和真实回执沿用同一固定身份；改变根编号、Worker、PID/出生、代次、版本或 scope 不能复用原锁。操作、Worker 操作租约、AgentRun、模型请求、证据和模型来源 MCP 操作携带 workspaceWriteLeaseId。公开 Worker 记录与 ZIP 仍去除内部 claimDigest。

## 状态与收尾

| 状态 | 事实 | 是否允许后续根取得保护 |
| --- | --- | --- |
| PREPARED | 根领取与写入意图已承诺，真实成功回执尚未落库 | 否 |
| HELD | 原 Worker 的锁成功回执与 ACK 已落库 | 否 |
| RELEASED | 全部实际调用已收尾并取得原保护释放回执 | 是 |
| UNKNOWN，protectionReleased=false | 原效果、实际锁或回执需核对 | 否 |
| UNKNOWN，protectionReleased=true | 用户核对后另存释放依据，原 UNKNOWN 历史保留 | 通过其他根/进程/效果门禁后可建立新尝试 |

每次实际准备、模型、验证、审查副本和团队调用借用原根锁。借用仍在途时，Worker 拒绝释放；模型容量释放、阶段结束和客户端退出不解除整次根保护。根内副任务继续在各自副本执行，根保护不代替其权限与冲突检查。

正常收尾先等待实际调用返回、团队结束与 Worker/MCP 调用收尾，再由原 Worker 返回锁关闭事实。核心等待释放回执时不持有全局存储锁，使原心跳和 RPC 读取可以继续。控制句柄保留到释放完成，在同一存储临界区再次处理迟到的暂停/取消请求、保存根结束回执并移除句柄；期间验收、导出和配置修改仍返回 409。

Worker 丢失、核心会话变化、真实锁取得但 ACK 落库失败、真实锁已关闭但释放回执落库失败，都保留原编号和 UNKNOWN。实际进程退出可以使 OS 锁变为空闲，但不表示外部效果或原意图已经核对；`writer.json` 的未完成状态继续阻止接管。旧任务实体缺失时，内部进程门禁仍通过写入历史寻找原 Worker，不以任务删除推断原进程已停止。

## 文件与路径核验

锁回执保存 fileKey 与随机 lockMarker。Unix 使用文件系统提供的 NIO 文件身份和大小关联实际路径，只从原持锁描述符读取 marker；路径核对不另开并关闭同一锁文件，避免解除原 POSIX 文件锁。Windows 在 NIO fileKey 为空时，通过原生属性句柄读取卷编号和 128 位文件编号，拒绝重解析点和目录。Windows FileChannel 使用 NOSHARE_DELETE，在持有期间拒绝删除/改名访问。

取得锁前后、每次借用和恢复释放，都核对原路径、文件身份、marker 与清单。清单被替换、原锁缺失、目录解析改变或未完成准备不自动修复。已取得成功 grant 的旧归属不能用“原锁/清单不存在”或外国清单来确认释放。

这提供工作台执行者之间的根归属检查；外部程序、原生 CLI 和用户编辑仍需各自的权限与版本边界。当前目录执行、不同核心数据目录共享源现场的协调、已读文件版本保护和完整 worktree 归档恢复继续实施。

## 核对、历史与导出

UNKNOWN 任务沿用 `POST /api/tasks/{id}/reconcile`，要求 `processesStopped:true`、`sideEffectsChecked:true` 和非空 note。先按[进程恢复协议](./process-recovery-contract.md)核验原 PID/出生与进程覆盖；仍存活、无法核验或覆盖未确认时拒绝任务核对。真实释放回执与用户依据分别保存，原 UNKNOWN、原请求、根尝试和累计预算保持原事实。下一次显式恢复生成新的根与写入编号。

`GET /api/tasks/{id}/workspace-write-leases?before=0&limit=30&state=` 需要核心访问令牌，返回 `{leases,cursor,hasMore}`。limit 在 1–100 之间，before 为非负整数；state 可为空或 PREPARED/HELD/RELEASED/UNKNOWN，非法游标和状态返回 400，任务不存在返回 404。按原持久行序倒序读取，游标翻页和状态筛选不混入其他任务。

任务详情返回当前 `workspaceWrite` 与读取时的 `workspaceWriteHistoryPresent`；界面在“Worker 执行”页显示状态、是否已释放、原目录/根/动作/Worker/核心身份、grant 和核对依据，历史轮询保留所选游标。所有 note 和路径作为纯文本展示。

ZIP 新增完整 `workspace-write-leases.json`，与 task、task-executions、dispatch-actions、worker-leases、model-requests、agent-runs、operations、证据及 MCP 原来源一起保留。导出等待根实际收尾；未知历史可在结束回执保存后导出，不把“能导出”解释为已核对效果。

## 验证入口

SQLite/实际文件锁回归覆盖根领取事务、唯一约束、HELD 与 ACK 原子保存、未知历史、新编号、路径别名和替换拒绝。实际 ProcessWorkspaceRuntime 回归使用原独立 JVM、Git、HTTP、Node 和第二 JVM 锁竞争；释放门只延迟生产 native release，用原持久心跳序号和 RPC 线程现场检查收尾及迟到暂停/取消。

`npm run test:workspace-writes` 使用普通最终 JarLauncher、生产 Web、真实文件工具和四个 SQLite 回执故障窗口（两策略 × grant/release）。报告保存原 Jar/Web/JNA 摘要、一次编译的夹具、实际 PID/出生、原 UNKNOWN、新尝试、READY owner 和两尝试 ZIP；结果按平台写入 `docs/qa/workspace-writes-<platform>.json`。每次失败现场单独保留；最终结果以[实施与验收记录](./implementation-status.md)为准。

`npm run test:bundled-writer` 在实际发行目录的内置Java中加载上述一次编译夹具与准确最终Jar类，独立数据目录内核验实际文件身份、授予、借用期间拒绝提前释放、正常释放与原探针停止。逐项核对包内核心、Java、JNA原Jar及Apache-2.0许可摘要，结果写入 `docs/qa/bundled-workspace-writer-<platform>.json`。该入口不调用编译器，不重复整套根任务或数据库故障验收。
