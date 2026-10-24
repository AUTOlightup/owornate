#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$JdkHome = [IO.Path]::GetFullPath($JdkHome)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or
        !(Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK missing javac or java: $JdkHome"
}

$classes = Join-Path $BuildRoot 'classes'
$stubRoot = Join-Path $BuildRoot 'stubs'
$stub = Join-Path $stubRoot 'dev/codex/watchworkshop/RsaKeyStore.java'
$actual = Join-Path $project 'src/dev/codex/watchworkshop/AdbClient.java'
$test = Join-Path $PSScriptRoot 'AdbClientDecodeTest.java'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $stub) -Force | Out-Null

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($stub,
    "package dev.codex.watchworkshop;`nfinal class RsaKeyStore { static final String COMMENT = `"watchworkshop@app`"; }`n",
    $utf8)
$sources = @($actual, $stub, $test)
& $javac -encoding UTF-8 --release 8 -Xlint:-options -d $classes @sources 2>&1 |
    Tee-Object -FilePath (Join-Path $BuildRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'AdbClientDecodeTest compile failed' }

& $java '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp $classes dev.codex.watchworkshop.AdbClientDecodeTest 2>&1 |
    Tee-Object -FilePath (Join-Path $BuildRoot 'test.log')
if ($LASTEXITCODE -ne 0) { throw 'AdbClientDecodeTest failed' }
