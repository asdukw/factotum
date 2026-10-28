import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { ASSET_NAME, publishRg } from './rg-publish.mjs';

const checksumName = `${ASSET_NAME}.sha256`;
const repo = 'fixture/project';
const root = `repos/${repo}`;
const version = '15.2.0';
const tag = `rg-${version}`;
const payload = Buffer.from([0x7f, 0x45, 0x4c, 0x46, 0, 255, 128, 10]);
const checksum = Buffer.from(`${createHash('sha256').update(payload).digest('hex')}  ${ASSET_NAME}\n`);
const ok = (stdout = '') => ({ status: 0, stdout: Buffer.isBuffer(stdout) ? stdout : Buffer.from(stdout) });
function http(code, body = {}, crlf = true) {
  const nl = crlf ? '\r\n' : '\n';
  return { status: code === 200 ? 0 : 1, stdout: Buffer.from(`HTTP/2.0 ${code} Response${nl}Content-Type: application/json${nl}${nl}${JSON.stringify(body)}`) };
}

// All gh calls are modeled in memory, without a token, remote or network.
function fixture(t, { exists = true, draft = false, assets = [[ASSET_NAME, payload], [checksumName, checksum]], fail, paginate = false } = {}) {
  const artifactDir = mkdtempSync(join(tmpdir(), 'zoneu-rg-'));
  t.after(() => rmSync(artifactDir, { recursive: true, force: true }));
  writeFileSync(join(artifactDir, ASSET_NAME), payload);
  writeFileSync(join(artifactDir, checksumName), checksum);
  let nextId = 1;
  const remote = new Map(assets.map(([name, bytes]) => [name, { id: nextId++, bytes: Buffer.from(bytes) }]));
  const state = { exists, draft, remote, calls: [] };
  const mutations = () => state.calls.filter((args) => args[0] === 'release');
  function runGh(args) {
    state.calls.push(args);
    const injected = fail?.(args, state);
    if (injected) return injected;
    if (args[0] === 'api') {
      const endpoint = args.at(-1);
      assert.ok(endpoint.startsWith(root), 'API calls explicitly locate the repository');
      if (endpoint === `${root}/releases?per_page=100`) {
        assert.deepEqual(args.slice(0, 3), ['api', '--paginate', '--slurp']);
        const value = { id: 123, tag_name: tag, draft: state.draft };
        return ok(JSON.stringify(paginate ? [[{ id: 124, tag_name: 'v1.0.0', draft: false }], state.exists ? [value] : []] : [state.exists ? [value] : []]));
      }
      if (endpoint === root) return http(200, { id: 1, full_name: repo });
      if (endpoint === `${root}/releases/123/assets?per_page=100`) {
        assert.deepEqual(args.slice(0, 3), ['api', '--paginate', '--slurp']);
        const entries = [...remote].map(([name, { id }]) => ({ name, id }));
        return ok(JSON.stringify(paginate ? [[], entries.slice(0, 1), entries.slice(1)] : [entries]));
      }
      const id = Number(endpoint.split('/').at(-1));
      const asset = [...remote.values()].find((value) => value.id === id);
      assert.ok(asset, `unknown mock asset ${endpoint}`);
      assert.ok(args.includes('Accept: application/octet-stream'));
      return ok(asset.bytes);
    }
    assert.equal(args[0], 'release');
    assert.deepEqual(args.slice(-2), ['--repo', repo]);
    assert.equal(args[2], tag);
    assert.ok(!args.includes('--clobber'));
    if (args[1] === 'create') {
      assert.equal(state.exists, false);
      assert.ok(args.includes('--draft'));
      assert.ok(args.includes('--latest=false'));
      state.exists = true;
      state.draft = true;
      return ok();
    }
    if (args[1] === 'upload') {
      assert.equal(state.draft, true, 'only drafts receive missing payloads');
      for (const path of args.slice(3, -2)) {
        const name = basename(path);
        assert.equal(remote.has(name), false, 'never overwrite an existing asset');
        remote.set(name, { id: nextId++, bytes: readFileSync(path) });
      }
      return ok();
    }
    if (args[1] === 'edit') {
      assert.ok(args.includes('--latest=false'));
      for (const [name, bytes] of [[ASSET_NAME, payload], [checksumName, checksum]]) assert.deepEqual(remote.get(name)?.bytes, bytes);
      if (state.draft) assert.ok(args.includes('--draft=false'));
      state.draft = false;
      return ok();
    }
    assert.fail(`unexpected command ${args.join(' ')}`);
  }
  return { artifactDir, state, mutations, run: (overrides = {}) => publishRg({ version, repo, artifactDir, runGh, ...overrides }) };
}

