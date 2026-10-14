# 第三方组件

自有源码使用 MIT。第三方源文件保留原有版权头和许可；这些许可不由 MIT 替代。

| 组件 | 固定来源 | 本包内容与许可 |
|---|---|---|
| Material Color Utilities | commit `5b3618b16fdc3825e21d5679bafd144662088ea1` | 所需 Java 源码随源码包提供，Apache-2.0，`licenses/material-color-utilities.txt` |
| Frida | 16.7.19 Android ARM | `frida-inject` 原样分发，作为首次部署的基础注入器；`licenses/frida-core.txt`、`frida-core-library.txt`、`frida-gum.txt` 原文 |
| Android Platform Tools | 安装包 `tools/adb/source.properties` 记录的版本 | adb 与配套 DLL 原样分发，完整 `tools/adb/NOTICE.txt` |

上游源码与发布：

- [Material Color Utilities 固定源码](https://github.com/material-foundation/material-color-utilities/tree/5b3618b16fdc3825e21d5679bafd144662088ea1/java)
- [Frida 16.7.19 发布](https://github.com/frida/frida/releases/tag/16.7.19)、[frida-core 源码](https://github.com/frida/frida-core/tree/16.7.19)、[frida-gum 源码](https://github.com/frida/frida-gum/tree/16.7.19)
- [Android Platform Tools 官方说明](https://developer.android.com/tools/releases/platform-tools)、[ADB 源码](https://android.googlesource.com/platform/packages/modules/adb/)

项目没有修改 Frida / adb 二进制；本包不包含 OPPO 系统 APK、用户表盘素材、预制私钥。APP 内可打开完整 HCT 与 Frida 许可证；平台工具的 NOTICE 随 Windows 包提供。

