#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AdbDirectory,
      [Parameter(Mandatory=$true)][string]$FridaInject,
      [string]$ReleaseRoot='')
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
foreach($name in @('runtime-manage.sh','runtime-service.sh','runtime-core.js','runtime-commit.js','runtime-base.js','widget-host.js','runtime-hook.js','build-runtime.ps1')){
    $required=Join-Path $project ('standalone/'+$name)
    if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw ('源码白名单缺少 standalone/'+$name)}
}
foreach($name in @('capture-controls.js','capture-indicator.js')){
    $required=Join-Path $project ('widget-host/'+$name)
    if(!(Test-Path -LiteralPath $required -PathType Leaf)){throw ('运行时构建缺少 widget-host/'+$name)}
}
[xml]$appManifest=Get-Content -LiteralPath (Join-Path $project 'AndroidManifest.xml') -Raw -Encoding UTF8
$androidNs='http://schemas.android.com/apk/res/android'
$version=$appManifest.manifest.GetAttribute('versionName',$androidNs)
$versionCode=[int]$appManifest.manifest.GetAttribute('versionCode',$androidNs)
if(!$ReleaseRoot){$ReleaseRoot=Join-Path (Get-Location) ("outputs/OWOrnate-v$version-code$versionCode")}
$apkName="OWOrnate-v$version.apk"
$docRoot=Join-Path $project 'docs/release';if(!(Test-Path -LiteralPath $docRoot)){$docRoot=Join-Path $project 'docs'}
$releaseFull=[IO.Path]::GetFullPath($ReleaseRoot)
if(Test-Path -LiteralPath $releaseFull){throw 'ReleaseRoot必须是不存在的新目录，防止旧资产混入本次发布。'}
$bundle=Join-Path $releaseFull "OWOrnate-v$version-Windows"
$source=Join-Path $releaseFull 'owornate-source'
$monet=Join-Path $releaseFull 'watch2-monet-source'
New-Item -ItemType Directory -Force -Path "$bundle/payload","$bundle/tools/adb","$bundle/scripts","$bundle/licenses",$source,$monet | Out-Null
$payload=[ordered]@{}; $payload[$apkName]="dist/$apkName"
foreach($name in $payload.Keys){Copy-Item -LiteralPath (Join-Path $project $payload[$name]) -Destination (Join-Path $bundle "payload/$name")}
Copy-Item -LiteralPath $FridaInject -Destination "$bundle/payload/frida-inject"
& (Join-Path $project 'plugins/pack-launcher-plugins.ps1') -OutputDirectory "$bundle/payload/plugins"
foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','libwinpthread-1.dll','NOTICE.txt','source.properties')){Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination "$bundle/tools/adb/$name"}
Get-ChildItem -LiteralPath "$project/assets/licenses" -File|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination "$bundle/licenses"}
Copy-Item -LiteralPath "$project/LICENSE" -Destination "$bundle/LICENSE"
Copy-Item -LiteralPath "$project/release-manage.ps1" -Destination "$bundle/release-manage.ps1"

