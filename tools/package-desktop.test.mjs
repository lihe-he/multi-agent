import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { builderAssets, isBuilderDownloadFailure, packageDesktop, prefetchOfficialBuilderAssets, saveVerifiedBuilderArchive } from './package-desktop.mjs';

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const officialUrl = tag => `https://github.com/electron-userland/electron-builder-binaries/releases/download/${tag}/${tag}.7z`;
const networkFailure = `cannot resolve ${officialUrl('nsis-resources-3.4.1')}: status code 504`;
async function fixture(t) {
  const root = await mkdtemp(resolve(workspace, '.runtime/builder-cache-test-'));
  t.after(async () => { const child = relative(resolve(workspace, '.runtime'), root); assert.ok(child && child !== '..' && !child.startsWith(`..${sep}`) && !isAbsolute(child)); await rm(root, { recursive: true }); });
  await mkdir(resolve(root, '.runtime'));
  await writeFile(resolve(root, 'package.json'), JSON.stringify({ devDependencies: { 'electron-builder': '26.0.12' } }));
  await writeFile(resolve(root, 'package-lock.json'), JSON.stringify({ packages: { 'node_modules/electron-builder': { version: '26.0.12' }, 'node_modules/app-builder-bin': { version: '5.0.0-alpha.12' } } }));
  for (const [name, version] of [['electron-builder', '26.0.12'], ['app-builder-lib', '26.0.12'], ['app-builder-bin', '5.0.0-alpha.12'], ['7zip-bin', '5.2.0']]) {
    await mkdir(resolve(root, 'node_modules', name), { recursive: true }); await writeFile(resolve(root, 'node_modules', name, 'package.json'), JSON.stringify({ version, main: 'index.js' }));
  }
  await writeFile(resolve(root, 'node_modules/app-builder-bin/index.js'), 'exports.appBuilderPath=__dirname+"/fixture.bin";');
  await writeFile(resolve(root, 'node_modules/app-builder-bin/fixture.bin'), builderAssets['appimage-12.0.1'].sha512);
  await writeFile(resolve(root, 'node_modules/7zip-bin/index.js'), 'exports.path7za=__dirname+"/unused-7zip";');
  for (const pin of Object.values(builderAssets).filter(pin => pin.source !== 'app-builder-bin')) {
    const target = resolve(root, 'node_modules', pin.source); await mkdir(dirname(target), { recursive: true }); await writeFile(target, pin.sha512);
  }
  const requests = []; let redirect = 'https://release-assets.githubusercontent.com/fixed-test-asset', assetId;
  const fetchImpl = async (url, options) => {
    requests.push({ url, headers: new Headers(options.headers) });
    if (url.includes('/releases/tags/')) {
      const tag = url.split('/').at(-1), pin = builderAssets[tag];
      return new Response(JSON.stringify({ tag_name: tag, draft: false, prerelease: false, assets: [{ name: `${tag}.7z`, id: assetId ?? pin.id, size: pin.size, digest: null, browser_download_url: officialUrl(tag) }] }));
    }
    if (url.includes('/releases/assets/')) return new Response(null, { status: 302, headers: { location: redirect } });
    return new Response(Buffer.alloc(builderAssets['nsis-3.0.4.1'].size), { headers: { 'content-type': 'application/octet-stream' } });
  };
  let extractions = 0; const extractImpl = async () => { extractions++; };
  return { root, requests, fetchImpl, extractImpl, extractions: () => extractions, redirect: value => { redirect = value; }, assetId: value => { assetId = value; } };
}

test('stream verifies the upstream SHA512, optional official SHA256 and exact byte count', async t => {
  const f = await fixture(t), bytes = Buffer.from('original pinned builder bytes'), sha512 = createHash('sha512').update(bytes).digest('base64'), sha256 = createHash('sha256').update(bytes).digest('hex');
  const archive = resolve(f.root, '.runtime/verified.7z');
  assert.deepEqual(await saveVerifiedBuilderArchive(new Response(bytes), { size: bytes.length, sha512 }, { digest: `sha256:${sha256}` }, archive), { bytes: bytes.length, sha256 });
  assert.deepEqual(await readFile(archive), bytes);
  await assert.rejects(saveVerifiedBuilderArchive(new Response(bytes), { size: bytes.length, sha512 }, { digest: `sha256:${'0'.repeat(64)}` }, resolve(f.root, '.runtime/bad-digest.7z')), /differ/);
  await assert.rejects(saveVerifiedBuilderArchive(new Response(bytes), { size: bytes.length + 1, sha512 }, { digest: null }, resolve(f.root, '.runtime/short.7z')), /differ/);
  await assert.rejects(saveVerifiedBuilderArchive(new Response(bytes), { size: bytes.length - 1, sha512 }, { digest: null }, resolve(f.root, '.runtime/long.7z')), /exceeded/);
});

