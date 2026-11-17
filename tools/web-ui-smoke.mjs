import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { appendFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), web = join(root, 'web');
const runtime = join(root, '.runtime'); mkdirSync(runtime, { recursive: true });
const directory = mkdtempSync(join(runtime, 'ui-production-')), hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (file, value) => writeFileSync(join(directory, file), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
const inventory = (folder, relative = '', result = []) => {
  for (const entry of readdirSync(join(folder, relative), { withFileTypes: true })) {
    const file = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) inventory(folder, file, result);
    else { assert.ok(entry.isFile()); const bytes = readFileSync(join(folder, file)); result.push({ file, bytes: bytes.length, sha256: hash(bytes) }); }
  }
  return result.sort((a, b) => a.file.localeCompare(b.file));
};
const version = JSON.parse(readFileSync(join(web, 'package.json'), 'utf8')).version;
assert.equal(version, JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version);
const sourceNames = ['package.json', 'package-lock.json', 'playwright.config.ts', 'vite.config.ts', 'tsconfig.json', 'index.html',
  ...['src', 'public', 'tests'].flatMap(folder => inventory(join(web, folder)).map(file => `${folder}/${file.file}`))];
const sources = sourceNames.sort().map(file => {
  const bytes = readFileSync(join(web, file)), target = join(directory, 'sources', file);
  mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes, { flag: 'wx' });
  return { file: `web/${file}`, bytes: bytes.length, sha256: hash(bytes) };
});
const buildPath = process.env.WORKBENCH_WEB_BUILD_RECEIPT;
const buildReceipt = buildPath ? JSON.parse(readFileSync(buildPath, 'utf8')) : null;
if (buildReceipt) {
  assert.equal(buildReceipt.status, 'passed'); assert.equal(buildReceipt.version, version); assert.equal(buildReceipt.sourcesUnchanged, true);
  for (const source of buildReceipt.sources) assert.equal(hash(readFileSync(resolve(root, source.file))), source.sha256, `Build source changed: ${source.file}`);
  for (const output of buildReceipt.output) assert.equal(hash(readFileSync(join(web, 'dist', output.file))), output.sha256, `Build output changed: ${output.file}`);
  save('original-build-receipt.json', buildReceipt);
}
const dependencies = ['@xterm/xterm', '@xterm/addon-fit', '@playwright/test', 'playwright', 'playwright-core', 'react', 'react-dom'].map(name => {
  const folder = join(web, 'node_modules', name), metadata = JSON.parse(readFileSync(join(folder, 'package.json'), 'utf8')), files = inventory(folder);
  save(`${name.replaceAll('/', '-')}-inventory.json`, files);
  return { name, version: metadata.version, gitHead: metadata.gitHead || null, fileCount: files.length, inventorySha256: hash(Buffer.from(JSON.stringify(files))) };
});
const historical = execFileSync('git', ['ls-files', '-z', '--', 'web/qa'], { cwd: root, windowsHide: true }).toString('utf8').split('\0').filter(Boolean).map(file => {
  const bytes = readFileSync(join(root, file)), target = join(directory, 'historical-before', file);
  mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes, { flag: 'wx' }); return { file, sha256: hash(bytes) };
});
assert.ok(existsSync(join(web, 'dist/index.html')), 'Build the current production Web before UI acceptance.');
cpSync(join(web, 'dist'), join(directory, 'dist'), { recursive: true, dereference: false });
const assets = inventory(join(directory, 'dist')), assetMap = new Map(assets.map(file => [`/${file.file}`, file]));
const scriptSource = readFileSync(fileURLToPath(import.meta.url)); writeFileSync(join(directory, 'runner-source.mjs'), scriptSource, { flag: 'wx' });
const proof = randomUUID(), staticRequests = [], startedAt = new Date().toISOString();
const server = createServer((request, response) => {
  const path = new URL(request.url, 'http://127.0.0.1').pathname;
  if (path === '/__workbench_frozen_build__') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(JSON.stringify({ proof, assets })); return;
  }
  const asset = assetMap.get(path === '/' ? '/index.html' : path);
  staticRequests.push({ at: new Date().toISOString(), path, status: asset ? 200 : 404, ...(asset ? { sha256: asset.sha256 } : {}) });
  if (!asset) { response.writeHead(404); response.end(); return; }
  const bytes = readFileSync(join(directory, 'dist', asset.file));
  if (hash(bytes) !== asset.sha256) { response.writeHead(503); response.end(); return; }
  response.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml' }[extname(asset.file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }); response.end(bytes);
});
await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
const baseUrl = `http://127.0.0.1:${server.address().port}`;
assert.equal((await (await fetch(`${baseUrl}/__workbench_frozen_build__`)).json()).proof, proof);
const channel = process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium');
const config = { testDir: join(web, 'tests'), timeout: 30000, retries: 0, workers: 2, outputDir: join(directory, 'test-results'),
  use: { baseURL: baseUrl, headless: true, channel, viewport: { width: 1440, height: 1000 }, trace: 'retain-on-failure', screenshot: 'only-on-failure' },
  reporter: [['list'], ['json', { outputFile: join(directory, 'playwright.json') }]] };
