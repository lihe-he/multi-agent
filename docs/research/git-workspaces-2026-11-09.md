# 分支选择与真 worktree 研究

研究日期：2026-11-09。产品依据为计划书 9.1、9.5、18.1 与 F03；源码基线为 alpha.12 浏览器修订 `835d27c450f16b3c0e562e374301c76cb1ccb950`。本轮先补所选提交的隔离 clone/worktree；当前目录直接执行、完整归档恢复和排他写入租约继续按迁移顺序实施。

## 固定开源依据

参考 [Git v2.51.1](https://github.com/git/git/tree/81f86aacc4eb74cdb9c2c8082d36d2070c666045)，固定提交 `81f86aacc4eb74cdb9c2c8082d36d2070c666045`，GPL-2.0。只参考命令、元数据和恢复语义，不复制上游实现。GitHub contents 返回的下列 blob 与本机 `git hash-object --no-filters` 均一致，缓存和来源清单位于 `.runtime/research-worktree-2026-11-09/`；本机 Git 为 2.53.0.windows.2。

| 来源 | blob | 对本项目有影响的事实 |
| --- | --- | --- |
| [worktree 实现](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/builtin/worktree.c) | `812774a5ca992c5523116811239bb5a2f505f677` | add 注册独立 HEAD/index 与原仓库关联；锁定、占用和失败清理具有独立条件 |
| [worktree 命令文档](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/Documentation/git-worktree.adoc) | `8340b7f028e6c1c3bae3de0879e9754098466d14` | 默认 add 会创建分支；显式 detach 可从固定提交建立现场。普通仓库配置和引用共享，HEAD/index 独立；lock 阻止自动清理，remove 默认拒绝脏目录 |
| [仓库扩展](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/Documentation/config/extensions.adoc) | `532456644b770e7f9a8d15b5eaac9eef143a5315` | worktreeConfig 需要仓库扩展；不能把普通 config 当作单个 worktree 的私有配置 |
| [add 测试](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/t/t2400-worktree-add.sh) | `023e1301c8e68eebf67ac9185fcf01a80ab91d54` | 覆盖 detached、原分支占用、锁定、已有路径和 checkout 失败 |
| [move 测试](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/t/t2403-worktree-move.sh) | `0bb33e8b1b90fb403de0bef859743226a1aba6b9` | 锁定及缺失路径有独立边界，不能靠手工移动或删除目录维持有效关联 |
| [许可证](https://github.com/git/git/blob/81f86aacc4eb74cdb9c2c8082d36d2070c666045/COPYING) | `536e55524db72bd2acf175208aef4f3dfc148d42` | 来源许可已核验；本轮不引入 Git 源码或运行库依赖 |

## 原实现与缺口

`WorkspaceRuntime.prepare` 只创建独立 clone，从执行准备时的源 HEAD 建立 detached 目录，删除 clone 的 origin，并可复制当前未提交修改。它保存 preparation.json，但没有可选分支、真 worktree 关联或创建任务时的提交选择。根持久队列已保存实际领取来源；等待期间的所选代码与源暂存区还需要独立冻结。

原 clone 的本地 user.name、user.email、hooksPath 配置只影响副本。相同调用放进 linked worktree 会改写共享配置，因此真 worktree 不能复用这些持久配置写入。分支名从受绑定仓库的实际 refs 枚举，传给创建命令的是已核验的提交对象，目录固定在任务数据范围；新现场采用 detached，保留原分支和暂存区。

## 实施与核验安排

选择快照保存策略、完整 ref、所选提交、源 HEAD/分支、内容及暂存区来源。UI 读取当前仓库绑定的本地/远程引用，显示实际版本；创建任务和实际准备分别检查，过期选择要求用户刷新。带入未提交修改只允许以当前 HEAD 为基线，不把针对另一个提交的改动静默套到所选分支。

准备意图先保存，clone/worktree 实际命令由原独立 Worker 领取执行；完成回执保存实际 Git 目录、共同仓库、锁定归属和内容版本。完整 READY 回执可以在核对后复用；部分准备、缺失目录、外国目录和关联变化保留现场并拒绝覆盖。根动作及 Worker 领取固定本次选择，未知准备不自动再创建。

验证使用真实 Git、SQLite、独立 JVM 和最终 Jar：原 HEAD/分支/暂存区/配置保全，选定分支的文件与规则来源，脏修改带入，过期选择，已有目录和符号链接拒绝，原编号重开、部分准备、锁定归属和异源关联，以及 UI 和完整交付包。各项结果在实际执行后追加，不把源码推断记为运行通过。

## 审查修订与失败保全

新 clone 不再先调用旧 HEAD 准备器再换版，而是 no-checkout 克隆、明确获取固定 SHA 并 detached checkout。真实 Git 定向 19 项中 18 通过、1 项 Windows 符号链接权限跳过；其中两项分别从 clone/worktree 读取只在远程引用出现的提交，并在选择后移动该 ref，仍保持原 SHA。原 index、config、refs 和源文件分别核对，日志 `.runtime/alpha13-workspace-targeted-attempt3.log`。共享 worktree 的配置没有沿用 clone 的持久写入。

同一次定向全共 53 项，三项新夹具失败另保留在 `.runtime/alpha13-targeted-failure3/surefire-reports/`：两项在 PID 值相同但 Java Long/SQLite Integer 类型不同处失败，一项向租约传入 Windows TEMP 短别名而不是绑定的实际路径，被原目录门禁拒绝。修正只统一数值比较并使用实际目录，三个针对性检查通过，日志 `.runtime/alpha13-workspace-targeted-attempt4.log`；这不是重跑全部 53 项的通过记录。更早的漏导入编译失败和 17 项初稿记录继续保留。

浏览器首次 20 项中 18 通过、2 项因请求数预填为 2 而实际开发挂载/刷新共 3 次失败。新断言确认显式刷新后读取新选项，并核对提交中更新后的内容/index；没有限制 React 挂载次数。原 trace 与错误现场在 `.runtime/alpha13-workspace-ui-failure1/`，最后全量 UI 60 / 60 通过，日志 `.runtime/alpha13-ui-full-attempt1.log`。1440px/430px 的固定提交、纯文本 ref/owner、历史和长目录换行截图已实际检查。

恢复预检区分尚未准备和原 READY；已有准备也固定原源目录，同源重新授权保留旧规则。选择历史保存原规则内容，不能只依赖已提交 baseline 回溯未提交 AGENTS.md。父目录在逐层创建前核对已有路径，避免通过 junction 先在外国目标留下空任务目录；该 Windows 回归及最新规则历史进入干净全量 verify，结果随后按实际报告追加。

最终 Jar 验收入口为 `npm run test:workspaces`，协议见[固定启动现场](../workspace-selection-contract.md)。外部执行器只建立原未领取窗口；真实 Worker 完成 READY 并 ACK 后，由专属 SQLite 触发器拒绝保存 task 的准备回执，再以普通 JarLauncher 重启。源码与夹具哈希、原元数据、两次根尝试、源变化后的原归属、预算及 ZIP 都保存；部分 PREPARED 意图另标为明确人工故障窗口，不能与真实 READY 保存故障混为同一个场景。当前 Jar 验收尚待本轮最终产物执行。

首轮干净核心 verify 共执行 246 项，1 失败、0 错误、2 跳过。失败为 Windows junction 夹具的 PowerShell 辅助进程超过 20 秒，尚未进入目录拒绝断言；完整原日志及 XML 保存在 `.runtime/alpha13-core-clean-failure1/`。夹具已改为 Node 原生 junction 创建，后续结果单独记录。新增 `WorkspaceSelectionConflict` 将源提交、文件或 index 过期映射为固定刷新提示 HTTP 409；普通无效参数仍为 400。第五次定向命令误用了系统旧 JDK，在测试前以“不支持发行版本 21”中止，原日志 `.runtime/alpha13-core-targeted-attempt5.log` 保留，下一轮使用项目固定 Java 21.0.13。

第六次定向在项目 Java 21.0.13 下执行 8 / 8 通过，0 失败/错误/跳过；包括原 junction 目标保持零文件、源 HEAD/内容/index 变化，以及选择冲突类型。日志与实际 XML 保存在 `.runtime/alpha13-targeted-fixed6/`，完整核心和实际 HTTP/Jar 验收另行记录。

后续界面审查发现 READY 元数据已保存而任务 workspace 回执为空时，原弹窗会误把它当作未准备任务，并强制重新选择。读取快照补充 `workspacePreparationPresent`，使已有任何准备记录保持原选择，更新同源执行授权时不提交替换选择；实际原目录/未知效果仍由核心核对。生产 Web 的真实 READY 保存故障验收同时覆盖该弹窗，而非只通过 API 绕过界面。

第二次干净 verify 22 套、246 项中 244 通过、2 项 Windows 权限跳过，0 失败/错误；全部 XML 的虚拟线程 parallelism/maxPoolSize 都为 2。该中间产物 SHA-256 `6cbf5e941dba989a19809e2a7cee18f09f3d1e29d016cf92e63300ca66430f14`、完整报告和当时 Java 源码哈希保存在 `.runtime/alpha13-core-clean-success2/`。它尚不包含随后补充的任务读取字段，不用于替代最新 Jar 的最终验收。

包含读取字段的第三次干净 verify 同样为 22 套、246 项，244 通过、2 项权限跳过，0 失败/错误，载体 2 / 2；准确 Jar SHA-256 `c625a2a97afe1e325d5078927b22f63756acfe5c1ded5d23d655c0f6814dcee8` 与完整源码/报告在 `.runtime/alpha13-final-core-evidence/`。最新 Web 构建和全量 UI 62 / 62 通过，新增两个尺寸的缺失 task 回执授权场景；原截图、报告和静态文件哈希保存在 `.runtime/alpha13-ui-full-evidence2/`，六张无关历史截图恢复原字节。

首轮实际 Jar 验收前 7 项通过，随后刷新页面时的脚本过早结束等待：截图显示 select 仍在加载，DOM 临时值为 HEAD，尚未读取新分支选项。日志、原脚本、实际任务 ZIP、源码/Jar/Web 哈希和清理前事实在 `.runtime/workspace-selection-YaUczx/reports/`，日志 `.runtime/alpha13-workspaces-jar-attempt1.log`。等待条件改为先收取该次真实 workspace-options 响应、核对新 index，再等待原 ref 恢复；Java 与生产 Web 未因此修改，后续同产物复验单独记录。

第二轮同 Jar/Web 的前 8 项通过，实际刷新创建已保留策略/ref 并提交新 index，草稿没有触发执行。随后任务列表定位超时；原截图和源码确认按钮的可访问名称包含任务标题、项目名和任务编号，脚本错误地使用只有标题的 exact 匹配。失败日志 `.runtime/alpha13-workspaces-jar-attempt2.log`、原脚本、截图、任务 ZIP 和原 Worker 退出核验在 `.runtime/workspace-selection-9dPBWZ/reports/`。定位改为标题前缀匹配，不改产品界面或生产 Jar/Web，第三轮继续验证剩余恢复流程。

2026-11-10 第三轮同 Jar/Web 的实际验收 15 / 15 通过，12 次模型协议请求有原请求/租约来源；原 UNKNOWN 与新成功尝试、同一 READY owner 和完整 ZIP 分别核对。52 个已记录的原 PID/出生身份在报告前和清理后均确认停止，完整现场 `.runtime/workspace-selection-7EmjFi/reports/`，日志 `.runtime/alpha13-workspaces-jar-attempt3.log`。收尾发现每次身份核验会重复编译同一 Java 源文件，夹具改为启动时编译一次原 `ProcessRecoveryProbe.java`，保存原源码/类/脚本哈希并在 CI 归档原类；核验逻辑与停止边界不变，最终脚本随后复验。

2026-11-10 最终第四轮在相同 Jar/Web 上执行 15 / 15，通过后正常退出 0；最终脚本 SHA-256 `abee69dba171fe98fff4011d0567fc6dc28e69c805d5730fd8b597e02f625a03`，原脚本、三个 Java 夹具来源及一次编译的全部类均已保存。12 次真实本地模型协议请求、原 READY owner、旧 UNKNOWN 与新成功尝试、生产窄屏同源授权和完整 ZIP 分别核对；52 个原 PID/出生身份在报告前及清理后均独立确认停止，页面/模型错误为空。报告为 `docs/qa/workspace-selection-win32.json`，完整现场 `.runtime/workspace-selection-kYJdBk/reports/`，日志 `.runtime/alpha13-workspaces-jar-attempt4.log`。截图在切换 viewport 后等待字体和两帧绘制，最终桌面/窄屏图已实际检查；前两轮失败与第三轮原成功证据继续保留。

2026-11-10 准确提交 `33863a42c5e2e84a6072daa78d24428c77aed5c5` 的 [run 42741955481](https://github.com/lihe-he/multi-agent/actions/runs/42741955481) 三端整条 success。实际 evidence ZIP 均下载并核对大小与 GitHub SHA-256；核心每端 22 套 / 246 项，载体 2 / 2，Windows 245 通过、1 项 POSIX 权限跳过，macOS/Linux 243 通过、3 项平台条件跳过。实际 UI 62、工作区 15、协议 29、恢复 11、队列 13、独立客户端和包内核心启动分别核验，原 READY owner、旧 UNKNOWN 与新尝试及任务 ZIP 有完整来源；停止身份数量按原报告分别为 Windows 52、macOS 51、Linux 52。各端原 Jar/Web/config、夹具源码与一次编译类均与归档或准确提交核对，详细来源见[正式 CI 记录](../qa/alpha13-ci-workspaces-receipt.json)。

收取证据的首次请求 HTTP 415、第二次大归档网络失败、第三次 macOS 收到 37158912 字节后的 ABORT_ERR 与沿原下载地址重试 HTTP 403 均保留。HTTP 403 的具体原因未被单独证实；改为每个 2 MiB 分段和重试重新从 GitHub 获取下载地址后，macOS/Linux 完整收取通过，Windows 使用前次已核验原字节并再次核对摘要。失败文件、原脚本和分段字节进度保留在正式记录所列目录。发行包 artifact 仅保存 GitHub 元数据，未下载核验完整发行归档。
