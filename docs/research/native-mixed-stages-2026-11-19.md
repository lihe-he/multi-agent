# 原生 CLI 混合阶段的下一项需求与能力边界

日期：2026-11-19。本次依据计划、接口约定、当前源码和下列官方来源提出下一项实现建议；没有修改现有实现、运行模型任务或启动构建。本文不作为原生运行器兼容性验收，也不改变既有需求或验收条件。

## 1. 推荐增量及优先级

推荐下一项为：**让经能力核验的 Codex、Claude Code 作为 Workflow 中可确认完成的原生阶段，与 API 执行者交换真实版本化成果，并通过任务限定的 MCP 桥接取得平台上下文。** API 根任务继续负责编排、整合、必要验证和独立审查；不能确认挂起的原生 CLI 不承担动态父委派。

这是一项独立的产品增量，不等于整个 M2 完成。其最小完整交付应有真实产物、祖先输入、权限与预算门禁、取消及未知结果恢复；仅放开下拉选择、仅运行自制 CLI 或仅允许一个只读节点不足以证明混合执行完成。原生动态父委派、远程节点和其他 M2 缺口继续保留。

优先级依据：

- 计划书第 15.1 节要求首批接入 Codex、Claude Code、自研 Java Agent，并以接入探针验证实际能力；M2 明列三运行器、混合接入、成果交接、能力匹配和双向 MCP。
- 第 7.4 节明确允许在无法确认 CLI 挂起时使用可确认停止、保存和恢复的阶段。阶段式接入有直接计划依据，不能通过修改平台等待状态假装原进程已经释放容量。
- 原计划第 1.2 节不把远程执行集群列入第一版，但新增 F01–F21 架构审查的第八节第二步要求逐步启用远程节点，F19 也要求设备配对。远程能力仍在总需求中；这里仅建议当前先补齐已经存在执行适配、但无法参与协作的混合阶段。
- Workflow 故障矩阵仍需扩展，但不宜只把下一次实质增量改写为新增测试或文档。本项应把故障场景作为原生混合执行的交付条件。

相关基线：`docs/多Agent智能研发工作台计划书.md` 第 7.4、8、9、15.1、17、18 节；`docs/research/current-architecture-audit-2026-11-06.md` 第六、八节；`docs/workflow-contract.md`。

## 2. 当前实现与需要修改的入口

| 入口 | 当前事实 | 下一项需要解决的问题 |
| --- | --- | --- |
| `WorkbenchCatalog.saveAgentDefinition` | 约 103 行拒绝职责绑定非 API 路由 | 职责声明与执行能力分开；允许声明已核验的原生阶段，动态父能力仍单独校验 |
| `CollaborationPolicy.snapshot` | 根动态委派及子职责路由都限 API | 不应直接删除限制；未来允许原生子阶段时须保持 API 父等待释放和原生叶阶段边界 |
| `WorkflowCatalog.snapshot` | 约 136 行按 `engine == API` 拒绝原生节点 | 改为按阶段所需能力、最终权限交集、预算口径和根锁定核验；固定原路由与能力证据 |
| `WorkflowCoordinator.executeNode` | 每节点创建 `TeamCoordinator.Session`；调用 runtime 时范围参数为 `**` | 原生阶段必须另行核验完整 read/write 多层范围，不能因调用参数为 `**` 忽略实际节点权限 |
| `WorkflowLedger.queue` / 完成核验 | AgentRun 的 engine 与 callUnit 固定为 `API` / `API_REQUEST`；成功复用另核对固定路由 engine | 原生节点必须保存实际引擎与 `CLI_SESSION`，不能只删除上层选择限制 |
| `TeamCoordinator.Session` | 约 121 行无条件解析 API `baseUrl` 并检查 API 凭据；同时承载逐请求资源、动态工具和阶段权限 | 原生路由地址为空，直接放开上层会被停止；应拆开运行有效性、阶段权限和动态父工具能力 |
| `WorkspaceRuntime.runWithMcp` | 约 651–652 行拒绝带 session 的原生运行及原生 MCP 固定动作 | 原生运行需要独立的阶段上下文与资源生命周期，不能只删除两条拒绝 |
| `ProcessWorkspaceRuntime` / `WorkerProcessMain` | `hasTools` 同时决定阶段权限、资源回调及动态工具可见性 | Worker 私有通道应显式传递原领取、权限、原生阶段能力与桥接信息；不向模型开放核心控制面 |
| `McpAccessRegistry` / `McpController` | token 只限项目，服务端可读该项目内任务及记忆；不绑定 task/run/Worker 领取 | 自动注入应使用任务、阶段运行与原领取限定的短期授权；不能把现有项目 token 或桌面管理 token 直接交给子 Agent |
| `ExecutionStore.agentToolSource` / `ProcessWorkspaceRuntime.Invocation.requireReceipt` | 团队和外部固定 MCP 工具要求原模型请求已经成功，且原调用没有尚在途请求 | 原生进程存活期间的 `CLI_SESSION` 不能冒充成功 API 回执；如开放外部动作，必须另定义正式的原 CLI 派发来源及操作账本 |
| `web/src/AgentDefinitions.tsx` / `WorkflowPicker.tsx` / `dialogs.tsx` | 职责、额外路由和流程入口均有 API 条件 | 展示实际支持的阶段、数据发送确认、软会话计数和拒绝原因；前端放开不能替代后端门禁 |

