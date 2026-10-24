#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$releaseSource=Join-Path $project 'release-manage.ps1'
$makeSource=Join-Path $project 'make-release.ps1'
$temp=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('owornate-desktop-setup-'+[guid]::NewGuid().ToString('N'))))
$fakeDir=Join-Path $temp 'fake'
$bundle=Join-Path $temp 'bundle'
$testScript=Join-Path $temp 'release-manage-test.ps1'
$fakeExe=Join-Path $fakeDir 'fake-adb.exe'
$fakeCs=Join-Path $fakeDir 'fake-adb.cs'
$hostExe=(Get-Process -Id $PID).Path
$legacy='C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe'

function Assert([bool]$ok,[string]$message){if(!$ok){throw $message}}
function DevicePath([string]$root,[string]$path){Join-Path $root $path.TrimStart('/').Replace('/','\')}
function Write-Text([string]$path,[string]$text){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path)|Out-Null;[IO.File]::WriteAllText($path,$text,[Text.UTF8Encoding]::new($false))}
function Remove-TestTree([string]$path){
    $testRoot=[IO.Path]::GetFullPath($temp)
    $target=[IO.Path]::GetFullPath($path)
    $prefix=$testRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    Assert ($target.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)) ('refusing recursive delete outside fixture: '+$target)
    if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target -Recurse -Force}
}
function Write-Dict([string]$path,[hashtable]$values){$lines=@($values.Keys|Sort-Object|ForEach-Object{[string]$_+'='+[string]$values[$_]});Write-Text $path ($lines -join [Environment]::NewLine)}
function Read-Dict([string]$path){$values=@{};if(Test-Path -LiteralPath $path){foreach($line in [IO.File]::ReadAllLines($path)){$i=$line.IndexOf('=');if($i -gt 0){$values[$line.Substring(0,$i)]=$line.Substring($i+1)}}};$values}
function Write-RuntimeMarker([string]$root,[string]$base,[hashtable]$extensions){
    $marker=[ordered]@{version=2;pid=4242;error='';monet=$false;widgets=$false;capture=$false}
    $marker.extensions=[ordered]@{}
    foreach($id in $extensions.Keys){$marker.extensions[$id]=$extensions[$id]}
    $json=$marker|ConvertTo-Json -Depth 5 -Compress
    Write-Text (Join-Path $base 'marker-copy.json') $json
    Write-Text (DevicePath $root '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json') $json
}
function Init-Device([string]$root,[bool]$shared){
    Remove-TestTree $root
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'state')|Out-Null
    Write-Dict (Join-Path $root 'state\props.txt') @{'persist.adb.tcp.port'='4301';'service.adb.tcp.port'='4301'}
    Write-Dict (Join-Path $root 'state\settings.txt') @{'enabled_accessibility_services'='com.example/.Keep';'accessibility_enabled'='1'}
    Write-Text (Join-Path $root 'state\doze.txt') 'user,com.example.keep'
    if($shared){
        Write-Text (DevicePath $root '/data/local/tmp/watchhide/hook.js') 'shared-hook-unchanged'
        Write-Text (DevicePath $root '/data/local/tmp/watchface-theme/theme.jar') 'shared-theme-unchanged'
    }
}
function Init-Bundle {
    if(Test-Path -LiteralPath $bundle){Remove-TestTree $bundle}
    New-Item -ItemType Directory -Force -Path (Join-Path $bundle 'payload'),(Join-Path $bundle 'tools\adb'),(Join-Path $bundle 'licenses')|Out-Null
    $apk=Join-Path $bundle 'payload\OWOrnate-v1.0.1-rc15.apk'
    Write-Text $apk 'fixture-apk'
    Write-Text (Join-Path $bundle 'payload\frida-inject') 'fixture-frida'
    $files=@(
        [ordered]@{path='payload/OWOrnate-v1.0.1-rc15.apk';sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $apk).Length},
        [ordered]@{path='payload/frida-inject';sha256='e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea';bytes=(Get-Item -LiteralPath (Join-Path $bundle 'payload\frida-inject')).Length}
    )
    $manifest=[ordered]@{version='1.0.1-rc15';versionCode=24;apk='OWOrnate-v1.0.1-rc15.apk';channel='candidate';helperVersion=6;protocol=1;files=$files}
    Write-Text (Join-Path $bundle 'manifest.json') ($manifest|ConvertTo-Json -Depth 6)
}
function Invoke-Action([string]$root,[string]$mode,[string]$action,[bool]$expectFail=$false,[string]$connection='FAKEWATCH001'){
    $env:FAKE_ADB_ROOT=$root
    $env:FAKE_SETUP_MODE=$mode
    $output=& $hostExe -NoProfile -ExecutionPolicy Bypass -File $testScript -Action $action -Serial $connection -Adb $fakeExe -BundleRoot $bundle 2>&1
    $code=$LASTEXITCODE
    if($expectFail){Assert ($code -ne 0) ('expected '+$action+' failure but exited 0: '+($output -join ' | '))}else{Assert ($code -eq 0) ($action+' failed: '+($output -join ' | '))}
    return ($output -join [Environment]::NewLine)
}

