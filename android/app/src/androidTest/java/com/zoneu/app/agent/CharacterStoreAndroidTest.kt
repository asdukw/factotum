package com.zoneu.app.agent

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zoneu.app.ui.settings.CharacterSaveResult
import com.zoneu.app.ui.settings.CharacterViewModel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 只使用本测试新建的 cache 子目录，不触碰用户卡、记忆或数据库。 */
@RunWith(AndroidJUnit4::class)
class CharacterStoreAndroidTest {
  private lateinit var root: File
  private lateinit var context: Context

  @Before
  fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    root = File(app.cacheDir, "card-store-test-${UUID.randomUUID()}").apply { mkdirs() }
    context = object : ContextWrapper(app) {
      override fun getFilesDir(): File = root
    }
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun oldCardsAndArchivedCardsSurviveDiskRead() {
    val directory = File(root, "roleplay/characters").apply { mkdirs() }
    File(directory, "old.json").writeText("""{"id":"old","name":"旧卡","description":"人设"}""")
    assertEquals("旧卡", CharacterStore.byId(context, "old")!!.name)
    val imported = CharacterImporter.fromBytes(
      """{"name":"导入","description":"人设","system_prompt":"PRIVATE_ARCHIVE","extensions":{"x":[1]}}""".toByteArray(),
      "x",
    ).card
    val saved = CharacterStore.createImported(context, imported)
    val read = CharacterStore.byId(context, imported.id)!!
    assertEquals(saved.card, read)
    assertTrue(CardExporter.toV3(read).contains("PRIVATE_ARCHIVE"))
    assertFalse(CharacterStore.renderPrompt(read).contains("PRIVATE_ARCHIVE"))
  }

  @Test
  fun unfinishedAtomicWriteRecoversOriginalAndFailedSaveDoesNotReportSuccess() {
    val card = CharacterStore.createImported(context, CharacterCard("test", "原名", description = "人设")).card
    val target = File(root, "roleplay/characters/test.json")
    val atomic = AtomicFile(target)
    val stream = atomic.startWrite()
    stream.write("incomplete".toByteArray())
    atomic.failWrite(stream)
    assertEquals(card, CharacterStore.byId(context, "test"))
    val blocked = File(root, "blocked").apply { writeText("not a directory") }
    val failing = object : ContextWrapper(context) {
      override fun getFilesDir(): File = blocked
    }
    assertTrue(runCatching { CharacterStore.createImported(failing, card.copy(name = "未保存")) }.isFailure)
    assertEquals("原名", Json.decodeFromString(CharacterCard.serializer(), target.readText()).name)
  }

  @Test
  fun memorySaveFailureAndClearFailureAreReported() {
    val blocked = File(root, "roleplay/memory/c.md").apply { mkdirs() }
    File(blocked, "child").writeText("test")
    assertFalse(CharacterMemory.save(context, "c", ""))
    assertFalse(CharacterMemory.save(context, "c", "new"))
  }

