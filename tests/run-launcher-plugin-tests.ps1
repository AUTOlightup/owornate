[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$JsonJar,
    [Parameter(Mandatory = $true)][string]$PackageDir,
    [Parameter(Mandatory = $true)][string]$BuildRoot
)

$ErrorActionPreference = 'Stop'
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$JsonJar = [IO.Path]::GetFullPath($JsonJar)
$PackageDir = [IO.Path]::GetFullPath($PackageDir)
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
if (!(Test-Path -LiteralPath $PackageDir -PathType Container)) {
    throw "找不到 launcher 插件包目录: $PackageDir"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $BuildRoot 'classes'
$runtime = Join-Path $BuildRoot 'runtime'
$launcherRuntime = Join-Path $runtime 'launcher'
$captureRuntime = Join-Path $runtime 'capture-settings'
$compileLog = Join-Path $BuildRoot 'compile.log'
$testLog = Join-Path $BuildRoot 'test.log'
$captureTestLog = Join-Path $BuildRoot 'capture-settings-test.log'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
New-Item -ItemType Directory -Path $launcherRuntime -Force | Out-Null
New-Item -ItemType Directory -Path $captureRuntime -Force | Out-Null

$production = @(
    'PluginManifest.java', 'PluginRepository.java', 'LauncherPlugins.java',
    'ModuleManagement.java', 'ModuleState.java', 'CaptureModuleState.java',
    'ThemeConfig.java', 'ExternalPlugins.java', 'PluginRunner.java', 'CaptureSettings.java'
) | ForEach-Object {
    Join-Path $projectRoot ('src/dev/codex/watchworkshop/' + $_)
}
$stubs = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'launcher-stubs') -Filter '*.java' -Recurse -File |
    ForEach-Object { $_.FullName }
$sources = @($production) + @($stubs) + @((Join-Path $PSScriptRoot 'LauncherPluginsTest.java'),
    (Join-Path $PSScriptRoot 'CaptureSettingsTest.java'),
    (Join-Path $PSScriptRoot 'state-stubs/android/content/SharedPreferences.java'))
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "缺少测试源码: $source"
    }
}

Write-Host "LauncherPluginsTest: JDK     -> $JdkHome"
Write-Host "LauncherPluginsTest: JSON    -> $JsonJar"
Write-Host "LauncherPluginsTest: packages-> $PackageDir"
Write-Host "LauncherPluginsTest: build   -> $BuildRoot"

$javacArgs = @('-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
    '-cp', $JsonJar, '-d', $classes) + $sources
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$compileOutput = (& $javac @javacArgs 2>&1 | Out-String)
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
[IO.File]::WriteAllText($compileLog, $compileOutput, $utf8)
Write-Host $compileOutput.TrimEnd()
if ($compileExit -ne 0) { throw 'LauncherPluginsTest 编译失败' }

$classPath = $classes + [IO.Path]::PathSeparator + $JsonJar
$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dlauncher.test.root=$launcherRuntime",
    "-Dlauncher.package.dir=$PackageDir",
    '-cp', $classPath,
    'dev.codex.watchworkshop.LauncherPluginsTest'
)
$ErrorActionPreference = 'Continue'
$testOutput = (& $java @javaArgs 2>&1 | Out-String)
$testExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
[IO.File]::WriteAllText($testLog, $testOutput, $utf8)
Write-Host $testOutput.TrimEnd()
if ($testExit -ne 0) { throw 'LauncherPluginsTest 用例失败' }

Write-Host 'LauncherPluginsTest: OK'

$javaArgs = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-Dstderr.encoding=UTF-8',
    "-Dcapture.settings.test.root=$captureRuntime",
    "-Dcapture.package.dir=$PackageDir",
    '-cp', $classPath,
    'dev.codex.watchworkshop.CaptureSettingsTest'
)
$ErrorActionPreference = 'Continue'
$captureOutput = (& $java @javaArgs 2>&1 | Out-String)
$captureExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
[IO.File]::WriteAllText($captureTestLog, $captureOutput, $utf8)
Write-Host $captureOutput.TrimEnd()
if ($captureExit -ne 0) { throw 'CaptureSettingsTest 用例失败' }

Write-Host 'CaptureSettingsTest: OK'
