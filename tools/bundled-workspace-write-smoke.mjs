import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { delimiter, join, resolve } from 'node:path';
import { zipEntry } from './team-smoke-fixture.mjs';

const root = resolve('.'), platform = process.platform, windows = platform === 'win32';
const json = file => JSON.parse(readFileSync(file, 'utf8')), hash = bytes => createHash('sha256').update(bytes).digest('hex');
const version = json('package.json').version, writer = json(`docs/qa/workspace-writes-${platform}.json`);
assert.equal(writer.version, version); assert.equal(writer.status, 'passed');
const evidence = writer.evidenceDirectory, fixture = json(join(evidence, 'fixture.json'));
const jar = `core/target/agent-workbench-core-${version}.jar`, jarBytes = readFileSync(jar);
assert.equal(hash(jarBytes), writer.jarSha256); assert.equal(fixture.jarSha256, writer.jarSha256);
const fixtureRoot = resolve(evidence, '..'), classes = join(fixtureRoot, 'fixture-classes'), boot = join(fixtureRoot, 'boot');
for (const compiled of fixture.fixtureClasses) assert.equal(hash(readFileSync(join(classes, compiled.file))), compiled.sha256);
for (const compiled of fixture.productionClasses) {
  assert.equal(hash(readFileSync(join(boot, 'BOOT-INF/classes', compiled.file))), compiled.sha256);
  assert.equal(hash(zipEntry(jarBytes, `BOOT-INF/classes/${compiled.file}`)), compiled.sha256);
}
const dependencies = fixture.nativeDependencies.map(dependency => {
  const bytes = readFileSync(join(boot, 'BOOT-INF/lib', dependency.file));
  assert.equal(hash(bytes), dependency.sha256); assert.equal(bytes.length, dependency.bytes);
  assert.equal(hash(zipEntry(jarBytes, `BOOT-INF/lib/${dependency.file}`)), dependency.sha256);
  return { ...dependency, licenseChoice: 'Apache-2.0', licenses: ['META-INF/AL2.0', 'META-INF/LICENSE'].map(entry => {
    const license = zipEntry(bytes, entry); assert.ok(license.length > 0); return { entry, bytes: license.length, sha256: hash(license) };
  }) };
});
const candidates = platform === 'win32' ? ['release/win-unpacked/resources/jre'] : platform === 'linux' ? ['release/linux-unpacked/resources/jre', 'release/linux-arm64-unpacked/resources/jre']
  : ['mac', 'mac-arm64', 'mac-x64', 'mac-universal'].map(name => `release/${name}/Agent Workbench.app/Contents/Resources/jre`);
