package com.zoneu.app.connector

import android.content.Context
import com.zoneu.app.data.crypto.SecretVault

/**
 * 连接器的本地存储：**非密配置进 SharedPreferences，凭据进 [SecretVault]**。
 *
 * 分层的理由与 [com.zoneu.app.agent.AgentConfigStore] 完全一致，只是换了数据源：
 * - refresh token 是**长期凭据**（等价于一把能一直读你邮箱的钥匙），走 Keystore 封装，
 *   明文只在内存里过一手：不落盘、不进日志、不进云备份（`allowBackup=false`）。
 * - 客户端 id 不是秘密（公共客户端 + 设备码流程，注册时就不该有客户端密码），
 *   所以放普通 prefs；把它塞进 Keystore 只会让「换个账号重连」变麻烦。
 *
 * **存储身份**：prefs 名、Keystore 别名、以及下面每个 `SUFFIX_*` 键名，都属于
 * `AGENTS.md` 说的「另一组同类身份」。别名带连接器 id（`zoneu-connector-outlook-v1`），
 * 所以将来加 Gmail 是**另一把钥匙**，删掉一个不影响另一个。
 * 改这些名字 = 设备上已存的授权静默读不回来（表现只是「设置页说我连过，但用起来像没连」）。
 */
private const val PREFS_NAME = "zoneu_connector_settings"
private const val SECRET_PREFS_PREFIX = "zoneu_connector_secret"
private const val SECRET_ALIAS_PREFIX = "zoneu-connector"
private const val SECRET_ALIAS_SUFFIX = "-v1"

// 下划线在**前缀里**：键名是 `gmail_client_id` 而不是 `gmailclient_id`。
// 这两条是存储身份（改了就静默读不回来），文档里写的就是这个形状，别再漏掉下划线。
private const val SUFFIX_CLIENT_ID = "_client_id"

/**
 * 客户端密码。**和客户端 id 一起放普通 prefs，不进 SecretVault** ——
 * 这不是偷懒：Google 对桌面应用明说它是公共客户端的公开标识、保不住秘密，
 * 而且预置的那一份本来就在 APK 里。把它塞进 Keystore 只会假装它有保密性，
 * 顺带多一组存储身份要维护。refresh token 才是真凭据，那个在 SecretVault 里。
 */
private const val SUFFIX_CLIENT_SECRET = "_client_secret"
private const val SUFFIX_ACCOUNT = "_account"

private fun prefs(context: Context) =
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

private fun vault(connectorId: String) =
  SecretVault(
    prefsName = "$SECRET_PREFS_PREFIX-$connectorId",
    keyAlias = "$SECRET_ALIAS_PREFIX-$connectorId$SECRET_ALIAS_SUFFIX",
  )

/** 连接器在界面上需要的全部状态。**不含任何凭据** —— 它会被渲染进设置页，也可能被截图。 */
internal data class ConnectorStatus(
  /** **生效的**客户端 id：用户填过就是用户的，没填过就是预置默认值（见 [defaultClientId]）。 */
  val clientId: String = "",
  /** true = 这个 id 来自预置默认值，不是用户填的。界面据此不摆那一大段注册指引。 */
  val clientIdFromDefault: Boolean = false,
  val connected: Boolean = false,
  /** 授权时拿到的账号标识（`id_token` 里的 `preferred_username` 一类）；解不出来时为 null。 */
  val account: String? = null,
  val problem: String? = null,
)

internal object ConnectorStore {
  fun status(context: Context, connectorId: String): ConnectorStatus {
    val stored = clientId(context, connectorId)
    val fallback = defaultClientId(connectorId)
    val credential = credentials(context, connectorId).read()
    return ConnectorStatus(
      clientId = stored.ifBlank { fallback },
      clientIdFromDefault = stored.isBlank() && fallback.isNotBlank(),
      connected = credential is GmailCredentialRead.Present,
      account = if (credential is GmailCredentialRead.Present) {
        credential.credential.account
      } else {
        prefs(context).getString(connectorId + SUFFIX_ACCOUNT, null)
      },
      problem = if (credential == GmailCredentialRead.Unreadable) "CREDENTIAL_UNREADABLE" else null,
    )
  }

  /** 用户**自己填**的那个（没填过就是空串）。界面上「是否用了预置值」靠它判断。 */
  fun clientId(context: Context, connectorId: String): String =
    prefs(context).getString(connectorId + SUFFIX_CLIENT_ID, null).orEmpty()

  /**
   * **实际拿去发请求的**那个：用户填过就用用户的，没填过用预置默认值。
   * 授权路径读这个，不要读 [clientId] —— 否则预置了默认值也仍然报「未配置」。
   */
  fun effectiveClientId(context: Context, connectorId: String): String =
    clientId(context, connectorId).ifBlank { defaultClientId(connectorId) }

