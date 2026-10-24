# OWOrnate · Monet helper 源码

本包（`watch2-monet-source`）只包含控制中心莫奈取色的 helper 与运行时源码，用于审计和重建 `theme.jar`/运行时片段。它不包含管理器 App、Frida 二进制、预制 keys 或 OPPO APK 素材，不能单独完成整机部署。

## 目录

| 路径 | 内容 |
| --- | --- |
| `native-src/` | `ControlTheme`、`ThemePalette`、`FaceSource`、`ControlChrome`、`ControlCorners`、`CornerGeometry`、`ThemeBridge` 等 helper 源码 |
| `vendor/` | Material Color Utilities 的 HCT、量化、Score 与工具类（Apache-2.0） |
| `build.ps1` | 构建 `WatchTheme-helper-v6.jar` |
| `standalone/runtime-base.js`、`standalone/runtime-hook.js` | 自有 Launcher 运行时基础与莫奈 helper 注入核心 |
| `standalone/runtime-core.js`、`runtime-commit.js`、`runtime-manage.sh`、`runtime-service.sh` | 运行时装配、提交、生命周期与 root supervisor |
| `standalone/monet-service.sh`、`module-manage.sh`、`hook.js` | 旧独立莫奈安装与兼容材料 |
| `standalone/widget-host.js` | 负一屏小组件运行时基础 |
| `standalone/build-runtime.ps1` | 由 runtime 基础、小组件与捕获片段生成可校验的运行时载荷 |
| `widget-host/capture-controls.js`、`widget-host/capture-indicator.js` | 截屏录屏控制与表盘指示器源码片段 |
| `PROTOCOL.md` | 莫奈主题通道协议 v1 |
| `licenses/`、`LICENSE` | 第三方组件声明与本项目 MIT 许可 |

本包不包含管理器的 Activity、页面、插件导入器、设置 UI、发布打包脚本或管理器 APK。它不是完整的 launcher-v1 插件源码包：`monet-1.1.0.owo` 的清单与 Hook 由发布附件提供，helper `theme.jar` 可由这里的源码重建。

## 架构：HCT helper v6 与闭合 mailbox

- helper 运行在 Launcher 进程内，用 HCT 从当前表盘派生配色，并按 `mode` / `seed` / `corners` 应用。
- 控制通道是 Launcher 私有目录里的闭合 mailbox：app 写一条固定格式 `request.json`，helper 用 `FileObserver` 响应并写 `ack.json` 回执；app 不提供任意 shell 或外部端口入口。
- 自有模式使用运行目录 `/data/local/tmp/watchworkshop-runtime`。code26 起，莫奈 `hook.js` 与 helper `theme.jar` 由用户导入并信任的 `.owo` 提供；本候选包为 `monet-1.1.0.owo`。管理器 APK 提供协调器、原生设置接口、运行时脚本和已知共享 Hook 兼容载荷，不再从 APK 回退自有莫奈载荷。
- 共享模式支持已知 WatchHide v9/v10/v11/v12/v13 + helper v6。识别后由管理器管理；未经用户显式接入不替换共享运行文件。Windows Setup 和插件导入都不会自动替换共享 Hook。

`runtime-hook.js` 是旧版合成载荷，随源码保留供兼容与历史检查；当前自有 v4 按 core → 导入插件的功能 Hook → commit 装配，不以这个旧合成文件回退。已知 v3 可迁移并保留已部署载荷与启停选择；旧 v2 的真实迁移仍未验收。自有运行组件不包含 NFC 点击替换、WatchHide 应用隐藏或 QQ 蓝点。

## 构建 helper

```powershell
pwsh -File .\build.ps1 -SdkRoot <AndroidSDK> -JdkHome <JDK>
```

产物为 `dist/WatchTheme-helper-v6.jar`。构建只用 JDK 与 Android SDK（platform 与 build-tools 中的 d8），不联网。

脚本直接取 `<AndroidSDK>/platforms/android-35/android.jar` 与 `<AndroidSDK>/build-tools/35.0.0/lib/d8.jar`，没有版本回退：本机缺少 android-35 或 build-tools 35.0.0 时构建直接失败。

helper v6 `theme.jar` SHA-256 为 `706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74`。`monet-1.1.0.owo` 沿用同一字节，只更新 `plugin.json` 的声明；管理器 APK 不含这份自有莫奈载荷，只有需要重建主题运行件时才运行这里的构建。

## 分发与部署

OWOrnate 1.0.1-rc23 / code32 候选的基础环境拟由 Windows 包的 `00-Setup.cmd` 一次性准备。Setup 不导入功能：首次使用需要把 `monet-1.1.0.owo` 放到手表 `Download`，在管理器“发现优化 → 本地插件”导入并确认信任，再从插件管理页或莫奈功能页完成部署/启用。

导入包保存在管理器私有目录，运行载荷部署到管理器自有环境。普通功能页卸载运行组件会保留导入包；本地插件页卸载会先清理自有运行组件，再移除导入包。两条路径均保留用户配置和媒体。

本源码包不带 Frida 二进制、预制 keys 或 OPPO APK 素材，无法单独完成整机部署；完整部署使用管理器发布的 Windows 安装包。共享环境继续使用既有 Hook/helper，不能把本包当作替换共享组件的安装器。

## 状态

当前管理器候选为 code32 / 1.0.1-rc23，本地构建已通过，尚待上传和自动回退实机验收；只增加 A.92 安装来源授权页的回退处理，仍需用户手动开启系统开关并确认安装。APK 为 341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd`，原证书、非 debuggable 与许可资产检查通过。helper v6 与莫奈 1.1.0 的 Hook/theme.jar 字节不变；声明式配色/圆角继续沿用既有实机验收。code31 / rc22 已于 2026-10-24 公开，发布时 `main` 与 tag 指向 `8fed5d49e5de04a61f08163b06428965c9874ae1`，九附件经匿名公网摘要核对；code30 → code31 的应用内下载、包校验、手动系统授权和系统安装器覆盖也已成功，code30 的 v3→v4 迁移及 code31 第三方/v4 重启证据继续有效。code32 的自动回退、旧 v2 真实迁移、实际录制、QQ 送达、长期功耗和其他固件版本仍未验收。
