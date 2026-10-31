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

zoneu 是一个把手机交给 AI 模型的 Android 应用。你可以让它找设置、发消息、看日程，或在某个
App 里把事情办完。它像你一样读屏、点按、输入、滑动 —— 只要能看见的 App 都能操作，
不是一份固定的 App 清单。

> [!IMPORTANT]
> zoneu 不自带模型。你需要自己的 API key（OpenAI、Anthropic、DeepSeek、OpenCode Go、
> OpenRouter 等，或任何 OpenAI 兼容端点），费用由对应服务商收取。

## 需要准备

- 一台 arm64 的 Android 手机（绝大多数现代手机）。截图功能需要 Android 11+。
- 一个可用的 API key。

## 安装

1. 从 [Releases](https://github.com/asdukw/zoneu/releases) 下载
   `zoneu-vX.Y.Z-arm64-v8a.apk`。
2. 打开文件，允许从此来源安装应用。
3. 更新时直接覆盖安装，聊天记录和设置会保留。

> [!WARNING]
> 卸载 zoneu 会永久删除聊天记录和保存的密钥。

## 首次设置

1. **填 API key**：设置 → 提供商，选择服务商并粘贴密钥。
2. **开启无障碍**：系统设置 → 无障碍 → zoneu。只能由你手动开启。系统会提示它能读取屏幕上
   的一切内容 —— 这正是它操作手机的前提。随时关掉即可让它停止操作。
3. **（可选）连接 Gmail**：如果你想让 zoneu 读写邮件，在设置里连接账号。

## 能做什么

- **操作任意 App**：搜索、点按、输入、滚动，在多个 App 之间来回完成任务。
- **日历与闹钟**：不用打开 App 就能新建、修改或删除日程和闹钟。
- **定时任务**：例如「每天早上 8 点看天气」；支持每天、每周、每月、每年或只执行一次。
- **Gmail**：搜索和阅读邮件；修改、存草稿或发送邮件每次都会先问你。
- **语音**：可以直接说话（语音输入在本机完成），回复也可以朗读。
- **角色扮演**：酒馆式聊天，支持角色卡、世界书、群聊和记忆。

会改变数据的操作 —— 发邮件、改日程、执行命令 —— 都会先弹出确认面板，你点允许之后才会执行。

## 隐私

- API key 存在 Android Keystore 里，不写日志，也不打进安装包。
- **AI 看到的内容会发送到你选择的服务商**：包括屏幕文字、整屏截图（含屏幕上可见的一切），
  以及你让它处理的邮件或角色卡内容。请选择你信任的服务商。
- 聊天记录加密保存在手机上。zoneu 不保存邮箱副本，也不会在后台读取邮件。
- 随时关闭无障碍或断开 Gmail，即可撤销对应权限。

## 遇到问题

- **切到别的 App 后任务卡住**：部分系统会冻结后台应用。在电池设置里允许 zoneu 后台运行。
- **某个 App 读不到内容**：部分 App（如微信）对无障碍隐藏界面，zoneu 会改走截图，
  速度较慢，且需要 Android 11+。
- **内置 Gmail 客户端连不上**：它还在 Google 测试模式，只有白名单账号可用；在设置里换成
  自己的 OAuth 客户端即可使用任意账号。
- **定时任务不准时**：在系统设置里允许 zoneu 使用「闹钟和提醒」。

更多问题：[docs/Q&A.md](docs/Q&A.md)。

## 给开发者

构建步骤、架构与验证状态见 [AGENTS.md](AGENTS.md)、[docs/build.md](docs/build.md) 和
[docs](docs/) 目录。
