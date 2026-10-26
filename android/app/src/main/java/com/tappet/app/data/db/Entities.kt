package com.tappet.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话类型。
 *
 * - [Chat] 日常：干活、问问题，人格是助手本人
 * - [Background] 后台任务留下的记录
 * - [Roleplay] 酒馆：绑一张角色卡，开场白、世界设定、卡的人设都只在会话里生效
 *
 * 分区不只是界面分组：`kind` 决定这个会话由哪套系统提示驱动，也决定「日常」列表里
 * 看不到它 —— 聊天记录不该混在待办里。
 */
enum class SessionKind {
  Chat,
  Background,
  Roleplay,
}

/** 消息角色。比聊天用到的多两种，为后面的工具调用与系统提示留位。 */
enum class MessageRole {
  User,
  Assistant,
  Tool,
  System,
}

/**
 * 消息状态。
 *
 * - [Streaming] 正在流式写入；进程被杀会残留，启动时统一改判为 [Interrupted]
 * - [Stopped] 用户主动中止，已收到的内容保留
 */
enum class MessageStatus {
  Streaming,
  Complete,
  Stopped,
  Error,
  Interrupted,
}

enum class ToolCallStatus {
  Running,
  Ok,
  Failed,
}

/**
 * 一个会话。
 *
 * - `id` 是 UUIDv7，不是自增：将来要与 PC 的 `tappet.db` 合并，自增号必然冲突
 * - `excluded` 是隐私闸门，语义与 Python `conv.excluded` 一致：
 *   置位的会话永不进 LLM、永不 embedding、永不导出
 * - `summary` / `summaryUpto` 支撑上下文压缩：更早的消息被摘要替代，
 *   `summaryUpto` 记录压缩到哪条消息，避免重复压缩
 */
@Entity(
  tableName = "session",
  indices = [Index("updated_at"), Index("kind"), Index("character_id")],
)
data class SessionEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: String,
  @ColumnInfo(name = "title") val title: String?,
  @ColumnInfo(name = "kind") val kind: SessionKind,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "updated_at") val updatedAt: Long,
  @ColumnInfo(name = "excluded") val excluded: Boolean = false,
  @ColumnInfo(name = "summary") val summary: String? = null,
  @ColumnInfo(name = "summary_upto") val summaryUpto: String? = null,
  @ColumnInfo(name = "model") val model: String? = null,
  @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String? = null,
  /**
   * 酒馆会话绑定的角色卡 id；日常会话为 null。
   *
   * 存 id 而不是把卡正文抄进会话：卡是可替换的数据，改卡后旧会话应该跟着新卡走；
   * 卡被删了则退化成「已删除的角色」，历史记录仍然读得出来。
   */
  @ColumnInfo(name = "character_id") val characterId: String? = null,
)

/**
 * 一条消息。
 *
 * `seq` 是会话内的单调序号，排序不依赖时间戳（同一毫秒插入两条时时间戳会并列）。
 * 助手消息在发起流式请求时就插入（`status = Streaming`），正文按节流增量更新 ——
 * 否则进程被 ColorOS 杀掉时整段回复都会丢。
 */
@Entity(
  tableName = "message",
  foreignKeys =
    [
      ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index(value = ["session_id", "seq"], unique = true), Index(value = ["anchor_id"])],
)
data class MessageEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: String,
  @ColumnInfo(name = "session_id") val sessionId: String,
  @ColumnInfo(name = "seq") val seq: Long,
  @ColumnInfo(name = "role") val role: MessageRole,
  @ColumnInfo(name = "content") val content: String,
  @ColumnInfo(name = "reasoning") val reasoning: String?,
  @ColumnInfo(name = "status") val status: MessageStatus,
  @ColumnInfo(name = "error") val error: String?,
  @ColumnInfo(name = "created_at") val createdAt: Long,
  @ColumnInfo(name = "finished_at") val finishedAt: Long?,
  /**
   * 这条助手回复是在回答哪一条用户消息（那条用户消息的 id）。
   *
   * 为空表示「没有替代品」——开场白、工具中间步骤、日常任务的步骤都是这一类。
   * 有了它，「重写」就不用删掉上一份回答：新回答挂同一个 anchor，两份并存，只显示选中的那份。
   */
  @ColumnInfo(name = "anchor_id") val anchorId: String? = null,
  /** 同一个 anchor 下的第几份回答（0 起）。用于「2/3」这种指示与左右切。 */
  @ColumnInfo(name = "variant_index") val variantIndex: Int = 0,
  /**
   * 同一组里当前显示哪一份。
   *
   * 查询只取选中的那些，所以屏幕上的历史永远只有一条线 —— 不做并排的树。
   * 用「选中标记」而不是「组里存一个索引」：删掉中间一份时不会出现「索引指向空气」。
   */
  @ColumnInfo(name = "variant_selected") val variantSelected: Boolean = true,
)

/**
 * 一次工具调用。`id` 用模型返回的 call id，这样 `arguments` 与结果能对回同一次调用。
 * 这张表现在就建好，agent 循环接上工具时直接用。
 */
@Entity(
  tableName = "tool_call",
  foreignKeys =
    [
      ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["message_id"],
        onDelete = ForeignKey.CASCADE,
      ),
    ],
  indices = [Index("message_id"), Index("session_id")],
)
data class ToolCallEntity(
  @PrimaryKey @ColumnInfo(name = "id") val id: String,
  @ColumnInfo(name = "message_id") val messageId: String,
  @ColumnInfo(name = "session_id") val sessionId: String,
  @ColumnInfo(name = "name") val name: String,
  @ColumnInfo(name = "arguments") val arguments: String,
  @ColumnInfo(name = "result") val result: String?,
  @ColumnInfo(name = "status") val status: ToolCallStatus,
  @ColumnInfo(name = "started_at") val startedAt: Long,
  @ColumnInfo(name = "finished_at") val finishedAt: Long?,
)
