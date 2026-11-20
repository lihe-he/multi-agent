# Worker 原错误回执与结果缺失的诊断边界

日期：2026-11-19。最初在基线 `a66838039c1cc3065dedd1daf1f014d067442587` 上只读检查执行路径并新增本文件；该阶段没有改动 `core/src`、运行测试或执行故障注入。随后以新增的真实 Worker 回归在旧生产实现上复现两项诊断失败，再实施下面的最小分类改动。修复后 `ProcessWorkspaceRuntimeTest` 的 10 项全部通过；第三轮 79 项回归有 2 项 Team 等待失败，第四轮阶段同步后的 Team 14 项仍有 1 项失败，不能称整轮通过。随后诊断与受控中断实验的结果见下；测试钩子调整后的 Team 14 项与 Process 10 项共 24 项/package 尚待最终回执。首次 Windows CI 的 Worker 失联根因仍未确定，不能从当前代码路径推断旧失败一定收到过远端错误。

## 已确定的调用事实

`ProcessWorkspaceRuntime.invoke` 为原领取生成本地 `callId`、递增 `callSequence`，把任务、操作、租约及参数经 `WorkerProcess.invoke` 交给原独立 JVM。成功返回经过 `callId`、Worker/epoch、PID/出生时间、原租约和整次写入编号核验；只有完整成功 envelope 才进入后续实际操作收尾。

Worker 侧 `WorkerProcessMain.invoke` 先核对绑定、原领取、方法及目标，再执行 `CHECK_CURRENT` 回调和实际现场动作。`WorkerRpc.accept` 捕获抛出的异常，发送带原 RPC `sequence` 的 `error`，只包含 `category`、`status` 和最多 2000 字符的安全 `message`。核心 `WorkerRpc.reply` 从 pending 中取回同一 RPC sequence，验证分类、400–599 状态及文本长度，再以 `RemoteFailure` 完成原 future。

错误 envelope **没有**成功结果所含的 `callId`、Worker PID、epoch 和租约回执字段。因此，这条路径只能证明原私有 RPC 的相应请求收到了合规错误，不能据此称完整领取效果回执已经核验，也不能证明错误发生在任何文件效果之前。

最初读取的修复前分类如下，并非所有错误都被统一处理：

| `ExecutionException` 的实际 cause | `ProcessWorkspaceRuntime.invoke` 修复前行为 |
| --- | --- |
| `RemoteFailure(VALIDATION, 400, …)` | 抛 `IllegalArgumentException`，不因该分支主动 fence 原 Worker |
| `RemoteFailure(CANCELLED, 500, …)` | 抛 `CancellationException`；这里 status=500 来自通用异常映射，不能按 HTTP 服务端故障解释 |
| `RemoteFailure(API, <500, …)` | 抛 `ApiException`，检查点恢复另映射 `CheckpointRestoreRejected` |
| `RemoteFailure(UNKNOWN, 500, …)` 或 `RemoteFailure(API, >=500, …)` | 与真正失去结果一起进入 `WORKER_RESULT_MISSING` |
| 原通道 IOException / EOF 等非 `RemoteFailure` cause | `WORKER_RESULT_MISSING`，或已经由原进程/通道观察抢先记录其他失联原因 |

已接收错误与没有接收结果这两种不同事实，在最后两个分支失去了区分。`WorkerProcess.signalLoss` 只接受首次失效原因；本机 Worker 账本另有独立的心跳观察和失效封闭。因此，先保存的失效原因不因后到错误或进程退出而覆盖，首因记录表示当时的失效观察，不能直接当作物理根因。

## 本轮已实施的最小诊断

生产改动只在 `ProcessWorkspaceRuntime.invoke` 增加两行，不改私有帧协议或数据库 schema：保留前面三类现有语义，在剩余 `RemoteFailure` 分支使用由已验证枚举和整数构成的原因，例如 `WORKER_REMOTE_ERROR_UNKNOWN_500` 或 `WORKER_REMOTE_ERROR_API_503`；随后仍停止原 Worker，等待其实际进程及已观察后代停止，并抛“独立 Worker 返回错误回执（分类/状态）；原调用结果未知且不自动重试”的固定文案。该分支不解析远端 message，也不附加原 cause。非远端错误 cause 继续使用 `WORKER_RESULT_MISSING`。

