# 邮箱连接器（Gmail）

邮箱连接器是设备动作之外的第一条云端数据通道：信息来自用户自己的 Gmail 账号，依赖网络与
OAuth，无障碍关闭时照常可用。它是 [privacy.md](privacy.md) §5「采集边界」要求的前置文档：
新增任何采集能力，先在这里写清边界与隐私闸门，再动代码。

## 1. 能力面与授权面

| | 是什么 | 当前状态 |
|---|---|---|
| 能力面 | 模型能调用的工具 | 九个：五个只读 + 四个审批门控写 |
| 授权面 | 向 Google 申请的 scope | `gmail.modify`（读 + 写 + 发） |

授权比能力宽是刻意取舍：Google 对已安装应用**不支持增量授权**，先窄后宽会让所有已连接用户
重走同意页。代价是边界从「Google 端点兜底」降级为「我们自己的审批与插件代码兜底」。

### 工具

| 工具 | 作用 | 副作用 |
|---|---|---|
| `mail_list` | 列标签下最近邮件（主题 / 发件人 / 时间 / 未读 / 星标 / 会话 id），支持分页 | 无 |
| `mail_search` | 按 Gmail `q` 语法检索，支持分页 | 无 |
| `mail_read` | 读一封正文（截断）与附件清单 | 无 |
| `mail_thread` | 读整条会话，按时间排序 | 无 |
| `mail_labels` | 列系统与自定义标签，带邮件数 / 未读数 | 无 |
| `mail_modify` | 改标签：已读 / 星标 / 归档 / 自定义 | 写，审批 |
| `mail_draft` | 创建草稿，不发送 | 写，审批 |
| `mail_send` | 发送，不可撤销 | 写，审批 |
| `mail_save_attachment` | 保存附件到公共 `Download/zoneu/` | 写手机存储，审批 |

读工具只列附件元数据，不下载内容。写工具全部在 `tools/pre-execute` 判 `ask`，只有
`allowed-once` 才发请求；`rejected` / `cancelled` / `unavailable` 一律不执行。细节见
[approvals.md](approvals.md)。不发多账号、回复 / 转发 / 批量操作。

scope 固定为 `gmail.modify` + `openid email`；**不要** `https://mail.google.com/`
（含永久删除），有单测钉住。

## 2. 边界

- **账号范围**：一个连接器实例 = 一个账号，只有用户自己授权的那个。
- **默认不落盘**：邮件正文与元数据只出现在给模型的工具结果里，不落日志、不单独落库、
  不写文件、不缓存；不做本地索引或增量同步。正文随会话历史存在于 SQLCipher，卸载即丢失。
- **唯一的落盘例外**：`mail_save_attachment` 经审批后写公共 `Download/zoneu/`，单附件 ≤20MB，
  Android 10+；App 不保留副本，文件名会清洗。
- **不做后台采集**：没有轮询、没有 Gmail `watch` / Pub/Sub、没有「定时看看有没有新邮件」。
- **关掉就是关掉**：断开 = 清 refresh token + 账号信息 + 内存里的 access token。模型侧九个
  工具仍在，但调用返回 `NOT_CONNECTED`；不做「工具消失」。
- 用户还应去 Google 账号页撤销授权；本机断开只删自己这一份。

## 3. 隐私闸门

1. 正文只进工具结果。日志、`ZoneuAudit`、异常信息里不得出现主题 / 发件人 / 附件名 / 正文；
   只记动作名、连接器名、结果码、条目数、耗时。
2. `mail_read` 截断正文（4000 字符）并说明截断了；工具结果会进历史、每轮重发。
3. 正文包在明确的数据边界里，工具描述声明它是**数据不是指令**。
4. 工具结果不得回显令牌，任何形态。
5. 错误信息不回显响应体；只回 HTTP 状态码 + 可行动的说明。
6. 审计产物同样不得出现正文。

## 4. 提示注入

