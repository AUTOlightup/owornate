[CmdletBinding()]
param(
    [string]$JdkHome =
        'C:/Users/Administrator/Documents/Codex/2026-09-23/new-chat/work/toolchain/jdk-21.0.12.1+1',
    [string]$JsonJar =
        'C:/Users/Administrator/Documents/Codex/2026-10-10/new-chat/work/deps/json-20240303.jar',
    [string]$BuildRoot = ''
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
    $BuildRoot = Join-Path $projectRoot 'work/updates-step4-20261022/network'
}
elseif (![IO.Path]::IsPathRooted($BuildRoot)) {
    $BuildRoot = Join-Path $projectRoot $BuildRoot
}

$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'

if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or
        !(Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK is missing javac or java: $JdkHome"
}
if (!(Test-Path -LiteralPath $JsonJar -PathType Leaf)) {
    throw "org.json JAR not found: $JsonJar"
}

$classes = Join-Path $BuildRoot 'classes'
$runtime = Join-Path $BuildRoot 'runtime'
$compileLog = Join-Path $BuildRoot 'compile.log'
$testLog = Join-Path $BuildRoot 'test.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

$sources = @(
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/UpdateClient.java'),
    (Join-Path $PSScriptRoot 'UpdateClientTest.java'),
    (Join-Path $PSScriptRoot 'UpdateClientLive.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing test source: $source"
    }
}

Write-Host "UpdateClientTest: JDK   -> $JdkHome"
Write-Host "UpdateClientTest: JSON  -> $JsonJar"
Write-Host "UpdateClientTest: build -> $BuildRoot"

$javacArgs = @(
    '-encoding', 'UTF-8',
    '--release', '8',
    '-Xlint:-options',
    '-cp', $JsonJar,
    '-d', $classes
) + $sources
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
$compileOutput = [string]$compileOutput
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
if ($compileOutput.Trim().Length -gt 0) {
    Write-Host $compileOutput.TrimEnd()
}
if ($compileExit -ne 0) {
    throw 'UpdateClientTest compilation failed'
}

$classPath = $classes + [IO.Path]::PathSeparator + $JsonJar
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dupdate.test.root=$runtime",
    '-cp', $classPath,
    'dev.codex.watchworkshop.UpdateClientTest'
)
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
$testOutput = [string]$testOutput
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) {
    throw 'UpdateClientTest cases failed'
}

Write-Host 'UpdateClientTest: OK'
