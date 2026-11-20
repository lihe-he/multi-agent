# Workflow 路由确认与声明输入交接研究

日期：2026-11-20。本轮实现最初在隔离导出目录 `.runtime/workflow-readiness-6e2e7bda` 进行，现已并入主工作区；本文记录已阅读的本地实现、固定开源来源及本轮设计范围。文档编写没有执行模型请求、原生 CLI、Maven 或浏览器验收，不能据此宣布构建或回归通过。原工作区的 Team/Worker 恢复修复、既有 CI 失败及其诊断属于独立事项，本轮不推断其根因。

## 计划依据与当前增量

计划书第 6.1–6.3 节要求固定上下文、按任务提供资料、保留交接来源；第 7.1 节要求先检查硬锁定、工具及权限、资料发送范围和预算；M2 同时要求三运行器混合接入与成果交接。原工作区已有的 `docs/research/native-mixed-stages-2026-11-19.md` 说明了 CLI 配置、文件范围、认证及领取限定 MCP 尚未闭合的边界；该前置研究不在本次隔离导出副本中。

本轮先交付现有 API Workflow 可直接使用的两项改进：在创建或替换任务流程前，展示实际固定路由、版本、节点声明输入及阻塞原因；最终选择按路由版本重新核对。与此同时，让实际模型提示与声明的根输入交接一致。这是混合阶段的准备工作和现有 API 流程的资料控制改进，不等于已经支持 Codex/Claude 阶段或完成 M2。

读取到的旧实现中，`WorkflowDsl` 已校验逻辑输入和祖先关系，`WorkflowCoordinator.handoffs` 已建立命名交接；节点提示仍额外拼接完整根目标、根约定和项目上下文。`WorkbenchCatalog.createTask` 会把有效项目记忆及仓库规则固定到任务，因此只检查 `attempt.inputs` 不能证明实际 HTTP 提示没有携带未声明的根记忆。原选择还以额外路由身份列表确认发送，无法表达用户确认的是哪个配置版本。

## 固定开源证据

下表列出 2026-11-20 通过公开原始文件端点实际读取的完整字节大小和 SHA-256；链接固定到提交及被使用的行。摘要针对表中完整文件，不针对网页 HTML、摘录或工作区产物。它们是设计参考，不作为该工作台兼容性、授权或回归通过的证据，未增加这些项目的运行时依赖。

