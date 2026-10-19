param(
  [string]$Version = "1.13.8",
  [string]$Abi = "arm64-v8a",
  [string]$Repo = "k2-fsa/sherpa-onnx",
  # 与 fetch-libnode 同一条规矩：代理跟随标准的 HTTPS_PROXY / HTTP_PROXY，
  # 没有就直连（CI 就是这种情况）。写死会让换机器 / 上 CI 必挂。
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把 sherpa-onnx 的预编译 Android 库拉到 android/app/src/main/jniLibs/<abi>/。
#
# 用 **static-link-onnxruntime** 那个变体，不是默认的 `sherpa-onnx-<ver>-android.tar.bz2`：
#   - 静态变体：单个 libsherpa-onnx-jni.so，arm64 约 24MB
#   - 默认变体：jni + c-api + cxx-api + libonnxruntime.so 四个，arm64 合计约 32MB
# 静态那份还省掉「四个 .so 之间版本必须配套」这一层。实测它不依赖 libc++_shared.so
# （动态段里根本没有这个名字），所以 libnode 那份 STL 与它无关。
#
# 产物与缓存在 .gitignore 内，不入库；改版本只改上面的 $Version。
# 注意 Kotlin 侧是**手工 vendor** 的 `com/k2fsa/sherpa/onnx/Tts.kt`（取自同一个 tag）——
# 升版本时两者要一起升，JNI 读的是 Kotlin 对象的字段名，对不上会崩在 native 层。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $root "android/.sherpa-cache"
$asset = "sherpa-onnx-v$Version-android-static-link-onnxruntime.tar.bz2"
$archive = Join-Path $cacheDir $asset
$url = "https://github.com/$Repo/releases/download/v$Version/$asset"

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

if (-not (Test-Path -LiteralPath $archive)) {
  Write-Host "downloading $url$(if ($Proxy) { " (proxy $Proxy)" })"
  $req = @{ Uri = $url; OutFile = $archive; Headers = @{ "User-Agent" = "opencode" } }
  if ($Proxy) { $req.Proxy = $Proxy }
  Invoke-WebRequest @req
} else {
  Write-Host "using cached $archive"
}

$extractDir = Join-Path $cacheDir "extract-$Version"
$soPath = Join-Path $extractDir "jniLibs/$Abi/libsherpa-onnx-jni.so"
if (-not (Test-Path -LiteralPath $soPath)) {
  Write-Host "extracting..."
  New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
  # tar.bz2：Windows 10+ 自带 bsdtar（tar.exe），Linux / macOS 也都有 tar。
  # 不用 Expand-Archive —— 它只认 zip。
  & tar -xjf $archive -C $extractDir
  if ($LASTEXITCODE -ne 0) { throw "tar 解包失败：$archive" }
}

$jniLibs = Join-Path $root "android/app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
$dest = Join-Path $jniLibs "libsherpa-onnx-jni.so"
Copy-Item -Force $soPath $dest

# 复制完**当场验一遍落在哪**：这个仓库已经栽过一次「脚本打印成功、文件其实落在别处」
# （fetch-libnode 里那个循环变量覆盖 $root），缺了自检要到装包之后才现形。
if (-not (Test-Path -LiteralPath $dest)) {
  throw "libsherpa-onnx-jni.so 没落到 jniLibs：期望在 $dest（拷贝源 $soPath）"
}
if ((Get-Item -LiteralPath $dest).Length -lt 1MB) {
  throw "libsherpa-onnx-jni.so 太小，像是坏文件：$dest"
}

Write-Host "sherpa-onnx $Version ($Abi) -> $dest"
Write-Host "  注意：Kotlin 绑定 Tts.kt 必须与这个版本同 tag，改版本时一起换。"
