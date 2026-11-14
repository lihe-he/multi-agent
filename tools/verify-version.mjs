import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function verifyVersions(root = projectRoot) {
  const read = file => JSON.parse(readFileSync(join(root, file), 'utf8'));
  const version = read('package.json').version;
  assert.equal(typeof version, 'string', 'package.json must declare a release version');
  assert.ok(version.length > 0, 'The release version must not be empty');
  for (const file of ['package-lock.json', 'web/package.json', 'web/package-lock.json']) {
    const metadata = read(file);
    assert.equal(metadata.version, version, `${file} must match the root release version`);
    if (file.endsWith('package-lock.json'))
      assert.equal(metadata.packages?.['']?.version, version, `${file} root package must match the release version`);
  }
  const pom = readFileSync(join(root, 'core/pom.xml'), 'utf8').replace(/<parent>[\s\S]*?<\/parent>/, '');
  const coreVersion = pom.match(/<version>\s*([^<\s]+)\s*<\/version>/)?.[1];
  assert.equal(coreVersion, version, 'core/pom.xml must match the root release version');
  return version;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(`Verified release versions: ${verifyVersions()}`); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
