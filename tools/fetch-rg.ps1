param(
  [string]$Version = "15.2.0",
  [string]$Abi = "arm64-v8a",
  [string]$Repo = "asdukw/factotum",
  # 与 $Version **必须一起改**：改版本而忘了改哈希，脚本会当场报错而不是装一个来路不明的二进制。
  [string]$Sha256 = "1ae03be7fb16989724326d661839a77dfb8d014da18ab9ba9d80fbc1fba387e5",
  # 同 fetch-libnode：代理跟随 HTTPS_PROXY / HTTP_PROXY，没有就直连（CI）。
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把交叉编译好的 aarch64 ripgrep 拉进 jniLibs，供 `tool-fs-search`（模型的 glob / grep）用。
#
# **产物从哪来**：`.github/workflows/rg.yml` 在 Linux runner 上用 NDK 交叉编译（默认特性、
# 无 pcre2、自包含：NEEDED 只有 libc/libdl），发成 `rg-<version>` release 的产物。
# 这是「编一次」那一半 —— 正常构建与发版都不需要 Rust，跟 libnode 同一套模式。
#
# **为什么是 jniLibs + 叫 librg.so**：Android 10 起，targetSdk ≥ 29 的应用受
# **W^X** 限制 —— **不能执行自己可写目录里的文件**。数据目录（filesDir / cacheDir）里
# 放一个 rg 出来 exec，会被 SELinux 挡掉（症状是 `ripgrep provider failure`，
# 而 `run-as …/rg --version` 却正常，因为 run-as 的 SELinux 域还是 shell —— 别被它骗了）。
# 应用**可以**执行的地方只有 APK 的原生库目录，所以把它当原生库打包。
#
# 配套三处，改这里时要一起看：
#   - `AndroidManifest.xml` 的 `android:extractNativeLibs="true"`：AGP 默认是 false，
#     原生库直接从 APK 里 mmap，`nativeLibraryDir` 下**根本没有文件**（实测那目录是空的）。
#     打开它才会真的解出来。**代价**：libnode.so(64MB) 与 libc++_shared.so(9MB) 也会一起解，
#     安装后多占约 77 MB。
#   - `build.gradle.kts` 的 `packaging.jniLibs.keepDebugSymbols`：不加的话 AGP 会对
#     这个非共享库跑 llvm-strip。
#   - `NodeRuntime` 注入 `FACTOTUM_RG_PATH` 指向 nativeLibraryDir 下的它；
#     `tools/dsh-phone/ripgrep-stub/` 是顶掉 `@vscode/ripgrep` 的桩包，读这个 env。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $root "android/.rg-cache"
$assetName = "rg-aarch64-linux-android"
$cachePath = Join-Path $cacheDir "$assetName-$Version"
$url = "https://github.com/$Repo/releases/download/rg-$Version/$assetName"

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

if (-not (Test-Path -LiteralPath $cachePath)) {
  Write-Host "downloading $url$(if ($Proxy) { " (proxy $Proxy)" })"
  $req = @{ Uri = $url; OutFile = $cachePath; Headers = @{ "User-Agent" = "factotum" } }
  if ($Proxy) { $req.Proxy = $Proxy }
  Invoke-WebRequest @req
} else {
  Write-Host "using cached $cachePath"
}

# 先验哈希再用：这个二进制会进 APK 并在设备上执行，来路必须钉死。
$actual = (Get-FileHash -LiteralPath $cachePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $Sha256.ToLowerInvariant()) {
  Remove-Item -LiteralPath $cachePath -Force
  throw "ripgrep 产物哈希不符（期望 $Sha256，实际 $actual）—— 已删掉缓存文件。换版本时记得同时改 `$Sha256。"
}

$jniLibs = Join-Path $root "android/app/src/main/jniLibs/$Abi"
New-Item -ItemType Directory -Force -Path $jniLibs | Out-Null
$target = Join-Path $jniLibs "librg.so"
Copy-Item -Force $cachePath $target

# 复制完当场验一遍落点与大小 —— 与 fetch-libnode 里那条自检同一个理由：
# 脚本打印「成功」而文件落在别处，症状要到装包之后才现形。
if (-not (Test-Path -LiteralPath $target)) { throw "librg.so 没落到 jniLibs：$target" }
if ((Get-Item -LiteralPath $target).Length -lt 1MB) { throw "librg.so 太小，像是坏文件：$target" }

Write-Host "ripgrep $Version ($Abi) -> $target"
