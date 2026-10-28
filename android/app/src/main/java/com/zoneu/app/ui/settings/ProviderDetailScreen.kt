package com.zoneu.app.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zoneu.app.agent.LlmPreset
import com.zoneu.app.agent.ModelModalities

/**
 * 提供商详情（参考图的第三屏）：配置 / 模型 / 高级设置三个页签。
 *
 * 与参考图的两处刻意差异，都是因为运行时能力对不上：
 * - 没有「API 路径」编辑：pi-ai 按调用风格自己拼 `/chat/completions`、`/responses` 或
 *   `/messages`，宿主的 env 只传 baseURL。给一个存了却不生效的字段比不给更糟。
 * - 没有「获取账户余额」：没有实现余额查询，就不摆这一行。
 *
 * 「Response API」开关是真的：它改的是写进 dsh profile 的调用风格（openai-responses ↔
 * openai-completions），只对两种协议都支持的 OpenAI / 自定义端点显示。
 */
@Composable
fun ProviderDetailScreen(
  providerId: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  // Nav3 1.0.1 的 ViewModelStore 装饰器要求配套的 SavedState 装饰器（这个版本没有），
  // 装了会在启动时直接崩。这里退回到 Activity 作用域的 VM，用 providerId 做 key ——
  // 详情页本来就按提供商区分状态，key 保证返回列表再进另一家时不会串。
  val viewModel: ProviderDetailViewModel = viewModel(key = "provider-detail-$providerId") {
    ProviderDetailViewModel(appContext, providerId)
  }
  val state by viewModel.state.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) { viewModel.reload() }

  ProviderDetailScreen(
    state = state,
    onBack = onBack,
    onTabSelect = viewModel::onTabSelect,
    onNameChange = viewModel::onNameChange,
    onBaseUrlChange = viewModel::onBaseUrlChange,
    onApiKeyChange = viewModel::onApiKeyChange,
    onToggleReveal = viewModel::toggleKeyReveal,
    onSaveKey = viewModel::saveKey,
    onEnabledChange = viewModel::onEnabledChange,
    onResponseApiChange = viewModel::onResponseApiChange,
    onModelDraftChange = viewModel::onModelDraftChange,
    onModelSelect = viewModel::onModelSelect,
    onToggleModel = viewModel::toggleSelectedModel,
    onUseModelDraft = viewModel::useModelDraft,
    onFetchModels = viewModel::fetchModels,
    onClearKey = viewModel::clearKey,
    onReset = viewModel::resetProvider,
    modifier = modifier,
  )
}

@Composable
internal fun ProviderDetailScreen(
  state: ProviderDetailViewModel.UiState,
  onBack: () -> Unit,
  onTabSelect: (DetailTab) -> Unit,
  onNameChange: (String) -> Unit,
  onBaseUrlChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSaveKey: () -> Unit,
  onEnabledChange: (Boolean) -> Unit,
  onResponseApiChange: (Boolean) -> Unit,
  onModelDraftChange: (String) -> Unit,
  onModelSelect: (String) -> Unit,
  onToggleModel: (String) -> Unit,
  onUseModelDraft: () -> Unit,
  onFetchModels: () -> Unit,
  onClearKey: () -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(modifier = modifier.fillMaxSize(), color = settingsPageColor()) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
      ProviderDetailHeader(state = state, onBack = onBack)
      Column(
        modifier =
          Modifier.weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
      ) {
        InfoBanner("改动会在保存后应用到运行时；运行中任务完成后再保存，模型上下文会重置但聊天记录保留")
        Spacer(Modifier.height(12.dp))
        if (state.error != null) {
          Text(
            text = state.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
          )
        }
        when (state.tab) {
          DetailTab.Config ->
            ProviderConfigTab(
              state = state,
              onNameChange = onNameChange,
              onBaseUrlChange = onBaseUrlChange,
              onApiKeyChange = onApiKeyChange,
              onToggleReveal = onToggleReveal,
              onSaveKey = onSaveKey,
              onEnabledChange = onEnabledChange,
              onResponseApiChange = onResponseApiChange,
            )

          DetailTab.Models ->
            ProviderModelsTab(
              state = state,
              onModelSelect = onModelSelect,
              onToggleModel = onToggleModel,
              onModelDraftChange = onModelDraftChange,
              onUseModelDraft = onUseModelDraft,
              onFetchModels = onFetchModels,
            )

          DetailTab.Advanced ->
            ProviderAdvancedTab(state = state, onClearKey = onClearKey, onReset = onReset)
        }
      }
      DetailTabBar(selected = state.tab, onSelect = onTabSelect)
    }
  }
}

