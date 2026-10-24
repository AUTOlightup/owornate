#requires -Version 5.1
param(
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$Bash,
    [Parameter(Mandatory=$true)][string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
if (Test-Path -LiteralPath $BuildRoot) { throw 'Use a new BuildRoot to preserve prior results' }
New-Item -ItemType Directory -Path $BuildRoot | Out-Null
$utf8 = New-Object Text.UTF8Encoding($false)
$starter = Join-Path $BuildRoot 'my plugin'
$tools = Join-Path $project 'plugins'
& "$tools/new-plugin.ps1" -PluginDirectory $starter -Id 'dev.example.timeout' -Name 'Developer timeout' -UpdateUrl 'https://api.github.com/repos/example/plugins/releases?per_page=20'
function Expect-Failure([string]$label, [scriptblock]$action) {
    try { & $action | Out-Host } catch { Write-Output "PASS rejected: $label"; return }
    throw "Expected rejection: $label"
}
Expect-Failure 'existing starter directory' { & "$tools/new-plugin.ps1" -PluginDirectory $starter -Id 'dev.example.timeout' -Name 'Other' }
Expect-Failure 'invalid starter id' { & "$tools/new-plugin.ps1" -PluginDirectory "$BuildRoot/invalid-id" -Id '../bad' -Name 'Other' }
foreach ($version in @('v1','v2')) { New-Item -ItemType Directory -Path "$BuildRoot/$version" | Out-Null }
$v1 = "$BuildRoot/v1/timeout-1.0.0.owo"
$v2 = "$BuildRoot/v2/timeout-1.1.0.owo"
& "$tools/pack-plugin.ps1" -PluginDirectory $starter -OutputFile $v1
& "$tools/test-plugin.ps1" -PackageFile $v1 -JdkHome $JdkHome -JsonJar $JsonJar -BuildRoot "$BuildRoot/check-v1"
$before = (Get-FileHash -LiteralPath $v1).Hash
Expect-Failure 'existing package' { & "$tools/pack-plugin.ps1" -PluginDirectory $starter -OutputFile $v1 }
if ((Get-FileHash -LiteralPath $v1).Hash -ne $before) { throw 'Existing package changed' }
$manifestFile = Join-Path $starter 'plugin.json'
$manifest = Get-Content -LiteralPath $manifestFile -Raw -Encoding UTF8 | ConvertFrom-Json
$manifest.versionCode = 2
$manifest.versionName = '1.1.0'
$manifest.settings[0].default = '15000'
[IO.File]::WriteAllText($manifestFile, ($manifest | ConvertTo-Json -Depth 12), $utf8)
& "$tools/pack-plugin.ps1" -PluginDirectory $starter -OutputFile $v2
& "$tools/test-plugin.ps1" -PackageFile $v2 -JdkHome $JdkHome -JsonJar $JsonJar -BuildRoot "$BuildRoot/check-v2"
$feed = "$BuildRoot/v2/owornate-updates.json"
& "$tools/make-update-catalog.ps1" -PluginsOnly -Repository 'example/plugins' -Tag 'v1.1.0' -PluginPackageDirectory "$BuildRoot/v2" -OutputFile $feed
Expect-Failure 'existing catalog' { & "$tools/make-update-catalog.ps1" -PluginsOnly -Repository 'example/plugins' -Tag 'v1.1.0' -PluginPackageDirectory "$BuildRoot/v2" -OutputFile $feed }
New-Item -ItemType Directory -Path "$BuildRoot/empty" | Out-Null
Expect-Failure 'empty plugin catalog' { & "$tools/make-update-catalog.ps1" -PluginsOnly -Repository 'example/plugins' -Tag 'v1' -PluginPackageDirectory "$BuildRoot/empty" -OutputFile "$BuildRoot/empty.json" }
# Exercise the unchanged release caller's parameter set without an APK build.
[IO.File]::WriteAllText("$BuildRoot/manager.apk", 'catalog test artifact only', $utf8)
& "$tools/make-update-catalog.ps1" -ApkFile "$BuildRoot/manager.apk" -ManagerVersionCode 27 -ManagerVersionName '1.0.1-rc18' -PluginPackageDirectory "$BuildRoot/v2" -OutputFile "$BuildRoot/combined.json"
$combined = Get-Content "$BuildRoot/combined.json" -Raw -Encoding UTF8 | ConvertFrom-Json
if ($combined.releases.Count -ne 2 -or $combined.releases[0].id -ne 'dev.codex.watchworkshop' -or $combined.releases[0].url -ne 'https://github.com/AUTOlightup/owornate/releases/download/v1.0.1-rc18/manager.apk') { throw 'Manager catalog compatibility failed' }
Write-Output 'PASS existing manager catalog call'
# A syntactically valid JSON with an unsupported field must fail the production parser.
$manifest | Add-Member -NotePropertyName unsupported -NotePropertyValue $true
[IO.File]::WriteAllText($manifestFile, ($manifest | ConvertTo-Json -Depth 12), $utf8)
& "$tools/pack-plugin.ps1" -PluginDirectory $starter -OutputFile "$BuildRoot/invalid.owo"
Expect-Failure 'unknown manifest field' { & "$tools/test-plugin.ps1" -PackageFile "$BuildRoot/invalid.owo" -JdkHome $JdkHome -JsonJar $JsonJar -BuildRoot "$BuildRoot/check-invalid" }
$manifest.PSObject.Properties.Remove('unsupported')
[IO.File]::WriteAllText($manifestFile, ($manifest | ConvertTo-Json -Depth 12), $utf8)
$classes = "$BuildRoot/classes"
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = @('PluginManifest','PluginRepository','PluginRunner','UpdateClient') | ForEach-Object { "$project/src/dev/codex/watchworkshop/$_.java" }
$sources += @("$PSScriptRoot/PluginRunnerTest.java", "$PSScriptRoot/PluginDevelopmentTest.java")
& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -cp $JsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Development test compilation failed' }
& "$JdkHome/bin/java.exe" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp ($classes + [IO.Path]::PathSeparator + $JsonJar) dev.codex.watchworkshop.PluginDevelopmentTest $BuildRoot $Bash
if ($LASTEXITCODE -ne 0) { throw 'Development workflow failed' }
Write-Output 'Plugin development workflow: PASS (local simulation; no device/network access)'
