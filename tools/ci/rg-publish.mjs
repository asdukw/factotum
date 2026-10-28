import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ASSET_NAME = 'rg-aarch64-linux-android';
const CHECKSUM_NAME = `${ASSET_NAME}.sha256`;

function gh(args) {
  const result = spawnSync('gh', args, { maxBuffer: 64 * 1024 * 1024 });
  if (result.error) throw result.error;
  return result;
}

function bodyAndStatus(result) {
  const response = result.stdout.toString('utf8');
  const match = /^HTTP\/\S+\s+(\d{3})[^\r\n]*\r?\n[\s\S]*?\r?\n\r?\n([\s\S]*)$/.exec(response);
  return match ? { status: Number(match[1]), body: match[2] } : {};
}

// No --clobber or delete: a version is an immutable URL consumed with a pinned SHA.
// Download and compare actual binary bytes AND sidecar, never trust the sidecar alone.
export function publishRg({ version, repo, artifactDir, runGh = gh }) {
  if (!/^\d+\.\d+\.\d+$/.test(version ?? '')) throw new Error('Invalid ripgrep version');
  if (!/^[\w.-]+\/[\w.-]+$/.test(repo ?? '')) throw new Error('GH_REPO must identify owner/repository');
  const tag = `rg-${version}`;
  const root = `repos/${repo}`;
  const local = new Map([ASSET_NAME, CHECKSUM_NAME].map((name) => [name, readFileSync(join(artifactDir, name))]));
  const digest = createHash('sha256').update(local.get(ASSET_NAME)).digest('hex');
  if (!local.get(ASSET_NAME).length || !local.get(CHECKSUM_NAME).equals(Buffer.from(`${digest}  ${ASSET_NAME}\n`))) {
    throw new Error('Local ripgrep payload/checksum mismatch');
  }

  function api(endpoint) {
    const result = runGh(['api', '--include', endpoint]);
    const { status, body } = bodyAndStatus(result);
    if (result.status !== 0 || status !== 200) {
      throw new Error(`GitHub API lookup failed (${status ? `HTTP ${status}` : 'no HTTP response'})`);
    }
    return JSON.parse(body);
  }

  function paginated(endpoint) {
    const result = runGh(['api', '--paginate', '--slurp', endpoint]);
    if (result.status !== 0) throw new Error('Cannot list GitHub releases/assets');
    const pages = JSON.parse(result.stdout.toString('utf8'));
    if (!Array.isArray(pages) || pages.some((page) => !Array.isArray(page))) throw new Error('Invalid paginated response');
    return pages.flat();
  }

  function release() {
    // The release-by-tag API is documented for published releases. The authenticated
    // release list includes drafts, so interrupted uploads can really be resumed.
    const found = paginated(`${root}/releases?per_page=100`).filter((value) => value.tag_name === tag);
    if (found.length > 1) throw new Error('Ambiguous release tag');
    const value = found[0];
    if (value && (!Number.isSafeInteger(value.id) || value.id < 1 || typeof value.draft !== 'boolean')) {
      throw new Error('Unexpected release metadata');
    }
    return value;
  }

  function command(args) {
    // Explicit --repo also works outside a git checkout; GH_REPO is wired by rg.yml.
    const result = runGh(['release', ...args, '--repo', repo]);
    if (result.status !== 0) throw new Error(`GitHub release ${args[0]} failed`);
  }

  function compareAssets(info) {
    const assets = paginated(`${root}/releases/${info.id}/assets?per_page=100`);
    const missing = [];
    for (const [name, bytes] of local) {
      const found = assets.filter((asset) => asset.name === name);
      if (!found.length) { missing.push(name); continue; }
      if (found.length !== 1 || !Number.isSafeInteger(found[0].id)) throw new Error('Ambiguous release asset');
      const downloaded = runGh(['api', '-H', 'Accept: application/octet-stream', `${root}/releases/assets/${found[0].id}`]);
      if (downloaded.status !== 0) throw new Error(`Cannot download existing ${name}`);
      if (!bytes.equals(downloaded.stdout)) throw new Error(`Immutable release asset differs: ${name}; use a new version`);
    }
    return missing;
  }

  // A missing/inaccessible repository returns 404, not permission to create a
  // release. Only a successful complete release list can establish tag absence.
  api(root);
  let info = release();
  if (!info) {
    command(['create', tag, '--draft', '--latest=false', '--title', `ripgrep ${version} (aarch64-linux-android)`,
      '--notes', '交叉编译给 Zoneu 内嵌运行时用的 ripgrep。默认特性（无 pcre2），自包含，只依赖 bionic 的 libc/libdl。供 tools/fetch-rg.ps1 按版本下载，产物不入库。']);
    info = release();
    if (!info?.draft) throw new Error('New release is not a draft');
  }
  const missing = compareAssets(info);
  if (missing.length && !info.draft) throw new Error('Published release is incomplete; refusing to change published assets');
  if (missing.length) {
    command(['upload', tag, ...missing.map((name) => join(artifactDir, name))]);
    const current = release();
    if (!current?.draft || current.id !== info.id || compareAssets(current).length) {
      throw new Error('Draft verification failed; refusing to publish');
    }
  }
  // Only complete, byte-verified drafts become public. Existing published payloads
  // are a no-op; --latest=false also repairs auxiliary-release metadata on retries.
  command(['edit', tag, ...(info.draft ? ['--draft=false'] : []), '--latest=false']);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    publishRg({ version: process.env.VERSION, repo: process.env.GH_REPO, artifactDir: resolve(process.argv[2] ?? 'rg-artifacts') });
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