test('changed official bytes stay outside the builder cache and are never extracted', async t => {
  const f = await fixture(t);
  await assert.rejects(prefetchOfficialBuilderAssets({ workspace: f.root, platform: 'win32' }, { fetchImpl: f.fetchImpl, extractImpl: f.extractImpl, token: 'fixture-token' }), /bytes differ/);
  assert.equal(f.extractions(), 0); assert.equal(f.requests.length, 3);
  assert.equal(f.requests[0].headers.get('authorization'), 'Bearer fixture-token'); assert.equal(f.requests[1].headers.get('authorization'), 'Bearer fixture-token'); assert.equal(f.requests[2].headers.get('authorization'), null);
});

test('metadata identity drift prevents binary fetching and extraction', async t => {
  const f = await fixture(t); f.assetId(1);
  await assert.rejects(prefetchOfficialBuilderAssets({ workspace: f.root, platform: 'win32' }, { fetchImpl: f.fetchImpl, extractImpl: f.extractImpl }), /metadata differs/);
  assert.equal(f.requests.length, 1); assert.equal(f.extractions(), 0);
});

test('unsupported release redirects are refused before sending credentials or bytes', async t => {
  const f = await fixture(t); f.redirect('https://unrelated.example.invalid/asset');
  await assert.rejects(prefetchOfficialBuilderAssets({ workspace: f.root, platform: 'win32' }, { fetchImpl: f.fetchImpl, extractImpl: f.extractImpl, token: 'fixture-token' }), /unsupported host/);
  assert.equal(f.requests.length, 2); assert.equal(f.extractions(), 0);
});

test('package or original checksum drift refuses all fallback network activity', async t => {
  const f = await fixture(t);
  await writeFile(resolve(f.root, 'package-lock.json'), JSON.stringify({ packages: {} }));
  await assert.rejects(prefetchOfficialBuilderAssets({ workspace: f.root, platform: 'win32' }, { fetchImpl: f.fetchImpl }), /exact reviewed/); assert.equal(f.requests.length, 0);
  const other = await fixture(t); await writeFile(resolve(other.root, 'node_modules/app-builder-bin/fixture.bin'), 'changed compiled checksum');
  await assert.rejects(prefetchOfficialBuilderAssets({ workspace: other.root, platform: 'linux' }, { fetchImpl: other.fetchImpl }), /changed its original checksum/); assert.equal(other.requests.length, 0);
});

test('fallback only recognizes transient errors for the current fixed platform asset', () => {
  assert.equal(isBuilderDownloadFailure(networkFailure, 'win32'), true); assert.equal(isBuilderDownloadFailure(networkFailure, 'linux'), false);
  assert.equal(isBuilderDownloadFailure(`cannot resolve ${officialUrl('appimage-12.0.1')}: status code 503`, 'linux'), true);
  assert.equal(isBuilderDownloadFailure(networkFailure.replace('504', '403'), 'win32'), false);
  assert.equal(isBuilderDownloadFailure(`${officialUrl('nsis-resources-3.4.1')}\nchecksum mismatch; unrelated status code 504`, 'win32'), false);
});

test('successful original packaging needs no fallback and failures retry the original builder once', async t => {
  const f = await fixture(t), builds = []; let downloads = 0;
  const prefetchImpl = async () => { downloads++; return { cacheRoot: resolve(f.root, '.runtime/verified-cache') }; };
  const receipt = await packageDesktop(f.root, { platform: 'win32', prefetchImpl, buildImpl: async (...args) => { builds.push(args); return { code: builds.length === 1 ? 1 : 0, tail: networkFailure, logPath: `attempt-${builds.length}` }; } });
  assert.equal(receipt.attempts, 2); assert.equal(downloads, 1); assert.equal(builds.length, 2); assert.equal(builds[1][2], receipt.fallback.cacheRoot);
  await packageDesktop(f.root, { platform: 'win32', prefetchImpl: async () => { assert.fail('A successful original build must not fetch fallback bytes'); }, buildImpl: async () => ({ code: 0 }) });
});

test('unrelated build failures and a failed second attempt never launch another retry', async t => {
  const f = await fixture(t); let builds = 0, downloads = 0;
  const prefetchImpl = async () => { downloads++; return { cacheRoot: resolve(f.root, '.runtime/verified-cache') }; };
  await assert.rejects(packageDesktop(f.root, { platform: 'win32', prefetchImpl, buildImpl: async () => ({ code: 1, tail: 'Packaging configuration is invalid', logPath: 'first' }) }), /Desktop package failed/); assert.equal(downloads, 0);
  await assert.rejects(packageDesktop(f.root, { platform: 'win32', prefetchImpl, buildImpl: async () => { builds++; return { code: 1, tail: networkFailure, logPath: `attempt-${builds}` }; } }), /retry failed/);
  assert.equal(builds, 2); assert.equal(downloads, 1);
});
