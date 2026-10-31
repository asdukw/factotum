package com.zoneu.app.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import com.zoneu.app.agent.CardStorageIdentity
import com.zoneu.app.agent.CharacterCard
import com.zoneu.app.agent.SecondaryLogic
import com.zoneu.app.agent.WorldEntry
import com.zoneu.app.agent.WorldEntryDraft
import java.util.UUID

/**
 * 编辑中的一条世界设定。
 *
 * 关键词类字段都存成「一行文本」，保存时才拆（[WorldEntryDraft.parseKeys]）——
 * 用户改到一半时不该被自动重排版打断。
 */
private data class WorldDraft(
  val original: WorldEntry? = null,
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
  val enabled: Boolean = true,
  val order: Int = 0,
  val preventRecursion: Boolean = false,
  val excludeRecursion: Boolean = false,
  val group: String = "",
  val delay: Int = 0,
  val delayUntilRecursion: Int = 0,
  val ignoreBudget: Boolean = false,
)

/**
 * 角色卡编辑页。
 *
 * 为什么需要它：卡是导入的外部数据，文案好坏只能由用户判断 —— 上一版助手代写的开场白被否过，
 * 「改文案去源文件改、重导一次」在 PC 上还行，在手机上等于做不到。所以这里给一张表单，
 * 让导入的卡能就地改；也给「新建空白卡」用同一条路，从零写一张。
 *
 * 卡内 `system_prompt` / 示例对话默认只归档；用户可以在「卡内提示」里显式打开，
 * 而不是由导入内容自行决定是否运行 —— 编辑页不应该成为绕过那道闸门的后门。
 *
 * 表单同时持有十几个字段的草稿状态；字段控件已拆成子 composable，剩余的状态提升
 * 再拆只会把同一个 state 在父子之间来回搬，因此这一处放宽 LongMethod。
 */
