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

fun agentValue(
  propertyKey: String,
  environmentKey: String,
  defaultValue: String,
): String {
  val environmentValue = System.getenv(environmentKey)
  if (!environmentValue.isNullOrBlank()) return environmentValue
  val localValue = localProperties.getProperty(propertyKey)
  if (!localValue.isNullOrBlank()) return localValue
  return defaultValue
}

fun buildConfigString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

// 模拟器调试：-Ptappet.abis=x86_64；默认只打真机在用的 arm64-v8a（控制 APK 体积）
val tappetAbis =
  (project.findProperty("tappet.abis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

val agentProxyPort = agentValue("tappet.agent.proxyPort", "TAPPET_AGENT_PROXY_PORT", "7897").toIntOrNull() ?: 7897

// --- 发布签名 ---
// keystore 与口令都不入库：本机读 android/local.properties（gitignored，键名见 AGENTS.md），
// CI 读同名环境变量（GitHub secret 注入）。两处都没有时 **release 照常构建，只是不打签名** ——
// 未签名包装不上，这个后果自己会说话；比起一个「必须先配签名」的构建错误，它更容易排查。
val releaseSigning =
  mapOf(
    "storeFile" to "TAPPET_KEYSTORE_FILE",
    "storePassword" to "TAPPET_KEYSTORE_PASSWORD",
    "keyAlias" to "TAPPET_KEY_ALIAS",
    "keyPassword" to "TAPPET_KEY_PASSWORD",
  ).mapValues { (prop, env) ->
    System.getenv(env)?.takeIf { it.isNotBlank() }
      ?: localProperties.getProperty("tappet.release.$prop")?.takeIf { it.isNotBlank() }
  }
val releaseStoreFile = releaseSigning["storeFile"]?.let { File(it) }?.takeIf { it.isFile }

android {
  namespace = "com.tappet.app"
  compileSdk = 36
  ndkVersion = "29.0.14206865"
  defaultConfig {
    // applicationId 是**设备上的应用身份**（uid）。改它 = 设备上变成另一个 App，
    // 旧包的库立刻读不到 —— SQLCipher 口令由 Keystore 按 uid 封装、不可导出。
    // 存储身份共四处，必须一起改：本行 / TappetDatabaseProvider.DATABASE_NAME /
    // PassphraseVault 的 prefsName 与 keyAlias。
    applicationId = "com.tappet.app"
    minSdk = 28
    targetSdk = 36
    versionCode = 12
    versionName = "0.11.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // 内嵌 libnode：默认只带真机的 arm64；模拟器用 -Ptappet.abis=x86_64 覆盖
    ndk {
      abiFilters += tappetAbis
    }

    // 这些是**默认值**：设置页（ui/settings）存的值优先级更高，见 AgentConfigStore。
    // 发布物装到别人手机上时，这里每一项都必须能空着跑。
    buildConfigField("String", "AGENT_BASE_URL", buildConfigString(agentValue("tappet.agent.baseUrl", "TAPPET_AGENT_BASE_URL", "https://opencode.ai/zen/go/v1")))
    // **这里没有 AGENT_API_KEY，是刻意的**：BuildConfig 是编译期常量、会以明文进 dex
    // （isMinifyEnabled=false，连混淆都没有 —— 实测能从 classes*.dex 里直接 grep 出密钥）。
    // 只要密钥**能**被编进包，「本机构建的包不能发出去」就永远是一条要人记住的规矩，
    // 而不是结构性的事实。密钥现在只有一个来源：设置页 → SecretVault（Keystore 封装）。
    // 要发包之前仍建议跑一遍 tools/check-sensitive.ps1 -ApkPath <包> 兜底。
    buildConfigField("String", "AGENT_MODEL", buildConfigString(agentValue("tappet.agent.model", "TAPPET_AGENT_MODEL", "deepseek-v4.1-flash")))
    buildConfigField("String", "AGENT_REASONING_EFFORT", buildConfigString(agentValue("tappet.agent.reasoningEffort", "TAPPET_AGENT_REASONING_EFFORT", "low")))
    // 连接器的 OAuth 客户端凭据：**构建期注入，不入库**。
    // GitHub 的 push protection 把这两个值都判成密钥（对管理员一样拦），而且它们本来就
    // 只该出现在**发布产物**里、不该出现在源码树里。发布由 CI 从 GitHub Actions secret 注入，
    // 本地开发放 android/local.properties（gitignored）。两处都没有 → 空串，
    // 设置页退回「要求用户自己填客户端 ID / 密码」的行为。
    // 这不是密钥泄漏防护（它们不是机密，见 docs/email-connector.md §4.1b），
    // 是**别让仓库和平台规则对着干**：值与源码分开，谁构建谁负责注入。
    buildConfigField("String", "GMAIL_CLIENT_ID", buildConfigString(agentValue("tappet.connector.gmailClientId", "TAPPET_GMAIL_CLIENT_ID", "")))
    buildConfigField("String", "GMAIL_CLIENT_SECRET", buildConfigString(agentValue("tappet.connector.gmailClientSecret", "TAPPET_GMAIL_CLIENT_SECRET", "")))
    buildConfigField("String", "AGENT_PROXY_HOST", buildConfigString(agentValue("tappet.agent.proxyHost", "TAPPET_AGENT_PROXY_HOST", "")))
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
      // 本机 debug 包也用 release 签名：设备上只保留**一个签名身份**，
      // 于是 debug ↔ release 可以互相覆盖安装（Android 要求更新包签名与已装版本一致）。
      //
      // **必须这样写（`?.let`）而不是直接赋值**：没有 release keystore 时 findByName 返回 null，
      // 直接赋值等于把 debug 变体的签名显式设成 null —— AGP 就不签了，产物变成
      // `app-debug-unsigned.apk`，装不上。贡献者机器与 CI 正是这种情况（assemble.yml 抓到过）。
      signingConfigs.findByName("release")?.let { signingConfig = it }
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
    }
  }

  androidResources {
    // dsh 运行时已是 zip，别再压一遍（也省构建时间）
    noCompress += "zip"
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
