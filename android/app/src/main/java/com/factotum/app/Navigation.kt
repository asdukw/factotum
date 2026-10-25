package com.factotum.app

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.factotum.app.ui.chat.ChatScreen
import com.factotum.app.ui.settings.AboutScreen
import com.factotum.app.ui.settings.ConnectorsScreen
import com.factotum.app.ui.settings.ExecutionSettingsScreen
import com.factotum.app.ui.settings.GmailConnectorScreen
import com.factotum.app.ui.settings.ModelServicesScreen
import com.factotum.app.ui.settings.ProviderDetailScreen
import com.factotum.app.ui.settings.ProvidersScreen
import com.factotum.app.ui.settings.SettingsScreen
import com.factotum.app.ui.settings.VoiceScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ChatScreen(onOpenSettings = { backStack.add(Settings) })
        }
        entry<Settings> {
          SettingsScreen(
            onBack = { backStack.removeLastOrNull() },
            onOpenModelServices = { backStack.add(ModelServices) },
            onOpenExecution = { backStack.add(ExecutionSettings) },
            onOpenConnectors = { backStack.add(Connectors) },
            onOpenVoice = { backStack.add(Voice) },
            onOpenAbout = { backStack.add(About) },
          )
        }
        entry<ModelServices> {
          ModelServicesScreen(
            onBack = { backStack.removeLastOrNull() },
            onOpenProviders = { backStack.add(Providers) },
          )
        }
        entry<ExecutionSettings> {
          ExecutionSettingsScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<About> {
          AboutScreen(onBack = { backStack.removeLastOrNull() })
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
      },
  )
}
