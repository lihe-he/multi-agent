# 检查点与撤销研究

本轮针对 F04、计划书9.4/13.2/13.3。固定来源为 OpenCode `59ed43f0b8da891f03a7333d540a7b0c428049ea` 与 Git `67ad42147a7acc2af6074753ebd03d904476118f`。18份原源码、文档和许可证已重新核对字节数、SHA-256 与 Git blob，具体 URL、路径、原摘要和观察时间见[来源清单](./checkpoint-sources-2026-11-15.json)。只参考设计，未复制上游实现、未运行上游测试、未增加运行时依赖。

OpenCode 的 snapshot 使用项目/实际 worktree 关联的独立内容仓库；scope 先解析真实路径，捕获后按固定树引用比较和恢复，忽略与单文件限额分开处理。session revert 按会话消息边界查原助手记录，为每个受影响文件选最早的原阶段快照，保存撤销前现场，再发布撤销事件。界面状态机区分暂存撤销、清除和提交，并处理迟到状态。

这些思路支持本项目保存原归档引用、独立预览和具体文件确认。本项目当前持有实际根写入租约、原 Worker/操作编号与 UNKNOWN 账本，因此实现还需要核对当前现场、保存原进度，并将文件回执与核心成功发布分别记录。将上游树恢复直接用于本项目不能独立保全原 index 字节、用户后续修改与原未知执行。

Git write-tree 根据完整合并的 index 生成树，read-tree/restore 与工作目录的关系由选项决定；update-index 文档另外说明执行模式、assume-unchanged、skip-worktree 和共享 index。仅保存工作树内容或重建树不能等同保存全部 index 字节。本项目保存完整原 index 和暂存 blob，保持 HEAD，只有原 index 前提仍满足时才通过独占 index 锁恢复；不完整 index 明确阻止恢复。

当前[协议](../checkpoint-contract.md)对应 immutable 清单/SHA-256对象、原成功工具回调、事务排队、原根 Worker 撤销、逐文件进度、完成后重新验证和完整引用导出。此研究不改变 F01–F21 / M0–M3 的退出条件。

## 原失败与当前验证进度

最初归档定向15项为10通过、1失败、3错误、1 Windows POSIX条件跳过。清单 JSON 读回后小整数为 Integer，现场文件大小为 Long，Map.equals 误报文件身份变化。改为逐字段比较身份、数值、时间与权限，原内容摘要检查保留。第二轮14通过、1条件跳过，0失败/错误；接续后新增原捕获进程与预览核验，当前编译后的同15项仍14通过、1条件跳过。原各次源码、报告和失败不回写。

第一次新增集成编译在 ExecutionStore 检查点查询的两处未声明异常处失败。修订只针对新查询，保留404/403业务拒绝与健康存储的区别，随后当前核心编译和归档回归通过。现正在运行真实 Git/原 Worker 集成，最终完整核心、实际普通 Jar/Web、发行物和三端 CI 尚待本轮收尾核验。

第一轮 Chromium UI 的32项都在启动阶段失败，原因为锁定 Playwright版本所需的headless shell不在本机默认缓存。原页面断言尚未执行；之后下载同锁定版本至任务本地缓存并复验32/32。第一轮 Edge 为30通过、2失败，排队后缓存的许可使保存按钮仍可点击；界面新增任务状态核对，第二轮32/32。补充失效成果说明及两项回归后，当前 Edge34/34。六主题/两宽度的实际文字对比度、布局和原附件均随各轮冻结。

归档捕获还在逐个暂存对象启动Git。后续先记录100个不同对象的原性能和完整字节，再验证有界批量读取，不能仅用耗时下降替代身份/内容核验。旧报告与对照类字节会分别保留。

补充固定 Git 提交的 `git-cat-file.adoc` 和 `config/core.adoc`：批协议分别提供对象信息和以长度界定的内容，必须核对完整对象编号、类型、长度、分隔符和结束；`core.fsync` 的对象持久化配置由具体组件控制。本项目按最多256项和2 MiB输出分批，逐项核对 SHA-1/SHA-256 原 Git blob，归档文件同步后发布引用。恢复对象显式使用命令级 `core.fsync=objects` 和 `core.fsyncMethod=fsync`，POSIX 目录项另做同步；这些实现与文件回执不等于断电恢复实测。