邮件正文是任何人可以投递的敌意输入，比屏幕文本更危险：不需要用户打开界面，发一封邮件即可。
当前缓解：

- 正文包在「数据，非指令」边界内，工具描述明确不可执行。
- 写操作全部走审批：注入最多让模型**请求**发信 / 改标签，用户在面板上看到收件人与主题；
  没有「允许一次」什么都不会发生。
- 正文里的链接不自动跳转；工具面没有「打开 URL」动作。

这不是完整防护；真正的闸门是审批 seam 本身。

## 5. 授权

### 回环流程

Gmail 走 OAuth 2.0 授权码 + PKCE + 回环重定向 `http://127.0.0.1:<随机端口>`：

- 监听器只绑 `127.0.0.1`，端口交给系统分配，带随机 `state` 做 CSRF 校验。
- 不用注册签名 SHA-1、不绑包名，所以可以预置一个桌面客户端 ID 对所有人生效。
- `client_secret` 必须发：Google 标它 Optional，但令牌端点会拒。它是公开客户端标识，
  真正的保护是 PKCE。

### Google 侧一次性准备（用户自己填自己的客户端时）

1. Google Cloud 新建项目 → 启用 Gmail API。
2. OAuth 同意屏幕：External → 填应用名与支持邮箱 → 测试用户加自己的 Gmail。
3. 创建 OAuth 客户端 ID，类型选**桌面应用**。
4. 保存客户端 ID 与配套密码；两者必须来自同一个客户端。

`gmail.modify` 是 restricted scope；Testing 状态下只有白名单用户可用，refresh token 通常
七天过期。公开分发需要 Google 验证。

### zoneu 侧

设置 → 连接器 → Gmail → 使用预置客户端或填写自己的 ID 与密码 → 连接 → 浏览器同意 →
自动跳回。App 完成换码与持久化后显示已连接账号。

预置值只走构建期注入：本地读 `local.properties` 的 `zoneu.connector.gmailClientId` /
`...gmailClientSecret`，发布由 `release.yml` 从 environment secret 注入
（`ZONEU_GMAIL_CLIENT_ID` / `ZONEU_GMAIL_CLIENT_SECRET`）；留空则要求用户自己填。
预置意味着所有用户的授权都记在维护者的 Google Cloud 项目下。

## 6. 实现分层

| 位置 | 职责 |
|---|---|
| `connector/GoogleOAuth.kt` | 纯协议：PKCE、授权 URL、回环请求行、令牌解析、scope 常量 |
| `connector/GmailAuth.kt` / `GmailLoopback.kt` / `GmailTransport.kt` / `GmailAuthorizationService.kt` | 授权编排、回环监听、可取消请求、临时前台保活 |
| `connector/GmailSession.kt` / `GmailCredentials.kt` / `ConnectorStore.kt` | 刷新单飞与版本保护、凭据绑定、`SecretVault` 持久化；access token 只在内存 |
| `bridge/ConnectorBridgeServer.kt` | 只发短命 access token 的本地桥，独立于设备桥 |
| `tools/dsh-phone/plugins/gmail/` | 九个模型工具；Gmail REST 语义全在插件 |

- **OAuth 与令牌留在 Kotlin 侧**，插件只拿短命 access token。桥只发令牌，不代理 Gmail 请求。
- **附件保存是例外**：插件把字节 POST 给桥，Kotlin 用 MediaStore 写公共目录。
- 插件名在 `package.json`、profile insert、`bundle-dsh.ps1` 的 `Package` 三处必须逐字相同；
  `NodeRuntimeProfileTest` 钉住。

### 存储身份

| 位置 | 值 |
|---|---|
| 连接器非密配置 prefs | `zoneu_connector_settings` |
| refresh token `SecretVault` prefsName | `zoneu_connector_secret-gmail` |
| refresh token Keystore keyAlias | `zoneu-connector-gmail-v1` |
| 客户端 id（非密） | `gmail_client_id` |
| 旧账号展示键（兼容读取 / 断开清理） | `gmail_account` |