@Suppress("LongMethod")
@Composable
internal fun CharacterEditPage(
  card: CharacterCard,
  memory: String = "",
  onCancel: () -> Unit,
  onSave: (CharacterCard, String) -> CharacterSaveResult,
  onReload: () -> Unit = {},
) {
  var baseline by remember(card.id) { mutableStateOf(card) }
  var conflict by remember(card.id) { mutableStateOf(false) }
  var name by remember(card.id) { mutableStateOf(card.name) }
  var tagline by remember(card.id) { mutableStateOf(card.tagline) }
  var description by remember(card.id) { mutableStateOf(card.description) }
  var personality by remember(card.id) { mutableStateOf(card.personality) }
  var scenario by remember(card.id) { mutableStateOf(card.scenario) }
  var firstMes by remember(card.id) { mutableStateOf(card.firstMes) }
  var nickname by remember(card.id) { mutableStateOf(card.nickname) }
  var greetingsText by remember(card.id) { mutableStateOf(card.alternateGreetings.joinToString("\n\n---\n\n")) }
  var groupGreetingsText by remember(card.id) { mutableStateOf(card.groupOnlyGreetings.joinToString("\n\n---\n\n")) }
  var useCardPrompt by remember(card.id) { mutableStateOf(card.useCardPrompt) }
  var useExampleDialogue by remember(card.id) { mutableStateOf(card.useExampleDialogue) }
  var tags by remember(card.id) { mutableStateOf(card.tags.joinToString("、")) }
  var memoryText by remember(card.id) { mutableStateOf(memory) }
  val world = remember(card.id) { worldDrafts(CardStorageIdentity.draft(card)) }
  var confirmDiscard by remember(card.id) { mutableStateOf(false) }

  fun draftCard(): CharacterCard =
    baseline.copy(
      name = name.trim().ifBlank { "未命名角色" },
      nickname = nickname.trim(),
      tagline = tagline.trim(),
      description = description.trim(),
      personality = personality.trim(),
      scenario = scenario.trim(),
      firstMes = firstMes.trim(),
      alternateGreetings = splitGreetings(greetingsText),
      groupOnlyGreetings = splitGreetings(groupGreetingsText),
      useCardPrompt = useCardPrompt,
      useExampleDialogue = useExampleDialogue,
      tags = if (tags == baseline.tags.joinToString("、")) baseline.tags else splitTokens(tags),
      world = toWorldEntries(world),
      imported = true,
    )

  // 与 baseline 比较得出「有未保存改动」：返回和取消都要先问一句，
  // 这个页面有十几个字段，误触一次丢的是整页编辑。
  val dirty =
    remember(
      baseline,
      name,
      nickname,
      tagline,
      description,
      personality,
      scenario,
      firstMes,
      greetingsText,
      groupGreetingsText,
      useCardPrompt,
      useExampleDialogue,
      tags,
      memoryText,
      world.toList(),
    ) { draftCard() != baseline || memoryText != memory }

  fun save() {
    val result = onSave(draftCard(), memoryText)
    conflict = result is CharacterSaveResult.NotCommitted
    val saved =
      when (result) {
        is CharacterSaveResult.Saved -> result.snapshot.card
        is CharacterSaveResult.MemoryFailed -> result.snapshot.card
        is CharacterSaveResult.NotCommitted -> null
      }
    if (saved != null) {
      baseline = saved
      rebaseWorldDrafts(world, saved)
    }
  }

  fun requestExit() {
    if (dirty) confirmDiscard = true else onCancel()
  }

  // 系统返回也要拦：NavDisplay 的返回不经过上面两个按钮，直接弹栈会把草稿一起带走。
  BackHandler(enabled = dirty) { confirmDiscard = true }

  Surface(Modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "编辑角色", onBack = ::requestExit) {
        // 长表单的保存原本只在页脚：改完顶部的字段要一路滚到底才能存，这里再给一个入口。
        TextButton(onClick = ::save, enabled = dirty && name.isNotBlank()) { Text("保存") }
      }
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
      ) {
        Field("名字", name, singleLine = true) { name = it }
        Field("昵称（可选，{{char}} 会用它）", nickname, singleLine = true) { nickname = it }
        if (conflict) ConflictReload(onReload)
        Field("一句话介绍", tagline, singleLine = true) { tagline = it }
        Field("标签", tags, singleLine = true, hint = "用、或逗号分隔") { tags = it }
        Field("人设（身份与外貌）", description, minLines = 4) { description = it }
        Field("性格与说话方式", personality, minLines = 3) { personality = it }
        Field("开场场景", scenario, minLines = 2) { scenario = it }
        GreetingFields(
          firstMes = firstMes,
          alternate = greetingsText,
          group = groupGreetingsText,
          onFirst = { firstMes = it },
          onAlternate = { greetingsText = it },
          onGroup = { groupGreetingsText = it },
        )

        CardPromptSection(
          useCardPrompt = useCardPrompt,
          useExampleDialogue = useExampleDialogue,
          onPrompt = { useCardPrompt = it },
          onExamples = { useExampleDialogue = it },
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
          onCancel = ::requestExit,
          onSave = ::save,
        )
      }
    }
  }

  if (confirmDiscard) {
    AlertDialog(
      onDismissRequest = { confirmDiscard = false },
      title = { Text("放弃未保存的更改？") },
      text = { Text("名字、人设、开场白、世界设定和角色记忆的改动都会丢失。") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmDiscard = false
            onCancel()
          },
        ) {
          Text("放弃", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
  }
}

/** Reload is an explicit user decision, never a silent revision rebase of the dirty draft. */
@Composable
private fun ConflictReload(onReload: () -> Unit) {
  var confirming by remember { mutableStateOf(false) }
  TextButton(onClick = { confirming = true }) { Text("重新读取当前卡") }
  if (confirming) {
    AlertDialog(
      onDismissRequest = { confirming = false },
      title = { Text("放弃草稿并重新读取？") },
      text = { Text("当前文本与记忆草稿仍保留。重新读取将放弃这份未保存草稿，读取最新卡片；不会自动覆盖其他修改。") },
      confirmButton = {
        TextButton(onClick = {
          confirming = false
          onReload()
        }) { Text("放弃草稿并读取") }
      },
      dismissButton = { TextButton(onClick = { confirming = false }) { Text("保留草稿") } },
    )
  }
}

/** Keep text drafts and only rebase identities of entries that were actually saved. */
private fun rebaseWorldDrafts(world: SnapshotStateList<WorldDraft>, saved: CharacterCard) {
  val savedDraft = CardStorageIdentity.draft(saved)
  var slot = 0
  world.indices.forEach { index ->
    val draft = world[index]
    if (toWorldEntries(listOf(draft)).isNotEmpty()) {
      world[index] = draft.copy(original = savedDraft.world[slot++])
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
        onMoveUp = { if (index > 0) world.add(index - 1, world.removeAt(index)) },
        onMoveDown = { if (index < world.lastIndex) world.add(index + 1, world.removeAt(index)) },
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
  onMoveUp: () -> Unit = {},
  onMoveDown: () -> Unit = {},
) {
  Column(Modifier.fillMaxWidth().padding(14.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("第 ${index + 1} 条", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = onMoveUp) { Text("上移") }
      TextButton(onClick = onMoveDown) { Text("下移") }
      TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
    }
    SwitchRow("启用这条", entry.enabled) { onUpdate(entry.copy(enabled = it)) }
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
  RecursionFields(entry, onUpdate)
}

/** 互斥组、顺序、延迟与递归护栏：ST 卡作者最常用的几个旋钮。 */
@Composable
private fun RecursionFields(entry: WorldDraft, onUpdate: (WorldDraft) -> Unit) {
  Field(
    label = "互斥组（逗号分隔）",
    value = entry.group,
    singleLine = true,
    hint = "同组条目一轮只激活一个；留空表示不分组",
  ) { value -> onUpdate(entry.copy(group = value)) }
  Field(
    label = "插入顺序",
    value = entry.order.toString(),
    singleLine = true,
    hint = "同一插入位置内，数字越小越靠前",
  ) { value -> value.toIntOrNull()?.let { onUpdate(entry.copy(order = it)) } }
  StepperRow(label = "开场后延迟", value = entry.delay, range = 0..20, suffix = " 轮") { next ->
    onUpdate(entry.copy(delay = next))
  }
  StepperRow(label = "递归门控层", value = entry.delayUntilRecursion, range = 0..3, suffix = " 层") { next ->
    onUpdate(entry.copy(delayUntilRecursion = next))
  }
  SwitchRow(label = "正文不触发递归", checked = entry.preventRecursion) { onUpdate(entry.copy(preventRecursion = it)) }
  SwitchRow(label = "不被递归触发", checked = entry.excludeRecursion) { onUpdate(entry.copy(excludeRecursion = it)) }
  SwitchRow(label = "超出预算也插入", checked = entry.ignoreBudget) { onUpdate(entry.copy(ignoreBudget = it)) }
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

/** 开场白三件套：首句、备用、群聊专用。多条之间用单独一行 `---` 分隔。 */
@Composable
private fun GreetingFields(
  firstMes: String,
  alternate: String,
  group: String,
  onFirst: (String) -> Unit,
  onAlternate: (String) -> Unit,
  onGroup: (String) -> Unit,
) {
  Field("开场白（第一句话由它开始）", firstMes, minLines = 4, onChange = onFirst)
  Field(
    "备用开场白",
    alternate,
    minLines = 2,
    hint = "多条之间用单独一行 --- 分隔；新会话里可以换一条开场。",
    onChange = onAlternate,
  )
  Field(
    "群聊专用开场白",
    group,
    minLines = 2,
    hint = "只在多人会话里使用；同样用 --- 分隔。",
    onChange = onGroup,
  )
  Text(
    text = "「{{char}}」会被换成角色名，「{{user}}」换成「你」—— 与 SillyTavern 的写法一致。",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
  )
}

/** 卡内提示开关：默认关闭，打开是用户对这张外部卡的信任决定。 */
@Composable
private fun CardPromptSection(
  useCardPrompt: Boolean,
  useExampleDialogue: Boolean,
  onPrompt: (Boolean) -> Unit,
  onExamples: (Boolean) -> Unit,
) {
  SectionLabel("卡内提示（默认关闭）")
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      SwitchRow("使用卡内 system_prompt / 后置指令", useCardPrompt, onPrompt)
      Text(
        text = "打开后会用卡作者写的系统提示替换应用模板；卡里的 {{original}} 代表应用原来的模板。" +
          "外部卡是不可信内容，只在你信任这张卡时打开。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      SwitchRow("使用卡内示例对话 mes_example", useExampleDialogue, onExamples)
      Text(
        text = "示例对话能稳定角色口癖和回合格式，但会占上下文，也可能让模型过度模仿。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
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
          original = entry,
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
          enabled = entry.enabled,
          order = entry.order,
          preventRecursion = entry.preventRecursion,
          excludeRecursion = entry.excludeRecursion,
          group = entry.group,
          delay = entry.delay,
          delayUntilRecursion = entry.delayUntilRecursion,
          ignoreBudget = entry.ignoreBudget,
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

/** 多条开场白用单独一行 `---` 分隔；正文里的破折号不受影响。 */
private fun splitGreetings(raw: String): List<String> =
  raw.split(Regex("\\n\\s*-{3,}\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

/** 草稿 → 世界条目：正文空白的直接丢掉（填了关键词没写内容，注入了也只是噪声）。 */
private fun toWorldEntries(world: List<WorldDraft>): List<WorldEntry> =
  world.mapNotNull { draft ->
    val entry =
      WorldEntryDraft.build(
        WorldEntryDraft.Draft(
          original = draft.original,
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
          enabled = draft.enabled,
          order = draft.order,
          preventRecursion = draft.preventRecursion,
          excludeRecursion = draft.excludeRecursion,
          group = draft.group,
          delay = draft.delay,
          delayUntilRecursion = draft.delayUntilRecursion,
          ignoreBudget = draft.ignoreBudget,
        ),
      )
    entry.takeIf { draft.original != null || WorldEntryDraft.isSavable(it) }
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
