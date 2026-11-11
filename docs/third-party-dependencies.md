# 第三方依赖与固定来源

本项目业务代码采用 [MIT](../LICENSE)。本页记录 alpha.14 新增的 JNA 依赖及所用 JDK 能力，完整构建依赖由 [Maven POM](../core/pom.xml)、[根锁文件](../package-lock.json)和 [Web 锁文件](../web/package-lock.json)固定。

## JNA 5.17.0

`net.java.dev.jna:jna:5.17.0` 与 `net.java.dev.jna:jna-platform:5.17.0` 用于 Windows 写入锁文件的原生文件身份，以及只读系统进程快照的动态调用。选择 JNA 提供的 **Apache-2.0** 许可路径，依赖 Jar 保持原字节；未复制或修改上游 Java 实现。

固定源码来自 [java-native-access/jna 5.17.0](https://github.com/java-native-access/jna/tree/695ae749e7bfd92f88324147c5d96b7129efec3e)，提交 `695ae749e7bfd92f88324147c5d96b7129efec3e`。[上游 LICENSE](https://github.com/java-native-access/jna/blob/695ae749e7bfd92f88324147c5d96b7129efec3e/LICENSE)的 contents blob、原字节及 SHA-256 已核对。

| Maven 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| jna-5.17.0.jar | 2002589 | `b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0` |
| jna-platform-5.17.0.jar | 1378617 | `b7e3d46c87bad2eb409b0e704916bcd81206168e357312dfddd0e253679cd9e0` |

实际 Maven classpath 使用上述摘要的依赖。两个原 Jar 均包含 `META-INF/AL2.0` 和 `META-INF/LICENSE`；Spring Boot 核心包以未修改的嵌套 Jar 携带上游许可文件，桌面发行物继续携带该核心 Jar。具体最终包与实际加载核验在[实施记录](./implementation-status.md)和对应产物报告中追加。

原始下载、来源与许可核验在 `.runtime/research-workspace-file-identity-2026-11-10-attempt2/sources-verified.json`。该清单保存下载缓存路径；构建实际使用的 Maven 缓存路径另以 `.runtime/alpha14-jna-maven-classpath.json` 为准，两处产物字节摘要相同。

## Java 21 的锁文件打开模式

Windows 使用 `com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE`，让原 FileChannel 持有期间拒绝删除/改名访问。该能力来自 `jdk.unsupported`，属于 JDK 内部 API，Java 21 编译产生内部 API 警告。内置 jlink 运行时的原模块集合已经包含 `jdk.unsupported`；后续 JDK 升级仍需重新核验该模式和实际文件锁回归。

本机固定 Temurin 21.0.13+9 的 release SOURCE 对应 [adoptium/jdk21u](https://github.com/adoptium/jdk21u/tree/2ab10ca20c191701c3a9f3153bfc6e93d63714c1) 提交 `2ab10ca20c191701c3a9f3153bfc6e93d63714c1`，GPL-2.0 with Classpath Exception；仅核验 API 与打开模式，没有复制上游实现。内置运行时继续保留原 legal 文件。

同一固定提交的 [FileLock 文档](https://github.com/adoptium/jdk21u/blob/2ab10ca20c191701c3a9f3153bfc6e93d63714c1/src/java.base/share/classes/java/nio/channels/FileLock.java)明确说明：部分系统关闭同进程另一文件通道会释放原锁，建议每个文件使用唯一通道。本机 `src.zip` 的 FileLock 与固定源码字节相同。原生 [UnixFileDispatcherImpl.c](https://github.com/adoptium/jdk21u/blob/2ab10ca20c191701c3a9f3153bfc6e93d63714c1/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c)使用 fcntl 的 F_SETLK/F_SETLKW，blob `a24f0503e557955c074814de05ebea5507056624` 已按目录 API 与原字节核对。源码只作参考，没有复制进业务代码；来源、原错误路径的404和文档文本断言失败保存在 `.runtime/alpha14-posix-lock-research*/`。

`npm run test:bundled-writer` 直接使用实际发行目录的内置 Java，逐项核对准确核心、原 JNA Jar 和许可摘要，并验证实际文件身份、授予、借用和释放。Windows 同时检查系统进程快照的本 JVM 出生。完整包内运行结果以[实施记录](./implementation-status.md)与平台报告为准。

## Windows 只读进程快照参考

Java 无法读取当前 PID 出生时间时，核心调用 `ntdll` 的 `NtQuerySystemInformation(SystemProcessInformation)`，只补充出生元数据。固定布局参考来自 [Wine 10.0 的 winternl.h](https://github.com/wine-mirror/wine/blob/b073859675060c9211fcbccfd90e4e87520dc2c2/include/winternl.h)，提交 `b073859675060c9211fcbccfd90e4e87520dc2c2`，该文件 blob 为 `0bae9fbf3859bf72bd05f331cb460acbb0563a4f`。Wine 为 LGPL-2.1；本轮仅参考 API 字段布局，没有复制或链接 Wine 实现，也未将其加入发行物。

[Microsoft 接口文档](https://learn.microsoft.com/en-us/windows/win32/api/winternl/nf-winternl-ntquerysysteminformation)说明该接口和返回结构可能变化。核心限制缓冲与增长次数，并核验长度、链偏移、指针宽度、重复 PID 和本 JVM 的实际出生；核验失败继续未知。发送停止信号仍检查原 Java 句柄的出生，不凭系统快照停止进程。独立验收探针通过 Windows CIM 读取出生信息，与核心解析分别核对。

原固定头文件、许可与 Microsoft 页面及其摘要保存在 `.runtime/research-process-snapshot-2026-11-10/`。本机前后稳定的527条 CIM 出生记录与原生快照一致；当时没有可用的受限候选，受限专项对照未执行，原失败和条件跳过见[研究记录](./research/workspace-writes-2026-11-10.md)。

## Git 参考

Git v2.51.1 提交 `81f86aacc4eb74cdb9c2c8082d36d2070c666045` 的 lockfile API 与 worktree 文档用于理解描述符、原子意图及注册现场的语义。Git 为用户环境中的外部工具；本轮未引入或复制 GPL Git 实现。来源和实现取舍见[根写入保护研究](./research/workspace-writes-2026-11-10.md)与[Git worktree 研究](./research/git-workspaces-2026-11-09.md)。
