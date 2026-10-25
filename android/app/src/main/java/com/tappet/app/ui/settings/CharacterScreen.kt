package com.tappet.app.ui.settings

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
import com.tappet.app.agent.CharacterStore

/**
 * 角色库（P0）。
 *
 * 这一版只做「选一张卡开始聊」这一条主线：卡片列表 → 点一下 → 重载运行时 → 回聊天页开口。
 * 没做的三件事都写在页面底部（导入、每个角色独立记忆、世界书按需检索），
 * 免得用户以为是坏了而不是还没做。
 */
@Composable
fun CharacterScreen(onBack: () -> Unit, onStartChat: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: CharacterViewModel = viewModel { CharacterViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  CharacterScreen(
    state = state,
    onBack = onBack,
    onStart = { id ->
      viewModel.start(id)
      onStartChat()
    },
    onStop = viewModel::stop,
    modifier = modifier,
  )
}

@Composable
internal fun CharacterScreen(
  state: CharacterViewModel.UiState,
  onBack: () -> Unit,
  onStart: (String) -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
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
        CardList(cards = state.cards, activeId = state.activeId, onStart = onStart)

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

        SectionLabel("还没做的")
        SettingsGroup {
          Column(Modifier.padding(16.dp)) {
            Note("写自己的角色 / 导入社区角色卡（PNG / JSON）")
            Note("每个角色独立记忆（现在共用一份 AGENTS.md）")
            Note("世界书按对话内容按需检索（现在只注入常驻条目与开场命中的条目）")
          }
        }
        Spacer(Modifier.height(12.dp))
        Text(
          text = "开始扮演会重载运行时并开一段新上下文；退出角色随时可以回到默认人格。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
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
        text = "角色卡是数据文件（assets/roleplay/characters/*.json），导入与编辑入口还没做，" +
          "所以这一页先空着 —— 读卡、渲染人设、注入世界设定的链路已经在跑。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CardList(cards: List<CharacterCard>, activeId: String?, onStart: (String) -> Unit) {
  if (cards.isEmpty()) {
    EmptyCards()
    return
  }
  SettingsGroup {
    cards.forEachIndexed { index, card ->
      if (index > 0) SettingsDivider()
      SettingsRow(
        glyph = GlyphKind.Person,
        title = card.name,
        subtitle = card.tagline.ifEmpty { card.tags.joinToString(" · ") },
        onClick = { onStart(card.id) },
        trailing = {
          if (card.id == activeId) StatusBadge("扮演中", BadgeTone.Enabled) else ChevronRight()
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
