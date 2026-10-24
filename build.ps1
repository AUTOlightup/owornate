#requires -Version 5.1
<#
.SYNOPSIS
    构建 OWOrnate 原生 Android 管理器 APK（版本号读自 AndroidManifest.xml）。

.DESCRIPTION
    独立 Java 8 + 原生 Views 工程，无第三方依赖。
    复用本机已有 JDK 与 Android SDK build-tools，只把 APK 产出到本项目 dist 目录。

    构建目录必须走纯 ASCII 路径：当前环境下 aapt2 打不开含中文的路径，
    而本项目目录名是中文，所以构建过程不直接在项目目录里读写。
    默认构建目录是 <当前目录>\work\watchworkshop-build，
    可用 -BuildRoot 或环境变量 WATCHWORKSHOP_BUILD_DIR 显式指定。
    脚本只清理由自己创建的 input / classes / dex 子目录与中间 APK，
    并逐个校验解析后的路径仍在该构建目录内，绝不整目录清空。
    原生工具（aapt2 / javac / d8 / zipalign / apksigner）只在构建目录内读写，
    最后用 PowerShell 把签名好的 APK 复制到 dist。

    工具链不写死任何机器路径：命令行参数 > 环境变量 > PATH(Get-Command) > 常见安装位置。
    找不到就带明确提示失败，不会随便猜一个 SDK / JDK 继续构建。

    签名：私钥与口令只放在工程之外的私有目录，默认
    <当前目录>\work\watchworkshop-private（可用 -SigningDir / WATCHWORKSHOP_SIGNING_DIR 覆盖）。
    兼容原来的 watchworkshop-dev-signing.properties；签名库缺失、口令缺失或别名不存在时
    直接失败，不会自动生成新证书、也不会改用其它证书。首发 1.0.0 沿用同一把既有签名库。

    -PublicRelease 使用公开发布名 OWOrnate-v<版本>.apk，并校验产物不是 debuggable；
    不带该开关时仍按通道命名 OWOrnate-v<版本>-code<码>-<通道>.apk。

    许可资产：工程根目录 assets\ 会原样打进 APK（aapt2 link -A），
    assets\licenses\*.txt 必须存在、非空且完整进包，否则直接失败。
    复制与链接都在纯 ASCII 构建目录内完成，不直接在中文项目目录里读写。

    不连接手表、不自动安装到设备、不 enable 无障碍服务、不注册开机广播以外的后台组件。

.PARAMETER SdkRoot
    Android SDK 根目录。环境变量：WATCHWORKSHOP_ANDROID_SDK、ANDROID_SDK_ROOT、ANDROID_HOME。

.PARAMETER JdkHome
    JDK 根目录（必须含 bin\javac.exe）。环境变量：WATCHWORKSHOP_JDK、JAVA_HOME。

.PARAMETER BuildRoot
    纯 ASCII 构建目录，只清脚本自有的 input / classes / dex 与中间 APK。
    环境变量：WATCHWORKSHOP_BUILD_DIR。

.PARAMETER SigningDir
    工程之外的私有签名目录，默认 <当前目录>\work\watchworkshop-private。
    拒绝把签名目录放进工程目录（公开交付物里不能有私钥）。环境变量：WATCHWORKSHOP_SIGNING_DIR。

.PARAMETER KeystorePath
    签名库完整路径。环境变量：WATCHWORKSHOP_KEYSTORE。

.PARAMETER KeyAlias
    签名别名。环境变量：WATCHWORKSHOP_KEY_ALIAS。

.PARAMETER SigningProperties
    私有 properties 文件，提供 storePassword、keyPassword、keyAlias、keystore、signingChannel。
    默认依次查找 <SigningDir>\signing.properties、watchworkshop-signing.properties、
    watchworkshop-dev-signing.properties。口令不得写进源码。

.PARAMETER NoClean
    保留已有构建目录内容（默认每次清空脚本自有的中间产物）。

.PARAMETER PublicRelease
    按公开发布命名产出 OWOrnate-v<版本>.apk，并校验产物不是 debuggable。

.EXAMPLE
    $env:WATCHWORKSHOP_ANDROID_SDK = 'D:\android-sdk'
    $env:WATCHWORKSHOP_JDK = 'C:\Program Files\Java\jdk-17'
    pwsh -File .\build.ps1 -PublicRelease

.EXAMPLE
    pwsh -File .\build.ps1 -SdkRoot D:\android-sdk -JdkHome C:\Java\jdk-17
#>
[CmdletBinding()]
param(
    [string]$SdkRoot,
    [string]$JdkHome,
    [string]$BuildRoot,
    [string]$SigningDir,
    [string]$KeystorePath,
    [string]$KeyAlias,
    [string]$SigningProperties,
    [switch]$NoClean,
    [switch]$PublicRelease
)

