package com.zoneu.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 封装一条**用户输入**的秘密（目前只有 LLM API key），
 * 密文与 IV 落在普通 SharedPreferences 里。
 *
 * 思路与 [PassphraseVault] 一致：Keystore 里的 AES 密钥是硬件封装、不可导出的，
 * 所以密文**换台设备就解不开**，也不需要用户自己记口令。
 *
 * **为什么刻意不复用 [PassphraseVault] 的实现**：那一个生成的是随机口令、服务于
 * SQLCipher，它的 prefs 名与 Keystore 别名属于 AGENTS.md 说的「存储身份」——
 * 那里改错一个字符，设备上已有的库就再也打不开，而这条路径本机没有真机可以回归验证。
 * 宁可重复这几十行，换「不可能碰坏数据库」。
 *
 * 明文只在内存里过一手：不落盘、不进日志、不进云备份（`allowBackup=false`）。
 */
internal class SecretVault(
  private val prefsName: String,
  private val keyAlias: String,
) {
  /** 不暴露异常原文；调用方可区分从未保存与已有密文不可读。 */
  sealed interface ReadResult {
    data object Missing : ReadResult

    data class Present(val value: String) : ReadResult

    data object Unreadable : ReadResult
  }

  fun get(context: Context): String? = (read(context) as? ReadResult.Present)?.value

  /** 读失败保留密文；读取绝不创建替代 key，避免掩盖 Keystore 丢失。 */
  fun read(context: Context): ReadResult =
    runCatching {
      val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      val iv = prefs.getString(PREF_IV, null)
      val wrapped = prefs.getString(PREF_WRAPPED, null)
      when {
        iv == null && wrapped == null -> ReadResult.Missing

        iv == null || wrapped == null -> ReadResult.Unreadable

        else -> decrypt(iv, wrapped).takeIf { it.isNotEmpty() }
          ?.let { ReadResult.Present(it) } ?: ReadResult.Unreadable
      }
    }.getOrDefault(ReadResult.Unreadable)

  /** 写入一条秘密。传空串等于清除。返回是否落盘成功。 */
  fun put(context: Context, secret: String): Boolean {
    if (secret.isEmpty()) return clear(context)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val wrapped = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
    return context
      .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
      .edit()
      .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
      .putString(PREF_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
      .commit()
  }

  /** 清除已存的秘密（连同密文）。返回是否落盘成功。 */
  fun clear(context: Context): Boolean =
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()

  fun has(context: Context): Boolean = get(context) != null

  private fun decrypt(ivEncoded: String, wrappedEncoded: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val iv = Base64.decode(ivEncoded, Base64.NO_WRAP)
    cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: error("KEY_MISSING"), GCMParameterSpec(GCM_TAG_BITS, iv))
    val plain = cipher.doFinal(Base64.decode(wrappedEncoded, Base64.NO_WRAP))
    return String(plain, Charsets.UTF_8)
  }

  private fun existingKey(): SecretKey? {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
  }

  private fun secretKey(): SecretKey = synchronized(keyLock) {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(keyAlias, null)
    if (existing is KeyStore.SecretKeyEntry) {
      return@synchronized existing.secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // GCM 必须随机 IV，否则同一把密钥下重复 IV 会直接毁掉保密性。
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    generator.generateKey()
  }

  internal companion object {
    private val keyLock = Any()
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREF_IV = "secret_iv"
    private const val PREF_WRAPPED = "secret_wrapped"
    private const val GCM_TAG_BITS = 128
  }
}
