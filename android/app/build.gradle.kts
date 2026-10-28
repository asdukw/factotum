import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val localProperties =
  Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
  }

// 兼容旧本机配置；新名称始终优先，不改写私有配置文件。
fun localValue(key: String): String? =
  localProperties.getProperty(key)?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty(key.replaceFirst("zoneu.", "factotum."))?.takeIf { it.isNotBlank() }

fun environmentValue(key: String): String? =
  System.getenv(key)?.takeIf { it.isNotBlank() }
    ?: System.getenv(key.replaceFirst("ZONEU_", "FACTOTUM_"))?.takeIf { it.isNotBlank() }

fun agentValue(
  propertyKey: String,
  environmentKey: String,
  defaultValue: String,
): String {
  val environmentValue = environmentValue(environmentKey)
  if (!environmentValue.isNullOrBlank()) return environmentValue
  val localValue = localValue(propertyKey)
  if (!localValue.isNullOrBlank()) return localValue
  return defaultValue
}

fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

/**
 * 并行 worktree 的 debug 包用不同 applicationId，避免两个会话的 `adb install` 互相覆盖。
 *
 * 在 `android/local.properties` 里写（该文件在 .gitignore 内，每台机器/每个 worktree 各自一份）：
 *
 * ```
 * zoneu.debugSuffix=.foamy
 * ```
 *
 * 产物变成 `com.zoneu.app.foamy`，与正式包并存；数据、密钥、无障碍授权都是另一份。
 * 留空 = 与发布包相同的身份（默认，CI 与普通开发不受影响）。
 */
val debugSuffix =
  localValue("zoneu.debugSuffix")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also {
      require(Regex("^\\.[a-z][a-z0-9_]*$").matches(it)) {
        "zoneu.debugSuffix 必须形如 .foamy（小写字母开头，只含小写字母/数字/下划线）"
      }
    }

/**
 * 本机 debug 包用哪把签名。`adb install -r` 要求新包与设备上已装的包**签名一致**，
 * 否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载 —— SQLCipher 库与密钥随之永久丢失。
 *
 * ```
 * zoneu.debugSigning=debug
 * ```
 *
 * - `release`（默认）：有 release keystore 时 debug 包也用它签（见下面 buildTypes.debug）。
 * - `debug`：用 AGP 默认的 `~/.android/debug.keystore`。设备上那份是这把钥匙签的时候用它
 *   （从没有 release keystore 的机器或 worktree 打出来的包就是这把）。
 */
val debugSigning =
  (localValue("zoneu.debugSigning")?.trim()?.takeIf { it.isNotEmpty() } ?: "release")
    .also {
      require(it == "release" || it == "debug") { "zoneu.debugSigning 只能是 release 或 debug" }
    }

