# OWOrnate 1.0.1-rc27 / code36 · 界面美化与发现分组

发布地址：[v1.0.1-rc27](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc27)。九个附件按打包时原字节提供；归档保留发布准备快照。

## 本版变化

本版统一管理器界面，精简主页与导航文案，发现页在同一界面按“未安装／已安装”分组，本地插件位于已安装首位。已安装条目显示实际版本；小组件设置、课表与录屏画质页使用统一样式。实际负一屏小组件的布局与配色保持。

- 首页仅保留功能计数和入口名；设置导航去除重复副标题。
- 统一暖金强调、深灰卡片、字体层级和交互反馈；修正页头图标比例，长模块标题支持两行。
- 课表使用连续日期选择区，信息卡片和倒计时设置精简说明；录屏画质与主要操作首屏完整显示。
- 三个官方包、helper v6、运行 Hook、v4 生命周期和更新安全校验沿用；未修改实际小组件 UI。

## 安装与开发

已有授权环境可同签覆盖 `OWOrnate-v1.0.1-rc27.apk`。首次安装使用 Windows 包 `00-Setup.cmd`，之后在管理器中导入并确认信任所需 `.owo`，再安装或启用对应功能。安装包不获取 root，也不自动导入或部署插件。

见 [安装说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc27/docs/INSTALL.md)、[开发快速开始](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc27/docs/PLUGIN-QUICKSTART.md) 与 [Launcher 插件](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc27/docs/LAUNCHER-PLUGINS.md)。

## 实机与验证边界

2026-10-25，在 OW20W1 / A.92 / API27 / 已有 root 的原无线生产表上，同签覆盖安装最终 code36，安装后 APK 摘要与本地一致。主要页面有真实截图，标准字体下录屏画质、更多说明和主操作首屏完整；125% 字体下模块标题与开关不重叠。构建、原签名、非 debuggable 和四项许可资产检查通过；14 项 assets 与 9 项编译后 widget 资源相对 code35 原字节不变。16 项收尾检查通过，插件文件、五组用户设置、共享组件、Launcher PID 与 boot 保留；临时字体和熄屏恢复，最后休眠，未重载或重启。

手动倒计时滚轮、所有子页面／字号、实际录制及配置写入没有全量验收。code32 的公网系统安装器主链作为历史沿用，不能当作 code36 再次在线升级通过；授权 catch/fallback 的直接触发、其他硬件／固件和长期功耗仍待验证。

最终 APK：341073 bytes，SHA-256 `b54aa93e9b6aa337ff8121f171f04eb76dbd74a1bdeccb6da7ae083f925be317`。

## 附件（9）

| 文件 | 内容 |
| --- | --- |
| `OWOrnate-v1.0.1-rc27.apk` | 管理器 APK，原签名、非 debuggable |
| `OWOrnate-v1.0.1-rc27-Windows.zip` | Windows 首次设置包、adb、三个官方插件与示例 |
| `owornate-v1.0.1-rc27-source.zip` | 完整源码、测试与两类插件开发工具 |
| `watch2-monet-v6-source.zip` | helper v6 与运行组件源码 |
| `monet-1.1.0.owo` | 莫奈取色 |
| `widgets-1.0.0.owo` | 负一屏小组件 |
| `capture-1.1.0.owo` | 截屏与录屏 |
| `owornate-updates.json` | 同 tag 的管理器／插件更新清单 |
| `SHA256SUMS.txt` | 八项正式产物的 SHA-256 |

公开归档不包含个人数据、设备标识、备份或私钥。源码归档与 tag 保留发布时快照，历史版本附件不覆盖。
