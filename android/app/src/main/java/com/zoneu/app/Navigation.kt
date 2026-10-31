package com.zoneu.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.zoneu.app.ui.chat.ChatScreen
import com.zoneu.app.ui.chat.LocalWorldNavigation
import com.zoneu.app.ui.chat.WorldNavigation
import com.zoneu.app.ui.settings.AboutScreen
import com.zoneu.app.ui.settings.CharacterDetailScreen
import com.zoneu.app.ui.settings.CharacterScreen
import com.zoneu.app.ui.settings.ConnectorsScreen
import com.zoneu.app.ui.settings.ExecutionSettingsScreen
import com.zoneu.app.ui.settings.GmailConnectorScreen
import com.zoneu.app.ui.settings.ModelServicesScreen
import com.zoneu.app.ui.settings.PersonaScreen
import com.zoneu.app.ui.settings.ProviderDetailScreen
import com.zoneu.app.ui.settings.ProvidersScreen
import com.zoneu.app.ui.settings.ScheduledTaskScreen
import com.zoneu.app.ui.settings.SettingsScreen
import com.zoneu.app.ui.settings.TavernPresetScreen
import com.zoneu.app.ui.settings.VoiceScreen
import com.zoneu.app.ui.settings.WorldBindingScreen
import com.zoneu.app.ui.settings.WorldBookScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  CompositionLocalProvider(
    LocalWorldNavigation provides WorldNavigation(
      openLibrary = { backStack.add(WorldBooks()) },
      openBook = { id -> backStack.add(WorldBooks(id)) },
      openBindings = { scope, id -> backStack.add(WorldBindings(scope, id)) },
    ),
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<Main> {
            ChatScreen(
              onOpenSettings = { backStack.add(Settings) },
              // 酒馆侧栏的入口：直接进角色库，不必绕设置（人格页暂时只留在代码里）。
              onOpenCharacters = { backStack.add(Characters) },
              onOpenCharacterDetail = { id -> backStack.add(CharacterDetail(id)) },
            )
          }
          entry<Settings> {
            SettingsScreen(
              onBack = { backStack.removeLastOrNull() },
              onOpenModelServices = { backStack.add(ModelServices) },
              onOpenExecution = { backStack.add(ExecutionSettings) },
              onOpenConnectors = { backStack.add(Connectors) },
              onOpenVoice = { backStack.add(Voice) },
              onOpenScheduledTasks = { backStack.add(ScheduledTasks) },
              onOpenCharacters = { backStack.add(Characters) },
              onOpenTavernPresets = { backStack.add(TavernPresets) },
              onOpenAbout = { backStack.add(About) },
            )
          }
          entry<ModelServices> {
            ModelServicesScreen(
              onBack = { backStack.removeLastOrNull() },
              onOpenProviders = { backStack.add(Providers) },
            )
          }
          entry<ScheduledTasks> { ScheduledTaskScreen(onBack = { backStack.removeLastOrNull() }) }
          entry<ExecutionSettings> {
            ExecutionSettingsScreen(onBack = { backStack.removeLastOrNull() })
          }
          entry<About> { AboutScreen(onBack = { backStack.removeLastOrNull() }) }
          entry<TavernPresets> {
            TavernPresetScreen(onBack = { backStack.removeLastOrNull() })
          }
          entry<Providers> {
            ProvidersScreen(
              onBack = { backStack.removeLastOrNull() },
              onOpenProvider = { id -> backStack.add(ProviderDetail(id)) },
            )
          }
          entry<Connectors> {
            ConnectorsScreen(
              onBack = { backStack.removeLastOrNull() },
              onOpenGmail = { backStack.add(GmailConnector) },
            )
          }
          entry<GmailConnector> {
            GmailConnectorScreen(onBack = { backStack.removeLastOrNull() })
          }
          entry<ProviderDetail> { key ->
            ProviderDetailScreen(
              providerId = key.providerId,
              onBack = { backStack.removeLastOrNull() },
            )
          }
          entry<Voice> {
            VoiceScreen(onBack = { backStack.removeLastOrNull() })
          }
          worldEntries(backStack)
          tavernEntries(backStack)
        },
    )
  }
}

private fun EntryProviderScope<NavKey>.tavernEntries(backStack: MutableList<NavKey>) {
  entry<Persona> {
    PersonaScreen(onBack = { backStack.removeLastOrNull() })
  }
  entry<Characters> {
    // 开始对话这件事交给详情页：点一行先看卡在演什么，再决定要不要开。
    CharacterScreen(
      onBack = { backStack.removeLastOrNull() },
      onOpenDetail = { id -> backStack.add(CharacterDetail(id)) },
    )
  }
  entry<CharacterDetail> { key ->
    CharacterDetailScreen(
      characterId = key.characterId,
      onBack = { backStack.removeLastOrNull() },
      onStartChat = { repeat(backStack.size - 1) { backStack.removeLastOrNull() } },
    )
  }
}

private fun EntryProviderScope<NavKey>.worldEntries(backStack: MutableList<NavKey>) {
  entry<WorldBooks> { key ->
    WorldBookScreen(onBack = { backStack.removeLastOrNull() }, initialBookId = key.bookId)
  }
  entry<WorldBindings> { key ->
    WorldBindingScreen(key.scope, key.targetId, onBack = { backStack.removeLastOrNull() })
  }
}
