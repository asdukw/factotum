package com.tappet.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.CharacterPins
import com.tappet.app.agent.CharacterStore

/**
 * 角色库（P0）。
 *
 * 这一版只做「选一张卡开始聊」这一条主线：卡片列表 → 点一下 → 重载运行时 → 回聊天页开口。
 * 没做的三件事都写在页面底部（导入、每个角色独立记忆、世界书按需检索），
 * 免得用户以为是坏了而不是还没做。
 */
@Composable
fun CharacterScreen(
  onBack: () -> Unit,
  onOpenDetail: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: CharacterViewModel = viewModel { CharacterViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  // 系统文件选择器：不申请存储权限，拿到的是单次可读的 content:// Uri。
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) viewModel.import(uri)
    }

  CharacterScreen(
    state = state,
    onBack = onBack,
    onStop = viewModel::stop,
    onImport = {
      // 有的网盘/文件管理器把 png 报成 octet-stream，所以给一组，别只写 image/png
      picker.launch(arrayOf("image/png", "image/*", "application/json", "text/plain", "application/octet-stream"))
    },
    onDelete = viewModel::delete,
    onCreateBlank = { viewModel.createBlank(onOpenDetail) },
    onDismissMessage = viewModel::consumeMessage,
    // 点一行进详情页：卡里存着人设 / 开场 / 世界设定，直接开聊等于逼用户先聊再判断。
    onOpenDetail = onOpenDetail,
    modifier = modifier,
  )
}

@Composable
internal fun CharacterScreen(
  state: CharacterViewModel.UiState,
  onBack: () -> Unit,
  onStop: () -> Unit,
  onImport: () -> Unit = {},
  onDelete: (String) -> Unit = {},
  onDismissMessage: () -> Unit = {},
  onOpenDetail: (String) -> Unit = {},
  onCreateBlank: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "角色", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
      ) {
        if (state.active != null) {
          ActiveBanner(card = state.active!!, applying = state.applying, onStop = onStop)
        }

        SectionLabel("角色卡")
        CardSourceRows(importing = state.importing, onImport = onImport, onCreateBlank = onCreateBlank)
        CardList(
          cards = state.cards,
          activeId = state.activeId,
          pins = CharacterPins.pinned(appContext),
          onOpen = onOpenDetail,
          onDelete = onDelete,
        )

        state.message?.let { message ->
          ImportMessage(message = message, notes = state.notes, onDismiss = onDismissMessage)
        }

        // 选中一张卡后，把「你会看到什么」摊开：开场白与场景。改不了，但能判断要不要进去。
        val preview = state.active
        if (preview != null) {
          SectionLabel("开场")
          SettingsGroup {
            Column(Modifier.padding(16.dp)) {
              Text(
                text = preview.firstMes.ifBlank { "（这张卡没有写开场白）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (preview.scenario.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                  text = "场景：" + preview.scenario.replace("{{user}}", "你").replace("{{char}}", preview.name),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        SectionLabel("怎么工作")
        SettingsGroup {
          Column(Modifier.padding(16.dp)) {
            Note("卡里的 system_prompt 与示例对话不会进来 —— 前者常写着「忽略一切规则」，后者占 token 且口癖会被过度模仿。")
            Note("常驻的世界设定每轮都在；其余条目在发消息那一刻按关键词命中才跟着这一轮送进去。")
            Note("记忆按角色分开存，只对它自己生效；内容由你写，模型不会偷偷往里加。")
          }
        }
        Spacer(Modifier.height(12.dp))
        Text(
          text = "开始扮演会重载运行时并开一段新上下文；退出角色随时可以回到日常。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
      }
    }
  }
}

/** 导入结果：成功一行 + 被丢弃字段的说明（这正是「不信任外部卡」对用户可见的部分）。 */
@Composable
private fun ImportMessage(message: String, notes: List<String>, onDismiss: () -> Unit) {
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      Text(message, style = MaterialTheme.typography.bodyMedium)
      notes.forEach { note -> Note(note) }
      if (notes.isNotEmpty() || message.startsWith("导入失败")) {
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onDismiss) { Text("知道了") }
      }
    }
  }
}

/** 空列表就明说空着，别放一张假卡让人以为坏了。 */
@Composable
private fun EmptyCards() {
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      Text("还没有角色卡", style = MaterialTheme.typography.bodyLarge)
      Spacer(Modifier.height(6.dp))
      Text(
        text = "点上面的「导入角色卡」，选一张 SillyTavern 的 PNG 卡或 .json 文件。" +
          "导入后只取人设与语气：卡里的 system_prompt、示例对话会被丢弃。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 两个来源：导入别人的卡，或者从空白开始自己写。 */
@Composable
private fun CardSourceRows(importing: Boolean, onImport: () -> Unit, onCreateBlank: () -> Unit) {
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Plus,
      title = if (importing) "正在读取…" else "导入角色卡",
      subtitle = "支持 SillyTavern 的 PNG 卡或 JSON（只取人设与语气）",
      enabled = !importing,
      onClick = onImport,
    ) {
      ChevronRight()
    }
    SettingsDivider()
    // 自己写：不导入任何人的卡，直接从空白开始 —— 这一条是「不要预设」的正解。
    SettingsRow(
      glyph = GlyphKind.Plus,
      title = "新建空白卡",
      subtitle = "从零写一张：名字 / 人设 / 开场白都留空，进编辑器自己填",
      onClick = onCreateBlank,
    ) {
      ChevronRight()
    }
  }
}

@Composable
private fun CardList(
  cards: List<CharacterCard>,
  activeId: String?,
  pins: List<String>,
  onOpen: (String) -> Unit,
  onDelete: (String) -> Unit,
) {
  if (cards.isEmpty()) {
    EmptyCards()
    return
  }
  SettingsGroup {
    cards.forEachIndexed { index, card ->
      if (index > 0) SettingsDivider()
      SettingsRow(
        glyph = GlyphKind.Person,
        // 收藏的卡在列表里也带个记号，不然点进详情才知道自己收藏过。
        title = if (card.id in pins) "★ ${card.name}" else card.name,
        subtitle = card.tagline.ifEmpty { card.tags.joinToString(" · ") },
        onClick = { onOpen(card.id) },
        trailing = {
          if (card.id == activeId) StatusBadge("扮演中", BadgeTone.Enabled)
          // 内置卡在 assets 里删不掉，所以只给导入的卡显示删除
          if (card.imported) {
            TextButton(onClick = { onDelete(card.id) }) { Text("删除") }
          } else {
            ChevronRight()
          }
        },
      )
    }
  }
}

@Composable
private fun ActiveBanner(card: CharacterCard, applying: Boolean, onStop: () -> Unit) {
  SettingsGroup {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          text = if (applying) "正在重载运行时…" else "正在扮演：${card.name}",
          style = MaterialTheme.typography.bodyLarge,
        )
        Text(
          text = card.tagline,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = onStop) { Text("退出角色") }
    }
  }
}

@Composable
private fun Note(text: String) {
  Text(
    text = "· $text",
    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, fontFamily = FontFamily.Default),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(vertical = 2.dp),
  )
}

/** 供设置首页摘要用。 */
internal fun characterSubtitle(card: CharacterCard?): String =
  when {
    card != null -> "正在扮演：${card.name}"

    // 页面现在是空的：副标题也要诚实，不然点进去才发现什么都没有。
    else -> "还没有角色卡"
  }
