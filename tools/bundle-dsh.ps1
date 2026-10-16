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

# profile 先过一遍**真解析器**再打包：overlay 的 YAML 写坏时 dsh 启动即报错，而内嵌 Node
# 与宿主同进程 —— 报错就是整个 App 崩。CI 上没有 dsh 运行时跑不了这一步，所以关口放在这里。
# js-yaml 从刚装好的运行时里借，本仓库不引入 npm 依赖。
node (Join-Path $src "check-profile.mjs") (Join-Path $src "profile/cordis.patch.yml") (Join-Path $cache "node_modules")
if ($LASTEXITCODE -ne 0) { throw "check-profile.mjs 失败（exit $LASTEXITCODE），已中止装配" }

$profileOut = Join-Path $cache "profiles/phone"
New-Item -ItemType Directory -Force -Path $profileOut | Out-Null
Copy-Item -Force (Join-Path $src "profile/package.json") $profileOut
Copy-Item -Force (Join-Path $src "profile/cordis.patch.yml") $profileOut
$plugins = @(
  @{ Package = "dsh-plugin-android-tools"; Src = "plugins/android-tools" }
  @{ Package = "dsh-android-attachments"; Src = "android-attachments" }
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
  foreach ($file in "package.json", "index.mjs", "cordis.patch.yml") {
    Copy-Item -Force (Join-Path $pluginDir $file) $pluginOut
  }
}

$assets = Split-Path -Parent $zipOut
New-Item -ItemType Directory -Force -Path $assets | Out-Null
if (Test-Path -LiteralPath $zipOut) { Remove-Item -LiteralPath $zipOut }
Write-Host "packing $zipOut ..."
Push-Location $cache
try {
  # bsdtar 按扩展名自动选 zip，比 Compress-Archive 快一个量级
  tar -a -c -f $zipOut *
  if ($LASTEXITCODE -ne 0) { throw "tar failed with $LASTEXITCODE" }
} finally {
  Pop-Location
}
Write-Host "done: $([math]::Round((Get-Item $zipOut).Length/1MB,1))MB"
