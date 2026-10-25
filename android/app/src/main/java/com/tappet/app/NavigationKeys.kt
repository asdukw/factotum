package com.tappet.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 原生聊天页（Compose）——默认入口，走 DshStreamer + Room。 */
@Serializable data object Main : NavKey

/** 设置首页：模型提供商 / 执行层 / 存储说明。 */
@Serializable data object Settings : NavKey

/** 提供商列表（设置 → 提供商）。 */
@Serializable data object Providers : NavKey

/** 单个提供商的详情：配置 / 模型 / 高级设置。id 是 [com.tappet.app.agent.LlmPreset.id]。 */
@Serializable data class ProviderDetail(val providerId: String) : NavKey

/** Linux 环境（Termux 桥）配置：地址 + 访问 token。 */
@Serializable data object TermuxBridge : NavKey

/** 定时任务列表（设置 → 执行层 → 定时任务）。 */
@Serializable data object ScheduledTasks : NavKey

/** 连接器列表（设置 → 连接器）：Gmail / Outlook…… */
@Serializable data object Connectors : NavKey

/** Gmail 连接器：客户端 id / 授权 / 断开。 */
@Serializable data object GmailConnector : NavKey

/** 语音设置（设置 → 语音）：朗读引擎 / 自动朗读 / 语速 / 音色。 */
@Serializable data object Voice : NavKey

/** 助手（人格）设置（设置 → 助手）：换人格、写自定义 soul、语气契约开关。 */
@Serializable data object Persona : NavKey
