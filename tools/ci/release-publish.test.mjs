import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { publish } from './release-publish.mjs';

const repo = 'fixture/project';
const tag = 'v0.16.0';
const asset = `factotum-${tag}-arm64-v8a.apk`;
const ok = stdout => ({ status: 0, stdout: stdout ?? '' });
function http(status, body = {}, ending = '\r\n') {
  return { status: status === 200 ? 0 : 1, stdout: `HTTP/2.0 ${status} Response${ending}Content-Type: application/json${ending}${ending}${JSON.stringify(body)}` };
}
function fixture(t, { exists = true, draft = true, notes = true, fail, verify } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'release-publish-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const sourceDirectory = join(directory, 'source');
  const artifactDirectory = join(directory, 'assets');
  mkdirSync(join(sourceDirectory, 'docs/releases'), { recursive: true });
  mkdirSync(artifactDirectory);
  if (notes) writeFileSync(join(sourceDirectory, 'docs/releases', `${tag}.md`), 'tag-local release notes');
  const bytes = Buffer.from('offline fixture APK');
  writeFileSync(join(artifactDirectory, asset), bytes);
  writeFileSync(join(artifactDirectory, `${asset}.sha256`), `${createHash('sha256').update(bytes).digest('hex')}  ${asset}\n`);
  const env = { GH_REPO: repo, TAG: tag, SOURCE_SHA: 'a'.repeat(40) };
  const state = { exists, draft, calls: [], verifications: 0, body: 'preserved historical notes' };
  const mutations = () => state.calls.filter(args => args[0] === 'release');
  const runGh = args => {
    state.calls.push(args);
    const failure = fail?.(args, state);
    if (failure) return failure;
    if (args[0] === 'api') {
      if (args.at(-1) === `repos/${repo}/releases?per_page=100`) {
        assert.deepEqual(args.slice(0, 3), ['api', '--paginate', '--slurp']);
        return ok(JSON.stringify([[], [{ id: 9, tag_name: 'rg-15.2.0', draft: false }], state.exists ? [{ id: 42, tag_name: tag, draft: state.draft, body: state.body }] : []]));
      }
      assert.equal(args.at(-1), `repos/${repo}`);
      return http(200, { full_name: repo });
    }
    assert.equal(args[0], 'release');
    assert.equal(args[2], tag);
    assert.equal(args[args.indexOf('--repo') + 1], repo);
    if (args[1] === 'upload') {
      assert.ok(args.includes('--clobber'));
      assert.ok(state.exists);
      return ok();
    }
    if (args[1] === 'create') {
      assert.ok(!state.exists);
      assert.ok(args.includes('--draft'));
      assert.ok(args.includes('--verify-tag'));
      assert.ok(args.includes('--notes-file') || args.includes('--generate-notes'));
      state.exists = true;
      state.draft = true;
      return ok();
    }
    assert.equal(args[1], 'edit');
    assert.ok(args.includes('--draft=false'));
    assert.ok(!args.includes('--verify-tag'), 'older runner gh edit need not support verify-tag');
    if (notes) {
      assert.equal(readFileSync(args[args.indexOf('--notes-file') + 1], 'utf8'), 'tag-local release notes');
      state.body = 'tag-local release notes';
    } else assert.ok(!args.includes('--notes-file'), 'retain existing/generated notes without a local notes file');
    state.draft = false;
    return ok();
  };
  const verifyTag = (source, actualTag, sha) => {
    assert.equal(source, sourceDirectory);
    assert.equal(actualTag, tag);
    assert.equal(sha, env.SOURCE_SHA);
    state.verifications++;
    verify?.(state);
  };
  return { directory, artifactDirectory, state, mutations, run: () => publish({ sourceDirectory, artifactDirectory, env, runGh, verifyTag }) };
}

test('existing draft is uploaded, notes synchronized, and publicly published; reruns remain idempotent', t => {
  const f = fixture(t);
  f.run();
  assert.equal(f.state.draft, false);
  assert.equal(f.state.body, 'tag-local release notes');
  f.run();
  assert.deepEqual(f.mutations().map(args => args[1]), ['upload', 'edit', 'upload', 'edit']);
  assert.equal(f.state.verifications, 4);
});

test('new release is created as draft, then published only after tag revalidation', t => {
  const f = fixture(t, { exists: false });
  f.run();
  assert.deepEqual(f.mutations().map(args => args[1]), ['create', 'edit']);
  assert.equal(f.state.draft, false);
  assert.equal(f.state.verifications, 2);
});

test('notes fallback preserves existing notes or generates notes for a new release', t => {
  for (const exists of [true, false]) {
    const f = fixture(t, { exists, notes: false });
    f.run();
    if (exists) assert.equal(f.state.body, 'preserved historical notes');
    else assert.ok(f.mutations()[0].includes('--generate-notes'));
  }
});

test('query network, auth, server, or malformed responses never authorize create/upload/publish', t => {
  for (const response of [
    { status: 1, stdout: '', stderr: 'offline network failure' }, http(401), http(403), http(500),
    { status: 0, stdout: '{}' }, http(200, { full_name: 'different/repository' }),
  ]) {
    const f = fixture(t, { fail: args => args[0] === 'api' ? response : null });
    assert.throws(f.run);
    assert.deepEqual(f.mutations(), []);
    assert.equal(f.state.draft, true);
  }
});

test('unreadable repository does not cause a create attempt', t => {
  const f = fixture(t, { exists: false, fail: args => args.at(-1) === `repos/${repo}` ? http(403) : null });
  assert.throws(f.run, /失败/);
  assert.deepEqual(f.mutations(), []);
});

test('upload/create failures do not publicly expose a partial release', t => {
  for (const exists of [true, false]) {
    const f = fixture(t, { exists, fail: args => args[0] === 'release' ? { status: 1, stdout: '' } : null });
    assert.throws(f.run, /未继续公开发布/);
    assert.ok(!f.mutations().some(args => args[1] === 'edit'));
    assert.equal(f.state.draft, true);
  }
});

test('tag validation failure before or after upload prevents public draft promotion', t => {
  for (const failAt of [1, 2]) {
    const f = fixture(t, { verify: state => { if (state.verifications === failAt) throw new Error('tag moved'); } });
    assert.throws(f.run, /tag moved/);
    assert.ok(!f.mutations().some(args => args[1] === 'edit'));
    if (failAt === 1) assert.deepEqual(f.mutations(), []);
  }
});

test('artifact checksum mismatch stops all remote calls', t => {
  const f = fixture(t);
  writeFileSync(join(f.artifactDirectory, asset), 'corrupted artifact');
  assert.throws(f.run, /SHA-256/);
  assert.deepEqual(f.state.calls, []);
});

 test('authenticated draft lookup consumes every page; list failures cannot create duplicate drafts', t => {
  for (const response of [
    {status:1,stdout:''}, {status:1,stdout:'HTTP/2.0 404 Not Found'},
    ok('{}'), ok(JSON.stringify([[{tag_name:tag,draft:true}]])),
    ok(JSON.stringify([[{id:1,tag_name:tag,draft:true}],[{id:2,tag_name:tag,draft:true}]])),
  ]) {
    const f=fixture(t,{fail:args=>args.includes('--paginate')?response:null});
    assert.throws(f.run);
    assert.deepEqual(f.mutations(),[]);
  }
});
