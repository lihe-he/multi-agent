const { app, BrowserWindow, ipcMain, dialog, shell, Tray, Menu, nativeImage, powerMonitor } = require('electron');
const { spawn } = require('node:child_process');
const { randomBytes } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { attachedConnection, compatibleCoreHealth } = require('./connection.cjs');

if (process.env.WORKBENCH_USER_DATA) app.setPath('userData', path.resolve(process.env.WORKBENCH_USER_DATA));

let window, core, tray, quitting = false;
let connection;
let coreReady = false;
const root = app.getAppPath();

function trusted(event) {
  const url = event.senderFrame?.url || '';
  if (!window || event.sender !== window.webContents || !url.startsWith('file://')) throw new Error('未授权的窗口');
}

async function unusedPort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolve(port)); });
  });
}

function javaPath() {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  if (app.isPackaged) return path.join(process.resourcesPath, 'jre', 'bin', exe);
  const runtime = path.join(root, '.runtime');
  const home = process.env.WORKBENCH_JAVA_HOME || process.env.JAVA_HOME || (fs.existsSync(runtime) && fs.readdirSync(runtime).filter(x => x.startsWith('jdk-')).map(x => path.join(runtime, x))[0]);
  return home ? path.join(home, 'bin', exe) : exe;
}

async function startCore() {
  const attached = attachedConnection();
  if (attached) {
    const response = await fetch(`${attached.baseUrl}/api/health`, { headers: { Authorization: `Bearer ${attached.token}` }, signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error(`已有核心连接失败（${response.status}），请核对地址和令牌。`);
    const health = await response.json();
    if (!compatibleCoreHealth(health)) throw new Error('已有核心状态异常或协议版本不兼容。');
    connection = attached; coreReady = true;
    return;
  }
  const port = await unusedPort();
  connection = { baseUrl: `http://127.0.0.1:${port}`, token: randomBytes(32).toString('hex') };
  const jar = app.isPackaged ? path.join(process.resourcesPath, 'core', 'core.jar') : path.join(root, 'core', 'target', `agent-workbench-core-${app.getVersion()}.jar`);
  const dataDir = path.join(app.getPath('userData'), 'core');
  fs.mkdirSync(dataDir, { recursive: true });
  const log = fs.openSync(path.join(dataDir, 'core.log'), 'a');
  core = spawn(javaPath(), ['-Dfile.encoding=UTF-8', '-jar', jar], {
    cwd: dataDir, windowsHide: true,
    env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_PORT: String(port), WORKBENCH_TOKEN: connection.token, WORKBENCH_DATA_DIR: dataDir },
    stdio: ['ignore', log, log],
  });
  core.on('error', error => { if (!quitting) dialog.showErrorBox('核心无法启动', error.message); });
  core.on('exit', code => {
    coreReady = false;
    if (!quitting && window) dialog.showMessageBox(window, { type: 'error', title: '执行核心已停止', message: `核心已退出（${code}）。已持久化记录将在下次启动核对恢复。`, detail: `诊断日志：${path.join(dataDir, 'core.log')}` });
  });
  for (let i = 0; i < 150; i++) {
    if (core.exitCode !== null) throw new Error(`执行核心启动失败，退出码 ${core.exitCode}`);
    try {
      const response = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` }, signal: AbortSignal.timeout(1000) });
      if (response.ok) { coreReady = true; return; }
    } catch { }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error('执行核心启动超时，请检查 Java 21 环境和 core.log。');
}

function createWindow() {
  window = new BrowserWindow({ width: 1480, height: 960, minWidth: 1000, minHeight: 680, backgroundColor: '#f5f7f6', title: 'Agent Workbench', show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true } });
  window.setMenuBarVisibility(false);
  window.webContents.setWindowOpenHandler(({ url }) => { if (url.startsWith('https://')) shell.openExternal(url); return { action: 'deny' }; });
  window.webContents.on('will-navigate', (event, url) => { if (!url.startsWith('file://')) event.preventDefault(); });
  window.once('ready-to-show', () => window.show());
  window.on('close', event => { if (!quitting && tray) { event.preventDefault(); window.hide(); } });
  window.loadFile(path.join(root, 'web/dist/index.html'));
  if (process.env.WORKBENCH_DEVTOOLS === '1') window.webContents.openDevTools({ mode: 'detach' });
}

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, 'icon.png'));
  if (icon.isEmpty()) return;
  tray = new Tray(icon.resize({ width: 20, height: 20 }));
  tray.setToolTip('Agent Workbench · 关闭窗口后任务继续运行');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开工作台', click: () => window?.show() },
    { type: 'separator' },
    { label: core ? '停止任务并退出' : '断开客户端并退出', click: () => app.quit() },
  ]));
  tray.on('double-click', () => window?.show());
}

async function shutdown() {
  if (!core || core.exitCode !== null) return;
  try {
    const response = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` }, signal: AbortSignal.timeout(2000) });
    const state = await response.json();
    const active = (state.tasks || []).filter(t => ['RUNNING', 'QUEUED', 'WAITING_RESOURCE'].includes(t.state));
    await Promise.allSettled(active.map(task => fetch(`${connection.baseUrl}/api/tasks/${task.id}/pause`, { method: 'POST', headers: { Authorization: `Bearer ${connection.token}`, 'Content-Type': 'application/json' }, body: '{}' })));
    for (let i = 0; i < 30 && active.length; i++) {
      await new Promise(resolve => setTimeout(resolve, 200));
      const snapshot = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` }, signal: AbortSignal.timeout(1000) }).then(r => r.json());
      if (!snapshot.tasks.some(t => ['RUNNING', 'PAUSING', 'CANCELLING'].includes(t.state))) break;
    }
  } catch { }
  try {
    await fetch(`${connection.baseUrl}/api/shutdown`, { method: 'POST', headers: { Authorization: `Bearer ${connection.token}` }, signal: AbortSignal.timeout(2000) });
    for (let i = 0; i < 80 && core.exitCode === null; i++) await new Promise(resolve => setTimeout(resolve, 200));
  } catch { }
  if (core.exitCode === null) core.kill();
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } });
  app.whenReady().then(async () => {
    ipcMain.handle('workbench:connection', event => { trusted(event); return connection; });
    ipcMain.handle('workbench:version', event => { trusted(event); return app.getVersion(); });
    ipcMain.handle('workbench:directory', async event => { trusted(event); const result = await dialog.showOpenDialog(window, { properties: ['openDirectory'] }); return result.canceled ? null : result.filePaths[0]; });
    ipcMain.handle('workbench:open-path', async (event, selected) => { trusted(event); if (typeof selected !== 'string' || !path.isAbsolute(selected) || !fs.statSync(selected).isDirectory()) throw new Error('只能打开现有本地目录'); return shell.openPath(selected); });
    try { await startCore(); createWindow(); createTray(); }
    catch (error) { dialog.showErrorBox('Agent Workbench 启动失败', error.message); app.quit(); }
    powerMonitor.on('resume', () => { if (coreReady) window?.webContents.send('workbench:resumed'); });
  });
  app.on('before-quit', event => { if (quitting) return; event.preventDefault(); quitting = true; shutdown().finally(() => app.quit()); });
  app.on('window-all-closed', () => { if (!tray) app.quit(); });
  app.on('activate', () => window?.show());
}
