#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $target) { throw 'OutputFile already exists' }
$controllerPath = Join-Path $PSScriptRoot 'control.sh'
$payloadPath = Join-Path $PSScriptRoot 'global-tap.js'
foreach ($required in @($controllerPath, $payloadPath)) {
    if (!(Test-Path -LiteralPath $required -PathType Leaf)) {
        throw ('Missing ' + (Split-Path -Leaf $required))
    }
}
$stage = Join-Path (Split-Path -Parent $target) ('alipay-global-package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$controller = [IO.File]::ReadAllText($controllerPath).Replace("`r`n", "`n")
$payload = [IO.File]::ReadAllText($payloadPath).Replace("`r`n", "`n")
$delimiter = 'OW_ALIPAY_GLOBAL_JS_EOF'
if ($payload.Contains($delimiter)) { throw 'Payload contains reserved heredoc delimiter' }
$script = $controller + "`nemit_payload() {`ncat <<'" + $delimiter + "'`n" + $payload + "`n" + $delimiter + "`n}`n" + @'
[ "$(id -u)" = 0 ] || fail 'root required'
case "${1-}" in
    status) status ;;
    enable) enable ;;
    disable) disable ;;
    configure) configure ;;
    uninstall) uninstall ;;
    *) fail 'unsupported action' ;;
esac
'@
[IO.File]::WriteAllText((Join-Path $stage 'plugin.sh'), $script.Replace("`r`n", "`n") + "`n", $utf8)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin.json') -Destination $stage
& (Join-Path (Split-Path -Parent $PSScriptRoot) 'pack-plugin.ps1') -PluginDirectory $stage -OutputFile $target
Write-Output "Package sources: $stage"
