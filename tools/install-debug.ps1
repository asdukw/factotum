param(
  # 只装不打：APK 已经是想要的那份时用
  [switch]$SkipBuild,
  # 多台设备时指定序列号；默认跟随 ANDROID_SERIAL
  [string]$Serial = $env:ANDROID_SERIAL,
  # 与 fetch-* 同一条规矩：代理跟随 HTTPS_PROXY / HTTP_PROXY，没有就直连
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 本机 debug 包上真机：打包 → 覆盖安装 → 重开无障碍 → 打印设备上的版本。
# AGENTS.md §8.1 的手工步骤固化在这里，省掉每次手敲时的几个坑：
#   - Gradle 的自动安装在 ColorOS 上会挂 ~5 分钟后失败，所以这里只用 `adb install -r`；
#   - `install -r` 会关掉无障碍服务，装完必须重开；
#   - 包名跟随 `zoneu.debugSuffix`，无障碍组件要写**完整类名**（相对写法会拼到带后缀的包名上）；
#   - 签名对不上时给出可操作的原因，而不是一句 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$apk = Join-Path $android "app/build/outputs/apk/debug/app-debug.apk"

$localProps = @{}
$propsFile = Join-Path $android "local.properties"
if (Test-Path -LiteralPath $propsFile) {
  foreach ($line in Get-Content -LiteralPath $propsFile) {
    if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*)$') { $localProps[$Matches[1]] = $Matches[2].Trim() }
  }
}
foreach ($key in 'debugSuffix', 'debugSigning') {
  if (-not $localProps["zoneu.$key"]) { $localProps["zoneu.$key"] = $localProps["factotum.$key"] }
}
$gradle = Get-Content -Raw -LiteralPath (Join-Path $android 'app/build.gradle.kts')
if ($gradle -notmatch 'applicationId\s*=\s*"([^"]+)"') { throw '找不到 applicationId' }
$package = "$($Matches[1])$($localProps['zoneu.debugSuffix'])"
$signing = if ($localProps['zoneu.debugSigning']) { $localProps['zoneu.debugSigning'] } else { "release" }

if (-not $SkipBuild) {
  if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.2.13-hotspot\' }
  if ($Proxy -and -not $env:GRADLE_OPTS) {
    $uri = [Uri]$(if ($Proxy -match '://') { $Proxy } else { "http://$Proxy" })
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port) " +
      "-Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port)"
  }
  Write-Host "building $package (debug signing: $signing)"
  Push-Location $android
  try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed (exit $LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
}
if (-not (Test-Path -LiteralPath $apk)) { throw "APK not found: $apk" }

$adbArgs = if ($Serial) { @("-s", $Serial) } else { @() }
$out = & adb @adbArgs install -r -g $apk 2>&1 | Out-String
if ($out -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
  throw "设备上的 $package 与新包签名不一致（当前 zoneu.debugSigning=$signing）。" +
    "设备上那份若是默认 debug 密钥签的，在 android/local.properties 写 zoneu.debugSigning=debug 后重跑；" +
    "否则只能卸载 —— SQLCipher 库与密钥会永久丢失，先确认可以不要。`n$out"
}
if ($LASTEXITCODE -ne 0) { throw "adb install failed:`n$out" }

# 新旧应用可并存：只追加本应用组件，不覆盖其它已启用的无障碍服务。
$component = "$package/com.zoneu.app.accessibility.ZoneuAccessibilityService"
$enabled = (& adb @adbArgs shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw '读取已启用的无障碍服务失败' }
$services = @($enabled -split ':' | Where-Object { $_ -and $_ -ne 'null' })
if ($services -notcontains $component) { $services += $component }
& adb @adbArgs shell settings put secure enabled_accessibility_services ($services -join ':')
& adb @adbArgs shell settings put secure accessibility_enabled 1
if ($LASTEXITCODE -ne 0) { throw "re-enabling accessibility failed" }

$info = & adb @adbArgs shell dumpsys package $package | Select-String -Pattern 'versionName=|lastUpdateTime=' | Select-Object -First 2
Write-Host "installed $package; accessibility re-enabled"
$info | ForEach-Object { Write-Host "  $($_.Line.Trim())" }
