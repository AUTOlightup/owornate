# OWOrnate 1.0.1-rc29 / code38 · 设置直达与全局碰一下

发布地址：[1.0.1-rc29](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc29)。附件保留本次打包时快照。

## 本版变化

插件详情改为设置优先，长说明、管理更新与技术资料同页折叠。莫奈、负一屏小组件和截屏录屏从首页、发现及本地插件列表直接进入完整设置；插件包页只负责包信息、更新和移除，原设置页保留重新接入与修复。新增独立的支付宝全局碰一下插件，支持从表盘或息屏碰标签唤起原支付宝，并有短震动反馈。

- 整合 code37、code38 的插件详情与导航改动，精简正常状态，保留错误反馈、启停、卸载确认、修复和重新接入。
- 新增 `alipay-global-0.1.0.owo`，更新清单和 Windows 包一并提供。
- 碰一下保留钱包模拟卡与原支付宝支付确认；用户已反馈外出完成实际付款。
- 三个既有 Launcher 插件、helper v6、共享 Hook 和实际小组件 UI 保持。

## 安装

已有授权用户同签覆盖 APK；首次使用完整 Windows 包 `00-Setup.cmd`。把所需 `.owo` 放到 Download 后导入并确认信任，再到功能设置启用。碰一下需自行准备 OPPO 渠道支付宝3152、root 和 Frida16.7.19；本项目不分发支付宝 APK。**开机或 NFC 进程重启后需重新开启碰一下。**

见 [安装说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc29/docs/INSTALL.md)、[碰一下使用说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc29/docs/ALIPAY-GLOBAL.md) 和 [插件开发](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc29/docs/PLUGIN-DEVELOPMENT.md)。

## 验证

管理器 code38 已在 OW20W1 / A.92 / API27 / root 生产表同签覆盖，设备 APK 与最终产物一致。实机验证三个官方功能与支付宝的入口、返回、折叠管理与恢复动作；临时同版本包模拟更新交接并取消，未执行真实升级。15 项管理测试、62 项资源／assets 一致性及 19 项生产状态与恢复检查通过。code37 已完成正常与 125% 字体下的代表详情页检查；本版沿用相关公共排版。实际小组件界面、共享 Hook 和已有配置保留，临时显示设置恢复，未重启或重载。

支付宝插件已完成导入、启停、配置、卸载重导入与 NFC 服务重启后的手动冷启动；真正息屏下碰贴纸自动唤醒、打开原支付宝并短震动已实测，钱包一张模拟卡的信息读取与标签发现可共存。用户反馈于 2026-10-27 22:23（UTC+8）外出碰一碰付款成功，并提供付款成功照片；这是用户现场实测结果，本轮不另行发起交易或用日志替代付款凭证。

开机或 NFC 进程重启后需手动重新开启碰一下。带屏蓝环、锁屏、轻智能、长期功耗、真实门禁闸机仍未验收。code38 未重新验收联网更新／系统安装器完整升级、实际录屏或所有设置写入；历史通过项按对应版本和前提沿用。

复用已装表的最终 APK，345169 bytes，SHA-256 `f72b77fa445179d42ef1484dc32523d00455df33bce64c973f0079229a22121a`，不重新构建或签名。发布动作不再安装、启停或重启手表。

## 附件（10）

| 文件 | 内容 |
| --- | --- |
| `OWOrnate-v1.0.1-rc29.apk` | 管理器 APK，原签名、非 debuggable |
| `OWOrnate-v1.0.1-rc29-Windows.zip` | Windows 设置包、adb、四个功能插件和示例 |
| `owornate-v1.0.1-rc29-source.zip` | 管理器、插件、测试与构建工具源码 |
| `watch2-monet-v6-source.zip` | helper v6 与运行组件源码 |
| `monet-1.1.0.owo` | 莫奈取色 |
| `widgets-1.0.0.owo` | 负一屏小组件 |
| `capture-1.1.0.owo` | 截屏与录屏 |
| `alipay-global-0.1.0.owo` | 支付宝全局碰一下 |
| `owornate-updates.json` | 同 tag 的管理器／四插件更新清单 |
| `SHA256SUMS.txt` | 九项正式产物的 SHA-256 |

公开源码与附件不包含个人付款照片、账号数据、设备备份、私有交接或密钥。旧 tag 与附件保留。
