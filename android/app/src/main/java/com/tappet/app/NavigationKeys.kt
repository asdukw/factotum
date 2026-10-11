package com.tappet.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 原生聊天页（Compose）——默认入口，走 DshStreamer + Room。 */
@Serializable data object Main : NavKey

/** dsh Web UI（WebView 嵌入）——按需打开的复杂预览面板。 */
@Serializable data object WebPreview : NavKey

/** 无障碍「四问」探针。 */
@Serializable data object Probe : NavKey
