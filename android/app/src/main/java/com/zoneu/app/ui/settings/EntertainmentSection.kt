package com.zoneu.app.ui.settings

import androidx.compose.runtime.Composable

@Composable
internal fun EntertainmentSection(onOpenCharacters: () -> Unit, onOpenTavernPresets: () -> Unit) {
  SectionLabel("娱乐")
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "角色卡",
      subtitle = "仅用于酒馆会话，日常助手不受影响",
      onClick = onOpenCharacters,
    ) { ChevronRight() }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "酒馆预设",
      subtitle = "系统模板与后置指令",
      onClick = onOpenTavernPresets,
    ) { ChevronRight() }
  }
}
