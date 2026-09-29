import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { CommandRouter } from '../src/forge/commands.ts';
import { ForgeClient } from '../src/forge/client.ts';
import { Keybindings } from '../src/forge/keybindings.ts';

const descriptor = (id, args = []) => ({ id, title: id, category: 'Test', description: '', requiresWorkspace: false, sensitive: false, undoable: false, source: 'builtin', arguments: args });

test('all TypeScript sources parse using the actual Node TypeScript transformer', () => {
  const root = new URL('../src/', import.meta.url);
  const paths = fs.readdirSync(root, { recursive: true }).filter(p => p.endsWith('.ts'));
  assert.equal(paths.length, 21);
  for (const path of paths) stripTypeScriptTypes(fs.readFileSync(new URL(path, root), 'utf8'), { mode: 'transform' });
});

test('generic command prompts, merges contextual arguments, and reaches backend', async () => {
  const calls = [];
  const router = new CommandRouter({ command: async (id, args) => { calls.push({ id, args }); return 'ok'; } });
  router.setBackendCommands([descriptor('file.rename', [{ name: 'path', type: 'string', description: 'Path' }, { name: 'newName', type: 'string', description: 'New name' }])]);
  router.provideArguments('file.rename', () => ({ path: 'old.txt' }));
  globalThis.window = { prompt: () => 'new.txt' };
  assert.equal(await router.execute('file.rename'), 'ok');
  assert.deepEqual(calls, [{ id: 'file.rename', args: { path: 'old.txt', newName: 'new.txt' } }]);
});

test('cancelled prompt never executes a command', async () => {
  let invoked = false;
  const router = new CommandRouter({ command: async () => { invoked = true; } });
  router.setBackendCommands([descriptor('sample.input', [{ name: 'value', type: 'string', description: 'Value' }])]);
  globalThis.window = { prompt: () => null };
  await router.execute('sample.input'); assert.equal(invoked, false);
});

test('extension metadata drives typed JSON arguments', async () => {
  let received;
  const router = new CommandRouter({ command: async (_, args) => { received = args; } });
  router.setBackendCommands([{ ...descriptor('sample.count', [{ name: 'count', type: 'number', description: 'Count' }]), source: 'sample' }]);
  globalThis.window = { prompt: () => '5' };
  await router.execute('sample.count'); assert.deepEqual(received, { count: 5 });
});

test('interactive save route is used for menus and raw arguments remain usable', async () => {
  let interactive = 0; let raw = 0;
  const router = new CommandRouter({ command: async () => { raw++; } });
  router.registerInteractive('file.save', () => { interactive++; });
  await router.execute('file.save'); await router.execute('file.save', { path: 'a.txt', content: 'text' });
  assert.equal(interactive, 1); assert.equal(raw, 1);
});

test('editorFocus keybinding is enforced, including unknown contexts', async () => {
  let handler; let calls = 0; let insideEditor = false;
  globalThis.Element = class { closest() { return insideEditor ? this : null; } };
  globalThis.document = { activeElement: null };
  const keys = new Keybindings({ execute: async () => { calls++; } });
  keys.bind('f2', 'rename', 'editorFocus');
  const detach = keys.attach({ addEventListener: (_, fn) => { handler = fn; }, removeEventListener: () => {} });
  const event = { key: 'F2', target: new Element(), preventDefault() {}, stopPropagation() {} };
  handler(event); assert.equal(calls, 0);
  insideEditor = true; handler(event); assert.equal(calls, 1);
  keys.bind('f2', 'rename', 'unsupportedContext'); handler(event); assert.equal(calls, 1);
  detach();
});

test('old workspace response is rejected and request retains original workspace header', async () => {
  const original = globalThis.fetch; let release; let sent;
  globalThis.fetch = async (_, options) => { sent = options.headers; return new Promise(resolve => { release = () => resolve(Response.json({ ok: true, value: { path: 'old' }, pending: false })); }); };
  try {
    const client = new ForgeClient(); client.setToken('test'); client.setWorkspace('a');
    const pending = client.query('file.read', { path: 'a.txt' });
    client.setWorkspace('b'); release();
    await assert.rejects(pending, error => error.code === 'CANCELLED');
    assert.equal(sent['X-Forge-Workspace'], 'a');
  } finally { globalThis.fetch = original; }
});

test('pending command resolves through authoritative outcome without SSE', async () => {
  const original = globalThis.fetch; const ids = [];
  globalThis.fetch = async (_, options) => {
    const body = JSON.parse(options.body); ids.push(body.id);
    return Response.json(body.id === 'search.text'
      ? { ok: true, pending: true, executionId: 'exec-1', value: null }
      : { ok: true, pending: false, value: { ok: true, pending: false, value: { matches: ['found'] } } });
  };
  try {
    const client = new ForgeClient(); client.setToken('test'); client.setWorkspace('a');
    assert.deepEqual(await client.command('search.text', { query: 'foo' }), { matches: ['found'] });
    assert.deepEqual(ids, ['search.text', 'command.result']);
  } finally { globalThis.fetch = original; }
});
