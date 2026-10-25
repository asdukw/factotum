package com.tappet.app.ui.settings

import android.widget.Toast
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 助手（人格）设置页。
 *
 * 三块，按用户会问的顺序排：**换成谁**（人格）→ **自己想写什么**（自定义）→ **怎么写**
 * （语气契约）。最后一栏是**当前真正发给模型的整份提示词** —— 人格类设置最容易变成「看不见
 * 效果」的黑箱，把成品直接摆出来，用户改完能自己对一遍。
 *
 * 「切换即生效」的实现是 [com.tappet.app.node.NodeRuntime.reload]，代价是当前对话开新上下文
 * （与切换模型同一条路）。这条代价写在页面上，不藏。
 */
@Composable
fun PersonaScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val viewModel: PersonaViewModel = viewModel { PersonaViewModel(appContext) }
  val state by viewModel.state.collectAsStateWithLifecycle()

  PersonaScreen(
    state = state,
    onBack = onBack,
    onSelect = viewModel::select,
    onSaveCustom = viewModel::saveCustom,
    onToneChange = viewModel::setToneContract,
    modifier = modifier,
  )
}

/**
 * 无状态版本（预览 / 测试用）。每一块拆成独立 composable：这一页会长，而 detekt 的
 * `LongMethod` 提醒的是「换个人来做，能不能只读一段就改对一块」。
 */
@Composable
internal fun PersonaScreen(
  state: PersonaViewModel.UiState,
  onBack: () -> Unit,
  onSelect: (String) -> Unit,
  onSaveCustom: (String) -> Unit,
  onToneChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
      SettingsHeader(title = "助手", onBack = onBack)
      Column(
        modifier =
          Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
      ) {
        SectionLabel("人格")
        SoulList(state = state, onSelect = onSelect)
        Spacer(Modifier.height(8.dp))
        StatusLine(state = state)

        SectionLabel("自定义")
        CustomEditor(saved = state.customText, onSave = onSaveCustom)

        SectionLabel("写法")
        SettingsGroup {
          SettingsRow(
            glyph = GlyphKind.Sliders,
            title = "语气契约",
            subtitle = "短句、不用书面语、不堆列表；追加在提示词最末尾",
            onClick = { onToneChange(!state.toneEnabled) },
            trailing = { Switch(checked = state.toneEnabled, onCheckedChange = onToneChange) },
          )
        }

        SectionLabel("当前生效的提示词")
        PromptPreview(state.preview)
      }
    }
  }
}

/** 人格列表：整行可点，右侧单选标记。 */
@Composable
private fun SoulList(state: PersonaViewModel.UiState, onSelect: (String) -> Unit) {
  SettingsGroup {
    state.souls.forEachIndexed { index, soul ->
      if (index > 0) SettingsDivider()
      SettingsRow(
        glyph = GlyphKind.Person,
        title = soul.name,
        subtitle = soul.summary.ifEmpty { soul.id },
        onClick = { onSelect(soul.id) },
        // 整行已经可点，单选按钮自己不再单独响应 —— 否则会出现「点文字没反应」的边缘感。
        trailing = { RadioButton(selected = soul.id == state.selectedId, onClick = null) },
      )
    }
  }
}

/** 切换结果 / 正在重载 / 默认说明，三者互斥。 */
@Composable
private fun StatusLine(state: PersonaViewModel.UiState) {
  val text =
    when {
      state.applying -> "正在重载运行时…"
      else -> state.message ?: "切换后立即生效；重载会换一个会话命名空间，当前对话从新上下文继续。"
    }
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 4.dp),
  )
}

/**
 * 自定义正文编辑器。
 *
 * 草稿与已保存值分开：边写边重载运行时毫无意义，只有点「保存」才落盘并生效。
 */
@Composable
private fun CustomEditor(saved: String, onSave: (String) -> Unit) {
  var draft by remember(saved) { mutableStateOf(saved) }
  OutlinedTextField(
    value = draft,
    onValueChange = { draft = it },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    minLines = 6,
    maxLines = 14,
    label = { Text("你自己的 soul.md") },
    placeholder = {
      Text("可以只写身份与语气，操作规则会自动追加在后面。头部支持 --- / name: / summary: ---；正文里不要写 {{...}}，那会被 dsh 当成提示词变量")
    },
    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
  )
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "保存后自动切到「自定义」",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    TextButton(onClick = { draft = "" }) { Text("清空") }
    Spacer(Modifier.width(4.dp))
    TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank() && draft != saved) {
      Text("保存")
    }
  }
}

/**
 * 整份系统提示的预览：等宽小字、可复制。
 *
 * 不做折叠：人格总共就一两千字，藏起来反而让人怀疑「到底写进去了没有」。
 */
@Composable
private fun PromptPreview(prompt: String) {
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  SettingsGroup {
    Column(Modifier.padding(16.dp)) {
      Text(
        text = prompt,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(8.dp))
      TextButton(
        onClick = {
          clipboard.setText(AnnotatedString(prompt))
          Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        },
      ) {
        Text("复制")
      }
    }
  }
}
