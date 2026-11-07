# MCP 本地停止、容量与核对审查

日期：2026-11-07。版本：0.2.0-alpha.3。依据计划书 7.4、8、9.3、9.5、T08–T12 与 F09；局部执行可靠性改进，不代表完整里程碑验收。

## 开源检索

先通过 GitHub 官方 API 与 raw 源码读取下列文件，未引入依赖或复制代码。缓存位于 `.runtime/research-2026-11-07/`；使用 `git hash-object` 校验实际保存内容。

| 来源 | 提交及文件 blob | 采用的思路 |
| --- | --- | --- |
| [官方 MCP Java SDK StdioClientTransport](https://github.com/modelcontextprotocol/java-sdk/blob/29a1c76b88f356f043f3c59690c337916586b6ed/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/StdioClientTransport.java) | commit `29a1c76b88f356f043f3c59690c337916586b6ed`；blob `60a8d7c134405986a2f81f471f1a34abe55fa488` | closeGracefully 在终止请求后等待 process.onExit；本项目同样把停止请求与停止证据分开，并另加已观察子树、有界等待及容量保留 |
| [官方 MCP 2025-06-18 Cancellation](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/c9b51b3a02f3851f9821d5191718652bab97adac/docs/specification/2025-06-18/basic/utilities/cancellation.mdx) | commit `c9b51b3a02f3851f9821d5191718652bab97adac`；blob `cef9444a13003fe4f03f0da36881cf1cc2ed1a25` | 接收端可以忽略取消，且取消与返回有竞争；本地停止与外部副作用必须分别记录，不能据本地取消推断动作未发生 |

浏览器搜索未返回正文；首次误把文件 blob 当成 raw commit 路径而收到 404，随后通过 commits API 固定提交并实际读取。失败请求不作为证据。没有声称 Java SDK 自身处理了本项目的全部后代或任务预算问题。

## 复现与改动

新增真实 Node stdio 服务：完成初始化和工具发现，调用时启动一个持续运行的 Node 子进程，延迟写入父子 PID 后挂起。等待调用确实进入后再取消。旧代码中父子均退出，但账本断言失败：预期 stopState=CONFIRMED，实际 UNCONFIRMED。

旧调度代码没有读取 McpTransport.stopConfirmed，try-with-resources 无条件归还容量；同时人工核对只验证客户端勾选。现统一三个 MCP 入口的 ManagedTransport 生命周期：关闭后实际确认停止，才归还 tool、mcp:<id> 和 process 资源；否则保留句柄和容量，每 100 ms 复查。复查只更新停止证据，不重新发起业务动作。

UNKNOWN 写入和后台停止证据更新共用存储锁，避免停止观察被过期快照覆盖；观察线程不再修改调用线程持有的可变 Map。确认停止后写入 localStoppedAt。人工核对先拒绝仍在执行/收尾的任务，再查询 MCP 存活；错误时不修改操作或任务状态。

真实停止不能撤销外部效果；已派发但未取得结果仍 UNKNOWN。若结果已保存但关闭失败，保留 result 与 dispatchState=ACKNOWLEDGED；若尚未派发，保留 NOT_SENT。两者均阻止继续执行。重启遇到 ACKNOWLEDGED/UNCONFIRMED 也转为 UNKNOWN，保留原结果。

## UI 与 CI 证据

alpha.2 的 run `42247710382` 在 Windows 人工核对测试中失败：5 秒内测试侧 actions 数量仍为 0，失败 DOM 已处于“正在保存…”，说明提交处理器已执行，不能据此断言表单丢失。没有 trace，无法证明请求延迟的具体原因。

只增加 main 分支约定的提交 `4ba1c06`（产品及测试代码与 alpha.2 相同）在 run `42347134703` 中 Windows、macOS、Ubuntu 全部通过，进一步表明该失败并非稳定复现。当前用例改为在点击前监听精确 POST 响应并验证状态、payload 和恢复按钮；保持原测试总时限、不加重试，失败保留 trace。不能把这项改动描述为已证实的产品错误修复。

## 验证与剩余范围

新增回归覆盖真实 stdio 父子进程在途取消；受控停止故障分别覆盖派发前定义变化与派发后已收到回执，检查资源保留、恢复观察、核对拒绝与通过、旧动作不重发；恢复测试覆盖回执保存后停止确认缺失。受控停止故障使用真实本机 HTTP 协议加可控 stopConfirmed，不证明操作系统中难以终止进程的全部情况。

完整命令、结果、打包和远端 CI 见实施记录。未验证：观察间隙创建后立即脱离的子进程、OS Job/cgroup 级隔离、逐持久化边界的真实核心崩溃、外部服务副作用、原生 CLI MCP 注入、动态委派、完整备份恢复和长期负载。这些仍属于后续工作。

本地 Edge 9/9 界面通过；额外 Chromium 检查遇到浏览器安装缺失及 Node spawn UNKNOWN。安装改用代理后已完成，但独立 `spawnSync(chrome.exe, ['--version'])` 仍失败，因此未将该运行记为界面回归通过。远端以原 CI Chromium 通道执行并保存失败 trace，另行核对实际结果。
