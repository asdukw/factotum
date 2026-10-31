<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/zoneu_mascot.jpg" width="120" alt="zoneu吉祥物">
</p>

<h1 align="center">zoneu</h1>

<p align="center">
  <em>替你操作 Android 手机的 AI。</em>
</p>

<p align="center">
  <a href="https://github.com/asdukw/zoneu/releases"><img src="https://img.shields.io/github/v/release/asdukw/zoneu" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"></a>
</p>

<p align="center">
  <a href="README.md">English</a> · 简体中文
</p>

你可以让 zoneu 指挥 AI 在手机上做事：找设置、发消息、看日程，或在某个 App 里把一件事办完。
它操作的是你眼前的界面，不挑 App。

> [!IMPORTANT]
> zoneu 不自带 AI。你需要自己的 API key，可来自 OpenAI、Anthropic、DeepSeek、OpenCode Go
> 或 OpenRouter；费用由对应服务商收取。

## 安装

1. 从 [Releases](https://github.com/asdukw/zoneu/releases) 下载
   `zoneu-vX.Y.Z-arm64-v8a.apk`。
2. 打开文件，允许从此来源安装应用。
3. 更新时直接覆盖安装，聊天记录和设置都会保留。

> [!WARNING]
> 卸载 zoneu 会清空聊天记录和保存的密钥。

## 设置

1. **填 API key**：设置 → 提供商。
2. **开启屏幕访问**：系统设置 → 无障碍 → zoneu。只能由你手动开启；这是 AI 看见并操作手机的
   前提，随时可以关掉。
3. **（可选）连接 Gmail**：想让它帮忙处理邮件，就在设置里连接账号。

## 可以让它做什么

- 在任意 App 里把事办完 —— 找、打开、填写、发送、滚动，直到任务结束。
- 管理日历和闹钟。
- 按计划执行任务，比如每天早上查看天气。
- 读取和处理你的 Gmail。
- 直接说话，也可以让它把回复读出来。
- 用角色卡和世界书进行角色扮演聊天。

会改变或发送内容的操作，都会先征求你的同意。

## 你的隐私

- API key 安全地保存在你的手机上。
- **AI 看到的内容会发送给你选择的服务商** —— 包括屏幕内容，以及你让它处理的邮件、角色卡等
  资料。请选择你信任的服务商。
- 聊天记录加密保存在手机上。zoneu 不保存邮箱副本，也不会在后台读取邮件。
- 随时关闭屏幕访问或断开 Gmail，即可撤销对应权限。

## 遇到问题

- **切到别的 App 后任务停了**：在手机的电池设置里允许 zoneu 后台运行。
- **某个 App 读不到**：部分 App（如微信）会阻止屏幕访问，zoneu 会改用截图，速度会慢一些。
- **Gmail 连不上**：内置连接仅限测试账号，详见 Q&A。
- **定时任务不准时**：在系统设置里允许 zoneu 使用「闹钟和提醒」。

更多问题：[docs/Q&A.md](docs/Q&A.md)。

给开发者：[AGENTS.md](AGENTS.md) 与 [docs/](docs/)。