try {
    $makeText=[IO.File]::ReadAllText($makeSource,[Text.Encoding]::UTF8)
    foreach($name in @('runtime-manage.sh','runtime-service.sh','runtime-core.js','runtime-commit.js','runtime-base.js','widget-host.js','runtime-hook.js','build-runtime.ps1')){
        Assert ($makeText.Contains("'$name'")) ('make-release whitelist missing '+$name)
    }
    Assert ($makeText.Contains("@('capture-controls.js','capture-indicator.js')")) 'make-release missing runtime fragments'
    Assert ($makeText.Contains('$monet/widget-host/$name')) 'make-release does not place runtime fragments in monet source'

    New-Item -ItemType Directory -Force -Path $fakeDir|Out-Null
    [IO.File]::WriteAllText($fakeCs,@'
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

class FakeAdb
{
    static string Root { get { return Environment.GetEnvironmentVariable("FAKE_ADB_ROOT"); } }
    static string Mode { get { return Environment.GetEnvironmentVariable("FAKE_SETUP_MODE") ?? "success"; } }
    static string StateDir { get { return Path.Combine(Root, "state"); } }
    static string DevicePath(string p)
    {
        p = p.Trim().Trim('\'').Trim('"');
        if (p.StartsWith("/")) p = p.Substring(1);
        return Path.Combine(Root, p.Replace('/', Path.DirectorySeparatorChar));
    }
    static Dictionary<string,string> Load(string name)
    {
        var d = new Dictionary<string,string>(StringComparer.Ordinal);
        var f = Path.Combine(StateDir, name + ".txt");
        if (!File.Exists(f)) return d;
        foreach (var line in File.ReadAllLines(f)) { int i = line.IndexOf('='); if (i > 0) d[line.Substring(0, i)] = line.Substring(i + 1); }
        return d;
    }
    static void Save(string name, Dictionary<string,string> d)
    {
        Directory.CreateDirectory(StateDir);
        File.WriteAllLines(Path.Combine(StateDir, name + ".txt"), d.OrderBy(x => x.Key).Select(x => x.Key + "=" + x.Value), new UTF8Encoding(false));
    }
    static string ReadText(string p) { var f = DevicePath(p); return File.Exists(f) ? File.ReadAllText(f, Encoding.UTF8) : ""; }
    static void WriteText(string p, string value) { var f = DevicePath(p); Directory.CreateDirectory(Path.GetDirectoryName(f)); File.WriteAllText(f, value, new UTF8Encoding(false)); }
    static string InstalledFeatures(string modulesRoot)
    {
        var root = DevicePath(modulesRoot);
        if (!Directory.Exists(root)) return "";
        var found = new List<string>();
        foreach (var path in Directory.GetFileSystemEntries(root).OrderBy(p => Path.GetFileName(p), StringComparer.Ordinal))
        {
            if (Directory.Exists(path))
            {
                if (File.Exists(Path.Combine(path, "installed"))) found.Add(Path.GetFileName(path));
            }
            else if (!Path.GetFileName(path).EndsWith(".disabled", StringComparison.Ordinal))
            {
                found.Add(Path.GetFileName(path));
            }
        }
        return found.Count == 0 ? "" : String.Join("\n", found) + "\n";
    }
    static string GetProp(string key)
    {
        var d = Load("props"); if (d.ContainsKey(key)) return d[key];
        if (key == "ro.product.model") return "OW20W1";
        if (key == "ro.build.display.id") return "OW20W1_11_A.92";
        if (key == "ro.build.version.sdk") return "27";
        if (key == "ro.product.cpu.abi") return "armeabi-v7a";
        if (key == "ro.serialno") return "FAKEWATCH001";
        return "";
    }
    static void SetProp(string key, string value) { var d = Load("props"); d[key] = value; Save("props", d); }
    static string GetSetting(string key) { var d = Load("settings"); return d.ContainsKey(key) ? d[key] : "null"; }
    static void SetSetting(string key, string value) { var d = Load("settings"); d[key] = value; Save("settings", d); }
    static void DeleteSetting(string key) { var d = Load("settings"); d.Remove(key); Save("settings", d); }
    static string Sha(string path)
    {
        if (path.EndsWith("/watchhide/hook.js") && File.Exists(DevicePath(path))) return "d0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7";
        if (path.EndsWith("/watchface-theme/theme.jar") && File.Exists(DevicePath(path))) return "706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74";
        if (path.EndsWith("launcher.apk")) return "3ba588ffc5d40b928b6f6cec115a92744a3966898be822c920c48d73d40c5f16";
        if ((path.EndsWith("/frida-inject") || path.EndsWith("/frida-inject.new")) && File.Exists(DevicePath(path))) return "e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea";
        if (path.EndsWith("/theme.jar") && File.Exists(DevicePath(path))) return "706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74";
        if (path.EndsWith("/hook.js") && File.Exists(DevicePath(path))) return "473ab51ed1888c278a414877eaafe04b59c6aa6d02a670548a50982ecac6aa35";
        var f = DevicePath(path); if (!File.Exists(f)) return "";
        using (var sha = SHA256.Create()) using (var fs = File.OpenRead(f)) return String.Concat(sha.ComputeHash(fs).Select(b => b.ToString("x2")));
    }
    static string StartActivity(string command)
    {
        var m = Regex.Match(command, @"owornate_setup_check\s+([A-Za-z0-9\-]+)");
        if (!m.Success) return "Starting: Intent { }\n";
        var token = m.Groups[1].Value;
        var delivered = "Warning: Activity not started, intent has been delivered to currently running top-most instance.\n";
        if (Mode == "timeout") return delivered;
        string actual = Mode == "mismatch" ? token + "-other" : token;
        bool ok = Mode == "success";
        string error = ok ? "" : "fixture app-side handshake failed";
        string json = "{\"token\":\"" + actual + "\",\"ok\":" + (ok ? "true" : "false") + ",\"error\":\"" + error + "\"}";
        WriteText("/data/user/0/dev.codex.watchworkshop/files/setup-check.json", json);
        return delivered;
    }
    static string Shell(string command)
    {
        command = command.Trim();
        if (command == "id -u") return "0";
        if (Regex.IsMatch(command, @"^getprop\s+\S+$")) return GetProp(command.Substring(7).Trim());
        var sha = Regex.Match(command, @"^sha256sum\s+(\S+)"); if (sha.Success) return Sha(sha.Groups[1].Value) + "  " + sha.Groups[1].Value + "\n";
        var pm = Regex.Match(command, @"^pm path\s+(\S+)");
        if (pm.Success)
        {
            if (pm.Groups[1].Value == "com.heytap.wearable.launcher") return "package:/system/fake/launcher.apk\n";
            if (pm.Groups[1].Value == "dev.codex.watchworkshop" && File.Exists(DevicePath("/data/local/tmp/fake-app-installed"))) return "package:/data/app/fake.apk\n";
            return "";
        }
        var sg = Regex.Match(command, @"^settings get secure\s+(\S+)"); if (sg.Success) return GetSetting(sg.Groups[1].Value);
        var sp = Regex.Match(command, @"settings put secure\s+(\S+)\s+(\S+)"); if (sp.Success) SetSetting(sp.Groups[1].Value, sp.Groups[2].Value.Trim('\'').Trim('"'));
        var sd = Regex.Match(command, @"settings delete secure\s+(\S+)"); if (sd.Success) DeleteSetting(sd.Groups[1].Value);
        var dw = Regex.Match(command, @"cmd deviceidle whitelist\s+([+-])([^\s;]+)");
        if (dw.Success)
        {
            var f = Path.Combine(StateDir, "doze.txt");
            var items = File.Exists(f) ? File.ReadAllLines(f).Where(x => x != "user," + dw.Groups[2].Value).ToList() : new List<string>();
            if (dw.Groups[1].Value == "+") items.Add("user," + dw.Groups[2].Value);
            Directory.CreateDirectory(StateDir); File.WriteAllLines(f, items, new UTF8Encoding(false));
        }
        else if (Regex.IsMatch(command, @"^cmd deviceidle whitelist$"))
        {
            var f = Path.Combine(StateDir, "doze.txt"); return File.Exists(f) ? String.Join("\n", File.ReadAllLines(f)) + "\n" : "";
        }
        foreach (Match p in Regex.Matches(command, @"setprop\s+(\S+)\s+(\S+)")) SetProp(p.Groups[1].Value, p.Groups[2].Value.Trim('\''));
        var tf = Regex.Match(command, @"test -f (.+?) && echo ([^\s|]+)");
        if (tf.Success)
        {
            foreach (var raw in Regex.Split(tf.Groups[1].Value, @"\s+-o\s+"))
            {
                var c = raw.Trim(); if (c.StartsWith("-f ")) c = c.Substring(3).Trim();
                if (File.Exists(DevicePath(c))) return tf.Groups[2].Value + "\n";
            }
        }
        var mk = Regex.Match(command, @"mkdir -p\s+([^;]+)"); if (mk.Success) Directory.CreateDirectory(DevicePath(mk.Groups[1].Value.Trim()));
        var touch = Regex.Match(command, @"^touch\s+(\S+)"); if (touch.Success) WriteText(touch.Groups[1].Value, "");
        var pr = Regex.Match(command, @"printf '%s' '([^']*)' \| base64 -d > (\S+)");
        if (pr.Success) { var bytes = Convert.FromBase64String(pr.Groups[1].Value); var f = DevicePath(pr.Groups[2].Value); Directory.CreateDirectory(Path.GetDirectoryName(f)); File.WriteAllBytes(f, bytes); }
        var gp = Regex.Match(command, @"grep -qxF '([^']+)' (\S+)");
        if (gp.Success)
        {
            var f = DevicePath(gp.Groups[2].Value); var want = gp.Groups[1].Value;
            var lines = File.Exists(f) ? File.ReadAllLines(f).ToList() : new List<string>();
            if (!lines.Contains(want)) { lines.Add(want); Directory.CreateDirectory(Path.GetDirectoryName(f)); File.WriteAllLines(f, lines, new UTF8Encoding(false)); }
        }
        if (command.Contains("am start ")) return StartActivity(command);
        if (command.Contains("hello.json") && command.Contains("pidof com.heytap.wearable.launcher"))
            return "{\"helperVersion\":6,\"protocol\":1,\"pid\":4242}\n\n4242\n";
        var rstat = Regex.Match(command, @"^sh (\S+)/runtime-service\.sh status");
        if (rstat.Success)
        {
            var rtBase = rstat.Groups[1].Value;
            return "service_pid=777\nlauncher_pid=4242\nruntime_live=yes\n" + ReadText(rtBase + "/marker-copy.json");
        }
        if (command.Contains("for f in ") && command.Contains("/modules/*; do") && command.Contains("$f/installed"))
        {
            var installed = Regex.Match(command, @"for f in (\S+)/modules/\*");
            if (installed.Success) return InstalledFeatures(installed.Groups[1].Value + "/modules");
        }
        var featureLoop = Regex.Match(command, @"for f in monet widgets capture; do if \[ -f (\S+)/modules/\$f \]; then echo feature=\$f; fi; done");
        if (featureLoop.Success)
        {
            var featureRoot = featureLoop.Groups[1].Value;
            var found = new List<string>();
            foreach (var name in new[] { "monet", "widgets", "capture" })
                if (File.Exists(DevicePath(featureRoot + "/modules/" + name))) found.Add("feature=" + name);
            return found.Count == 0 ? "" : String.Join("\n", found) + "\n";
        }
        if (Regex.IsMatch(command, @"^grep -q frida-agent-32\.so\s+/proc/\d+/maps")) return "";
        var cat = Regex.Match(command, @"^cat\s+(\S+)"); if (cat.Success) return ReadText(cat.Groups[1].Value);
        var rm = Regex.Match(command, @"^rm -f\s+(.+)$");
        if (rm.Success) foreach (var p in rm.Groups[1].Value.Split(new[] {' '}, StringSplitOptions.RemoveEmptyEntries)) { var f = DevicePath(p); if (File.Exists(f)) File.Delete(f); }
        var mv = Regex.Match(command, @"mv\s+(\S+)\s+([^\s;]+)");
        if (mv.Success)
        {
            var from = DevicePath(mv.Groups[1].Value); var to = DevicePath(mv.Groups[2].Value);
            if (File.Exists(from)) { Directory.CreateDirectory(Path.GetDirectoryName(to)); if (File.Exists(to)) File.Delete(to); File.Move(from, to); }
        }
        if (command == "pidof com.heytap.wearable.launcher") return "4242\n";
        if (command.StartsWith("tr ")) return "com.heytap.wearable.launcher\0\0\0\n";
        return "";
    }
    static int Main(string[] args)
    {
        var a = args.ToList();
        var connection = a.Count >= 2 && a[0] == "-s" ? a[1] : "";
        if (a.Count >= 2 && a[0] == "-s") a.RemoveRange(0, 2);
        if (Root == null) { Console.Error.WriteLine("FAKE_ADB_ROOT missing"); return 2; }
        Directory.CreateDirectory(Root); if (a.Count == 0) return 2;
        var cmd = a[0];
        if (connection.Contains(":") && !connection.EndsWith(":" + GetProp("service.adb.tcp.port")))
        {
            Console.Error.WriteLine("fixture: reconnect used an obsolete port"); return 2;
        }
        if (cmd == "devices") { Console.WriteLine("List of devices attached\nFAKEWATCH001\tdevice\n"); return 0; }
        if (cmd == "connect") { Console.WriteLine("connected"); return 0; }
        if (cmd == "wait-for-device") return 0;
        if (cmd == "tcpip") { SetProp("service.adb.tcp.port", a.Count > 1 ? a[1] : "5555"); Console.WriteLine("restarting in TCP mode port"); return 0; }
        if (cmd == "install")
        {
            WriteText("/data/local/tmp/fake-app-installed", "1");
            var key = Convert.ToBase64String(new byte[524]);
            WriteText("/data/user/0/dev.codex.watchworkshop/files/adb/adbkey.pub", key + " watchworkshop@app\n");
            Console.WriteLine("Success"); return 0;
        }
        if (cmd == "push") { var target = DevicePath(a[2]); Directory.CreateDirectory(Path.GetDirectoryName(target)); File.Copy(a[1], target, true); Directory.CreateDirectory(StateDir); File.AppendAllText(Path.Combine(StateDir, "push.log"), a[2] + Environment.NewLine, new UTF8Encoding(false)); Console.WriteLine("1 file pushed"); return 0; }
        if (cmd == "shell") { Console.Write(Shell(String.Join(" ", a.Skip(1)))); return 0; }
        Console.Error.WriteLine("unsupported fake adb command: " + cmd); return 2;
    }
}


'@,[Text.UTF8Encoding]::new($false))
    $csc=Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    Assert (Test-Path -LiteralPath $csc) 'csc.exe unavailable for fake adb fixture'
    & $csc /nologo /target:exe /out:$fakeExe $fakeCs | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'fake adb fixture did not compile'

    $tokens=$null;$errors=$null
    [System.Management.Automation.Language.Parser]::ParseFile($releaseSource,[ref]$tokens,[ref]$errors)|Out-Null
    Assert ($errors.Count -eq 0) ('release-manage parse failed: '+($errors.Message -join '; '))
    $shim=@'
$script:OwornateRealFileHash=Get-Command Microsoft.PowerShell.Utility\Get-FileHash -CommandType Cmdlet
function Get-FileHash {
    param([string]$LiteralPath,[string]$Algorithm='SHA256')
    if($LiteralPath -match '[\\/]payload[\\/]frida-inject$'){return [pscustomobject]@{Path=$LiteralPath;Algorithm=$Algorithm;Hash='e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea'}}
    & $script:OwornateRealFileHash -LiteralPath $LiteralPath -Algorithm $Algorithm
}
# The fixture proves token handling without spending wall time on the production retry cadence.
function Start-Sleep { param([int]$Milliseconds=0,[int]$Seconds=0) }
'@
    $needle='$ErrorActionPreference=''Stop'''
    $testText=[IO.File]::ReadAllText($releaseSource,[Text.Encoding]::UTF8)
    Assert ($testText.Contains($needle)) 'release-manage setup anchor missing'
    $testText=$testText.Replace($needle,$needle+[Environment]::NewLine+$shim)
    [IO.File]::WriteAllText($testScript,$testText,[Text.UTF8Encoding]::new($true))
    if(Test-Path -LiteralPath $legacy){
        $parse='$t=$null;$e=$null;[System.Management.Automation.Language.Parser]::ParseFile("'+$testScript+'",[ref]$t,[ref]$e)|Out-Null;if($e.Count){$e|ForEach-Object{Write-Output $_.Message};exit 1}'
        & $legacy -NoProfile -Command $parse | Out-Null
        Assert ($LASTEXITCODE -eq 0) 'packaged release-manage does not parse under Windows PowerShell 5.1'
    }
    Init-Bundle

    $shared=Join-Path $temp 'device-shared'
    Init-Device $shared $true
    $hookBefore=[IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchhide/hook.js'))
    $themeBefore=[IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchface-theme/theme.jar'))
    [void](Invoke-Action $shared 'success' 'Setup')
    $base=DevicePath $shared '/data/local/tmp/watchworkshop-runtime'
    Assert (Test-Path -LiteralPath (Join-Path $base 'manager-ready')) 'shared setup missing manager-ready'
    Assert (Test-Path -LiteralPath (Join-Path $base 'frida-inject')) 'shared setup missing frida'
    Assert ([IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchhide/hook.js')) -eq $hookBefore) 'shared setup modified hook'
    Assert ([IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchface-theme/theme.jar')) -eq $themeBefore) 'shared setup modified theme'
    $pushLog=Join-Path $shared 'state\push.log'
    if(Test-Path -LiteralPath $pushLog){Assert (-not ([IO.File]::ReadAllText($pushLog) -match 'watchhide|watchface-theme')) 'shared setup pushed shared files'}
    $state=Get-Content -LiteralPath (Join-Path $base 'install-state.json') -Raw|ConvertFrom-Json
    Assert ($state.persistPort -eq '4301' -and $state.servicePort -eq '4301') 'shared setup snapshot ports mismatch'
    Assert ($state.services -eq 'com.example/.Keep' -and $state.accessibilityEnabled -eq '1' -and !$state.whitelisted) 'shared setup snapshot settings mismatch'
    $check=Get-Content -LiteralPath (DevicePath $shared '/data/user/0/dev.codex.watchworkshop/files/setup-check.json') -Raw|ConvertFrom-Json
    Assert ($check.ok -eq $true -and $check.token -match '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') 'shared setup nonce result mismatch'
    $props=Read-Dict (Join-Path $shared 'state\props.txt')
    Assert ($props['persist.adb.tcp.port'] -eq '5555' -and $props['service.adb.tcp.port'] -eq '5555') 'shared setup did not prepare local ADB port'
    Assert (-not (Test-Path -LiteralPath (Join-Path $base 'modules\monet'))) 'shared setup installed a feature'

    $hello=DevicePath $shared '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-theme/hello.json'
    Write-Text $hello '{"helperVersion":6,"protocol":1,"pid":4242}'
    $sharedBoot=Invoke-Action $shared 'success' 'VerifyBoot'
    Assert ($sharedBoot -match '共享环境') 'shared VerifyBoot did not state its scope'

    $beforeOpen=[IO.File]::ReadAllText((Join-Path $base 'install-state.json'))
    $pushBefore=if(Test-Path -LiteralPath $pushLog){[IO.File]::ReadAllText($pushLog)}else{''}
    [void](Invoke-Action $shared 'success' 'OpenManager')
    Assert ([IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchhide/hook.js')) -eq $hookBefore) 'compat02 modified hook'
    Assert ([IO.File]::ReadAllText((Join-Path $base 'install-state.json')) -eq $beforeOpen) 'compat02 modified setup state'
    $pushAfter=if(Test-Path -LiteralPath $pushLog){[IO.File]::ReadAllText($pushLog)}else{''}
    Assert ($pushAfter -eq $pushBefore) 'compat02 pushed files'

    $markers=Join-Path $base 'modules'
    foreach($name in @('monet','widgets','capture')){Write-Text (Join-Path $markers $name) 'installed'}
    [void](Invoke-Action $shared 'success' 'RemoveManager' $true)
    Assert (Test-Path -LiteralPath (Join-Path $base 'manager-ready')) 'marker guard removed manager-ready'
    Assert (Test-Path -LiteralPath (Join-Path $base 'frida-inject')) 'marker guard removed frida'
    foreach($name in @('monet','widgets','capture')){Remove-Item -LiteralPath (Join-Path $markers $name) -Force}

    [void](Invoke-Action $shared 'success' 'RemoveManager')
    Assert (!(Test-Path -LiteralPath (Join-Path $base 'manager-ready'))) 'RemoveManager left manager-ready'
    Assert (!(Test-Path -LiteralPath (Join-Path $base 'frida-inject'))) 'RemoveManager left frida'
    Assert (!(Test-Path -LiteralPath (Join-Path $base 'install-state.json'))) 'RemoveManager left snapshot'
    Assert ([IO.File]::ReadAllText((DevicePath $shared '/data/local/tmp/watchhide/hook.js')) -eq $hookBefore) 'RemoveManager modified shared hook'
    $props=Read-Dict (Join-Path $shared 'state\props.txt')
    Assert ($props['persist.adb.tcp.port'] -eq '4301' -and $props['service.adb.tcp.port'] -eq '4301') 'RemoveManager port restore failed'
    $settings=Read-Dict (Join-Path $shared 'state\settings.txt')
    Assert ($settings['enabled_accessibility_services'] -eq 'com.example/.Keep') 'RemoveManager accessibility restore failed'
    $doze=[IO.File]::ReadAllText((Join-Path $shared 'state\doze.txt'))
    Assert ($doze.Trim() -eq 'user,com.example.keep') ('RemoveManager doze restore failed: '+$doze)

    $fresh=Join-Path $temp 'device-fresh'
    Init-Device $fresh $false
    Write-Dict (Join-Path $fresh 'state\settings.txt') @{'enabled_accessibility_services'='';'accessibility_enabled'='null'}
    Remove-Item -LiteralPath (Join-Path $fresh 'state\doze.txt') -Force
    [void](Invoke-Action $fresh 'success' 'Setup')
    $freshBase=DevicePath $fresh '/data/local/tmp/watchworkshop-runtime'
    Assert (Test-Path -LiteralPath (Join-Path $freshBase 'manager-ready')) 'fresh setup missing manager-ready'
    $freshProps=Read-Dict (Join-Path $fresh 'state\props.txt')
    Assert ($freshProps['persist.adb.tcp.port'] -eq '5555' -and $freshProps['service.adb.tcp.port'] -eq '5555') 'fresh setup did not prepare local ADB port'
    $freshCheck=Get-Content -LiteralPath (DevicePath $fresh '/data/user/0/dev.codex.watchworkshop/files/setup-check.json') -Raw|ConvertFrom-Json
    Assert ($freshCheck.ok -eq $true) 'fresh setup nonce result mismatch'
    Assert (-not (Test-Path -LiteralPath (Join-Path $freshBase 'modules\monet'))) 'fresh setup installed a feature'

    $bootId='11111111-2222-3333-4444-555555555555'
    Write-Text (DevicePath $fresh '/proc/sys/kernel/random/boot_id') $bootId
    Write-Text (DevicePath $fresh '/data/user/0/dev.codex.watchworkshop/shared_prefs/runtime.xml') ('<map><string name="bootId">'+$bootId+'</string><boolean name="bootReady" value="true" /></map>')
    Write-Text (Join-Path $freshBase 'runtime-version') '2'
    New-Item -ItemType Directory -Force -Path (Join-Path $freshBase 'modules')|Out-Null
    Write-Text (Join-Path $freshBase 'modules\widgets') 'installed'
    Write-Text (Join-Path $freshBase 'modules\capture') 'installed'
    Write-Text (Join-Path $freshBase 'modules\capture.disabled') 'disabled'
    $marker='{"version":2,"pid":4242,"error":"","monet":false,"widgets":true,"capture":false}'
    Write-Text (Join-Path $freshBase 'marker-copy.json') $marker
    Write-Text (DevicePath $fresh '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json') $marker
    $ownBoot=Invoke-Action $fresh 'success' 'VerifyBoot'
    Assert ($ownBoot -match '本包运行时') 'VerifyBoot did not verify the own v2 runtime'
    Write-Text (Join-Path $freshBase 'runtime-version') '3'
    $ownBoot=Invoke-Action $fresh 'success' 'VerifyBoot'
    Assert ($ownBoot -match '本包运行时') 'VerifyBoot did not verify the own v3 runtime'

    Write-Text (Join-Path $freshBase 'marker-copy.json') '{"version":2,"pid":4242,"error":"","monet":false,"widgets":false,"capture":false}'
    Write-Text (DevicePath $fresh '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json') '{"version":2,"pid":4242,"error":"","monet":false,"widgets":false,"capture":false}'
    [void](Invoke-Action $fresh 'success' 'VerifyBoot' $true)

    $scenarioCount=13
    foreach($name in @('widgets','capture','capture.disabled')){
        $path=Join-Path (Join-Path $freshBase 'modules') $name
        if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path -Force}
    }
    $extensionId='dev.example.launcher-keep-awake'
    $extensionDir=Join-Path (Join-Path $freshBase 'modules') $extensionId
    New-Item -ItemType Directory -Force -Path $extensionDir|Out-Null
    Write-Text (Join-Path $extensionDir 'installed') 'installed'
    Write-Text (Join-Path $freshBase 'runtime-version') '4'
    $extensions=@{};$extensions[$extensionId]=$true
    Write-RuntimeMarker $fresh $freshBase $extensions
    $extensionBoot=Invoke-Action $fresh 'success' 'VerifyBoot'
    Assert ($extensionBoot -match '本包运行时') 'VerifyBoot rejected a v4 runtime with only an enabled third-party extension'
    $scenarioCount++

    Write-Text (Join-Path $extensionDir 'disabled') 'disabled'
    $extensions[$extensionId]=$false
    Write-RuntimeMarker $fresh $freshBase $extensions
    $disabledOnly=Invoke-Action $fresh 'success' 'VerifyBoot' $true
    Assert ($disabledOnly -match '没有已启用功能') ('VerifyBoot did not reject a v4 runtime whose only extension was disabled: '+$disabledOnly)
    $scenarioCount++

    Remove-Item -LiteralPath (Join-Path $extensionDir 'disabled') -Force
    $missingExtensions=@{}
    Write-RuntimeMarker $fresh $freshBase $missingExtensions
    $missingMarker=Get-Content -LiteralPath (Join-Path $freshBase 'marker-copy.json') -Raw|ConvertFrom-Json
    Assert (-not ($missingMarker.extensions.PSObject.Properties.Name -contains $extensionId)) 'fixture unexpectedly wrote the extension mark'
    $missingMark=Invoke-Action $fresh 'success' 'VerifyBoot' $true
    Assert ($missingMark -match '插件运行标记不匹配') ('VerifyBoot did not reject an enabled extension with no extension mark: '+$missingMark)
    $scenarioCount++

    $extensions[$extensionId]=$false
    Write-RuntimeMarker $fresh $freshBase $extensions
    $falseMark=Invoke-Action $fresh 'success' 'VerifyBoot' $true
    Assert ($falseMark -match '插件运行标记不匹配') ('VerifyBoot did not reject an enabled extension marked false: '+$falseMark)
    $scenarioCount++

    Remove-TestTree $extensionDir
    $blockedId='dev.example.keep.disabled'
    $blockedDir=Join-Path (Join-Path $freshBase 'modules') $blockedId
    New-Item -ItemType Directory -Force -Path $blockedDir|Out-Null
    Write-Text (Join-Path $blockedDir 'installed') 'installed'
    Write-Text (Join-Path $blockedDir 'disabled') 'disabled'
    $blocked=Invoke-Action $fresh 'success' 'RemoveManager' $true
    Assert ($blocked -match [regex]::Escape($blockedId)) 'RemoveManager did not report the installed third-party directory'
    Assert (Test-Path -LiteralPath (Join-Path $freshBase 'manager-ready')) 'RemoveManager removed the manager despite an installed third-party directory'
    $scenarioCount++

    $bad=Join-Path $temp 'device-ok-false'
    Init-Device $bad $false
    Write-Dict (Join-Path $bad 'state\settings.txt') @{'enabled_accessibility_services'='';'accessibility_enabled'='null'}
    [void](Invoke-Action $bad 'error' 'Setup' $true)
    Assert (!(Test-Path -LiteralPath (Join-Path (DevicePath $bad '/data/local/tmp/watchworkshop-runtime') 'manager-ready'))) 'ok=false left manager-ready'

    $stale=Join-Path $temp 'device-stale-nonce'
    Init-Device $stale $false
    Write-Dict (Join-Path $stale 'state\settings.txt') @{'enabled_accessibility_services'='';'accessibility_enabled'='null'}
    Write-Text (DevicePath $stale '/data/user/0/dev.codex.watchworkshop/files/setup-check.json') '{"token":"00000000-0000-0000-0000-000000000000","ok":true,"error":""}'
    [void](Invoke-Action $stale 'mismatch' 'Setup' $true)
    Assert (!(Test-Path -LiteralPath (Join-Path (DevicePath $stale '/data/local/tmp/watchworkshop-runtime') 'manager-ready'))) 'mismatched nonce left manager-ready'

    Write-Text (DevicePath $stale '/data/local/tmp/watchworkshop-runtime/modules/widgets') 'installed'
    $status=Invoke-Action $stale 'success' 'Status'
    Assert ($status -match 'feature=widgets') 'Show-Status did not preserve the shell feature loop variable'

    $lan=Join-Path $temp 'device-lan-port'
    Init-Device $lan $false
    Write-Dict (Join-Path $lan 'state\settings.txt') @{'enabled_accessibility_services'='';'accessibility_enabled'='null'}
    [void](Invoke-Action $lan 'success' 'Setup' $false '192.0.2.10:4301')
    Assert (Test-Path -LiteralPath (DevicePath $lan '/data/local/tmp/watchworkshop-runtime/manager-ready')) 'LAN port transition failed Setup'

    Write-Output ('desktop_setup_test: PASS ('+$scenarioCount+' scenarios; PS5/PS7 parse and package whitelist)')
} finally {
    $root=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if(Test-Path -LiteralPath $temp){
        $resolved=[IO.Path]::GetFullPath($temp)
        if($resolved.StartsWith($root,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path -Leaf $resolved) -like 'owornate-desktop-setup-*'){Remove-Item -LiteralPath $resolved -Recurse -Force}
    }
}

