param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Adb = '',
    [Parameter(Mandatory=$true)][string]$ExpectedHardware
)
$ErrorActionPreference='Stop'
if(!$Adb){$found=Get-Command adb -ErrorAction SilentlyContinue;if(!$found){throw '请通过-Adb指定adb。'};$Adb=$found.Source}
function Invoke-Watch([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'ADB operation failed' }
    return ($result -join "`n").Trim()
}
$hardware = Invoke-Watch -Arguments @('shell','getprop ro.serialno')
if ($hardware -ne $ExpectedHardware) { throw "Device mismatch: $hardware" }
$uid = Invoke-Watch -Arguments @('shell','id -u')
if ($uid -ne '0') { throw 'An existing root adbd connection is required.' }
$installed = Invoke-Watch -Arguments @('shell','pm path dev.codex.watchworkshop')
if (-not $installed.StartsWith('package:')) { throw 'Install OWOrnate first.' }
$null = Invoke-Watch -Arguments @('shell','am start -n dev.codex.watchworkshop/.MainActivity')
Start-Sleep -Seconds 2
$publicKey = Invoke-Watch -Arguments @('shell','cat /data/user/0/dev.codex.watchworkshop/files/adb/adbkey.pub')
if ($publicKey -notmatch '^[A-Za-z0-9+/=]+ watchworkshop@app$') { throw 'Unexpected app public key format.' }
if ([Convert]::FromBase64String(($publicKey -split ' ')[0]).Length -ne 524) { throw 'Unexpected ADB public key size.' }
# Only append this app's PUBLIC key. Existing authorizations and production files are untouched.
$command = "grep -qxF '$publicKey' /data/misc/adb/adb_keys || printf '%s\n' '$publicKey' >> /data/misc/adb/adb_keys"
$null = Invoke-Watch -Arguments @('shell',$command)
Write-Output 'App public key enrolled. Open Device check in OWOrnate. No adbd or Launcher restart was performed.'
