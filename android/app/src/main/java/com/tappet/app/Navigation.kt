package com.tappet.app

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.tappet.app.ui.chat.ChatScreen
import com.tappet.app.ui.settings.ConnectorsScreen
import com.tappet.app.ui.settings.GmailConnectorScreen
import com.tappet.app.ui.settings.ProviderDetailScreen
import com.tappet.app.ui.settings.ProvidersScreen
import com.tappet.app.ui.settings.SettingsScreen
import com.tappet.app.ui.settings.TermuxBridgeScreen

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
            onOpenProviders = { backStack.add(Providers) },
            onOpenConnectors = { backStack.add(Connectors) },
            onOpenTermuxBridge = { backStack.add(TermuxBridge) },
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
        entry<TermuxBridge> {
          TermuxBridgeScreen(onBack = { backStack.removeLastOrNull() })
        }
      },
  )
}
