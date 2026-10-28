package com.zoneu.app.connector

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 请求可随协程取消，不返回或记录响应体中的服务器描述。 */
internal class GmailTransport {
  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

  suspend fun post(form: String): TokenOutcome = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(GOOGLE_TOKEN_URL)
      .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
    val call = http.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (continuation.isActive) continuation.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        val result = runCatching {
          response.use {
            Log.i("ZoneuConnector", "token response http=${it.code}")
            parseTokenHttpResponse(it.code, it.body?.string().orEmpty())
          }
        }
        if (continuation.isActive) continuation.resumeWith(result)
      }
    })
  }
}
