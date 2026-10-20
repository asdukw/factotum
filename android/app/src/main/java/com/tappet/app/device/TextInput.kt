package com.tappet.app.device

/**
 * `type_text` / `press(enter)` 失败时的机器可读原因码。
 *
 * 与 [VisionCode] 同一条规矩：文案会改，码不会 —— 界面、插件与审计都按码判断，
 * 不去匹配中文。**这几个码是给「下一步该做什么」用的**：每一条都对应一个不同的动作
 * （重新开关无障碍 / 先点一下输入框 / 换台设备），混在一起就等于什么都没说。
 */
object TextInputCode {
  /** 系统太老（Android 13 以下）没有无障碍输入法通道，而控件树里又没有输入框可写。 */
  const val UNSUPPORTED_OS = "text_unsupported_os"

  /**
   * 无障碍服务**当前生效的配置里没有 `flagInputMethodEditor`**。
   *
   * 这条只在一种情况下出现：服务是按旧配置绑定起来的（升级 APK 之后没重新启用过无障碍）。
   * 开关一次无障碍就会重新解析 `accessibility_service_config.xml`。
   */
  const val FLAG_MISSING = "text_flag_missing"

  /** 通道拿到连接了，但这一笔没写进去（App 侧拒绝 / 连接已失效）。 */
  const val COMMIT_REJECTED = "text_commit_rejected"

  /** 没有拿到连接：界面上没有**已聚焦**的输入框（先点一下输入框再来）。 */
  const val NO_CHANNEL = "text_no_channel"

  /** 控件树里有可编辑节点，但它拒绝了 `ACTION_SET_TEXT`（自绘输入框常见）。 */
  const val SET_TEXT_REJECTED = "text_set_text_rejected"
}

/** 一次文本注入失败的结论：码给程序，[summary] 给模型。 */
data class TextInputFailure(val code: String, val summary: String)

/**
 * 两条路都走不通时该报哪一个码 —— **纯函数，故意抽出来单测**。
 *
 * 判据顺序就是「先说最外层的原因」：
 *
 * 1. 没有可编辑节点、系统又太老 → [TextInputCode.UNSUPPORTED_OS]（换台设备才行，别让用户去设置里翻）
 * 2. 没有可编辑节点、配置里没这个 flag → [TextInputCode.FLAG_MISSING]（用户能修：重新开关一次无障碍）
 * 3. 通道有连接却写失败 → [TextInputCode.COMMIT_REJECTED]
 * 4. 没有可编辑节点、也没有连接 → [TextInputCode.NO_CHANNEL]（**最常见**：输入框没聚焦）
 * 5. 其余（有节点但被拒）→ [TextInputCode.SET_TEXT_REJECTED]
 *
 * @param hadEditableNode 控件树里找到了可见的可编辑节点
 * @param channelSupported 系统版本是否有这条通道（API 33+）
 * @param flagActive 无障碍服务**当前生效的**配置里有 `flagInputMethodEditor`
 * @param channelReady 通道此刻拿得到输入框连接
 */
fun textInputFailure(
  hadEditableNode: Boolean,
  channelSupported: Boolean,
  flagActive: Boolean,
  channelReady: Boolean,
): TextInputFailure =
  when {
    !hadEditableNode && !channelSupported ->
      TextInputFailure(
        TextInputCode.UNSUPPORTED_OS,
        "当前界面没有可读的输入框，而这台设备（Android 13 以下）没有无障碍文本注入通道。" +
          "如实告诉用户这一步做不到，不要反复重试。",
      )

    !hadEditableNode && !flagActive ->
      TextInputFailure(
        TextInputCode.FLAG_MISSING,
        "当前界面没有可读的输入框，而无障碍服务的文本注入能力没生效" +
          "（多半是升级 App 之后没有重新启用过无障碍）。请让用户在系统设置里把 Tappet 的" +
          "无障碍**关掉再打开一次**，然后重新发起这一步。",
      )

    channelReady ->
      TextInputFailure(
        TextInputCode.COMMIT_REJECTED,
        "输入框连接拿到了，但文本没有写进去（App 拒绝了这次写入）。" +
          "可以再点一次输入框、重新试一次；仍然失败就如实告诉用户。",
      )

    !hadEditableNode ->
      TextInputFailure(
        TextInputCode.NO_CHANNEL,
        "当前界面没有可读的输入框，也没有任何输入框**获得焦点**。" +
          "先用视觉路径点一下输入框（capture_frame → tap_target），让它拿到焦点，再调 type_text。",
      )

    else ->
      TextInputFailure(
        TextInputCode.SET_TEXT_REJECTED,
        "控件树里的输入框拒绝了写入（自绘输入框常见），而文本注入通道也没有可用连接。" +
          "先点一下输入框让它聚焦，再调一次 type_text。",
      )
  }