// 模拟器调试：-Pzoneu.abis=x86_64；默认只打真机在用的 arm64-v8a（控制 APK 体积）
val zoneuAbis =
  ((project.findProperty("zoneu.abis") ?: project.findProperty("factotum.abis")) as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

val agentProxyPort = agentValue("zoneu.agent.proxyPort", "ZONEU_AGENT_PROXY_PORT", "7897").toIntOrNull() ?: 7897

// --- 发布签名 ---
// keystore 与口令都不入库：本机读 android/local.properties（gitignored，键名见 AGENTS.md），
// CI 读同名环境变量（GitHub secret 注入）。两处都没有时 **release 照常构建，只是不打签名** ——
// 未签名包装不上，这个后果自己会说话；比起一个「必须先配签名」的构建错误，它更容易排查。
val releaseSigning =
  mapOf(
    "storeFile" to "ZONEU_KEYSTORE_FILE",
    "storePassword" to "ZONEU_KEYSTORE_PASSWORD",
    "keyAlias" to "ZONEU_KEY_ALIAS",
    "keyPassword" to "ZONEU_KEY_PASSWORD",
  ).mapValues { (prop, env) ->
    environmentValue(env) ?: localValue("zoneu.release.$prop")
  }
val releaseStoreFile = releaseSigning["storeFile"]?.let { File(it) }?.takeIf { it.isFile }

android {
  namespace = "com.zoneu.app"
  compileSdk = 37
  ndkVersion = "29.0.14206865"
  defaultConfig {
    // applicationId 是**设备上的应用身份**（uid）。改它 = 设备上变成另一个 App，
    // 旧包的库立刻读不到 —— SQLCipher 口令由 Keystore 按 uid 封装、不可导出。
    // 存储身份共四处，必须一起改：本行 / ZoneuDatabaseProvider.DATABASE_NAME /
    // PassphraseVault 的 prefsName 与 keyAlias。
    applicationId = "com.zoneu.app"
    minSdk = 28
    targetSdk = 36
    versionCode = 19
    versionName = "2.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 内嵌 libnode：默认只带真机的 arm64；模拟器用 -Pzoneu.abis=x86_64 覆盖
    ndk {
      abiFilters += zoneuAbis
    }

    // 这些是**默认值**：设置页（ui/settings）存的值优先级更高，见 AgentConfigStore。
    // 发布物装到别人手机上时，这里每一项都必须能空着跑。
    buildConfigField("String", "AGENT_BASE_URL", buildConfigString(agentValue("zoneu.agent.baseUrl", "ZONEU_AGENT_BASE_URL", "https://opencode.ai/zen/go/v1")))
    // **这里没有 AGENT_API_KEY，是刻意的**：BuildConfig 是编译期常量、会以明文进 dex
    // （isMinifyEnabled=false，连混淆都没有 —— 实测能从 classes*.dex 里直接 grep 出密钥）。
    // 只要密钥**能**被编进包，「本机构建的包不能发出去」就永远是一条要人记住的规矩，
    // 而不是结构性的事实。密钥现在只有一个来源：设置页 → SecretVault（Keystore 封装）。
    // 要发包之前仍建议跑一遍 tools/check-sensitive.ps1 -ApkPath <包> 兜底。
    buildConfigField("String", "AGENT_MODEL", buildConfigString(agentValue("zoneu.agent.model", "ZONEU_AGENT_MODEL", "deepseek-v4.1-flash")))
    buildConfigField("String", "AGENT_REASONING_EFFORT", buildConfigString(agentValue("zoneu.agent.reasoningEffort", "ZONEU_AGENT_REASONING_EFFORT", "low")))
    // 连接器的 OAuth 客户端凭据：**构建期注入，不入库**。
    // GitHub 的 push protection 把这两个值都判成密钥（对管理员一样拦），而且它们本来就
    // 只该出现在**发布产物**里、不该出现在源码树里。发布由 CI 从 GitHub Actions secret 注入，
    // 本地开发放 android/local.properties（gitignored）。两处都没有 → 空串，
    // 设置页退回「要求用户自己填客户端 ID / 密码」的行为。
    // 这不是密钥泄漏防护（它们不是机密，见 docs/email-connector.md §4.1b），
    // 是**别让仓库和平台规则对着干**：值与源码分开，谁构建谁负责注入。
    buildConfigField("String", "GMAIL_CLIENT_ID", buildConfigString(agentValue("zoneu.connector.gmailClientId", "ZONEU_GMAIL_CLIENT_ID", "")))
    buildConfigField("String", "GMAIL_CLIENT_SECRET", buildConfigString(agentValue("zoneu.connector.gmailClientSecret", "ZONEU_GMAIL_CLIENT_SECRET", "")))
    buildConfigField("String", "AGENT_PROXY_HOST", buildConfigString(agentValue("zoneu.agent.proxyHost", "ZONEU_AGENT_PROXY_HOST", "")))
    buildConfigField("int", "AGENT_PROXY_PORT", agentProxyPort.toString())
  }

  signingConfigs {
    // 只有真的拿到 keystore 才建这个配置；否则 release 走未签名（本机没配时也能出包看体积）
    if (releaseStoreFile != null) {
      create("release") {
        storeFile = releaseStoreFile
        storePassword = releaseSigning["storePassword"]
        keyAlias = releaseSigning["keyAlias"]
        keyPassword = releaseSigning["keyPassword"]
      }
    }
  }
  buildTypes {
    debug {
      // 并行 worktree：独立的 applicationId，装在同一台设备上互不覆盖（见文件顶部的 debugSuffix）。
      if (debugSuffix != null) applicationIdSuffix = debugSuffix
      // 默认本机 debug 包也用 release 签名：设备上只保留**一个签名身份**，
      // 于是 debug ↔ release 可以互相覆盖安装（Android 要求更新包签名与已装版本一致）。
      // `zoneu.debugSigning=debug` 时不碰它，留给 AGP 用默认 debug 密钥（见文件顶部）。
      //
      // **必须这样写（`?.let`）而不是直接赋值**：没有 release keystore 时 findByName 返回 null，
      // 直接赋值等于把 debug 变体的签名显式设成 null —— AGP 就不签了，产物变成
      // `app-debug-unsigned.apk`，装不上。贡献者机器与 CI 正是这种情况（assemble.yml 抓到过）。
      if (debugSigning == "release") signingConfigs.findByName("release")?.let { signingConfig = it }
    }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
    aidl = false
    shaders = false
  }

  externalNativeBuild {
    cmake {
      path = file("CMakeLists.txt")
      version = "3.31.6"
    }
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    jniLibs {
      // 让原生库**解压**到 nativeLibraryDir，而不是留在 APK 里被 mmap。
      // 必须这样：Android 10 起 targetSdk >= 29 的应用受 W^X 限制，不能执行自己可写
      // 目录里的文件，而唯一能执行的地方就是 APK 的原生库目录 —— 不解压出来那目录是空的。
      // 代价：libnode.so(64MB) 与 libc++_shared.so(9MB) 也会一起解，安装后多占约 77 MB。
      useLegacyPackaging = true
      // librg.so 不是共享库，是 fetch-rg.ps1 放进去的可执行文件（见 manifest 的
      // extractNativeLibs="true"）。不加这条 AGP 会拿 llvm-strip 去处理它。
      keepDebugSymbols += "**/librg.so"
      // 同理：proot 与它的 loader 也是可执行文件（fetch-proot.ps1 放进来的），
      // 被 strip 处理过的 loader 起不来。
      keepDebugSymbols += "**/libproot.so"
      keepDebugSymbols += "**/libprootloader.so"
    }
  }

  androidResources {
    // dsh 运行时已是 zip，别再压一遍（也省构建时间）
    noCompress += "zip"
    // Debian rootfs 是 gzip（27MB，本身压不动），别再 deflate 一遍。
    // **扩展名必须是 .bin**：aapt2 会把 .gz 资产解压并把后缀去掉（变成 proot-rootfs.tar），
    // 运行时按 .gz 名字 open 会直接 FileNotFoundException。
    noCompress += "bin"
  }

  lint {
    // 分工：错误挡 CI，警告只进报告。几十条警告里多数是「有新版本可用」这类提示，
    // 把它们升成错误等于逼着每次上游发版都改一遍构建脚本。
    abortOnError = true
    warningsAsErrors = false
    // 逐条取舍与理由见 app/lint.xml
    lintConfig = file("lint.xml")
    // SARIF 交给 GitHub Code Scanning（见 .github/workflows/ci.yml），
    // 结果落在仓库 Security 标签页，不用点进 CI 日志里翻。
    sarifReport = true
  }
}

kotlin {
  jvmToolchain(17)
}

// Room 导出 schema，供后续迁移比对
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.generateKotlin", "true")
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.commons.compress)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Chat / Agent HTTP + streaming
  implementation(libs.okhttp)

  // Markdown 渲染（助手正文 / 思考过程）：GFM 表格、任务列表、代码高亮。
  // 解析在库内用 LaunchedEffect + conflate 异步完成，流式返回时不阻塞组合。
  implementation(libs.multiplatform.markdown.renderer.m3)
  implementation(libs.multiplatform.markdown.renderer.code)
  implementation(libs.kotlinx.serialization.json)

  // 加密存储（SQLCipher）
  implementation(libs.sqlcipher.android)
  implementation(libs.androidx.sqlite)

  // Room：会话 / 消息 / 工具调用持久化，底层走 SQLCipher
  implementation(libs.androidx.room.runtime)
  ksp(libs.androidx.room.compiler)
  androidTestImplementation(libs.androidx.room.testing)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
