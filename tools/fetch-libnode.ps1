param(
  [string]$Version = "24.18.0-0",
  [string]$Abi = "arm64-v8a",
  [string]$Repo = "gmaclennan/nodejs-mobile",
  # 代理默认跟随标准的 HTTPS_PROXY / HTTP_PROXY（本机是 127.0.0.1:7897）；
  # 没有这个变量就直连（CI 就是这种情况）。不再写死，否则换台机器 / 上 CI 必挂。
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把 nodejs-mobile 的预编译 libnode.so + 头文件拉到 android/app/libnode/。
# 产物与缓存都在 .gitignore 内，不入库；改版本只改上面的 $Version。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $root "android/.libnode-cache"
$targetDir = Join-Path $root "android/app/libnode"
$zipName = "nodejs-mobile-android-$Version.zip"
$zipPath = Join-Path $cacheDir $zipName
$url = "https://github.com/$Repo/releases/download/v$Version/$zipName"

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

if (-not (Test-Path -LiteralPath $zipPath)) {
  Write-Host "downloading $url$(if ($Proxy) { " (proxy $Proxy)" })"
  $req = @{ Uri = $url; OutFile = $zipPath; Headers = @{ "User-Agent" = "opencode" } }
  if ($Proxy) { $req.Proxy = $Proxy }
  Invoke-WebRequest @req
} else {
  Write-Host "using cached $zipPath"
}

$extractDir = Join-Path $cacheDir "extract-$Version"
if (-not (Test-Path -LiteralPath $extractDir)) {
  Write-Host "extracting..."
  Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force
}

$binOut = Join-Path $targetDir "bin/$Abi"
$includeOut = Join-Path $targetDir "include"
New-Item -ItemType Directory -Force -Path $binOut | Out-Null
Copy-Item -Force (Join-Path $extractDir "bin/$Abi/libnode.so") $binOut
if (-not (Test-Path -LiteralPath $includeOut)) {
  Copy-Item -Recurse -Force (Join-Path $extractDir "include") $targetDir
}

# libnode.so 依赖 NDK 的 libc++_shared.so；AGP 不会自动打包，直接放进 jniLibs。
$NdkVersion = "29.0.14206865"
$abiDir = switch ($Abi) {
  "arm64-v8a" { "aarch64-linux-android" }
  "armeabi-v7a" { "arm-linux-androideabi" }
  "x86_64" { "x86_64-linux-android" }
  default { throw "unsupported abi $Abi" }
}
# NDK 的 host 目录名随平台变（CI 是 linux-x86_64），SDK 位置也不止一种写法 —— 逐个试。
$hostTag =
  if ($IsWindows -or $env:OS -eq "Windows_NT") { "windows-x86_64" }
  elseif ($IsMacOS) { "darwin-x86_64" }
  else { "linux-x86_64" }
$ndkRoots = @()
if ($env:ANDROID_NDK_HOME) { $ndkRoots += $env:ANDROID_NDK_HOME }
foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android/Sdk" }))) {
  if ($sdk) { $ndkRoots += (Join-Path $sdk "ndk/$NdkVersion") }
}
$stl = $null
# 变量名别用 $root：上面那个 $root 是仓库根目录，循环里覆盖掉它，
# 后面 $jniLibs 就会拼到 NDK 目录里去 —— 副本落在错地方，脚本还照样打印成功。
foreach ($ndkRoot in $ndkRoots) {
  $candidate = Join-Path $ndkRoot "toolchains/llvm/prebuilt/$hostTag/sysroot/usr/lib/$abiDir/libc++_shared.so"
  if (Test-Path -LiteralPath $candidate) { $stl = $candidate; break }
}
if (-not $stl) {
  throw "libc++_shared.so not found（NDK $NdkVersion 装了吗？找过：$($ndkRoots -join ' / ')）"
}
$jniLibs = Join-Path $root "android/app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
Copy-Item -Force $stl $jniLibs

# 复制完**当场验一遍落在哪**：这个脚本打印过「成功」而实际把文件复制进了 NDK 目录，
# 根因就是一个循环变量覆盖了 $root。缺了这个自检，症状要到装包之后（dlopen libc++_shared 失败）
# 才现形 —— 与 bundle-dsh 里那条「打完包验头四字节」同一个理由。
$copied = Join-Path $jniLibs "libc++_shared.so"
if (-not (Test-Path -LiteralPath $copied)) {
  throw "libc++_shared.so 没落到 jniLibs：期望在 $copied（实际拷贝源 $stl）"
}
if ((Get-Item -LiteralPath $copied).Length -lt 100KB) {
  throw "libc++_shared.so 太小，像是坏文件：$copied"
}

Write-Host "libnode $Version ($Abi) + libc++_shared -> $targetDir / $jniLibs"
