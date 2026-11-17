# 本机任务终端研究与实现审查

对应 F01 的交互终端和 F12 的实际活动来源。完整 F01–F21 / M0–M3 目标继续实施，本文不改变计划退出条件。生产完整核心与实际最终 Jar 的 HTTP、Web/native 验收已完成，原生命周期回归及发行物验收继续进行；暂存成功与生产成功分别保存。

## 固定开源参考

| 来源 | 固定版本 | 参考范围与采用方式 |
| --- | --- | --- |
| [OpenCode](https://github.com/anomalyco/opencode/tree/59ed43f0b8da891f03a7333d540a7b0c428049ea) | `59ed43f0b8da891f03a7333d540a7b0c428049ea`，MIT | 10 份 PTY、协议、短期 ticket、原测试和前端连接文件；参考输出重读、controller/observer 与原会话恢复边界，独立实现，不加入运行时依赖 |
| [pty4j](https://github.com/JetBrains/pty4j/tree/7dd1378d167cb451fe6c50f4b73e129ec2925897) | 固定研究 commit，EPL-1.0；实际依赖为 Maven 0.13.4 | 核对固定源码及实际发布 POM/Jar/sources.jar，实测发布依赖的 JNA 5.14.0 和产品 JNA 5.17.0 两组；产品使用原发布 Jar |
| [node-pty](https://github.com/microsoft/node-pty/tree/35c28c7d68a192699e70b1e6a142908bdccd9212) | `35c28c7d68a192699e70b1e6a142908bdccd9212`，MIT | 参考真实后端、线程和 OS 权限边界；本项目采用 Java Worker 原有生命周期，没有加入 Node 原生 addon |
| [xterm.js](https://github.com/xtermjs/xterm.js/tree/f70764dd3da05ebda0390605c6f9adbbea3f0e90) | 初始固定研究 commit，MIT | 比较字节写入排空回调、ANSI 与实际字符尺寸；初始研究不作为发布包 commit |
| [xterm.js 发布来源](https://github.com/xtermjs/xterm.js/tree/f447274f430fd22513f6adbf9862d19524471c04) | `@xterm/xterm` 6.0.0、`@xterm/addon-fit` 0.11.0 | 两份官方 npm tarball 的完整 SHA-512 integrity、全部包字节和 MIT 核验；源文件、typings、CSS 与许可证绑定固定 Git blob，生成 bundle 的上游可复现构建未执行 |

上述 OpenCode/PTY 初始研究共 30 份固定文件，原始响应、Git blob 与 SHA-256 保存在 `.runtime/alpha20-terminal-references-attempt1` 和 `.runtime/alpha20-native-pty-sources-attempt1`。加入原生/Kotlin许可与pty4j NOTICE后，36份固定原文件及当前发布资源已再次核对，见[正式来源清单](./terminal-sources-2026-11-16.json)。没有将上游实现复制到业务源码；xterm 作为已锁定的运行时依赖参与本项目生产构建。

pty4j 原生库存第一次把 commit 查询别名当作声明的 tree ID 而失败。第二次复用同一原 commit 字节，按其声明 tree 读取，并保持原失败。npm 来源第一次验证与重新核对的结果也独立保存，不能由当前源头的新 HEAD 替代原固定版本。

## 实现选择

沿用原 Worker 的一个长调用和实际工作区写入保护，避免终端启动返回后提前释放根租约。启动意图和持久根领取先保存；每次输入、调整尺寸、停止和输出发布重新核对原来源与授权。控制 RPC 地址仍是原调用，不能通过控制路径创建第二个 shell。

选用有界、已认证 HTTP 输出页和现有 Bearer header。OpenCode 的 ticket 设计用于理解单次授权，但此版本没有额外引入 WebSocket 或通用令牌 URL。多个查看者和显式控制者分离；接管改变代次，重开只读原记录。输入丢 ACK 只查询原动作，保留 UNKNOWN，不通过 resize/fit 或新 requestId 伪装重试。

原始段文件保存先于 SQLite 引用。产品区分已观察、已保存、已提交和已 ACK 字节；native exit、EOF、输出线程、原身份、子进程和原生资源也分开收尾。xterm 以字节帧和排空回调更新游标，UTF-8 与 ANSI 跨帧保持原顺序，显示不解释为 HTML。

Windows 发布 ConPTY 的原输入流可能在无输出时阻塞原生资源清理。独立复现后，拥有者通过固定 ABI 取得原读句柄，复制为不可继承的拥有句柄，再关闭原包装器；产品原 Jar 未改写。私有 class 的 SHA-256 固定为 `1f44486911d333b5738d7adf0a150c9fb4cba595309f28b8917d36fa49f80df7`，字段 `myReadPipe`；依赖升级必须重新核对 ABI 和实际清理。

Windows 原 Git 过程在较长的中文路径上未完整应用 `core.longpaths`。当前原 init/fetch/worktree 等准备命令显式携带该配置，并验证完整原对象与 READY 归属；原失败及原 index/config 保留。POSIX 条件与三端运行结果需要分别认证。

## 原失败与后续边界

| 原现场 | 保存事实与后续处理 |
| --- | --- |
| 原 native/JNA 探针与 Worker 集成失败 | 原后台读句柄、清理线程、目录被改名后的原输出、存储回执和末尾丢失分别复现及审计；原失败保留，当前实现显式区分 EOF、排空与确认状态 |
| 原 Web 类型、尺寸/对比度与 UNKNOWN 边界失败 | 原 TypeScript 与浏览器结果独立保存；原 196 项保持，终端新增 30 项覆盖 byte cursor、丢 ACK、过期来源、排队/启动、输入边界及六主题两宽度 |
| 原真实 UI 45 秒计划等待与 5 秒显示超时 | 原模型请求和实际进程身份另行审计；以实际 HTTP 和等待边界继续核验，不能从新成功补造原超时时间线 |
| 原 INPUT 数量 3 不等于 2 | 原 native 输出实际启用了焦点报告，第三条是准确 3 字节 `ESC[O`。两条人工命令按长度/SHA-256各匹配一个 ACK，焦点报告单独认证，人工效果仍为一次 |
| 终端停止后原导出 409 | 原终端 CANCELLED/COMPLETE 和任务 PAUSED 已保存，准确根 `endedAt` 尚未被验收者核对；原完整 ZIP 未生成。新验收等待原终端的准确根结束回执 |
| 撤权后等待终端 `endedAt` 45 秒超时 | 原任务、终端和根 UNKNOWN，输出 UNCONFIRMED，原 shell 已停。独立审计保留原 UNKNOWN 和零原生授权的 resize；新验收不要求或补造完整终端结束回执 |

最后两份原 UI 失败在 `.runtime/alpha20-native-ui-fourth-and-fifth-original-failure-audit-attempt1` 独立核对，461 个原文件保持。前三份过程审计、每份原报告、失败截图、SQLite 和准确 PID/出生停止记录继续保留。

新增三个生产边界用例已经定向通过：原输出发布提交前丢失、提交后 ACK 丢失，以及原生构造器实际创建 shell 后未返回对象。前两项保留原已提交游标、原 raw 文件与 UNKNOWN，重开无补造引用/重放/模型请求；后一项产品回执 PID 0、出生缺失、清理未确认，测试拥有者另记实测身份并独立停止。这是受控返回边界覆盖，不证明上游任意内部异常均已得到完整身份。

当前 main Web 已独立干净构建，13 份第三方资源与核心资源保持原实际字节。冻结生产 dist 上 226 项完整 UI 已通过，0 跳过/失败/重试；44 张历史图片另存本次结果并恢复原字节。原完整核心43套/542项为529通过、10条件跳过、3失败，未生成最终Jar；原两项60秒团队根结束超时与一项20秒子请求未到达见[原失败回执](../qa/alpha20-production-core-original-team-failure-win32.json)。原临时文件现场被JUnit清理的缺口保留，1133份native夹具和48原身份停止独立核对不改变整套失败。

随后原三项用例复现仍有两项根超时；启动时请求的JFR参数被POM中已有argLine覆盖，实际晚附加的8秒采样单独保存，不能称全程采样。独立10次系统PID枚举共2.23秒、原生快照共0.19秒，证明当前读取成本差异，不补造原任务的完整耗时归因。现优先复用已经核验完整布局与本JVM出生事实的Windows原生快照，同时提供PID与出生信息，失败仍回退原系统枚举；每次核验范围结束后重新读取，停止仍核验原句柄。3项新增边界和原3项团队用例通过，原用例耗时分别26/15/28秒，定向29项为28通过/1原有条件跳过。临时零跳过汇总误判的原回执保留，独立按原XML核对；实际JFR启动及载体2/2在原JVM中核验，见[定向跟进回执](../qa/alpha20-native-snapshot-team-repair-win32.json)。当前新完整核心、实际最终Jar和包内JRE/asar继续等待各自原回执，暂存Jar的4项HTTP和5项UI成功不认证当前生产产物。

## 发布依赖和许可

| 组件 | 原发布字节与范围 |
| --- | --- |
| pty4j 0.13.4 Jar | SHA-256 `bc1bb1855a20a5f99583890ed0ed23165f2a7c5b64be037db2c46fc5533edccc`；24 个原生项逐项固定。原 Jar/POM/Gradle module 与官方校验一致，原 Jar 未携带 LICENSE/NOTICE |
| pty4j sources.jar | 66,477 字节，SHA-256 `0669c0c5b41abb1c71a42730f4002895929c162371651b6f29bffb04da38ce01`；随产品保存原发布 Java 源码 |
| Kotlin stdlib 1.9.22 | Apache-2.0；固定 tag 对应 `44ed2e94f57d63b380a81ad9dceba95b57f2f33d`，原 LICENSE/NOTICE 随产品携带 |
| annotations 24.0.1 | Apache-2.0；固定 tag 对应 `784429a1111930d51904cf8dcf4259f077d6bc7f`，原 LICENSE 随产品携带 |
| WinPTY / OpenConsole | 固定上游 MIT 文本分别对应 `7e59fe2d09adf0fa2aa606492e7ca98efbc5184e` / `f6de9e0618015def1c9dda5b82f9d3abf03eb799`；实际发布 native 的准确构建 commit 未证明 |
| xterm / FitAddon | 原官方 npm MIT 文件和 tarball；本项目 Web 构建生成自己的 bundle，不能称生成 Web 字节与原 npm bundle 完全相同 |

JNA 5.17.0 与 SLF4J 2.0.17 沿用既有产品依赖。两处 `third-party` 保存许可、notice、origins、SOURCE-OFFER 和原 sources.jar；正式发行时须核对 Jar、asar 和实际资源，不能只引用创建资源时的旧摘要。具体许可入口见[第三方依赖](../third-party-dependencies.md)和[源码提供说明](../../core/src/main/resources/third-party/SOURCE-OFFER.txt)。

## 最终生产产物跟进（2026-11-17）

快照修复后完整 `clean verify` 为44套/545项，535通过、10条件跳过、零失败或错误，33分钟，载体2/2保持。原RuntimeIntegrationTest和TeamExecutionTest源码、断言、时限及预算未变。最终Jar为44,336,302字节，SHA-256 `66d0a60fe86afa9e3a0eef86ab44412ed7a9b4d2eb59950a911d871f029e96fa`，160个class、16份资源、36份依赖和24份native逐项核对，见[完整核心回执](../qa/alpha20-core-build-win32.json)。审计器先假设全部依赖都有独立Maven文件，后又按带版本文件名检索插件内条目；两次失败保留，最终按原loader-tools插件中的`META-INF/jarmode/spring-boot-jarmode-tools.jar`与产物内带版本文件逐字节匹配。

同一普通最终Jar的真实HTTP4项及生产Web/native5项通过。原CLONE/WORKTREE使用真实系统shell，控制代次、一次人工输入效果、Unicode、原生尺寸、查看/接管/重载与完整输出ZIP分别核对。强杀原核心后核对Worker、shell及已观察子进程停止，重开保留UNKNOWN、原输入ACK、输出缺口和累计预算，人工核对不会重放输入；撤权后任务、终端及根UNKNOWN保持。HTTP602份原现场与221份原身份、Web119份原身份独立核对，见[HTTP回执](../qa/terminals-http-win32.json)与[Web/native回执](../qa/terminals-native-ui-win32.json)。1440px控制与430px查看截图已目视。原协议29项实际通过，临时汇总器因旧报告没有版本字段误判，原回执保留，原退出码0、29项结果与前后Jar摘要独立核对。

同一最终Jar的原恢复、队列、工作区、写入、文件版本、差异、检查点、协议和守卫全部通过，独立浏览器及实际开发/发行桌面完整重开通过。实际发行物内Jar/JRE/asar终端5项通过，核心PATH没有外部Java或Node；源码、Web、13份许可资源与内置JRE全部原文件摘要核对。首轮ASAR审计在Windows使用正斜线时失败，包内原资产实际存在，修正两处读取路径为宿主原生join后全流程通过。原失败保留，见[本机正式验证](../qa/alpha20-validation-win32.json)。

首轮生产提交`96d9edb`的三端CI均完成核心545项并失败，未生成新Jar；三份实际上传ZIP大小/digest和原XML、native现场已核对。POSIX尺寸断言读取了shell刚创建但未写完的文件，原保留文件最终均为`37 101\n`；macOS原第138行字节为`TAIL_79f345d2557f45a99c6297f419da77a6_138\r\r\n`，完整编号和内容保留。修订夹具等待独立完成标记，并按LF逐行验证完整编号，只忽略行尾CR；不修改输出存储或原字节。Windows长路径测试的最后remote查询退出0，但stderr原警告混入预期为空的stdout；夹具分别排空两流、保留原stderr/退出码并上传诊断，保留全部文件/index/配置和长对象断言。

Windows另两项原15秒/10秒等待保存了已回显命令和原PowerShell子进程，初始JSON/目录证明尚未产生；内部停在哪一步仍未知。没有延长原时限，新增命令开始与事实就绪标记，以及停止前原PID/出生、命令、CPU和结果完成状态。当前本机修订20项终端、3项长路径及10项诊断通过，1135份原native文件、两处输出发布故障和48原身份停止核对；这些成功不改写原CI失败。见[原CI失败](../qa/alpha20-ci-first-failure.json)与[定向跟进](../qa/alpha20-ci-portability-followup-win32.json)。

所有开发和提交集中在 main。修订提交的完整三端CI、完整历史进程树、长期负载、其他 F01–F21 与 M0–M3 条件继续实施；测试失败、UNKNOWN 与现场缺口保持独立事实。

## 第二轮三端原执行与跟进（2026-11-17）

准确提交`1d33e95`的[run 44002160511](https://github.com/lihe-he/multi-agent/actions/runs/44002160511)三端终态failure，原包分别124136451字节（Ubuntu）、80380227字节（macOS）、27772292字节（Windows），整包GitHub digest及内部原XML、native夹具、UI trace均核验。每端完整44套/545项；macOS与Ubuntu核心535通过/10条件跳过，Windows535通过/8条件跳过/2失败。原Windows长路径三项已通过，原POSIX尺寸和完整尾部用例通过，见[准确失败记录](../qa/alpha20-ci-second-failure.json)。

Windows两项原PowerShell等待保留命令进入标记、未完成事实文件和原停止前进程；两个原子PID7784/2696分别耗CPU约13.11/10.06秒。初始脚本在控制台属性与`Get-Location`组合内未到事实就绪；目录脚本在`Get-Location`后未写出证明。没有采到原内部CPU栈，不据此证明具体PowerShell内部缺陷。夹具现直接读取.NET进程当前目录、控制台尺寸和重定向属性，仍用相同实际PTY输入，保留原15/10秒与全部目录替换、尺寸、Unicode和200行尾部断言；本机完整原生套件10项通过。

macOS完整UI225通过/1失败。原trace中请求计数失败时两次`after=0`可见，第三次91字节原输出响应在失败后3.788ms返回。修订先等待既有5秒内的原输出排空状态，再核对准确三次请求、一次原输入及零新shell；本机完整生产UI226项、零失败/跳过/重试，原Web字节保持。

Ubuntu真实终端HTTP前三项通过，强杀时原独立停止探针的`stopRequested`断言通过，但`originalStopped`五秒内仍为false；探针完整返回在原断言后才保存，失败时缺失。拥有核心的Node父进程当时用`execFileSync`阻塞等待探针，其后原核心SIGKILL、所有原身份停止分别可核验，原OS僵尸状态未记录，具体回收归因属于推断。现用异步`execFile`保留相同原PID/出生探针、5秒停止要求和30秒命令外限，Node可继续处理退出事件，且在断言前保存原停止返回。本机实际最终Jar的完整4项HTTP/崩溃恢复通过，UNKNOWN、单次输入效果、预算和零重放均保留。

这三处均未更改产品核心或Web。准确源码、选定10项XML、完整226项UI和实际HTTP4项分别见[本机跟进](../qa/alpha20-ci-execution-followup-win32.json)；原本机545项完整构建的测试源码范围保持，不能称其执行了后来的夹具。原两轮失败与新提交完整三端验证继续独立保存。

## 第三轮三端原执行与跟进（2026-11-17）

准确提交`fec42fa`的[run 44011006549](https://github.com/lihe-he/multi-agent/actions/runs/44011006549)终态failure。Windows完整44套/545项为534通过、8条件跳过、1失败和2错误，均停在原Git差异比较的收尾拒绝；三个原失败现场完整保存，但异常没有记录原status、exitCode、流和后代状态，不能由本机重跑通过反推原因。macOS与Ubuntu核心各535通过/10条件跳过，完整UI各226通过。三份ZIP及GitHub digest、原XML和请求现场见[第三轮原CI失败](../qa/alpha20-ci-third-failure.json)。

macOS真实HTTP在原尺寸文件刚创建时读取到空文本，原保留文件最终为`37 101\n`。夹具现在先将`stty size`写入同目录临时文件，再由原`mv`发布目标；实际发行最小PATH加入`mv`。Ubuntu真实HTTP四项已经通过，真实UI的两条人工输入分别一次ACK，且各只有一次原生派发/授权；失败来自摘要预期LF、实际xterm发送CR。生成UI命令改用CR，HTTP POSIX仍用LF，保持原长度、SHA-256、一次效果及完整输出断言。Git Bash的延迟分段`stty()`命令体核对通过，不作为实际macOS/Linux PTY验收。

本机另用相同生产类确定性复现非零退出的后代收尾缺陷：退出0会等原子进程自然结束，退出1则直接停止尚未结束的原后代并返回UNKNOWN。`git diff --no-index`以退出1表示存在差异，因此修复相关，但远端原异常未保存准确返回值，因果归属仍未证实。现对非零退出沿用原有最多3秒及总命令截止时间内的同身份等待；取消、超时、输出不完整、截断和未确认原后代的规则保持。差异拒绝补上status、exitCode和outputComplete诊断，不接纳部分结果。

原自然退出用例委托同一夹具，新增退出1/37两项实例；首次定向38项中的退出37被PowerShell转为1，独立保留原失败。Windows夹具显式`exit $LASTEXITCODE`后五项自然退出/非零退出/UNKNOWN回归通过。新完整生产`clean verify`为44套/547项，537通过、10条件跳过、零失败或错误，40分56秒；全部当前核心输入摘要保持，原545项保留，仅新增两个退出实例。新Jar为44,336,382字节，SHA-256 `d24dc14969c84d9425b6646cb683f55c1f3338bf28a0e350efaf18d733f81249`，160个class、16份资源、36份依赖、24份native和13份许可资源逐项核对。原生故障现场1,164份文件及48原身份停止独立核验，两处输出UNKNOWN和构造器回执缺口保持。

新Jar的首轮本机HTTP在Unicode标记已经可读、环境事实文件尚未产生时失败，原ENOENT及所有原身份停止保留。夹具改为先完成事实文件再输出标记，尺寸和崩溃子PID文件均写完后原子发布。随后HTTP四项通过；首轮真实UI又因较长尺寸命令回显将Unicode标记挤出重载后可访问视口而失败，原截图、输出及停止事实保持。Windows尺寸采集移到同一原脚本的`size`分支，仍读取真实Console尺寸，用短输入调用并保留原长度/摘要/一次动作和重载断言。最终准确夹具的HTTP4项和真实UI5项均通过，UNKNOWN、预算、完整输出ZIP及零重放保持。Git Bash命令体和CR/LF再次绑定最终夹具核对；实际POSIX与包内JRE仍由准确提交三端CI验收。

Java106份/370条原注释与顺序保持，新增Javadoc为0；原TeamExecutionTest源码不变，原RuntimeIntegrationTest的历史用例和边界保留，新增实例单独计数。旧545项Jar和旧发行物继续保存，新Jar及准确提交三端验收各自绑定原回执。

## 第四轮原生终端失败与夹具跟进（2026-11-17）

准确提交`d8846e5`的[run 44037048478](https://github.com/lihe-he/multi-agent/actions/runs/44037048478)中，Ubuntu与macOS job成功；Windows在原20秒CLONE shell Unicode输出边界失败，后续Windows Web/native与发行JRE终端步骤未执行。134,911,394字节Windows artifact完整SHA-256匹配GitHub digest；44套原XML合计547项、539通过、8条件跳过、零失败/错误。原ZIP中的HTTP回执为failed，只有认证检查通过，原manual-count为1，环境文件缺失，三段终端原始输出共515字节且没有目标中文标记。原夹具源码、报告和Jar均从该整包中读取；ZIP提取后将CRLF规范为LF，与基线提交夹具及Runner内容一致。没有原阶段标记或内部CPU栈，不能推断准确PowerShell停滞位置。

恢复被中断的本机夹具跟进时，初版阶段标记用.NET `File.Move`覆盖既有目标；Windows PowerShell 5实际报“文件已存在”，脚本仅记录到COUNT。保留初版失败后，标记改为UTF-8追加行，旧错误与现场在`.runtime/alpha20-terminal-fixture-powershell-recheck-VBMR3J`保留。最终HTTP与UI两类实际Windows PowerShell脚本分别用四个环境变量全缺失和全存在运行，4/4退出0，完整顺序为COUNT/FACTS/MARKER/MARKUP，JSON与Unicode/ANSI/HTML正文逐项核对，无残留临时文件。摘要匹配最终生产Jar的真实HTTP4项和生产Web/native5项通过；各自272/101个进程身份停止，原tracked QA恢复且新回执另存。详见[第四轮失败](../qa/alpha20-ci-fourth-failure.json)及[本机夹具跟进](../qa/alpha20-ci-fourth-fixture-followup-win32.json)。这些Windows本机结果不替代新提交的准确三端CI或包内JRE验收。
