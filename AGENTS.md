# Tappet — AGENTS.md

Project memory for coding agents. Read §1–§9 before changing code; details live in `docs/`
(Chinese). §11 says which doc to load for which change; §12 governs edits to this file.

## 1. What this is

Tappet is a general-purpose mobile agent on Android: an LLM operates arbitrary apps through tool
calls. The only runtime is the Android app (Kotlin).

| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Navigation 3 |
| Data | Room + SQLCipher; API keys wrapped by Android Keystore |
| Agent runtime | Embedded Node 24 (nodejs-mobile fork) + dsh phone profile |
| Device execution | `AccessibilityService` (UI tree first, screenshots fallback) + `DeviceBridgeServer` (127.0.0.1 + token) |
| Tools | dsh plugin `dsh-plugin-android-tools` (10 primitives + `run_command`), separate public repo, HTTP bridge → `DeviceAction`; plus a mail connector (read tools + approval-gated writes) |
.\tools\fetch-proot.ps1                   # 内嵌 proot + Debian rootfs（见 docs/linux-on-phone.md）
| Native | JNI (`app/src/main/cpp/native-lib.cpp`): Kotlin ↔ `node::Start`; needs NDK |

## 2. Setup and assembly

Environment: **JDK 21** (`C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\`), Node 24 + npm on the
PC, Android SDK (compileSdk 37), `ndk;29.0.14206865`, `cmake;3.31.6` (last three via `sdkmanager`).

```powershell
git submodule update --init --recursive   # device-tools plugin lives in its own repo
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so from the NDK
.\tools\fetch-sherpa.ps1                  # sherpa native lib + silero VAD model (ASR only)
.\tools\bundle-dsh.ps1                    # vendored dsh → assets/dsh-runtime.zip
.	oolsetch-proot.ps1                   # 内嵌 proot + Debian rootfs（见 docs/linux-on-phone.md）
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

CI (`.github/workflows/`): `ci.yml` always runs a change-scope job; code changes run the gates plus
`:app:testDebugUnitTest`, docs-only changes skip them while still reporting the required checks
(skipped counts as pass). `assemble.yml` re-runs the whole assembly path (libnode → dsh → JNI → APK)
when `tools/**`, `app/src/main/cpp/**`, or Gradle files change; `release.yml` builds and signs the
APK on a `v*` tag inside the `release` environment (manual approval; secrets live there).

## 5. Repository map

```
android/                  the only runtime (Kotlin / Compose)
  app/src/main/java/com/tappet/app/
    agent/ accessibility/ bridge/ connector/ data/ device/ node/ voice/ ui/
  app/src/main/cpp/       JNI (node::Start + stdout forwarding)
  app/src/test/           JVM unit tests
  app/src/androidTest/    device tests
tools/                    fetch-libnode, fetch-sherpa, fetch-proot, bundle-dsh, check-sensitive,
                          install-debug (build + install on device),
                          audit/ (task traces), dsh-phone/ (launcher,
                          profile, patches, plugins)
docs/                     deep docs + releases/<tag>.md
```

`libnode.so` and the dsh runtime (`assets/dsh-runtime.zip`, `libnode/`, `jniLibs/`) are **not
committed**; `tools/` assembles them at pinned versions (see `.gitignore`).

## 6. Hard constraints

**Ask first** before a change that spans Kotlin + plugin + profile in one go, adds a dependency or
permission, deletes or replaces a shipped capability, or moves a boundary in §6.1.

**Never:** commit secrets or real identifiers; log screen, UI-tree, or chat content; hand-edit
generated assets (`assets/dsh-runtime.zip`, `libnode/`, `jniLibs/`); add to `detekt` `baseline.xml`;
rewrite pushed history or force-push `master` without explicit approval.

### 6.1 Security and privacy

1. **Secrets never enter the repo, logs, or build artifacts.** The only source is Settings →
   `SecretVault` (Keystore-wrapped), passed to dsh as env `TAPPET_LLM_KEY` at Node start.
2. **Never log screen or chat content.** Log counts, class names, coordinates, and error codes
   only. UI-tree payloads appear only in model tool results. `--ez logPayload true` is debug-only.
3. **The visual path sends the full screen image to the model provider** (including all on-screen
   text). Treat it like a UI-tree payload: tool results only, never logged or persisted — and
   never describe it as "structure only".
4. **`tappet.db` is SQLCipher-encrypted**; the passphrase is Keystore-wrapped. Uninstalling loses
   it permanently. Export before uninstalling or clearing data.
5. **`TappetDatabaseProvider` must call `System.loadLibrary("sqlcipher")`**; otherwise `nativeOpen`
   throws `UnsatisfiedLinkError`.
6. **Do not collect other people's data.** Any new collection capability needs a documented
   boundary and privacy gate in `docs/` first.

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
  and `OemProfile`. Third-party apps are test targets, not integrations.
- **The bridge listens on `127.0.0.1` with a per-process random token**, exposes only
  `POST /action`, and installs nothing on the device.
- **Write operations require the official dsh approval seam.** `tools/pre-execute` returns
  `ask` for a declared write tool → `@deepseek-ai/dsh-user-approval` → phone answerer →
  confirmation sheet; only `allowed-once` executes, and a missing answerer fails closed.
  Settings → 执行层 → 写操作审批 (default: ask) can switch the answerer to auto-answer
  `allowed-once`; the seam and its audit stay, and `policy: never` is still a denial, not a
  grant. `reason` is persisted in the session audit log (keep user content out of it);
  `displayReason` is UI-only and not persisted. See `docs/approvals.md`.
- **Do not enable touch exploration during tasks**: it swallows injected `dispatchGesture` events
  and cannot rescue a running app. Keep the `touchExplorationActive`
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

The legacy package `com.memex.app` cannot read this app's data; `adb uninstall com.memex.app` is
enough. Scrub real-world data before committing.

## 7. Workflows

### 7.1 Change Kotlin

1. Change code; add a `src/test` (or `src/androidTest`) test — behavior changes need tests. Test
   paths mirror the package; class names end in `Test` (JVM) or `AndroidTest` (device).
2. Run `:app:testDebugUnitTest`, then §4 gates.
3. For UI, database, or permission changes, verify on a device (§8).

### 7.2 Change the dsh runtime, profile, or a plugin

1. Plugins live in `tools/dsh-phone/plugins/` (submodule, separate public repo). Commit there
   first, then update the submodule pointer here.
2. Run `.\tools\bundle-dsh.ps1` — it validates the profile with the real parser; broken YAML kills
   the app because Node runs in-process.
3. Run `:app:testDebugUnitTest`, reinstall the APK (the runtime is re-extracted on first launch), and
   run one real task on the device (§9).
4. Plugin releases come from the plugin repo's `v*` tag workflow.

### 7.3 Add or change a device action

One atomic action touches four places: `device/DeviceAction.kt` (definition),
`accessibility/TappetAccessibilityService` (execution), `bridge/DeviceBridgeServer` (HTTP mapping),
and `tools/dsh-phone/plugins/android-tools/index.mjs` (`ctx.tools` + model-facing description).
Then `bundle-dsh.ps1` and reinstall.

Model-visible tools (19): `read_ui_tree`, `tap_node(ref)`, `long_press(ref,duration_ms)`,
`capture_screenshot()`, `tap_target(screenshot_id,x1,y1,x2,y2)`, `long_press_target(...)`,
`type_text`, `press(back|home|recents|enter)`, `launch_app(query)`, `swipe(direction)`,
`set_alarm(hour,minute,label?)`, `add_calendar_event(...)`, `list_calendar_events(begin,end)`,
`update_calendar_event(event_id,...)`, `delete_calendar_event(event_id)`,
`schedule_task(title,prompt,hour,minute)`, `list_scheduled_tasks`, `update_scheduled_task(id,...)`,
`delete_scheduled_task(id)`. `run_command` runs inside the embedded Debian (registered when the host sets
`TAPPET_PROOT=1`); with a mail connector add
nine mail tools (five read-only: `mail_list`, `mail_search`, `mail_read`, `mail_thread`,
`mail_labels`; four approval-gated writes: `mail_modify`, `mail_draft`, `mail_send`,
`mail_save_attachment`).

- **Scheduled tasks are created by conversation, not by a settings form.** The model parses
  "每天早上八点提醒我看天气" into `schedule_task(title,prompt,hour,minute)`; the settings entry is
  gone. Writes (`schedule_task` / `update_scheduled_task` / `delete_scheduled_task`) go through
  the approval seam. Only daily HH:mm exists; no cron. See `docs/scheduled-tasks.md`.
- **Calendar and alarms never go through the UI.** `ContentResolver` + `AlarmClock` intents
  (`device/SystemIntents.kt`), silent: no editor, no system confirmation. Editing an event by
  tapping the Calendar app cost 27 UI actions for one change — `list_calendar_events` first, then
  update/delete by `event_id`. All four writes are approval-gated (`tools/pre-execute` → `ask`)
  and post a notification receipt (`DeviceNotices`, promoted/流体云 where the ROM allows it).
  Needs `READ_CALENDAR`/`WRITE_CALENDAR`, granted from the chat prompt only after a real failure.

- **Coordinate contract:** the model uses only the **image pixels of the latest
  `capture_screenshot`** (1080×2344 → 943×2047), never screen pixels.
  `device/ScreenFrame.kt#toScreen` is the single conversion point. Screenshots are stateful
  (`STALE_SCREENSHOT`); out-of-bounds boxes are rejected (`OUT_OF_SCREENSHOT`), never clamped.
- **Two kinds of failure, never conflated.** An action name the bridge does not know is a
  contract error (plugin and app versions mismatch): the message must say **retrying will not
  help**. Missing required parameters are the model's own to fix: say **retry once they are
  supplied**. Sharing one "unknown or incomplete action" line made the model hit the same dead
  error six times in a row; `DeviceBridgeActionContractTest` pins the split.
- **Text input:** UI tree first; if unreadable, use the accessibility text-injection channel
  (`flagInputMethodEditor`, API 33+, no IME switch). `text_no_channel` = tap the field first;
  `text_flag_missing` = re-enable accessibility after the upgrade.
- **Incremental reads:** consecutive `read_ui_tree` calls return only the diff (`[+ref]` added,
  `[~ref]` changed, `[-ref]` gone); unchanged nodes keep their refs and are not resent. Refs stay
  valid until the screen changes, so consecutive taps on one screen need **no re-read** — read
  again only on navigation or to confirm the final result. Whole-page churn (>50%) or an app
  switch falls back to full. `read_ui_tree(full=true)` forces full when the model can no longer
  see the previous output. Page memory (`PageMemoryStore`) stores structure and last-action
  coordinates only — **never text**.

Details: `docs/android-accessibility.md` §9–§11.

### 7.4 Add or change a provider / model

Four places: `LlmPreset` → settings UI → `AgentConfigStore` → `NodeRuntime` + profile. Then
`bundle-dsh.ps1` + reinstall. Endpoint, call style, and model must match as a set; the `PROVIDER`
key must match the profile key exactly. Full rules: `docs/providers.md`.

### 7.5 Other subsystems

Everything else is routed by §11: change → document → follow-up steps.

STT invariants that break quietly: recognition runs **per VAD segment** (`RecognizedText.joinSegments`
joins the texts — never concatenate the session into one decode). The recognizer is **paraformer
only** and has no settings entry: it downloads after install and on mic tap, and the punctuation
model is its **companion** (`AsrModel.companions`) that installs with it. `decodingMethod` /
`blankPenalty` are dead knobs (only `greedy_search` is accepted; anything else exits the process) —
details in `docs/voice.md` §8–§8.2.

Downloads fail closed and stop: no auto-retry, actionable reason via `UpdateHttp.failReason`, and a
short body is an error (`TRANSFER_TRUNCATED`), not a finished file. See `docs/pitfalls.md`.

### 7.6 Release

Bump `versionCode` / `versionName` → write `docs/releases/<tag>.md` and update `CHANGELOG.md` →
commit → tag `v*`. The in-app version comes from `build.gradle.kts`, not the tag. Releases run in the
`release` environment (manual approval); signing and Gmail secrets live there, not at repo level.
Full process and signing material: `docs/releasing.md`.

## 8. Verification

### 8.1 Device install and tests

Never use Gradle's automatic install: ColorOS blocks a first install, then reports
`Starting 0 tests` + `Failed to install split APK(s)` after ~5 minutes.

**The daily-driver device runs debug builds only.** `run-as`, the debug broadcasts, and the audit
loop all require a debuggable APK; a release install has a different signature, so switching back
to debug means uninstalling and losing the SQLCipher database + keys. Keep release verification in
CI (or a separate device/emulator, where wiping is fine) — never install release over debug on the
same device. For the same reason the in-app updater is disabled in debug builds; update with
`adb install -r`.

**Parallel worktrees share `applicationId`.** Another session's install can replace your build
(same version code), and ColorOS does not always kill the running process on update. Two ways out:
give each worktree its own debug identity, or never install from two sessions at once. To coexist,
put `tappet.debugSuffix=.foamy` in `android/local.properties` (gitignored) — the debug APK becomes
`com.tappet.app.foamy` with its own data, key and accessibility grant; `run-suite` reads the same
file. Debug broadcasts then need the **full class name**
(`<pkg>/com.tappet.app.node.NodeDebugReceiver`) and accessibility must be enabled for
`<pkg>/com.tappet.app.accessibility.TappetAccessibilityService` — the relative `.node.X` form
expands against the suffixed package and points at a class that does not exist. After installing,
verify the device really runs your code — pull the installed `base.apk` and grep a marker, or check
a log format you just changed — and `am force-stop` before testing. Otherwise a whole trace can
silently come from someone else's build.

```powershell
.\tools\install-debug.ps1   # assembleDebug → adb install -r -g → re-enable accessibility → print version
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain   # from android/
```

- **The new APK's signature must match the installed one**, or the install fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the only way out is uninstalling (database + keys lost).
  With a release keystore in `local.properties`, debug builds are release-signed by default; if the
  device's copy was signed with the default debug key, set `tappet.debugSigning=debug` there.
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
# Receipt notification (importance / sound / promotion), without running a task
adb shell am broadcast -n com.tappet.app/.accessibility.DeviceActionReceiver -a com.tappet.app.debug.ACTION --es action NOTICE
# 内嵌 proot 冒烟（先把 App 拉到前台，ColorOS 会推迟后台广播）；--es cmd 可换命令
adb shell "am broadcast -n com.tappet.app/.node.NodeDebugReceiver -a com.tappet.app.debug.NODE --es op PROOT --ez logPayload true"
```

Other entries (`TEXT_CHANNEL`, `IME_TEXT`, `IME_ENTER`, voice `ASR`/`VAD`) are in
`docs/android-accessibility.md` §10, `docs/linux-on-phone.md`, and `docs/voice.md` §9. Quote the whole
`am broadcast` with `adb shell "..."` when arguments contain spaces.

With `tappet.debugSuffix` set, replace both the package and the component shorthand:
`-n com.tappet.app.foamy/com.tappet.app.node.NodeDebugReceiver` (classes never take the suffix).

### 8.3 Task audit

`node tools/audit/selftest.mjs` → `run-suite.mjs` → `collect.mjs --label <name>`;
`.claude/agents/tappet-auditor.md` judges. Rules (sensitive `runs/`, stable `suite.json` ids,
keep `runs/index.json`, never hardcode the package) live in `tools/audit/README.md`. Keep durable
artifacts in `tools/` or `docs/`, not `tmp/`.

## 9. Commit and PR conventions

- Conventional commits (`feat(android): ...`, `docs: ...`, `fix(repo): ...`); the body states what
  changed and how it was verified. No secrets or personal data.
- Run gates and unit tests before committing; don't commit unverified work.
- Merging a PR and pushing require explicit user approval.

**Done means the command actually ran**, and the report says what ran — not "tests pass":

| Change | Evidence |
|---|---|
| Kotlin | `:app:testDebugUnitTest` + §4 gates |
| UI / database / permissions | device check per §8.1 |
| dsh runtime / profile / plugin | `bundle-dsh.ps1` + reinstall + one real task |
| Release | §7.6 checklist |

Run the focused tests that cover the change; don't re-run an unrelated passing suite. Behavior
changes carry their test in the same commit.

## 10. Troubleshooting

Full table: `docs/pitfalls.md`.

## 11. Routing index

| Change / question | Read | Then |
|---|---|---|
| Agent runtime, embedded Node, dsh assembly | `docs/dsh-android.md` | — |
| Execution layer, visual path, text injection, device/ROM | `docs/android-accessibility.md` | — |
| Providers, models, modalities, runtime routing | `docs/providers.md` | §7.4 |
| Connectors (Gmail etc.) | `docs/email-connector.md` | plugin name in three places + `bundle-dsh.ps1` + reinstall |
| Web search | `docs/web-search.md` | `bundle-dsh.ps1` + reinstall; verify the search model actually searches |
| Voice (TTS/STT, ASR decoding) | `docs/voice.md` | §7.5 invariants; unit tests; device check per §8 |
| In-app updates, download failure policy | `docs/app-updater.md` | §7.5; `UpdateDownloaderResumeTest` |
| Linux on the phone (embedded Debian) | `docs/linux-on-phone.md` | `fetch-proot.ps1` + plugin commit first + `bundle-dsh.ps1` |
| Memory / context injection | `docs/memory.md` | — |
| Scheduled tasks | `docs/scheduled-tasks.md` | — |
| `ask_user_question` | `docs/user-questions.md` | — |
| Approvals, confirmation UI, write-operation gates | `docs/approvals.md` | profile change → `bundle-dsh.ps1` + reinstall |
| On-device inference | `docs/on-device-inference.md` | — |
| Releasing, signing material | `docs/releasing.md` | §7.6 |
| Planning direction (unverified hypotheses) | `docs/roadmap.md` | — |

## 12. Editing this file

- `AGENTS.md` is the only project memory; `CLAUDE.md` imports it. Edit here, never there.
- One home per fact: this file keeps the rule and the link, depth goes to `docs/`. Keep each rule
  self-contained — a reader must be able to obey it without opening the link.
- If a rule can be checked mechanically, add the test in the same change instead of more prose:
  the §6.3 identity list, the §7.3 tool list, and the §7.3 coordinate contract are all testable.
- Describe current state only: no changelog, no "we used to", no migration history — git and
  `docs/releases/` hold that. Delete stale rules rather than letting them rot; update §11 when a
  doc is added or removed.
- Keep this file under ~400 lines — new detail belongs in `docs/`, not here.
