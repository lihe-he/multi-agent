# Worker 派发前取消与原领取释放

## 原始失败与现场

alpha.29 诊断提交 `3497b2a41ea1dec5f0ea7014bf85557a57c64fe2` 的 [CI Run 45738852507](https://github.com/lihe-he/multi-agent/actions/runs/45738852507) 在 macOS 的核心测试中失败一项：`WorkflowExecutionTest.pauseBeforeTheNextModelRequestReusesOnlyTheCompletedNodeAndKeepsTheRootBudget`。首节点成功，第二个写入节点暂停；根任务、根动作和写入保护变为 `UNKNOWN`。核心共 770 项、1 失败、0 错误、29 条件跳过，后续步骤没有执行。

已下载原 artifact `15081425543`，10,752,193 字节，完整 ZIP 的 SHA-256 `007eec2ff477c0ff05f8be42b8025adbb216d552fecbc9d7a0c98f10ac364d97` 与 GitHub 元数据相符。测试失败后的保存回调记录 `completeCopy=true`、`copyGaps=[]`；读取 SQLite 前复制数据库、WAL 与 SHM，保留原件。原现场显示：

- 第二节点的 `WORKFLOW_MODEL` 操作已 `CANCELLED`，没有该节点的模型请求，根累计预算仍为 1。
- 节点为 `PAUSED`，原因是“根任务、授权或原 Worker 归属已经停止”。
- Worker 为 `LOST`，写入保护为 `UNKNOWN`、`protectionReleased=false`；两者都记录 `WORKER_RELEASE_RECEIPT_MISSING`。
- 原 `writer.json` 仍为 `HELD`、`lockValid=true`。停止节点的完成回执不能据此证明根写入保护已经释放。

## 状态转换缺口

核心取得原领取后，`ProcessWorkspaceRuntime.invoke` 首次检查取消和授权，递增本领取调用序号，再由 `Invocation.openStageInput` 复查取消和授权，最后才执行 `WorkerProcess.invoke`。暂停可以发生在两次检查之间。此时第二次检查抛出取消异常，实际 `INVOKE` 尚未发送，但调用序号已经大于零。

原 `releaseClaim` 以序号大于零判断是否向 Worker 发送 `RELEASE`。Worker 只在收到 `INVOKE` 时登记领取，因此会拒绝这个从未收到的领取。核心把该释放失败按真实失联处理，记录 `WORKER_RELEASE_RECEIPT_MISSING` 并封闭 Worker；根随后按已有未知效果门禁保留写入归属。问题在于派发前后的区分，失联后的保护行为应保留。

## 修正规则与验收

每个 Claim 单独保存“已进入派发尝试”的单调标记，在阶段输入准备成功后、调用 `WorkerProcess.invoke` 前置位。调用序号继续递增并防止重放；它不再作为远端登记领取的证据。

首次调用在本地准备阶段取消或失败时，收尾无需向未收到调用的 Worker 发释放请求。同一领取曾经派发过的任一调用、远端在模型请求前拒绝、实际发送失败或丢失回执，都仍需沿原释放或失联规则处理。后续调用准备失败不能清除该标记；不能依据模型调用数为零或缺少成功结果跳过远端释放。

确定性回归使用真实独立 Worker 与原领取，在两次检查之间切换取消条件。必须分别核对首次准备取消和已有调用后的准备取消，保持零新增 HTTP、模型记录与预算，确认 Worker 可继续执行；已派发但丢回执、活动调用尚未结束及真实释放拒绝的既有门禁继续验收。准确结果记录在 [alpha.29 回执](../qa/alpha29-linux-native-sandbox.json)，本研究不把单项本机复跑作为三平台完整验收。

旧生产从 `c0346ed` 冻结并清洁编译，三个新场景中本地取消和输入准备异常两项均按预期失败，发送帧序号由应有的 3 变为 4；已有调用后的同领取取消对照通过。另一个保留旧 classes 的诊断实际记录：两次取消检查、零 INVOKE、零 HTTP 和模型记录，收尾多发一个 RELEASE，随后同一 Worker 以 `WORKER_RELEASE_RECEIPT_MISSING` 失效。诊断未修改生产实现，也未重放原 CI 任务。

修复后的五组独立 Linux 清洁回归 **44/44**、独立 Linux 沙箱必验 **25/25** 通过；两份 152 文件源码快照与最终工作区逐字节一致。新增五个场景包括远端 CHECK_CURRENT 的 CANCELLED 和 VALIDATION：即使尚无模型请求，也必须释放已经登记的领取。Windows Worker 四组 **36/36** 以及原 Workflow 暂停单项 **1/1** 通过。首次扩展运行缺 Node 的七项失败原报告保留，补齐经过官方摘要核对的 Linux Node 22.14.0 后从新目录清洁复跑。旧提交 `c0346ed` 的 Windows CI 随后也在同一暂停用例失败，Ubuntu/macOS 成功；该历史结果与修复后准确提交的 CI 分别记录。