const existing = candidates.filter(candidate => existsSync(join(candidate, 'bin', windows ? 'java.exe' : 'java')));
assert.equal(existing.length, 1, 'The current packaged application must provide one unambiguous Java runtime.');
const runtime = resolve(existing[0]), java = join(runtime, 'bin', windows ? 'java.exe' : 'java');
const runtimeRelease = readFileSync(join(runtime, 'release'), 'utf8'); assert.match(runtimeRelease, /jdk\.unsupported/);
assert.equal(hash(Buffer.from(runtimeRelease)), hash(readFileSync('.runtime/jre/release')));
assert.equal(hash(readFileSync(java)), hash(readFileSync(join('.runtime/jre/bin', windows ? 'java.exe' : 'java'))));
const packagedJar = resolve(runtime, '..', 'core/core.jar'); assert.equal(hash(readFileSync(packagedJar)), writer.jarSha256);
mkdirSync('.runtime', { recursive: true }); const directory = mkdtempSync(join(root, '.runtime/bundled-workspace-writer-'));
let nio = join(directory, 'nio'); if (windows) while (Buffer.byteLength(nio, 'utf8') < 120) nio = join(nio, 'workbench-jdk-loopback');
mkdirSync(nio, { recursive: true });
const classpath = [classes, join(boot, 'BOOT-INF/classes'), ...readdirSync(join(boot, 'BOOT-INF/lib')).filter(file => file.endsWith('.jar')).map(file => join(boot, 'BOOT-INF/lib', file))].join(delimiter);
const outputOptions = ['-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8'];
const command = [java, ...outputOptions, `-Djdk.net.unixdomain.tmpdir=${nio}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2',
  '-cp', classpath, 'dev.agentworkbench.WorkspaceWriteProbe', 'own', join(directory, 'data')];
writeFileSync(join(directory, 'command.json'), JSON.stringify({ command, fixtureClasses: fixture.fixtureClasses, productionClasses: fixture.productionClasses }, null, 2) + '\n');
const result = JSON.parse(execFileSync(command[0], command.slice(1), { cwd: root, windowsHide: true, encoding: 'utf8', timeout: 30000 }));
writeFileSync(join(directory, 'native-writer.json'), JSON.stringify(result, null, 2) + '\n');
assert.equal(result.acquired, true); assert.equal(result.grant.state, 'HELD'); assert.equal(result.grant.lockValid, true);
assert.ok(result.grant.fileKey.startsWith(windows ? 'WINDOWS:' : 'NIO:'));
assert.equal(result.grant.executorPid, result.pid); assert.equal(result.grant.processStartedAt, result.processStartedAt);
assert.equal(result.releaseDuringBorrowRejected, true); assert.equal(result.release.protectionReleased, true); assert.equal(result.lockAvailableAfterRelease, true);
assert.equal(result.manifest.state, 'RELEASED'); assert.equal(result.manifest.identity.id, result.grant.identity.id); assert.equal(result.release.identity.id, result.grant.identity.id);
assert.equal(realpathSync(result.javaHome), realpathSync(runtime)); assert.match(result.javaVersion, /^21\./);
if (windows) { assert.equal(result.nativeProcessBirth, result.processStartedAt); assert.ok(result.nativeProcessSnapshotCount > 0); }
const stopped = JSON.parse(execFileSync(java, [...outputOptions, '-cp', classes, 'ProcessRecoveryProbe', 'inspect', String(result.pid), result.processStartedAt],
  { cwd: root, windowsHide: true, encoding: 'utf8', timeout: 15000 }));
assert.equal(stopped.originalStopped, true); writeFileSync(join(directory, 'original-probe-stopped.json'), JSON.stringify(stopped, null, 2) + '\n');
const report = { recordedAt: new Date().toISOString(), version, platform, status: 'passed', scope: 'ACTUAL_PACKAGED_JRE_FINAL_JAR_WORKSPACE_WRITE_GUARD',
  jarSha256: writer.jarSha256, packagedJarSha256: hash(readFileSync(packagedJar)), packagedJava: java, packagedJavaSha256: hash(readFileSync(java)), javaVersion: result.javaVersion,
  packagedRuntimeReleaseSha256: hash(Buffer.from(runtimeRelease)), fixtureClassesCompiledOnce: true, fixtureClasses: fixture.fixtureClasses, productionClasses: fixture.productionClasses,
  nativeDependencies: dependencies, nativeFileKey: result.grant.fileKey, originalPid: result.pid, originalProcessStartedAt: result.processStartedAt, originalStopped: true,
  releaseDuringBorrowRejected: true, lockAvailableAfterRelease: true, windowsNativeProcessSnapshotApplicable: windows, windowsNativeProcessSnapshotVerified: windows ? true : null,
  ...(windows ? { nativeProcessBirth: result.nativeProcessBirth, nativeProcessSnapshotCount: result.nativeProcessSnapshotCount } : {}),
  probeOutputEncoding: 'UTF-8', schedulerParallelism: 2, schedulerMaxPoolSize: 2, evidenceDirectory: directory };
writeFileSync(join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n');
mkdirSync('docs/qa', { recursive: true }); writeFileSync(`docs/qa/bundled-workspace-writer-${platform}.json`, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ status: report.status, javaVersion: result.javaVersion, originalPid: result.pid, nativeFileKey: report.nativeFileKey, jarSha256: writer.jarSha256, evidenceDirectory: directory }));
