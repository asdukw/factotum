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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tappet.app.agent.CharacterCard
import com.tappet.app.agent.SecondaryLogic
import com.tappet.app.agent.WorldEntry
import com.tappet.app.agent.WorldEntryDraft
import java.util.UUID

/**
 * 编辑中的一条世界设定。
 *
 * 关键词类字段都存成「一行文本」，保存时才拆（[WorldEntryDraft.parseKeys]）——
 * 用户改到一半时不该被自动重排版打断。
 */
private data class WorldDraft(
  val keys: String,
  val content: String,
  val constant: Boolean,
  val secondary: String = "",
  val exclude: String = "",
  val logic: SecondaryLogic = SecondaryLogic.ANY,
  val depth: Int = 4,
  val probability: Int = 100,
  val sticky: Int = 0,
  val cooldown: Int = 0,
  val position: Int = WorldEntry.POSITION_DEPTH,
  val role: Int = WorldEntry.ROLE_SYSTEM,
  val useProbability: Boolean = true,
  val matchWholeWords: Boolean = false,
  val caseSensitive: Boolean = false,
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
  val world = remember(card.id) { worldDrafts(card) }

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
        EditorFooter(
          enabled = name.isNotBlank(),
          onCancel = onCancel,
          onSave = {
            onSave(
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
              ),
              memoryText,
            )
          },
        )
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
      WorldEntryCard(
        index = index,
        entry = entry,
        onUpdate = { next -> world[index] = next },
        onDelete = { world.removeAt(index) },
      )
    }
  }
  TextButton(onClick = { world.add(WorldDraft(keys = "", content = "", constant = false)) }) {
    Text("添加一条设定")
  }
}

/** 一条设定的编辑卡片：关键词与正文常驻在明面上，编排参数收进「高级」。 */
@Composable
private fun WorldEntryCard(
  index: Int,
  entry: WorldDraft,
  onUpdate: (WorldDraft) -> Unit,
  onDelete: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("第 ${index + 1} 条", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
    Field(
      label = "关键词",
      value = entry.keys,
      singleLine = true,
      hint = if (entry.constant) "常驻条目不需要关键词" else "对话里命中其一才注入；留空则永远不注入",
    ) { value -> onUpdate(entry.copy(keys = value)) }
    Field(label = "内容", value = entry.content, minLines = 3) { value -> onUpdate(entry.copy(content = value)) }
    TextButton(onClick = { onUpdate(entry.copy(constant = !entry.constant)) }, modifier = Modifier.align(Alignment.End)) {
      Text(if (entry.constant) "常驻：每轮都注入" else "常驻：关闭（按关键词）")
    }
    AdvancedFields(entry = entry, onUpdate = onUpdate)
  }
}

/**
 * 编排参数：次要关键词、排除词、深度、概率。
 *
 * 默认折叠 —— 一张卡里绝大多数条目只需要关键词和正文；把这些摆在外面会让「改一句话」
 * 变成一次长滚动。展开过就记住，连着调几条时不用反复点。
 */
@Composable
private fun AdvancedFields(entry: WorldDraft, onUpdate: (WorldDraft) -> Unit) {
  var open by remember { mutableStateOf(false) }
  TextButton(onClick = { open = !open }) {
    Text(if (open) "收起编排参数" else "编排：次要关键词 / 排除词 / 深度 / 概率")
  }
  if (!open) return
  Field(
    label = "次要关键词",
    value = entry.secondary,
    singleLine = true,
    hint = "主词命中只代表话题沾边；填了这里就要同时命中才注入",
  ) { value -> onUpdate(entry.copy(secondary = value)) }
  // 点一下轮换四种语义（ST 的 selectiveLogic）：全中 / 任一 / 不全中 / 全不中。
  // 「非」语义看着像「永远不出现」，但它其实是卡作者控制「别在某种场合提这件事」的手段。
  TextButton(onClick = { onUpdate(entry.copy(logic = entry.logic.next())) }) {
    Text("次要关键词：${entry.logic.label()}")
  }
  Field(
    label = "排除词",
    value = entry.exclude,
    singleLine = true,
    hint = "命中任何一条就整条不注入（优先级最高）",
  ) { value -> onUpdate(entry.copy(exclude = value)) }
  StepperRow(label = "插入深度", value = entry.depth, range = 1..12, suffix = " 条之前") { next ->
    onUpdate(entry.copy(depth = next))
  }
  StepperRow(label = "触发概率", value = entry.probability, range = 0..100, step = 10, suffix = "%") { next ->
    onUpdate(entry.copy(probability = next))
  }
  StepperRow(label = "粘住几轮", value = entry.sticky, range = 0..20, suffix = " 轮") { next ->
    onUpdate(entry.copy(sticky = next))
  }
  StepperRow(label = "冷却几轮", value = entry.cooldown, range = 0..20, suffix = " 轮") { next ->
    onUpdate(entry.copy(cooldown = next))
  }
  ChoiceRow(
    label = "插在哪儿",
    options = listOf(WorldEntry.POSITION_BEFORE to "人设之前", WorldEntry.POSITION_AFTER to "人设之后", WorldEntry.POSITION_DEPTH to "按深度"),
    selected = entry.position,
  ) { next -> onUpdate(entry.copy(position = next)) }
  if (entry.position == WorldEntry.POSITION_DEPTH) {
    ChoiceRow(
      label = "以谁的口吻",
      options = listOf(WorldEntry.ROLE_SYSTEM to "system", WorldEntry.ROLE_USER to "user", WorldEntry.ROLE_ASSISTANT to "assistant"),
      selected = entry.role,
    ) { next -> onUpdate(entry.copy(role = next)) }
  }
  SwitchRow(label = "按概率抽签", checked = entry.useProbability) { onUpdate(entry.copy(useProbability = it)) }
  SwitchRow(label = "关键词整词匹配（只对英文生效）", checked = entry.matchWholeWords) { onUpdate(entry.copy(matchWholeWords = it)) }
  SwitchRow(label = "关键词区分大小写", checked = entry.caseSensitive) { onUpdate(entry.copy(caseSensitive = it)) }
}

