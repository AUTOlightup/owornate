#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AdbDirectory,
      [Parameter(Mandatory=$true)][string]$FridaInject,
      [string]$ReleaseRoot=(Join-Path (Get-Location) 'outputs/OWOrnate-v1.0.0'))
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
$docRoot=Join-Path $project 'docs/release';if(!(Test-Path -LiteralPath $docRoot)){$docRoot=Join-Path $project 'docs'}
$releaseFull=[IO.Path]::GetFullPath($ReleaseRoot)
if(Test-Path -LiteralPath $releaseFull){throw 'ReleaseRoot必须是不存在的新目录，防止旧资产混入本次发布。'}
$bundle=Join-Path $releaseFull 'OWOrnate-v1.0.0-Windows'
$source=Join-Path $releaseFull 'owornate-source'
$monet=Join-Path $releaseFull 'watch2-monet-source'
New-Item -ItemType Directory -Force -Path "$bundle/payload","$bundle/tools/adb","$bundle/scripts","$bundle/licenses",$source,$monet | Out-Null
$payload=[ordered]@{'OWOrnate-v1.0.0.apk'='dist/OWOrnate-v1.0.0.apk'}
foreach($name in $payload.Keys){Copy-Item -LiteralPath (Join-Path $project $payload[$name]) -Destination (Join-Path $bundle "payload/$name")}
Copy-Item -LiteralPath $FridaInject -Destination "$bundle/payload/frida-inject"
foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','libwinpthread-1.dll','NOTICE.txt','source.properties')){Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination "$bundle/tools/adb/$name"}
Get-ChildItem -LiteralPath "$project/assets/licenses" -File|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination "$bundle/licenses"}
Copy-Item -LiteralPath "$project/LICENSE" -Destination "$bundle/LICENSE"
Copy-Item -LiteralPath "$project/release-manage.ps1" -Destination "$bundle/release-manage.ps1"

Copy-Item -LiteralPath $docRoot -Destination "$bundle/docs" -Recurse
Copy-Item -LiteralPath "$docRoot/README-public.md" -Destination "$bundle/README.md"
$entry=@{'00-Setup'='Setup';'01-Status'='Status'}
foreach($name in $entry.Keys){
    $body="@echo off`r`nchcp 65001 >nul`r`nsetlocal`r`ncd /d `"%~dp0`"`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0release-manage.ps1`" -Action $($entry[$name]) %*`r`nif errorlevel 1 echo Operation did not complete. Read the message above.`r`npause`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle "$name.cmd"),$body,[Text.Encoding]::ASCII)
}
# Source allowlist excludes historical probes, screenshots, build output and signing data.
foreach($dir in @('src','res','assets','tests','standalone')){Copy-Item -LiteralPath (Join-Path $project $dir) -Destination $source -Recurse}
foreach($name in @('AndroidManifest.xml','build.ps1','release-manage.ps1','deploy-theme-helper.ps1','setup-readonly.ps1','make-release.ps1','LICENSE','.gitignore')){Copy-Item -LiteralPath (Join-Path $project $name) -Destination $source}
Copy-Item -LiteralPath $docRoot -Destination "$source/docs" -Recurse
Copy-Item -LiteralPath "$docRoot/README-public.md" -Destination "$source/README.md"
Copy-Item -LiteralPath "$project/assets/licenses" -Destination "$source/licenses" -Recurse
New-Item -ItemType Directory -Path "$source/runtime-helper" -Force|Out-Null
foreach($dir in @('native-src','vendor')){Copy-Item -LiteralPath "$project/runtime-helper/$dir" -Destination "$source/runtime-helper" -Recurse;Copy-Item -LiteralPath "$project/runtime-helper/$dir" -Destination $monet -Recurse}
Copy-Item -LiteralPath "$project/runtime-helper/build.ps1" -Destination "$source/runtime-helper/build.ps1"
Copy-Item -LiteralPath "$project/runtime-helper/build.ps1" -Destination "$monet/build.ps1"
Copy-Item -LiteralPath "$project/standalone" -Destination $monet -Recurse
Copy-Item -LiteralPath "$project/assets/licenses" -Destination "$monet/licenses" -Recurse
Copy-Item -LiteralPath "$project/LICENSE" -Destination "$monet/LICENSE"
Copy-Item -LiteralPath "$docRoot/MONET-README.md" -Destination "$monet/README.md"
Copy-Item -LiteralPath "$project/docs/莫奈协议-v1.md" -Destination "$monet/PROTOCOL.md"
Copy-Item -LiteralPath "$project/docs/莫奈协议-v1.md" -Destination "$source/docs/莫奈协议-v1.md"
foreach($basePath in @($bundle,$source)){
    $duplicateReadme=Join-Path $basePath 'docs/README-public.md'
    $readmeText=[IO.File]::ReadAllText($duplicateReadme,[Text.Encoding]::UTF8).Replace('(docs/','(').Replace('src="docs/','src="')
    [IO.File]::WriteAllText($duplicateReadme,$readmeText,[Text.UTF8Encoding]::new($false))
}
# Windows PowerShell 5.1 needs a UTF-8 BOM for scripts containing Chinese text.
Get-ChildItem -LiteralPath $releaseFull -Recurse -File -Filter '*.ps1'|ForEach-Object{$scriptText=[IO.File]::ReadAllText($_.FullName,[Text.Encoding]::UTF8);[IO.File]::WriteAllText($_.FullName,$scriptText,[Text.UTF8Encoding]::new($true))}
$files=@(Get-ChildItem -LiteralPath "$bundle/payload","$bundle/tools","$bundle/licenses" -File -Recurse|ForEach-Object{[ordered]@{path=$_.FullName.Substring($bundle.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$_.Length}})
$manifest=[ordered]@{version='1.0.0';versionCode=7;channel='candidate';helperVersion=6;protocol=1;files=$files}
[IO.File]::WriteAllText("$bundle/manifest.json",($manifest|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
foreach($repo in @($source,$monet)){
    $secretFiles=@(Get-ChildItem -LiteralPath $repo -Recurse -File|Where-Object{$_.Name -match '\.(jks|keystore|pk8|pem|der|p12)$' -or $_.Name -eq 'adbkey' -or $_.Name -match 'signing.*properties'})
    if($secretFiles.Count){throw '源码清单包含签名/密钥材料，停止打包'}
}
Compress-Archive -LiteralPath $bundle -DestinationPath "$releaseFull/OWOrnate-v1.0.0-Windows.zip"
Compress-Archive -LiteralPath $source -DestinationPath "$releaseFull/owornate-v1.0.0-source.zip"
Compress-Archive -LiteralPath $monet -DestinationPath "$releaseFull/watch2-monet-v6-source.zip"
Copy-Item -LiteralPath "$project/dist/OWOrnate-v1.0.0.apk" -Destination "$releaseFull/OWOrnate-v1.0.0.apk"
$checks=@(Get-ChildItem -LiteralPath $releaseFull -File|Where-Object{$_.Extension -in @('.zip','.apk')}|ForEach-Object{(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$_.Name})
[IO.File]::WriteAllLines("$releaseFull/SHA256SUMS.txt",$checks,[Text.Encoding]::ASCII)
Write-Output $releaseFull
