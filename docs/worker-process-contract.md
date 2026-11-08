# 独立本机 Worker 进程约定

适用版本：0.2.0-alpha.10 起；alpha.11 增加跨核心会话的进程核验，SQLite schema 升为 5。公开 API 与私有 Worker 帧协议仍为 1。独立本机执行已接入生产入口，远程配对、网络重连和完整持久队列继续实施。验收记录见[实施状态](./implementation-status.md)。

## 执行边界

核心是唯一 SQLite owner，持有根预算、请求身份、授权版本、团队调度、资源容量与 MCP 传输。实际任务准备、审查及副任务复制、依赖整合与采纳、API/CLI 模型、文件工具和验证命令由同一独立 Java Worker 执行。项目扫描、检索、内容标识、差异、运行器能力探测和交付包读取仍在核心；这些元数据读取不代表 Worker 执行。

正常 Spring 入口使用 ProcessWorkspaceRuntime。核心用正在运行的 Java/JRE 启动子进程；打包 Jar 经内部入口直接进入 Worker，classpath 场景使用独立 main。子进程创建 WorkspaceRuntime，不启动 Spring 或打开 SQLite。直接构造基础运行时的内部测试仍声明 CORE_PROCESS。

核心通过实际 Process 句柄核验 HELLO 的 PID、进程出生时间和父 PID，再由原节点账本分配 Worker 编号与 epoch，完成一次 BIND/READY 后才能执行。公开节点声明 LOCAL / LOCAL_PROCESS；Worker 保存 corePid、parentPid 与实际子进程 PID。数据目录仍保存稳定节点编号，启动新核心会创建新会话和代次。

启动环境去除 WORKBENCH_*、JAVA_TOOL_OPTIONS、JDK_JAVA_OPTIONS 和 _JAVA_OPTIONS，仅显式传递 UTF-8 与有效 NIO 临时目录。核心配置的模型密钥经每次调用的私有帧交接，领取原凭据保存在内存，均不进入启动参数。HELLO 核验控制环境/JVM 选项环境/workbench.* 属性的布尔事实。Worker stdout 保留给协议，普通输出进入单独 stderr 管道并被排空，原始错误或私有帧不写入日志。

## 私有帧与调用

每帧是四字节 big-endian 长度与 UTF-8 JSON 对象，最大 8 MiB。拒绝负数、空帧、截断、尾随 JSON、重复字段和非对象。双向 REQUEST/REPLY 与 EVENT 共用有序写锁；每个方向的请求序号在写锁内分配并发送，接收方严格要求下一序号。每方向最多 128 个在途请求；不重试、重连或补发丢失调用。

读线程只路由，实际处理器使用独立虚拟线程，允许 Worker 等待核心团队工具时，核心再向 Worker 派发副任务复制或执行。不同调用分别同步保护预算观察者、消息游标和资源 token，心跳有独立线程。模型不能通过参数提供核心执行上下文。

阻塞回调、RPC/帧写入和资源等待使用 ReentrantLock/Condition，Java 21 的父调用等待不会持有 monitor 占满载体。MCP stdio 的原生管道请求/通知读写使用每传输一条平台线程，stderr 另有一条平台排空线程；传输仍受原工具/进程容量限制，虚拟线程只等待结果和取消，不直接阻塞在原生管道写入。停止确认前继续保留原容量。

核心先原子保存操作 DISPATCHED、租约 ACTIVE 与 AgentRun 来源，再交接原领取。私有领取固定 workerId、epoch、operationKind/operationId、taskId、agentRunId、节点/仓库/授权版本、workingDirectory、realWorkingDirectory、operationType、subjectId 和领取摘要。每次实际调用还有原 callId 与递增 callSequence；同一领取按顺序完成多次依赖整合与副本创建，不能并发或重放。

Worker 核对原随机领取凭据、操作允许的方法、任务/副任务编号与原目录解析目标。执行前必须向核心 CHECK_CURRENT；核心核对原领取、根停止、当前授权与未知效果。回执带原 callId、租约、Worker/epoch、实际 PID 与出生时间，核心核验后才保存结果。私有调用不对外暴露领取/回执 API。

## 模型与工具来源

