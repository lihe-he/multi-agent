# 原生 CLI 操作系统沙箱协议

日期：2026-11-26。当前 Linux 策略编号为 `linux-bwrap-workspace-v1`；范围与固定开源依据见[Linux 沙箱研究](./research/linux-native-sandbox-2026-11-26.md)。本文记录运行契约，实际测试结果以本轮独立 QA 回执和[实施记录](./implementation-status.md)为准。

## 请求与执行门禁

原生 `CODEX`、`CLAUDE` 路由支持 `requireOsSandbox:boolean` 和 `osSandboxNetwork:'DENY'|'ALLOW'`，网络默认 `DENY`。API 路由不能请求此策略。`requireOsSandbox:true` 时，`NativeSandboxPolicy` 必须返回包含实际包装命令和策略编号的 `ENFORCED` 决策；`UNAVAILABLE`、不完整决策或原命令不变均在提示派发和预算承诺前拒绝，不自动改为普通启动。

Linux 直接运行时与独立 Worker 共用 `NativeSandboxPolicy.platform`。策略检查真实工作区、系统运行环境与 cBPF 后执行有界的 bwrap 预检。CLI 帮助探测也必须在该工作区的只读沙箱中完成；设置页没有实际任务工作区，因此严格路由的设置探测不启动 CLI。未启用严格选项的原生路由沿用原运行路径，CLI 自带权限参数不构成本协议的系统强制证明。

执行结果中的 `osSandbox` 含 `state`、`policyId`、`workspace`、`fileAccess`、`networkAccess`。它记录本次采用的策略，不能单独证明模型成功、远端效果或进程树已经停止；这些结论继续使用[原生终态规则](./research/native-cli-receipts-2026-11-21.md)、模型请求账本和[Worker 进程协议](./worker-process-contract.md)。每次原生会话仍使用 `CLI_SESSION` 软预算，内部请求量与费用保持未知。

## 信任与工作区范围

当前适用于单用户、可信本地仓库下，由工作台创建的自包含独立 clone。内核、工作台、管理员维护的 `/usr/bin/bwrap`、shell、系统程序和运行库属于信任根。系统执行器及检查路径要求 root 所有且不可由非管理员写入；挂载的系统目录整体可读，不能把该范围描述为只允许读取项目文件。

工作区不得覆盖核心控制目录或保留系统挂载点。策略有界遍历工作区，拒绝设备、FIFO、Unix socket 等特殊文件与共享硬链接。发现 `.git` 标记时解析真实 `gitdir`、`commondir` 和递归 `objects/info/alternates`，依赖必须位于同一工作区；不支持或无法解析的元数据拒绝。依赖外部管理目录的 worktree、外部对象库和 shared clone 返回 `UNAVAILABLE`。

保证范围不扩展到任意 bare 仓库、自定义 `core.worktree` 或所有 Git 布局。当前尚无文件描述符固定挂载身份，规范路径检查与实际启动之间仍存在时间差；不可信宿主进程并发写入、目录替换和恶意同 UID 对手不属于当前威胁范围。OS 的 CPU、内存、磁盘等资源配额及抗资源耗尽不属于本策略目标。

## 文件、环境与后代

策略从空挂载树开始，只读挂入系统运行目录和必要的证书/运行配置；不挂载宿主用户 HOME 和核心控制目录。挂载树根以非递归方式设为只读，保留工作区及私有 HOME/tmp 子挂载的独立权限。私有 `/tmp` 中与宿主同名的路径可能被写入临时影子文件，该写入不触及宿主文件。工作区文件权限只有两档：

| `fileAccess` | 持久文件行为 |
| --- | --- |
| `READ_ONLY` | 整个工作区以只读 bind mount 暴露，拒绝改写原文件或创建工作区文件。 |
| `WORKSPACE_WRITE` | 整个工作区可写；不提供单文件或细粒度 glob 权限。 |

