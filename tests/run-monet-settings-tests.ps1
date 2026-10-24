[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$JsonJar,
    [Parameter(Mandatory = $true)][string]$PackageFile,
    [Parameter(Mandatory = $true)][string]$BuildRoot
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$PackageFile = [IO.Path]::GetFullPath($PackageFile)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'

if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or
        !(Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK 缺少 javac 或 java: $JdkHome"
}
if (!(Test-Path -LiteralPath $JsonJar -PathType Leaf)) {
    throw "找不到测试用 org.json JAR: $JsonJar"
}
if (!(Test-Path -LiteralPath $PackageFile -PathType Leaf)) {
    throw "找不到当前 monet 测试包: $PackageFile"
}

$runRoot = Join-Path $BuildRoot ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classes = Join-Path $runRoot 'classes'
$runtime = Join-Path $runRoot 'runtime'
$compileLog = Join-Path $runRoot 'compile.log'
$testLog = Join-Path $runRoot 'test.log'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $runtime -Force | Out-Null

$production = @(
    'PluginManifest.java', 'PluginRepository.java', 'PluginRunner.java',
    'ExternalPlugins.java', 'CaptureSettings.java', 'MonetSettings.java',
    'ThemeConfig.java', 'ModuleState.java'
) | ForEach-Object { Join-Path $projectRoot ('src/dev/codex/watchworkshop/' + $_) }
$stubs = @(
    'launcher-stubs/android/content/Context.java',
    'launcher-stubs/dev/codex/watchworkshop/RuntimeSupport.java',
    'launcher-stubs/dev/codex/watchworkshop/RootShell.java',
    'launcher-stubs/dev/codex/watchworkshop/AdbClient.java',
    'state-stubs/android/content/SharedPreferences.java',
    'monet-settings-stubs/dev/codex/watchworkshop/ThemeRuntime.java',
    'monet-settings-stubs/dev/codex/watchworkshop/ModuleManager.java',
    'monet-settings-stubs/dev/codex/watchworkshop/RuntimeStateStore.java'
) | ForEach-Object { Join-Path $PSScriptRoot $_ }
$sources = @($production) + @($stubs) + @((Join-Path $PSScriptRoot 'MonetSettingsTest.java'))
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "缺少测试源码: $source"
    }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

Write-Host "MonetSettingsTest: JDK     -> $JdkHome"
Write-Host "MonetSettingsTest: JSON    -> $JsonJar"
Write-Host "MonetSettingsTest: package -> $PackageFile"
Write-Host "MonetSettingsTest: build   -> $runRoot"

$javacArgs = @(
    '-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-cp', $JsonJar, '-d', $classes
) + $sources
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
if ($compileOutput.Trim().Length -gt 0) { Write-Host $compileOutput.TrimEnd() }
if ($compileExit -ne 0) { throw 'MonetSettingsTest 编译失败' }

$classPath = $classes + [IO.Path]::PathSeparator + $JsonJar
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dmonet.settings.test.root=$runtime",
    "-Dmonet.package.file=$PackageFile",
    '-cp', $classPath,
    'dev.codex.watchworkshop.MonetSettingsTest'
)
$ErrorActionPreference = 'Continue'
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) { throw 'MonetSettingsTest 用例失败' }

Write-Host "MonetSettingsTest: OK ($runRoot)"
