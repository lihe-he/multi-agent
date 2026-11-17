function validatePreviewInput(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input) || typeof input.type !== 'string') throw new Error('预览动作无效。');
  const fields = { hide: [], suspend: [], bounds: ['bounds'], select: ['tabId'], open: ['tabId', 'url', 'expectedVersion'], navigate: ['tabId', 'url'], reload: ['tabId'], back: ['tabId'], forward: ['tabId'], clearSite: ['tabId'] };
  if (!Object.hasOwn(fields, input.type) || Object.keys(input).some(key => !['type', 'projectId', 'sessionId'].includes(key) && !fields[input.type].includes(key))) throw new Error('预览动作包含不支持的字段。');
  if (!['projectId', 'sessionId'].every(key => typeof input[key] === 'string' && /^[a-zA-Z0-9_-]{1,100}$/.test(input[key]))) throw new Error('预览会话范围无效。');
  if (['select', 'open', 'navigate', 'reload', 'back', 'forward', 'clearSite'].includes(input.type)
    && (typeof input.tabId !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(input.tabId))) throw new Error('预览标签编号无效。');
  if (['open', 'navigate'].includes(input.type) && (typeof input.url !== 'string' || input.url.length > 8192)) throw new Error('预览地址无效。');
  if (input.type === 'open' && (!Number.isSafeInteger(input.expectedVersion) || input.expectedVersion < 1)) throw new Error('预览配置版本无效。');
  if (input.type === 'bounds') {
    const value = input.bounds;
    if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key => !['x', 'y', 'width', 'height'].includes(key))
      || !['x', 'y', 'width', 'height'].every(key => Number.isSafeInteger(value[key]))) throw new Error('预览布局尺寸无效。');
  }
  return input;
}

module.exports = { validatePreviewInput };
