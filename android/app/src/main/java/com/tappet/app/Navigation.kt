package com.tappet.app

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.tappet.app.ui.chat.ChatScreen
import com.tappet.app.ui.settings.CharacterDetailScreen
import com.tappet.app.ui.settings.CharacterScreen
import com.tappet.app.ui.settings.ConnectorsScreen
import com.tappet.app.ui.settings.GmailConnectorScreen
import com.tappet.app.ui.settings.PersonaScreen
import com.tappet.app.ui.settings.ProviderDetailScreen
import com.tappet.app.ui.settings.ProvidersScreen
import com.tappet.app.ui.settings.ScheduledTasksScreen
import com.tappet.app.ui.settings.SettingsScreen
import com.tappet.app.ui.settings.TermuxBridgeScreen
import com.tappet.app.ui.settings.VoiceScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ChatScreen(
            onOpenSettings = { backStack.add(Settings) },
            // 酒馆侧栏的两个入口：直接进角色库 / 人格页，不必绕设置。
            onOpenCharacters = { backStack.add(Characters) },
            onOpenPersona = { backStack.add(Persona) },
            onOpenCharacterDetail = { id -> backStack.add(CharacterDetail(id)) },
          )
        }
        entry<Settings> {
          SettingsScreen(
            onBack = { backStack.removeLastOrNull() },
            onOpenProviders = { backStack.add(Providers) },
            onOpenConnectors = { backStack.add(Connectors) },
            onOpenScheduledTasks = { backStack.add(ScheduledTasks) },
            onOpenVoice = { backStack.add(Voice) },
            onOpenPersona = { backStack.add(Persona) },
            onOpenCharacters = { backStack.add(Characters) },
          )
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
        entry<ScheduledTasks> {
          ScheduledTasksScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<TermuxBridge> {
          TermuxBridgeScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<Voice> {
          VoiceScreen(onBack = { backStack.removeLastOrNull() })
        }
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
      },
  )
}