test('new release: explicit repository, draft, verified uploads, publish without Latest', (t) => {
  const f = fixture(t, { exists: false, assets: [] });
  f.run();
  assert.deepEqual(f.mutations().map((args) => args[1]), ['create', 'upload', 'edit']);
  assert.equal(f.state.draft, false);
  assert.ok(f.state.calls.some((args) => args.at(-1) === root));
});

test('identical published bytes are idempotent; pagination is fully consumed', (t) => {
  const f = fixture(t, { paginate: true });
  f.run();
  f.run();
  assert.deepEqual(f.mutations().map((args) => args[1]), ['edit', 'edit']);
  assert.ok(f.mutations().every((args) => !args.includes('--draft=false')));
  assert.equal(f.state.calls.filter((args) => args.includes('Accept: application/octet-stream')).length, 4);
});

test('changed binary is rejected even when remote sidecar matches local checksum', (t) => {
  const original = Buffer.from('different executable');
  const f = fixture(t, { assets: [[ASSET_NAME, original], [checksumName, checksum]] });
  assert.throws(() => f.run(), /Immutable release asset differs/);
  assert.deepEqual(f.state.remote.get(ASSET_NAME).bytes, original);
  assert.deepEqual(f.mutations(), []);
});

test('changed sidecar is independently rejected, with published bytes untouched', (t) => {
  const f = fixture(t, { assets: [[ASSET_NAME, payload], [checksumName, Buffer.from('false checksum')]] });
  assert.throws(() => f.run(), /Immutable release asset differs/);
  assert.deepEqual(f.mutations(), []);
  assert.deepEqual(f.state.remote.get(ASSET_NAME).bytes, payload);
});

test('a published release missing any required asset is not modified', (t) => {
  for (const assets of [[], [[ASSET_NAME, payload]], [[checksumName, checksum]]]) {
    const f = fixture(t, { assets });
    assert.throws(() => f.run(), /Published release is incomplete/);
    assert.deepEqual(f.mutations(), []);
  }
});

test('draft recovery verifies existing assets before adding only missing assets', (t) => {
  for (const assets of [[], [[ASSET_NAME, payload]], [[checksumName, checksum]], [[ASSET_NAME, payload], [checksumName, checksum]]]) {
    const f = fixture(t, { draft: true, assets });
    f.run();
    const uploads = f.mutations().filter((args) => args[1] === 'upload');
    assert.equal(uploads.length, assets.length === 2 ? 0 : 1);
    if (uploads.length) assert.equal(uploads[0].slice(3, -2).length, 2 - assets.length);
    assert.equal(f.state.draft, false);
  }
});

test('missing binary plus conflicting sidecar fails before any partial upload', (t) => {
  const f = fixture(t, { draft: true, assets: [[checksumName, Buffer.from('conflict')]] });
  assert.throws(() => f.run(), /Immutable release asset differs/);
  assert.deepEqual(f.mutations(), []);
  assert.equal(f.state.remote.has(ASSET_NAME), false);
  assert.equal(f.state.draft, true);
});

test('upload failure leaves draft private; retry resumes partial successful assets', (t) => {
  let failOnce = true;
  const f = fixture(t, { draft: true, assets: [], fail(args, state) {
    if (args[0] === 'release' && args[1] === 'upload' && failOnce) {
      failOnce = false;
      state.remote.set(ASSET_NAME, { id: 99, bytes: payload });
      return { status: 1, stdout: Buffer.alloc(0) };
    }
  } });
  assert.throws(() => f.run(), /upload failed/);
  assert.equal(f.state.draft, true);
  assert.equal(f.mutations().some((args) => args[1] === 'edit'), false);
  f.run();
  assert.equal(f.state.draft, false);
});

