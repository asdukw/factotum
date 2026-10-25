package com.factotum.app.agent

import android.content.Context

/**
 * 写操作的审批策略：默认「每次询问」，用户可以在 设置 → 执行层 改成「全部允许」。
 *
 * **这不是 dsh 的 `policy`。** dsh 只有 `ask` / `never` 两个值，而 `never` 是确定性拒绝
 * （所有 ask 直接被拒），不是放行 —— 详见 `docs/approvals.md` §1.1。所以「全部允许」是
 * answerer 侧的决定：桥收到审批请求后直接回 `allowed-once`，面板不出现。
 *
 * 请求仍然经过 dsh 的审批 seam 与审计，变的只是「谁来回答」。代价是用户看不到那一次
 * 动作的内容 —— 成功之后的回执通知（[com.factotum.app.device.DeviceNotices]）是唯一可见的痕迹。
 *
 * 独立 prefs 文件：不含凭据，也不与任何存储身份绑定。
 */
object ApprovalSettings {
  private const val PREFS_NAME = "factotum_approval_settings"
  private const val KEY_ALLOW_ALL_WRITES = "allow_all_writes"

  fun policy(context: Context): ApprovalPolicy =
    if (prefs(context).getBoolean(KEY_ALLOW_ALL_WRITES, false)) {
      ApprovalPolicy.ALLOW_ALL
    } else {
      ApprovalPolicy.ASK
    }

  fun setAllowAllWrites(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ALLOW_ALL_WRITES, enabled).apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** 只有两种策略；dsh 的词汇里没有「总是允许」以外的第三种。 */
enum class ApprovalPolicy { ASK, ALLOW_ALL }

/**
 * 桥把请求交给 UI 之前的策略判定。
 *
 * 只有 [ApprovalPolicy.ALLOW_ALL] 会得到 `allowed-once`；[ApprovalPolicy.ASK] 返回 null，
 * 走原来的确认面板。新增策略时必须先在这里写出一条显式语义，不能靠默认值放行。
 */
internal fun autoApprovalOutcome(policy: ApprovalPolicy): ApprovalDecision? =
  when (policy) {
    ApprovalPolicy.ALLOW_ALL -> ApprovalDecision.ALLOW_ONCE
    ApprovalPolicy.ASK -> null
  }
