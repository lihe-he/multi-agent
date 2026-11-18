# 页面与网络恢复

日期：2026-11-18。基线 `b3216d36683e3870606c5e69c2011d0c064b82fd`，继续计划书 9.6、13.4 和 F01 的客户端恢复。

## 固定开源来源

参考 OpenCode 固定提交 `59ed43f0b8da891f03a7333d540a7b0c428049ea`：

| 文件 | SHA-256 | Git blob |
| --- | --- | --- |
| [client/src/solid/connection.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/client/src/solid/connection.ts) | `3b93c8ba83bd7b77bfb6901fa27d062f90e65007a8db0694731c0eab21c19d32` | `01e6b75b28c1c84d705c74b5f5f31063cc03d6c0` |
| [client/test/solid-connection.test.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/client/test/solid-connection.test.ts) | `567322215b348555d26d876e758945b098db4e7fbf10a565359e4a452d1ffe02` | `60219ed73ea3339cb461dad379fc35c577d4443e` |
| [app/e2e/stability/lifecycle.spec.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/app/e2e/stability/lifecycle.spec.ts) | `339012589c477f8fcddcd7a9ba64e504fee81faf58af2ded69bb626a01c54c53` | `6814e24caa151fa3cecca5796e67af460a7190cd` |

原字节 SHA-256 与固定完整树中的 Git blob 均已核对。参考点为离页停止传输、返回或联网时重新读取、每次读取的取消与期限并存，以及恢复时保全已有会话状态。没有复制或执行上游代码，没有增加运行依赖。上游前台可见性与手机锁屏处理也已识别，本轮尚未实现或验收该范围。

## 实现与边界

每轮定期读取拥有独立 AbortController 和计时器。`pagehide` 与 `offline` 取消旧读取并停止定时器，原缓存文档的 `pageshow.persisted` 或首次 `online` 立即重新读取，重复事件不创建额外读取。卸载删除监听器并取消当前读取。应用初始化/状态、打开的会话列表、消息/活动及预览配置使用同一生命周期管理。

消息和活动按原游标续读，按原编号去重；已加载的更早记录、会话选择和未提交笔记/名称仍保存在当前文档。明确失败的配置初始化保持停止，网络与页面事件不自动重读配置，也不清除原错误；仍需用户点击“重试连接”。本轮不重放任务或写操作。

所有原 `api()` 请求仍有三十秒期限，传入取消信号时将其与期限合并。配置读取继续使用五秒。浏览器网络状态不能代替回环核心的实际可达性，真实核心断网场景需单独核验；终端轮询、原生桌面休眠/前台返回、远程节点和长期负载继续实施。

## 可复现验收

`npm run test:ui:production` 使用冻结生产资产和独立 HTTP 服务，新增五项恢复场景：实际浏览器断网/联网；受控离页返回与迟到状态；初始化明确失败保留；会话游标/去重/草稿保全；持有消息响应的取消与期限。最后一项将三十秒 AbortSignal 期限映射为 120ms 以检查组合逻辑，不能视作一次真实三十秒计时验收。原配置五秒真实超时测试继续保留。

`npm run test:pages:web` 使用独立 HTTP 协议夹具、冻结生产资产和完整 Chromium/Edge，去掉 Playwright 默认关闭 BFCache 的参数；没有请求路由拦截。两次实际导航/后退必须恢复相同文档编号，并由浏览器报告 `pageshow.persisted === true`。返回后沿原消息/活动游标读取，并在原 1500ms 检查内显示新项目和记录，保留更早记录、当前会话及未提交内容。全部 API 请求必须为携带原授权的 GET。

夹具响应使用 `Cache-Control: private, no-cache` 以允许浏览器选择 BFCache。实际 Java API 的 `no-store` 没有改动；浏览器可拒绝缓存真实核心页面。这项实际浏览器缓存验收只证明符合缓存条件的 HTTP 夹具，不能替代真实核心/终端/操作系统休眠验收。三平台 CI 增加同一入口，独立保存生产资产、测试源文件、请求及成功/失败现场。

## 原失败与负对照

旧生产资产的受控恢复用例失败，返回后没有新状态读取。真实 BFCache 负对照也确认恢复原文档后，在原 1500ms 内仍显示“原项目”，而不是“返回后的项目1”；没有缓存未命中。这两份原失败保持。

首个真实浏览器工具运行被 `MainResourceHasCacheControlNoStore` 与 `JsNetworkRequestReceivedCacheControlNoStoreResource` 拒绝缓存；第二次在原 `goBack` 的 `load` 等待超过三十秒。两份原失败保持。工具将夹具缓存策略改为允许缓存，返回等待改为导航 `commit`；原相同文档、实际 persisted、1500ms 新状态和草稿/游标断言保留，实际产品缓存策略未改变。

本轮结果不会被用来推定此前 macOS 初始化超时的唯一根因；准确提交的三端 CI 继续分别记录。

## 本机结果

最终完整生产 UI 241/241，零失败、跳过与重试，源码/资产摘要保持；此前十一项定向成功单独保留。最终真实 BFCache 两次返回同一原文档，在原 1500ms 检查内显示新项目/消息/活动，本机实际观察为 49ms/54ms；消息游标 52→53、活动游标 70→71，33 次 API 全为授权 GET，零缓存未命中。

实际独立核心/代理/浏览器/附加 Electron 流程通过；重新打包的 Windows 包内 JRE/asar 原生预览十组通过，三次启动的 12 条原身份观察全部停止，无外部 Java/Node PATH。两次原生检查在独立非输入 Windows 测试桌面，44 张历史截图和三份原报告恢复原字节。

安装器 157,726,027 字节，SHA-256 `13ade78f99ee99d2fc6309f05149b8679340785a349f716e039956fe945938ec`，NotSigned，安装/卸载未重测。Java 无修改，沿用先前显式跳过测试的增量打包 Jar；21 项依赖/打包/版本/连接守卫及 workflow 解析通过。准确原输入、结果、负对照与未完成范围见[本机回执](../qa/alpha22-page-recovery-win32.json)。