test('download failure or changed bytes after upload cannot publish draft', (t) => {
  for (const kind of ['download', 'changed-after-upload']) {
    let uploaded = false;
    const f = fixture(t, { draft: true, assets: kind === 'download' ? [[ASSET_NAME, payload], [checksumName, checksum]] : [],
      fail(args) {
        if (args[0] === 'release' && args[1] === 'upload') uploaded = true;
        if (args.includes('Accept: application/octet-stream')) {
          if (kind === 'download') return { status: 1, stdout: Buffer.alloc(0) };
          if (uploaded) return ok('corrupted transfer');
        }
      } });
    assert.throws(() => f.run(), /Cannot download|Immutable release asset differs/);
    assert.equal(f.mutations().some((args) => args[1] === 'edit'), false);
    assert.equal(f.state.draft, true);
  }
});

test('HTTP 401/403/500 and transport/malformed errors are never treated as not found', (t) => {
  for (const response of [http(401), http(403), http(500), { status: 1, stdout: Buffer.alloc(0) }, ok('not HTTP')]) {
    const f = fixture(t, { fail: () => response });
    assert.throws(() => f.run(), /lookup failed/);
    assert.deepEqual(f.mutations(), []);
  }
});

test('repository 404/403 fails without attempting create', (t) => {
  for (const code of [403, 404]) {
    const f = fixture(t, { exists: false, fail: (args) => args.at(-1) === root ? http(code) : undefined });
    assert.throws(() => f.run(), /lookup failed/);
    assert.deepEqual(f.mutations(), []);
  }
});

test('LF-only HTTP headers work and malformed API JSON fails closed', (t) => {
  const f = fixture(t, { fail: (args) => args.includes('--include') ? http(200, { id: 1, full_name: repo }, false) : undefined });
  f.run();
  const bad = fixture(t, { fail: () => ok('HTTP/2.0 200 OK\nContent-Type: application/json\n\n{') });
  assert.throws(() => bad.run(), SyntaxError);
  assert.deepEqual(bad.mutations(), []);
});

test('invalid local checksum, version or repository performs no API calls', (t) => {
  const f = fixture(t);
  assert.throws(() => f.run({ version: '15.2.0;command' }), /version/);
  assert.throws(() => f.run({ repo: undefined }), /GH_REPO/);
  writeFileSync(join(f.artifactDir, checksumName), 'mismatch');
  assert.throws(() => f.run(), /payload\/checksum mismatch/);
  assert.deepEqual(f.state.calls, []);
});

test('publisher entrypoint parses as ESM and invalid environment never invokes gh', () => {
  const script = fileURLToPath(new URL('./rg-publish.mjs', import.meta.url));
  const syntax = spawnSync(process.execPath, ['--check', script], { encoding: 'utf8' });
  assert.equal(syntax.status, 0, syntax.stderr);
  const result = spawnSync(process.execPath, [script], { encoding: 'utf8', env: { ...process.env, VERSION: '', GH_REPO: '' } });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Invalid ripgrep version/);
});

test('failed or malformed release listings never become a create decision', (t) => {
  for (const response of [http(404), http(403), http(500), { status: 1, stdout: Buffer.alloc(0) }, ok('{}'), ok('{')]) {
    const f = fixture(t, { exists: false, fail: (args) => args.at(-1) === `${root}/releases?per_page=100` ? response : undefined });
    assert.throws(() => f.run());
    assert.deepEqual(f.mutations(), []);
  }
});

test('failed asset listing cannot modify or publish an existing draft', (t) => {
  const f = fixture(t, { draft: true, fail: (args) => args.at(-1).includes('/assets?') ? http(500) : undefined });
  assert.throws(() => f.run(), /Cannot list/);
  assert.deepEqual(f.mutations(), []);
  assert.equal(f.state.draft, true);
});

test('create failure and publish failure propagate instead of reporting success', (t) => {
  for (const operation of ['create', 'edit']) {
    const f = fixture(t, { exists: operation !== 'create', draft: true, assets: operation === 'create' ? [] : [[ASSET_NAME, payload], [checksumName, checksum]],
      fail: (args) => args[0] === 'release' && args[1] === operation ? { status: 1, stdout: Buffer.alloc(0) } : undefined });
    assert.throws(() => f.run(), new RegExp(`${operation} failed`));
    assert.equal(f.mutations().at(-1)[1], operation);
    assert.equal(f.state.draft, true);
  }
});