writeFileSync(join(directory, 'playwright.config.cjs'), `module.exports = ${JSON.stringify(config, null, 2)};\n`, { flag: 'wx' });
const log = join(directory, 'command.log'); writeFileSync(log, '', { flag: 'wx' });
const selection = process.argv.slice(2), args = [join(web, 'node_modules/@playwright/test/cli.js'), 'test', '--config', join(directory, 'playwright.config.cjs'), ...selection];
const child = spawn(process.execPath, args, { cwd: web, env: process.env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { appendFileSync(log, bytes); process.stdout.write(bytes); });
let childError; child.once('error', error => { childError = error.message; });
const ending = await new Promise(done => child.once('close', (exitCode, signal) => done({ exitCode, signal })));
server.closeAllConnections(); await new Promise(done => server.close(done));
save('static-requests.json', staticRequests);
if (existsSync(join(web, 'qa'))) cpSync(join(web, 'qa'), join(directory, 'generated-qa'), { recursive: true, dereference: false });
for (const original of historical) {
  const bytes = readFileSync(join(directory, 'historical-before', original.file)); assert.equal(hash(bytes), original.sha256);
  writeFileSync(join(root, original.file), bytes); assert.equal(hash(readFileSync(join(root, original.file))), original.sha256);
}
const results = existsSync(join(directory, 'playwright.json')) ? JSON.parse(readFileSync(join(directory, 'playwright.json'), 'utf8')) : undefined;
const sourcesUnchanged = sources.every(source => hash(readFileSync(join(root, source.file))) === source.sha256);
const buildSourcesUnchanged = !buildReceipt || buildReceipt.sources.every(source => hash(readFileSync(resolve(root, source.file))) === source.sha256);
const assetsUnchanged = assets.every(asset => hash(readFileSync(join(directory, 'dist', asset.file))) === asset.sha256 && hash(readFileSync(join(web, 'dist', asset.file))) === asset.sha256);
const passed = ending.exitCode === 0 && !childError && sourcesUnchanged && buildSourcesUnchanged && assetsUnchanged && results?.stats.expected > 0 && results.stats.unexpected === 0 && results.stats.flaky === 0 && results.stats.skipped === 0;
const receipt = { startedAt, recordedAt: new Date().toISOString(), status: passed ? 'passed' : 'failed', ...ending, childError, version, platform: process.platform,
  baseCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, windowsHide: true, encoding: 'utf8' }).trim(), baseUrl,
  serverOwnedByRunner: true, reusedServer: false, serverProofVerified: true, browserChannel: channel, browsersPath: process.env.PLAYWRIGHT_BROWSERS_PATH || null,
  sources, sourcesUnchanged, buildReceiptPath: buildPath || null, buildSourcesUnchanged, dependencies, assets, assetsUnchanged, stats: results?.stats || null,
  selection, originalHistoricalImagesPreserved: historical.length, runnerSha256: hash(scriptSource), directory,
  scope: selection.length ? 'SELECTED_ACTUAL_PRODUCTION_WEB_BROWSER_PROTOCOL_FIXTURES' : 'COMPLETE_ACTUAL_PRODUCTION_WEB_UI_CORPUS_WITH_BROWSER_PROTOCOL_FIXTURES; REAL_NATIVE_AND_RELEASE_ACCEPTANCE_SEPARATE' };
save('receipt.json', receipt); save('report.json', receipt);
const qa = join(root, 'docs/qa'), reportFile = join(qa, `ui-production-${process.platform}.json`); mkdirSync(qa, { recursive: true });
if (existsSync(reportFile)) writeFileSync(join(directory, 'previous-qa-report.json'), readFileSync(reportFile), { flag: 'wx' });
writeFileSync(reportFile, JSON.stringify(receipt, null, 2) + '\n');
console.log(JSON.stringify({ directory, status: receipt.status, stats: receipt.stats, sourcesUnchanged, buildSourcesUnchanged, assetsUnchanged, historicalImagesPreserved: historical.length }));
process.exitCode = passed ? 0 : ending.exitCode || 1;
