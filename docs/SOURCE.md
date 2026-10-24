# 源码与打包

**当前发布候选：1.0.1-rc23 / code32。本地构建已通过，尚待上传和回退实机验收。** code32 修复 A.92 上普通应用无法直接启动安装来源授权页的问题：仅在 `ActivityNotFoundException` 或 `SecurityException` 时回退标准应用信息页，仍要求用户手动开启“允许来自此来源的应用”并在返回后确认系统安装器。候选 APK 为 341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd`；原证书、非 debuggable 与许可资产检查通过，14 个 APK assets 与 code31 相同。详见 [Launcher 插件](LAUNCHER-PLUGINS.md) 与 [验证记录](VALIDATION.md)。

## 正式附件与源码范围

本次候选维持 9 个顶层正式附件：管理器 APK、Windows 包、管理器完整源码包、莫奈 helper 源码包、三个官方 `.owo`、`owornate-updates.json` 和 `SHA256SUMS.txt`。目录结构不变，文件名随 `v1.0.1-rc23` 重命名；三个官方包仍为 1.1.0 / 1.0.0 / 1.1.0。文件名与用途见 [GitHub 发布说明](GITHUB-RELEASE.md)。

`owornate-v1.0.1-rc23-source.zip` 包含管理器 `src/`、资源、测试、`standalone/`、`widget-host/`、`capture/`、`plugins/`、`runtime-helper/`、构建/安装/发布脚本、许可证和文档。`plugins/` 中有三个官方 launcher-v1 包源、shell-v1 熄屏示例、第三方 launcher-v1 常亮示例，以及 `new-plugin.ps1`、`pack-plugin.ps1`、`test-plugin.ps1`、`make-update-catalog.ps1` 和对应测试入口。插件创建、校验、打包和独立清单工具只在这个完整源码包中提供。

第三方常亮示例为 `plugins/examples/launcher-keep-awake`，ID 是 `dev.example.launcher-keep-awake`，有效版本为 1.0.1 / versionCode 2。源码已导出；最终 Windows 包的 `plugins/examples/launcher-keep-awake/` 会同时提供 `plugin.json`、`hook.js` 和可直接导入的 `launcher-keep-awake-1.0.1.owo`。该示例不占顶层 9 个正式附件，也不进入官方更新清单。1.0.0 示例保留为历史失败记录，不再推荐。

`watch2-monet-v6-source.zip` 只包含莫奈 helper 与运行时源码：`native-src/`、`vendor/`、`build.ps1`、`standalone/`、`widget-host/capture-controls.js`、`widget-host/capture-indicator.js`、许可证和协议。莫奈 1.1.0 只更新插件声明，Hook 与 helper v6 `theme.jar` 未改，因此源码包版本名保持 v6；它不含整个管理器，不能单独完成整机部署。

Windows 包和管理器源码包都带 `docs/`，包含本目录的发布文档，以及 `PLUGIN-QUICKSTART.md`、`UPDATES.md`、`PLUGIN-DEVELOPMENT.md`、`LAUNCHER-PLUGINS.md`、`MODULE-MANAGEMENT.md` 和 `截屏录屏-原生版.md`；管理器源码包另带 `莫奈协议-v1.md`。公共文档不依赖开发交接文件或本机证据路径。

## 运行时与源码边界

code32 候选 APK 继续提供管理器 UI、原生设置与采集服务、运行时协调脚本和已知共享 Hook 兼容载荷；相对 code31，运行载荷不变，只调整系统授权页的失败回退。莫奈、小组件、捕获的 launcher-v1 功能载荷仍由用户导入的 `.owo` 提供。源码树中的 `assets/monet/` 保留部分构建/兼容材料，不能据此推断 APK 含有自有莫奈 `hook.js` 或 `theme.jar`；APK 实际内容以最终发布归档为准。

launcher-v1 保留三个官方角色 `dev.owornate.monet`、`dev.owornate.widgets`、`dev.owornate.capture`，同时接受使用自有 safeID 的第三方普通 Hook。第三方包只含 `plugin.json` 与 `hook.js`，不开放原生 Activity、采集服务、任意设置页或通用配置 SDK。需要普通 root 命令和声明式配置时使用 shell-v1；协议、清单、动作和本地测试入口见 [本地插件开发](PLUGIN-DEVELOPMENT.md) 与 [Launcher 插件](LAUNCHER-PLUGINS.md)。

自有运行环境为 version 4，保留单个 supervisor 和一次注入。已知 version 3 迁移时保留已部署载荷及启停选择；旧 v2 的真实迁移仍未验收。共享 WatchHide 环境只保留导入包，不部署第三方 Hook，也不创建第二套 supervisor。

第三方 Hook 是用户明确信任的进程内代码，没有沙箱隔离。清单、ZIP 和文件集合校验不等于代码审计或作者签名。

保留根目录 `.gitattributes` 对 `assets/`、`widget-host/`、`standalone/` 和 `capture/` 的原始字节设置，避免 Windows Git 换行转换改变 Hook 或 shell 指纹。负一屏“统一编辑器 / 首页重排”与滑动修复仍是已放弃范围；发布清单不包含 `quickcenter/`、`quickcenter.jar` 或相关测试。

## 桌面 Setup 与兼容入口

```text
release-manage.ps1 -Action Setup
```

Setup 只准备管理器基础环境：校验发布清单、设备/固件/ABI/Launcher 与已知共享指纹，安装管理器，授权管理器公钥，部署 `frida-inject`，设置恢复与无障碍，保留端口/无障碍/doze 快照，恢复本机 ADB 通道，写入 `manager-ready`，再以一次性 GUID 验证应用内 root 回环认证。共享环境与 fresh 环境走同一收尾，不提前返回。

Setup 不导入或部署 `payload/plugins` 中的功能。首次使用仍需把所需 `.owo` 复制到手表 `Download`，在管理器“发现优化 → 本地插件”导入并确认信任，再完成安装或接入。详见 [INSTALL.md](INSTALL.md)。

认证依赖主线 MainActivity 的 Activity intent 契约：`--es owornate_setup_check <GUID>`，异步通过只读 root 会话检查后原子写入应用私有 `files/setup-check.json`，结构为 `{token, ok, error}`。`release-manage.ps1` 最多轮询约 15 秒，只接受令牌完全匹配且 `ok:true`。没有控制器端设备测试时，不得把脚本解析或电脑端 `adb root` 结果当作认证通过。

Windows 包的 `02-EnableWidgets.cmd` 保留为兼容文件名，实际调用 `OpenManager`：只打开管理器，不写 Hook、不重载 Launcher、不改系统设置。

`RemoveManager` 会枚举 `modules/`：任何官方或第三方已安装标记仍存在时都拒绝移除（停用仍视为已安装），并保留旧 `installed` 独立莫奈保护。它恢复 Setup 保存的端口、无障碍和 doze 状态，随后删除本包的 `manager-ready`、`frida-inject` 与状态快照；共享 Hook、QQ 和未知文件不动。

## 打包内容

在源码根目录准备签名 APK 后运行：

```powershell
./make-release.ps1 -AdbDirectory '<platform-tools>' -FridaInject '<匹配版本的 frida-inject>' -ReleaseRoot '<不存在的新目录>'
```

发布脚本从 `AndroidManifest.xml` 读取版本。本候选的顶层文件应为：

- `OWOrnate-v1.0.1-rc23.apk`
- `OWOrnate-v1.0.1-rc23-Windows.zip`
- `owornate-v1.0.1-rc23-source.zip`
- `watch2-monet-v6-source.zip`
- `monet-1.1.0.owo`、`widgets-1.0.0.owo`、`capture-1.1.0.owo`
- `owornate-updates.json`
- `SHA256SUMS.txt`

Windows 包包含 APK、`frida-inject`、adb 工具、三个官方 `.owo`、`manifest.json`、Setup/Status 脚本、许可证、文档和 `docs/`。`README-public.md` 复制为包根 `README.md`；`docs/README-public.md` 副本使用包内相对链接。`docs/screenshots/` 中旧截图随历史记录保留，不表示 code32 验收。

Windows 包在 `plugins/examples/screen-timeout/` 附带 shell-v1 示例源码，在 `plugins/examples/launcher-keep-awake/` 附带第三方 launcher-v1 示例源码和 `launcher-keep-awake-1.0.1.owo`。这样包内开发文档中的示例链接可离线打开。管理器源码包包含完整 `plugins/` 目录和同一组示例。

主题源码包带完整 `standalone/`、生成运行时所需的 `widget-host/capture-controls.js` 与 `widget-host/capture-indicator.js`、`licenses/` 和 `PROTOCOL.md`。`make-release.ps1` 会检查新运行时文件和两个片段是否进入白名单。导出在发现签名/密钥材料（`.jks`、`.keystore`、`.pk8`、`.adbkey`、`signing*.properties` 等）时中止。

清单生成器按传入的管理器版本、实际官方插件清单和文件摘要生成 `owornate-updates.json`，URL 指向同一 tag 下的实际附件。第三方示例不进入官方清单。运行打包不会创建 GitHub Release 或上传文件；上传清单时必须同时上传它引用的 APK 与官方 `.owo`。

## 从源码构建

构建需要 JDK 21 与同时含 `platforms`、`build-tools` 的 Android SDK；`build.ps1` 优先使用 build-tools 35.0.0 与 `platforms\android-35`，缺失时改用已安装版本。Java 目标仍为 Java 8。示例：

```powershell
./build.ps1 -PublicRelease -SdkRoot '<Android SDK>' -JdkHome '<JDK>' -BuildRoot '<纯英文构建目录>' -SigningDir '<工程外签名目录>'
```

签名材料不随源码分发；换签名无法覆盖已有正式安装。构建只产出 APK，不连接设备或运行测试。`runtime-helper/build.ps1` 重建 `theme.jar` 时固定使用本机 `platforms\android-35\android.jar` 与 `build-tools\35.0.0\lib\d8.jar`，缺任一项直接失败。helper v6 `theme.jar` 的 SHA-256 为 `706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74`。

## 验证入口与边界

离线 runner 在 `tests/`，不连接设备，在指定 work 或临时夹具目录写入测试产物。常见前提：

- 本机开发树中的无 BOM 脚本优先使用 PowerShell 7（`pwsh`）；导出的源码包与 Windows 包所有 `.ps1` 都带 UTF-8 BOM，可供 Windows PowerShell 5.1 解析。
- `run-tests.ps1`（调色板）调用 `rg --files`，需要已安装 ripgrep。
- `module_lifecycle_test.py` 需要 Python 与 bash，可传 `--bash <bash 路径>`。
- 课表测试使用 `org.json`；本地缺失时可从 Maven Central 下载并校验，或用 `-JsonJar` 指定。
- 桌面 Setup 的离线检查可使用静态解析和 fake-adb fixture；这些结果不能替代手表上的 Activity 回写、root 回环和 `adb tcpip` 重连。

历史 code31 定向入口（相应解码、插件仓库与升级检查继续作为 code32 的回归基线）：

```powershell
pwsh -File ./tests/run-adb-client-decode-test.ps1 -JdkHome '<JDK>' -BuildRoot '<输出目录>'
pwsh -File ./tests/run-plugin-repository-tests.ps1 -JdkHome '<JDK>' -JsonJar '<org.json jar>' -BuildRoot '<输出目录>'
pwsh -File ./tests/run-plugin-upgrade-tests.ps1 -JdkHome '<JDK>' -JsonJar '<org.json jar>' -BuildRoot '<输出目录>'
pwsh -File ./tests/run-launcher-plugin-tests.ps1 -JdkHome '<JDK>' -JsonJar '<org.json jar>' -BuildRoot '<输出目录>' -PackageDir '<packages 目录>'
pwsh -File ./tests/desktop_setup_test.ps1
```

插件测试工具只要求 JDK 11+ 与 `org.json` JAR，不执行插件代码，也不需要 Android SDK、ADB 或手表。完整开发流程测试另需 Git Bash；`BuildRoot` 应使用尚不存在的新目录。code31 / rc22 的公开附件、证书、摘要、v4 生命周期、v3 迁移和共享限制证据继续作为历史基线；rc23 / code32 本地构建和载荷身份检查已通过，但自动回退尚未在下一轮实机中触发，见 [VALIDATION.md](VALIDATION.md) 与 [ACCEPTANCE.md](ACCEPTANCE.md)。

## 历史

code29 的声明式设置与独立部署记录、code28 及更早的本机检查继续保留在 [VALIDATION.md](VALIDATION.md)。code31 / rc22 于 2026-10-24 公开发布，发布时 `main` 与 tag 指向 `8fed5d49e5de04a61f08163b06428965c9874ae1`，九附件已通过匿名公网摘要核对。上述历史不代表 code32 的授权回退已在设备上完成安装。当前未覆盖项以 [ACCEPTANCE.md](ACCEPTANCE.md) 首部为准。
