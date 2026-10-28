# OWOrnate

**1.0.1-rc30 / code39** · [下载与完整附件](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc30)

OWOrnate（`dev.codex.watchworkshop`）是面向 OPPO Watch 2 的原生 Android 插件管理器，支持 `.owo` 本地导入、声明式设置和主动检查更新。

插件详情改为设置优先，长说明、管理更新与技术资料同页折叠。莫奈、负一屏小组件和截屏录屏从首页、发现及本地插件列表直接进入完整设置；插件包页只负责包信息、更新和移除，原设置页保留重新接入与修复。支付宝全局碰一下新增开机和 NFC 服务重启自动恢复，支持从表盘或息屏碰标签唤起原支付宝，并有短震动反馈。

<img src="docs/ui-code39/alipay.png" width="201" alt="支付宝全局碰一下设置" /> <img src="docs/ui-code39/capture.png" width="201" alt="截屏录屏完整设置" /> <img src="docs/ui-code39/package.png" width="201" alt="独立的插件包管理" />

## 安装

适配基线：OPPO Watch 2 46mm / OW20W1 / A.92 / Android 8.1 API27 / 已有 root。Windows 包不负责获取 root。

1. 首次使用，完整解压 `OWOrnate-v1.0.1-rc30-Windows.zip` 并运行 `00-Setup.cmd`，安装管理器、建立 root ADB 授权和基础环境。已有授权用户可同签覆盖 APK，保留应用数据。
2. 把所需 `.owo` 放到手表 `Download`，从“发现优化 → 本地插件”导入并确认信任。Setup 不自动导入或部署功能插件。
3. 打开相应设置页，安装、接入或启用功能。管理与更新位于同页折叠区域。

详细步骤见 [安装说明](docs/INSTALL.md)。

## 插件

| 插件 | 版本 | 功能 |
| --- | --- | --- |
| `monet-1.1.0.owo` | code2 | 控制中心取色、固定颜色和连续圆角 |
| `widgets-1.0.0.owo` | code1 | 负一屏信息卡片、倒计时与课表 |
| `capture-1.1.0.owo` | code2 | 原生截图、H.264 无声录屏，单段最多 180 秒 |
| `alipay-global-0.2.1.owo` | code3 | 全局碰一下，支持息屏识别、80ms 短震动 |

碰一下还需要自行安装并登录 **OPPO 渠道支付宝 10.6.41.260917 / code3152**，以及 Frida 16.7.19 注入运行环境。发布包不含支付宝 APK。开启后即可碰标签，由原支付宝完成金额输入和支付确认；开启后自动记住状态，开机或 NFC 服务重启后自动恢复。见 [碰一下安装与使用](docs/ALIPAY-GLOBAL.md)。

倒计时可只读接入系统“纪念日”，课表支持拾光单表 JSON。开发支持 `shell-v1` 和 `launcher-v1`，见 [快速开始](docs/PLUGIN-QUICKSTART.md)、[本地插件开发](docs/PLUGIN-DEVELOPMENT.md) 和 [Launcher 插件](docs/LAUNCHER-PLUGINS.md)。

自有 Launcher 运行环境 v4 支持多个 Hook；共享 WatchHide 环境保留限制，不部署第三方 Launcher Hook 或替换共享载荷。碰一下使用独立 NFC 进程。插件需要用户信任，没有沙箱隔离；暂不提供第三方原生 Activity、服务或自定义设置页 SDK。

## 更新与验证

“设置 → 管理器更新”检查管理器版本，插件从“管理与更新”检查。清单包含管理器 code39 和上表四个插件，下载后校验 ID、版本和 SHA-256；管理器还检查包名及原签名，经用户确认后交系统安装器。见 [更新协议](docs/UPDATES.md)。

管理器 code39 与支付宝插件 0.2.1 已在原表验证 NFC 进程和整表重启自动恢复，手动停用后保持关闭。0.1.0 曾验证亮屏与息屏贴纸唤起、钱包模拟卡信息读取共存，用户反馈外出支付成功。0.2.1 重启后的贴纸支付、长期功耗仍待复测。

详见 [验证记录](docs/VALIDATION.md) 和 [验收范围](docs/ACCEPTANCE.md)。

## 隐私与源码

插件不修改原支付宝账号、支付请求、金额或支付确认，保留钱包 SE/AID 路由。没有遥测、后台定时更新或静默安装；发布包不含付款照片、账号数据、设备备份、签名或 ADB 私钥。见 [隐私说明](docs/PRIVACY.md)、[源码与打包](docs/SOURCE.md)、[第三方组件](docs/THIRD-PARTY.md)。


升级旧版碰一下时先停用旧插件，再导入 0.2.1 并开启一次。code39 和 0.2.1 已实机验证整表及 NFC 服务重启恢复，重启后贴纸付款与长期功耗仍待复测。
