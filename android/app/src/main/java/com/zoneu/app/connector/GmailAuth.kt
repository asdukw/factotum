package com.zoneu.app.connector

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.util.UUID

/** Android 适配层：浏览器 + PKCE；长期凭据提交与并发刷新由 GmailSession 保护。 */
internal object GmailAuth {
  private const val TAG = "ZoneuConnector"
  private const val CONSENT_TIMEOUT_MILLIS = 10 * 60 * 1000L
  private val transport = GmailTransport()

  @Volatile private var activeSession: GmailSession? = null

  private fun session(context: Context): GmailSession = synchronized(this) {
    activeSession ?: run {
      val app = context.applicationContext
      GmailSession(
        ConnectorStore.credentials(app),
        { ConnectorStore.gmailClient(app) },
        { client, token -> transport.post(refreshTokenForm(client.id, client.secret, token)) },
        SystemClock::elapsedRealtime,
        System::currentTimeMillis,
      ).also { activeSession = it }
    }
  }

  fun status(context: Context): ConnectorStatus {
    val stored = ConnectorStore.status(context, GMAIL_CONNECTOR_ID)
    val problem = session(context).problem()
    return stored.copy(connected = problem == null, problem = problem)
  }

  fun connect(context: Context, client: GmailClient): Flow<ConnectEvent> = channelFlow {
    val lifecycle = session(context)
    val ticket = lifecycle.beginConnection()
    try {
      if (client.id.isBlank()) {
        send(failed("NOT_CONFIGURED"))
      } else if (!lifecycle.prepareConnection(ticket) { ConnectorStore.saveClient(context, client) }) {
        send(failed("STORAGE_FAILED"))
      } else {
        GmailAuthorizationService.during(context) {
          authorize(context, client, lifecycle, ticket).collect { send(it) }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: GmailAuthorizationException) {
      send(failed(failure.code))
    } catch (_: IOException) {
      send(failed("NETWORK"))
    } finally {
      lifecycle.cancelConnection(ticket)
    }
  }.flowOn(Dispatchers.IO)

  private fun authorize(context: Context, client: GmailClient, lifecycle: GmailSession, ticket: Long): Flow<ConnectEvent> = flow {
    GmailLoopback().use { receiver ->
      val verifier = newCodeVerifier()
      val state = UUID.randomUUID().toString()
      val url = authorizationUrl(client.id, receiver.redirectUri, codeChallenge(verifier), state)
      if (!openBrowser(context, url)) {
        emit(failed("BROWSER_UNAVAILABLE"))
      } else {
        emit(ConnectEvent.AwaitingConsent)
        val params = receiver.await(CONSENT_TIMEOUT_MILLIS)
        val error = redirectFailure(params, state)
        if (error != null) {
          emit(failed(error))
        } else {
          val outcome = transport.post(codeExchangeForm(client.id, client.secret, params!!.code!!, verifier, receiver.redirectUri))
          currentCoroutineContext().ensureActive()
          val result = when (outcome) {
            is TokenOutcome.Granted -> lifecycle.commitConnection(ticket, client, outcome)
            is TokenOutcome.Denied -> tokenFailure(outcome.code)
          }
          emit(
            when (result) {
              is AccessTokenResult.Ok -> ConnectEvent.Connected
              is AccessTokenResult.Unavailable -> failed(result.code)
            },
          )
        }
      }
    }
  }

  suspend fun accessToken(context: Context, rejectedToken: String? = null): AccessTokenResult =
    session(context).accessToken(rejectedToken)

  fun cancelPending(context: Context) = session(context).cancelPending()

  fun invalidate(context: Context, token: String) = session(context).invalidate(token)

  fun disconnect(context: Context): Boolean = session(context).disconnect()

  private fun failed(code: String): ConnectEvent.Failed {
    // 固定白名单类别，不记录服务器文本、邮箱或任何凭据。
    val failure = tokenFailure(code)
    Log.w(TAG, "connect failed code=${failure.code}")
    return ConnectEvent.Failed(failure.summary)
  }

  private fun openBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }.isSuccess
}