进程继承环境被清空后，仅设置策略的固定变量。`HOME=/workbench-home`、`TMPDIR=/tmp` 和 XDG 路径位于每次启动新建的私有 tmpfs；只读工作区模式仍允许这些临时目录写入。宿主凭据、代理变量和运行时注入参数不自动继承。新 HOME 不等于已经证明 CLI 的所有项目配置、hook 或插件发现均已禁用，既有 CLI 配置能力检查继续独立执行。

user、PID、IPC、UTS 和 mount namespace、全能力丢弃、`--new-session`、`--die-with-parent` 共同约束后代。`--disable-userns` 与 `--assert-userns-disabled` 禁止再创建 user namespace；seccomp 另拒绝 `unshare`、`setns` 及宿主进程/句柄访问等 syscall。已有取消、超时、原进程观察和 UNKNOWN 收尾规则继续适用，不以包装器退出代替停止证明。

## 网络

| `networkAccess` | 实际边界 |
| --- | --- |
| `DENY` | 创建独立 network namespace，拒绝所有普通 `socket()` 创建；保留私有 `AF_UNIX`、`SOCK_STREAM` socketpair。 |
| `ALLOW` | 复用宿主网络，只允许普通 `AF_INET`、`AF_INET6` socket；包含宿主 loopback。没有端点、域名或管理端口白名单。 |

两种模式均拒绝普通 Unix socket、VSOCK、TIPC 及其他未允许 family；`socketpair` 仅允许 `AF_UNIX` 且 `(type & 0xF) == SOCK_STREAM`，保留 NONBLOCK/CLOEXEC 标志，拒绝数据报和顺序包类型。数据报 pair 可通过 `connect`、`sendto` 或 `sendmsg` 改向宿主抽象地址，因此不能视为私有通道。io_uring 同时禁用，防止绕过 socket syscall 规则。family 与 type 比较使用参数低 32 位；当前仅支持 x86_64/aarch64，未知架构或不匹配 ABI 不会放宽过滤。

ALLOW 模式还挂入必要的 DNS/hosts 配置。这不是受控代理网络；MCP 桥接和供应商端点授权仍须另行设计。DENY 模式不能承担需要外部模型服务联网的正常 CLI 调用。

## 验证入口

在允许非特权 user namespace 的 Linux 上准备 JDK 21、Maven、`/usr/bin/bwrap`、`/usr/bin/python3` 与 `/usr/bin/git`，从仓库根运行：

```sh
mvn -B -ntp -f core/pom.xml -Dworkbench.require-linux-sandbox=true -Dtest=LinuxBubblewrapSandboxPolicyTest,LinuxNativeSandboxWorkerTest test
```

必验模式下依赖不足、namespace/seccomp 不可用或策略拒绝应使测试失败，不能条件跳过后宣称通过。现有 Linux CI 使用该入口。其两类证据为：

- `LinuxBubblewrapSandboxPolicyTest`：真实文件读写、绝对/相对/符号链接越界、后代继承、网络和宿主 abstract Unix listener、raw syscall 高位、私有流式 socketpair 及数据报/顺序包拒绝、嵌套 user namespace、环境/HOME、特殊文件/硬链接及 Git 依赖。
- `LinuxNativeSandboxWorkerTest`：原独立 Worker/领取内的帮助与实际命令隔离、只读/写入与网络组合、环境边界、单次会话预算和持久回执、缺终态保留 UNKNOWN，以及拒绝启动时零提示交付和零模型预算承诺。

实际 bwrap/内核版本、目标提交、命令和原始报告必须随 QA 回执记录；源码参数断言和自制 CLI 协议夹具不能替代真实官方 CLI 验收。官方 Codex/Claude 登录、认证传递、任务限定 MCP 和混合阶段交付仍未完成。Windows/macOS 当前没有严格平台实现，请求严格策略时保持 `UNAVAILABLE`。
