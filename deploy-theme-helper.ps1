#requires -Version 5.1
<#
.SYNOPSIS
    把主题 helper v6 部署到手表：只替换 /data/local/tmp/watchface-theme/theme.jar。

.DESCRIPTION
    用户在需要时手动运行。构建脚本（build.ps1 / runtime-helper\build.ps1）不会调用它，
    也不会自动连接或安装到设备。

    脚本只做一件事：把 runtime-helper\dist\WatchTheme-helper-v6.jar 覆盖到设备的
    /data/local/tmp/watchface-theme/theme.jar，然后在核验 /proc/<pid>/cmdline 后
    kill -9 该 Launcher 进程，让既有 supervisor 自动重注入，等待 hello.json 出现新的 helper v6。

    绝不触碰：现有综合 Hook（hook.js）、supervisor（watchhide-bootstrap.sh）、adbd、
    QQ 数据与 userdata。脚本不做目录递归删除，只对固定的两个文件路径做 mkdir / cp / mv / rm -f。

    部署前核对：设备序列号、root 身份、综合 Hook 基线哈希、设备现有 theme.jar 哈希
    （只接受旧 helper v5 或本次新 helper v6）、本机待部署 jar 哈希。
    失败时自动恢复设备备份 jar 并再重载一次。

.PARAMETER Serial
    目标设备序列号或 host:port。

.PARAMETER ExpectedHardware
    ro.serialno 期望值，防止误操作到别的设备。

.PARAMETER Adb
    adb 可执行文件完整路径。

.PARAMETER HelperJar
    待部署的本机 jar，默认 <脚本目录>\runtime-helper\dist\WatchTheme-helper-v6.jar。

.PARAMETER ExpectedHookSha
    现有综合 Hook 的 SHA256 基线；不一致即中止。

.PARAMETER OldThemeSha
    设备原有 helper v5 的 SHA256，允许作为部署前状态。

.PARAMETER NewHelperSha
    本次新 helper 的 SHA256。本机 jar 必须与它一致；设备现有 theme.jar 也只接受旧值或该新值。
    重新构建过 jar 时请显式传入新的值。

.EXAMPLE
    .\deploy-theme-helper.ps1 -Serial <target> -ExpectedHardware <hardware> -Adb <adb-path>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$ExpectedHardware,
    [string]$Adb = '',
    [string]$HelperJar,
    [string]$ExpectedHookSha = '6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722',
    [string]$OldThemeSha = '715a624b78d1ede21e81a2bfb93b44eb254dbdf77db2eefd26741f745f9851cd',
    [string]$NewHelperSha = '706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# 固定契约（与 README / docs 一致，不要在运行时改变目标路径）
# ---------------------------------------------------------------------------
$LauncherPackage       = 'com.heytap.wearable.launcher'
$DeviceThemeDir        = '/data/local/tmp/watchface-theme'
$DeviceJar             = $DeviceThemeDir + '/theme.jar'
$DeviceMailbox         = '/data/user_de/0/' + $LauncherPackage + '/files/watchworkshop-theme'
$HookPath              = '/data/local/tmp/watchhide/hook.js'
$SupervisorPath        = '/data/local/tmp/watchhide/watchhide-bootstrap.sh'
$ExpectedProtocol      = 1
$ExpectedHelperVersion = 6
$WaitAttempts          = 60
$WaitMilliseconds      = 500

# ---------------------------------------------------------------------------
# adb 辅助
# ---------------------------------------------------------------------------
function Invoke-AdbRaw {
    param([string[]]$Arguments)
    $output = & $Adb -s $Serial @Arguments 2>&1
    $code = $LASTEXITCODE
    $text = ''
    if ($null -ne $output) { $text = (($output | ForEach-Object { [string]$_ }) -join "`n") }
    return @{ Code = $code; Output = $text }
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = Invoke-AdbRaw -Arguments $Arguments
    if ($result.Code -ne 0) {
        throw ('adb ' + ($Arguments -join ' ') + ' 失败（exit ' + $result.Code + '）：' + $result.Output)
    }
    return $result.Output
}

function Invoke-Shell {
    param([string]$Command)
    return (Invoke-Adb -Arguments @('shell', $Command)).Trim()
}

