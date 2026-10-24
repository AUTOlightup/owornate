param(
    [string]$JdkHome,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw '请通过 -JdkHome 指定包含 javac/java 的 JDK。'
}
if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
    throw '请通过 -BuildRoot 指定测试输出目录。'
}

$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or !(Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK 缺少 javac 或 java: $JdkHome"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$outClasses = Join-Path $BuildRoot 'classes'
$compileLog = Join-Path $BuildRoot 'compile.log'
$testLog = Join-Path $BuildRoot 'test.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null

$sources = @(
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/ModuleManagement.java'),
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/ModuleManagementUi.java'),
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/PageRegistry.java'),
    (Join-Path $PSScriptRoot 'management-stubs/dev/codex/watchworkshop/WorkshopUi.java'),
    (Join-Path $PSScriptRoot 'ModuleManagementTest.java')
)

Write-Host "ModuleManagementTest: JDK   -> $JdkHome"
Write-Host "ModuleManagementTest: class -> $outClasses"

$javacArgs = @('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-d', $outClasses) + $sources
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
Write-Host $compileOutput.TrimEnd()
if ($compileExit -ne 0) { throw 'ModuleManagementTest 编译失败' }

$javaArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8', '-cp', $outClasses,
    'dev.codex.watchworkshop.ModuleManagementTest')
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) { throw 'ModuleManagementTest 用例失败' }

Write-Host 'ModuleManagementTest: OK'
