<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/tappet_mascot.jpg" width="120" alt="Tappet mascot">
</p>

<h1 align="center">Tappet</h1>

<p align="center">
  <em>A general-purpose mobile agent for Android. An LLM operates arbitrary apps through tool calls — no per-app integrations.</em>
</p>

<p align="center">
  <a href="https://github.com/asdukw/tappet/actions/workflows/ci.yml"><img src="https://github.com/asdukw/tappet/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/asdukw/tappet/releases"><img src="https://img.shields.io/github/v/release/asdukw/tappet" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="README_zh.md">简体中文</a> · English
</p>

Tappet hands the phone to the model. It reads the screen, taps, long-presses, types, swipes and
launches apps — ten general UI primitives that the model composes itself to finish tasks like
"open the calculator and compute 37×48", instead of a script per app.

The agent runtime is an embedded Node 24 running the dsh phone profile. The execution layer is an
Android `AccessibilityService` (UI tree first, screenshots as fallback). They talk over a localhost
HTTP bridge bound to `127.0.0.1` with a per-process random token.

Since v0.5.0 there is a second information path: connectors (Gmail first, read-only). They read the
user's own cloud account directly, without going through accessibility.

> [!IMPORTANT]
> Three things are needed after install: add your own LLM API key (Settings → Providers; presets
> for OpenAI, Anthropic, DeepSeek, OpenCode Go and OpenRouter, or a custom endpoint), enable the
> accessibility service (only you can do this), and optionally connect Gmail. The status table below
> says which paths have actually been verified on a device.

## Features

- **Ten UI primitives.** `read_ui_tree`, `tap_node`, `long_press`, `capture_screenshot`,
  `tap_target`, `long_press_target`, `type_text`, `press`, `launch_app`, `swipe`. The model sees
  them as tools; the app executes them.
- **Visual fallback.** Apps that hide their accessibility tree (WeChat is the known case) are driven
  by a screenshot. The model returns a box in image pixels; `ScreenFrame.toScreen` is the only
  conversion point. Screenshots are stateful, and out-of-bounds boxes are rejected, never clamped.
- **Text input without switching IMEs.** On Android 13+, the accessibility text-injection channel
  types into fields even when the tree is unreadable. The tree path is still preferred.
- **Bring your own model.** Five presets plus custom endpoints. Keys live in a Keystore-wrapped
  vault and are passed to the runtime as environment variables at Node start — never written to
  disk or compiled into the APK.
- **Local voice.** Voice input uses sherpa-onnx with Paraformer, SenseVoice or Whisper tiny,
  segmented by silero VAD, plus a punctuation model that installs with Paraformer. TTS uses the
  system engine or a local VITS voice. Audio never leaves the device and is never persisted.
- **Connectors.** Gmail is read-only: list, search and read. The connector bridge is separate from
  accessibility, so mail tools keep working when accessibility is off.
- **Web search.** `web_search` / `web_fetch` use the model provider's own server-side search — no
  extra vendor and no extra credential. Page content is marked as untrusted external data.
- **Agent plumbing.** `ask_user_question`, long-term memory, file read/write/edit plus `glob` /
  `grep`, todos, scheduled daily tasks, the system-intent helpers `set_alarm` /
  `add_calendar_event`, and in-app updates with resume and signature checks.
- **Encrypted at rest.** Chat history lives in Room + SQLCipher; the database passphrase is
  Keystore-wrapped.

## Status

Tappet is explicit about what has been verified. "Unit-tested" is not the same as "works on a
device".

| Capability | Status |
|---|---|
| Chat UI, persistence, accessibility execution layer | Verified on device (OnePlus 15T / ColorOS) |
| Embedded Node 24 + dsh runtime, multi-turn tasks | Verified on device |
| LLM providers | Only OpenCode Go has been exercised with a real key; the other presets match the runtime contract but have not sent a real request |
| Visual path | Coordinate conversion is pinned by unit tests; the model's pixel estimation has not been verified on device |
| Voice input | Verified on device (VAD segmentation and transcription; the punctuation model ships with Paraformer) |
| TTS | Verified on device (system engine, local VITS, auto-read) |
| Gmail connector | Verified on device with one account, including the full OAuth flow |
| Web search | Verified on device with OpenCode Go only |
| Memory, file tools, `ask_user_question` | End-to-end on an Android 16 emulator; not yet verified on a physical device |
| Scheduled tasks | Unit-tested; on-time triggering, boot rescheduling and permission fallback not verified on device |
| Alarms and calendar | Emulator-verified prefill; not yet verified on a physical device |
| In-app updates | Emulator-verified download, cancel and signature checks; resume logic is unit-tested and partially emulator-verified |

## Quick start

### Install a release

