# 预览页面元数据与打开操作竞争

日期：2026-11-18。基线 `c60051bcbaf7799d58abe0de64a0dfa83ad6f1b6`，跟进 F05。

## 固定开源来源

沿用已获取的 OpenCode [pane-machine.ts](https://github.com/anomalyco/opencode/blob/59ed43f0b8da891f03a7333d540a7b0c428049ea/packages/gui-plugins/src/browser/pane-machine.ts)，固定提交 `59ed43f0b8da891f03a7333d540a7b0c428049ea`。该状态机将页面发布与显式请求分开，检查页面 attempt，并抑制重复状态报告。本轮重新核对文件 SHA-256 `2042abd062ccdcac96cc1bf23ca3049a0eff3de6cf3592a6b9c7325b60c90989` 和 Git blob 均与原来源回执一致。

Electron 39.2.7 的 [WebContents 事件文档](https://github.com/electron/electron/blob/4d18062d0f0ca34c455bc7ec032dd7959a0365b6/docs/api/web-contents.md)说明 `page-title-updated` 可在导航期间触发，`did-stop-loading` 表示加载停止；不能把加载停止视为所有后续元数据保存已完成。固定文件 SHA-256 `d84f5f8d9c8ef6b5975f4221c161cadbc6c7366765d1ffddfb6afed27180c917` 与原回执/Git blob 已核对。未执行或复制上游实现，未新增运行依赖。

## 原始证据与可复现边界

`8a7c797` 的 [run44249855968](https://github.com/lihe-he/multi-agent/actions/runs/44249855968) Windows 发行预览前三组成功，第二标签等待实际 URL 超过原 20 秒。取回的原生视图仍为 `/login`，不可见；截图显示新标签输入保留，HTTP 夹具没有收到 `/other`。选取 ZIP 条目已核对大小、CRC32 与条目 SHA-256；263 MB 整包未完整下载，不能声称整包摘要已核验。原现场没有保存 IPC 失败文本，因此这些条目本身不能证明唯一根因。

基线宿主中，页面元数据与用户动作共用串行队列；元数据先保存后推进 `version`，已发出的打开动作仍持有前一 UI 版本，因而被严格相等检查拒绝。新增测试明确将 `pageState` 与 `open` 依次入队，旧实现稳定拒绝，新实现完成一次导航。另一用例证明相同元数据在旧实现仍推进版本。以 `git show` 在内存加载原宿主的负对照中，五项新增用例三失败/两通过；原实现与文件未被替换。

此外，UI 的成功配置轮询原本无条件清除操作错误。生产浏览器用例使打开失败，再等成功 GET，验证错误和输入继续保留，且没有自动第二次打开；用户明确重提后才成功。未增加导航/初始化超时，未增加自动重试。

## 修改与验证

宿主只记忆自身连续成功元数据写入之前的版本，最多 64 个。任何外部版本变化、显式标签选择/打开、暂停或身份变化均清空；打开仍重新授权并使用当前整份配置的 CAS 保存。页面标题或地址没有变化时只发布配置。核心版本协议与端口/代次检查保持，旧未知外部写入仍拒绝。

Windows 26 项桌面策略/宿主测试通过；生产 Web 构建及完整 230 项 UI 通过，零重试/跳过/失败，源码/资产摘要保持，44 张历史截图恢复原字节。实际开发主程序与包内 JRE/asar 各十组通过，新增场景实际修改原生标题，再以此前版本打开带 query/fragment 的地址，核对当前标题保留、服务只收到一次回调。发行程序 PATH 无外部 Node/Java，三次完整退出核对全部 12 条原身份观察。当前轮未修改 Java 文件。

首次发行运行十组功能检查通过后，单次停止探针仍观察核心 PID 存在但出生不可读，原运行记为失败。随后独立探针确认核心/Worker 已停止；工具改为在 20 秒内观察全部原身份并保存每轮结果，最终同一发行物通过，原失败保持。安装器为 157,725,720 字节，SHA-256 `41b6d18bf1a80c6f280e9129f9848fc9a376f71eb52400ba9c9f7679d20af0e5`，NotSigned；本轮未重测安装/卸载。准确输入与回执见[验证记录](../qa/alpha22-preview-metadata-race-win32.json)。

`c60051b` 的 [run44257141250](https://github.com/lihe-he/multi-agent/actions/runs/44257141250) 已终态失败：Ubuntu 整条成功，Windows 核心 563 项/8 条跳过及协议/UI 通过后在开发桌面的第二标签等待复现原超时，macOS 核心 563 项/11 条跳过通过，UI 228/229。macOS 原 trace 的配置 HTTP 200、无 `/api/state` 请求、界面 `signal timed out` 继续保留；完整 artifact 79,906,333 字节及 GitHub digest 已核验，未据此直接修改初始化期限。修复提交后的三端结果需重新验收。
