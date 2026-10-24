# 插件升级与管理器更新

code27 / 1.0.1-rc18 接入同 ID 本地升级，以及 GitHub Releases 版本检查和下载。已同签装表：脚本插件升级、配置保留、失败回滚与管理器/插件检查页面实测通过；现有 GitHub 源尚无清单，真实公网下载和系统安装器完整覆盖仍待后续。完整实测范围见发布包 VALIDATION.md。检查和下载均由用户触发，没有定时更新或静默安装。

## 本地插件升级

继续使用 `.owo` 导入入口。若 ID 已安装，预览会显示当前版本与候选版本，并要求再次确认信任。只有相同 runtime、严格更大的 versionCode、兼容现有设置值的包才能升级；相同版本和降级不覆盖原包。versionName 仅用于展示。

shell-v1 插件必须先停用。管理器在执行升级前重新读取状态；启用或状态不明时保留旧包。替换后再次调用新脚本 status，确认仍停用后提交；校验失败恢复原包。包目录备份和提交记录允许下次仓库访问恢复中断的替换。配置和 data/<id> 保留；旧默认值也保持，新设置使用新包声明的默认值。删除已有设置或使已有值失效的版本会被拒绝，需要作者设计兼容版本。

launcher-v1 升级只替换导入包，不自动重载 Launcher 或替换共享 Hook。自有环境中，用户之后点击“更新组件”才部署新载荷；共享环境仍保留原兼容路径。

code28 的捕获包可声明原生画质选项，详见 [Launcher 插件](LAUNCHER-PLUGINS.md)。这份声明导入后即由两个设置入口使用，原生画质偏好保持唯一存储；升级前保留原有效值（包括未显式保存的旧默认值）。捕获 1.1.0 需要先升级管理器到 code28，旧管理器会拒绝新增字段。原生设置的变化不会启动或中断录制。

包回滚不是任意 root 操作的回滚。插件必须是用户信任的代码，status 应只读；不提供数据或进程沙箱。

code29 的莫奈 1.1.0 同样声明 `nativeSettings`，需先升级管理器到 code29。导入只更新配色方式和圆角的选项定义，不应用默认值或修改实际主题；设置页面读取 Launcher 实际状态，用户选择后通过原主题通道确认应用。旧 code28 仍可使用捕获 1.1.0，但不接受莫奈的新声明。

## 在线检查与下载

- 管理器：设置 → 管理器更新 → 检查更新。
- 插件：插件管理页 → 检查插件更新。
- 发现更高版本后展示版本与说明；下载校验通过后，插件进入同一升级确认页，管理器进入系统安装确认流程。

默认源为 `https://api.github.com/repos/AUTOlightup/owornate/releases?per_page=20`。读取最近20条中的非草稿 Release，包含本项目使用的 prerelease，按 API 返回顺序选择首个含 `owornate-updates.json` 附件的版本。官方源的清单应包含管理器和官方插件条目；第三方源只需包含该源所提供的插件，不要求管理器 APK。没有清单、没有对应 ID、HTTP/TLS 错误分别显示错误，不冒充“已是最新版”。不使用 GitHub 令牌。

第三方插件可在 plugin.json 增加可选 `updateUrl`，指向自己的 HTTPS 清单或 GitHub releases API。旧清单不必修改，默认使用上述仓库。URL 不接受用户信息、fragment 或明文 HTTP；预览页会展示包声明的更新源。更新源与包作者都需要用户信任。

第三方清单可用 `plugins/make-update-catalog.ps1 -PluginsOnly -Repository '作者/仓库' -Tag 'v1.1.0' -PluginPackageDirectory '<仅含各插件最新版.owo的目录>' -OutputFile '<输出目录>/owornate-updates.json'` 生成。此模式必须明确仓库和 tag，无需管理器参数；空目录或重复 ID 会报错。生成后把清单和引用的 `.owo` 放在同一个 Release，文件名保持一致。多插件共用源时，每次清单应保留该源所有插件的最新条目，否则旧用户会看到缺少对应 ID。完整开发流程见 [快速开始](PLUGIN-QUICKSTART.md)。

清单格式：

```json
{
  "schemaVersion": 1,
  "releases": [
    {
      "id": "dev.codex.watchworkshop",
      "versionCode": 27,
      "versionName": "1.0.1-rc18",
      "url": "https://github.com/AUTOlightup/owornate/releases/download/v1.0.1-rc18/OWOrnate-v1.0.1-rc18.apk",
      "sha256": "填写对应文件的64位SHA256十六进制摘要",
      "notes": "版本说明"
    }
  ]
}
```

插件条目采用相同字段，id 为插件清单 ID。每个 ID 只出现一次。下载文件的 ID、版本与清单必须一致；HTTPS 重定向仍须为 HTTPS，下载大小和 SHA256 必须通过。插件上限2MiB，管理器上限32MiB；SHA256用于核对清单与下载文件，不代表第三方插件有官方签名。

管理器 APK 还必须为当前包名、更高 versionCode、与已安装应用相同的签名集合。系统可能要求开启“允许此来源安装应用”；管理器仅打开该设置，不自行授予权限。安装器只得到单个 APK 的临时只读 URI 权限，不能读取插件数据或其他私有文件。取消安装保留当前版本和数据。

## 生成发布附件

`make-release.ps1` 会在发布目录顶层生成三个 `.owo` 和 `owornate-updates.json`，与 APK 一起列入 SHA256SUMS。运行打包不会创建 GitHub Release 或上传文件。

单独生成清单：

```powershell
./plugins/make-update-catalog.ps1 -ApkFile '<本次签名APK>' -PluginPackageDirectory '<本次.owo目录>' -OutputFile '<新文件路径>/owornate-updates.json' -ManagerVersionCode 27 -ManagerVersionName '1.0.1-rc18'
```

脚本按传入的管理器版本、实际插件清单和文件摘要生成附件地址。发布者须使用本次构建产物，并把清单及其引用的原文件上传到同一 tag；不能只上传清单或改名后不重生成清单。第三方仓库可传 `-Repository owner/repo -Tag tag`。正式发布仍按用户明确指令执行。

当前旧公开 Release 没有此更新清单；完整线上可用性需要后续发布带清单的版本。测试记录应分别说明本机下载/回滚模拟、真实网络检查、设备升级及系统安装器实际覆盖结果。

实现参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#list-releases)、[Android 安装 Intent](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE)。

## 本机验证入口

在源码工程目录使用 PowerShell 7；指定 JDK、org.json JAR 与新的输出目录，不连接设备：

```powershell
./tests/run-plugin-repository-tests.ps1 -JdkHome '<JDK目录>' -JsonJar '<org.json.jar>' -BuildRoot '<新的仓库测试目录>'
./tests/run-plugin-upgrade-tests.ps1 -JdkHome '<JDK目录>' -JsonJar '<org.json.jar>' -BuildRoot '<新的升级测试目录>'
./tests/run-update-client-tests.ps1 -JdkHome '<JDK目录>' -JsonJar '<org.json.jar>' -BuildRoot '<新的网络测试目录>'
```

`UpdatePolicyTest.java` 与 `src/dev/codex/watchworkshop/UpdatePolicy.java` 单独编译后运行 `dev.codex.watchworkshop.UpdatePolicyTest`，无 Android 依赖。`UpdateClientLive` 是真实网络检查入口，可传 source、id、currentVersion；单元测试使用替身 transport，不会连接 GitHub。系统安装器、Android TLS 与圆屏 UI 的结果须另记实机证据。
