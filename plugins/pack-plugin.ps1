#requires -Version 5.1
param(
    [Parameter(Mandatory=$true)][string]$PluginDirectory,
    [Parameter(Mandatory=$true)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$directory = (Resolve-Path -LiteralPath $PluginDirectory).Path
$target = [IO.Path]::GetFullPath($OutputFile)
if ([IO.Path]::GetExtension($target) -ne '.owo') { throw 'OutputFile must end in .owo' }
$manifest = Get-Content -LiteralPath (Join-Path $directory 'plugin.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.runtime -eq 'shell-v1') { $names = @('plugin.json','plugin.sh') }
elseif ($manifest.runtime -eq 'launcher-v1') {
    $idPattern = '^[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+$'
    $reservedShortIds = @('monet','widgets','capture','monet.disabled','widgets.disabled','capture.disabled')
    if ($manifest.id.Length -gt 80 -or ![regex]::IsMatch([string]$manifest.id, $idPattern) -or
            $reservedShortIds -contains $manifest.id) {
        throw 'launcher-v1 id must be a safe reverse-domain id and may not use a reserved short id'
    }
    $names = @('plugin.json','hook.js')
    if ($manifest.id -eq 'dev.owornate.monet') { $names += 'theme.jar' }
    $officialIds = @('dev.owornate.monet','dev.owornate.widgets','dev.owornate.capture')
    if ($officialIds -notcontains $manifest.id) {
        if ($manifest.PSObject.Properties.Name -notcontains 'settings' -or
                $null -eq $manifest.settings -or @($manifest.settings).Count -gt 0) {
            throw 'Third-party launcher-v1 settings must be an empty array'
        }
        if ($manifest.PSObject.Properties.Name -contains 'nativeSettings') {
            throw 'Third-party launcher-v1 may not declare nativeSettings'
        }
    }
    $entries = @(Get-ChildItem -LiteralPath $directory -Force)
    $unexpected = @($entries | Where-Object { $_.PSIsContainer -or $names -notcontains $_.Name })
    if ($unexpected.Count -gt 0) {
        throw ('launcher-v1 package may only contain: ' + ($names -join ', '))
    }
} else { throw 'Unsupported plugin runtime/id' }
foreach ($name in $names) {
    if (!(Test-Path -LiteralPath (Join-Path $directory $name) -PathType Leaf)) { throw "Missing $name" }
}
$stream = [IO.File]::Open($target, [IO.FileMode]::CreateNew)
try {
    $zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($name in $names) {
            $bytes = [IO.File]::ReadAllBytes((Join-Path $directory $name))
            if ($name -in @('plugin.sh','hook.js') -and ($bytes -contains 13 -or ($bytes.Length -gt 2 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191))) {
                throw 'Scripts must use UTF-8 without BOM and LF line endings'
            }
            $entry = $zip.CreateEntry($name)
            $dest = $entry.Open()
            try { $dest.Write($bytes,0,$bytes.Length) } finally { $dest.Dispose() }
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }
Write-Output $target
# The manager performs the authoritative manifest/package validation before import.