该分支先保存失效时，`LocalExecutionWorker.lost` 把 fence 原因经 `WorkerLedger.stop` 保存至原 Worker、仍在途的租约/操作和任务的 UNKNOWN 记录。独立观察线程也可能先保存 `WORKER_HEARTBEAT_EXPIRED`；此时仍保留该首因，公开异常里的原错误分类也继续保留。不能为了显示后到诊断覆盖先前事实。新增测试允许这两条实际持久路径，UNKNOWN 用例另检查重开前后首因原文一致；精确的并发先后尚未另做定向强制。以后如需要保存并发调用的次要诊断，可另行追加绑定本地 call/operation 的事件，不把它冒充完整效果 ACK。

该增量保留以下行为，验证结果另行记录：

- 收到 `UNKNOWN` 或服务端错误仍不推断未执行；原操作、模型缺失回执、预算和独立现场效果继续保留，禁止自动重放。
- 仍 fence 原 Worker，仍等待原进程树停止后释放原资源；不因此宣布 Worker 可继续使用，也不生成替代进程。
- 已经收到并保存成功的模型请求继续保持原终态。`Invocation` 的 `requestInFlight` 收尾只为尚未结束的模型观测调用 `observer.end(UNKNOWN)`；不要用外围 Worker 错误改写已保存模型成功。
- `VALIDATION`、`CANCELLED`、`API<500` 以及 `RESTORE_CHECKPOINT` 的既有映射继续各自处理，不能把诊断补充分支放在它们之前。

若还要保留异常类型，应作为另一项有界协议增量：Worker 只发可选的安全类型标识，核心验证长度/字符集后保存。现有安全文案中包含 simpleName，但不应通过解析本地化显示文字创建结构化类型，更不能把原 `Throwable`、`getMessage()`、堆栈或 cause 对象直接附到公开异常上。该类型增量并非区分“已收错误/无回执”所必需。

## 脱敏与持久化边界

`WorkerProcessMain` 给 `WorkerRpc` 的 `safeError` 仅生成异常 simpleName 的固定文案，明确不附带原参数、原异常消息或因果链。核心回调使用 `WorkerRpc` 默认安全分类文案。因此本次诊断分支只使用已验证的 `category`、`status`，不打印 RPC 帧、claim token、API key、提示词、目录内容或原错误消息。

外围异常保持固定文本和有界代码即可。`RequestObservation.end` 的 `reason`、`errorType`、`errorChain` 等字段会在核心先经 `catalog.redact` 后截断到 6000 字符，并由 `ExecutionStore.endModelRequest` 的允许字段保存；但原准备错误没有模型请求，不应为诊断凭空创建模型记录。实际模型 HTTP 的 `errorChain` 是另一条已经实现的类型链路径，不能把 HTTP 错误与 Worker RPC 错误混为一类。

## 真实 Worker 回归范围与结果

1. **收到原 UNKNOWN 错误。** 使用 `ProcessWorkspaceRuntimeTest` 现有真实 Git/SQLite/独立 JVM 夹具，准备根现场后创建 `REVIEW_SNAPSHOT` 操作并以原 Worker 派发到该 workspace，调用 `runtime.reviewCopy(workspace, "root", "deliberately-stale-version")`。`subjectId=root` 与原领取匹配；Worker 的 `WorkspaceRuntime.reviewCopy` 会实际计算 fingerprint，再因版本不匹配抛 `IllegalStateException`，经原通道返回 `UNKNOWN/500`。检查新诊断原因、原操作/租约/根 UNKNOWN、未产生任何模型请求或预算、原进程停止、不新建 Worker、不重放及重开后原事实保留。这是确定性的真实错误回执触发，不靠替代 runtime 或 mock Worker。
2. **收到 API 服务端错误。** 为同一真实 Worker 的测试 `AgentToolSession.checkCurrent` 提供受控故障，在首次实际 `CHECK_CURRENT` 核心回调抛 `ApiException(503, "private-worker-check-fault-message")`。`WorkerProcessMain.invoke` 在执行现场动作前进行该回调，收到核心安全 `API/503` 后再次从原 Worker 返回结构化 `API/503`；核对独立诊断、零现场动作/模型请求，以及 secret 不出现在外层错误、Worker/任务/租约或事件。这里注入的是核心回调服务端故障，必须如实标注；不能称为真实提供商 HTTP 503。实际模型 HTTP 500 通常返回正常 `value` 中的 FAILED，**不会**覆盖这里的 RPC `RemoteFailure` 分支。
3. **真正缺失原结果。** 保留已有 `processLossKeepsTheOriginalInFlightRequestUnknownAndNeverStartsAnotherWorker`：实际 HTTP 在途时终止原 Worker PID，缺少原结果，原请求/预算保持 UNKNOWN，原资源在原进程停止后释放，禁止新建替代 Worker。原 Worker reason 允许先观察到 `WORKER_CHANNEL_LOST`、`WORKER_PROCESS_EXITED` 等真实失联代码，不能强迫竞争顺序等于 `WORKER_RESULT_MISSING`；应断言它没有被伪造为“已收远端错误”。
4. **原分类不回退。** 已有 `WorkerRpcTest.validationReplyKeepsItsCategoryWithoutPrivatePayloadOrExceptionCause`、实际 Worker 的错误 nonce/目标/重放拒绝和检查点拒绝映射继续验证。若新增结构化类型字段，再补错类型、超长、缺失字段与私有信息不回显；只有修改外围分类时不需要扩张为完整协议改造。