第一轮批量归档实测停在原生管道读取：两个虚拟载体均阻塞，虚拟 stdin 写线程无法得到载体。原线程栈、夹具、中断与四个原进程身份停止回执保留于 `.runtime/alpha19-core-batch-archive-attempt1`。输入改用独立平台线程，标准输入、输出和错误输出均完成后才接受进程回执。第二、三轮因新增导出夹具 `close()` 的异常声明而编译失败，原报告保留。第四轮53项为7失败、7错误、1条件跳过；批协议22项全部通过，跨264个对象和SHA-256仓库通过。新导出夹具补齐原task记录并缩减重复清单字段，仍保留16 MiB清单边界、128 MiB和100000项完整导出门禁。

接续14项定向为10通过、4失败，旧READY clone、36条完整历史、对象去重、原日志变化、原回执摘要和两种出口限额通过。四项损坏/缺失确实拒绝完整导出，后续比较因构造时Long与SQLite读回Integer不同误报；已改为同一SQLite来源的破坏前后记录比较，没有放宽409和原历史保全断言。原 Worker 首轮19项的7失败、2错误继续保留；两个原后代身份后来独立核验已停止，未发送停止请求，原失败时序与原因仍未证明。

原两次100对象对照分别为15388.1482 / 14028.2372 ms；初次批量实测为4863.8389 / 3208.3733 ms。按原捕获JVM的PID与出生时间派生，每次顶层Git启动均从220降到22；原 `gitProcessStarts` 字段包含后代观测，不能直接作为顶层启动数。两侧均核对100个不同原文件的完整字节、摘要和源HEAD/index。派生回执为 `.runtime/alpha19-performance-comparison-attempt1.json`。这只是本机两次测量，最终构建性能与长期负载验收另行记录。

接续 Worker 集成23项为22通过、1失败。worktree 完整计划未在原90秒内结束；其原数据库后来保存的根收尾耗时为100.1495556秒。原临时目录的109个文件已按完整字节冻结，366个原PID/出生身份随后独立核验均已停止，没有发送停止请求；这次读取不重建原截止时的进程状态，失败仍保留。构造失败的后续夹具补充原记录保存和 Worker 关闭，继续传播原断言。

固定 `git-rev-parse.adoc` 和本机 Git CLI 实测支持合并 HEAD、对象格式、Git/index目录与 shared index 读取。捕获前后仍重新取回整组并逐字段比较，完整 index、暂存清单及文件指纹独立核对；归属核验的 Git/共享目录也由同一回执提供。首轮合并读取21项为2失败、17错误、2条件跳过，主要原因是解析把未启用 split index 的省略输出误当成空白行。实际CLI输出和固定文档已核对，解析只将末项缺省作为无共享 index，其他缺失或额外字段仍拒绝，并新增真实 split index 保存与不可完整恢复的回归。该轮另外一项原子进程停止核验失败保留为UNKNOWN；原身份随后核验已停止，原失败原因仍未证明。

只读权限审查发现 POSIX 恢复先设置只读模式，再以写方式重开临时文件进行同步，可能在非特权账户上失败。改为保持原写通道打开，写完、恢复权限并再次同步后才发布文件；临时目录项删除也同步。新增真实只读目标回归，Windows 条件跳过，macOS/Linux 验证结果单独记录。

OpenCode来源沿用原 MIT许可证；Git文档为GPL-2.0来源。本项目只参考语义并调用用户安装的Git，不分发复制的Git实现。完整许可证、固定来源与已有第三方运行时继续见[第三方依赖记录](../third-party-dependencies.md)。

