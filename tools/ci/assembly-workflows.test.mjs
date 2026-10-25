import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { globSync, mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

// Reuse the existing pinned CI-only YAML parser; no dependency is added.
const require = createRequire(new URL('../offline-checks/package.json', import.meta.url));
const { load } = require('js-yaml');
const root = fileURLToPath(new URL('../../', import.meta.url));
const action = load(readFileSync(path.join(root, '.github/actions/setup-build/action.yml'), 'utf8'));
const assemble = load(readFileSync(path.join(root, '.github/workflows/assemble.yml'), 'utf8'));
const steps = action.runs.steps;
const bashPath = (value) => value.replaceAll(String.fromCharCode(92), '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
let temporary;

before(() => { temporary = mkdtempSync(path.join(tmpdir(), 'assembly-workflows-')); });
after(() => { if (temporary) rmSync(temporary, { recursive: true, force: true }); });
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, encoding: 'utf8', ...options });
  if (result.error) throw result.error;
  return result;
}
function file(location, content) {
  mkdirSync(path.dirname(location), { recursive: true });
  writeFileSync(location, content, { mode: 0o755 });
}

test('SDK installation does not restore root-owned SDK directories', () => {
  const caches = steps.filter((step) => step.uses?.startsWith('actions/cache@'));
  assert.equal(caches.length, 2);
  for (const cache of caches) assert.doesNotMatch(cache.with.path, /ANDROID_HOME|\/ndk|\/cmake/);
  const sdk = steps.find((step) => step.run?.includes('sdkmanager'));
  const bin = path.join(temporary, 'sdk-bin');
  const calls = path.join(temporary, 'sdk-calls');
  file(path.join(bin, 'sdkmanager'), '#!/usr/bin/env bash\nprintf "%s\n" "$*" >> "$SDK_CALLS"\n');
  const result = run('bash', ['-c', `set -euo pipefail\nexport PATH="$MOCK_BIN:$PATH"\n${sdk.run}`], {
    env: { ...process.env, MOCK_BIN: bashPath(bin), SDK_CALLS: bashPath(calls) },
  });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(readFileSync(calls, 'utf8').trim().split('\n'), [
    '--licenses',
    'platforms;android-37.0 build-tools;36.0.0 ndk;29.0.14206865 cmake;3.31.6',
  ]);
});

test('optional Gradle encryption key defaults to empty and passes through', () => {
  assert.equal(action.inputs['cache-encryption-key'].required, false);
  assert.equal(action.inputs['cache-encryption-key'].default, '');
  const gradle = steps.find((step) => step.uses?.startsWith('gradle/actions/setup-gradle@'));
  assert.equal(gradle.with['cache-encryption-key'], '${{ inputs.cache-encryption-key }}');
  const setup = assemble.jobs.assemble.steps.find((step) => step.uses === './.github/actions/setup-build');
  assert.equal(setup.with['cache-encryption-key'], '${{ secrets.GRADLE_ENCRYPTION_KEY }}');
});

test('download cache includes proot; dsh key only follows assembly/runtime inputs', () => {
  const caches = steps.filter((step) => step.uses?.startsWith('actions/cache@'));
  const fetch = caches.find((step) => step.with.key.startsWith('v2-fetch-cache-'));
  const dsh = caches.find((step) => step.with.key.startsWith('v4-dsh-runtime-'));
  assert.ok(fetch && dsh);
  assert.match(fetch.with.path, /\$\{\{ inputs\.source-directory \}\}\/android\/\.proot-cache/);
  assert.match(fetch.with.key, /fetch-proot\.ps1/);
  assert.equal(dsh.with.path, '${{ inputs.source-directory }}/android/.dsh-cache/runtime');
  assert.doesNotMatch(dsh.with.key, /fetch-/);
  const patterns = [...dsh.with.key.matchAll(/format\('([^']+)', inputs\.source-directory\)/g)].map((m) => m[1]);
  assert.deepEqual(patterns, ['{0}/tools/bundle-dsh.ps1', '{0}/tools/dsh-phone/**']);
  for (const cache of caches) {
    for (const input of cache.with.path.trim().split('\n')) assert.match(input.trim(), /^\$\{\{ inputs\.source-directory \}\}\//);
    assert.doesNotMatch(cache.with.key, /hashFiles\('tools\//);
  }
  const target = path.join(temporary, 'cache-target');
  const tracked = ['bundle-dsh.ps1', 'fetch-libnode.ps1', 'fetch-rg.ps1', 'fetch-sherpa.ps1', 'fetch-proot.ps1',
    'dsh-phone/profile/cordis.patch.yml', 'dsh-phone/launcher.mjs', 'dsh-phone/patch-runtime.mjs',
    'dsh-phone/plugins/android-tools/index.mjs'];
  for (const entry of tracked) file(path.join(target, 'tools', entry), entry);
  function cacheHash() {
    const hash = createHash('sha256');
    const names = patterns.flatMap((pattern) => [...globSync(pattern.replace('{0}/', ''), { cwd: target })]);
    for (const entry of names.sort().filter((entry) => statSync(path.join(target, entry)).isFile())) {
      hash.update(entry).update(readFileSync(path.join(target, entry)));
    }
    return hash.digest('hex');
  }
  const original = cacheHash();
  for (const entry of tracked.filter((entry) => entry.startsWith('fetch-'))) {
    file(path.join(target, 'tools', entry), 'changed native fetch');
    assert.equal(cacheHash(), original, entry);
  }
  for (const entry of tracked.filter((entry) => !entry.startsWith('fetch-'))) {
    file(path.join(target, 'tools', entry), 'changed runtime input');
    assert.notEqual(cacheHash(), original, entry);
    file(path.join(target, 'tools', entry), entry);
  }
});

test('trusted action runs target-pinned scripts and only declared proot', () => {
  const runtime = steps.find((step) => step.shell === 'pwsh');
  assert.equal(action.inputs['source-directory'].default, '.');
  assert.equal(runtime['working-directory'], '${{ inputs.source-directory }}');
  assert.doesNotMatch(runtime.run, /github\.action_path|scripts-directory/);
  for (const [name, declared, missingFetcher] of [['current', true, false], ['historical', false, false], ['inconsistent', true, true]]) {
    const target = path.join(temporary, `${name} target with spaces`);
    for (const script of ['fetch-libnode', 'fetch-rg', 'fetch-sherpa', 'fetch-proot', 'bundle-dsh']) {
      if (script === 'fetch-proot' && missingFetcher) continue;
      const body = '[IO.File]::AppendAllText((Join-Path (Split-Path -Parent $PSScriptRoot) "calls.txt"), "' + script + '|$PSScriptRoot`n")\n';
      file(path.join(target, 'tools', `${script}.ps1`), body);
    }
    if (declared) file(path.join(target, 'android/app/src/main/assets/proot-runtime.json'), '{}');
    const result = run('pwsh', ['-NoProfile', '-NonInteractive', '-Command', runtime.run], { cwd: target });
    const calls = readFileSync(path.join(target, 'calls.txt'), 'utf8').trim().split(/\r?\n/);
    const expected = ['fetch-libnode', 'fetch-rg', 'fetch-sherpa'];
    if (!missingFetcher) expected.push(...(declared ? ['fetch-proot'] : []), 'bundle-dsh');
    assert.deepEqual(calls.map((call) => call.split('|')[0]), expected);
    for (const call of calls) assert.equal(path.resolve(call.split('|')[1]), path.join(target, 'tools'));
    if (missingFetcher) assert.notEqual(result.status, 0);
    else assert.equal(result.status, 0, result.stdout + result.stderr);
  }
});
