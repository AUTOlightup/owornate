param(
    [string]$ProjectRoot,
    [string]$JdkHome = $env:JAVA_HOME,
    [Parameter(Mandatory = $true)][string]$JsonJar,
    [Parameter(Mandatory = $true)][string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
} catch { }
$javaEncodingArgs = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw '请通过 -JdkHome 或 JAVA_HOME 指定包含 javac 的 JDK。'
}
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$JsonJar = [IO.Path]::GetFullPath($JsonJar)

# BuildRoot is mandatory and may only resolve inside the project's work tree.
$workRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'work'))
if ([IO.Path]::IsPathRooted($BuildRoot)) {
    $resolvedBuildRoot = [IO.Path]::GetFullPath($BuildRoot)
} else {
    $resolvedBuildRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot $BuildRoot))
}
$trimChars = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
$workRootTrimmed = $workRoot.TrimEnd($trimChars)
$workPrefix = $workRootTrimmed + [IO.Path]::DirectorySeparatorChar
$insideWork = $resolvedBuildRoot.Equals($workRootTrimmed,
        [StringComparison]::OrdinalIgnoreCase) -or
    $resolvedBuildRoot.StartsWith($workPrefix, [StringComparison]::OrdinalIgnoreCase)
if (!$insideWork) {
    throw "BuildRoot 必须位于工程 work 目录内: $workRoot"
}
$BuildRoot = $resolvedBuildRoot

if (!(Test-Path -Path "$JdkHome/bin/javac.exe")) {
    throw "缺少 JDK javac: $JdkHome/bin/javac.exe"
}
if (!(Test-Path -Path $JsonJar)) {
    throw "缺少 org.json: $JsonJar"
}

$srcRoot = Join-Path $ProjectRoot 'src/dev/codex/watchworkshop'
$production = @(
    (Join-Path $srcRoot 'StatusObservation.java'),
    (Join-Path $srcRoot 'ModuleState.java'),
    (Join-Path $srcRoot 'CaptureModuleState.java'),
    (Join-Path $srcRoot 'RuntimeStateStore.java'),
    (Join-Path $srcRoot 'RuntimeSupport.java'),
    (Join-Path $srcRoot 'ThemeConfig.java'),
    (Join-Path $srcRoot 'OWOrnateWidgetStore.java'),
    (Join-Path $srcRoot 'OWOrnateWidgetSnapshot.java')
)
$stubs = @(Get-ChildItem -Path (Join-Path $PSScriptRoot 'state-stubs') -Recurse -Filter '*.java' |
    Sort-Object FullName | ForEach-Object { $_.FullName })
$testSource = Join-Path $PSScriptRoot 'RuntimeStateStoreTest.java'
$sources = @($production) + @($stubs) + @($testSource) + @(Join-Path $PSScriptRoot 'RuntimeSupportTest.java')
foreach ($source in $sources) {
    if (!(Test-Path -Path $source)) { throw "缺少源文件: $source" }
}

$outClasses = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Force -Path $outClasses | Out-Null
Write-Host "RuntimeStateStoreTest: JDK        -> $JdkHome"
Write-Host "RuntimeStateStoreTest: project    -> $ProjectRoot"
Write-Host "RuntimeStateStoreTest: org.json   -> $JsonJar"
Write-Host "RuntimeStateStoreTest: build      -> $BuildRoot"
Write-Host "RuntimeStateStoreTest: class      -> $outClasses"

& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options `
    -cp $JsonJar -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'RuntimeStateStoreTest 编译失败' }

$classPath = $outClasses + [IO.Path]::PathSeparator + $JsonJar
& "$JdkHome/bin/java.exe" @javaEncodingArgs -cp $classPath `
    dev.codex.watchworkshop.RuntimeStateStoreTest
if ($LASTEXITCODE -ne 0) { throw 'RuntimeStateStoreTest 用例失败' }

& "$JdkHome/bin/java.exe" @javaEncodingArgs -cp $classPath dev.codex.watchworkshop.RuntimeSupportTest
if ($LASTEXITCODE -ne 0) { throw 'RuntimeSupportTest 用例失败' }

Write-Host 'RuntimeStateStoreTest: OK'