新增前两项回归先在旧生产实现上执行：**2 项均按预期失败，0 错误、0 跳过**，源码快照前后一致，退出码 1。两项都准确失败在外层异常文案：期望分别保留 `UNKNOWN/500`、`API/503`，实际均为“独立 Worker 未取得原调用的完整回执，结果未知且不自动重试”。这是修复前的反例，不能计作通过。原源码、报告和日志见[原始回执](../../.runtime/alpha23-worker-error-before-fix-5b94feea8aba4ba8aa69819104523c42/receipt.json)及同目录 `reports/`、`maven.log`。

修复后的第三轮综合回归于 22:08:38–22:29:58（+08:00）执行，原始回执为 **10 个套件、79 项，77 通过、2 失败、0 错误、0 跳过，退出码 1**；137 项源码记录的路径、字节数和 SHA-256 前后一致。该轮 `ProcessWorkspaceRuntimeTest` **10/10 通过**，包括新增的 UNKNOWN/500、API/503 两项和既有真实 PID 丢失用例；`WorkerRpcTest` 4/4、`WorkerProcessTest` 8/8、`WorkflowExecutionTest` 7/7 也通过。已直接核对 Process 套件 XML 及其 SHA-256 `d964c3d92e1ae1897db6cc5af0acb5618c89caa92fef40a40b921922411770c2`，与回执一致。原证据见[该轮回执](../../.runtime/alpha23-preparation-and-worker-error-final-8c1881c373274fbb9f39ae8de1b8c8c5/receipt.json)及同目录 `reports/`、`maven.log`。

该轮两项失败均在 `TeamExecutionTest`：`actualDelegationSharesRootBudgetAndDependencySnapshotBeforeGuardedAdoption` 在根等待 60 秒后仍未取得包含 `taskExecution.endedAt` 的完成边界；原错误显示根 RUNNING、14 次调用、审查已通过，后到副本的 READY 事件不能把原超时改成通过。`nodeRevocationStopsTheActualChildRequestAndRestartPreservesUnknownLeases` 在撤权前的子 HTTP 到达门禁 15 秒超时；原 RUNNING 状态并不等于父 HTTP 已到达。两份现场和复制缺口均保留，不从这些时序推断内部性能或原 CI 根因。随后只调整这两项测试的阶段计时起点：先以实际首轮 INVESTIGATE HTTP 到达为独立的 60 秒门禁，保留原根完成 60 秒及 `endedAt` 条件、子请求原有的 15 秒到达门禁及在途 15 秒窗口和全部业务断言；这会改变批准后的总时限，须单独记录。

第四轮阶段同步后的 [Team 14 项/package 回执](../../.runtime/alpha23-team-phase-final-00048df9df504565bd2533dda470808e/receipt.json)记录 **13 通过、1 失败、0 错误、0 跳过，退出码 1**，执行前后源码一致。该轮失败为 `workerLossDuringChildPreparationPreservesUnknownAndDoesNotReplay[SUBAGENT_SNAPSHOT]`：原 preparation 与根已为 UNKNOWN，但 15 秒收尾等待后 child 仍无 `endedAt`。这不是第三轮两项等待失败的同一断言，也不能据此称 79 项已全部通过。准备故障与等待现场的详细证据另见[准备阶段研究](./team-preparation-recovery-2026-11-19.md)。