function Invoke-ShellRaw {
    param([string]$Command)
    return Invoke-AdbRaw -Arguments @('shell', $Command)
}

function Get-DeviceSha {
    param([string]$Path)
    $text = Invoke-Shell -Command ('sha256sum ' + $Path)
    if ([string]::IsNullOrWhiteSpace($text)) { return '' }
    $match = [regex]::Match($text, '[0-9a-fA-F]{64}')
    if (-not $match.Success) { return '' }
    return $match.Value.ToLowerInvariant()
}

function Get-LocalSha {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Sha {
    param([string]$Label, [string]$Actual, [string]$Expected)
    if ($Actual -ne $Expected) {
        throw ($Label + ' SHA256 不一致。期望 ' + $Expected + '，实际 ' + $Actual)
    }
}

# ---------------------------------------------------------------------------
# 0. 本机参数与 jar 核对
# ---------------------------------------------------------------------------
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) { throw ('找不到 adb: ' + $Adb) }

if ([string]::IsNullOrWhiteSpace($HelperJar)) {
    $HelperJar = Join-Path $PSScriptRoot 'runtime-helper\dist\WatchTheme-helper-v6.jar'
}
$HelperJar = [System.IO.Path]::GetFullPath($HelperJar)
if (-not (Test-Path -LiteralPath $HelperJar -PathType Leaf)) { throw ('找不到待部署 jar: ' + $HelperJar) }

$ExpectedHookSha = $ExpectedHookSha.ToLowerInvariant()
$OldThemeSha     = $OldThemeSha.ToLowerInvariant()
$NewHelperSha    = $NewHelperSha.ToLowerInvariant()

$localSha = Get-LocalSha -Path $HelperJar
if ($localSha -ne $NewHelperSha) {
    throw ('本机 jar SHA256 与预期新 helper 不一致。' + "`n" +
        '文件：' + $HelperJar + "`n" +
        '实际：' + $localSha + "`n" +
        '预期：' + $NewHelperSha + "`n" +
        '若确为重新构建的 jar，请显式传入 -NewHelperSha 后再运行。')
}

Write-Host '== 部署前核对 =='

# ---------------------------------------------------------------------------
# 1. 设备身份：序列号 + root
# ---------------------------------------------------------------------------
$hardware = Invoke-Shell -Command 'getprop ro.serialno'
if ($hardware -ne $ExpectedHardware) {
    throw ('设备不匹配：ro.serialno=' + $hardware + ' 期望=' + $ExpectedHardware)
}
$uid = Invoke-Shell -Command 'id -u'
if ($uid -ne '0') {
    throw ('当前连接不是 root（id -u=' + $uid + '），需要已有的 root adbd 连接。')
}

# ---------------------------------------------------------------------------
# 2. 综合 Hook 基线与 supervisor 存在性（只核对，不修改）
# ---------------------------------------------------------------------------
$hookSha = Get-DeviceSha -Path $HookPath
Assert-Sha -Label '综合 Hook' -Actual $hookSha -Expected $ExpectedHookSha
Write-Host ('综合 Hook 基线一致：' + $hookSha)

$supervisorCheck = Invoke-ShellRaw -Command ('if [ -f ' + $SupervisorPath + ' ]; then echo __YES__; else echo __NO__; fi')
if ($supervisorCheck.Output -notmatch '__YES__') {
    throw ('未找到既有 supervisor（' + $SupervisorPath + '），拒绝在没有 supervisor 的设备上部署。')
}

# ---------------------------------------------------------------------------
# 3. 设备现有 theme.jar：只接受旧 v5 或本次新 v6
# ---------------------------------------------------------------------------
$linkCheck = Invoke-ShellRaw -Command ('if [ -L ' + $DeviceJar + ' ]; then echo __LINK__; else echo __FILE__; fi')
if ($linkCheck.Output -notmatch '__FILE__') {
    throw ('目标 jar 是符号链接或不可判定，拒绝替换：' + $DeviceJar)
}

