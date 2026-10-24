package com.tappet.app.ui.chat

/**
 * 工具的**对外显示名**。
 *
 * 模型看到的是原始工具名（`read_ui_tree`、`tap_node`…），但界面上不该出现带下划线的英文 ——
 * 使用者关心的是「读取屏幕」，不是「read_ui_tree」。这里只做展示层映射，不动工具契约。
 *
 * **认不出的工具回落到原名**：插件仓库可以随时加工具，UI 不该因为没同步而显示空白；
 * 宁可难看，也不要猜错。
 *
 * **这里是唯一的一份**：聊天页（`ProcessBlock`）和悬浮进度（`AgentProgress`）都从这里取名字。
 * 之前两处各有一个 `when`，加了新工具只补一处，另一处就漏出原始名。做 i18n 时也只改这一处
 * ——把字面量换成 `stringResource` / 资源 id 即可，调用方不用动。
 */
internal val TOOL_LABELS =
  mapOf(
    "read_ui_tree" to "读取屏幕",
    "screenshot" to "截屏",
    "capture_screenshot" to "截取画面",
    "tap_node" to "点按控件",
    "tap_point" to "点按坐标",
    "tap_target" to "点按目标",
    "long_press" to "长按",
    "long_press_target" to "长按目标",
    "type_text" to "输入文字",
    "press" to "按键",
    "launch_app" to "启动应用",
    "swipe" to "滑动",
    "set_alarm" to "设置闹钟",
    "add_calendar_event" to "添加日程",
    "list_calendar_events" to "查看日程",
    "update_calendar_event" to "修改日程",
    "delete_calendar_event" to "删除日程",
    "run_command" to "运行命令",
    "read" to "读取文件",
    "write" to "写入文件",
    "edit" to "修改文件",
    "todo_write" to "更新任务清单",
    "web_search" to "联网搜索",
    "ask_user_question" to "向你提问",
    "mail_list" to "查看邮件",
    "mail_search" to "搜索邮件",
    "mail_read" to "读取邮件",
    "mail_thread" to "读取邮件会话",
    "mail_labels" to "查看邮件标签",
    "mail_modify" to "修改邮件",
    "mail_draft" to "创建草稿",
    "mail_send" to "发送邮件",
    "mail_save_attachment" to "保存附件",
  )

internal fun toolDisplayName(raw: String): String = TOOL_LABELS[raw] ?: raw

/** 认不出时回 null，让调用方决定怎么兜底（进度条要加「调用」前缀，聊天页直接显示原名）。 */
internal fun toolLabelOrNull(raw: String): String? = TOOL_LABELS[raw]

/**
 * 思考时长：[elapsedMs] 毫秒 → 人读的字符串。
 *
 * 保留一位小数（「思考了 3.8 秒」比「4 秒」有信息量），且**最少 0.1 秒** —— 界面上的模板是
 * 「思考了 $x」，给「不到 1 秒」会拼成「思考了 不到 1 秒」。超过一分钟换成「M 分 S 秒」，
 * 免得出现「93.4 秒」这种要心算的数字。
 */
internal fun formatThinkingDuration(elapsedMs: Long): String {
  if (elapsedMs < 0) return ""
  val tenths = (elapsedMs / 100).coerceAtLeast(1)
  if (tenths < 600) return "${tenths / 10}.${tenths % 10} 秒"
  val totalSeconds = elapsedMs / 1_000
  return "${totalSeconds / 60} 分 ${totalSeconds % 60} 秒"
}
