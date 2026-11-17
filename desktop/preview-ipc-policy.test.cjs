const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validatePreviewInput } = require('./preview-ipc-policy.cjs');
const scope = { projectId: 'project-A', sessionId: 'session-A' };

test('Preview IPC accepts only narrow actions and bounded data', () => {
  assert.equal(validatePreviewInput({ ...scope, type: 'reload', tabId: 'tab-1' }).type, 'reload');
  assert.equal(validatePreviewInput({ ...scope, type: 'navigate', tabId: 'tab-1', url: 'https://example.test/login?code=one#callback' }).url, 'https://example.test/login?code=one#callback');
  assert.equal(validatePreviewInput({ ...scope, type: 'open', tabId: 'tab-1', url: 'https://example.test/login?code=one#callback', expectedVersion: 1 }).type, 'open');
  assert.equal(validatePreviewInput({ ...scope, type: 'bounds', bounds: { x: 0, y: 70, width: 800, height: 450 } }).type, 'bounds');
  for (const type of ['hide', 'suspend']) assert.equal(validatePreviewInput({ ...scope, type }).type, type);
  for (const input of [
    null, {}, { ...scope, type: 'attach' }, { ...scope, type: 'pageState', tabId: 'tab-1' },
    { ...scope, type: 'reload', tabId: '../../' }, { ...scope, type: 'reload', tabId: 'tab', cookies: [] },
    { ...scope, type: 'navigate', tabId: 'tab', url: 'x'.repeat(8193) },
    { ...scope, type: 'bounds', bounds: { x: 0, y: 0, width: 800, height: 450, url: 'https://example.test' } },
  ]) assert.throws(() => validatePreviewInput(input));
});

test('Preview IPC requires the original project and session for every public action', () => {
  for (const type of ['hide', 'suspend', 'bounds', 'select', 'open', 'navigate', 'reload', 'back', 'forward', 'clearSite']) {
    assert.throws(() => validatePreviewInput({ type }), /会话范围/);
    assert.throws(() => validatePreviewInput({ type, projectId: 'project-A', sessionId: '../other' }), /会话范围/);
  }
  for (const expectedVersion of [undefined, 0, -1, 1.5, '1', Number.MAX_SAFE_INTEGER + 1]) {
    assert.throws(() => validatePreviewInput({ ...scope, type: 'open', tabId: 'tab-1', url: 'https://example.test/', expectedVersion }), /配置版本/);
  }
  assert.throws(() => validatePreviewInput({ ...scope, type: 'open', tabId: 'tab-1', url: 'x'.repeat(8193), expectedVersion: 1 }), /地址/);
});
