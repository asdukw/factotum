package com.zoneu.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zoneu.app.agent.WorldBook
import com.zoneu.app.agent.WorldBookException
import com.zoneu.app.agent.WorldBookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen-local state; a failed write preserves both the visible snapshot and any edit draft. */
internal class WorldBookUi(val repository: WorldBookRepository, private val scope: CoroutineScope) {
  var books by mutableStateOf<List<WorldBook>>(emptyList())
    private set
  var busy by mutableStateOf(false)
    private set
  var message by mutableStateOf<String?>(null)
    private set
  var messageIsError by mutableStateOf(false)
    private set

  /** 成功提示：与失败共用一条横幅，但用成功色，不然「已保存」是红的。 */
  fun notice(text: String) {
    message = text
    messageIsError = false
  }

  fun fail(text: String) {
    message = text
    messageIsError = true
  }

  fun run(block: suspend () -> Unit) {
    if (busy) return
    busy = true
    scope.launch {
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (
        // Surface every storage/SAF failure without losing the draft; cancellation remains distinct.
        @Suppress("TooGenericExceptionCaught") error: Exception,
      ) {
        message = worldUiFailure(error)
        messageIsError = true
      } finally {
        busy = false
      }
    }
  }

  suspend fun refresh() {
    books = withContext(Dispatchers.IO) { repository.list() }
  }
}

internal fun worldUiFailure(error: Exception): String = when ((error as? WorldBookException)?.code) {
  "WORLD_REVISION_CONFLICT", "WORLD_BINDING_CONFLICT" -> "版本已改变，草稿仍保留。请确认放弃草稿后重新读取，不会自动覆盖。"
  "WORLD_REFERENCED" -> "仍有角色或会话引用这本书（包括已停用的引用），请先解绑。"
  "WORLD_DUPLICATE_DELETED" -> "原书已删除，重复导入不会复活它。要独立创建请使用新书。"
  "WORLD_REQUEST_PENDING" -> "这段会话有待结算请求，请先在会话世界配置中核对或明确放弃。"
  else -> "操作未确认，请保留草稿后重读核对。${(error as? WorldBookException)?.code.orEmpty()}"
}
