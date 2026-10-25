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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.CharacterMemory
import com.tappet.app.agent.CharacterPins
import com.tappet.app.agent.CharacterStore

/**
 * 一张角色卡的详情页。
 *
 * 参考同类产品「作品页 → 新对话」的结构：侧栏那一行只够放名字，用户真正要判断的是
 * 「这张卡在演什么、开场是什么、世界设定有几条」—— 那些字段导入时都读过来了，之前却
 * 只显示了一行 tagline，等于白存。
 *
 * 页面上不放编辑入口：卡是导入的数据，改文案在源文件里改，重导一次比在手机上打一段
 * 长文靠谱。
 */
@Composable
fun CharacterDetailScreen(
  characterId: String,
  onBack: () -> Unit,
  onStartChat: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: CharacterViewModel = viewModel { CharacterViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  // 本地持一份，编辑保存后不用等列表刷新回来（列表是另一条流，回来晚一步就会闪一下旧文案）。
  var card by remember(characterId) { mutableStateOf(CharacterStore.byId(appContext, characterId)) }
  var pinned by remember(characterId) { mutableStateOf(CharacterPins.isPinned(appContext, characterId)) }
  // 空白卡建完直接进编辑：它的第一个动作一定是「写名字」，没有别的可看。
  var editing by remember(characterId) { mutableStateOf(card?.name.isNullOrBlank()) }

  val current = card
  if (editing && current != null) {
    CharacterEditPage(
      card = current,
      memory = CharacterMemory.notes(appContext, characterId),
      onCancel = { editing = false },
      onSave = { updated, memory ->
        viewModel.save(updated, memory)
        card = updated
        editing = false
      },
    )
    return
  }

  CharacterDetailScreen(
    card = current,
    pinned = pinned,
    applying = state.applying,
    memory = CharacterMemory.notes(appContext, characterId),
    onBack = onBack,
    onStart = { id -> viewModel.start(id) { onStartChat() } },
    onTogglePin = { pinned = CharacterPins.toggle(appContext, characterId) },
    onEdit = { editing = true },
    onDelete = {
      viewModel.delete(characterId)
      CharacterPins.forget(appContext, characterId)
      onBack()
    },
    modifier = modifier,
  )
}

@Composable
internal fun CharacterDetailScreen(
  card: CharacterCard?,
  pinned: Boolean,
  applying: Boolean,
  memory: String = "",
  onBack: () -> Unit,
  onStart: (String) -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit = {},
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = card?.name ?: "角色", onBack = onBack)
      if (card == null) {
        MissingCard()
        return@Column
      }
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
      ) {
        CardHero(card = card)
        Spacer(Modifier.height(12.dp))
        ActionRow(
          pinned = pinned,
          applying = applying,
          editable = card.imported,
          onStart = { onStart(card.id) },
          onTogglePin = onTogglePin,
          onEdit = onEdit,
        )

        ProseSection("人设", card.description)
        ProseSection("性格与说话方式", card.personality)
        ProseSection("开场场景", card.scenario)
        ProseSection("开场白", card.firstMes)

        if (card.world.isNotEmpty()) {
          SectionLabel("世界设定 · ${card.world.size} 条")
          SettingsGroup {
            Column(Modifier.padding(16.dp)) {
              card.world
                .sortedByDescending { it.constant }
                .forEach { entry ->
                  Text(
                    text = if (entry.constant) "常驻" else entry.keys.joinToString("、").ifBlank { "无关键词" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = entry.content.take(160) + if (entry.content.length > 160) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 10.dp),
                  )
                }
            }
          }
        }

        MemoryCard(memory)
        AboutCard(card)

        if (card.imported) {
          Spacer(Modifier.height(16.dp))
          TextButton(onClick = onDelete) {
            Text("删除这张卡", color = MaterialTheme.colorScheme.error)
          }
          Text(
            text = "删卡不会删掉已经聊过的会话：历史仍然在酒馆里，只是标成「已删除的角色」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }
      }
    }
  }
}

/**
 * 角色记忆：一张卡一份，注入到它自己的系统提示里。
 *
 * 摆在这一页是因为它和卡的正文一样属于「这张卡是什么」；但存在另一份文件里 ——
 * 记忆是随对话长出来的，不该混进从别处导入的卡正文。
 */
@Composable
private fun MemoryCard(notes: String) {
  SectionLabel("角色记忆")
  SettingsGroup {
    Text(
      text = notes.ifBlank { "还没有。点「编辑文案」可以写：你们之间发生过什么、约好过什么 —— 只对这张卡生效。" },
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
  }
}

@Composable
private fun AboutCard(card: CharacterCard) {
  SectionLabel("关于这张卡")
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      Detail("来源", if (card.imported) "导入的卡（存在应用私有目录）" else "内置卡")
      if (card.creator.isNotBlank()) Detail("作者", card.creator)
      if (card.license.isNotBlank()) Detail("许可", card.license)
      if (card.tags.isNotEmpty()) Detail("标签", card.tags.joinToString(" · "))
      Detail(
        "这两样都不进来",
        "卡里的 system_prompt / 示例对话在导入时就被丢弃 —— 前者常写着「忽略一切规则」，" +
          "后者占 token 且口癖会被过度模仿。编辑页也没有这两个输入框，那里不该成为绕过去的路。",
      )
    }
  }
}

@Composable
private fun CardHero(card: CharacterCard) {
  SettingsGroup {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
      Text(card.name, style = MaterialTheme.typography.headlineSmall)
      if (card.tagline.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
          text = card.tagline,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (card.tags.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = card.tags.joinToString("  ·  "),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun ActionRow(
  pinned: Boolean,
  applying: Boolean,
  editable: Boolean,
  onStart: () -> Unit,
  onTogglePin: () -> Unit,
  onEdit: () -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Button(onClick = onStart, enabled = !applying, modifier = Modifier.weight(1f)) {
        Text(if (applying) "正在准备…" else "开始对话")
      }
      Spacer(Modifier.width(8.dp))
      TextButton(onClick = onTogglePin) { Text(if (pinned) "取消收藏" else "收藏置顶") }
    }
    // 内置卡的正文在 assets 里，改了会被下次打包覆盖 —— 只有导入/新建的卡能改。
    if (editable) {
      TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.End)) { Text("编辑文案") }
    }
  }
}

@Composable
private fun ProseSection(title: String, body: String) {
  if (body.isBlank()) return
  SectionLabel(title)
  SettingsGroup {
    Text(
      text = body.trim().replace("{{char}}", "角色").replace("{{user}}", "你"),
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
  }
}

@Composable
private fun Detail(label: String, value: String) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 8.dp),
  )
  Text(
    text = value,
    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Default, lineHeight = 18.sp),
    modifier = Modifier.padding(top = 2.dp),
  )
}

/** 卡被删了还停在详情页（从侧栏进来、期间删卡）：说清原因，给条退路。 */
@Composable
private fun MissingCard() {
  Column(Modifier.fillMaxSize().padding(24.dp)) {
    Text("这张卡不在了", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
      text = "可能刚被删除，或者从别的设备同步掉了。已经聊过的会话不会跟着消失。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
