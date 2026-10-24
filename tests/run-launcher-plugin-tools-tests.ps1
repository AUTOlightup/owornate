#requires -Version 5.1
param(
    [string]$BuildRoot = (Join-Path (Join-Path $PWD.Path 'work') ('launcher-tool-test-' + [Guid]::NewGuid().ToString('N')))
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $project 'plugins'
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
if (Test-Path -LiteralPath $BuildRoot) { throw 'Use a new BuildRoot to preserve prior results' }
New-Item -ItemType Directory -Path $BuildRoot | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)

function Expect-Failure([string]$label, [scriptblock]$action) {
    try { & $action | Out-Host } catch { Write-Output "PASS rejected: $label"; return }
    throw "Expected rejection: $label"
}

function Assert-LfNoBom([string]$file) {
    $bytes = [IO.File]::ReadAllBytes($file)
    if ($bytes.Length -gt 2 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        throw "$file contains a UTF-8 BOM"
    }
    if ($bytes -contains 13) { throw "$file contains CR line endings" }
}

function Get-ZipNames([string]$file) {
    $zip = [IO.Compression.ZipFile]::OpenRead($file)
    try { return @($zip.Entries | ForEach-Object { $_.FullName } | Sort-Object) }
    finally { $zip.Dispose() }
}

$shell = Join-Path $BuildRoot 'default-shell'
& (Join-Path $tools 'new-plugin.ps1') -PluginDirectory $shell -Id 'dev.example.shell' -Name 'Default shell'
$shellManifest = Get-Content -LiteralPath (Join-Path $shell 'plugin.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($shellManifest.runtime -ne 'shell-v1') { throw 'new-plugin default runtime changed' }
if (!(Test-Path -LiteralPath (Join-Path $shell 'plugin.sh') -PathType Leaf)) { throw 'shell template missing' }
if (Test-Path -LiteralPath (Join-Path $shell 'hook.js')) { throw 'shell template gained hook.js' }
Assert-LfNoBom (Join-Path $shell 'plugin.sh')
$shellPackage = Join-Path $BuildRoot 'default-shell.owo'
& (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $shell -OutputFile $shellPackage
$shellNames = (Get-ZipNames $shellPackage) -join ','
if ($shellNames -ne 'plugin.json,plugin.sh') { throw "shell package files changed: $shellNames" }

$launcher = Join-Path $BuildRoot 'launcher-tools'
& (Join-Path $tools 'new-plugin.ps1') -PluginDirectory $launcher -Id 'dev.example.tools-launcher' -Name 'Launcher tools' -Runtime 'launcher-v1'
$launcherManifest = Get-Content -LiteralPath (Join-Path $launcher 'plugin.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($launcherManifest.runtime -ne 'launcher-v1') { throw 'launcher runtime missing' }
if (@($launcherManifest.settings).Count -ne 0) { throw 'launcher settings must be empty' }
if ($launcherManifest.PSObject.Properties.Name -contains 'nativeSettings') { throw 'launcher generator added nativeSettings' }
if (!(Test-Path -LiteralPath (Join-Path $launcher 'hook.js') -PathType Leaf)) { throw 'launcher hook missing' }
if (Test-Path -LiteralPath (Join-Path $launcher 'plugin.sh')) { throw 'launcher generator copied plugin.sh' }
Assert-LfNoBom (Join-Path $launcher 'hook.js')

$launcherPackage = Join-Path $BuildRoot 'launcher-tools.owo'
& (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile $launcherPackage
$launcherNames = (Get-ZipNames $launcherPackage) -join ','
if ($launcherNames -ne 'hook.js,plugin.json') { throw "third-party launcher files wrong: $launcherNames" }

$launcherManifest.settings = @(@{
    key = 'demo'
    title = 'Demo'
    type = 'choice'
    default = 'one'
    options = @(
        @{ value = 'one'; label = 'One' }
        @{ value = 'two'; label = 'Two' }
    )
})
[IO.File]::WriteAllText((Join-Path $launcher 'plugin.json'), ($launcherManifest | ConvertTo-Json -Depth 12), $utf8)
Expect-Failure 'third-party launcher settings' {
    & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot 'settings.owo')
}
$launcherManifest.settings = @()
$launcherManifest | Add-Member -NotePropertyName nativeSettings -NotePropertyValue @()
[IO.File]::WriteAllText((Join-Path $launcher 'plugin.json'), ($launcherManifest | ConvertTo-Json -Depth 12), $utf8)
Expect-Failure 'third-party launcher nativeSettings' {
    & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot 'native-settings.owo')
}
$launcherManifest.PSObject.Properties.Remove('nativeSettings')
$launcherManifest.PSObject.Properties.Remove('settings')
[IO.File]::WriteAllText((Join-Path $launcher 'plugin.json'), ($launcherManifest | ConvertTo-Json -Depth 12), $utf8)
Expect-Failure 'third-party launcher missing settings' {
    & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot 'missing-settings.owo')
}
$launcherManifest | Add-Member -NotePropertyName settings -NotePropertyValue @()
[IO.File]::WriteAllText((Join-Path $launcher 'plugin.json'), ($launcherManifest | ConvertTo-Json -Depth 12), $utf8)

[IO.File]::WriteAllBytes((Join-Path $launcher 'theme.jar'), [byte[]](1, 2, 3))
Expect-Failure 'third-party theme.jar' {
    & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot 'extra.owo')
}
Remove-Item -LiteralPath (Join-Path $launcher 'theme.jar') -Force
[IO.File]::WriteAllText((Join-Path $launcher 'extra.txt'), 'extra', $utf8)
Expect-Failure 'launcher extra file' {
    & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot 'extra-text.owo')
}
Remove-Item -LiteralPath (Join-Path $launcher 'extra.txt') -Force

