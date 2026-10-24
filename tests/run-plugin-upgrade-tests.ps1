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
    $BuildRoot = Join-Path $projectRoot 'work/plugin-upgrade-20261022'
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

$production = @(
    'PluginManifest.java',
    'PluginRepository.java',
    'PluginRunner.java',
    'ExternalPlugins.java', 'CaptureSettings.java'
) | ForEach-Object {
    Join-Path $projectRoot ('src/dev/codex/watchworkshop/' + $_)
}
$stubs = @(
    'android/content/Context.java',
    'dev/codex/watchworkshop/RuntimeSupport.java',
    'dev/codex/watchworkshop/RootShell.java',
    'dev/codex/watchworkshop/AdbClient.java',
    'dev/codex/watchworkshop/ManagerCalls.java'
) | ForEach-Object {
    Join-Path $PSScriptRoot ('launcher-stubs/' + $_)
}
$sources = @($production) + @($stubs) + @(
    (Join-Path $PSScriptRoot 'state-stubs/android/content/SharedPreferences.java'),
    (Join-Path $PSScriptRoot 'PluginUpgradeIntegrationTest.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Missing test source: $source"
    }
}

Write-Host "PluginUpgradeIntegrationTest: JDK     -> $JdkHome"
Write-Host "PluginUpgradeIntegrationTest: JSON    -> $JsonJar"
Write-Host "PluginUpgradeIntegrationTest: fixtures-> $(Join-Path $projectRoot 'plugins')"
Write-Host "PluginUpgradeIntegrationTest: build   -> $BuildRoot"

$javacArgs = @(
    '-encoding', 'UTF-8',
    '--release', '8',
    '-Xlint:-options',
    '-cp', $JsonJar,
    '-d', $classes
) + $sources
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
$compileOutput = [string]$compileOutput
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
if ($compileOutput.Trim().Length -gt 0) {
    Write-Host $compileOutput.TrimEnd()
}
if ($compileExit -ne 0) {
    throw 'PluginUpgradeIntegrationTest compilation failed'
}

$classPath = $classes + [IO.Path]::PathSeparator + $JsonJar
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dplugin.upgrade.test.root=$runtime",
    "-Dplugin.upgrade.fixtures=$(Join-Path $projectRoot 'plugins')",
    '-cp', $classPath,
    'dev.codex.watchworkshop.PluginUpgradeIntegrationTest'
)
$ErrorActionPreference = 'Continue'
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
$testOutput = [string]$testOutput
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) {
    throw 'PluginUpgradeIntegrationTest cases failed'
}

Write-Host 'PluginUpgradeIntegrationTest: OK'
