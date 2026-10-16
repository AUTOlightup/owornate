<#
.SYNOPSIS
    拾光课表解析器与焦点计算的纯 JVM 测试（ShiguangJsonTest + TimetableFocusTest）。

.DESCRIPTION
    直接编译并运行工程里的真实 src（TimetableData / ShiguangJson / TimetableFocus），
    不构建 APK、不连设备、不修改工程。

    工具链解析顺序一律是：命令行参数 > 环境变量 > PATH / 当前工作目录 work，
    脚本内不写死任何机器或其它项目的路径：
      * JDK        : -JdkHome > JAVA_HOME > WATCHWORKSHOP_JDK > PATH 上的 javac
      * android.jar: -AndroidJar > WATCHWORKSHOP_ANDROID_JAR > SDK 根（WATCHWORKSHOP_ANDROID_SDK /
                     ANDROID_SDK_ROOT / ANDROID_HOME）> PATH 上的 aapt2/adb/sdkmanager 向上找 SDK >
                     常见本地安装位置 > 当前工作目录 work 下的 android.jar
      * org.json   : -JsonJar > WATCHWORKSHOP_JSON_JAR > 当前工作目录 work 下已有的 json-<版本>.jar >
                     从 Maven Central 取固定版本并校验 SHA-256（仅测试用，不打进 APK）
      * fixture    : -FixtureDir > WATCHWORKSHOP_FIXTURES > 工程 tests/fixtures（合成数据，可写）
      * 构建产物   : -BuildRoot > WATCHWORKSHOP_TEST_BUILD > 当前工作目录 work/timetable-json-tests

.EXAMPLE
    pwsh -File .\tests\run-timetable-tests.ps1
#>
param(
    [string]$JdkHome,
    [string]$AndroidJar,
    [string]$JsonJar,
    [string]$FixtureDir,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'

# 用例名与报错信息含中文：控制台与子进程输出固定为 UTF-8。
try {
    [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $OutputEncoding = [Console]::OutputEncoding
} catch { }
# JDK 17 认 file.encoding，JDK 18+ 认 stdout/stderr.encoding，三个都带上。
$javaEncodingArgs = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')

$projectRoot = Split-Path -Parent $PSScriptRoot
$srcRoot = Join-Path $projectRoot 'src/dev/codex/watchworkshop'
$workRoot = Join-Path (Get-Location).Path 'work'

# ---------------------------------------------------------------------------
# 辅助
# ---------------------------------------------------------------------------
function Test-AndroidSdkRoot {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $Path 'platforms') -PathType Container)
}

function Get-AndroidJarFromSdk {
    param([string]$SdkRoot)
    if (!(Test-AndroidSdkRoot $SdkRoot)) { return $null }
    $platforms = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platforms') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending)
    foreach ($platform in $platforms) {
        $jar = Join-Path $platform.FullName 'android.jar'
        if (Test-Path -LiteralPath $jar) { return $jar }
    }
    return $null
}

function Get-AndroidSdkRootFromPath {
    foreach ($name in @('aapt2.exe', 'aapt2', 'sdkmanager.bat', 'adb.exe', 'adb')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $command -or [string]::IsNullOrWhiteSpace($command.Source)) { continue }
        $dir = Split-Path -Parent $command.Source
        for ($level = 0; $level -lt 3; $level++) {
            if (Test-AndroidSdkRoot $dir) { return $dir }
            $parent = Split-Path -Parent $dir
            if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $dir) { break }
            $dir = $parent
        }
    }
    return $null
}

function Get-AndroidSdkRootFromCommonLocations {
    foreach ($base in @($env:LOCALAPPDATA, $env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:USERPROFILE)) {
        if ([string]::IsNullOrWhiteSpace($base)) { continue }
        foreach ($candidate in @(
            (Join-Path $base 'Android\Sdk'),
            (Join-Path $base 'AppData\Local\Android\Sdk')
        )) {
            if (Test-AndroidSdkRoot $candidate) { return $candidate }
        }
    }
    return $null
}

function Get-FirstExistingFile {
    param([string[]]$Candidates)
    $hit = @($Candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) })
    if ($hit.Count -gt 0) { return $hit[0] }
    return $null
}

