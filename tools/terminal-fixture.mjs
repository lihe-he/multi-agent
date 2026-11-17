import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { copyFileSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { delimiter, dirname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { zipEntry } from './team-smoke-fixture.mjs';

export const terminalFixtureRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
export const terminalHash = bytes => createHash('sha256').update(bytes).digest('hex');

function files(folder, relative = '', result = []) {
  for (const entry of readdirSync(join(folder, relative), { withFileTypes: true })) {
    const file = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) files(folder, file, result);
    else { assert.ok(entry.isFile()); result.push(file); }
  }
  return result.sort();
}

function runtimeFiles(folder, relative = '', result = []) {
  const root = realpathSync(folder);
  for (const entry of readdirSync(join(folder, relative), { withFileTypes: true })) {
    const file = relative ? `${relative}/${entry.name}` : entry.name, path = join(folder, file);
    if (entry.isDirectory()) runtimeFiles(folder, file, result);
    else {
      const actual = realpathSync(path); assert.ok(actual.startsWith(root + sep), `Runtime file resolved outside its runtime: ${file}`);
      assert.ok(statSync(actual).isFile()); result.push(file);
    }
  }
  return result.sort();
}

// These inputs bind actual production bytes; they do not claim that a build or test passed.
export function freezeTerminalArtifacts(directory, { withWeb = false, packaged = false } = {}) {
  const root = terminalFixtureRoot, windows = process.platform === 'win32', metadata = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  const version = metadata.version, ordinaryJar = join(root, `core/target/agent-workbench-core-${version}.jar`), ordinaryBytes = readFileSync(ordinaryJar);
  const jarSha256 = terminalHash(ordinaryBytes), compilerRoot = javaHome(root);
  const coreSourceNames = ['core/pom.xml', ...files(join(root, 'core/src')).map(file => `core/src/${file}`)];
  const snapshot = (names, destination) => names.sort().map(file => {
    const bytes = readFileSync(join(root, file)), target = join(directory, destination, file);
    mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes, { flag: 'wx' });
    return { file, bytes: bytes.length, sha256: terminalHash(bytes) };
  });
  const coreSources = snapshot(coreSourceNames, 'sources');
  const acceptedBuildPath = process.env.WORKBENCH_CORE_BUILD_RECEIPT;
  if (acceptedBuildPath) {
    const accepted = JSON.parse(readFileSync(acceptedBuildPath, 'utf8'));
    assert.equal(accepted.status, 'passed'); assert.equal(accepted.version, version); assert.equal(accepted.jarSha256, jarSha256); assert.equal(accepted.sourcesUnchanged, true);
    for (const source of accepted.sources) assert.equal(terminalHash(readFileSync(resolve(root, source.file))), source.sha256);
    writeFileSync(join(directory, 'original-core-build-receipt.json'), readFileSync(acceptedBuildPath), { flag: 'wx' });
  }
  let resources, runtime = compilerRoot, archive, packagedFiles = [], packagedRuntimeFiles = [];
  if (packaged) {
    const candidates = windows ? ['release/win-unpacked/resources'] : process.platform === 'linux'
      ? ['release/linux-unpacked/resources', 'release/linux-arm64-unpacked/resources']
      : ['mac', 'mac-arm64', 'mac-x64', 'mac-universal'].map(name => `release/${name}/${metadata.build.productName}.app/Contents/Resources`);
    const available = candidates.map(candidate => join(root, candidate)).filter(candidate => existsSync(join(candidate, 'jre/bin', windows ? 'java.exe' : 'java')));
    assert.equal(available.length, 1, 'One unambiguous current packaged Java runtime is required.'); resources = available[0];
    assert.equal(terminalHash(readFileSync(join(resources, 'core/core.jar'))), jarSha256); runtime = join(resources, 'jre');
    const originalRuntime = join(root, '.runtime/jre');
    assert.deepEqual(runtimeFiles(runtime), runtimeFiles(originalRuntime));
    packagedRuntimeFiles = runtimeFiles(originalRuntime).map(file => {
      const bytes = readFileSync(join(runtime, file)); assert.equal(terminalHash(bytes), terminalHash(readFileSync(join(originalRuntime, file))), `Packaged JRE changed: ${file}`);
      return { file, bytes: bytes.length, sha256: terminalHash(bytes), originalSymbolicLink: lstatSync(join(originalRuntime, file)).isSymbolicLink(), packagedSymbolicLink: lstatSync(join(runtime, file)).isSymbolicLink() };
    });
    const release = readFileSync(join(runtime, 'release'), 'utf8'); assert.match(release, /JAVA_VERSION="21\./); assert.match(release, /jdk\.unsupported/);
    archive = join(resources, 'app.asar'); const asar = createRequire(join(root, 'package.json'))('@electron/asar');
    const packagedMetadata = JSON.parse(asar.extractFile(archive, 'package.json').toString('utf8'));
    for (const key of ['name', 'version', 'main']) assert.equal(packagedMetadata[key], metadata[key]);
    const names = [...files(join(root, 'desktop')).map(file => `desktop/${file}`), ...files(join(root, 'web/dist')).map(file => `web/dist/${file}`)];
    packagedFiles = names.map(file => {
      const bytes = asar.extractFile(archive, join(file)); assert.equal(terminalHash(bytes), terminalHash(readFileSync(join(root, file))), `Packaged source or Web asset changed: ${file}`);
      return { file, bytes: bytes.length, sha256: terminalHash(bytes) };
    });
  }
  const jar = join(directory, 'core.jar'); copyFileSync(packaged ? join(resources, 'core/core.jar') : ordinaryJar, jar);
  assert.equal(terminalHash(readFileSync(jar)), jarSha256);
  const nativeInventory = JSON.parse(zipEntry(ordinaryBytes, 'BOOT-INF/classes/terminal-native-inventory.json'));
  const pty = zipEntry(ordinaryBytes, 'BOOT-INF/lib/pty4j-0.13.4.jar'); assert.equal(terminalHash(pty), nativeInventory.jarSha256);
  for (const native of nativeInventory.native) {
    const bytes = zipEntry(pty, native.name); assert.equal(bytes.length, native.bytes); assert.equal(terminalHash(bytes), native.sha256);
  }
  const notices = files(join(root, 'core/src/main/resources/third-party')).map(file => {
    const bytes = readFileSync(join(root, 'core/src/main/resources/third-party', file));
    assert.equal(terminalHash(zipEntry(ordinaryBytes, `BOOT-INF/classes/third-party/${file}`)), terminalHash(bytes));
    assert.equal(terminalHash(readFileSync(join(root, 'web/public/third-party', file))), terminalHash(bytes));
    return { file: `third-party/${file}`, bytes: bytes.length, sha256: terminalHash(bytes) };
  });
  const output = [], webSources = [];
  if (withWeb) {
    const sourceNames = ['web/package.json', 'web/package-lock.json', 'web/index.html', 'web/vite.config.ts', 'web/tsconfig.json',
      ...['src', 'public', 'tests'].flatMap(folder => files(join(root, 'web', folder)).map(file => `web/${folder}/${file}`))];
    webSources.push(...snapshot(sourceNames, 'sources'));
    const asar = packaged ? createRequire(join(root, 'package.json'))('@electron/asar') : null;
    for (const file of files(join(root, 'web/dist'))) {
      const bytes = packaged ? asar.extractFile(archive, join('web', 'dist', file)) : readFileSync(join(root, 'web/dist', file)), target = join(directory, 'dist', file);
      mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes, { flag: 'wx' }); output.push({ file, bytes: bytes.length, sha256: terminalHash(bytes) });
    }
    const acceptedWebPath = process.env.WORKBENCH_WEB_BUILD_RECEIPT;
    if (acceptedWebPath) {
      const accepted = JSON.parse(readFileSync(acceptedWebPath, 'utf8')); assert.equal(accepted.status, 'passed'); assert.equal(accepted.version, version); assert.equal(accepted.sourcesUnchanged, true);
      for (const source of accepted.sources) assert.equal(terminalHash(readFileSync(resolve(root, source.file))), source.sha256);
      for (const asset of accepted.output) assert.equal(output.find(file => file.file === asset.file)?.sha256, asset.sha256);
      writeFileSync(join(directory, 'original-web-build-receipt.json'), readFileSync(acceptedWebPath), { flag: 'wx' });
    }
    for (const notice of notices) assert.equal(output.find(file => file.file === notice.file)?.sha256, notice.sha256);
  }
  const java = join(runtime, 'bin', windows ? 'java.exe' : 'java'), probeClasses = join(directory, 'probe-classes'); mkdirSync(probeClasses);
  copyFileSync(join(root, 'tools/ProcessRecoveryProbe.java'), join(directory, 'ProcessRecoveryProbe.java'));
  execFileSync(join(compilerRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', probeClasses, join(directory, 'ProcessRecoveryProbe.java')], { windowsHide: true, timeout: 30000 });
  const nioDirectory = windows ? directory : mkdtempSync(join(realpathSync('/tmp'), 'terminal-nio-'));
  const artifactInputs = { status: 'verified', version, platform: process.platform, jar, jarSha256, originalJar: packaged ? join(resources, 'core/core.jar') : ordinaryJar,
    sources: coreSources, buildReceiptProvided: Boolean(acceptedBuildPath), java, javaSha256: terminalHash(readFileSync(java)), javaRoot: runtime, compilerRoot,
    packaged, resources: resources || null, packagedFiles, packagedRuntimeFiles, nativeInventory, notices, probeClasses, nioDirectory,
    scope: packaged ? 'ACTUAL_PACKAGED_JRE_JAR_AND_ASAR_BYTES' : 'ACTUAL_PRODUCTION_MAIN_ORDINARY_JAR_BYTES' };
  const webInputs = { status: 'verified', version, sources: webSources, output, scope: packaged ? 'ACTUAL_PACKAGED_ASAR_WEB_BYTES' : 'ACTUAL_PRODUCTION_MAIN_WEB_BYTES' };
  writeFileSync(join(directory, 'artifact-inputs.json'), JSON.stringify({ core: artifactInputs, web: withWeb ? webInputs : null }, null, 2) + '\n', { flag: 'wx' });
  return { root, windows, core: artifactInputs, web: webInputs, java, javaRoot: runtime, probeClasses, nioDirectory };
}

// Packaged terminal tests need only system shell facilities and Git, with Node/Java absent from PATH.
export function terminalCoreEnvironment(directory, packaged) {
  if (!packaged) return {};
  let path;
  if (process.platform === 'win32') {
    const system = join(process.env.SystemRoot, 'System32'), git = execFileSync(join(system, 'where.exe'), ['git.exe'], { windowsHide: true, encoding: 'utf8' }).trim().split(/\r?\n/)[0];
    path = [system, join(system, 'WindowsPowerShell/v1.0'), dirname(realpathSync(git))].join(delimiter);
    for (const executable of ['node.exe', 'java.exe']) {
      const result = spawnSync(join(system, 'where.exe'), [executable], { env: { ...process.env, PATH: path }, windowsHide: true, encoding: 'utf8', timeout: 10000 });
      assert.equal(result.status, 1, `Packaged fixture PATH unexpectedly exposes ${executable}`); assert.equal(result.stdout.trim(), '');
    }
  } else {
    path = join(directory, 'system-path'); mkdirSync(path);
    for (const executable of ['git', 'stty', 'sleep']) {
      const original = execFileSync('/bin/sh', ['-c', `command -v ${executable}`], { encoding: 'utf8' }).trim();
      assert.ok(original.startsWith('/')); symlinkSync(realpathSync(original), join(path, executable));
    }
    const result = execFileSync('/bin/sh', ['-c', 'command -v node || :; command -v java || :'], { env: { ...process.env, PATH: path }, encoding: 'utf8' }); assert.equal(result.trim(), '');
  }
  const facts = { PATH: path, externalNodeAvailable: false, externalJavaAvailable: false, terminalCommands: 'SYSTEM_SHELL_AND_OPERATING_SYSTEM_TOOLS_ONLY' };
  writeFileSync(join(directory, 'packaged-system-path.json'), JSON.stringify(facts, null, 2) + '\n', { flag: 'wx' });
  return { PATH: path, JAVA_HOME: join(directory, 'external-java-disabled'), WORKBENCH_JAVA_HOME: '', WORKBENCH_ELECTRON_JAVA: '' };
}

export function inspectOriginalTerminalBundle(terminal, core) {
  const bundle = terminal.nativeProcess.nativeBundle;
  assert.equal(bundle.version, '0.13.4'); assert.equal(bundle.releasedJarSha256, core.nativeInventory.jarSha256);
  const inventoryBytes = readFileSync(join(bundle.directory, 'inventory.json')), ownerBytes = readFileSync(join(bundle.directory, 'owner.json'));
  assert.equal(terminalHash(inventoryBytes), bundle.inventorySha256); assert.equal(terminalHash(ownerBytes), bundle.ownerSha256);
  const owner = JSON.parse(ownerBytes); assert.equal(owner.pid, terminal.source.executorPid);
  assert.equal(owner.processStartedAt, terminal.source.processStartedAt);
  for (const file of bundle.files) {
    const bytes = readFileSync(join(bundle.directory, file.path)); assert.equal(bytes.length, file.bytes); assert.equal(terminalHash(bytes), file.sha256);
    assert.ok(core.nativeInventory.native.some(native => native.name === `resources/com/pty4j/native/${file.path}` && native.sha256 === file.sha256));
  }
  return { originalWorker: { pid: owner.pid, processStartedAt: owner.processStartedAt }, bundle, originalNativeFilesVerified: bundle.files.length };
}

export function writeTerminalShellFixture(source, { ui = false } = {}) {
  const windows = process.platform === 'win32', newline = windows ? '\r' : '\n', basename = ui ? 'native-ui-probe' : 'terminal-probe';
  const marker = ui ? 'ORIGINAL_NATIVE_UI_中文_' : 'ORIGINAL_PTY_中文_', markup = ui ? '<script>window.__nativeUiMarkupExecuted=true</script>' : '<script>window.__terminalMarkupExecuted=true</script>';
  const environmentFile = ui ? 'actual-environment.json' : 'shell-environment.json', sourceText = ui ? 'original native UI source\n' : 'original source\n';
  writeFileSync(join(source, '.gitattributes'), '* -text\n'); writeFileSync(join(source, 'app.txt'), sourceText);
  const script = windows ? `param([string]$Mode)
$utf8=[Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding=$utf8
if ($Mode -eq 'unicode') {
  [IO.File]::AppendAllText('manual-count.txt','1',$utf8)
  [Console]::WriteLine('${marker}'+[char]27+'[32mGREEN'+[char]27+'[0m')
  [Console]::WriteLine('${markup}')
  $facts=[ordered]@{}
  foreach ($name in @('WORKBENCH_TOKEN','WORKBENCH_CORE_TOKEN','OPENAI_API_KEY','ANTHROPIC_API_KEY')) { $facts[$name]=[bool][Environment]::GetEnvironmentVariable($name) }
  [IO.File]::WriteAllText('${environmentFile}',($facts|ConvertTo-Json -Compress),$utf8)
} elseif ($Mode -eq 'forbidden') {
  [IO.File]::WriteAllText('must-not-execute.txt','forbidden after revocation',$utf8)
} elseif ($Mode -eq 'hold') {
  [IO.File]::AppendAllText('crash-count.txt','1',$utf8)
  [IO.File]::WriteAllText('crash-child-pid.txt',[string]$PID,$utf8)
  [Console]::WriteLine('ORIGINAL_CORE_CRASH_OUTPUT')
  while ($true) { [Console]::WriteLine('original pending output 中文'); [Threading.Thread]::Sleep(20) }
} else { exit 3 }
` : `present() { if [ "$1" = x ]; then printf true; else printf false; fi; }
case "$1" in
unicode)
  printf '1' >> manual-count.txt
  printf '${marker}\\033[32mGREEN\\033[0m\\n'
  printf '%s\\n' '${markup}'
  printf '{"WORKBENCH_TOKEN":%s,"WORKBENCH_CORE_TOKEN":%s,"OPENAI_API_KEY":%s,"ANTHROPIC_API_KEY":%s}\\n' "$(present "\${WORKBENCH_TOKEN+x}")" "$(present "\${WORKBENCH_CORE_TOKEN+x}")" "$(present "\${OPENAI_API_KEY+x}")" "$(present "\${ANTHROPIC_API_KEY+x}")" > ${environmentFile}
  ;;
forbidden) printf '%s' 'forbidden after revocation' > must-not-execute.txt ;;
hold)
  printf '1' >> crash-count.txt
  printf '%s' "$$" > crash-child-pid.txt
  printf 'ORIGINAL_CORE_CRASH_OUTPUT\\n'
  while :; do printf 'original pending output 中文\\n'; sleep 0.02; done
  ;;
*) exit 3 ;;
esac
`;
  const filename = `${basename}.${windows ? 'ps1' : 'sh'}`;
  writeFileSync(join(source, filename), windows ? Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(script)]) : script);
  const command = mode => windows ? `powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File ${filename} ${mode}${newline}` : `/bin/sh ${filename} ${mode}${newline}`;
  const baselineCommand = windows ? `if ([IO.File]::ReadAllText('app.txt') -cne '${sourceText}') { exit 2 }; [Console]::WriteLine('ORIGINAL_TERMINAL_BASELINE')`
    : `IFS= read -r original < app.txt; [ "$original" = '${sourceText.trim()}' ] || exit 2; printf 'ORIGINAL_TERMINAL_BASELINE\\n'`;
  return { sourceText, unicodeCommand: command('unicode'), forbiddenCommand: command('forbidden'), crashCommand: command('hold'), baselineCommand,
    sizeCommand: windows ? `powershell.exe -NoLogo -NoProfile -NonInteractive -Command "[IO.File]::WriteAllText('actual-native-size.txt',(([Console]::WindowHeight).ToString()+' '+[Console]::WindowWidth))"${newline}` : `stty size > actual-native-size.txt${newline}` };
}
