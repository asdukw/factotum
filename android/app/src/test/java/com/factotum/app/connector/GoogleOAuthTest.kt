package com.factotum.app.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Google OAuth 里**纯函数那一半**的单测：PKCE、授权 URL、回环重定向的请求行解析、令牌解析。
 *
 * 这些形状在真机上出错时表现都很含糊：浏览器跳回来了但界面一直转、或者显示「账号未知」。
 * 放在这里钉住，比在设备上抓 logcat 便宜得多。
 */
class GoogleOAuthTest {
  // --- PKCE -----------------------------------------------------------------

  @Test
  fun `code challenge matches the rfc 7636 appendix b vector`() {
    // RFC 7636 Appendix B 的官方样例。PKCE 是这套流程唯一的密码学保护 ——
    // 它算错了，整个「不用客户端密码」的论证就不成立，而错误的表现只是「换令牌失败」。
    assertEquals(
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
      codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    )
  }

  @Test
  fun `code verifier is url safe and within the rfc length bounds`() {
    val verifier = newCodeVerifier()
    // RFC 7636 §4.1：43–128 个字符，字符集是 ALPHA / DIGIT / "-" / "." / "_" / "~"。
    assertTrue("长度应在 43–128，实际 ${verifier.length}", verifier.length in 43..128)
    assertTrue("必须是 URL 安全字符集", verifier.all { it.isLetterOrDigit() || it in "-._~" })
    // 每次都要不一样，否则 PKCE 就退化成常量了。
    assertTrue(verifier != newCodeVerifier())
  }

  // --- 授权 URL -------------------------------------------------------------

  @Test
  fun `authorization url carries pkce and the offline consent flags`() {
    val url = authorizationUrl("cid.apps.googleusercontent.com", "http://127.0.0.1:1234", "CH", "ST")
    assertTrue(url.startsWith(GOOGLE_AUTH_URL))
    assertTrue(url.contains("client_id=cid.apps.googleusercontent.com"))
    assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A1234"))
    assertTrue(url.contains("code_challenge=CH"))
    assertTrue(url.contains("code_challenge_method=S256"))
    assertTrue(url.contains("state=ST"))
    // 这两个是**拿到 refresh token 的前提**：少了 offline 只在首次授权给，
    // 少了 consent 则已同意过的用户会被直接跳过同意页、拿不到新的 refresh token。
    assertTrue("必须有 access_type=offline", url.contains("access_type=offline"))
    assertTrue("必须有 prompt=consent", url.contains("prompt=consent"))
  }

  @Test
  fun `the requested scope is gmail modify, not full access`() {
    // **申请的是 gmail.modify，而工具面仍然是只读的** —— 这个「授权比能力宽」是有意的：
    // Google 对 installed apps 不支持增量授权，扩权要所有已连接用户重走同意页，
    // 所以一次要齐（理由与代价见 docs/email-connector.md §1.1）。
    assertTrue(GMAIL_SCOPE.contains("https://www.googleapis.com/auth/gmail.modify"))
    // gmail.modify 本身已含发送（Google 的描述是「Read, compose, and send emails」），
    // 所以不需要再单独申请 gmail.send。
    assertTrue("gmail.modify 已含发送，不必再要 gmail.send", !GMAIL_SCOPE.contains("gmail.send"))
    // 但**不能**要 https://mail.google.com/ —— 那个额外含「绕过回收站永久删除」。
    assertTrue("不该申请 https://mail.google.com/", !GMAIL_SCOPE.contains("https://mail.google.com/"))
    for (forbidden in listOf("gmail.settings.basic", "gmail.settings.sharing", "gmail.insert")) {
      assertTrue("不该申请 $forbidden", !GMAIL_SCOPE.contains(forbidden))
    }
    // 只读的保证现在只在插件那一侧（出网全是 GET）。这条断言是提醒：
    // 边界从「Google 兜」变成了「我们自己的代码兜」，别把它当免费的。
    assertTrue(GMAIL_SCOPE.contains("openid"))
  }

  // --- 回环重定向 -----------------------------------------------------------

  @Test
  fun `parses the redirect request line`() {
    val params = parseRedirectRequest("GET /?code=4/0AY0e&state=abc HTTP/1.1")
    assertEquals("4/0AY0e", params?.code)
    assertEquals("abc", params?.state)
    assertNull(params?.error)
  }

  @Test
  fun `parses an error redirect with its description`() {
    // 用户在授权页点了拒绝：Google 回的是 error，不是 code。
    val params = parseRedirectRequest("GET /?error=access_denied&state=abc HTTP/1.1")
    assertNull(params?.code)
    assertEquals("access_denied", params?.error)

    val described =
      parseRedirectRequest("GET /?error=invalid_request&error_description=Missing+required+parameter HTTP/1.1")
    assertEquals("invalid_request", described?.error)
    assertEquals("Missing required parameter", described?.errorDescription)
  }

  @Test
  fun `percent decodes the redirect parameters`() {
    // 授权码里会出现 / 与 = 这类字符，浏览器会把它们转义。
    val params = parseRedirectRequest("GET /?code=4%2F0AY0e-g7X%2Bq%3D%3D&state=a%20b HTTP/1.1")
    assertEquals("4/0AY0e-g7X+q==", params?.code)
    assertEquals("a b", params?.state)
  }

  @Test
  fun `ignores requests that are not the redirect`() {
    // 浏览器还会发 /favicon.ico 之类；解析不出来就该返回 null 让监听器继续等。
    assertNull(parseRedirectRequest("GET /favicon.ico HTTP/1.1"))
    assertNull(parseRedirectRequest("GET / HTTP/1.1"))
    assertNull(parseRedirectRequest("POST /?code=x HTTP/1.1"))
    assertNull(parseRedirectRequest("garbage"))
  }

