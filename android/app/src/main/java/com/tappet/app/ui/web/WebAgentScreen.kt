package com.tappet.app.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tappet.app.BuildConfig
import com.tappet.app.node.NodeRuntime
import com.tappet.app.node.NodeTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * dsh Web UI 的原生壳（按需打开的预览面板）：启动内嵌 dsh 运行时（含 host-webserver），
 * 等 launcher 把带 token 的 URL 落到 dsh-home/web-url.txt，再用 WebView 打开。
 *
 * 日常聊天走原生 Compose（ChatScreen）；这里留给 HTML / Office 预览、diff、
 * 代码高亮、复杂富文本这类需要 dsh 前端自己渲染的内容。
 *
 * [onBack] 非空时顶部显示返回栏（独立导航入口用）；为空则整屏都是 WebView。
 */
@Composable
fun WebAgentScreen(onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
  val appContext = LocalContext.current.applicationContext
  val url by NodeRuntime.webUiUrl.collectAsStateWithLifecycle()
  val status by NodeRuntime.status.collectAsStateWithLifecycle()
  var failure by remember { mutableStateOf<String?>(null) }
  var attempt by remember { mutableStateOf(0) }

  // 任务期间保活：Web UI 的会话在 Node 进程里跑，App 切到后台也不能被冻。
  // start/stop 必须成对放在 DisposableEffect 里（重试不会重复计数）。
  DisposableEffect(Unit) {
    NodeTaskService.start(appContext)
    onDispose { NodeTaskService.stop(appContext) }
  }
  LaunchedEffect(attempt) {
    failure = null
    val result = runCatching { withContext(Dispatchers.IO) { NodeRuntime.initialize(appContext) } }
    failure = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
  }

  Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
    if (onBack != null) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onBack) { Text("返回") }
        Column(Modifier.weight(1f)) {
          Text(text = "Web 预览", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "HTML / Office / diff / 富文本等复杂渲染",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      HorizontalDivider()
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      val readyUrl = url
      when {
        readyUrl != null -> DshWebView(url = readyUrl, modifier = Modifier.fillMaxSize())
        failure != null -> FailureState(message = failure!!, onRetry = { attempt++ })
        else -> LoadingState(status = status.toString())
      }
    }
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DshWebView(url: String, modifier: Modifier = Modifier) {
  var webView by remember { mutableStateOf<WebView?>(null) }
  var canGoBack by remember { mutableStateOf(false) }

  AndroidView(
    modifier = modifier,
    factory = { context ->
      WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webChromeClient =
          object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
              if (BuildConfig.DEBUG) Log.i(TAG, "console: ${message.message()} @${message.sourceId()}:${message.lineNumber()}")
              return true
            }
          }
        webViewClient =
          object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
              if (BuildConfig.DEBUG) Log.i(TAG, "page started: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
              if (BuildConfig.DEBUG && request.isForMainFrame) Log.e(TAG, "load error ${error.errorCode}: ${error.description} ${request.url}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
              if (BuildConfig.DEBUG && request.isForMainFrame) Log.e(TAG, "http error ${response.statusCode} ${request.url}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
              canGoBack = view.canGoBack()
              if (BuildConfig.DEBUG) Log.i(TAG, "page finished: title=${view.title} url=$url")
              // 部分 WebView（ColorOS）里 100%/100vh 都解析成 0（innerHeight 却正常），整页高度 0 → 白屏。
              // 用 window.innerHeight 的像素高度兜底，并在 resize 后重算。
              view.evaluateJavascript(
                "(function(){function f(){var h=window.innerHeight+'px';document.documentElement.style.height=h;document.body.style.height=h;var r=document.getElementById('root');if(r)r.style.height=h;}f();window.addEventListener('resize',f);setTimeout(f,300);setTimeout(f,1500);function c(){var b=[].slice.call(document.querySelectorAll('button')).find(function(x){return /收起侧边栏|Collapse sidebar/.test(x.getAttribute('aria-label')||'');});if(b)b.click();}c();var n=0;var t=setInterval(function(){c();if(++n>24)clearInterval(t);},400);})()",
                null,
              )
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
              canGoBack = view.canGoBack()
            }
          }
        loadUrl(url)
        webView = this
      }
    },
    update = { view -> if (view.url != url) view.loadUrl(url) },
  )

  BackHandler(enabled = canGoBack) { webView?.goBack() }
}

@Composable
private fun LoadingState(status: String) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    CircularProgressIndicator()
    Text(
      text = "正在启动 dsh 运行时…",
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(top = 16.dp),
    )
    Text(
      text = status,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

@Composable
private fun FailureState(message: String, onRetry: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "dsh Web UI 启动失败", style = MaterialTheme.typography.titleMedium)
    Text(
      text = message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
      modifier = Modifier.padding(top = 8.dp),
    )
    Button(onClick = onRetry, modifier = Modifier.padding(top = 20.dp)) { Text("重试") }
  }
}

private const val TAG = "TappetWeb"
