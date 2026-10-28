package com.zoneu.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 原生聊天页（Compose）——默认入口，走 DshStreamer + Room。 */
@Serializable data object Main : NavKey

/** 设置首页：只放分组入口（模型与服务 / 执行层 / 连接器 / 语音 / 关于）。 */
@Serializable data object Settings : NavKey

/** 模型与服务（设置 → 模型与服务）：提供商入口，后续的搜索 / 语音服务也挂这里。 */
@Serializable data object ModelServices : NavKey

/** 执行层（设置 → 执行层）：无障碍 / 写操作审批 / 悬浮球 / 定时任务。 */
@Serializable data object ExecutionSettings : NavKey

/** 关于（设置 → 关于）：版本与应用内更新。 */
@Serializable data object About : NavKey

/** 提供商列表（设置 → 模型与服务 → 提供商）。 */
@Serializable data object Providers : NavKey

/** 单个提供商的详情：配置 / 模型 / 高级设置。id 是 [com.zoneu.app.agent.LlmPreset.id]。 */
@Serializable data class ProviderDetail(val providerId: String) : NavKey

/** 连接器列表（设置 → 连接器）：Gmail / Outlook…… */
@Serializable data object Connectors : NavKey

/** Gmail 连接器：客户端 id / 授权 / 断开。 */
@Serializable data object GmailConnector : NavKey

/** 语音设置（设置 → 语音）：朗读引擎 / 自动朗读 / 语速 / 音色。 */
@Serializable data object Voice : NavKey
