# OWOrnate 1.0.1-rc23 / versionCode 32（预发布）

发布地址：[v1.0.1-rc23](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc23)。九个附件按打包时原字节提供；源码归档中的候选说明保留为准备阶段快照。

候选管理器 APK 发布文件名为 `OWOrnate-v1.0.1-rc23.apk`：341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd`。原证书、非 debuggable 和许可资产检查通过；14 个 APK assets 与 code31 相同。

## 本版变化

- 修复 A.92 上普通应用不能直接启动 `MANAGE_UNKNOWN_APP_SOURCES` 设置页的问题。`UpdateActivity` 仅在启动遇到 `ActivityNotFoundException` 或 `SecurityException` 时回退到标准 `ACTION_APPLICATION_DETAILS_SETTINGS` 应用信息页，并显示明确说明。
- 回退后仍由用户手动打开“允许来自此来源的应用”，返回管理器后仍须点击按钮并确认系统安装器。应用不会自动修改权限，也不会自动确认安装。
- code31 的 AdbClient 无行尾换行修复、第三方 launcher-v1、v4 自有运行环境及插件错误详情沿用；三个官方插件包版本和运行载荷不变。
- 从 code30 起，launcher-v1 接受第三方 safeID 与只含 `plugin.json`、`hook.js` 的两文件包。第三方与三个官方插件共用导入、管理页、右上开关、错误展示及更新/卸载入口。
- 自有运行环境使用 version 4，单个 supervisor 和单次注入；已知 version 3 可迁移并保留已部署载荷与启停选择。共享 WatchHide 环境只保留导入包，不部署第三方 Hook。
- 第三方不开放原生 Activity、服务或自定义设置页 SDK。需要普通 root 命令和声明式配置时继续使用 shell-v1；进程内 Hook 没有沙箱隔离。
- 三个官方插件契约保持：莫奈 1.1.0 / code2、负一屏小组件 1.0.0 / code1、截屏与录屏 1.1.0 / code2。莫奈 Hook/helper v6 `theme.jar` 与官方运行载荷字节未改。
- 管理器完整源码包包含插件创建、生产解析器校验、打包和独立更新清单工具。Windows 包用于安装和插件使用，不是插件开发工具包。

## 安装

1. 完整解压 `OWOrnate-v1.0.1-rc23-Windows.zip`，连接已开启 root adbd 的适配手表，运行其中的 `00-Setup.cmd`。Setup 安装管理器、授权本机 ADB、部署固定版本 `frida-inject`、准备无障碍/恢复配置，并通过管理器自身的 root 回环认证确认就绪；安装包不负责获取 root。
2. Setup 只准备管理器基础环境，**不会把 `payload/plugins` 中的 `.owo` 复制到手表，也不会替你导入或部署功能插件**。
3. 首次使用把所需 `.owo` 放到手表 `Download`（或通过系统文件选择器选取），在管理器“发现优化 → 本地插件”导入并确认信任。导入只注册包；之后在插件管理页或对应功能页完成安装、接入或启用。
   - `monet-1.1.0.owo`：莫奈取色；安装并启用后可在统一页设置配色方式与圆角。
   - `widgets-1.0.0.owo`：负一屏小组件；导入后接入负一屏。
   - `capture-1.1.0.owo`：截屏与录屏；导入后安装模块。
4. 已有授权用户也可以对当前包同签覆盖：`adb install -r OWOrnate-v1.0.1-rc23.apk`。该命令保留应用数据，但不建立或修复管理器的本机 root 授权；换电脑或授权失效时重新运行完整 `00-Setup.cmd`。
5. Windows 包 `plugins/examples/` 提供 shell-v1 与第三方 launcher-v1 示例源码；后者同时提供可直接导入的 `plugins/examples/launcher-keep-awake/launcher-keep-awake-1.0.1.owo`。该文件不占顶层 9 个正式附件，也不进入官方更新清单。

详细步骤见 [安装说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/INSTALL.md)，插件工具见 [开发快速开始](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/PLUGIN-QUICKSTART.md)，Launcher 插件协议见 [Launcher 插件](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/LAUNCHER-PLUGINS.md)。

## 验证范围

- code31 / 1.0.1-rc22 已在 OPPO Watch 2 / OW20W1 / A.92 / API27 / root 上完成第三方卸载、重新信任导入、重装就绪、初始化错误按 ID 显示、开关停用与卸载恢复，以及 v4 真实重启。这些结果作为 code32 的历史沿用，不替代 rc23 的安装授权验收。
- 重启后首次打开管理器前取得当前 bootId 对应 `bootReady=true`、实际 Launcher PID/映射、单 supervisor 和启停掩码。电脑保留 ADB 取证连接，不据此声称物理断开。
- 共享环境完成真实信任导入、限制页第三方开关禁用、卸载模块确认和返回三个官方列表。原插件仓库、共享组件与 ADB keys 字节、Launcher 偏好、PID、bootId、主题和 capture idle 状态保持不变。
- code30 已验证的 v3→v4 迁移、第三方升级、无 trace 常亮效果及其他本机检查继续有效；code31 全部 14 个 APK assets 与 code30 相同。
- 真实 AdbClient 6 项回归、完整构建、原证书、非调试与许可资产检查通过；Windows 符号链接断言因权限跳过，不声称通过。

当前 A.92 阶段：设备以临时 code30 作为起点；GitHub API 可发现更新，但 `github.com` 附件直连超时。经已有 PC 代理，应用成功发现并下载 code31，包名、版本、签名和 SHA-256 校验通过。直接安装来源授权 Activity 被系统拒绝后，用户手动进入标准应用信息页开启“允许来自此来源的应用”，返回管理器确认安装，再由 `HeytapPackageInstallerActivity` 点击安装；当前已安装 APK 摘要确认升级到 code31，主链成功。该路径需要手动绕到系统应用信息页；code32 的自动回退尚未在新一轮实机中触发。

**未覆盖：** rc23 / code32 的自动回退触发、发布、安装后状态及重启；旧 v2 真实迁移、其他硬件/固件、本版实际录制、QQ 送达和长期功耗。代理下载和包校验本身不是安装证据；本阶段另以上述实际系统安装器界面与安装后 APK 摘要确认主链成功。详见 [验证记录](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/VALIDATION.md) 与 [验收范围](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/ACCEPTANCE.md)。

## 附件（9）

| 文件 | 内容 |
| --- | --- |
| `OWOrnate-v1.0.1-rc23.apk` | code32 管理器 APK；原证书、非 debuggable；341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd` |
| `OWOrnate-v1.0.1-rc23-Windows.zip` | Windows 首次设置包：管理器 APK、`frida-inject`、adb 工具、三个官方 `.owo`、示例源码与 `plugins/examples/launcher-keep-awake/launcher-keep-awake-1.0.1.owo`、脚本、文档和发布清单 |
| `owornate-v1.0.1-rc23-source.zip` | 管理器完整源码与开发工具：`src/`、资源、测试、`plugins/`、插件创建/校验/打包工具、运行时脚本、构建/发布脚本、文档和许可证 |
| `watch2-monet-v6-source.zip` | 莫奈 helper v6 源码：`native-src/`、`vendor/`、`build.ps1`、`standalone/` 与运行时片段；不含整个管理器 |
| `monet-1.1.0.owo` | 莫奈 launcher-v1 包；`hook.js` 与 helper v6 `theme.jar` 保持原字节 |
| `widgets-1.0.0.owo` | 负一屏小组件 launcher-v1 包 |
| `capture-1.1.0.owo` | 截屏与录屏 launcher-v1 包 |
| `owornate-updates.json` | 管理器与三个官方插件的更新清单；URL 指向本 tag 的同名附件 |
| `SHA256SUMS.txt` | 上述 8 个文件的 SHA-256 摘要，不含本文件自身 |

