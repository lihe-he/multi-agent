# Electron 打包资产下载与核验

日期：2026-11-09。alpha.11 下载修订提交 `0bb421f82b5e888136881cef2199464313de1db8` 的 [run 42468924458](https://github.com/lihe-he/multi-agent/actions/runs/42468924458) 已终态 failure：macOS 全部成功，Windows 和 Ubuntu 在原 Electron 安装成功后分别遇到 NSIS resources、AppImage 固定资产的 HTTP 504，停止在打包步骤。原日志、报告和 ZIP 保留，后续打包运行不计作已执行。原 Electron 安装回退研究见[先前记录](./electron-download-2026-11-08.md)。

## 固定开源来源

[electron-builder](https://github.com/electron-userland/electron-builder) 26.0.12，MIT，标签解析为提交 `44c5265a9260ca6f319f111b5a2ca7e50b0a7cd6`。安装的 electron-builder/app-builder-lib 均为该固定版本，app-builder-bin 为锁文件中的 `5.0.0-alpha.12`；实现先检查这些版本及原校验值，不在版本变化后沿用旧资产。

| 来源 | 固定 blob 与观察 |
| --- | --- |
| [binDownload.ts](https://github.com/electron-userland/electron-builder/blob/44c5265a9260ca6f319f111b5a2ca7e50b0a7cd6/packages/app-builder-lib/src/binDownload.ts) | `9ad96d7284e7084e2ee0ad122b635c87734b5f44`；调用原 app-builder 下载/缓存入口 |
| [nsisUtil.ts](https://github.com/electron-userland/electron-builder/blob/44c5265a9260ca6f319f111b5a2ca7e50b0a7cd6/packages/app-builder-lib/src/targets/nsis/nsisUtil.ts) | `348cb1de816d45ed1f300bb993bec96908be3f8b`；固定 NSIS 3.0.4.1 的原 SHA-512 |
| [NsisTarget.ts](https://github.com/electron-userland/electron-builder/blob/44c5265a9260ca6f319f111b5a2ca7e50b0a7cd6/packages/app-builder-lib/src/targets/nsis/NsisTarget.ts) | `b53650e82a71f67bad10dbf9abec283faefb89ca`；固定 nsis-resources 3.4.1 的原 SHA-512 |
| [app-builder tool.go](https://github.com/develar/app-builder/blob/f9101753dd2b93b857864d4051baeb6d8856dd64/pkg/linuxTools/tool.go) | `0e5c5c88df183f7a5d29d73b8e0224dbba069d94`；固定 AppImage 12.0.1 的 SHA-512，安装的实际 app-builder 二进制同时包含该原值 |
| [artifactDownloader.go](https://github.com/develar/app-builder/blob/7004925f95d8f034fc88d7e782c9aa7583debb8e/pkg/download/artifactDownloader.go) | `bf1e82bf3ae043c8d384984b55eab45dfc301b69`；缓存父目录取资产名首个连字符之前的前缀，存在完整缓存时原入口返回该目录 |

app-builder 为 MIT。以上五份本机源码均核对 `git hash-object` 与官方 contents blob，一并保留于 `.runtime/research-builder-assets-2026-11-08/`。仅参考原固定值和缓存约定，未复制上游实现或更换打包器。

## 实现边界

`tools/package-desktop.mjs` 先执行锁定的原 electron-builder 并保留独立日志。只有当前平台固定资产 URL 同行出现 HTTP 502/503/504 或明确临时网络错误，才进入一次回退；配置、权限、校验、解压及其他失败直接保留。真实失败日志的 Windows/Linux 两条识别已核对。

回退通过官方 GitHub release API 核对 tag、唯一资产名、固定 ID、大小与官方地址。API 授权只发送到初始 API 主机，重定向只允许官方 HTTPS 发布主机，跨重定向不带授权。下载按固定大小有界流式保存，核对原包/二进制中的 SHA-512；上游有 metadata SHA-256 时另行核对。三个旧资产没有 metadata digest，记录缺失，不推测补造。

字节核验成功后才由原 `7zip-bin` 解压到专属临时目录，随后发布到新的本项目缓存；既有缓存和失败文件不覆盖。只为一次重试设置 `ELECTRON_BUILDER_CACHE`，仍由原打包器生成发行物，子打包/解压进程不继承 GitHub API 令牌。macOS 当前没有已审查的回退资产，其他 URL 不扩大回退范围。

## 本机证据

下载守卫 8 项、原 Electron 安装守卫 6 项与连接策略 3 项全部通过。覆盖固定元数据/包版本漂移、错误字节不解压、不支持重定向、凭据剥离、实际流式大小及校验、只重试一次和无关失败不重试。

| 资产 | 官方资产 ID / 字节数 | 本机实际 SHA-256 | 本轮结果 |
| --- | --- | --- | --- |
| nsis-3.0.4.1 | 16428442 / 1287512 | `9877df902530f96357d13a7a31ae2b9df67f48b11ffc9a1700a7c961574ec5fa` | 官方 API 下载、原 SHA-512、原 7zip 解压均通过 |
| nsis-resources-3.4.1 | 13752602 / 730800 | `593a9a92ef958321293ac6a2ee61e64bf1bd543142a5bd6b3d310709cc924103` | 官方 API 下载、原 SHA-512、原 7zip 解压均通过 |
| appimage-12.0.1 | 13613639 / 1573447 | `d12ff7eb8f1d1ec4652ca5237a7fbdca33acc0c758045636feca62dc6ecb8ec4` | 官方 API 下载和原 SHA-512 通过；Windows 解压在两个 Linux 符号链接处权限失败，完整失败文件保留 |

两个 Windows 缓存还由安装的实际 app-builder `download-artifact` 消费，在测试代理不可用时仍返回原专属缓存路径。该项证明原缓存入口可读取验证后的资产，不代替完整发行物构建。Linux 原生解压与发行物必须由对应 Linux CI 验证；本机 Windows 失败不计作 Linux 通过。准确结果与缓存路径见[资产记录](../qa/alpha12-desktop-assets-win32.json)。

最终 alpha.12 Jar/Web 的打包、内置 Java 启动和三端 CI 继续按准确提交验收；该页的资产核验不能替代这些后续检查。
