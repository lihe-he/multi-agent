# 继续开发的来源与决策

日期：2026-11-06。此轮恢复未完成的开发现场，先落实架构审查第八节的第一步。原有计划书 1.1 与新增 F01–F21 的范围保留；下述迭代不是整体完成声明。

## 开源检索

本轮实际读取官方仓库 README，并通过 GitHub API 取得该 README 最近修改的提交。它们用于设计参照，未复制实现代码或新增框架依赖。

| 项目 | 读取材料 | README 最近修改提交 | 采用的思路 |
| --- | --- | --- | --- |
| LangGraph | https://github.com/langchain-ai/langgraph/blob/main/README.md | `37014c09b9fafbfec9f336da80d8624d75a9ff7f` | 持久执行、状态检查点与人工介入分别建模；传输断开不直接改变任务事实 |
| OpenHands | https://github.com/OpenHands/OpenHands/blob/main/README.md | `6dad5568d64d942cc910bf7124e8d16f90b1d07d` | 控制界面与执行后端分离，客户端生命期与后台任务分开；该 README 当前标题为 Agent Canvas，应依据实际材料而非旧印象选型 |
| MCP Java SDK | https://github.com/modelcontextprotocol/java-sdk/blob/main/README.md | `e7d8a3623fcafaea5a1e4c6a9fe76bb2903e19e2` | 协议客户端、服务端与 Spring 集成保留独立边界，不能因协议可用就省略任务授权账本 |

源材料的完整本机缓存位于 `.runtime/research-2026-11-06/`，不作为发布产物。后续引入源码或依赖前应固定具体版本并复核许可证；本轮不依据 README 宣称这些第三方项目的全部行为已经实测。

## CLI 边界核验

本机 `codex exec --help` 实际提供 `--ignore-user-config` 与 `--ignore-rules`；本机 `claude --help` 提供 `--safe-mode`，并说明禁用自定义 MCP、hooks、plugins、skills 等。适配器现在要求这些选项存在，缺少时在派发提示和计入模型调用前拒绝；真实子进程夹具验证参数、只读工具白名单和拒绝路径。

官方 Codex CLI 参考地址为 https://developers.openai.com/codex/cli/reference 。本轮直接访问遇到 EOF、DNS/代理 TLS 错误，未把失败请求或空缓存当作新文档证据；已另调用官方域名搜索/读取工具，但未获得可引用正文。本轮新增行为的证据为本机帮助与适配器回归，不宣称已完成全部原生运行器外部配置、管理员策略或远端副作用隔离。此前 0.2 审查发现的完整权限边界仍须继续验证。

## 本轮交付与后续依赖

已落实构建脚本修复、缺失 Java 导入修复、独立核心/Web 构建入口、无凭据运行时 Web 配置、已有核心桌面连接，以及对应自动化生命周期检查。保持本机鉴权与版本证据门禁。

仍需补齐：MCP 固定动作从任务创建到模型工具回路的完整接入、未知操作在根任务上的传播与恢复、动态 AgentRun/Team/Workflow、远程执行节点与身份、持久多会话及其 UI。完整 F01–F21 与 M0–M4 的待办继续以架构审查和计划矩阵为准，不能用本轮绿色检查覆盖未实现项。