@Composable
private fun ProviderDetailHeader(state: ProviderDetailViewModel.UiState, onBack: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BackCircle(onBack)
    Spacer(Modifier.width(12.dp))
    ProviderAvatar(preset = state.preset, name = state.name, size = 34)
    Spacer(Modifier.width(10.dp))
    Column(Modifier.weight(1f)) {
      Text(
        text = state.name,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (state.enabled) "已启用" else "未启用",
        style = MaterialTheme.typography.labelSmall,
        color =
          if (state.enabled) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
      )
    }
  }
}

@Composable
private fun ProviderConfigTab(
  state: ProviderDetailViewModel.UiState,
  onNameChange: (String) -> Unit,
  onBaseUrlChange: (String) -> Unit,
  onApiKeyChange: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSaveKey: () -> Unit,
  onEnabledChange: (Boolean) -> Unit,
  onResponseApiChange: (Boolean) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    OutlinedTextField(
      value = state.name,
      onValueChange = onNameChange,
      label = { Text("名称") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    )
    ApiKeyField(
      state = state,
      onApiKeyChange = onApiKeyChange,
      onToggleReveal = onToggleReveal,
      onSaveKey = onSaveKey,
    )
    OutlinedTextField(
      value = state.baseUrl,
      onValueChange = onBaseUrlChange,
      label = { Text("API Base Url") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    ToggleRow(label = "是否启用", checked = state.enabled, onCheckedChange = onEnabledChange)
    if (state.preset.responseApiToggle) {
      ToggleRow(
        label = "Response API",
        checked = state.responseApi,
        onCheckedChange = onResponseApiChange,
      )
      Text(
        text = "开启走 /responses，关闭走 /chat/completions。端点不支持 Responses 时请关闭。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    if (state.enabled && !state.hasKey) {
      Text(
        text = "已启用但没有 API Key：当前运行时不会发送请求。请先保存密钥。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 4.dp),
      )
    }
    Text(
      text =
        when {
          state.verifiedNow -> "已验证：目录端点 + 默认模型在真机跑通过。"
          state.preset.verified -> "当前端点 / 模型组合未在真机验证（目录默认组合已验证）。"
          else -> "这条路径的端点与模型 id 对得上运行时目录，但还没有用真 key 在真机上跑通过。"
        },
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}

@Composable
private fun ApiKeyField(
  state: ProviderDetailViewModel.UiState,
  onApiKeyChange: (String) -> Unit,
  onToggleReveal: () -> Unit,
  onSaveKey: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
      value = state.keyInput,
      onValueChange = onApiKeyChange,
      label = { Text(if (state.hasKey) "API Key（已保存，留空不修改）" else "API Key") },
      singleLine = true,
      shape = RoundedCornerShape(12.dp),
      // Password 类型让输入法不要学习 / 联想 / 首字母大写这一段；只遮住显示是不够的。
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { onSaveKey() }),
      visualTransformation =
        if (state.revealKey) VisualTransformation.None else PasswordVisualTransformation(),
      trailingIcon = {
        Box(
          modifier =
            Modifier.size(44.dp)
              .clip(CircleShape)
              .clickable(onClick = onToggleReveal)
              .semantics { contentDescription = if (state.revealKey) "隐藏 API Key" else "显示 API Key" },
          contentAlignment = Alignment.Center,
        ) {
          Glyph(
            kind = if (state.revealKey) GlyphKind.EyeOff else GlyphKind.Eye,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      modifier = Modifier.fillMaxWidth(),
    )
    if (state.keyInput.isNotBlank()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onSaveKey) { Text("保存密钥") }
      }
    }
    when {
      state.runtimeApplying ->
        Text(
          text = "密钥已保存，正在应用到运行时…",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )

      state.runtimeApplied ->
        Text(
          text = "密钥已保存并生效，无需重启 App。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )

      state.keySaved ->
        Text(
          text = "已保存；启用此提供商或启动运行时时使用。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )

      state.hasKey ->
        Text(
          text = "已保存到本机（Keystore 封装）；已存的密钥不会回填到这里。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
  }
}

@Composable
private fun ProviderModelsTab(
  state: ProviderDetailViewModel.UiState,
  onModelSelect: (String) -> Unit,
  onToggleModel: (String) -> Unit,
  onModelDraftChange: (String) -> Unit,
  onUseModelDraft: () -> Unit,
  onFetchModels: () -> Unit,
) {
  var pickerOpen by remember { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "点选可用模型会把它设为当前模型；已保存 API Key 时同时启用该提供商。",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp),
    )
    CurrentModelCard(state)
    AvailableModelsSection(
      state = state,
      onModelSelect = onModelSelect,
      onToggleModel = onToggleModel,
    )
    RemoteModelsSection(
      state = state,
      onFetchModels = onFetchModels,
      onOpenPicker = { pickerOpen = true },
    )
    ModelDraftField(
      state = state,
      onModelDraftChange = onModelDraftChange,
      onUseModelDraft = onUseModelDraft,
    )
    Text(
      text = "可用模型来自端点列表或手填；pi-ai 目录里没有的 id 会按纯文本声明。",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 4.dp),
    )
  }

  if (pickerOpen) {
    FetchedModelsDialog(
      models = state.remoteModels,
      selectedModels = state.selectedModels,
      onToggle = onToggleModel,
      onDismiss = { pickerOpen = false },
    )
  }
}

/** 用户从端点列表 / 手填里选出来的模型；点行设当前，右边的 × 移出列表。 */
@Composable
private fun AvailableModelsSection(
  state: ProviderDetailViewModel.UiState,
  onModelSelect: (String) -> Unit,
  onToggleModel: (String) -> Unit,
) {
  SettingsGroup {
    if (state.selectedModels.isEmpty()) {
      Text(
        text = "还没有可用模型：从端点添加，或在下面手填 id。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      )
    } else {
      state.selectedModels.forEachIndexed { index, id ->
        if (index > 0) SettingsDivider()
        AvailableModelRow(
          id = id,
          current = id == state.model,
          onSelect = { onModelSelect(id) },
          onRemove = { onToggleModel(id) },
        )
      }
    }
  }
}

@Composable
private fun AvailableModelRow(
  id: String,
  current: Boolean,
  onSelect: () -> Unit,
  onRemove: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onSelect)
        .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = id,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (current) {
      StatusBadge(text = "当前", tone = BadgeTone.Info)
      Spacer(Modifier.width(4.dp))
    }
    Box(
      modifier =
        Modifier.size(44.dp)
          .clip(CircleShape)
          .clickable(onClick = onRemove)
          .semantics { contentDescription = "移除模型 $id" },
      contentAlignment = Alignment.Center,
    ) {
      Glyph(
        kind = GlyphKind.Plus,
        modifier = Modifier.size(18.dp).rotate(45f),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CurrentModelCard(state: ProviderDetailViewModel.UiState) {
  if (state.model.isBlank()) return
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          text = "当前模型",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = state.model, style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

/**
 * 端点模型列表这一段：拉取 / 刷新 + 浏览入口。错误就地显示 —— 用户正看着这个页签，
 * 不需要再弹一层。
 */
@Composable
private fun RemoteModelsSection(
  state: ProviderDetailViewModel.UiState,
  onFetchModels: () -> Unit,
  onOpenPicker: () -> Unit,
) {
  SettingsGroup {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            text = "端点模型列表",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text =
              if (state.remoteModels.isEmpty()) {
                "GET ${state.baseUrl}/models —— 拉一份端点自己认的模型 id"
              } else {
                "已拉取 ${state.remoteModels.size} 个模型"
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.width(8.dp))
        if (state.fetchingModels) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
          TextButton(onClick = onFetchModels) {
            Text(if (state.remoteModels.isEmpty()) "获取" else "刷新")
          }
        }
      }
      if (state.fetchError != null) {
        Spacer(Modifier.height(6.dp))
        Text(
          text = state.fetchError,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (state.remoteModels.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) {
          Text("浏览端点返回的模型")
        }
      }
    }
  }
}

@Composable
private fun ModelDraftField(
  state: ProviderDetailViewModel.UiState,
  onModelDraftChange: (String) -> Unit,
  onUseModelDraft: () -> Unit,
) {
  OutlinedTextField(
    value = state.modelDraft,
    onValueChange = onModelDraftChange,
    label = { Text("其它模型 id") },
    singleLine = true,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  )
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Button(onClick = onUseModelDraft, enabled = state.modelDraft.isNotBlank()) { Text("使用") }
  }
}

/** 端点返回的清单可能几百条（OpenRouter），所以带筛选 + 懒加载；勾选即加入可用模型。 */
@Composable
private fun FetchedModelsDialog(
  models: List<String>,
  selectedModels: List<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val visible = remember(models, query) {
    val needle = query.trim()
    if (needle.isEmpty()) models else models.filter { it.contains(needle, ignoreCase = true) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("添加模型（${models.size}）") },
    text = {
      Column {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          placeholder = { Text("筛选") },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (visible.isEmpty()) {
          Text(
            text = "没有匹配的模型",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(visible) { id ->
              FetchedModelRow(
                id = id,
                checked = id in selectedModels,
                onToggle = { onToggle(id) },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("完成") }
    },
  )
}

@Composable
private fun FetchedModelRow(id: String, checked: Boolean, onToggle: () -> Unit) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onToggle)
        .padding(horizontal = 8.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = id,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(12.dp))
    if (checked) {
      Glyph(
        kind = GlyphKind.Check,
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    } else {
      Box(Modifier.size(20.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
  }
}

@Composable
private fun ProviderAdvancedTab(
  state: ProviderDetailViewModel.UiState,
  onClearKey: () -> Unit,
  onReset: () -> Unit,
) {
  var confirm by remember { mutableStateOf<ConfirmAction?>(null) }

  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SettingsGroup {
      InfoRow("调用风格", state.api)
      SettingsDivider()
      InfoRow("推理格式", state.preset.thinkingFormat ?: "由协议自带")
      SettingsDivider()
      InfoRow("真机验证", if (state.verifiedNow) "已验证" else "未验证")
      SettingsDivider()
      InfoRow(
        "当前模型来源",
        when {
          state.model.isBlank() -> "未设置"
          ModelModalities.isKnown(LocalContext.current, state.preset.id, state.model) -> "pi-ai 目录"
          else -> "手填"
        },
      )
    }
    if (state.hasKey) {
      OutlinedButton(
        onClick = { confirm = ConfirmAction.ClearKey },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("清除已保存的 API Key")
      }
    }
    OutlinedButton(
      onClick = { confirm = ConfirmAction.Reset },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("恢复默认设置")
    }
    Text(
      text = "重置会清掉名称 / 端点 / 模型覆盖与密钥；如果重置后端点或模型为空（自定义提供商），会一并停用。",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 4.dp),
    )
    InfoBanner("密钥用 Android Keystore 封装；卸载或换机后密文解不开，需要重新填一次。")
  }

  val action = confirm
  if (action != null) {
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text(if (action == ConfirmAction.Reset) "恢复默认设置？" else "清除 API Key？") },
      text = {
        Text(
          if (action == ConfirmAction.Reset) {
            "会清掉名称、API Base Url、模型与已保存的密钥，端点回到运行时目录里的默认值。"
          } else {
            "已存的密文会一并删除；如果这是当前提供商，运行时会立即重载并停用旧密钥。"
          },
          style = MaterialTheme.typography.bodySmall,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirm = null
            if (action == ConfirmAction.Reset) onReset() else onClearKey()
          },
        ) {
          Text("确定", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirm = null }) { Text("取消") }
      },
    )
  }
}

private enum class ConfirmAction {
  ClearKey,
  Reset,
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun InfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
