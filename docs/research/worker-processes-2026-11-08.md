# 独立 Worker 进程来源、取舍与验证

研究与实现日期：2026-11-08，基线 `4f21a83ad4488e73e88bccd1da4495f92e432f8c`。按计划书 6.1/6.3、9.1–9.6 和 F03/F12/F14，把原 CORE_PROCESS 执行迁移到实际独立 JVM；核心保持 SQLite、预算与调度归属。

## 固定开源源码

| 来源 | 实际观察 | 本轮设计 |
| --- | --- | --- |
| [VS Code child process IPC](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/base/parts/ipc/node/ipc.cp.ts)，blob `e07b3ab7bdf2a141cde9e03f60d62bdc3e8731c9` | 子进程 disconnect 会 dispose；实际请求随退出清理；继承参数与危险环境独立处理。其 client 有按下一请求惰性创建新进程的生命周期策略 | 私有双向管道、实际请求/回执生命周期和环境过滤；本项目有外部费用与文件写入，失联后封闭原会话，保持 UNKNOWN，不采用自动新建/重发执行 |
| [VS Code utility process](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/platform/utilityProcess/electron-main/utilityProcess.ts)，blob `c1dd46b4c1771f1b59bda15b78198085f63b5155` | spawn 后记录实际 PID，exit/crash/kill 有各自事件；窗口生命周期可选择退出等待，超时再 kill | 实际 PID/出生时间握手，管道与退出分别观察；客户端退出独立于核心与 Worker，正常核心关闭采用 EOF、退出窗口和实际句柄停止 |

缓存位于 `.runtime/research-worker-process-2026-11-08/`，本轮再次用 git hash-object 核对两份 blob。没有复制上游源码或增加上游运行依赖；本项目用 Java 私有 stdin/stdout 帧，保持已有原领取和预算边界。前轮固定 client-go、Temporal 和 VS Code PTY 的来源继续见[租约审查](./worker-leases-2026-11-08.md)。临时查看的未固定 OpenJDK 源码不作为固定采用来源。

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

最终完整构建、UI、Jar、独立浏览器/附加桌面、打包、注释审查与对应提交三平台 CI 以[实施记录](../implementation-status.md)为准。定向通过不能替代最终回归和正式发布条件。

持久队列、旧进程树跨重启完整核验、远程配对/重连、PTY、角色/写入租约、workflow、完整备份与长期验收继续实施，完整项目目标保持未完成。
