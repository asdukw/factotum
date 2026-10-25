import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { releaseProvenance as createProvenance } from './release-provenance.mjs';

export { createProvenance };

export function requireTag(tag) {
  if (typeof tag !== 'string' || tag !== tag.trim() || !/^v\d+\.\d+\.\d+$/.test(tag ?? '')) throw new Error('非法 release tag');
  return `refs/tags/${tag}`;
}

export function requireSha(sha) {
  if (typeof sha !== 'string' || sha !== sha.trim() || !/^[a-f0-9]{40}$/.test(sha ?? '')) throw new Error('缺少固定的源码 commit SHA');
  return sha;
}

function git(directory, ...args) {
  return execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

export function verifyHead(directory, sha) {
  if (git(directory, 'rev-parse', 'HEAD') !== requireSha(sha)) throw new Error('checkout HEAD 与构建源码 SHA 不一致');
}

export function resolveSource(directory, tag) {
  const ref = requireTag(tag);
  const sha = requireSha(git(directory, 'rev-parse', '--verify', `${ref}^{commit}`));
  verifyHead(directory, sha);
  const gradle = readFileSync(join(directory, 'android/app/build.gradle.kts'), 'utf8');
  const version = gradle.match(/\bversionName\s*=\s*"([^"]+)"/)?.[1];
  if (version !== tag.slice(1)) throw new Error('tag 与目标源码 versionName 不一致');
  const suffixes = ['KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'GMAIL_CLIENT_ID', 'GMAIL_CLIENT_SECRET'];
  const supported = ['FACTOTUM', 'TAPPET'].some(prefix => suffixes.every(suffix => gradle.includes(`"${prefix}_${suffix}"`)));
  if (!supported) throw new Error('不支持的历史 Gradle 签名 / Gmail 环境变量合同（支持完整 FACTOTUM_* 或 TAPPET_*）');
  const prootMarker = existsSync(join(directory, 'android/app/src/main/assets/proot-runtime.json'));
  const prootScript = existsSync(join(directory, 'tools/fetch-proot.ps1'));
  if (prootMarker !== prootScript) throw new Error('不支持的 proot 装配合同：声明与 fetch-proot.ps1 不一致');
  for (const file of ['fetch-libnode.ps1', 'fetch-rg.ps1', 'fetch-sherpa.ps1', 'bundle-dsh.ps1', 'offline-checks.sh']) {
    if (!existsSync(join(directory, 'tools', file))) throw new Error(`不支持的历史装配合同：缺 tools/${file}`);
  }
  return sha;
}

export function verifyRemoteTag(directory, tag, sourceSha) {
  verifyHead(directory, sourceSha);
  const ref = requireTag(tag);
  // Exact full refs avoid a same-named branch; ^{} peels annotated tags to their commit.
  const lines = git(directory, 'ls-remote', '--exit-code', 'origin', ref, `${ref}^{}`).split('\n');
  const refs = lines.map(line => line.split(/\s+/));
  const actual = refs.find(([, name]) => name === `${ref}^{}`)?.[0]
    ?? refs.find(([, name]) => name === ref)?.[0];
  if (actual !== requireSha(sourceSha)) throw new Error('目标 tag 已移动或不存在；拒绝将旧构建发布到新源码');
}

function main() {
  const [command, directory = '.'] = process.argv.slice(2);
  if (command === 'resolve') {
    const sha = resolveSource(directory, process.env.TAG);
    appendFileSync(process.env.GITHUB_OUTPUT, `source_sha=${sha}\n`);
    console.log(`release source: ${sha}`);
  } else if (command === 'verify-tag') {
    verifyRemoteTag(directory, process.env.TAG, process.env.SOURCE_SHA);
  } else if (command === 'provenance') {
    verifyHead(directory, process.env.SOURCE_SHA);
    verifyHead(process.env.WORKFLOW_DIRECTORY, process.env.GITHUB_WORKFLOW_SHA);
    writeFileSync(process.argv[4], `${JSON.stringify(createProvenance(process.env), null, 2)}\n`);
  } else {
    throw new Error('usage: release-source.mjs resolve|verify-tag|provenance <source-directory> [predicate-path]');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(`::error::${error.message}`); process.exitCode = 1; }
}
