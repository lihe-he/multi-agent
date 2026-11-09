import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { StringDecoder } from 'node:string_decoder';
import { fileURLToPath } from 'node:url';

const repository = 'electron-userland/electron-builder-binaries';
const releaseHosts = new Set(['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com']);
// Checksums are the original electron-builder 26.0.12 / app-builder pins.
// Old GitHub assets have no metadata digest, so their absence is recorded.
export const builderAssets = Object.freeze({
  'nsis-3.0.4.1': { id: 16428442, size: 1287512, sha512: 'VKMiizYdmNdJOWpRGz4trl4lD++BvYP2irAXpMilheUP0pc93iKlWAoP843Vlraj8YG19CVn0j+dCo/hURz9+Q==', source: 'app-builder-lib/out/targets/nsis/nsisUtil.js' },
  'nsis-resources-3.4.1': { id: 13752602, size: 730800, sha512: 'Dqd6g+2buwwvoG1Vyf6BHR1b+25QMmPcwZx40atOT57gH27rkjOei1L0JTldxZu4NFoEmW4kJgZ3DlSWVON3+Q==', source: 'app-builder-lib/out/targets/nsis/NsisTarget.js' },
  'appimage-12.0.1': { id: 13613639, size: 1573447, sha512: '3el6RUh6XoYJCI/ZOApyb0LLU/gSxDntVZ46R6+JNEANzfSo7/TfrzCRp5KlDo35c24r3ZOP7nnw4RqHwkMRLw==', source: 'app-builder-bin' },
});
const platformAssets = { win32: ['nsis-3.0.4.1', 'nsis-resources-3.4.1'], linux: ['appimage-12.0.1'], darwin: [] };