现有 `probe` 只进行版本及帮助探测，返回 `verified=false`。它不证明模型登录、实际 MCP 调用、完整配置隔离、进程挂起或细粒度路径权限。现有原生测试使用自制子进程，能验证参数、预算和拒绝路径，不能作为官方 CLI 的最终兼容性证据。

## 3. 最小能力契约与启动门禁

原生阶段的最小能力是：固定输入版本与实际权限、单次 CLI 会话身份、整个进程树的执行与停止观察、确定性配置注入、可追溯输出，以及确认结束后才能交接的文件成果。阶段不提供 `delegate_agent` 或 `wait_agent` 等要求挂起当前原生模型会话的动态父工具。

启动前必须满足：

1. 固定路由、模型、供应商及版本，遵守根硬锁定。额外路由仍需 `allowedRouteIds` 明确确认，不能因一个 CLI 缺少能力自动换供应商或清除锁定。
2. 检查根、职责、节点形成的完整读写范围交集。适配器不能强制的窄范围在派发提示和承诺预算前拒绝；不能用事后 diff 检查代替读取权限边界。完整隔离项目写入也必须原本就在授权内，不能自动扩大为 `**`。
3. 明确原生工具可控制范围。没有命令白名单强制能力时，不把合同中的任意命令权限交给原生工具；必要验证仍由核心已有执行链完成。
4. 确认配置隔离和 MCP 来源。只允许任务批准的桥接与工具，不默许继承用户、项目、系统或插件的额外 MCP、hook、技能和外部动作；做不到时应明确报告不支持该组合。
5. 对实际 CLI 二进制/版本与本次配置策略保存能力证据。帮助中出现参数只能证明参数存在；实际连通、权限和取消分别验收。

资源与预算不变量：

- CLI 存活期间持续持有真实 model/process/route 容量，不能在其内部等待 MCP 或模型时仅改状态释放。只有原进程及已观察后代实际结束，且回执归属核验通过，才能释放并发布可复用成功。
- API 父执行者等待阶段结果时可释放自身已结束的模型请求容量。模型容量二时，两条 API 编排链及原生阶段应能推进，不需要把原生进程假装挂起。
- 每次原生启动承诺一个 `CLI_SESSION` 软预算单位；内部请求数、未观测 token、费用仍为 `UNKNOWN`。混合根预算要明确其单位是平台承诺调用，而非声称是精确模型请求总数；每个运行继续保留自己的 `API_REQUEST` / `CLI_SESSION`。
- 普通开发及平台自身模型开销不能消耗验证预留；CLI 失败、取消或失联不退回已经承诺的会话预算。
- 原领取、操作、模型会话、节点尝试与输入版本在首次派发前持久保存。断连或丢失结果不证明未执行，不自动启动第二次 CLI。

## 4. MCP 桥接边界

建议复用核心已经持有的权限与操作账本，给每次原生阶段发放短期 token。授权至少关联项目、根任务、AgentRun、节点尝试、Worker 领取及允许工具；取消、撤权、失联封闭或阶段结束后，后续请求不可继续使用旧 token。每次工具调用重查原领取与授权，不只验证随机 token 的字符串。

