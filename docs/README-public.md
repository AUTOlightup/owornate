# OWOrnate

**当前公开版本：1.0.1-rc23 / code32。** 本版针对 A.92 上普通应用无法启动 `MANAGE_UNKNOWN_APP_SOURCES` 设置页的问题，增加仅在 `ActivityNotFoundException` 或 `SecurityException` 时进入标准应用信息页的回退。授权仍由用户在系统界面手动完成，返回管理器后仍须再次确认系统安装器操作；管理器不会自动授予权限或静默安装。第三方 `launcher-v1`、v4 自有运行环境和三个官方插件包版本沿用 code31，见 [Launcher 插件](LAUNCHER-PLUGINS.md)与 [验证记录](VALIDATION.md)。

OWOrnate（`dev.codex.watchworkshop`）是面向 OPPO Watch 2 的原生 Android 插件管理器，提供统一管理页、右上开关和 `.owo` 导入。莫奈取色、负一屏小组件、截屏与录屏分别提供 launcher-v1 插件包；其他高级权限功能可使用 shell-v1 自行开发。

## 安装流程

首次安装和本机 root ADB 授权使用 Windows 包里的 `00-Setup.cmd`；安装包不负责获取 root。Setup 安装管理器、建立本机授权、准备固定版本 `frida-inject` 与恢复配置，并通过管理器自身的 root 回环认证确认就绪。

Windows 包内的 `payload/plugins/` 带有三个官方 `.owo`，但 **Setup 不会替你复制、导入或部署它们**。首次使用需要：

1. 把所需 `.owo` 复制到手表 `Download`，或通过系统文件选择器选取。
2. 打开管理器 → `发现优化` → `本地插件`，选择包并确认信任。
3. 导入后在插件管理页或对应功能页完成安装、接入或启用；`打开功能设置` 只进入配置页。

- 莫奈取色：`monet-1.1.0.owo`，安装并启用；声明式配色与圆角设置要求管理器 code29。
- 负一屏小组件：`widgets-1.0.0.owo`，接入负一屏。
- 截屏与录屏：`capture-1.1.0.owo`，安装模块；画质声明要求管理器 code28 或更高。

已有 code29 或 code30 授权环境可同签覆盖安装本版管理器；插件仍通过“信任并升级”更新。升级管理器不应清除第三方导入包、插件配置、主题、卡片或媒体。已有 v3 自有环境按保留载荷和启停选择的迁移路径升级到 v4。

打开页面、刷新状态或运行 Setup 都不会自动安装功能。`02-EnableWidgets.cmd` 只作为旧入口兼容名打开管理器，不再从电脑写入 Hook。详细步骤见 [安装说明](INSTALL.md)。

## 功能与兼容

- 莫奈 1.1.0 声明配色方式（自动、固定颜色、系统原色）和连续圆角开关。统一插件页与原主题页共用标题、选项顺序和文案；设置时重新读取实际主题，只修改所选字段，收到主题通道回执后才显示成功。
- 捕获 1.1.0 声明画质标题和三档选项，继续使用原有 `capture_options/bitrate`。高、标准、低分别对应 2.4、1.2、0.6 Mbps，默认标准；导入或切换声明不启动录制。
- 原生截屏与录屏：控制中心第二页短按截屏或打开录屏设置，录制中再按停止；长按分别打开截屏页、录屏设置页。H.264/MP4、原分辨率、无声、单段最多 180 秒。实际帧率随画面更新变化。
- 三类负一屏小组件：OWOrnate 信息、倒计时、课表，卡片 181×100dp。倒计时可只读接入系统“纪念日”；课表支持拾光单表 JSON，不支持 ICS、完整备份或通用皮肤导入。
- 新用户使用管理器自有运行环境，无需预装 WatchHide。莫奈、小组件、捕获的 launcher-v1 载荷来自导入的 `.owo`；管理器提供协调器、原生设置、采集服务与运行时脚本，导入不等于部署。
- 第三方 launcher-v1 使用自己的反向域名 ID，包只含 `plugin.json` 与 `hook.js`。多个 Hook 共用一个 supervisor 和一次注入；停用或卸载后的撤去仍依赖原 Launcher 重载。第三方代码没有沙箱隔离。
- 已知共享 Hook v9/v10/v11/v12/v13 与 helper v6 继续兼容。共享莫奈与捕获可按既有方式启停；共享 Hook 不能单独卸载，共享小组件暂不支持独立启停或卸载。Setup 和导入都不会替换共享 Hook，也不修改 QQ。

三个官方角色仍为莫奈、负一屏小组件、截屏与录屏；除此之外，第三方可提供普通 launcher-v1 Hook。当前不开放第三方原生 Activity、服务或自定义设置页 SDK。需要普通 root 命令和声明式配置时使用 shell-v1，见 [本地插件开发](PLUGIN-DEVELOPMENT.md) 与 [Launcher 插件](LAUNCHER-PLUGINS.md)。

<img src="screenshots/record-quick-code18.png" width="201" alt="历史录屏快捷页参考" />

