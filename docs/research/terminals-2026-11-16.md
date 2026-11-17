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

所有开发和提交集中在 main。发行物与准确提交三端CI继续验收，完整历史进程树、长期负载、其他 F01–F21 与 M0–M3 条件继续实施；测试失败、UNKNOWN 与现场缺口保持独立事实。
