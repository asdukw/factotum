import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import test from 'node:test'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(join(root, 'tools/offline-checks/package.json'))
const workflow = require('js-yaml').load(readFileSync(join(root, '.github/workflows/release.yml'), 'utf8'))

test('workflow separates trusted helpers and fixed source SHA with strict current proot verification', () => {
  const build = workflow.jobs.build
  const publishJob = workflow.jobs.publish
  assert.equal(build.steps.find((s) => s.with?.path === 'source').with.ref, 'refs/tags/${{ env.TAG }}')
  for (const job of [build, publishJob]) {
    assert.equal(job.steps.find((s) => s.with?.path === 'workflow-tools').with.ref, '${{ github.workflow_sha }}')
    for (const s of job.steps.filter((s) => s.uses?.startsWith('actions/checkout@'))) assert.equal(s.with['persist-credentials'], false)
  }
  assert.equal(publishJob.steps.find((s) => s.with?.path === 'source').with.ref, '${{ needs.build.outputs.source_sha }}')
  assert.equal(publishJob.env.SOURCE_SHA, '${{ needs.build.outputs.source_sha }}')
  const setupIndex = build.steps.findIndex((s) => s.uses?.endsWith('/setup-build'))
  assert.ok(build.steps.findIndex((s) => s.id === 'source') < setupIndex)
  assert.equal(build.steps[setupIndex].with['source-directory'], 'source')
  assert.equal(build.steps[setupIndex].with['cache-encryption-key'], '${{ secrets.GRADLE_ENCRYPTION_KEY }}')
  const verify = build.steps.find((s) => s.run?.includes('/tools/verify-apk.sh'))
  assert.match(verify.run, /PROOT=absent/); assert.match(verify.run, /PROOT=required/); assert.ok(verify.run.includes('--proot=$PROOT'))
  assert.doesNotMatch(verify.run, /--allow-unsigned/)
  const attestIndex = publishJob.steps.findIndex((s) => s.uses?.startsWith('actions/attest-build-provenance@'))
  assert.ok(publishJob.steps.findIndex((s) => s.run?.includes('verify-tag')) < attestIndex)
  assert.equal(publishJob.steps[attestIndex].with['predicate-type'], 'https://slsa.dev/provenance/v1')
  assert.equal(build.permissions.contents, 'read')
  assert.equal(build.environment, 'release')
  for (const job of [build, publishJob]) for (const step of job.steps) {
    if (!step.run) continue
    const result = spawnSync('bash', ['-n'], { input: step.run, encoding: 'utf8' })
    assert.equal(result.status, 0, `${step.name}: ${result.stderr}`)
  }
})
