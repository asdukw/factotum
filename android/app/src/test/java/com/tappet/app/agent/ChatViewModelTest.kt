package com.tappet.app.agent

import com.tappet.app.data.db.MessageRole
import com.tappet.app.data.db.MessageStatus
import com.tappet.app.data.db.SessionKind
import com.tappet.app.data.db.ToolCallStatus
import com.tappet.app.data.session.ChatSession
import com.tappet.app.data.session.SessionStore
import com.tappet.app.data.session.StoredMessage
import com.tappet.app.data.session.StoredToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun send_streamsDeltasIntoAssistantMessage() = runTest(dispatcher) {
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer = FakeStreamer(events = listOf(AgentStreamEvent.ReasoningDelta("想"), AgentStreamEvent.TextDelta("你"), AgentStreamEvent.TextDelta("好"), AgentStreamEvent.Completed)),
        store = FakeSessionStore(),
      )
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()

    val messages = viewModel.state.value.messages
    assertEquals(2, messages.size)
    assertEquals(ChatRole.User, messages[0].role)
    assertEquals("在吗", messages[0].content)
    assertEquals(ChatRole.Assistant, messages[1].role)
    assertEquals("你好", messages[1].content)
    assertEquals("想", messages[1].reasoning)
    assertFalse(messages[1].streaming)
    assertFalse(messages[1].thinking)
    assertNull(messages[1].error)
    assertFalse(viewModel.state.value.isStreaming)
    assertEquals("", viewModel.state.value.input)
  }

  @Test
  fun send_replacesStatusTextWithTheFirstAnswerDelta() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer =
          FakeStreamer(
            events =
              listOf(
                AgentStreamEvent.Status("正在调用 read_ui_tree…"),
                AgentStreamEvent.TextDelta("电池 100%"),
                AgentStreamEvent.TextDelta("。"),
                AgentStreamEvent.Completed,
              ),
          ),
        store = store,
      )
    advanceUntilIdle()

    viewModel.onInputChange("看看电量")
    viewModel.send()
    advanceUntilIdle()

    assertEquals("电池 100%。", viewModel.state.value.messages.last().content)
    assertEquals("电池 100%。", store.rows.last().content)
  }

  @Test
  fun send_persistsBothMessagesAndFinishesTheDraft() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = listOf(AgentStreamEvent.TextDelta("你好"))), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()

    assertEquals(2, store.rows.size)
    val question = store.rows[0]
    assertEquals(MessageRole.User, question.role)
    assertEquals("在吗", question.content)
    assertEquals(MessageStatus.Complete, question.status)

    val answer = store.rows[1]
    assertEquals(MessageRole.Assistant, answer.role)
    assertEquals("你好", answer.content)
    assertEquals(MessageStatus.Complete, answer.status)
    assertNotNull(answer.finishedAt)
    assertEquals("在吗", store.sessions.single().title)
  }

  @Test
  fun send_flushesPartialAnswerBeforeTheStreamEnds() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer = PacedStreamer(),
        store = store,
        clock = { testScheduler.currentTime },
        flushIntervalMs = 200,
      )
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceTimeBy(350)

    val midStream = store.rows.last()
    assertEquals("ab", midStream.content)
    assertEquals(MessageStatus.Streaming, midStream.status)
    assertNull(midStream.finishedAt)

    advanceUntilIdle()

    val finished = store.rows.last()
    assertEquals("abc", finished.content)
    assertEquals(MessageStatus.Complete, finished.status)
  }

  @Test
  fun stop_keepsPartialTextAndMarksItStopped() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = BlockingStreamer(), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()
    assertEquals("半", viewModel.state.value.messages.last().content)
    assertTrue(viewModel.state.value.isStreaming)

    viewModel.stop()
    advanceUntilIdle()

    val assistant = viewModel.state.value.messages.last()
    assertFalse(assistant.streaming)
    assertFalse(assistant.thinking)
    assertEquals("半", assistant.content)
    assertFalse(viewModel.state.value.isStreaming)
    assertEquals(MessageStatus.Stopped, store.rows.last().status)
    assertNotNull(store.rows.last().finishedAt)
  }

  @Test
  fun send_failureSurfacesOnAssistantMessage() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(failure = IOException("HTTP 401：无效密钥")), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("你好")
    viewModel.send()
    advanceUntilIdle()

    val assistant = viewModel.state.value.messages.last()
    assertEquals("HTTP 401：无效密钥", assistant.error)
    assertFalse(assistant.streaming)
    assertFalse(viewModel.state.value.isStreaming)
    assertEquals(MessageStatus.Error, store.rows.last().status)
    assertEquals("HTTP 401：无效密钥", store.rows.last().error)
  }

  @Test
  fun send_blankInputDoesNothing() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("   ")
    viewModel.send()
    advanceUntilIdle()

    assertTrue(viewModel.state.value.messages.isEmpty())
    assertTrue(store.rows.isEmpty())
  }

  @Test
  fun restore_loadsPersistedHistory() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val session = store.createSession(title = "上次的对话")
    store.appendMessage(session.id, MessageRole.User, "昨天聊到哪了", MessageStatus.Complete)
    store.appendMessage(session.id, MessageRole.Assistant, "聊到数据湖", MessageStatus.Complete, reasoning = "先查一下上下文")

    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = store)
    advanceUntilIdle()

    val messages = viewModel.state.value.messages
    assertEquals(2, messages.size)
    assertEquals("昨天聊到哪了", messages[0].content)
    assertEquals("聊到数据湖", messages[1].content)
    assertEquals("先查一下上下文", messages[1].reasoning)
    assertFalse(messages[1].streaming)
    assertTrue(viewModel.state.value.ready)
  }

  @Test
  fun restore_dropsEmptyDraftLeftByAKilledProcess() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val session = store.createSession(title = null)
    store.appendMessage(session.id, MessageRole.User, "在吗", MessageStatus.Complete)
    store.appendMessage(session.id, MessageRole.Assistant, "", MessageStatus.Interrupted)
    store.appendMessage(session.id, MessageRole.Assistant, "刚才断了", MessageStatus.Interrupted)

    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = store)
    advanceUntilIdle()

    val messages = viewModel.state.value.messages
    assertEquals(2, messages.size)
    assertEquals("在吗", messages[0].content)
    assertEquals("刚才断了", messages[1].content)
    assertFalse("半截回复不该显示成流式中", messages[1].streaming)
  }

  @Test
  fun restore_whenDatabaseIsUnreadable_blocksSendingAndExplainsWhy() = runTest(dispatcher) {
    val viewModel =
      ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = BrokenSessionStore())
    advanceUntilIdle()

    val state = viewModel.state.value
    assertFalse("读不到会话就不该放行发送", state.ready)
    assertFalse(state.canSend)
    assertNotNull("必须把原因说出来，否则用户只看到一个永远点不亮的发送键", state.storageError)
    assertTrue(state.storageError!!.contains("disk is gone"))

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()
    assertTrue("库坏了不该假装消息发出去了", viewModel.state.value.messages.isEmpty())
  }

  @Test
  fun toolCall_showsCardRunningThenOkAndPersistsIt() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer =
          FakeStreamer(
            events =
              listOf(
                AgentStreamEvent.ToolCallStarted("c1", "read_ui_tree", """{"mode":"tree"}"""),
                AgentStreamEvent.TextDelta("屏幕上写着设置"),
                AgentStreamEvent.ToolCallFinished("c1", ok = true, result = "设置 / 电池"),
                AgentStreamEvent.Completed,
              ),
          ),
        store = store,
      )
    advanceUntilIdle()

    viewModel.onInputChange("看看屏幕")
    viewModel.send()
    advanceUntilIdle()

    val assistant = viewModel.state.value.messages.last()
    assertEquals(1, assistant.toolCalls.size)
    assertEquals("read_ui_tree", assistant.toolCalls.single().name)
    assertEquals(ToolCallState.Ok, assistant.toolCalls.single().state)
    assertEquals("设置 / 电池", assistant.toolCalls.single().result)

    // 库里也落了一条，挂在这次回复的助手消息上
    assertEquals(1, store.toolRows.size)
    assertEquals(ToolCallStatus.Ok, store.toolRows.single().status)
    assertEquals(assistant.id, store.toolRows.single().messageId)

    // 切走再切回来，卡片从库里恢复（而不是留在内存里）
    val firstSession = viewModel.state.value.sessionId
    viewModel.newSession()
    advanceUntilIdle()
    assertTrue(viewModel.state.value.messages.isEmpty())

    viewModel.openSession(firstSession!!)
    advanceUntilIdle()
    val restored = viewModel.state.value.messages.last()
    assertEquals(1, restored.toolCalls.size)
    assertEquals("read_ui_tree", restored.toolCalls.single().name)
    assertEquals(ToolCallState.Ok, restored.toolCalls.single().state)
  }

  @Test
  fun toolCall_staysRunningUntilStopMarksItFailed() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = ToolThenBlockStreamer(), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("点一下")
    viewModel.send()
    advanceUntilIdle()

    val running = viewModel.state.value.messages.last().toolCalls.single()
    assertEquals(ToolCallState.Running, running.state)

    viewModel.stop()
    advanceUntilIdle()

    assertEquals(ToolCallState.Failed, viewModel.state.value.messages.last().toolCalls.single().state)
    assertEquals(ToolCallStatus.Failed, store.toolRows.single().status)
  }

  @Test
  fun newSession_switchesToAnEmptySessionAndBack() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel =
      ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = listOf(AgentStreamEvent.TextDelta("你好"))), store = store)
    advanceUntilIdle()

    viewModel.onInputChange("第一句")
    viewModel.send()
    advanceUntilIdle()
    val firstSession = viewModel.state.value.sessionId

    viewModel.newSession()
    advanceUntilIdle()

    assertEquals(2, store.sessions.size)
    assertEquals(2, viewModel.state.value.sessions.size)
    assertTrue("新会话应该是空的", viewModel.state.value.messages.isEmpty())
    assertTrue("新会话要能直接发消息", viewModel.state.value.ready)

    viewModel.openSession(firstSession!!)
    advanceUntilIdle()

    assertEquals("第一句", viewModel.state.value.messages.first().content)
  }

  @Test
  fun deleteSession_removesItAndFallsBackToAnother() = runTest(dispatcher) {
    val store = FakeSessionStore()
    val viewModel = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = store)
    advanceUntilIdle()
    val first = viewModel.state.value.sessionId!!
    viewModel.newSession()
    advanceUntilIdle()
    val second = viewModel.state.value.sessionId!!

    viewModel.deleteSession(second)
    advanceUntilIdle()

    assertEquals(1, store.sessions.size)
    assertEquals(first, viewModel.state.value.sessionId)
  }

  @Test
  fun state_reportsWhetherAgentIsConfigured() = runTest(dispatcher) {
    val unconfigured = ChatViewModel(config = testConfig(apiKey = ""), streamer = FakeStreamer(events = emptyList()), store = FakeSessionStore())
    assertFalse(unconfigured.state.value.configured)

    val configured = ChatViewModel(config = testConfig(), streamer = FakeStreamer(events = emptyList()), store = FakeSessionStore())
    assertTrue(configured.state.value.configured)
  }

  @Test
  fun chatCompletionsUrl_acceptsBaseOrFullEndpoint() {
    assertEquals("https://example.invalid/v1/chat/completions", testConfig().chatCompletionsUrl)
    assertEquals("https://example.invalid/v1/chat/completions", testConfig(baseUrl = "https://example.invalid/v1/chat/completions").chatCompletionsUrl)
    assertEquals("https://example.invalid/v1/chat/completions", testConfig(baseUrl = "https://example.invalid/v1/").chatCompletionsUrl)
  }

  private fun testConfig(apiKey: String = "test-key", baseUrl: String = "https://example.invalid/v1") =
    AgentConfig(
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = "deepseek-v4.1-flash",
      reasoningEffort = "low",
      proxyHost = null,
      proxyPort = 7897,
      // 用默认那条（OpenCode Go）的预选：这里只关心「有没有 key」，端点风格无所谓。
      preset = LlmPreset.default,
    )

  /** 增量先显示、成品后替换：内容只能出现一次（增量与成品不一致时以成品为准）。 */
  @Test
  fun `streamed deltas are replaced by the step final without duplication`() = runTest(dispatcher) {
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer =
          FakeStreamer(
            events =
              listOf(
                AgentStreamEvent.TextDelta("你", startsStep = true),
                AgentStreamEvent.TextDelta("好呀"),
                AgentStreamEvent.StepFinal(text = "你好", reasoning = null),
                AgentStreamEvent.Completed,
              ),
          ),
        store = FakeSessionStore(),
      )
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()

    assertEquals("你好", viewModel.state.value.messages.last().content)
  }

  /** 多步回答：第二步的成品只替换第二步追加的部分，第一步的正文不能被吃掉。 */
  @Test
  fun `step final only replaces the current step, keeping earlier steps`() = runTest(dispatcher) {
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer =
          FakeStreamer(
            events =
              listOf(
                AgentStreamEvent.TextDelta("A", startsStep = true),
                AgentStreamEvent.StepFinal(text = "A", reasoning = null),
                AgentStreamEvent.TextDelta("\n\n"),
                AgentStreamEvent.TextDelta("B", startsStep = true),
                AgentStreamEvent.StepFinal(text = "B", reasoning = null),
                AgentStreamEvent.Completed,
              ),
          ),
        store = FakeSessionStore(),
      )
    advanceUntilIdle()

    viewModel.onInputChange("在吗")
    viewModel.send()
    advanceUntilIdle()

    assertEquals("A" + "\n\n" + "B", viewModel.state.value.messages.last().content)
  }

  @Test
  fun switchModel_updatesStateAfterTheRuntimeReloads() = runTest(dispatcher) {
    val switcher =
      FakeModelSwitcher(
        choices = listOf(ModelChoice("openai", "OpenAI", "gpt-6-sol", current = true)),
      )
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer = FakeStreamer(events = emptyList()),
        store = FakeSessionStore(),
        switcher = switcher,
        switchDispatcher = dispatcher,
      )
    advanceUntilIdle()

    assertEquals(1, viewModel.modelSwitch.prepare().size)
    viewModel.modelSwitch.switch(ModelChoice("openai", "OpenAI", "gpt-6-sol", current = false))
    advanceUntilIdle()

    assertEquals(listOf("openai" to "gpt-6-sol"), switcher.calls)
    val state = viewModel.state.value
    assertEquals("openai", state.providerId)
    assertEquals("OpenAI", state.providerName)
    assertEquals("gpt-6-sol", state.model)
    assertFalse(state.switchingModel)
    assertNull(state.switchError)
    assertTrue(state.configSummary.contains("gpt-6-sol"))
  }

  @Test
  fun switchModel_surfacesFailureAndKeepsTheOldSelection() = runTest(dispatcher) {
    val switcher = FakeModelSwitcher(failure = IllegalStateException("重载超时"))
    val viewModel =
      ChatViewModel(
        config = testConfig(),
        streamer = FakeStreamer(events = emptyList()),
        store = FakeSessionStore(),
        switcher = switcher,
        switchDispatcher = dispatcher,
      )
    advanceUntilIdle()
    val before = viewModel.state.value.model

    viewModel.modelSwitch.switch(ModelChoice("openai", "OpenAI", "gpt-6-sol", current = false))
    advanceUntilIdle()

    val state = viewModel.state.value
    assertEquals("重载超时", state.switchError)
    assertFalse(state.switchingModel)
    assertEquals(before, state.model)
  }
}