第三方 1.0.1 示例只随 Windows 包和完整源码包分发，不进入顶层正式附件，也不进入官方更新清单。开发工具只随完整管理器源码包提供。

## 更新清单

`owornate-updates.json` 使用 `schemaVersion: 1`，包含管理器 `dev.codex.watchworkshop` 和三个官方插件 ID，每个 ID 只出现一次。rc23 的管理器条目应为 1.0.1-rc23 / code32，莫奈为 1.1.0 / code2，小组件为 1.0.0 / code1，捕获为 1.1.0 / code2。清单须引用同 tag 下的实际附件；摘要用于传输完整性，不代表第三方作者签名。

上传时必须同时提供清单及其引用的原文件，不能只传清单或改名后沿用旧清单。code26 及更早版本没有这套应用内更新入口，需要先手动同签升级。code27 及更高客户端能读取发布清单；rc23 已构建，但其自动回退仍需在下一次实机更新中单独触发验证。

## 许可与隐私

自有源码使用 MIT；Material Color Utilities 为 Apache-2.0，Frida 与 Android Platform Tools 保留原许可。发布包不含签名私钥、ADB 私钥、用户课表、设备备份或个人设备识别信息。详见 [隐私说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/PRIVACY.md)、[第三方组件](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/THIRD-PARTY.md) 和 [源码与打包](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc23/docs/SOURCE.md)。

## 已公开历史版本

[v1.0.1-rc22](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc22) 已于 2026-10-24 公开。发布时 `main` 与 tag 指向提交 `8fed5d49e5de04a61f08163b06428965c9874ae1`；九个附件已完成匿名公网下载和 SHA-256 核对。该版本修复 ADB 无行尾换行输出截断，并在设备上完成第三方插件与 v4 重启验收。后续在临时 code30 上通过代理完成附件下载和包校验，手动进入系统应用信息页授权后，管理器确认安装，再由系统安装器完成 code31 覆盖；安装后摘要已确认。

## 发布时补充

捕获插件1.0.0→1.1.0已通过手表实际公网下载、预览及“信任并升级”；原导入包字节、画质和共享运行组件保留，未部署组件或启动录制。本次网络使用已有PC代理临时连接；附件直连超时，未更改电脑代理配置。code32自动回退分支仍按上述边界单独说明，不把手动设置入口的成功当作新分支实测。
