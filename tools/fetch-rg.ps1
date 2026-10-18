param(
  [string]$Version = "15.2.0",
  [string]$Repo = "asdukw/tappet",
  # 与 $Version **必须一起改**：改版本而忘了改哈希，脚本会当场报错而不是装一个来路不明的二进制。
  [string]$Sha256 = "1ae03be7fb16989724326d661839a77dfb8d014da18ab9ba9d80fbc1fba387e5",
  # 同 fetch-libnode：代理跟随 HTTPS_PROXY / HTTP_PROXY，没有就直连（CI）。
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把交叉编译好的 aarch64 ripgrep 拉到 tools/dsh-phone/.bin/rg，供 `tool-fs-search`
# （模型的 glob / grep）用。**本脚本只负责取件**，怎么进设备见下面的「产物怎么进 App」。
#
# **产物从哪来**：`.github/workflows/rg.yml` 在 Linux runner 上用 NDK 交叉编译
# （默认特性，无 pcre2，自包含：NEEDED 只有 libc/libdl），发成 `rg-<version>` release 的产物。
# 这是「编一次」那一半 —— 正常构建与发版都不需要 Rust，跟 libnode 同一套模式。
#
# **产物怎么进 App**：`bundle-dsh.ps1` 把它复制进 dsh 运行时缓存并打进
# `assets/dsh-runtime.zip`，App 侧解压到 `files/dsh-runtime/rg` 再 `chmod +x`，
# `NodeRuntime` 注入 `TAPPET_RG_PATH` 指向它。
#
# **为什么不放 jniLibs**（试过，2026-10-18）：放 `jniLibs/<abi>/librg.so` 看着最顺 ——
# 原生库目录是应用有执行权限的地方。但**现代 AGP 默认 `extractNativeLibs=false`**，
# 原生库直接从 APK 里 mmap，`nativeLibraryDir` 下**根本没有这个文件**（实测 `lib/arm64/`
# 是空的），exec 必然失败。要让它落地就得 `extractNativeLibs="true"`，那会把 64 MB 的
# libnode.so 和 9 MB 的 libc++_shared 一起解出来——为了一个 3.4 MB 的 rg 不值。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $root "android/.rg-cache"
$assetName = "rg-aarch64-linux-android"
$cachePath = Join-Path $cacheDir "$assetName-$Version"
$url = "https://github.com/$Repo/releases/download/rg-$Version/$assetName"

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

if (-not (Test-Path -LiteralPath $cachePath)) {
  Write-Host "downloading $url$(if ($Proxy) { " (proxy $Proxy)" })"
  $req = @{ Uri = $url; OutFile = $cachePath; Headers = @{ "User-Agent" = "tappet" } }
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

# 落在 tools/dsh-phone/.bin/（gitignored）：它是 bundle-dsh.ps1 的输入，不是入库物。
$binDir = Join-Path $root "tools/dsh-phone/.bin"
New-Item -ItemType Directory -Force -Path $binDir | Out-Null
$target = Join-Path $binDir "rg"
Copy-Item -Force $cachePath $target

# 复制完当场验一遍落点与大小 —— 与 fetch-libnode 里那条自检同一个理由：
# 脚本打印「成功」而文件落在别处，症状要到装包之后才现形。
if (-not (Test-Path -LiteralPath $target)) { throw "rg 没落到 .bin：$target" }
if ((Get-Item -LiteralPath $target).Length -lt 1MB) { throw "rg 太小，像是坏文件：$target" }

Write-Host "ripgrep $Version -> $target"
