package com.tappet.app

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.tappet.app.ui.chat.ChatScreen
import com.tappet.app.ui.probe.ProbeScreen
import com.tappet.app.ui.web.WebAgentScreen

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
            onOpenProbe = { backStack.add(Probe) },
            onOpenWeb = { backStack.add(WebPreview) },
          )
        }
        entry<WebPreview> { WebAgentScreen(onBack = { backStack.removeLastOrNull() }) }
        entry<Probe> { ProbeScreen(onBack = { backStack.removeLastOrNull() }) }
      },
  )
}
