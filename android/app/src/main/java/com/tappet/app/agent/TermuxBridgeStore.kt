package com.tappet.app.agent

import android.content.Context
import com.tappet.app.data.crypto.SecretVault
import java.security.SecureRandom

/**
 * 手机上 Linux 环境（Termux + proot Debian）的桥配置：地址 + 访问 token。
 *
 * 分工与 [AgentConfigStore] 一致 —— **非密的进普通 prefs，凭据进 [SecretVault]**（Keystore 封装）。
 * 这里的 token 是货真价实的凭据：拿到它就能在设备上执行任意命令，所以它和 API key 同级，
 * 不进日志、不进云备份、不写进 `runtime-config.json`（那个文件是明文，只放非密配置）。
 *
 * **token 由 Tappet 生成**（[ensureToken]），不是用户从 Termux 抄过来的：桥的 token 只在
 * Tappet 与桥之间同步，用户没必要知道它。Tappet 读不到 Termux 的私有目录，但反过来可以 ——
 * 经 RUN_COMMAND 把 token 送过去（见 [TermuxBridgeLauncher]）。
 *
 * **存储身份**（prefs 名 / Keystore 别名，见 AGENTS.md 的改名红线）：定下就别改，
 * 改了等于换一把密钥，已存的 token 读不回来，而表现出来只是「设置页说我配过，但用起来像没配」。
 */
object TermuxBridgeStore {
  /** 桥的默认地址：与 `tools/termux/bridge.py` 的监听端口一致。 */
  const val DEFAULT_URL: String = "http://127.0.0.1:8899"

  private const val PREFS_NAME = "tappet_termux_bridge"
  private const val KEY_URL = "url"
  private const val SECRET_PREFS = "tappet_termux_bridge_secret"
  private const val KEY_ALIAS = "tappet-termux-bridge-token-v1"

  /** token 的字节数；48 个 hex 字符，与 setup.sh 生成的长度一致，方便肉眼核对。 */
  private const val TOKEN_BYTES = 24

  private val vault = SecretVault(prefsName = SECRET_PREFS, keyAlias = KEY_ALIAS)
  private val random = SecureRandom()

  /** 设置页要展示的状态。**不含 token 本体** —— 这个对象会进界面、进截图。 */
  data class State(val url: String, val hasToken: Boolean) {
    val enabled: Boolean get() = hasToken && url.isNotBlank()
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun url(context: Context): String = normalizeUrl(prefs(context).getString(KEY_URL, null))

  fun token(context: Context): String? = vault.get(context)

  fun state(context: Context): State = State(url = url(context), hasToken = vault.has(context))

  /**
   * 取当前的 token；没有就**生成一个并落盘**。这是「启用 Linux 环境」的第一步：
   * 有 token 之后，宿主才会把 `TAPPET_TERMUX_TOKEN` 注入给插件，`run_command` 才会出现在工具表里。
   */
  fun ensureToken(context: Context): String {
    vault.get(context)?.let { return it }
    val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
    val generated = bytes.joinToString(separator = "") { "%02x".format(it) }
    vault.put(context, generated)
    return generated
  }

  fun saveUrl(context: Context, url: String): Boolean =
    prefs(context).edit().putString(KEY_URL, normalizeUrl(url)).commit()

  /** 停用：连 token 一起清掉（工具表里随之不再出现 `run_command`）。 */
  fun clear(context: Context): Boolean = prefs(context).edit().clear().commit() && vault.clear(context)
}

/**
 * 地址留空就回落到默认值 —— 用户清空输入框不该让桥变成一个空 URL（那会在第一次
 * `run_command` 时才以 fetch 报错的形式现形）。纯函数，单独测。
 */
internal fun normalizeUrl(input: String?): String =
  input?.trim()?.takeIf { it.isNotEmpty() } ?: TermuxBridgeStore.DEFAULT_URL