function Get-AndroidJarUnderWork {
    if (!(Test-Path -LiteralPath $workRoot)) { return $null }
    $found = @(Get-ChildItem -LiteralPath $workRoot -Recurse -Depth 4 -Filter 'android.jar' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1)
    if ($found.Count -gt 0) { return $found[0].FullName }
    return $null
}

# ---------------------------------------------------------------------------
# 1) JDK
# ---------------------------------------------------------------------------
foreach ($candidate in @($JdkHome, $env:JAVA_HOME, $env:WATCHWORKSHOP_JDK)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    if (Test-Path -LiteralPath (Join-Path $candidate 'bin/javac.exe')) { $JdkHome = $candidate; break }
    throw "指定的 JDK 目录里没有 bin/javac.exe: $candidate"
}
if (-not $JdkHome) {
    $javac = Get-Command javac -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javac -and $javac.Source) { $JdkHome = Split-Path -Parent (Split-Path -Parent $javac.Source) }
}
if (-not $JdkHome -or !(Test-Path -LiteralPath (Join-Path $JdkHome 'bin/javac.exe'))) {
    throw '未找到 JDK。请用 -JdkHome，或设置 JAVA_HOME / WATCHWORKSHOP_JDK，或把 javac 放进 PATH。'
}
$JdkHome = (Resolve-Path -LiteralPath $JdkHome).Path

# ---------------------------------------------------------------------------
# 2) android.jar（只用于编译时的 API 面；运行时不带 android.jar）
# ---------------------------------------------------------------------------
if (-not $AndroidJar) { $AndroidJar = $env:WATCHWORKSHOP_ANDROID_JAR }
if (-not $AndroidJar) {
    foreach ($sdkRoot in @($env:WATCHWORKSHOP_ANDROID_SDK, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ([string]::IsNullOrWhiteSpace($sdkRoot)) { continue }
        $AndroidJar = Get-AndroidJarFromSdk -SdkRoot $sdkRoot
        if ($AndroidJar) { break }
    }
}
if (-not $AndroidJar) {
    $sdkRoot = Get-AndroidSdkRootFromPath
    if ($sdkRoot) { $AndroidJar = Get-AndroidJarFromSdk -SdkRoot $sdkRoot }
}
if (-not $AndroidJar) {
    $sdkRoot = Get-AndroidSdkRootFromCommonLocations
    if ($sdkRoot) { $AndroidJar = Get-AndroidJarFromSdk -SdkRoot $sdkRoot }
}
if (-not $AndroidJar) { $AndroidJar = Get-AndroidJarUnderWork }
if (-not $AndroidJar -or !(Test-Path -LiteralPath $AndroidJar -PathType Leaf)) {
    throw '未找到 android.jar。请用 -AndroidJar，或设置 WATCHWORKSHOP_ANDROID_JAR / WATCHWORKSHOP_ANDROID_SDK / ANDROID_SDK_ROOT / ANDROID_HOME，或把 Android SDK 放进 PATH / 当前工作目录 work。'
}
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path

# ---------------------------------------------------------------------------
# 3) 测试专用 org.json（不进 APK）
# ---------------------------------------------------------------------------
$jsonVersion = '20240303'
$jsonFileName = "json-$jsonVersion.jar"
$jsonSha256 = '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'
$depsDir = Join-Path $workRoot 'deps'
$cachedJson = Join-Path $depsDir $jsonFileName

if (-not $JsonJar) { $JsonJar = $env:WATCHWORKSHOP_JSON_JAR }
if (-not $JsonJar) { $JsonJar = Get-FirstExistingFile -Candidates @($cachedJson) }
if (-not $JsonJar -and (Test-Path -LiteralPath $workRoot)) {
    $inWork = @(Get-ChildItem -LiteralPath $workRoot -Recurse -Depth 4 -Filter $jsonFileName -File -ErrorAction SilentlyContinue |
        Select-Object -First 1)
    if ($inWork.Count -gt 0) { $JsonJar = $inWork[0].FullName }
}
if (-not $JsonJar) {
    New-Item -ItemType Directory -Path $depsDir -Force | Out-Null
    $url = "https://repo1.maven.org/maven2/org/json/json/$jsonVersion/$jsonFileName"
    Write-Host "下载测试依赖: $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $cachedJson -UseBasicParsing
    } catch {
        throw "无法取得测试用的 org.json（$url）：$($_.Exception.Message)。可用 -JsonJar 指定本地已有 jar。"
    }
    $hash = (Get-FileHash -LiteralPath $cachedJson -Algorithm SHA256).Hash
    if ($hash -ne $jsonSha256) {
        Remove-Item -LiteralPath $cachedJson -Force
        throw "下载的 $jsonFileName SHA-256 与预期不符（实际 $hash），已删除。可用 -JsonJar 指定本地已有 jar。"
    }
    $JsonJar = $cachedJson
}
if (!(Test-Path -LiteralPath $JsonJar -PathType Leaf)) { throw "指定的 -JsonJar 不存在: $JsonJar" }
$JsonJar = (Resolve-Path -LiteralPath $JsonJar).Path

