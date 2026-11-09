# 持久派发队列研究

研究日期：2026-11-08；结果更新：2026-11-09。研究基线为 alpha.11 提交 `3471160c0e9e6cfd86fc849f29a1a172b2c162ce`，对应计划书 9.1、9.5、9.6 与 F03/F12。alpha.12 已实现本机根持久派发，协议见[根任务派发](../task-dispatch-contract.md)。本页保留原缺口、固定开源来源、实现取舍和实际失败记录。

## 固定开源依据

来源为 [db-scheduler](https://github.com/kagkarlsson/db-scheduler)，Apache-2.0，固定提交 `ff5e13176a1849d153339664216a616bbe515063`。缓存位于 `.runtime/research-dispatch-queue-2026-11-08/`；下列 GitHub contents blob 均与本机 `git hash-object` 一致。

| 来源 | 已核对的事实 | 本项目采用的边界 |
| --- | --- | --- |
| [JdbcTaskRepository](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/db-scheduler/src/main/java/com/github/kagkarlsson/scheduler/jdbc/JdbcTaskRepository.java)，blob `82f1b13f22952d6abc49bc50b1978414399780c6` | 领取同时限制原任务身份、未领取标志与版本；零行更新表示原记录已不能领取。删除未领取记录也检查 picked=false | 保存稳定动作编号；预检和原子领取同受 SQLite owner 约束；取消未领取与停止已领取分别处理 |
| [TransactionManager](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/db-scheduler/src/main/java/com/github/kagkarlsson/jdbc/TransactionManager.java)，blob `32cbd48ac051c5bd65ec550f663fc4d7a7f9787d` | 单连接事务保存多个变化，异常回滚；拒绝嵌套事务并处理提交失败 | 复用现有 ExecutionStore 事务边界，原任务状态、执行尝试和待派发动作一次保存，失败时零执行 |
| [Scheduler](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/db-scheduler/src/main/java/com/github/kagkarlsson/scheduler/Scheduler.java)，blob `51e4ec924942fd964f7b91adb8deccefeeec8e29` | 数据库轮询、执行线程和心跳分别工作；失联执行交给任务自己的 deadExecutionHandler | 派发器读取持久动作；模型容量和 Worker 原租约继续分别计数。已领取后失联保留 UNKNOWN，原进程与外部效果核对后才允许新尝试 |
| [JdbcTaskRepositoryTest](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/db-scheduler/src/test/java/com/github/kagkarlsson/scheduler/JdbcTaskRepositoryTest.java)，blob `c0d1bdfab8125dc097b221fcc9defed47971da15`；[TransactionManagerTest](https://github.com/kagkarlsson/db-scheduler/blob/ff5e13176a1849d153339664216a616bbe515063/db-scheduler/src/test/java/com/github/kagkarlsson/jdbc/TransactionManagerTest.java)，blob `c7ea1cb8d1708a9a1c07ed15505af9d504463f07` | 测试覆盖领取后不再属于待派发查询、旧版本领取失败、事务提交与回滚 | 使用本项目真实 SQLite 事务、并发领取、重开目录和取消竞争验证相应边界 |

只参考队列、领取和事务设计，不复制上游代码或增加运行依赖。上游的数据库和失联处理能力按自身适配器定义；本项目继续使用单 SQLite owner，其他数据库及远程 Worker 单独实施。

## alpha.11 原缺口

alpha.11 的 `WorkbenchEngine.launch` 将任务保存为 QUEUED 后直接向内存 ExecutorService 提交 Runnable。Control 先进入内存，数据库中尚无独立根执行尝试或可查询的待派发动作。启动恢复把 QUEUED 与 RUNNING 等状态统一保留 UNKNOWN；仅凭旧 QUEUED 文字不能证明动作从未领取。

Worker 的逐操作租约、AgentRun、模型请求与 MCP 动作已有持久来源，原进程核验也已完成本机真实恢复。alpha.12 在这些门禁之前补根任务的持久派发，继续保存原未知事实与累计预算。旧版本缺少动作编号的任务保持历史恢复语义。

## 本轮实现边界

保存 TaskExecution 根执行尝试与 DispatchAction 待派发动作。计划确认或显式恢复时，任务状态、尝试编号和唯一动作在同一事务内保存；领取另存原核心会话、时间和 dispatchCount=1。派发器在领取前重新检查原任务、授权版本、未知效果和 alpha.11 原进程门禁。

PENDING 表示从未领取的动作。它可跨核心重开保留原编号，门禁通过后由新核心领取一次。CLAIMED 后缺失结果的动作与尝试保留 UNKNOWN，旧请求和预算不重放；后续人工核对保存依据，新的显式恢复建立新尝试。

用户暂停或取消未领取动作时，撤回队列、结束未开始尝试与任务状态共用事务。已领取动作继续沿原 Control、实际 Worker、后代和 MCP 传播停止；原结果与实际停止分别保存。正常关闭停止派发器，未领取动作保留，实际运行继续按既有停止边界收尾。

根执行尝试保存自身结束状态及当时任务状态。等待人工验收与业务成功分别记录，原结果未知也在核对历史中保留。查询使用稳定分页，交付 ZIP 保存完整动作和尝试；AgentRun、操作、Worker 租约、模型请求及 MCP 来源关联实际根尝试。

首轮范围为本机根任务队列。动态副任务仍沿已验证的团队和逐操作租约执行；远程拉取、完整角色/写入租约、任意 workflow 和定时调度继续实施。使用 schema 6，公开 API 与私有 Worker 帧协议保持 1；仅支持 schema 5 的旧核心拒绝打开新库。

## 验证安排

1. 在动作或尝试写入处注入真实 SQLite 失败，核对任务、确认状态和事件全部回滚，实际 Worker 零执行。
2. 多个领取者竞争同一原动作，只允许一次领取；原编号、根预算与授权版本保持一致。
3. 保存未领取动作后重开同一目录，核对原编号保留、旧进程门禁继续生效，门禁通过后实际执行一次。
4. 保存领取后中断，重开保持动作、尝试、原请求及预算 UNKNOWN，不自动重放；人工核对与新尝试分别保留。
5. 在未领取取消与领取之间制造竞争，核对取消获胜时零执行，领取获胜时实际停止和未知效果按原来源保存。
6. 检查存储故障、授权变更和根未知效果阻止新增领取，界面与完整导出保留真实等待及历史来源。
7. 运行现有两载体团队、完整协议、真实核心恢复、独立客户端和打包检查，记录准确 Jar、源码及三端提交结果。

## 复现、验证与失败保全

原 alpha.11 缺口由真实 SQLite、确认和独立 Worker 路径复现，执行器使用受控屏障，没有声称复现操作系统调度故障。任务 `e68fee52-2bba-441c-8460-07d9f82535f6` 保存 QUEUED 后重开成为 UNKNOWN；确认仍为真，操作/租约/运行/请求均为零，预算不变，原核心和 Worker 均独立确认退出。证据在 `.runtime/alpha11-queue-gap-20261108-091955/`；此前 NIO 初始化失败的 `.runtime/alpha11-queue-gap-20261108-091912/` 另行保留。

最新源码的干净 `mvn clean verify` 在 Windows 完成 20 套、212 项检查，211 通过、1 项 POSIX 权限条件跳过，0 失败/错误。所有报告均核对虚拟线程 parallelism/maxPoolSize 为 2，包含队列 20 项、根引擎 11 项与原团队 12 项；原容量和等待期限未放宽。最终 Jar SHA-256 为 `d8a3a837a377dd7cfe1f7b4f635c20dab73c569d3743d91ecfe77cd8a923060f`，产物及报告在 `.runtime/alpha12-final-core-evidence-212/`。

`npm run test:dispatch` 最终 Jar 验收 13 / 13：实际批准事务失败回滚、未领取暂停/取消、原编号跨重开等待旧进程核验、门禁通过后领取一次、实际模型 HTTP 在途核心退出、原 UNKNOWN/请求/预算保留、人工核对与新尝试、完整 ZIP、生产桌面/430px 界面及所有本次进程退出。原未领取尝试 `a79203fc-bd1a-4ea7-963e-fc74c60a77a1`、动作 `bc777638-4582-4577-aee2-14224f034f5c` 由新核心执行一次；另一任务的原已领取尝试 `84b7ecad-4b3c-45b7-9daa-d4ce5c00360c` 保持 UNKNOWN，新尝试 `d2593d2c-ab97-4466-aebe-0b24f0de2235` 完成，预算从已用 1 累计为 4。报告见[实际队列验收](../qa/dispatch-queue-win32.json)，完整现场在 `.runtime/dispatch-queue-dlI22Q/reports/`。

外部夹具编译只使用此 Jar 解出的 classes/lib，受控启动通过 PropertiesLauncher 加载此 Jar 的原生产类；重开执行使用普通 JarLauncher，Worker 也从此 Jar 启动。生产 Web 最后重建后的静态文件哈希与上述实际验收时完全相同，全量 UI 48 / 48 通过。原未知记录和核对历史分别保存，导出与验收等待根结束回执；并发导出任务变化另有回归。

失败不改写：首轮 UI 五项停在错误的任务按钮精确定位，修正夹具后通过；首次完整核心在子请求到达前的原 15 秒等待失败，相同期限定向与最后完整回归均未复现，新增失败快照保留派发/线程/进程事实，不能据此声称已找到该次超时原因。Jar 夹具前三次分别因重复目标、缺少打开连接设置和误用不存在的恢复字段失败，均未放宽生产去重、连接或旧进程门禁；失败/清理记录在 `.runtime/dispatch-queue-ca7J7T/`、`dispatch-queue-pdr7CX/`、`dispatch-queue-0pbJuL/`，最后一轮才计为通过。

本轮实际进程恢复、完整协议、独立客户端、发行物与准确提交的三端结果见[实施记录](../implementation-status.md)。这些检查的覆盖范围不替代完整 F01–F21、长期负载、远程 Worker 或首版里程碑验收。
