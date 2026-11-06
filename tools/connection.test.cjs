const { test } = require('node:test');
const assert = require('node:assert/strict');
const { attachedConnection } = require('../desktop/connection.cjs');

test('attached connection accepts only an explicit local endpoint and separate token', () => {
  assert.equal(attachedConnection({}), null);
  const token = 'fixture-token-with-at-least-24-characters';
  assert.deepEqual(attachedConnection({ WORKBENCH_CORE_URL: 'http://127.0.0.1:18765/', WORKBENCH_CORE_TOKEN: token }), { baseUrl: 'http://127.0.0.1:18765', token });
  for (const url of ['https://example.com', 'http://127.0.0.1@evil.example', 'http://127.0.0.1:18765/?token=x', 'http://127.0.0.1:18765/#x', 'http://127.0.0.1:18765/path', 'file:///tmp/core']) {
    assert.throws(() => attachedConnection({ WORKBENCH_CORE_URL: url, WORKBENCH_CORE_TOKEN: token }));
  }
  assert.throws(() => attachedConnection({ WORKBENCH_CORE_URL: 'http://localhost:18765' }));
  assert.throws(() => attachedConnection({ WORKBENCH_CORE_URL: 'http://localhost:18765', WORKBENCH_CORE_TOKEN: token + '\n' }));
});
