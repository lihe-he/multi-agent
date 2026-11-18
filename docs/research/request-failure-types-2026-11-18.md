# API 异常类型链研究

Windows run44244487383 的协议夹具在 analysis 阶段承诺一次模型预算后，6 毫秒内保存 `ExecutionException` 与 UNKNOWN。夹具未收到对应请求；原回执没有保存 cause，不能据此判断是连接、TLS、EOF 还是其他传输失败。

检查 OpenJDK 21.0.13+9 固定提交 `ba94eace682733f66e623c089bcafcf4a1b8e39c`：

- [ExecutionException.java](https://github.com/openjdk/jdk21u/blob/ba94eace682733f66e623c089bcafcf4a1b8e39c/src/java.base/share/classes/java/util/concurrent/ExecutionException.java) 说明异常可通过 `getCause()` 检查原始原因。保存文件 SHA-256：`901acedcdfb75eed3427c85e7ca54ad0c4364322f2ab6abb6b48ddf61f1a0f05`。
- [CompletableFuture.java](https://github.com/openjdk/jdk21u/blob/ba94eace682733f66e623c089bcafcf4a1b8e39c/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java) 的 `reportGet` 将异常结果包装为 `ExecutionException`，带超时和不带超时的 `get` 均调用它。保存文件 SHA-256：`3d350ceb3bc6b5d22edd71065693d742480f01a5640a681d8413cc7c82185db4`。

源码保存在 `.runtime/alpha22-http-diagnostics-research/`，仅用作诊断参考，未复制上游实现。第一次并行获取 ExecutionException 失败，单独重新获取成功；只引用成功获取的完整字节。

alpha.22 为 API 失败回执补充有界异常类型链，保留原有 errorType、UNKNOWN、预算和不重放规则。类型链经过 Worker 回调、核心脱敏、SQLite 持久保存与字面检索，并在 UI 中以折叠纯文本显示。类型链不能证明远端未执行，也不能替代故障原始现场。验证包括真实 HTTP 断连、独立 Worker 传输、重开保存、类型链循环/深度边界以及不读取异常消息；见[验证回执](../qa/alpha22-request-diagnostics-win32.json)。
