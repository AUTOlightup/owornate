#requires -Version 5.1
param(
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$JsonJar,
    [Parameter(Mandatory=$true)][string]$Bash,
    [Parameter(Mandatory=$true)][string]$BuildRoot
)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$BuildRoot=[IO.Path]::GetFullPath($BuildRoot)
New-Item -ItemType Directory -Force -Path "$BuildRoot/classes" | Out-Null
# A fresh package is required so the test always uses this source revision.
$sample=Join-Path $BuildRoot ("screen-timeout-"+[Guid]::NewGuid().ToString('N')+'.owo')
& "$project/plugins/pack-plugin.ps1" -PluginDirectory "$project/plugins/examples/screen-timeout" -OutputFile $sample
$sources=@(
    "$project/src/dev/codex/watchworkshop/PluginManifest.java",
    "$project/src/dev/codex/watchworkshop/PluginRepository.java",
    "$project/src/dev/codex/watchworkshop/PluginRunner.java",
    "$PSScriptRoot/PluginRunnerTest.java"
)
& "$JdkHome/bin/javac.exe" '-J-Duser.language=en' -encoding UTF-8 --release 8 -Xlint:-options -cp $JsonJar -d "$BuildRoot/classes" @sources 2>&1 | Tee-Object -FilePath "$BuildRoot/compile.log"
if($LASTEXITCODE -ne 0){throw 'PluginRunnerTest compile failed'}
& "$JdkHome/bin/java.exe" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp "$BuildRoot/classes;$JsonJar" dev.codex.watchworkshop.PluginRunnerTest "$BuildRoot/runtime" $sample $Bash 2>&1 | Tee-Object -FilePath "$BuildRoot/test.log"
if($LASTEXITCODE -ne 0){throw 'PluginRunnerTest failed'}
Write-Output "Sample package: $sample"
