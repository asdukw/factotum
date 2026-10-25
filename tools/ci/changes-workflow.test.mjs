import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(new URL('../offline-checks/package.json', import.meta.url));
const { load } = require('js-yaml');
const workflow = (name) => load(readFileSync(resolve(root, '.github/workflows', name), 'utf8'));

test('change-scope job passes commit SHAs to the tested helper and retains required checks', () => {
  const ci = workflow('ci.yml');
  assert.equal(ci.on.push.paths, undefined);
  assert.equal(ci.on.push['paths-ignore'], undefined);
  assert.equal(ci.on.pull_request?.paths, undefined);
  assert.equal(ci.on.pull_request?.['paths-ignore'], undefined);
  const steps = ci.jobs.changes.steps;
  assert.equal(steps.find((step) => step.uses?.startsWith('actions/checkout@')).with['fetch-depth'], 0);
  assert.equal(steps.find((step) => step.uses?.startsWith('actions/setup-node@')).with['node-version'], '24');
  const filter = steps.find((step) => step.id === 'filter');
  assert.equal(filter.run, 'node tools/ci/changes-scope.mjs');
  assert.equal(filter.env.BASE_SHA, '${{ github.event.pull_request.base.sha || github.event.before }}');
  assert.equal(filter.env.HEAD_SHA, '${{ github.event.pull_request.head.sha || github.sha }}');
  assert.equal(ci.jobs.changes.outputs.code, '${{ steps.filter.outputs.code }}');
  for (const name of ['quality', 'test', 'offline-checks']) {
    assert.equal(ci.jobs[name].needs, 'changes');
    assert.equal(ci.jobs[name].if, "needs.changes.outputs.code == 'true'");
  }
});

test('quality and test wire only the optional encryption secret, with no secret-presence gate', () => {
  const ci = workflow('ci.yml');
  for (const name of ['quality', 'test']) {
    const gradle = ci.jobs[name].steps.find((step) => step.uses?.startsWith('gradle/actions/setup-gradle@'));
    assert.equal(gradle.with['cache-encryption-key'], '${{ secrets.GRADLE_ENCRYPTION_KEY }}');
    assert.equal(gradle.if, undefined);
    assert.equal(gradle.with['validate-wrappers'], true);
  }
  assert.deepEqual(ci.permissions, { contents: 'read' });
});

test('workflow-lint covers every helper/test and APK verifier and runs all offline CI tests', () => {
  const lint = workflow('workflow-lint.yml');
  for (const event of ['pull_request', 'push']) {
    for (const path of ['tools/ci/**', 'tools/verify-apk.sh', 'tools/offline-checks/**']) {
      assert.ok(lint.on[event].paths.includes(path), `${event}: ${path}`);
    }
  }
  const check = lint.jobs.lint.steps.find((step) => step.run?.includes('node --test tools/ci/*.test.mjs'));
  assert.ok(check);
  assert.match(check.run, /npm --prefix tools\/offline-checks ci --ignore-scripts --no-audit --no-fund/);
  assert.ok(check.run.indexOf('npm --prefix') < check.run.indexOf('node --test'));
  assert.deepEqual(lint.permissions, { contents: 'read' });
});
