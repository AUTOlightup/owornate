#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Setup','Install','Status','RemoveManager','VerifyBoot','OpenManager','EnableWidgets')][string]$Action='Status',
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
$RuntimeMark="/data/user_de/0/$Launcher/files/watchworkshop-runtime.json"
$HookShas=@('6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722','0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d','9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1','4bc93055211b1c070155b45808955b6e41380c51edc63010ba8f0f759c508ac0','d0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7','aff84e9105a66738234398db5e5beef4fd67b384a804308479cfda472ef349a1','fdbf56b324eb29fd37f9d92644b051194503024ea34fcca4b1ddcdd9ecb3d46a','46b4ae970215c889e36524c3969b66e45bba45100afd973daae07947f531ea19')
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
function Read-SetupResult([string]$Nonce){
    $raw=Shell "cat /data/user/0/$App/files/setup-check.json 2>/dev/null || true"
    if(!$raw){return $null}
    try{$value=$raw|ConvertFrom-Json}catch{throw '应用回写的 setup-check.json 不是有效 JSON。'}
    if(!$value.token -or "$($value.token)" -ne $Nonce){return $null}
    return $value
}
function Confirm-SetupCheck {
    $nonce=[guid]::NewGuid().ToString()
    $started=Run-Adb @('shell',"am start -n $App/.MainActivity --es owornate_setup_check $nonce")
    if($started -match '(?im)^Error:|Exception occurred|Unable to resolve|Activity not found'){throw ('无法启动管理器认证页: '+$started)}
    # The activity writes the result asynchronously. Only a token-matched boolean true confirms
    # the app-side loopback ADB handshake; host-side adb root alone is not sufficient.
    for($i=0;$i -lt 30;$i++){
        $result=Read-SetupResult $nonce
        if($result){
            if($result.ok -eq $true){return}
            $detail=[string]$result.error
            if(!$detail){$detail='应用未提供错误说明'}
            throw ('应用内 root 回环认证失败: '+$detail)
        }
        Start-Sleep -Milliseconds 500
    }
    throw '应用内 root 回环认证在 15 秒内未返回匹配结果。请保持解锁，查看管理器错误后重试 00-Setup。'
}
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
function Installed-Features {
    $raw=Shell ('for f in '+$Base+'/modules/*; do if [ -d "$f" ]; then [ -f "$f/installed" ] && printf "%s\n" "${f##*/}"; else case "$f" in *.disabled) continue;; esac; [ -f "$f" ] && printf "%s\n" "${f##*/}"; fi; done; true')
    if($raw){@($raw -split '\r?\n'|Where-Object{$_})}else{@()}
}
function Verify-OwnRuntime {
    if((Shell "cat $Base/runtime-version 2>/dev/null || true").Trim() -notin @('2','3','4')){throw '未识别到本包运行时'}
    $expected=@{};$active=$false
    $extensionExpected=@{}
    foreach($kind in @('monet','widgets','capture')){
        $installed=(Shell "test -f $Base/modules/$kind && echo yes || true") -eq 'yes'
        $disabled=(Shell "test -f $Base/modules/$kind.disabled && echo yes || true") -eq 'yes'
        $expected[$kind]=$installed -and (!$disabled)
        if($expected[$kind]){$active=$true}
    }
    foreach($id in @(Installed-Features)){
        if($id -in @('monet','widgets','capture')){continue}
        if($id.Length -gt 80 -or $id -cnotmatch '^[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+$'){throw '插件运行标识无效'}
        $extensionExpected[$id]=(Shell "test -f $Base/modules/$id/disabled && echo yes || true") -ne 'yes'
        if($extensionExpected[$id]){$active=$true}
    }
    if(!$active){throw '本包运行时没有已启用功能，无法验证当前启动映射'}
    $runtimeStatus=Shell "sh $Base/runtime-service.sh status 2>/dev/null || true"
    if($runtimeStatus -notmatch '(?m)^service_pid=(\d+)\s*$'){throw '本包运行时服务未启动'}
    $servicePid=$Matches[1]
    if($runtimeStatus -notmatch '(?m)^launcher_pid=(\d+)\s*$'){throw '运行时未确认 Launcher PID'}
    $launcherPid=$Matches[1]
    if($runtimeStatus -notmatch '(?m)^(?:runtime|theme)_live=yes\s*$'){throw '运行时 Hook 未映射到当前 Launcher'}
    $raw=Shell "cat $RuntimeMark 2>/dev/null || true"
    if(!$raw){throw '缺少当前运行时状态标记'}
    try{$mark=$raw|ConvertFrom-Json}catch{throw '运行时状态标记不是有效 JSON'}
    if($mark.version -ne 2 -or "$($mark.pid)" -ne $launcherPid){throw '运行时标记的版本或 PID 与当前 Launcher 不一致'}
    if([string]$mark.error -ne ''){throw ('运行时报告错误: '+$mark.error)}
    foreach($kind in @('monet','widgets','capture')){
        $property=$mark.PSObject.Properties[$kind]
        if(!$property -or [bool]$property.Value -ne $expected[$kind]){throw ('运行时标记的 '+$kind+' 启用状态不一致')}
    }
    foreach($id in $extensionExpected.Keys){
        $property=$mark.extensions.PSObject.Properties[$id]
        if(!$property -or $property.Value -isnot [bool] -or $property.Value -ne $extensionExpected[$id]){throw ('插件运行标记不匹配: '+$id)}
    }
    if((Shell "pidof $Launcher").Trim() -ne $launcherPid){throw '运行时 Launcher PID 已变化'}
    return [ordered]@{servicePid=$servicePid;launcherPid=$launcherPid;mask=($expected|ConvertTo-Json -Compress)}
}
function Restart-Launcher {
    $pidText=(Shell "pidof $Launcher").Trim();if($pidText -notmatch '^\d+$'){throw 'Launcher PID无效'}
    $actual=Shell ('tr ''\000'' '' '' < /proc/'+$pidText+'/cmdline');if($actual.Trim() -ne $Launcher){throw 'Launcher身份不同'}
    [void](Shell "kill -9 $pidText")
}
function Show-Status {
    Write-Host (Shell "id -u; getprop ro.build.display.id; if [ -f $Base/manager-ready ]; then echo 'manager=ready'; fi; if [ -f $Base/installed ]; then sh $Base/monet-service.sh status; else echo 'monet=not-installed'; fi")
    $features=@(Installed-Features)
    foreach($feature in $features){Write-Host ('feature='+$feature)}
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
    $bootIntegrated=(Shell 'test -f /data/local/tmp/watchhide/hook.js && echo yes || true') -eq 'yes'
    if($bootIntegrated){
        if((Hash-Device '/data/local/tmp/watchhide/hook.js') -notin $HookShas -or (Hash-Device '/data/local/tmp/watchface-theme/theme.jar') -ne $ThemeSha){throw '共享组件指纹未知，无法确认既有加载范围'}
        $null=Wait-Theme
        Write-Host '已知共享环境的 Hook 与 helper 当前已加载。本包不接管其开机恢复，此结果不证明本包运行时恢复。'
        exit
    }
    if((Shell 'test -f /data/local/tmp/watch-monet/hook.js && echo legacy || true') -eq 'legacy'){throw '存在旧独立分享版，不能验证本包开机恢复'}
    $boot=(Shell 'cat /proc/sys/kernel/random/boot_id').Trim();$raw=Shell "cat /data/user/0/$App/shared_prefs/runtime.xml 2>/dev/null || true"
    if($raw -notmatch ('<string name="bootId">'+[regex]::Escape($boot)+'</string>') -or $raw -notmatch '<boolean name="bootReady" value="true"'){throw '尚未确认本次开机由APP恢复且功能就绪。请手动重启后，在首次使用功能之前再次运行此入口。'}
    if((Shell "cat $Base/runtime-version 2>/dev/null || true").Trim() -in @('2','3','4')){
        $verified=Verify-OwnRuntime
        Write-Host ('本次开机由本包运行时恢复；service_pid='+$verified.servicePid+'; launcher_pid='+$verified.launcherPid+'; mask='+$verified.mask+'.')
        exit
    }
    if((Shell "test -f $Base/installed && echo yes || true") -eq 'yes'){
        $null=Wait-Theme
        Write-Host '本次开机记录与旧独立莫奈主题加载一致。'
        exit
    }
    throw '没有可验证的本包运行时或旧独立莫奈组件'
}
Check-Compatible
$integrated=(Shell 'test -f /data/local/tmp/watchhide/hook.js && echo yes || true') -eq 'yes'
if($Action -eq 'OpenManager' -or $Action -eq 'EnableWidgets'){
    if((Shell "pm path $App") -notmatch '^package:'){throw '管理器尚未安装，请先运行 00-Setup.cmd。'}
    [void](Shell "am start -n $App/.MainActivity")
    Write-Host '已打开管理器。功能接入、重新接入、启停和卸载均在管理器内完成；此兼容入口不修改 Hook 或系统设置。'
    exit
}
if($Action -eq 'Setup'){
    Check-Payload
    $known=Shell "cat $Base/install-state.json 2>/dev/null || true"
    if($known){$prior=$known|ConvertFrom-Json;if($prior.schema -ne 1 -or $prior.hardware -ne $hardware){throw '基础环境记录不匹配'}}
    if($integrated){
        if((Hash-Device '/data/local/tmp/watchhide/hook.js') -notin $HookShas -or (Hash-Device '/data/local/tmp/watchface-theme/theme.jar') -ne $ThemeSha){throw '现有综合组件版本不同，不能接管。需要 Launcher Hook v9/v10/v11/v12/v13 + helper v6。'}
    }else{
        if((Shell 'test -f /data/local/tmp/watch-monet/hook.js && echo legacy || true') -eq 'legacy'){throw '存在旧独立分享版，请先按旧包说明卸载运行组件。'}
        if(!$known -and (Shell "test -f $Base/frida-inject -o -f $Base/hook.js -o -f $Base/theme.jar -o -f $Base/monet-service.sh -o -f $Base/runtime-version -o -f $Base/runtime-service.sh && echo unknown || true") -eq 'unknown'){throw '基础目录已有未知组件'}
        if((Shell "test -f $Base/installed && echo yes || true") -eq 'yes'){
            if((Hash-Device "$Base/theme.jar") -ne $ThemeSha -or (Hash-Device "$Base/hook.js") -ne '473ab51ed1888c278a414877eaafe04b59c6aa6d02a670548a50982ecac6aa35'){throw '原独立模块版本不匹配'}
        }else{
            $launcherPid=(Shell "pidof $Launcher").Trim()
            if($launcherPid -match '^\d+$' -and (Shell "grep -q frida-agent-32.so /proc/$launcherPid/maps && echo occupied || true") -eq 'occupied'){throw '已有其他注入环境，不能部署第二套'}
        }
    }
    [void](Shell "mkdir -p $Base; chmod 755 $Base")
    if(!$known){
        # Capture the pre-setup channel, accessibility and doze state for explicit RemoveManager.
        $state=[ordered]@{schema=1;hardware=$hardware;persistPort=(Shell 'getprop persist.adb.tcp.port');servicePort=(Shell 'getprop service.adb.tcp.port');services=(Shell 'settings get secure enabled_accessibility_services');accessibilityEnabled=(Shell 'settings get secure accessibility_enabled');whitelisted=((Shell 'cmd deviceidle whitelist') -match [regex]::Escape($App))}
        Remote-Json "$Base/install-state.json" $state;[void](Shell "chmod 600 $Base/install-state.json")
    }
    Write-Host (Run-Adb @('install','-r',$PayloadApk))
    [void](Shell "am start -n $App/.MainActivity")
    $key='';for($n=0;$n -lt 10;$n++){$key=Shell "cat /data/user/0/$App/files/adb/adbkey.pub 2>/dev/null || true";if($key -match '^[A-Za-z0-9+/=]+ watchworkshop@app$'){break};Start-Sleep -Seconds 1}
    if($key -notmatch '^[A-Za-z0-9+/=]+ watchworkshop@app$' -or [Convert]::FromBase64String(($key -split ' ')[0]).Length -ne 524){throw 'APP公钥尚未就绪，请重新运行首次部署。'}
    [void](Shell "grep -qxF '$key' /data/misc/adb/adb_keys || printf '%s\n' '$key' >> /data/misc/adb/adb_keys")
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
        $nextSerial=$Serial
        if($Serial.Contains(':')){
            if($Serial -notmatch '^(?<endpoint>[^:]+):[0-9]+$'){throw '无法识别局域网连接地址，请使用 IP:端口。'}
            $nextSerial=$Matches.endpoint+':5555'
        }
        Write-Host (Run-Adb @('tcpip','5555'))
        if($Serial.Contains(':')){$Serial=$nextSerial;Start-Sleep -Seconds 2;[void](Run-Adb @('connect',$Serial) -Global)}
        [void](Run-Adb @('wait-for-device'))
        if((Shell 'id -u') -ne '0'){throw '重连后 root adbd 不可用。请恢复 root adbd 后重新部署'}
        Check-Compatible
        if((Shell 'getprop ro.serialno').Trim() -ne $hardware){throw '重连后的设备与部署目标不同'}
    }
    $hadManagerReady=(Shell "test -f $Base/manager-ready && echo yes || true") -eq 'yes'
    [void](Shell "touch $Base/manager-ready")
    try{Confirm-SetupCheck}catch{if(!$hadManagerReady){[void](Shell "rm -f $Base/manager-ready")};throw}
    Write-Host '管理器基础环境与应用内 root 回环认证均已就绪。请在管理器“发现优化”中分别安装莫奈取色、负一屏小组件和截屏与录屏；Setup 不安装这些功能。'
}else{
    $features=@(Installed-Features)
    if($features.Count){throw ('请先在管理器内卸载功能: '+($features -join '、')+'；模块已停用但安装标记仍在，也必须先卸载。')}
    if((Shell "test -f $Base/installed && echo yes || true") -eq 'yes'){throw '请先在管理器内卸载旧版独立莫奈，再移除管理器基础环境。'}
    $raw=Shell "cat $Base/install-state.json 2>/dev/null || true";if(!$raw){throw '没有本包基础环境记录'};$state=$raw|ConvertFrom-Json
    if($state.schema -ne 1 -or $state.hardware -ne $hardware){throw '基础环境记录与硬件不同'}
    $services=@(Services (Shell 'settings get secure enabled_accessibility_services')|Where-Object{$_ -ne $Component});Set-Services $services
    if(!$services.Count){if($state.accessibilityEnabled -eq 'null'){[void](Shell 'settings delete secure accessibility_enabled')}elseif($state.accessibilityEnabled -match '^[01]$'){[void](Shell "settings put secure accessibility_enabled $($state.accessibilityEnabled)")}}
    if(!$state.whitelisted){[void](Shell "cmd deviceidle whitelist -$App")}
    foreach($prop in @('persist.adb.tcp.port','service.adb.tcp.port')){$old=if($prop.StartsWith('persist')){$state.persistPort}else{$state.servicePort};if($old -notmatch '^-?\d{0,5}$'){throw '旧端口格式无效'};if((Shell "getprop $prop") -eq '5555'){[void](Shell "setprop $prop '$old'")}}
    [void](Shell "rm -f $Base/manager-ready $Base/frida-inject $Base/install-state.json")
    Write-Host '管理器基础环境已移除；APP可在系统中卸载。'
}
