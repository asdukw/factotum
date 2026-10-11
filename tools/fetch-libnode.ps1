param(
  [string]$Version = "24.18.0-0",
  [string]$Abi = "arm64-v8a",
  [string]$Repo = "gmaclennan/nodejs-mobile"
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
  Write-Host "downloading $url"
  $proxy = "http://127.0.0.1:7897"
  Invoke-WebRequest -Uri $url -OutFile $zipPath -Proxy $proxy -Headers @{ "User-Agent" = "opencode" }
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
$stl = Join-Path $env:LOCALAPPDATA "Android/Sdk/ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/$abiDir/libc++_shared.so"
if (-not (Test-Path -LiteralPath $stl)) { throw "libc++_shared.so not found: $stl (NDK $NdkVersion 未安装?)" }
$jniLibs = Join-Path $root "android/app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
Copy-Item -Force $stl $jniLibs

Write-Host "libnode $Version ($Abi) + libc++_shared -> $targetDir / jniLibs"
