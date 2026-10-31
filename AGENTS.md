# zoneu — AGENTS.md

Project memory for coding agents, and the only file injected every session. It holds what zoneu
is, the rules that cause damage if you don't know them, and the routing table. Depth lives in
`docs/` (Chinese). Read the matching row before starting; don't guess.

## 1. What this is

zoneu is a general-purpose mobile agent on Android: an LLM operates arbitrary apps through tool
calls. The only runtime is the Android app (Kotlin).

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| Data | Room + SQLCipher; API keys wrapped by Android Keystore |
| Agent runtime | Embedded Node 24 (nodejs-mobile fork) + dsh phone profile |
| Device execution | `AccessibilityService` (UI tree first, screenshots fallback) + `DeviceBridgeServer` (127.0.0.1 + token) |
| Tools | dsh plugin in `tools/dsh-phone/plugins/` → HTTP bridge; optional mail connector |
| Native | JNI (`app/src/main/cpp/native-lib.cpp`): Kotlin ↔ `node::Start`; needs NDK |

## 2. Ask first

Ask before a change that spans Kotlin + plugin + profile in one go, adds a dependency or permission,
deletes or replaces a shipped capability, or moves a boundary in [docs/privacy.md](docs/privacy.md).

## 3. Hard rules

These lose data, leak it, or break the only runtime. Full text: [docs/privacy.md](docs/privacy.md),
[docs/identity.md](docs/identity.md), [docs/dsh-android.md](docs/dsh-android.md).

- Secrets live only in `SecretVault` (Keystore-wrapped) and reach dsh as env at Node start. They
  never enter the repo or logs. A build artifact carries only the OAuth client secret, a public
  identifier by design ([docs/email-connector.md](docs/email-connector.md) §4.1b).
- Log counts, class names, coordinates, and error codes. Screen text, UI-tree text, chat, mail,
  card, and world-book content appear only in model tool results. `capture_screenshot` sends the
  full screen image to the model provider.
- Rename an identity only as the full group in [docs/identity.md](docs/identity.md); a partial
  rename silently orphans existing installs.
- Write tools go through the dsh approval seam (`tools/pre-execute` → `ask`). A write tool without
  it is a security regression; `policy: never` is a denial, not a grant.
- Generate, don't hand-edit `assets/dsh-runtime.zip`, `libnode/`, `jniLibs/`. Don't add to
  `detekt` `baseline.xml`; don't rewrite pushed history or force-push `master` without explicit
  approval.
- The daily-driver device runs **debug builds only**. Release has a different signature; switching
  back means uninstalling and losing the SQLCipher database and keys. Update with `adb install -r`.
- Read [docs/dsh-android.md](docs/dsh-android.md) before changing the runtime: `boot()` / the CLI
  loader, `process.exit()` / `shutdown`, and stdout logging each break the in-process Node.
- Touch exploration stays off during tasks: it swallows injected gestures and cannot rescue a
  running app ([docs/android-accessibility.md](docs/android-accessibility.md) §5).
- A new collection capability gets a documented boundary and privacy gate in `docs/` first.

## 4. Build, test, gates

From `android/`; assembly scripts run under `pwsh` 7:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain
```

`spotlessApply` fixes formatting. Setup, assembly, emulator, and CI: [docs/build.md](docs/build.md).

## 5. Done means evidence

| Change | Evidence |
|---|---|
| Kotlin | `:app:testDebugUnitTest` + the three gates above |
| UI / database / permissions | device check per [docs/device-verification.md](docs/device-verification.md) |
| dsh runtime / profile / plugin | `:app:testDebugUnitTest` + `bundle-dsh.ps1` + reinstall + one real task |
| Release | [docs/releasing.md](docs/releasing.md) checklist |

Report what actually ran, not "tests pass". CI green ≠ usable: the execution layer and the embedded
Node need a device.

## 6. Commits

Conventional commits (`feat(android): ...`, `docs: ...`); the body says what changed and how it was
verified. Run the gates and the tests that cover the change before committing, and don't re-run an
unrelated passing suite. A behavior change carries its test in the same commit. Push, merge, and
release need explicit approval. No secrets or personal data.

## 7. Where to read next

Follow-up steps (bundle, reinstall, device check) live in the doc; the row only says where to start.

| Change / question | Read |
|---|---|
| Build, assembly, emulator, gates, CI | [docs/build.md](docs/build.md) |
| Install, signatures, debug broadcasts, task audit | [docs/device-verification.md](docs/device-verification.md) |
| Renaming an app / database / prefs / provider / connector identity | [docs/identity.md](docs/identity.md) |
| Model-visible tools, calendar and alarms, `run_command`, adding a device action | [docs/tools.md](docs/tools.md) |
| Privacy, secrets, logging, storage boundaries | [docs/privacy.md](docs/privacy.md) |
| Agent runtime, embedded Node, dsh assembly, reload | [docs/dsh-android.md](docs/dsh-android.md) |
| Execution layer, visual path, text injection, incremental reads, ROM quirks | [docs/android-accessibility.md](docs/android-accessibility.md) |
| Providers, models, modalities, runtime routing | [docs/providers.md](docs/providers.md) |
| Connectors (Gmail) | [docs/email-connector.md](docs/email-connector.md) |
| Web search | [docs/web-search.md](docs/web-search.md) |
| Voice (TTS / STT, ASR decoding) | [docs/voice.md](docs/voice.md) |
| In-app updates, download failure policy | [docs/app-updater.md](docs/app-updater.md) |
| Linux on the phone (embedded Debian) | [docs/linux-on-phone.md](docs/linux-on-phone.md) |
| Memory / context injection | [docs/memory.md](docs/memory.md) |
| Roleplay, cards, presets, persona, recaps | [docs/roleplay.md](docs/roleplay.md) |
| World books | [docs/worldbooks.md](docs/worldbooks.md) |
| Scheduled tasks | [docs/scheduled-tasks.md](docs/scheduled-tasks.md) |
| `ask_user_question` | [docs/user-questions.md](docs/user-questions.md) |
| Approvals, confirmation UI, write gates | [docs/approvals.md](docs/approvals.md) |
| On-device inference | [docs/on-device-inference.md](docs/on-device-inference.md) |
| Releasing, signing | [docs/releasing.md](docs/releasing.md) |
| Something is broken | [docs/pitfalls.md](docs/pitfalls.md), [docs/Q&A.md](docs/Q&A.md) |

## 8. Editing this file

- `CLAUDE.md` imports this file; edit here only — this is the only project memory.
- One home per fact: the rule here, depth in `docs/`. A rule must be obeyable without opening the
  link; only the detail behind it moves.
- If a rule can be checked mechanically, add the test in the same change instead of more prose.
- Update the routing table when a doc is added or removed.
- Current state only: no changelog, no migration history. Delete stale rules rather than letting
  them rot.
- Keep this file under ~130 lines. New detail goes to `docs/`.
