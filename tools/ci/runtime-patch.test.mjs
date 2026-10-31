import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';

const patcher = fileURLToPath(new URL('../dsh-phone/patch-runtime.mjs', import.meta.url));
test('old cached stream patch is normalized by effect and stays single after repeated assembly', t => {
  const directory = mkdtempSync(join(tmpdir(), 'zoneu-patch-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const paths = {
    world: '@deepseek-ai/dsh-llm-pi-ai/lib/index.js',
    flock: '@deepseek-ai/node-addon-system/lib/flock.js',
    jsonl: '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
    stream: '@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js',
    welcome: '@deepseek-ai/dsh-client-ui-settings-models/lib/client.js',
  };
  function write(name, text) {
    const path = join(directory, paths[name]);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, text);
  }
  const read = name => readFileSync(join(directory, paths[name]), 'utf8');
  // The provider projection patch needs all three anchors; a fixture without them
  // fails closed by design, so keep every anchor the patcher looks for.
  write('world', [
    '\tconst adapter = new PiAiAdapter({',
    '\t\tprofiles,',
    '\t\tresolveApiKey,',
    '\t});',
    '\tasync function run() {',
    '\t\t\t\tconst iterator = toStreamChunks(snapshot.models.streamSimple(model, context, {',
    '\t\t\t\t\tbody: payload,',
    '\t\t\t\t\theaders: requestHeaders(profile.headers)',
    '\t\t\t\t}), model.contextWindow',
    '\t\t\t\t});',
    '\t}',
  ].join('\n'));
  write('flock', 'export async function tryLockExclusive(fd) {\n}\n');
  write('jsonl', 'import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";\nawait link(tmp, finalPath);');
  write('welcome', 'if (state.status === "idle" || state.status === "loading" || state.acknowledged) return null;');
  // Lifecycle patch anchors live in the same module as the stream patch; the
  // fixture must carry both or the patch fails closed.
  write('stream', [
    'class Server {',
    '\tmount(ctx) {',
    '\t\tthis.disposers.push(ctx.on("session/created", (session) => {',
    '\t\t}));',
    '\t}',
    '\tasync prompt(params) {',
    '\t\trec.handle.agent.followup(message);',
    '\t}',
    '\tdispatch(params) {',
    '\t\tswitch (params.type) {',
    '\t\t\tcase "session/prompt": return this.prompt(params);',
    '\t\t}',
    '\t}',
    '}',
    'Server',
  ].join('\n'));
  const run = () => {
    const result = spawnSync(process.execPath, [patcher, directory], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stdout + result.stderr);
  };
  run();
  // Reproduce the shipped Factotum injection, including its old marker and variable.
  for (const key of Object.keys(paths)) write(key, read(key).replaceAll('zoneu patch:', 'Factotum patch:').replaceAll('zoneuStreamStats', 'factotumStreamStats').replaceAll('[zoneu]', '[factotum]'));
  run();
  const first = read('stream');
  run();
  assert.equal(read('stream'), first);
  assert.equal((first.match(/ctx\.on\("agent\/assistant-stream"/g) ?? []).length, 2);
  assert.doesNotMatch(first, /factotumStreamStats|Factotum patch/);
  const events = new Map();
  const notices = [];
  const diagnostics = [];
  const Server = vm.runInNewContext(first, { console: { error: line => diagnostics.push(line) } });
  const server = new Server();
  server.disposers = [];
  server.transport = { notify: (...args) => notices.push(args) };
  server.mount({ on(name, handler) { events.set(name, [...(events.get(name) ?? []), handler]); return () => {}; } });
  const handlers = events.get('agent/assistant-stream');
  const agent = { session: { id: 'fixture' } };
  for (const frame of [{ type: 'chunk', chunk: { type: 'text-delta', text: 'fixture' }, attemptId: '1', index: 0, revision: 0 }, { type: 'end' }]) {
    for (const handler of handlers) handler({ agent, frame });
  }
  assert.equal(notices.length, 1);
  assert.equal(notices[0][0], 'session.stream');
  assert.equal(diagnostics.length, 1);
});
