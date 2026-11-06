# 独立前端、核心与桌面连接

此入口实现前后端分离迁移的第一步：Web 静态产物、Java 核心和 Electron 可以独立启动；现阶段仍是单用户、本机执行。用户、远程设备、Worker 节点、PostgreSQL 和移动审批仍按架构审查的后续阶段实施，不把独立进程等同于完整远程服务。

## 单独构建和运行

```powershell
npm run build:core
npm run build:web
npm run core
```

`build:core` 仅需要 Java 21 和 Maven，不构建 Web 或安装 Electron。也可直接运行 `mvn -B -ntp -f core/pom.xml package`。把生成的 Jar 复制到独立目录后，执行 `java -jar <核心Jar>` 即可运行；该目录无需源码、npm、Web dist 或 Electron。

核心默认监听 `127.0.0.1:18765`。`WORKBENCH_PORT`、`WORKBENCH_DATA_DIR`、`WORKBENCH_TOKEN` 分别配置端口、独立数据目录和至少 24 字符的管理令牌。未提供令牌时，核心会在启动终端输出新令牌。访问 `/api/health` 也需要 `Authorization: Bearer <令牌>`。关闭 API 使用携带同一令牌的 `POST /api/shutdown`。同一数据目录只允许一个核心持有。

开发浏览器入口为 `npm run web`，其 `/api` 代理指向默认端口的本机核心。生产 Web 只部署 `web/dist/`，无需运行 Electron。可采用 [Nginx 本机配置](../deploy/nginx-local.conf)；该文件是部署样例，需按实际路径校验，并未声称已完成 Linux 生产部署验收。

## 运行时配置与令牌

构建生成 `web/dist/workbench-config.json`，内容只有：

```json
{ "apiBaseUrl": "" }
```

空值使用同源 `/api`；`/service` 使用同源 `/service/api`；本机独立端口可填写 `http://127.0.0.1:18765`。修改此文件不需要重新构建。相对前缀必须以一个 `/` 开始，禁止协议相对 URL、查询、片段和反斜杠；绝对地址只接受本机回环地址。前端现有 CSP 与本机鉴权边界继续生效。

公共 JSON 不能存放令牌、API 密钥或其他字段。前端已移除 `VITE_API_TOKEN` 注入入口；在连接对话框中输入的令牌只写当前浏览器会话存储。服务地址、代理配置与单用户本机管理令牌不是远程多用户认证。不要仅通过修改监听地址或放宽 CORS 宣称远程设备支持完成。

## 桌面连接已有服务

在启动 Electron 之前设置：

```powershell
$env:WORKBENCH_CORE_URL = 'http://127.0.0.1:18765'
$env:WORKBENCH_CORE_TOKEN = '<已有核心的管理令牌>'
npm start
```

该模式验证 `/api/health` 和协议版本，不创建 Java 子进程，也不要求桌面环境安装 Java。退出菜单显示“断开客户端并退出”；桌面退出不会暂停任务或关闭已有核心。未配置 `WORKBENCH_CORE_URL` 时，保持嵌入核心模式；显式配置连接失败会报错，不偷偷启动另一个核心。

取消配置可在当前 PowerShell 执行：

```powershell
Remove-Item Env:WORKBENCH_CORE_URL, Env:WORKBENCH_CORE_TOKEN -ErrorAction SilentlyContinue
```

## 可复现检查

`npm run test:standalone` 需要已构建核心/Web、根依赖、Web 测试依赖、Java 21 与 Playwright 浏览器。Linux 带桌面检查时需要显示服务，例如 `xvfb-run -a npm run test:standalone`。

检查会在独立空目录启动真实 Java 核心，使用生产 Web 静态产物及同源代理，验证浏览器无 Electron 桥接时的连接、设置和刷新。随后导入独立 Git 样例、批准任务，等待本机测试模型实际接到请求；关闭浏览器、启动并退出连接已有服务的 Electron 后，核对任务仍为 RUNNING 且请求只派发一次。最后显式取消测试任务并关闭测试服务。不会使用真实供应商或日常项目数据。

报告输出 `docs/qa/standalone-<platform>.json`。这不验证外部供应商、远程账号、全部业务 UI 流程或三端 Computer Use；完整验收范围继续见实施记录与计划书。
