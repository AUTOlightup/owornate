#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PluginDirectory,
    [Parameter(Mandatory = $true)]
    [string]$Id,
    [Parameter(Mandatory = $true)]
    [string]$Name,
    [ValidateSet('shell-v1', 'launcher-v1')]
    [string]$Runtime = 'shell-v1',
    [string]$UpdateUrl
)

$ErrorActionPreference = 'Stop'

$target = [IO.Path]::GetFullPath($PluginDirectory)
if (Test-Path -LiteralPath $target) {
    throw "目标目录已存在: $target"
}

switch ($Runtime) {
    'shell-v1' {
        $template = Join-Path $PSScriptRoot 'examples/screen-timeout'
        $runtimeFiles = @('plugin.json', 'plugin.sh')
        $description = '熄屏时长示例：启用后设置系统熄屏时长，停用时恢复首次启用前的值。请修改 plugin.json 与 plugin.sh，实现你自己的功能；修改后仍须保证停用能恢复本插件拥有的系统改动。'
    }
    'launcher-v1' {
        $template = Join-Path $PSScriptRoot 'examples/launcher-keep-awake'
        $runtimeFiles = @('plugin.json', 'hook.js')
        $description = '桌面调试常亮示例：在 Launcher 返回或离开时设置或移除窗口常亮。请修改 plugin.json 与 hook.js；Hook 已在 Java.perform 上下文运行，异步错误使用 plugin.report(error)。'
    }
}
if (!(Test-Path -LiteralPath $template -PathType Container)) {
    throw "找不到 $Runtime 示例模板: $template"
}

$idPattern = '^[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9_-]*[a-z0-9])?)+$'
$reservedIds = @(
    'monet', 'widgets', 'capture',
    'dev.owornate.monet', 'dev.owornate.widgets', 'dev.owornate.capture'
)
if ($Id.Length -gt 80 -or ![regex]::IsMatch($Id, $idPattern) -or
        $reservedIds -contains $Id -or
        ($Runtime -eq 'launcher-v1' -and $Id -in @('monet.disabled','widgets.disabled','capture.disabled'))) {
    throw 'Id 必须是生产清单支持的 1-80 字符小写反向域名，且不能占用内置模块 ID'
}
if ([string]::IsNullOrWhiteSpace($Name) -or $Name.Length -gt 60) {
    throw 'Name 必须是非空白字符串，最多 60 个字符'
}

$hasUpdateUrl = $PSBoundParameters.ContainsKey('UpdateUrl') -and
    ![string]::IsNullOrEmpty($UpdateUrl)
if ($hasUpdateUrl) {
    $uri = $null
    if ($UpdateUrl.Length -gt 2048 -or $UpdateUrl -match '[\s\x00-\x1F\x7F]' -or
            $UpdateUrl.Contains('#') -or
            ![Uri]::TryCreate($UpdateUrl, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ne 'https' -or [string]::IsNullOrEmpty($uri.Host) -or
            ![string]::IsNullOrEmpty($uri.UserInfo) -or
            $uri.OriginalString -notmatch '^https://') {
        throw 'UpdateUrl 必须是不含用户信息和 fragment 的 HTTPS URL，最多 2048 字符'
    }
}

$created = $false
$createdFiles = New-Object Collections.Generic.List[string]
try {
    New-Item -ItemType Directory -Path $target | Out-Null
    $created = $true
    foreach ($fileName in $runtimeFiles) {
        Copy-Item -LiteralPath (Join-Path $template $fileName) -Destination $target
        $createdFiles.Add($fileName)
    }

    $manifestPath = Join-Path $target 'plugin.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.runtime = $Runtime
    $manifest.id = $Id
    $manifest.name = $Name
    $manifest.description = $description
    $manifest.versionName = '1.0.0'
    $manifest.versionCode = 1
    if ($Runtime -eq 'launcher-v1') {
        $manifest.settings = @()
        $manifest.PSObject.Properties.Remove('nativeSettings')
    }
    if ($hasUpdateUrl) {
        if ($manifest.PSObject.Properties['updateUrl']) {
            $manifest.updateUrl = $UpdateUrl
        }
        else {
            $manifest | Add-Member -NotePropertyName updateUrl -NotePropertyValue $UpdateUrl
        }
    }
    else {
        $manifest.PSObject.Properties.Remove('updateUrl')
    }

    $json = $manifest | ConvertTo-Json -Depth 10
    $json = $json.Replace("`r`n", "`n").Replace("`r", "`n") + "`n"
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($manifestPath, $json, $utf8NoBom)

    foreach ($fileName in $runtimeFiles) {
        $bytes = [IO.File]::ReadAllBytes((Join-Path $target $fileName))
        if ($bytes.Length -gt 2 -and $bytes[0] -eq 0xEF -and
                $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            throw "$fileName 不能带 UTF-8 BOM"
        }
        if ($bytes -contains 13) {
            throw "$fileName 必须使用 LF 换行"
        }
    }

    Write-Output $target
}
catch {
    foreach ($fileName in $createdFiles) {
        $file = Join-Path $target $fileName
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
        }
    }
    if ($created -and (Test-Path -LiteralPath $target -PathType Container) -and
            @(Get-ChildItem -LiteralPath $target -Force).Count -eq 0) {
        Remove-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
    }
    throw
}

