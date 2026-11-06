import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { javaHome } from './java-runtime.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const npm = windows ? 'npm.cmd' : 'npm';
const maven = windows ? 'mvn.cmd' : 'mvn';
const command = process.argv[2] || 'start';
const runtime = path.join(root, '.runtime');
const metadata = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const jar = path.join(root, 'core', 'target', `agent-workbench-core-${metadata.version}.jar`);
fs.mkdirSync(runtime, { recursive: true });

function environment() {
  const home = javaHome(root);
  return { ...process.env, JAVA_HOME: home, PATH: `${path.join(home, 'bin')}${path.delimiter}${process.env.PATH}`, JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8' };
}

function run(exe, args, cwd = root, env = process.env) {
  return new Promise((resolve, reject) => {
    if (windows && /\.(cmd|bat)$/i.test(exe)) {
      const quote = value => `'${value.replaceAll("'", "''")}'`;
      const code = `& ${[exe, ...args].map(quote).join(' ')}; exit $LASTEXITCODE`;
      exe = 'powershell.exe';
      args = ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(code, 'utf16le').toString('base64')];
    }
    const child = spawn(exe, args, { cwd, env, stdio: 'inherit', windowsHide: true });
    child.on('error', reject);
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${path.basename(exe)} 退出码 ${code}`)));
  });
}

async function build() {
  await run(maven, ['-B', '-ntp', 'package'], path.join(root, 'core'), environment());
  await run(npm, ['run', 'build'], path.join(root, 'web'));
}

async function main() {
  if (command === 'setup') {
    javaHome(root);
    await run(npm, ['install']);
    await run(process.execPath, [path.join(root, 'node_modules/electron/install.js')]);
    await run(npm, ['install'], path.join(root, 'web'));
    console.log('依赖已安装。运行 npm run build，然后 npm start。');
  } else if (command === 'build') await build();
  else if (command === 'build:core') await run(maven, ['-B', '-ntp', 'package'], path.join(root, 'core'), environment());
  else if (command === 'build:web') await run(npm, ['run', 'build'], path.join(root, 'web'));
  else if (command === 'test') {
    await run(maven, ['-B', '-ntp', 'test'], path.join(root, 'core'), environment());
    await run(npm, ['run', 'build'], path.join(root, 'web'));
  } else if (command === 'core') {
    const env = environment();
    env.WORKBENCH_DATA_DIR ||= path.join(root, '.workbench');
    await run(path.join(env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java'), ['-jar', jar], root, env);
  } else if (command === 'start' || command === 'dev') {
    const attached = Boolean(process.env.WORKBENCH_CORE_URL);
    if (attached) {
      if (!fs.existsSync(path.join(root, 'web/dist/index.html'))) await run(npm, ['run', 'build'], path.join(root, 'web'));
    } else if (!fs.existsSync(path.join(root, 'web/dist/index.html')) || !fs.existsSync(jar)) await build();
    const electron = path.join(root, 'node_modules', '.bin', windows ? 'electron.cmd' : 'electron');
    const env = { ...(attached ? process.env : environment()), WORKBENCH_DEVTOOLS: command === 'dev' ? '1' : '0' };
    delete env.ELECTRON_RUN_AS_NODE;
    await run(electron, ['.'], root, env);
  } else if (command === 'package' || command === 'package:prepared') {
    if (command === 'package') await build();
    else if (!fs.existsSync(jar) || !fs.existsSync(path.join(root, 'web/dist/index.html'))) throw new Error('缺少已验证的核心或网页构建产物，请先运行 npm run build。');
    const env = environment();
    const jre = path.join(runtime, 'jre');
    if (!fs.existsSync(jre)) await run(path.join(env.JAVA_HOME, 'bin', windows ? 'jlink.exe' : 'jlink'), ['--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', jre], root, env);
    await run(path.join(root, 'node_modules', '.bin', windows ? 'electron-builder.cmd' : 'electron-builder'), ['--publish', 'never'], root, env);
  } else throw new Error(`未知操作：${command}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
