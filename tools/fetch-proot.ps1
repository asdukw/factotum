param(
  # 版本与摘要都钉死：这三个 deb 来自 Termux 官方仓库，改版本必须先重跑一遍设备验证。
  [string]$ProotVersion = "5.1.107.101",
  [string]$TallocVersion = "2.5.0",
  [string]$ShmemVersion = "0.7",
  # Debian bookworm-slim（linux/arm64）的单一层；摘要来自 index manifest，不是 tag。
  [string]$RootfsDigest = "sha256:41b2195c355367079ebad200f9591fe3c2cb886c3b1e67abaaf2090dc11c04d9",
  [string]$RootfsRef = "debian:bookworm-slim@sha256:991827bb788d78bbc130dcb851913beecff43296360f48838aca7cbdfae4375c",
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把 proot + Termux 的 proot 加载器 + 两个运行时依赖，以及一份最小 Debian rootfs 拉到
# 构建输入目录里。产物全部在 .gitignore 内，不入库 —— 与 libnode / sherpa 同一个规矩。
#
# 为什么要这三个 deb 而不是自己编：Termux 的 proot 是打过 Android 补丁的分支
# （link2symlink、ashmem、seccomp 加速），从上游 proot 自己编会缺这些扩展。
#
# 产物：
#   android/app/src/main/jniLibs/arm64-v8a/libproot.so         ← usr/bin/proot（改名，见下）
#   android/app/src/main/jniLibs/arm64-v8a/libprootloader.so   ← usr/libexec/proot/loader
#   android/app/src/main/jniLibs/arm64-v8a/libtalloc.so        ← 运行时依赖（见下）
#   android/app/src/main/jniLibs/arm64-v8a/libandroidshmem.so  ← 运行时依赖
#   android/app/src/main/assets/proot-rootfs.bin              ← Debian rootfs 的 docker 层
#
# 两处改名不是随便写的：
#   1. 可执行文件必须以 lib*.so 命名才进得了 jniLibs；Android 10+ 只允许从
#      nativeLibraryDir 执行代码（AGENTS.md 的 W^X 条目）。
#   2. libtalloc.so.2 在设备上由 ProotRuntime 复制成正确的文件名 —— jniLibs 只收 *.so。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cacheDir = Join-Path $root "android/.proot-cache"
$jniDir = Join-Path $root "android/app/src/main/jniLibs/arm64-v8a"
$assetDir = Join-Path $root "android/app/src/main/assets"
$rootfsPath = Join-Path $cacheDir "proot-rootfs.bin"
$rootfsAsset = Join-Path $assetDir "proot-rootfs.bin"

$debs = @(
  @{ Name = "proot_${ProotVersion}_aarch64.deb"; Url = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${ProotVersion}_aarch64.deb"; Sha256 = "a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7" },
  @{ Name = "libtalloc_${TallocVersion}_aarch64.deb"; Url = "https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_${TallocVersion}_aarch64.deb"; Sha256 = "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7" },
  @{ Name = "libandroid-shmem_${ShmemVersion}_aarch64.deb"; Url = "https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-shmem/libandroid-shmem_${ShmemVersion}_aarch64.deb"; Sha256 = "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6" }
)

function Get-File([string]$url, [string]$path, [string]$sha256, [string]$what) {
  if (Test-Path -LiteralPath $path) {
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -eq $sha256.ToUpperInvariant()) {
      Write-Host "using cached $path"
      return
    }
    Write-Host "cached $what 摘要不符，重新下载"
    Remove-Item -LiteralPath $path -Force
  }
  Write-Host "downloading $what $(if ($Proxy) { "(proxy $Proxy)" })"
  $req = @{ Uri = $url; OutFile = $path; Headers = @{ "User-Agent" = "tappet" } }
  if ($Proxy) { $req.Proxy = $Proxy }
  Invoke-WebRequest @req
  $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
  if ($actual -ne $sha256.ToUpperInvariant()) {
    Remove-Item -LiteralPath $path -Force
    throw "$what 摘要不符：期望 $sha256，实际 $actual"
  }
}

<#
  .deb 是 ar 归档；Windows 自带的 tar 能读，GNU tar 不能 —— 所以在 PowerShell 里自己走
  60 字节头，只取出 data.tar.*，再交给 tar 解压。这样 CI 上（ubuntu 的 GNU tar）也一致。
#>
function Expand-DebPayload([string]$deb, [string]$dest, [string[]]$members) {
  $bytes = [IO.File]::ReadAllBytes($deb)
  $pos = 8
  $payload = $null
  $arMagic = [string][char]96 + [char]10
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  while ($pos + 60 -le $bytes.Length) {
    $magic = [Text.Encoding]::ASCII.GetString($bytes, $pos + 58, 2)
    if ($magic -ne $arMagic) { throw "ar 头损坏：$deb" }
    $name = [Text.Encoding]::ASCII.GetString($bytes, $pos, 16).Trim().TrimEnd("/")
    $size = [int]([Text.Encoding]::ASCII.GetString($bytes, $pos + 48, 10).Trim())
    $start = $pos + 60
    if ($name -like "data.tar*") { $payload = @{ Name = $name; Start = $start; Size = $size }; break }
    $pos = $start + $size + ($size % 2)
  }
  if ($null -eq $payload) { throw "$deb 里没有 data.tar.*" }
  $tmp = Join-Path $dest $payload.Name
  $out = New-Object byte[] $payload.Size
  [Array]::Copy($bytes, $payload.Start, $out, 0, $payload.Size)
  [IO.File]::WriteAllBytes($tmp, $out)
  & tar -xf $tmp -C $dest @members
  if ($LASTEXITCODE -ne 0) { throw "解压 $deb 失败" }
  Remove-Item -LiteralPath $tmp -Force
}

New-Item -ItemType Directory -Force -Path $cacheDir, $jniDir, $assetDir | Out-Null

foreach ($deb in $debs) {
  $path = Join-Path $cacheDir $deb.Name
  Get-File -url $deb.Url -path $path -sha256 $deb.Sha256 -what $deb.Name
}

$stage = Join-Path $cacheDir "stage"
if (Test-Path -LiteralPath $stage) { Remove-Item -Recurse -Force -LiteralPath $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$usr = "./data/data/com.termux/files/usr"
Expand-DebPayload (Join-Path $cacheDir "proot_${ProotVersion}_aarch64.deb") (Join-Path $stage "proot") @(
  "$usr/bin/proot", "$usr/libexec/proot/loader", "$usr/libexec/proot/loader32")
Expand-DebPayload (Join-Path $cacheDir "libtalloc_${TallocVersion}_aarch64.deb") (Join-Path $stage "talloc") @(
  "$usr/lib/libtalloc.so.2.5.0")
Expand-DebPayload (Join-Path $cacheDir "libandroid-shmem_${ShmemVersion}_aarch64.deb") (Join-Path $stage "shmem") @(
  "$usr/lib/libandroid-shmem.so")

$payload = (Join-Path $stage "proot") + "/data/data/com.termux/files/usr"
Copy-Item -Force (Join-Path $payload "bin/proot") (Join-Path $jniDir "libproot.so")
Copy-Item -Force (Join-Path $payload "libexec/proot/loader") (Join-Path $jniDir "libprootloader.so")
Copy-Item -Force (Join-Path $stage "talloc/data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0") (Join-Path $jniDir "libtalloc.so")
Copy-Item -Force (Join-Path $stage "shmem/data/data/com.termux/files/usr/lib/libandroid-shmem.so") (Join-Path $jniDir "libandroidshmem.so")

# Docker Hub 的 blob 需要先拿匿名 token；摘要即 blob 的 sha256，与 docker 的 content digest 同义。
if (-not (Test-Path -LiteralPath $rootfsPath)) {
  $repo = "library/debian"
  $tokenUrl = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repo`:pull"
  $tokenReq = @{ Uri = $tokenUrl }
  if ($Proxy) { $tokenReq.Proxy = $Proxy }
  $token = (Invoke-RestMethod @tokenReq).token
  $blobUrl = "https://registry-1.docker.io/v2/$repo/blobs/$RootfsDigest"
  Write-Host "downloading $RootfsRef $(if ($Proxy) { "(proxy $Proxy)" })"
  $blobReq = @{ Uri = $blobUrl; OutFile = $rootfsPath; Headers = @{ Authorization = "Bearer $token"; "User-Agent" = "tappet" } }
  if ($Proxy) { $blobReq.Proxy = $Proxy }
  Invoke-WebRequest @blobReq
}

$rootfsSha = (Get-FileHash -LiteralPath $rootfsPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ("sha256:$rootfsSha" -ne $RootfsDigest) {
  Remove-Item -LiteralPath $rootfsPath -Force
  throw "rootfs 摘要不符：期望 $RootfsDigest，实际 sha256:$rootfsSha"
}
# 资产名用 .bin：aapt2 会把 .gz 资产解压并去掉后缀，运行时按原名打不开。
Copy-Item -Force $rootfsPath $rootfsAsset

# 版本标记给 Gradle/测试读：rootfs 换了版本，设备上的旧解压结果要能识别出来（ProotRuntime 比对）。
$marker = [ordered]@{
  proot = $ProotVersion
  talloc = $TallocVersion
  shmem = $ShmemVersion
  rootfs = $RootfsDigest
}
$markerPath = Join-Path $assetDir "proot-runtime.json"
$marker | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding utf8NoBOM

$size = [math]::Round((Get-Item -LiteralPath $rootfsPath).Length / 1MB, 1)
Write-Host "done: jniLibs=$jniDir rootfs=$rootfsPath ($size MiB)"
