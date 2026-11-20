# 子任务准备失联与终态保全

本轮基线 `a66838039c1cc3065dedd1daf1f014d067442587`。来自 Windows CI Run `44503691251` attempt 1 的失败触发审查；原始失联原因仍未确定，后续重跑成功不能将其归因为平台时序。

## 开源参考

重新获取并核对 [Temporal Java HeartbeatContextImpl](https://github.com/temporalio/sdk-java/blob/112769ca18aa413758afd6b002aee2390430d435/temporal-sdk/src/main/java/io/temporal/internal/activity/HeartbeatContextImpl.java)，固定提交 `112769ca18aa413758afd6b002aee2390430d435`，Git blob `fba3ec29afff00a7f92ac7a7d459835b42c00dd3`，SHA-256 `8682e0fba884ea38df9ee8842c578d61d17572a2ab0eb7591d5698142d7f92b5`。源码的 `sendHeartbeatRequest` 分别保存取消、重置、暂停、归属不存在和完成回执失败，调用者读取已观察到的状态。下载字节与来源回执保存在 `.runtime/research-team-preparation-20261119/`。

本项目沿用已有核心独占账本：收尾先读取持久操作状态，已知 `UNKNOWN` 优先于普通本地异常分类；收尾期间再次失联，也以最终持久状态为准。文件准备与已承诺模型请求均不自动重试。没有复制上游代码或增加依赖。

## 原始证据与确定边界

失败 Windows job `135489669766` 的唯一失败是 `TeamExecutionTest.actualDelegationSharesRootBudgetAndDependencySnapshotBeforeGuardedAdoption`。证据 artifact `14520288008` 的完整 ZIP 已下载并核对 SHA-256 `e71d4abcd56afd60c9a92e4631d3794730f6f27863b6f7c23d1fb0b0f50b647d`；虽然收集时 Run 已为 attempt 2，该 artifact 属于首次失败。

夹具保留有三个缺口：核心锁与 SQLite 共享内存被锁定，WAL 在复制期间变化。副本的 `quick_check` 成功不能证明它是原子快照。依赖现场文件已有 `made\n`，其准备操作与租约是 `UNKNOWN`；复制的数据库还能观察到子运行后来以 `FAILED` 结束。这条线索需要独立复现，不能用于推断最初 Worker 失联的根因。

新增真实 Git/HTTP/独立 JVM 回归，在 Worker 完成文件准备后、整次准备确认前强停原 PID，先等待原操作与租约封闭为 `UNKNOWN`，再让协调器处理异常。修复前依赖准备场景准确失败于 `expected UNKNOWN but was FAILED`，完整源码快照、JUnit 与日志保留在 `.runtime/alpha23-team-dependency-loss-before-fix-63994c6cd72e4f1995fa804e070f5e1a/`。该确定复现揭示收尾判断只识别 `DISPATCHED`，遗漏已经被失联处理改为 `UNKNOWN` 的操作；相同判断也存在于初始快照准备路径。

回归覆盖初始快照及依赖整合两条路径：子任务与 AgentRun 均应保留 `UNKNOWN`，原准备仅派发一次，未开始的子模型没有请求，预算保留；重开核心后不自动重放，恢复要求先核对未知效果。根现场与用户原仓库仍保留原内容，独立准备现场的实际写入不能记成未执行。

## 本机回归与测试阶段同步

两条强停场景的修复前原失败保存在 `.runtime/alpha23-team-preparation-loss-before-fix-88854f072852429293d43f15cf46a2d0/`；修复后定向 **2/2** 通过，源码摘要保持，原报告见 `.runtime/alpha23-team-preparation-loss-fixed-b8d0d417f64840c0964c8577af8e2adf/`。独立代码审查未发现阻断问题；测试先使操作持久化为 `UNKNOWN`，收尾期间再次失联的分支经源码审查，尚未单独强制该精确窗口。

随后八个套件 **65 项、64 通过、1 失败、0 错误/跳过**，源码摘要保持。新增两条场景均通过；唯一失败是既有 `disablingDefinitionStopsPendingChildWithoutChangingItsFixedVersionOrRetrying`，批准后原 20 秒内尚未收到子请求，停用定义的动作未执行。原回执及失败现场保存在 `.runtime/alpha23-team-preparation-regression-c701f39e7fe6461b977a2b01f7d9a806/`，其中 `definition-withdrawal-original-failure` 的 facts/threads 原字节 SHA-256 分别为 `57965ba24fff905c8bc231524073273c5dd27d0684fae5d7518333105b531a10`、`02eb9c8bfb7601c287ae938028674008e4550cb28d4d758ce91163d2d2547da1`。

该时刻没有 AgentRun、子任务或模型请求，根任务仍为 `RUNNING`。工作区准备从 `13:29:35.780783Z` 到 `13:29:49.523798200Z`；基线验证于 `13:29:53.307745900Z` 确认后，`13:29:55.762895800Z` 的原虚拟线程仍在 `WorkspaceCheckpointArchive.capture → WorkspaceRuntime.git → ProcessImpl.waitFor`。这些事实证明原等待窗包含了根准备与检查点捕获，不能证明不存在性能缺陷，也不重建历次缺少现场的超时原因。

该用例验证在途子请求的定义撤回，现先等待父 `INVESTIGATE` 首轮真实 HTTP 到达，准备门禁上限 60 秒，再沿用子请求 20 秒和在途 HTTP 15 秒窗口。父请求在 fixture 中仍先核对持久请求、Worker 身份及预算。此改动明确改变了原批准到子请求的端到端计时；产品超时、撤权、固定版本、停止后零新增请求及容量断言不变。另一独立审查确认该同步点不会跳过实际 Worker 准备，父子互锁仍会有界失败。最终同源回归结果单独记录，不回写上述原失败。

第二次八套件回归为 **65 项、63 通过、2 失败、0 错误/跳过**，源码摘要保持；目录为 `.runtime/alpha23-team-preparation-final-regression-1344195799f64980a826a75693e9e9cb/`。定义撤回与新增两条强停参数均通过，失败换为既有独立提案根完成等待和在途子请求取消。独立提案在批准后的原 60 秒内未满足根完成条件，原失败文字显示步骤已完成、验证与审查通过，但根任务仍为 `RUNNING`。稍后保存的非原子副本读到批准后约 60.204 秒转为 `AWAITING_ACCEPTANCE`，根执行仍无 `endedAt`；这不能记为原测试通过或根收尾已经结束。取消用例则在批准后的原 20 秒内尚未收到子请求，取消动作未执行；副本显示父请求在批准约 16.5 秒后开始，子快照约 17 秒后派发，不能把原剩余约三秒等同于完整子准备窗口。

两份原夹具及只读检查结果分别保存在该目录的 `checkpoint-evidence-test-3503ce50-6fae-426e-a092-548a2ab51977/`、`checkpoint-evidence-test-76ff3b6e-5fdd-4668-ba87-1b40dc4b5c99/`。检查只打开另外复制的 SQLite，保留原字节摘要不变；原保存分别存在两个和三个缺口，后者包含 WAL 在复制期间变化。副本 `quick_check` 成功不提供原子快照保证，不能据此确定性能、死锁或旧 CI 失联根因。

取消用例现与定义撤回一样，先以 60 秒上限等待父 `INVESTIGATE` 首轮真实 HTTP，再沿用子请求 20 秒及在途 15 秒窗口，并在失败时保存事实与线程。独立提案则先以 60 秒上限等待实际首轮 `PROPOSAL` HTTP，再沿用根完成 60 秒窗口；完成仍联合要求 `AWAITING_ACCEPTANCE` 与根执行 `endedAt`，广播隔离、隐藏工具拒绝及提案数量断言保留。这两项也改变了批准到子请求或根结束的总计时，均不是产品超时变更。独立只读审查支持这些阶段边界；最终回归须在四份变更 Java 源码冻结后单独记录。

随后加入原 Worker 错误分类修复及通信/进程套件的第三次较广回归为 **79 项、77 通过、2 失败、0 错误/跳过**，137 项源码清单前后保持。目录为 `.runtime/alpha23-preparation-and-worker-error-final-8c1881c373274fbb9f39ae8de1b8c8c5/`；目录中的 `final` 是当时标签，不代表通过。新增准备强停参数、两项错误分类、前三处同步用例，以及 Team 以外全部 **65 项**通过。两项失败分别是 `actualDelegationSharesRootBudgetAndDependencySnapshotBeforeGuardedAdoption` 在批准后的原 60 秒内未完成根收尾，以及 `nodeRevocationStopsTheActualChildRequestAndRestartPreservesUnknownLeases` 在撤权动作前未于原 15 秒子请求窗口收到实际子 HTTP。

两份原失败夹具保存在该目录的 `checkpoint-evidence-test-1a43bdba-d547-4348-aebd-f3dc83958166/` 与 `checkpoint-evidence-test-69061aef-cab1-4c1e-97d0-150564e95bed/`；节点撤权另保存 `team-child-start-failure-188212231701500/facts.json` 与 `threads.json`。分别有核心锁一个缺口、核心锁及写入锁两个缺口；只在另复制的数据库上检查，原 SQLite/WAL/SHM 字节摘要保持。委派用例的稍后副本于批准后约 60.3485 秒出现 READY 事件、62.2917 秒更新任务，根执行仍为 `RUNNING`、没有 `endedAt`，不能据此将原超时改为通过。其首个父请求在批准约 15.8032 秒后保存。

节点撤权的原 facts 显示：批准时间为 `14:20:27.320335400Z`，工作区在 `14:20:34.405404600Z` 确认，父模型操作于 `14:20:42.941383400Z` 创建，实际父请求在 `14:20:43.564286Z` 保存，子快照于 `14:20:49.208350Z` 确认，子模型操作于 `14:20:49.230369900Z` 派发；原 facts 尚无子模型请求。线程中父/子原调用仍等待回执，子 MODEL_BEGIN 回调经 `RequestObservation.begin → ExecutionNodes.canonical`；稍后非原子副本才读到 `14:20:49.868583800Z` 的子请求。原 `RUNNING` 状态并不表示首个父 HTTP 已到达，不能把该状态当成已排除根准备的门禁。

据此，节点撤权改为先以 60 秒上限等待实际首轮父 `INVESTIGATE` HTTP，立即核对根仍为 `RUNNING`，再保留子请求 15 秒与实际在途 15 秒窗口；替换原 `awaitTask(RUNNING)`，不叠加另一个根状态等待。委派采纳用例则先以 60 秒上限等待首轮父 `INVESTIGATE` HTTP，再沿用根完成 60 秒及 `endedAt` 联合条件。后者把批准到根完成的测试等待上限从原 60 秒改为两段至多 120 秒；前者的阶段边界也有改变。独立审查未发现阻断，全部撤权/预算/依赖/采纳/重开断言保留。这是测试阶段同步调整，尚未证明产品不存在性能或清理缺陷，仍不确定旧 CI 失联根因。

第四轮只运行 `TeamExecutionTest`，结果为 **14 项、13 通过、1 失败、0 错误/跳过**，退出码 1；137 项生产及测试源码与 `core/pom.xml` 清单前后保持。原回执、冻结源码、JUnit 与日志保存在 `.runtime/alpha23-team-phase-final-00048df9df504565bd2533dda470808e/`，其中 `final` 仍只是目录标签。JUnit XML 的 SHA-256 为 `77d008b4ae6ad487e9bc1014d1fa273dcd358a9d80b9c9dddc6a75f3379b1c3e`。上述定义撤回、在途取消、独立提案、完整委派采纳和节点撤权五个阶段同步用例均通过，依赖整合强停参数 `[2] SUBAGENT_DEPENDENCIES` 也通过；唯一失败是 `workerLossDuringChildPreparationPreservesUnknownAndDoesNotReplay(String)[1]`，对应 `SUBAGENT_SNAPSHOT`。本轮虽然请求 Maven `package`，但测试阶段失败，没有产出本轮成功打包证据，不能引用旧 JAR 代替。

原测试先等到根任务 `UNKNOWN`，再沿用原 15 秒等待子任务结束及模型资源释放，随后在冻结的 `TeamExecutionTest.java:350` 因 `child.containsKey("endedAt")` 为 false 失败。原夹具于 `2026-11-19T14:38:42.405282600Z` 记录，归档为该轮的 `checkpoint-evidence-test-e984e34c-3263-4fb1-aa15-f564c2d45dfe/`。其 `inspection.json` 显示根任务与根执行均为 `UNKNOWN`，根执行已在 `14:38:27.617330900Z` 写入 `endedAt`；原 Worker epoch 2、PID 9832 为 `LOST`，`processStopFacts` 中 `processStopped=true`、`treeStopped=true`、`unconfirmedProcesses=[]`，观测覆盖仍为 `OBSERVED_ONLY`。这些进程停止事实不确认原效果成功，Worker 的 `stopState` 仍为 `UNKNOWN`。

同一副本中，子 `SUBAGENT_SNAPSHOT` 操作与原租约已为 `UNKNOWN`，租约已有 `endedAt`，但对应子任务及 `SUBAGENT` AgentRun 仍为 `RUNNING`，两者均无 `endedAt`，也没有该子 AgentRun 的模型请求。这是强停后子执行终态保全尚有缺口的失败证据，五个阶段同步用例通过不能覆盖它，也不能据此将问题归为 HTTP 到达等待。是否存在 `Error` 绕过收尾等路径仍属待证假说；本次归档未确定具体异常类别或首因并发先后，后续诊断与修复须单独验证。

本次原夹具成功保存 136 个文件，核心锁 `data\.core.lock` 有一个复制缺口，`completeCopy=false`；另复制数据库的 `quick_check` 返回 `ok`，仍不构成原子或完整现场。保留清单与只读检查文件的 SHA-256 分别为 `ba5d63bfed8af10410352a44dc2725d1c81045e8a7a87b7121144ba4fae9eaea`、`5adb236c1e6af277e3eb5e989405d3af7047da31cf12f37b3617b852e605c6c4`。本轮失败独立保留，尚无修复后最终 Team 通过回执；本地 QA 终态生成继续暂停，不回写前三轮及定向回归的原结果。

## 故障注入的回调中断诊断

先只增加注入阶段及意外异常记录，保留原 `assertDoesNotThrow`、强停与等待逻辑，定向 **2/2** 通过，回执为 `.runtime/alpha23-preparation-injection-diagnostic-b27179286b8447339eb1e016e6f553ff/receipt.json`。该次没有重现第四轮，不能据此排除原缺陷。

随后在快照回调强停原 PID 后、等待退出之前加入一个最长 10 秒的空 latch 等待，不主动设置线程中断标志，不改生产源码；这是使真实 RPC 关闭中断能够在已知等待点被观测的受控实验。原 `WorkerRpc.fail` 在封闭连接、记录失联并完成等待回执后调用 `handlers.shutdownNow()`，中断仍在该核心 RPC handler 中运行的快照 hook。实验为 **2 项、1 通过、1 失败、0 错误/跳过**，源码清单前后保持；快照准确记录 `AssertionFailedError: Unexpected exception thrown: java.lang.InterruptedException`，cause 为 `InterruptedException`。`Error` 绕过 `delegate` 与 `Session.invoke` 的 `catch(Exception)`，最外 RPC handler 收走它，子任务仍缺少结束记录。原日志、JUnit 与源码在 `.runtime/alpha23-preparation-callback-interrupt-repro-92f9559e20664d98859158f07b4fcd33/`，该目录同时保留 `team-child-start-failure-190910501073700/facts.json`、`threads.json` 及带核心锁缺口的原夹具。实验只证明这条旁路可达；第四轮没有实际 Throwable 记录，仍不能认定其实际异常或最初 CI 根因。

据此仅修正测试故障注入：原 PID 退出与指定准备操作持久 `UNKNOWN` 各沿用一次建立的 10 秒截止时间，遇到中断只记录、继续剩余期限，最终恢复线程中断标志。只有原退出 future 已完成、原 PID 已停止且指定操作已亲见为 `UNKNOWN`，才以预定的普通 `IllegalStateException` 进入协调器收尾。hook 内不再运行 JUnit 断言，实际文件内容、依赖 ACK、两个前置边界及意外 Throwable 均交由主测试线程强断言；意外异常不能因为清理继续而被记成通过。原根等待、15 秒子任务结束/模型资源释放窗口、预算和重开零重放断言保留。没有扩大生产 `catch` 为 `Throwable`，也没有延长产品或收尾期限。独立只读审查未发现阻断；修正后中断循环的精确并发顺序尚未另做强制验收。

将新注入用于旧协调器再次做负对照，**2 项均按预期失败，0 错误/跳过**；快照仍缺子任务 `endedAt`，依赖子任务仍是 `FAILED` 而非 `UNKNOWN`。主线程对注入异常、原 PID 退出、准备先为 UNKNOWN、实际内容的检查均先通过，说明这两项失败仍检出原有产品收尾缺陷。冻结源码、回执、JUnit 与原现场保存在 `.runtime/alpha23-preparation-robust-injection-before-fix-36bcf3fa21434bf5bfd9e1f342bfc1a6/`；随后按保存字节恢复当前协调器，SHA-256 为 `178b3462a2708b0f6f4af3b84114cafeef83426861533eb55edae16f85bc70a0`。

最终定向验证为 `TeamExecutionTest` 14 项与 `ProcessWorkspaceRuntimeTest` 10 项合计 24 项，使用 Maven `package`，目录 `.runtime/alpha23-preparation-worker-final-f9bad9f1b48c4a8fad87f3fc9c0d140b/`；结果待实际结束后记录。第三轮其余生产及测试输入保持的比较、全部历史失败与最终 JAR 摘要由本地 QA 另行核验，不以历史 79 项失败回执冒充最终全量通过。

## 2026-11-20 续跑及执行字节码核对

上述最终目录实际被中断，没有 Team XML 或打包终态。续跑 `.runtime/alpha23-recovery-resumed-34f35dc1e2df4565b084d77fc957d9ab/` 为 24 项、22 通过、2 失败；两项均为准备强停参数。独立检查真实 class 发现它们仍是负对照旧实现：外部类 SHA-256 `f96df5aea3d912125251ec25d6c24b3a8934c35e34f0eb4723a6986b2e84400f`，Session 为 `260cf7a776440799b26e76cf0b09301bd1beb0dfdaa8860c917b9c3decf89757`，反汇编不含 `finishFailedPreparation`，Session 仍只判断 DISPATCHED。源码恢复保留了早于这些 class 的时间，Maven 两次均报告无需编译。两次运行的源码摘要不能证明所执行的实现，续跑失败不作为当前修复失败或新的中断路径证据；原始失败仍完整保留。

在上述续跑目录归档旧字节码、反汇编、编译输出及失败现场后，移开 classes、test-classes、maven-status，再执行新定向 package。`.runtime/alpha23-recovery-fresh-classes-7c89963e336b48a0afdd8b6968422b99/` 实际重新编译 69 份生产、51 份测试源码，**24/24 通过，零失败、错误或跳过**，成功产生新的生产 JAR。反汇编确认修复 helper 存在，并逐字节核对 JAR 内 Team 外部类、Session 及 ProcessWorkspaceRuntime 与本次编译输出相同。详细源码、历史范围、class 与 JAR 摘要见[正式回执](../qa/alpha23-team-preparation-recovery-win32.json)。该记录只证明最终冻结源码的定向范围，未重分类历史失败，也不是完整核心或三端发布验收。
