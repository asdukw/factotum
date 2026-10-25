// Custom predicate for pinned attest-build-provenance; SLSA v1 schema:
// https://slsa.dev/spec/v1.2/build-provenance
// This buildType explicitly models workflow_dispatch inputs:
// https://slsa-framework.github.io/github-actions-buildtypes/workflow/v1
import { writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export function releaseProvenance(env) {
  const requireValue = (name) => {
    if (typeof env[name] !== 'string' || !env[name] || env[name] !== env[name].trim()) throw new Error(`Missing ${name}`)
    return env[name]
  }
  const commit = (name) => {
    const value = requireValue(name)
    if (!/^[0-9a-f]{40}$/.test(value)) throw new Error(`Invalid ${name}`)
    return value
  }
  const tag = requireValue('TAG')
  if (!/^v[0-9]+\.[0-9]+\.[0-9]+$/.test(tag)) throw new Error('Invalid TAG')
  const sourceSha = commit('SOURCE_SHA')
  const workflowSha = commit('GITHUB_WORKFLOW_SHA')
  const eventSha = commit('GITHUB_SHA')
  const repository = requireValue('GITHUB_REPOSITORY')
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) throw new Error('Invalid GITHUB_REPOSITORY')
  const server = requireValue('GITHUB_SERVER_URL')
  const serverUrl = new URL(server)
  if (serverUrl.protocol !== 'https:' || serverUrl.username || serverUrl.password || serverUrl.origin !== server) throw new Error('Invalid GITHUB_SERVER_URL')
  const repositoryUrl = `${server}/${repository}`
  const ref = requireValue('GITHUB_REF')
  if (!/^refs\/(heads|tags)\/[^\s]+$/.test(ref)) throw new Error('Invalid GITHUB_REF')
  const workflowRef = requireValue('GITHUB_WORKFLOW_REF')
  const prefix = `${repository}/`
  const suffix = `@${ref}`
  if (!workflowRef.startsWith(prefix) || !workflowRef.endsWith(suffix)) throw new Error('Workflow identity does not match triggering ref')
  const workflowPath = workflowRef.slice(prefix.length, -suffix.length)
  if (workflowPath !== '.github/workflows/release.yml') throw new Error('Unexpected release workflow path')
  const event = requireValue('GITHUB_EVENT_NAME')
  if (!['push', 'workflow_dispatch'].includes(event)) throw new Error('Unsupported release event')
  if (event === 'push' && (ref !== `refs/tags/${tag}` || eventSha !== sourceSha)) throw new Error('Tag push does not match build source')

  for (const name of ['GITHUB_REPOSITORY_ID', 'GITHUB_REPOSITORY_OWNER_ID', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']) {
    if (!/^[1-9][0-9]*$/.test(requireValue(name))) throw new Error(`Invalid ${name}`)
  }
  if (!['github-hosted', 'self-hosted'].includes(requireValue('RUNNER_ENVIRONMENT'))) throw new Error('Invalid RUNNER_ENVIRONMENT')

  return {
    buildDefinition: {
      buildType: 'https://slsa-framework.github.io/github-actions-buildtypes/workflow/v1',
      externalParameters: {
        workflow: { ref, repository: repositoryUrl, path: workflowPath },
        ...(event === 'workflow_dispatch' ? { inputs: { tag } } : {}),
      },
      internalParameters: {
        github: {
          event_name: event,
          repository_id: requireValue('GITHUB_REPOSITORY_ID'),
          repository_owner_id: requireValue('GITHUB_REPOSITORY_OWNER_ID'),
          runner_environment: requireValue('RUNNER_ENVIRONMENT'),
          sha: eventSha,
        },
      },
      // Preserve both the triggering workflow and actual APK source identities.
      resolvedDependencies: [
        { name: 'source', uri: `git+${repositoryUrl}@refs/tags/${tag}`, digest: { gitCommit: sourceSha } },
        { name: 'workflow', uri: `git+${repositoryUrl}@${ref}`, digest: { gitCommit: workflowSha } },
      ],
    },
    runDetails: {
      builder: { id: `${server}/${workflowRef}` },
      metadata: {
        invocationId: `${repositoryUrl}/actions/runs/${requireValue('GITHUB_RUN_ID')}/attempts/${requireValue('GITHUB_RUN_ATTEMPT')}`,
      },
    },
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const output = process.argv[2]
  if (!output) throw new Error('usage: release-provenance.mjs <predicate.json>')
  writeFileSync(output, `${JSON.stringify(releaseProvenance(process.env), null, 2)}\n`)
}
