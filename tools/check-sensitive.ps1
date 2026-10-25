<#
.SYNOPSIS
    发布前自检：确认要发出去的东西里没有密钥。

.DESCRIPTION
    检查三样东西：

      1. **工作树**（git 跟踪的文件）里有没有密钥
      2. **全部 git 历史**（所有提交的每个文件版本）里有没有密钥
         —— 历史必须查：git 里的东西跟着每一个 clone 走，事后只能重写历史
      3. （可选，-ApkPath）某个 APK 的 classes*.dex 里有没有密钥

    另外会做一项健康检查：确认 android/local.properties 仍然被 .gitignore 忽略。
    这一条失效的后果比密钥本身还大，所以顺手钉住。

    再扫描一组**启发式**的真实标识（NT UID / wxid / 手机号）。这类东西**只报警、
    不判失败** —— 正则分不清「真实标识」和「格式相似的字符串」，判断得靠人。
    本仓库历史上真出过这类泄漏（见 AGENTS.md 的「Git 历史」一节）。

    为什么不是「扫 APK 就完事」：构建期注入已经去掉，密钥只存在设备上的 Keystore 封装里，
    所以本机包现在也不带密钥了。这一步留着当**回归检查** —— 确认那条结构性保证没被谁改回去
    （在删掉注入之前，它靠的是「记得别发包」）。所以默认只查发布物（源码 = git 仓库）；
    只有你明确要对外发一个包时，才用 -ApkPath 指着它查一遍。

.NOTES
    输出里**不会打印密钥本体**，只报「哪个文件的哪一行」。原因见 AGENTS.md 的
    「数据与隐私红线」：日志与报告不得出现正文。

    退出码：0 = 干净，1 = 有命中（或环境不对）。

.EXAMPLE
    .\tools\check-sensitive.ps1
    发源码前的常规自检。

.EXAMPLE
    .\tools\check-sensitive.ps1 -ApkPath .\android\app\build\outputs\apk\release\app-release-unsigned.apk
    真要对外发一个包时，连包一起查。

.EXAMPLE
    .\tools\check-sensitive.ps1 -SelfTest
    只跑内置自检：确认扫描器**确实能命中**。不做这个的话，「扫出来是空的」有可能
    只是扫描器根本没在工作（本仓库反复吃过这种亏）。
