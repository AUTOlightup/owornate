param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'

# JDK 只从 -JdkHome / JAVA_HOME / PATH 解析，不写死任何机器路径。
if (-not $JdkHome) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $JdkHome = Split-Path (Split-Path $javac.Source -Parent) -Parent }
}
if (-not $JdkHome -or !(Test-Path -LiteralPath "$JdkHome/bin/javac.exe")) {
    throw '请通过 -JdkHome 或 JAVA_HOME 指定 JDK。'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
# BuildRoot 可传纯 ASCII 的 work 输出目录；默认工程内 work。
if (-not $BuildRoot) { $BuildRoot = Join-Path $projectRoot 'work' }

# 只写本测试专属的 class 输出目录；不删除任何目录，只覆盖自己的 class。
$outClasses = Join-Path $BuildRoot 'owornate-tests/classes'
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null

$sources = @(
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/LatestThemeQueue.java'),
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/ModuleCatalog.java'),
    (Join-Path $PSScriptRoot 'OWOrnateFlowTest.java')
)

Write-Host "OWOrnateFlowTest: JDK   -> $JdkHome"
Write-Host "OWOrnateFlowTest: class -> $outClasses"
& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'OWOrnateFlowTest 编译失败' }

& "$JdkHome/bin/java.exe" -cp $outClasses dev.codex.watchworkshop.OWOrnateFlowTest
if ($LASTEXITCODE -ne 0) { throw 'OWOrnateFlowTest 用例失败' }

Write-Host 'OWOrnateFlowTest: OK'

