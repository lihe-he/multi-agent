import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const npm = windows ? 'npm.cmd' : 'npm';
const maven = windows ? 'mvn.cmd' : 'mvn';
const command = process.argv[2] || 'start';
const runtime = path.join(root, '.runtime');
fs.mkdirSync(runtime, { recursive: true });

function javaHome() {
  const candidates = [process.env.WORKBENCH_JAVA_HOME, ...fs.readdirSync(runtime).filter(x => x.startsWith('jdk-')).map(x => path.join(runtime, x)), process.env.JAVA_HOME].filter(Boolean);
  for (const home of candidates) {
    const java = path.join(home, 'bin', windows ? 'java.exe' : 'java');
    if (!fs.existsSync(java)) continue;
    const result = spawnSync(java, ['-version'], { encoding: 'utf8', windowsHide: true });
    const match = `${result.stderr}${result.stdout}`.match(/version "(\d+)/);
    if (match && Number(match[1]) >= 21) return home;
  }
  throw new Error('需要 Java 21 或更高版本。设置 WORKBENCH_JAVA_HOME，或将 JDK 解压到项目 .runtime/jdk-* 目录。');
}

function environment() {
  const home = javaHome();
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
    javaHome();
    await run(npm, ['install']);
    await run(process.execPath, [path.join(root, 'node_modules/electron/install.js')]);
    await run(npm, ['install'], path.join(root, 'web'));
    console.log('依赖已安装。运行 npm run build，然后 npm start。');
  } else if (command === 'build') await build();
  else if (command === 'test') {
    await run(maven, ['-B', '-ntp', 'test'], path.join(root, 'core'), environment());
    await run(npm, ['run', 'build'], path.join(root, 'web'));
  } else if (command === 'core') {
    const env = environment();
    env.WORKBENCH_DATA_DIR ||= path.join(root, '.workbench');
    await run(path.join(env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java'), ['-jar', path.join(root, 'core', 'target', 'agent-workbench-core-0.1.0.jar')], root, env);
  } else if (command === 'start' || command === 'dev') {
    if (!fs.existsSync(path.join(root, 'web/dist/index.html')) || !fs.existsSync(path.join(root, 'core/target/agent-workbench-core-0.1.0.jar'))) await build();
    const electron = path.join(root, 'node_modules', '.bin', windows ? 'electron.cmd' : 'electron');
    const env = { ...environment(), WORKBENCH_DEVTOOLS: command === 'dev' ? '1' : '0' };
    delete env.ELECTRON_RUN_AS_NODE;
    await run(electron, ['.'], root, env);
  } else if (command === 'package') {
    await build();
    const env = environment();
    const jre = path.join(runtime, 'jre');
    if (!fs.existsSync(jre)) await run(path.join(env.JAVA_HOME, 'bin', windows ? 'jlink.exe' : 'jlink'), ['--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.charsets', '--strip-debug', '--no-header-files', '--no-man-pages', '--output', jre], root, env);
    await run(path.join(root, 'node_modules', '.bin', windows ? 'electron-builder.cmd' : 'electron-builder'), [], root, env);
  } else throw new Error(`未知操作：${command}`);
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