$ErrorActionPreference = 'Stop'
# Ship the same fixed widget-host payload used by the desktop fallback.
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'assets/widget-host') | Out-Null
foreach($name in @('widget-manage.sh','WatchHide-Launcher-Hook-v13.js')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "widget-host/$name") -Destination (Join-Path $PSScriptRoot "assets/widget-host/$name")
}
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'assets/capture') | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'capture/capture.sh') -Destination (Join-Path $PSScriptRoot 'assets/capture/capture.sh')

Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# 固定契约（与 AndroidManifest.xml 保持一致）
# ---------------------------------------------------------------------------
$PackageName         = 'dev.codex.watchworkshop'
$MainActivityName    = 'dev.codex.watchworkshop.MainActivity'
# 版本号只在 AndroidManifest.xml 里维护；这里和 make-release.ps1 都从它读取，不再两处手工同步。
[xml]$versionSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'AndroidManifest.xml') -Raw -Encoding UTF8
$VersionName         = $versionSource.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$VersionCode         = [int]$versionSource.manifest.GetAttribute('versionCode', 'http://schemas.android.com/apk/res/android')
$MinSdkVersion       = 26
$TargetSdkVersion    = 27
$CompileSdkVersion   = 35
$PreferredBuildTools = '35.0.0'

$ProjectRoot = $PSScriptRoot
$SrcDir      = Join-Path $ProjectRoot 'src'
$ResDir      = Join-Path $ProjectRoot 'res'
$Manifest    = Join-Path $ProjectRoot 'AndroidManifest.xml'
$AssetsDir   = Join-Path $ProjectRoot 'assets'
$DistDir     = Join-Path $ProjectRoot 'dist'

# 签名目录默认在工程之外：工程目录本身是公开交付物，绝不能放私钥。
$DefaultSigningDir = Join-Path (Get-Location).Path 'work\watchworkshop-private'

# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------
function Test-AsciiPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    foreach ($character in $Path.ToCharArray()) {
        if ([int]$character -gt 127) { return $false }
    }
    return $true
}

function Read-PrivateProperties {
    param([string]$Path)
    $table = @{}
    if ([string]::IsNullOrWhiteSpace($Path)) { return $table }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $table }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        $text = $line.Trim()
        if ($text.Length -eq 0) { continue }
        if ($text.StartsWith('#') -or $text.StartsWith(';')) { continue }
        $index = $text.IndexOf('=')
        if ($index -lt 1) { $index = $text.IndexOf(':') }
        if ($index -lt 1) { continue }
        $name = $text.Substring(0, $index).Trim()
        $value = $text.Substring($index + 1).Trim()
        if ($name.Length -gt 0) { $table[$name] = $value }
    }
    return $table
}

function Test-JdkRoot {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    return (Test-Path -LiteralPath (Join-Path $Path 'bin\javac.exe') -PathType Leaf)
}

function Get-JdkRootFromCommand {
    foreach ($name in @('javac.exe', 'javac')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $command -or [string]::IsNullOrWhiteSpace($command.Source)) { continue }
        $root = Split-Path -Parent (Split-Path -Parent $command.Source)
        if (Test-JdkRoot -Path $root) { return $root }
    }
    return $null
}

function Get-JdkRootFromCommonLocations {
    $roots = @()
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if ([string]::IsNullOrWhiteSpace($base)) { continue }
        $roots += (Join-Path $base 'Java')
        $roots += (Join-Path $base 'Eclipse Adoptium')
        $roots += (Join-Path $base 'Microsoft')
        $roots += (Join-Path $base 'Amazon Corretto')
        $roots += (Join-Path $base 'Android\Android Studio\jbr')
        $roots += (Join-Path $base 'Programs\Eclipse Adoptium')
        $roots += (Join-Path $base 'Programs\Microsoft')
    }
    $candidates = @()
    foreach ($root in $roots) {
        if (Test-JdkRoot -Path $root) { $candidates += $root }
        if (-not (Test-Path -LiteralPath $root -PathType Container)) { continue }
        foreach ($child in @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
            if (Test-JdkRoot -Path $child.FullName) { $candidates += $child.FullName }
        }
    }
    if ($candidates.Count -eq 0) { return $null }
    return $candidates[0]
}

function Test-AndroidSdkRoot {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'build-tools') -PathType Container)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $Path 'platforms') -PathType Container)) { return $false }
    return $true
}

function Get-AndroidSdkRootFromCommand {
    foreach ($name in @('aapt2.exe', 'sdkmanager.bat', 'adb.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $command -or [string]::IsNullOrWhiteSpace($command.Source)) { continue }
        $dir = Split-Path -Parent $command.Source
        for ($level = 0; $level -lt 3; $level++) {
            if (Test-AndroidSdkRoot -Path $dir) { return $dir }
            $parent = Split-Path -Parent $dir
            if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $dir) { break }
            $dir = $parent
        }
    }
    return $null
}

