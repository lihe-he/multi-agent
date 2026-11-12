import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const metadata = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const product = metadata.build.productName;
const paths = {
  win32: join(root, 'release', 'win-unpacked', `${product}.exe`),
  darwin: join(root, 'release', process.arch === 'arm64' ? 'mac-arm64' : 'mac', `${product}.app`, 'Contents', 'MacOS', product),
  linux: join(root, 'release', 'linux-unpacked', metadata.build.linux.executableName || metadata.name.toLowerCase()),
};
const executable = paths[process.platform];
if (!executable || !existsSync(executable)) throw new Error('缺少当前系统的桌面发行目录，请先完成打包。');
const env = { ...process.env, WORKBENCH_ELECTRON_PATH: executable, WORKBENCH_EXPECT_PACKAGED: '1' };
const virtualDisplay = process.platform === 'linux' && !process.env.DISPLAY;
const script = join(root, 'tools', 'theme-desktop-smoke.cjs');
const child = spawn(virtualDisplay ? 'xvfb-run' : process.execPath, virtualDisplay ? ['-a', process.execPath, script] : [script], { cwd: root, env, windowsHide: true, stdio: 'inherit' });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