private class FakeModelSwitcher(
  private val choices: List<ModelChoice> = emptyList(),
  private val failure: Throwable? = null,
) : ModelSwitcher {
  val calls = mutableListOf<Pair<String, String>>()

  override fun choices(): List<ModelChoice> = choices

  override suspend fun switch(providerId: String, model: String) {
    calls += providerId to model
    failure?.let { throw it }
  }
}

private class FakeStreamer(
  private val events: List<AgentStreamEvent> = emptyList(),
  private val failure: Throwable? = null,
) : ChatStreamer {
  override fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent> = flow {
    events.forEach { emit(it) }
    failure?.let { throw it }
  }
}

/** 每片之间隔 300ms（虚拟时间），用来验证节流落库。 */
private class PacedStreamer : ChatStreamer {
  override fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent> = flow {
    emit(AgentStreamEvent.TextDelta("a"))
    delay(300)
    emit(AgentStreamEvent.TextDelta("b"))
    delay(300)
    emit(AgentStreamEvent.TextDelta("c"))
    emit(AgentStreamEvent.Completed)
  }
}

/** 吐一片就永远挂住，用来验证“停止”。 */
private class BlockingStreamer : ChatStreamer {
  override fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent> = flow {
    // 与真实 Streamer 一致：每步第一条增量带 startsStep（界面据此立刻上屏）
    emit(AgentStreamEvent.TextDelta("半", startsStep = true))
    awaitCancellation()
  }
}

