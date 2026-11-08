import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { createInterface } from 'node:readline';

const [pidFile, initializedFile, eofFile, modeFile, callsFile, eventsFile] = process.argv.slice(2);
const record = method => { if (eventsFile) appendFileSync(eventsFile, JSON.stringify({ pid: process.pid, method, at: new Date().toISOString() }) + '\n'); };
writeFileSync(pidFile, String(process.pid));
record('started');
const input = createInterface({ input: process.stdin });
input.on('line', line => {
  const message = JSON.parse(line);
  record(message.method);
  if (message.id === undefined) return;
  const mode = modeFile ? readFileSync(modeFile, 'utf8').trim() : 'hold-initialize';
  let result = {};
  if (message.method === 'initialize') {
    writeFileSync(initializedFile, String(process.pid));
    if (mode === 'hold-initialize') return;
    result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'recovery-stdio-fixture', version: '1' } };
  }
  if (message.method === 'tools/list') result = { tools: [{ name: 'echo', description: 'Deterministic recovery fixture', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false } }] };
  if (message.method === 'tools/call') {
    appendFileSync(callsFile, JSON.stringify({ pid: process.pid, id: message.id, name: message.params.name, arguments: message.params.arguments, at: new Date().toISOString() }) + '\n');
    if (mode === 'hold-call') return;
    result = { content: [{ type: 'text', text: message.params.arguments.text }], isError: false };
  }
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: message.id, result }) + '\n');
});
input.on('close', () => { writeFileSync(eofFile, String(process.pid)); record('stdin-eof'); });
setInterval(() => {}, 1000);
