# 命名与存储身份（改名红线）

下面每一个名字都是**已安装用户的存储身份**。改一个而不改其余的，不会报错，只会让老用户
的数据库、密钥、授权悄悄对不上。改名时**整组一起改**，并确认旧数据要么迁移、要么明确放弃。

## 1. 应用与数据库

| 位置 | 作用 |
|---|---|
| `app/build.gradle.kts` → `applicationId` | 应用身份（uid） |
| `ZoneuDatabaseProvider.DATABASE_NAME` | 数据库文件名 |
| `PassphraseVault.prefsName` | 存放 Keystore 包装口令的 SharedPreferences 文件 |
| `PassphraseVault.keyAlias` | Keystore 别名 |

## 2. 其余身份

- `SecretVault` 的 `prefsName` / `keyAlias`，以及 `AgentConfigStore.PREFS_NAME`。
- 提供商 id（`LlmPreset.id`）：出现在 `provider.<id>.*` 与 `zoneu-agent-llm-key-v1-<id>`。
- 连接器：`zoneu_connector_settings`、`zoneu_connector_secret-<id>`、`zoneu-connector-<id>-v1`，
  以及 `<id>_client_id` / `<id>_client_secret` / `<id>_account` 三个键
  （**前导下划线是契约的一部分**）。连接器一侧的细节见 [email-connector.md](email-connector.md) §5.2。
- 组件名用 `com.zoneu.app/...`；`OemProfiles.ZONEU_ACCESSIBILITY_COMPONENT` 读
  `BuildConfig.APPLICATION_ID`，不要写死。

## 3. 边界

应用私有数据属于这个包：别的包继承不了它的数据库，也继承不了它的 Keystore 密钥。
提交前把真实世界的标识（账号、主机、路径）洗掉，见 [releasing.md](releasing.md) 与
`tools/check-sensitive.ps1`。
