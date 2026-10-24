#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$PackageFile,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [string]$BuildRoot = (Join-Path (Join-Path $PWD.Path 'work') ('plugin-check-' + [Guid]::NewGuid().ToString('N')))
)
$ErrorActionPreference = 'Stop'
$package = (Resolve-Path -LiteralPath $PackageFile).Path
if ([IO.Path]::GetExtension($package) -ne '.owo') { throw 'PackageFile must end in .owo' }
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
foreach ($file in @($javac, $java, $JsonJar)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing dependency: $file" }
}
$JsonJar = (Resolve-Path -LiteralPath $JsonJar).Path
$project = Split-Path -Parent $PSScriptRoot
$classes = Join-Path ([IO.Path]::GetFullPath($BuildRoot)) 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(
    "$project/src/dev/codex/watchworkshop/PluginManifest.java",
    "$project/src/dev/codex/watchworkshop/PluginRepository.java",
    "$PSScriptRoot/PluginPackageCheck.java"
)
& $javac -encoding UTF-8 --release 8 -Xlint:-options -cp $JsonJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Package validator compilation failed (requires JDK 11+)' }
& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp ($classes + [IO.Path]::PathSeparator + $JsonJar) dev.codex.watchworkshop.PluginPackageCheck $package (Split-Path -Parent $classes)
if ($LASTEXITCODE -ne 0) { throw 'Package validation failed; see parser error above' }