  /** 用户自己填的客户端密码（没填过就是空串）。 */
  fun clientSecret(context: Context, connectorId: String): String =
    prefs(context).getString(connectorId + SUFFIX_CLIENT_SECRET, null).orEmpty()

  fun saveClientSecret(context: Context, connectorId: String, value: String): Boolean {
    prefs(context)
      .edit()
      .putString(connectorId + SUFFIX_CLIENT_SECRET, value.trim().ifEmpty { null })
      .apply()
    return true
  }

  /** 生效的客户端密码：用户填过就用用户的，否则用预置的那份。 */
  fun effectiveClientSecret(context: Context, connectorId: String): String =
    clientSecret(context, connectorId).ifBlank {
      if (effectiveClientId(context, connectorId) == defaultClientId(connectorId)) defaultClientSecret(connectorId) else ""
    }

  /**
   * 保存客户端 id。空串等于清除（回落到「未配置」），不是「不修改」。
   *
   * **用 `apply` 而不是 `commit`**：这个字段在输入过程中**每敲一个键写一次**，
   * 而 `commit` 是同步落盘 —— 真机上实测快速输入会丢字符（`11111111` 变成 `111`），
   * 因为主线程被 fsync 占住时 Compose 的文本状态更新会掉帧丢键。
   * 客户端 id 不是秘密、写丢了重填一次即可，不值得为它同步阻塞主线程。
   * 凭据那几条仍然用 `commit`：那里需要「到底写进去没有」的答案。
   */
  fun saveClientId(context: Context, connectorId: String, value: String): Boolean {
    prefs(context)
      .edit()
      .putString(connectorId + SUFFIX_CLIENT_ID, value.trim().ifEmpty { null })
      .apply()
    return true
  }

  fun refreshToken(context: Context, connectorId: String): String? =
    (credentials(context, connectorId).read() as? GmailCredentialRead.Present)?.credential?.refreshToken

  /** 写入 refresh token。空串走 [clearCredentials] 的语义（清除），别把「没拿到」当「存空」。 */
  fun saveRefreshToken(context: Context, connectorId: String, token: String): Boolean {
    if (token.isEmpty()) return false
    return vault(connectorId).put(context, token)
  }

  fun saveAccount(context: Context, connectorId: String, account: String?): Boolean =
    prefs(context)
      .edit()
      .putString(connectorId + SUFFIX_ACCOUNT, account?.trim()?.takeIf { it.isNotEmpty() })
      .commit()

  fun gmailClient(context: Context): GmailClient = GmailClient(
    effectiveClientId(context, GMAIL_CONNECTOR_ID),
    effectiveClientSecret(context, GMAIL_CONNECTOR_ID),
  )

  fun saveClient(context: Context, config: GmailClient): Boolean =
    prefs(context).edit()
      .putString(GMAIL_CONNECTOR_ID + SUFFIX_CLIENT_ID, config.id.trim().ifEmpty { null })
      .putString(GMAIL_CONNECTOR_ID + SUFFIX_CLIENT_SECRET, config.secret.trim().ifEmpty { null })
      .commit()

  fun credentials(context: Context, connectorId: String = GMAIL_CONNECTOR_ID): GmailCredentialStore =
    object : GmailCredentialStore {
      override fun read(): GmailCredentialRead = when (val stored = vault(connectorId).read(context)) {
        SecretVault.ReadResult.Missing -> GmailCredentialRead.Missing

        SecretVault.ReadResult.Unreadable -> GmailCredentialRead.Unreadable

        is SecretVault.ReadResult.Present -> decodeGmailCredential(
          stored.value,
          prefs(context).getString(connectorId + SUFFIX_ACCOUNT, null),
        )
      }

      override fun save(credential: GmailCredential): Boolean = vault(connectorId).put(context, encodeGmailCredential(credential))

      override fun clear(): Boolean = clearCredentials(context, connectorId)
    }

  /**
   * 断开：清凭据 + 清账号标识。**客户端 id 留着** —— 用户重连时不该再填一遍，
   * 而且它不是秘密。返回是否全部落盘成功。
   *
   * 这只清本机这一份；微软账号侧的授权仍然有效，撤销步骤写在
   * `docs/email-connector.md` §2。
   */
  fun clearCredentials(context: Context, connectorId: String): Boolean {
    val cleared = vault(connectorId).clear(context)
    val accountCleared = saveAccount(context, connectorId, null)
    // 客户端 id / 密码**不清**：它们不是凭据，用户重连时不该再填一遍
    // （与 Outlook 那版同一条取舍）。
    return cleared && accountCleared
  }
}