补充固定 `git-ls-files.adoc`、`gitformat-index.adoc`、`git-worktree.adoc` 和 `gitrepository-layout.adoc`。捕获清单使用同次 `ls-files -t --stage --cached --others --exclude-standard -z`，由状态前缀区分 index 记录与未跟踪文件的完整字面路径，避免含 tab 的未跟踪名字被解析为暂存记录。捕获收尾重新读取清单和全部文件字节；当前 Git 回执与原选择记录分别核对 `.git`、公共目录、worktree 反向关联、锁和 detached HEAD。普通 Windows 短路径可以匹配实际目录，重解析目录仍被拒绝。旧 clone 的准备和 READY 恢复入口返回实际父目录下的工作目录，旧任务记录保留。

完整 `full-attempt2` 为34套/433项，411通过、13失败、1错误、8条件跳过，退出码1，42分钟。团队12项均失败，其中9项原 XML 明确显示旧 clone 短路径被检查点归属拒绝，另外3项子任务等待断言未证明具有同一原因。重复审查在文字终态先到、原根未收尾时被409拒绝；工作区选择在原15秒边界超时。默认 JUnit 清理已移除这些原 TempDir，原 XML、日志、源码、编译类和一个团队子启动快照仍保留；后来的复现不能补造原文件或15秒现场。运行中成功取得的线程栈属于后来通过的 WorkspaceWrite 套件，不能据此解释原团队或选择失败。

第一个完整失败派生摘要未消费自闭合成功 testcase，将重复审查和工作区选择两项失败名称错配到较早的方法。原 XML 与套件计数正确；`.runtime/alpha19-full-failure-summary-attempt2.json` 保存更正和原摘要 SHA-256，两个派生文件均保留。

三轮原边界定向分别为1通过/3失败、3通过/1失败、3通过/1失败。修订后的真实 MCP 团队分别在54.35秒和49.94秒结束；选择夹具仍在15秒截止时失败。捕获优化后的新超时快照已进入 implement、保存6个检查点，旧快照为 analysis、4个检查点；各轮复制的旧 target 快照按原 recordedAt 与本轮起止时间区分，不能计为本轮现场。

工作区选择单元夹具原先已模拟模型与验证，本轮在两处匿名引擎中隔离自动归档依赖，保留原15秒、三次模型领取、真实 Git、SQLite、规则和归属断言。普通引擎仍执行真实捕获，由 CheckpointEngineTest 的实际 Worker 和最终普通 Jar/Web 独立验收。这项夹具调整不能证明普通生产完整计划满足15秒。最新归属拒绝、字面文件名及上述完整回归正在执行，最终构建、性能和三端 CI 结果后续追加。

接续 `final-regression-attempt1` 四套71项为67通过、2失败、2 Windows POSIX条件跳过，0错误，20:06分钟，源码前后摘要一致。归档25通过/2条件跳过，引擎24/24、工作区选择8/8；团队10通过/2失败。独立提案在原60秒内未到终态，子HTTP准备在原20秒屏障内未进入请求。两份原失败复制保留逐项文件摘要，被占用的 `.core.lock` 与 `writer.lock` 记入 `copyGaps`，`completeCopy=false`。后续只在独立派生SQLite副本中查询，复制的数据库/WAL/SHM按原字节核对，两份 `quick_check` 为ok；这些逐项稳定复制不是原子数据库快照，不能重建原60/20秒截止瞬间。

单次捕获按SHA-256去重对象发布，实际工作文件和暂存对象仍分别读取；首次对象保存仍完整同步与核验。单次清单读回按不同SHA-256实际读取一次，只缓存实际长度和Git哈希，各文件/index/暂存引用仍分别核对声明长度与各自限额，新读取与新捕获不复用旧核验结果。新增真实Git回归证明重复文件引用仍核对各自长度，256001字节对象即使先以index限额读取，也不能绕过256000字节暂存引用限额；夹具显式使用改写后清单的新摘要，原HEAD/index/业务文件保全保持。`object-reuse-regression-attempt1` 四项为3通过/1失败，原子HTTP取消通过，独立提案仍在60秒超时，约第58秒进入review。原失败保持。

