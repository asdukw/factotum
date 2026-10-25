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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.WorldEntry
import java.util.UUID

/** 编辑中的一条世界设定（[keys] 用逗号分隔的文本，保存时再拆）。 */
private data class WorldDraft(
  val keys: String,
  val content: String,
  val constant: Boolean,
)

/**
 * 角色卡编辑页。
 *
 * 为什么需要它：卡是导入的外部数据，文案好坏只能由用户判断 —— 上一版助手代写的开场白被否过，
 * 「改文案去源文件改、重导一次」在 PC 上还行，在手机上等于做不到。所以这里给一张表单，
 * 让导入的卡能就地改；也给「新建空白卡」用同一条路，从零写一张。
 *
 * 只改**人设与语气这几层**（与导入时的白名单一致）：卡里的 `system_prompt` / 示例对话本来
 * 就没进来，这里也没有对应的输入框 —— 编辑页不该成为绕过那道闸门的后门。
 */
@Composable
internal fun CharacterEditPage(
  card: CharacterCard,
  memory: String = "",
  onCancel: () -> Unit,
  onSave: (CharacterCard, String) -> Unit,
) {
  var name by remember(card.id) { mutableStateOf(card.name) }
  var tagline by remember(card.id) { mutableStateOf(card.tagline) }
  var description by remember(card.id) { mutableStateOf(card.description) }
  var personality by remember(card.id) { mutableStateOf(card.personality) }
  var scenario by remember(card.id) { mutableStateOf(card.scenario) }
  var firstMes by remember(card.id) { mutableStateOf(card.firstMes) }
  var tags by remember(card.id) { mutableStateOf(card.tags.joinToString("、")) }
  var memoryText by remember(card.id) { mutableStateOf(memory) }
  val world =
    remember(card.id) {
      mutableStateListOf<WorldDraft>().apply {
        card.world.forEach { entry ->
          add(WorldDraft(keys = entry.keys.joinToString("、"), content = entry.content, constant = entry.constant))
        }
      }
    }

  Surface(Modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "编辑角色", onBack = onCancel)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
      ) {
        Field("名字", name, singleLine = true) { name = it }
        Field("一句话介绍", tagline, singleLine = true) { tagline = it }
        Field("标签", tags, singleLine = true, hint = "用、或逗号分隔") { tags = it }
        Field("人设（身份与外貌）", description, minLines = 4) { description = it }
        Field("性格与说话方式", personality, minLines = 3) { personality = it }
        Field("开场场景", scenario, minLines = 2) { scenario = it }
        Field("开场白（第一句话由它开始）", firstMes, minLines = 4) { firstMes = it }
        Text(
          text = "「{{char}}」会被换成角色名，「{{user}}」换成「你」—— 与 SillyTavern 的写法一致。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        WorldEditSection(world)

        SectionLabel("角色记忆")
        Field(
          label = "你与用户之间已经发生过的事",
          value = memoryText,
          minLines = 3,
          hint = "只对这张卡生效，换角色不会串。写关系、约定、发生过的事；留空即清除。",
        ) { memoryText = it }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = onCancel) { Text("取消") }
          Spacer(Modifier.weight(1f))
          Button(
            onClick = {
              val next =
                card.copy(
                  name = name.trim().ifBlank { "未命名角色" },
                  tagline = tagline.trim(),
                  description = description.trim(),
                  personality = personality.trim(),
                  scenario = scenario.trim(),
                  firstMes = firstMes.trim(),
                  tags = splitTokens(tags),
                  world = toWorldEntries(world),
                  imported = true,
                )
              onSave(next, memoryText)
            },
            enabled = name.isNotBlank(),
          ) {
            Text("保存")
          }
        }
      }
    }
  }
}

/**
 * 世界设定编辑：一条一张卡，关键词、正文、是否常驻。
 *
 * 常驻 / 按关键词是这张卡最容易被忽略的一层 —— 不加说明的话，用户会以为「没填关键词 = 不生效」，
 * 而实际上留空意味着这条永远不注入。
 */
@Composable
private fun WorldEditSection(world: MutableList<WorldDraft>) {
  SectionLabel("世界设定 · ${world.size} 条")
  world.forEachIndexed { index, entry ->
    SettingsGroup {
      Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("第 ${index + 1} 条", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
          TextButton(onClick = { world.removeAt(index) }) {
            Text("删除", color = MaterialTheme.colorScheme.error)
          }
        }
        Field(
          label = "关键词",
          value = entry.keys,
          singleLine = true,
          hint = "对话里命中其一才注入；留空则永远不注入",
        ) { value -> world[index] = entry.copy(keys = value) }
        Field(label = "内容", value = entry.content, minLines = 3) { value ->
          world[index] = entry.copy(content = value)
        }
        TextButton(
          onClick = { world[index] = entry.copy(constant = !entry.constant) },
          modifier = Modifier.align(Alignment.End),
        ) {
          Text(if (entry.constant) "常驻：每轮都注入" else "常驻：关闭（按关键词）")
        }
      }
    }
  }
  TextButton(onClick = { world.add(WorldDraft(keys = "", content = "", constant = false)) }) {
    Text("添加一条设定")
  }
}

/** 草稿 → 世界条目：正文空白的直接丢掉（填了关键词没写内容，注入了也只是噪声）。 */
private fun toWorldEntries(world: List<WorldDraft>): List<WorldEntry> =
  world.mapNotNull { draft ->
    val content = draft.content.trim()
    if (content.isEmpty()) {
      null
    } else {
      WorldEntry(keys = splitTokens(draft.keys), content = content, constant = draft.constant)
    }
  }

/** 「a、b, c, d」→ [a, b, c, d]：中文顿号、中英文逗号都认。 */
private fun splitTokens(raw: String): List<String> =
  raw.split('、', ',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun Field(
  label: String,
  value: String,
  singleLine: Boolean = false,
  minLines: Int = 1,
  hint: String? = null,
  onChange: (String) -> Unit,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    supportingText = hint?.let { { Text(it) } },
    singleLine = singleLine,
    minLines = if (singleLine) 1 else minLines,
    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
  )
}

/**
 * 一张空白卡：给「自己写」留一条路。
 *
 * id 用 `local-` 前缀，和导入卡的 id 区分开 —— 导入卡的 id 来自卡文件本身，两者撞了会互相覆盖。
 */
internal fun blankCard(): CharacterCard =
  CharacterCard(id = "local-" + UUID.randomUUID().toString().take(8), name = "", imported = true)
