package com.factotum.app.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 封装一条 32 字节随机口令，供 SQLCipher 使用。
 *
 * 每个数据库对应一份独立的 vault（各自的 SharedPreferences 文件与 Keystore 别名），
 * 这样多个库的口令互不牵连，将来可以单独轮换。明文口令永不落盘、永不进日志。
 *
 * **重要约束**：Keystore 中的 AES 密钥是硬件封装、不可导出的，所以这些库
 * **在设备之外打不开**。手机 → PC 的数据交接必须走应用内导出，不能靠拷文件。
 */
internal class PassphraseVault(
  private val prefsName: String,
  private val keyAlias: String,
) {
  @Synchronized
  fun passphrase(context: Context): String {
    val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val storedIv = prefs.getString(PREF_IV, null)
    val storedWrapped = prefs.getString(PREF_WRAPPED, null)
    if (storedIv != null && storedWrapped != null) {
      return decrypt(storedIv, storedWrapped)
    }

    val random = ByteArray(PASSPHRASE_BYTES)
    SecureRandom().nextBytes(random)
    val encoded = Base64.encodeToString(random, Base64.NO_WRAP)
    random.fill(0)

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val wrapped = cipher.doFinal(encoded.toByteArray(Charsets.UTF_8))
    val persisted =
      prefs
        .edit()
        .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        .putString(PREF_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        .commit()
    check(persisted) { "数据库口令无法写入本地存储" }
    return encoded
  }

  private fun decrypt(ivEncoded: String, wrappedEncoded: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val iv = Base64.decode(ivEncoded, Base64.NO_WRAP)
    cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    val plain = cipher.doFinal(Base64.decode(wrappedEncoded, Base64.NO_WRAP))
    return String(plain, Charsets.UTF_8)
  }

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(keyAlias, null)
    if (existing is KeyStore.SecretKeyEntry) {
      return existing.secretKey
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return generator.generateKey()
  }

  internal companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREF_IV = "db_passphrase_iv"
    private const val PREF_WRAPPED = "db_passphrase_wrapped"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32

    // 主库（factotum.db）：会话 / 消息 / 工具调用。
    // prefs 名与 Keystore 别名决定用哪把密钥取口令：改名等于换一把新密钥，旧库再也解不开。
    // 与 applicationId / DATABASE_NAME 同属「存储身份」，改名时四处一起改。
    val App = PassphraseVault(prefsName = "factotum_app_keys", keyAlias = "factotum-app-db-key-v1")
  }
}
