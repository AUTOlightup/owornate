#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Setup','Install','Status','RemoveManager','VerifyBoot','EnableWidgets')][string]$Action='Status',
    [string]$Serial='', [string]$ExpectedHardware='', [string]$Adb='',
    [string]$BundleRoot=''
)
$ErrorActionPreference='Stop'
if(!$BundleRoot){$BundleRoot=$PSScriptRoot}
$BundleRoot=[IO.Path]::GetFullPath($BundleRoot)
if($Action -eq 'Install'){$Action='Setup'}
$Base='/data/local/tmp/watchworkshop-runtime'
$App='dev.codex.watchworkshop'
$Component="$App/$App.RuntimeAccessibility"
$Launcher='com.heytap.wearable.launcher'
$Mailbox="/data/user_de/0/$Launcher/files/watchworkshop-theme"
$HookShas=@('6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722','0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d','9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1')
$ThemeSha='706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74'
if(!$Adb){$Adb=Join-Path $BundleRoot 'tools/adb/adb.exe';if(!(Test-Path -LiteralPath $Adb)){$found=Get-Command adb -ErrorAction SilentlyContinue;if($found){$Adb=$found.Source}}}
if(!(Test-Path -LiteralPath $Adb -PathType Leaf)){throw '找不到 adb。安装包请完整解压；源码运行请传 -Adb。'}
function Run-Adb([string[]]$NativeArguments,[switch]$Global){
    $all=@();if(!$Global){$all+=@('-s',$Serial)};$all+=$NativeArguments
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=$Adb
    $info.Arguments=($all|ForEach-Object{'"'+[regex]::Replace([regex]::Replace($_,'(\\*)"','$1$1\"'),'(\\+)$','$1$1')+'"'}) -join ' '
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.StandardOutputEncoding=New-Object Text.UTF8Encoding($false);$info.StandardErrorEncoding=New-Object Text.UTF8Encoding($false)
    $p=New-Object Diagnostics.Process;$p.StartInfo=$info;[void]$p.Start();$stdout=$p.StandardOutput.ReadToEndAsync();$stderr=$p.StandardError.ReadToEndAsync()
    if(!$p.WaitForExit(90000)){try{$p.Kill()}finally{$p.Dispose()};throw 'ADB操作超时。重新连接后运行状态检查。'}
    $p.WaitForExit();$rc=$p.ExitCode;$value=($stdout.Result+$stderr.Result).Trim();$p.Dispose();if($rc -ne 0){throw ('ADB失败: '+$value)};return $value
}
function Shell([string]$Code){Run-Adb @('shell',$Code)}
function Hash-Device([string]$Path){((Shell "sha256sum $Path 2>/dev/null || true") -split '\s+')[0]}
function Services([string]$Value){if($Value -and $Value -ne 'null'){@($Value.Split(':')|Where-Object{$_})}else{@()}}
function Set-Services([string[]]$Items){if($Items.Count){$v=$Items -join ':';if($v -notmatch '^[a-zA-Z0-9_./:$-]+$'){throw '无障碍列表格式不支持'};[void](Shell "settings put secure enabled_accessibility_services '$v'")}else{[void](Shell 'settings delete secure enabled_accessibility_services')}}
function Remote-Json([string]$Path,[object]$Value){$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($Value|ConvertTo-Json -Depth 8 -Compress)));[void](Shell "printf '%s' '$encoded' | base64 -d > $Path")}
function Check-Payload {
    $manifestPath=Join-Path $BundleRoot 'manifest.json';if(!(Test-Path -LiteralPath $manifestPath)){throw '缺少manifest.json。请运行打包脚本后使用完整安装包。'}
    $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8|ConvertFrom-Json
    if($manifest.apk -notmatch '^OWOrnate-v[a-zA-Z0-9.\-]+\.apk$'){throw 'APK清单文件名无效'}
    $script:PayloadApk=Join-Path $BundleRoot ('payload/'+$manifest.apk)
    foreach($f in $manifest.files){if($f.path -notmatch '^(payload|tools|licenses)/[a-zA-Z0-9_.\-/]+$' -or $f.path.Contains('..')){throw '清单路径无效'};$file=Join-Path $BundleRoot $f.path;if(!(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.sha256){throw ('文件校验失败: '+$f.path)}}
}
function Check-Compatible {
    if((Shell 'getprop ro.product.model') -ne 'OW20W1' -or (Shell 'getprop ro.build.display.id') -ne 'OW20W1_11_A.92' -or (Shell 'getprop ro.build.version.sdk') -ne '27' -or (Shell 'getprop ro.product.cpu.abi') -ne 'armeabi-v7a'){throw '首发仅适配OW20W1 / A.92 / API27 / armeabi-v7a。'}
    $apk=Shell "pm path $Launcher";if($apk -notmatch '^package:(/[a-zA-Z0-9_./-]+\.apk)$'){throw '无法识别Launcher APK路径'}
    if((Hash-Device $Matches[1]) -ne '3ba588ffc5d40b928b6f6cec115a92744a3966898be822c920c48d73d40c5f16'){throw 'Launcher版本不同，请等待对应适配。'}
}
function Wait-Theme {
    for($n=0;$n -lt 24;$n++){$raw=Shell "cat $Mailbox/hello.json 2>/dev/null; echo; pidof $Launcher";if($raw -match '(?s)(\{.*\})\s+(\d+)\s*$'){$hello=$Matches[1]|ConvertFrom-Json;if($hello.helperVersion -eq 6 -and $hello.protocol -eq 1 -and "$($hello.pid)" -eq $Matches[2]){return $hello}};Start-Sleep -Seconds 2};throw '未确认主题加载。保留安装状态，请运行状态入口查看或卸载恢复。'
}
function Restart-Launcher {
    $pidText=(Shell "pidof $Launcher").Trim();if($pidText -notmatch '^\d+$'){throw 'Launcher PID无效'}
    $actual=Shell ('tr ''\000'' '' '' < /proc/'+$pidText+'/cmdline');if($actual.Trim() -ne $Launcher){throw 'Launcher身份不同'}
    [void](Shell "kill -9 $pidText")
}
function Show-Status {
    Write-Host (Shell "id -u; getprop ro.build.display.id; if [ -f $Base/manager-ready ]; then echo 'manager=ready'; fi; if [ -f $Base/installed ]; then sh $Base/monet-service.sh status; else echo 'monet=not-installed'; fi")
    $raw=Shell "cat /data/user_de/0/$Launcher/files/watchface-theme-status.json 2>/dev/null || true"
    if($raw){$current=$raw|ConvertFrom-Json;$safe=[ordered]@{};foreach($field in @('ready','mode','seed','active','corners','error','helperVersion')){$safe[$field]=$current.$field};Write-Host ($safe|ConvertTo-Json -Compress)}
}
if(!$Serial){$online=@((Run-Adb @('devices') -Global) -split '\r?\n'|Where-Object{$_ -match '^\S+\s+device\s*$'}|ForEach-Object{($_ -split '\s+')[0]});if($online.Count -ne 1){throw '请使用-Serial指定一台已连接手表（也可通过cmd入口输入局域网IP:5555）。'};$Serial=$online[0]}
if($Serial -notmatch '^[a-zA-Z0-9.:-]+$'){throw '设备连接标识格式无效'}
if($Serial.Contains(':')){[void](Run-Adb @('connect',$Serial) -Global)}
$hardware=(Shell 'getprop ro.serialno').Trim()
if(!$hardware -or $hardware -notmatch '^[a-zA-Z0-9_-]+$'){throw '设备硬件标识无效'}
if($ExpectedHardware -and $hardware -ne $ExpectedHardware){throw '连接到的硬件与预期不同'}
if((Shell 'id -u') -ne '0'){throw '需要已有root adbd；安装包不负责root。'}
if($Action -eq 'Status'){Show-Status;exit}
if($Action -eq 'VerifyBoot'){
    $boot=(Shell 'cat /proc/sys/kernel/random/boot_id').Trim();$raw=Shell "cat /data/user/0/$App/shared_prefs/runtime.xml 2>/dev/null || true"
    if($raw -notmatch ('<string name="bootId">'+[regex]::Escape($boot)+'</string>') -or $raw -notmatch '<boolean name="bootOk" value="true"'){throw '尚未确认本次开机由APP恢复。请手动重启后，首次开工坊之前再次运行此入口。'}
    $null=Wait-Theme;Write-Host '本次开机记录与当前主题加载一致。';exit
}
Check-Compatible
$integrated=(Shell 'test -f /data/local/tmp/watchhide/hook.js && echo yes || true') -eq 'yes'
if($Action -eq 'EnableWidgets'){
    Check-Payload
    if(!$integrated){throw '本版小组件接入需要已有共享 Hook v9/v10/v11；独立莫奈环境暂不支持。'}
    $hook='/data/local/tmp/watchhide/hook.js'
    $v9=$HookShas[0];$v10=$HookShas[1];$v11=$HookShas[2]
    $current=Hash-Device $hook
    if($current -notin $HookShas -or (Hash-Device '/data/local/tmp/watchface-theme/theme.jar') -ne $ThemeSha){throw '共享组件版本不受支持，未作修改。'}
    if((Shell "pm path $App") -notmatch '^package:'){throw '请先运行 00-Setup.cmd 安装管理器。'}
    # Query the existing supervisor using its own read-only status action.
    $status=Shell 'sh /data/local/tmp/watchhide/watchhide-bootstrap.sh status'
    if($status -notmatch '(?m)^supervisor_pid=(\d+)\s*$'){throw '既有 Launcher 守护程序未运行，请恢复原组件后重试。'}
    $candidate=Join-Path $BundleRoot 'payload/WatchHide-Launcher-Hook-v11.js'
    if((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant() -ne $v11){throw '小组件接入文件指纹不符'}
    # Upgrade the shared hook to v11 when an older shared hook (v9/v10) is live.
    if($current -ne $v11){
        $backup='/data/local/tmp/watchhide/hook-before-v11.js'
        [void](Shell "if [ ! -e $backup ]; then cp -p $hook $backup; fi")
        [void](Run-Adb @('push',$candidate,"$hook.widgets-new"))
        if((Hash-Device "$hook.widgets-new") -ne $v11){throw '小组件接入文件推送不完整'}
        [void](Shell "chmod 644 $hook.widgets-new && chown 0:0 $hook.widgets-new && mv $hook.widgets-new $hook")
    }
    Restart-Launcher
    $null=Wait-Theme
    Write-Host '小组件接入文件已更新到 v11 并重载 Launcher。可在负一屏原生编辑页添加/移除卡片；在管理器里可开关隐藏小布助手。'
    exit
}
if($Action -eq 'Setup'){
    Check-Payload
    if($integrated){
        if((Hash-Device '/data/local/tmp/watchhide/hook.js') -notin $HookShas -or (Hash-Device '/data/local/tmp/watchface-theme/theme.jar') -ne $ThemeSha){throw '现有综合组件版本不同，不能接管。需要 Launcher Hook v9/v10/v11 + helper v6。'}
    }else{
        if((Shell 'test -f /data/local/tmp/watch-monet/hook.js && echo legacy || true') -eq 'legacy'){throw '存在旧独立分享版，请先按旧包说明卸载运行组件。'}
        $known=Shell "cat $Base/install-state.json 2>/dev/null || true"
        if($known){$prior=$known|ConvertFrom-Json;if($prior.schema -ne 1 -or $prior.hardware -ne $hardware){throw '基础环境记录不匹配'}}
        elseif((Shell "test -f $Base/frida-inject -o -f $Base/hook.js -o -f $Base/theme.jar -o -f $Base/monet-service.sh && echo unknown || true") -eq 'unknown'){throw '基础目录已有未知组件'}
        if((Shell "test -f $Base/installed && echo yes || true") -eq 'yes'){
            if((Hash-Device "$Base/theme.jar") -ne $ThemeSha -or (Hash-Device "$Base/hook.js") -ne '473ab51ed1888c278a414877eaafe04b59c6aa6d02a670548a50982ecac6aa35'){throw '原独立模块版本不匹配'}
        }else{
            $launcherPid=(Shell "pidof $Launcher").Trim()
            if($launcherPid -match '^\d+$' -and (Shell "grep -q frida-agent-32.so /proc/$launcherPid/maps && echo occupied || true") -eq 'occupied'){throw '已有其他注入环境，不能部署第二套'}
        }
        [void](Shell "mkdir -p $Base; chmod 755 $Base")
        if(!$known){
            $state=[ordered]@{schema=1;hardware=$hardware;persistPort=(Shell 'getprop persist.adb.tcp.port');servicePort=(Shell 'getprop service.adb.tcp.port');services=(Shell 'settings get secure enabled_accessibility_services');accessibilityEnabled=(Shell 'settings get secure accessibility_enabled');whitelisted=((Shell 'cmd deviceidle whitelist') -match [regex]::Escape($App))}
            Remote-Json "$Base/install-state.json" $state;[void](Shell "chmod 600 $Base/install-state.json")
        }
    }
    Write-Host (Run-Adb @('install','-r',$PayloadApk))
    [void](Shell "am start -n $App/.MainActivity")
    $key='';for($n=0;$n -lt 10;$n++){$key=Shell "cat /data/user/0/$App/files/adb/adbkey.pub 2>/dev/null || true";if($key -match '^[A-Za-z0-9+/=]+ watchworkshop@app$'){break};Start-Sleep -Seconds 1}
    if($key -notmatch '^[A-Za-z0-9+/=]+ watchworkshop@app$' -or [Convert]::FromBase64String(($key -split ' ')[0]).Length -ne 524){throw 'APP公钥尚未就绪，请重新运行首次部署。'}
    [void](Shell "grep -qxF '$key' /data/misc/adb/adb_keys || printf '%s\n' '$key' >> /data/misc/adb/adb_keys")
    if($integrated){Write-Host '管理器已就绪。打开 OWOrnate 管理已有莫奈；首次接入小组件请运行 02-EnableWidgets.cmd。';exit}
    $frida=Join-Path $BundleRoot 'payload/frida-inject'
    if((Get-FileHash -LiteralPath $frida -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea'){throw '基础依赖版本不符'}
    if((Hash-Device "$Base/frida-inject") -ne 'e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea'){
        [void](Run-Adb @('push',$frida,"$Base/frida-inject.new"))
        if((Hash-Device "$Base/frida-inject.new") -ne 'e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea'){throw '基础依赖推送不完整'}
        [void](Shell "mv $Base/frida-inject.new $Base/frida-inject; chmod 755 $Base/frida-inject")
    }
    $services=@(Services (Shell 'settings get secure enabled_accessibility_services'));if($Component -notin $services){$services+=$Component;Set-Services $services}
    [void](Shell "settings put secure accessibility_enabled 1; cmd deviceidle whitelist +$App; setprop persist.adb.tcp.port 5555")
    if((Shell 'getprop persist.adb.tcp.port') -ne '5555'){throw '管理器启动通道未能持久化，请重新部署'}
    if((Shell 'getprop service.adb.tcp.port') -ne '5555'){
        Write-Host (Run-Adb @('tcpip','5555'))
        if($Serial.Contains(':')){Start-Sleep -Seconds 2;[void](Run-Adb @('connect',$Serial) -Global)}
        [void](Run-Adb @('wait-for-device'))
        if((Shell 'id -u') -ne '0'){throw '重连后 root adbd 不可用。请恢复 root adbd 后重新部署'}
    }
    [void](Shell "touch $Base/manager-ready")
    Write-Host '管理器部署完成。现在在手表首页点击“安装莫奈”；安装、停用、卸载与重装都在手表完成。'
}else{
    if($integrated){throw '共享环境只需在系统中卸载管理器 APP，不删除原有综合组件。'}
    if((Shell "test -f $Base/installed && echo yes || true") -eq 'yes'){throw '请先在手表的功能管理中卸载莫奈，再移除管理器基础环境。'}
    $raw=Shell "cat $Base/install-state.json 2>/dev/null || true";if(!$raw){throw '没有本包基础环境记录'};$state=$raw|ConvertFrom-Json
    if($state.schema -ne 1 -or $state.hardware -ne $hardware){throw '基础环境记录与硬件不同'}
    $services=@(Services (Shell 'settings get secure enabled_accessibility_services')|Where-Object{$_ -ne $Component});Set-Services $services
    if(!$services.Count){if($state.accessibilityEnabled -eq 'null'){[void](Shell 'settings delete secure accessibility_enabled')}elseif($state.accessibilityEnabled -match '^[01]$'){[void](Shell "settings put secure accessibility_enabled $($state.accessibilityEnabled)")}}
    if(!$state.whitelisted){[void](Shell "cmd deviceidle whitelist -$App")}
    [void](Shell "rm -f $Base/manager-ready $Base/frida-inject")
    foreach($prop in @('persist.adb.tcp.port','service.adb.tcp.port')){$old=if($prop.StartsWith('persist')){$state.persistPort}else{$state.servicePort};if($old -notmatch '^-?\d{0,5}$'){throw '旧端口格式无效'};if((Shell "getprop $prop") -eq '5555'){[void](Shell "setprop $prop '$old'")}}
    Write-Host '管理器基础环境已移除；APP可在系统中卸载。'
}
