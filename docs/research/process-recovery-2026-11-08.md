# 核心重启与旧进程核验研究

日期：2026-11-08。开发基线为 `c4a7558f56f65336f05c236c717e271cdd243b74`。本轮接续 F03/F12/F14 和计划书 6.3、9.1–9.6，研究持久进程清单与新派发的停止门禁。实现为 alpha.11 / schema 5；本轮完整产物和真实重启验收结果分别记录，不能以设计或定向检查代替实际发布验收。

## 固定开源依据

| 来源 | 核对的事实 | 本项目采用的边界 |
| --- | --- | --- |
| [OpenJDK ProcessHandle](https://github.com/openjdk/jdk21u/blob/bb6b279495ceff8442dbd0225756b92d466d5309/src/java.base/share/classes/java/lang/ProcessHandle.java)，blob `1704a6da37a509465cd6671c4f8713a93c26e34f` | PID 复用时间不可预测；状态检查与操作之间有竞态；`children()`、`descendants()`、`allProcesses()` 都是当前快照 | 持久保存原 PID、出生时间与观察来源；已观察后代不等于完整历史进程树 |
| [OpenJDK ProcessHandleImpl](https://github.com/openjdk/jdk21u/blob/bb6b279495ceff8442dbd0225756b92d466d5309/src/java.base/share/classes/java/lang/ProcessHandleImpl.java)，blob `7dd6bf6a5e458feaa7be664b48a420dad26c303b` | 实际句柄保存创建时的 startTime，终止调用向 native 传递这份 startTime；部分相等及存活检查允许未知 startTime | 原出生时间与当前出生时间必须均已知且一致才请求停止；空出生时间不能借助 Java 句柄的相等结果补造身份 |
| [OpenJDK Windows 终止实现](https://github.com/openjdk/jdk21u/blob/bb6b279495ceff8442dbd0225756b92d466d5309/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c)，blob `0bc036b723656041adc5c512461d642ad2b98fdc` | `destroy0` 在持有实际 native handle 时核对创建时间；传入 startTime 为 0 时也允许终止 | 生产代码在调用终止前额外拒绝缺失出生时间、出生信息读取失败及当前核心 PID，不能依赖 native 的未知时间回退 |

三份源码均固定于 OpenJDK jdk21u 的 `jdk-21.0.13+8`，提交 `bb6b279495ceff8442dbd0225756b92d466d5309`。缓存位于 `.runtime/research-restart-2026-11-08/`；本轮新增两份源码的 GitHub contents blob 与本机 `git hash-object` 一致。仅参考设计与平台事实，不复制上游代码，不增加运行依赖。

## 已复现的缺口

真实 Node 进程夹具保存 PID 19444 与原出生时间，写入旧 LOCAL_PROCESS Worker、UNKNOWN 操作和 UNKNOWN 租约后关闭并重开 SQLite。新核心桥接启动另一实际 Worker PID 20056，旧 Worker 被标为 LOST；旧进程仍活着时，原 `reconcile` 接受两项人工勾选，将任务改为 PAUSED、租约改为 RECONCILED / USER_CONFIRMED。已扣模型调用数仍为 3。这证明当前门禁没有读取旧 Worker 的实际 PID，不能由界面声明证明停止。

探针在 `.runtime/OldWorkerRecoveryProbe.java`，事实为 `.runtime/alpha11-old-process-gate-before.json`，样例数据库和事实副本保留在其中记录的目录。这里使用带真实 PID/出生时间的旧记录夹具，不能声称进行了完整的生产核心崩溃与历史树覆盖验收。夹具最终用启动时持有的原句柄终止并等待实际退出。

首次探针没有指定本机有效 NIO 临时目录，在基础 HttpClient 初始化前失败；这次没有进入门禁断言，日志单独保留于 `.runtime/alpha11-old-process-gate-probe-nio-failed.log`。加入与有效 Surefire 场景相同的 NIO 参数后，才取得上述门禁复现，不把启动失败计作复现成功。

## 本轮恢复设计

启动 Worker 与 MCP stdio 前先保存本机进程会话意图，启动后保存实际根 PID/出生时间。核心逐条保存由私有管道或实际句柄观察的后代与来源；Worker、租约和 MCP 操作关联原进程会话。SQLite 仍由核心独占。

重启先读取旧进程会话、原进程记录以及缺少新清单的历史 Worker / stdio 操作。进程仍活着、出生时间不能核验或观察失败时，拒绝核对停止与新派发；PID 已复用时只记录原进程不再匹配，不终止新进程。新核心的空闲 Worker 管道会话不代表任务恢复，任何实际派发仍经过旧进程门禁。

启动意图到 PID 回执之间存在不可原子覆盖的间隙，进程枚举也不是完整历史树。中断会话、缺失启动回执和旧版本缺清单分别标记覆盖不足，要求记录对未列出进程的核对依据。已知仍活着或无法核验的进程不能通过这项人工声明绕过。实际进程停止观察、用户补充的覆盖核对与原任务外部效果分别保存，不改写原模型、工具、预算或 UNKNOWN 结果，不自动重放。

新增进程记录和恢复语义使用后续数据库版本，旧核心应拒绝打开该版本，避免旧执行入口忽略停止门禁。公开 API 兼容版本仍独立于数据库版本。界面展示旧进程、原出生时间、观察来源及缺失覆盖，并提供重新核验、请求停止和记录覆盖依据；原任务继续单独核对文件及外部效果。

本轮仍不提供操作系统级完整历史树、远程进程恢复、持久拉取队列或完整角色/写入租约。后续验收必须分别记录真实进程、存储失败、出生时间缺失、PID 复用、MCP stdio 与核心重启，不能以清单全部消失证明未知外部效果已撤销。

## 自审与失败保全

首个账本集成回归 36 / 36 与界面恢复套件 6 / 6 通过后，进一步检查实际清理边界发现：MCP 启动失败直接释放容量，且清理时后代记录失败可能中断实际停止。修订把未完成启动的原传输交回管理器，在实际停止未确认时继续保留容量；清理先在内存捕获原身份，落库失败仍处理已捕获的实际父子进程。已保存的 STOP/RECONCILED 历史不被重复停止或迟到的已停止清理抹去，晚到新观察仍重新触发门禁。

新增清理故障与迟到记录后，首次 39 项中两项失败，保留于 `.runtime/alpha11-process-self-review-failed/`。MCP 用例实际父子进程都已停止，但第一版断言错误地认为注入前只有根记录；后代可能已在正常检查时被记录。夹具现明确在安装故障后才允许新的实际子进程启动，并比较注入前的原记录，验证失败事务不覆盖历史。

Worker 失联用例仍超时，第二次两项诊断中 MCP 通过、Worker 超时。清理前事实保存于 `.runtime/alpha11-process-loss-diagnosis-failed/`：原 Worker PID 53144 已退出，25 条已观察记录最终均停止，来源和原出生保留。Windows 未见 PID 的回退在每条历史记录上重复枚举全部系统进程，拖慢收尾；修订将一次核验合并为同一枚举快照，保持每条原身份和无法核验门禁。原十秒失联断言保持，后续账本 13、MCP 21、Worker 桥接 5 共 **39 / 39** 通过，日志为 `.runtime/alpha11-process-self-review-fixed.log`。原失败不计作通过。

诊断期间两次 jcmd 调用未获得有效线程文件：首次 Windows 中文输出路径失败，第二次目标测试 JVM 已退出。因此仅使用已保存的进程事实、原失败/成功报告及代码边界作依据，不声称有这次成功线程栈证据。

完整恢复协议见[进程恢复约定](../process-recovery-contract.md)。交付增加完整原始 `process-observations.json`，显示五十条的上限不减少门禁或导出。现有注释与新 Javadoc、版本、准确 Jar 和三端结果仍按最终产物分别核对。

## 第一轮实际 Jar 重启

第一份干净构建为 177 项、176 通过和一项 Windows POSIX 跳过，18 份报告均核对两载体限制。Jar SHA-256 为 `0c0779d28e2820a886999ac45ca83877c8bd779b4c9dfba7ea631468fe8e135b`，原 Jar、报告与源码审查另存 `.runtime/alpha11-gate200-before-fix-core-evidence/`。生产 Web 构建和 43 项界面回归均通过。

这份 Jar 的首次真实重启运行位于 `.runtime/process-recovery-Kbev6C/`。原 stdio 已真实派发一次固定工具、另一任务真实进入在途 HTTP，核对独立 Worker 来源后只终止原核心。MCP PID 48040、出生 `2026-11-08T07:27:28.317Z` 在 EOF 后仍活着；同目录重开后原模型/操作 UNKNOWN、请求身份和已扣预算保留。

该次在新服务探测的返回值上失败：全局恢复门禁实际已阻止启动，却被 probe 的异常回退改为 HTTP 200 / available=false。失败前记录、启动/EOF/唯一调用事实与完整核心日志保留；失败清理另用原出生核验，随后独立 Win32_Process 观察确认原 PID 已不在系统进程列表。没有用清理后的状态回写失败时的存活事实。

修订把恢复门禁放到探测回退之外，未核对旧会话时明确返回 409，服务健康状态不被改写为普通不可用。新增未回执旧启动阻止无关服务的回归，检查零传输打开、零资源申请和零操作；最终 Jar、完整构建及同一真实重启脚本必须重新核对，不能用第一份 177 项结果替代。

## 完整构建中的团队等待失败

409 门禁修订后的首次完整 package 运行 178 项，出现一项团队等待失败，0 错误和一项 Windows POSIX 权限跳过。`twoParentsWaitingForChildrenMakeProgressAtCapacityTwo` 中第一名子任务未在原八秒期限内等到第二名子任务；完整团队套件耗时 261.8 秒。日志、全部 18 份报告及原失败保留于 `.runtime/alpha11-core-gate409-team-failed/`，这次 package 未成功，不能用 target 中旧 Jar 代表这份源码。

诊断保持原八秒期限及两条虚拟线程载体限制，在失败时保存 HotSpot 的完整虚拟线程快照及原任务、运行、模型请求、操作和资源事实。单项回归 1 / 1 通过，日志 `.runtime/alpha11-team-capacity-diagnosis.log`；单项通过不能证明已定位原失败，也不能将原失败回写为通过。整套团队及最终完整构建继续分别核对。

随后整套团队 12 / 12 通过，耗时 251.7 秒，原报告保留于 `.runtime/alpha11-team-capacity-suite-passed/`。这次没有复现原等待失败，也没有生成失败线程文件；原失败原因尚未有足够证据确认，不能声称获得了这次成功线程栈或修复了已证实的容量死锁。最终完整构建和提交后的三端仍保留原严格检查。

恢复验收的独立 Java 探针同时修订停止事实：明确分别保存请求前存活状态和停止后的实际观察，原 PID/出生核验不变。最终核心退出、原夹具独立核验停止之后才生成成功报告，失败清理仍先保全失败时的原事实。

## 后代观察失败后的覆盖状态

进一步自审发现 MCP 清理虽然会继续停止内存中捕获的真实进程，但后代观察失败且存储仍可用时，原会话被记为正常 CLOSED，丢失覆盖失败的恢复门禁。将原真实父子进程测试扩展为数据库写入故障和观察异常两种情形；修订前两个用例中写入故障通过，观察异常准确失败，原进程均已停止而会话却为 CLOSED。失败日志与报告保留于 `.runtime/alpha11-mcp-coverage-observation-failed/`。

清理现仅在未发生观察异常时记录正常关闭；有异常时保留 UNKNOWN，实际进程退出与缺失进程覆盖分别判断。观察异常用例继续核验原父子进程停止、原观察回执不覆盖、全局 409 门禁以及独立覆盖核对后的开放；存储故障仍不补造持久回执。最终定向和完整产物按本次修订重新核对。

账本 13、MCP 23 共 36 / 36 通过，日志 `.runtime/alpha11-mcp-coverage-observation-fixed.log`。随后干净完整 package 179 项中 178 通过、1 项 Windows POSIX 跳过，0 失败/错误；18 份报告均核对两载体限制。团队 12 项全部通过，原八秒等待断言保持。Jar SHA-256 为 `3ec93078303558335131fbb2814708fbf93d07b82f1dba1a92f67dc1725f9905`，原 Jar、完整报告和源码审查保留于 `.runtime/alpha11-final-core-evidence-179/`。

这份 Jar 的真实重启前五项断言通过，包括新服务探测的 409；随后浏览器定位器因同一卡片中根进程和后代均显示“原进程仍在运行”而严格匹配失败。完整清理前事实保留于 `.runtime/process-recovery-0y1oOP/`，日志为 `.runtime/alpha11-recovery-actual-attempt2.log`。原 MCP PID 44124、出生 `2026-11-08T08:02:17.970Z`，后代 PID 44272、出生 `2026-11-08T08:02:18.083Z`；清理后两者分别独立核验已退出。定位器现使用原根 PID 精确选取观察行，保留原出生和存活断言。

## 后代快照的原父身份

再次核对固定 OpenJDK `ProcessHandleImpl.java` 的 `descendants()`：它用当前系统快照中的父 PID/出生展开后代，不能保证快照前后的原句柄身份。实际 Worker、MCP 和命令现在共用原父身份检查，在枚举前后均要求原父仍存活且出生一致；身份更换、缺失或退出时丢弃这份新增快照，继续清理原已记录身份。命令清理中的观察异常不会提前跳过原父停止，缺失停止事实仍未确认。原自然退出不会被当作完整历史覆盖。

新增受控句柄回归覆盖枚举前的不同身份、缺出生、枚举后的替换身份、正常身份和已退出；这是受控来源核验，未声称强制操作系统产生了真实 PID 复用。账本 14、MCP 23、实际运行器 27、真实 Worker 4 共 68 项中 67 通过和一项 Windows POSIX 跳过，0 失败/错误，日志 `.runtime/alpha11-parent-snapshot-guard-targeted.log`。最终完整构建及真实重启继续按这次修订重新生成产物，179 项 Jar 不替代后续产物。

## 停止声明时序与长进程清单

原父身份修订后的干净完整 package 为 180 项中 179 通过、一项 Windows POSIX 跳过，18 份报告均核对两载体限制；团队 12 项通过。Jar SHA-256 为 `5787d27fb5648ecc68988469c27b318debf8c16f6c8146ea0851d99b1596fd1c`，原产物与报告保留于 `.runtime/alpha11-final-core-evidence-180/`。

这份 Jar 的第三次实际恢复在前六项通过后，于旧 Worker 停止声明返回 409。清理前 `.runtime/process-recovery-l6pQWm/failure-before-cleanup.json` 保存旧 Worker 91 条观察中一条 UNVERIFIABLE：后代 PID 39900，原出生 `2026-11-08T08:21:27.076Z`，来源 WORKER_PIPE，当前出生缺失。脚本在请求停止后立即核对，服务端正确拒绝；之后独立探针确认该 PID 已不再存在，但这份后续观察不能回写失败时事实，也未据此确认当时无法核验的具体原因。原日志保留于 `.runtime/alpha11-recovery-actual-attempt3.log`。

验收脚本现对旧 Worker 和 MCP 均有界等待 `observedProcessesStopped=true` 后再提交覆盖依据，保存每次等待中的完整会话观察；停止请求本身和 409 均不计作停止成功。生产停止入口仍最多等待两秒，出生缺失或观察失败继续阻止声明。

第四次运行 `.runtime/process-recovery-glLUQY/` 又暴露 `/stop` 的客户端 15 秒超时。清理前旧 Worker 48 条记录已全部确认退出，但服务端停止流程的后代捕获、信号检查及等待仍为每条已退出 PID 单独枚举整个系统；原失败日志为 `.runtime/alpha11-recovery-actual-attempt4.log`。新增 64 条原身份的受控来源回归准确计得 **185 次**枚举回退，修订前失败报告保留于 `.runtime/alpha11-stop-history-failed/`。这是可复现的重复枚举依据，不声称已获得原超时的线程栈或完整时间分解。

停止流程现按后代捕获、信号发送和每轮等待分别共享一次 PID 快照，下一轮重新读取；活进程的原出生仍逐项重新核验。回归同时检查只停止已知原身份、复用 PID 和出生缺失均不接收信号、仍有无法核验记录时核对返回 409。定向、最终完整构建、准确 Jar 与实际恢复继续按这份生产修订核对。

修订后账本 15、MCP 23 共 **38 / 38** 通过；64 条身份的停止流程最多读取四次系统快照，未知/复用和核对门禁断言仍通过。日志为 `.runtime/alpha11-stop-history-fixed.log`，两套完整报告单独保留于 `.runtime/alpha11-stop-history-passed/`。

随后干净完整 verify 为 181 项中 180 通过、一项 Windows POSIX 权限跳过，0 失败/错误；18 份报告均核对两载体限制，团队 12 / 12、255.4 秒。最终 Jar SHA-256 为 `2232e0b6db7c61de9695702ba802fa5c29b3bcc27c290517ca2383294804a261`，原 Jar、完整报告与源码注释审查保留于 `.runtime/alpha11-final-core-evidence-181/`。原 180 项产物与第三、第四次恢复失败分别保留，不用旧 SHA 代表停止批处理修订。

最终这份 Jar 的实际恢复 **11 / 11** 通过，原事实位于 `.runtime/process-recovery-ZEGkdV/`，日志 `.runtime/alpha11-recovery-final.log`，报告见[Windows 实际恢复](../qa/process-recovery-win32.json)。原核心 PID 42768，重开核心 PID 45480、51488；原 Worker PID 46652/出生 `2026-11-08T08:40:26.609Z`，原 MCP PID 17148/出生 `2026-11-08T08:40:45.898Z`。Worker 47 条记录与 MCP 两条记录均确认退出，等待中的会话观察分别保存。

原三次模型调用、一次外部工具调用没有重放；原请求 UNKNOWN 和已扣预算保留。进程覆盖与任务效果分别核对后，再次重启保留原依据，新任务实际完成，ZIP 保留原请求与完整进程来源。最后核心退出，全部已记录原夹具身份由独立 Java 探针确认停止后才生成成功报告；之后清理再次确认相同事实。1440px 与 430px 的生产界面截图已查看，原出生、存活拒绝和表单字段可见，无横向溢出或页面异常。外部供应商、远程进程和完整历史树仍不属于这份本机夹具的验证范围。

同一最终 Jar 随后完成协议 28 / 28、独立浏览器/附加桌面生命周期和 Windows 打包运行。独立 Worker PID 54444 与核心 PID 54008 分别保存，原在途请求/租约编号在客户端退出和重连后保留，18 次根调用、13 条实际 Worker 回执与一次 MCP 工具完成。打包应用与核心均为 alpha.11，禁用外部 Java 后用内置运行时连接 API 200 并正常退出；内置核心 SHA 与最终 Jar 相同。生产恢复、Worker、团队和打包截图已复查。日志、源码/网页/安装包哈希和完整报告汇总于[最终 Windows 产物核对](../qa/alpha11-recovery-validation-win32.json)，原版本及失败分别保留；三端 CI 仍以推送提交的实际结果为准。
