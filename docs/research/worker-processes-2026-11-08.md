# 独立 Worker 进程来源、取舍与验证

研究与实现日期：2026-11-08，基线 `4f21a83ad4488e73e88bccd1da4495f92e432f8c`。按计划书 6.1/6.3、9.1–9.6 和 F03/F12/F14，把原 CORE_PROCESS 执行迁移到实际独立 JVM；核心保持 SQLite、预算与调度归属。

后续 Java 21 锁与 Windows 回执修订基线为 `391d42e5052dc3f5d2cad5ee029fd8c04d08a55c`，首次三端失败与每轮产物分别保留。

## 固定开源源码

| 来源 | 实际观察 | 本轮设计 |
| --- | --- | --- |
| [VS Code child process IPC](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/base/parts/ipc/node/ipc.cp.ts)，blob `e07b3ab7bdf2a141cde9e03f60d62bdc3e8731c9` | 子进程 disconnect 会 dispose；实际请求随退出清理；继承参数与危险环境独立处理。其 client 有按下一请求惰性创建新进程的生命周期策略 | 私有双向管道、实际请求/回执生命周期和环境过滤；本项目有外部费用与文件写入，失联后封闭原会话，保持 UNKNOWN，不采用自动新建/重发执行 |
| [VS Code utility process](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/platform/utilityProcess/electron-main/utilityProcess.ts)，blob `c1dd46b4c1771f1b59bda15b78198085f63b5155` | spawn 后记录实际 PID，exit/crash/kill 有各自事件；窗口生命周期可选择退出等待，超时再 kill | 实际 PID/出生时间握手，管道与退出分别观察；客户端退出独立于核心与 Worker，正常核心关闭采用 EOF、退出窗口和实际句柄停止 |
| [OpenJDK 21 VirtualThread](https://github.com/openjdk/jdk21u/blob/bb6b279495ceff8442dbd0225756b92d466d5309/src/java.base/share/classes/java/lang/VirtualThread.java)，tag `jdk-21.0.13+8`，blob `1b2b40b8c08c8aee18291c5e2926e60ce550f937` | `parkNanos()` 无法 yield 时转入 `parkOnCarrierThread()`，等待会占用载体线程 | 原调用的阻塞回调、RPC/帧写入与资源等待使用 ReentrantLock/Condition；原序号与全量资源授予继续原子核验。完整核心回归固定两条载体线程，覆盖两个父 Agent 等待子任务与递归委派 |
| [VS Code 原子 rename](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/base/node/pfs.ts)，blob `8eac76b3233218a54f8911e17d9e113d92f7d45c` | `renameAtomic` 保留 Windows 的有界重试，分开处理普通 rename 的跨设备 copy/delete；只重试 EACCES、EPERM、EBUSY，退避至 100 ms | Java 只对平台元数据的原临时文件替换采用 Windows AccessDeniedException 重试，每次以单调时间限一秒；用户文件写入和实际工具不重试，不采用 copy/delete 回退 |

VS Code 缓存位于 `.runtime/research-worker-process-2026-11-08/`，本轮再次用 git hash-object 核对两份 blob。OpenJDK 固定源码与来源记录位于 `.runtime/research-carrier-2026-11-08/`，以表中提交和 blob 为准。没有复制上游源码或增加上游运行依赖；本项目用 Java 私有 stdin/stdout 帧，保持已有原领取和预算边界。前轮固定 client-go、Temporal 和 VS Code PTY 的来源继续见[租约审查](./worker-leases-2026-11-08.md)。

原子 rename 源码缓存于 `.runtime/research-receipts-2026-11-08/`，GitHub contents 的 blob 与下载文件 git hash-object 已核对一致。

## 实现审查

独立 Worker main 在 Spring 之前进入，不打开 SQLite；实际现场、模型、文件工具和验证在子进程。核心元数据读取与 MCP 传输仍在核心，协议明确这条边界。启动者核验实际句柄与 HELLO/BIND/READY，不用子进程自报或同进程 timer 代替来源。

帧有长度和 JSON 边界，双向序号在同一写锁内分配/发送，拒绝重复请求与领取调用。读线程只路由，虚拟线程处理嵌套核心回调；每调用的观察者、资源和消息游标分别同步，两个父 Agent 等待副任务时不会占用读线程或全部模型容量。

原 SQLite 派发后交接原 lease/nonce、操作类型/目标编号和实际路径；Windows 的原路径与解析后的真实路径分别保存。模型 begin 在实际 HTTP 前原子保存预算与请求 ID，end 返回核心原成功 receipt；团队/MCP 沿原 requestId/toolCallId 回调。回执丢失后不退款、不推测成功，不新建原调用。

核心只有收到子进程原序号心跳才续约。自己的线程只检查过期/回退和实际退出；断线先封闭账本，再请求停止实际进程/已观察后代。停止前核对原出生时间，调用等待真实停止后才收尾和释放原资源。实际进程停止事实与远端效果/执行 UNKNOWN 分开记录。

启动失败与正常关闭都有实际子进程清理；Worker 关闭私有管道后取消调用并等待处理器。核心存储关闭时同时封闭 healthy 标记，避免后续回调继续尝试写入已关闭连接。保留旧 Java 注释，新增 Javadoc 使用标准多行格式。

## 失败与修正

初始协议测试使用 PipedInputStream，有一个 failure 和一个 error。独立探针确认短命写入线程结束可触发 Write end dead，输出流仍开放；夹具改用 JDK Pipe/Channels 后 Wire 2 与 RPC 4 合计 6 / 6。原失败保留于 `.runtime/alpha10-worker-rpc-attempt1/` 和对应日志，不能当作成功。

真实 JVM/Node 四项回归首次通过：实际身份/环境/独立心跳、原领取命令输出与一次派发、错误 token/目录/操作拒绝，以及 EOF 后在途命令树停止。日志 `.runtime/alpha10-worker-real-process-initial.log`。

首次完整团队 11 项全部在 BIND 阶段失败，未进入团队执行断言；原报告位于 `.runtime/alpha10-team-attempt1/`。补充仅含分类与异常类型的启动诊断后，子进程报 UncheckedIOException。独立的无私有数据 Java 探针在原 Windows 临时路径复现 HttpClient 初始化错误：Unable to establish loopback connection / Invalid argument: connect；短工作区路径及较长有效目录均初始化成功。原探针源和 JSON 保存在 `.runtime/WorkerNioProbe.java` / `.runtime/alpha10-worker-nio-path-probe.json`。Windows 启动目录现在保证 UTF-8 路径长度不少于 120 字节，让 JDK 使用可用的本机回退管道；没有放宽 Worker 握手或伪造 READY。

目录修正后，原完整委派、依赖快照与采纳单项通过。新增核心桥接夹具首次五项在派发前被真实目录门禁拒绝：夹具使用 Windows 短路径，但冻结绑定已经解析为真实路径；产品引擎原本沿确认的 sourcePath 执行。夹具现使用确认后的源目录，并在构造失败时清理实际服务、Worker 和数据库；失败报告保留于 `.runtime/alpha10-core-bridge-attempt1/`。修正后核心桥接五项与严格 Wire 两项合计 7 / 7，日志 `.runtime/alpha10-worker-core-bridge-path-fixed.log`。

核心桥接测试检查 HTTP 到达前的原输入 SHA-256、请求/预算/领取已落库；子进程文件工具只改变任务副本；保存请求事务失败时没有实际 HTTP，重开后原领取 UNKNOWN 且不重放；实际 Worker 退出保留原在途请求 UNKNOWN；核心已保存模型成功但 end 回调回执丢失时原模型 SUCCEEDED、原执行 UNKNOWN；核心 timer 不制造子心跳。完整团队测试均改为实际独立 JVM，继续检查根预算、两个父子进展、递归/权限、节点/定义撤回、MCP 原来源和采纳冲突。

第一次完整核心构建因用户暂停而中断，日志与当时报告保留在 `.runtime/alpha10-full-core-interrupted/`，不计作完整验证。恢复后运行的 157 项检查有两个旧 MCP 参数化夹具失败：夹具仍沿用无来源别名调用，未提供核心成功模型回执和原工具编号；原报告保存于 `.runtime/alpha10-core-attempt1/`。夹具现使用实际核心观察者 begin/end 返回的原请求编号，继续验证成功/响应丢失、容量释放、预算和去重，不恢复无来源的引擎调用入口。

随后自审发现普通固定阶段的真实 MCP 也被团队归属检查拒绝。新增实际独立 JVM、模型 HTTP 与 MCP HTTP 用例先复现 WAITING_INPUT、外部动作零派发；失败报告保留于 `.runtime/alpha10-fixed-mcp-attempt1/`，日志 `.runtime/alpha10-fixed-mcp-before.log`。修复把成功运行回执与共享团队资格分别校验，并为所有新固定阶段冻结根/阶段/角色权限；MCP 仍要求原成功请求、原工具编号和写入/MCP 能力，独立提案、审查和窄范围副任务仍拒绝。MCP 记录补齐实际 deployment、executorPid、processStartedAt、corePid。定向 12 / 12 通过，日志 `.runtime/alpha10-mcp-source-fixed.log`；另补冻结权限、非成功/其他运行回执、已结束来源和普通运行不能创建团队副任务的门禁回归，最终完整构建再验。

提交 `391d42e5052dc3f5d2cad5ee029fd8c04d08a55c` 的 [CI run 42447144181](https://github.com/lihe-he/multi-agent/actions/runs/42447144181) 已终态：macOS 成功，Ubuntu 和 Windows 在完整核心测试失败。两端均是 `twoParentsWaitingForChildrenMakeProgressAtCapacityTwo` 与 `actualRecursiveDelegationStopsAtDepthAndCumulativeLimits` 超时；Windows 另有 `descendantWithoutExitConfirmationRemainsUnknownAfterParentSuccess` 的 JUnit 临时目录清理错误。原结果和两端日志保留于 `.runtime/ci-alpha10-attempt1/`，不能以此前本机通过覆盖。

将实际测试 JVM 的 `jdk.virtualThreadScheduler.parallelism` 与 `maxPoolSize` 同时限制为 2，两个团队失败在 Windows 准确复现。实际线程快照显示两个回调在持有 `synchronized(call)` 时，分别进入 Git 元数据命令的 `Thread.join` 和父任务的 `Thread.sleep`，栈均包含 `VirtualThread.parkOnCarrierThread`。两条载体被占用，子 Agent 的核心回调无法推进。有效失败日志为 `.runtime/alpha10-limited-carriers-effective-before.log`，原 Surefire 报告、线程事实和快照保留于 `.runtime/ci-alpha10-attempt1/limited-carriers-reports/`、`limited-carriers-facts.json` 和 `.runtime/alpha10-core-carriers-before-threads.json`。早先通过 `-DargLine` 传值的命令被 pom 的显式 argLine 覆盖，不能计为有效的两载体复现。

修订将核心 Invocation 和 Worker Call 的回调锁、RPC 与帧写锁改为 ReentrantLock。每调用仍同步检查原序号、原回执与收尾，RPC 仍在同一锁内分配序号并写帧；ResourceManager 使用 ReentrantLock/Condition，等待不持临时预留，释放继续幂等。Surefire 固定两载体限制，使正常完整回归持续覆盖该场景。修订后的定向 22 / 22 通过，包含原两个团队失败、Wire/RPC、真实 Worker、全量资源门禁和两项真实后代测试，日志 `.runtime/alpha10-limited-carriers-lock-fixed.log`。

Windows 后代夹具改用子脚本绝对路径，并使 detached 子进程在临时目录的父目录启动。真实后代、自然退出、UNKNOWN、原 PID 和输出截断断言均保留；结果读取之前的失败也进入 finally 清理，原后代进程等待退出后再明确检查 `isAlive()`。PID 复用保护继续依据原回执与出生时间，清理不处理后出生的同号进程。原 Windows 清理错误没有足够证据证明具体残留来源，cwd 修订和后续成功不能回写原失败为通过。最新完整构建与最终 Jar/打包证据另见实施记录。

锁修订后的首次完整两载体核心回归运行 159 项，两个团队原失败和完整团队 12 项通过，两项真实后代也通过；仍有一项 `cancellingRealStdioTreeConfirmsLocalStopButKeepsEffectUnknown` 在工具启动前失败，另有一项 Windows POSIX 权限跳过。完整失败日志与报告保留于 `.runtime/alpha10-carrier-full-core.log` / `.runtime/alpha10-carrier-full-attempt1/`。定向再次复现，并取得实际线程快照：一条虚拟线程在 MCP stderr 的原生 `FileInputStream.readBytes`，另一条在 `Stdio.request()` 的 monitor 内进入 `parkOnCarrierThread`；stdout 响应处理无法取得载体。证据见 `.runtime/alpha10-carrier-mcp-effective-before.log`、`.runtime/alpha10-carrier-mcp-before-dump.log` 和 `.runtime/alpha10-carrier-mcp-before/alpha10-carrier-mcp-before.json`。

MCP stdio 的请求/通知/关闭与 ManagedTransport 收尾改用 ReentrantLock。每个受工具/进程容量限制的传输使用一条平台管道读写线程和一条 stderr 平台排空线程，原生管道等待不占虚拟线程载体。帧、编号、取消、未知效果与停止前保留容量的语义继续沿用；不增加重试或自动重放。首次完整 MCP 18 / 18 通过（`.runtime/alpha10-carrier-mcp-fixed.log`），随后完整核心 159 项中 158 通过、1 项 Windows POSIX 跳过，0 失败/错误（`.runtime/alpha10-carrier-full-final.log`），该轮对应报告保留于 `.runtime/alpha10-carrier-full-before-stdin/`。

该轮之后的实际 stdin 故障探针让 Node 服务暂停读取，在虚拟线程写入 500 KB 参数，再请求取消。仅迁移读取时，取消未在 1.5 秒窗口内推进；探针使用已核验出生时间的原句柄清理，实际进程停止，证据 `.runtime/alpha10-carrier-mcp-stdin-effective-before.log`。进一步把请求和通知写入移到同一条平台管道线程后，同一探针取得 CancellationException 并确认原进程停止，证据 `.runtime/alpha10-carrier-mcp-stdin-after.log`。新增真实大参数回归核对服务暂停、原请求未返回、取消有界返回和原进程实际退出，失败时也用原出生时间保护清理。完整 MCP 19 / 19 通过，日志 `.runtime/alpha10-carrier-mcp-io-fixed.log`。最终完整核心 160 项中 159 通过、1 项 Windows POSIX 跳过，0 失败/错误，package 成功；所有 Surefire 报告均核对两载体参数为 2，日志 `.runtime/alpha10-carrier-stdio-final-core.log`、报告 `.runtime/alpha10-carrier-final/`，最终产物按这一修订记录。

这份 Jar 的实际协议验收运行 26 项：25 通过，圆桌质疑一项失败，其余后续流程尚未执行。原模型 SUCCEEDED、源版本一致、执行 UNKNOWN；`read_file` 的磁盘账本已含 `completedAt`，证据为 AccessDeniedException，定位为工具返回之后的 CONFIRMED 元数据替换失败。没有足够证据确定拒绝共享的外部进程。原报告、Jar SHA 与事实保留于 `.runtime/alpha10-carrier-integration-attempt1/`，完整样例与数据库继续保留在 `.runtime/ci-R7cM9j/` 和原 TEMP 样例目录，失败不能以重跑通过覆盖。

新增 Windows 实际 NOSHARE_DELETE 文件共享夹具，在工具执行前锁住已发送的回执；工具真实写入完成后分别保留短暂锁和持续锁。夹具最初一处 HTTP handler 异常声明编译失败，修正后两个用例准确得到 1 failure：短暂锁错误进入 UNKNOWN；持续锁的 UNKNOWN 与零重放断言通过。原编译日志、失败日志与报告为 `.runtime/alpha10-receipt-sharing-compile.log`、`.runtime/alpha10-receipt-sharing-before.log` / `.runtime/alpha10-receipt-sharing-before/`。

修订只对 preparation、进程与工具账本等平台元数据重试原临时文件替换；每次最多一秒，持续失败或中断仍保持 UNKNOWN，已扣调用预算不退回，工具和用户文件写入不重放。缺失回执另保存异常分类、回执阶段与工具是否已经返回，不保存原异常参数或工具内容。修订后运行器 27 项和 MCP 19 项合计 46 项中 45 通过、1 项 Windows POSIX 跳过，日志 `.runtime/alpha10-receipt-sharing-fixed.log`。NOSHARE_DELETE 仅用于 Windows 实际共享冲突测试；非 Windows 跳过这两项，生产代码没有该内部 API 依赖。

最终完整 package 162 项中 161 通过、1 项 Windows POSIX 跳过，0 失败/错误；17 份 Surefire 报告全部核对两载体参数为 2。最新 Jar SHA-256 为 `c64d2d2c21b2647b3c0747f5d2292ab2db539aa2194e6095fff06f8786d6fea7`；日志、原 Jar、源码 diff 与完整报告独立保留于 `.runtime/alpha10-receipt-final-core-evidence/`。同一 Jar 的实际协议 28 / 28 通过，包含原圆桌失败及完整后续团队/交付流程；独立运行核对客户端退出后保留原请求/领取，并完成 18 次根调用、13 条 Worker 回执及一次 MCP 派发。重新生成的 NSIS 与 packaged=true 程序以内置 Java 启动及正常退出，打包核心 SHA 与最新 Jar 一致。最新截图已复查，详细产物及原失败来源见[Windows 核对记录](../qa/alpha10-receipt-validation-win32.json)。未重复完整安装/卸载，本机成功不能替代最终提交的三端 CI。

最终完整构建、UI、Jar、独立浏览器/附加桌面、打包、注释审查与对应提交三平台 CI 以[实施记录](../implementation-status.md)为准。定向通过不能替代最终回归和正式发布条件。

持久队列、旧进程树跨重启完整核验、远程配对/重连、PTY、角色/写入租约、workflow、完整备份与长期验收继续实施，完整项目目标保持未完成。