| 固定来源 | 已观察事实与本轮取舍 | 完整文件字节与 SHA-256 |
| --- | --- | --- |
| Argo Workflows [`docs/workflow-inputs.md`](https://github.com/argoproj/argo-workflows/blob/4d269b9a55e37c9eafc88289d6d284df8f9a7489/docs/workflow-inputs.md#L9-L75)，提交 `4d269b9a55e37c9eafc88289d6d284df8f9a7489` | 明确区分 Workflow 参数与模板声明输入；[祖先输出映射](https://github.com/argoproj/argo-workflows/blob/4d269b9a55e37c9eafc88289d6d284df8f9a7489/docs/workflow-inputs.md#L95-L118)按参数值或产物来源交给特定节点。借鉴显式资料投影，不引入 Kubernetes 或扩张现有 DSL。 | 3,832；`2a7e01228badd2ea812c664ed26288ba5f3fd346446fd04a774075fecfcf691c` |
| ACP [`session-setup.mdx`](https://github.com/agentclientprotocol/agent-client-protocol/blob/d98a032006928dffc465f930638f83815b91ebe3/docs/protocol/v1/session-setup.mdx#L438-L459)，提交 `d98a032006928dffc465f930638f83815b91ebe3` | 目录声明与实际 shell 沙箱强制分开；[HTTP/SSE 能力与凭据处理](https://github.com/agentclientprotocol/agent-client-protocol/blob/d98a032006928dffc465f930638f83815b91ebe3/docs/protocol/v1/session-setup.mdx#L608-L641)以 `MUST` 要求先核验传输能力，并以 `SHOULD NOT` 建议避免将 `env`/`headers` 凭据值写入会话更新、错误、模型上下文和命令行。借鉴能力显式拒绝和资料边界说明，不宣称名义输入或只读标签提供 OS 隔离。 | 21,639；`6eec268643ce3ae356cc9db7576701b9550ddd9642452a1e0e332a9fbe342081` |
| ACP [`prompt-turn.mdx`](https://github.com/agentclientprotocol/agent-client-protocol/blob/d98a032006928dffc465f930638f83815b91ebe3/docs/protocol/v1/prompt-turn.mdx#L547-L580)，同上提交 | 发送取消与收到原 prompt 的取消终态是不同边界，取消后仍可能收到更新。现有原请求、原 Worker 停止及 `UNKNOWN` 收尾不因本轮 UI 检查而简化。 | 23,354；`8210742fc7899f81aab01f932f67e38a4366d9e802a888584cb95802e2b821c5` |
| MCP [`2025-11-25/basic/authorization.mdx`](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/8a18b4b3292b9f9b3aee73f6325f9f62f19a89f9/docs/specification/2025-11-25/basic/authorization.mdx#L454-L483)，提交 `8a18b4b3292b9f9b3aee73f6325f9f62f19a89f9` | 每个 HTTP 请求都需授权，访问令牌须校验目标受众，不能把其他资源令牌当作本资源授权。此处作为未来领取限定桥接参考；本轮不声称现有项目 token 已绑定 task/run/Worker，也不把工作台管理令牌交给模型。 | 41,363；`8361d0dd9d7072d9d93cace82afe4d1d562d0a6e456f48f2bcf0ef3a922907c6` |
| codex-acp [`docs/mcp-over-acp.md`](https://github.com/agentclientprotocol/codex-acp/blob/ad09e0ead18511a65a0761cac832aa3087219b10/docs/mcp-over-acp.md#L3-L37)，提交 `ad09e0ead18511a65a0761cac832aa3087219b10` | 先协商桥接能力再注册会话服务器；客户端连接生命周期不同于单会话生命周期，文档还要求固定二进制支持相应 MCP 版本。说明“有桥接”不自动等于该工作台的阶段领取授权；不直接接入这个适配器。 | 2,479；`fb609a1f4ec5198840a483c5231cff985fb3d2d19f0d8379aa4d8b2371932266` |

## 实施范围和保留边界

### 路由检查与实际准入

`WorkflowCatalog.readiness` 与 `snapshot` 共用解析器。一次解析为同一根接收者使用任务原根快照，为同一额外身份缓存一次读取的路由对象；不在同一选择中逐节点悄悄切换版本。预览仅返回身份、端点、版本、声明输入和拒绝原因，不返回任务资料，不写授权、不运行探针或模型。API 以外的节点仍明确拒绝；`probe.verified=false` 不能作为解除限制的依据。

最终选择使用 `rootRouteVersion` 和 `{routeId: positiveIntegerVersion}` 形式的 `allowedRouteVersions`，精确匹配额外接收者，另固定流程摘要。版本变化、未知/过期路由或违反锁定会停止准入；旧 ID 列表本身不能批准额外发送。没有额外接收者的旧同根选择继续兼容。现有任务的根接收者仍是原冻结版本，当前同身份配置必须仍启用；修改配置并不把旧任务静默转向新地址。新任务创建和路由修改使用 `WorkbenchCatalog` 的既有互斥；探针返回时重新核验配置版本，避免旧结果覆盖新配置。

检查并非完整运行探测：端点登录和模型/tool-call 协议、最终权限交集、执行授权、预算、独立现场准备和真实进程停止仍按各自既有门禁判断。就绪结果也不是缓存授权；最终选择和运行另行复核。客户端改变项目、根路由、锁定、额外路由版本或流程选择后，需废弃原确认并重新检查。

### 自动交接、仓库规则与可访问资料

`WorkflowCoordinator` 不再把完整根目标、约定和项目记忆作为额外字段附加到所有节点提示，只通过声明交接提供。每个节点仍有自己的固定目标、职责、实际权限、验收和终止条件；根工具约束、最终必要验证和独立审查保持。

适用仓库规则是固定任务上下文中平台专用的 `id=project-rules`、`kind=RULE`、`source=AGENTS.md` 条目。它的 `{source, version, content}` 单独随节点提供，不发送宿主绝对路径；显式 `context` 不再重复规则。普通 RULE 记忆即使 source 写为 `AGENTS.md` 也不是强制注入条目。额外路由界面需披露共同的节点信息和仓库规则，不能只展示可选 `inputs` 后暗示它们是全部提示。

这项投影约束平台主动组装的输入，不承诺阻止一切资料传播。已授权文件和检索、团队消息、副 Agent、祖先实际输出及其原文件准备仍遵守各自权限和来源规则；这些路径的内容可能与未主动附加的记忆重合。任务固定规则也不能证明模型必然遵守文字约定，实际权限仍由后端强制。未新增或静默删去文件范围、团队或固定 MCP 权限。

### 内置版本及旧回执

保留生成历史 v1 的 `WorkflowTemplates.dsl`，只在新的 v2 内为实现节点补充明确的 `goal`、`contract`。初始化只升级仍启用、未编辑、历史来源和摘要都符合内置模板的 v1；LEAD/PIPELINE 的引用职责只有 research/developer，ROUNDTABLE 另有 reviewer，不能统一要求三者存在于原快照。新库也保留 v1 后发布 v2；用户编辑或停用的当前定义不自动覆盖。任务不随模板当前指针自动升级。

新输入 ACK、尝试及节点结果证据记录 `promptInputProjectionVersion=1`，输入准备保留规则来源。现有 proof digest 已包含输入准备操作、原请求和结果证据，不改写旧证明公式。无标记的历史成功结果没有因此获得“新投影已经生效”的证明；按既有完整性规则复用时仍代表旧请求的实际资料，可能在输出中保留旧隐含上下文。新代码执行旧 DSL 仍按其声明提供输入；需要根资料的节点应发布新版本，而不是回填旧 DSL 或重放原请求。

## 验收要求与本机验证结果

以下用例列出本增量应覆盖的范围；逐项通过证据与实际跳过内容见[alpha.24 本机验收回执](../qa/alpha24-workflow-route-inputs-win32.json)。回执区分清洁构建、实际生产 UI、最终 Jar 和仍未完成的跨平台及原生能力验收。

本机 Windows `npm run verify:core` 于 2026-11-20 在 52 个测试套件运行 **633 项：623 通过、0 失败、0 错误、10 项条件跳过**，命令回执与 JUnit 原报告、源码清单保存在 `.runtime/alpha24-core-clean-28a3cb7df3894d27b90e771d6509b253/`。重新编译了 69 个生产与 53 个测试源文件；177 个生产 class 和 157 个测试 class 在测试结束后保持原字节，最终 Jar 内生产 class 与捕获字节完全一致，Jar SHA-256 为 `c5fc13433235ecce2ae620cbe00a01afc7d895d3251efcd6635aac2e3e97a9bd`。本机 alpha.24 生产 Web 构建成功，独立完整生产 UI **272/272**，零失败、跳过、flaky 或重试；实际最终 Jar 的本机协议集成 **33/33**，运行期源码与 Jar 摘要稳定。完整生产 UI/Jar 汇总和截图引用见提交版 QA 回执，原始明细与构建回执位于其中所指的本机 `.runtime` 目录。固定开源文件的字节与行号核验另存于本机 `.runtime/alpha24-oss-source-verification.json`，只证明来源定位，不替代产品验收。

上述为本机 Windows 证据；实现提交 `26b8dedfe4571aa3436f24848041ec3822bc47fd` 随后在 [Run 44665907542](https://github.com/lihe-he/multi-agent/actions/runs/44665907542) 首次运行三端成功。每端核心 633 项、0 失败/错误，Windows 条件跳过 8 项、macOS/Ubuntu 各 11 项；每端生产 UI 272/272、实际 Jar 协议 33/33，适用发行构建和包内 Java/Web 运行步骤均成功。逐平台门禁、原始日志 SHA 和 artifact 仅为 API 摘要元数据的限定见[CI 终态回执](../qa/alpha24-ci-final-26b8ded.json)。这些结果不证明外部供应商可用、原生 Codex/Claude CLI 兼容或完整 M0–M3 退出条件均已满足。

1. 路由检查无任务资料、凭据和写入；跨项目、停用职责、缺失/原生路由、硬锁定不满足分别拒绝。检查的路由身份和实际快照一致。
2. 额外身份列表不能替代版本批准；旧/缺失/多报的额外版本及过期根版本拒绝，同根旧选择保留兼容。配置变更后的 UI 旧确认及迟到响应失效，探针不回写新配置。
3. 实际独立 Worker 经两个不同 HTTP 路径派发：无 `context` 的节点没有自动注入项目记忆，显式输入节点取得对应原值和准确祖先来源；所有节点各有一次仓库规则，普通 RULE 记忆不冒充规则，强制规则没有宿主路径。
4. 原文件权限、祖先整合、根必要验证、独立审查、暂停恢复和 `UNKNOWN` 禁止重放保持。旧成功证明保留且不能补造投影标记。
5. 三种旧内置 v1 都仅发布一次 v2；完整旧记录、摘要及原任务选择保留；新库、重复初始化与 SQLite 重开幂等。停用、编辑或与历史模板不一致的定义不被自动覆盖。

原生阶段的真实二进制/认证、任务与运行及领取限定 MCP、配置隔离、细粒度读取保证、取消故障矩阵、远程节点和三端完整发行仍未由本增量完成。
