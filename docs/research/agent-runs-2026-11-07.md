# AgentRun 来源与自审

日期：2026-11-07。对应架构迁移第二步的 AgentRun、F12 原始活动来源与 F16 执行定位基础；未将固定阶段认定为动态 SubAgent 或 Teams。

## 开源参考

| 来源 | 实际观察 | 本项目取舍 |
| --- | --- | --- |
| [AutoGen TaskRunner / TaskResult](https://github.com/microsoft/autogen/blob/ec5da1ef7bbef2117d8f38f6caa2fc17145b7368/python/packages/autogen-agentchat/src/autogen_agentchat/base/_task.py) | 运行接口分开传取消令牌，TaskResult 保存消息与 stop_reason；run_stream 单独产出事件和最终结果 | 保存独立运行与结束原因，保留根取消；运行成功和任务验收分别记录。未复制其有状态继续执行机制 |
| [LangGraph CheckpointTuple](https://github.com/langchain-ai/langgraph/blob/3a1796ecf072b9f3bb429d5835179c15e92d719a/libs/checkpoint/langgraph/checkpoint/base/__init__.py) | CheckpointTuple 关联 checkpoint、metadata、parent_config 和 pending_writes，metadata 包含 run_id | 将运行身份关联现有操作意图、内容版本与原始证据；未引入该框架、自动恢复或副作用重放 |

已通过 GitHub API 固定文件提交并实际下载原始文件，缓存在 `.runtime/research-agent-runs-2026-11-07/`。Git blob hash：AutoGen `b858b4a4517c417cae116dd2043f749cd4ff652c`，LangGraph `c7cacc7bfd703f298427e870bd6d07b4aa395812`。Web 搜索未返回结果，原始内容经本机代理读取；未以搜索摘要代替源文件，也未增加依赖。

## 自审决策

- 运行身份由核心生成，归属从任务派生；API 仅查询，不接收客户端伪造的执行身份。字段只复制必要路由属性，原异常通过现有脱敏器处理。
- 排队、操作启动、调用预算、证据、结束分别在明确事务边界保存。资源等待中取消不调用模型、不消耗根预算；已承诺调用不退款。
- MCP 等待释放模型容量并显示 WAITING_TOOL；继续模型时先恢复资源申请，权限与预算仍走原路径。
- 结果未知和人工核对保留历史事实，不修改旧运行结果。审查运行 SUCCEEDED 不代表意见 APPROVED，更不代表根任务已被接受。
- 尝试计数按 task/stage 的局部索引查询；查询页按稳定行号分页。界面轮询当前页，旧页不会被最新运行替换；返回最新由用户主动触发。
- 证据按钮定位并展开原始 evidenceId；会话运行链接回真实来源任务。纯文本原因不解释 HTML。
- 复用实体存储是当前单实例 SQLite 的实现选择；大规模查询、长期保留策略及多节点租约尚未验收。

## 验证与失败记录

新增故障注入验证调用保存失败后根预算/运行计数同时回滚；覆盖持久重开、作用域、稳定分页、结束不可覆盖。引擎覆盖资源排队取消、实际执行/证据、根取消、预算不足、重启未知和重复审查保留旧身份。浏览器覆盖桌面/430px、筛选、历史翻页持续轮询、纯文本和证据定位。真实 Java HTTP 和生产 Web 验证以本轮实施记录为准。

第一次新增用例把会话归属不符预期写为 403，现有协议实际为 404；另一次脚本插入将崩溃用例的断言放到了 MCP 用例内，已移回正确测试。修正后重跑通过，不改产品边界迁就断言。窄屏截图自审发现标题说明间距过小，已调整行高与间距后重新验证。