平台只读事实应限制为该阶段已经获准的上下文和证据；窄范围阶段不能通过 `project_search`、`project_memory` 或其他任务查询绕过输入范围。当前项目级工具不天然满足这一条件。

可先实现一个内部只读快照桥，工具只返回固定阶段的获准输入与上下文，不接任意 taskId，也不透传整份 task 或 attempt。授权应在原 `Invocation` 与领取建立后发放；HTTP 线程按已绑定身份重新检查，不能依赖调用线程的 `ThreadLocal` 或过滤器一次成功。每次请求同时核对原根执行、原操作与活动租约、Worker 身份、写入归属、阶段结束/取消/超时、输入版本及当前授权；正常收尾、失联或核心重启后旧授权失效。该内部桥本身也尚未实现，不作为完整原生阶段交付。

现有 `attempt.inputs` 只提供输入来源起点，不能天然证明窄范围读取已经授权。`handoffs.context` 引用根任务上下文，任务创建又可带入全部有效项目记忆；这些资料缺少可按文件读取范围逐项核验的规范出处。桥接需采用获准字段投影，无法证明文本来源符合窄范围时在派发前拒绝该组合；上下文版本变化或撤销也须拒绝旧授权，不能静默过滤后继续使用。现有项目级 `task_context` 行为不能直接充当该边界。

如阶段需要现有授权的外部 MCP 固定动作，应仍经核心固定别名、完整参数、原授权版本、根 MCP 预算与操作账本执行。不能直接把外部服务凭据、任意参数化 MCP 或桌面管理能力传给 CLI。原固定动作尚不能获得正确调用身份时，启动前拒绝相应组合，不能静默删掉必要工具后继续。

凭据只在本次会话/私有进程环境传递；配置尽量引用环境变量，不把实际 token 写入普通配置、命令行、模型提示、事件或导出。实际环境变量名须考虑 `WorkspaceRuntime` 现在会清除继承的 `WORKBENCH_` 前缀：显式授权的私有变量应通过受控覆盖传递，不能取消整个环境清理。

## 5. 官方来源：Codex