[Releases](https://github.com/asdukw/tappet/releases) contain the signed APK, its `.sha256` and
release notes. Only `arm64-v8a` is shipped.

```bash
adb install -r tappet-v0.13.0-arm64-v8a.apk
sha256sum -c tappet-v0.13.0-arm64-v8a.apk.sha256   # optional
apksigner verify --print-certs tappet-v0.13.0-arm64-v8a.apk
gh attestation verify tappet-v0.13.0-arm64-v8a.apk --repo asdukw/tappet
```

`adb install -r` removes the enabled accessibility service. Re-enable it after installing.

The APK is signed by CI on `v*` tags and carries a GitHub build-provenance attestation;
`gh attestation verify` confirms it was produced by this repository's release workflow. Signing
material never enters the build, and no API key is compiled into the package.

> [!NOTE]
> On ColorOS the first launch may show a "keep running" dialog. Accept it; the system may otherwise
> freeze the process. Only the user can enable accessibility.

### Build from source

Prerequisites: **JDK 21**, **Node 24 + npm** on the PC, and the Android SDK with **compileSdk 36**,
**NDK 29.0.14206865** and **CMake 3.31.6** (install the last three with `sdkmanager`).

```powershell
git submodule update --init --recursive   # the device-tools plugin lives in its own repo
.\tools\fetch-libnode.ps1                 # libnode + libc++_shared.so
.\tools\fetch-sherpa.ps1                  # local TTS runtime + silero VAD model
.\tools\bundle-dsh.ps1                    # vendored dsh -> assets/dsh-runtime.zip

$env:JAVA_HOME = '<path-to-jdk-21>'
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

- Run `.ps1` scripts with **`pwsh` 7**, not Windows PowerShell 5.1: 5.1 writes backslashed zip
  entries that the device cannot unpack.
- The assembly scripts fetch pinned runtimes; their outputs are not committed. They honor
  `HTTP_PROXY` / `HTTPS_PROXY`.
- For an emulator, fetch the x86_64 libnode and build with `"-Ptappet.abis=x86_64"`, then switch
  back to arm64.
- Do not use Gradle's automatic install on ColorOS: it blocks the first install and fails after
  several minutes. Use `adb install -r` and re-enable accessibility.

## How it works

```
Compose UI ── Room/SQLCipher
    │  session/prompt
    ▼
Embedded Node 24 (one resident instance in the app process)
    └─ dsh phone profile
         ├─ dsh-plugin-android-tools (10 UI primitives + system-intent helpers)
         │    │  HTTP POST /action (127.0.0.1 + per-process random token)
         │    ▼
         │  DeviceBridgeServer (in-app)
         │    ▼
         │  AccessibilityService: UI tree first, screenshot fallback
         │
         └─ dsh-plugin-gmail (three read-only mail tools, once connected)
              │  HTTP POST /action (127.0.0.1 + per-process random token)
              ▼
            ConnectorBridgeServer (in-app) — issues only short-lived access tokens
              ▼
            Gmail API (the user's own mailbox, read-only)
```

The connector path does not go through accessibility: when accessibility is off, all device tools
fail, while mail tools keep working. It is also the first path in the repository that reads
personal cloud data directly, so its boundary and privacy gate have their own document:
[docs/email-connector.md](docs/email-connector.md).

Deliberate choices:

- **No plugins installed on the device.** Tools land through a localhost bridge; plugin source is a
  separate public repository. A new ROM is a new `OemProfile`, not app-specific logic — QQ and
  WeChat are test targets, not integrations.
- **stdout is protocol-only.** `session.event` and `session.status` use stdout; diagnostics go to
  stderr. A stdout logger would corrupt JSON-RPC.
- **Reload config, don't restart.** Switching a provider or model writes a non-secret runtime
  config and sends `runtime.reload`; the launcher remounts the cordis root (about 0.2–1 s, same
  pid). Keys are never written to disk. Changing an API key is the one case that restarts the app,
  because keys are read into the environment when Node starts.
- **One atomic action per turn.** Tool descriptions ask the model to read the screen first and
  confirm after acting, so it does not tap blind.

## Privacy and keys

> [!NOTE]
> These are structural facts, not promises. The collection pipelines that used to exist (QQ/WeChat
> chat logs, knowledge graph, notification probes) have been removed from the codebase and the
> repository history.

- **One source for secrets.** Settings → `SecretVault` (Android Keystore). Ciphertext on disk,
  hardware-wrapped and non-exportable, excluded from cloud backup and device transfer, never echoed
  to the UI or logs, and never compiled into the APK.
- **No screen or chat content in logs.** Logs carry counts, class names, coordinates and error codes
  only. UI-tree payloads appear only in model tool results. Everything sent to the cloud goes to the
  endpoint you configured.
- **The visual path sends a full-screen image** to the model provider, including all on-screen text.
  It is handled like a UI-tree payload: tool results only, never logged or persisted.
- **No local mailbox copy.** The Gmail connector does not persist mail, does not log it and does not
  poll in the background. The refresh token lives in `SecretVault`; the plugin only ever receives a
  short-lived access token.
- **The Gmail scope is wider than the tool surface.** The app requests `gmail.modify` (read + write
  + send) while exposing only three read-only tools. Google does not support incremental
  authorization for installed apps, so widening later would force every connected user through
  consent again. The trade-off is documented in
  [docs/email-connector.md](docs/email-connector.md) §1.1.
- **`tappet.db` is SQLCipher-encrypted**; the passphrase is Keystore-wrapped. Uninstalling loses it
  permanently.
- **Pre-release scan.** `tools/check-sensitive.ps1` scans the working tree and full history, and can
  scan the APK too. `-SelfTest` proves the scanner can hit.

## Development

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain   # formatting / smells / lint
.\gradlew.bat spotlessApply                                        # fix formatting
.\gradlew.bat :app:testDebugUnitTest                               # JVM unit tests
```

- Device tests (`androidTest/`, covering Room/SQLCipher and Compose UI): `adb install -r`, re-enable
  accessibility, then `.\gradlew.bat :app:connectedDebugAndroidTest`.
- The task audit loop: `node tools/audit/run-suite.mjs` runs a fixed suite and collects traces;
  `tappet-auditor` judges them. Artifacts contain screen text and are treated as sensitive.
- Changes under `tools/dsh-phone/` require `.\tools\bundle-dsh.ps1` and a reinstall: the runtime is
  packaged into assets. The plugin itself lives in a
  [separate public repo](https://github.com/asdukw/dsh-plugin-android-tools); commit there first,
  then update the submodule pointer.

CI has four workflows: `ci.yml` (static checks, unit tests, offline self-checks), `assemble.yml`
(assembles libnode/dsh and builds an APK when assembly paths change, then verifies the artifact
structure), `codeql.yml`, and `release.yml` (on a `v*` tag: gates → signed APK → signature and
secret checks → release).

> [!WARNING]
> CI green does not mean usable. The accessibility execution layer, embedded Node startup and real
> tasks can only be verified on a device. CI does not install the NDK; only the assembly and release
> workflows do.

## Known limitations

- Only `arm64-v8a` is shipped. The embedded Node runtime alone is 65 MB; a second ABI would roughly
  double the APK. Screenshots require Android 11+ (minSdk is 28).
- Of the five presets, only OpenCode Go has been exercised with a real key. Switching providers or
  models on a device while tasks are running is still not fully verified.
- Apps that hide the accessibility tree (WeChat 8.0.78) use the visual path. Text input works there
  via the accessibility text-injection channel on Android 13+; older versions still cannot type into
  such apps.
- Visual-path coordinates are unit-tested, but the model's pixel estimation has not been verified on
  a device.
- File tools are not sandboxed: the model can read and write any path the app can reach.
- Switching provider or model rotates the dsh session namespace: UI history remains, but model
  context restarts with the next message (the dsh SDK has create, not resume).
- The preset Gmail OAuth client is in Google testing mode: only allowlisted accounts can authorize,
  and refresh tokens are short-lived (about 7 days in practice). To use Gmail, replace the client ID
  and secret in Settings. Authorization also requires the app process to stay alive (up to 10
  minutes).
- Scheduled tasks, alarms and calendar prefill are not verified on a physical device. Scheduled
  tasks are daily-only; exact alarms need a special permission on Android 12+, and Android 14
  denies it by default for new installs.
- Cross-app long tasks rely on a foreground service; ColorOS background freezing still has edge
  cases.
- The project was renamed from Memex on 2026-10-12, including `applicationId`. The old
  `com.memex.app` package cannot read this app's data; uninstall it.

## Documentation

| Document | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | Project memory for coding agents: hard rules, commands, change entry points, full doc index |
| [docs/dsh-android.md](docs/dsh-android.md) | Agent runtime selection and measurements |
| [docs/android-accessibility.md](docs/android-accessibility.md) | Execution layer, visual path, text injection |
| [docs/providers.md](docs/providers.md) | Providers, models, modalities, runtime routing |
| [docs/voice.md](docs/voice.md) | TTS/STT: engines, model licenses, VAD |
| [docs/email-connector.md](docs/email-connector.md) | Connector boundaries and privacy gate |
| [docs/web-search.md](docs/web-search.md) | Web search boundary, protocol and measurements |
| [docs/app-updater.md](docs/app-updater.md) | In-app updates: resume and verification |
| [docs/termux.md](docs/termux.md) | Linux on the phone: Termux bridge and `run_command` |
| [docs/releasing.md](docs/releasing.md) | CI, release process, signing |
| [docs/pitfalls.md](docs/pitfalls.md) | Full troubleshooting table |
| [docs/roadmap.md](docs/roadmap.md) | Unverified direction |
| [CHANGELOG.md](CHANGELOG.md) | Visible changes per release |