  @Test
  fun editorSaveResultDistinguishesFailureFromPartialSuccessAndRetainsNewBaseline() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val model = CharacterViewModel(context)
      val missing = model.save(CharacterCard("missing", "角色", description = "人设"))
      assertTrue(missing is CharacterSaveResult.NotCommitted)
      val initial = CharacterStore.createImported(context, CharacterCard("partial", "原名", description = "人设"))
      val memory = File(root, "roleplay/memory/partial.md").apply { mkdirs() }
      File(memory, "child").writeText("test")
      val partial = model.save(initial.card.copy(name = "已编辑"), "new memory") as CharacterSaveResult.MemoryFailed
      assertNotEquals(initial.revision, partial.snapshot.revision)
      assertTrue(model.state.value.message!!.contains("角色卡已保存，但角色记忆保存失败"))
      assertEquals("已编辑", CharacterStore.byId(context, "partial")!!.name)
      assertTrue(model.save(initial.card.copy(name = "旧草稿"), "new memory") is CharacterSaveResult.NotCommitted)
      memory.deleteRecursively()
      val retried = model.save(partial.snapshot.card, "new memory") as CharacterSaveResult.Saved
      assertEquals(partial.snapshot.revision, retried.snapshot.revision)
      assertEquals("new memory", CharacterMemory.notes(context, "partial"))
    }
  }

  @Test
  fun readsDoNotCreateDirectoriesAndLegacyRevisionChangesOnlyOnApprovedWrite() {
    assertTrue(CharacterStore.snapshots(context).isEmpty())
    assertFalse(File(root, "roleplay").exists())
    val directory = File(root, "roleplay/characters").apply { mkdirs() }
    val file = File(directory, "legacy.json")
    val original = """{"id":"legacy","name":"旧卡","imported":false,"world":[{"content":"x","constant":true}]}"""
    file.writeText(original)
    val first = CharacterStore.readSnapshot(context, "legacy")!!
    assertTrue(first.editable)
    assertTrue(first.card.imported)
    assertEquals(first.revision, CharacterStore.readSnapshot(context, "legacy")!!.revision)
    assertEquals("", first.card.world.single().sourceToken)
    assertEquals(original, file.readText())
    val changed = CharacterStore.updateImported(context, "legacy", first.revision) { it.copy(name = "新名") }
    assertNotEquals(first.revision, changed.revision)
    assertTrue(changed.card.world.single().sourceToken.isNotBlank())
    assertThrows(CardOperationException::class.java) {
      CharacterStore.updateImported(context, "legacy", first.revision) { it.copy(name = "过期") }
    }
  }

  @Test
  fun assetOriginIsReadOnlyAndSameIdFileIsAnExplicitConflict() {
    val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    val assetsContext = object : ContextWrapper(context) {
      override fun getAssets() = testAssets
    }
    val builtin = CharacterStore.readSnapshot(assetsContext, "builtin-fixture")!!
    assertFalse(builtin.editable)
    assertFalse(builtin.card.imported)
    assertEquals(
      "CARD_READ_ONLY",
      assertThrows(CardOperationException::class.java) {
        CharacterStore.updateImported(assetsContext, builtin.card.id, builtin.revision) { it.copy(name = "forbidden") }
      }.code,
    )
    assertEquals(
      "CARD_ALREADY_EXISTS",
      assertThrows(CardOperationException::class.java) {
        CharacterStore.createImported(assetsContext, builtin.card.copy(imported = true))
      }.code,
    )
    val directory = File(root, "roleplay/characters").apply { mkdirs() }
    File(directory, "builtin-fixture.json").writeText("""{"id":"builtin-fixture","name":"collision"}""")
    assertEquals(
      "CARD_ID_CONFLICT",
      assertThrows(CardOperationException::class.java) {
        CharacterStore.readSnapshot(assetsContext, "builtin-fixture")
      }.code,
    )
    assertThrows(CardOperationException::class.java) { CharacterStore.snapshots(assetsContext) }
  }

  @Test
  fun conversationalCreateReplaysReceiptAndDoesNotOverwriteLocalEditsOrResurrectDeletedCard() {
    val fields = Json.parseToJsonElement("""{"name":"自然语言生成","description":"人设","first_mes":"开场"}""")
      as kotlinx.serialization.json.JsonObject
    val request = "test-${UUID.randomUUID()}"
    val result = CharacterCardOperations.create(context, request, fields)
    assertTrue(result.ok)
    val payload = Json.parseToJsonElement(result.payload!!) as kotlinx.serialization.json.JsonObject
    val id = (payload["characterId"] as kotlinx.serialization.json.JsonPrimitive).content
    val card = CharacterStore.byId(context, id)!!
    val edited = CharacterStore.updateImported(context, id, card.storageRevision!!) { it.copy(name = "本地修改") }
    assertTrue(CharacterCardOperations.create(context, request, fields).payload!!.contains("本地修改"))
    assertTrue(CharacterCardOperations.create(context, "$request-new", fields).payload!!.contains(id))
    assertEquals(1, CharacterStore.cards(context).count { it.id == id })
    CharacterStore.deleteImported(context, id, edited.revision)
    assertEquals("CARD_PREVIOUSLY_DELETED", CharacterCardOperations.create(context, request, fields).code)
  }

  @Test
  fun brokenLegacyCardDoesNotCrashBrowsingOrAuthorizeOverwritingItsFile() {
    val directory = File(root, "roleplay/characters").apply { mkdirs() }
    val broken = File(directory, "broken.json")
    val raw = """{"id":"broken","name":"""
    broken.writeText(raw)
    val good = CharacterStore.createImported(context, CharacterCard("good", "正常卡", description = "人设"))
    val listing = CharacterStore.library(context)
    assertEquals(1, listing.unreadable)
    assertTrue(listing.cards.any { it.id == "good" })
    assertEquals("正常卡", CharacterStore.byId(context, "good")!!.name)
    assertTrue(runCatching { CharacterStore.readSnapshot(context, "broken") }.isFailure)
    assertTrue(runCatching { CharacterStore.snapshots(context) }.isFailure)
    assertTrue(runCatching { CharacterStore.createImported(context, CharacterCard("broken", "不得覆盖")) }.isFailure)
    assertEquals(raw, broken.readText())
    val tools = CharacterCardLibrary.forContext(context)
    val result = tools.list(Json.parseToJsonElement("""{"query":"正常卡"}""") as kotlinx.serialization.json.JsonObject)
    assertTrue(result.ok)
    val listed = Json.parseToJsonElement(result.payload!!) as kotlinx.serialization.json.JsonObject
    assertEquals("1", (listed["unreadable_count"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertEquals("true", (listed["partial"] as kotlinx.serialization.json.JsonPrimitive).content)
    assertTrue(
      (listed["items"] as kotlinx.serialization.json.JsonArray).any {
        (it as kotlinx.serialization.json.JsonObject)["character_id"] == kotlinx.serialization.json.JsonPrimitive("good")
      },
    )
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val model = CharacterViewModel(context)
      assertEquals(1, model.state.value.unreadableCards)
      assertTrue(model.state.value.cards.any { it.id == "good" })
      val saved = model.save(good.card.copy(name = "可保存正常卡"))
      assertTrue(saved is CharacterSaveResult.Saved)
      assertEquals("可保存正常卡", CharacterStore.byId(context, "good")!!.name)
    }
  }

  @Test
  fun refreshedLibraryUsesLatestRevisionAfterDetailOrToolUpdates() {
    val base = CharacterStore.createImported(context, CharacterCard("c", "旧名", description = "人设"))
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val model = CharacterViewModel(context)
      val changed = CharacterStore.updateImported(context, "c", base.revision) { it.copy(name = "新名") }
      model.refresh()
      assertEquals(changed.revision, model.state.value.cards.single { it.id == "c" }.storageRevision)
      assertTrue(model.delete("c"))
      assertEquals(null, CharacterStore.readSnapshot(context, "c"))
    }
  }

  @Test
  fun noOpLegacyDraftDoesNotMigrateAndDeletionRejectsItsStaleBaseline() {
    val directory = File(root, "roleplay/characters").apply { mkdirs() }
    val file = File(directory, "legacy.json")
    val raw = """{"id":"legacy","name":"旧卡","imported":false,"world":[{"keys":["x"],"content":"old"}]}"""
    file.writeText(raw)
    val legacy = CharacterStore.readSnapshot(context, "legacy")!!
    val noChange = CharacterStore.updateImported(context, "legacy", legacy.revision) { CardStorageIdentity.draft(it) }
    assertEquals(legacy.revision, noChange.revision)
    assertEquals(raw, file.readText())
    val saved = CharacterStore.updateImported(context, "legacy", legacy.revision) { it.copy(name = "new") }
    assertNotEquals(legacy.revision, saved.revision)
    assertEquals(
      "CARD_REVISION_CONFLICT",
      assertThrows(CardOperationException::class.java) {
        CharacterStore.deleteImported(context, "legacy", legacy.revision)
      }.code,
    )
  }
}
