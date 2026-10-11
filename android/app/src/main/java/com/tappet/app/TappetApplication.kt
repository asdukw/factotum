package com.tappet.app

import android.app.Application
import android.util.Log
import com.tappet.app.data.db.TappetDatabase
import com.tappet.app.data.db.TappetDatabaseProvider
import com.tappet.app.data.session.RoomSessionStore
import com.tappet.app.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application：打开 tappet.db（会话 / 消息），并把上次进程被杀时残留的
 * “流式中”消息改判掉。
 */
class TappetApplication : Application() {
  /** 会话库。第一次用到时才开库，避免拖慢冷启动。 */
  val database: TappetDatabase by lazy { TappetDatabaseProvider.open(this) }

  val sessionStore: SessionStore by lazy { RoomSessionStore(database) }

  override fun onCreate() {
    super.onCreate()

    appScope.launch {
      runCatching { sessionStore.markStreamingAsInterrupted() }
        .onSuccess { count -> if (count > 0) Log.i(TAG, "改判 $count 条中断的流式消息") }
        .onFailure { Log.w(TAG, "会话库初始化失败: ${it.javaClass.simpleName}") }
    }
  }

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  companion object {
    const val TAG = "Tappet"
  }
}
