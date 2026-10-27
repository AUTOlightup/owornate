#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputFile)
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $target) { throw 'OutputFile already exists' }
$stage = Join-Path (Split-Path -Parent $target) ('alipay-package-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$controller = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'control.sh')).Replace("`r`n", "`n")
$hook = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'hook.js')).Replace("`r`n", "`n")
if ($hook.Contains('OW_ALIPAY_HOOK_EOF')) { throw 'Payload contains reserved heredoc delimiter' }
$script = $controller + "`nemit_payload() {`ncat <<'OW_ALIPAY_HOOK_EOF'`n" + $hook + "`nOW_ALIPAY_HOOK_EOF`n}`n" + @'
[ "$(id -u)" = 0 ] || fail 'root required'
case "${1-}" in
    status|configure) status ;;
    enable) enable ;;
    disable) disable ;;
    uninstall) uninstall ;;
    *) fail 'unsupported action' ;;
esac
'@
[IO.File]::WriteAllText((Join-Path $stage 'plugin.sh'), $script.Replace("`r`n", "`n") + "`n", $utf8)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'plugin.json') -Destination $stage
& (Join-Path (Split-Path -Parent $PSScriptRoot) 'pack-plugin.ps1') -PluginDirectory $stage -OutputFile $target
Write-Output "Package sources: $stage"
