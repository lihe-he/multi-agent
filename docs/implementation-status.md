# 实施与验收记录

产品基线：[多 Agent 智能研发工作台计划书 1.1](./多Agent智能研发工作台计划书.md)。本文记录实现和证据，不改变计划中的验收标准。更新日期：2026-11-18。

## 0.2.0-alpha.21 会话网页预览

F05 已增加本机开发桌面预览：窄IPC与原生WebContentsView、核心/项目/会话/代次分区、每会话端口授权、多标签与活动选择、脱敏URL持久化、真实登录恢复、站点清理、代次账本及撤权销毁。工作台弹窗和任务详情暂停原生页面，独立Web明确能力边界。协议见[会话预览约定](./preview-contract.md)，固定来源与原失败见[预览研究](./research/previews-2026-11-17.md)。

| 检查 | 准确结果与范围 |
| --- | --- |
| 核心配置与完整回归 | Java21定向PreviewCatalogTest 8/8通过，涵盖重启、项目归属、关闭会话、旧版本、并发、端口撤销、URL脱敏和回环表达。当前完整verify为45套/555项，545通过、10条件跳过、零失败或错误，41分22秒，源码摘要保持；增量编译并重新打包，未声称clean构建。最终Jar SHA-256为`0d7fb612133569d6165551306cd9be8a9376a5782347e122ae647b8814fe903f`。见[完整核心](./qa/alpha21-core-build-win32.json) |
| Electron策略与宿主 | 21/21通过，覆盖管理端口、回环绑定、危险URL、窄IPC、原范围动作、回调一次加载、活动标签、撤权、代次与清理失败 |
| 完整生产UI | 冻结当前dist的229/229通过，零失败/跳过/重试；保留原226项并新增3项预览用例，源码/产物摘要保持，44张历史截图恢复原字节。见[完整UI](./qa/ui-production-win32.json) |
| 实际开发桌面 | 当前main/preload、生产Web和alpha.21 Jar九组通过：可见原生页面、登录回调一次加载、弹窗暂停恢复、无Node/管理桥接、项目/会话隔离、多标签、完整桌面/核心/Worker退出后HttpOnly Cookie与localStorage恢复、站点清理、重启后旧代次账本和Cookie清理、撤权销毁；原PID/出生停止独立核验。见[真实预览](./qa/preview-native-win32.json)。使用本地HTTP登录夹具，外部OAuth与发行包另验 |
| Windows发行预览 | 实际alpha.21解包发行程序九组通过，包内Jar与本机最终Jar摘要一致，31份桌面/Web文件、207份JRE文件逐项核对；PATH无外部Java/Node，三次完整启动及12个桌面/核心/Worker原PID/出生停止核验。独立非输入Windows测试桌面复验通过，输入桌面保持Default。安装器157,724,967字节，SHA-256 `0917e9cff3fe84b377b72c664f5dc7946e80692a1ec44fe6509d28690605a291`，NotSigned，安装/卸载本轮未重测。见[发行预览](./qa/alpha21-packaged-preview-validation-win32.json) |
| Java注释与版本 | 109份Java词法审计，370条原注释保持，5条新增Javadoc为标准多行；根/Web npm声明与Maven统一0.2.0-alpha.21 |
| 前版CI | 准确提交c6360ac的[run44049918025](https://github.com/lihe-he/multi-agent/actions/runs/44049918025)终态failure；Ubuntu成功，Windows长路径WORKTREE准备报`$GIT_DIR too big`，macOS检查点UI在任务入口点击超时。三份原日志及Ubuntu/macOS整包artifact大小和digest已核对，Windows缺少一段，原下载失败保留，本轮本机通过不改变前版CI失败 |

生产提交`3c70a083141a70c9aadd0fc229980028ccab7edc`的[run44082980225](https://github.com/lihe-he/multi-agent/actions/runs/44082980225)终态failure。GitHub元数据及三份原日志显示Ubuntu、macOS整条成功，两端555核心/10条件跳过、UI229及实际开发预览九组通过；Windows整包67,231,380字节的SHA-256已匹配GitHub digest，45套XML确认555核心/8条件跳过、零失败或错误，协议原回执前28项通过，最后取消夹具在原30秒内未观测到延迟模型请求。原工具未保存该任务状态、队列、核心数据库和完整现场，准确原因未确定；后续本机成功不改写远端失败。POSIX证据ZIP继续收集，不以日志结论代替整包核验。见[原CI失败回执](./qa/alpha21-ci-original-failure.json)。

跟进增强验收工具：取消等待保留原30秒期限，逐变化保存任务与原执行摘要，失败时保存队列、操作、模型请求、Worker租约和事件；CI保存独立核心日志、SQLite与原始Git夹具。取消对象明确匹配带SMOKE_DELAY的准确请求，原固定1.5秒响应改为持有至取消请求返回，新增断言确认取消时该响应仍在途。相同最终Jar完整协议29/29通过，零新增后续模型请求；此前仅加诊断的29项成功另存。见[准确在途响应回执](./qa/alpha21-protocol-held-response-win32.json)与[初次诊断回执](./qa/alpha21-protocol-diagnostics-win32.json)。本轮未改核心、桌面或Web产品代码，最新工具提交仍需完整三端CI。

更新后的同一预览工具另行复验开发入口九组通过，12个原身份停止，独立非输入Windows测试桌面保持；原开发与协议历史报告恢复原字节，本次结果单独保存。见[开发入口跟进](./qa/alpha21-preview-tools-followup-win32.json)。依赖/打包/版本/连接守卫21/21、工具语法与声明版本校验通过。

当前保留：远程执行节点端口转发、外部OAuth矩阵、三端发行预览、安装器生命周期、跨平台最终CI与长期存储/网络负载。端口授权不证明服务PID，URL路径/标题仍会保存；撤权按主动核验与一秒轮询检测，不能撤销已发到服务端的效果。

### Windows worktree 路径修复跟进（2026-11-18）

工具提交 `854bb6b8c5dbd5a7187b89ad3eb0c76ef1cd3d60` 的 [run44100293698](https://github.com/lihe-he/multi-agent/actions/runs/44100293698) 第二次运行已结束：Ubuntu/macOS job 成功，Windows 在核心测试步骤失败。原 Windows 现场中的 worktree `.git` 路径为 221 个 UTF-8 字节，触发 Git for Windows 的 `PATH_MAX - 40` 显式 `GIT_DIR` 检查。固定版本的开源源码与本机复现见[路径研究](./research/worktree-longpaths-2026-11-17.md)。

Windows 创建改为先登记同一锁定 detached worktree，再在目标目录执行固定 SHA 的检出；其他平台沿用原命令。PREPARED 意图仍先保存，只有检出、源状态与实际归属检查完成才写 READY，中断现场不自动重放。新增用例跨过 220 字节门槛，同时核对 READY 原字节复用、独立审查副本及源 HEAD/refs/index/config 保全；原三种长对象路径用例保留。

历史专项 XML 的四项全部通过，XML 与详细现场的摘要已重新核验，见[专项回执](./qa/alpha21-worktree-longpath-regression-win32.json)。随后完整核心回归被中断，且启动后的长路径测试源码有变化，因此不能将旧专项或这次未完成运行作为完整回归通过。11 月 18 日按当时源码重新完成完整 `mvn verify`：556 项、0 失败、0 错误、10 条条件跳过，源码摘要保持，Jar SHA-256 为 `bb869dd2c009b912e1a7f4494afeb1a552c26f47872874ce996ecb4f0456634e`，见[历史核心回执](./qa/alpha21-worktree-core-build-win32.json)。该回执从原始运行目录恢复，45 套 XML 摘要重新核验；其源码清单早于后续 PID 索引改动，不能代表最新源码。旧发行包仍对应修复前核心。三份受改 Java 文件保留 74 条原注释，两条新增 Javadoc 为标准多行。准确范围见[修复回执](./qa/alpha21-worktree-longpath-fix-win32.json)。

随后准确提交 `6205a295557ab353bc4b7136c772f2091143dd8c` 的三端 CI 中，Ubuntu 与 Windows job 全部成功；Windows 核心、协议、UI、工作区、发行预览及内置 Java 入口均通过。macOS 在 `NativePtySessionTest.stoppingARealForegroundChildWaitsForItsOriginalBirthAndTheOutputReader` 失败：同一后代 PID 的出生时间先可读、后一次枚举暂时为空，原实现把重复空值当作枚举失败并返回 UNKNOWN；原始日志和 10,055,504 字节证据包摘要已核对，见[macOS 失败回执](./qa/alpha22-macos-pty-ci-failure.json)。`3504476` 已修复该重复观察处理，未知新 PID 仍保持 UNKNOWN。

包含上述修复的准确提交 `53e6070cb5e6e70b51aa6293418cd0850e0295d6` 在 [run44233907633](https://github.com/lihe-he/multi-agent/actions/runs/44233907633) 中 Ubuntu、Windows 整条成功，macOS 核心成功，但 UI 229 项中 1 项失败、228 项通过：`checkpoints.spec.ts:57` 在 `workspaces.ts:85` 点击“固定启动现场的任务”超过原 30 秒期限。源码、构建源码与资产摘要保持；尚无证据将该 UI 超时归因于 Java/PT​​Y 改动，见[跟进 CI 回执](./qa/alpha22-post-fix-ci.json)。

本次将已验证出生时间改为显式 PID 索引，并增加确定性测试覆盖首次空值、已验证 PID 的空值重复、不同未知 PID 与同 PID 新出生时间。Windows 定向回归 30 项、0 失败、0 错误、1 条条件跳过，源码摘要保持，见[PID 索引定向回执](./qa/alpha22-pid-index-targeted-win32.json)。从 `854bb6b` 基线审计五份受改 Java 文件，94 条原注释保持，3 条新增 Javadoc 均为标准多行。最新提交的完整核心及三端验收以对应 CI 为准。

跟进审查补回原有空身份占位清理：首次空值后读到可靠出生时间时，从收尾集合移除同 PID 的空身份记录，防止旧占位影响停止核验；此前枚举失败标记仍保留。修改后再次完成同样 30 项定向回归与注释审计，见[最终定向回执](./qa/alpha22-pid-index-followup-win32.json)。

`34fff04` 的 [run44242815474](https://github.com/lihe-he/multi-agent/actions/runs/44242815474) 中，macOS 原生 PTY 11 项通过，核心共 557 项中另有 1 项失败：实时终端重复读取将增长的 `publishedEnd`（234→242）也作为不可变响应比较。两次原字节、摘要和段身份相同。测试改为仅对发布末尾断言单调不减，其余响应继续全量相等校验；本机 CLONE/WORKTREE 两项真实集成路径通过。六份 Java 文件保留 97 条原注释。见[原失败与定向复验](./qa/alpha22-terminal-read-regression-win32.json)和[固定版本进程研究](./research/process-identities-2026-11-18.md)。该失败运行不能作为三端成功验收。

本轮汇总见[预览验证回执](./qa/alpha21-preview-validation-win32.json)。[原生页面截图](./qa/alpha21-native-preview.png)与[工作台外壳截图](./qa/alpha21-workbench-chrome.png)分别捕获，后者不包含独立原生视图的合成画面。

## 0.2.0-alpha.20 开发迭代

按 F01/F12 增加任务实际隔离目录中的交互终端。启动与持久根、写入意图同事务保存，原 Worker 保持完整调用与锁；多个查看者和一个显式控制者分离。输入/resize/停止记录原意图、派发、一次原生授权和 ACK，丢失回执只核对原动作，禁止重放。原始字节先持久保存再发布 SQLite 引用，UTF-8/ANSI 与实际尺寸由 xterm 排空回调和测量核对。停止后任务暂停并重新验证/审查，撤权或回执缺失保留 UNKNOWN。使用和导出边界见[任务终端协议](./terminal-contract.md)，来源与原失败见[终端研究](./research/terminals-2026-11-16.md)。

| 检查 | 当前准确状态 |
| --- | --- |
| 原生产核心构建 | 原 main 的 clean verify 完整执行43套/542项，529通过、10条件跳过、3失败，源码保持；未生成最终Jar。两项原团队60秒根收尾超时和一项20秒子请求未到达分别保存，临时现场缺口保留。原完整构建的1133份native夹具文件、两处输出发布边界、构造器对象返回丢失及48原身份停止独立核对；子集通过不改变整套失败。见[原构建失败](./qa/alpha20-production-core-original-team-failure-win32.json) |
| 当前核心跟进 | 完整Windows原生快照复用PID与出生事实，读取失败保留原系统枚举回退，停止仍核验原句柄。新增3项身份/刷新/不可读取边界及原3项团队用例均通过；定向29项为28通过/1原有条件跳过，原时限、预算、两份旧用例源码及载体2/2保持。临时汇总器零跳过误判另存，按原XML独立核对，实际JFR启动核验。见[定向跟进](./qa/alpha20-native-snapshot-team-repair-win32.json)。新完整clean verify为44套/545项，535通过/10条件跳过，零失败或错误，33分钟；源码前后摘要保持。最终Jar 44,336,302字节，SHA-256 `66d0a60fe86afa9e3a0eef86ab44412ed7a9b4d2eb59950a911d871f029e96fa`，160个class、16份资源、36份依赖与24份native逐项核对。Spring Boot内嵌工具Jar按原打包插件内实际字节核对，审计器两次定位错误保留。见[完整核心](./qa/alpha20-core-build-win32.json) |
| 生产 Web 与 UI | 独立干净 tsc/Vite 构建通过，20 个产物文件，13 份 third-party 资源逐项核对。自有随机端口、冻结生产 dist 的完整 UI 226/226，0跳过/失败/重试，261秒，源码与产物摘要保持；原196项与新增30项保留，44张历史截图另存本次结果后恢复原字节。见[生产 UI](./qa/ui-production-win32.json) |
| 三项原边界 | 输出提交前丢失、提交后ACK丢失、原生对象未交还三项定向全部通过；独立核对195原文件、原SQLite与游标、一次实际shell及8原身份停止。输出 UNKNOWN 不补造引用，构造器产品回执 PID 0/出生空/清理未确认；此为受控夹具边界，未推断任意上游内部失败覆盖 |
| 来源与许可 | 36份固定参考/许可证/NOTICE按原字节、SHA-256和Git blob核对；main xterm安装170份原npm字节，160份源绑定固定blob。pty4j原发布Jar及24份native、私有ConPTY class ABI、sources.jar与13份当前资源核对；实际native build commit与上游xterm bundle可复现构建缺口保留。见[正式来源清单](./research/terminal-sources-2026-11-16.json) |
| 原真实 UI 失败 | 计划等待、显示等待、第三条实际焦点报告、导出409及撤权UNKNOWN等待分别保留。最后两份461原文件独立核对；停止终端不等于根已结束，撤权不补造完整终端回执。后续暂存成功不回写原失败 |
| Java与历史 | 当前106份Java词法审计保留304原注释及顺序，64新增Javadoc均为标准多行；原RuntimeIntegrationTest与TeamExecutionTest源码、断言、时限和预算保持。旧alpha.19生产成功与文档CI失败回执独立保存 |
| 最终运行与发行 | 相同最终Jar的HTTP4、Web/native5、恢复11、派发13、工作区15、写入22、文件版本9、差异14、检查点16、协议29、完整UI226及守卫21均通过。独立浏览器/附加桌面、开发和发行主题12及侧栏10、完整重开、发行启动及包内JRE锁探针通过；四项原生桌面入口使用非输入测试桌面。本轮实际包内Jar/JRE/asar终端5项通过，PATH没有外部Java/Node，原人工输入、完整输出ZIP和撤权UNKNOWN分别核对。ASAR读取首轮因Windows路径分隔符失败，原回执保留，两处测试工具路径改用原生join；产品字节不变。安装包157,698,500字节，SHA-256 `fa37c2dcfaeea71de4d2d47df487514bd0a0a7099ecfb06f9e0fb3dff3913dd3`，实际NotSigned，完整安装/卸载本轮未重测。见[18步本机验证](./qa/alpha20-validation-win32.json)与[包内终端](./qa/terminals-native-ui-packaged-win32.json) |
| 首次三端CI与跟进 | `96d9edb` 的 [run 43995189833](https://github.com/lihe-he/multi-agent/actions/runs/43995189833) 三端核心失败，均545项：Windows532通过/8条件跳过/5失败，macOS533/10/2，Ubuntu534/10/1；没有生成本轮最终Jar。三份实际证据ZIP按GitHub大小/digest、44套原XML和两组native现场逐字节核对，见[原CI失败](./qa/alpha20-ci-first-failure.json)。POSIX尺寸文件创建早于写完，原保留文件最终均为37×101；macOS原第138行实际存在，行尾为CR/CR/LF。Windows长路径夹具混合stderr和stdout，另两项原PowerShell启动/执行等待原因仍UNKNOWN。跟进等待原命令完成标记、逐行验证完整编号且只忽略行尾CR、分别保存Git两流及原退出码；增加停止前原子进程和阶段标记诊断，原时限、预算及全部旧注释保持。本机终端20项、长路径3项、诊断10项均通过，1135原native文件及48原身份停止核对；产品78份核心输入未变。见[本机跟进](./qa/alpha20-ci-portability-followup-win32.json)，修订提交的整套三端CI仍待验收 |

第二轮提交`1d33e95`的[run 44002160511](https://github.com/lihe-he/multi-agent/actions/runs/44002160511)终态failure。三端均完整执行44套/545项：Windows535通过/8条件跳过/2失败，macOS与Ubuntu各535通过/10条件跳过/零核心失败。三份实际证据ZIP、原日志、XML、native字节与实际UI trace按整包digest和内部摘要独立核对，见[第二轮原CI失败](./qa/alpha20-ci-second-failure.json)。Windows仅余两项15/10秒原PowerShell等待，已写入命令进入标记，事实就绪/目录证明未产生，原子进程分别耗CPU约13.11/10.06秒；准确内部CPU栈未保存。macOS完整UI225通过/1失败，第三次输出响应在原请求计数失败后3.788ms返回。Ubuntu完整UI226通过，真实HTTP前三项通过，强杀独立探针`stopRequested`为true，`originalStopped`为false；随后原核心SIGKILL与全部原身份停止可核验，原探针完整返回值与准确OS状态缺失，未进入重启验证阶段。

跟进仅修改夹具与验收工具：Windows直接读取.NET实际进程目录/控制台事实并构造JSON，避免上述PowerShell命令入口；macOS重载先等待原输出排空，再保留准确三次读取、一次输入及零新shell断言；强杀核心异步执行原独立探针，使Node父进程可以处理子进程退出，原5秒停止核对和PID/出生代码保持。相同产品78份核心输入和Web产物未变，本机原生10/10、完整生产UI226/226、真实HTTP与崩溃恢复4/4通过，0重试/跳过；Java106份/370原注释保持，新增Javadoc0。原545项本机构建没有执行后来的测试源码，定向与完整UI结果分别绑定准确源码。见[本机执行跟进](./qa/alpha20-ci-execution-followup-win32.json)，新提交整套三端CI仍待验收。

第三轮提交`fec42fa`的[run 44011006549](https://github.com/lihe-he/multi-agent/actions/runs/44011006549)终态failure。Windows完整545项为534通过/8条件跳过/1失败/2错误，三处均为Git差异比较未完整收尾；原异常未保存具体状态、退出码和流/后代事实，准确原因仍UNKNOWN。macOS与Ubuntu核心各535通过/10条件跳过、完整UI各226通过；macOS真实终端HTTP读取到刚创建但尚未写完的尺寸文件，保留文件最终为`37 101\n`；Ubuntu真实HTTP4项通过，真实终端UI人工输入各一次ACK，但摘要预期使用LF，实际xterm输入为CR。三份原ZIP大小/digest、44套XML、Windows三份完整失败现场及POSIX原请求字节独立核验，见[第三轮原CI失败](./qa/alpha20-ci-third-failure.json)。

本机独立探针确认另一处相关产品缺陷：相同原子进程自然收尾时，父进程退出0得到SUCCEEDED，退出1却提前停止后代并返回UNKNOWN。现让非零退出也使用原有最多3秒、同PID/出生身份的等待，仍保留取消、超时、不完整输出和未确认后代的UNKNOWN边界。差异异常补记status、exitCode和outputComplete，接受条件保持。原RuntimeIntegrationTest用例名称、断言、时限与预算保留，新增退出1/37两个参数实例；首次定向38项因PowerShell把37映射成1而有1失败，夹具显式返回原退出码后的5项定向全部通过，两个原回执分别保存。本机探针不能证明远端原差异失败的精确原因。

POSIX终端尺寸夹具改为同目录临时文件写完后由原`mv`发布，最小PATH包含`mv`；UI命令摘要按实际CR计算，HTTP仍按LF。Git Bash中延迟分段输出的命令体检查核对了原子发布及两种准确换行字节；实际macOS/Linux PTY仍待新提交CI。新完整核心`clean verify`为44套/547项，537通过/10条件跳过、零失败或错误，40分56秒，全部当前核心输入摘要保持，原545项保留。新Jar 44,336,382字节，SHA-256 `d24dc14969c84d9425b6646cb683f55c1f3338bf28a0e350efaf18d733f81249`；160个class、16份资源、36份依赖、24份native和13份许可资源逐项核对，1,164份原native夹具及48原身份停止独立核验。

新Jar首轮HTTP在Unicode输出先到、环境事实文件尚未产生时ENOENT；夹具改为事实写完后再发布标记，尺寸和崩溃子PID文件写完后原子发布。首轮真实UI又因较长尺寸命令回显将标记挤出重载后的可访问视口而失败，后将真实Console尺寸测量移入同一夹具`size`分支，用短命令调用，保留原重开/重载、尺寸、输入摘要及一次效果断言。两次原失败和原进程停止分别保存。最终准确夹具的HTTP4、真实Web/native5、差异14和协议29均通过，原UNKNOWN、预算、完整ZIP和零重放保持；原Web生产资产未变，本轮没有重复完整226项本机UI。Java106份/370条原注释保持，新增Javadoc0。见[本机收尾跟进](./qa/alpha20-ci-settlement-followup-win32.json)。原三轮CI失败、旧545项Jar和旧发行物保持；新提交的完整三端核心、UI和包内JRE验收待运行，不能以本机成功代替。

第四轮准确提交`d8846e5`的[run 44037048478](https://github.com/lihe-he/multi-agent/actions/runs/44037048478)终态failure：Ubuntu与macOS job成功，Windows在547项核心、实际Jar的HTTP终端、包内JRE步骤之前，于原20秒CLONE Unicode输出边界失败。Windows artifact 134,911,394字节，整包 SHA-256 与GitHub digest一致；内部44套/547项为539通过、8条件跳过、0失败/错误。原手工计数为1，保留输出515字节且无中文目标，环境JSON不存在；脚本停滞的精确PowerShell内部原因仍UNKNOWN。新夹具以追加方式留下`COUNT`、`FACTS`、`MARKER`、`MARKUP`诊断，并保持事实JSON原子发布。Windows PowerShell 5 的HTTP/UI两类脚本各以四种环境变量全有/全无运行，共4/4通过；同一SHA-256 `d24dc14969c84d9425b6646cb683f55c1f3338bf28a0e350efaf18d733f81249`的真实Jar HTTP4项及生产Web/native5项通过，源、Jar、Web资产及原进程停止核验保持。失败artifact、修订前两个tracked QA版本和修订后两份本机原始报告均分别保存。新的完整三端CI仍待准确提交验收。见[第四轮原始失败](./qa/alpha20-ci-fourth-failure.json)与[夹具跟进](./qa/alpha20-ci-fourth-fixture-followup-win32.json)。

API/Worker帧、文件日志和检查点协议分别保持1、2、1，新增终端协议1，SQLite schema为10。原项目逐文件/代码块局部采纳、项目会话与远程终端、完整workflow/备份及其余 F01–F21 / M0–M3 条件继续实施，完整项目目标未完成。

## 0.2.0-alpha.19 开发迭代

按F04增加实际隔离目录的文件/index检查点、稳定历史游标、独立撤销预览与原Worker文件恢复。根就绪、阶段开始/结束及原成功工具分别保存完整归档引用；确认时重新核对当前文件、HEAD/index、原准备归属和根写入租约，将原预览与新根尝试同事务排队。受影响的后续修改阻止撤销，未参与路径保留；成功后任务暂停，旧验证、审查、运行及预算保留，新现场等待重新验证。具体保存、权限、恢复与完整ZIP边界见[检查点协议](./checkpoint-contract.md)，22份固定OpenCode/Git来源、原失败和派生证据见[检查点研究](./research/checkpoints-2026-11-15.md)。

文件操作回执、SQLite确认与本地进度分别保存，已领取撤销在核心失联或发布故障后保持UNKNOWN并禁止重放。导出收齐原目标、来源、预览、结果及日志引用的文件和暂存对象，原日志缺失、当前版本与原任务版本不一致分别标明。人工核对原效果后继续沿用重新验证门禁。归档批读取保留有界协议和逐对象Git哈希；单次相同对象发布/读回去重，普通内容指纹合并成员与模式读取并保持原v2版本兼容。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | 最终第7轮 clean verify 34套/447项，439通过/8条件跳过，0失败或错误，28.39分钟，载体2/2；原356项保留。冻结Jar SHA-256 `c912bf2dbbbcf7d3900762ef00a55acd3978923867d87259f4736cb9984ca40e`，138个class、版本资源、原依赖与许可证字节逐项核对，源码前后摘要一致。见[核心构建](./qa/alpha19-core-build-win32.json)。原433项完整失败、443项旧构建、启动前EPERM及第六轮暂停后的11套/149项部分报告分别保留 |
| 最新原边界定向 | 原归属七项全部通过，真实完整计划CLONE/WORKTREE、选择记录改写后的零新归档/回调及一次模型请求分别核对，原CONFIRMED工具和SUCCEEDED模型保持。指纹十项为9通过/1 Windows POSIX条件跳过；两种Git对象格式固定旧版本及各引用长度/限额保全。两个原团队60/20秒边界保持，定向与同源探针范围分别记录 |
| Web与完整UI | Edge与Chromium headless shell完整UI各196/196，无失败、跳过或重试，原162项及新增检查点34项保留。每种浏览器原六主题/两宽度/22视图264次测量及差异12次保持，新增检查点12次，最低实际检查点文字对比度4.899217:1。准确源码、生产Web与原附件逐项核对，见[UI验证](./qa/alpha19-ui-validation-win32.json) |
| 实际撤销与原生命周期 | 冻结普通最终Jar、原Worker、真实CLONE/WORKTREE和生产Web的检查点16项通过，共4任务/32模型请求/3次核心启动。原index、二进制、删除和用户后续文件保全，成功撤销后暂停并重置当前验证。两处SQLite故障保留原APPLIED/ACK/UNKNOWN、完整引用ZIP和原身份；人工核对后旧版本导出409、零重放均核验。原差异14、协议29、恢复11、队列13、工作区15、写入22、文件版本9及独立客户端、开发/发行主题12与侧栏10、发行/内置JRE和守卫21均通过。发行安装器152743998字节、SHA-256 `28554715ec3a6f97d8b2b4240bdb95e63230db0bd51a3ea754912c1391d6b566`，实际NotSigned；四项原生桌面入口均使用独立非输入测试桌面，原时限与预算保持；故障锁断言要求锁仍被持有或独立确认原Worker停止，原失败与源码保留 |
| 性能 | 最终Jar对应的100个不同对象两次完整捕获为9633.4 / 7927.8ms，原顶层Git启动对照220→6 / 220→6，各次完整字节与源HEAD/index核对。同源四文件对象去重的17.69%–24.52%变化与完整JFR分别保留；这些本机测量不替代长期性能验收 |
| 来源与注释 | 22份固定来源逐项核对SHA-256/Git blob，无新增运行时依赖。最新17份受改/12份新增Java词法审计保留原110条注释，67条新增Javadoc均为标准多行；旧审计保留。原RuntimeIntegrationTest行和原侧栏产品/验收源码保持，依赖锁仅更新根版本；生成历史PNG另存后恢复原字节，新增检查点两张保留 |
| CI 夹具跟进 | 原提交 `40e98fa9a1e134ec75608681c5fc654d97f1f474` 的 macOS 核心447项为1失败/1错误/9跳过。原检查点夹具70份文件按上传ZIP逐项核对，已发布READY规范路径；Diff原临时夹具未上传，锁文件消失的参与者和准确时序仍有缺口。现遍历前排除顶层`.git`，保留完整普通文件及HEAD/index/config核对；旧数据目录准备仍使用未解析路径，POSIX旧任务指针使用规范路径，Windows普通短路径兼容保留，新增三种符号目录别名拒绝及实际不可读Git目录检查。两套完整本机回归65项为59通过/6条件跳过，原63项、全部断言/时限及138个生产class保留，原两条Java注释保持、新增一条标准多行Javadoc。见[夹具跟进](./qa/alpha19-ci-fixture-followup-win32.json)。JUnit在Windows停用整个POSIX参数化模板，预期准确提交全套win32为449项、darwin/linux为451项 |
| 并发归档与重启暂停修复 | 原生产类的八个真实CLONE现场首次同时捕获，六个READY、两个因共享`data/checkpoints`创建竞争发生`FileAlreadyExistsException`；原298份夹具文件、六份成功清单及138个原class独立核对。现仅接收并发目录已存在这一异常，仍逐级核对NOFOLLOW目录和规范路径，再同步父目录；不吞掉其他I/O失败。原restart-pause夹具在恢复前先阻塞替换执行器，补核对原PENDING/count0，保留全部原断言与时限。五项定向全部通过，三份受改Java保留原18条注释，新增三条标准多行Javadoc |
| 修复产物复验 | 新clean verify 34套/450项，440通过/10条件跳过，0失败或错误，42:40分钟，源码前后摘要一致；原447项及更早356项均保留。新Jar SHA-256 `048141550c1bf8f9e46dfd39d5bb12f559c4b0d36c2b725e9946da0a7eff5508`，138个生产class中仅归档类改变，原依赖/许可证与资源逐项保持。普通新Jar的检查点16、差异14、协议29、恢复11、队列13、工作区15、写入22、文件版本9均通过；同源新安装器152744012字节、SHA-256 `01922b6904659b7ec921fd89400a1f469044080a63d867e518cdbf03f8e07518`，NotSigned。发行程序禁用外部Java后启动与内置JRE写入通过，守卫21/21；包内核心、全部Web/桌面字节及原进程停止核对。两浏览器原196项对应的UI源码/Web保持，本次运行时修复未重复UI全套。15份被工具更新的历史报告/截图另存后恢复原字节。见[新核心](./qa/alpha19-runtime-repair-core-win32.json)与[修复冻结](./qa/alpha19-runtime-repair-validation-win32.json) |
| 前两条 CI | 原提交`40e98fa9a1e134ec75608681c5fc654d97f1f474`的 [run 43800367811](https://github.com/lihe-he/multi-agent/actions/runs/43800367811) 整条终态failure，Windows与Ubuntu完整成功，macOS核心失败；三端完整日志/ZIP已核对，见[原失败回执](./qa/alpha19-ci-original-failure-receipt.json)。测试夹具跟进`41a5145b8578fb55f5db8b2e31023c8685298d46`的 [run 43805087356](https://github.com/lihe-he/multi-agent/actions/runs/43805087356) 也终态failure，Windows完整成功，Ubuntu/macOS核心失败；三份ZIP digest及失败夹具300份文件核对，见[跟进失败回执](./qa/alpha19-ci-followup-failure-receipt.json)。Ubuntu原团队首次检查点捕获失败，随后另一个父请求屏障失败；macOS还有重开后原撤销已领取的暂停夹具竞争。独立并发复现证明共享目录缺陷，不补造原CI未保存的具体异常参与者和时序。原[本机冻结](./qa/alpha19-validation-win32.json)、失败、UNKNOWN、复制缺口及15秒选择夹具隔离说明保持 |
| 并发归档修复的原 CI | `38155bb14e3eab977d30f2ad4d25603c18e11922`的 [run 43816939938](https://github.com/lihe-he/multi-agent/actions/runs/43816939938) 整条终态failure，三端日志及证据ZIP digest均核对。Windows完整成功，450核心为442通过/8跳过，UI196与原协议、检查点、发行/内置JRE和UNKNOWN历史独立复核；发行附件仅列出，未下载核对。Ubuntu452项为1失败/9跳过，原暂停撤销实际为PAUSED、日志APPLIED、八个文件均已恢复，94份原夹具逐项核对，原生取消的准确到达仍未观测。macOS核心452项零失败/错误、9跳过，UI195通过/1失败；原trace保存focus时按钮disabled。见[原失败回执](./qa/alpha19-per-file-boundary-ci-original-failure-receipt.json) |
| 逐文件撤销授权与键盘修复 | 原RESTORE_CHECKPOINT的每个停止/写入边界同步调用原CHECK_CURRENT，后续文件效果等待核心授权回执；CLONE/WORKTREE新增两项在首个文件后撤回授权，任务、根动作和日志保留UNKNOWN，原模型预算为0、恢复次数为1、旧resume返回409。定向核心25/25，两个导出补丁各有八个路径，其中一个已恢复、七个保持原现场，原Worker PID/出生停止分别核对；临时工作区原文件未另复制，证据范围明确。原暂停/取消断言、时限和预算保持。键盘用例仅在原focus/Enter前等待按钮enabled，Edge与Chromium headless shell各34/34；生产Web字节与原两浏览器196项完整结果保持。两份受改Java的原六条注释保持，新增两条Javadoc为标准多行 |
| 逐文件修复产物复验 | clean verify 34套/452项，442通过/10条件跳过，零失败或错误，45:34分钟，源码摘要保持；原356/447/450项逐项保留。新Jar SHA-256 `7db450e119112484f971c7d2276a1a39a13438cce35484f4598370ef1adf568f`，138个class与编译产物/Jar逐项绑定。相对450项产物，Worker四个class变化，其中两个仅LineNumberTable变化，去除这些属性后的原/新字节完全相同；依赖、许可证和版本保持。普通Jar检查点16、协议29、队列13、恢复11、工作区15、写入22、文件版本9、差异14均通过。原构建器的新安装器152743970字节、SHA-256 `54e988d3ce3166ebf7ae1d4fa17d724f32382a644d678cbb847635d8af0375f6`，NotSigned；发行程序禁用外部Java后的实际启动、内置JRE写入、原进程停止与守卫21/21均通过，包内Jar及全部Web/桌面字节核对。见[新核心](./qa/alpha19-per-file-boundary-repair-core-win32.json)与[完整本机冻结](./qa/alpha19-per-file-boundary-repair-validation-win32.json) |
| 本轮证据与三端 CI | 接受源码206份及12组本机回执保持原字节，绑定准确生产提交 `eb666f4120a45139f56d2d46224a985ea2eefb75` 的 [run 43834820969](https://github.com/lihe-he/multi-agent/actions/runs/43834820969) 三端终态success，三份实际证据ZIP大小/SHA-256均匹配GitHub digest。每端34套，win32 452项/444通过/8条件跳过，darwin 454项/445通过/9条件跳过，linux 454项/445通过/9条件跳过，零失败或错误，载体2/2；Windows停用的符号目录参数容器在POSIX展开三项，原源码完全一致且三项通过，具体映射保存。完整UI196、四项关键修复、CLONE/WORKTREE各一个恢复/七个原路径、原UNKNOWN及Worker停止核对；检查点16、差异14、协议29、恢复11、队列13、工作区15、写入22、文件版本9、发行主题12/侧栏10、独立客户端和内置JRE均通过。[正式成功回执](./qa/alpha19-per-file-boundary-ci-success-receipt.json)另增；原三条CI失败、本机PENDING快照、114份历史文件及证据缺口保持。首次验证器把Windows停用容器按名称直接匹配POSIX展开项而失败，原失败保留，明确映射后核验通过；发行附件仅列出元数据 |
| 文档提交的独立CI失败 | 文档提交 `e06f770` 的 [run 43851324554](https://github.com/lihe-he/multi-agent/actions/runs/43851324554) 终态failure，三份实际ZIP大小及GitHub摘要全部核对。macOS/Linux每端454项/445通过/9条件跳过、UI196及完整实际核验通过；Windows452项/444通过/8条件跳过、UI196、差异14、检查点16、恢复11通过，在队列held根首次模型调用等待处失败。原modelRequest 9ms UNKNOWN/ExecutionException、预算1/派发1，模型服务仅观察前一任务三次调用，未到强杀核心；原26份队列文件、SQLite、原完整pending ZIP与停止记录核对。206份产品接受源码与先前三端成功生产提交完全一致。底层传输cause/message/socket、held工作区与原准确poll缺口保留；上传清理后SQLite与failure-before-cleanup分别记录；历史tracked队列成功报告不计作本步成功。见[独立失败回执](./qa/alpha19-documentation-ci-original-queue-failure-receipt.json)，先前生产成功回执保持原字节 |


API/Worker帧仍为1，SQLite schema为9，文件日志协议仍为2，检查点协议为1。根目录外副执行归档、原项目逐文件/代码块局部采纳、完整workflow和备份、PTY/远程能力及其余F01–F21/M0–M3退出条件继续实施，完整项目目标未完成。

## 0.2.0-alpha.18 开发迭代

按 F04 增加逐文件树、原/新行坐标、上下文与新增/删除高亮。显示内容绑定同次捕获的文件摘要及固定 Git HEAD，对比只读取经摘要核验的拥有副本；全部差异行与省略区间必须还原原/新字节，返回前重查现场版本、HEAD 和合并冲突。二进制、排除、空文件、文件/行数上限分别显示状态，旧文本接口提供经过核验的有界预览。界面使用字面文本、键盘树导航和请求序号/中止，真实 409 清除旧行、刷新保留文件选择。具体约定见[差异协议](./diff-view-contract.md)，十份固定 OpenCode/Git 原来源及各轮失败见[差异研究](./research/file-diff-review-2026-11-14.md)。

完整核心回归发现 Windows 不完整输出路径同步关闭管道会等到原子进程退出，丢失子身份诊断。现由读取线程关闭拥有的流，先保留不完整输出与原子身份并处理原进程停止。原 RuntimeIntegrationTest 源码、断言、常量及预算保持，独立原/候选进程和实际线程栈另行归档，不用诊断重建原完整失败的时间线。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | 第二轮 clean verify 30 套 / 356 项，350 通过 / 6 条件跳过，0 失败或错误，载体 2/2。冻结 Jar SHA-256 `e82eae779857fe0f7e667d5d3f7f2725196edcf7c18d893361ac09afede2990b`，118 个 class、版本资源、未过滤配置及原依赖/许可证字节逐项核对。见[核心构建](./qa/alpha18-core-build-win32.json) |
| 最终 Web 与 UI | Edge 与 Chromium headless shell 完整 UI 各 162/162，无失败、跳过或重试，原 129 项保留，新增差异 33 项。每种浏览器保留原六主题/两宽度/22 视图的 264 次测量，新增差异 12 次；最低实际差异文字对比度 4.718681:1。准确源码、生产 Web 和原附件分别归档，见[UI 验证](./qa/alpha18-ui-validation-win32.json) |
| 真实文件差异 | 冻结普通最终 Jar、原独立 Worker、真实 CLONE/WORKTREE 和生产 Web 的 14/14 通过，十次实际 HTTP 请求、原/新行 2/20、原交付 ZIP、真实限制状态、字面文本、409/刷新选择和 HEAD 门禁均核对。301 个原身份独立确认停止，预览 Git 不在 Worker 账本的覆盖限制保留。见[实际差异](./qa/diff-view-win32.json) |
| 生产首次渲染与真实桌面 | 同一最终 Web 四场景通过。开发与发行的原桌面脚本在各自独立 Windows 测试桌面分别通过主题 12 项、侧栏 10 项及两次完整启动/原身份停止，原断言和时限保持，未切换用户输入桌面。原生最小内容窗口 986×644，侧栏 250px、主内容 720.666687px，320px 保存意图保持。每种入口六个原身份先核验再停止，开发与发行指定截图已查看。隔离前的原拖动超时和第二启动尺寸超时仍各为 UNKNOWN；独立观察器成功、捕获丢失诊断与最终原验收分开记录 |
| 原协议、恢复与独立客户端 | 冻结最终 Jar/Web 的原协议 29、崩溃恢复 11、队列 13、所选工作区 15、整次写入 22 和文件版本 9 项均通过；UNKNOWN、原请求/执行编号、READY 与预算保全、零重放和原进程停止分别核对。文件版本共 28 次原请求、每个 clone/worktree 130 条完整历史与 ZIP，278 个原身份独立确认停止。独立 Web/附加桌面退出保持同一在途请求与 Worker 租约，重连后完成团队与原来源核对 |
| Windows 发行物 | 原 builder 一次成功，安装器 152670556 字节，SHA-256 `07604edcf9418f02040135eb00a52a7b9822694ad2d08bd70239a73b0c22cbd0`。后续签名收集的模块加载失败单独保留，子进程默认模块路径下核验原安装器签名状态 NotSigned；第二份收集不重复 builder。发行程序生命周期、禁用外部 Java 后的原主题/布局及内置 JRE 写入探针均通过，asar 中 Web/桌面与包内核心逐项匹配准确产物。版本/连接/下载守卫 21/21，本轮未重测完整安装/卸载 |
| 来源、注释与冻结 | 十份固定 OpenCode/Git 源码和许可证按 Git blob/SHA-256 核验，无新增运行时依赖，两份依赖锁仅更新根版本。三份受改和三份新增 Java 以词法核验保留原 33 条注释，16 条新增 Javadoc 为标准多行。原 RuntimeIntegrationTest 和原侧栏产品/验收源码保持；15 条最终验收前后准确 Jar/Web/源码摘要一致，完整原现场、两浏览器附件、原成功 builder 与各失败冻结在 `.runtime/alpha18-final-local-qa/`。41 张生成的历史 PNG 另存后恢复原字节，新差异图两张保留，见[本机最终验证](./qa/alpha18-validation-win32.json) |
| CI | 生产提交 `1ead6b29d9532a7d31f89b040bd73fdb6ed4a1b9` 的 [run 43583094529](https://github.com/lihe-he/multi-agent/actions/runs/43583094529) 三端终态 success，三份实际证据 ZIP 大小和 GitHub SHA-256 digest 均核对。每端 30 套 / 356 项、载体 2/2，win32 352通过/4条件跳过，darwin 347通过/9条件跳过，linux 347通过/9条件跳过；完整 UI 162、实际差异 14、生产主题四场景、实际发行主题 12 / 侧栏 10 / 两次完整原启动、文件版本 9 / 原请求 28 / 每个 clone/worktree 130 条完整历史，原协议/恢复/队列/工作区/写入/独立客户端及内置 JRE 均核对。[正式记录](./qa/alpha18-ci-diff-receipt.json)保留条件跳过原因及预览进程覆盖限制；发行物 artifact 仅记录元数据。整条期限 50 分钟 |

API/Worker 帧仍为 1，SQLite schema 仍为 8，文件日志协议仍为 2，新增差异协议为 1。轮次撤销、检查点事务和原项目逐文件/代码块局部采纳继续实施；其余 F01–F21 / M0–M3 的退出条件保持，完整项目目标未完成。

## 0.2.0-alpha.17 开发迭代

按 F01 增加 200–420px 可拖动侧栏，主指针捕获、逐帧预览和松开提交；方向键、Shift、Home/End、Enter 与 Escape 支持完整键盘操作。取消、捕获丢失、失焦、隐藏、窗口变化和卸载恢复已提交布局及原 body 样式。实际容器测量扣除滚动条与预留边缘，为主内容保留 720px；缩窗只限制显示，扩大后恢复保存意图。独立客户端记录保存宽度和收起选择，同源读取最新值，拖动期间延后同步，存储失败保留本窗口意图及提示。外观页可恢复响应式默认值。协议见[侧栏布局约定](./sidebar-layout-contract.md)，固定 OpenCode/W3C 来源及逐轮原结果见[侧栏研究](./research/sidebar-layout-2026-11-14.md)。

实际重开同时发现旧核心版本字面值和收起后连接按钮缺少可访问名称。核心现从单独过滤的 Maven 构建资源读取版本，health/state、MCP 客户端/服务端和任务策略共用；应用配置保持未过滤。根、Web、两份锁文件及 POM 的版本守卫接入构建和 CI。连接按钮按原状态提供稳定名称。原主题桌面 12 项继续保留，新增侧栏 10 项独立计数。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | 最终第三轮 clean verify 29 套 / 324 项，320 通过 / 4 条件跳过，0 失败或错误，全部载体 2 / 2。Jar SHA-256 `cd19e194627d82d758ac14252f1bc9b89a6ead9de801c55e71eb59215fd6cc85`，112 个 class 与本次原编译逐项对应，过滤版本和未过滤配置均核对。准确源码、类和报告在 `.runtime/alpha17-final-core-evidence-v3/`，正式结果见[核心构建](./qa/alpha17-core-build-win32.json) |
| 最终 Web 与 UI | 缩窗竞态修订的生产构建通过，Edge 与 Chromium headless shell 完整 UI 各 129 / 129，无失败、跳过或重试；原 127 项保留，新增两个事件顺序回归，侧栏共 23 项。每种浏览器六主题 / 两种宽度 / 22 视图均有 264 次实际颜色与溢出测量；分隔器最低左侧 7.694:1、右侧 11.448:1。986px 视口和预留边缘下实际容器 956px、侧栏 236px、主内容 720px，420px 保存意图保留。准确源码、Web 和各次附件在 `.runtime/alpha17-layout-ui-repaired-{edge|chromium-shell}-full/`，原 127 项归档保留，见[UI 验证](./qa/alpha17-ui-validation-win32.json) |
| 生产首次渲染 | 相同最终 Web 的四场景通过，保留 React 入口阻塞时的主题恢复、系统变化、读取受阻、配额失败和页面恢复检查；首次存储受阻定位冲突仍单独保留。报告见[生产 Web](./qa/themes-web-win32.json) |
| 真实开发与发行桌面 | 两种入口分别 12 项原主题与 10 项侧栏通过，各在同一独立用户目录完整启动两次。指针仅松开保存、Escape、拖动中重载、320px 与收起选择在完整重开后恢复、键盘展开、缩窗与扩大及默认恢复均核对。实际最小内容窗口 986×644，侧栏 250px、主内容 720.666687px，保存意图仍为 320px。每种入口六个原桌面/核心/Worker 身份先存活核验再停止，第二轮另核验两条历史身份；发行目录禁用外部 Java。开发与发行最终截图已查看，见[开发侧栏](./qa/sidebar-desktop-win32.json)和[发行侧栏](./qa/sidebar-packaged-win32.json) |
| 原协议与恢复 | 相同最终 Jar 的协议 29、崩溃恢复 11、派发 13、所选工作区 15、整次根写入 22 和文件版本 9 项通过。文件版本共 28 次原请求、每个 clone/worktree 130 条完整历史及 ZIP、独立审查和跨任务隔离均核对，502 条原身份独立确认停止。独立浏览器/附加桌面的客户端退出保留在途请求、租约及预算，原 UNKNOWN、READY 与零重放保留 |
| 来源、注释与产物 | 五份固定 OpenCode 源码及 MIT 按 Git blob / SHA-256 核验，W3C 原 HTML 摘要保留，无新增运行时依赖。词法核对排除字符串/字符/文本块，五份受改 Java 的原 16 条注释原文和开头缩进保留，6 条新增 Javadoc 为标准多行；旧正则的 18 段统计及修正前报告保留。原 13 条验收冻结在 `.runtime/alpha17-final-local-qa/`；本次只修改侧栏与其回归，核心源码和 Jar 原字节保持，另有八条最终产物跟进前后摘要一致，asar 内 Web/桌面字节逐项对应。本次生成的 37 张历史 PNG 另存后恢复原字节，当前归档在 `.runtime/alpha17-layout-followup-local-qa/`，原 41 张图及各轮失败继续保留 |
| Windows 发行物 | 修订 Web 重新打包后的生命周期、内置 JRE 锁探针及原身份停止通过，原下载/打包及版本守卫 21 / 21 保留。当前安装器 152642879 字节，SHA-256 `a3cd42ee366da71f1cb36ceb102bb1b00308ccf3e5cd78a27dc0c5c4bec9c2ce`，实际签名状态 NotSigned，完整安装/卸载本轮未重测。原安装器和验收归档保留，本次八条跟进见[布局复验](./qa/alpha17-layout-followup-win32.json)，全部汇总见[alpha.17 验证](./qa/alpha17-validation-win32.json) |
| CI | 生产提交 `71743f6cff18e57f93553ddc8e2897411e339548` 的 [run 43561584652](https://github.com/lihe-he/multi-agent/actions/runs/43561584652) 三端终态 success，三份实际证据 ZIP 大小和 GitHub SHA-256 digest 均核对。每端 29 套 / 324 项、载体 2 / 2，win32 321通过/3条件跳过，darwin 316通过/8条件跳过，linux 316通过/8条件跳过；UI 129、生产主题四场景、实际发行桌面主题 12 项及侧栏 10 项 / 两次完整原启动、320px 与收起恢复、拖动中重载、原生最小窗口、准确 health/state 版本、原进程停止、文件版本 9 项 / 28 次请求及每个 clone/worktree 130 条完整历史、原写入 / 工作区 / 恢复 / 派发 / 协议、独立客户端和包内 JRE 均核对。[正式记录](./qa/alpha17-ci-sidebar-receipt.json)保留条件跳过原因；发行物 artifact 仅记录元数据。整条期限 50 分钟 |

原基线缺少分隔器、首轮浅色背景测量错误、UI/生产主题状态定位冲突、旧 API 版本、首次清理遮住原错、收起后重开超时及 705px 主内容分别保留。第二轮核心 clean verify 为 319 通过 / 1 失败 / 4 条件跳过，子请求在原 20 秒内未到达，尚未执行禁用定义；原临时现场没有此等待点快照且随 JUnit 清理，原因仍为 UNKNOWN。原源码、期限和预算的独立 1 / 1 诊断与最终第三轮成功分开记录；新增代码只在断言失败时保存事实，原业务断言及期限保持。原 323 项初始 Jar、各失败归档和重复参考回执的 EEXIST 记录不回写。

原 CI 三端均未把隐藏分隔器的焦点移交给按钮，macOS/Ubuntu 的拖动取消另将临时 344px 保存而未恢复 215px。延后页面缩窗/观察回调的独立生产 Web 诊断复现两种状态，但没有捕获原 CI 的准确回调顺序。原源码的四项定向为 1 通过 / 3 失败，修订后 4 / 4；原有断言和 5 秒等待保持。松开提交现核对实际视口及可用宽度，CSS 隐藏失焦时移交焦点，已聚焦其他输入时保持用户选择；测量更新放到观察通知后的动画帧，保留原观察器与取消收尾。完整 Chromium 本机启动的 spawn UNKNOWN 和首次诊断对隐藏分隔器的定位超时也单独保留，不计作业务通过。

SQLite 仍为 8，API/Worker 帧仍为 1，文件日志协议仍为 2。PTY/终端重连、真实触控与笔、完整 BFCache、完整历史进程树、远程配对/队列、长期负载和其余 F01–F21 / M0–M3 退出条件继续实施，完整项目目标未完成。

## 0.2.0-alpha.16 开发迭代

按 F10 增加纯白、纸墨、经典暖色、青瓷、墨夜、墨夜蓝六套外观。默认跟随系统，浅色青瓷、深色墨夜；固定选择持久保存，同源标签接受最新偏好。外部脚本在 React 入口到达前恢复主题，生产脚本 CSP 保持 `self`。存储不可用时外观、会话和已验证连接保留内存状态；写入失败后的页面恢复保留未保存选择及提示。核心离线时外观入口仍可使用。固定来源与原失败见[主题研究](./research/themes-2026-11-12.md)。

自审同时修复 MCP stdio 派发前取消绕过关闭的问题。请求/通知的检查、派发与等待统一失败收尾，保留原异常及停止核验错误。原 500000 字节、150ms 取消窗口、3 秒等待和原进程断言完整保留，新请求/通知两项基线均失败，修复后三项定向通过。原失败和独立诊断见[回归证据](./qa/alpha16-regression-evidence-win32.json)。

原生产提交 `a7a939fed65881b5e9816a1a994fe8b42e7b890e` 的三端 CI 在 Windows/macOS 的设置入口点击处失败。跟进修复较矮窗口的侧栏滚动、标签栏自身横向滚动和完整键盘焦点；在 986px/1280px × 640px、空项目/八个项目下验证鼠标、键盘和收起侧栏后的访问。真实桌面另记录默认及声明的 1000×680 最小窗口实际内容尺寸、设置滚入视口、整页宽度和截图。原 CI 未记录尺寸，不把本机复现尺寸作为原 CI 尺寸。具体原失败与最终证据见[布局跟进验证](./qa/alpha16-layout-validation-win32.json)。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | 第二轮 clean verify 28 套、323 项，319 通过 / 4 条件跳过、0 失败或错误，全部载体 2 / 2。Jar SHA-256 `1dc2dc35d8147e403c988d2215c6cc69471dc75f9fc300e782d7e71f40fc9df7`，准确源码、类和报告在 `.runtime/alpha16-final-core-evidence/` |
| 最终 Web 与 UI | 最后布局源码的生产构建通过，完整 UI 106 / 106，无失败或跳过；六主题 × 1440px/430px × 22 视图共 264 次实际颜色与溢出测量，另有四项较矮窗口导航与完整标签焦点回归。侧栏焦点最低 7.69:1，六主题和窄屏最终截图已查看。完整源码、产物和同次原始附件在 `.runtime/alpha16-layout-final-local-qa/`，独立复核见[UI 验证](./qa/alpha16-ui-validation-win32.json) |
| 生产首次渲染 | 实际打包入口阻塞时 React 根仍空，已保存深色、系统深色与背景已应用；四场景通过，包括系统变化、存储被阻止、配额写入失败及页面恢复事件。报告见[生产 Web](./qa/themes-web-win32.json) |
| 实际桌面 | 最后布局产物的 12 / 12 检查通过，同一独立用户目录完整启动两次、六主题与渲染器重载通过；默认及最小窗口设置可访问、整页无横向溢出。六个本轮原身份先核验存活/出生，再确认停止，第二轮另核验两条历史身份。报告见[实际桌面](./qa/themes-desktop-win32.json)。原历史行误判和本轮最小尺寸等待超时分别保留；后者原内容区域为 0×0，独立诊断未复现，原因仍为 UNKNOWN，同源码及原 30 秒等待的完整重跑通过 |
| 原协议与恢复 | 相同最终 Jar 的协议 29、原崩溃恢复 11、派发 13、所选工作区 15、整次根写入 22和文件版本 9 项均通过；原 UNKNOWN、预算、READY、零重放与原 PID/出生停止分别核验，独立浏览器/附加桌面的在途任务和重连通过。文件版本仍为每个 clone/worktree 130 条完整历史与 ZIP、共 28 次原请求 |
| 来源与源码 | 十一份固定 OpenCode/next-themes 源码与原许可证按 Git blob / SHA-256 核验，无新增运行时依赖；原 6 份受改 Java 的 14 条原注释保留，新 Javadoc 为标准多行，Jar 111 个 class 与编译输出逐项对应。本次布局跟进没有修改 core/desktop 源码，八条验证前后 Jar 和最终源码摘要一致，asar 内 Web/桌面字节与当前构建逐项相同；版本/语法/whitespace 已核对 |
| Windows 发行物 | 最后布局源码重新打包，禁用外部 Java 后的生命周期、真实六主题/完整退出重开 12 项、最小窗口导航和包内 JRE 实际锁探针通过。原 17 项下载/连接守卫源码及通过报告保留；当前安装包 152638855 字节，SHA-256 `061380a862d262af76597e35a936fb23364d3d2f285c9c415565d44917fd09f3`，实际签名测量为 NotSigned，完整安装/卸载本轮未重测。原 13 条验收冻结不回写，本次布局跟进八条产物核对步骤均退出 0，原失败继续保留。正式汇总见[alpha.16 本机验证](./qa/alpha16-validation-win32.json) |
| CI | 生产提交 `045f72b913370e5249508c140226e66e630bdd99` 的 [run 43177701330](https://github.com/lihe-he/multi-agent/actions/runs/43177701330) 三端终态success，三份实际证据ZIP大小和GitHub digest均核对。每端28套/323项、载体2 / 2，win32 320通过/3条件跳过，darwin 315通过/8条件跳过，linux 315通过/8条件跳过；UI106、生产首次渲染四场景与实际发行桌面12项/完整重开、默认及最小窗口实际尺寸/设置滚动/整页宽度、文件版本9项/28次原请求、各130条完整历史/ZIP、独立审查读取及跨任务隔离、原写入/工作区/恢复/派发/协议、独立客户端和包内JRE均核对。原PID/出生停止与准确源码/Jar/Web/config保留，[正式记录](./qa/alpha16-ci-themes-receipt.json)包含原条件跳过原因；发行物artifact只保存元数据。整条期限50分钟 |

开发桌面首轮主题与重载通过，第二轮对保留的历史 Worker 误用本轮存活断言而失败。原 SQLite 的只读核验把两条 Worker 行分别对应到两轮已核验的原 PID/出生；修订后历史身份必须已由前轮核验存活并确认停止，本轮新身份仍保留原存活及停止断言。原 25 秒关闭保护保留，原失败、实际 Jar/Web、用户数据与源码没有改写。核心首轮 321 项中的原失败、10 次原类诊断中的真实复现、焦点对比不足及配额恢复失败分别保留；最终成功不代替原失败事实。

SQLite 仍为 8，API/Worker 帧仍为 1，文件日志协议仍为 2。F10 的局部验收不能代替其余 F01–F21、完整历史进程树、远程配对、长期负载和 M0–M3 退出条件，完整项目目标仍在实施。

根写入首轮 22 项业务和第一次 1,407 条独立停止检查通过，但 finally 第二次检查有两条历史身份缺少当前出生，整条命令退出 1。原 CIM 保存 PID 23960 的后续出生，但枚举/元数据的准确时间窗口不能重建，原因仍为 UNKNOWN。完整原报告、数据、源码、Jar/Web 与失败回执在 `.runtime/alpha16-workspace-writes-failure1/`。使用原编译探针的只读补核对确认原 1,407 条身份均停止，未发出终止请求；随后同一源码、产物、期限和断言的整条重跑退出 0，原 22 项和最终清理通过。后续成功不回写首轮失败。

文件版本首轮 clone 的冲突保护、原请求关联和 130 条完整 ZIP 通过；下一次 worktree 创建请求超过原 15 秒后退出 1。原只读 SQLite 确认该任务随后才保存为 DRAFT，模型调用 0，失败清理 84 条原身份均停止。完整原现场在 `.runtime/alpha16-file-versions-failure1/`。带请求计时的独立诊断使用原源码和原 AbortSignal，三次创建在 15 秒内并完成 9 项 / 28 次请求，但没有捕获原超时的线程，原因仍为 UNKNOWN。其后普通脚本同一产物、源码、预算与期限的完整验收通过；诊断与最终普通验收分开记录，原失败不回写。

## 0.2.0-alpha.15 开发迭代

按 F03/F12/F16 与计划书9.4–9.6、18.1，增加完整已读文本版本保护、有界二进制检查、原请求关联的文件历史和完整根/副Agent导出。协议见[文件版本约定](./file-version-contract.md)，固定开源与逐轮失败见[文件版本研究](./research/file-versions-2026-11-11.md)。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | 最终clean verify成功：28套/321项，317通过/4条件跳过，0失败/错误，全部报告载体2 / 2。准确Jar 39243784字节、SHA-256 `b7fc97e4fcb0f9316c093fad7a22294ed13561a517487c04036290b210004ea8`，源码/类/完整报告在 `.runtime/alpha15-final-core-evidence/` |
| 版本与完整历史 | 普通最终Jar和原独立Worker的9项、28次实际模型请求通过。CLONE/WORKTREE各9条原请求关联回执及2次冲突，包含真实独立审查读取；分别加入121条明确旧日志后，130条稳定分页/完整ZIP通过，另一任务审查后原历史保持本任务归属。同大小/恢复mtime改动拒绝旧覆盖和删除，重新读取恢复，未解决冲突保留WAITING_INPUT。原身份520项独立确认停止，覆盖仍为OBSERVED_ONLY |
| Web | 第二轮生产构建与全量79 / 79通过，1440px/430px卡片已目视，跨任务请求焦点与迟到轮询通过；原73通过/6失败仍保留。准确源码/Web/config在 `.runtime/alpha15-ui-final-evidence/`，无关历史截图恢复原字节，实际Jar验收截图另行归档 |
| 原生命周期与发行物 | 相同最终Jar的根写入22、工作区15、恢复11、派发13、协议29及独立浏览器/附加桌面均通过；四个原回执故障窗口、原预算/UNKNOWN、新尝试、READY与原进程停止继续核对。打包启动和实际内置JRE探针通过，17项下载/连接守卫通过。安装包152636765字节、SHA-256 `3cea3a6e4f408d9af013bcd0a7f986b727e5d0f8b18b7d811743ea5391afe565`，签名状态NotSigned，本轮未重测完整安装/卸载 |
| 源码审查 | 15份新增/受改Java的80条原基线注释、首次alpha.15提交的99条原注释均保留，新Javadoc为标准多行；版本、语法、whitespace与Jar全部class对应编译输出通过。十个顺序步骤均保存原脚本SHA、退出码和前后Jar/Web/config摘要，正式汇总见[alpha.15本机验证](./qa/alpha15-validation-win32.json)，完整本机归档在 `.runtime/alpha15-final-local-qa/` |
| CI | 生产提交 `0663bd463fe049eca4e8c377907a57f81fd4cfc0` 的 [run 42992001214](https://github.com/lihe-he/multi-agent/actions/runs/42992001214) 三端终态success，三份实际证据ZIP大小和GitHub digest均核对。每端28套/321项、载体2 / 2，win32 318通过/3条件跳过，darwin 313通过/8条件跳过，linux 313通过/8条件跳过；UI79、文件版本9项/28次原请求、各130条完整历史/ZIP、独立审查读取及跨任务隔离、原写入/工作区/恢复/派发/协议、独立客户端和包内JRE均核对。原PID/出生停止与准确源码/Jar/Web/config保留，[正式记录](./qa/alpha15-ci-file-versions-receipt.json)包含原条件跳过原因；发行物artifact只保存元数据。整条期限50分钟 |

文件工具日志协议为2，公开API/实体/Worker帧仍为1，SQLite为8。alpha.14的准确Jar、QA和三端CI保留原版本与原结果。外部进程在最后比较至原子替换之间仍有写入窗口；CLI、命令、MCP没有一般文件CAS。当前目录、跨核心现场协调、完整归档恢复及其余F01–F21/M0–M3仍在实施，完整目标未完成。

首轮完整核心311项/3旧Worker夹具失败/4条件跳过在 `.runtime/alpha15-core-clean-failure1/`；三条真实读取后覆盖复验3 / 3通过，原预算上限不增加。第二轮在自审发现顶层自定义日志键脱敏遗漏后主动结束，部分记录在 `.runtime/alpha15-core-clean-interrupted2/`；遗漏真实复现1项失败，补齐后版本/历史26项中25通过/1实际Windows POSIX条件跳过。第三轮311项/1失败/4条件跳过在 `.runtime/alpha15-core-clean-failure3/`；XML栈和原95份临时文件确认恢复后成功收尾发生在夹具两次读取之间，补原条件二次核对，原90秒和业务断言保留。短凭据破坏协议的5项原代码失败在 `.runtime/alpha15-short-credential-failure1/`；修订后33项定向32通过/1Windows POSIX条件跳过。第四轮因发现共享审查目录而主动结束，部分293项/0断言失败/4跳过仍为不完整验证；5个原进程身份确认停止。两项共享审查目录问题在原代码复现1失败/1错误，原源码/报告和117份实际临时文件保留，隔离修订后47项定向46通过/1跳过。新审查副本各自存放操作清单，旧共享日志不能可靠归属时返回409并保留原文件。固定协议事实按格式保留，自由文本、扩展和无效版本继续脱敏。原无效UTF-8、目标消失、已产生效果后的租约关闭UNKNOWN、短路径、long游标、原日志字节和完整根/子导出均保留断言。

首次生产提交 `f3588639a99c3644c2fa288aa6d5e61e309492c7` 的 [run 42976352869](https://github.com/lihe-he/multi-agent/actions/runs/42976352869) 保留为失败：Ubuntu整条success，macOS核心318项/0失败/1错误/8条件跳过，原请求目录保留系统别名时被误拒绝；Windows核心、UI及原生命周期通过，文件版本前两个任务通过，等待未解决冲突根时提前读取更新中的日志而取得原409。三份实际ZIP均核对大小和GitHub digest，正式事实在本机QA的 `initialProductionValidation` 中。原Jar、源码/类、安装器和QA保存在 `.runtime/alpha15-pre-canonical-core-evidence/` 与 `.runtime/alpha15-pre-canonical-local-qa/`，不改写为三端通过。

真实Windows祖先junction在原生产类上复现3项中1错误；修订后63项定向61通过/2条件跳过。现在先定位实际数据根，再逐项拒绝根内链接；指向本数据根和另一任务的链接仍拒绝。实际客户端函数的受控HTTP对照保留原409，修订后只在同一原根结束后读取完整日志，不重试吞掉409，原60秒/模型预算不变。该客户端对照只证明请求顺序，最终Jar的实际9项/28次模型请求另行核对。

打包验收 attempt2 在 `page.screenshot` 处触发原30秒超时，未写入PASS报告；原桌面、核心和Worker三个PID/出生身份均独立确认停止，没有强制关闭。原产物、失败、完整用户数据与探针在 `.runtime/alpha15-packaged-failure1/` 保留，原因仍为UNKNOWN。补核对的辅助清单首次因缺少同目录原夹具文件被探针拒绝，原拒绝和修正输入后的核对分开保留；探针约束未修改。attempt3 使用同一Jar/Web/桌面产物、原脚本和原30秒截图期限通过，包内JRE探针另行通过；后续成功不改写原失败。

## 0.2.0-alpha.14 开发迭代

按 F03/F12 与计划书9.1、9.4–9.6、18.1，为已有本机 clone/worktree 增加独立的整次根写入归属。根领取与意图、实际锁 grant 与操作ACK分别同事务保存；原Worker跨阶段持有文件锁，模型容量释放不解除保护。未知核对另存释放依据，原UNKNOWN、新尝试和预算继续保留。协议见[根现场写入保护](./workspace-write-contract.md)，固定Git/JNA/Temurin源码、路径兼容与真实释放心跳阻塞复现见[来源与自审](./research/workspace-writes-2026-11-10.md)。

| 检查 | 当前准确状态 |
| --- | --- |
| 干净核心构建 | POSIX 文件描述符修订后的第五轮 clean verify 成功：26 套、285 项中 282 通过、3 项条件跳过，0失败/错误，全部报告载体2 / 2。最终 Jar 39213501 字节、SHA-256 `f262f5e88fff99b01d3a4881673afd0c41821836aa5bfa99a8c7ef816cd86b12`；准确源码/类/报告在 `.runtime/alpha14-final-core-evidence/`，修订前285项产物在 `.runtime/alpha14-pre-posix-core-evidence/`，第三轮278项产物仍在 `.runtime/alpha14-pre-native-process-core-evidence/` |
| 路径与进程兼容 | 74项定向中Ledger15、ManagedProcessLedger16、MCP23和WorkspaceWriteEngine8通过；两项团队夹具边界失败已保留，原两个期限复验2 / 2通过 |
| 真实释放与容量 | 旧实现两项释放心跳阻塞真实复现继续保留；最终完整测试两门心跳分别 6→7、6→7，store monitor 未持有，原RPC读管道，暂停/取消、原锁释放、ZIP及原Worker停止均核对。原八秒容量屏障在完整团队12项中通过；原释放与自然/未确认后台子进程边界定向5 / 5通过 |
| Web、最终Jar与发行物 | Web源码和生产产物摘要与已通过的69 / 69全量UI一致，原桌面与430px长卡片目视记录保留。修订后的准确最终Jar写入保护22项、5次核心启动、26次模型请求通过；工作区15、恢复11、队列13、协议29、独立浏览器/附加桌面、打包启动及内置JRE的实际授予/借用/释放和系统进程快照均通过。17项未修改守卫的原通过记录已核对；新安装包152607646字节、SHA-256 `c420568025e08536667374a841f7cf79ba71d1ba8693e7b224b178c579e4375c`，签名状态NotSigned，本轮未重测完整安装/卸载 |
| 源码审查 | 27 份新增/受改Java的 95 条原注释保留，新Javadoc为标准多行；版本、脚本语法和whitespace通过，最终Jar全部class匹配实际编译输出。验收命令均保存脚本SHA、退出码和前后Jar/Web/config摘要；九条顺序步骤均 passed，完整本机证据在 `.runtime/alpha14-final-local-qa/`，正式汇总见[alpha.14验证](./qa/alpha14-validation-win32.json) |
| CI | 修订提交 `cff00fb3bca47cf13986928146aa9879f06980f4` 的 [run 42941627882](https://github.com/lihe-he/multi-agent/actions/runs/42941627882) 三端终态success，三份实际证据ZIP大小与GitHub digest均核对；核心每端26套/285项、载体2 / 2，win32 283通过/2条件跳过，darwin 277通过/8条件跳过，linux 277通过/8条件跳过。三端UI69、写入22、工作区15、协议29、恢复11、队列13、独立客户端及包内JRE锁探针均通过，四个真实写入故障窗口、UNKNOWN/预算历史、原PID/出生停止与准确Jar/Web/config/夹具已核对。[正式记录](./qa/alpha14-ci-writers-receipt.json)保留原跳过原因及初始Unix四项失败；初始收取不完整，发行包artifact仅保存元数据。整条期限仍为50分钟 |

SQLite schema 为8，公开API/实体/Worker帧仍为1；原alpha.13的Jar、QA和准确CI保留原版本。第二轮WORKTREE释放夹具在Node基线返回0后发现未确认原子进程而保留UNKNOWN，尚未进入释放故障窗口；独立八次PowerShell/Node自然退出观察未复现，原因仍未确定，后续定向成功不回写原失败。当前目录、跨核心现场协调、已读版本保护、完整归档恢复及其余F01–F21/M0–M3仍继续实施，完整目标未完成。

Windows PID 复用回归定向23项中22通过、1项当时无受限候选而跳过。缺失Java句柄出生使用实际原生快照、快照失败继续UNKNOWN两项强制条件均通过。独立WMI与原生快照的527条前后稳定出生记录相同；当时受限候选为0，特定受限对照未执行。原夹具先完成22项业务与全部独立停止报告，重复逐JVM清理被主动中止，命令保留失败；后续原1266个身份批量补核对通过。第二轮原PID63096被LogonUI复用，新出生比原记录晚约47秒；该轮真实恢复失败与未确认记录保留。最终第三轮写入验收退出0，独立确认 1447 个原PID/出生停止，覆盖仍为OBSERVED_ONLY。

内置Java首次验收的锁已释放、原探针已停止，但默认GBK标准输出按UTF-8解码后，中文javaHome路径核对失败，原命令保留exit1。显式设置UTF-8标准输出和错误输出后的第二轮通过，最终Jar、Web和配置字节未变化；原失败、独立停止和一次PowerShell日期转换造成的辅助核验输入失败分别保存在 `.runtime/alpha14-bundled-writer-failure1/`。本轮未重测完整安装/卸载。

2026-11-11，POSIX修订后的本机九组顺序验收均退出0，前后准确Jar/Web/config及命令源码摘要相同；写入验收独立确认1379个原PID/出生身份停止，进程覆盖仍为OBSERVED_ONLY。两份原释放门的心跳均为6→7，原第二JVM竞争、暂停/取消及完整UNKNOWN历史继续核对。修订前九步骤、安装器和app.asar保存在 `.runtime/alpha14-pre-posix-local-qa/`；新归档为 `.runtime/alpha14-final-local-qa/`，本轮实际客户端截图另存 `.runtime/alpha14-posix-acceptance-screenshots/`，无关历史截图恢复原字节。

## 0.2.0-alpha.13 开发迭代

按 F03/F12 与计划书 9.1、9.4、10.2、18.1 增加实际本地/远程提交选择和 locked detached Git worktree。任务、根尝试、准备操作和 Worker 租约固定同一 SHA、源文件/index 快照与规则；完整 READY 通过原目录、Git 关联、owner 和内容核对后可复用，原未知尝试与累计预算保留。协议见[启动现场](./workspace-selection-contract.md)，固定 Git v2.51.1 提交、取舍及每轮失败见[worktree 研究](./research/git-workspaces-2026-11-09.md)。

| 检查 | 本轮准确产物与当前状态 |
| --- | --- |
| 干净核心构建 | 含读取时准备字段的最后 `clean verify` 成功：22 套、246 项中 244 通过、2 项 Windows 权限跳过，0 失败/错误；全部 XML 载体 parallelism/maxPoolSize 均为 2。最终 Jar 为 35793599 字节，SHA-256 `c625a2a97afe1e325d5078927b22f63756acfe5c1ded5d23d655c0f6814dcee8`；完整 Jar、源码哈希和报告在 `.runtime/alpha13-final-core-evidence/` |
| 最终 Jar 工作区 | 最终第四轮 15 / 15、退出 0：固定提交 worktree/remote-only clone、原 HEAD/branch/index/config/refs/文件保全、排队源变化零操作、生产 Web 409 刷新、锁定归属变化拒绝、原 Worker READY+ACK 后 task 回执存储故障、普通 Jar 重开与同源授权、原 READY owner 复用和两尝试 ZIP 均通过。12 次真实本地模型协议请求，52 个原 PID/出生身份在报告前及报告后均独立确认停止；页面/模型错误为空。[实际报告](./qa/workspace-selection-win32.json)、`.runtime/workspace-selection-kYJdBk/reports/` 和 `.runtime/alpha13-workspaces-jar-attempt4.log` 保存完整来源 |
| Web 与界面 | 最终 TypeScript/生产构建成功，全量 UI 62 / 62；1440px/430px 的固定提交、纯文本 ref/owner、旧选择规则、版本冲突及 READY 已保存但 task 回执缺失的同源授权均验证。四张新截图已查看，六张无关历史截图恢复原字节；最终 HTML/JS/CSS/config 哈希及 UI 报告在 `.runtime/alpha13-ui-full-evidence2/` |
| 原进程恢复 | 同一最终 Jar 的 11 / 11、退出 0：原 Worker/在途 HTTP/旧 MCP EOF 存活、三次核心启动、进程覆盖与任务效果分别核对、UNKNOWN/原请求/预算保全及新任务 ZIP 通过；原夹具停止独立核验。[实际报告](./qa/process-recovery-win32.json)，日志 `.runtime/alpha13-recovery-final.log` |
| 持久根队列 | 同一最终 Jar 的 13 / 13、退出 0：批准事务故障零执行、原未领取撤回/跨重启领取一次、实际领取后 HTTP 在途核心退出、原 UNKNOWN/请求/预算和显式新尝试完整 ZIP 通过；预算 1→4，原核心与 Worker 身份独立核验停止。[实际报告](./qa/dispatch-queue-win32.json)，日志 `.runtime/alpha13-dispatch-final.log` |
| 完整本机协议 | 同一最终 Jar 的 29 / 29、退出 0，含三模式、实际团队依赖/采纳、根尝试/动作与预算、MCP 原来源、版本门禁、审查/验收和 ZIP；[协议报告](./qa/smoke-win32.json)，日志 `.runtime/alpha13-integration-final.log` |
| 独立客户端 | 核心 PID 38724、Worker PID 43160，真实生产 Web/同源代理核对客户端和附加 Electron 退出与重连保留原请求/租约且在途请求仍为 1；随后 18 次根调用、两名副 Agent、13 条 Worker 回执及一次 MCP 派发完成。无页面/模型异常；[报告](./qa/standalone-win32.json)与实际 Worker/团队截图已查看，日志 `.runtime/alpha13-standalone-final.log` |
| Windows 打包 | 原依赖/打包/连接守卫 17 / 17；原 electron-builder 26.0.12 一次生成 alpha.13 NSIS，安装器 149461427 字节，SHA-256 `3163ec3b58641b96b48c2dd94b76ffb957fcf2184b6fa19328138ee19db3a177`。打包程序 API 200、version/application.version=alpha.13、packaged=true，外部 Java 禁用后以内置 Java 21.0.13 启动，无页面异常且正常退出；包内核心、全部 Web 文件（含 config）、桌面脚本和 Java 来源逐项哈希核对。[打包报告](./qa/desktop-packaged-win32.json)及[产物汇总](./qa/alpha13-validation-win32.json)保存实际事实，本轮未重复完整安装/卸载，安装器签名状态为 NotSigned |
| 源码审查 | 22 份新增/受改 Java 中 71 条原注释逐项保留，新增 Javadoc 为标准多行；根/Web/锁文件/POM 版本一致，diff whitespace 与脚本语法通过，最终 Jar 全部 class 与原编译输出相同。[源码审查](./qa/alpha13-source-audit-win32.json)保存准确源码与类哈希 |
| 证据归档与 CI | `33863a42c5e2e84a6072daa78d24428c77aed5c5` 的 [run 42741955481](https://github.com/lihe-he/multi-agent/actions/runs/42741955481) 三端终态 success，三份实际证据 ZIP 的大小与 GitHub digest 均逐字节核对。各端核心 22 套 / 246 项、载体 2 / 2，Windows 245 通过 + 1 跳过，macOS/Linux 243 通过 + 3 跳过；实际 UI 62、工作区 15、协议 29、恢复 11、队列 13、独立客户端及打包启动均通过。原 READY owner、两次根尝试、隐藏现场、一次编译夹具、准确 Jar/Web/config 与包内核心分别核对；Windows/macOS/Linux 分别独立确认 52/51/52 个原 PID/出生身份停止。[准确提交记录](./qa/alpha13-ci-workspaces-receipt.json)保留全部 SHA、原跳过原因、下载失败与核验来源，发行包 artifact 仅记元数据 |

SQLite schema 为 7，API 与 Worker 帧协议仍为 1。`workspacePreparationPresent` 只表示读取时已有准备记录/目录，不作为 READY 成功事实持久化；实际恢复仍核对原回执和 Git 关联。READY 回执保存故障来自真实 Worker 完成及 ACK 后的 SQLite 触发器；人工 PREPARED 故障窗口另行标记，不能互相替代。选择历史保存原规则全文，源变化后同源更新授权保留原规则。

原 junction 辅助进程超时、旧 JDK 在测试前中止、UI 请求数量假设和前两轮 Jar 脚本等待/名称定位失败均保留。第三轮工作区成功与一次编译探针修订后的第四轮成功分别保存；本机 QA 首次 ASAR 路径分隔符读取失败及修正也记录于汇总，未改变生产源码或原验证结果。alpha.13 最终生产源码以 `33863a42c5e2e84a6072daa78d24428c77aed5c5` 固定，本次 CI 核验只读取该提交的实际归档及上述保留产物；alpha.14 的后续源码与验证另行记录。

本轮完成 F03 的所选提交 clone/worktree 与上述恢复边界；当前目录执行、排他写入租约、完整工作区归档恢复、远程配对/队列、完整历史进程树及其他 F01–F21/M0–M3 退出条件继续实施。完整项目目标仍未完成。

## 0.2.0-alpha.12 开发迭代

按 F03/F12 与计划书 9.1、9.5、9.6 增加持久根执行尝试与待派发动作。计划确认、显式继续、未领取暂停/取消、原子领取及根结束回执分别保存；PENDING 保留原编号跨重启等待条件，CLAIMED 缺少结束回执保持 UNKNOWN，核对后继续建立新尝试并沿用累计预算。协议见[根任务派发](./task-dispatch-contract.md)，固定 db-scheduler 来源、原缺口、实现取舍及失败记录见[队列研究](./research/dispatch-queue-2026-11-08.md)。

| 检查 | 本轮证据与当前状态 |
| --- | --- |
| 干净核心构建 | 最新 `mvn -B -ntp -f core/pom.xml clean verify` 成功：20 套、212 项中 211 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；全部报告的虚拟线程 parallelism/maxPoolSize 均为 2。含队列 20、根引擎 11 和原团队 12 项；原等待/容量期限保留。最终 Jar SHA-256 为 `d8a3a837a377dd7cfe1f7b4f635c20dab73c569d3743d91ecfe77cd8a923060f`，完整产物/报告在 `.runtime/alpha12-final-core-evidence-212/` |
| 最终 Jar 队列验收 | 13 / 13：批准事务故障零执行、未领取暂停/取消、原编号重开等待旧进程核验后领取一次、实际模型 HTTP 在途核心退出、原 UNKNOWN/请求/预算、核对后新尝试与完整 ZIP 均通过。[实际报告](./qa/dispatch-queue-win32.json)保存四次核心/Worker 启动、原编号及预算 1→4；受控执行器仅建立未领取窗口，普通恢复执行使用生产 JarLauncher，原类/Worker 均来自准确最终 Jar |
| Web 与界面 | 最后 TypeScript/生产构建及全量 UI 48 / 48 通过；最后静态产物哈希与上述 Jar 队列验收完全相同。1440px/430px 实际排队来源、历史翻页/轮询、核对输入纯文本与零横向溢出均已检查；首次五项定位失败另保留 |
| 原进程恢复 | 最终 Jar 11 / 11；旧 MCP 核心 EOF 后存活、原 Worker/请求、两种任务根尝试/动作、预算和零重放均核对，进程/覆盖与任务效果分别确认，另一次重启仍保留原 UNKNOWN 历史。最终新任务完成和 ZIP 原来源通过，三次核心及原夹具退出已独立确认；[报告](./qa/process-recovery-win32.json) |
| 完整本机协议 | 最终 Jar 29 / 29，包含新增根尝试/动作查询鉴权、状态/游标、领取核心、累计预算、根来源、三模式、真实团队依赖/采纳、MCP 原来源和 ZIP；[Windows 协议报告](./qa/smoke-win32.json)，日志 `.runtime/alpha12-integration-final.log` |
| 独立客户端 | 最终 Jar/生产 Web 的 Worker PID 23484、核心 PID 21296；浏览器/会话/附加 Electron 退出和重连保持原请求/租约编号且在途派发仍为 1，随后 18 次根调用、两名副 Agent、13 条 Worker 回执及一次 MCP 原来源派发完成。页面/模型均无异常；[Windows 独立报告](./qa/standalone-win32.json)，日志 `.runtime/alpha12-standalone-final.log` |
| 打包资产 | 原 Electron 安装守卫 6、打包守卫 8、连接策略 3 共 17 / 17。Windows 两个官方资产实际下载/原 SHA-512/7zip/原 app-builder 缓存消费通过；Linux 资产实际字节核验通过，本机 Windows 解压符号链接权限失败保留，不计作 Linux 原生通过。原失败 run 已终态，原打包器一次回退及固定来源见[打包研究](./research/electron-builder-assets-2026-11-09.md)和[资产报告](./qa/alpha12-desktop-assets-win32.json) |
| Windows 发行物 | 最终 Jar/Web 使用已核验的 NSIS 资产缓存，由原 electron-builder 26.0.12 生成 alpha.12 安装包；149444501 字节，SHA-256 `9d744f178b7174e04b01d8c66563314b6489e73f929d22a06f1ee17e191c09b9`。打包程序 version/application.version 均为 alpha.12、packaged=true，禁用外部 Java 后以内置运行时启动，API 200、无页面异常；内置核心 SHA 与最终 Jar 一致，见[产物核对](./qa/alpha12-validation-win32.json)和[打包报告](./qa/desktop-packaged-win32.json)。本轮未重复完整安装/卸载，安装包未签名 |
| 源码审查 | 16 份新增/受改 Java 的 50 条原注释逐项保留，新 Javadoc 为标准多行；根/Web/锁文件/POM 版本一致，diff whitespace 通过，原源码与准确 Jar 哈希见[源码核对](./qa/alpha12-source-audit-win32.json) |
| 三端 CI | 修订 `835d27c450f16b3c0e562e374301c76cb1ccb950` 的 [run 42623675831](https://github.com/lihe-he/multi-agent/actions/runs/42623675831) 已终态 success，Windows、macOS、Ubuntu 整条通过。三份实际证据 ZIP 均下载核对 GitHub digest；各平台核心 20 套 / 212 项、载体 2 / 2，Windows 211 通过 + 1 跳过，macOS/Linux 210 通过 + 2 跳过；准确 job 日志确认 UI 48，报告确认队列 13、恢复 11、协议 29，独立客户端和打包启动均通过。1.64.1 Chromium 的实际队列夹具、原编号、停止身份与 `.runtime` 报告已在归档核对。证据 SHA-256 分别为 Windows `9fb02ab40199804d2c43b2b0732641529b4def6f8647c012afa4639791cf175c`、macOS `2e13cf3814d9994a1a18e1a8ee9047130fc20138182ddfaaad6e57a4d8510863`、Ubuntu `c9929b4ca8222ea3a046a768b4a7b7fe1f3ce6262c6aef5df4143c6e9c16c66d`。原 `43da58f` 的 [run 42619100655](https://github.com/lihe-he/multi-agent/actions/runs/42619100655) 三端浏览器版本错误、已执行/未执行步骤、旧归档缺少隐藏现场，以及本机 Chromium spawn UNKNOWN 继续保留在[准确提交记录](./qa/alpha12-ci-dispatch-receipt.json)。发行包 artifact 另只保存元数据，未下载核验全部字节 |

本轮 SQLite schema 为 6，API、实体及 Worker 帧协议仍为 1；旧记录不补造根尝试。执行结束、未知核对和人工验收分别保存，验收/导出等待根结束回执；并发任务变化会拒绝混合导出。旧核心不能打开新库。原 15 秒子请求等待超时尚无足够证据确定原因，相同期限定向与最后完整回归通过，新增失败线程/派发快照进入 CI 证据；不以重跑通过改写旧失败。

本轮完成本机根队列的上述边界。完整历史进程树、远程配对/队列、PTY、角色/写入租约、workflow、原项目局部采纳、完整备份、其他 F01–F21 和 M0–M3 退出条件继续实施，完整项目目标仍未完成。

## 0.2.0-alpha.11 开发迭代

按 F03/F12/F14 和计划书 9.1–9.6 保存 Worker/MCP stdio 原启动意图、核心会话、PID/出生时间及已观察后代。重启后新派发受原进程和清单覆盖核验约束，任务外部效果另行核对；原 UNKNOWN、请求和预算保留，不自动重放。协议见[进程恢复约定](./process-recovery-contract.md)，固定 OpenJDK 来源、取舍和每轮失败见[恢复研究](./research/process-recovery-2026-11-08.md)。

| 检查 | 本轮证据与当前状态 |
| --- | --- |
| 清理与身份定向 | 原父身份快照等 68 项中 67 通过、1 项 Windows POSIX 跳过；停止批处理后的账本 15、MCP 23 共 38 / 38 通过，64 条身份最多四次系统快照，未知/复用门禁保留。日志 `.runtime/alpha11-parent-snapshot-guard-targeted.log`、`.runtime/alpha11-stop-history-fixed.log` |
| 完整核心产物 | 停止批处理修订后的干净 verify 成功：181 项中 180 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；18 份报告均核对载体 parallelism/maxPoolSize 为 2。最终 Jar SHA-256 为 `2232e0b6db7c61de9695702ba802fa5c29b3bcc27c290517ca2383294804a261`，完整产物与报告保留于 `.runtime/alpha11-final-core-evidence-181/`；[最终产物核对](./qa/alpha11-recovery-validation-win32.json)同时记录各条验证及源码哈希 |
| Web 与界面 | TypeScript/生产构建及全量 UI 43 / 43 通过；桌面、430px 弹窗、原出生/PID 复用、轮询输入和显式重提交已检查。Web 源码在后续 Java 修订期间没有变化 |
| 实际核心重启 | 最终 181 项 Jar 的真实恢复 11 / 11 通过：原 Worker PID 46652、MCP PID 17148、三次实际核心启动；原 3 次模型调用和 1 次外部工具不重放，原 UNKNOWN/预算保留，覆盖与任务效果分别核对后新任务完成。原 47 条 Worker 及 2 条 MCP 记录确认退出，最终核心与原夹具独立核验停止；[实际报告](./qa/process-recovery-win32.json)和 1440px/430px 截图已复查。各轮失败仍分别保留 |
| 最终 Jar 协议 | 实际协议 28 / 28 通过，包含三模式、独立 Worker、动态团队、依赖采纳、MCP 原来源和 ZIP 导出；[Windows 协议报告](./qa/smoke-win32.json)，日志 `.runtime/alpha11-integration-final.log` |
| 独立运行 | 最终 Jar/生产 Web 的 Worker PID 54444、核心 PID 54008；客户端退出和重连保持原请求/租约编号，随后 18 次根调用、两名副 Agent、13 条 Worker 回执及一次 MCP 派发完成。无页面/模型异常；[Windows 独立报告](./qa/standalone-win32.json)与实际 Worker/团队截图已复查 |
| Windows 打包运行 | 最新 Jar/Web 生成 NSIS，打包程序 version/application.version 均为 alpha.11、packaged=true；禁用外部 Java 后使用内置运行时，API 200、无页面异常并正常退出。内置核心 SHA 与最终 Jar 一致，安装包及来源哈希见[最终产物核对](./qa/alpha11-recovery-validation-win32.json)；本轮未重复完整安装/卸载验收 |
| 源码审查 | 17 份新增/受改 Java 的 59 条原注释全部保留，新增 Javadoc 为标准多行；根/Web/锁文件/POM 版本一致，脚本语法和 diff whitespace 通过。连接策略原 3 / 3，连接代码无本次变更 |
| 三端 CI | `3471160c0e9e6cfd86fc849f29a1a172b2c162ce` 的 [run 42464690847](https://github.com/lihe-he/multi-agent/actions/runs/42464690847) 终态为 failure：macOS、Ubuntu 整条成功，Windows 三次在固定 Electron 下载 HTTP 504 处失败。安装回退修订 `0bb421f82b5e888136881cef2199464313de1db8` 的 [run 42468924458](https://github.com/lihe-he/multi-agent/actions/runs/42468924458) 也已终态：三个平台核心 181 项、协议 28、UI 43、原恢复与独立生命周期通过，三个 ZIP digest/18 套报告均核对；macOS 整条成功，Windows/Linux 原 Electron 安装成功后分别在 NSIS resources/AppImage 资产 HTTP 504 处失败，后续打包启动未执行。原失败及证据见[准确提交记录](./qa/alpha11-ci-process-receipt.json)；alpha.12 的打包资产回退和自身三端 CI 另行验收 |

本轮 schema 为 5，公开 API 与私有 Worker 协议仍为 1。进程覆盖为 OBSERVED_ONLY，真实完整历史树、持久派发队列、远程配对/重连、PTY、角色/写入租约、workflow、完整备份及 F01–F21/M0–M3 退出条件继续实施。完整项目目标仍未完成。

## 0.2.0-alpha.10 开发迭代

按 F03/F12/F14 与计划书 9.1–9.6，将实际任务现场、API/CLI 模型、文件工具、验证和依赖采纳迁移至独立 Java Worker。核心仍独占 SQLite、预算、授权、调度、资源和 MCP 传输；私有双向管道交接原操作领取，原 PID/出生时间、请求、工具和回执逐步核验。协议见[独立进程约定](./worker-process-contract.md)，固定 VS Code 源码、取舍及失败记录见[来源与自审](./research/worker-processes-2026-11-08.md)。

| 检查 | 本轮最终证据 |
| --- | --- |
| 核心构建 | Windows 元数据替换修订后的最终 package 成功：162 项中 161 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；17 份 Surefire 报告均核对载体 parallelism/maxPoolSize 为 2。最新 Jar 与完整报告单独保留，见[产物核对](./qa/alpha10-receipt-validation-win32.json) |
| 进程与回调边界 | 最终完整构建中的 Wire 2、RPC 4、真实 Worker JVM/Node 4、核心桥接 5、Worker 租约 9、实际独立 JVM 团队 12、团队账本 9、MCP 19 全部通过；包含原请求在 HTTP 前落库、存储故障零派发、模型成功/回调回执丢失的不同 UNKNOWN 边界、EOF 后真实命令停止、原序号心跳、两个父子进展、冻结权限、根取消与 MCP 原来源；新增真实暂停 stdin 后的大参数取消和进程退出 |
| 浏览器与 Web | 原独立 Worker 迭代全量 37 / 37，TypeScript/生产 Web 构建成功；1440px/430px 的 Worker 与核心 PID 分别展示，截图已查看。本次锁和 stdio 修订无 Web 源码变化，继续使用这份 Web 构建；最终 Jar 的独立浏览器和打包程序另行验证 |
| 最终 Jar 集成 | 最新 162 项构建的 Jar 实际协议 28 / 28；包含此前失败的圆桌质疑、三模式、实际团队、依赖采纳、MCP 原来源及 ZIP 交付完整流程，见[Windows 协议报告](./qa/smoke-win32.json)。此前管道修订 Jar 的 26 项中 25 通过、一项失败及原 SHA 保留于 `.runtime/alpha10-carrier-integration-attempt1/` |
| 独立运行 | 最新 Jar/生产 Web 核对 Worker PID 16044、核心 PID 6996；退出会话、浏览器及附加桌面，重连保持原请求/租约。随后 18 次根调用、两名副 Agent、13 条 Worker 回执及一次 MCP 派发完成；无页面或模型夹具异常。[Windows 独立报告](./qa/standalone-win32.json)、[实际 Worker](../web/qa/worker-real-win32.png)与[团队截图](../web/qa/team-real-win32.png)已复查 |
| Windows 打包运行 | 最新 Jar/Web 重新生成 NSIS，`npm run test:packaged` 成功；packaged=true、version=alpha.10，禁用外部 Java 后使用内置 Java，API 200、无页面异常、正常退出。打包核心 SHA-256 与最新 Jar 同为 `c64d2d2c21b2647b3c0747f5d2292ab2db539aa2194e6095fff06f8786d6fea7`，见[产物核对](./qa/alpha10-receipt-validation-win32.json)与[打包报告](./qa/desktop-packaged-win32.json)。首次独立 Worker 产物另存，本轮未重复完整安装/卸载验收 |
| 源码与连接审查 | 原独立 Worker 23 份新增/受改 Java 文件的 50 条原注释逐项保留；本次十份受改 Java 文件另核对 27 条原注释全部保留，新增 Javadoc 为标准多行。版本一致性、脚本语法、diff whitespace 通过；连接策略原 3 / 3，连接代码无本次变更 |
| 三端 CI | 最终修订 `c4a7558f56f65336f05c236c717e271cdd243b74` 的 [run 42451935456](https://github.com/lihe-he/multi-agent/actions/runs/42451935456) 已完成，Windows、macOS、Ubuntu 均成功，见[准确提交的 CI 记录](./qa/alpha10-ci-carrier-receipt.json)。首次 `391d42e` 的 [run 42447144181](https://github.com/lihe-he/multi-agent/actions/runs/42447144181) 的两端团队超时、Windows 后代清理错误及 alpha.9 历史结果分别保留 |

普通固定阶段的 MCP 来源缺口已先用真实独立 JVM/HTTP 复现，再修复成功运行回执与团队资格的检查。所有新阶段冻结根/阶段/角色权限；固定 MCP 仍要求成功模型回执、原工具编号及写入/MCP 权限，窄范围副任务和独立提案/审查拒绝调用。完整构建中原两个 MCP 参数化夹具和新增门禁回归均通过。此前中断、157 项中的两个夹具失败、Windows BIND/NIO 失败及固定阶段产品失败继续保留于研究记录，不改写为通过。

CI 的两项团队超时已在实际两载体 JVM 中复现：阻塞核心回调持有 monitor，使父调用占满载体；原线程栈、失败报告和固定 OpenJDK 21 源码核对见[来源与自审](./research/worker-processes-2026-11-08.md)。核心/Worker 回调、私有管道写入和资源等待改为 ReentrantLock/Condition，继续保留原序号与原子授予。定向 22 / 22 通过。首次完整两载体回归另暴露 MCP stdio 原生读取与同步请求占满载体；修订后 159 项中 158 通过、1 项 Windows POSIX 跳过。随后大参数实际探针发现原生 stdin 写入也会阻塞取消，现请求/通知读写均在受限平台线程；最终完整核心 160 项中 159 通过、1 项 Windows POSIX 跳过，0 失败/错误。Windows 夹具另补失败路径清理和原后代进程停止断言，不能用 cwd 修订掩盖仍在途的后代。首次 Jar/打包产物与最终修订分别记录，不以旧 SHA 替代最新验证。

实际 Jar 在工具返回后的 Windows 元数据替换处失败后，使用真实 NOSHARE_DELETE 共享锁复现临时失败与持续失败。修订只对原临时文件的元数据替换做每次最多一秒的 Windows AccessDeniedException 重试；实际工具及用户文件写入不重放，持续失败仍 UNKNOWN。最终 162 项构建、28 项 Jar 协议、独立生命周期与 Windows 打包均已完成，日志与报告在 `.runtime/alpha10-receipt-final-core-evidence/` 分别保存；正式 QA 记录最新结果，原失败副本继续保留。

schema 仍为 4，公开 API 仍为 1；私有 Worker 帧协议为 1。生产方式为 LOCAL_PROCESS，基础运行时测试和历史记录保留 CORE_PROCESS。失联封闭原会话，执行/远端效果保持 UNKNOWN，实际进程停止前保留收尾和资源。持久拉取队列、旧进程树跨重启完整核验、远程配对/重连、PTY、角色/写入租约、workflow、完整备份及其他 F01–F21/M0–M3 退出条件继续实施，完整项目目标未完成。

## 0.2.0-alpha.9 开发迭代

继续 F03/F12/F14 的执行归属基础：实际本机节点、项目仓库绑定、冻结源目录和授权版本；Worker 记录实际 JVM、执行代次与持久操作租约，失效和重启不重放。设置、创建计划、任务 Worker 活动和授权更新对应持久来源，模型/MCP 原来源及 ZIP 保存归属。协议见[本机 Worker 约定](./worker-contract.md)，固定开源来源及失败记录见[审查记录](./research/worker-leases-2026-11-08.md)。

| 检查 | 本轮证据 |
| --- | --- |
| 核心最终构建 | 补充进程事实后的 `npm run build` 及最后 `npm run build:core`，142 项中 141 通过、1 项 Windows POSIX 跳过，0 失败/错误；Jar、TypeScript 与生产 Web 构建成功。包含规则来源、时钟、错误保全、副任务 UNKNOWN、快速停止与交付收尾；新增子进程退出核验与大输出截断后的原 PID 证据。夹具增加 PID 复用清理保护后，两项定向再次通过 |
| Worker/执行归属 | 最终完整回归中的 Worker 租约 9 / 9、实际团队 11 / 11；真实 Git/HTTP 覆盖绑定版本更新、草稿换仓库/规则历史、节点撤回/子请求 UNKNOWN 与重启不重放；定向修正后的 10 / 10 为补充证据 |
| 浏览器 | 全量 35 / 35；6 项新增覆盖 1440px/430px 来源、未知停止事实、历史游标、配置冲突/输入保全、创建时固定绑定和授权版本刷新；截图已查看 |
| Jar 集成 | 最终 Jar 的本机协议 28 / 28；实际两名副 Agent、依赖采纳、共享根预算、原模型/MCP/Worker 来源、一次派发和 ZIP 去除领取摘要均核对；[Windows 协议报告](./qa/smoke-win32.json) |
| 独立运行 | 真实最终 Jar/生产 Web/同源代理核对 Worker PID 48116；关闭会话、浏览器和附加桌面后，同一副请求与租约均保持原编号、不重放。随后 18 次根模型调用、两名副 Agent、13 条 Worker 回执及一次实际 MCP 派发完成；无页面或模型夹具异常。[Windows 独立报告](./qa/standalone-win32.json)及[真实 Worker 截图](../web/qa/worker-real-win32.png) |
| Windows 打包运行 | `npm run package:prepared` / `npm run test:packaged` 均成功；NSIS 与 packaged=true 程序为 alpha.9，禁用外部 Java 后以内置 Java 启动，API 200、无页面异常、正常退出。打包核心 SHA-256 与最终验证 Jar 一致；[Windows 打包报告](./qa/desktop-packaged-win32.json)。本轮未重复完整安装/卸载验收 |
| 代码及连接审查 | 首个提交 15 份受改 Java 文件的 29 条已有注释原文保留；停止修订的三份 Java 文件六条基线注释及本次三份文件的 14 条基线注释逐项保留，新 Javadoc 为标准多行；版本一致性、脚本语法、diff whitespace 通过；连接策略 3 / 3，API 兼容仍独立于数据库版本 |
| 三端 CI | 补充进程事实的 `4f21a83ad4488e73e88bccd1da4495f92e432f8c` 的 [run 42440191423](https://github.com/lihe-he/multi-agent/actions/runs/42440191423) 已完成，Windows、macOS、Ubuntu 均成功。此前 `8c37fb0` 的 [run 42435846414](https://github.com/lihe-he/multi-agent/actions/runs/42435846414) 及 `158fcda` 的 [run 42437567111](https://github.com/lihe-he/multi-agent/actions/runs/42437567111) 失败均保留；后者的第二父任务在基线验证进入 UNKNOWN，原输出不足以确定进程或版本原因，不用后续成功回写原失败 |

SQLite schema 为 4，API 仍为 1。当前 Worker 的实际执行方式为 CORE_PROCESS，不代表独立远程执行进程或已交付的设备配对。原工具磁盘账本、模型预算和 MCP 停止明细保留；完整持久队列、独立 Worker、远程重连/旧进程核对、PTY、角色/写入租约、workflow 与其他 F01–F21/计划退出条件继续实施。

## 0.2.0-alpha.8 开发迭代

基于实际模型回执建立动态副任务、父子 AgentRun 和 Team，固定定义版本/路由/限制，后代共用根预算和取消。新增实际权限与多层范围、独立现场、同父依赖、预检采纳、消息原来源与固定 MCP 子来源/父去重；设置、创建计划和团队界面对应真实记录。协议见[动态团队约定](./team-contract.md)，AutoGen/LangGraph 固定提交、设计与失败记录见[来源与自审](./research/dynamic-teams-2026-11-07.md)。

| 检查 | 本轮最终证据 |
| --- | --- |
| `npm run build` | 最终核心 127 项中 126 通过、1 项 Windows POSIX 跳过，0 失败/错误；新增输入边界和只读结果不可标为文件采纳均在最后完整回归中通过；Jar、TypeScript 与生产 Web 构建成功 |
| 实际委派与故障回归 | `TeamExecutionTest` 8 项、`RuntimeDelegationTest` 4 项、`TeamRecordsTest` 8 项全部通过；包括容量二下两个父子推进、真实递归/根累计预算、越界权限、依赖更新基线、冲突不写入、暂存/执行位保全、子 HTTP 取消/定义撤回/重开 UNKNOWN、消息与采纳事务故障、MCP 实际来源与父去重 |
| `npm run test:ui --prefix web` | 29 / 29；1440px/430px 覆盖定义编辑/版本冲突、额外路由和冻结计划、团队状态/依赖/权限/来源、请求/证据定位、历史轮询、消息回执丢失切页/重载/原编号重试及明确结束重试。窄屏布局修正后另重验定义用例 2 / 2，截图已查看 |
| `npm run test:integration` | 最终 Jar 的本机协议 27 / 27；两名实际副任务、依赖成果、父新增文件保全、采纳、共享根模型/MCP 预算、用户及模型来源、子 MCP 一次派发和实际 ZIP 解压来源均核对；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 最终 Jar/生产 Web 经真实同源代理显示在途副请求；关闭会话、浏览器和附加桌面后原请求仍为 1、不重放，父等待只释放模型容量；随后 18 次根模型调用完成两名副任务、采纳与独立审查，MCP 派发 1 次，团队消息重载后仍只有一条用户来源；[Windows 独立报告](./qa/standalone-win32.json)及[真实团队截图](../web/qa/team-real-win32.png) |
| `node --test tools/connection.test.cjs` | 3 / 3；API 兼容仍独立于数据库版本 |
| `npm run package:prepared` / `npm run test:packaged` | 最终 Jar/Web 重新生成 Windows NSIS alpha.8；打包程序 packaged=true、version=alpha.8，在禁用外部 Java 后以内置 Java 启动，API 200、无页面异常；[Windows 打包报告](./qa/desktop-packaged-win32.json)。本轮未重跑完整安装/卸载验收 |
| 代码与文档审查 | 已有九份受改 Java 文件的全部原注释逐项保留；新增 Javadoc 为多行；diff whitespace、版本一致性、固定开源 blob 和完整 F01–F21 状态核对通过 |
| 多端 CI | 提交 `75cf450a5b259ed0f6347ca434aeccdc6cb78197` 的 [run 42386082344](https://github.com/lihe-he/multi-agent/actions/runs/42386082344) 已完成，Windows、macOS、Ubuntu 三项均成功；这是 alpha.8 历史结果 |

schema 仍为 3，API 仍为 1。最终构建之前的一轮失败保留在本机：MCP 测试在 Windows/JDK 通道初始化处未进入断言；新增用例重复开始已运行 Agent 的操作被既有门禁拒绝，修正夹具后最后完整回归通过。失败记录未计作通过。

本轮完成本机 API 动态团队的基础路径，F12/F13/F14 的具体边界和其他 F01–F21 见下表。原生 CLI 委派/远程 Worker、PTY、workflow、五档操作审批、预览、主题/图像/技能/调度/用量、移动与 IM、Computer Use、完整备份和长期验收继续实施；完整项目目标仍未完成。

## 0.2.0-alpha.7 开发迭代

接续实际 AgentRun，按 F16 保存每轮模型请求身份、根预算、输入摘要、供应商回执、耗时和脱敏输入输出片段。任务详情与单次运行支持状态/字面检索、稳定游标分页和保留历史位置的轮询；交付包包含请求记录。协议见[模型请求追踪协议](./model-request-contract.md)，固定提交的开源参考、取舍及失败记录见[请求追踪来源与自审](./research/model-requests-2026-11-07.md)。

| 检查 | 本轮证据 |
| --- | --- |
| `npm run build`、最后修正后的 `npm run build:core` / `npm run build:web` | 核心 107 项中 106 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；最后的截断标记修正后核心全部重跑通过，最终 Web TypeScript 与生产构建通过 |
| 请求存储、真实 HTTP 与引擎回归 | 请求/预算/运行计数事务故障回滚、持久重开、终态不可覆盖、回执缺失 UNKNOWN、作用域及字面分页；两轮 HTTP 工具请求/供应商身份/已知及缺失用量、HTTP 500/无效协议/断连/取消均不重试；凭据先脱敏再截断、最终长度标记及重启保留旧终态均通过 |
| `npm run test:ui --prefix web` | 最终 21 / 21；1440px/430px 覆盖 UNKNOWN 与 0、状态/检索、历史轮询、返回最新、脱敏输入输出纯文本、单次运行展开/收起及真实 runId 参数；请求回执文案已验证，最终截图已复查，无横向溢出 |
| `npm run test:integration` | 最终 alpha.7 Jar 本机协议 26 / 26；逐轮身份/预算/用量/输入片段、任务/运行筛选、供应商身份、认证及游标边界和交付 ZIP 均覆盖；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 真实 Java 和生产 Web 经同源代理显示 DISPATCHING 请求并核对 AgentRun 归属；客户端退出后同一在途模型请求仍为 1，无页面异常；[Windows 独立运行报告](./qa/standalone-win32.json) |
| `node --test tools/connection.test.cjs` | 3 / 3；API 兼容仍独立于数据库版本 |
| `npm run package:prepared`、`npm run test:packaged` | 最终 Web 构建后的 Windows NSIS alpha.7 生成成功；打包程序 packaged=true、version=alpha.7，禁用外部 Java 后以内置 Java 启动，API 200，无页面异常；[Windows 打包报告](./qa/desktop-packaged-win32.json)。本轮未重复完整安装/卸载验收 |
| 多端 CI | 提交 `07dde3a094be2e7186f6489ed505c9c01e88273a` 的 [run 42377177250](https://github.com/lihe-he/multi-agent/actions/runs/42377177250) 已完成，Windows、macOS、Ubuntu 三项均成功；这是 alpha.7 历史结果 |

schema 仍为 3、API 仍为 1。结束记录不可覆盖，传输中断/在途 HTTP 取消/重启回执缺失均保留 UNKNOWN，不自动重试或重放。缺失用量与 0 分别保存，费用和 CLI 内部调用仍未知；当前输入输出是本机保存的脱敏片段，尚无遥测内容采集开关。本轮完成 F16 的逐轮定位基础，Token 趋势/F21、动态 SubAgent/Teams、远程 Worker、终端与完整 F01–F21/计划验收仍继续实施。

## 0.2.0-alpha.6 开发迭代

按架构迁移第二步建立持久 AgentRun：每次实际模型阶段和独立审查先保存排队身份，再关联操作意图、根预算调用、输入输出版本与原始证据。任务详情和会话活动提供状态筛选、稳定游标分页及来源定位；交付包包含运行记录。协议见 [Agent 运行协议](./agent-run-contract.md)，固定提交的开源来源、设计取舍和失败记录见 [AgentRun 来源与自审](./research/agent-runs-2026-11-07.md)。

| 检查 | 本轮证据 |
| --- | --- |
| `npm run build` | 最终 alpha.6 核心 98 项检查中 97 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；Jar、Web TypeScript 与生产构建通过 |
| 核心生命周期与存储回归 | 覆盖持久重开、独立尝试号、作用域/游标、结束不可覆盖、调用保存故障时根预算与运行计数同时回滚；排队取消不派发且不扣预算、运行/证据关联、预算不足、取消、重启 UNKNOWN 与重复审查均通过 |
| `npm run test:ui --prefix web` | 19 / 19；桌面/430px 覆盖状态筛选、翻页后轮询不跳回、返回最新、原因纯文本及证据展开定位；最终截图已复查，无横向溢出 |
| `npm run test:integration` | 最终 alpha.6 Jar 的本机协议 25 / 25；真实 HTTP 运行身份/计数/证据、会话查询、认证、非法游标/状态、凭据排除和交付 ZIP 均覆盖；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 真实 Java 与生产 Web 经同源代理展示在途 AgentRun，跳转来源任务/运行页后返回会话；退出客户端后在途请求仍为 1、无页面异常；[Windows 独立运行报告](./qa/standalone-win32.json) |
| `node --test tools/connection.test.cjs` | 3 / 3；API 兼容判断保持独立于数据库版本 |
| `npm run package:prepared`、`npm run test:packaged` | Windows NSIS alpha.6 生成成功；打包程序 packaged=true、version=alpha.6，禁用外部 Java 后以内置 Java 启动，API 200、无页面异常；[Windows 打包报告](./qa/desktop-packaged-win32.json)。本轮未重复完整安装/卸载验收 |
| 多端 CI | 提交 `f5ab9660db3a6b4209f8605b531a77fc6c797e94` 的 [run 42360593180](https://github.com/lihe-he/multi-agent/actions/runs/42360593180) 已完成，Windows、macOS、Ubuntu 三项均成功；这是 alpha.6 历史结果 |

schema 仍为 3、API 仍为 1；旧数据不推测补造运行。重启和未知外部效果保留 UNKNOWN，不自动重放；运行成功、审查意见与人工验收分别保存。动态 SubAgent、父子运行、Teams、远程 Worker、F16 请求级追踪和 Token 趋势仍未完成，本轮不代表完整 F01–F21 或计划验收。

## 0.2.0-alpha.5 开发迭代

按 F02 的会话记录检索方向新增项目内跨会话消息搜索。SQLite FTS5 trigram 索引支持连续子串；少于三个 Unicode 码点的查询使用项目消息字面回退。返回结果带会话标题、消息定位和纯文本片段，前端以文本节点高亮。schema 升至 3，旧消息回填派生索引，消息与索引写入处于同一事务。开源来源、自审与范围见[会话检索审查](./research/session-search-2026-11-07.md)。

| 检查 | 本轮证据 |
| --- | --- |
| `npm run build` | 最终 alpha.5 核心 93 项中 92 通过、1 项因 Windows POSIX 权限跳过；Jar、Web TypeScript 与生产构建通过；顶栏快捷键接入后另行 `npm run build:web` 通过 |
| `npm run test:ui --prefix web` | 17 / 17；包含跨会话搜索、关闭会话重新打开、历史阅读不被轮询跳走、消息定位/高亮、全局按钮及 Ctrl/Cmd K；桌面/430px 无横向溢出，截图已复查 |
| `npm run test:integration` | 最终 alpha.5 Jar 的本机协议 24 / 24；真实 HTTP 搜索路由、认证/空查询、大小写、中文短词及 schema=3 均覆盖；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 生产 Web 和真实 Java 经同源代理搜索、重新打开已关闭会话并定位原记录；客户端退出后在途模型请求仍为 1、无页面异常；[Windows 独立运行报告](./qa/standalone-win32.json) |
| `node --test tools/connection.test.cjs` | 3 / 3；附加模式兼容判断仍独立于数据库版本 |
| `npm run package:prepared`、`npm run test:packaged` | Windows NSIS alpha.5 生成成功；打包程序 packaged=true、version=alpha.5，外部 Java 禁用时使用内置 Java 启动，API 200、无页面异常；[Windows 打包报告](./qa/desktop-packaged-win32.json)。本轮不替代完整安装/卸载验收 |
| 多端 CI | 推送后按本轮提交 SHA 核对；不以 alpha.3/alpha.4 历史结果替代 |

F02 在会话消息范围的全文检索已实现；项目文件符号索引、语义检索、跨仓库检索及完整 F01–F21 仍继续实施。本版本不是完整计划验收。

开发中直调 Maven 选择了系统旧 Java，未进入编译；改用项目 Java 选择脚本后完成最终构建。首次中文短语用例漏写了正文中的“话”，预期与字面查询不符，修正为实际连续短语后通过；首次 UI 用例点击到关闭标签按钮，已使用结果区域定位器。自审另修复已关闭会话结果无法打开、定位历史被轮询跳走、Unicode 高亮区间及迁移原子性，并新增故障/浏览器回归。失败尝试不计作通过证据。

首次推送 `30cc344` 的 run `42357045462` 在 Ubuntu 的既有 `modelCrashRequiresReconciliationInsteadOfAutomaticReplay` 用例失败：UNKNOWN 已落库但执行控制仍在 finally 收尾，人工核对正确返回 409。用例现仅对该停止收尾 409 做有界重试，期间断言任务仍为 UNKNOWN；其他异常继续直接失败，产品停止门禁保持原样。该次失败不计作三端验收，修订后的当前提交需重新跑 CI。

后续核验：修订提交 `28ff22834dd7bb3492c55ebc24cfec91b6f655cd` 的 run `42357684175` 已在 Windows、macOS、Ubuntu 全部成功。该结果只对应 alpha.5 修订提交。

## 0.2.0-alpha.4 开发迭代

按 F01/F12 及架构迁移第二步建立项目会话基础：持久标签、重命名、历史分页、幂等笔记、同会话多任务及原始活动来源。创建任务和目标记录原子保存；旧任务迁移保留执行账本。来源与自审见[会话基础审查](./research/session-foundation-2026-11-07.md)，协议见[会话接口](./session-contract.md)。

| 检查 | 本轮证据 |
| --- | --- |
| `npm run build` | 88 项 Java 检查中 87 通过、1 项 Windows POSIX 权限跳过，0 失败/错误；alpha.4 Jar、TypeScript、生产 Web 构建成功 |
| `mvn -B -ntp -f core/pom.xml -Dtest=SessionStoreTest test` | 8 / 8；后补实际 schema=1 且无会话表的迁移回归亦通过；事务故障注入、消息分页、关闭标签不改任务、未来 schema 拒绝及重新打开均覆盖 |
| `npm run test:ui --prefix web`、后补 `sessions.spec.ts` | 全量 13 / 13 后，新增冲突场景；会话套件 5 / 5，原界面 9 项保持通过。桌面/430px 截图已复查，成功流程无错误提示 |
| `node --test tools/connection.test.cjs` | 3 / 3；API 版本与数据库版本分离，拒绝未知协议或退化核心 |
| `npm run test:integration` | 最终 alpha.4 Jar 的本机协议 24 / 24；新增真实会话 HTTP、消息服务端身份、幂等、版本冲突、任务目标来源和活动聚合；[Windows 报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 真实独立 Java + 生产 Web 经同源代理保存会话/笔记、页面重载、关闭标签后在途任务仍运行；附加桌面退出后仍只有 1 个在途模型请求，无页面异常；[Windows 报告](./qa/standalone-win32.json) |
| `npm run package:prepared` | Windows NSIS alpha.4 已生成；本轮不重复安装/卸载验收 |
| `npm run test:packaged` | alpha.4 打包程序在外部 Java 被禁用时使用内置 Java 启动，API 200、无页面异常；[Windows 打包运行报告](./qa/desktop-packaged-win32.json) |

真实独立生命周期首次发现附加桌面把 schema 当成接口版本，导致连接被拒绝；修复为 `apiVersion=1`、`schemaVersion=2` 后重新构建并验证通过。测试代理原先仅转发 GET，现保存方法、请求体和查询参数，真实写入流程得到覆盖。协议回归中的同目标任务被既有去重门禁拒绝，夹具改为两个不同的真实任务目标，保留产品准入规则。

远端本轮提交和三端 CI 需以推送后当前 SHA 的实际记录为准。F01 的终端/PTY/侧栏拖动、F12 的 Agent 层级，以及 AgentRun/Team/Worker、动态委派、完整备份恢复和其他 F01–F21 要求仍未完成。会话笔记不是自动执行对话，不能以本轮通过替代完整计划验收。

## 0.2.0-alpha.3 开发迭代

本轮补齐 MCP 本地停止确认、容量保留及人工核对门禁；未知外部效果继续保留，已收到的结果不因清理失败丢失。真实 stdio 父子进程取消先复现旧账本错误，再验证修复；受控停止故障和恢复窗口另有回归。开源来源、自审和未覆盖边界见[本地停止审查](./research/mcp-stop-accounting-2026-11-07.md)。全部提交继续使用 `main`。

| 检查 | 当前证据 |
| --- | --- |
| `npm run build` | Java 核心 80 项中 79 通过，0 失败，0 错误，1 因 Windows 不支持 POSIX 权限跳过；alpha.3 Jar、Web TypeScript 与生产构建成功 |
| `PLAYWRIGHT_CHANNEL=msedge npm run test:ui --prefix web` | 9 / 9 通过；包含核对 POST 响应、请求体与恢复按钮 |
| `node --test tools/connection.test.cjs` | 1 / 1 通过 |
| `npm run test:integration` | 最终 alpha.3 Jar 的本机协议流程 22 / 22 通过；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | alpha.3 独立 Java、生产 Web、附加桌面生命周期通过；关闭客户端后在途模型请求仍为 1，页面无异常；[Windows 报告](./qa/standalone-win32.json) |
| `npm run package:prepared`、`npm run test:packaged` | Windows NSIS 生成成功；打包程序 alpha.3 在外部 Java 无效时使用内置 Java 启动，API 200，页面无异常；[打包报告](./qa/desktop-packaged-win32.json) |

附加的本地 Chromium 检查未通过启动：起初缺少浏览器，下载直连超时后改用现有本机代理完成安装；Node 单独启动 Chrome 也返回 spawn UNKNOWN，UI 断言尚未执行。此项不计作通过，未修改产品代码来隐藏环境问题。三端 Chromium、打包与运行结果以本次提交的远端 GitHub Actions 实际记录为准。本轮未重跑安装/卸载。

上一版本产品代码加 main 约定的 `4ba1c06` 在 run `42347134703` 中三端全部通过；这条历史结果不替代 alpha.3 验收。完整动态副 Agent、Session/Team/Worker、F01–F21、完整备份恢复及正式发布继续实施，不能以本轮回归替代完整计划验收。

后续核验：alpha.3 提交 `c6281ebe4356eaf9615cf11720f35edc34be0abd` 的 GitHub Actions run `42348756640` 已在 Windows、macOS、Ubuntu 全部成功，包含各端 Chromium、协议、独立生命周期、打包与运行步骤。该证据只对应 alpha.3 提交。

## 0.2.0-alpha.2 开发迭代

全部开发集中在 `main`。按计划书 7.4、8、9.3、9.5 节补齐 MCP 等待取消、模型容量释放、并发回执去重和派发拒绝后等待人工处理；本轮仍不代表完整 M0–M3 验收。开源参照、失败复现、自审与剩余范围见[调度审查](./research/mcp-scheduling-review-2026-11-06.md)。

新增回归使用真实本机 HTTP MCP 端点和受控锁存器，先在旧代码上复现服务等待不可取消和并发动作无法取回确认回执，再验证修复。引擎回归从创建任务、确认执行进入真实 MCP，分别覆盖确认回执和响应丢失；后者使根任务 UNKNOWN，拒绝直接恢复/验收，人工核对后重建引擎并手动恢复仍不重放外部动作。该引擎用例的模型运行器为 mock；实际模型 HTTP 工具循环的终止分支另由 RuntimeIntegrationTest 覆盖，不能据此宣称完整外部端点组合验收。

本轮构建和生命周期脚本原本选择不同 Java：构建选项目内 Java 21，验收误选系统 Java 17并启动失败。已抽出公共运行时选择入口，重跑本地协议与独立生命周期通过；失败记录不计作通过。

| 检查 | 当前证据 |
| --- | --- |
| `npm run build` | Java 核心 76 项中 75 通过、0 失败、0 错误、1 因 Windows 不支持 POSIX 权限而跳过；核心 Jar 与 Web TypeScript/生产构建成功 |
| `npm run test:ui --prefix web` | 9 / 9 通过，无界面代码变化 |
| `node --test tools/connection.test.cjs` | 1 / 1 通过 |
| `npm run test:integration` | 最终 alpha.2 Jar 重跑 22 / 22 通过；[Windows 协议报告](./qa/smoke-win32.json) |
| `npm run test:standalone` | 最终 alpha.2 Jar、生产 Web、附加模式桌面检查通过；客户端退出前后在途模型请求数均为 1，页面无异常；[Windows 生命周期报告](./qa/standalone-win32.json) |
| `npm run package:prepared`、`npm run test:packaged` | Windows NSIS 安装包生成成功；打包程序 alpha.2 在外部 Java 路径无效时使用内置运行时启动，核心 API 200、页面无异常；[Windows 打包报告](./qa/desktop-packaged-win32.json) |

本轮未重跑安装/卸载，macOS/Linux 本轮打包及运行结果以本次提交的远端 CI 为准，不使用 alpha.1 历史报告替代。动态委派、原生 CLI MCP 注入、完整备份恢复、外部服务副作用以及长期负载继续实施。

## 0.2.0-alpha.1 开发迭代

本轮从未完成的 0.2 现场恢复，修复构建脚本语法和 Java 缺失导入。新增独立核心/Web 构建入口、无凭据运行时连接配置、Electron 连接已有核心模式，以及 CLI 配置隔离参数与旧版本拒绝检查。后续迭代已将固定 MCP 动作接入任务创建、Web 确认流程、API 模型工具回路、独立工具预算、操作查询、未知结果核对及版本化重新授权；真实外部服务副作用仍需环境验收。

2026-11-06 Windows 实测：

| 检查 | 结果与证据 |
| --- | --- |
| `npm run build` | 本轮 Java 21 核心 68 项中 67 通过、0 失败、0 错误、1 因不支持 POSIX 跳过；Web TypeScript 与生产构建成功 |
| `npm run test:ui --prefix web` | 9 / 9 通过；运行时代理前缀、凭据与外部地址拒绝、任务与 MCP 管理流程 |
| `node --test tools/connection.test.cjs` | 1 / 1 通过；校验已有核心的地址与独立令牌要求 |
| `npm run test:integration` | 22 / 22 通过；[Windows 报告](./qa/smoke-win32.json)，使用本机协议测试端点 |
| `npm run test:standalone` | [Windows 报告](./qa/standalone-win32.json)：Java 从无源码/npm/Web 的独立目录启动；生产 Web 无桌面桥接可连接、设置与刷新；真实测试任务已进入在途模型请求，关闭浏览器、连接并退出附加模式 Electron 后仍 RUNNING，请求数始终为 1；桌面 Java 路径故意设为无效 |
| `npm run package:prepared`、`npm run test:packaged` | Windows NSIS 安装包构建成功；[打包启动报告](./qa/desktop-packaged-win32.json) 确认 packaged=true、核心版本 0.2.0-alpha.1、外部 Java 路径无效时 API 200、页面无异常并正常退出；本轮未重新执行安装/卸载 |

三端 CI 已增加界面交互、独立生命周期、带 Java 的桌面打包及打包启动检查；配置存在不等于三端验收已经通过，本轮远端实际结果需另外核对。上述证据不能证明外部供应商、完整 CLI 自定义隔离、远程身份/Worker、全部截图业务或长期运行能力。

首次新增 CI（提交 `1aba678`，run `42227764674`）在 macOS 已通过核心、UI 与独立生命周期，DMG 也已生成，但 electron-builder 自动进入发布检查并因缺少 GH_TOKEN 使步骤失败。打包入口已补上 `--publish never`，保持只生成本地产物；不以该次失败运行宣称打包检查全部通过。

后续 run `42228328055` 中 macOS 全部通过；Linux 在 AppImage 已生成后，Xvfb 中 packaged Electron 的 Playwright 启动失败。打包启动检查已补上 Linux 必要的 `--no-sandbox` 参数并启用完整 Electron 输出诊断；以提交后的 Linux CI 为准。

run `42229839029` 验证 Linux 的 `--no-sandbox` 修复成功；随后 macOS UI 用例发现首个同源 API 代理请求时序不稳定。用例现改为从连接对话框确认运行时前缀，再以真实认证请求断言 URL 路径和 bearer 值，并完整等待连接完成。

部署与边界见[独立部署说明](./standalone-deployment.md)，来源和后续依赖见[迭代记录](./research/iteration-foundation-2026-11-06.md)。下面原有验收矩阵和 0.1.0 运行记录继续保留，完整计划尚未完成。

## 状态口径

- **已验证**：已有可复现命令及实际运行结果；范围以证据为准。
- **已实现，待验证**：代码存在，尚未完成相应环境或真实集成检查。
- **部分实现**：具备基础路径，但没有满足该里程碑的完整退出条件。
- **待实现**：尚无实现，或仅有接口与展示。

本地模型测试端点只验证平台协议、工具回路与门禁，不能证明外部供应商可用。模拟事件不能证明原生运行器能力。仅 Windows 上执行的检查不能证明 macOS 或 Linux 兼容。

## 里程碑追踪

| 里程碑 | 产品退出条件 | 当前验收边界 |
| --- | --- | --- |
| M0 架构与可执行性 | 能力探测、计划检查、持久状态与操作账本、崩溃核对、分类资源、预算及锁定；三端进程与凭据验证 | 部分实现；具备契约、计划检查、SQLite 记录及分类容量，三端故障验收待完成 |
| M1 可靠单任务闭环 | 真实项目导入、任务执行、暂停恢复、独立验证审查、人工验收；取消、验证预留、版本证据及必要降级 | 部分实现；具备完整基础路径，真实外部运行器与故障验收仍须逐项验证 |
| M2 三模式与混合接入 | 三种模式真实任务验证，Codex / Claude Code / Java Agent，双向 MCP、等待释放、模板版本、权限交集 | 部分实现；本机 API 动态委派、依赖、权限交集、根预算与容量二等待已通过实际 HTTP/Git 调度；原生 CLI MCP 注入、混合接管、外部运行器和完整故障验收继续实施 |
| M3 日常使用 | 长期运行、完整备份恢复、未提交修改共存、接管、局部采纳、三端安装及性能验收 | 尚未完成发布验收 |
| M4 效率与扩展 | 对照评测、混合检索、跨仓库、关联重开 | 后续迭代；不计入 M0–M3 完成度 |

## 当前实现边界

- 三种模式提供固定角色阶段，API 阶段可按任务固定定义在运行中实际创建副 Agent；后代使用独立现场、同父依赖和受控采纳，根验证/独立审查/人工验收保留。尚无动态 workflow DSL 或远程节点调度。
- 生产入口用同一 Jar/JRE 启动实际独立本机 Worker，现场、模型、文件工具和验证经过原领取/回执；核心独占 SQLite、预算、授权和调度。实际子进程心跳、PID/出生时间和核心 PID 分别保存。alpha.11 持久保存原核心会话、启动意图和已观察后代，跨重启先核验原进程与清单覆盖，再核对任务效果；alpha.12 增加本机根 PENDING/CLAIMED 原子领取、未领取保全与 UNKNOWN 核对后新尝试，失联不自动重放。完整操作系统历史树、远程拉取队列与配对仍需扩展。
- API 运行器支持兼容 Chat Completions 的工具调用；Codex 与 Claude Code 有原生命令适配与版本探测。版本探测不会确认登录、模型访问、费用或长期接管能力。
- 每轮 API 请求和 CLI 会话已有持久请求身份、根预算及 AgentRun 归属、脱敏输入输出、供应商回执和可观测用量；缺失回执及远端未知保留 UNKNOWN。Token 趋势、账单核对、CLI 内部请求与内容采集/保留治理仍需扩展。
- API 工具强制路径与只读限制，启用 collaboration 的运行还强制实际能力和多层委派范围；枚举/检索先过滤再计数，窄范围不开放命令/MCP。未启用 collaboration 的历史固定阶段仍走既有路径，不能宣称全部历史权限声明已执行新交集。根约定命令在本机子进程中执行，隔离副本不是操作系统沙箱，适用于可信项目与验证命令。
- API 密钥仅会话内保存。系统凭据服务、账号额度组与自动跨账号接管尚未完成。
- 独立 MCP 客户端支持 HTTP JSON/SSE 和 stdio 协议、服务定义与权限检查、操作去重及未知状态；固定参数动作已接入 API 实现/修复阶段，使用无参数别名、根工具预算和持久操作状态。普通固定阶段及子执行保存实际运行/模型/工具、Worker/核心进程来源，并核对阶段冻结权限，父引用不重复派发；窄范围副任务不开放 MCP。项目专属会话令牌只访问授权项目的只读 RPC；原生 CLI MCP 注入及混合运行器协同仍未完成。
- 项目检索为实时关键词扫描，显示覆盖范围；Lucene 持久符号索引、引用关系、语义检索与检索缓存校准尚未完成。
- 记忆支持项目归属、编辑、停用与修订；自动提炼、完整来源图、跨项目确认偏好和全部缓存失效传播仍需扩展。
- 已有修改可整体复制到独立任务现场，并保全原暂存状态；动态副成果采纳保全父现场后续修改、暂存 blob 和执行位。alpha.19本机已验证根隔离目录的文件/index检查点和撤销；原项目逐文件/代码块选择带入、局部采纳及整合队列仍待实现。
- alpha.13 从实际本地/远程 refs 固定提交，独立 clone 与 locked detached worktree 保存原源 HEAD/分支、文件/index 快照和实际 Git 归属；排队源变化零准备/零请求，完整 READY 在原回执和关联核对后复用。alpha.14 已实现独立根写入意图、原Worker实际整次持锁、迟到停止收尾和UNKNOWN核对后新编号，已由其原最终Jar和发行物核对。alpha.15在执行/路径绑定的完整读取后拒绝旧覆盖和删除，原请求关联的完整历史通过本轮准确Jar 9项和Web 79项验收。当前目录执行、跨核心现场协调/一般文件CAS与完整worktree归档恢复继续实施。
- 导出为任务 JSON、补丁及证据 ZIP；它不是数据库、配置和所有成果的一致备份。完整备份恢复、保留策略和磁盘治理仍待实现。
- 已提供构建与桌面打包入口，三端安装、签名分发及长时间负载验收没有因入口存在而自动完成。

## F01–F21 当前追踪

下表记录已验收的alpha.18与alpha.19本机新增范围，三端CI以准确提交回执为准；原[架构审查](./research/current-architecture-audit-2026-11-06.md)保留审查时的历史快照。局部通过与完整需求完成分别记录。

| 编号 | 当前实现与证据 | 保留的工作 |
| --- | --- | --- |
| F01 多会话工作台 | 持久标签、项目切换、历史与笔记、同会话多任务及较矮窗口导航已验；alpha.17 侧栏指针 / 键盘 / 取消 / 同源保存与实际可用宽度通过 23 项，真实开发与发行程序及准确提交三端发行目录完整重开、320px / 收起恢复、原生最小窗口和原身份停止均通过 | PTY / 终端重连、真实触控与笔、完整 BFCache 和长期负载 |
| F02 全文搜索 | 项目内跨会话消息搜索、Ctrl/Cmd K、纯文本片段和原记录定位已验证 | 文件/符号/语义混合检索及校准 |
| F03 启动现场 | 固定SHA clone/locked detached worktree、READY/根队列及整次原Worker锁已验证；alpha.15执行/路径绑定完整读取、旧覆盖/删除拒绝、重新读取恢复、权限保留及未解决冲突等待，在准确Jar 9项及核心版本13项中验证 | 当前目录执行、跨核心现场协调/一般文件CAS、完整worktree归档恢复、远程队列/配对和完整历史树 |
| F04 Diff 与撤销 | alpha.19文件/index检查点、独立预览、实际原Worker撤销、完整引用ZIP与UNKNOWN恢复已验；准确生产提交三端核心win32 452/darwin与linux 454项、UI196项、真实撤销16项及逐文件撤权均通过。原差异与后续用户修改保全保留 | 原项目逐文件/代码块局部采纳、根目录外副执行归档及完整workflow接续 |
| F05 预览浏览器 | alpha.21本机开发桌面已实现会话/项目/代次隔离、明确端口授权、多标签、真实登录持久化、站点/旧代次清理与撤权销毁；核心8项、Electron21项、完整UI229项及真实主程序九组验收通过，Windows包内JRE/asar发行预览九组通过 | 远程节点端口转发、外部OAuth矩阵、三端发行预览、安装器、三端最终CI与长期负载 |
| F06 五档权限 | 计划确认、文件范围、动态能力交集、未知操作人工核对已验证 | 五档预设、逐操作审批事务、反问和跨设备审批 |
| F07 模型资源 | API/两类原生 CLI 配置与能力探测入口；凭据仅核心会话内 | 正式账号接入核验、系统凭据服务、额度组/跨账号接管及本地模型发现 |
| F08 图像 | 待实现 | 附件、生成/编辑、供应商能力、取消与用量 |
| F09 MCP | HTTP JSON/SSE 响应和 stdio、项目只读令牌、API 固定动作、成功请求与实际 Worker 来源、冻结权限及父去重已验证；实际 stdio 在核心退出后仍存活的原身份、409 新派发门禁、停止和覆盖核对通过最终 Jar 恢复 | 动态参数、原生注入、共享/全局继承、完整历史树及完整传输矩阵 |
| F10 六主题 | 六套 63 变量、持久选择、系统浅深色、首次渲染与存储失败回退；alpha.17 全量 UI 129 项 / 264 视图和分隔器两侧焦点、开发与发行桌面原主题各 12 项，准确提交三端生产四场景 / 主题 12 项 / 侧栏 10 项 / 原生最小窗口 / 完整退出重开均通过；alpha.16 原 CI 与失败保持原记录 | 未覆盖交互状态 / 系统原生控件、完整浏览器 BFCache 生命周期持续核验 |
| F11 技能 | 职责模板可导入和版本固定，不等同于技能安装 | 技能源、许可证/权限/安全状态、隔离安装与回退 |
| F12 活动来源 | 会话/AgentRun/请求/副任务、原Worker/核心PID、根/动作/写入及MCP已验证；alpha.15原请求/工具顺序/参数摘要/读操作关联、long稳定游标、全部根/子/独立审查日志和缺失清单事实通过Web 79项及准确Jar验收，各130条ZIP区分实际回执与旧日志夹具，跨任务/重复审查隔离及旧共享日志409保全通过 | 终端/其他后台活动、完整历史树、远程Worker与长期事件负载 |
| F13 副 Agent | 可视化定义/模型/能力/范围、版本冻结、实际受控委派、数量/深度/预算已验证 | 原生/远程运行器能力与完整生命周期验收 |
| F14 Teams | 实际团队、父子/同父依赖、深度视图、来源消息、等待与采纳已验证 | 完整角色租约、任意 workflow DAG 与跨节点团队 |
| F15 Workflow | 固定三模式可配合实际动态副任务 | 版本化 DSL、执行前校验、动态 DAG、检查点续跑 |
| F16 模型追踪 | 每轮身份、耗时、筛选/游标、供应商回执与脱敏片段已验证 | 内容采集开关/保留、CLI 内部事实及账单核对 |
| F17 Computer Use | 待实现 | 三端原生能力与系统授权分别验收 |
| F18 桌面宠物 | 待实现 | 默认关闭、随任务动作、自定义和权限隔离 |
| F19 移动端 | 待实现 | 设备配对/撤销、H5/App、远程审批与熄屏验收 |
| F20 IM | 待实现 | 各平台正式连接器、身份/项目绑定、回调去重与能力矩阵 |
| F21 定时与用量 | 根调用预算及逐请求可观测 Token 已保存 | 持久调度、时区/漏跑/并发去重、独立会话和趋势 |

## 场景覆盖表

自动化用例通过后只把相应局部行为记为已验证；其余条件继续保留。

| 场景 | 检查重点 | 验收方式 / 状态 |
| --- | --- | --- |
| T01 | DAG 循环、输入产物、权限、角色及资源约束 | 单元测试及 HTTP 循环计划拒绝已通过 |
| T02 | 重复任务与非成功结束 | 持久任务接口；待执行 |
| T03–T05 | 模式选择、独立圆桌、流水线失败返回 | 三种固定阶段、本机 API 动态委派及独立提案/审查隔离已通过实际协议/Git 回归；真实外部运行器另验 |
| T06、T16、T20–T21 | 固定上下文、缓存失效、记忆撤回、策略版本 | 记忆修订、新任务排除失效记忆及验收时编辑/撤回竞争拒绝通过；完整缓存传播待实现 |
| T07–T08 | 委派权限交集、MCP 身份与定义 | 动态定义版本/路由锁定、实际能力与范围交集、越界读写/命令/MCP 拒绝、固定动作原来源与父去重引用已通过；原生/远程运行器另验 |
| T09–T10 | 结果未知与崩溃边界 | 状态/存储故障、原进程和在途 HTTP/stdio 的最终 Jar 恢复 11 / 11、根队列 13 / 13 通过；alpha.13 原 Worker 保存 READY 并 ACK 后 task 回执落库失败、普通 Jar 重开、原 UNKNOWN/预算和同 owner 新尝试复用通过，人工部分 PREPARED 保全另验；完整外部供应商及崩溃组合待验 |
| T11–T12 | 根取消及限制覆盖所有后代 | 实际子 HTTP 取消/重开保留 UNKNOWN、停止后无新增请求、两层递归/累计上限、根预算与验证预留通过；跨账号/原生接管另验 |
| T13–T15 | 语义冲突、版本变化、验证规则变更 | 二进制内容和 Git 可执行位指纹、独立审查副本篡改拒绝、交付版本门禁通过；语义冲突分析待实现 |
| T17 | 原暂存、未暂存、未跟踪修改共存 | 整体带入及子成果向父采纳保全后续文件/暂存 blob/执行位已验证；alpha.13 固定提交 clone/worktree 逐项核对源 HEAD/branch/index/config/refs/文件，明确带入未提交规则，生产 UI 的过期 409 刷新保留输入并提交新 index，源变化后的原 READY/规则保留；原项目逐项选择与采纳仍待实现 |
| T18–T19 | 生命周期、磁盘、完整备份恢复 | Windows 进程与文件故障；其他系统待验证 |
| T22–T23 | 效率评测、实验预算与隔离 | 未开展付费评测，不报告收益数字 |
| T24 | Windows / macOS / Linux 安装及中文空格路径 | 三端完整验收待完成 |
| T25–T26 | 模型容量二推进、等待环与取消 | 实际调度/HTTP 场景中两个父执行者释放容量、两个子请求同时前进；同父已创建依赖限制、整体资源申请及等待取消通过。远程租约/任意 workflow 等待环与原生挂起仍需验证 |
| T27–T28 | 验证预留、平台开销、硬锁定 | 预算不可越界、已用预留不重复扣算、无额度不发请求及硬锁定拒绝通过 |
| T29–T30 | 基线失败与有限复现，保留全部结果 | 基线及修改后成功实测，同版本基线失败不能被后续成功抹除的回归通过；完整失败归因组合待验 |
| T31 | 索引覆盖、来源与无语义服务的降级 | 关键词来源、行号与覆盖说明接口通过；符号及语义检索未实现 |
| T32 | 职责模板版本与权限 | 导入未支持权限拒绝通过；版本迁移回退的完整流程待验 |
| T33–T34 | 必要降级、持久事件续读、负载下控制响应 | SQLite 重开、有界事件分页及游标续读通过；长期负载与全故障组合待验 |
| T35–T36 | 跨仓库与验收重开 | M4，待实现 |

## 可重复的集成验收

`tools/smoke-test.mjs` 使用临时目录创建独立 Git 样例仓库，启动仅用于测试的本机模型端点，并通过已启动核心的 HTTP API 执行测试。

样例任务修改 `src/greeting.txt`，运行固定命令核验文件，随后经过独立审查和人工验收接口。覆盖流水线、负责人模式和圆桌固定阶段；检查独立提案输入、根调用预算、项目原目录未被改写、验证结果绑定版本、事件续读、令牌校验、硬锁定、计划错误与验收门禁。

alpha.8 起另使用实际根任务委派实现副任务和依赖只读副任务，保全父新增文件、采纳副成果、发送来源消息、共享根模型/MCP 预算；解压实际 ZIP 核对团队、采纳、消息、请求和 MCP 原来源。alpha.9 同时核对实际 Worker/租约、绑定/节点版本与去除领取摘要的执行导出；alpha.10 进一步核对独立 Worker 与核心的不同 PID、原出生时间、子进程管道心跳和模型/MCP 执行来源。生产独立 Web 经同源代理完成团队界面与消息重载，关闭客户端时在途子请求及其 Worker 租约保持同一编号且不重复派发，随后继续完成交付。

alpha.11 的 `npm run test:recovery` 另使用同一最终 Jar 与生产 Web，在真实 stdio 工具和 HTTP 模型请求在途时只终止核心，再打开同一数据目录。核对原进程存活、UNKNOWN/预算/来源保全和 409 门禁；有界等待原进程清单确认停止、分别保存覆盖与任务效果依据，再次重启检查持久记录，新任务完成后解压原 ZIP。失败先保全当时事实，最后核心与原夹具独立确认退出后才保存成功报告，完整历史树和外部供应商仍另行验收。

脚本还会在审查完成后改变样例现场，确认验收和导出均拒绝旧版本证据；恢复相同内容后再验收。实际验证子进程会检查它没有继承平台启动令牌。在真正发起一个延迟模型请求后取消，允许按适配器实际情况保留结果未知，而不把取消请求等同于远端效果撤销。

测试端点的响应与工具请求是确定性构造，目的是检查 Java 运行器是否真实读写隔离工作目录和执行命令。它不衡量模型能力，也不表示已经使用真实 Codex、Claude Code 或外部 API 完成任务。测试会消耗配置的测试调用次数，但不会调用外部付费模型。

测试产生的仓库、工作目录、事件与核心数据应使用独立数据目录保留供排查。不要把测试数据目录指向日常项目。报告默认写入样例临时目录；CI 启动入口另写入 `docs/qa/smoke-<platform>.json`，直接调用脚本可通过 `WORKBENCH_REPORT_PATH` 指定副本位置。报告只保存调用元信息与检查结果，并替换可能出现在失败信息中的启动令牌和测试密钥。

## 实际运行记录

2026-11-05 在 Windows、Java 21.0.13、Maven 3.9.11、本地 Git 与 Node.js 环境完成以下检查：

| 检查 | 命令 / 证据 | 结果 |
| --- | --- | --- |
| Java 核心测试与打包 | `mvn -q test package`；Maven Surefire 报告 | 60 / 60 通过，0 失败、0 错误、0 跳过；覆盖计划/预算/资源、SQLite、执行门禁、本机鉴权、运行器及 MCP |
| 全链路本地协议验收 | `node tools/ci-smoke.mjs`；[Windows 报告](./qa/smoke-win32.json) | 最终核心 22 / 22 通过；三模式实际读写、命令、独立审查、人工验收、版本变更后导出拒绝及项目命令不继承控制令牌 |
| 运行器与交付补丁 | 完整 Java 测试中的 `RuntimeIntegrationTest` | 11 / 11 通过；含二进制及无末尾换行补丁往返、新暂存文件、内容边界编码、Git 可执行位及工具 UNKNOWN |
| 独立 MCP 模块 | 完整 Java 测试中的 `McpIntegrationTest` | 11 / 11 通过；HTTP JSON/SSE、真实 stdio 进程、身份/定义/权限、固定动作原子预算与去重、响应丢失 UNKNOWN 与控制令牌不继承 |
| 脚本语法 | `node --check tools/smoke-test.mjs` | 通过 |
| 前端构建与界面夹具 | `npm run build --prefix web`、`npm run test:ui --prefix web` | 构建通过，7 / 7 界面测试通过；包括 MCP 项目令牌生命周期与不写浏览器存储 |
| Windows 打包程序 | `WORKBENCH_EXPECT_PACKAGED=1`、`node tools/desktop-smoke.cjs`；[报告](./qa/desktop-packaged-win32.json) | 打包状态为真；故意设置无效外部 Java 路径后，本机 API 200、存储健康、页面无异常、正常退出 |

发布前另修正了跨平台权限测试：index 模式场景显式关闭 `core.filemode`，新增真实 POSIX 0644→0755 的权限场景。仅定向运行这两项，Windows 结果为 1 通过、1 因无 POSIX 权限支持跳过；生产 Jar 未变化，不把它记作全量 61 项已运行。POSIX 实际结果以三端 CI 为准。

Windows 安装包打包成功，程序内含 Java 21。打包启动测试曾发现裁剪运行时缺少 `java.security.jgss`，现已将运行时模块集扩为 `java.se` 及必要 JDK 扩展，并使用该内置运行时再次执行完整本地协议流程，22 / 22 通过。该次报告覆盖了前述报告副本，完整样例位于 `C:\Users\ZENGQI~1\AppData\Local\Temp\agent-workbench-smoke-2w1I8u`。安装器尚未签名，不能据此声称正式分发与三端支持完成。

打包前独立审查的完整样例与交付包保留在本机 `C:\Users\ZENGQI~1\AppData\Local\Temp\agent-workbench-smoke-AzmhZO`。22 项检查全部通过，实际发起 37 次本地模型协议请求。报告不包含启动令牌或 API 密钥。流水线交付包包含任务、补丁、操作账本与事件；任务为 `COMPLETED`，基线、验证及审查记录均为 `PASSED`，审查状态为 `APPROVED`，代码版本与审查版本一致。本轮使用系统默认 TEMP/TMP，测试核心自动启动和关闭。仓库中的报告副本已由上述内置运行时验收更新。

Windows 安装器已完成当前用户静默安装，目标路径含中文和空格；安装后的程序在外部 Java 路径无效时启动成功，核心 API 200、页面无异常并正常退出。安装验收使用独立数据目录。随后卸载返回 0，已确认测试程序移除，安装与卸载结果见[报告](./qa/installer-win32.json)。安装测试时 `/D` 参数必须引用完整路径，未引用的路径会被安装器参数处理截断到第一个空格。

Java 测试使用 Windows junction 覆盖重解析点穿越拒绝，未以缺少创建符号链接的权限跳过这项检查。测试期间发现并修复了 JDK 21 在 Windows 短格式临时路径上的本地管道初始化、Git 标准错误混入文件清单、UTF-8 分块读取及元数据截断问题。

交付补丁测试将新增二进制文件、含空格文件名和没有末尾换行的文本应用到同一基线的新克隆，重新计算内容指纹并确认完全一致。包含敏感文件的变更与超过读取上限的补丁拒绝导出，不能交付截断内容。

最后一轮回归还检查：内容指纹不能把文件字节误当成另一条文件记录；Git 可执行模式变化会改变交付版本；模型内部命令返回未知后保留未知账本并停止后续请求；同一数据目录不能被第二个 Java 进程同时持有；基线失败、独立审查副本变化及验收前记忆撤回均不能被忽略。这些具体回归通过不等于所有故障场景已完成验收。

## 首版发布前剩余检查

1. 使用用户配置的真实端点与原生 CLI 分别验证能力声明、停止边界、授权和费用字段。
2. 对启动前后、工具响应丢失、核心存储不可写和旧进程未停止进行故障注入。
3. 执行容量二、相互依赖与取消竞争，验证没有失去写入归属或无限等待。
4. 验证完整备份恢复、引用完整性、磁盘不足与清理策略。
5. 实测三端安装包，包含中文与空格路径、休眠、托盘退出和长时间运行。
6. 记录声明设备、任务并发、日志速率及持续时间后，再进行性能验收。

M0–M3 的完整退出条件全部满足后才能标记首个日常使用版本完成。测试失败、未知与未覆盖项不以用户验收或后续偶然成功改写。
