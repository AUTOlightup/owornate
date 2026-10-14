param([string]$JdkHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(-not $JdkHome){$javac=Get-Command javac -ErrorAction SilentlyContinue;if($javac){$JdkHome=Split-Path (Split-Path $javac.Source -Parent) -Parent}}
if(-not $JdkHome -or !(Test-Path -LiteralPath "$JdkHome/bin/javac.exe")){throw '请通过-JdkHome或JAVA_HOME指定JDK。'}
$projectRoot=Split-Path -Parent $PSScriptRoot

# ---------------------------------------------------------------------------
# 1) 调色板离线测试：app 侧预览类，纯 Java、不连设备。
#    构建目录 work/watchworkshop-monet/palette-test-<时间戳>
# ---------------------------------------------------------------------------
$paletteSource=Join-Path $projectRoot 'src/dev/codex/watchworkshop/palette'
$testBuild=Join-Path (Get-Location) ('work/watchworkshop-monet/palette-test-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $testBuild -Force | Out-Null
$testSources=@(rg --files $paletteSource -g '*.java')+@(Join-Path $PSScriptRoot 'ThemePalettePreviewTest.java')
& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -d $testBuild @testSources
if($LASTEXITCODE -ne 0){throw 'test compile failed'}
& "$JdkHome/bin/java.exe" -cp $testBuild ThemePalettePreviewTest
if($LASTEXITCODE -ne 0){throw 'palette tests failed'}

# ---------------------------------------------------------------------------
# 2) RsaKeyStore 并发与稳定性测试：需要 src 里的 RsaKeyStore 一起编译。
#    16 个独立实例并发使用同一临时目录，断言只生成一份密钥对、private 与 public 匹配、
#    风暴之后新实例读取结果不变（不重新生成）。
#    构建目录 work/rsakeystore-build-<时间戳>，测试临时目录 work/rsakeystore-test-<时间戳>
# ---------------------------------------------------------------------------
$rsaBuild=Join-Path (Get-Location) ('work/rsakeystore-build-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $rsaBuild -Force | Out-Null
$rsaSources=@(
    (Join-Path $projectRoot 'src/dev/codex/watchworkshop/RsaKeyStore.java'),
    (Join-Path $PSScriptRoot 'RsaKeyStoreTest.java')
)
& "$JdkHome/bin/javac.exe" -encoding UTF-8 --release 8 -Xlint:-options -d $rsaBuild @rsaSources
if($LASTEXITCODE -ne 0){throw 'rsa keystore test compile failed'}
& "$JdkHome/bin/java.exe" -cp $rsaBuild dev.codex.watchworkshop.RsaKeyStoreTest
if($LASTEXITCODE -ne 0){throw 'rsa keystore tests failed'}
