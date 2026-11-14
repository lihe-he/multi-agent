# 逐文件差异研究与回归证据

本轮按 F04 增加逐文件树、原/新行号、高亮和可靠的内容版本绑定。源码与协议独立实现，不新增运行时依赖。轮次撤销和原项目局部采纳涉及检查点及写入归属事务，继续单独实施。

## 固定开源参考

先核验 OpenCode 的 session diff/review、patch、file-diff schema、文件树工具与 MIT LICENSE，再核验 Git 的差异格式、diff、diff-options 与 GPL-2.0 COPYING。十份原文件均按 Git blob 和 SHA-256 核对；原下载字节保存于 `.runtime/alpha18-diff-references-2026-11-14/`，来源摘要见[固定清单](./file-diff-sources-2026-11-14.json)。没有复制上游业务实现，也未运行上游测试。

| 固定来源 | 用于本轮的设计问题 |
| --- | --- |
| [OpenCode `59ed43f0b8da891f03a7333d540a7b0c428049ea`](https://github.com/anomalyco/opencode/tree/59ed43f0b8da891f03a7333d540a7b0c428049ea) | 逐文件审查、文件清单、选择及结果与版本分离；MIT |
| [Git v2.53.0 `67ad42147a7acc2af6074753ebd03d904476118f`](https://github.com/git/git/tree/67ad42147a7acc2af6074753ebd03d904476118f) | 原/新行坐标、NUL 路径、无末行换行、原始字节与文本转换边界；GPL-2.0，仅作工具和文档参考 |

本机 Git 为 2.53.0.windows.2。Git 沿用用户环境中的外部工具，不复制或链接其实现到业务代码。新增 Windows 目录身份核验复用已有 JNA 5.17.0 的原生属性接口，原文件锁路径和许可证保持。

## 原版本绑定缺口

冻结 alpha.17 Jar SHA-256 `cd19e194627d82d758ac14252f1bc9b89a6ead9de801c55e71eb59215fd6cc85`、原编译类和 Jar 内原依赖直接用于独立进程，没有 Maven 重构建。控制真实文件改写发生在原 diff 返回之后、原控制器调用 fingerprint 之前，得到旧显示文本与后来内容版本；边界断言退出 1。记录在 `.runtime/alpha18-diff-baseline-attempt2/`。这是控制顺序的独立诊断，没有声称重建用户机器上某次未捕获的时间线。

第一份诊断在 JDK 原生管道构造处失败，尚未执行 diff 断言，原日志和编译夹具仍保存于 `.runtime/alpha18-diff-baseline/`。第二份增加项目已有 unix-domain 临时目录设置，另建目录，不覆盖第一份结果。

修订共享原指纹遍历，在同次清单捕获文件摘要，选中字节必须匹配原摘要；固定 HEAD 的 Git blob 验证对象哈希。Git 只比较拥有的两个副本，解析结果还需匹配原/新文件全部差异及省略区间。最终重新检查内容版本、HEAD 和合并冲突。实际 old→new 与 A→B→A 用例均检验；原/新 SHA-1、SHA-256、CRLF、无末行换行、空文件、暂存/未暂存/未跟踪、删除与执行位分别覆盖。具体行为见[协议](../diff-view-contract.md)。

## 原失败与诊断

| 原调用 | 原结果与独立处理 |
| --- | --- |
| 核心定向第一轮 | 29 项中 5 失败、11 错误、2 条件跳过；普通 NTFS 目录被当作无可核验身份。三份原路径的独立诊断都显示 NIO fileKey 为 null，真实目录且非链接，复用现有 JNA 原生目录编号后修复。原归档 `.runtime/alpha18-core-directed-attempt1/`、`.runtime/alpha18-temp-identity-diagnosis/` |
| 核心定向第二轮 | 29 项中 5 失败、10 错误、2 条件跳过；本机 Git 不支持 `--no-suppress-blank-empty`，原命令退出 129。独立同参数命令与 stderr 保存在 `.runtime/alpha18-compare-option-diagnosis/`，改用实际接受的 `-c diff.suppressBlankEmpty=false` |
| 核心定向第三轮 | 29 项中 27 通过、2 条件跳过，0 失败/错误；归档 `.runtime/alpha18-core-directed-attempt3/`。之后增加旧接口省略/交付原路径、两并发副本/第三读取者 429、比较期间新合并冲突三项，完整新套件为 32 项 |
| 前端定向第一轮 | 33 项中 32 通过、1 失败；跨任务用例的任务标题 exact 按钮定位没有包含实际项目/短 ID，断言尚未执行。原 trace 和页面快照在 `.runtime/alpha18-ui-edge-directed-attempt1/`；定位到对应任务行，原 30 秒期限保持，没有为此修改产品 |
| 前端定向第二轮 | 33/33，无跳过或重试，生产 TypeScript/Vite 构建通过。六主题×两宽度的实际文字/布局测量及原附件保存在 `.runtime/alpha18-ui-edge-directed-attempt2/`。实际查看改进的宽/窄完整页，以及纯白宽屏、墨夜窄屏；表头与 marker 单元格保留清晰单行，代码横向滚动限定在自身区域 |
| 完整核心第一轮 | 30 套/356 项，349 通过、1 失败、6 条件跳过，0 错误；原后代进程测试仍为 UNKNOWN，但返回文本没有原子 PID。准确源码、类和报告在 `.runtime/alpha18-core-full-attempt1/`，不计作通过 |
| 管道独立诊断 | 冻结 alpha.17 类和候选类使用原 600000 字节、父 750ms、子 15000ms 常量及原断言。alpha.17 返回原子身份，候选读取未完整后同步关闭管道，等待到子自然退出再枚举，漏掉诊断。第二份线程栈实际停在候选 `WorkspaceRuntime.execute` 的 `FileDescriptor.close0`；两份独立记录在 `.runtime/alpha18-descendant-diagnosis-attempt{1,2}/`。第一份 jcmd Unicode 路径失败也保留；第二份用 `Thread.print` 取得原现场。原完整失败没有同次返回文本，不用独立诊断重写原时序 |
| 管道修复定向 | 读取线程自行关闭拥有的流，先保存并请求停止观察到的原子进程，输出完整性和子身份事实都保留。原 RuntimeIntegrationTest 27 项与新 diff 32 项合计 59 项，56 通过/3 条件跳过，0 失败/错误；原断言、等待、预算及测试源码保持。记录 `.runtime/alpha18-core-lifecycle-directed-attempt1/` |
| 最终验证包装器首轮 | 冻结收集已完成，包装器复制自己的源码时仍使用旧文件名，ENOENT，实际 diff 脚本及核心尚未启动。原包装源码和失败回执保存于 `.runtime/alpha18-final-checks/diff-view-attempt1/`；修正包装器文件名后另建第二轮，不改产品或实际验收断言 |
| 原桌面检查第一轮 | 原首个指针拖动等待 320px 在 30 秒超时，主题完成 9 项、侧栏尚未计入通过。原调用未记录准确宽度与事件，原因 UNKNOWN。原失败报告顶层 `originalProcessesStopped:false` 原样保留，三个原桌面/核心/Worker 身份的独立停止结果分别记录；归档 `.runtime/alpha18-final-checks/layout-desktop-attempt1/` |
| 桌面独立诊断第一轮 | 观察包装器读到猜测的不存在源码路径，尚未启动桌面；原包装器与回执在 `.runtime/alpha18-layout-native-diagnostic-attempt1/`。第二轮修正路径，额外观察器下两次完整启动和原断言通过，但不算原最终验收、不回写原失败；归档 `.runtime/alpha18-layout-native-diagnostic-attempt2/` |
| 原桌面检查第二轮 | 首次完整启动的拖动、Escape、重载和收起通过；第二次完整启动的原生尺寸同步在原 30 秒超时，失败页面为 1466×923。原调用没有保存该次请求后的原生 bounds，原因 UNKNOWN，不能与首轮拖动超时合并归因。六个原身份分别确认停止；归档 `.runtime/alpha18-final-checks/layout-desktop-attempt2/` |
| 桌面独立诊断第三轮 | 原第三次拖动等待 370px 超时。同次事件显示指针捕获在首个移动后丢失，侧栏由 330px 回滚至已保存 320px；另有非预期指针移动及截图后的 1×1→1266×813 页面 resize。记录没有指针 buttons，不能据此确认物理鼠标或截图是原因，也不重建原前两次失败；同次原生 bounds/content、事件和截图在 `.runtime/alpha18-layout-native-diagnostic-attempt3/` |
| 原桌面检查第三轮 | 在新建的独立 Windows 桌面上运行未修改的原脚本、断言和时限，主题 12 项、侧栏 10 项及两次原桌面/核心/Worker 完整启动和停止通过，Jar/Web 与源码摘要保持。测试未切换用户输入桌面，运行前后仍为 Default，结束关闭独立桌面句柄。环境包装器和原日志在 `.runtime/alpha18-isolated-layout-desktop-attempt3/`，原验收在 `.runtime/alpha18-final-checks/layout-desktop-attempt3/`；成功不确定前两次原失败的原因 |
| 原打包后签名收集 | 原 builder 一次成功完成，安装器已经生成；包装器随后调用 Windows PowerShell 的 Security 模块自动加载失败，包装命令整体退出 1。原 builder 回执、包装源码和队列原 stderr 全部在 `.runtime/alpha18-final-checks/package-attempt1/`。独立读取同一安装器时，继承模块路径的显式 Import 出现扩展类型重复错误，子进程默认模块路径返回 NotSigned，安装器摘要保持；诊断在 `.runtime/alpha18-signature-diagnosis-attempt1/`。修订只设置签名子进程的默认模块路径，第二份收集验证原成功 builder、源码/Jar/Web 和原安装器，再完成签名读取，原 builder 不重复；`.runtime/alpha18-final-checks/package-attempt2/` 与 `.runtime/alpha18-original-package-verification.json` 分别保留 |

## 指纹兼容与完整前端

独立 Java 进程分别加载冻结的原 alpha.17 类和第三轮候选类，使用同一真实 Git 目录和原 Jar 依赖。基线、混合暂存/未暂存/删除/未跟踪/二进制/空文件/执行位，以及相同长度恢复 mtime 的手动改写三种状态，普通内容指纹逐项完全相同；HEAD/index/config 未改，观察到的原进程均已停止。原类未重构建，记录 `.runtime/alpha18-fingerprint-compatibility/receipt.json`。

最后生产 Web 对应的完整 UI 在 Edge 与 Chromium headless shell 各 162/162，无失败、条件跳过或重试，原 129 项保留。两份独立原调用在 `.runtime/alpha18-ui-{edge|chromium-shell}-full-attempt1/`。协议夹具证明界面请求、选择、迟到响应、状态及实际渲染；实际最终 Jar/原 Worker 的 Git 与生产页面结果由新增 `tools/diff-view-smoke.mjs` 另行收集。

完整第二轮核心 clean verify、最终产物、实际 Jar/生产 Web、原生命周期、发行物和准确三端 CI 的终态以[实施记录](../implementation-status.md)及对应正式 QA 为准。当前研究证据不替代尚未执行的验收。alpha.17 原 20 秒子请求等待原因仍为 UNKNOWN，其原报告和三端成功均保持各自结果。

第二轮 clean verify 已完整通过：30 套/356 项，350 通过/6 条件跳过，0 失败/错误，全部载体 2/2。冻结 Jar SHA-256 `e82eae779857fe0f7e667d5d3f7f2725196edcf7c18d893361ac09afede2990b`，118 个 class 逐项匹配原编译；版本资源与未过滤配置、全部原依赖和许可证字节核对。受改 Java 的原 33 条注释保留，16 条新增 Javadoc 标准多行。准确归档 `.runtime/alpha18-final-core-evidence/`，原完整第一轮失败保持独立结果。

## 实际最终 Jar 与生产 Web

`tools/diff-view-smoke.mjs` 使用上述冻结 Jar 的普通 JarLauncher、原独立 Worker 和同一生产 Web，真实创建 CLONE 与 WORKTREE。最终第二轮 14/14，通过实际版本绑定的读取和写入、独立审查、原交付 ZIP 与新接口的两段 hunk：原/新行均为 2 和 20。十次原 HTTP 请求逐项关联根执行、AgentRun、Worker PID/出生和预算，不用协议夹具结果代替实际 Worker 回执。

实际二进制、排除文件、空文件、删除、字面 `<img>/<script>`、无末行换行、文件字节上限和 5001 行上限分别核对。生产页面在 1440px/430px 渲染字面文本；真实 409 时立即清除旧行，刷新保留选中文件并展示新字节。内容指纹不变但 HEAD 移动时也返回 409。预览前后源项目 HEAD/index/config、任务现场及模型预算保持；旧 `/diff` 的明确省略结果和原交付补丁分别检查。

实际服务还收集并核对 `theme-init.js`，301 个原执行/核心/Worker PID 与出生身份在结束后独立确认停止。预览 Git 由核心运行，未写入 Worker 账本，覆盖声明保留 `CORE_PREVIEW_NOT_PERSISTED_IN_WORKER_LEDGER; TASK_EXECUTION_OBSERVED_ONLY`。六张实际页面图完整归档，其中 CLONE 1440px、WORKTREE 430px 和 WORKTREE 刷新后的 430px 已实际查看。原调用及源码绑定在 `.runtime/alpha18-final-checks/diff-view-attempt2/`，实际现场在 `.runtime/diff-view-0A6WVw/`；[正式结果](../qa/diff-view-win32.json)与独立 `.runtime/alpha18-diff-final-verification.json` 均保留。该脚本和实际报告、原现场已加入 CI，不把尚未收取的 CI 证据记作通过。

## 本机最终冻结

冻结 Jar/Web 的原协议 29、恢复 11、队列 13、工作区 15、根写入 22、文件版本 9、独立客户端、版本/连接/下载守卫 21 及发行物全部通过。文件版本仍为 28 次原请求，每个 clone/worktree 130 条实际/历史记录与完整 ZIP，278 个原身份独立确认停止。普通最终 Jar、原 Worker、所有旧 UNKNOWN 和累计预算各自保留，不把后续新编号视作原未知成功。

开发与发行入口各 12 项主题和 10 项侧栏检查在独立 Windows 测试桌面通过两次完整原启动，原脚本、断言和时限保持；用户输入桌面前后为 Default。包内 JRE 的真实锁/借用/释放探针和原身份停止通过，asar 内 Web/桌面与包内核心逐项核对。原安装器 152670556 字节，SHA-256 `07604edcf9418f02040135eb00a52a7b9822694ad2d08bd70239a73b0c22cbd0`，实际签名 NotSigned，本轮未重测完整安装/卸载。

15 条最终验收、两浏览器原附件、实际原现场、原成功 builder 和诊断/失败全部冻结于 `.runtime/alpha18-final-local-qa/`，正式结果见[alpha.18 本机验证](../qa/alpha18-validation-win32.json)。41 张生成的历史图片已归档并恢复原提交字节，两张新增差异图保留；开发与发行的 320px/最小窗口图、最终 UI 宽/窄差异图本次实际查看，九张累计指定图的摘要和查看范围在 `.runtime/alpha18-visual-review.json`。原 alpha.17 核心 20 秒等待及本轮两个未捕获原因的桌面超时仍为 UNKNOWN；准确三端 CI 待收取，全部 F01–F21 / M0–M3 目标继续实施。
