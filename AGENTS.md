# Tappet — AGENTS.md

Project memory for coding agents. Read §1–§9 before changing code; details live in `docs/`
(Chinese). §11 says which doc to load for which change.

## 1. What this is

Tappet is a general-purpose mobile agent on Android: an LLM operates arbitrary apps through tool
calls. The only runtime is the Android app (Kotlin).

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| Data | Room + SQLCipher; API keys wrapped by Android Keystore |
| Agent runtime | Embedded Node 24 (nodejs-mobile fork) + dsh phone profile |
| Device execution | `AccessibilityService` (UI tree first, screenshots fallback) + `DeviceBridgeServer` (127.0.0.1 + token) |
| Tools | dsh plugin `dsh-plugin-android-tools` (10 primitives + optional `run_command`), separate public repo, HTTP bridge → `DeviceAction`; plus a read-only mail connector |
| Linux on phone | Termux + proot Debian, `tools/termux/bridge.py` (127.0.0.1 + token) → plugin `run_command` (bypasses accessibility) |
| Native | JNI (`app/src/main/cpp/native-lib.cpp`): Kotlin ↔ `node::Start`; needs NDK |

- Dev machine: Windows + PowerShell 7. Device: OnePlus 15T (PLZ110, Android 16 / API 36, ColorOS).
- **Direction red lines (2026-10-03):** no notification capture, no QQ/WeChat chat pipelines, no
  knowledge graph, no Python side, no PC/Electron client. Deleted code must not come back. The
  hand-written `LlmClient` protocol / `AgentLoop` / chat integration is deprecated — don't extend it.

## 2. One-time setup

Environment: **JDK 21** (`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`), Node 24 + npm on the
PC, Android SDK (compileSdk 36), `ndk;29.0.14206865`, `cmake;3.31.6` (last three via `sdkmanager`).

```powershell
git submodule update --init --recursive   # device-tools plugin lives in its own repo
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so from the NDK
.\tools\fetch-sherpa.ps1                  # local TTS native lib + silero VAD model
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip
```

- Run `.ps1` scripts with **`pwsh` 7**, not `powershell` 5.1: 5.1 writes backslashed zip entries,
  the device cannot unpack the tree, and the app dies with `ERR_MODULE_NOT_FOUND`.
- Assembly scripts honor only `HTTP_PROXY` / `HTTPS_PROXY` (default `127.0.0.1:7897`); with the
  proxy off, npm fails with `ECONNREFUSED 127.0.0.1:7897`.

## 3. Build and test

Run from `android/`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\'
$env:GRADLE_OPTS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain

# one test class
.\gradlew.bat :app:testDebugUnitTest --tests "com.tappet.app.device.ScreenSnapshotRenderTest"
```

- Proxy flags must go in `GRADLE_OPTS`; inline `-D` is split by `gradlew.bat`.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk` (~160 MB).
- Emulator: `fetch-libnode.ps1 -Abi x86_64` + `assembleDebug "-Ptappet.abis=x86_64"` (quote the
  flag). **Switch jniLibs back to arm64 afterwards**, or the next device APK lacks
  `libc++_shared.so`.

## 4. Gates

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain
.\gradlew.bat spotlessApply
```

| Tool | Scope | Config | On failure |
|---|---|---|---|
| ktlint (Spotless) | formatting | `android/.editorconfig` | `spotlessApply` fixes it |
| detekt | smells / complexity | `android/config/detekt/detekt.yml` | relax the rule with a reason; **never add to `baseline.xml`** |
| Android Lint | API level / manifest / resources | `android/app/lint.xml` | errors block CI; warnings do not |

- Spotless resolves `.editorconfig` from the **Gradle project directory**, not the repo root.
- ktlint `ktlint_*` properties do not pass through Spotless; repeat them in `editorConfigOverride`.

## 5. Repository map

```
android/                  the only runtime (Kotlin / Compose)
  app/src/main/java/com/tappet/app/
    agent/ accessibility/ bridge/ connector/ data/ device/ node/ voice/ ui/
  app/src/main/cpp/       JNI (node::Start + stdout forwarding)
  app/src/test/           JVM unit tests
  app/src/androidTest/    device tests
