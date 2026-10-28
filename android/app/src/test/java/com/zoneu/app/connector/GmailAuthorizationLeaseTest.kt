package com.zoneu.app.connector

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GmailAuthorizationLeaseTest {
  @Test
  fun `work cannot start before foreground notification is ready`() = runTest {
    val registry = GmailAuthorizationLease()
    val lease = registry.begin()
    var started = false
    val work = async {
      lease.protect {
        started = true
        "done"
      }
    }
    runCurrent()
    assertFalse(started)
    lease.ready.complete(Unit)
    assertEquals("done", work.await())
  }

  @Test
  fun `service start failure never starts browser work`() = runTest {
    val registry = GmailAuthorizationLease()
    val lease = registry.begin()
    registry.stop(lease.id, "AUTH_SERVICE_FAILED")
    var started = false
    val error = runCatching { lease.protect { started = true } }.exceptionOrNull()
    assertEquals("AUTH_SERVICE_FAILED", (error as GmailAuthorizationException).code)
    assertFalse(started)
  }

  @Test
  fun `readiness deadline fails closed`() = runTest {
    val lease = GmailAuthorizationLease().begin()
    var started = false
    val error = runCatching { lease.protect { started = true } }.exceptionOrNull()
    assertEquals("AUTH_SERVICE_FAILED", (error as GmailAuthorizationException).code)
    assertFalse(started)
  }

  @Test
  fun `cancel while waiting for service prevents work`() = runTest {
    val lease = GmailAuthorizationLease().begin()
    var started = false
    val work = async { lease.protect { started = true } }
    runCurrent()
    work.cancelAndJoin()
    lease.ready.complete(Unit)
    assertFalse(started)
  }

  @Test
  fun `notification cancel timeout and service destroy cancel active work`() = runTest {
    for (code in listOf("AUTH_CANCELLED", "TIMEOUT", "AUTH_SERVICE_STOPPED")) {
      val registry = GmailAuthorizationLease()
      val lease = registry.begin()
      lease.ready.complete(Unit)
      val started = CompletableDeferred<Unit>()
      var closed = false
      val work = async {
        runCatching {
          lease.protect {
            started.complete(Unit)
            try {
              awaitCancellation()
            } finally {
              closed = true
            }
          }
        }.exceptionOrNull()
      }
      started.await()
      registry.stop(lease.id, code)
      assertEquals(code, (work.await() as GmailAuthorizationException).code)
      assertTrue(closed)
      assertTrue(registry.release(lease.id))
    }
  }

  @Test
  fun `old attempt cannot stop or release a new attempt`() {
    val registry = GmailAuthorizationLease()
    val old = registry.begin()
    val next = registry.begin()
    registry.stop(old.id, "AUTH_SERVICE_STOPPED")
    assertFalse(registry.release(old.id))
    assertEquals(next, registry.current(next.id))
    assertFalse(next.ended.isCompleted)
    assertTrue(registry.release(next.id))
  }

  @Test
  fun `success and exception both release caller owned lease`() = runTest {
    for (fails in listOf(false, true)) {
      val registry = GmailAuthorizationLease()
      val lease = registry.begin()
      lease.ready.complete(Unit)
      try {
        runCatching { lease.protect { if (fails) error("synthetic") else Unit } }
      } finally {
        assertTrue(registry.release(lease.id))
      }
      assertEquals(null, registry.current(lease.id))
    }
  }

  @Test
  fun `authorization service manifest is private and declares the explicit special use`() {
    val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    val services = manifest.getElementsByTagName("service")
    val service = (0 until services.length).map { services.item(it) }
      .single { it.attributes.getNamedItem("android:name")?.nodeValue == ".connector.GmailAuthorizationService" }
    assertEquals("false", service.attributes.getNamedItem("android:exported").nodeValue)
    assertEquals("specialUse", service.attributes.getNamedItem("android:foregroundServiceType").nodeValue)
    val text = File("src/main/AndroidManifest.xml").readText()
    assertTrue(text.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    assertTrue(text.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
    val source = File("src/main/java/com/zoneu/app/connector/GmailAuthorizationService.kt").readText()
    assertTrue(source.contains("START_NOT_STICKY"))
    assertFalse(source.contains("refreshToken"))
    assertFalse(source.contains("redirectUri"))
  }
}
