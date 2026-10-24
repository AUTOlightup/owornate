# 本地插件快速上手

请把下面这些命令放在 `1.0.1-rc22 / code31` 的完整管理器源码包根目录运行。源码包包含 `new-plugin.ps1`、打包/校验工具、shell-v1 与 launcher-v1 示例测试、插件专用更新清单工具。Windows 安装包用于管理器部署、基础环境准备和插件导入，开发工具请使用完整源码包。Windows 包另附 `plugins/examples/launcher-keep-awake/launcher-keep-awake-1.0.1.owo`，可手动导入体验第三方示例；Setup 不会自动部署它。协议细节见同级的 [shell-v1 插件开发](PLUGIN-DEVELOPMENT.md)、[Launcher 插件](LAUNCHER-PLUGINS.md) 和 [插件升级与更新协议](UPDATES.md)。

本页从现有的 `plugins/examples/screen-timeout` 复制一个真实可运行的 shell-v1 插件，再修改成自己的实现。模板会读写 Android 的 `system.screen_off_timeout`：启用时写入清单选择的毫秒值，停用时在系统值仍由本插件持有时恢复首次启用前的值。换成别的功能时，需要同时修改 `plugin.sh` 与 `plugin.json`，并保证 `status`、启用、停用和卸载的现有生命周期仍然成立。

以下命令可在分发源码包中用 PowerShell 7 或 Windows PowerShell 5.1 运行。make-release.ps1 为导出的源码包和 Windows 包中所有 .ps1 加上 UTF-8 BOM；本机开发树中的无 BOM 脚本优先使用 PowerShell 7。先赋值本机实际的源码与工具路径；这些变量只在当前 PowerShell 会话有效。校验器只要求 JDK 11+ 与 `org.json` JAR，不需要 Android SDK；完整测试另外要求 Git Bash。

```powershell
Set-Location -LiteralPath 'C:\path\to\owornate-source'
$JdkHome = 'C:\path\to\jdk'
$JsonJar = 'C:\path\to\json-20240303.jar'
$Bash = 'C:\path\to\Git\bin\bash.exe'
```

## 创建自己的插件

目标目录必须尚不存在。脚本只复制示例的两个协议文件，然后写入新的 ID、名称、版本 `1.0.0` / `1` 和示例说明；不会覆盖已有目录。若要使用 GitHub 在线更新，请替换下面的仓库名，并在第一次打包前就把 `updateUrl` 写入清单。

```powershell
$GitHubRepository = 'YOUR_ACCOUNT/YOUR_REPOSITORY'
$UpdateUrl = 'https://api.github.com/repos/' + $GitHubRepository + '/releases?per_page=20'
$PluginDirectory = Join-Path $PWD 'work\my-screen-timeout'
./plugins/new-plugin.ps1 `
    -PluginDirectory $PluginDirectory `
    -Id 'dev.example.my-screen-timeout' `
    -Name '我的熄屏测试' `
    -UpdateUrl $UpdateUrl
```

如果不打算发布更新，可以省略 `-UpdateUrl`。脚本接受的 ID 规则与生产清单一致：1–80 字符、小写反向域名风格、不能占用内置模块 ID。`UpdateUrl` 必须是 HTTPS、最多 2048 字符，且不含用户信息或 `#fragment`。

直接编辑 `$PluginDirectory` 中的 `plugin.sh`，后续命令仍在源码根目录运行。示例已经实现 `status`、`enable`、`disable`、`configure` 和 `uninstall`，并把恢复信息保存在 `OW_PLUGIN_DATA`。若功能或设置发生变化，同时修改 `plugin.json` 的说明与 `settings`。

## 创建 launcher-v1 插件

new-plugin.ps1 默认仍创建 shell-v1。显式传入 -Runtime launcher-v1 会复制新的“桌面调试常亮”模板。生成目录只含 plugin.json 与 hook.js；settings 必须为空，第三方插件不能声明 nativeSettings。safeID 最多 80 字符，并拒绝 monet、widgets、capture 及三个官方完整 ID，以及内部状态名 monet.disabled、widgets.disabled、capture.disabled。

~~~powershell
./plugins/new-plugin.ps1 -PluginDirectory 'work/my-launcher-plugin' -Id 'dev.example.my-launcher-plugin' -Name '我的桌面 Hook' -Runtime 'launcher-v1'
~~~

编辑生成的 hook.js 时，文件内容就是 factory 函数体，不需要另写 Java.perform，也不要重复写 ID。factory 已在 Java 环境中运行，可直接使用 Java.use；参数 plugin 提供只读 id、Launcher context 和 report(error)。初始化同步异常由公共核心按 ID 显示，异步异常由作者调用 plugin.report(error)。完整 API、冲突处理、示例行为及部署边界见 [Launcher 插件](LAUNCHER-PLUGINS.md)。

使用相同打包工具：

~~~powershell
./plugins/pack-plugin.ps1 -PluginDirectory 'work/my-launcher-plugin' -OutputFile 'work/my-launcher-plugin-1.0.0.owo'
./plugins/test-plugin.ps1 -PackageFile 'work/my-launcher-plugin-1.0.0.owo' -JdkHome $JdkHome -JsonJar $JsonJar -BuildRoot 'work/launcher-check-v1'
~~~

第三方 launcher-v1 包只接受 plugin.json、hook.js 两个文件；额外文件、目录和 theme.jar 均拒绝。只有官方 dev.owornate.monet 可以带 theme.jar。导入只保存包，部署到自有 version 4 运行环境并通过原 Launcher 重载后才生效；shared WatchHide 环境不能同时部署新的第三方 Hook。code30/31 已在 A.92 实机完成第三方导入升级、启停、卸载重装、示例效果、初始化错误恢复及 v4 重启恢复；共享环境下仅移除导入包也已验证，原共享运行组件保持不变。完整范围见发布包中的 `docs/VALIDATION.md`。

