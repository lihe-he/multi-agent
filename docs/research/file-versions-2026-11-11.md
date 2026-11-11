# 已读文件版本与完整操作历史研究

日期：2026-11-11。对应计划书 9.4–9.6、18.1 与 F03/F12/F16。实现范围为本机 API 文件工具、原请求关联、完整历史查询及导出；协议见[文件版本约定](../file-version-contract.md)，最终验证见[实施记录](../implementation-status.md)。

## 固定开源来源

先核对以下固定提交的许可证与实际文件，再实现本项目约定。已获取源码位于 `.runtime/read-version-sources-2026-11-11/`；[来源索引](./file-versions-sources-2026-11-11.json)保存提交、实际 blob、SHA-256 和许可证摘要。本轮没有复制上游实现。

| 项目 | 固定提交与许可证 | 已核对机制及本项目取舍 |
| --- | --- | --- |
| [OpenCode](https://github.com/anomalyco/opencode/tree/59ed43f0b8da891f03a7333d540a7b0c428049ea) | `59ed43f0b8da891f03a7333d540a7b0c428049ea`，MIT | 当前实现路径为 `packages/core/src/`。读取、edit/write 与 `FileMutation.withLock` 使用原文匹配、文件修改锁和整文件当前内容预览/审批。本项目补充执行/路径绑定的完整读取回执与持久来源；没有把未核实的 mtime 机制归于此提交。 |
| [Aider](https://github.com/Aider-AI/aider/tree/5dc9490bb35f9729ef2c95d00a19ccd30c26339c) | `5dc9490bb35f9729ef2c95d00a19ccd30c26339c`，Apache-2.0 | base/editblock coder 的原文 search/replace 与 dry-run 提供修改前核对思路。本项目整文件工具需要同时比较摘要、身份和元数据，不仅匹配文本片段。 |
| [OpenHands SDK](https://github.com/OpenHands/software-agent-sdk/tree/5ff99f1b6ca28f7d16870b69f57a31351e59bcd0) | `5ff99f1b6ca28f7d16870b69f57a31351e59bcd0`，MIT | 编辑器/encoding 对原文歧义拒绝、原子替换、权限及换行保留提供边界参考。本项目不自动改写无效 UTF-8，二进制检查仅授权删除。 |

初始公开请求 HTTP 403 和旧路径失败均保留。后续从 Git 凭据助手在内存取得现有授权后读取固定 blob，凭据未写入报告。OpenCode 正确路径取证为 `opencode-source-attempt4/manifest.json`；Aider 与 OpenHands 为 `authenticated-attempt2/` 下各项目 manifest。

Windows 8.3/junction 通过固定 Temurin 21.0.13+9 实际 Java 源码入口核对。`C:\Users\ZENGQI~1` 的 NOFOLLOW real path 展开为长目录名；专用 junction 的 NOFOLLOW 保留链接且 `isOther=true`，默认 real path 指向目标。原探针、观察和固定 JDK `WindowsPath.java` / `WindowsLinkSupport.java` 在 `.runtime/alpha15-path-alias-probe/`。数据根可使用合法别名，日志组件中的外国链接仍拒绝。

## 设计与自审

1. 完整 UTF-8 读取后签发不可预测回执，比较 SHA-256、原生文件身份、大小、mtime、可执行位/POSIX 权限。同大小且恢复 mtime 的外部改动也必须拒绝旧覆盖/删除。
2. `inspect_file` 有界读取摘要，不能授权现有文本覆盖；成功写入可签发新文本回执。新建使用临时文件硬链接占位，不能覆盖刚出现的目标。外部写入最后比较至 rename 的窗口明确保留。
3. 已产生文件效果后的最终确认/租约收尾错误保留 UNKNOWN。回执元数据在租约关闭前收集；`toolReturned` 后的 `IllegalArgumentException` 不能把实际效果改成 REJECTED。
4. 文件历史读取不持有全局 store monitor。使用原 entities rowid 的 long 游标与原工具顺序；关联原成功请求及 AgentRun 工作区，来源字段取自原请求。传入 `source` 清除，旧日志保持未验证。
5. 所有 AgentRun 日志完整收集，缺失目录记录为事实。单记录及累计字节、记录数量有界；超限报错，ZIP 不按 100 条页面截断。自由文本及扩展键先递归脱敏，再编码 JSON；固定协议键和格式有效的枚举、UUID、摘要、回执、时间及原生身份保留，以免短凭据破坏状态、来源和游标。无效版本字段仍按文本脱敏，格式核对不等于日志签名。
6. 页面顺序轮询、取消晚响应，核对实际响应形状；切任务清除原请求焦点。长路径和日志正文用 React 纯文本渲染；请求关联不是日志内容的独立签名。

## 已执行结果与原失败

文件版本和历史定向第六轮共 27 项：26 通过，1 项为实际 Windows POSIX 条件跳过。`.runtime/alpha15-file-history-targeted-success6/` 保存原编译类、源码、报告和命令。13 项 FileVersionRuntimeTest 包括目标消失、无效 UTF-8 及已写入后的租约收尾拒绝；FileToolHistoryTest 已增至 13 项，覆盖 long 游标/重开、实际短路径、完整 125 条根/子导出、原请求、缺失目录、外来链接、坏记录与引号/反斜杠脱敏。第六轮之前的缺少 import、检查异常签名和短路径失败分别保留，未改成成功。

随后原团队委派/采纳与父修改冲突两项、32 MiB 累计日志超限一项定向 3 / 3 通过；原预算和期限保留，证据在 `.runtime/alpha15-team-journal-boundaries-success1/`。

首轮 Web 生产构建成功，新界面定向 10 / 10 通过，1440px 与 430px 截图已目视。原测试源和产物按执行前摘要还原并保存在 `.runtime/alpha15-ui-targeted-evidence1/`；后来加入切任务焦点断言，须由后续完整 Web 构建/UI重新验证。

首轮 clean verify 的 ProcessWorkspaceRuntimeTest 出现三处旧夹具失败：未完整读取即覆盖已有 `app.txt`，执行按新约定进入 WAITING_INPUT；版本与历史套件均已通过。完整构建结束后保存原失败，再补真实读取回执，原预算、超时、SQLite-before-HTTP 和独立 Worker 断言保留。后续完整构建、真实最终 Jar、发行物及准确提交三端 CI 的结果以实施记录和正式 QA 为准。

首轮完整核心为28套/311项、3失败/4条件跳过，原源码/类/报告在 `.runtime/alpha15-core-clean-failure1/`。三个旧Worker路径补真实 `read_file` 后定向3 / 3通过，原预算上限未增加；每条请求仍先保存SQLite，再发送实际HTTP，原文件/index/config与Worker身份继续核对。

第二轮全量构建在自审发现顶层自定义日志键脱敏遗漏后主动结束，部分报告保存在 `.runtime/alpha15-core-clean-interrupted2/`，没有把它算作完整通过。含引号/反斜杠凭据的原日志顶层键真实复现1项失败，证据在 `.runtime/alpha15-root-key-redaction-failure1/`；补齐顶层键后，13项历史与13项版本定向25通过/1实际Windows POSIX跳过，原日志字节及mtime未改变，证据在 `.runtime/alpha15-root-key-redaction-success1/`。

首轮全量Web73通过/6失败：五项原桌面测试观察到新增详情标签撑宽，另一个新跨任务夹具错误要求任务按钮名称恰等于标题。原源/产物/trace在 `.runtime/alpha15-ui-full-failure1/`；修正详情导航横向滚动和任务定位后，第二轮生产构建与79 / 79全量UI通过，切任务请求焦点、迟到轮询和长路径继续核对。最终两张文件截图已目视，20张无关历史截图恢复原字节，摘要和源码在 `.runtime/alpha15-ui-final-evidence/`。首个失败归档辅助命令因原list reporter没有report.json而退出，已复制的原源/日志/trace按执行前摘要核对后保留，后续正式全量使用list与JSON reporter。

第三轮干净核心构建实际结束为28套/311项、1失败/4条件跳过，完整报告与源码保留于 `.runtime/alpha15-core-clean-failure3/`。原XML栈指向 `realGrantedLockWithMissingSqliteAckRequiresReconciliationBeforeANewRoot(CLONE)` 的恢复后 `settled()`；失败转储的原第二次根已为 `SUCCEEDED`，任务为 `AWAITING_ACCEPTANCE`、使用3次模型请求。夹具在两次独立存储读取之间发生成功收尾，观察到 `endedAt` 后没有再次核对原条件；现补二次条件核对，仍对真正意外的结束报错，原90秒期限和业务断言保留。原失败不会改为成功，原临时目录另保存补充事实。

后续自审发现全字符串替换也会误改固定协议字段。短凭据 `a`、`read`、`state`、`CONFIRMED`、`0` 的5项回归在原代码上全部真实失败，原报告、类和源码在 `.runtime/alpha15-short-credential-failure1/`。现仅保留已识别字段中格式有效的协议事实；任意扩展、路径、错误和无效版本仍递归脱敏。新测试同时核对原UUID、成功请求、摘要、版本、状态筛选和游标，以及原日志字节不变；含引号/反斜杠回归继续要求无效身份、时间、摘要和权限中的凭据被脱敏。修订后的定向与完整构建结果需以实际执行为准。

修订后的文件历史18项、文件版本13项和原恢复成功边界2项共33项定向完成：32通过、1实际Windows POSIX条件跳过，证据在 `.runtime/alpha15-short-credential-and-boundary-success1/`。原失败临时目录的95份文件另存于第三轮归档，原XML栈、成功根转储和失败本身保持不变。第四轮干净核心构建继续验证最终源码。

真实Jar验收入口在首次执行前补强脱敏断言：JSON编码后的字符串搜索可能漏掉带引号/反斜杠的原凭据，现递归核对解码后的实际字符串与键，并逐条确认旧日志顶层键为 `[REDACTED]`。独立证据检查器采用相同的解码边界；检查数量、模型请求、分页/ZIP和原截止条件保留。

第四轮在自审发现审查副本共用 `reviews/operations` 后受控中断。Maven报告293项、0断言失败/4条件跳过后因主动停止退出1，不能算完整通过；原源码/类/部分报告和命令在 `.runtime/alpha15-core-clean-interrupted4/`。原Maven、Surefire与已观察Worker共5个原PID/出生身份确认停止。

共享审查清单的两项新回归在原代码真实复现1失败/1错误：一次查询混入其他任务记录，重复实际Git审查副本读取相同编号而报错。`.runtime/alpha15-shared-review-failure1/` 保存原源码、报告和两份实际临时目录的117份文件。现每个审查副本改为独立父目录下的 `work`；旧共享清单无法可靠归属，查询/完整ZIP返回409并保留原文件。修订后20项历史及27项原运行器集成定向共47项：46通过、1Windows POSIX条件跳过，原预算/期限保留，证据在 `.runtime/alpha15-review-isolation-success1/`。

最终Jar入口进一步要求CLONE/WORKTREE的独立审查副本真实 `read_file`，并在另一任务审查后核对原历史。每个成功任务由11增为12次实际模型请求、8增为9条原请求关联回执；仍加入121条明确旧日志，完整分页/ZIP为130条，总预期模型请求28次。此入口此前尚未执行，最终结果以实际验收为准。第五轮干净核心构建验证全部最终源码。

第五轮干净核心构建实际成功：28套/318项、314通过/4条件跳过、0失败/错误，最终Jar SHA-256为 `0f06b0d7e22a2dbd3ec97cfb07d0788dea11ae9c44746aba650defd3114d82e9`。原源码、全部编译类、报告和Jar在 `.runtime/alpha15-final-core-evidence/`；15份Java的80条原注释保留、全部Jar class对应编译输出。

首次普通Jar入口因夹具请求不存在的 `/tasks/{id}/process-observations` 而退出1，原现场、脚本和停止记录在 `.runtime/file-versions-xX5eIU/reports/`，产物使用回执保留为failed。修正夹具使用现有任务进程会话的带截断事实的界面观察，再从所有原ZIP收集完整进程观察；原预算、超时和产品校验保留。第二次普通最终Jar验收9项/28次原模型请求通过，两份各130条完整历史/ZIP、实际独立审查与跨任务隔离、未解决冲突等待均核对，498个原PID/出生身份独立确认停止。原API错误没有改记为通过，最终Jar/Web/config前后摘要一致。

最终clean verify 28套/318项、314通过/4条件跳过、0失败/错误，Jar 39243679字节、SHA-256 `0f06b0d7e22a2dbd3ec97cfb07d0788dea11ae9c44746aba650defd3114d82e9`，完整源码、类和报告在 `.runtime/alpha15-final-core-evidence/`。普通最终Jar文件版本9项/28次原模型请求、原生命周期、十条顺序产物核对、打包启动和包内JRE通过，全部原进程停止按各自原PID/出生核对。Web 79 / 79通过并目视最终截图，15份Java的80条原注释保留。正式本机结果见[alpha.15验证](../qa/alpha15-validation-win32.json)；准确提交的三端CI结果另行记录，完整目标保持未完成。