#>
[CmdletBinding()]
param(
  [string]$ApkPath,
  [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$localProperties = Join-Path $root 'android/local.properties'

# ── 模式定义（全脚本唯一来源：git grep 与 .NET 正则共用同一组） ─────────────────
# 注意：这些会被塞进 git grep -E（POSIX ERE），所以不用 (?:...) / \d 这类扩展语法。
$SecretPatterns = @(
  'oc_sk_[A-Za-z0-9_]{16,}'            # opencode zen
  'sk-[A-Za-z0-9_-]{16,}'              # OpenAI 风格
  'ghp_[A-Za-z0-9]{20,}'               # GitHub PAT（经典）
  'github_pat_[A-Za-z0-9_]{20,}'       # GitHub PAT（细粒度）
  'AKIA[0-9A-Z]{16}'                   # AWS Access Key ID
  'AIza[0-9A-Za-z_-]{30,}'             # Google API key
  'xox[baprs]-[A-Za-z0-9-]{10,}'       # Slack
  '-----BEGIN [A-Z ]*PRIVATE KEY-----' # 私钥
)

# 启发式：真实标识。只报警，不判失败。
$IdentifierPatterns = @(
  'u_[A-Za-z0-9]{20,}'                            # QQ NT UID
  'wxid_[A-Za-z0-9_-]{6,}'                        # 微信 wxid
  '(^|[^0-9])1[3-9][0-9]{9}([^0-9]|$)'            # 中国大陆手机号
)

function Get-LiveApiKey {
  if (-not (Test-Path -LiteralPath $localProperties)) { return $null }
  foreach ($line in Get-Content -LiteralPath $localProperties) {
    if ($line -match '^\s*factotum\.agent\.apiKey\s*=') {
      $value = $line.Substring($line.IndexOf('=') + 1).Trim()
      if ($value.Length -gt 0) { return $value }
    }
  }
  return $null
}

# 在文本里找命中，返回命中的模式列表（不返回命中内容 —— 内容可能正是密钥）。
function Find-PatternHits {
  param([string]$Text, [string[]]$Patterns)
  $hits = @()
  foreach ($p in $Patterns) {
    if ([regex]::IsMatch($Text, $p)) { $hits += $p }
  }
  # 前置逗号：PowerShell 默认会把数组「解包」，空数组会变成 $null，
  # 调用方那边 $hits.Count 就会炸（或更糟：悄悄当成 0 命中）。这是刻意的。
  return , $hits
}

function Find-LiteralHits {
  param([string]$Text, [string]$Literal)
  if ([string]::IsNullOrEmpty($Literal)) { return 0 }
  $count = 0
  $start = 0
  while ($true) {
    $at = $Text.IndexOf($Literal, $start, [StringComparison]::Ordinal)
    if ($at -lt 0) { break }
    $count++
    $start = $at + $Literal.Length
  }
  return $count
}

# git grep 的输出是 "rev:path:line:content"（带 rev）或 "path:line:content"（工作树）。
# 这里只保留定位信息，**丢掉正文** —— 正文可能正是密钥。
function Get-LocationsOnly {
  param([string[]]$Lines, [switch]$IncludeRevision)
  $take = if ($IncludeRevision) { 3 } else { 2 }
  $out = @()
  foreach ($line in $Lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split ':'
    if ($parts.Count -ge $take) {
      $out += (($parts[0..($take - 1)]) -join ':')
    } else {
      $out += $line
    }
  }
  return , $out
}

function Invoke-GitGrep {
  param([string]$Pattern, [string[]]$Revisions)
  # 变量不能叫 $args：那是 PowerShell 的自动变量，赋值会踩掉它。
  $gitArgs = @('grep', '-nIE', '-e', $Pattern)
  if ($Revisions) { $gitArgs += $Revisions }
  $gitArgs += @('--', '.')
  $result = & git -C $root @gitArgs 2>$null
  if ($LASTEXITCODE -eq 1) { return , @() }   # 1 = 没有命中
  if ($LASTEXITCODE -ne 0) { throw "git grep 失败（exit $LASTEXITCODE）" }
  return , @($result)
}

# ── 自检 ──────────────────────────────────────────────────────────────────────
if ($SelfTest) {
  Write-Host '== 自检：扫描器确实能命中吗 ==' -ForegroundColor Cyan
  $failures = 0

  # 自检样本一律**碎片拼装**，源码里不留整串字面量。两个理由都是踩过的：
  #   1) 扫描器会扫到自己 —— 整串躺在文件里，默认扫描就恒红，闸门形同失效（本仓库真发生过）；
  #   2) 真实标识不能借自检样本还魂 —— u_ 那条原本是从已剥离的历史里抄来的真实 NT UID
  #      （见 AGENTS.md「Git 历史」）。样本只要能命中模式即可，越假越好。
  $ocSample = 'oc_sk_' + 'abcdefghijklmnop1234'
  $ghSample = 'ghp_' + 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
  $phoneSample = '138' + '00138000'
  $uidSample = 'u_' + 'SyntheticPlaceholder00'
  $positive = @(
    'key = "' + $ocSample + '"'
    'gh = ' + $ghSample
    'phone = ' + $phoneSample
    'uid = ' + $uidSample
  ) -join "`n"
  $negative = @'
baseUrl = https://example.com/v1
model = deepseek-v4.1-flash
note = "这里没有任何密钥，也没有 u_ 开头的长串"
'@

  $secretHits = Find-PatternHits -Text $positive -Patterns $SecretPatterns
  $identHits = Find-PatternHits -Text $positive -Patterns $IdentifierPatterns
  $cleanSecret = Find-PatternHits -Text $negative -Patterns $SecretPatterns
  $cleanIdent = Find-PatternHits -Text $negative -Patterns $IdentifierPatterns
  $literal = Find-LiteralHits -Text $positive -Literal $ocSample

  if ($secretHits.Count -lt 2) { Write-Host "  FAIL 密钥模式命中不足：$($secretHits.Count)" -ForegroundColor Red; $failures++ }
  else { Write-Host "  ok   密钥模式命中 $($secretHits.Count) 条" -ForegroundColor Green }

  if ($identHits.Count -lt 2) { Write-Host "  FAIL 标识模式命中不足：$($identHits.Count)" -ForegroundColor Red; $failures++ }
  else { Write-Host "  ok   标识模式命中 $($identHits.Count) 条" -ForegroundColor Green }

  if ($cleanSecret.Count -ne 0) { Write-Host "  FAIL 干净文本上误报：$($cleanSecret -join ', ')" -ForegroundColor Red; $failures++ }
  else { Write-Host '  ok   干净文本零误报（密钥）' -ForegroundColor Green }

  if ($cleanIdent.Count -ne 0) { Write-Host "  FAIL 干净文本上误报：$($cleanIdent -join ', ')" -ForegroundColor Red; $failures++ }
  else { Write-Host '  ok   干净文本零误报（标识）' -ForegroundColor Green }

  if ($literal -ne 1) { Write-Host "  FAIL 字面量命中数应为 1，实际 $literal" -ForegroundColor Red; $failures++ }
  else { Write-Host '  ok   字面量扫描正常' -ForegroundColor Green }

  if ($failures -gt 0) { exit 1 }
  Write-Host "`n自检通过。" -ForegroundColor Green
  exit 0
}

# ── 正式检查 ──────────────────────────────────────────────────────────────────
$liveKey = Get-LiveApiKey
$failed = $false
$warned = $false

Write-Host '== Factotum 发布前敏感信息自检 ==' -ForegroundColor Cyan
if ($liveKey) {
  Write-Host "已读到本机 API key（$($liveKey.Length) 字符，不打印）" -ForegroundColor DarkGray
} else {
  Write-Host '本机没有 API key（local.properties 无 factotum.agent.apiKey），只按模式扫' -ForegroundColor DarkGray
}

# 1) .gitignore 健康检查 —— 这条失效的后果最大
Write-Host "`n[1/4] local.properties 仍被忽略？" -ForegroundColor Cyan
& git -C $root check-ignore -q 'android/local.properties' 2>$null
if ($LASTEXITCODE -eq 0) {
  Write-Host '  ok   已被 .gitignore 覆盖' -ForegroundColor Green
} else {
  Write-Host '  FAIL android/local.properties 不再被忽略 —— 它随时可能被 git add 进去' -ForegroundColor Red
  $failed = $true
}

# 2) 工作树
Write-Host "`n[2/4] 工作树（git 跟踪的文件）" -ForegroundColor Cyan
$treeHit = $false
foreach ($p in $SecretPatterns) {
  $lines = Invoke-GitGrep -Pattern $p -Revisions @()
  if ($lines.Count -gt 0) {
    Write-Host "  FAIL 命中模式 $p" -ForegroundColor Red
    Get-LocationsOnly -Lines $lines | ForEach-Object { Write-Host "         $_" -ForegroundColor Red }
    $treeHit = $true
  }
}
if ($liveKey) {
  $lines = Invoke-GitGrep -Pattern ([regex]::Escape($liveKey)) -Revisions @()
  if ($lines.Count -gt 0) {
    Write-Host '  FAIL 命中本机那把 API key 本体' -ForegroundColor Red
    Get-LocationsOnly -Lines $lines | ForEach-Object { Write-Host "         $_" -ForegroundColor Red }
    $treeHit = $true
  }
}
if ($treeHit) { $failed = $true } else { Write-Host '  ok   未发现密钥' -ForegroundColor Green }

# 3) 全部历史
Write-Host "`n[3/4] git 全部历史（每个提交的每个文件版本）" -ForegroundColor Cyan
$revs = @(& git -C $root rev-list --all)
Write-Host "  扫描 $($revs.Count) 个提交……" -ForegroundColor DarkGray
$historyHit = $false
foreach ($p in $SecretPatterns) {
  $lines = Invoke-GitGrep -Pattern $p -Revisions $revs
  if ($lines.Count -gt 0) {
    Write-Host "  FAIL 命中模式 $p" -ForegroundColor Red
    Get-LocationsOnly -Lines $lines -IncludeRevision | Select-Object -First 10 | ForEach-Object { Write-Host "         $_" -ForegroundColor Red }
    $historyHit = $true
  }
}
if ($liveKey) {
  $lines = Invoke-GitGrep -Pattern ([regex]::Escape($liveKey)) -Revisions $revs
  if ($lines.Count -gt 0) {
    Write-Host '  FAIL 历史里出现过本机那把 API key 本体' -ForegroundColor Red
    Get-LocationsOnly -Lines $lines -IncludeRevision | Select-Object -First 10 | ForEach-Object { Write-Host "         $_" -ForegroundColor Red }
    $historyHit = $true
  }
}
if ($historyHit) { $failed = $true } else { Write-Host '  ok   未发现密钥' -ForegroundColor Green }

# 4) 可选的 APK
Write-Host "`n[4/4] 构建产物" -ForegroundColor Cyan
if (-not $ApkPath) {
  Write-Host '  跳过（没有 -ApkPath）。密钥已不再编进包，这一步是回归检查；' -ForegroundColor DarkGray
  Write-Host '  只有要对外发一个包时，才需要指着它查。' -ForegroundColor DarkGray
} elseif (-not (Test-Path -LiteralPath $ApkPath)) {
  Write-Host "  FAIL 找不到文件：$ApkPath" -ForegroundColor Red
  $failed = $true
} else {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath))
  try {
    $dexEntries = @($zip.Entries | Where-Object { $_.Name -like 'classes*.dex' })
    Write-Host "  包内 $($dexEntries.Count) 个 dex" -ForegroundColor DarkGray
    $apkHit = $false
    foreach ($entry in $dexEntries) {
      # Latin1 是字节的 1:1 映射，所以「按字节搜」与「按串搜」等价 —— 不需要额外的字节扫描。
      $stream = $entry.Open()
      try {
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::Latin1)
        $text = $reader.ReadToEnd()
      } finally {
        $stream.Dispose()
      }
      $hits = Find-PatternHits -Text $text -Patterns $SecretPatterns
      if ($liveKey -and (Find-LiteralHits -Text $text -Literal $liveKey) -gt 0) { $hits += '(本机 API key 本体)' }
      if ($hits.Count -gt 0) {
        Write-Host "  FAIL $($entry.Name) 命中：$($hits -join ', ')" -ForegroundColor Red
        $apkHit = $true
      }
    }
    if ($apkHit) { $failed = $true } else { Write-Host '  ok   包里未发现密钥' -ForegroundColor Green }
  } finally {
    $zip.Dispose()
  }
}