tools/                    fetch-libnode, fetch-sherpa, bundle-dsh, check-sensitive,
                          audit/ (task traces), termux/ (bridge), dsh-phone/ (launcher,
                          profile, patches, plugins)
docs/                     deep docs + releases/<tag>.md
```

`libnode.so` and the dsh runtime (`assets/dsh-runtime.zip`, `libnode/`, `jniLibs/`) are **not
committed**; `tools/` assembles them at pinned versions (see `.gitignore`).

## 6. Hard constraints

### 6.1 Security and privacy

1. **Secrets never enter the repo, logs, or build artifacts.** The only source is Settings →
   `SecretVault` (Keystore-wrapped), passed to dsh as env `TAPPET_LLM_KEY` at Node start.
   Build-time injection is gone (`AGENT_API_KEY` no longer exists).
2. **Never log screen or chat content.** Log counts, class names, coordinates, and error codes
   only. UI-tree payloads appear only in model tool results. `--ez logPayload true` is debug-only.
3. **The visual path sends the full screen image to the model provider** (including all on-screen
   text). Treat it like a UI-tree payload: tool results only, never logged or persisted — and
   never describe it as "structure only".
4. **`tappet.db` is SQLCipher-encrypted**; the passphrase is Keystore-wrapped. Uninstalling loses
   it permanently. Export before uninstalling or clearing data.
5. **`TappetDatabaseProvider` must call `System.loadLibrary("sqlcipher")`**; otherwise `nativeOpen`
   throws `UnsatisfiedLinkError`.
6. **Do not collect other people's data.** Notification and chat pipelines were deleted. Any new
   collection capability needs a documented boundary and privacy gate in `docs/` first.

Pre-release scan — `-SelfTest` is mandatory; the scanner's identifier heuristic only warns:

```powershell
.\tools\check-sensitive.ps1                 # worktree + full history
.\tools\check-sensitive.ps1 -SelfTest       # prove the scanner can hit
.\tools\check-sensitive.ps1 -ApkPath <apk>  # scan the artifact too
```

Anything that must not be public (real identifiers, secrets, local paths) gets rewritten before
push, not later.

### 6.2 Runtime and execution

- **Keep the contract decoupled from the ROM.** The execution layer depends only on `DeviceBridge`
  and `OemProfile`. QQ/WeChat are test targets, not integrations.
- **The bridge listens on `127.0.0.1` with a per-process random token**, exposes only
  `POST /action`, and installs nothing on the device.
- **Do not enable touch exploration during tasks** (2026-10-17): it swallows injected
  `dispatchGesture` events and cannot rescue a running app. Keep the `touchExplorationActive`
  fallback branches — with TalkBack, those `performAction` branches are the only usable path.
- **Do not use `boot()` or the CLI loader** (it needs `node-addon-require-builtin`, absent on
  Android). Use `launcher.mjs`; `patch-runtime.mjs` allows flock, publishes jsonl with rename, and
  turns `agent/assistant-stream` into `session.stream`. Patches are idempotent and fail loudly on
  anchor mismatch.
- **The embedded Node is a single in-process resident instance.** stdout is JSON-RPC only (no
  stdout logger; diagnostics to stderr). Never send `shutdown` or call `process.exit()` — either
  SIGABRTs the app. Cross-app tasks need a foreground service.
- **Reload config; don't restart.** Write `dsh-home/runtime-config.json` (non-secret) and send
  `runtime.reload`; the launcher remounts the cordis root. Keys are preseeded as
  `TAPPET_LLM_KEY_<ID>` and never written to disk. After reload, re-`initialize` and rotate the
  session namespace (`runtimeTag`).

### 6.3 Naming and storage identity

Renaming anything below silently breaks existing installs. Change all of them together:

| Location | Role |
|---|---|
| `app/build.gradle.kts` → `applicationId` | app identity (uid) |
| `TappetDatabaseProvider.DATABASE_NAME` | database file |
| `PassphraseVault.prefsName` | SharedPreferences file holding the wrapped passphrase |
| `PassphraseVault.keyAlias` | Keystore alias |

Also: `SecretVault` `prefsName` / `keyAlias` and `AgentConfigStore.PREFS_NAME`; provider ids
(`LlmPreset.id`) in `provider.<id>.*` and `tappet-agent-llm-key-v1-<id>`; connector names
`tappet_connector_settings`, `tappet_connector_secret-<id>`, `tappet-connector-<id>-v1`, and the
`<id>_client_id` / `<id>_client_secret` / `<id>_account` keys (the leading underscore matters).
Components use `com.tappet.app/...`; `OemProfiles.TAPPET_ACCESSIBILITY_COMPONENT` reads
`BuildConfig.APPLICATION_ID`.

The public git history was rebuilt on 2026-10-12 (backup:
`../memex-history-backup-2026-10-12.bundle`). The old package `com.memex.app` cannot read the new
app's data; `adb uninstall com.memex.app` is enough. Scrub real-world data before committing.

## 7. Workflows

### 7.1 Change Kotlin

1. Change code; add a `src/test` (or `src/androidTest`) test — behavior changes need tests.
2. Run `:app:testDebugUnitTest`, then §4 gates.
3. For UI, database, or permission changes, verify on a device (§8).

### 7.2 Change the dsh runtime, profile, or a plugin

1. Plugins live in `tools/dsh-phone/plugins/` (submodule, separate public repo). Commit there
   first, then update the submodule pointer here.
2. Run `.\tools\bundle-dsh.ps1` — it validates the profile with the real parser; broken YAML kills
   the app because Node runs in-process.
3. Run `:app:testDebugUnitTest` and reinstall the APK (the runtime is re-extracted on first launch).
4. Plugin releases come from the plugin repo's `v*` tag workflow.

### 7.3 Add or change a device action

One atomic action touches four places: `device/DeviceAction.kt` (definition),
`accessibility/TappetAccessibilityService` (execution), `bridge/DeviceBridgeServer` (HTTP mapping),
and `tools/dsh-phone/plugins/android-tools/index.mjs` (`ctx.tools` + model-facing description).
Then `bundle-dsh.ps1` and reinstall.

Model-visible tools (10): `read_ui_tree`, `tap_node(ref)`, `long_press(ref,duration_ms)`,
`capture_screenshot()`, `tap_target(screenshot_id,x1,y1,x2,y2)`, `long_press_target(...)`,
`type_text`, `press(back|home|recents|enter)`, `launch_app(query)`, `swipe(direction)`. With Linux
configured add `run_command`; with a mail connector add three read-only mail tools.

- **Coordinate contract:** the model uses only the **image pixels of the latest
  `capture_screenshot`** (1080×2344 → 943×2047), never screen pixels.
  `device/ScreenFrame.kt#toScreen` is the single conversion point. Screenshots are stateful
  (`STALE_SCREENSHOT`); out-of-bounds boxes are rejected (`OUT_OF_SCREENSHOT`), never clamped.