function Get-AndroidSdkRootFromCommonLocations {
    $roots = @()
    foreach ($base in @($env:LOCALAPPDATA, $env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ([string]::IsNullOrWhiteSpace($base)) { continue }
        $roots += (Join-Path $base 'Android\Sdk')
    }
    if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
        $roots += (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
    }
    foreach ($root in $roots) {
        if (Test-AndroidSdkRoot -Path $root) { return $root }
    }
    return $null
}

# ---------------------------------------------------------------------------
# 1. 定位工具链（参数 > 环境变量 > PATH > 常见安装位置，不写死机器路径）
# ---------------------------------------------------------------------------
# 注意：PowerShell 变量不区分大小写，这里不能用 $sdkRoot / $jdkRoot，
# 否则会把同名参数 $SdkRoot / $JdkHome 覆盖掉。
$sdkRootPath = $null
foreach ($candidate in @($SdkRoot, $env:WATCHWORKSHOP_ANDROID_SDK, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    if (-not (Test-AndroidSdkRoot -Path $candidate)) {
        throw ('SdkRoot 不是可用的 Android SDK 根目录（需要同时含 build-tools 与 platforms）: ' + $candidate)
    }
    $sdkRootPath = (Resolve-Path -LiteralPath $candidate).Path
    break
}
if (-not $sdkRootPath) { $sdkRootPath = Get-AndroidSdkRootFromCommand }
if (-not $sdkRootPath) { $sdkRootPath = Get-AndroidSdkRootFromCommonLocations }
if (-not $sdkRootPath) {
    throw '未找到 Android SDK。请用 -SdkRoot 或环境变量 WATCHWORKSHOP_ANDROID_SDK / ANDROID_SDK_ROOT / ANDROID_HOME 指定含 build-tools 与 platforms 的 SDK 根目录。'
}

$jdkRootPath = $null
foreach ($candidate in @($JdkHome, $env:WATCHWORKSHOP_JDK, $env:JAVA_HOME)) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    if (-not (Test-JdkRoot -Path $candidate)) {
        throw ('JdkHome 不是含 bin\javac.exe 的 JDK 根目录: ' + $candidate)
    }
    $jdkRootPath = (Resolve-Path -LiteralPath $candidate).Path
    break
}
if (-not $jdkRootPath) { $jdkRootPath = Get-JdkRootFromCommand }
if (-not $jdkRootPath) { $jdkRootPath = Get-JdkRootFromCommonLocations }
if (-not $jdkRootPath) {
    throw '未找到 JDK。请用 -JdkHome 或环境变量 WATCHWORKSHOP_JDK / JAVA_HOME 指定含 bin\javac.exe 的 JDK 根目录。'
}

$buildToolsRoot = Join-Path $sdkRootPath 'build-tools'
if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
    throw ('Android SDK 缺少 build-tools 目录: ' + $buildToolsRoot)
}

$buildToolsDir = Join-Path $buildToolsRoot $PreferredBuildTools
if (-not (Test-Path -LiteralPath $buildToolsDir -PathType Container)) {
    $available = @(Get-ChildItem -LiteralPath $buildToolsRoot -Directory | Sort-Object Name)
    if ($available.Count -eq 0) { throw ('Android SDK build-tools 目录为空: ' + $buildToolsRoot) }
    $buildToolsDir = $available[-1].FullName
    Write-Warning ('未找到 build-tools ' + $PreferredBuildTools + '，改用 ' + $available[-1].Name)
}

$androidJar = Join-Path (Join-Path $sdkRootPath ('platforms\android-' + $CompileSdkVersion)) 'android.jar'
if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
    $platforms = @(Get-ChildItem -LiteralPath (Join-Path $sdkRootPath 'platforms') -Directory -ErrorAction SilentlyContinue | Sort-Object Name)
    if ($platforms.Count -eq 0) { throw ('未找到 Android platform: platforms\android-' + $CompileSdkVersion) }
    $androidJar = Join-Path $platforms[-1].FullName 'android.jar'
    Write-Warning ('未找到 android-' + $CompileSdkVersion + '，改用 ' + $platforms[-1].Name)
}

$aapt2Exe     = Join-Path $buildToolsDir 'aapt2.exe'
$d8Bat        = Join-Path $buildToolsDir 'd8.bat'
$zipalignExe  = Join-Path $buildToolsDir 'zipalign.exe'
$apksignerBat = Join-Path $buildToolsDir 'apksigner.bat'
$javacExe     = Join-Path $jdkRootPath 'bin\javac.exe'

