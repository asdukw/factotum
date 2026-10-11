import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.isFile) file.inputStream().use { load(it) }
}

fun agentValue(propertyKey: String, environmentKey: String, defaultValue: String): String {
  val environmentValue = System.getenv(environmentKey)
  if (!environmentValue.isNullOrBlank()) return environmentValue
  val localValue = localProperties.getProperty(propertyKey)
  if (!localValue.isNullOrBlank()) return localValue
  return defaultValue
}

fun buildConfigString(value: String): String =
  "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

// 模拟器调试：-Ptappet.abis=x86_64；默认只打真机在用的 arm64-v8a（控制 APK 体积）
val tappetAbis =
  (project.findProperty("tappet.abis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

val agentProxyPort = agentValue("tappet.agent.proxyPort", "TAPPET_AGENT_PROXY_PORT", "7897").toIntOrNull() ?: 7897

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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 内嵌 libnode：默认只带真机的 arm64；模拟器用 -Ptappet.abis=x86_64 覆盖
        ndk {
            abiFilters += tappetAbis
        }

        // 测试阶段固定 Agent 端点；后续设置页会覆盖这些值。
        buildConfigField("String", "AGENT_BASE_URL", buildConfigString(agentValue("tappet.agent.baseUrl", "TAPPET_AGENT_BASE_URL", "https://opencode.ai/zen/go/v1")))
        // AGENT_API_KEY 的**值在 debug 变体里才注入**（见 buildTypes.debug）。这里是 release
        // 及一切其它变体的兜底：永远是空串。
        //
        // 原因是发布审查发现的硬问题：BuildConfig 是编译期常量，会以**明文**进 dex。
        // 实测从 debug APK 的 classes*.dex 里能直接 grep 出完整密钥（isMinifyEnabled=false
        // 连混淆都没有）。只要带密钥的包发出去一次，密钥就等于公开，且没有任何补救余地。
        // 所以「密钥从构建期注入」这件事只能存在于本机自用的 debug 变体；release 必须留空，
        // 由用户自己在运行时提供（设置页仍待做——在此之前 release 包是不可用的，这是刻意的）。
        buildConfigField("String", "AGENT_API_KEY", buildConfigString(""))
        buildConfigField("String", "AGENT_MODEL", buildConfigString(agentValue("tappet.agent.model", "TAPPET_AGENT_MODEL", "deepseek-v4.1-flash")))
        buildConfigField("String", "AGENT_REASONING_EFFORT", buildConfigString(agentValue("tappet.agent.reasoningEffort", "TAPPET_AGENT_REASONING_EFFORT", "low")))
        buildConfigField("String", "AGENT_PROXY_HOST", buildConfigString(agentValue("tappet.agent.proxyHost", "TAPPET_AGENT_PROXY_HOST", "")))
        buildConfigField("int", "AGENT_PROXY_PORT", agentProxyPort.toString())
    }

    buildTypes {
        debug {
            // 只有本机自用的 debug 变体从 local.properties / 环境变量注入密钥（两者都已
            // gitignore）。这个变体永远不进发布物。
            buildConfigField("String", "AGENT_API_KEY", buildConfigString(agentValue("tappet.agent.apiKey", "TAPPET_AGENT_API_KEY", "")))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    androidResources {
      // dsh 运行时已是 zip，别再压一遍（也省构建时间）
      noCompress += "zip"
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