- **Text input:** UI tree first; if unreadable, use the accessibility text-injection channel
  (`flagInputMethodEditor`, API 33+, no IME switch). `text_no_channel` = tap the field first;
  `text_flag_missing` = re-enable accessibility after the upgrade.

Details: `docs/android-accessibility.md` §9–§10.

### 7.4 Add or change a provider / model

Four places: `LlmPreset` → settings UI → `AgentConfigStore` → `NodeRuntime` + profile. Then
`bundle-dsh.ps1` + reinstall. Endpoint, call style, and model must match as a set; the `PROVIDER`
key must match the profile key exactly. Full rules: `docs/providers.md`.

### 7.5 Other subsystems

| Change | Read first | Then |
|---|---|---|
| Connectors (Gmail etc.) | `docs/email-connector.md` | plugin name in three places + `bundle-dsh.ps1` + reinstall |
| Web search | `docs/web-search.md` | `bundle-dsh.ps1` + reinstall; verify the search model actually searches |
| Voice (TTS/STT) | `docs/voice.md` | unit tests; device check per §9 |
| In-app updates | `docs/app-updater.md` | `UpdateDownloaderResumeTest` |
| Linux on the phone | `docs/termux.md` | plugin commit first + `bundle-dsh.ps1` |
| Memory / context injection | `docs/memory.md` | — |
| Scheduled tasks | `docs/scheduled-tasks.md` | — |
| `ask_user_question` | `docs/user-questions.md` | — |
| On-device inference | `docs/on-device-inference.md` | — |

