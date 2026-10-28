import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { requireTag } from './release-source.mjs';

// Historical workflows published factotum-* even for Tappet source. Preserve that contract.
export function artifactName(applicationId, tag) {
  requireTag(tag);
  const prefix = new Map([['com.zoneu.app', 'zoneu'], ['com.factotum.app', 'factotum'], ['com.tappet.app', 'factotum']]).get(applicationId);
  if (!prefix) throw new Error('不支持的 release applicationId');
  return `${prefix}-${tag}-arm64-v8a.apk`;
}

export function readIdentity(directory, tag, sourceSha) {
  const identity = JSON.parse(readFileSync(join(directory, 'release-identity.json'), 'utf8'));
  if (identity.sourceSha !== sourceSha || identity.tag !== tag || identity.asset !== artifactName(identity.applicationId, tag)) {
    throw new Error('release 资产身份与构建源码不匹配');
  }
  return identity;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [command, apk, directory] = process.argv.slice(2);
    if (command !== 'inspect') throw new Error('usage: release-identity.mjs inspect <apk> <artifact-directory>');
    const tool = join(process.env.ANDROID_HOME, 'cmdline-tools/latest/bin/apkanalyzer');
    const applicationId = execFileSync(tool, ['manifest', 'application-id', apk], { encoding: 'utf8' }).trim();
    const identity = { applicationId, tag: process.env.TAG, sourceSha: process.env.SOURCE_SHA,
      asset: artifactName(applicationId, process.env.TAG) };
    if (!/^[a-f0-9]{40}$/.test(identity.sourceSha ?? '')) throw new Error('缺少固定的源码 SHA');
    writeFileSync(join(directory, 'release-identity.json'), `${JSON.stringify(identity)}\n`);
    appendFileSync(process.env.GITHUB_OUTPUT, `asset=${identity.asset}\n`);
  } catch (error) { console.error(`::error::${error.message}`); process.exitCode = 1; }
}
