import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const script = fileURLToPath(new URL('./release-secrets.sh', import.meta.url));
const bashPath = value => value.split(String.fromCharCode(92)).join('/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
const names = ['KEYSTORE_BASE64', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'GMAIL_CLIENT_ID', 'GMAIL_CLIENT_SECRET'].map(suffix => `FACTOTUM_${suffix}`);
const mockSecrets = Object.fromEntries(names.map(name => [name, `fixture-only-${name}`]));
function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'release-signing-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function run(command, env = {}, cwd) {
  return spawnSync('bash', [bashPath(script), command], {
    env: { ...process.env, ...mockSecrets, ...env }, cwd, encoding: 'utf8',
  });
}

test('secret preflight parses and accepts six configured values without logging any', () => {
  const syntax = spawnSync('bash', ['-n', bashPath(script)], { encoding: 'utf8' });
  assert.equal(syntax.status, 0, syntax.stderr);
  const result = run('check');
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout + result.stderr, '');
});

test('every missing secret fails and reports names, never credential values', () => {
  for (const name of names) {
    const result = run('check', { [name]: '' });
    assert.equal(result.status, 1);
    assert.ok(result.stdout.includes(name));
    for (const value of Object.values(mockSecrets)) assert.ok(!(result.stdout + result.stderr).includes(value));
    assert.equal(result.stdout.trim().split('\n').length, 1);
  }
  const result = run('check', Object.fromEntries(names.map(name => [name, ''])));
  assert.equal(result.status, 1);
  assert.equal(result.stdout.trim().split('\n').length, 6);
});

test('restore decodes one keystore and exports the same path for both signing contracts', t => {
  const directory = fixture(t);
  const output = join(directory, 'github-env');
  const bytes = Buffer.from('offline-only mock keystore');
  const result = run('restore', {
    RUNNER_TEMP: bashPath(directory), GITHUB_ENV: bashPath(output), FACTOTUM_KEYSTORE_BASE64: bytes.toString('base64'),
  });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(result.stdout + result.stderr, '');
  const entries = Object.fromEntries(readFileSync(output, 'utf8').trim().split('\n').map(line => line.split('=')));
  assert.equal(entries.FACTOTUM_KEYSTORE_FILE, entries.TAPPET_KEYSTORE_FILE);
  const file = readdirSync(directory).find(name => name.endsWith('.jks'));
  assert.deepEqual(readFileSync(join(directory, file)), bytes);
});

test('invalid or empty keystore encoding leaves no signing material or environment export', t => {
  for (const encoding of ['%%notbase64%%', '']) {
    const directory = fixture(t);
    const output = join(directory, 'github-env');
    const result = run('restore', { RUNNER_TEMP: bashPath(directory), GITHUB_ENV: bashPath(output), FACTOTUM_KEYSTORE_BASE64: encoding });
    assert.notEqual(result.status, 0);
    assert.ok(!existsSync(output));
    assert.deepEqual(readdirSync(directory), []);
    assert.ok(!result.stdout.includes(encoding) || encoding === '');
  }
});

test('mock Gradle receives identical FACTOTUM and TAPPET credentials and signed release command', t => {
  const directory = fixture(t);
  const capture = join(directory, 'captured.json');
  const captureScript = join(directory, 'capture.mjs');
  const suffixes = ['KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'GMAIL_CLIENT_ID', 'GMAIL_CLIENT_SECRET'];
  writeFileSync(captureScript, `import fs from 'node:fs';\nconst keys=${JSON.stringify(suffixes)};\nfs.writeFileSync(process.env.CAPTURE,JSON.stringify({args:process.argv.slice(2),values:Object.fromEntries(keys.map(k=>[k,[process.env['FACTOTUM_'+k],process.env['TAPPET_'+k]]]))}));\n`);
  writeFileSync(join(directory, 'gradlew'), '#!/usr/bin/env bash\nexec node "$CAPTURE_SCRIPT" "$@"\n', { mode: 0o755 });
  const env = { FACTOTUM_KEYSTORE_FILE: 'fixture-signing-identity.jks', CAPTURE: bashPath(capture), CAPTURE_SCRIPT: bashPath(captureScript) };
  const result = run('assemble', env, directory);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(result.stdout + result.stderr, '');
  const actual = JSON.parse(readFileSync(capture, 'utf8'));
  assert.deepEqual(actual.args, [':app:assembleRelease', '--console=plain']);
  for (const suffix of suffixes) {
    const expected = suffix === 'KEYSTORE_FILE' ? env.FACTOTUM_KEYSTORE_FILE : mockSecrets[`FACTOTUM_${suffix}`];
    assert.deepEqual(actual.values[suffix], [expected, expected]);
  }
});