随后仅为原注入增加阶段与 `Throwable` 采集，保留原 kill、`onExit`、轮询和 `assertDoesNotThrow` 逻辑；[诊断回执](../../.runtime/alpha23-preparation-injection-diagnostic-b27179286b8447339eb1e016e6f553ff/receipt.json)为 **2 项、0 失败、0 错误、0 跳过，退出码 0**，未复现。另一轮仅在 snapshot 测试钩子杀死原 Worker 后增加有界 latch 等待，由真实 `WorkerRpc.fail` 内的 `handlers.shutdownNow()` 中断回调，未主动中断线程或改动生产代码；[受控回执](../../.runtime/alpha23-preparation-callback-interrupt-repro-92f9559e20664d98859158f07b4fcd33/receipt.json)为 **2 项、1 失败、0 错误、0 跳过，退出码 1**，snapshot 准确采集到 `AssertionFailedError`，cause 为 `InterruptedException`。两轮执行前后各自源码快照一致。受控实验说明原 `assertDoesNotThrow` 可将关闭中断包装为 `Error`，绕过协调器的 `catch(Exception)`；它证明该旁路可达，第四轮未采集到实际 `Throwable`，其实际 cause 仍未知。

当前测试钩子已改为沿原 PID 退出 10 秒、UNKNOWN 10 秒期限处理关闭中断，恢复中断后抛普通异常；主测试线程另行断言注入失败记录、实际内容、原进程已停及 helper 前 UNKNOWN 的事实。原 15 秒收尾窗口保留；该调整后的 Team 14 项与 Process 10 项共 24 项/package 尚待最终回执，不能用前述诊断或受控实验替代最终验证。

当前新增回归对 Worker reason 保留原错误代码或抢先保存的 `WORKER_HEARTBEAT_EXPIRED`，不强迫观察线程顺序；UNKNOWN 用例重开后保留准确原 reason。未知错误回执发生在真实文件写入之后的完整组合仍需独立故障注入；版本不匹配触发只证明错误回执分类及其保守 UNKNOWN 行为，不能替代完整外部效果矩阵。

## 本次静态源码标识

以下 SHA-256 来自最初只读阶段实际读取的工作区文件，用于保留修改前输入。随后 `ProcessWorkspaceRuntime.java` 已实施上述分类改动，因此该行不是最终源码摘要；最终输入与校验结果以本轮回归快照为准。

| 文件 | SHA-256 |
| --- | --- |
| `ProcessWorkspaceRuntime.java` | `4168ac091632175ae6b96fd1420f1382bdbb5e97dd3acbaf96345bbf0bd7adef` |
| `WorkerRpc.java` | `08570f5e8a9f3174d4d355fa789293391082fdf273ad3334f9c45fd902bb8572` |
| `WorkerProcessMain.java` | `5f76194b04a92365d0feb86330e90370589195904f32ffb9e3e596e0b8d57aeb` |
| `WorkerProcess.java` | `5ad48e073f8a963dbd93513eccfa6dc2677ddf61ccb231bdabb49526756154aa` |
| `WorkerLedger.java` | `e6a55bd3eb9ea3ad4c1662b9a071b46f1b23859c183df86b38b7af997d8799ee` |
| `RequestObservation.java` | `adbd256bb9ca6eb97776727e0020bbaebc40dae3714f5e856c915cf6f0aa02e0` |

关键源码位置：`ProcessWorkspaceRuntime.invoke:198–266`、`WorkerRpc.accept/reply:96–136`、`WorkerProcessMain:52–55,111–136,403–423`、`WorkerProcess.signalLoss:194–201`、`WorkerLedger.stop/observeStopped:195–215`、`RequestObservation.end:57–68`。这些位置定位最初只读版本，后续实现可能移动行号。

## 2026-11-20 最终定向与字节码证据

前次最终目录被中断，续跑 24 项中两项 Team 失败。实际反汇编确认这两轮执行了未含准备修复的旧 Team 字节码；源码恢复的修改时间早于负对照编译产物，Maven 因此跳过重编译。它们不能用于判断当前 Team 修复，原日志、报告和 class 仍保存；Process 的原 10 项结果也不被扩大为整轮通过。

归档旧编译输出后重新编译、定向 package 为 **24/24 通过**，其中 Process **10/10**，零失败、错误或跳过。本次新 Team/Session/Process class 已与新 JAR 内实际字节逐项核对，源码前后保持。[最终定向及字节码回执](../qa/alpha23-team-preparation-recovery-win32.json)包括原失败、限制和 JAR 摘要，不把历史 79 项失败回执当作最终全量验证；原 CI Worker 失联首因仍未确定。
