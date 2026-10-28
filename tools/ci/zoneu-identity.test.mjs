import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = name => readFileSync(join(root, name), 'utf8');
const source = 'android/app/src/main/java/com/zoneu/app/';
function sources(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? sources(path) : entry.name.endsWith('.kt') ? [path] : [];
  });
}

test('Android package, components, JNI and schema share the new identity', () => {
  const gradle = read('android/app/build.gradle.kts');
  assert.match(gradle, /namespace = "com\.zoneu\.app"/);
  assert.match(gradle, /applicationId = "com\.zoneu\.app"/);
  for (const set of ['main', 'test', 'debug', 'androidTest']) {
    const path = `android/app/src/${set}/java/com/zoneu/app`;
    assert.ok(existsSync(join(root, path)));
    assert.ok(!existsSync(join(root, `android/app/src/${set}/java/com/factotum/app`)));
    for (const file of sources(join(root, path))) {
      assert.match(readFileSync(file, 'utf8'), /^package com\.zoneu\.app\b/);
    }
  }
  assert.ok(existsSync(join(root, 'android/app/schemas/com.zoneu.app.data.db.ZoneuDatabase/2.json')));
  const manifest = read('android/app/src/main/AndroidManifest.xml');
  assert.match(manifest, /android:name="\.ZoneuApplication"/);
  assert.match(manifest, /android:name="\.accessibility\.ZoneuAccessibilityService"/);
  assert.match(manifest, /android:authorities="\$\{applicationId\}\.fileprovider"/);
  const native = read('android/app/src/main/cpp/native-lib.cpp');
  for (const method of ['prepareStdio', 'startNodeWithArguments', 'setLineListener', 'writeStdin']) {
    assert.ok(native.includes(`Java_com_zoneu_app_node_NodeRuntime_${method}`));
  }
  assert.doesNotMatch(native, /Java_com_factotum/);
});

test('current storage has fresh zoneu labels and no old app read path', () => {
  const identities = {
    'data/db/ZoneuDatabaseProvider.kt': ['zoneu.db'],
    'data/crypto/PassphraseVault.kt': ['zoneu_app_keys', 'zoneu-app-db-key-v1'],
    'agent/AgentConfigStore.kt': ['zoneu_agent_settings', 'zoneu_agent_secret', 'zoneu-agent-llm-key-v1'],
    'connector/ConnectorStore.kt': ['zoneu_connector_settings', 'zoneu_connector_secret', 'zoneu-connector'],
    'agent/ApprovalSettings.kt': ['zoneu_approval_settings'],
    'device/PageMemoryStore.kt': ['zoneu_page_memory'],
    'node/NodeRuntime.kt': ['zoneu_node'],
    'schedule/ExactAlarmMonitor.kt': ['zoneu_schedule_notice'],
    'update/UpdatePreferences.kt': ['zoneu_update'],
    'accessibility/AgentOverlayController.kt': ['zoneu_agent_overlay'],
    'accessibility/OverlaySettings.kt': ['zoneu_agent_overlay'],
    'voice/VoiceSettings.kt': ['zoneu_voice'],
    'voice/VoiceSecretStore.kt': ['zoneu_voice', 'zoneu_voice_tts_key_v1', 'zoneu-voice-tts-key-v1'],
  };
  for (const [file, names] of Object.entries(identities)) {
    const text = read(source + file);
    for (const name of names) assert.ok(text.includes(`"${name}`), `${file}: ${name}`);
  }
  for (const file of sources(join(root, source))) {
    assert.doesNotMatch(readFileSync(file, 'utf8'), /Factotum|factotum|FACTOTUM|Tappet|tappet|TAPPET/);
  }
});

test('first-party runtime uses ZONEU while only the external plugin gets the legacy proot switch', () => {
  const launcher = read('tools/dsh-phone/launcher.mjs');
  assert.ok(launcher.includes("process.env.FACTOTUM_PROOT = process.env.ZONEU_PROOT ?? ''"));
  assert.doesNotMatch(read(source + 'node/NodeRuntime.kt'), /FACTOTUM_/);
  assert.doesNotMatch(read('tools/dsh-phone/profile/cordis.patch.yml'), /FACTOTUM_/);
  assert.match(read(source + 'bridge/ConnectorBridgeServer.kt'), /DOWNLOAD_SUBDIR = "zoneu"/);
  const profile = read('tools/dsh-phone/profile/cordis.patch.yml');
  assert.ok(profile.includes('x-opencode-session'));
  assert.ok(profile.includes('provider: zoneu'));
  const gradle = read('android/app/build.gradle.kts');
  assert.ok(gradle.includes('key.replaceFirst("zoneu.", "factotum.")'));
  assert.ok(gradle.includes('key.replaceFirst("ZONEU_", "FACTOTUM_")'));
});