Worker 的 MODEL_BEGIN 回调在 HTTP 到达前由核心原子保存根预算和原 modelRequestId。MODEL_END 保存实际 HTTP/供应商/用量观测；只有核心保存真实成功回执后才返回原请求编号。子进程沿该编号调用团队或固定 MCP 工具，携带原 toolCallId，不能自行构造成功身份。

所有新固定阶段在 AgentRun 中冻结根约定、阶段与角色的实际权限。普通固定阶段可使用其成功模型回执调用已授权 MCP，无需创建动态团队；共享团队工具仍要求所属团队。固定 MCP 同时核对写入/MCP 能力与原工具编号，独立提案和审查拒绝使用，历史缺失权限不会由实时配置补造授权。

准备、进程与工具账本的元数据先写原临时文件再替换。Windows AccessDeniedException 仅对这次元数据替换有界重试，每次最多一秒；实际工具、用户文件及整合写入不重放。持续失败或中断仍保留 UNKNOWN 和已扣预算；缺失回执另记录异常分类、失败回执阶段及工具是否已返回，不保存原异常参数或工具内容。

团队定义与实际权限在调用开始时固定，checkCurrent、资源申请/释放、收件箱、委派、等待、采纳和消息调用都回到核心。资源 token 对应原 ResourceManager.Lease，逐请求释放模型容量后再等待副任务或 MCP；后代继续使用同一根模型/工具预算。MCP 网络/stdio 传输仍在核心的原管理器，Worker 通过原模型/工具编号请求固定动作，父复用保存原子来源并去重。

公开运行、请求、租约和证据保留 deployment、executorPid、processStartedAt、corePid；原 Worker、节点、绑定、授权版本与操作编号继续保留。界面区分独立 Worker 和历史核心进程，缺失历史字段明确显示未提供。API 与交付 ZIP 移除领取摘要，模型内容先由核心脱敏再截断。

## 心跳与停止

子进程每三秒发送带原身份和严格递增序号的心跳。核心仅由这条实际管道消息续约三十秒期限，保存 CHILD_PIPE 与 lastHeartbeatSequence；自己的检查线程和 isAlive 不生成心跳。单调时间限制无心跳窗口，系统时间回退不能延长旧归属；迟到或失序心跳失败后封闭原会话。

取消帧设置原任务调用的停止标志并请求停止运行器管理的命令；请求本身不证明执行或远端效果停止。原回执、版本核验与租约结束才确定本机结果。Worker EOF 会停止心跳、取消调用/回调、请求结束命令并等待处理器；核心正常关闭先发送 EOF，给五秒退出窗口，再请求杀死原进程并有界等待。

实际启动者报告命令及已观察后代的原 PID/出生时间，核心保存来源链。停止前再次核验出生时间，避免处理后出生的同号进程。若原调用失联，核心先将原会话/领取封闭并记录 UNKNOWN，随后停止实际 Worker 和已观察进程；未确认停止时原调用继续保留资源与收尾门禁。processStopFacts 记录实际本机停止观察，不能将原操作或远端模型 UNKNOWN 改写为成功。

若核心已经保存模型 SUCCEEDED，但 MODEL_END 回调回执丢失，模型观测仍为 SUCCEEDED，执行领取保持 UNKNOWN；若 HTTP 回执本身缺失，原请求也为 UNKNOWN。两者均保留原编号和已扣预算，不重放。

## 跨核心会话核验与继续实施

会话失效后同一引擎不自动启动替代 Worker。人工核对同时要求原调用与副任务结束收尾、实际 Worker/已观察进程停止及 MCP 停止核验；用户确认外部效果后另存 RECONCILED。启动新核心仍将旧在途操作/租约/运行保存为 UNKNOWN，原任务不自动恢复。

alpha.11 在 schema 5 保存原启动阶段、根 PID/出生与逐条已观察后代，实际派发受跨重启门禁约束。启动间隙、旧版本缺清单与历史覆盖分别保留；已知仍活着或无法核验的进程不能由人工勾选绕过。进程覆盖与任务外部效果分别核对，原请求、未知结果和预算保留。完整接口与边界见[进程恢复协议](./process-recovery-contract.md)。

已观察清单不构成完整操作系统历史树。持久拉取队列、远程主机身份/配对/传输重连、操作系统资源隔离、PTY、角色/写入租约、workflow、备份与长期故障矩阵继续实施。当前本机协议不能替代完整 F01–F21 或 M0–M3 验收。