# 附加：启发式真实标识（只报警）
Write-Host "`n[附] 启发式：真实标识（NT UID / wxid / 手机号）" -ForegroundColor Cyan
$identHit = $false
# 两个作用域分开列：写成 @(@(), $revs) 会被 PowerShell 把嵌套数组摊平，
# 结果变成「逐个提交扫一遍工作树」，看起来一样但什么都没查。
$scopes = @(
  @{ Name = '工作树'; Revs = @(); IncludeRevision = $false }
  @{ Name = '历史'; Revs = $revs; IncludeRevision = $true }
)
foreach ($p in $IdentifierPatterns) {
  foreach ($scope in $scopes) {
    $lines = Invoke-GitGrep -Pattern $p -Revisions $scope.Revs
    if ($lines.Count -gt 0) {
      Write-Host "  WARN [$($scope.Name)] 模式 $p 命中 $($lines.Count) 处" -ForegroundColor Yellow
      $shown = if ($scope.IncludeRevision) {
        Get-LocationsOnly -Lines $lines -IncludeRevision
      } else {
        Get-LocationsOnly -Lines $lines
      }
      $shown | Select-Object -First 5 | ForEach-Object { Write-Host "         $_" -ForegroundColor Yellow }
      $identHit = $true
    }
  }
}
if ($identHit) {
  Write-Host '  这只说明「有形状相似的东西」，不等于泄漏 —— 正则认不出真实标识，得你看一眼。' -ForegroundColor Yellow
  $warned = $true
} else {
  Write-Host '  ok   未发现形状相似的标识' -ForegroundColor Green
}

# 结论
Write-Host ''
if ($failed) {
  Write-Host '结论：不通过 —— 上面标 FAIL 的地方处理掉再发布。' -ForegroundColor Red
  exit 1
}
if ($warned) {
  Write-Host '结论：通过（有 WARN，请人工确认）。' -ForegroundColor Yellow
  exit 0
}
Write-Host '结论：通过。' -ForegroundColor Green
exit 0
