import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, relative, resolve, sep } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { isElectronDownloadFailure, prefetchOfficialElectron } from './install-desktop-deps.mjs';

const workspace = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const bytes = Buffer.from('fixed official electron test artifact'), digest = createHash('sha256').update(bytes).digest('hex');
const name = 'electron-v39.2.7-win32-x64.zip';
async function fixture(t) {
  const root = await mkdtemp(resolve(workspace, '.runtime/electron-cache-test-'));
  t.after(async () => { const child = relative(resolve(workspace, '.runtime'), root); assert.ok(child && !child.startsWith(`..${sep}`) && child !== '..'); await rm(root, { recursive: true }); });
  await mkdir(resolve(root, 'node_modules/electron'), { recursive: true });
  await writeFile(resolve(root, 'package.json'), JSON.stringify({ devDependencies: { electron: '39.2.7' } }));
  await writeFile(resolve(root, 'package-lock.json'), JSON.stringify({ packages: { 'node_modules/electron': { version: '39.2.7' } } }));
  await writeFile(resolve(root, 'node_modules/electron/package.json'), JSON.stringify({ version: '39.2.7' }));
  await writeFile(resolve(root, 'node_modules/electron/checksums.json'), JSON.stringify({ [name]: digest }));
  const requests = [], metadata = { tag_name: 'v39.2.7', draft: false, prerelease: false, assets: [{ id: 123, name, size: bytes.length, digest: `sha256:${digest}`, browser_download_url: `https://github.com/electron/electron/releases/download/v39.2.7/${name}` }] };
  let payload = bytes, redirect = 'https://release-assets.githubusercontent.com/official-test-asset';
  const fetchImpl = async (url, options) => {
    requests.push({ url, headers: new Headers(options.headers) });
    if (url.includes('/releases/tags/')) return new Response(JSON.stringify(metadata), { headers: { 'content-type': 'application/json' } });
    if (url.includes('/releases/assets/')) return new Response(null, { status: 302, headers: { location: redirect } });
    return new Response(payload, { headers: { 'content-type': 'application/octet-stream' } });
  };
  return { root, requests, metadata, fetchImpl, setPayload: value => { payload = value; }, setRedirect: value => { redirect = value; } };
}

test('official API bytes match both checksums and redirect strips API authorization', async t => {
  const f = await fixture(t), receipt = await prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl, token: 'test-api-token' });
  assert.equal(receipt.sha256, digest); assert.deepEqual(await readFile(receipt.cachePath), bytes); assert.equal(receipt.cached, false);
  assert.equal(f.requests[0].headers.get('authorization'), 'Bearer test-api-token'); assert.equal(f.requests[1].headers.get('authorization'), 'Bearer test-api-token'); assert.equal(f.requests[2].headers.get('authorization'), null);
  const again = await prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl }); assert.equal(again.cached, true); assert.equal(f.requests.filter(request => request.url.includes('/releases/assets/')).length, 1);
});

test('official digest mismatch prevents binary download and cache creation', async t => {
  const f = await fixture(t); f.metadata.assets[0].digest = `sha256:${'a'.repeat(64)}`;
  await assert.rejects(prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl }), /differs/); assert.equal(f.requests.length, 1);
});

test('changed downloaded bytes never populate a trusted Electron cache entry', async t => {
  const f = await fixture(t); f.setPayload(Buffer.alloc(bytes.length));
  await assert.rejects(prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl }), /Downloaded Electron asset differs/);
});

test('redirect to another host is rejected before credentials or bytes are sent', async t => {
  const f = await fixture(t); f.setRedirect('https://unrelated.example.invalid/asset');
  await assert.rejects(prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl, token: 'test-api-token' }), /unsupported download host/); assert.equal(f.requests.length, 2);
});

test('lock version drift refuses the fallback before any network request', async t => {
  const f = await fixture(t); await writeFile(resolve(f.root, 'package-lock.json'), JSON.stringify({ packages: { 'node_modules/electron': { version: '39.2.8' } } }));
  await assert.rejects(prefetchOfficialElectron({ workspace: f.root, platform: 'win32', arch: 'x64' }, { fetchImpl: f.fetchImpl }), /same exact version/); assert.equal(f.requests.length, 0);
});

test('fallback is limited to Electron install network failures', () => {
  assert.equal(isElectronDownloadFailure('npm error path D:\\repo\\node_modules\\electron\nnpm error HTTPError: Response code 504 (Gateway Time-out)'), true);
  assert.equal(isElectronDownloadFailure('npm error command node install.js\nnpm error code ETIMEDOUT'), true);
  assert.equal(isElectronDownloadFailure('npm error path D:\\repo\\node_modules\\another-package\nnpm error HTTPError: Response code 504'), false);
  assert.equal(isElectronDownloadFailure('npm error path D:\\repo\\node_modules\\electron\nnpm error checksum mismatch'), false);
  assert.equal(isElectronDownloadFailure('npm error path D:\\repo\\node_modules\\electron\nnpm error HTTPError: Response code 403'), false);
});
