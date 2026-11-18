# 进程身份快照研究（2026-11-18）

OpenJDK 21.0.13+9 固定到提交 `ba94eace682733f66e623c089bcafcf4a1b8e39c`。[来源清单](./process-identities-sources-2026-11-18.json)保存准确 URL、原文件位置与 SHA-256。本轮重新核验此前 Windows/OpenJDK 和 System Informer 文件，并补读同一 OpenJDK 提交的 Java、Unix 与 macOS 实现。

`ProcessHandle` 将后代列表和进程信息定义为异步变化进程的快照，`Info.startInstant()` 返回 Optional。macOS 的 `os_getParentPidAndTimings` 单独通过 `sysctl` 查询 PID，只有非空且 PID 匹配的记录才填入出生时间。Unix 共用的 `Info_info0` 先将时间设为 -1，仅在父 PID 查询成功后发布。因此，先前枚举到后代并不保证随后查询信息时仍能读到出生时间。

这支持 run44225016803 的观察间竞态解释：相同后代先有出生时间、后为空，而原进程、后代和输出清理均已完成。它不证明每次空值的成因，也不消除 PID 重用的歧义。实现使用 PID 索引查找已记录的可靠身份，同时保留所有非空 PID/出生组合供独立停止核验。首次未知 PID 仍记录枚举失败；之后得到可靠身份时移除空占位，但不抹去此前失败标记。

Windows OpenJDK 的 Toolhelp 枚举和出生时间读取同样分开进行。System Informer 的 `SYSTEM_PROCESS_INFORMATION` 声明包含创建时间，并注明继承的父 PID 可能指向被复用的身份。这两份资料仅作为 Windows 诊断参考，不外推为 macOS 实现依据。未复制上游实现到产品代码。

`34fff04` 的 macOS CI 中，`NativePtySessionTest` 11 项通过；核心另外一项失败发生在 `TerminalEngineTest` 的实时输出重复读取断言。两次返回的原字节、摘要和段身份相同，只有 `publishedEnd` 从 234 增为 242。`TerminalLedger.outputPage` 从每次读取时的 `outputEnd` 生成该字段，因此测试应验证其单调不减，其他字段仍要求完全一致。原失败与后续定向复验保存在[输出读取回执](../qa/alpha22-terminal-read-regression-win32.json)。
