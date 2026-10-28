package com.zoneu.app.connector

/** 自包含回调页：不引用外部资源、不插入请求参数，不提前宣告令牌已保存。 */
internal fun gmailCallbackPage(denied: Boolean): String {
  val title = if (denied) "授权未完成" else "已收到授权回调"
  val description = if (denied) {
    "此次授权未完成。请返回 zoneu 查看原因，准备好后可以重新连接。"
  } else {
    "浏览器这一步已结束。请返回 zoneu，查看 Gmail 的最终连接结果。"
  }
  return CALLBACK_PAGE.replace("{{title}}", title).replace("{{description}}", description)
}

private val CALLBACK_PAGE =
  """
  <!doctype html>
  <html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="color-scheme" content="light dark">
    <meta name="referrer" content="no-referrer">
    <title>返回 zoneu · Gmail 连接</title>
    <style>
      :root {
        color-scheme: light dark;
        --bg: #f4f5f7; --surface: #fff; --text: #17181c;
        --muted: #6f7683; --line: #e5e7ec; --accent: #4d6bfe; --soft: #e9edfb;
      }
      * { box-sizing: border-box; }
      body {
        margin: 0; min-height: 100vh; min-height: 100svh;
        display: grid; grid-template-columns: minmax(0, 1fr); place-items: center;
        padding: 28px 20px;
        background: var(--bg); color: var(--text);
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", sans-serif;
        -webkit-text-size-adjust: 100%;
      }
      main { width: 100%; max-width: 420px; min-width: 0; overflow-wrap: anywhere; }
      .brand {
        margin: 0 0 24px; text-align: center; font-size: 22px; font-weight: 750; letter-spacing: -.6px;
      }
      .brand span { color: var(--accent); }
      .card {
        padding: 36px 26px 28px; border: 1px solid var(--line); border-radius: 28px;
        background: var(--surface); box-shadow: 0 12px 48px #17181c08; text-align: center;
      }
      .icon {
        display: grid; place-items: center; width: 64px; height: 64px; margin: 0 auto 24px;
        border-radius: 22px; background: var(--soft); color: var(--accent);
      }
      .icon svg { width: 30px; height: 30px; }
      .eyebrow {
        margin: 0 0 10px; font-size: 12px; font-weight: 650; letter-spacing: 2px; color: var(--muted);
      }
      h1 { margin: 0; font-size: clamp(23px, 6vw, 28px); line-height: 1.35; letter-spacing: -.6px; }
      .description { margin: 16px 0 28px; color: var(--muted); font-size: 15px; line-height: 1.8; }
      .next { border-radius: 16px; padding: 18px; background: var(--bg); text-align: left; }
      .next strong { display: block; font-size: 15px; line-height: 1.5; }
      .next p { margin: 6px 0 0; color: var(--muted); font-size: 13px; line-height: 1.7; }
      footer {
        margin: 20px 8px 0; color: var(--muted); font-size: 12px; line-height: 1.8; text-align: center;
      }
      @media (prefers-color-scheme: dark) {
        :root {
          --bg: #111216; --surface: #17181d; --text: #ecedef;
          --muted: #9ba1ac; --line: #31343d; --accent: #8fa4ff; --soft: #2a3149;
        }
      }
      @media (max-height: 540px) { body { padding-block: 20px; } .brand { margin-bottom: 16px; } }
    </style>
  </head>
  <body>
    <main aria-labelledby="title">
      <p class="brand">zoneu<span aria-hidden="true">.</span></p>
      <section class="card">
        <div class="icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
               stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="5" width="18" height="14" rx="3"/>
            <path d="m4 7 8 6 8-6"/>
          </svg>
        </div>
        <p class="eyebrow">GMAIL · 连接</p>
        <h1 id="title">{{title}}</h1>
        <p class="description">{{description}}</p>
        <div class="next">
          <strong>下一步：返回 zoneu</strong>
          <p>从最近任务切回 App，或点开 zoneu 图标。<br>在「设置 → 连接器 → Gmail」查看状态。</p>
        </div>
      </section>
      <footer>可以关闭此标签页，无需刷新。<br>最终是否连接成功，请以 App 内显示为准。</footer>
    </main>
  </body>
  </html>
  """.trimIndent()
