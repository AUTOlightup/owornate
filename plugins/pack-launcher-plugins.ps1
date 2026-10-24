#requires -Version 5.1
param([Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$target=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $target | Out-Null
foreach($feature in @('monet','widgets','capture')) {
    $source=Join-Path $PSScriptRoot ('launcher/'+$feature)
    $manifest=Get-Content -LiteralPath (Join-Path $source 'plugin.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if($manifest.versionName -notmatch '^[a-zA-Z0-9._-]+$'){throw 'Unsafe version name for package output'}
    $output=Join-Path $target ($feature+'-'+$manifest.versionName+'.owo')
    & (Join-Path $PSScriptRoot 'pack-plugin.ps1') -PluginDirectory $source -OutputFile $output
}
