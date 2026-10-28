package com.zoneu.app.connector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zoneu.app.data.crypto.SecretVault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** 只使用本测试独立命名空间，绝不清应用现有连接器或模型凭据。 */
@RunWith(AndroidJUnit4::class)
class GmailCredentialAndroidTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val name = "gmail-test-${UUID.randomUUID()}"
  private val alias = "$name-key"

  private fun vault() = SecretVault(name, alias)

  @After
  fun cleanIsolatedStorage() {
    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    KeyStore.getInstance("AndroidKeyStore").apply {
      load(null)
      deleteEntry(alias)
    }
  }

  @Test
  fun encryptedRecordSurvivesNewVaultInstance() {
    val credential = GmailCredential("synthetic-refresh", GmailClient("synthetic-client", "synthetic-secret"), "fixture@example.test")
    assertTrue(vault().put(context, encodeGmailCredential(credential)))
    val stored = vault().read(context) as SecretVault.ReadResult.Present
    assertEquals(GmailCredentialRead.Present(credential), decodeGmailCredential(stored.value))
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    assertFalse(prefs.all.values.any { it.toString().contains("synthetic-refresh") })
  }

  @Test
  fun absentKeyIsUnreadableAndReadDoesNotRecreateIt() {
    assertTrue(vault().put(context, "synthetic-refresh"))
    val keys = KeyStore.getInstance("AndroidKeyStore").apply {
      load(null)
      deleteEntry(alias)
    }
    assertEquals(SecretVault.ReadResult.Unreadable, vault().read(context))
    assertFalse(keys.containsAlias(alias))
    assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).contains("secret_wrapped"))
  }

  @Test
  fun missingAndCorruptedValuesAreDistinct() {
    assertEquals(SecretVault.ReadResult.Missing, vault().read(context))
    assertTrue(vault().put(context, "synthetic-refresh"))
    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("secret_wrapped", "corrupt").commit()
    assertEquals(SecretVault.ReadResult.Unreadable, vault().read(context))
    assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).contains("secret_wrapped"))
  }

  @Test
  fun legacyRefreshTokenRemainsReadableWithoutBindingAssumption() {
    assertTrue(vault().put(context, "synthetic-legacy"))
    val read = vault().read(context) as SecretVault.ReadResult.Present
    assertEquals(GmailCredentialRead.Present(GmailCredential("synthetic-legacy", null)), decodeGmailCredential(read.value))
  }
}