/** 三选一：位置 / 角色这种选项少又必须看得见的，用一排按钮而不是下拉菜单。 */
@Composable
private fun ChoiceRow(label: String, options: List<Pair<Int, String>>, selected: Int, onPick: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    options.forEach { (value, text) ->
      TextButton(onClick = { onPick(value) }) {
        Text(text, fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal)
      }
    }
  }
}

/** 开关行：布尔编排参数用它，比「点一下切换」的按钮能看出当前状态。 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** 数字步进行：深度 / 概率这种小范围整数，加减比键盘输入快，也不会输进非法值。 */
@Composable
private fun StepperRow(
  label: String,
  value: Int,
  range: IntRange,
  suffix: String,
  step: Int = 1,
  onChange: (Int) -> Unit,
) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    TextButton(onClick = { onChange((value - step).coerceIn(range.first, range.last)) }) { Text("−") }
    Text("$value$suffix", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { onChange((value + step).coerceIn(range.first, range.last)) }) { Text("＋") }
  }
}

/** 卡里的世界设定 → 可编辑草稿列表（编排参数一并载入，否则一保存就会把用户调过的值冲掉）。 */
private fun worldDrafts(card: CharacterCard): SnapshotStateList<WorldDraft> =
  mutableStateListOf<WorldDraft>().apply {
    card.world.forEach { entry ->
      add(
        WorldDraft(
          keys = entry.keys.joinToString("、"),
          content = entry.content,
          constant = entry.constant,
          secondary = entry.secondary.joinToString("、"),
          exclude = entry.exclude.joinToString("、"),
          logic = entry.secondaryLogic,
          depth = entry.depth,
          probability = entry.probability,
          sticky = entry.sticky,
          cooldown = entry.cooldown,
          position = entry.position,
          role = entry.role,
          useProbability = entry.useProbability,
          matchWholeWords = entry.matchWholeWords,
          caseSensitive = entry.caseSensitive,
        ),
      )
    }
  }

/** 底部的取消/保存。抽出来只为让编辑页本身留在 detekt 的行数阈值内。 */
@Composable
private fun EditorFooter(enabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    TextButton(onClick = onCancel) { Text("取消") }
    Spacer(Modifier.weight(1f))
    Button(onClick = onSave, enabled = enabled) { Text("保存") }
  }
}

/** 「a、b, c, d」→ [a, b, c, d]：标签这类自由文本用，世界书关键词走 [WorldEntryDraft.parseKeys]。 */
private fun splitTokens(raw: String): List<String> =
  raw.split('、', ',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** 草稿 → 世界条目：正文空白的直接丢掉（填了关键词没写内容，注入了也只是噪声）。 */
private fun toWorldEntries(world: List<WorldDraft>): List<WorldEntry> =
  world.mapNotNull { draft ->
    val entry =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(
          primary = draft.keys,
          content = draft.content,
          constant = draft.constant,
          secondary = draft.secondary,
          exclude = draft.exclude,
          depth = draft.depth,
          probability = draft.probability,
          logic = draft.logic,
          sticky = draft.sticky,
          cooldown = draft.cooldown,
          position = draft.position,
          role = draft.role,
          useProbability = draft.useProbability,
          matchWholeWords = draft.matchWholeWords,
          caseSensitive = draft.caseSensitive,
        ),
      )
    entry.takeIf { WorldEntryDraft.isSavable(it) }
  }

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
