import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

export function javaHome(root) {
  const runtime = join(root, '.runtime');
  const bundled = existsSync(runtime) ? readdirSync(runtime).filter(name => name.startsWith('jdk-')).map(name => join(runtime, name)) : [];
  const candidates = [process.env.WORKBENCH_JAVA_HOME, ...bundled, process.env.JAVA_HOME].filter(Boolean);
  for (const home of candidates) {
    const executable = join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (!existsSync(executable)) continue;
    const result = spawnSync(executable, ['-version'], { encoding: 'utf8', windowsHide: true, timeout: 10000 });
    const match = `${result.stderr}${result.stdout}`.match(/version "(\d+)/);
    if (result.status === 0 && match && Number(match[1]) >= 21) return home;
  }
  throw new Error('需要 Java 21 或更高版本。设置 WORKBENCH_JAVA_HOME，或将 JDK 解压到项目 .runtime/jdk-* 目录。');
}
