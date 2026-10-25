import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { createRequire } from 'node:module'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(resolve(root, 'tools/offline-checks/package.json'))
const yaml = require('js-yaml')
const read = (path) => readFileSync(resolve(root, path), 'utf8')
const load = (path) => yaml.load(read(path))
const workflows = readdirSync(resolve(root, '.github/workflows'))
  .filter((name) => /[.]ya?ml$/.test(name))
  .map((name) => [name, load(`.github/workflows/${name}`)])
const setup = load('.github/actions/setup-build/action.yml')
const steps = (workflow) => Object.values(workflow.jobs).flatMap((job) => job.steps ?? [])

// YAML parsing alone cannot catch the doubled-backslash release preflight regression.
test('all Bash run blocks parse, including composite actions', () => {
  const scripts = [
    ...workflows.flatMap(([name, workflow]) => steps(workflow).map((step) => [name, step])),
    ...setup.runs.steps.map((step) => ['setup-build', step]),
  ]
  for (const [name, step] of scripts) {
    if (!step.run || (step.shell && step.shell !== 'bash')) continue
    // Actions expressions are resolved before Bash runs; substitute a harmless value for syntax checking.
    const source = step.run.replace(/\$\{\{[\s\S]*?\}\}/g, 'workflow-value')
    const result = spawnSync('bash', ['-n'], { input: source, encoding: 'utf8', cwd: root })
    assert.ifError(result.error)
    assert.equal(result.status, 0, `${name}: ${step.name ?? step.id}\n${result.stderr}`)
  }
})

test('remote actions stay commit-pinned and checkout credentials are not persisted', () => {
  for (const [name, workflow] of workflows) {
    for (const step of steps(workflow)) {
      if (!step.uses || step.uses.startsWith('./')) continue
      assert.match(step.uses, /@[a-f0-9]{40}$/, `${name}: ${step.uses}`)
      if (step.uses.startsWith('actions/checkout@')) {
        assert.equal(step.with?.['persist-credentials'], false, name)
      }
    }
  }
  for (const step of setup.runs.steps) {
    if (step.uses) assert.match(step.uses, /@[a-f0-9]{40}$/)
  }
})

test('shared assembly caches the declared native runtime but not root-owned SDK directories', () => {
  const caches = setup.runs.steps.filter((step) => step.uses?.startsWith('actions/cache@'))
  assert.ok(caches.length > 0)
  assert.ok(caches.some((step) => step.with.path.includes('.proot-cache')))
  assert.ok(caches.some((step) => step.with.key.includes('fetch-proot.ps1')))
  for (const step of caches) {
    assert.doesNotMatch(step.with.path, /ANDROID_HOME|ANDROID_SDK_ROOT/)
  }
  const dsh = caches.find((step) => step.with.path.includes('.dsh-cache'))
  assert.ok(dsh)
  assert.match(dsh.with.key, /bundle-dsh[.]ps1/)
  assert.match(dsh.with.key, /tools\/dsh-phone/)
  assert.doesNotMatch(dsh.with.key, /fetch-(libnode|rg|sherpa)[.]ps1/)
  const assembly = setup.runs.steps.find((step) => step.run?.includes('fetch-libnode.ps1'))
  assert.match(assembly.run, /fetch-proot[.]ps1/)
  assert.match(assembly.run, /proot-runtime[.]json/)
  assert.equal(assembly['working-directory'], '${{ inputs.source-directory }}')
  assert.equal(setup.inputs['source-directory'].default, '.')
  assert.equal(setup.inputs['scripts-directory'], undefined)
  assert.doesNotMatch(assembly.run, /Copy-Item|scripts-directory|[.]ci-setup-tools/)
})

test('optional Gradle cache encryption has an empty-safe input and is wired to callers', () => {
  assert.equal(setup.inputs['cache-encryption-key'].required, false)
  assert.equal(setup.inputs['cache-encryption-key'].default, '')
  const gradle = setup.runs.steps.find((step) => step.uses?.startsWith('gradle/actions/setup-gradle@'))
  assert.equal(gradle.with['cache-encryption-key'], '${{ inputs.cache-encryption-key }}')
  for (const [name, workflow] of workflows) {
    for (const step of steps(workflow)) {
      if (step.uses?.startsWith('gradle/actions/setup-gradle@') || step.uses?.endsWith('/setup-build')) {
        assert.equal(step.with?.['cache-encryption-key'], '${{ secrets.GRADLE_ENCRYPTION_KEY }}', name)
      }
    }
  }
})

test('CI and release both retain the shared offline checks and workflow regression suite', () => {
  const ci = workflows.find(([name]) => name === 'ci.yml')[1]
  assert.equal(ci.on.push.paths, undefined, 'required checks cannot use workflow-level path filtering')
  assert.equal(ci.on.push['paths-ignore'], undefined)
  assert.equal(ci.on.pull_request?.['paths-ignore'], undefined)
  const gate = read('tools/offline-checks.sh')
  assert.ok(gate.indexOf('npm --prefix tools/offline-checks ci') < gate.indexOf('node --test tools/ci/*.test.mjs'))
  assert.match(gate, /node --test tools\/ci\/\*[.]test[.]mjs/)
  const lint = workflows.find(([name]) => name === 'workflow-lint.yml')[1]
  const paths = lint.on.pull_request.paths
  for (const path of ['tools/ci/**', 'tools/verify-apk.sh', 'tools/offline-checks/**']) {
    assert.ok(paths.includes(path), path)
  }
  assert.ok(steps(lint).some((step) => step.run?.includes('node --test tools/ci/*.test.mjs')))
})