$requiredTools = [ordered]@{
    'aapt2'       = $aapt2Exe
    'd8'          = $d8Bat
    'zipalign'    = $zipalignExe
    'apksigner'   = $apksignerBat
    'javac'       = $javacExe
    'android.jar' = $androidJar
}
foreach ($toolName in $requiredTools.Keys) {
    if (-not (Test-Path -LiteralPath $requiredTools[$toolName] -PathType Leaf)) {
        throw ('缺少构建工具 ' + $toolName + ' : ' + $requiredTools[$toolName])
    }
}

# apksigner / d8 都依赖 JAVA_HOME
$env:JAVA_HOME = $jdkRootPath
$env:PATH = (Join-Path $jdkRootPath 'bin') + ';' + $env:PATH

# ---------------------------------------------------------------------------
# 2. 选择纯 ASCII 构建目录（默认 <当前目录>\work\watchworkshop-build）
# ---------------------------------------------------------------------------
$buildCandidates = @(
    $BuildRoot,
    $env:WATCHWORKSHOP_BUILD_DIR,
    (Join-Path (Get-Location).Path 'work\watchworkshop-build')
)
$stagingRoot = $null
foreach ($candidate in $buildCandidates) {
    if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
    $fullCandidate = [System.IO.Path]::GetFullPath($candidate)
    if (-not (Test-AsciiPath $fullCandidate)) { continue }
    $stagingRoot = $fullCandidate
    break
}
if (-not $stagingRoot) {
    throw '构建目录必须是纯 ASCII 路径。请用 -BuildRoot 或 WATCHWORKSHOP_BUILD_DIR 指定。'
}

$stageInput    = Join-Path $stagingRoot 'input'
$stageRes      = Join-Path $stageInput 'res'
$stageAssets   = Join-Path $stageInput 'assets'
$stageManifest = Join-Path $stageInput 'AndroidManifest.xml'
$classesDir    = Join-Path $stagingRoot 'classes'
$dexDir        = Join-Path $stagingRoot 'dex'
$stagingRootFull = [System.IO.Path]::GetFullPath($stagingRoot).TrimEnd('\') + '\'

function Clear-OwnedStagingItem {
    param([string]$Path)
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($stagingRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ('拒绝清理构建目录之外的路径: ' + $fullPath)
    }
    if (Test-Path -LiteralPath $fullPath) { Remove-Item -LiteralPath $fullPath -Recurse -Force }
}

New-Item -ItemType Directory -Force -Path $stagingRoot | Out-Null

# 只清理脚本自有的子项，绝不清空调用方给的整个构建目录
if (-not $NoClean) {
    foreach ($ownedItem in @(
        $stageInput,
        $classesDir,
        $dexDir,
        (Join-Path $stagingRoot 'resources.zip'),
        (Join-Path $stagingRoot 'app-unsigned.apk'),
        (Join-Path $stagingRoot 'app-aligned.apk')
    )) {
        Clear-OwnedStagingItem -Path $ownedItem
    }
}

New-Item -ItemType Directory -Force -Path $stageInput, $classesDir, $dexDir | Out-Null

Write-Host '--------------------------------------------------------------'
Write-Host ('包名            : ' + $PackageName)
Write-Host ('启动组件        : ' + $MainActivityName)
Write-Host ('版本            : v' + $VersionName + ' (versionCode ' + $VersionCode + ')')
Write-Host ('SDK 范围        : min ' + $MinSdkVersion + ' / target ' + $TargetSdkVersion + ' / compile ' + $CompileSdkVersion)
Write-Host ('项目目录        : ' + $ProjectRoot)
Write-Host ('构建目录        : ' + $stagingRoot)
Write-Host ('Android SDK     : ' + $sdkRootPath)
Write-Host ('Build-tools     : ' + $buildToolsDir)
Write-Host ('android.jar     : ' + $androidJar)
Write-Host ('JDK             : ' + $jdkRootPath)
Write-Host ('aapt2           : ' + $aapt2Exe)
Write-Host ('javac           : ' + $javacExe)
Write-Host ('d8              : ' + $d8Bat)
Write-Host ('zipalign        : ' + $zipalignExe)
Write-Host ('apksigner       : ' + $apksignerBat)
Write-Host '--------------------------------------------------------------'

# ---------------------------------------------------------------------------
# 3. 输入检查 + 复制到 ASCII 构建目录
# ---------------------------------------------------------------------------
$requiredFiles = @(
    $Manifest,
    (Join-Path $ResDir 'values\styles.xml'),
    (Join-Path $ResDir 'drawable\ic_launcher.xml'),
    (Join-Path $ResDir 'xml\runtime_accessibility.xml')
)
foreach ($file in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw ('缺少必需文件: ' + $file) }
}