相同四文件探针、载体2/2的 `repaired-attempt2` 捕获耗时为CLONE 2333.0417/1729.295ms、WORKTREE 1826.4752/1977.8333ms；对象去重后的 `repaired-attempt3` 为1873.3311/1391.2706ms、1503.306/1492.799ms，分别减少19.70%/19.55%/17.69%/24.52%。两侧均逐文件核对完整字节及源HEAD/index，每次仍为六次顶层Git调用。此前JFR首次启动参数解析失败、初版派生类名和五层栈范围不足均保留；从同一原录制按64层栈只读重渲染的更正派生另存。新的候选录制仍显示目录和身份查询及进程快照样本，样本仅支持定位重复工作，不构成精确耗时分摊或长期性能验收。

固定Git文档支持以状态标记区分同次文件成员与暂存模式。普通指纹改为一次 `ls-files -t --stage --cached --others --exclude-standard -z`，原v2算法、未合并路径最后阶段的模式、未跟踪字面文件名、忽略、删除文件与新鲜完整字节读取保持。第一份兼容探针因Windows禁止 `?` 文件名在准备阶段失败，原日志/源码/回执保留。修正后旧实现的真实SHA-1/SHA-256边界现场分别冻结于 `fingerprint-compatibility-baseline-attempt2`，新实现 `candidate-attempt1` 两种对象格式的原版本及同长度改写版本均一致，原HEAD和index字节保全；UTF-8 `facts.json` 保存字面中文路径，原Java标准输出使用本机编码的日志不代替此文件。

`fingerprint-regression-attempt1` 十项为9通过、1 Windows POSIX条件跳过，0失败/错误，2:32分钟，源码摘要一致。包括两项固定旧版本兼容回归、四项原指纹检查、两个原对象引用边界及两项原团队失败。独立提案和子HTTP取消均在原60/20秒边界通过，JUnit含准备/收尾耗时分别为60.419/26.670秒；这些时间不等同根执行单段耗时。新增两个参数用例后预期完整核心为34套/443项。

`full-attempt3` 在移动旧target证据时被Node `rename` 的EPERM拒绝，Maven和测试尚未启动；原错误、冻结源码和随后四目录字节核验写入独立失败回执。相同目标由原生PowerShell成功移动，原EPERM具体原因未证明。其余七份旧target证据的304个文件在移动前后完整摘要相同，旧冻结运行副本保持。`full-attempt4` 随后正常启动干净构建；完整核心、最终普通Jar/Web、最终100对象性能、发行与准确提交的三端CI仍以各自最终回执验收。

完整回归期间的单次只读JVM线程观察实际位于 `ExecutionStoreTest.anotherJavaProcessCannotOwnTheSameDataDirectory` 的子进程等待。观察时的较早套件日志不能替代线程中的实际测试位置；原线程、时间、进程出生与更正派生分别保留。随后原XML确认检查点集成19/19、完整引用导出13/13通过，归档29项为27通过/2 Windows POSIX条件跳过。这个快照不解释更早检查点超时，也不是长期活性证明。

源码审查发现工具完成捕获使用三参数入口，未显式携带核心保存的原准备归属。`tool-owner-check-baseline-attempt1` 的真实HTTP/Git交错探针在CLONE与WORKTREE上分别改写 `selection.json` 中的 `owner`，保留实际Git路径、HEAD/index与原文件；旧运行器仍保存一个新的工具归档、返回一个检查点回调，并继续第二次模型请求。原工具操作为CONFIRMED，两侧原模型响应均为SUCCEEDED。此探针核验运行器、归档与HTTP边界，未声称是最终普通Worker/SQLite可见引用验收。修复将从核心上下文传入原归属，在捕获前后核对，不改写已确认工具回执；新源码的定向与完整验证另行记录。

`full-attempt4` 的原完整终态为34套/443项，435通过/8条件跳过，0失败或错误，51:42分钟，源码前后摘要一致。检查点引擎19/19、完整引用导出13/13、团队12/12、原工作台引擎24/24及选择8/8通过。两个原团队用例的JUnit准备与收尾总耗时为59.209/26.701秒，原60/20秒业务等待保持。这份Jar早于随后原归属修复，不能作为最新最终产物验收。

