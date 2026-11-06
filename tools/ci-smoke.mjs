import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import net from 'node:net';

mkdirSync(resolve('.runtime'), { recursive: true });
const dir = mkdtempSync(join(resolve('.runtime'), 'ci-'));
const port = await new Promise(resolvePort => { const server = net.createServer(); server.listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolvePort(port)); }); });
const token = randomBytes(32).toString('hex');
const env = { ...process.env, WORKBENCH_PORT: String(port), WORKBENCH_URL: `http://127.0.0.1:${port}`, WORKBENCH_TOKEN: token, WORKBENCH_DATA_DIR: dir, WORKBENCH_REPORT_PATH: resolve(`docs/qa/smoke-${process.platform}.json`) };
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const version = JSON.parse(readFileSync(resolve('package.json'), 'utf8')).version;
const child = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${dir}`, '-jar', resolve(`core/target/agent-workbench-core-${version}.jar`)], { env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
let logs = ''; child.stdout.on('data', b => { logs = (logs + b).slice(-10000); }); child.stderr.on('data', b => { logs = (logs + b).slice(-10000); });
try {
  let ready = false;
  for (let i = 0; i < 150; i++) {
    if (child.exitCode !== null) throw new Error(`核心启动失败：${logs}`);
    try { const res = await fetch(`${env.WORKBENCH_URL}/api/health`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(1000) }); if (res.ok) { ready = true; break; } } catch { }
    await new Promise(resolveWait => setTimeout(resolveWait, 200));
  }
  if (!ready) throw new Error(`核心启动超时：${logs}`);
  await new Promise((resolveRun, reject) => { const test = spawn(process.execPath, ['tools/smoke-test.mjs'], { env, stdio: 'inherit', windowsHide: true }); test.once('error', reject); test.once('exit', code => code === 0 ? resolveRun() : reject(new Error(`端到端测试失败：${code}`))); });
} catch (error) { console.error(error.message); process.exitCode = 1; }
finally {
  try { await fetch(`${env.WORKBENCH_URL}/api/shutdown`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(2000) }); } catch { }
  for (let i = 0; i < 100 && child.exitCode === null; i++) await new Promise(resolveWait => setTimeout(resolveWait, 100));
  if (child.exitCode === null) child.kill();
}