# ---------------------------------------------------------------------------
# 4) fixture（工程内合成数据，只用于测试）
# ---------------------------------------------------------------------------
if (-not $FixtureDir) { $FixtureDir = $env:WATCHWORKSHOP_FIXTURES }
if (-not $FixtureDir) { $FixtureDir = Join-Path $projectRoot 'tests/fixtures' }
if (!(Test-Path -LiteralPath $FixtureDir -PathType Container)) {
    throw "fixture 目录不存在: $FixtureDir（可用 -FixtureDir 或 WATCHWORKSHOP_FIXTURES 指定）"
}
$FixtureDir = (Resolve-Path -LiteralPath $FixtureDir).Path

# ---------------------------------------------------------------------------
# 5) 构建目录（默认当前工作目录 work 下）
# ---------------------------------------------------------------------------
if (-not $BuildRoot) { $BuildRoot = $env:WATCHWORKSHOP_TEST_BUILD }
if (-not $BuildRoot) { $BuildRoot = Join-Path $workRoot 'timetable-json-tests' }
$outClasses = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Path $outClasses -Force | Out-Null

# ---------------------------------------------------------------------------
# 6) 编译真实 src + 两个测试类
# ---------------------------------------------------------------------------
$sources = @(
    (Join-Path $srcRoot 'TimetableData.java'),
    (Join-Path $srcRoot 'ShiguangJson.java'),
    (Join-Path $srcRoot 'TimetableFocus.java'),
    (Join-Path $PSScriptRoot 'ShiguangJsonTest.java'),
    (Join-Path $PSScriptRoot 'TimetableFocusTest.java')
)
foreach ($source in $sources) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "缺少源文件: $source" }
}

Write-Host "拾光课表测试: JDK         -> $JdkHome"
Write-Host "拾光课表测试: android.jar -> $AndroidJar"
Write-Host "拾光课表测试: org.json    -> $JsonJar（仅测试，不进 APK）"
Write-Host "拾光课表测试: fixtures    -> $FixtureDir"
Write-Host "拾光课表测试: class       -> $outClasses"

& (Join-Path $JdkHome 'bin/javac.exe') -encoding UTF-8 --release 8 -Xlint:-options -classpath "$AndroidJar;$JsonJar" -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { throw '拾光课表测试编译失败' }

# ---------------------------------------------------------------------------
# 7) 运行（org.json 走测试专用 jar；不连设备、不写工程）
# ---------------------------------------------------------------------------
$runtimeClasspath = "$outClasses;$JsonJar"

& (Join-Path $JdkHome 'bin/java.exe') @javaEncodingArgs -cp $runtimeClasspath dev.codex.watchworkshop.ShiguangJsonTest $FixtureDir
if ($LASTEXITCODE -ne 0) { throw 'ShiguangJsonTest 用例失败' }

& (Join-Path $JdkHome 'bin/java.exe') @javaEncodingArgs -cp $runtimeClasspath dev.codex.watchworkshop.TimetableFocusTest $FixtureDir
if ($LASTEXITCODE -ne 0) { throw 'TimetableFocusTest 用例失败' }

Write-Host '拾光课表测试: OK（ShiguangJsonTest + TimetableFocusTest）'
