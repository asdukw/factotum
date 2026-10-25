import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const native = ['libnode.so', 'libc++_shared.so', 'librg.so', 'libsherpa-onnx-jni.so'].map((name) => `lib/arm64-v8a/${name}`);
const proot = ['libproot.so', 'libprootloader.so', 'libtalloc.so', 'libandroidshmem.so'].map((name) => `lib/arm64-v8a/${name}`)
  .concat('assets/proot-rootfs.bin', 'assets/proot-runtime.json');
const required = [...native, ...proot, 'assets/dsh-runtime.zip'];
const bashPath = (value) => value.replaceAll(String.fromCharCode(92), '/').replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
let temporary;
let sdk;

before(() => {
  temporary = mkdtempSync(path.join(tmpdir(), 'assembly-apk-'));
  sdk = path.join(temporary, 'sdk with spaces');
  const buildTools = path.join(sdk, 'build-tools/36.0.0');
  mkdirSync(buildTools, { recursive: true });
  writeFileSync(path.join(buildTools, 'apksigner'), '#!/usr/bin/env bash\nprintf "%s\n" "$*" >> "$SIGNER_CALLS"\nexit "${MOCK_SIGNER_STATUS:-0}"\n', { mode: 0o755 });
  const fixtures = [{ name: 'valid' }, { name: 'large', large: true }, { name: 'historical', historical: true },
    { name: 'duplicate', duplicate: proot[0] }, { name: 'fake-dsh', dsh: 'fake' },
    { name: 'truncated-dsh', dsh: 'truncated' }, { name: 'corrupt-dsh', dsh: 'corrupt' },
    { name: 'secret', secret: true }];
  for (const [index, entry] of required.entries()) {
    fixtures.push({ name: `missing-${index}`, missing: entry }, { name: `empty-${index}`, empty: entry });
  }
  for (const [index, entry] of proot.entries()) {
    fixtures.push({ name: `suffix-${index}`, suffix: entry }, { name: `injected-${index}`, historical: true, inject: entry });
  }
  // Fixtures stay separate from scripts; -I prevents Python loading planted modules.
  const python = spawnSync(process.platform === 'win32' ? 'python' : 'python3', ['-I', '-c', String.raw`
import io, json, pathlib, sys, warnings, zipfile
warnings.simplefilter('ignore', UserWarning)
base = pathlib.Path(sys.argv[1])
spec = json.load(sys.stdin)
for fixture in spec['fixtures']:
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, 'w', compression=zipfile.ZIP_STORED) as z:
        z.writestr('runtime/main.mjs', b'export default 1;' if not fixture.get('large') else bytes(range(256)) * 16384)
    nested = inner.getvalue()
    if fixture.get('dsh') == 'fake': nested = b'PKnot-a-zip'
    if fixture.get('dsh') == 'truncated': nested = nested[:-30]
    if fixture.get('dsh') == 'corrupt': nested = nested.replace(b'export default 1;', b'export default 2;')
    entries = {entry: b'nonempty fixture payload' for entry in spec['required']}
    entries['assets/dsh-runtime.zip'] = nested
    entries['assets/proot-runtime.json'] = b'{"proot":"fixture","rootfs":"sha256:fixture"}'
    entries['classes.dex'] = b'dex fixture' + (b'sk-' + b'A' * 40 if fixture.get('secret') else b'')
    if fixture.get('historical'):
        for entry in spec['proot']: del entries[entry]
    if fixture.get('inject'): entries[fixture['inject']] = b'unexpected injected payload'
    if fixture.get('missing'): del entries[fixture['missing']]
    if fixture.get('empty'): entries[fixture['empty']] = b''
    if fixture.get('suffix'): entries[fixture['suffix'] + '.bak'] = entries.pop(fixture['suffix'])
    with zipfile.ZipFile(base / (fixture['name'] + '.apk'), 'w', compression=zipfile.ZIP_STORED) as apk:
        for name, value in entries.items(): apk.writestr(name, value)
        if fixture.get('duplicate'): apk.writestr(fixture['duplicate'], b'duplicate')
valid = (base / 'valid.apk').read_bytes()
(base / 'truncated.apk').write_bytes(valid[:-30])
(base / 'corrupt.apk').write_bytes(valid.replace(b'nonempty fixture payload', b'corrupt! fixture payload', 1))
(base / 'fake.apk').write_bytes(b'PKnot-an-apk')
(base / 'empty.apk').write_bytes(b'')
`, temporary], { encoding: 'utf8', cwd: root, input: JSON.stringify({ fixtures, required, proot }) });
  if (python.error) throw python.error;
  assert.equal(python.status, 0, python.stdout + python.stderr);
});

after(() => { if (temporary) rmSync(temporary, { recursive: true, force: true }); });

function verify(name, args = [], env = {}) {
  const result = spawnSync('bash', [bashPath(path.join(root, 'tools/verify-apk.sh')), bashPath(path.join(temporary, `${name}.apk`)), ...args], {
    cwd: temporary, encoding: 'utf8',
    env: { ...process.env, ANDROID_HOME: bashPath(sdk), SIGNER_CALLS: bashPath(path.join(temporary, 'signer-calls')), ...env },
  });
  if (result.error) throw result.error;
  return { ...result, output: result.stdout + result.stderr };
}
function pass(result) { assert.equal(result.status, 0, result.output); }
function fail(result, context = '') { assert.notEqual(result.status, 0, `${context}\n${result.output}`); }

test('valid signed/unsigned APK succeeds and a large nested ZIP never SIGPIPEs', () => {
  pass(verify('valid'));
  assert.match(readFileSync(path.join(temporary, 'signer-calls'), 'utf8'), /verify --print-certs/);
  pass(verify('valid', ['--allow-unsigned'], { MOCK_SIGNER_STATUS: '91' }));
  fail(verify('valid', [], { MOCK_SIGNER_STATUS: '91' }));
  for (let index = 0; index < 3; index++) pass(verify('large', ['--allow-unsigned']));
});

test('every native, proot and dsh payload is required and nonempty', () => {
  for (const [index, entry] of required.entries()) {
    fail(verify(`missing-${index}`, ['--allow-unsigned']), `missing ${entry}`);
    fail(verify(`empty-${index}`, ['--allow-unsigned']), `empty ${entry}`);
  }
});

test('proot paths are exact and duplicate required entries are rejected', () => {
  for (const [index, entry] of proot.entries()) fail(verify(`suffix-${index}`, ['--allow-unsigned']), entry);
  fail(verify('duplicate', ['--allow-unsigned']));
});

test('fake, empty, truncated and CRC-damaged APK or nested ZIP fails', () => {
  for (const name of ['fake', 'empty', 'truncated', 'corrupt', 'fake-dsh', 'truncated-dsh', 'corrupt-dsh']) {
    fail(verify(name, ['--allow-unsigned']), name);
  }
});

test('historical proot-absent mode is explicit and rejects each injected payload', () => {
  fail(verify('historical', ['--allow-unsigned']));
  pass(verify('historical', ['--allow-unsigned', '--proot=absent']));
  for (const [index, entry] of proot.entries()) fail(verify(`injected-${index}`, ['--allow-unsigned', '--proot=absent']), entry);
  assert.equal(verify('valid', ['--proot=auto']).status, 2);
});

test('secret scan fails without printing matching contents', () => {
  pass(verify('valid', ['--allow-unsigned', '--scan-secrets']));
  const result = verify('secret', ['--allow-unsigned', '--scan-secrets']);
  fail(result);
  assert.match(result.output, /疑似密钥/);
  assert.doesNotMatch(result.output, /sk-A{40}/);
});