# 许可资产必须存在且非空：APK 内固定携带 assets/licenses/*.txt
$licenseAssetDir = Join-Path $AssetsDir 'licenses'
if (-not (Test-Path -LiteralPath $licenseAssetDir -PathType Container)) {
    throw ('缺少许可资产目录: ' + $licenseAssetDir + '（APK 必须带 assets/licenses/*.txt）')
}
$licenseAssetFiles = @(Get-ChildItem -LiteralPath $licenseAssetDir -Filter '*.txt' -File | Sort-Object Name)
if ($licenseAssetFiles.Count -eq 0) { throw ('许可资产目录里没有 *.txt: ' + $licenseAssetDir) }
$emptyLicenseAssets = @($licenseAssetFiles | Where-Object { $_.Length -eq 0 })
if ($emptyLicenseAssets.Count -gt 0) {
    throw ('许可资产是空文件: ' + (($emptyLicenseAssets | ForEach-Object { $_.Name }) -join ', '))
}

if (-not (Test-Path -LiteralPath $SrcDir -PathType Container)) { throw ('缺少源码目录: ' + $SrcDir) }

$javaSources = @(Get-ChildItem -LiteralPath $SrcDir -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
if ($javaSources.Count -eq 0) {
    throw ('未找到 Java 源码（' + $SrcDir + '\dev\codex\watchworkshop\*.java）。')
}

# The APK carries only the self-owned coordinator; feature payloads come from imported packages.
$moduleAssets=Join-Path $AssetsDir 'monet'
$runtimeControl = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'standalone/runtime-manage.sh'))
foreach ($pin in @(@{Name='runtime-core.js'; Key='CORE_SHA'}, @{Name='runtime-commit.js'; Key='COMMIT_SHA'}, @{Name='runtime-service.sh'; Key='SERVICE_SHA'})) {
    $digest = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot ('standalone/' + $pin.Name)) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($runtimeControl -notmatch ('(?m)^' + $pin.Key + '=' + $digest + '\r?$')) {
        throw ('Runtime payload pin does not match: ' + $pin.Name)
    }
}
foreach($scriptName in @('module-manage.sh','runtime-core.js','runtime-commit.js','runtime-service.sh','runtime-manage.sh')){
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "standalone/$scriptName") -Destination (Join-Path $moduleAssets $scriptName) -Force
}
Copy-Item -LiteralPath $Manifest -Destination $stageManifest -Force
Copy-Item -LiteralPath $ResDir -Destination $stageInput -Recurse -Force
Copy-Item -LiteralPath $AssetsDir -Destination $stageInput -Recurse -Force
# Historical payloads remain in the source checkout for compatibility tests, not in new APKs.
foreach($legacy in @('runtime-hook.js','theme.jar','hook.js','monet-service.sh')){
    $stagedLegacy=Join-Path $stageAssets ('monet/'+$legacy)
    if(Test-Path -LiteralPath $stagedLegacy){Remove-Item -LiteralPath $stagedLegacy -Force}
}

# ---------------------------------------------------------------------------
# 4. 资源编译 + 链接（aapt2，全程 ASCII 路径）
# ---------------------------------------------------------------------------
$compiledRes = Join-Path $stagingRoot 'resources.zip'
$unsignedApk = Join-Path $stagingRoot 'app-unsigned.apk'
$alignedApk  = Join-Path $stagingRoot 'app-aligned.apk'
$generatedJava = Join-Path $stageInput 'generated-java'
New-Item -ItemType Directory -Path $generatedJava -Force | Out-Null

& $aapt2Exe compile --dir $stageRes -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

$aapt2LinkArgs = @(
    'link',
    '-o', $unsignedApk,
    '-I', $androidJar,
    '--manifest', $stageManifest,
    '--min-sdk-version', $MinSdkVersion,
    '--target-sdk-version', $TargetSdkVersion,
    '--version-code', $VersionCode,
    '--version-name', $VersionName,
    '--java', $generatedJava,
    '-A', $stageAssets,
    $compiledRes
)
& $aapt2Exe @aapt2LinkArgs
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# 链接产物自检：版本必须与固定契约一致，且不得是 debuggable
$badgingOutput = & $aapt2Exe dump badging $unsignedApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 dump badging 失败' }
$badgingText = (($badgingOutput | ForEach-Object { [string]$_ }) -join "`n")
if ($badgingText -notmatch ("versionCode='" + $VersionCode + "'")) {
    throw ('链接产物 versionCode 不是 ' + $VersionCode + '：' + $badgingText)
}
if ($badgingText -notmatch ("versionName='" + [regex]::Escape($VersionName) + "'")) {
    throw ('链接产物 versionName 不是 ' + $VersionName + '：' + $badgingText)
}

$manifestTree = & $aapt2Exe dump xmltree --file AndroidManifest.xml $unsignedApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'aapt2 dump xmltree 失败' }
$debuggableLine = @($manifestTree | Where-Object { [string]$_ -match ':debuggable\(' } | Select-Object -First 1)
if ($debuggableLine.Count -gt 0 -and [string]$debuggableLine[0] -match '=true') {
    throw '产物被标记为 debuggable，拒绝产出发布包。'
}