原归属修复后的 `tool-owner-regression-attempt1` 七项全部通过，0失败/错误/跳过，4:43分钟，源码摘要一致。所选真实完整计划CLONE/WORKTREE两项通过，原请求归属拒绝一项通过，新核心上下文与工具交错两项通过，两个原团队边界通过；各套JUnit总耗时为147.1、9.359、16.32和81.42秒，不能视为单段执行时限。`tool-owner-check-candidate-attempt1` 使用同一新编译的冻结类，两种策略均为WAITING_INPUT、一次模型请求、零新工具检查点回调、清单仍一份；原工具CONFIRMED、原模型SUCCEEDED和HEAD/index/工作字节保全，改写记录也保留。该探针的HTTP/运行器/归档范围保持，最终普通Worker与SQLite可见引用另验。新完整 `full-attempt5` 已开始，预期34套/445项；原443项构建、探针与各失败均分别保存。

`full-attempt5` 随后完成34套/445项，437通过/8条件跳过，0失败或错误，31:58分钟，源码摘要一致。Edge和Chromium headless shell各196/196、原162项及新增检查点34项保留。原普通Jar检查点脚本16/16通过，撤销刚完成时原index字节断言通过；独立最终证据复核随后发现CLONE和WORKTREE的最终index摘要与目标不同，不能将原脚本终态当作完整验收。整份774文件/48330934字节现场、原脚本、报告和复现错误冻结于 `.runtime/alpha19-index-mismatch-attempt1/`。

原始二进制解析确认两处index仅app.txt和binary.dat的ctime/mtime秒与纳秒字段变化；对象编号、模式、标记和TREE扩展均保持。独立副本上的命令矩阵确认本机Git `2.53.0.windows.2` 的 `git diff HEAD` 即使指定 `--no-optional-locks`，仍会刷新时间缓存。固定Git提交 `67ad42147a7acc2af6074753ebd03d904476118f` 的 `builtin/diff.c`、`diff.c` 和 `Documentation/config/diff.adoc` 与此行为一致：`diff.autoRefreshIndex`默认启用，`refresh_index_quietly`独立取得index锁并更新。新增两种实际撤销回归在旧实现上均于查看差异后的index字节断言失败，原失败另存。

单独关闭缓存刷新能保留index，但快捷路径清单仍可能包含纯时间变化；原`.env`内容未改的门禁回归复现了该误判。最终方案通过`GIT_INDEX_FILE`为每次差异读取提供私有原index副本，完整内容核对和缓存刷新只作用于该副本，收尾重查原index字节。私有命令禁用split index写入，并核对原共享对象保持。固定`Documentation/git.adoc`约定alternate index环境变量；这四份新增来源使当前清单达到22份，原第二轮21份来源回执和首轮不存在测试文件路径的404分别保留。未复制Git实现、未新增运行时依赖。私有目录先规范化原数据根，原普通Windows短路径可解析，新建重解析入口仍拒绝。各轮候选失败、通过和最新完整Jar验收继续分别记录。

最终第7轮34套/447项为439通过/8条件跳过，0失败或错误，源码摘要保持。普通最终Jar SHA-256 `c912bf2dbbbcf7d3900762ef00a55acd3978923867d87259f4736cb9984ca40e`；两浏览器各196/196，原162项和新增检查点34项及六主题/两宽度测量核对。普通Jar/原Worker/真实Git的16项检查点验收包含4任务/32模型请求/3次核心启动、原index与用户后续文件、两处SQLite故障的原APPLIED/ACK/UNKNOWN、完整引用ZIP及人工核对后旧版本导出409。故障时原写入租约仍为HELD且未确认释放；锁可取得时另有原PID/出生停止观测。原界面5秒断言的一次失败保留，后续同断言通过未确定其原原因。原差异、协议、恢复、队列、工作区、写入、文件版本、独立客户端及发行/内置JRE分别通过，冻结与范围见[本机最终验证](../qa/alpha19-validation-win32.json)。最终100对象的两次捕获9633.4 / 7927.8ms、原顶层Git启动对照220→6 / 220→6，完整字节及源HEAD/index核对；仍是本机两次测量。最新Java审计保留原110条注释，67条新增Javadoc均为标准多行。第六轮暂停后的11套/149项部分报告、原失败、UNKNOWN、字节缺口和15秒选择夹具隔离说明不回写；准确生产提交三端CI随后另验，完整F01–F21/M0–M3目标继续实施。