$monetPackage = Join-Path $BuildRoot 'official-monet.owo'
& (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory (Join-Path $tools 'launcher/monet') -OutputFile $monetPackage
$monetNames = (Get-ZipNames $monetPackage) -join ','
if ($monetNames -ne 'hook.js,plugin.json,theme.jar') { throw "official Monet files wrong: $monetNames" }

Expect-Failure 'reserved launcher id' {
    & (Join-Path $tools 'new-plugin.ps1') -PluginDirectory (Join-Path $BuildRoot 'reserved') -Id 'dev.owornate.monet' -Name 'Reserved' -Runtime 'launcher-v1'
}
Expect-Failure 'short reserved id' {
    & (Join-Path $tools 'new-plugin.ps1') -PluginDirectory (Join-Path $BuildRoot 'short') -Id 'monet' -Name 'Reserved' -Runtime 'launcher-v1'
}
Expect-Failure 'invalid launcher id' {
    & (Join-Path $tools 'new-plugin.ps1') -PluginDirectory (Join-Path $BuildRoot 'invalid') -Id 'Not_Safe' -Name 'Invalid' -Runtime 'launcher-v1'
}

foreach($reservedState in @('monet.disabled','widgets.disabled','capture.disabled')) {
    Expect-Failure "reserved state id $reservedState in template" {
        & (Join-Path $tools 'new-plugin.ps1') -PluginDirectory (Join-Path $BuildRoot $reservedState) -Id $reservedState -Name 'Reserved' -Runtime 'launcher-v1'
    }
    $launcherManifest.id = $reservedState
    [IO.File]::WriteAllText((Join-Path $launcher 'plugin.json'), ($launcherManifest | ConvertTo-Json -Depth 12), $utf8)
    Expect-Failure "reserved state id $reservedState in package" {
        & (Join-Path $tools 'pack-plugin.ps1') -PluginDirectory $launcher -OutputFile (Join-Path $BuildRoot ($reservedState+'.owo'))
    }
}
Write-Output "PASS launcher tool workflow: $BuildRoot"
