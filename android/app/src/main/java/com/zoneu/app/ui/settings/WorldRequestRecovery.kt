package com.zoneu.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zoneu.app.agent.WorldRequestPlan

/** Unknown outcomes stay blocked until verified or explicitly abandoned by the user. */
@Composable
internal fun WorldRequestRecovery(sessionId: String, ui: WorldBookUi) {
  val context = LocalContext.current
  var pending by remember(sessionId) { mutableStateOf<WorldRequestPlan?>(null) }
  var confirm by remember { mutableStateOf(false) }
  LaunchedEffect(sessionId, ui.busy) {
    if (!ui.busy) pending = ui.repository.preparedPlan(sessionId)
  }
  val plan = pending ?: return
  Text("存在待结算请求 ${plan.requestId}。不会猜测成功或自动推进计时。")
  TextButton(enabled = !ui.busy, onClick = {
    ui.run {
      val verified = com.zoneu.app.agent.WorldRequestRecovery.reconcile(context, sessionId, plan.requestId)
      if (verified) {
        ui.notice("已核对正式结算证据")
      } else {
        ui.fail("仍无法核对，保留待结算状态；可明确放弃本次计时。")
      }
    }
  }) { Text("重新核对结算证据") }
  TextButton(enabled = !ui.busy, onClick = { confirm = true }) { Text("明确放弃待结算请求") }
  if (confirm) {
    WorldConfirm("放弃这次世界计时？", "停止并核对对应请求；必要时重载运行时。已收到的消息不会删除，本次世界书计时不推进。不能据此断言后端先前没有完成。", { confirm = false }) {
      confirm = false
      ui.run {
        com.zoneu.app.agent.WorldRequestRecovery.abandon(context, sessionId, plan.requestId)
        pending = null
        ui.notice("已明确放弃本次待结算计时")
      }
    }
  }
}
