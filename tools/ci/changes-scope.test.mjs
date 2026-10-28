import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { hasCodeChanges } from './changes-scope.mjs';

const script = fileURLToPath(new URL('./changes-scope.mjs', import.meta.url));
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null', GIT_CONFIG_NOSYSTEM: '1',
  GIT_AUTHOR_NAME: 'Fixture', GIT_AUTHOR_EMAIL: 'fixture@example.invalid', GIT_COMMITTER_NAME: 'Fixture', GIT_COMMITTER_EMAIL: 'fixture@example.invalid' };

function fixture(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'zoneu-changes-'));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  function git(args, input) {
    const result = spawnSync('git', args, { cwd, env: gitEnv, input, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  }
  git(['init', '--quiet']);
  const blob = git(['hash-object', '-w', '--stdin'], 'fixture\n');
  function tree(paths) {
    const entries = new Map();
    for (const path of paths) {
      const [name, ...rest] = path.split('/');
      if (rest.length) {
        if (!entries.has(name)) entries.set(name, []);
        entries.get(name).push(rest.join('/'));
      } else entries.set(name, null);
    }
    const records = [...entries].map(([name, nested]) => nested
      ? `040000 tree ${tree(nested)}\t${name}\0`
      : `100644 blob ${blob}\t${name}\0`).join('');
    return git(['mktree', '-z'], records);
  }
  function commit(paths, parent) {
    return git(['commit-tree', tree(paths), ...(parent ? ['-p', parent] : [])], 'fixture\n');
  }
  const base = commit(['README.md', 'LICENSE']);
  return { cwd, base, commit, classify(paths, from = base) {
    return hasCodeChanges({ base: from, head: commit(paths, from), cwd, eventName: 'pull_request' });
  } };
}

test('docs, empty, code and mixed diffs retain required-check semantics', (t) => {
  const f = fixture(t);
  assert.equal(f.classify(['README.md', 'LICENSE']), false);
  assert.equal(f.classify(['README.md', 'LICENSE', 'docs/guide.txt', 'nested/note.md']), false);
  assert.equal(f.classify(['README.md', 'LICENSE', 'App.kt']), true);
  assert.equal(f.classify(['README.md', 'LICENSE', 'docs/guide.txt', 'App.kt']), true);
  assert.equal(f.classify(['README.md', 'LICENSE', '.github/workflows/ci.yml']), true);
});

test('large code lists never become docs-only (repeat beyond pipe-buffer sizes)', (t) => {
  const f = fixture(t);
  const head = f.commit(Array.from({ length: 25000 }, (_, index) => `src/LongFileName${index.toString().padStart(5, '0')}.kt`), f.base);
  for (let run = 0; run < 5; run++) {
    assert.equal(hasCodeChanges({ base: f.base, head, cwd: f.cwd, eventName: 'push' }), true);
  }
});

test('NUL paths preserve unicode, whitespace, embedded newlines and quotePath edge cases', (t) => {
  const f = fixture(t);
  assert.equal(f.classify(['README.md', 'LICENSE', 'docs/two\nlines.kt', 'notes/含 空格.md', 'quote".md']), false);
  assert.equal(f.classify(['README.md', 'LICENSE', 'two\nlines.kt']), true);
  assert.equal(f.classify(['README.md', 'LICENSE', 'docs\nnot-a-directory/file.kt']), true);
  assert.equal(f.classify(['README.md', 'LICENSE', 'notes/含 空格.kt']), true);
});

test('moving or deleting code remains a code change', (t) => {
  const f = fixture(t);
  const base = f.commit(['README.md', 'LICENSE', 'App.kt'], f.base);
  assert.equal(f.classify(['README.md', 'LICENSE', 'docs/App.kt'], base), true);
  assert.equal(f.classify(['README.md', 'LICENSE'], base), true);
});

test('dispatch, new branch and absent base fail open to running code gates', (t) => {
  const f = fixture(t);
  const head = f.base;
  for (const base of [undefined, '', '0'.repeat(40), 'f'.repeat(40), 'not-a-sha']) {
    assert.equal(hasCodeChanges({ base, head, cwd: f.cwd, eventName: 'push' }), true);
  }
  assert.equal(hasCodeChanges({ base: f.base, head, cwd: f.cwd, eventName: 'workflow_dispatch' }), true);
});

test('bad head or diff errors cannot emit code=false; CLI emits a single output', (t) => {
  const f = fixture(t);
  assert.throws(() => hasCodeChanges({ base: f.base, head: 'not-a-sha', cwd: f.cwd }), /HEAD_SHA/);
  assert.throws(() => hasCodeChanges({ base: f.base, head: 'f'.repeat(40), cwd: f.cwd }), /changed paths/);
  const output = join(f.cwd, 'github-output');
  const result = spawnSync(process.execPath, [script], { cwd: f.cwd, encoding: 'utf8',
    env: { ...process.env, BASE_SHA: f.base, HEAD_SHA: f.base, GITHUB_EVENT_NAME: 'pull_request', GITHUB_OUTPUT: output } });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(readFileSync(output, 'utf8'), 'code=false\n');
  const failed = spawnSync(process.execPath, [script], { cwd: f.cwd, encoding: 'utf8',
    env: { ...process.env, BASE_SHA: f.base, HEAD_SHA: 'f'.repeat(40), GITHUB_EVENT_NAME: 'push', GITHUB_OUTPUT: output } });
  assert.equal(failed.status, 1);
  assert.equal(readFileSync(output, 'utf8'), 'code=false\n');
});
