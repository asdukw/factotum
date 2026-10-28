package com.zoneu.app.connector

import com.zoneu.app.BuildConfig

/**
 * 连接器的 id 与**预置的 OAuth 客户端 id**。
 *
 * ## id 是存储身份
 *
 * [GMAIL_CONNECTOR_ID] 会拼进非密 prefs 的键（`<id>_client_id` / `<id>_account`）、
 * SecretVault 的 prefsName（`zoneu_connector_secret-<id>`）与 Keystore 别名
 * （`zoneu-connector-<id>-v1`）。**改它 = 设备上已完成的授权静默失效**，表现是
 * 「设置页说连过，但工具全报未连接」。与包名 / 数据库名 / 提供商 id 同一类红线。
 *
 * ## 为什么客户端 id 可以烧进包里
 *
 * 它是**公开标识**，不是秘密：Google 对桌面应用明说「桌面应用是公共客户端，保不住秘密」，
 * 使用 PKCE 验证授权码归属（见 `docs/email-connector.md` §4.1）。所以预置它**不违反**
 * `AGENTS.md` 那条「密钥永不入库、不进构建产物」的红线 —— 预置的是 id，不是密钥，
 * 配套 client secret 同样是公共客户端标识；邮箱 refresh token 不随包分发。
 *
 * 对照 LLM 的 API key 就清楚了：那个一旦能被编进包，「本机构建的包不能发出去」就只是一条
 * 要人记住的规矩，而不是结构性的事实；客户端 id 没有这个问题。
 *
 * ## 值从哪来（**不入库**）
 *
 * 这两个值由**构建期注入**：本地读 `android/local.properties` 的
 * `zoneu.connector.gmailClientId` / `zoneu.connector.gmailClientSecret`，CI 读同名环境变量
 * （发布时由 GitHub Actions secret 提供）。源码树里没有它们 —— GitHub 的 push protection
 * 把这两个值都判成密钥（对管理员一样拦），而且它们本来就只该出现在发布产物里。
 *
 * ## 留空的行为
 *
 * 注入为空 = 没有预置，设置页退回「要求用户自己填 client id / 密码」的行为。
 * 注入之后，新装用户打开设置页就能直接点「连接」。
 */
internal const val GMAIL_CONNECTOR_ID = "gmail"

/**
 * Google Cloud → Google Auth Platform → Clients → OAuth 客户端 ID，类型**桌面应用**。
 * 见 `docs/email-connector.md` §4.2。
 *
 * **它是公开标识，不是秘密**：桌面应用类型不绑签名指纹，这个 id 会随 APK 分发给每个安装，
 * 谁拿到都能用它发起授权（配额与同意屏幕状态记在这个项目名下）。这正是「一个 id 焊死
 * 给所有人」的代价，不是疏漏；配套客户端密码同样只由构建注入，不在源码中硬编码。
 */
internal val DEFAULT_GMAIL_CLIENT_ID: String = BuildConfig.GMAIL_CLIENT_ID

/**
 * 配套的客户端密码（`GOCSPX-…`）。
 *
 * **它不是机密** —— Google 对桌面应用的原文是「Desktop apps are public clients and cannot
 * keep secrets confidential」，真正的保护是 PKCE。但它**必须发**：文档把这一项标成
 * 「Optional」，而令牌端点实际会回 `client_secret is missing`（真机上就是这么栽的）。
 * Google 自家 `google-auth-oauthlib` 的 installed-app 流程也照发不误。
 *
 * 别人拿到它拿不到任何人的邮件，最多蹭这个项目的配额与同意屏幕 —— 与 client id 同性质。
 * `tools/check-sensitive.ps1` 不会命中它（那里的 Google 模式是 API key 的 `AIza…`），
 * 这是**有意不拦**：把它当密钥拦下来，就等于把「公共客户端的公开标识」误判成泄漏。
 */
internal val DEFAULT_GMAIL_CLIENT_SECRET: String = BuildConfig.GMAIL_CLIENT_SECRET

private val DEFAULT_CLIENT_IDS = mapOf(GMAIL_CONNECTOR_ID to DEFAULT_GMAIL_CLIENT_ID)

private val DEFAULT_CLIENT_SECRETS = mapOf(GMAIL_CONNECTOR_ID to DEFAULT_GMAIL_CLIENT_SECRET)

/**
 * 某个连接器的预置 id。**每个连接器各有各的注册，不能互相借用** —— 借错了的表现是
 * 「授权页上写着别人家的应用名」，用户会以为中招了。
 */
internal fun defaultClientId(connectorId: String): String = DEFAULT_CLIENT_IDS[connectorId].orEmpty()

/** 配套的预置密码；没有就是空串（自定义客户端的用户自己填）。 */
internal fun defaultClientSecret(connectorId: String): String =
  DEFAULT_CLIENT_SECRETS[connectorId].orEmpty()
