param(
  [string]$Version = "0.2.0-rc.2",
  # 同 fetch-libnode：代理跟随 HTTPS_PROXY / HTTP_PROXY，没有就直连（CI）
  [string]$Proxy = $(if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:HTTP_PROXY) { $env:HTTP_PROXY } else { "" })
)

# 把 pinned 的 dsh 运行时装配成 android/app/src/main/assets/dsh-runtime.zip：
#   npm（按 android/arm64 条件安装）→ Android 适配补丁 → launcher + phone profile → 打包
# 产物与 staging 都在 .gitignore 内，不入库。
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root "android/.dsh-cache/runtime"
$zipOut = Join-Path $root "android/app/src/main/assets/dsh-runtime.zip"
$src = Join-Path $root "tools/dsh-phone"

if ($Proxy) {
  # npm 认这两个变量；没有代理时必须清掉，否则会出现让人摸不着头的 ECONNREFUSED
  $env:HTTP_PROXY = $Proxy
  $env:HTTPS_PROXY = $Proxy
} else {
  Remove-Item Env:HTTP_PROXY -ErrorAction SilentlyContinue
  Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
}

if (-not (Test-Path -LiteralPath (Join-Path $cache "node_modules/@deepseek-ai/dsh/package.json"))) {
  Write-Host "staging @deepseek-ai/dsh@$Version (android/arm64)"
  New-Item -ItemType Directory -Force -Path $cache | Out-Null
  Push-Location $cache
  try {
    npm init -y | Out-Null
    npm install "@deepseek-ai/dsh@$Version" --omit=dev --ignore-scripts --no-audit --no-fund --os=android --cpu=arm64
  } finally {
    Pop-Location
  }
} else {
  Write-Host "using cached runtime at $cache"
}

node (Join-Path $src "patch-runtime.mjs") (Join-Path $cache "node_modules")
# 补丁失败必须硬停：否则会拿一个没打补丁（或半打）的运行时去打 zip，装到机器上才炸
if ($LASTEXITCODE -ne 0) { throw "patch-runtime.mjs 失败（exit $LASTEXITCODE），已中止装配" }

Copy-Item -Force (Join-Path $src "launcher.mjs") $cache
# launcher 的兄弟模块（zen 会话头补丁），必须一起进运行时 —— 漏了会在设备上以
# 「Cannot find module」起不来，而那是最难查的一类错。
Copy-Item -Force (Join-Path $src "zen-session-fetch.mjs") $cache

# profile 先过一遍**真解析器**再打包：overlay 的 YAML 写坏时 dsh 启动即报错，而内嵌 Node
# 与宿主同进程 —— 报错就是整个 App 崩。CI 上没有 dsh 运行时跑不了这一步，所以关口放在这里。
# js-yaml 从刚装好的运行时里借，本仓库不引入 npm 依赖。
node (Join-Path $src "check-profile.mjs") (Join-Path $src "profile/cordis.patch.yml") (Join-Path $cache "node_modules")
if ($LASTEXITCODE -ne 0) { throw "check-profile.mjs 失败（exit $LASTEXITCODE），已中止装配" }

# 模型模态索引：设置页徽章与 TAPPET_LLM_MODELS 的 input 声明共用这一份结论。
# 从同一个缓存运行时抽出来，保证索引与打进 APK 的 pi-ai 是同一版本。
$dataDir = Join-Path $cache "node_modules/@earendil-works/pi-ai/dist/providers/data"
$modalityOut = Join-Path $root "android/app/src/main/assets/model-modalities.json"
node (Join-Path $src "modality-index.mjs") $dataDir $modalityOut
if ($LASTEXITCODE -ne 0) { throw "生成模型模态索引失败（exit $LASTEXITCODE），已中止装配" }

$profileOut = Join-Path $cache "profiles/phone"
New-Item -ItemType Directory -Force -Path $profileOut | Out-Null
Copy-Item -Force (Join-Path $src "profile/package.json") $profileOut
Copy-Item -Force (Join-Path $src "profile/cordis.patch.yml") $profileOut
$plugins = @(
  @{ Package = "dsh-plugin-android-tools"; Src = "plugins/android-tools" }
  @{ Package = "dsh-android-attachments"; Src = "android-attachments" }
  @{ Package = "dsh-plugin-gmail"; Src = "plugins/gmail" }
  @{ Package = "dsh-plugin-approval"; Src = "plugins/approval" }
  @{ Package = "dsh-plugin-user-questions"; Src = "plugins/user-questions" }
)

