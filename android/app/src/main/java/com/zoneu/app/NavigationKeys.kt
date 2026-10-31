package com.zoneu.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 原生聊天页（Compose）——默认入口，走 DshStreamer + Room。 */
@Serializable data object Main : NavKey

/** 设置首页：只放分组入口（模型与服务 / 执行层 / 连接器 / 语音 / 定时任务 / 娱乐 / 关于）。 */
@Serializable data object Settings : NavKey

/** 模型与服务（设置 → 模型与服务）：提供商入口，后续的搜索 / 语音服务也挂这里。 */
@Serializable data object ModelServices : NavKey

/** 执行层（设置 → 执行层）：无障碍 / 写操作审批 / 悬浮球。 */
@Serializable data object ExecutionSettings : NavKey

/** 定时任务（设置 → 定时任务）：只读列表，创建与修改仍走对话。 */
@Serializable data object ScheduledTasks : NavKey

/** 一条任务的执行记录；taskId 来自列表行，不按标题猜。 */
@Serializable data class ScheduledTaskRuns(val taskId: String) : NavKey

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

/** 助手（人格）设置（设置 → 助手）：换人格、写自定义 soul、语气契约开关。 */
@Serializable data object Persona : NavKey

/** 角色库（设置 → 角色）：选一张角色卡开始角色扮演。 */
@Serializable data object Characters : NavKey

/** 一张角色卡的详情：人设 / 开场 / 世界设定，收藏与开始对话都从这里走。 */
@Serializable data class CharacterDetail(val characterId: String) : NavKey

/** 独立共享世界书库，可从精确工具回执打开指定资产。 */
@Serializable data class WorldBooks(val bookId: String? = null) : NavKey

/** 角色默认绑定或会话固定清单，scope 不混用。 */
@Serializable data class WorldBindings(val scope: String, val targetId: String) : NavKey
