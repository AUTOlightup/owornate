#requires -Version 5.1
[CmdletBinding(DefaultParameterSetName='Manager')]
param(
    [Parameter(Mandatory=$true,ParameterSetName='Manager')][string]$ApkFile,
    [Parameter(Mandatory=$true)][string]$PluginPackageDirectory,
    [Parameter(Mandatory=$true)][string]$OutputFile,
    [Parameter(Mandatory=$true,ParameterSetName='Manager')][int]$ManagerVersionCode,
    [Parameter(Mandatory=$true,ParameterSetName='Manager')][string]$ManagerVersionName,
    [Parameter(Mandatory=$true,ParameterSetName='Plugins')][switch]$PluginsOnly,
    [Parameter(ParameterSetName='Manager')]
    [Parameter(Mandatory=$true,ParameterSetName='Plugins')]
    [string]$Repository = 'AUTOlightup/owornate',
    [Parameter(ParameterSetName='Manager')]
    [Parameter(Mandatory=$true,ParameterSetName='Plugins')]
    [string]$Tag,
    [string]$Notes = ''
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid GitHub repository' }
if (!$PluginsOnly -and ($ManagerVersionCode -le 0 -or !$ManagerVersionName)) { throw 'Manager version required' }
if (!$Tag) { $Tag = 'v' + $ManagerVersionName }
$target = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $target) { throw 'OutputFile already exists' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$base = 'https://github.com/' + $Repository + '/releases/download/' + [Uri]::EscapeDataString($Tag) + '/'
$entries = New-Object Collections.Generic.List[object]
$ids = New-Object 'Collections.Generic.HashSet[string]'
function Add-Entry($id, $versionCode, $versionName, $file, $notes) {
    if (!$ids.Add($id)) { throw "Duplicate update id: $id" }
    if ($versionCode -le 0 -or !$versionName) { throw 'Invalid package version' }
    $info = Get-Item -LiteralPath $file
    if ($info.PSIsContainer -or !$info.Length) { throw 'Empty update artifact' }
    $entries.Add([ordered]@{
        id=$id; versionCode=[int]$versionCode; versionName=[string]$versionName
        url=$base+[Uri]::EscapeDataString($info.Name)
        sha256=(Get-FileHash -LiteralPath $info.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        notes=[string]$notes
    })
}
if (!$PluginsOnly) { Add-Entry 'dev.codex.watchworkshop' $ManagerVersionCode $ManagerVersionName $ApkFile $Notes }
foreach ($package in (Get-ChildItem -LiteralPath $PluginPackageDirectory -Filter '*.owo' -File | Sort-Object Name)) {
    $zip = [IO.Compression.ZipFile]::OpenRead($package.FullName)
    try {
        $entry = $zip.GetEntry('plugin.json')
        if (!$entry -or $entry.Length -gt 32768) { throw "Missing/oversized manifest: $($package.Name)" }
        $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8)
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        Add-Entry $manifest.id $manifest.versionCode $manifest.versionName $package.FullName $manifest.description
    } finally { $zip.Dispose() }
}
if ($entries.Count -eq 0) { throw 'No .owo packages found; plugin catalog would be empty' }
$catalog = [ordered]@{schemaVersion=1; releases=@($entries.ToArray())}
[IO.File]::WriteAllText($target, ($catalog | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
Write-Output $target
# Upload the catalog and the exact referenced APK/.owo files to the same tag after release approval.