发行打包第一次在固定官方NSIS下载的Windows wsarecv连接超时处失败，未产出安装器；原日志、源码、失败回执及283份部分发行文件已保全。产品打包助手未改写，现有固定尺寸/SHA-512验证器经本机代理取得缓存后，仅重跑打包及其后续验收。第2次打包和发行原生检查通过；该后续通过不更改原下载失败，也不构成长时网络可靠性证明。缓存预取助手在运行前的一次缺少闭合括号语法失败单独记录。

2026-11-16 的 alpha.19 原提交 `40e98fa9a1e134ec75608681c5fc654d97f1f474` 在 [run 43800367811](https://github.com/lihe-he/multi-agent/actions/runs/43800367811) 的 macOS 核心报告为447项、1失败、1错误、9条件跳过。上传证据ZIP的7411479字节及SHA-256 `7627a561c0af9c9008863a5d47de4c1b5194141a4282778af9804d1ddc08b406` 与GitHub digest一致；原检查点失败夹具70份文件逐项核对。原READY清单保存 `/private/var/folders/...`，JVM临时目录为 `/var/folders/...`；旧路径断言根据原源码和规范清单重建，原断言点的字面路径和符号链接元数据没有独立保存。产品的POSIX符号别名拒绝边界保留，夹具继续验证未解析数据路径的准备/恢复，并使用规范POSIX任务指针；Windows普通目录短路径保持原检查。

另一原错误发生在Diff测试保存普通现场的 `Files.walk`，枚举内部 `.git/objects/maintenance.lock` 时文件已不可读取。原ON_SUCCESS临时目录未被CI上传，无法确定原锁字节、创建/删除参与者和准确时序。跟进使用 `walkFileTree` 在枚举前排除顶层Git目录，完整普通文件哈希及原HEAD/index/config断言保留，普通工作区读取错误继续抛出。现有失败保存扩展也接入Diff套件；新增POSIX实际不可读对象目录检查和LEGACY/CLONE/WORKTREE符号别名拒绝，原清单、归属、index、HEAD与文件字节分别保全。

跟进两套完整Windows回归65项、59通过/6条件跳过，原63项及原等待表达式保留；所有138个生产class与原冻结Jar逐项相同，核心生产源码/POM和原验收产物保持原字节。原两条Java注释保持，新增一条标准多行Javadoc。JUnit对Windows停用整个POSIX参数化模板，Windows全套预期449项，macOS/Linux预期451项。准确来源、原错误XML、70份原引用、条件跳过及覆盖缺口见[夹具跟进记录](../qa/alpha19-ci-fixture-followup-win32.json)。原运行已完整结束，Windows/Ubuntu作业成功，macOS核心失败；后续准确SHA的成功沿独立回执记录。

两条alpha.19 CI随后均完整收齐三端日志和证据ZIP。原`40e98fa9a1e134ec75608681c5fc654d97f1f474`的 [run 43800367811](https://github.com/lihe-he/multi-agent/actions/runs/43800367811) 终态failure，Windows447项为441通过/6跳过，Linux447项为438通过/9跳过，macOS447项为1失败/1错误/9跳过；原70份检查点夹具再次逐项核对，消失的Git锁文件原夹具、参与者、字节与时序缺口保持。见[原失败回执](../qa/alpha19-ci-original-failure-receipt.json)。

测试夹具跟进`41a5145b8578fb55f5db8b2e31023c8685298d46`的 [run 43805087356](https://github.com/lihe-he/multi-agent/actions/runs/43805087356) 也终态failure。Windows完整作业成功，449项为441通过/8跳过，完整UI196、实际检查点16、原生命周期、内置JRE及UNKNOWN历史独立复核。Ubuntu451项为1失败/0错误/9跳过，macOS451项为2失败/0错误/9跳过；三份ZIP digest及两端原失败夹具300份文件逐项核对。修订的GitDiff/归档套件及四个POSIX新增用例均通过。Ubuntu原两父团队在首次检查点捕获失败后，另一父请求又未满足15秒屏障；macOS还有原restart-pause夹具重开后已领取动作，原快照为重开前PENDING/count0、重开后CLAIMED/count1，原恢复次数仍为0。见[跟进失败回执](../qa/alpha19-ci-followup-failure-receipt.json)。这些原CI只保存通用包装I/O错误，具体目录创建异常、参与者与准确时序仍未捕获。

独立真实复现为八个原归属CLONE现场共用同一数据根，所有首次`a.txt`读取到达后同时发布。原生产类得到六个READY、两个包装`FileAlreadyExistsException`，异常明确指向共享`data/checkpoints`。原298份夹具文件、六份成功清单的文件/blob/index字节、源HEAD/index和138个与原冻结Jar相同的生产class均独立核对，证据在`.runtime/alpha19-shared-directory-reproduction-audit-attempt1/receipt.json`。这证明原实现存在共享目录创建竞争；不据此重建早前CI未保留的内部异常时间线。

运行时修复仅捕获目录创建的`FileAlreadyExistsException`，拒绝非目录，继续逐级核对NOFOLLOW和规范路径，并在核对后同步父目录；其他I/O异常继续传播。restart-pause原用例在替换执行器恢复前建立阻塞，补核对原待领取编号、PENDING与count0；原恢复逻辑、断言和时限保持。八现场并发、三项原未领取暂停/取消/重开及原两父容量用例共五项定向全部通过，源码摘要一致。

新完整`runtime-repair-full-attempt1`为34套/450项，440通过/10条件跳过，0失败或错误，42:40分钟，载体2/2，源码前后摘要一致。原447项及更早356项逐项保留；138个生产class中仅`WorkspaceCheckpointArchive`改变，依赖、许可证、版本和未过滤资源保持。新Jar SHA-256 `048141550c1bf8f9e46dfd39d5bb12f559c4b0d36c2b725e9946da0a7eff5508`的普通检查点16、差异14、协议29、恢复11、队列13、工作区15、写入22及文件版本9均通过；新发行程序禁用外部Java后的启动、原身份停止、包内核心与Web/桌面字节及内置JRE写入核对，守卫21/21。安装器152744012字节、SHA-256 `01922b6904659b7ec921fd89400a1f469044080a63d867e518cdbf03f8e07518`，NotSigned。原UI源码与两浏览器196项对应Web字节保持，本次运行时修复未重跑UI全套。三份受改Java的原18条注释保持，新增三条Javadoc为标准多行。

内置JRE探针首轮在原写入QA仍指向旧Jar的摘要门禁处失败，未开始原生写入；新Jar写入22项完整通过后，原探针第二轮通过，首轮回执保留。汇总收集器首轮将原138项class核对数组误当布尔值，启动复制前即失败，原收集器源码和失败另存；修正字段读取后完整收集通过，没有重复产品验收或改写原回执。15份工具生成的历史报告/截图另存后恢复原字节，新验收冻结在`.runtime/alpha19-runtime-repair-final-local-qa-attempt1/`，正式结果见[修复验证](../qa/alpha19-runtime-repair-validation-win32.json)。新运行时修复的准确生产提交仍需三端CI，预期win32为450项、darwin/linux为452项；原失败、UNKNOWN和证据缺口保持，完整F01–F21/M0–M3继续实施。

运行时修复提交`38155bb14e3eab977d30f2ad4d25603c18e11922`的 [run 43816939938](https://github.com/lihe-he/multi-agent/actions/runs/43816939938) 随后终态failure，三份完整作业日志和证据ZIP已按GitHub digest独立核对。Windows完整成功，450核心为442通过/8条件跳过，UI196和原协议、恢复、工作区、写入、文件版本、检查点、发行/内置JRE生命周期独立复验；成功Windows不改变整条运行失败。Windows证据ZIP98641894字节、SHA-256 `c220c02af4e7b92b4e083e574638fbcd1407735b389bcb60c983b70ac329278c`，发行附件仅列出、未下载核对。Ubuntu452项为1失败/9跳过，原用例预期UNKNOWN而实际PAUSED；原撤销日志APPLIED，八个路径均已恢复，94份原失败夹具完整核对。原生取消何时到达首个写入后的边界仍未观测。macOS核心452项、0失败/错误/9跳过，UI195通过/1失败；原trace明确保存原focus时按钮disabled，该证据范围保留，不据此扩大为其他生产UI缺陷。见[本次原CI失败回执](../qa/alpha19-per-file-boundary-ci-original-failure-receipt.json)。

撤销原停止供应器此前只读取Worker本地取消标记、停止标记与RPC连接；异步取消未到达时，后续文件边界缺少同步的核心授权核对。当前RESTORE_CHECKPOINT沿用原每个边界，同步发送原CHECK_CURRENT并等待回执；不改变原文件恢复、取消断言、预算或时限。新增CLONE/WORKTREE回归在第一个已记录文件后提升原repositoryBinding版本，使原核心拒绝后续写入。25项定向核心全部通过；独立读取两份完整导出ZIP、原日志、快照、补丁和原PID/出生停止记录，两个现场均只有`revoke-0.txt`恢复，另七个保持`agent`内容，任务、根动作、SQLite撤销记录与文件日志均UNKNOWN，原模型预算为0、恢复调用为1、旧resume被409拒绝。成功用例的临时工作区原文件未另行复制，原效果字节核验使用导出补丁，范围明确记录于`.runtime/alpha19-per-file-revocation-independent-audit-attempt1/receipt.json`；这不补造旧Ubuntu取消的未观测时序。

原键盘测试仅在focus/Enter前增加按钮enabled等待，原focus、键盘操作、路径计数及全部主题/宽度断言保持。Edge定向34/34与Chromium headless shell定向34/34通过，无跳过、重试或失败。生产Web完整字节保持，因此原两浏览器各196项完整结果继续作为原验收保存，本次定向结果单独记录；未重复全量UI，也不将定向34项计为新全量196项。

新完整`per-file-boundary-repair-full-attempt1`为34套/452项，442通过/10条件跳过，零失败或错误，45:34分钟，载体2/2，源码前后摘要一致。更早356/447/450项逐项保留，138个生产class与实际编译/Jar逐项绑定。新Jar SHA-256 `7db450e119112484f971c7d2276a1a39a13438cce35484f4598370ef1adf568f`；相对450项产物，WorkerProcessMain、Call及两个RemoteObserver class变化，后两者只改变LineNumberTable。固定原/新Jar的class逐属性检查证明去除这类调试属性后字节完全相同，原第一轮只预期两个变化class的失败和部分目录保留。依赖、许可证与版本不变。两份受改Java保留原六条注释，新增两条标准多行Javadoc，见[核心冻结](../qa/alpha19-per-file-boundary-repair-core-win32.json)。

同一新普通Jar的检查点16、协议29、队列13、恢复11、工作区15、写入22、文件版本9、差异14顺序通过，实际来源、UNKNOWN历史、完整引用、现场字节及原进程停止核对。原构建器的新安装器152743970字节、SHA-256 `54e988d3ce3166ebf7ae1d4fa17d724f32382a644d678cbb847635d8af0375f6`，NotSigned。独立非输入桌面中的发行程序禁用外部Java后启动通过，内置JRE实际写入通过，守卫21/21；包内Jar和全部Web/桌面字节分别核对，用户输入桌面保持，测试桌面句柄关闭。接受源码206份和12组回执冻结于`.runtime/alpha19-per-file-boundary-repair-final-local-qa-attempt2/`，见[本轮完整验证](../qa/alpha19-per-file-boundary-repair-validation-win32.json)。汇总收集首轮在Windows报告路径的文件名提取处复制失败，原收集器、失败和部分目录保留；改用系统basename后完成第二轮收集，没有重跑产品验收。114份历史报告/截图从原HEAD保全，其中21份被验收更新或有换行差异的文件另存后恢复原HEAD字节。新准确生产提交仍需三端CI，预期win32为452项、darwin/linux为454项；alpha.20终端仍在忽略目录暂存，完整F01–F21/M0–M3目标继续实施。
