package com.zoneu.app.ui.settings

import androidx.compose.runtime.Composable
import com.zoneu.app.ui.chat.LocalWorldNavigation

@Composable
internal fun EntertainmentSection(onOpenCharacters: () -> Unit, onOpenTavernPresets: () -> Unit) {
  val worlds = LocalWorldNavigation.current
  SectionLabel("娱乐")
  SettingsGroup {
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "角色卡",
      subtitle = "仅用于酒馆会话，日常助手不受影响",
      onClick = onOpenCharacters,
    ) { ChevronRight() }
    SettingsDivider()
    SettingsRow(GlyphKind.Sparkles, "世界书", "独立书库、共享引用与固定修订", onClick = worlds.openLibrary) { ChevronRight() }
    SettingsDivider()
    SettingsRow(
      glyph = GlyphKind.Sparkles,
      title = "酒馆预设",
      subtitle = "系统模板与后置指令",
      onClick = onOpenTavernPresets,
    ) { ChevronRight() }
  }
}
