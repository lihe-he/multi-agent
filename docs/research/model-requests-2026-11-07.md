# 模型请求追踪来源与自审

日期：2026-11-07。对应 F16 的单次模型请求定位，接续 alpha.6 的持久 AgentRun。实现将请求、根预算和运行身份关联，尚未引入 OpenTelemetry SDK 或遥测导出。

## 开源参考

实际读取 OpenTelemetry `semantic-conventions-genai` 的固定提交 `e6658f14c9d9ea0760825f029cb82e7673f48fab`：

| 来源 | 实际观察 | 本项目取舍 |
| --- | --- | --- |
| [GenAI spans](https://github.com/open-telemetry/semantic-conventions-genai/blob/e6658f14c9d9ea0760825f029cb82e7673f48fab/docs/gen-ai/gen-ai-spans.md) | 生命周期从操作开始持续到响应、错误或取消；输入输出被视为敏感内容，遥测采集要求显式选择 | 每次派发前保存身份，结束时记录实际回执，缺失时保留 UNKNOWN；内容只在本机任务内保存脱敏片段。未实现该规范的遥测采集开关，不声称符合其内容采集要求 |
| [Client inference](https://github.com/open-telemetry/semantic-conventions-genai/blob/e6658f14c9d9ea0760825f029cb82e7673f48fab/docs/gen-ai/client-inference.md) | 请求模型与响应模型/身份分别记录；输入、输出、缓存读取、推理用量按供应商可提供的事实记录 | 保存原请求路由和供应商回执，保留未知与 0 的区别；缺少任何一轮用量时汇总仍未知，CLI 内部调用保持未知 |

原始文件经本机代理下载，缓存于 `.runtime/research-model-requests-2026-11-07/`。Git blob hash：gen-ai-spans `548d47fdc1c7f2c202e7616797a69ce96e5d3b8a`；client-inference `b269dbb0415d0e3d383873009d06cf939c04a55e`。本轮再次读取固定提交的 client-inference 原始文件并核对 hash 一致。没有以搜索摘要替代原文，也没有复制上游实现或增加依赖。

上游区分逻辑操作和单次尝试，能够表达重试。本项目当前没有自动请求重试，每条请求 attempt=1；任务恢复使用新的 AgentRun，保留原请求事实。当前本地字段和状态是工作台自己的协议，不代表已输出符合 OpenTelemetry 的 span。

## 自审决策

- 请求身份、根预算及 AgentRun 调用数在同一事务承诺，再允许发送。预算拒绝不产生请求；故障注入验证三项一起回滚。
- 观测器包围模型传输和基本响应校验，工具调用仍走原执行路径。工具失败不能把已收到的模型响应改成失败。
- 收到 HTTP 错误或无效结构记 FAILED；传输中断或停止本地等待记 UNKNOWN。HTTP 取消仅证明本地行为，不推测远端结束或费用退款。
- AgentRun 结束或核心恢复时，未结束请求原子收尾为 UNKNOWN/MISSING_OBSERVATION；旧成功结果不覆盖、不重放。
- 输入摘要针对实际序列化内容，Authorization 头不进入输入；凭据在截断前移除。脱敏前后长度不同，截断标记必须基于最终文本长度。
- 响应身份、模型、结束原因和错误均经过脱敏器；异常响应原文不保存。前端输入输出使用 React 文本节点，脚本样式文本不能执行。
- 缺失/无效用量为 UNKNOWN，明确 0 保留；完整总值与已知部分累计分别保存。缓存/推理属于已有总量的明细，不再叠加。
- 请求查询按任务及可选运行限制作用域，以稳定行号翻页，过滤使用连续字面匹配；轮询保留历史页。旧数据不推测补造。

## 验证与失败记录

存储用例覆盖请求 INSERT 故障的事务回滚、重开持久化、终态不可覆盖、回执缺失、预算拒绝、任务/运行作用域、中文/大小写字面匹配和游标边界。实际 HTTP 用例覆盖两轮工具调用、不同输入摘要、供应商身份、已知/缺失用量、HTTP 500、无效协议、断连与取消，每种失败均检查不自动重试。引擎用例验证截断边界上的凭据先移除、输入输出和身份脱敏，以及重启后只把待确认请求置为 UNKNOWN。

浏览器在 1440px/430px 验证状态筛选、检索、历史轮询、返回最新、UNKNOWN 与 0、输入输出纯文本和横向溢出。生产 Web 与真实 Java 经同源代理展示一个在途请求，并核对真实 AgentRun 身份；客户端退出后仍保留相同在途任务。完整命令及本轮结果见[实施与验收记录](../implementation-status.md)。本机协议端点仅证明工作台集成，不证明外部供应商可用。

新增 HTTP 用例首次编译因测试工具构造抛出 checked exception 失败，已把构造移到 HTTP handler 外。引擎新增 Mockito 用例首次使用 when/thenAnswer，触发旧 answer 的空参数路径；改为 doAnswer 后重跑通过。截图自审修正筛选输入样式；后续发现运行器按脱敏前长度设置输出截断标记，已改为核心根据脱敏后长度决定，并验证边界内容无需截断时标记为 false。失败尝试不计作通过证据。

Token 趋势/F21、成本换算、自动重试、原生 CLI 内部请求、动态 SubAgent/Teams、远程 Worker、长期保留与内容采集开关均未在本轮完成。