STT invariants that break quietly: recognition runs **per VAD segment** (`RecognizedText.joinSegments`
joins the texts — never concatenate the session into one decode), and the punctuation model is a
**companion** of Paraformer (`AsrModel.companions`) that installs with it and has no settings entry.
`decodingMethod` / `blankPenalty` are dead knobs for these three models (only `greedy_search` is
accepted; anything else exits the process) — details in `docs/voice.md` §8.1–8.2.

Downloads fail closed and stop: no auto-retry, actionable reason via `UpdateHttp.failReason`, and a
short body is an error (`TRANSFER_TRUNCATED`), not a finished file. See `docs/pitfalls.md`.

### 7.6 Release

Bump `versionCode` / `versionName` → write `docs/releases/<tag>.md` and update `CHANGELOG.md` →
commit → tag `v*`. The in-app version comes from `build.gradle.kts`, not the tag. Full process and
signing material: `docs/releasing.md`.

## 8. Verification

### 8.1 Device install and tests

Never use Gradle's automatic install: ColorOS blocks a first install, then reports
`Starting 0 tests` + `Failed to install split APK(s)` after ~5 minutes.

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r -g app\build\outputs\apk\debug\app-debug.apk
adb shell settings put secure enabled_accessibility_services com.tappet.app/.accessibility.TappetAccessibilityService
adb shell settings put secure accessibility_enabled 1
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

- `adb install -r` disables the accessibility service; re-enable it after every install.
- `pm grant` / `appops set` are blocked by ColorOS. The app only needs `INTERNET`.
- Only the user can enable accessibility. Prompt **only after a real device action has failed**,
  driven by `BridgeHealth` codes, not Chinese UI strings.
- ColorOS shows a "keep running" dialog (3 s) after enable/reinstall and defers background
  broadcasts — bring the app to the foreground (`monkey`) first. `uiautomator dump` displaces the
  accessibility service for ~1 s; avoid it during automation.
- Log tags: `TappetDevice`, `TappetNode`, `TappetAudit` (`key=value`; rename with `AUDIT_TAG`),
  `NODEJS-MOBILE` (Node stderr; stdout is protocol).
- **CI green ≠ usable.** The execution layer, embedded Node startup, and real tasks need a device.

### 8.2 Debug broadcasts (debug builds only)

```powershell
# Start the resident runtime once
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op START
# Submit a task; add --ez logPayload true to print assistant text
adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op TASK --es task 'task text'"
# Drive the execution layer
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action READ_SCREEN
# Visual path: capture, then tap image pixels
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action CAPTURE_FRAME
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action TAP_TARGET --es screenshot_id f1 --ei x1 400 --ei y1 1000 --ei x2 543 --ei y2 1047
```

Other entries (`TEXT_CHANNEL`, `IME_TEXT`, `IME_ENTER`, `TERMUX`, voice `ASR`/`VAD`) are in
`docs/android-accessibility.md` §10, `docs/termux.md`, and `docs/voice.md` §9. Quote the whole
`am broadcast` with `adb shell "..."` when arguments contain spaces.

### 8.3 Task audit

```powershell
node tools/audit/selftest.mjs
node tools/audit/run-suite.mjs
node tools/audit/collect.mjs --label <name>
```

