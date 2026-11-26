# 持久请求用量趋势：开源依据与设计

计划 §12.1 要求保留输入、输出、缓存、请求次数和未知费用；F21 还缺趋势视图。已有请求分页最多返回 100 条，不能用一页请求或 AgentRun 累计作为整个项目的统计。alpha.30 直接从持久 ModelRequest 聚合，以原创建时间归桶，保持请求、会话和未知量的含义。

## 固定开源来源

先核对上游实现，再确定本项目的指标语义。以下文件已通过 GitHub 内容 API 下载，原文件、blob 身份和 SHA-256 保存在 `.runtime/alpha30-20261126/research/verified-manifest.json`；SQLite 依赖来源另保存在 `.runtime/alpha30-usage-review/vendor-source/`。

| 来源 | 固定版本与已核对行为 | 对本项目的影响 |
| --- | --- | --- |
| [OpenTelemetry Java GenAiClientMetrics](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/018b80b099ff025da89c60f71c8c6091810e70aa/instrumentation-api-incubator/src/main/java/io/opentelemetry/instrumentation/api/incubator/semconv/genai/GenAiClientMetrics.java#L105) | v2.21.0 提交 `018b80b099ff025da89c60f71c8c6091810e70aa`，输入/输出值非 null 才记录；0 仍可记录。文件 SHA-256 `e0930afd164c200894b16b0bc448861b3cd7906a11148f22267038477659e7a1` | 缺失不能按零统计，每字段单独保留已知和未知覆盖 |
| [OpenTelemetry OpenAI ChatAttributesGetter](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/018b80b099ff025da89c60f71c8c6091810e70aa/instrumentation/openai/openai-java-1.1/library/src/main/java/io/opentelemetry/instrumentation/openai/v1_1/ChatAttributesGetter.java#L136) | 同一提交，response 或 usage 缺失返回 null。文件 SHA-256 `5167a3844ec248cf0f8c3d60367236cea40bec4bbd9daf5e61e482a395597b13` | 不从状态、成功文本或 AgentRun 计数推测 Token |
| [OpenTelemetry GenAI client inference](https://github.com/open-telemetry/semantic-conventions-genai/blob/81b5d438519f868a186bff088d9de24aefdb2f6b/docs/gen-ai/client-inference.md) | 提交 `81b5d438519f868a186bff088d9de24aefdb2f6b`，blob `00700e59472101bd6f958514f7fcf6e4ad76606e`。输入包含缓存，输出包含推理，详细用量为总量的子集。文件 SHA-256 `18c410f8385889a01cbb3ba791b37bed1117af1b850f5e5d76b906fe477e7fd4` | 缓存与推理只显示明细，四字段不相加；只取 ModelRequest 一个统计来源 |
| [Xerial SQLite Function.Aggregate](https://github.com/xerial/sqlite-jdbc/blob/4781e381921a348fa2e533d0c43767525374b26d/src/main/java/org/sqlite/Function.java#L308) | 既有依赖 3.50.3.0，提交 `4781e381921a348fa2e533d0c43767525374b26d`，聚合器以 `super.clone()` 浅克隆。文件 SHA-256 `8237af2e13a2d8d9fa807bbba02478127f397a7af5fc324e39ac5410f330b5d6` | 本项目使用不可变 BigInteger 并替换引用，避免不同分组共享可变累加器；不增加新依赖 |

来源核验中纠正了两条候选指针：原摘要中的 semantic-conventions SHA `e6658f14c9d9ea0760825f029cb82e7673f48fab` 在该仓库 API 返回无此提交，不能作为依据；估计的 ChatAttributesGetter 通用目录返回 404。旧 semantic-conventions 仓库在固定提交 `054189a0330c4fb05f8e8e80d84f89765e49fb6f` 的 `gen-ai-metrics.md` 明确指向新的 `semantic-conventions-genai` 仓库，因此重新下载并固定上表的真实文件。没有将旧本机副本当作本次验证件。

## 实现选择

- 查询只接受 UTC 7/30/90 天及项目、会话、任务范围。先校验当前实体归属，再按请求原 `projectId/sessionId/taskId/createdAt` 统计；路由编辑不会改写历史。
- 项目与创建时间的局部索引先缩小窗口；SQL 在数据库内聚合，核心只取得最多 90×3 条日×单位结果。实际 EXPLAIN 的四种筛选组合均使用该范围索引，没有把整个请求表加载到 Java。
- 一次 SELECT 提供全部统计行，summary 和 buckets 从同批结果生成，避免两个查询之间出现请求结束或并发写入导致结果矛盾。
- 精确 BigInteger 累计同时避免 SQLite `sum` 的 long 溢出和跨日合并溢出；响应超过 JavaScript 安全整数范围时返回 `UNKNOWN/overflow=true`，覆盖计数不变。
- API_REQUEST、CLI_SESSION 和未知单位分别保留。无记录、未知值、明确零和溢出分别呈现；费用与 CLI 内部请求保持未知。响应只含聚合白名单，不返回请求内容。

这是基于现有 SQLite API 的本项目实现，没有集成 OpenTelemetry SDK、采集服务或价格服务。上游源码用于核对语义和依赖行为，不代表外部供应商账单认证。

## 验证与剩余范围

新增存储和 HTTP 测试 13 项覆盖持久重开、257 条请求、原身份、UTC 纳秒/偏移边界、三种窗口、单位混合、状态更新、零与未知、SQLite 与 JavaScript 数值边界，以及鉴权和响应不含内容。应用后 Windows 存储/接口四组 26/26；实际 alpha.30 Jar 的完整本机协议流程 36/36，新增检查将趋势和真实 Worker 请求记录逐项比对，并检查范围拒绝、空会话、零新增模型请求和预算保持。

独立审查额外覆盖 1,161 条时区边界记录、100 轮分组/字段隔离、多连接并发，以及查询期间外连接 WAL 提交：当前查询 summary/buckets 一致保留旧值，下次查询一致读取新值。原 probe、源码和哈希在 `.runtime/alpha30-usage-review/frozen-probe/`。最终完整核心、生产 UI 和准确提交 CI 以本版本 QA 回执为准；首次完整 Linux 中两项 PTY 尾帧失败的原记录单独保留。

合同见[用量趋势协议](../usage-trends-contract.md)。本次不交付调度器、任意 IANA 时区、供应商账单、CLI 内部请求或完整效率账本，也不替代真实官方 CLI、远程执行与其余 F01–F21 退出条件。
