package com.zoneu.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zoneu.app.agent.CardInput
import com.zoneu.app.agent.CardSnapshot
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.CharacterImporter
import com.zoneu.app.agent.CharacterMemory
import com.zoneu.app.agent.CharacterStore
import com.zoneu.app.agent.PersonaStore
import com.zoneu.app.agent.SessionHandoff
import com.zoneu.app.agent.worldBookRepository
import com.zoneu.app.data.db.SessionKind
import com.zoneu.app.data.session.zoneuSessionStore
import com.zoneu.app.node.NodeRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 角色库状态。
 *
 * 选中即生效（写偏好 → 重载运行时），与人格切换同一条路；代价也一样：**当前对话会开新上下文**。
 * 这条代价在界面上明说，并且「开始对话」按钮点完就直接回聊天页 —— 用户马上能看到开场。
 */
class CharacterViewModel(private val appContext: Context) : ViewModel() {
  data class UiState(
    val cards: List<CharacterCard> = emptyList(),
    /** 正在扮演的卡 id；null = 不在角色扮演。 */
    val activeId: String? = null,
    val applying: Boolean = false,
    val importing: Boolean = false,
    val message: String? = null,
    val unreadableCards: Int = 0,
    /** 导入兼容报告摘要；完整报告随卡保存，在详情页可回看。 */
    val notes: List<String> = emptyList(),
  ) {
    val active: CharacterCard? get() = cards.firstOrNull { it.id == activeId }
  }

  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    val listing = runCatching { CharacterStore.library(appContext) }.getOrElse {
      _state.update { state -> state.copy(message = "角色库读取失败；保留现有列表，请稍后重新打开。") }
      return
    }
    val selected = runCatching { PersonaStore.selectedCharacter(appContext)?.id }.getOrNull()
    _state.update {
      it.copy(cards = listing.cards, unreadableCards = listing.unreadable, activeId = selected)
    }
  }

  /**
   * 开始对话：**给这张卡新开一段酒馆会话**，然后把「去哪」交给聊天页。
   *
   * 这里不写偏好、也不重载运行时：卡的生效属于「会话」而不是「全局设置」——
   * 同一张卡可以有好几段对话，谁在说话由当前打开的那段决定。重载交给聊天页
   * 切会话时做，一次，而不是这里一次、那边再一次。
   */
  fun start(id: String, onReady: () -> Unit = {}) {
    val card = _state.value.cards.firstOrNull { it.id == id } ?: return
    if (_state.value.applying) return
    _state.update { it.copy(applying = true) }
    viewModelScope.launch {
      val created =
        runCatching {
          appContext.zoneuSessionStore().createSession(
            title = card.name,
            kind = SessionKind.Roleplay,
            characterId = card.id,
          )
        }
      created.onSuccess { session -> SessionHandoff.open(session.id) }
      // 建完（含失败）再回聊天页：早一步返回会让聊天页在交接还没写下时就去取，
      // 结果停在原来那段会话上 —— 看起来像「点了没反应」。
      onReady()
      refresh()
      _state.update {
        it.copy(
          applying = false,
          message =
            created.fold(
              onSuccess = { "已开一段新对话，回去就能说话" },
              onFailure = { error -> "开不了会话：${error.message ?: error.javaClass.simpleName}" },
            ),
        )
      }
    }
  }

  /** 退出扮演：回最近一段日常会话（人格在那里生效）。 */
  fun stop() {
    SessionHandoff.openNormal()
    _state.update { it.copy(message = "回到日常会话后，人格才会生效") }
  }

  fun consumeMessage() {
    _state.update { it.copy(message = null, notes = emptyList()) }
  }

  /**
   * 导入一张卡（PNG 或 JSON）。
   *
   * 存下来再刷新，而不是把解析结果直接塞进内存列表：卡最终是磁盘上的数据，
   * 只放内存会让「重启 App 后卡不见了」这种问题晚一步才被发现。
   */
  fun import(uri: Uri) {
    if (_state.value.importing) return
    _state.update { it.copy(importing = true, notes = emptyList()) }
    viewModelScope.launch {
      var existing: CharacterCard? = null
      val outcome =
        runCatching {
          val bytes =
            appContext.contentResolver.openInputStream(uri)?.use { CardInput.read(it) }
              ?: throw CharacterImporter.ImportException("读不到这个文件")
          val parsed = CharacterImporter.fromBytes(bytes, displayName(uri))
          // 与 URL 导入共用内容指纹：同一份卡再选一次不应该变成两张，之后的编辑才不会分裂。
          existing =
            parsed.card.importMetadata?.contentHash?.let { hash ->
              CharacterStore.cards(appContext).firstOrNull { it.importMetadata?.contentHash == hash }
            }
          if (existing == null) {
            CharacterStore.createImported(
              appContext,
              parsed.card,
              bytes.takeIf { it.size >= 8 && it.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) },
            )
          }
          parsed
        }
      outcome
        .onSuccess { result ->
          refresh()
          _state.update {
            val duplicate = existing
            it.copy(
              importing = false,
              message = if (duplicate != null) "库里已有「${duplicate.name}」，未重复导入" else "已导入「${result.card.name}」",
              notes = result.notes,
            )
          }
        }
        .onFailure { error ->
          _state.update {
            it.copy(importing = false, message = "导入失败：${error.message ?: error::class.simpleName}")
          }
        }
    }
  }

  /** The library list supplies its already-loaded baseline, never a fresh revision on deletion. */
  fun delete(id: String): Boolean = _state.value.cards.firstOrNull { it.id == id }?.let(::delete) ?: false

  /** Cleanup follows successful deletion; stale drafts cannot delete a newer card. */
  fun delete(card: CharacterCard): Boolean {
    val deleted = runCatching {
      CharacterStore.deleteImported(appContext, card.id, card.storageRevision.orEmpty())
    }
    if (deleted.isFailure) {
      _state.update { it.copy(message = "删除失败：角色卡可能已改变，请重新读取。") }
      return false
    }
    CharacterMemory.forget(appContext, card.id)
    // Card files are outside Room: drop the dead default bindings so their books
    // do not stay blocked by a character that no longer exists.
    viewModelScope.launch { runCatching { appContext.worldBookRepository().forgetCharacter(card.id) } }
    if (_state.value.activeId == card.id) {
      PersonaStore.clearCharacter(appContext)
      apply("已删除角色卡，已退出扮演")
    }
    refresh()
    _state.update { it.copy(message = "已删除") }
    return true
  }

  fun createBlank(onCreated: (String) -> Unit) {
    val saved = runCatching { CharacterStore.createImported(appContext, blankCard()) }.getOrElse {
      _state.update { it.copy(message = "新建失败：写不进应用目录或身份冲突") }
      return
    }
    refresh()
    onCreated(saved.card.id)
  }

  /** Return the actual card baseline even when the separate memory commit fails. */
  fun save(card: CharacterCard, memory: String = ""): CharacterSaveResult {
    val outcome = runCatching {
      CharacterStore.updateImported(appContext, card.id, card.storageRevision.orEmpty()) { current ->
        current.copy(
          name = card.name,
          nickname = card.nickname,
          tagline = card.tagline,
          tags = card.tags,
          description = card.description,
          personality = card.personality,
          scenario = card.scenario,
          firstMes = card.firstMes,
          alternateGreetings = card.alternateGreetings,
          groupOnlyGreetings = card.groupOnlyGreetings,
          useCardPrompt = card.useCardPrompt,
          useExampleDialogue = card.useExampleDialogue,
          world = card.world,
        )
      }
    }
    val saved = outcome.getOrElse { error ->
      val code = (error as? com.zoneu.app.agent.CardOperationException)?.code ?: "CARD_SAVE_UNCONFIRMED"
      _state.update { it.copy(message = "保存失败（$code）：草稿保留；请重新读取卡片后再编辑。") }
      return CharacterSaveResult.NotCommitted(code)
    }
    refresh()
    if (!CharacterMemory.save(appContext, card.id, memory)) {
      _state.update { it.copy(message = "角色卡已保存，但角色记忆保存失败；草稿保留，请重试。") }
      return CharacterSaveResult.MemoryFailed(saved)
    }
    _state.update { it.copy(message = "已保存角色卡") }
    return CharacterSaveResult.Saved(saved)
  }

  private fun displayName(uri: Uri): String {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    val name =
      runCatching {
        appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
      }
        .getOrNull()
    return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "未命名角色"
  }

  private fun apply(success: String) {
    if (_state.value.applying) return
    _state.update { it.copy(applying = true) }
    viewModelScope.launch {
      val outcome =
        runCatching { NodeRuntime.reload(appContext) }
          .fold(onSuccess = { success }, onFailure = { "切换失败：${it.message ?: it::class.simpleName}" })
      _state.update { it.copy(applying = false, message = outcome) }
    }
  }
}

/** A Boolean cannot distinguish a conflict from a new card with unsaved memory. */
sealed interface CharacterSaveResult {
  data class NotCommitted(val code: String) : CharacterSaveResult

  data class MemoryFailed(val snapshot: CardSnapshot) : CharacterSaveResult

  data class Saved(val snapshot: CardSnapshot) : CharacterSaveResult
}
