import { spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function git(args, cwd) {
  const result = spawnSync('git', args, { cwd, maxBuffer: 64 * 1024 * 1024 });
  if (result.error) throw result.error;
  return result;
}

// NUL-separated paths avoid quotePath/newline ambiguities. Consume the complete diff,
// with no grep -q pipeline that can turn SIGPIPE into a false docs-only result.
export function hasCodeChanges({ base, head, eventName, cwd = process.cwd() }) {
  if (eventName === 'workflow_dispatch' || !base || /^0+$/.test(base)) return true;
  if (!/^[a-fA-F0-9]{40,64}$/.test(base)) return true;
  if (git(['cat-file', '-e', `${base}^{commit}`], cwd).status !== 0) return true;
  if (!/^[a-fA-F0-9]{40,64}$/.test(head ?? '')) throw new Error('Invalid HEAD_SHA');
  // Report both ends of renames: moving a code file into docs is still a code change.
  const diff = git(['diff', '--name-only', '--no-renames', '-z', base, head, '--'], cwd);
  if (diff.status !== 0) throw new Error('Cannot determine changed paths');
  const paths = diff.stdout.toString('utf8').split('\0').filter(Boolean);
  return paths.some((path) => !path.startsWith('docs/') && !path.endsWith('.md') && path !== 'LICENSE');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const code = hasCodeChanges({ base: process.env.BASE_SHA, head: process.env.HEAD_SHA, eventName: process.env.GITHUB_EVENT_NAME });
    if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required');
    appendFileSync(process.env.GITHUB_OUTPUT, `code=${code}\n`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
