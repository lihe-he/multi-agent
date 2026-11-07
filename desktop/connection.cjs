function attachedConnection(env = process.env) {
  const value = env.WORKBENCH_CORE_URL;
  if (!value) return null;
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('已有核心必须使用本机回环地址，且不能在地址中携带凭据。');
  const token = env.WORKBENCH_CORE_TOKEN || '';
  if (token.length < 24 || /[\r\n]/.test(token)) throw new Error('连接已有核心需要至少 24 个字符的 WORKBENCH_CORE_TOKEN。');
  return { baseUrl: url.origin, token };
}

function compatibleCoreHealth(health) {
  return health?.status === 'UP' && (health.apiVersion === 1 || (health.apiVersion === undefined && health.schemaVersion === 1));
}

module.exports = { attachedConnection, compatibleCoreHealth };