改名规则见 [identity.md](identity.md)。

### 取数成本

`messages.list` 只回 id，主题 / 发件人靠 `messages.get?format=metadata` 再取一次：
`mail_list(10)` 是 11 个请求。这些 `get` 以 5 路有上限并发执行，结果顺序不变。不使用
`/batch`，以保持「出网请求全是 GET」这条边界断言。

正文提取走 MIME 树：优先 `text/plain`，只有 HTML 时做一次极简去标签并注明「只有 HTML 版」。

## 7. 生命周期与错误

- **连接成功**：拿到 refresh token 且加密记录同步提交成功。仅有 access token、保存失败或
  异常都不能报告连接完成。
- 既有裸 refresh token 可读；成功刷新后才升级为绑定记录。刷新响应省略 refresh token 时沿用旧值。
- 客户端 ID / 密码在连接时快照并整体保存；配置变化后旧授权不匹配时要求重连，不混用。
- access token 按需刷新，缓存 TTL 用单调时钟；刷新单飞，重连 / 取消 / 断开后旧结果不能
  清掉或恢复授权。
- 只有 GET 在明确 401 后刷新并最多重试一次；写请求不自动重放，重新执行仍需审批。
- 403 / 429 / 5xx / 网络异常不套用授权重试。

| 错误码 | 含义 / 下一步 |
|---|---|
| `NOT_CONNECTED` | 本机没有可用授权，重新连接 |
| `CREDENTIAL_UNREADABLE` | 密文读不出；先解锁重试，仍失败再重连，不清数据 |
| `STORAGE_FAILED` | 长期凭据未可靠保存，不能把短暂可用当连接成功 |
| `MISSING_REFRESH_TOKEN` | Google 未颁发长期凭据；旧凭据不被空值覆盖 |
| `NETWORK` / `TEMPORARY` | 网络或 Google 暂时故障 / 限流；保留凭据后重试 |
| `invalid_client` / `invalid_request` | ID / 密码或客户端类型配置问题 |
| `CONNECTION_PENDING` | 正在交互重连 |
| `CLIENT_CHANGED` | 配置与已保存授权不匹配，用目标客户端重新授权 |
| `invalid_grant` | 当前 refresh grant 被拒；重连并核实 Testing / 撤权 / 密码变化 |
| `STATE_MISMATCH` / `STALE_OPERATION` | 回调不属于当前尝试或操作已取消，重新发起 |

### 授权期间的后台保障

`GmailAuthorizationService` 是独立 `specialUse` 前台服务，不复用 Node 任务服务；声明
`FOREGROUND_SERVICE_SPECIAL_USE` 与 subtype。服务就绪前不打开浏览器；启动失败返回
`AUTH_SERVICE_FAILED`。完成、失败、取消与十分钟超时都停止服务并释放监听。

ColorOS 即使前台服务在跑也可能冻结应用，本机回环回调会超时；排查见
[Q&A](Q&A.md#local-callback-timeout)。

## 8. 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
node tools/dsh-phone/plugins/gmail/test/smoke.mjs
.\tools\bundle-dsh.ps1
```

JVM / 插件测试覆盖：PKCE、scope、GET / 写端点边界、写工具审批、`reason` 不含用户内容、
MIME、附件文件名清洗、令牌缓存与不落结果、错误不回显响应体、分页、会话视图、真实
`defineTool` 解析。`GmailCredentialAndroidTest` 用独立 prefs / alias 验证真实加密读写。

真机验收：连接 → 退出进程 / 重启 → 连接状态仍在 → 只读任务；取消后能立即重试；
断网不清授权。写工具先验可逆的 `mail_draft`，再验发送、标签修改与附件保存。
`adb logcat -s ZoneuConnector ZoneuAudit` 只应有动作名与失败码。
