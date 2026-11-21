# 原生 CLI 终态回执

更新日期：2026-11-21。对应计划书 M0 的运行器能力边界、M1 的结果未知与预算，以及 M2 的混合接入。固定来源、文件大小和 SHA-256 见[来源清单](./native-cli-receipts-sources-2026-11-21.json)。

## 开源依据与实现选择

已实读 Codex 固定提交 `29eb79e9866b3262a777fdf8195c1b9e63684c91` 的 [exec_events.rs](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/exec/src/exec_events.rs) 和 [JSONL 事件处理器](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/exec/src/event_processor_with_jsonl_output.rs)。`thread.started` 固定线程身份，`turn.started` 开始一轮，`turn.completed` 与 `turn.failed` 表示明确终态；已完成的 `agent_message` 是输出来源。被打断的轮次不会被处理器发成成功终态，只有中间消息不能支持成功判定。

已实读 Claude Agent SDK Python 固定提交 `3184360393a92ca8a6ec7bbbdee571afd1ce1c56` 的 [ResultMessage 类型](https://github.com/anthropics/claude-agent-sdk-python/blob/3184360393a92ca8a6ec7bbbdee571afd1ce1c56/src/claude_agent_sdk/types.py)。该类型包含 `subtype`、`is_error`、`session_id` 和可选 `terminal_reason`。即使 `subtype=success`，`is_error=true` 仍可能表示 API 错误；`aborted_streaming` 和 `aborted_tools` 表示运行器报告的中断，不能由此生成核心的进程停止证明。

上述源码用于协议设计。真实 Codex/Claude 二进制、登录方式、平台配置与供应商任务的兼容性仍分别验收。解析器沿用现有配置隔离、文件权限、MCP 和预算门禁。

## 协议解析

`NativeCliReceipt` 只读取执行器单独收集的 stdout。Codex 接受一个线程中的完整轮次序列，仅使用 `item.completed` 的助手消息；后续未结束轮次、重复终态、重复线程、未收尾错误或缺少轮次终态均为 `UNKNOWN`。明确失败轮次不会被后续文本或成功轮次抹去。已被明确终态收尾的重试错误和不致命 item 错误分别处理。

Claude 的当前 `--output-format json` 配置只接受单个 result object。成功要求 `subtype=success`、布尔 `is_error=false`、有效会话身份，以及缺省或 `completed` 的终止原因。已知失败 subtype 与错误标志冲突、未知终止原因、未知 subtype 或不合法字段保持 `UNKNOWN`；明确错误、中断和限额为 `FAILED`。

Jackson Reader 独立启用重复键与尾随 JSON 检查，不改变共享 ObjectMapper。输入最多 524,288 字符、Codex 最多 10,000 个非空事件；截断和未确认管道收尾不产生可复用成功。未知回执输出为空，合法的结构化成功可以没有自然语言文本，具体阶段的交付门禁继续检查业务输出。

## 进程与协议分别保存

| 原进程状态 | 协议成功 | 协议失败 | 协议未知 |
| --- | --- | --- | --- |
| SUCCEEDED | SUCCEEDED | FAILED | UNKNOWN |
| FAILED | UNKNOWN | FAILED | UNKNOWN |
| UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |
| CANCELLED | CANCELLED | CANCELLED | CANCELLED |

原进程 `SUCCEEDED` 仍要求现有执行器确认流已收尾、退出码为零及已观察后代停止。非零退出与协议成功冲突时保留 `UNKNOWN`。核心取消只沿用原进程停止事实，Claude 的中断字符串不会直接生成 `CANCELLED`。

运行结果和模型请求回执分别记录协议 `state`、`processState`、`processExitCode`、`executionState`、流完整性、解析原因和捕获文本摘要。`stdoutSha256` 是传入 String 的 UTF-8 编码摘要，属于已解码文本，不能当作解码前原始进程字节的摘要。

## 持久化与授权

核心对 `nativeReceipt` 使用固定键、枚举、布尔、整数、摘要和 UUID 投影。未知嵌套载荷、stdout 原文、任意错误文本和不透明身份原值不能透传到模型请求回执。已识别凭据先脱敏，UUID 形式的敏感身份也不能因格式合法而被保留。缺少有效终态或矛盾的执行事实不能发布成功请求来源；已结束请求不可覆盖。

每次实际 CLI 启动仍承诺一个 `CLI_SESSION` 软预算单位。内部请求数、费用和未观测 token 继续为 `UNKNOWN`，`remoteOutcome` 不根据一个 CLI 终态推断所有内部远端请求已经确认。失败或未知回执不退回已承诺预算，也不自动重新启动 CLI。

## 当前验证

本机 Windows 清洁定向回归为 114 项：113 通过、1 条件跳过，零失败，源码前后摘要一致。包含 51 个解析、状态组合和元数据边界实例、56 个运行时实例及 7 个持久化实例。实际子进程覆盖 Codex/Claude 成功、明确失败、缺少终态、stderr 中的伪终态、非零退出冲突与 Unicode；重启测试覆盖原预算、脱敏、不可变终态以及短预览的既有截断标志。

该定向结果与本机完整核心、实际 Jar 生命周期、三端 CI、真实官方 CLI 兼容性分别记录。alpha.26 本机完整运行的唯一递归收尾超时，以及 macOS 原生终端 UI 的 CI 失败，保留在各自原始记录中。