上图是 code18 的历史界面参考，不是 code32 验收截图。

## 更新

管理器可从“设置 → 管理器更新”检查 GitHub，插件从管理页“检查插件更新”检查。rc23 已于 2026-10-24 23:59（UTC+8）发布，更新清单包含管理器 32、莫奈 1.1.0、小组件 1.0.0 和捕获 1.1.0；九个线上附件的大小和摘要均已核对。

清单公开后，下载内容会核对 ID、版本、大小和 SHA-256。APK 还必须匹配原包名、严格更高 versionCode 和原签名，之后才交给系统安装器确认。若 A.92 拒绝直接打开安装来源授权页，code32 只回退到系统应用信息页；用户须手动开启其中的“允许来自此来源的应用”开关，再返回管理器确认安装。插件进入同 ID 升级预览；launcher-v1 升级只替换导入包，部署由用户另行确认。

## 验证范围

历史 code31 已在测试用 OPPO Watch 2 / OW20W1 / A.92 / API27 / root 上完成同签安装、第三方卸载后重新信任导入并重装、初始化错误按 ID 显示、停用与卸载恢复，以及 v4 真实重启。重启后首次打开管理器前确认当前 bootId 对应的 `bootReady`、Launcher PID/映射、单 supervisor 和启停掩码。

共享环境随后完成真实信任导入、限制页禁用第三方开关、卸载模块确认及返回三个官方列表。原插件仓库、共享组件与 ADB keys 字节、Launcher 偏好、PID、bootId、主题和 capture idle 状态保持不变。测试恢复原偏好与 10 秒熄屏、删除测试下载并最终休眠；这几项界面检查没有安装、重载或重启。

code30 已验证的 v3→v4 迁移、第三方升级、无 trace 常亮效果和其他本机检查继续有效。code31 / rc22 已于 2026-10-24 公开发布，九附件经匿名公网下载和摘要核对；发布时 `main` 与 tag 指向提交 `8fed5d49e5de04a61f08163b06428965c9874ae1`。rc23 / code32 管理器 APK 为 341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd`；原证书、非 debuggable、许可资产检查通过，14 个 APK assets 与 code31 相同。

code32 已于 2026-10-24 23:59（UTC+8）公开，发布时 `main` 与 tag 指向提交 `440f6d4165d3671fd29a88be8a15f7fef27a3d35`；九个附件的线上大小、摘要和匿名公网下载校验通过。A.92 实机已从 code31 经管理器发现并下载公网 code32，核对包名、版本、签名和 SHA-256 后，由用户确认，再经 `HeytapPackageInstallerActivity` 点击安装；安装后的 current APK 摘要确认为 code32。该结果不是 `adb install`。

捕获插件也从 1.0.0 经公网发现并下载 1.1.0，完成预览和“信任并升级”；原 `plugin.json`/`hook.js` 字节、录屏画质和运行 Hook 均保持不变。收尾 17 项恢复检查全部通过：管理器原 `files` 与 `shared_prefs` 且无新增文件，保护组件与 keys、Launcher 偏好/PID/boot、主题 previous、无自有 runtime 和 capture idle 不变；5 个代理相关键与全局 unknown sources 原为 null，安装来源 AppOp 恢复 default；10 秒熄屏、31 表盘/10 复杂功能/5 卡片/41 应用顺序原样，最终 Asleep。

仍不能声称 code32 新增的 catch/fallback 分支已实际触发：本次 code31 → 32 安装同样是在普通应用信息页已手动授权后完成。标准目标页面路径已有实机证据，下一次从未授权状态升级时仍需单独验证 catch 分支。

**未覆盖：** code32 新增 catch/fallback 分支的实际触发、旧 v2 真实迁移、其他硬件/固件、实际录制、QQ 送达及长期功耗。已公开的九附件保持原字节；发布包内文档是发布前准备快照，本仓库文档记录发布后的设备与恢复结果。详见 [验证记录](VALIDATION.md) 与 [验收范围](ACCEPTANCE.md)。

## 隐私与源码

截图保存到 `Pictures/Screenshots`，视频保存到 `Movies/Screenrecords`，只保存在手表本地。更新检查只在用户主动操作时访问 GitHub 或插件声明的 HTTPS 源；没有账号、遥测、后台定时更新或静默安装。

发布包不包含签名私钥、口令、ADB 私钥、个人课表、设备备份或个人设备识别信息。详见 [隐私说明](PRIVACY.md)、[源码与打包](SOURCE.md) 和 [第三方组件](THIRD-PARTY.md)。管理器完整源码包包含三个官方 launcher-v1 包源、shell-v1 与 launcher-v1 示例、创建/校验/打包工具和发布说明；开发工具只在这个完整源码包中提供。第三方 1.0.1 示例源码已导出，Windows 包会在 `plugins/examples/launcher-keep-awake/` 提供示例源码与可直接导入的 `launcher-keep-awake-1.0.1.owo`；该示例不进入官方更新清单。