function inside(root, path) {
  const child = relative(root, path);
  if (!child || child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new Error('Builder cache target must stay inside its workspace.');
  return path;
}

async function lockedTools(workspace, names) {
  const require = createRequire(resolve(workspace, 'package.json'));
  const manifest = JSON.parse(await readFile(resolve(workspace, 'package.json'), 'utf8'));
  const lock = JSON.parse(await readFile(resolve(workspace, 'package-lock.json'), 'utf8'));
  if (manifest.devDependencies?.['electron-builder'] !== '26.0.12' || lock.packages?.['node_modules/electron-builder']?.version !== '26.0.12'
      || require('electron-builder/package.json').version !== '26.0.12' || require('app-builder-lib/package.json').version !== '26.0.12'
      || lock.packages?.['node_modules/app-builder-bin']?.version !== '5.0.0-alpha.12' || require('app-builder-bin/package.json').version !== '5.0.0-alpha.12') throw new Error('Builder fallback requires the exact reviewed package and lock versions.');
  const appBuilder = require('app-builder-bin').appBuilderPath;
  for (const name of names) {
    const asset = builderAssets[name];
    const source = await readFile(asset.source === 'app-builder-bin' ? appBuilder : require.resolve(asset.source));
    if (!source.includes(Buffer.from(asset.sha512))) throw new Error(`Installed builder has changed its original checksum: ${name}`);
  }
  return require('7zip-bin').path7za;
}

async function extract(archive, target, executable) {
  const env = { ...process.env }; delete env.GITHUB_TOKEN;
  const child = spawn(executable, ['x', '-bd', archive, `-o${target}`], { env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let tail = ''; for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { tail = (tail + bytes).slice(-8192); });
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  if (code !== 0) throw new Error(`Original 7zip extraction failed (exit ${code}): ${tail}`);
}

export async function saveVerifiedBuilderArchive(response, pin, asset, archive, signal) {
  const hash512 = createHash('sha512'), hash256 = createHash('sha256'); let bytes = 0;
  const verify = new Transform({ transform(chunk, encoding, callback) {
    bytes += chunk.length; if (bytes > pin.size) return callback(new Error('Builder asset exceeded its fixed size.'));
    hash512.update(chunk); hash256.update(chunk); callback(null, chunk);
  } });
  await pipeline(Readable.fromWeb(response.body), verify, createWriteStream(archive, { flags: 'wx' }), { signal });
  const sha256 = hash256.digest('hex');
  if (bytes !== pin.size || hash512.digest('base64') !== pin.sha512 || (asset.digest != null && asset.digest !== `sha256:${sha256}`)) throw new Error('Builder bytes differ from the original checksum or official metadata; failed bytes preserved.');
  return { bytes, sha256 };
}

// A fresh fallback cache never overwrites a previous download or unpacked cache.
export async function prefetchOfficialBuilderAssets({ workspace, platform = process.platform }, { fetchImpl = fetch, token = process.env.GITHUB_TOKEN, extractImpl = extract } = {}) {
  workspace = resolve(workspace);
  const names = platformAssets[platform];
  if (!names?.length) throw new Error('No reviewed builder fallback assets for this platform.');
  const executable = await lockedTools(workspace, names);
  const cacheRoot = inside(workspace, resolve(workspace, `.runtime/builder-verified-${randomUUID()}`));
  const receipts = [];
  for (const tag of names) {
    const pin = builderAssets[tag], name = `${tag}.7z`;
    const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'multi-agent-desktop-package' };
    if (token) headers.Authorization = `Bearer ${token}`;
    const metadata = await fetchImpl(`https://api.github.com/repos/${repository}/releases/tags/${tag}`, { headers, signal: AbortSignal.timeout(30000) });
    if (!metadata.ok) throw new Error(`Official builder release metadata failed: HTTP ${metadata.status}`);
    const text = await metadata.text(); if (text.length > 1024 * 1024) throw new Error('Builder release metadata exceeded its bound.');
    const release = JSON.parse(text), matches = (release.assets || []).filter(asset => asset.name === name);
    if (release.tag_name !== tag || release.draft || release.prerelease || matches.length !== 1) throw new Error('Official builder release identity differs from its fixed tag.');
    const asset = matches[0], officialUrl = `https://github.com/${repository}/releases/download/${tag}/${name}`;
    if (asset.id !== pin.id || asset.size !== pin.size || asset.browser_download_url !== officialUrl || (asset.digest != null && !/^sha256:[a-f0-9]{64}$/.test(asset.digest))) throw new Error('Official builder asset metadata differs from the fixed identity or size.');
    const staging = inside(cacheRoot, resolve(cacheRoot, `.official-${randomUUID()}`));
    const archive = inside(cacheRoot, resolve(staging, name)); await mkdir(staging, { recursive: true });
    let url = `https://api.github.com/repos/${repository}/releases/assets/${pin.id}`, response;
    const signal = AbortSignal.timeout(120000);
    for (let redirects = 0; redirects <= 5; redirects++) {
      const downloadHeaders = { Accept: 'application/octet-stream', 'User-Agent': 'multi-agent-desktop-package' };
      if (redirects === 0 && token) downloadHeaders.Authorization = `Bearer ${token}`;
      response = await fetchImpl(url, { headers: downloadHeaders, redirect: 'manual', signal });
      if (![301, 302, 303, 307, 308].includes(response.status)) break;
      await response.body?.cancel();
      const next = new URL(response.headers.get('location') || '', url);
      if (next.protocol !== 'https:' || next.username || next.password || !releaseHosts.has(next.hostname)) throw new Error('Official builder asset redirected to an unsupported host.');
      url = next.href; if (redirects === 5) throw new Error('Official builder asset exceeded the redirect bound.');
    }
    if (!response.ok || !response.body || response.headers.get('content-type')?.includes('application/json')) throw new Error(`Official builder asset download failed: HTTP ${response.status}`);
    const { bytes, sha256 } = await saveVerifiedBuilderArchive(response, pin, asset, archive, signal);
    const unpacked = inside(cacheRoot, resolve(staging, 'unpacked')); await mkdir(unpacked);
    await extractImpl(archive, unpacked, executable);
    // app-builder uses the prefix before the first hyphen as the cache parent.
    const target = inside(cacheRoot, resolve(cacheRoot, tag.split('-')[0], tag)); await mkdir(dirname(target), { recursive: true });
    await rename(unpacked, target);
    const receipt = { tag, assetId: pin.id, size: bytes, sha512: pin.sha512, sha256, originalChecksumVerified: true, metadataDigestProvided: asset.digest != null, metadataDigestVerified: asset.digest != null, cachePath: target };
    await writeFile(resolve(staging, 'verified.json'), `${JSON.stringify(receipt, null, 2)}\n`); receipts.push(receipt);
  }
  return { cacheRoot, platform, assets: receipts, freshCache: true };
}

export function isBuilderDownloadFailure(output, platform = process.platform) {
  return output.split(/\r?\n/).some(line => (platformAssets[platform] || []).some(tag => line.includes(`https://github.com/${repository}/releases/download/${tag}/${tag}.7z`))
    && /status code (?:502|503|504)|(?:ETIMEDOUT|ECONNRESET|EAI_AGAIN)/.test(line));
}

async function build(workspace, attempt, cacheRoot) {
  const require = createRequire(resolve(workspace, 'package.json'));
  const logPath = inside(workspace, resolve(workspace, `.runtime/desktop-package-attempt${attempt}-${randomUUID()}.log`)); await mkdir(dirname(logPath), { recursive: true });
  const log = createWriteStream(logPath, { flags: 'wx' }), env = { ...process.env }; delete env.GITHUB_TOKEN;
  if (cacheRoot) env.ELECTRON_BUILDER_CACHE = cacheRoot;
  const child = spawn(process.execPath, [require.resolve('electron-builder/cli.js'), '--publish', 'never'], { cwd: workspace, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let tail = '';
  for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) {
    const decoder = new StringDecoder('utf8');
    stream.on('data', bytes => { destination.write(bytes); log.write(bytes); tail = (tail + decoder.write(bytes)).slice(-65536); });
  }
  const code = await new Promise((done, reject) => { child.once('error', reject); child.once('close', done); });
  await new Promise(done => log.end(done)); return { code, tail, logPath };
}

export async function packageDesktop(workspace, { buildImpl = build, prefetchImpl = prefetchOfficialBuilderAssets, platform = process.platform } = {}) {
  const first = await buildImpl(workspace, 1); let fallback;
  if (first.code !== 0) {
    if (!isBuilderDownloadFailure(first.tail, platform)) throw new Error(`Desktop package failed (exit ${first.code}); preserved ${first.logPath}`);
    console.log('Fixed builder asset download failed; checking its official API bytes and original checksum before one retry.');
    fallback = await prefetchImpl({ workspace, platform });
    const second = await buildImpl(workspace, 2, fallback.cacheRoot);
    if (second.code !== 0) throw new Error(`Desktop package retry failed (exit ${second.code}); preserved ${first.logPath} and ${second.logPath}`);
  }
  const receipt = { recordedAt: new Date().toISOString(), status: 'passed', attempts: fallback ? 2 : 1, originalBuilderUsed: true, fallback };
  await writeFile(resolve(workspace, '.runtime/desktop-package-verified.json'), `${JSON.stringify(receipt, null, 2)}\n`); return receipt;
}

async function main() {
  const workspace = process.cwd();
  if (process.argv.includes('--prefetch-only')) { console.log(JSON.stringify(await prefetchOfficialBuilderAssets({ workspace, platform: process.argv.find(arg => arg.startsWith('--platform='))?.slice(11) || process.platform }), null, 2)); return; }
  console.log(JSON.stringify(await packageDesktop(workspace), null, 2));
}

if (process.argv[1] && resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1])) main().catch(error => { console.error(error.message); process.exitCode = 1; });
