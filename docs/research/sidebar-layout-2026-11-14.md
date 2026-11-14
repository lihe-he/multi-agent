# F01 可拖动侧栏研究与验证

日期：2026-11-14。版本：0.2.0-alpha.17。产品边界见[侧栏布局约定](../sidebar-layout-contract.md)，实际结果见[实施记录](../implementation-status.md)。

## 已固定的开源来源

优先复用上一轮取得的 OpenCode 源码事实。本轮重新核对五份源码的 Git blob SHA-1、SHA-256 和字节数，以及同提交的 MIT LICENSE；没有引入其 Solid、Effect 或持久层依赖，也没有复制其实现代码。

源提交：[anomalyco/opencode `59ed43f0b8da891f03a7333d540a7b0c428049ea`](https://github.com/anomalyco/opencode/tree/59ed43f0b8da891f03a7333d540a7b0c428049ea)。

| 来源 | 采用的思路及边界 |
| --- | --- |
| [resize-handle-machine.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/ui/src/components/resize-handle-machine.ts) | 固定一次按下的指针、原尺寸和边界；区分点击、拖动和释放，其他指针不能结束原拖动 |
| [resize-handle-machine.test.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/ui/src/components/resize-handle-machine.test.ts) | 取消、丢失捕获、失焦、卸载和原指针归属的覆盖；本轮没有运行其 Bun 用例 |
| [resize-handle.tsx](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/ui/src/components/resize-handle.tsx) | 可信指针捕获、原 body 样式恢复和共同清理路径；原组件没有提供此次键盘实现 |
| [resize-handle.css](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/ui/src/components/resize-handle.css) | 边缘命中区域与调整状态；本项目颜色继续来自六主题语义变量 |
| [layout.tsx](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/app/src/shell/state/layout.tsx) | 有限数值、布局版本和默认值；本项目采用独立客户端记录 |

[同提交 MIT LICENSE](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/LICENSE)的原字节已复核。采用设计思路不等于取得上游测试或兼容性验收。

另复核 [W3C APG Window Splitter Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/windowsplitter/)的原 HTML，SHA-256 为 `04d6163a3d7dfee412408fc9536ddfbe3ef4a2fb904aa80e1c574699d2462450`。参考其左右箭头、Enter、可选 Home/End、名称、主面板关联与当前/最小/最大值要求。本项目明确以像素标注大小，键盘和收起后的焦点独立实现；该模式页本身不是产品完成证明。

本轮原字节复核记录位于 `.runtime/alpha17-references-verified-2026-11-14.json`，固定来源缓存和前一轮复核继续保留。公开来源摘要随本轮验收记录提交。

## 实现取舍与自审

侧栏布局从 App 的临时收起状态移到独立组件。拖动预览使用一个待执行动画帧，只更新共同 CSS 宽度和分隔器尺寸；提交才保存偏好，任务、日志和输入继续使用原 React 状态。大窗口意图与实际显示宽度分别处理，避免缩小窗口或无移动点击把原意图改写。

布局记录显式校验版本、字段类型和宽度。捕获只属于原主指针，释放前先清除归属，取消与正常提交共用收尾。body 原属性值和 important 优先级一起恢复；取消、窗口变化及卸载不会提交临时宽度。

同源同步读取最新记录，拖动期间延后。保存失败后保留本窗口意图和提示，恢复事件不回读旧值；明确调整会再次尝试保存。布局偏好没有复用连接令牌或服务端授权状态。

六主题焦点线分别对侧栏和内容背景核验，增加键盘说明及恢复默认入口。现有六主题桌面验收保留 12 项，新增侧栏检查共用同一真实启动和原进程核验，使用独立报告计数。

## 保留的原结果

新增功能的基线在 alpha.16 `045f72b913370e5249508c140226e66e630bdd99` 上失败：真实拖动用例找不到可聚焦分隔器。基线源码、报告和 trace 保存于 `.runtime/alpha17-sidebar-baseline-20261114/`，没有把功能缺失写成通过。

首轮实现后的 20 项定向测试中 16 通过、4 失败。四项浅色焦点测量使用透明 body 的 `rgba(0,0,0,0)` 通道作为黑色背景，遗漏了已绘制的页面根背景；原报告、附件、源码及静态产物保存在 `.runtime/alpha17-sidebar-directed-attempt1/`。修订测量读取实际页面根背景，并核对顶栏和侧栏；不是更改焦点颜色或降低 3:1 阈值。修订后的定向 20 / 20 通过，同源窗口排队事件、窄窗口无移动点击和保存成功后的刷新另在原行为断言中覆盖。

第一次完整 UI 为 124 通过、2 失败：旧主题用例的全局 `getByRole('status')` 同时找到原主题状态和新增布局保存提示。原主题文案和功能检查保留，定位限定到“当前外观”状态；原报告、源码、产物和 trace 在 `.runtime/alpha17-ui-full-attempt1/`，未隐藏布局提示或放宽存储失败断言。

后续完整 UI、核心、实际桌面和发行目录结果分别记录；通过结果不覆盖上述原失败。完整浏览器 BFCache、触控和笔设备、系统原生控件与长期负载仍需另外验收。

## 构建版本校验跟进

首个 alpha.17 Jar 完成 323 项核心测试，但实际桌面检查暴露了遗漏：Jar/桌面已为 alpha.17，核心 health/state、MCP 客户端/服务端和策略记录仍含 alpha.16 字面值。首轮清理断言遮住原错误，当时只记录原 Electron 的停止，不能补称已核对全部原进程；完整原现场位于 `.runtime/alpha17-desktop-initial-failure/` 及其原桌面目录。

诊断脚本改为先记录 health/state 版本和原进程，再执行版本断言；原检查、期限和停止条件保留。原错误与清理错误分别保存。相同旧 Jar 的后续真实启动复现 health/state 都为 alpha.16，并确认原桌面、核心和 Worker 三个身份全部停止，原版本基线在 `.runtime/alpha17-version-baseline-evidence/`。

核心增加 `WorkbenchVersion`，从 Maven 单独过滤的构建资源读取版本，API、MCP 和策略共用。应用的环境变量配置继续作为未过滤资源复制。缺失或未过滤版本拒绝使用，避免继续报告旧版本；新增构建资源测试与四项发布声明守卫，根、Web、两份锁文件和 POM 在构建/打包前核对，并接入 CI。既有 Java 注释原文和格式保留，新增 Javadoc 使用标准多行。

## 真实重开与实际可用宽度跟进

统一版本后的候选桌面第一轮通过指针提交、Escape、拖动中的渲染器重载、320px 保存及收起，三个原进程正常停止；第二轮等待连接按钮时超时。已收起侧栏只渲染图标，连接按钮缺少可访问名称。原报告、源码和候选 Jar 位于 `.runtime/alpha17-candidate-desktop-failure/`。连接按钮现有状态文案同时用于稳定的可访问名称，完整 UI 中增加收起后重载的原连接按钮检查。

新增 986px 窗口、右侧预留 15px 的实际几何回归在原计算中失败：主内容为 705px。原报告、测量附件和源码保存在 `.runtime/alpha17-width-baseline/`。宽度上界改由实际容器测量，并观察容器变化；稳定滚动条预留避免宽度往返变化。最终完整 UI 127 / 127，无失败、跳过或重试；该场景实际容器 956px、侧栏 236px、内容 720px，保存的 420px 意图继续保留。六主题分隔器最低左侧对比 7.694:1、右侧 11.448:1。完整 UI 和 264 次主题视图测量见[UI 证据](../qa/alpha17-ui-validation-win32.json)，原源码、生产 Web 与附件在 `.runtime/alpha17-ui-final-available-width/`。

生产主题脚本的首轮在存储受阻场景也匹配到两个 `status` 元素，已完成的前两个场景继续保留，整条退出 1。原脚本、生产 Web 和结果在 `.runtime/alpha17-themes-web-attempt1/`。读取限定到“当前外观”，原主题文案、页面恢复事件和断言保留；相同 Web 的四个完整生产入口场景通过，记录在 `.runtime/alpha17-themes-web-attempt2/`。布局提示继续可见。

## 核心原等待失败与独立诊断

统一版本的第二轮完整 clean verify 共 29 套 / 324 项，319 通过、1 失败、4 条件跳过，0 错误；没有生成本轮最终 Jar。失败是 `disablingDefinitionStopsPendingChildWithoutChangingItsFixedVersionOrRetrying` 在原 20 秒内未收到子请求，尚未执行禁用定义动作。原源码、编译类、完整报告和退出 1 的日志保存在 `.runtime/alpha17-core-clean-failure-v2/`。该用例原来没有保存此等待点的线程和派发快照，原临时目录随 JUnit 清理，不能事后重建原因，原因仍为 UNKNOWN。

使用原生产/测试源码、原 20 秒期限和原 15 秒子请求等待的独立单项诊断通过 1 / 1；仅将 JUnit 临时目录保留设置用于诊断，原实际数据单独复制至 `.runtime/alpha17-team-entered-original-diagnostic1/actual-temp/`。单项成功不改变完整构建的原失败。

现有子请求启动失败快照增加可区分撤回动作的调用入口，禁用定义测试的原断言仅补充失败时的线程、请求、派发和进程事实保存；20 秒期限、15 秒等待、预算和业务断言保持原值。按 Java 字符串、字符和文本块边界核对，受改文件的原 16 条注释原文及开头缩进保留，6 条新增 Javadoc 为标准多行。旧正则统计误把两个 URL 字符串片段计为注释，原 18 段统计与修正前报告分别保留在 `.runtime/alpha17-java-comment-audit-final.json` 和 `.runtime/alpha17-java-comment-count-correction/`，词法核对见 `.runtime/alpha17-java-comment-token-audit.json`。随后完整 clean verify、实际最终 Jar 和三端 CI 结果另行记录。

## 最终本机核对

最终第三轮 clean verify 29 套 / 324 项，320 通过 / 4 条件跳过，0 失败或错误，全部载体 2 / 2。Jar SHA-256 为 `cd19e194627d82d758ac14252f1bc9b89a6ead9de801c55e71eb59215fd6cc85`，112 个 class、构建版本及未过滤应用资源与本次原编译对应，原生依赖和许可证字节保留。核心与完整 UI 后的 13 条最终顺序验收前后 Jar、Web 和源码均保持相同摘要，正式汇总见[alpha.17 本机验证](../qa/alpha17-validation-win32.json)，准确原产物归档于 `.runtime/alpha17-final-local-qa/`。

真实开发和发行程序分别保留原主题 12 项及新增侧栏 10 项，各在同一独立用户目录完整启动两次。320px 和收起选择在新原桌面/核心/Worker 启动后恢复，Escape、拖动中渲染器重载、键盘展开、实际最小窗口和默认恢复通过；实际最小窗口内容为 986×644，侧栏 250px、主内容 720.666687px，保存意图仍为 320px。每种入口六个原身份先存活核验再确认停止，历史身份另行核验；发行目录禁用外部 Java。开发与发行最终截图已查看。生成的历史 PNG 另行归档后按修改前快照和 HEAD 摘要恢复，避免将原版本图片误写为本次证据。

相同最终 Jar 的协议 29、恢复 11、派发 13、工作区 15、根写入 22、文件版本 9 项及独立客户端通过；文件版本共 28 次原请求、每个 clone/worktree 130 条完整历史/ZIP与 502 条原身份停止均核对。asar 的全部 Web/桌面字节与当前产物一致，包内核心摘要一致，内置 JRE 实际锁探针通过。Windows 安装器 152642732 字节，SHA-256 `1787b04bb272bd4b20267d6dcc9a5e453ee5df10000cb16db7a38110e123bc48`，签名实测 NotSigned，完整安装/卸载未重测。准确提交的三端 CI 继续核验，原未知原因和各轮失败不由本机成功替代。

## 原生产 CI 的缩窗顺序跟进

原生产提交 `a97ca19b76f8f1bf60a5827e2472bd41c49f954d` 的 [run 43553364383](https://github.com/lihe-he/multi-agent/actions/runs/43553364383) 三端终态 failure，三份实际证据 ZIP 大小和 SHA-256 均匹配 GitHub digest。三端核心都是 29 套 / 324 项，Windows 321 通过 / 3 条件跳过，macOS/Ubuntu 各 316 通过 / 8 条件跳过；UI Windows 126 通过 / 1 失败，macOS/Ubuntu 各 125 通过 / 2 失败。三端均在分隔器隐藏后的焦点断言失败，macOS/Ubuntu 另在拖动中改变尺寸后得到 344px，未恢复 215px。原 5 秒等待和状态断言保留，后续桌面与文件门禁未执行。准确结果和原 trace 见[初始 CI 回执](../qa/alpha17-initial-ci-layout-receipt.json)。

复核本机已安装的开源 Playwright 1.64.1（Apache-2.0）视口更新源码：它等待浏览器协议更新，不等待页面的 resize 监听或 ResizeObserver 回调。源码摘录、整份 bundle 摘要与许可证保存在 `.runtime/alpha17-playwright-viewport-reference.json` 和 `.runtime/alpha17-playwright-license.txt`；未复制其实现到产品，也未增加依赖。本机完整 Chromium 首次仍在启动处返回 spawn UNKNOWN，两项 UI 断言尚未执行；同版本 Chromium headless shell 可以启动，原启动失败在 `.runtime/alpha17-layout-ui-baseline-chromium-directed/`。

独立诊断对实际生产 Web 使用真实视口和鼠标操作，仅延后原页面通知交付，复现先松开后通知仍保存 344px 的状态。焦点场景在实际缩窗后执行 DOM 失焦并延后通知，原 5 秒内按钮未得到焦点；这没有重建原 CI 的准确回调顺序。首次诊断还用默认 role 定位已经隐藏的分隔器，定位超时单独保留；改用明确 DOM 引用后两种状态完整复现。原回执分别在 `.runtime/alpha17-layout-event-order-baseline/` 和 `.runtime/alpha17-layout-event-order-baseline-attempt2/`。

新增两项原生视口/鼠标与通知顺序回归，保留原 127 项。修订前四项定向为 1 通过 / 3 失败，其中原普通拖动取消也复现；修订后 4 / 4。松开现在核对实际视口、高度和容器宽度，尺寸已变化时取消提交；分隔器保留焦点归属，在 CSS 隐藏失焦时交给按钮，其他输入已有焦点时不抢占。ResizeObserver 继续观察实际容器，布局更新进入后续动画帧以避免同一轮高度变化的通知错误；取消和卸载仍释放两类动画帧与捕获。

最后生产构建通过，Edge 与 Chromium headless shell 各完整 UI 129 / 129、侧栏 23 项，原 127 项和全部期限保留。每种浏览器均有 264 次主题视图测量，分隔器最低左侧 7.694:1、右侧 11.448:1；独立生产 Web 通知诊断 2 / 2、原生产主题四场景通过。只修改侧栏和回归，核心源码与原 Jar 字节保持；八条顺序跟进验证新的 Web、开发桌面、协议、独立客户端、打包、发行启动、发行布局和内置 JRE 写入。实际两种桌面各主题 12 / 侧栏 10、两次完整原启动及最小窗口通过，最终五张图已查看。当前安装器 152642879 字节，SHA-256 `a3cd42ee366da71f1cb36ceb102bb1b00308ccf3e5cd78a27dc0c5c4bec9c2ce`，NotSigned。准确来源和产物在 `.runtime/alpha17-layout-followup-local-qa/`，见[本机跟进验收](../qa/alpha17-layout-followup-win32.json)；原冻结归档不回写，修订提交的三端 CI 继续核验。

准确生产提交 `71743f6cff18e57f93553ddc8e2897411e339548` 的 [run 43561584652](https://github.com/lihe-he/multi-agent/actions/runs/43561584652) 三端终态 success；三份实际证据 ZIP 的大小 / SHA-256 匹配 GitHub digest，原夹具源码对应此提交，准确 Jar、Web/config 与包内核心一致。每端 29 套 / 324 项、载体 2 / 2，win32 321通过/3条件跳过，darwin 316通过/8条件跳过，linux 316通过/8条件跳过；UI 129、生产主题四场景、发行主题 12 项和侧栏 10 项分别计数，原 320px / 收起选择在同一用户目录完整退出重开后恢复，拖动中重载、实际原生最小窗口 / 意图保全和 health/state alpha.17 版本均核对。各原 PID / 出生先存活再停止、旧身份继续停止、原恢复 / 派发 / 工作区 / 写入 / 文件版本 / 独立客户端和内置 JRE 均验证。正式结果见[alpha.17 三端回执](../qa/alpha17-ci-sidebar-receipt.json)。发行物 artifact 只记录元数据，本机原冻结归档及各轮失败不回写；原核心 20 秒等待的原因仍为 UNKNOWN，整个 F01–F21 / M0–M3 目标继续实施。
