const { createHash } = require('node:crypto');
const { isIP } = require('node:net');

function partition(scope) {
  const values = ['serverId', 'projectId', 'sessionId', 'storageGeneration'].map(key => scope[key]);
  if (values.some(value => value === undefined || value === null || value === '')) throw new Error('预览缺少完整会话身份。');
  return 'persist:awb-preview-v1-' + createHash('sha256').update(JSON.stringify(values)).digest('hex');
}

function effectivePort(url) { return Number(url.port || (url.protocol === 'https:' || url.protocol === 'wss:' ? 443 : 80)); }

function ipv6Words(value) {
  let address = value.toLowerCase();
  const dotted = address.match(/(\d+\.\d+\.\d+\.\d+)$/);
  if (dotted) {
    const octets = dotted[1].split('.').map(Number);
    address = address.slice(0, -dotted[1].length) + `${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`;
  }
  const [left = '', right = ''] = address.split('::');
  const first = left ? left.split(':').filter(Boolean) : [], last = right ? right.split(':').filter(Boolean) : [];
  const missing = 8 - first.length - last.length;
  if (missing < 0) return [];
  return [...first.map(part => Number.parseInt(part, 16)), ...Array(missing).fill(0), ...last.map(part => Number.parseInt(part, 16))];
}

function loopback(host) {
  const value = host.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
  if (value === 'localhost' || value.endsWith('.localhost')) return true;
  if (isIP(value) === 4) return value.split('.')[0] === '127';
  if (isIP(value) !== 6) return false;
  if (value === '::1') return true;
  const words = ipv6Words(value);
  if (words.length !== 8) return false;
  const ipv4Mapped = words.slice(0, 5).every(word => word === 0) && words[5] === 0xffff && (words[6] >>> 8) === 127;
  if (ipv4Mapped) return true;
  return false;
}

function corePort(core) {
  let url;
  try { url = new URL(core.baseUrl); } catch { throw new Error('核心地址无效，已停止预览。'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error('核心地址无效，已停止预览。');
  return effectivePort(url);
}

function destination(input, scope, core, { subresource = false } = {}) {
  let url;
  try { url = new URL(input); } catch { throw new Error('请输入完整HTTP或HTTPS预览地址。'); }
  const schemes = subresource ? ['http:', 'https:', 'ws:', 'wss:'] : ['http:', 'https:'];
  if (!schemes.includes(url.protocol) || url.username || url.password) throw new Error('预览只接受不含用户名和密码的网页地址。');
  const port = effectivePort(url), managementPort = corePort(core);
  if (port === managementPort) throw new Error('核心管理端口不能在预览中访问。');
  if (loopback(url.hostname) && !scope.allowedLoopbackPorts.includes(port)) throw new Error('请先绑定该会话的本机预览端口。');
  return url.href;
}

function preferences(session) {
  return { session, nodeIntegration: false, nodeIntegrationInWorker: false, nodeIntegrationInSubFrames: false, contextIsolation: true,
    sandbox: true, webSecurity: true, webviewTag: false, devTools: false, backgroundThrottling: false, allowRunningInsecureContent: false };
}

module.exports = { partition, effectivePort, loopback, destination, preferences };