Copy-Item -LiteralPath $docRoot -Destination "$bundle/docs" -Recurse
Copy-Item -LiteralPath "$docRoot/README-public.md" -Destination "$bundle/README.md"
$entry=@{'00-Setup'='Setup';'01-Status'='Status';'02-EnableWidgets'='OpenManager'}
foreach($name in $entry.Keys){
    $body="@echo off`r`nchcp 65001 >nul`r`nsetlocal`r`ncd /d `"%~dp0`"`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"%~dp0release-manage.ps1`" -Action $($entry[$name]) %*`r`nif errorlevel 1 echo Operation did not complete. Read the message above.`r`npause`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle "$name.cmd"),$body,[Text.Encoding]::ASCII)
}
# Source allowlist excludes raw probes, root screenshots, build output and signing data.
# Release-document screenshots remain with the historical validation records that cite them.
foreach($dir in @('src','res','assets','tests','standalone','widget-host','capture','plugins')){
    Get-ChildItem -LiteralPath (Join-Path $project $dir) -File -Recurse -Force |
        Where-Object {$_.Extension -notin @('.pyc','.pyo') -and $_.FullName -notmatch '[\\/]__pycache__[\\/]'} |
        ForEach-Object {$target=Join-Path $source $_.FullName.Substring($project.Length+1);New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target)|Out-Null;Copy-Item -LiteralPath $_.FullName -Destination $target}
}
foreach($name in @('AndroidManifest.xml','build.ps1','release-manage.ps1','deploy-theme-helper.ps1','setup-readonly.ps1','make-release.ps1','LICENSE','.gitignore','.gitattributes')){Copy-Item -LiteralPath (Join-Path $project $name) -Destination $source}
Copy-Item -LiteralPath $docRoot -Destination "$source/docs" -Recurse
foreach($name in @('PLUGIN-DEVELOPMENT.md','PLUGIN-QUICKSTART.md','LAUNCHER-PLUGINS.md','MODULE-MANAGEMENT.md','UPDATES.md')){
    Copy-Item -LiteralPath "$project/docs/$name" -Destination "$source/docs/$name"
    Copy-Item -LiteralPath "$project/docs/$name" -Destination "$bundle/docs/$name"
}
# Keep the example links in the bundled developer guide usable offline.
New-Item -ItemType Directory -Path "$bundle/plugins/examples/screen-timeout" -Force|Out-Null
foreach($name in @('plugin.json','plugin.sh')){
    Copy-Item -LiteralPath "$project/plugins/examples/screen-timeout/$name" -Destination "$bundle/plugins/examples/screen-timeout/$name"
}
New-Item -ItemType Directory -Path "$bundle/plugins/examples/launcher-keep-awake" -Force|Out-Null
foreach($name in @('plugin.json','hook.js')){
    Copy-Item -LiteralPath "$project/plugins/examples/launcher-keep-awake/$name" -Destination "$bundle/plugins/examples/launcher-keep-awake/$name"
}
$exampleManifest=Get-Content -LiteralPath "$project/plugins/examples/launcher-keep-awake/plugin.json" -Raw -Encoding UTF8|ConvertFrom-Json
& (Join-Path $project 'plugins/pack-plugin.ps1') -PluginDirectory "$project/plugins/examples/launcher-keep-awake" -OutputFile (Join-Path "$bundle/plugins/examples/launcher-keep-awake" ('launcher-keep-awake-'+$exampleManifest.versionName+'.owo'))
Copy-Item -LiteralPath "$docRoot/README-public.md" -Destination "$source/README.md"
Copy-Item -LiteralPath "$project/assets/licenses" -Destination "$source/licenses" -Recurse
New-Item -ItemType Directory -Path "$source/runtime-helper" -Force|Out-Null
foreach($dir in @('native-src','vendor')){Copy-Item -LiteralPath "$project/runtime-helper/$dir" -Destination "$source/runtime-helper" -Recurse;Copy-Item -LiteralPath "$project/runtime-helper/$dir" -Destination $monet -Recurse}
Copy-Item -LiteralPath "$project/runtime-helper/build.ps1" -Destination "$source/runtime-helper/build.ps1"
Copy-Item -LiteralPath "$project/runtime-helper/build.ps1" -Destination "$monet/build.ps1"
Copy-Item -LiteralPath "$project/standalone" -Destination $monet -Recurse
New-Item -ItemType Directory -Force -Path "$monet/widget-host"|Out-Null
foreach($name in @('capture-controls.js','capture-indicator.js')){Copy-Item -LiteralPath "$project/widget-host/$name" -Destination "$monet/widget-host/$name"}
Copy-Item -LiteralPath "$project/assets/licenses" -Destination "$monet/licenses" -Recurse
Copy-Item -LiteralPath "$project/LICENSE" -Destination "$monet/LICENSE"
Copy-Item -LiteralPath "$docRoot/MONET-README.md" -Destination "$monet/README.md"
Copy-Item -LiteralPath "$project/docs/莫奈协议-v1.md" -Destination "$monet/PROTOCOL.md"
Copy-Item -LiteralPath "$project/docs/莫奈协议-v1.md" -Destination "$source/docs/莫奈协议-v1.md"
foreach($basePath in @($bundle,$source)){
    $captureDoc=[IO.File]::ReadAllText("$project/docs/截屏录屏-原生版.md",[Text.Encoding]::UTF8).Replace('(release/','(')
    [IO.File]::WriteAllText("$basePath/docs/截屏录屏-原生版.md",$captureDoc,[Text.UTF8Encoding]::new($false))
}
foreach($basePath in @($bundle,$source)){
    $duplicateReadme=Join-Path $basePath 'docs/README-public.md'
    $readmeText=[IO.File]::ReadAllText($duplicateReadme,[Text.Encoding]::UTF8).Replace('(docs/','(').Replace('src="docs/','src="')
    [IO.File]::WriteAllText($duplicateReadme,$readmeText,[Text.UTF8Encoding]::new($false))
}
# Windows PowerShell 5.1 needs a UTF-8 BOM for scripts containing Chinese text.
Get-ChildItem -LiteralPath $releaseFull -Recurse -File -Filter '*.ps1'|ForEach-Object{$scriptText=[IO.File]::ReadAllText($_.FullName,[Text.Encoding]::UTF8);[IO.File]::WriteAllText($_.FullName,$scriptText,[Text.UTF8Encoding]::new($true))}
$files=@(Get-ChildItem -LiteralPath "$bundle/payload","$bundle/tools","$bundle/licenses" -File -Recurse|ForEach-Object{[ordered]@{path=$_.FullName.Substring($bundle.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant();bytes=$_.Length}})
$manifest=[ordered]@{version=$version;versionCode=$versionCode;apk=$apkName;channel='candidate';helperVersion=6;protocol=1;files=$files}
[IO.File]::WriteAllText("$bundle/manifest.json",($manifest|ConvertTo-Json -Depth 7),[Text.UTF8Encoding]::new($false))
foreach($repo in @($source,$monet)){
    $secretFiles=@(Get-ChildItem -LiteralPath $repo -Recurse -File|Where-Object{$_.Name -match '\.(jks|keystore|pk8|pem|der|p12)$' -or $_.Name -eq 'adbkey' -or $_.Name -match 'signing.*properties'})
    if($secretFiles.Count){throw '源码清单包含签名/密钥材料，停止打包'}
}
Compress-Archive -LiteralPath $bundle -DestinationPath "$releaseFull/OWOrnate-v$version-Windows.zip"
Compress-Archive -LiteralPath $source -DestinationPath "$releaseFull/owornate-v$version-source.zip"
Compress-Archive -LiteralPath $monet -DestinationPath "$releaseFull/watch2-monet-v6-source.zip"
Copy-Item -LiteralPath "$project/dist/$apkName" -Destination "$releaseFull/$apkName"
Get-ChildItem -LiteralPath "$bundle/payload/plugins" -Filter '*.owo' -File | Copy-Item -Destination $releaseFull
& (Join-Path $project 'plugins/make-update-catalog.ps1') -ApkFile "$releaseFull/$apkName" -PluginPackageDirectory "$bundle/payload/plugins" -OutputFile "$releaseFull/owornate-updates.json" -ManagerVersionCode $versionCode -ManagerVersionName $version
$checks=@(Get-ChildItem -LiteralPath $releaseFull -File|Where-Object{$_.Extension -in @('.zip','.apk','.owo','.json')}|ForEach-Object{(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+'  '+$_.Name})
[IO.File]::WriteAllLines("$releaseFull/SHA256SUMS.txt",$checks,[Text.Encoding]::ASCII)
Write-Output $releaseFull
