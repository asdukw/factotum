import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { artifactName, readIdentity } from './release-identity.mjs';

const tag = 'v1.0.0';
test('APK identity selects current or historical artifact prefix, never relabels old APKs', () => {
  assert.equal(artifactName('com.zoneu.app', tag), `zoneu-${tag}-arm64-v8a.apk`);
  for (const id of ['com.factotum.app', 'com.tappet.app']) {
    assert.equal(artifactName(id, tag), `factotum-${tag}-arm64-v8a.apk`);
  }
  for (const id of ['com.unknown.app', 'com.zoneu.app.debug', '__proto__', '']) assert.throws(() => artifactName(id, tag));
  assert.throws(() => artifactName('com.zoneu.app', 'v1.0.0\n'));
});

test('publish identity is pinned to tag, source SHA and package-specific name', t => {
  const directory = mkdtempSync(join(tmpdir(), 'release-identity-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const sha = 'a'.repeat(40);
  const identity = { applicationId: 'com.zoneu.app', tag, sourceSha: sha, asset: artifactName('com.zoneu.app', tag) };
  const file = join(directory, 'release-identity.json');
  writeFileSync(file, JSON.stringify(identity));
  assert.deepEqual(readIdentity(directory, tag, sha), identity);
  for (const patch of [{ tag: 'v1.1.0' }, { sourceSha: 'b'.repeat(40) },
    { asset: `factotum-${tag}-arm64-v8a.apk` }, { applicationId: 'com.factotum.app' }]) {
    writeFileSync(file, JSON.stringify({ ...identity, ...patch }));
    assert.throws(() => readIdentity(directory, tag, sha));
  }
});