已核对官方发布 [rust-v0.170.0](https://github.com/openai/codex/releases/tag/rust-v0.170.0)，固定提交 `29eb79e9866b3262a777fdf8195c1b9e63684c91`。本机只读探测报告 `codex --version = 0.169.0-alpha.11.1`；两个版本不同，因此固定源码证据不能直接当成本机二进制的全部行为证明。

| 官方固定源码 | 观察与影响 |
| --- | --- |
| [exec/src/lib.rs:396](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/exec/src/lib.rs#L396) | exec 将 `ignore_user_config` 与 `ignore_user_and_project_exec_policy_rules` 交给 loader；没有据此关闭项目配置的证据 |
| [config/src/loader/mod.rs:273](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/loader/mod.rs#L273) | 系统/云配置仍有独立加载层；`--ignore-user-config` 不能解释为忽略所有外部配置 |
| [config/src/loader/mod.rs:412](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/loader/mod.rs#L412) | 项目发现仍可加载项目配置；CLI 覆盖在后续叠加 |
| [config/src/loader/mod.rs:550](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/loader/mod.rs#L550) | ignore-user 分支生成空用户配置层，只说明用户层被跳过 |
| [config/src/mcp_types.rs:672](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/mcp_types.rs#L672) | Streamable HTTP 支持 `bearer_token_env_var`、`http_headers`、`env_http_headers`，存在不把 token 本身放入命令行的官方配置入口 |
| [codex-mcp/src/rmcp_client.rs:1080](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/codex-mcp/src/rmcp_client.rs#L1080) | Bearer 环境变量解析对缺失、空值或非 Unicode 返回错误；这仍不能代替平台按原领取检查授权 |
| [config/src/merge.rs:143](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/merge.rs#L143) | 表按键递归合并；设置 `mcp_servers={platform=...}` 不能当作清除下层全部 MCP 的全量替换 |
| [protocol/src/permissions.rs:597](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/protocol/src/permissions.rs#L597) 与 [ReadOnly 映射:2128](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/protocol/src/permissions.rs#L2128) | 只读策略包含 root 读取；不能从 `--sandbox read-only` 推导出只能读取授权项目目录 |

显式注入候选是保留必要隔离参数，并通过 `-c` 设置单个 `mcp_servers.platform` 的 HTTP URL 与 `bearer_token_env_var`。这是配置入口的源码证据，尚未完成实际 MCP 连接或模型调用。单独增加该覆盖不能证明不存在其他继承服务器；[managed/MDM 配置还会在更高层叠加](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/config/src/loader/mod.rs#L457)，CLI 覆盖不能当作删除组织策略。

`--ignore-rules` 的范围是 execpolicy `.rules`，不能据此声称 AGENTS、hooks、plugins、skills、所有系统/项目策略均已禁用。实际支持组合仍需逐项核验；必要的上层策略也不能为了兼容而关闭。

## 6. 官方来源：Claude Code

本机实际 `claude --version` 为 `2.1.319 (Claude Code)`。已实读[官方固定版本 manifest](https://downloads.claude.ai/claude-code-releases/2.1.319/manifest.json)，其 `win32-x64` 制品大小为 344,807,072 字节，SHA-256 为 `2e055732c9d03d22700d9046251a8370b673607bfcc9e4d16a1b0e8fb1345cbd`，与本机 `C:\Users\Zeng qi he\.local\bin\claude.exe` 的实读摘要一致。manifest 的构建时间为 `2026-11-05T19:38:09Z`，内部构建 commit 为 `fbdd7e02e8530e4c2ade8cc67d30104e53d2c8f2`。

公开[发行 tag v2.1.319](https://github.com/anthropics/claude-code/releases/tag/v2.1.319) 指向 `0ed94698aa0aeee471465fc9c867c2e8434c4d68`；[固定 CHANGELOG](https://github.com/anthropics/claude-code/blob/0ed94698aa0aeee471465fc9c867c2e8434c4d68/CHANGELOG.md) 可引用。该公开仓库没有核心 CLI 完整源码，不能把公开 tag 或内部构建 commit 说成已经公开审计的核心源码。

下列结论来自本机真实帮助、官方文档，以及上述匹配官方摘要制品的静态读取；没有真实 MCP 握手或模型调用。官方网页文档会继续更新，不能把网页正文当作固定发行制品的一切行为证明。

| 官方证据 | 结论与限制 |
| --- | --- |
| [CLI flags](https://code.claude.com/docs/en/cli-reference#cli-flags)、[干净配置调试](https://code.claude.com/docs/en/debug-your-config#test-against-a-clean-configuration)与真实 `--safe-mode` 帮助 | safe mode 禁自定义 MCP、hooks、installed plugins、skills、CLAUDE.md 等；managed settings 仍适用，部分 managed hooks 也仍适用，不能声称所有管理策略消失 |
| 官方制品字节偏移 `308437488`、`308438206`、`308493249` 附近的嵌入脚本 | safe mode 的动态 MCP 过滤会丢弃普通 HTTP、SSE、stdio 服务器，`--mcp-config` 显式配置也经过该分支；保留的 `sdk` 类型还要通过 SDK host 通道校验。普通原生 CLI 不能用 `--safe-mode --mcp-config <HTTP配置>` 作为平台桥接方案 |
| [CLI flags](https://code.claude.com/docs/en/cli-reference#cli-flags) 与固定 CHANGELOG | `--strict-mcp-config` 只跳过其他 MCP 来源，并不单独关闭 hook、plugin 或文件访问。显式 `--agents` 里的 inline MCP 还有独立边界，不应额外注入后声称只剩一个服务器 |
| [bare mode](https://code.claude.com/docs/en/headless#start-faster-with-bare-mode)、真实 `--bare` 帮助与固定 CHANGELOG | bare 跳过常规自动发现，只连接命令行显式 MCP；settings/plugin 中显式传入的 hooks 也不运行。但它不读取 OAuth/keychain，Anthropic 认证使用 `ANTHROPIC_API_KEY` 或显式 settings 的 helper，因此不能直接取代现有本地订阅登录路径 |
| [受信任目录前运行边界](https://code.claude.com/docs/en/permissions#what-runs-before-you-trust-a-folder) | bare 仍受项目 settings 的 `env` 与某些 helpers 影响，不能解释为忽略全部用户/项目配置 |
| [CLI flags](https://code.claude.com/docs/en/cli-reference#cli-flags) 与真实 `--restricted` 帮助 | restricted 忽略 user/project/local settings，保留 managed 与显式 settings；内建文件工具限制在 working directories。命令/代码工具默认移除，但 `--tools` 可显式重新列入；此保证不等于 MCP 或 OS 的同一文件沙箱 |
| [MCP 环境变量展开](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcp-json) | `url`、`headers` 等支持 `${VAR}`，可用独立平台 token 变量配置 Bearer。缺变量时可能保留原文字面量，平台必须自行拒绝空/缺 token；不应复用被特别处理的供应商凭据变量 |
| [企业 MCP 与动态配置](https://code.claude.com/docs/en/managed-mcp#servers-passed-with-mcp-config-or-strict-mcp-config) 与制品偏移 `301807301` 附近 | 工作站企业 MCP 配置可使 `--strict-mcp-config` 或动态 `--mcp-config` 在启动时拒绝；平台不能为了接入覆盖或删除企业策略 |
| [目录外读取限制](https://code.claude.com/docs/en/settings-reference#permissions-blockreadsoutsideworkingdirectories)、[目录外写入限制](https://code.claude.com/docs/en/settings-reference#permissions-blockwritesoutsideworkingdirectories) | 文件工具另有目录边界设置，且存在 CLI 自身文件例外；这些键不限制 shell、MCP 或 hook 进程。`--tools Read,Glob,Grep` 只限定内建工具种类，不能单独证明只读授权项目 |

因此可验证的候选是固定发行版本的 `--bare` / `--restricted` / `--strict-mcp-config` 与显式 MCP 组合，分别确认配置、认证、内建文件边界和企业策略。其组合尚未完成实际运行验证；bare 认证变化也必须作为产品能力显示。不能仅删除 `--safe-mode` 后保留原来的隔离声明，或将一个受支持的 API Key 路径当作订阅/OAuth 登录已经兼容。

CLAUDE.md 自动发现与 Agent 主动读取文件也应分开。官方 [AGENTS.md 文档](https://code.claude.com/docs/en/memory#agents-md) 涉及 built-in 插件，safe-mode 帮助又说明 built-in 插件正常；本次没有追完这个特例在各模式下的专门 gate，不宣称 safe/bare 必然禁掉 AGENTS.md 自动注入。

## 7. 交付验收

1. 通过产品入口创建包含 API、Codex、Claude 阶段的固定版本流程，并明确确认额外数据路由。真实官方 CLI 在用户已经授权的登录/端点上完成固定任务；两种原生适配器分别保留版本、实际配置策略和原始回执。
2. 原生阶段取得被授权的平台上下文，生成实际文件成果；下游只取得声明祖先的实际证据和版本。根整合之后执行合同必要验证与独立审查，最终证据绑定最终产物版本。不能用 CLI 自报成功替代这些门禁。
3. 不支持的窄读/写范围、动态父工具、强逐请求预算、缺失配置隔离或必需 MCP 在派发提示和扣预算前明确拒绝；硬锁定和必要工具不被降级移除。
4. 两条编排链在模型容量二下能推进，观察到的原生存活数与真实资源占用一致；原生内部 MCP 等待不假装释放模型/进程槽。验证预留不能被开发会话使用。
5. 根取消、阶段超时、Worker 强停、核心重开、MCP 回执丢失分别保留原领取、文件现场、预算和状态；已派发且结果不明时全链保持 `UNKNOWN`，无第二次 CLI 或工具副作用。确认停止事实与确认远端效果分开保存。
6. 旧 token 在阶段结束、撤权或原领取失效后不能调用；跨项目、其他任务、越范围检索及直接访问管理 API 均被后端拒绝。日志、导出和 CLI 参数观察不暴露凭据。
7. 参数/协议夹具用于确定性边界与失败路径；实际官方 CLI 和真实端点用于最终兼容性验收。环境缺少已授权登录或服务时，保留实现与拒绝证据，明确列为未验收，不把夹具成功等同最终完成。

本文不推断首次 Windows CI Worker 失联的根因。该问题与本次下一项需求建议、以及准备操作已经为 `UNKNOWN` 后的收尾修复分别记录。
