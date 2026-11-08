import { createHash, randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { StringDecoder } from 'node:string_decoder';
import { fileURLToPath } from 'node:url';

const releaseHosts = new Set(['github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com']);
const supportedPlatforms = new Set(['win32', 'darwin', 'linux']);
const supportedArchitectures = new Set(['x64', 'arm64']);

function inside(root, path) {
  const child = relative(root, path);
  if (!child || child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new Error('Download target must stay inside its cache directory.');
  return path;
}

async function sha256(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest('hex');
}

// The fallback fills @electron/get's normal cache. Electron's original install.js
// still verifies its pinned checksums and extracts the same fixed release.
export async function prefetchOfficialElectron({ workspace, platform = process.platform, arch = process.arch }, { fetchImpl = fetch, token = process.env.GITHUB_TOKEN } = {}) {
  workspace = resolve(workspace);
  const manifest = JSON.parse(await readFile(resolve(workspace, 'package.json'), 'utf8'));
  const lock = JSON.parse(await readFile(resolve(workspace, 'package-lock.json'), 'utf8'));
  const version = manifest.devDependencies?.electron;
  if (!/^\d+\.\d+\.\d+$/.test(version || '') || lock.packages?.['node_modules/electron']?.version !== version) throw new Error('Electron must use the same exact version in package.json and the lockfile.');
  if (!supportedPlatforms.has(platform) || !supportedArchitectures.has(arch)) throw new Error('Unsupported Electron download target.');
  const require = createRequire(resolve(workspace, 'package.json'));
  const installed = require('electron/package.json');
  if (installed.version !== version) throw new Error('Installed Electron package does not match the lockfile.');
  const checksums = require('electron/checksums.json');
  const name = `electron-v${version}-${platform}-${arch}.zip`, checksum = checksums[name];
  if (!/^[a-f0-9]{64}$/.test(checksum || '')) throw new Error('Pinned Electron package has no valid checksum for this target.');
  const apiHeaders = { Accept: 'application/vnd.github+json', 'User-Agent': 'multi-agent-desktop-deps' };
  if (token) apiHeaders.Authorization = `Bearer ${token}`;
  const metadata = await fetchImpl(`https://api.github.com/repos/electron/electron/releases/tags/v${version}`, { headers: apiHeaders, signal: AbortSignal.timeout(30000) });
  if (!metadata.ok) throw new Error(`Official Electron release metadata failed: HTTP ${metadata.status}`);
  const text = await metadata.text();
  if (text.length > 2 * 1024 * 1024) throw new Error('Official release metadata exceeded the expected bound.');
  const release = JSON.parse(text);
  if (release.tag_name !== `v${version}` || release.draft || release.prerelease) throw new Error('Official Electron release identity does not match the locked stable version.');
  const matches = (release.assets || []).filter(asset => asset.name === name);
  if (matches.length !== 1) throw new Error('Expected one exact official Electron asset.');
  const asset = matches[0];
  if (!Number.isSafeInteger(asset.id) || asset.id < 1 || !Number.isSafeInteger(asset.size) || asset.size < 1 || asset.size > 512 * 1024 * 1024 || asset.digest !== `sha256:${checksum}`) throw new Error('Official asset metadata differs from the pinned package checksum or size bound.');
  const downloadUrl = `https://github.com/electron/electron/releases/download/v${version}/${name}`;
  if (asset.browser_download_url !== downloadUrl) throw new Error('Official asset URL does not match the fixed Electron release.');
  const { Cache } = require(resolve(dirname(require.resolve('@electron/get')), 'Cache.js'));
  const cacheRoot = inside(workspace, resolve(workspace, '.runtime/electron-cache'));
  const cache = new Cache(cacheRoot), cachePath = inside(cacheRoot, cache.getCachePath(downloadUrl, name));
  const receipt = { version, platform, arch, assetId: asset.id, assetName: name, size: asset.size, sha256: checksum, metadataDigestVerified: true, cachePath };
  try {
    const existing = await stat(cachePath);
    if (existing.size !== asset.size || await sha256(cachePath) !== checksum) throw new Error('Existing Electron cache differs; preserved it for inspection.');
    return { ...receipt, cached: true };
  } catch (exception) { if (exception.code !== 'ENOENT') throw exception; }
  const temporary = inside(cacheRoot, resolve(cacheRoot, `.official-${randomUUID()}`, `${name}.part`));
  await mkdir(dirname(temporary), { recursive: true });
  let url = `https://api.github.com/repos/electron/electron/releases/assets/${asset.id}`, response;
  const signal = AbortSignal.timeout(180000);
  for (let redirects = 0; redirects <= 5; redirects++) {
    const headers = { Accept: 'application/octet-stream', 'User-Agent': 'multi-agent-desktop-deps' };
    if (redirects === 0 && token) headers.Authorization = `Bearer ${token}`;
    response = await fetchImpl(url, { headers, redirect: 'manual', signal });
    if (![301, 302, 303, 307, 308].includes(response.status)) break;
    await response.body?.cancel();
    const next = new URL(response.headers.get('location') || '', url);
    if (next.protocol !== 'https:' || next.username || next.password || !releaseHosts.has(next.hostname)) throw new Error('Official release redirected to an unsupported download host.');
    url = next.href;
    if (redirects === 5) throw new Error('Official release exceeded the redirect bound.');
  }
  if (!response.ok || !response.body) throw new Error(`Official Electron asset download failed: HTTP ${response.status}`);
  if (response.headers.get('content-type')?.includes('application/json')) throw new Error('Official asset returned metadata instead of binary bytes.');
  const hash = createHash('sha256'); let bytes = 0;
  const verify = new Transform({ transform(chunk, encoding, callback) {
    bytes += chunk.length;
    if (bytes > asset.size) return callback(new Error('Electron asset exceeded its official size.'));
    hash.update(chunk); callback(null, chunk);
  } });
  await pipeline(Readable.fromWeb(response.body), verify, createWriteStream(temporary, { flags: 'wx' }), { signal });
  if (bytes !== asset.size || hash.digest('hex') !== checksum) throw new Error('Downloaded Electron asset differs from official metadata and the pinned checksum; partial bytes preserved.');
  await mkdir(dirname(cachePath), { recursive: true });
  await rename(temporary, cachePath);
  return { ...receipt, cached: false };
}

export function isElectronDownloadFailure(output) {
  return /npm error (?:path|command)[^\n]*(?:[\\/]electron(?:\s|$)|node install\.js)/i.test(output)
    && /HTTPError: Response code (?:502|503|504)|(?:ETIMEDOUT|ECONNRESET|EAI_AGAIN)/.test(output);
}

async function install(workspace, attempt) {
  const logPath = inside(workspace, resolve(workspace, `.runtime/desktop-deps-attempt${attempt}-${randomUUID()}.log`));
  await mkdir(dirname(logPath), { recursive: true });
  const log = createWriteStream(logPath, { flags: 'wx' }), decoder = new StringDecoder('utf8'); let tail = '';
  const executable = process.platform === 'win32' ? process.env.ComSpec || 'cmd.exe' : 'npm';
  const metadataOnly = attempt === 'metadata';
  const args = process.platform === 'win32' ? ['/d', '/s', '/c', metadataOnly ? 'npm ci --ignore-scripts --cache .runtime/npm-cache' : 'npm ci --cache .runtime/npm-cache']
    : ['ci', ...(metadataOnly ? ['--ignore-scripts'] : []), '--cache', '.runtime/npm-cache'];
  const env = { ...process.env, electron_config_cache: resolve(workspace, '.runtime/electron-cache') };
  delete env.GITHUB_TOKEN;
  const child = spawn(executable, args, { cwd: workspace, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  for (const [stream, destination] of [[child.stdout, process.stdout], [child.stderr, process.stderr]]) stream.on('data', chunk => {
    destination.write(chunk); log.write(chunk); tail = (tail + decoder.write(chunk)).slice(-65536);
  });
  const code = await new Promise((resolveExit, reject) => { child.once('error', reject); child.once('close', status => resolveExit(status)); });
  await new Promise(resolveLog => log.end(resolveLog));
  return { code, tail, logPath };
}

async function verifyInstalled(workspace) {
  const require = createRequire(resolve(workspace, 'package.json'));
  const version = require('electron/package.json').version, electronRoot = dirname(require.resolve('electron/package.json'));
  const installed = (await readFile(resolve(electronRoot, 'dist/version'), 'utf8')).trim().replace(/^v/, '');
  const target = (await readFile(resolve(electronRoot, 'path.txt'), 'utf8')).trim();
  const executable = inside(electronRoot, resolve(electronRoot, 'dist', target));
  if (installed !== version || !target || !(await stat(executable)).isFile()) throw new Error('Electron installation did not preserve its fixed version and executable.');
  return { version, installedVersion: installed, executablePresent: true };
}

async function main() {
  const workspace = process.cwd();
  if (process.argv.includes('--prefetch-only')) { console.log(JSON.stringify(await prefetchOfficialElectron({ workspace }), null, 2)); return; }
  const first = await install(workspace, 1); let fallback;
  if (first.code !== 0) {
    if (!isElectronDownloadFailure(first.tail)) throw new Error(`Desktop dependency install failed (exit ${first.code}); preserved ${first.logPath}`);
    console.log('Electron release download failed; checking the exact official API asset and pinned checksum before one retry.');
    const metadata = await install(workspace, 'metadata');
    if (metadata.code !== 0) throw new Error(`Locked package metadata install failed (exit ${metadata.code}); preserved ${metadata.logPath}`);
    fallback = await prefetchOfficialElectron({ workspace });
    const second = await install(workspace, 2);
    if (second.code !== 0) throw new Error(`Desktop dependency retry failed (exit ${second.code}); preserved ${first.logPath} and ${second.logPath}`);
  }
  const receipt = { recordedAt: new Date().toISOString(), status: 'passed', attempts: fallback ? 2 : 1, metadataOnlyInstall: Boolean(fallback), originalInstallScriptUsed: true, electron: await verifyInstalled(workspace), fallback };
  await writeFile(resolve(workspace, '.runtime/desktop-deps-verified.json'), `${JSON.stringify(receipt, null, 2)}\n`);
  console.log(JSON.stringify(receipt, null, 2));
}

if (process.argv[1] && resolve(fileURLToPath(import.meta.url)) === resolve(process.argv[1])) main().catch(exception => { console.error(exception.message); process.exitCode = 1; });
