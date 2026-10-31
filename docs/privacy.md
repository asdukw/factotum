# 隐私与密钥边界

红线。越过任何一条都要先改这里的边界说明，并在同一变更里加上对应的隐私闸门与测试；
新增**采集能力**（不是新的设备动作）必须先有本文的边界条目。

## 1. 密钥

- 唯一来源是设置 → `SecretVault`（Keystore 封装）。Node 启动时以环境变量
  `ZONEU_LLM_KEY` 交给 dsh；每个提供商预置为 `ZONEU_LLM_KEY_<ID>`。
- 密钥**永不落盘**：`dsh-home/runtime-config.json` 只写非密字段。
- 密钥不进仓库、日志或构建产物。唯一的例外是 OAuth 客户端密码（`GOCSPX-…`）：
  它是公开客户端标识，必须随包分发，真正的保护是 PKCE——见
  [email-connector.md](email-connector.md) §5；`check-sensitive.ps1` 有意不拦它。
- 邮箱连接器的 token 存储与生命周期见 [email-connector.md](email-connector.md)。

## 2. 日志

- 只记**计数、类名、坐标、错误码、状态、长度、耗时**。
- 屏幕正文、UI 树文本、聊天内容、邮件正文、人设正文、世界书关键词与提要都只出现在
  模型的工具结果里，不落日志、不落审计、不进 `reason`。
- `--ez logPayload true` 只用于 debug 复现，不是默认行为。

## 3. 视觉路径

`capture_screenshot` 把**整屏图像**（包含屏幕上的全部文字）发给模型提供商。
它和 UI 树载荷同级：只做工具结果，不记日志、不持久化。不要把它描述成「只发结构」。

## 4. 本地存储

- `zoneu.db` 是 SQLCipher 加密的，口令由 Keystore 包装；**卸载会永久丢失**。
  卸载或清数据前先导出。
- 角色卡、角色记忆、人格与偏好是应用私有文件，**不是** SQLCipher 加密存储，
  不能当密钥保险箱用。
- `ZoneuDatabaseProvider` 必须显式调用 `System.loadLibrary("sqlcipher")`，否则
  `nativeOpen` 抛 `UnsatisfiedLinkError`。

## 5. 采集边界

不采集别人的数据。任何新的采集能力（新的连接器、通知读取、后台抓取）都要先在 `docs/`
里写清边界与隐私闸门，再写代码。邮件连接器的边界见 [email-connector.md](email-connector.md) §1–§3。

## 6. 提交前扫描

```powershell
.\tools\check-sensitive.ps1                 # worktree + 全历史
.\tools\check-sensitive.ps1 -SelfTest       # 必跑：证明扫描器能命中
.\tools\check-sensitive.ps1 -ApkPath <apk>  # 连产物一起扫
```

真实标识、密钥、本机路径在 **push 之前**改掉，不要留到发版。扫描器的标识启发式只告警。