$currentSha = Get-DeviceSha -Path $DeviceJar
if ($currentSha -eq '') { throw ('设备上读不到 ' + $DeviceJar) }
if ($currentSha -ne $OldThemeSha -and $currentSha -ne $NewHelperSha) {
    throw ('设备现有 theme.jar SHA256 未知：' + $currentSha + "`n" +
        '只接受旧 helper v5（' + $OldThemeSha + '）或本次新 helper（' + $NewHelperSha + '）。')
}
Write-Host ('设备现有 theme.jar：' + $currentSha)

$oldLauncherPid = (Invoke-Shell -Command ('pidof ' + $LauncherPackage)).Trim()
Write-Host ('当前 Launcher PID：' + $oldLauncherPid)

# ---------------------------------------------------------------------------
# 4. 备份旧 jar 到设备目录与 PC work/
# ---------------------------------------------------------------------------
$timestamp      = Get-Date -Format 'yyyyMMdd-HHmmss'
$PcBackupRoot   = Join-Path (Get-Location) 'work/watchworkshop-monet'
$PcBackupDir    = Join-Path $PcBackupRoot ('backup-workshop-' + $timestamp)
$DeviceBackupDir = $DeviceThemeDir + '/backup-workshop-' + $timestamp

New-Item -ItemType Directory -Force -Path $PcBackupDir | Out-Null
Invoke-Shell -Command ('mkdir -p ' + $DeviceBackupDir) | Out-Null
Invoke-Shell -Command ('cp -p ' + $DeviceJar + ' ' + $DeviceBackupDir + '/theme.jar') | Out-Null
$deviceBackupSha = Get-DeviceSha -Path ($DeviceBackupDir + '/theme.jar')
Assert-Sha -Label '设备备份' -Actual $deviceBackupSha -Expected $currentSha

$PcBackupJar = Join-Path $PcBackupDir 'theme.jar'
Invoke-Adb -Arguments @('pull', $DeviceJar, $PcBackupJar) | Out-Null
$pcBackupSha = Get-LocalSha -Path $PcBackupJar
Assert-Sha -Label 'PC 备份' -Actual $pcBackupSha -Expected $currentSha
Write-Host ('备份完成：设备 ' + $DeviceBackupDir + ' / PC ' + $PcBackupDir)

# ---------------------------------------------------------------------------
# 5. push + 校验 + chmod 644 + mv
# ---------------------------------------------------------------------------
$pushTarget = $DeviceJar + '.new'
Invoke-Adb -Arguments @('push', $HelperJar, $pushTarget) | Out-Null
$pushedSha = Get-DeviceSha -Path $pushTarget
Assert-Sha -Label '推送文件' -Actual $pushedSha -Expected $NewHelperSha

$replaced = $false
function Restore-BackupJar {
    Invoke-Shell -Command ('cp -p ' + $DeviceBackupDir + '/theme.jar ' + $DeviceJar) | Out-Null
    Invoke-Shell -Command ('chmod 644 ' + $DeviceJar) | Out-Null
    $restored = Get-DeviceSha -Path $DeviceJar
    if ($restored -eq $currentSha) {
        Write-Host ('已恢复备份 theme.jar：' + $restored)
    } else {
        throw ('恢复后的 theme.jar SHA256 与备份不一致：' + $restored + '（期望 ' + $currentSha + '）')
    }
}
function Restart-Launcher {
    $pidText = (Invoke-Shell -Command ('pidof ' + $LauncherPackage)).Trim()
    if ($pidText -notmatch '^[0-9]+$') { throw ('无法定位唯一的 Launcher PID：' + $pidText) }
    $cmdline = (Invoke-Adb -Arguments @('shell', ('cat /proc/' + $pidText + '/cmdline'))).Replace([string][char]0, '').Trim()
    if ($cmdline -ne $LauncherPackage) {
        throw ('/proc/' + $pidText + '/cmdline=' + $cmdline + ' 不是 ' + $LauncherPackage + '，拒绝 kill。')
    }
    Invoke-Shell -Command ('kill -9 ' + $pidText) | Out-Null
    return $pidText
}
function Wait-LauncherPid {
    param([string]$PreviousPid)
    for ($i = 0; $i -lt $WaitAttempts; $i++) {
        Start-Sleep -Milliseconds $WaitMilliseconds
        $pidText = (Invoke-ShellRaw -Command ('pidof ' + $LauncherPackage)).Output.Trim()
        if ($pidText -match '^[0-9]+$' -and $pidText -ne $PreviousPid) { return $pidText }
    }
    return $null
}
function Wait-NewHelper {
    param([string]$PreviousPid)
    for ($i = 0; $i -lt $WaitAttempts; $i++) {
        Start-Sleep -Milliseconds $WaitMilliseconds
        $pidText = (Invoke-ShellRaw -Command ('pidof ' + $LauncherPackage)).Output.Trim()
        if ($pidText -notmatch '^[0-9]+$' -or $pidText -eq $PreviousPid) { continue }
        $helloResult = Invoke-ShellRaw -Command ('cat ' + $DeviceMailbox + '/hello.json 2>/dev/null')
        if ($helloResult.Code -ne 0) { continue }
        $hello = $null
        try { $hello = ($helloResult.Output.Trim() | ConvertFrom-Json) } catch { continue }
        $ok = $false
        try {
            if ([int]$hello.protocol -eq $ExpectedProtocol -and
                [int]$hello.helperVersion -eq $ExpectedHelperVersion -and
                [string]$hello.pid -eq $pidText) { $ok = $true }
        } catch { $ok = $false }
        if ($ok) { return @{ Pid = $pidText; Hello = $hello } }
    }
    return $null
}

