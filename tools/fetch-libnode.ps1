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
foreach ($root in $ndkRoots) {
  $candidate = Join-Path $root "toolchains/llvm/prebuilt/$hostTag/sysroot/usr/lib/$abiDir/libc++_shared.so"
  if (Test-Path -LiteralPath $candidate) { $stl = $candidate; break }
}
if (-not $stl) {
  throw "libc++_shared.so not found（NDK $NdkVersion 装了吗？找过：$($ndkRoots -join ' / ')）"
}
$jniLibs = Join-Path $root "android/app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
Copy-Item -Force $stl $jniLibs

Write-Host "libnode $Version ($Abi) + libc++_shared -> $targetDir / jniLibs"
