import { spawn } from 'node:child_process';
import { appendFileSync, copyFileSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { randomBytes } from 'node:crypto';
import net from 'node:net';
import { javaHome } from './java-runtime.mjs';

mkdirSync(resolve('.runtime'), { recursive: true });
const dir = mkdtempSync(join(resolve('.runtime'), 'ci-'));
const fixtureRoot = join(dir, 'fixtures'); mkdirSync(fixtureRoot);
for (const file of ['ci-smoke.mjs', 'smoke-test.mjs', 'team-smoke-fixture.mjs']) copyFileSync(join('tools', file), join(dir, file));
const port = await new Promise(resolvePort => { const server = net.createServer(); server.listen(0, '127.0.0.1', () => { const { port } = server.address(); server.close(() => resolvePort(port)); }); });
const token = randomBytes(32).toString('hex');
const env = { ...process.env, WORKBENCH_PORT: String(port), WORKBENCH_URL: `http://127.0.0.1:${port}`, WORKBENCH_TOKEN: token, WORKBENCH_DATA_DIR: dir, WORKBENCH_SMOKE_ROOT: fixtureRoot,
  WORKBENCH_REPORT_PATH: process.env.WORKBENCH_PRESERVE_QA === '1' ? join(dir, 'report.json') : resolve(`docs/qa/smoke-${process.platform}.json`) };
const java = join(javaHome(resolve('.')), 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
const version = JSON.parse(readFileSync(resolve('package.json'), 'utf8')).version;
const root = resolve('.'), runtimeArgument = relative(root, dir);
const jar = join('core', 'target', `agent-workbench-core-${version}.jar`);
const child = spawn(java, ['-Dfile.encoding=UTF-8', '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', `-Djdk.net.unixdomain.tmpdir=${runtimeArgument}`, '-jar', jar], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
env.WORKBENCH_EXPECTED_CORE_PID = String(child.pid);
const log = join(dir, 'core.log'); writeFileSync(log, '', { flag: 'wx' });
let logs = ''; for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { appendFileSync(log, bytes); logs = (logs + bytes).slice(-10000); });
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
  writeFileSync(join(dir, 'lifecycle.json'), JSON.stringify({ recordedAt: new Date().toISOString(), version, corePid: child.pid, exitCode: child.exitCode, signalCode: child.signalCode,
    directory: dir, fixtureRoot, scope: 'OBSERVED_CORE_EXIT_ONLY; COMPLETE_ORIGINAL_PROCESS_TREE_REQUIRES_SEPARATE_RECOVERY_ACCEPTANCE' }, null, 2) + '\n', { flag: 'wx' });
}