$deployedHello = $null
$newLauncherPid = ''
try {
    Invoke-Shell -Command ('chmod 644 ' + $pushTarget) | Out-Null
    Invoke-Shell -Command ('mv ' + $pushTarget + ' ' + $DeviceJar) | Out-Null
    $replaced = $true
    Assert-Sha -Label '设备 theme.jar' -Actual (Get-DeviceSha -Path $DeviceJar) -Expected $NewHelperSha

    $killedPid = Restart-Launcher
    Write-Host ('Launcher ' + $killedPid + ' 已按既有 supervisor 重载机制处理，等待新 helper…')
    $wait = Wait-NewHelper -PreviousPid $killedPid
    if ($null -eq $wait) {
        throw ('Launcher 已重启，但在 ' + ($WaitAttempts * $WaitMilliseconds / 1000) + ' 秒内没有等到新的 helper v' + $ExpectedHelperVersion + ' hello.json。')
    }
    $newLauncherPid = $wait.Pid
    $deployedHello = $wait.Hello
    Write-Host ('部署完成：helper v' + $ExpectedHelperVersion + '，新 Launcher PID ' + $newLauncherPid + '，SHA256 ' + $NewHelperSha)
} catch {
    Write-Warning ('部署未确认：' + $_.Exception.Message)
    if ($replaced) {
        Write-Host '正在恢复备份 theme.jar 并再重载一次…'
        try {
            Restore-BackupJar
            $restoredFrom = Restart-Launcher
            $restoredPid = Wait-LauncherPid -PreviousPid $restoredFrom
            if ($null -ne $restoredPid) {
                Write-Host ('已恢复备份 jar，Launcher 新 PID ' + $restoredPid + '。')
            } else {
                Write-Warning '恢复备份 jar 后未在等待窗口内确认 Launcher 重载，请手动只读检查。'
            }
        } catch {
            Write-Warning ('自动恢复未完成：' + $_.Exception.Message)
        }
    } else {
        Invoke-ShellRaw -Command ('rm -f ' + $pushTarget) | Out-Null
    }
    throw
}

# ---------------------------------------------------------------------------
# 6. 证据记录（PC 备份目录）
# ---------------------------------------------------------------------------
$record = [ordered]@{
    timestamp       = $timestamp
    serial          = $Serial
    helperJar       = $HelperJar
    hookSha         = $hookSha
    oldThemeSha     = $currentSha
    newHelperSha    = $NewHelperSha
    deviceBackupDir = $DeviceBackupDir
    pcBackupDir     = $PcBackupDir
    oldLauncherPid  = $oldLauncherPid
    newLauncherPid  = $newLauncherPid
    hello           = $deployedHello
}
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $PcBackupDir 'deploy-record.json') -Encoding UTF8
Write-Host ('记录已写入：' + (Join-Path $PcBackupDir 'deploy-record.json'))
Write-Host '本次只修改了 /data/local/tmp/watchface-theme/theme.jar；未触碰 Hook、supervisor、adbd、QQ 与 userdata。'
