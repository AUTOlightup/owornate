param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
if(-not $BuildRoot){$BuildRoot=Join-Path (Get-Location) 'work/countdown-tests'}

# 用例名含中文：把控制台与子进程输出固定为 UTF-8，避免在非 936 代码页下出现乱码。
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
} catch { }
$javaEncodingArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')

# 只做纯 JVM 编译与运行：直接编译真实 src 下的 CountdownModel.java（纯 java.time，
# 无 Android 依赖），不引入 R、不构建整个 APP。
# JDK 只从 -JdkHome / JAVA_HOME / PATH 解析，不写死机器路径。
if (-not $JdkHome) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $JdkHome = Split-Path (Split-Path $javac.Source -Parent) -Parent }
}
if (-not $JdkHome -or !(Test-Path -LiteralPath "$JdkHome/bin/javac.exe")) {
    throw '请通过 -JdkHome 或 JAVA_HOME 指定包含 javac 的 JDK。'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$srcRoot = Join-Path $projectRoot 'src/dev/codex/watchworkshop'

$sources = @(
    (Join-Path $srcRoot 'CountdownModel.java'),
    (Join-Path $PSScriptRoot 'CountdownModelTest.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source)) { throw "缺少源文件: $source" }
}

$outClasses = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null

Write-Host "CountdownModelTest: JDK   -> $JdkHome"
Write-Host "CountdownModelTest: src   -> $srcRoot"
Write-Host "CountdownModelTest: class -> $outClasses"

& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'CountdownModelTest 编译失败' }

& "$JdkHome/bin/java.exe" @javaEncodingArgs -cp $outClasses dev.codex.watchworkshop.CountdownModelTest
if ($LASTEXITCODE -ne 0) { throw 'CountdownModelTest 用例失败' }

Write-Host 'CountdownModelTest: OK'

