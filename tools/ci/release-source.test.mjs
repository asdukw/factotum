import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createProvenance, requireSha, requireTag, resolveSource, verifyRemoteTag } from './release-source.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const helper = fileURLToPath(new URL('./release-source.mjs', import.meta.url));
const suffixes = ['KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'GMAIL_CLIENT_ID', 'GMAIL_CLIENT_SECRET'];
function file(location, content) {
  mkdirSync(dirname(location), { recursive: true });
  writeFileSync(location, content);
}
function fixture(t, prefix = 'FACTOTUM', { annotated = false } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'release-source-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '-b', 'master');
  git('config', 'user.email', 'fixture@example.invalid');
  git('config', 'user.name', 'Release fixture');
  git('config', 'commit.gpgsign', 'false');
  git('config', 'tag.gpgsign', 'false');
  const gradlePath = join(directory, 'android/app/build.gradle.kts');
  file(gradlePath, `versionName = "0.16.0"\n${suffixes.map(suffix => `"${prefix}_${suffix}"`).join('\n')}\n`);
  for (const script of ['fetch-libnode.ps1', 'fetch-rg.ps1', 'fetch-sherpa.ps1', 'bundle-dsh.ps1', 'offline-checks.sh']) {
    file(join(directory, 'tools', script), '# pinned historical fixture');
  }
  git('add', 'android', 'tools');
  git('commit', '-m', 'source fixture');
  const sourceSha = git('rev-parse', 'HEAD');
  git('tag', ...(annotated ? ['-a', '-m', 'annotated fixture'] : []), 'v0.16.0');
  file(join(directory, 'branch-marker'), 'this is not the tagged source');
  git('add', 'branch-marker');
  git('commit', '-m', 'different workflow and branch');
  const eventSha = git('rev-parse', 'HEAD');
  git('branch', 'v0.16.0');
  git('remote', 'add', 'origin', directory.split(String.fromCharCode(92)).join('/'));
  git('checkout', '--detach', 'refs/tags/v0.16.0');
  return { directory, gradlePath, sourceSha, eventSha, git };
}

test('exact tag wins over same-named branch; annotated and lightweight tags resolve to commits', t => {
  for (const annotated of [false, true]) {
    const f = fixture(t, 'FACTOTUM', { annotated });
    assert.notEqual(f.sourceSha, f.eventSha);
    assert.equal(resolveSource(f.directory, 'v0.16.0'), f.sourceSha);
    verifyRemoteTag(f.directory, 'v0.16.0', f.sourceSha);
    f.git('checkout', '--detach', 'refs/heads/v0.16.0');
    assert.throws(() => resolveSource(f.directory, 'v0.16.0'), /HEAD/);
  }
});

test('deleted or moved tag fails instead of falling back to same-named branch', t => {
  const f = fixture(t);
  f.git('tag', '-d', 'v0.16.0');
  assert.throws(() => resolveSource(f.directory, 'v0.16.0'));
  assert.throws(() => verifyRemoteTag(f.directory, 'v0.16.0', f.sourceSha));
  f.git('tag', 'v0.16.0', f.eventSha);
  assert.throws(() => verifyRemoteTag(f.directory, 'v0.16.0', f.sourceSha), /tag 已移动/);
});

test('both signing generations work; unknown or version-mismatched contracts fail before setup', t => {
  for (const prefix of ['TAPPET', 'FACTOTUM']) {
    const f = fixture(t, prefix);
    assert.equal(resolveSource(f.directory, 'v0.16.0'), f.sourceSha);
    writeFileSync(f.gradlePath, readFileSync(f.gradlePath, 'utf8').replace('KEY_ALIAS', 'UNKNOWN_ALIAS'));
    assert.throws(() => resolveSource(f.directory, 'v0.16.0'), /不支持的历史 Gradle/);
    writeFileSync(f.gradlePath, 'versionName = "0.15.0"');
    assert.throws(() => resolveSource(f.directory, 'v0.16.0'), /versionName/);
  }
  assert.throws(() => requireTag('v1.0.0\nmalicious'), /非法/);
  assert.throws(() => requireTag('refs/heads/v1.0.0'), /非法/);
});

test('strict tag and SHA validation rejects trailing newlines and whitespace', () => {
  for (const suffix of [String.fromCharCode(10), String.fromCharCode(13,10), ' ', String.fromCharCode(9)]) {
    assert.throws(() => requireTag('v1.0.0'+suffix));
    assert.throws(() => requireSha('a'.repeat(40)+suffix));
  }
});

function event(sourceSha, eventSha, eventName = 'workflow_dispatch') {
  return {
    TAG: 'v0.16.0', SOURCE_SHA: sourceSha, GITHUB_WORKFLOW_SHA: eventSha,
    GITHUB_SERVER_URL: 'https://github.com', GITHUB_REPOSITORY: 'fixture/project',
    GITHUB_WORKFLOW_REF: 'fixture/project/.github/workflows/release.yml@refs/heads/master',
    GITHUB_SHA: eventSha, GITHUB_REF: 'refs/heads/master', GITHUB_EVENT_NAME: eventName,
    GITHUB_RUN_ID: '123', GITHUB_RUN_ATTEMPT: '2',
    GITHUB_REPOSITORY_ID: '456', GITHUB_REPOSITORY_OWNER_ID: '789', RUNNER_ENVIRONMENT: 'github-hosted',
  };
}

test('dispatch provenance names actual tag source, not a fictitious tag-triggered workflow', t => {
  const f = fixture(t, 'TAPPET');
  const env = event(f.sourceSha, f.eventSha);
  const predicate = createProvenance(env);
  const definition = predicate.buildDefinition;
  assert.equal(definition.buildType, 'https://slsa-framework.github.io/github-actions-buildtypes/workflow/v1');
  assert.deepEqual(definition.resolvedDependencies[0], {
    name: 'source', uri: 'git+https://github.com/fixture/project@refs/tags/v0.16.0', digest: { gitCommit: f.sourceSha },
  });
  assert.equal(definition.externalParameters.workflow.ref, 'refs/heads/master');
  assert.deepEqual(definition.externalParameters.inputs, { tag: 'v0.16.0' });
  assert.equal(definition.internalParameters.github.event_name, 'workflow_dispatch');
  assert.equal(definition.internalParameters.github.sha, f.eventSha);
  assert.equal(predicate.runDetails.builder.id, 'https://github.com/fixture/project/.github/workflows/release.yml@refs/heads/master');
  assert.equal(predicate.buildDefinition.resolvedDependencies[1].digest.gitCommit, f.eventSha);
  assert.equal(predicate.runDetails.metadata.invocationId, 'https://github.com/fixture/project/actions/runs/123/attempts/2');
  const output = join(f.directory, 'predicate.json');
  const result = spawnSync(process.execPath, [helper, 'provenance', f.directory, output], {
    env: { ...process.env, ...env, WORKFLOW_DIRECTORY: f.directory }, encoding: 'utf8',
  });
  assert.notEqual(result.status, 0, 'CLI refuses a workflow SHA not matching the trusted checkout');
  assert.match(result.stderr, /HEAD/);
});

test('tag push retains real workflow ref and omits dispatch-only inputs', () => {
  const sha = 'a'.repeat(40);
  const env = { ...event(sha, sha, 'push'), GITHUB_REF: 'refs/tags/v0.16.0', GITHUB_WORKFLOW_REF: 'fixture/project/.github/workflows/release.yml@refs/tags/v0.16.0' };
  const predicate = createProvenance(env);
  assert.equal(predicate.buildDefinition.externalParameters.workflow.ref, 'refs/tags/v0.16.0');
  assert.ok(!('inputs' in predicate.buildDefinition.externalParameters));
  assert.equal(predicate.buildDefinition.resolvedDependencies[1].digest.gitCommit, sha);
  assert.throws(() => createProvenance({ ...env, SOURCE_SHA: '' }), /SOURCE_SHA/);
  assert.throws(() => createProvenance({ ...env, GITHUB_EVENT_NAME: 'pull_request' }), /Unsupported release event/);
});

test('proot marker and fetch script must agree before any assembly', t => {
  for (const missing of ['marker', 'fetcher']) {
    const f = fixture(t);
    const marker = join(f.directory, 'android/app/src/main/assets/proot-runtime.json');
    const fetcher = join(f.directory, 'tools/fetch-proot.ps1');
    if (missing === 'marker') file(fetcher, '# target-pinned fetch');
    else file(marker, '{}');
    assert.throws(() => resolveSource(f.directory, 'v0.16.0'), /proot/);
    file(marker, '{}');
    file(fetcher, '# target-pinned fetch');
    assert.equal(resolveSource(f.directory, 'v0.16.0'), f.sourceSha);
  }
});

test('provenance rejects inconsistent events, missing identifiers and invalid workflow origins', () => {
  const env = event('a'.repeat(40), 'b'.repeat(40));
  const bad = {
    SOURCE_SHA: 'bad', GITHUB_WORKFLOW_SHA: '', GITHUB_REF: 'master', GITHUB_REPOSITORY: '../bad',
    GITHUB_SERVER_URL: 'http://github.com', GITHUB_RUN_ID: 'abc', RUNNER_ENVIRONMENT: '',
    GITHUB_WORKFLOW_REF: 'fixture/project/.github/workflows/release.yml@refs/heads/other',
  };
  for (const [name, value] of Object.entries(bad)) assert.throws(() => createProvenance({ ...env, [name]: value }), undefined, name);
  const push = { ...env, GITHUB_EVENT_NAME: 'push', GITHUB_REF: 'refs/tags/v0.16.0', GITHUB_WORKFLOW_REF: 'fixture/project/.github/workflows/release.yml@refs/tags/v0.16.0' };
  assert.throws(() => createProvenance(push), /source/);
  for (const name of ['SOURCE_SHA', 'TAG', 'GITHUB_WORKFLOW_SHA', 'GITHUB_REPOSITORY_ID']) {
    assert.throws(() => createProvenance({ ...env, [name]: env[name] + String.fromCharCode(10) }), undefined, name);
  }
});