# ---------------------------------------------------------------------------
# 5. Java 8 编译（源文件 UTF-8，无第三方依赖）
# ---------------------------------------------------------------------------
$javacArgs = @(
    '-source', '8',
    '-target', '8',
    '-encoding', 'UTF-8',
    '-Xlint:-options',
    '-classpath', $androidJar,
    '-d', $classesDir
) + $javaSources + @(Get-ChildItem -LiteralPath $generatedJava -Filter '*.java' -Recurse -File | ForEach-Object { $_.FullName })
# javac writes a deprecation NOTE to stderr (harmless; it still exits 0). Under
# $ErrorActionPreference='Stop' any native stderr aborts the script before the
# exit-code check, so drop to Continue just for this call and gate on the code.
$savedEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $javacExe @javacArgs
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $savedEap
if ($javacExit -ne 0) { throw 'javac 编译失败' }

$classFiles = @(Get-ChildItem -LiteralPath $classesDir -Filter '*.class' -Recurse -File | ForEach-Object { $_.FullName })
if ($classFiles.Count -eq 0) { throw ('编译未产出 class 文件: ' + $classesDir) }

# ---------------------------------------------------------------------------
# 6. dex（d8）
# ---------------------------------------------------------------------------
$classesJar = Join-Path $stagingRoot 'classes.jar'
& (Join-Path $jdkRootPath 'bin\jar.exe') cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'class JAR 打包失败' }
# A single JAR also keeps d8.bat below cmd.exe's command-line length limit.
$d8Args = @('--min-api', $MinSdkVersion, '--lib', $androidJar, '--output', $dexDir, $classesJar)
& $d8Bat @d8Args
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path -LiteralPath $dexFile -PathType Leaf)) { throw 'd8 未生成 classes.dex' }

# ---------------------------------------------------------------------------
# 7. 把 classes.dex 写进 APK
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $existingEntry = $archive.GetEntry('classes.dex')
    if ($null -ne $existingEntry) { $existingEntry.Delete() }
    [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archive,
        $dexFile,
        'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal
    )
}
finally {
    $archive.Dispose()
}

