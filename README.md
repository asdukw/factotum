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

zoneu lets you ask an AI to do things on your phone: find a setting, send a message, check your
calendar, or get something done inside an app. It works with whatever is on screen, not a fixed
list of apps.

> [!IMPORTANT]
> zoneu does not include an AI. Bring your own API key from OpenAI, Anthropic, DeepSeek,
> OpenCode Go or OpenRouter. Usage is billed by that provider.

## Install

1. Download `zoneu-vX.Y.Z-arm64-v8a.apk` from [Releases](https://github.com/asdukw/zoneu/releases).
2. Open the file and allow installing apps from this source.
3. To update, install the new version over the old one — your chats and settings are kept.

> [!WARNING]
> Uninstalling zoneu erases your chats and saved keys.

## Set up

1. **Add your API key** in Settings → Providers.
2. **Turn on screen access** for zoneu in Android Settings → Accessibility. Only you can do this;
   it is what lets the AI see and operate your phone. Turn it off whenever you want.
3. **Optional:** connect Gmail in Settings if you want help with email.

## What you can ask it to do

- Get things done in any app — find, open, fill in, send or scroll until the task is finished.
- Manage your calendar and alarms.
- Run a task on a schedule, such as checking the weather every morning.
- Read and handle your Gmail.
- Talk to it, and have replies read aloud.
- Chat in character with cards and world books.

Anything that changes or sends something asks for your approval first.

## Your privacy

- Your API key is stored securely on your phone.
- **Whatever the AI sees goes to the provider you chose** — screen content, and anything you ask it
  to work with, such as email or character cards. Pick a provider you trust.
- Chats are encrypted on your phone. zoneu keeps no copy of your mailbox and never reads it in the
  background.
- Turn off screen access or disconnect Gmail at any time to revoke that access.

## If something does not work

- **Tasks stop when you switch apps:** allow zoneu to run in the background in your phone's
  battery settings.
- **An app cannot be read:** some apps block screen access (WeChat, for example). zoneu falls back
  to screenshots, which is slower.
- **Gmail will not connect:** the built-in connection is limited to test accounts. See the Q&A.
- **Scheduled tasks fire late:** allow "Alarms & reminders" for zoneu in Android settings.

More answers: [docs/Q&A.md](docs/Q&A.md).

For developers: [AGENTS.md](AGENTS.md) and [docs/](docs/).