  // --- 令牌 -----------------------------------------------------------------

  @Test
  fun `parses a granted token`() {
    val outcome =
      parseTokenResponse("""{"access_token":"at","refresh_token":"rt","expires_in":3599,"token_type":"Bearer"}""")
    val granted = outcome as TokenOutcome.Granted
    assertEquals("at", granted.accessToken)
    assertEquals("rt", granted.refreshToken)
    assertEquals(3599L, granted.expiresInSeconds)
  }

  @Test
  fun `a refresh response without a new refresh token keeps it null so the caller can reuse the old one`() {
    // RFC 6749 §6：刷新响应可以不返回新的 refresh_token。返回 null 而不是空串，
    // 是为了让调用方走「沿用旧的」这条分支 —— 空串会被当成「拿到了一个空的」。
    val outcome = parseTokenResponse("""{"access_token":"at","expires_in":3599}""")
    assertNull((outcome as TokenOutcome.Granted).refreshToken)
  }

  @Test
  fun `hard errors carry the oauth code and the endpoint description`() {
    // 描述必须带上：真机上第一轮卡在一个没有映射的 invalid_request 上，而当时既没日志
    // 也没描述，只能靠猜（docs/email-connector.md §6）。
    assertEquals(
      TokenOutcome.Denied("invalid_grant", "Token has been expired or revoked."),
      parseTokenResponse("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""),
    )
    assertEquals(TokenOutcome.Denied("invalid_response"), parseTokenResponse("<html>502</html>"))
  }

  @Test
  fun `missing expires_in falls back to one hour`() {
    val granted = parseTokenResponse("""{"access_token":"at"}""") as TokenOutcome.Granted
    assertEquals(3600L, granted.expiresInSeconds)
  }

  // --- 账号标识 -------------------------------------------------------------

  @Test
  fun `reads the account from the id token claims`() {
    val outcome = parseTokenResponse("""{"access_token":"at","id_token":"${jwt("""{"email":"a@gmail.com"}""")}"}""")
    assertEquals("a@gmail.com", (outcome as TokenOutcome.Granted).account)
  }

  @Test
  fun `an undecodable id token yields null rather than a wrong address`() {
    assertNull(parseIdTokenAccount("not-a-jwt"))
    assertNull(parseIdTokenAccount(jwt("""{"aud":"x"}""")))
  }

  // --- 表单 -----------------------------------------------------------------

  @Test
  fun `url encoding handles the characters that actually show up`() {
    assertEquals("a+b", urlEncode("a b"))
    // scope 里的 `/` 与 `:`、PKCE 里的 `-` `_` 是历史上最容易出偏差的几样。
    assertEquals(
      "https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly",
      urlEncode("https://www.googleapis.com/auth/gmail.readonly"),
    )
    assertEquals("-._~", urlEncode("-._~"))
    assertEquals("%E9%82%AE%E4%BB%B6", urlEncode("邮件"))
  }

  @Test
  fun `the exchange form carries the pkce verifier and the same redirect uri`() {
    val form = codeExchangeForm("cid", "GOCSPX-x", "the-code", "the-verifier", "http://127.0.0.1:9")
    assertTrue(form.contains("grant_type=authorization_code"))
    assertTrue(form.contains("code=the-code"))
    assertTrue(form.contains("code_verifier=the-verifier"))
    // redirect_uri 必须与授权时**逐字相同**，否则端点回 redirect_uri_mismatch。
    assertTrue(form.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A9"))
  }

  @Test
  fun `the client secret is sent because the token endpoint demands it`() {
    // Google 的文档把这一项标成「Optional」，而令牌端点实际会回 `client_secret is missing`
    // （真机上就是这么栽的）。它不是机密（公共客户端），但**必须发**。
    val form = codeExchangeForm("cid", "GOCSPX-abc", "code", "verifier", "http://127.0.0.1:9")
    assertTrue(form.contains("client_secret=GOCSPX-abc"))
    val refresh = refreshTokenForm("cid", "GOCSPX-abc", "rt")
    assertTrue(refresh.contains("client_secret=GOCSPX-abc"))
  }

  @Test
  fun `an empty client secret omits the parameter instead of sending it blank`() {
    // 空串与「没有这一项」在端点上不是一回事：`client_secret=` 会让 Google 认为给了个空密码。
    val form = codeExchangeForm("cid", "", "code", "verifier", "http://127.0.0.1:9")
    assertTrue("空密码应整项略去", !form.contains("client_secret"))
    assertTrue("空密码不该留下裸的等号", !form.contains("client_secret="))
  }

  @Test
  fun `the refresh form uses the refresh grant`() {
    val form = refreshTokenForm("cid", "GOCSPX-x", "rt")
    assertTrue(form.contains("grant_type=refresh_token"))
    assertTrue(form.contains("refresh_token=rt"))
  }

  @Test
  fun `the preset client secret belongs to gmail only`() {
    assertEquals(DEFAULT_GMAIL_CLIENT_SECRET, defaultClientSecret(GMAIL_CONNECTOR_ID))
    assertEquals("", defaultClientSecret("outlook"))
  }

  // --- 预置默认值 -----------------------------------------------------------

  @Test
  fun `the preset client id belongs to gmail only`() {
    // 每个连接器各有各的应用注册，不能互相借用 —— 借错了的表现是「授权页上写着别人家的
    // 应用名」，用户会以为中招了。
    assertEquals(DEFAULT_GMAIL_CLIENT_ID, defaultClientId(GMAIL_CONNECTOR_ID))
    assertEquals("", defaultClientId("outlook"))
  }

  private fun jwt(payload: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(Charsets.UTF_8))
    val body = encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
    return "$header.$body."
  }
}
