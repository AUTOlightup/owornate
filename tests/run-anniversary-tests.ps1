<#
.SYNOPSIS
    纪念日数据解析与纪念日口径倒计时的纯 JVM 测试（AnniversaryDataTest）。

.DESCRIPTION
    直接编译并运行工程里的真实 src（CountdownModel / AnniversaryData），用合成数据，
    不构建 APK、不连设备、不修改工程。
      * JDK      : -JdkHome > JAVA_HOME > PATH 上的 javac
      * org.json : -JsonJar > WATCHWORKSHOP_JSON_JAR > 当前工作目录 work/deps/json-20240303.jar
                   （仅测试用，不打进 APK；没有时先跑一次 run-timetable-tests.ps1 取得）
      * 构建产物 : -BuildRoot > 当前工作目录 work/anniversary-tests

.EXAMPLE
    pwsh -File .\tests\run-anniversary-tests.ps1 -JdkHome <JDK>
#>
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$JsonJar = $env:WATCHWORKSHOP_JSON_JAR,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
} catch { }
$javaEncodingArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')

if (-not $JdkHome) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $JdkHome = Split-Path (Split-Path $javac.Source -Parent) -Parent }
}
if (-not $JdkHome -or !(Test-Path -LiteralPath "$JdkHome/bin/javac.exe")) {
    throw '请通过 -JdkHome 或 JAVA_HOME 指定包含 javac 的 JDK。'
}
if (-not $JsonJar) { $JsonJar = Join-Path (Get-Location) 'work/deps/json-20240303.jar' }
if (!(Test-Path -LiteralPath $JsonJar -PathType Leaf)) {
    throw "未找到测试用 org.json：$JsonJar（用 -JsonJar 指定，或先跑一次 run-timetable-tests.ps1）"
}
if (-not $BuildRoot) { $BuildRoot = Join-Path (Get-Location) 'work/anniversary-tests' }

$projectRoot = Split-Path -Parent $PSScriptRoot
$srcRoot = Join-Path $projectRoot 'src/dev/codex/watchworkshop'
$sources = @(
    (Join-Path $srcRoot 'CountdownModel.java'),
    (Join-Path $srcRoot 'AnniversaryData.java'),
    (Join-Path $PSScriptRoot 'AnniversaryDataTest.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source)) { throw "缺少源文件: $source" }
}
$outClasses = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null

& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -classpath $JsonJar -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'AnniversaryDataTest 编译失败' }
& "$JdkHome/bin/java.exe" @javaEncodingArgs -cp "$outClasses;$JsonJar" dev.codex.watchworkshop.AnniversaryDataTest
if ($LASTEXITCODE -ne 0) { throw 'AnniversaryDataTest 用例失败' }
Write-Host 'AnniversaryDataTest: OK'