/** 起了个工具调用就挂住，用来验证「停掉时把执行中的卡片落成失败」。 */
private class ToolThenBlockStreamer : ChatStreamer {
  override fun stream(sessionId: String, prompt: String): Flow<AgentStreamEvent> = flow {
    emit(AgentStreamEvent.ToolCallStarted("c1", "tap_node", "{}"))
    awaitCancellation()
  }
}

/** 纯内存的会话库，让单测不依赖 SQLCipher（它的原生库在 PC 的 JVM 上不存在）。 */
private open class FakeSessionStore : SessionStore {
  val sessions = mutableListOf<ChatSession>()
  val rows = mutableListOf<StoredMessage>()
  val toolRows = mutableListOf<StoredToolCall>()
  private val sessionFlow = MutableStateFlow<List<ChatSession>>(emptyList())
  private var nextId = 0
  private var clock = 1_000L

  override suspend fun currentSession(): ChatSession = sessions.firstOrNull() ?: createSession(title = null)

  override suspend fun createSession(title: String?): ChatSession {
    val now = clock++
    val session = ChatSession(id = "session-${sessions.size}", title = title, kind = SessionKind.Chat, createdAt = now, updatedAt = now)
    sessions += session
    publishSessions()
    return session
  }

  override fun observeSessions(): Flow<List<ChatSession>> = sessionFlow.asStateFlow()

