# Linux 原生 CLI 沙箱：固定来源与实施范围

日期：2026-11-26。本文说明 `linux-bwrap-workspace-v1` 的设计依据及当前边界；运行契约见[原生沙箱协议](../native-sandbox-contract.md)。固定源码供审查使用，不能代替实际安装版本、内核能力或官方 CLI 的兼容性验收。测试命令、报告和结果由独立 QA 回执记录，本文不声明测试通过或计数。

## 固定开源来源与采纳理由

| 来源 | 固定版本与可复核位置 | 采纳内容 |
| --- | --- | --- |
| containers/bubblewrap | `v0.11.0`，提交 `9ca3b05ec787acfb4b17bed37db5719fa777834f`；[README](https://github.com/containers/bubblewrap/blob/9ca3b05ec787acfb4b17bed37db5719fa777834f/README.md)、[参数协议](https://github.com/containers/bubblewrap/blob/9ca3b05ec787acfb4b17bed37db5719fa777834f/bwrap.xml) | 从空挂载树构建允许视图，使用独立 user/PID/IPC/UTS namespace、最小设备、`--new-session`、`--die-with-parent`。上层必须自己定义策略；存在 bwrap 二进制不证明隔离成立。 |
| containers/bubblewrap | 同一提交的 [bwrap.xml:173](https://github.com/containers/bubblewrap/blob/9ca3b05ec787acfb4b17bed37db5719fa777834f/bwrap.xml#L173) | `--disable-userns` 阻止后代创建 user namespace 和重新安排挂载树；配合 `--assert-userns-disabled`。选项或实际 namespace 能力不可用时拒绝严格启动。 |
| anthropic-experimental/sandbox-runtime | 包版本 `0.0.83`，提交 `0bbebed0aa9a8e628dc4df8bc30293550a1988eb`；[namespace 说明](https://github.com/anthropic-experimental/sandbox-runtime/blob/0bbebed0aa9a8e628dc4df8bc30293550a1988eb/src/sandbox/linux-sandbox-utils.ts#L1666)、[socket 过滤](https://github.com/anthropic-experimental/sandbox-runtime/blob/0bbebed0aa9a8e628dc4df8bc30293550a1988eb/vendor/seccomp-src/seccomp-unix-block.c#L205) | 关闭嵌套 user namespace；按 socket family 参数的低 32 位判断，避免寄存器高位影响过滤；封闭 VSOCK 与 io_uring 的旁路。这里采纳规则，没有引入整个 SRT 运行时。 |
| openai/codex | `rust-v0.170.0`，提交 `29eb79e9866b3262a777fdf8195c1b9e63684c91`；[Linux README](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/linux-sandbox/README.md)、[seccomp](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/linux-sandbox/src/seccomp.rs#L190) | 文件视图与 syscall 约束组合；完整后代继承约束；网络规则不能只处理普通 `socket()` 而保留 io_uring 旁路。当前 Java 过滤器按自身较窄的策略生成 cBPF。 |
| git/git | `v2.47.3`，提交 `a52a24e03c8c711f1d5e252fba78f9276908129b`；[gitdir 布局](https://github.com/git/git/blob/a52a24e03c8c711f1d5e252fba78f9276908129b/Documentation/gitrepository-layout.txt#L25)、[alternates](https://github.com/git/git/blob/a52a24e03c8c711f1d5e252fba78f9276908129b/Documentation/gitrepository-layout.txt#L80)、[commondir](https://github.com/git/git/blob/a52a24e03c8c711f1d5e252fba78f9276908129b/Documentation/gitrepository-layout.txt#L261) | `.git` 可以是指针文件，worktree 还可依赖外部公共目录；对象 alternates 的相对路径以对象目录为基准。必须在 prepare 时检查这些实际依赖，不能仅检查工作树文件。 |
| systemd/systemd | `v259`，提交 `9ca433482f2281d71718718705ca8cd3bf562ad6`；[服务账号](https://github.com/systemd/systemd/blob/9ca433482f2281d71718718705ca8cd3bf562ad6/units/systemd-resolved.service.in#L46)、[运行目录所有权](https://github.com/systemd/systemd/blob/9ca433482f2281d71718718705ca8cd3bf562ad6/src/resolve/resolved.c#L46)、[DNS 文件写入](https://github.com/systemd/systemd/blob/9ca433482f2281d71718718705ca8cd3bf562ad6/src/resolve/resolved-resolv-conf.c#L347)、[标准链接](https://github.com/systemd/systemd/blob/9ca433482f2281d71718718705ca8cd3bf562ad6/tmpfiles.d/systemd-resolve.conf#L10) | 标准 resolver 文件和运行目录由 `systemd-resolve` 服务账号维护；仅针对两个固定 DNS 路径定义专用账号信任，不扩大执行文件信任范围。 |

## 当前策略与审查修正

实现位于 `LinuxBubblewrapSandboxPolicy`、`LinuxSeccompFilter` 和 `NativeSandboxPolicy.platform`。Linux 的直接调用与独立 Worker 共用平台选择。严格请求在 CLI 帮助探测、任务提示交付和预算承诺之前，完成工作区检查及真实 bwrap/seccomp 预检；失败返回 `UNAVAILABLE`。本轮审查补充了嵌套 user namespace 禁用、socket family 允许清单及 Git 外部元数据依赖检查。

文件视图从空根开始，只读挂载选定系统运行目录及必要配置；工作区按请求只读或整目录可写。挂载树根最后以非递归方式设为只读，同时保留各子挂载的写入权限。新 `/proc`、最小 `/dev`、私有 `/tmp` 和新 HOME 用于运行需要，不挂入宿主用户 HOME 或核心控制目录。环境完全清空后只设置固定变量，XDG 路径也转入新 HOME。新 HOME 和 `/tmp` 在只读工作区模式下仍可写；因此同名路径的私有影子写入不等于宿主文件写入，实际边界还须核对宿主原字节。

`DENY` 创建独立 network namespace，并拒绝普通 `socket()`；`ALLOW` 复用宿主网络，只允许 IPv4/IPv6 普通 socket，因此包含宿主 loopback。两种模式都拒绝普通 Unix、VSOCK、TIPC 等 socket，仅允许 `AF_UNIX` 且 `(type & 0xF) == SOCK_STREAM` 的私有 socketpair，保留 NONBLOCK/CLOEXEC 标志。过滤器还拒绝 io_uring、宿主进程/句柄访问及 namespace 切换相关 syscall；未知架构与不匹配 ABI 不获得降级执行。

本轮独立实验推翻了“所有 Unix socketpair 都是私有通道”的初始判断：在共享网络命名空间中，数据报 pair 通过 `connect` 后发送、`sendto` 和 `sendmsg` 均能把消息交给宿主抽象监听器。流式 pair 在这些调用及断连、shutdown、关闭对端等状态变化实验中未能重定向。最终策略只保留流式类型，拒绝数据报与顺序包；实际宿主监听器回归同时检查创建结果、消息接收及流式私有通信，旧过滤器负对照和修复后报告单独保存。

SRT 的[受控网络实现](https://github.com/anthropic-experimental/sandbox-runtime/blob/0bbebed0aa9a8e628dc4df8bc30293550a1988eb/README.md#how-it-works)和 Codex 的[代理模式](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/linux-sandbox/README.md#L93)保留网络 namespace，再通过明确的代理通道开放访问。当前实现提供完整 IP 网络的二值选择，没有该代理层，也没有域名、端点或管理端口白名单；不能据此宣称允许联网时仍隔离宿主 loopback 服务。

## 信任范围与保留缺口

保证范围是单用户、可信本地仓库下，由工作台创建的自包含独立 clone。内核、管理员维护的 bwrap/shell/系统运行目录和工作台自身属于信任根；挂入的系统目录整体属于允许读取视图。宿主管理员或同 UID 的不可信进程并发改写目录不在当前保证范围内。

首次 Ubuntu 26.04 CI 的 DENY 通过、ALLOW 在准备时拒绝。后续只读 runner 元数据确认 `/etc/resolv.conf` 指向 `/run/systemd/resolve/stub-resolv.conf`，文件与 `resolve` 目录由专用服务账号拥有。实现因此为这两个固定 resolver 文件允许专用 `systemd-resolve` 所有权，同时保持祖先 root、禁止组/其他用户写入及工作台账号不可等于服务账号；可执行路径与其他系统配置没有放宽。首次失败、诊断和修复复验分别保留于 QA 回执，未修改 runner 的 DNS 所有权或 user namespace 设置。

准备阶段拒绝特殊文件、共享硬链接，以及 `.git`、`gitdir`、`commondir` 和递归 `objects/info/alternates` 的外部依赖。真正依赖外部管理目录的 Git worktree 和 shared clone 会被拒绝；不能保证任意 bare 仓库或自定义 `core.worktree` 配置具有完整 Git 可用性。quoted alternates 等未支持元数据形式也会拒绝，不能静默绕过检查。

当前仍使用规范路径与路径参数挂载，尚未通过文件描述符固定目录身份。Codex 的 [fd_mount.rs](https://github.com/openai/codex/blob/29eb79e9866b3262a777fdf8195c1b9e63684c91/codex-rs/linux-sandbox/src/fd_mount.rs#L12)核验描述符和目标 inode 后关闭宿主描述符，提供后续加强依据；本轮没有实现这种固定。因此，启动前 inventory 和预检不证明能抵御不可信宿主并发路径替换。CPU、内存、磁盘等 OS 资源配额与抗资源耗尽也不属于本策略目标。

新 HOME 与环境清理会影响个人安装路径、OAuth/订阅登录和用户配置；环境清理本身也不证明 CLI 已禁用全部项目配置。真实官方 Codex/Claude 登录、任务限定 MCP 注入和混合阶段交付仍按[原生混合阶段研究](./native-mixed-stages-2026-11-19.md)继续实施。Windows/macOS 严格策略保持 `UNAVAILABLE`。

## 验证入口

Linux 必验入口及前置条件见[原生沙箱协议](../native-sandbox-contract.md#验证入口)。`LinuxBubblewrapSandboxPolicyTest` 检查真实文件、网络、环境、namespace 与 Git 依赖；`LinuxNativeSandboxWorkerTest` 检查原 Worker 调用、帮助/任务两次启动、请求预算、持久回执和 UNKNOWN 边界。确定性 CLI 夹具用于验证执行链，不替代官方 CLI 登录或混合阶段兼容性证据。
