<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/zoneu_mascot.jpg" width="120" alt="zoneu mascot">
</p>

<h1 align="center">zoneu</h1>

<p align="center">
  <em>An AI that uses your Android phone for you.</em>
</p>

<p align="center">
  <a href="https://github.com/asdukw/zoneu/releases"><img src="https://img.shields.io/github/v/release/asdukw/zoneu" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="README_zh.md">简体中文</a> · English
</p>

zoneu is an Android app that hands your phone to an AI model. Ask it to find a setting, send a
message, check your calendar or work through an app. It reads the screen and taps, types and
swipes the way you would, so it works with any app it can see — not just a fixed list.

> [!IMPORTANT]
> zoneu does not include an AI model. You need your own API key from a provider such as OpenAI,
> Anthropic, DeepSeek, OpenCode Go or OpenRouter. Usage is billed by that provider.

## What you need

- An Android phone with an arm64 chip (almost all modern phones). Screenshots need Android 11+.
- An API key from a supported provider, or any OpenAI-compatible endpoint.

## Install

1. Download `zoneu-vX.Y.Z-arm64-v8a.apk` from [Releases](https://github.com/asdukw/zoneu/releases).
2. Open the file and allow installing apps from this source.
3. Install over the old version when updating — this keeps your chats and settings.

> [!WARNING]
> Uninstalling zoneu permanently deletes your chat history and saved keys.

## First setup

1. **Add your API key.** Open Settings → Providers, pick a provider and paste your key.
2. **Turn on accessibility.** Android Settings → Accessibility → zoneu. Only you can do this.
   Android will warn that the app can read everything on screen; that is what lets it operate
   your phone. Turn it off at any time to stop zoneu from acting.
3. **Optional: connect Gmail** in Settings if you want zoneu to read your mail.

## What it can do

- **Use any app** — search, tap, type, scroll, and move between apps to finish a task.
- **Calendar and alarms** — add, change or delete events and alarms without opening an app.
- **Scheduled tasks** — "every morning at 8, check the weather"; daily, weekly, monthly, yearly or
  once.
- **Gmail** — search and read mail. Changing, drafting or sending mail always asks you first.
- **Voice** — talk to it (speech stays on your phone) and have replies read aloud.
- **Roleplay** — tavern-style chat with character cards, world books, group chats and memory.

Actions that change something — sending mail, editing your calendar, running a command — show a
confirmation panel first. Nothing is sent or changed until you tap allow.

## Privacy

- Your API key is stored in Android's Keystore and never written to logs or the app package.
- **What the AI sees is sent to the provider you chose.** That includes screen text, screenshots
  (the whole screen, including anything visible), and any mail or character-card content you ask
  it to work with. Choose a provider you trust.
- Chat history is encrypted on your phone. zoneu keeps no copy of your mailbox and does not read
  mail in the background.
- You can revoke access at any time by turning off accessibility or disconnecting Gmail.

## If something goes wrong

- **Tasks stall when you switch apps:** some phones freeze background apps. Allow zoneu to run in
  the background in your phone's battery settings.
- **An app shows nothing to read:** some apps hide their screen from accessibility (WeChat is one).
  zoneu falls back to screenshots, which is slower and needs Android 11+.
- **Gmail will not connect with the built-in client:** it is in Google testing mode, so only
  allowlisted accounts work. Add your own OAuth client in Settings to use any account.
- **Scheduled tasks fire late:** allow "Alarms & reminders" for zoneu in Android settings.

More answers: [docs/Q&A.md](docs/Q&A.md).

## For developers

Build instructions, architecture and verification status live in [AGENTS.md](AGENTS.md),
[docs/build.md](docs/build.md) and the [docs](docs/) folder.
