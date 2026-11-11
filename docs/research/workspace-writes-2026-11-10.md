# 根现场写入保护研究

研究日期：2026-11-10。产品依据为计划书 9.1、9.4–9.6、18.1 与 F03/F12；源码基线为 main `7f31be5d30d2deff637e251be5208104eb179f9d`，alpha.13 生产源码固定于 `33863a42c5e2e84a6072daa78d24428c77aed5c5`。本轮先完成已有 clone/worktree 的整次根保护，协议见[根现场写入保护](../workspace-write-contract.md)。

## 固定开源与实际运行时

下列源码分别核对 GitHub contents blob、实际原字节、SHA-256 和本机 `git hash-object --no-filters`。只使用命令/API 与恢复语义，没有复制上游实现。

| 固定来源 | 文件 / blob | 对实现的影响 |
| --- | --- | --- |
| [Git v2.51.1](https://github.com/git/git/tree/81f86aacc4eb74cdb9c2c8082d36d2070c666045)，GPL-2.0 | lockfile.c `1d5ed0168287464e24d60e30b0cfbce181676535`；lockfile.h `1bb99264976d27095f348caf0fc35efe5598348b` | 描述符、独占意图和原子提交/回滚需要独立核验；worktree lock 保留注册归属，不能替代根写入保护 |
| [JNA 5.17.0](https://github.com/java-native-access/jna/tree/695ae749e7bfd92f88324147c5d96b7129efec3e)，选择 Apache-2.0 | Kernel32.java `b1ee1acb51e0727098422dd3c6329377dc740e6d`；WinBase.java `4039da0238dffed704dd2cb589c23a18c6fe171a`；WinNT.java `22e5a7236b6446a784f9e89d32260ccec334b656` | Windows NIO fileKey 为空时，从原生属性句柄读取卷编号和 128 位文件编号，拒绝重解析点和目录 |
| [Temurin 21.0.13+9 SOURCE](https://github.com/adoptium/jdk21u/tree/2ab10ca20c191701c3a9f3153bfc6e93d63714c1)，GPL-2.0 with Classpath Exception | WindowsChannelFactory.java `56f09d34ab866316237bb904f1b5405b68752e90`；UnixChannelFactory.java `eed7bc4c575bcac05ee225c9be4df44108fb9ee9`；ExtendedOpenOption.java `5ec869dc1a9b609c6ad444f4d56e51f70f020591` | Windows NOSHARE_DELETE 拒绝持锁时删除/改名；Unix 另核对路径与原描述符 marker |

Git COPYING、JNA LICENSE 与 JDK LICENSE 原文件同样已核对。清单分别在 `.runtime/research-workspace-writers-2026-11-10-attempt2/`、`.runtime/research-workspace-file-identity-2026-11-10-attempt2/`、`.runtime/research-lock-open-2026-11-10/`。Git 技术文档候选路径和 JNA checksum 请求的 404 原失败继续保留；后者改为核对 canonical Maven Jar 原字节，没有据 404 推断依赖完整性。

JNA 实际 Maven 依赖的字节数、摘要和许可见[第三方依赖记录](../third-party-dependencies.md)。本机 Windows JDK src.zip 的 WindowsChannelFactory 和 ExtendedOpenOption **两份**与固定源码逐字节相同；UnixChannelFactory 不在 Windows 源码包内，不计作第三份匹配。准确结果在 `bundled-jdk-source-comparison.json`。

## 原实现缺口与取舍

alpha.13 已有根尝试、单操作 Worker 租约、READY 和 Git owner，但写入权还没有覆盖整次根执行。模型容量释放、单操作结束和实际根收尾具有不同生命周期。alpha.14 在根领取事务中追加独立意图，实际 Worker 持有 FileLock，并逐调用借用原锁；根结束回执保存前检查保护是否已释放。

系统锁与持久业务意图分别记录。进程退出会关闭 OS 锁，原 HELD 清单、UNKNOWN、未确认效果和预算仍保留；相同 scope 不自动接管。真实授予和释放回执具有不同数据库故障窗口，两者都必须保留实际 native 状态与原未确认 SQLite 状态，重开后核对原进程/覆盖/效果再建立新尝试。

Windows NIO fileKey 为空的首轮失败促成原生文件身份实现。FileChannel 仍承担实际锁，锁 marker 与文件身份同时核验；Windows 拒绝持有期间删除/改名，Unix 核对实际路径与原描述符 marker。相同物理别名与外国解析目标分别处理，不能为了兼容短路径放松目录范围。

本轮只保护任务数据范围内的 clone/worktree。当前目录、多核心数据目录共享同一源目录、已读版本冲突、外部编辑、原生 CLI 的权限边界和完整归档恢复仍是后续要求。

## 实际失败与修订

早期 Ledger/Guard 定向的 Windows 空 fileKey、短临时目录 NIO selector 和引擎编译错误分别保留。实际引擎第二轮六项均失败；原 SQLite、native 清单、源码及线程现场在 `.runtime/alpha14-engine-failure2/`。线程 dump 显示当时主测试线程在 Git 的 Windows 进程枚举中，没有据此证明释放死锁。对照原 DB 时间后把夹具有界等待改为 90 秒，并在意外根终态时提前报告。

引擎第三轮已通过实际 HTTP、第二 JVM 锁竞争与提前释放 409，随后发现模型请求缺少 workspaceWriteLeaseId；`beginModelRequest` 补复制原根来源。原失败在 `.runtime/alpha14-engine-failure3/`。第四轮真实引擎 6 / 6 通过，Ledger12 + Guard7 定向 19 / 19 通过；各次报告分别保存，不能把该定向结果代替完整构建。

第一次完整 `clean verify` 实际执行 272 项，13 failure、2 error、2 skip，没有 alpha.14 Jar。新增 Guard8 与真实引擎6均通过，原团队12项因 Windows 长短路径误判为越界失败；ManagedProcessLedger 的历史查询错误要求任务存在，导致两项进程门禁和一项 MCP 夹具返回404。原完整报告、编译类、源码和团队现场已归档 `.runtime/alpha14-core-failure1/`，共292个文件并保存逐文件摘要。

scope 现在按物理目录验证已保存别名，同时逐层拒绝预期现场的重定向。内部进程来源查询保留孤立写入历史，公开历史 API 仍验证任务存在；新增别名、新尝试、Windows短路径重开、外国父目录和孤立原Worker回归。

释放/心跳阻塞先由源码发现，再用真实 Worker 复现。新增门只延迟生产 native release，不改心跳或管道。首轮夹具括号编译失败，没有执行测试；第二轮两项均真实失败：根线程持有store monitor，原持久心跳序号分别保持7→7和8→8，RPC读取线程均BLOCKED于 `LocalExecutionWorker.receivedHeartbeat`，上游栈为 `WorkerProcess.event` 和 `WorkerRpc.read`。原源码、类、两个完整临时现场和报告保存在 `.runtime/alpha14-release-gate-reproduced2/`，365个文件有逐文件摘要。该失败尚未执行后续迟到停止断言。

修订将真实释放等待移出存储锁，保留Control至收尾完成；释放后在同一store临界区再次处理迟到的停止请求、提交根结束回执并移除Control。相同真实门后续核对原持久心跳推进、门内暂停/取消、实际锁关闭、原根终态与期间验收/导出409。最终全量、Web、实际Jar及发行物结果在执行后另行追加。

74 项兼容定向实际为 72 通过、1 failure、1 error，完整报告、源码与类保存在 `.runtime/alpha14-compatibility-targeted-failure1/`，共252个文件。Ledger15、ManagedProcessLedger16、MCP23 与 WorkspaceWriteEngine8 全部通过；真实释放门的取消/暂停均通过。团队两项失败分别为任务已进入等待验收但根收尾尚未结束时提前调用 accept，以及把现场准备混入子请求十五秒期限。夹具分别等待原 taskExecution.endedAt、从实际 RUNNING 开始子请求计时；不改变原子请求十五秒。两项复验 2 / 2 通过，报告在 `.runtime/alpha14-team-boundaries-success1/`，不回写74项的原失败。

第二轮完整 clean verify 为25套、278项，2 failure、0 error、2 skip，没有生成 alpha.14 Jar。完整报告、原源码/类、容量线程现场和两个成功释放门目录保存在 `.runtime/alpha14-core-failure2/`，302个文件附逐文件摘要。两份真实释放门的 storeMonitorHeld 均为 false，持久心跳分别13→14和14→15，原RPC读取线程均在读管道；原Worker停止与实际ZIP随目录保留。

团队容量失败现场 `team-capacity-failure-186841731057600` 的八秒到期时，资源快照 model=1；第二父任务完成基线晚于第一父任务约五秒，第二子快照直到07:49:47.779Z才完成，07:49:48.022Z线程快照仍在 `WorkspaceWriteLedger.scope()` 的Windows真实路径核验。第一父运行为 WAITING_CHILDREN，第二父运行为 WAITING_TOOL；未观察到两个父请求占满模型槽的互锁。夹具现在先让两个原父HTTP请求同时占满两个模型槽，再返回委派；子HTTP屏障仍为原八秒，以建立被验证的并发前提。

另一项失败为 WORKTREE 释放回执夹具的基线验证：Node原PID6584输出后返回0，原PowerShell父PID28760的已观察子PID55172@2026-11-10T07:56:37.433Z尚被列为未确认，核心保留UNKNOWN，尚未进入释放触发器窗口。完整临时目录另保存在 `.runtime/alpha14-workspace-baseline-failure2/`。随后用生产PowerShell参数、相同Node命令和原PID/出生时间进行八次自然退出观察，均未复现仍存活后代；该观察不能确定原子进程用途或原失败原因，也不能把原UNKNOWN改为成功。观察源码、原类和逐次记录在 `.runtime/alpha14-baseline-probe-classes/`、`.runtime/alpha14-baseline-shell-observation1/`；定向继续验证原故障窗口及自然/未确认子进程两项边界。

建立两父原请求同时占满容量的前提后，原八秒子HTTP屏障、两策略释放回执故障、自然退出与后台子进程UNKNOWN边界定向5 / 5通过，完整报告、源码与类保存在 `.runtime/alpha14-capacity-baseline-success1/`。第三轮clean verify最终25套、278项中276通过、2项Windows条件跳过，0失败/错误，全部虚拟线程载体2 / 2。最终Jar39209532字节、SHA-256 `1b628dd2f242082b678ec64c7734de305dc481b0ed563dd53445567992892e12`，原JNA两份Jar及Apache-2.0许可摘要在Boot Jar核对；完整Jar、源码、编译类、报告和两份成功释放门保存在 `.runtime/alpha14-final-core-evidence/`。最终两门心跳均13→14，原RPC读取线程RUNNABLE，storeMonitorHeld=false；原Worker停止事实和实际ZIP一并核对。该全量成功不回写第二轮基线子进程UNKNOWN失败。

第三轮当时的278项最终Jar与报告现保存在 `.runtime/alpha14-pre-native-process-core-evidence/`，不被后续产物覆盖。实际Jar首轮22项、6任务、5核心、26请求通过，独立停止报告完成后重复逐身份启动JVM的末尾清理被中止，原命令退出1与全部现场在 `.runtime/workspace-writes-4Y6cMC/`。新批量核验仍逐项核对原出生、发送信号前复核原句柄，1266身份补核对约1秒；错误出生未停止存活进程、正确出生停止原夹具的证据在 `.runtime/alpha14-process-batch-boundaries2/`。

第二轮CLONE授予故障的恢复等待超时，数据库没有缺失出生字段，原清理保留3项未确认。独立Windows有限查询对PID63096返回ACCESS_DENIED，而WMI记录它已被LogonUI复用：原出生09:50:03.271Z，新出生09:50:50.3713490Z。其他两个PID之后已不存在；这些之后的读数不改写失败时的观察。保留句柄的专属Node小夹具未复现问题，不能归因残留句柄。全部原数据库、来源、回执、未确认和原生查询在 `.runtime/workspace-writes-uEDh3Z/`。

核心新增只读系统进程快照，在Java句柄出生为空时补充元数据。固定[Wine10.0源码](https://github.com/wine-mirror/wine/blob/b073859675060c9211fcbccfd90e4e87520dc2c2/include/winternl.h)的CreationTime位于0x20，PID在32/64位分别0x44/0x50；源码blob `0bae9fbf3859bf72bd05f331cb460acbb0563a4f`与LGPL-2.1许可文件同存 `.runtime/research-process-snapshot-2026-11-10/`，仅参考API布局，没有复制实现。Microsoft文档说明该接口可能变化，解析有界缓冲、链偏移、重复PID和本JVM出生同时核验；失败继续无法核验。停止方法保持原句柄出生检查，不凭系统快照发送停止信号。独立夹具使用WMI，不共用核心解析代码。

首个固定PID原生对照未通过，之后同PID已不存在，原类和读数继续保留。后续527条前后稳定WMI记录与原生出生相同，但当时0个受限候选，受限专项对照未执行。定向23项22通过1条件跳过，实际缺失句柄回退、回退失败和坏布局边界通过，源码/类/报告在 `.runtime/alpha14-process-identity-targeted-success1/`。最终第四轮clean verify 26套/285项、282通过/3跳过、0失败/错误、载体2 / 2，新Jar 39213511字节、SHA-256 `ffaeeb1415ebde3d99863fc566492364b2b3852a5dfab50d2a5816722bdf5ff4`，完整证据在 `.runtime/alpha14-final-core-evidence/`。两份释放门心跳6→7和6→7、storeMonitorHeld=false，原RPC读管道、Worker停止和实际ZIP均核对。

最终第三轮实际Jar写入 22 项/26 请求通过，1447 原身份独立确认停止；工作区15、恢复11、队列13、协议29、独立客户端与实际包内JRE通过。桌面清理只处理已记录的原PID/出生，正常关闭后再写通过报告；各Jar验收核心固定2 / 2。27份Java的95条原注释保留，生产Web 69 项三轮通过并目视检查最终截图。正式摘要见[alpha.14本机验证](../qa/alpha14-validation-win32.json)；准确CI提交结果在推送后另行追加。完整F01–F21/M0–M3目标仍未完成。

内置Java首次锁验收在javaHome路径核对失败退出1。该次实际授予、借用拒绝与释放返回，原PID32712/出生2026-11-10T12:05:18.311Z后来独立确认停止；这些部分观察不改变原命令失败。该运行时native.encoding为GBK，JSON中的中文路径按UTF-8解码后损坏。验收命令增加sun.stdout.encoding/sun.stderr.encoding=UTF-8，第二轮实际包内Java通过，原Jar/Web/config字节不变。一次辅助PowerShell核验把原ISO字符串转换为DateTime后按本地格式传给探针而失败，后续直接读取JSON原字符串核验成功。原脚本、日志、回执、部分观察与停止依据在 `.runtime/alpha14-bundled-writer-failure1/`，成功记录在 `.runtime/alpha14-bundled-writer-artifact-usage-attempt2.json`。

准确初始提交 `a9f55d5f1f9640de1c0acfc70d9a0790244f99f2` 的 [run 42815606757](https://github.com/lihe-he/multi-agent/actions/runs/42815606757) 已终态 failure。Windows 整条成功；macOS、Ubuntu 的原核心日志均为285项、4失败、0错误、8跳过，失败是两策略的实际第二JVM竞争及暂停/取消释放门仍取得原锁。原用例的四个 `assertFalse(lockAvailable(...))` 未被删除或改成条件跳过。原日志与归档在 `.runtime/alpha14-ci/run-42815606757-attempt-1/`，三端原已执行与未执行步骤另按实际报告核验。

持锁Worker中的Unix路径marker核对使用 `Files.readString(writer.lock)`，打开并关闭了同一文件的另一描述符。固定Temurin来源的[FileLock文档](https://github.com/adoptium/jdk21u/blob/2ab10ca20c191701c3a9f3153bfc6e93d63714c1/src/java.base/share/classes/java/nio/channels/FileLock.java)明确说明同进程另一channel的关闭可解除原锁；本机src.zip与该文件摘要相同，blob为 `b5c0ba4a81f361ddd416fcba0bae4a5c60c394a9`。同提交的[UnixFileDispatcherImpl.c](https://github.com/adoptium/jdk21u/blob/2ab10ca20c191701c3a9f3153bfc6e93d63714c1/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)实际使用fcntl F_SETLK/F_SETLKW，blob `a24f0503e557955c074814de05ebea5507056624`与目录API核对。修订只从原channel读取marker，路径另核对已保存的NIO文件身份与大小，不打开第二描述符；已完成/UNKNOWN、原预算与进程核对规则继续使用原协议。

源下载首个原生文件路径返回404；目录API确认实际文件名为UnixFileDispatcherImpl.c。第二轮两文件已下载且字节核对完成，文档断言因Java与virtual machine跨Javadoc行而失败；第三轮复用原字节、按文档空白核对通过，未重复下载。原失败、固定GPL-2.0 with Classpath Exception许可与最终核验在 `.runtime/alpha14-posix-lock-research/`、`-attempt2/`、`-attempt3/`。只参考文档与原生API，没有复制上游实现。原本机285项Jar/类/报告已移存 `.runtime/alpha14-pre-posix-core-evidence/`；原九步骤、安装器与app.asar保存在 `.runtime/alpha14-pre-posix-local-qa/`，摘要映射为 `.runtime/alpha14-pre-posix-artifact-preservation.json`。POSIX修订的本机最终Jar与准确三端结果随后追加。

2026-11-11，修订后的第五轮clean verify为26套/285项、282通过/3条件跳过、0失败/错误，原载体仍为2 / 2。准确Jar为39213501字节，SHA-256 `f262f5e88fff99b01d3a4881673afd0c41821836aa5bfa99a8c7ef816cd86b12`。最终九组顺序步骤均通过，包含写入22项/26请求、工作区15、恢复11、队列13、协议29、独立客户端、重新打包、打包启动与实际内置JRE。写入报告独立确认1379个原身份停止；两份释放心跳门均6→7，原断言和UNKNOWN/预算历史保留。新安装器152607646字节，SHA-256 `c420568025e08536667374a841f7cf79ba71d1ba8693e7b224b178c579e4375c`，未签名，未重复完整安装/卸载。Web及17项守卫源码未变化，已核对原通过证据与当前摘要；不将其记为新执行轮次。[本机汇总](../qa/alpha14-validation-win32.json)及 `.runtime/alpha14-final-local-qa/` 保存准确Jar/类/命令/产物来源，修订提交的三端结果另行核验。

2026-11-11，准确修订提交 `cff00fb3bca47cf13986928146aa9879f06980f4` 的 [run 42941627882](https://github.com/lihe-he/multi-agent/actions/runs/42941627882) 三端整条success。三份实际evidence ZIP均完整收取、核对大小与GitHub SHA-256，并读取原XML、最终Jar/Web/config、一次编译夹具与隐藏现场；每端核心26套/285项、载体2 / 2，win32 283通过/2条件跳过，darwin 277通过/8条件跳过，linux 277通过/8条件跳过，三端UI69。实际写入22、工作区15、协议29、恢复11、队列13、独立客户端、打包启动和内置JRE锁授予/借用/释放逐项核验，四个故障窗口及原UNKNOWN/预算历史保留；原PID/出生独立停止数量为win32 52、darwin 52、linux 52。完整摘要、每套XML和原跳过原因见[正式CI记录](../qa/alpha14-ci-writers-receipt.json)。初始失败run保留三端日志及两份Unix部分证据归档，但收取曾被中止，没有完整receipt，不能宣称全部初始归档已核验。最终发行包artifact只保存元数据，未下载核验完整发行归档。alpha.15后续源码单独实施，此记录以准确alpha.14提交和原归档为依据。
