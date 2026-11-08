# 执行节点与本机 Worker 来源、自审

研究日期：2026-11-07；实现审查续于 2026-11-08。基线 `75cf450a5b259ed0f6347ca434aeccdc6cb78197`，按计划书 6.1/6.3、9.1–9.6 和当前架构审查 F03/F12/F14 继续建立节点、仓库绑定与持久归属。

## 固定开源源码

| 来源 | 实际观察 | 采用的设计 |
| --- | --- | --- |
| [Kubernetes client-go leader election](https://github.com/kubernetes/client-go/blob/494d62c5edeff3b84f2e6b3e757554c13bc3648f/tools/leaderelection/leaderelection.go) | 文件开头明确选举不保证 fencing；本地观察时间与远端记录时间分开；恢复只续约原 holder，释放前核验归属 | 稳定节点与新 Worker epoch 分开；旧租约失效立即封闭新增调用，不能把心跳失效当作进程停止或重放许可 |
| [Temporal Java ActivityExecutionContext](https://github.com/temporalio/sdk-java/blob/112769ca18aa413758afd6b002aee2390430d435/temporal-sdk/src/main/java/io/temporal/internal/activity/ActivityExecutionContextImpl.java) 与[HeartbeatContextImpl](https://github.com/temporalio/sdk-java/blob/112769ca18aa413758afd6b002aee2390430d435/temporal-sdk/src/main/java/io/temporal/internal/activity/HeartbeatContextImpl.java) | 活动心跳关联 taskToken 和 identity，独立调度器节流；取消/重置/暂停与回执错误各自处理，超时使用本地单调时钟 | 心跳独立于浏览器和模型槽；每次原操作领取随机凭据，回执核验原 Worker/epoch/摘要；授权与归属变化使迟到观测保留 UNKNOWN |
| [VS Code PtyService](https://github.com/microsoft/vscode/blob/17c503e7ef0c2575daa5338b706a767a24b10e7b/src/vs/platform/terminal/node/ptyService.ts) | persistentProcessId、PID 与 workspaceId 参与 attach；序列化/回放与重新创建进程分别处理；日志过滤环境变量 | Worker 来源与客户端显示分开，重连既有在途执行不新建请求；后续 PTY 必须区分 attach 与 revive，不能把输出回放当作进程还在运行 |

文件缓存于 `.runtime/research-worker-2026-11-07/`。实际 Git blob：client-go `83181a77f4ac8154b3d99a804a9c42a06d02d435`；Temporal 活动上下文 `20c3cba1868aafb6dca50a099a9352f2b9346f20`，心跳 `fba3ec29afff00a7f92ac7a7d459835b42c00dd3`；VS Code `0e5f4109c89ddfad2cf662dfe88a5b279439313c`。心跳文件与 GitHub contents 返回的 blob 已核对。没有复制上游代码或添加上游运行依赖。

## 实现与审查取舍

- 在原 Java/SQLite 模块抽出执行接口和本机执行者，实际部署声明 CORE_PROCESS；不把同进程记录包装成已支持远程节点。
- 节点、项目和仓库绑定使用独立稳定 ID；路径在绑定中保存，任务冻结配置版本。新绑定先核验实际 Git，版本冲突保留草稿。更改绑定不覆盖进行中任务的源目录。
- Worker 领取保存随机凭据摘要、唯一操作约束及实际目录，派发/租约/AgentRun 同事务；公开 API 和导出去除摘要。其他客户端不能伪造执行 PID 或领取成功。
- 证据、操作回执、租约结束共用事务；团队采纳的 integrations 同时保存。事务失败维持旧派发状态，原始输出不会以不可靠成功进入验收。
- 心跳可更新归属期限，期限过期不能再续旧租约；单调时钟同时限制无心跳窗口，系统时间回退不延长原归属。启动新代次与旧在途租约 UNKNOWN 共用事务；未核对效果同时封闭原编号和新编号。迟到原模型成功不能将副任务 UNKNOWN 改为成功或取消。
- 模型请求与固定 MCP 记录实际 Worker/租约来源；父复用保留原子来源。普通工具原有磁盘账本继续提供明细，独立进程迁移和完整工具原子账本仍需后续实现。
- 查询沿索引读取当前 Worker 与任务租约，页面分页使用稳定 rowid；客户端轮询不会跳回最新页。实际独立 Web 验收核对关闭会话/浏览器/附加桌面后仍为同一个副请求和租约。

## 验证和失败记录

`WorkerLeaseTest` 使用实际 SQLite/Git、可控时间和事务故障，核对一次领取、原回执归属、过期未知、迟到观测、新编号停止、证据/租约事务回滚、重启代次、错误目录、版本与项目边界。单调时间超期和系统时间回退分别执行，避免一个条件掩盖另一个；租约收尾保留原错误也有断言。`TeamExecutionTest` 追加真实 Git/HTTP 节点停用、子请求 UNKNOWN、绑定版本重新授权及草稿切换实际仓库/规则历史场景。

首轮编译因三处旧团队测试没有传入新的执行依赖而失败，测试改为明确提供记录专用执行替身；产品真实集成继续使用实际 Worker。首次全量核心 137 项中两项 MCP 停止回归使用整体 Catalog 替身，节点依赖为 null 导致 Worker 初始化失败，未进入相关停止断言。已改用实际节点 Catalog 并在 finally 中清理 Worker；MCP 原停止与容量断言保留，失败报告在 `.runtime/alpha9-regression-attempt1/`，不计为通过。

新增规则切换与时钟回归后的首轮 139 项中，备用仓库准备场景有一项 UNKNOWN。新克隆未显式沿用夹具的 `core.autocrlf=false`，现已在克隆及仓库配置中固定；修正后该实际执行场景与 Worker 九项定向回归合计 10 / 10 通过。第一次失败的原错误被租约通用收尾原因覆盖，不能从最终任务错误完整归因；已修复该来源保全问题。该次完整失败与线程快照保留于 `.runtime/alpha9-rule-clock-attempt1/` 及 `.runtime/alpha9-build-threads.txt`，最终结果另行记录，不以定向通过代替完整构建。

首个推送提交 `8c37fb0950fd6d6a9d99c1c40ec3ce0b5132604b` 的 [run 42435846414](https://github.com/lihe-he/multi-agent/actions/runs/42435846414) 已结束：Windows 成功，macOS/Ubuntu 核心回归失败。两端的节点撤回用例在根 UNKNOWN 后立即释放子 HTTP，子请求可以先收到真实成功回执；现改为先等待具体子请求的停止观测和租约收尾。模型请求状态保留已收到的真实回执，执行证据及副任务结果另按租约归属判断。Ubuntu 另暴露实际取消竞态：停止回执先于 STOP_REQUESTED 写入，随后 CANCELLING 覆盖 CANCELLED。停止请求/状态现同锁发布，收尾处理最后的请求，待停止不能进入交付；新增故意延迟请求写入的回归核对事件时间和零派发/零预算。失败日志及终态保留在 `.runtime/ci-alpha9-attempt1/`。

修订后定向 3 / 3，最后完整核心 140 项中 139 通过、1 项 Windows POSIX 跳过。修订 Jar 协议 28 / 28、打包程序及最终 Jar 同一性均通过。并行打包时独立用例未在旧固定十秒内进入副请求，清理后的现场已有后续副请求和关闭服务产生的错误，不能用于准确判断清理前原因；现按实际状态有界观察、异常状态提前失败并写清理前记录，单独重验成功。该次失败日志和现场保留在 `.runtime/alpha9-ci-fix-final-standalone.log` / `.runtime/standalone-lBraKu/`。最终三端结果只按后续修订提交核对。

浏览器 1440px/430px 验证真实字段展示、配置版本冲突/输入保全、草稿任务固定绑定、执行授权刷新、租约 UNKNOWN 原因/来源/历史游标/证据定位与纯文本处理。截图经过实际查看，最终完整构建、Jar、打包与三端提交 CI 证据以实施记录为准。

后续修订 `158fcda3868ae78c2640fc90f680c93dd287c483` 的 [run 42437567111](https://github.com/lihe-he/multi-agent/actions/runs/42437567111) 已结束：macOS/Ubuntu 成功，Windows 核心失败。第二父任务的基线操作在约 0.6 秒后保存 UNKNOWN，因此第二个子模型没有进入屏障；首个父任务的子模型等待随后超时。原失败缺少各根任务的命令输出和版本后值，不能据此认定资源死锁、版本变化或残留子进程中的某一种。Windows 日志与报告保存在 `.runtime/ci-alpha9-attempt2/`。同源本机单项及完整团队 11 / 11 均通过，32 次并发 PowerShell/Node 进程探针未复现；这些通过不能改写原 CI 失败。

补充审查发现，已有运行器只将未确认子进程信息写入事件日志，任务证据可能只有命令输出。现将原 PID/开始时间同时保存在 UNKNOWN 命令结果中（最多列出 50 个），输出截断后仍保留身份，并在团队失败诊断中保留每个根任务的状态及原证据。新增真实独立 Node 子进程场景分别检查父进程等待原来的子进程退出后成功，以及父进程成功退出但原来的子进程未结束时 UNKNOWN/原 PID/请求停止；不放宽原停止判定，不自动重试命令。

这组夹具最初仅设置 unref，在 Windows 随 Node 父进程一起关闭，不能证明仍有实际在途子进程。修订为 detached/windowsHide、原 PID 核对、有界退出及 finally 清理后，原停止边界通过；另加的原 PID 证据断言先在旧代码失败（`.runtime/alpha9-child-facts-before.log`），补全结果来源后两项定向通过（`.runtime/alpha9-child-facts-after.log`）。较早夹具失败保留在 `.runtime/alpha9-child-exit-before/` 及对应日志，不归因为产品停止故障。夹具的清理另核对进程开始时间不晚于原 PID 回执，拒绝清理后出生的同号进程。

最后完整核心回归 142 项中 141 通过、1 项 Windows POSIX 跳过（`.runtime/alpha9-process-facts-final-core.log`）；上述夹具清理保护后两项再验通过（`.runtime/alpha9-process-facts-final-child-regression.log`）。最终 Jar 协议 28 / 28、独立浏览器/附加桌面生命周期、Windows 打包运行与 Jar 同一性均通过；当前三份 Java 文件的 14 条已有注释原文保留，新 Javadoc 为标准多行。修订提交的三端 CI 仍须单独核对，不能用这些本机通过替代原 Windows 失败。

远程主机身份与配对、Worker 独立进程与持久队列、旧进程树跨重启核验、终端/PTY、任意 workflow/角色租约、完整备份、保留策略与长期验收仍继续实施。本轮基础不能替代 F01–F21 或 M0–M3 全部退出条件。
