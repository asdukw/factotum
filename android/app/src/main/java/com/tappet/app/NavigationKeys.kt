package com.tappet.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 原生聊天页（Compose）——默认入口，走 DshStreamer + Room。 */
@Serializable data object Main : NavKey

/** 设置：LLM 端点与自己的 API key（密钥走 Keystore 封装存储）。 */
@Serializable data object Settings : NavKey