# ---------------------------------------------------------------------------
# 7a. 资产路径规范化
#     aapt2 在 Windows 上用反斜杠记录 -A 带进来的资产（assets\licenses\x.txt），
#     统一改写成 zip 规范的正斜杠，保证任何平台解包/查看都是 assets/licenses/x.txt。
# ---------------------------------------------------------------------------
$renameArchive = [System.IO.Compression.ZipFile]::Open($unsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $renameTargets = @($renameArchive.Entries | Where-Object { $_.FullName.Contains('\') })
    foreach ($entry in $renameTargets) {
        $newName = $entry.FullName.Replace('\', '/')
        $buffer = New-Object System.IO.MemoryStream
        $source = $entry.Open()
        $source.CopyTo($buffer)
        $source.Dispose()
        $buffer.Position = 0
        $newEntry = $renameArchive.CreateEntry($newName, [System.IO.Compression.CompressionLevel]::Optimal)
        $target = $newEntry.Open()
        $buffer.CopyTo($target)
        $target.Dispose()
        $buffer.Dispose()
        $entry.Delete()
    }
} finally {
    $renameArchive.Dispose()
}

# ---------------------------------------------------------------------------
# 7b. 资产自检：许可文件必须完整进包
# ---------------------------------------------------------------------------
$apkArchive = [System.IO.Compression.ZipFile]::OpenRead($unsignedApk)
try {
    $apkAssetEntries = @($apkArchive.Entries |
        Where-Object { ($_.FullName.Replace('\', '/') -like 'assets/licenses/*') -and -not $_.FullName.EndsWith('/') })
} finally {
    $apkArchive.Dispose()
}
if ($apkAssetEntries.Count -eq 0) { throw 'APK 内没有 assets/licenses/*.txt，许可资产未打进去。' }
$apkAssetNames = @($apkAssetEntries | ForEach-Object { $_.FullName.Replace('\', '/') })
$expectedAssetNames = @($licenseAssetFiles | ForEach-Object { 'assets/licenses/' + $_.Name })
$missingAssets = @($expectedAssetNames | Where-Object { $apkAssetNames -notcontains $_ })
if ($missingAssets.Count -gt 0) {
    throw ('APK 缺少许可资产: ' + ($missingAssets -join ', '))
}
$blankAssets = @($apkAssetEntries | Where-Object { $_.Length -eq 0 } | ForEach-Object { $_.FullName })
if ($blankAssets.Count -gt 0) {
    throw ('APK 内许可资产为空: ' + ($blankAssets -join ', '))
}

# ---------------------------------------------------------------------------
# 8. 对齐（zipalign）
# ---------------------------------------------------------------------------
& $zipalignExe -f 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

# ---------------------------------------------------------------------------
# 9. 私有签名（工程外）：参数 / 环境变量 / 私有 properties
#    口令与私钥都不进源码，也不会被打印；缺签名直接失败，不自动生成或换证书
# ---------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($SigningDir)) { $SigningDir = $env:WATCHWORKSHOP_SIGNING_DIR }
if ([string]::IsNullOrWhiteSpace($SigningDir)) { $SigningDir = $DefaultSigningDir }
$SigningDir = [System.IO.Path]::GetFullPath($SigningDir)
$projectRootFull = [System.IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
$signingDirFull  = $SigningDir.TrimEnd('\')
if ($signingDirFull -eq $projectRootFull -or
    $signingDirFull.StartsWith($projectRootFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw ('签名目录不能放在工程目录内（工程目录是公开交付物）: ' + $SigningDir + [Environment]::NewLine +
        '请用 -SigningDir 或 WATCHWORKSHOP_SIGNING_DIR 指到工程之外。')
}
if (-not (Test-Path -LiteralPath $SigningDir -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
}

# 收紧私有目录权限到当前用户（失败不阻断构建）
try {
    $currentUser = (& whoami).Trim()
    & icacls $SigningDir /inheritance:r /grant:r ($currentUser + ':(OI)(CI)F') | Out-Null
}
catch {
    Write-Warning ('未能收紧私有签名目录 ACL: ' + $_.Exception.Message)
}

if ([string]::IsNullOrWhiteSpace($SigningProperties)) { $SigningProperties = $env:WATCHWORKSHOP_SIGNING_PROPERTIES }
if (-not [string]::IsNullOrWhiteSpace($SigningProperties)) {
    if (-not (Test-Path -LiteralPath $SigningProperties -PathType Leaf)) {
        throw ('指定的 SigningProperties 不存在: ' + $SigningProperties)
    }
}
else {
    $candidateProperties = @(
        (Join-Path $SigningDir 'signing.properties'),
        (Join-Path $SigningDir 'watchworkshop-signing.properties'),
        (Join-Path $SigningDir 'watchworkshop-dev-signing.properties')
    )
    foreach ($candidate in $candidateProperties) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $SigningProperties = $candidate; break }
    }
}
$privateProperties = Read-PrivateProperties -Path $SigningProperties

if ([string]::IsNullOrWhiteSpace($KeystorePath)) { $KeystorePath = $env:WATCHWORKSHOP_KEYSTORE }
if ([string]::IsNullOrWhiteSpace($KeystorePath) -and $privateProperties.ContainsKey('keystore')) {
    $propertiesKeystore = $privateProperties['keystore']
    if ([System.IO.Path]::IsPathRooted($propertiesKeystore)) { $KeystorePath = $propertiesKeystore }
    else { $KeystorePath = Join-Path $SigningDir $propertiesKeystore }
}
if ([string]::IsNullOrWhiteSpace($KeystorePath)) { $KeystorePath = Join-Path $SigningDir 'watchworkshop-release.keystore' }
$KeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)

if ([string]::IsNullOrWhiteSpace($KeyAlias)) { $KeyAlias = $env:WATCHWORKSHOP_KEY_ALIAS }
if ([string]::IsNullOrWhiteSpace($KeyAlias) -and $privateProperties.ContainsKey('keyAlias')) { $KeyAlias = $privateProperties['keyAlias'] }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { $KeyAlias = 'watchworkshop' }

$storePassword = $env:WATCHWORKSHOP_KEYSTORE_PASSWORD
if ([string]::IsNullOrWhiteSpace($storePassword) -and $privateProperties.ContainsKey('storePassword')) {
    $storePassword = $privateProperties['storePassword']
}
$keyPassword = $env:WATCHWORKSHOP_KEY_PASSWORD
if ([string]::IsNullOrWhiteSpace($keyPassword) -and $privateProperties.ContainsKey('keyPassword')) {
    $keyPassword = $privateProperties['keyPassword']
}
if ([string]::IsNullOrWhiteSpace($keyPassword)) { $keyPassword = $storePassword }

# 通道标记持久化读取：dev / release 决定默认文件名后缀；-PublicRelease 强制 release
$signingChannel = 'dev'
if ($privateProperties.ContainsKey('signingChannel')) {
    $configuredChannel = $privateProperties['signingChannel'].Trim().ToLowerInvariant()
    if ($configuredChannel.Length -gt 0) { $signingChannel = $configuredChannel }
}
if ($PublicRelease) { $signingChannel = 'release' }

if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
    throw ('找不到签名库，拒绝自动生成或改用其它证书: ' + $KeystorePath + [Environment]::NewLine +
        '请把既有 keystore 放回 ' + $SigningDir +
        '，或用 -KeystorePath / WATCHWORKSHOP_KEYSTORE 指定。原私钥必须长期保留，否则无法同签覆盖升级。')
}
if ([string]::IsNullOrWhiteSpace($storePassword)) {
    throw ('签名库已存在但未提供口令: ' + $KeystorePath + [Environment]::NewLine +
        '请设置环境变量 WATCHWORKSHOP_KEYSTORE_PASSWORD，或在 ' + $SigningDir +
        ' 放 signing.properties（storePassword=...，keyAlias=...）。口令绝不能写入源码。')
}
$signingDescription = '既有签名库（同签覆盖升级）: ' + $KeystorePath

# ---------------------------------------------------------------------------
# 10. 签名 + 校验
# ---------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
if ($PublicRelease) {
    $outputName = 'OWOrnate-v' + $VersionName + '.apk'
}
else {
    $outputName = 'OWOrnate-v' + $VersionName + '-code' + $VersionCode + '-' + $signingChannel + '.apk'
}
$distApk = Join-Path $DistDir $outputName
$stagedApk = Join-Path $stagingRoot $outputName
if (Test-Path -LiteralPath $stagedApk -PathType Leaf) { Remove-Item -LiteralPath $stagedApk -Force }

# 口令走环境变量，避免出现在命令行参数里
$env:WATCHWORKSHOP_KS_PASS = $storePassword
$env:WATCHWORKSHOP_KEY_PASS = $keyPassword
try {
    $signArgs = @(
        'sign',
        '--ks', $KeystorePath,
        '--ks-key-alias', $KeyAlias,
        '--ks-pass', 'env:WATCHWORKSHOP_KS_PASS',
        '--key-pass', 'env:WATCHWORKSHOP_KEY_PASS',
        '--out', $stagedApk,
        $alignedApk
    )
    & $apksignerBat @signArgs
    if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }
}
finally {
    Remove-Item Env:WATCHWORKSHOP_KS_PASS -ErrorAction SilentlyContinue
    Remove-Item Env:WATCHWORKSHOP_KEY_PASS -ErrorAction SilentlyContinue
}

$verifyOutput = & $apksignerBat verify --verbose --print-certs $stagedApk 2>&1
$verifyExit = $LASTEXITCODE
$verifyOutput | ForEach-Object { Write-Host ([string]$_) }
if ($verifyExit -ne 0) { throw 'apksigner 校验失败' }

$signerFingerprint = ''
$fingerprintLine = $verifyOutput | Where-Object { [string]$_ -match 'SHA-256 digest' } | Select-Object -First 1
if ($fingerprintLine) {
    $fingerprintMatch = [regex]::Match([string]$fingerprintLine, '([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){31}|[0-9a-fA-F]{64})')
    if ($fingerprintMatch.Success) {
        $fingerprintHex = $fingerprintMatch.Groups[1].Value -replace ':', ''
        $fingerprintPairs = @()
        for ($index = 0; $index -lt $fingerprintHex.Length; $index += 2) {
            $fingerprintPairs += $fingerprintHex.Substring($index, 2)
        }
        $signerFingerprint = ($fingerprintPairs -join ':').ToUpperInvariant()
    }
}

# ---------------------------------------------------------------------------
# 11. 产出到项目 dist（PowerShell 复制，绕开原生工具的中文路径限制）
# ---------------------------------------------------------------------------
if (Test-Path -LiteralPath $distApk -PathType Leaf) { Remove-Item -LiteralPath $distApk -Force }
Copy-Item -LiteralPath $stagedApk -Destination $distApk -Force

$apkInfo = Get-Item -LiteralPath $distApk
Write-Host '--------------------------------------------------------------'
Write-Host ('构建完成        : ' + $apkInfo.FullName)
Write-Host ('文件名          : ' + $apkInfo.Name)
Write-Host ('大小            : ' + $apkInfo.Length + ' bytes (' + [math]::Round($apkInfo.Length / 1KB, 1) + ' KB)')
Write-Host ('签名            : ' + $signingDescription)
Write-Host ('签名通道        : ' + $signingChannel)
if ($PublicRelease) { Write-Host '发布命名        : OWOrnate-v<版本>.apk（公开发布候选）' }
Write-Host ('签名指纹 SHA-256: ' + $signerFingerprint)
Write-Host 'debuggable      : 否（已用 aapt2 dump xmltree 校验清单）'
Write-Host ('许可资产        : ' + $licenseAssetFiles.Count + ' 个 assets/licenses/*.txt 已进包并核对')
Write-Host ('安装            : 未执行。本脚本不调用 adb，不连接任何手表。')
Write-Host '--------------------------------------------------------------'

Write-Output $distApk