  override suspend fun deleteSession(id: String) {
    sessions.removeAll { it.id == id }
    rows.removeAll { it.sessionId == id }
    toolRows.removeAll { it.sessionId == id }
    publishSessions()
  }

  private fun publishSessions() {
    sessionFlow.value = sessions.toList()
  }

  override suspend fun messages(sessionId: String): List<StoredMessage> = rows.filter { it.sessionId == sessionId }

  override suspend fun appendMessage(
    sessionId: String,
    role: MessageRole,
    content: String,
    status: MessageStatus,
    reasoning: String?,
  ): StoredMessage {
    val row =
      StoredMessage(
        id = "message-${nextId++}",
        sessionId = sessionId,
        seq = rows.count { it.sessionId == sessionId }.toLong() + 1,
        role = role,
        content = content,
        reasoning = reasoning,
        status = status,
        error = null,
        createdAt = clock++,
        finishedAt = null,
      )
    rows += row
    return row
  }

  override suspend fun updateMessage(
    id: String,
    content: String,
    reasoning: String?,
    status: MessageStatus,
    error: String?,
    finishedAt: Long?,
  ) {
    val index = rows.indexOfFirst { it.id == id }
    if (index >= 0) {
      rows[index] = rows[index].copy(content = content, reasoning = reasoning, status = status, error = error, finishedAt = finishedAt)
    }
  }

