# Electron 固定版本下载回退

日期：2026-11-08。原 alpha.11 提交为 `3471160c0e9e6cfd86fc849f29a1a172b2c162ce`。[三次 CI](https://github.com/lihe-he/multi-agent/actions/runs/42464690847) 的 Windows 都在固定 `electron@39.2.7` 的 `node install.js` 下载处返回 HTTP 504；核心 181 项、协议 28 项与 UI 43 项通过，后续桌面步骤没有执行。macOS、Ubuntu 的完整成功、原日志 SHA 和每次失败分别保存在[准确提交记录](../qa/alpha11-ci-process-receipt.json)，不使用仓库里旧的 Windows QA 代替未执行的检查。

## 固定来源与选择

参照 [electron/get](https://github.com/electron/get)，MIT，npm `@electron/get@2.0.3`，固定提交 [`32211d4303a413ebe8dce3000266ec768d72b49e`](https://github.com/electron/get/commit/32211d4303a413ebe8dce3000266ec768d72b49e)。[Cache.ts](https://github.com/electron/get/blob/32211d4303a413ebe8dce3000266ec768d72b49e/src/Cache.ts) 按下载目录 URL 的 SHA-256 和文件名选择缓存位置；[index.ts](https://github.com/electron/get/blob/32211d4303a413ebe8dce3000266ec768d72b49e/src/index.ts) 读取缓存后仍验证校验和。锁文件已经固定该依赖，本轮没有添加或替换下载运行依赖。

本机 npm 包里的 `dist/cjs/Cache.js` SHA-256 为 `b65dcf5a2665d242b6a933aa3796ecaeb273cbfb354e961d2e76ca09ad019d91`。脚本直接调用原 Cache 类计算路径，不自行猜测缓存算法。Electron 原 `install.js` 使用 `electron_config_cache` 并读取自身 `checksums.json`；恢复缓存后仍由原 npm ci/install.js 核对、解压和安装相同版本。

官方 [v39.2.7 发布资产](https://github.com/electron/electron/releases/tag/v39.2.7) 中，`electron-v39.2.7-win32-x64.zip` 的 asset ID 为 `327945425`，大小 `136609029` 字节，官方 digest 与固定 npm 包内校验和都为 `3464537fa4be6b7b073f1c9b694ac2eb1f632d6ec36f6eeac9e00d8a279f188c`。元数据来源为 GitHub release API；二进制从原资产 API 获取，重定向只跟随官方 HTTPS 资产主机，API 授权不带到资产主机。

## 本轮行为与验证

`tools/install-desktop-deps.mjs` 先执行原 npm ci。只有 Electron 安装中的临时网络错误才进入回退；先用不运行脚本的 npm ci 恢复锁定的包元数据，避免读取失败安装遗留的半份依赖，随后复查版本、名称、大小、官方 digest 和包内 SHA-256，填入原缓存，并仅重试一次完整 npm ci。最终仍必须由原 install.js 安装实际二进制。其他依赖安装错误、校验失败和权限错误保持失败；原完整日志保存，后续桌面、恢复、打包与运行步骤继续作为必需检查。

定向守卫 **6 / 6** 通过，覆盖真实缓存路径、同一资产重复命中、两份校验和、错误字节拒绝、版本漂移、重定向授权隔离和回退分类。日志 `.runtime/alpha11-electron-cache-targeted.log`。生产预取入口使用本机代理实际下载上述 **136609029** 字节官方资产，双重 digest 一致，日志 `.runtime/alpha11-electron-official-prefetch.log`；随后原 npm ci/install.js 安装成功，`dist/version` 为 `39.2.7`，实际可执行文件存在，日志 `.runtime/alpha11-electron-original-install.log`。

本机先单独预取，再执行原安装；安装成功不声称在本机发生过原 HTTP 504。修订提交的三端 CI 将另行记录准确 SHA、失败/回退日志和实际打包运行结果，原三次失败不覆盖为成功。