`tools/audit/` collects; `.claude/agents/tappet-auditor.md` judges (details: `tools/audit/README.md`).
`tools/audit/runs/` contains screen text — treat it as sensitive; reports carry structure only.
`suite.json`: changing task text invalidates history; add new ids. Keep `runs/index.json` across runs.
Never hardcode the package or session directory; read `applicationId`. Keep durable artifacts in
`tools/` or `docs/`, not `tmp/`.

## 9. Commit and PR conventions

- Conventional commits (`feat(android): ...`, `docs: ...`, `fix(repo): ...`); the body states what
  changed and how it was verified. No secrets or personal data.
- Develop on a branch; merge back with `git merge --no-ff` or a PR. Don't develop on `master`.
- Run gates and unit tests before committing; don't commit unverified work.
- Merging a PR and pushing require explicit user approval.

## 10. Troubleshooting

Full table: `docs/pitfalls.md`.

| Symptom | Cause / fix |
|---|---|
| First Gradle install hangs ~5 min then fails | ColorOS blocks the install channel → `adb install -r` manually |
| Accessibility dies after install | `install -r` removes the service → re-run `settings put secure` |
| `dlopen failed: libc++_shared.so not found` | Re-run `fetch-libnode.ps1`; confirm the file is in `jniLibs/<abi>/` |
| App crashes at task end (FORTIFY/SIGABRT) | `process.exit()` (including SDK `shutdown`) → record, never exit |
| Broadcast stalls at `debug task start` | Cached app freezer → bring the app to the foreground with `am start` |
| JSON-RPC stops responding | Logs on stdout → no stdout logger in the phone profile |
| `bad indentation of a mapping entry`, app crashes | `!!js` object literal in the profile → quote the whole expression |
| No readable UI nodes (WeChat) | Tree hidden → visual path (`EMPTY_TREE_HINT`) |
| Taps land ~12.6% left/up | Use `tap_target` image pixels, not screen pixels |
| `type_text` fails with an unreadable tree | Read the code: `text_no_channel` → tap the field first |
| `ERR_MODULE_NOT_FOUND` after install | `bundle-dsh.ps1` ran under PowerShell 5.1 → use `pwsh` 7 |
| Push rejected with `GH013` | Distinguish PR rules from push protection; the latter means GitHub sees a secret |
| Model download says the network is unreachable | Phone has no VPN → tap **retry** on that row; it resumes from the partial file. No auto-retry by design (`UpdateHttp.failReason`) |

## 11. Docs index

| Doc | Read when |
|---|---|
| `docs/dsh-android.md` | Changing the agent runtime, embedded Node, or dsh assembly |
| `docs/android-accessibility.md` | Changing the execution layer, visual path, text injection, or device/ROM |
| `docs/providers.md` | Changing providers, models, modalities, or runtime routing |
| `docs/voice.md` | Changing TTS/STT: engine trade-offs, model licenses, VAD thresholds, device checks |
| `docs/email-connector.md` | Changing connectors: boundaries, privacy gates, OAuth registration |
| `docs/web-search.md` | Changing web search: protocol gate, real-search gate, privacy |
| `docs/app-updater.md` | Changing in-app updates: resume details, verification |
| `docs/termux.md` | Changing Linux-on-phone: token delivery, script constraints, ColorOS lifecycle |
| `docs/memory.md` | Changing memory / context injection |
| `docs/scheduled-tasks.md` | Changing scheduled tasks |
| `docs/user-questions.md` | Changing `ask_user_question` |
| `docs/on-device-inference.md` | Changing on-device models / inference |
| `docs/releasing.md` | Changing workflows, releasing, or signing material |
| `docs/pitfalls.md` | Troubleshooting |
| `docs/roadmap.md` | Planning direction (unverified hypotheses, not commitments) |
| `docs/releases/<tag>.md` | Release notes |
| `README.md` / `CHANGELOG.md` | Public overview and visible changes |
