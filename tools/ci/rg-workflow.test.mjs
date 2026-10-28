import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../offline-checks/package.json', import.meta.url));
const { load } = require('js-yaml');
const rg = load(readFileSync(resolve(root, '.github/workflows/rg.yml'), 'utf8'));
const bashPath = (value) => value.replaceAll(String.fromCharCode(92), '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);

function sandbox(t) {
  const dir = mkdtempSync(join(tmpdir(), 'zoneu-rg-workflow-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  function file(path, content) {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, content, { mode: 0o755 });
  }
  return { dir, file };
}

test('rg remains dispatch-only with narrow token permissions and trusted helper checkout', () => {
  assert.deepEqual(Object.keys(rg.on), ['workflow_dispatch']);
  assert.deepEqual(rg.permissions, { contents: 'read' });
  assert.deepEqual(rg.jobs.build.permissions, { contents: 'read' });
  assert.deepEqual(rg.jobs.publish.permissions, { contents: 'write' });
  assert.equal(rg.concurrency['cancel-in-progress'], false);
  const steps = rg.jobs.publish.steps;
  const checkout = steps.find((step) => step.uses?.startsWith('actions/checkout@'));
  assert.equal(checkout.with.ref, '${{ github.sha }}');
  assert.equal(checkout.with['persist-credentials'], false);
  assert.equal(checkout.with['sparse-checkout'], 'tools/ci');
  assert.equal(steps.find((step) => step.uses?.startsWith('actions/setup-node@')).with['node-version'], '24');
  const publish = steps.find((step) => step.run?.includes('rg-publish.mjs'));
  assert.equal(publish.run, 'node tools/ci/rg-publish.mjs rg-artifacts');
  assert.equal(publish.env.GH_REPO, '${{ github.repository }}');
  assert.equal(publish.env.GH_TOKEN, '${{ github.token }}');
  assert.ok(steps.indexOf(checkout) < steps.indexOf(publish));
});

test('NDK cache and installation use an owned SDK tree, not the preinstalled path', (t) => {
  const { dir, file } = sandbox(t);
  const bin = join(dir, 'bin');
  const home = join(dir, 'preinstalled SDK');
  const sdk = join(dir, 'owned SDK');
  const calls = join(dir, 'sdk-call');
  file(join(home, 'licenses', 'android-sdk-license'), 'accepted-fixture-license\n');
  file(join(bin, 'sdkmanager'), '#!/usr/bin/env bash\nprintf "%s\n" "$@" > "$SDK_CALLS"\n');
  const job = rg.jobs.build;
  assert.ok(['${{ runner.temp }}/', '${{ github.workspace }}/'].some((prefix) => job.env.RG_ANDROID_SDK_ROOT.startsWith(prefix)));
  assert.doesNotMatch(job.env.RG_ANDROID_SDK_ROOT, /ANDROID_HOME|ANDROID_SDK_ROOT/);
  const cache = job.steps.find((step) => step.uses?.startsWith('actions/cache@'));
  assert.equal(cache.with.path, '${{ env.RG_ANDROID_SDK_ROOT }}/ndk/${{ env.NDK_VERSION }}');
  assert.match(cache.with.key, /^rg-user-owned-ndk-v1-/);
  const install = job.steps.find((step) => step.run?.includes('sdkmanager --sdk_root='));
  const result = spawnSync('bash', ['-c', `export PATH="$MOCK_BIN:$PATH"\n${install.run}`], { cwd: dir, encoding: 'utf8',
    env: { ...process.env, MOCK_BIN: bashPath(bin), SDK_CALLS: bashPath(calls), ANDROID_HOME: bashPath(home),
      RG_ANDROID_SDK_ROOT: bashPath(sdk), NDK_VERSION: job.env.NDK_VERSION } });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.deepEqual(readFileSync(calls, 'utf8').trim().split('\n'), [`--sdk_root=${bashPath(sdk)}`, `ndk;${job.env.NDK_VERSION}`]);
  assert.equal(readFileSync(join(sdk, 'licenses/android-sdk-license'), 'utf8'), 'accepted-fixture-license\n');
});

test('Rust cross-build explicitly selects stable and uses the locked crate graph', (t) => {
  const { dir, file } = sandbox(t);
  const bin = join(dir, 'bin');
  const sdk = join(dir, 'owned SDK');
  const calls = join(dir, 'cargo-call');
  const environment = join(dir, 'linker-env');
  file(join(dir, 'out/bin/rg'), 'fixture executable');
  file(join(bin, 'cargo'), '#!/usr/bin/env bash\nprintf "%s\n" "$@" > "$CARGO_CALLS"\nprintf "%s\n" "$CC_aarch64_linux_android" "$AR_aarch64_linux_android" "$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" > "$LINKER_ENV"\n');
  const steps = rg.jobs.build.steps;
  const rust = steps.find((step) => step.run?.includes('rustup toolchain install'));
  assert.match(rust.run, /rustup target add --toolchain stable aarch64-linux-android/);
  assert.match(rust.run, /rustc \+stable --version/);
  const build = steps.find((step) => step.run?.includes('cargo +stable install'));
  const result = spawnSync('bash', ['-c', `export PATH="$MOCK_BIN:$PATH"\n${build.run}`], { cwd: dir, encoding: 'utf8',
    env: { ...process.env, MOCK_BIN: bashPath(bin), CARGO_CALLS: bashPath(calls), LINKER_ENV: bashPath(environment),
      RG_ANDROID_SDK_ROOT: bashPath(sdk), NDK_VERSION: rg.jobs.build.env.NDK_VERSION, ANDROID_API: rg.jobs.build.env.ANDROID_API, VERSION: '15.2.0' } });
  assert.equal(result.status, 0, result.stdout + result.stderr);
  const args = readFileSync(calls, 'utf8').trim().split('\n');
  assert.deepEqual(args.slice(0, 6), ['+stable', 'install', 'ripgrep', '--locked', '--version', '15.2.0']);
  assert.ok(args.includes('aarch64-linux-android'));
  assert.ok(args.includes('release-lto'));
  const ndk = `${bashPath(sdk)}/ndk/${rg.jobs.build.env.NDK_VERSION}/toolchains/llvm/prebuilt/linux-x86_64/bin`;
  assert.deepEqual(readFileSync(environment, 'utf8').trim().split('\n'), [
    `${ndk}/aarch64-linux-android24-clang`, `${ndk}/llvm-ar`, `${ndk}/aarch64-linux-android24-clang`,
  ]);
});
