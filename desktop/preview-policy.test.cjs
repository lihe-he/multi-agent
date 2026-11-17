const { test } = require('node:test');
const assert = require('node:assert/strict');
const { partition, destination, preferences } = require('./preview-policy.cjs');

const scope = { serverId: 'core-A', projectId: 'project-A', sessionId: 'session-A', storageGeneration: 1, allowedLoopbackPorts: [5173] };
const core = { baseUrl: 'http://127.0.0.1:18765' };

test('Persistent partition isolates core, project, session and reset generation', () => {
  const same = partition(scope); assert.equal(same, partition({ ...scope }));
  for (const [key, value] of [['serverId', 'core-B'], ['projectId', 'project-B'], ['sessionId', 'session-B'], ['storageGeneration', 2]]) assert.notEqual(same, partition({ ...scope, [key]: value }));
  assert.throws(() => partition({ ...scope, serverId: '' }));
});

test('Core port is rejected across host aliases, public DNS names and WebSocket requests', () => {
  for (const host of ['127.0.0.1', '127.1', '2130706433', 'localhost', 'other.localhost', '[::1]', '[::ffff:127.0.0.1]']) {
    for (const scheme of ['http', 'https', 'ws', 'wss']) assert.throws(() => destination(`${scheme}://${host}:18765/api/state`, { ...scope, allowedLoopbackPorts: [18765] }, core, { subresource: true }));
  }
  for (const scheme of ['http', 'https', 'ws', 'wss']) assert.throws(() => destination(`${scheme}://rebound.example.test:18765/api/state`, { ...scope, allowedLoopbackPorts: [18765] }, core, { subresource: true }));
});

test('Explicit loopback bindings apply to navigation and subrequests', () => {
  for (const host of ['127.0.0.1', '127.1', '2130706433', 'localhost', 'localhost.', 'a.localhost', '[::1]', '[::ffff:127.0.0.1]']) {
    assert.match(destination(`http://${host}:5173/`, scope, core), /^http:/);
    assert.throws(() => destination(`http://${host}:5174/`, scope, core));
  }
  assert.equal(destination('wss://localhost:5173/hmr', scope, core, { subresource: true }), 'wss://localhost:5173/hmr');
  assert.throws(() => destination('ws://localhost:5173/hmr', scope, core));
});

test('Unsupported schemes and URL credentials cannot enter the native page', () => {
  for (const url of ['file:///C:/Windows/win.ini', 'javascript:alert(1)', 'data:text/html,x', 'ftp://example.test/', 'https://user:password@example.test/']) assert.throws(() => destination(url, scope, core));
  assert.equal(destination('https://example.test/login?q=1#step', scope, core), 'https://example.test/login?q=1#step');
  assert.equal(preferences('partition').session, 'partition'); assert.equal(preferences('partition').sandbox, true);
  assert.equal(preferences('partition').nodeIntegration, false); assert.equal(Object.hasOwn(preferences('partition'), 'preload'), false);
});