# chat-tools 已下线：从持久缓存里删掉，避免旧包被 tar 通配符重新打进运行时。
$removedPlugin = Join-Path $cache "node_modules/dsh-plugin-chat-tools"
if (Test-Path -LiteralPath $removedPlugin) {
  $resolvedCache = (Resolve-Path -LiteralPath $cache).Path
  $resolvedRemoved = (Resolve-Path -LiteralPath $removedPlugin).Path
  if (-not $resolvedRemoved.StartsWith($resolvedCache, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝删除缓存目录之外的路径：$resolvedRemoved"
  }
  Remove-Item -LiteralPath $resolvedRemoved -Recurse -Force
}
# 清理历史包名残留（缓存目录持久存在，旧插件会被一起打进 zip）
Remove-Item -Recurse -Force (Join-Path $cache "node_modules/@tappet") -ErrorAction SilentlyContinue
foreach ($plugin in $plugins) {
  $pluginDir = Join-Path $src $plugin.Src
  if (-not (Test-Path -LiteralPath (Join-Path $pluginDir "index.mjs"))) {
    throw "插件源码缺失：$pluginDir（先运行 git submodule update --init --recursive）"
  }
  $pluginOut = Join-Path $cache "node_modules/$($plugin.Package)"
  New-Item -ItemType Directory -Force -Path $pluginOut | Out-Null
  # cordis.patch.yml 是**可选**的（有的插件需要自带的 patch 层，有的不需要）。
  # 早先这里无条件拷三个文件，结果一个没有那个文件的插件会让装配整个失败 ——
  # 报的是「找不到路径」，看着像插件缺文件，其实是脚本假设太强。
  foreach ($file in "package.json", "index.mjs", "cordis.patch.yml") {
    $source = Join-Path $pluginDir $file
    if (Test-Path -LiteralPath $source) { Copy-Item -Force $source $pluginOut }
  }
}

# 顶掉真的 @vscode/ripgrep：它**没有 android 平台产物**（12 个 optional dep 只有
# darwin / win32 / linux），而 dsh-tool-fs-search 靠 `(await import("@vscode/ripgrep")).rgPath`
# 拿二进制 —— 不换掉的话模型侧的 glob / grep 一调用就炸。桩包只做一件事：把 rgPath 指向
# 我们交叉编译的那份（tools/fetch-rg.ps1 拉进 jniLibs，NodeRuntime 注入 TAPPET_RG_PATH）。
$rgStub = Join-Path $cache "node_modules/@vscode/ripgrep"
if (Test-Path -LiteralPath $rgStub) { Remove-Item -LiteralPath $rgStub -Recurse -Force }
New-Item -ItemType Directory -Force -Path $rgStub | Out-Null
Copy-Item -Force (Join-Path $src "ripgrep-stub/package.json") $rgStub
Copy-Item -Force (Join-Path $src "ripgrep-stub/index.js") $rgStub
$assets = Split-Path -Parent $zipOut
New-Item -ItemType Directory -Force -Path $assets | Out-Null
if (Test-Path -LiteralPath $zipOut) { Remove-Item -LiteralPath $zipOut }
Write-Host "packing $zipOut ..."
# 用 .NET 的 ZipFile 而不是 tar/Compress-Archive：
#   - Windows 的 bsdtar 支持 `tar -a -c -f x.zip`，**Linux 的 GNU tar 不支持写 zip**
#     （它要么报错、要么给你一个改成 .zip 后缀的 tar）—— 而 CI 是 Linux。
#   - App 侧是用 ZipInputStream 解压的，包的必须是真 zip。tar 冒充 zip 会在设备上才炸。
[System.Reflection.Assembly]::LoadWithPartialName("System.IO.Compression.FileSystem") | Out-Null
[System.IO.Compression.ZipFile]::CreateFromDirectory(
  $cache,
  $zipOut,
  [System.IO.Compression.CompressionLevel]::Optimal,
  $false
)

# 打完立刻验一下「这确实是个 zip」：前四个字节必须是 PK。
# 这类错误在设备上表现成「解压失败 / 运行时起不来」，不值得留到那一步才发现。
$head = [System.IO.File]::ReadAllBytes($zipOut)[0..3]
if ($head[0] -ne 0x50 -or $head[1] -ne 0x4B -or $head[2] -ne 0x03 -or $head[3] -ne 0x04) {
  throw "打出来的不是 zip（头四字节不是 PK）：$zipOut"
}

# 再验条目名的分隔符：**必须是正斜杠**。
# Windows PowerShell 5.1（.NET Framework）的 ZipFile::CreateFromDirectory 会按
# Path.DirectorySeparatorChar 写成 ``，而那是**不合规的 zip**：App 侧的 ZipInputStream
# 会把它当成一个扁平文件名，解出来是 `node_modules.bincordis` 这样的文件而不是目录树，
# 于是运行时起不来，报的是 `ERR_MODULE_NOT_FOUND: Cannot find package '@deepseek-ai/cordis'`
# —— 看着像依赖没装，其实是包写坏了。pwsh 7（.NET Core）没这个问题，所以「在谁家 shell 下跑的」
# 决定了产物对不对；这条检查就是为了不让它取决于这个。实测踩过一次（2026-10-18）。
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipOut)
try {
  $bad = $archive.Entries | Where-Object { $_.FullName.Contains([char]92) } | Select-Object -First 1
  if ($null -ne $bad) {
    throw "zip 条目名用了反斜杠（$($bad.FullName)）—— 这个包在设备上解不出目录树。请用 pwsh 7 重跑本脚本。"
  }
} finally {
  $archive.Dispose()
}
Write-Host "done: $([math]::Round((Get-Item $zipOut).Length/1MB,1))MB"
