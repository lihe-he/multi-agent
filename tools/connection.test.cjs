const { test } = require('node:test');
const assert = require('node:assert/strict');
const { attachedConnection, compatibleCoreHealth } = require('../desktop/connection.cjs');

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

test('API compatibility is independent of database schema migration', () => {
  assert.equal(compatibleCoreHealth({ status: 'UP', apiVersion: 1, schemaVersion: 2 }), true);
  assert.equal(compatibleCoreHealth({ status: 'UP', apiVersion: 1, schemaVersion: 99 }), true);
  assert.equal(compatibleCoreHealth({ status: 'UP', apiVersion: 2, schemaVersion: 1 }), false);
  assert.equal(compatibleCoreHealth({ status: 'DEGRADED', apiVersion: 1, schemaVersion: 2 }), false);
  assert.equal(compatibleCoreHealth({ status: 'UP', apiVersion: '1', schemaVersion: 1 }), false);
});

test('legacy health without API version is restricted to the known schema', () => {
  assert.equal(compatibleCoreHealth({ status: 'UP', schemaVersion: 1 }), true);
  assert.equal(compatibleCoreHealth({ status: 'UP', schemaVersion: 2 }), false);
  assert.equal(compatibleCoreHealth(null), false);
});
