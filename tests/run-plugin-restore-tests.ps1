#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$JdkHome,
    [Parameter(Mandatory = $true)]
    [string]$JsonJar,
    [Parameter(Mandatory = $true)]
    [string]$BuildRoot
)

$ErrorActionPreference = 'Stop'

$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'

if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or
        !(Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK 缺少 javac 或 java: $JdkHome"
}
if (!(Test-Path -LiteralPath $JsonJar -PathType Leaf)) {
    throw "找不到 org.json JAR: $JsonJar"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $BuildRoot 'classes'
$runtime = Join-Path $BuildRoot 'runtime'
$compileLog = Join-Path $runtime 'compile.log'
$testLog = Join-Path $runtime 'test.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

$sources = @(
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/PluginManifest.java'),
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/PluginRepository.java'),
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/PluginRunner.java'),
    (Join-Path $PSScriptRoot 'PluginRestoreTest.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "缺少测试源码: $source"
    }
}

Write-Host "PluginRestoreTest: JDK   -> $JdkHome"
Write-Host "PluginRestoreTest: JSON  -> $JsonJar"
Write-Host "PluginRestoreTest: build -> $BuildRoot"

$javacArgs = @('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-cp', $JsonJar, '-d', $classes) + $sources
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
Write-Host $compileOutput.TrimEnd()
if ($compileExit -ne 0) {
    throw 'PluginRestoreTest 编译失败；请先完成三个生产类中的恢复接口'
}

$classPath = $classes + [IO.Path]::PathSeparator + $JsonJar
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dplugin.restore.test.root=$runtime",
    '-cp', $classPath,
    'dev.codex.watchworkshop.PluginRestoreTest'
)
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) {
    throw 'PluginRestoreTest 用例失败'
}

Write-Host 'PluginRestoreTest: OK'
