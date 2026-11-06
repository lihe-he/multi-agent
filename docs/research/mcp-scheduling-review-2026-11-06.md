# MCP 等待、去重与根任务边界审查

日期：2026-11-06。版本：0.2.0-alpha.2。计划依据：计划书 7.4、8、9.3、9.5 节及 T08–T12、T25–T26；仅验证下述局部行为，不代替这些场景的完整验收。

## 开源来源

本轮通过 GitHub Contents API 实际读取官方仓库 README，并记录文件 blob SHA；这里的 SHA 是文件对象，不是发布版本或提交号。材料只提供架构参照，未复制源码或新增依赖。

| 来源 | 文件 blob SHA | 本轮使用范围 |
| --- | --- | --- |
| [LangGraph README](https://github.com/langchain-ai/langgraph/blob/main/README.md) | `97c31e9cb4d8fe56be8d768ce3eb5e22400e897e` | README 将持久执行和人工介入分别列为基础能力；本项目继续保留 UNKNOWN 与用户核对记录，不把恢复操作等同于重发副作用 |
| [MCP Java SDK README](https://github.com/modelcontextprotocol/java-sdk/blob/main/README.md) | `575b03b86ca5af0b48df272de2690e4b1c04f0b5` | README 分开给出客户端、服务端、Spring 集成与协议一致性检查入口；协议边界和本项目任务账本继续分别验证 |

本地缓存为 `.runtime/research-2026-11-06/langgraph-alpha2-readme.md` 和 `java-sdk-alpha2-readme.md`。文档页面搜索未返回可引用正文，旧 LangGraph 文档源路径返回 404；这些失败结果不作为设计证据。上述项目本身的锁、取消或副作用行为未经本轮实测，不宣称本轮实现来自其具体算法。

## 复现与修复

1. 固定动作在 Java `synchronized` 服务锁上等待，不能检查根取消；另一个操作未确认时还会先在锁外拒绝等待。新增三条并发回归在旧实现均失败。现在使用公平 ReentrantLock 的限时申请，每 100 ms 检查取消，并在取得锁后重新核验账本。排队取消不产生该任务的 MCP 记录或预算消耗，且不终止持锁任务。
2. 固定动作的旧去重查询位于服务锁外。现在移入锁内，前一请求收到回执后，等待者返回该回执；外部调用数、账本数及预算消耗均为一次。前一调用响应丢失时，排队的新动作被 409 阻止，预算保持未使用。
3. 引擎此前在整个模型阶段持有模型、路由和平台进程资源，包括 MCP 等待。现在进入 MCP 前释放这些资源，下次模型调用前重新申请；HTTP 夹具在业务调用时观测到 model=0、route=0、process=0、tool=1。后续模型调用重新观测到 model=1，阶段结束后资源归零。该测试不能证明动态父副 Agent 互锁已解决。
4. 运行器此前把 REJECTED 当成普通工具输出继续请求模型，最终可能耗尽轮次。新增回归在旧实现得到 FAILED 而非 WAITING_INPUT。现在立即停止回路，根任务保存原因并进入 WAITING_INPUT；UNKNOWN 与 CANCELLED 同样不发下一次模型请求。
5. 集成脚本与构建选择的 Java 不一致，在本机分别使用 Java 17 和 Java 21。现共用 Java 版本探测与选择入口，支持 WORKBENCH_JAVA_HOME、项目内 JDK 和 JAVA_HOME；仅采用版本不低于 21 且探测正常的候选。独立核心启动失败时输出诊断日志。

## 验证和未覆盖范围

- McpIntegrationTest：真实 HTTP 端点、可控调用阻塞、并发回执、等待取消、排队后 UNKNOWN 门禁；既有 stdio、定义变化和项目权限回归继续执行。
- WorkbenchEngineTest：创建任务至真实 MCP 服务，覆盖确认与响应丢失；根 UNKNOWN 拒绝恢复/验收，核对并重建引擎后恢复不重发；模型运行器为 mock。
- RuntimeIntegrationTest：真实本机模型 HTTP 工具回路在 UNKNOWN、CANCELLED、REJECTED 后只发出一次请求。
- 完整命令结果见实施记录与本轮 Surefire/Windows 协议报告。远端 CI 结果必须按实际提交另行检查。

尚待完成：逐持久化边界的进程崩溃故障注入；真实 stdio 在途取消及进程存活确认；服务定义/授权撤回与等待者取消的竞争；完整动态委派与原生 CLI MCP 注入；完整备份恢复及三端长期运行。没有以本轮绿色回归覆盖这些缺口。
