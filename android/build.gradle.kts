// Top-level build file where you can add configuration options common to all sub-projects/modules.
import io.gitlab.arturbosch.detekt.Detekt

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  // 代码质量门禁：两个都在根工程跑，作用在 app/src 的 Kotlin 源码上
  alias(libs.plugins.detekt)
  alias(libs.plugins.spotless)
}

// 三个工具分工不重叠，CI 与本地跑的是同一套：
//   ktlint（经 Spotless） 格式 —— 能自动改：./gradlew spotlessApply
//   detekt                坏味道 / 复杂度 —— 要人判断
//   Android Lint          Android 专有（API 级别 / 清单 / 资源）—— 在 :app 模块里配
// 本地等价命令：./gradlew check（单测 + lint + 上面两个）

spotless {
  kotlin {
    target("app/src/**/*.kt")
    targetExclude("**/build/**")
    ktlint(libs.versions.ktlint.get()).editorConfigOverride(
      // .editorconfig 里的这条 ktlint_ 属性 Spotless 没往规则里传（试过，不生效），
      // 只能在这里显式给一次。理由见 android/.editorconfig。
      mapOf(
        "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
        // 这三条是 ktlint_official 的排版主张，不是「错误」。本仓库的既有写法
        // 在这三处与它相左，而它们的收益（换个换行位置）抵不过 120 处签名被拆成
        // 一行一个参数的 churn —— 详见 android/.editorconfig。
        "ktlint_standard_function-signature" to "disabled",
        "ktlint_standard_chain-method-continuation" to "disabled",
        "ktlint_standard_multiline-expression-wrapping" to "disabled",
        "ktlint_standard_class-signature" to "disabled",
        "ktlint_standard_when-entry-bracing" to "disabled",
      ),
    )
  }
  kotlinGradle {
    target("*.gradle.kts", "app/*.gradle.kts")
    ktlint(libs.versions.ktlint.get())
  }
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(files("config/detekt/detekt.yml"))
  source.setFrom(files("app/src/main/java", "app/src/test/java", "app/src/androidTest/java"))
  parallel = true
  // 基线是「历史欠账」的快照，不是免检区：它只挡新增。文件不存在就不设 ——
  // 免得第一次跑（或有人删了基线）时 detekt 直接报错，那看着像门禁坏了。
  val baselineFile = file("config/detekt/baseline.xml")
  if (baselineFile.exists()) baseline = baselineFile
}

tasks.withType<Detekt>().configureEach {
  jvmTarget = "17"
  reports {
    // SARIF 交给 GitHub Code Scanning（见 .github/workflows/ci.yml），
    // 结果落在仓库 Security 标签页，不用点进 CI 日志里翻。
    sarif.required.set(true)
  }
}
