package com.tappet.app

import android.app.Application
import android.util.Log
import com.tappet.app.data.db.TappetDatabase
import com.tappet.app.data.db.TappetDatabaseProvider
import com.tappet.app.data.session.RoomSessionStore
import com.tappet.app.data.session.SessionStore
import com.tappet.app.schedule.ExactAlarmMonitor
import com.tappet.app.voice.AsrModelCatalog
import com.tappet.app.voice.VoiceModelInstaller
import com.tappet.app.voice.VoiceModelKinds
import com.tappet.app.voice.VoiceModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application：打开 tappet.db（会话 / 消息），把上次进程被杀时残留的“流式中”消息改判掉，
 * 并在安装后把识别模型准备好。
 */
class TappetApplication : Application() {
  /** 会话库。第一次用到时才开库，避免拖慢冷启动。 */
  val database: TappetDatabase by lazy { TappetDatabaseProvider.open(this) }

  val sessionStore: SessionStore by lazy { RoomSessionStore(database) }

  override fun onCreate() {
    super.onCreate()

    // 定时任务的「闹钟和提醒」权限提醒：每 6 小时一次非精确闹钟，幂等（已排过就不重复排）。
    ExactAlarmMonitor.schedule(this)

    appScope.launch {
      runCatching { sessionStore.markStreamingAsInterrupted() }
        .onSuccess { count -> if (count > 0) Log.i(TAG, "改判 $count 条中断的流式消息") }
        .onFailure { Log.w(TAG, "会话库初始化失败: ${it.javaClass.simpleName}") }
    }

    appScope.launch {
      runCatching { pruneLegacyModels() }
        .onFailure { Log.w(TAG, "清理旧语音模型失败: ${it.javaClass.simpleName}") }
      prepareAsrModel()
    }
  }

  /**
   * 识别模型**没有设置页入口**，随安装自动下载（用户不需要也不该选它）。
   *
   * 已经装好、或者正在装，[VoiceModelInstaller.ensureInstalled] 都是空操作；上次下载失败
   * 的话下一次启动会再试一次（下载本身断点续传）。
   */
  private fun prepareAsrModel() {
    VoiceModelInstaller.ensureInstalled(this, VoiceModelKinds.ASR, AsrModelCatalog.PARAFORMER_ZH_SMALL)
  }

  /**
   * 清掉旧版本下过、目录里已经没有的识别模型（sense-voice / whisper tiny）。
   *
   * 这些模型现在既不会被用到、界面上也没有入口，留着只是让用户白白少掉几十上百 MB。
   * 判据是当前目录表 —— 目录里保留的那条模型（以及它需要的标点模型）不会被碰到。
   */
  private fun pruneLegacyModels() {
    VoiceModelStore.pruneUnused(this, VoiceModelKinds.ASR, AsrModelCatalog.ALL.map { it.id }.toSet())
  }

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  companion object {
    const val TAG = "Tappet"
  }
}
