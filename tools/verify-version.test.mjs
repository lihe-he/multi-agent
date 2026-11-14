import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, relative, resolve } from 'node:path';
import test from 'node:test';
import { verifyVersions } from './verify-version.mjs';

const version = '0.2.0-alpha.17';
function fixture(t) {
  const parent = realpathSync(tmpdir()), root = mkdtempSync(join(parent, 'workbench-version-'));
  mkdirSync(join(root, 'web')); mkdirSync(join(root, 'core'));
  for (const file of ['package.json', 'web/package.json']) writeFileSync(join(root, file), JSON.stringify({ version }));
  for (const file of ['package-lock.json', 'web/package-lock.json'])
    writeFileSync(join(root, file), JSON.stringify({ version, packages: { '': { version } } }));
  writeFileSync(join(root, 'core/pom.xml'), `<project><parent><version>3.5.7</version></parent><artifactId>agent-workbench-core</artifactId><version>${version}</version></project>`);
  t.after(() => {
    const target = realpathSync(root), fromParent = relative(parent, target);
    assert.equal(dirname(target), resolve(parent));
    assert.ok(fromParent && !fromParent.startsWith('..') && basename(target).startsWith('workbench-version-'));
    rmSync(target, { recursive: true });
  });
  return root;
}

test('consistent release metadata accepts the primary Maven version, not the parent version', t => {
  assert.equal(verifyVersions(fixture(t)), version);
});

test('renderer version drift is refused before build or packaging', t => {
  const root = fixture(t);
  writeFileSync(join(root, 'web/package.json'), JSON.stringify({ version: '0.2.0-alpha.16' }));
  assert.throws(() => verifyVersions(root), /web\/package.json must match/);
});

test('a stale nested lockfile version cannot pass on its top-level version', t => {
  const root = fixture(t), file = join(root, 'package-lock.json'), metadata = JSON.parse(readFileSync(file, 'utf8'));
  metadata.packages[''].version = '0.2.0-alpha.16';
  writeFileSync(file, JSON.stringify(metadata));
  assert.throws(() => verifyVersions(root), /root package must match/);
});

test('Maven version drift is refused even when every npm version agrees', t => {
  const root = fixture(t), file = join(root, 'core/pom.xml');
  writeFileSync(file, readFileSync(file, 'utf8').replace(`<version>${version}</version>`, '<version>0.2.0-alpha.16</version>'));
  assert.throws(() => verifyVersions(root), /core\/pom.xml must match/);
});