  override suspend fun setTitleIfEmpty(sessionId: String, title: String) {
    val index = sessions.indexOfFirst { it.id == sessionId }
    if (index >= 0 && sessions[index].title == null) {
      sessions[index] = sessions[index].copy(title = title)
    }
  }

  override suspend fun appendToolCall(
    id: String,
    sessionId: String,
    messageId: String,
    name: String,
    arguments: String,
  ): StoredToolCall {
    val row =
      StoredToolCall(
        id = id,
        sessionId = sessionId,
        messageId = messageId,
        name = name,
        arguments = arguments,
        result = null,
        status = ToolCallStatus.Running,
        startedAt = clock++,
        finishedAt = null,
      )
    toolRows += row
    return row
  }

  override suspend fun finishToolCall(id: String, result: String?, status: ToolCallStatus) {
    val index = toolRows.indexOfFirst { it.id == id }
    if (index >= 0) {
      toolRows[index] = toolRows[index].copy(result = result, status = status, finishedAt = clock++)
    }
  }

  override suspend fun toolCalls(sessionId: String): List<StoredToolCall> = toolRows.filter { it.sessionId == sessionId }

  override suspend fun markStreamingAsInterrupted(): Int = 0
}

/** 连会话列表都读不出来的库（比如 keystore 口令丢了）。 */
private class BrokenSessionStore : FakeSessionStore() {
  override suspend fun currentSession(): ChatSession = throw IOException("disk is gone")
}