本轮的窄例子与工具测试：

~~~powershell
node ./tests/launcher_keep_awake_test.js
./tests/run-launcher-plugin-tools-tests.ps1 -BuildRoot 'work/launcher-tool-test'
~~~

这些测试验证 Hook 调用顺序、包名过滤、128 标志、错误上报、模板创建、ZIP 文件集合和 ID 边界；不会运行真实 Android Java 或访问手表。

## 打包并校验

`pack-plugin.ps1` 的参数和包格式保持不变。输出文件也必须尚不存在；先生成包，再调用 `test-plugin.ps1`。后者每次编译并运行生产 `PluginManifest` / `PluginRepository` 解析器检查实际 `.owo`，不访问 Android SDK、ADB 或手表，也不执行插件脚本。

```powershell
$Release1 = Join-Path $PWD 'work\release-v1'
New-Item -ItemType Directory -Path $Release1 -Force | Out-Null
$Package1 = Join-Path $Release1 'my-screen-timeout-1.0.0.owo'
./plugins/pack-plugin.ps1 -PluginDirectory $PluginDirectory -OutputFile $Package1
./plugins/test-plugin.ps1 `
    -PackageFile $Package1 `
    -JdkHome $JdkHome `
    -JsonJar $JsonJar `
    -BuildRoot (Join-Path $PWD 'work\plugin-test-v1')
```

校验器覆盖 ZIP 结构、清单、脚本编码/换行和文件大小等生产导入约束。它不执行 `plugin.sh`，所以通过校验只能证明包可被生产代码解析，不能证明手表上的熄屏效果。实际效果仍要在手表导入并信任后启用确认；系统或 OEM 电源策略也可能覆盖该设置。

省略 `-BuildRoot` 时，校验器在当前目录的 `work/plugin-check-<随机标识>/` 下保存编译结果；临时解包数据完成后删除。也可以像上例一样指定自己的输出目录。

## 修改实现并从 1 升级到 2

保留原来的 `id`，把 `plugin.json` 的 `versionCode` 从 `1` 改成 `2`，并把 `versionName` 改成例如 `1.0.1`。已有设置键和用户选择的值要继续有效；不要删除正在使用的设置键。管理器只接受同一 ID、相同 `runtime` 且版本号严格增大的升级，升级前还会要求插件已停用。

```powershell
$Release2 = Join-Path $PWD 'work\release-v2'
New-Item -ItemType Directory -Path $Release2 -Force | Out-Null
$Package2 = Join-Path $Release2 'my-screen-timeout-1.0.1.owo'
./plugins/pack-plugin.ps1 -PluginDirectory $PluginDirectory -OutputFile $Package2
./plugins/test-plugin.ps1 `
    -PackageFile $Package2 `
    -JdkHome $JdkHome `
    -JsonJar $JsonJar `
    -BuildRoot (Join-Path $PWD 'work\plugin-test-v2')
```

在手表上先停用旧版，再导入 v2 并确认信任升级。正常停用时，插件应已恢复它拥有的原设置；同一 ID 升级会保留管理器保存的配置，以及 `data/<id>` 中作者未删除的数据。若改掉了状态文件名或恢复协议，必须自己兼容旧数据，否则停用可能无法恢复原来拥有的系统值。

## 生成插件专用更新清单

每次 Release 的准备目录只放该标签要上传的 `.owo` 文件，避免同一 ID 的多个版本同时进入一份清单。`-PluginsOnly` 模式不再要求 APK 或管理器版本参数。

```powershell
$ReleaseTag = 'v1.0.1'
$ReleaseDirectory = Join-Path $PWD 'work\release-v2'
$FeedFile = Join-Path $ReleaseDirectory 'owornate-updates.json'
./plugins/make-update-catalog.ps1 `
    -PluginsOnly `
    -Repository $GitHubRepository `
    -Tag $ReleaseTag `
    -PluginPackageDirectory $ReleaseDirectory `
    -OutputFile $FeedFile
```

清单会为每个包写入基于 `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>` 的地址和实际 SHA256。这里只生成文件，不会创建标签、创建 Release 或上传。请到 GitHub Release 页面手工上传同一标签下的 `my-screen-timeout-1.0.1.owo` 与 `owornate-updates.json`，文件名不要改。

插件清单中的 `updateUrl` 可以指向 GitHub Releases API：

```text
https://api.github.com/repos/<owner>/<repo>/releases?per_page=20
```

管理器读取最近 20 条 Release，选择首个包含 `owornate-updates.json` 的非草稿版本。仓库须能匿名访问；管理器不使用 GitHub 令牌。检查更新和下载都由用户在管理页手动触发，下载后仍进入信任升级确认。HTTPS 与 SHA256 只校验传输，不等于作者签名。

## 一次跑完整本地流程

下面的开发测试会在新的 `BuildRoot` 中独立生成 `dev.example.timeout` 的 v1/v2 包和 v2 更新清单，验证打包、生产解析、真实 shell 动作、配置保留以及本地更新检查/下载。它只做本机模拟，不连接设备、不执行 Android 安装，也不上传 Release；`BuildRoot` 必须尚不存在。

```powershell
$DevelopmentTestRoot = Join-Path $PWD 'work\plugin-development-test'
./tests/run-plugin-development-tests.ps1 `
    -JdkHome $JdkHome `
    -JsonJar $JsonJar `
    -Bash $Bash `
    -BuildRoot $DevelopmentTestRoot
```

完整模拟和包校验都不替代手表上的真实导入、启用、停用、同 ID 升级及 Android 设置效果验收。
