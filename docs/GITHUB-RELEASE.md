# GitHub 发布记录与打包说明

**预发布版本：1.0.1-rc6 / versionCode 15。** [下载本版](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc6)。发布包含 APK、Windows 包、管理器源码包、主题源码包与 SHA256SUMS；沿用已核对的候选原始字节，不重建 APK。旧版 v1.0.0-rc2（code7）的附件与验收结论仅对应旧版。

## 候选内容

OWOrnate 1.0.1-rc6 在控制中心莫奈取色之外，提供三类负一屏小组件（OWOrnate 信息、倒计时、课表，卡片 181×100dp）与负一屏外观开关：

- 管理器内接入：复用现有 root ADB 授权，核对共享 Hook v9/v10/v11 与 helper v6，备份并升级 Hook 到 v11，再定向重载一次表盘；已在 v11 上时按钮为“重新接入”。
- 隐藏小布助手：管理器「负一屏小组件」页底的开关；用户已实测确认。
- 倒计时可选系统“纪念日”（只读接入快应用数据），离开设置页自动保存。
- 管理器首页直接列出已安装的功能，“发现优化”只在还有可安装项时出现；莫奈的刷新配色、恢复上一次设置与卸载收在“更多选项”里。

支持范围是已知共享环境（共享 Hook v9/v10/v11 + helper v6）。独立莫奈仍可用，独立宿主的小组件接入暂未支持。课表兼容拾光单表 JSON，不支持 ICS、完整备份或通用皮肤导入。没有在线目录，也没有应用内自动更新。

## 发布件状态

- 已存在：`dist/OWOrnate-v1.0.1-rc6.apk`，250,364 bytes，SHA256 `31098f0462072b47653170895e28e369773ea5e8b8de5ce747e44755883bb055`，versionCode 15 / 1.0.1-rc6，沿用既有签名且不可调试。
- 发布附件由打包脚本统一生成：Windows 包、OWOrnate 源码包、主题源码包与 SHA256SUMS。重新打包时运行：

```powershell
./make-release.ps1 -AdbDirectory "<platform-tools>" -FridaInject "<匹配版本的 frida-inject>" -ReleaseRoot "<不存在的输出目录>"
```

打包脚本从 `AndroidManifest.xml` 读取版本，产物名是 `OWOrnate-v1.0.1-rc6-Windows.zip`、`owornate-v1.0.1-rc6-source.zip`、`watch2-monet-v6-source.zip` 与 `OWOrnate-v1.0.1-rc6.apk`。输出目录必须不存在，否则脚本直接报错；不传 `-ReleaseRoot` 时默认写到当前目录的 `outputs/OWOrnate-v1.0.1-rc6-code15`。

Windows 包与管理器源码包带 `docs/`：`README-public.md` 复制为包根 `README.md`，其中 `docs/README-public.md` 的链接前缀会相应调整；`docs/screenshots/` 是历史验证记录引用的证据，随这些记录一起保留。源码包另带 `runtime-helper/`；主题包带 `standalone/`、`licenses/` 与 `PROTOCOL.md`。打包脚本一旦在源码清单里发现签名或密钥材料（`*.jks`、`*.keystore`、`adbkey`、`signing*.properties` 等）就中止。

## 发布前状态

已确认（用户实测）：隐藏小布助手与管理器里的开关。已按设备回读核对：安装版本、接入状态、当前加载的 Hook 为 v11、安装后无崩溃。

已装表但用户未逐项反馈：首页直接列出功能、倒计时离开自动保存、莫奈“更多选项”、小组件状态显示“已接入”、图标外观、读状态时不闪不跳。未实测：纪念日只读模式。未测：整机重启后的恢复。code15 构建后没有整体重跑离线测试。

历史 code8 课表四项用户实测、code9 共享 Hook v10 重载恢复，以及生命周期 68/68、倒计时 94、解析 149、焦点 136 等记录都属于历史版本，不得表述为 code15 验收。

## 上传注意

- 公开源码提交在本仓库，发布标签为 `v1.0.1-rc6`；本机签名与 ADB 凭据不随源码分发。
- 源码包必须包含 `widget-host/`（含 `WatchHide-Launcher-Hook-v11.js`、`widget-manage.sh`）与 `assets/monet/`，保留许可证；不得包含签名私钥、口令、ADB 私钥、设备备份或个人日志。
- 不把负一屏“统一编辑器 / 首页重排”与滑动修复写进发布说明：这两项已放弃，相关代码与 `quickcenter.jar` DEX 已从源码和打包管线移除。
- 不宣称应用内自动更新；升级方式是电脑同签覆盖安装新 APK，再在管理器里重新接入。

## 发布标题

OWOrnate 1.0.1-rc6 · 负一屏小组件、隐藏小布助手与纪念日模式

发布标签 `v1.0.1-rc6`（与 versionName 一致），以预发布形式提供。

## 发布说明

OWOrnate 1.0.1-rc6 候选在控制中心莫奈取色之外，提供三类负一屏小组件：OWOrnate 信息、倒计时和课表，卡片尺寸 181×100dp。管理器可在已知共享环境中核对共享 Hook v9/v10/v11 与 helper v6、备份并升级到 v11、再定向重载一次表盘；接好后可在管理器里隐藏负一屏顶部的小布助手那一行。倒计时支持显示系统“纪念日”里的日子（只读接入）。首次安装与 root ADB 授权用电脑运行 `00-Setup.cmd`；之后的接入与开关在手表上完成，后续 APK 更新仍用电脑同签覆盖安装。

课表支持拾光单表 JSON，不支持 ICS、完整备份或通用皮肤导入。独立莫奈功能仍可使用，独立宿主的小组件接入暂未支持。没有在线目录与下载，也没有应用内自动更新。

此候选已在原表装表验证：安装版本、接入状态与当前加载的 Hook 版本已核对，隐藏小布助手经用户实测确认；纪念日模式、整机重启恢复与其余管理器改动仍待实测。历史 versionCode 7 至 14 的结果不作为本版验收。发布前的待测项目见 `VALIDATION.md` 与 `ACCEPTANCE.md`。
