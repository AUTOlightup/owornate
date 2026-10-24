# OWOrnate

**当前发布候选：1.0.1-rc22 / code31，发布准备快照；实际发布状态见 [GitHub 预发布](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc22)。** code31 修复 ADB 输出缺少行尾换行时被截断的问题，插件初始化错误现在显示插件 ID 与具体异常。第三方 `launcher-v1` 可使用自己的 safeID，与官方插件共用导入、管理页、右上开关和错误展示；自有运行环境为 v4，保留已知 v3 迁移。共享 WatchHide 环境只保留导入包，不部署第三方 Hook。详见 [Launcher 插件](LAUNCHER-PLUGINS.md)与 [验证记录](VALIDATION.md)。

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

上图是 code18 的历史界面参考，不是 code31 验收截图。

## 更新

管理器可从“设置 → 管理器更新”检查 GitHub，插件从管理页“检查插件更新”检查。rc22 候选的更新清单包含管理器 31、莫奈 1.1.0、小组件 1.0.0 和捕获 1.1.0；清单与附件尚未上传，当前不能把本候选项当作在线可用版本。

清单公开后，下载内容会核对 ID、版本、大小和 SHA-256。APK 还必须匹配原包名、严格更高 versionCode 和原签名，之后才交给系统安装器确认。插件进入同 ID 升级预览；launcher-v1 升级只替换导入包，部署由用户另行确认。真实公网附件下载到系统安装器的完整覆盖仍未实机验收，`adb install -r` 不能替代该结果。

## 验证范围

code31 已在测试用 OPPO Watch 2 / OW20W1 / A.92 / API27 / root 上完成同签安装、第三方卸载后重新信任导入并重装、初始化错误按 ID 显示、停用与卸载恢复，以及 v4 真实重启。重启后首次打开管理器前确认当前 bootId 对应的 `bootReady`、Launcher PID/映射、单 supervisor 和启停掩码。

共享环境随后完成真实信任导入、限制页禁用第三方开关、卸载模块确认及返回三个官方列表。原插件仓库、共享组件与 ADB keys 字节、Launcher 偏好、PID、bootId、主题和 capture idle 状态保持不变。测试恢复原偏好与 10 秒熄屏、删除测试下载并最终休眠；这几项界面检查没有安装、重载或重启。

code30 已验证的 v3→v4 迁移、第三方升级、无 trace 常亮效果和其他本机检查继续有效；code31 的 14 个 APK assets 与 code30 相同，APK 为 341,073 bytes，SHA-256 `a8f52477c9284750cacdf25496e88eebf54f2efbc4043b09883c4e161ba1c790`。发布流程直接复用该原证书 APK，不重新构建或签名。

**未覆盖：** 旧 v2 真实迁移、其他硬件/固件、本版实际录制、QQ 送达、长期功耗，以及公网下载到系统安装器。上述结果仍待相应环境验证。详见 [验证记录](VALIDATION.md) 与 [验收范围](ACCEPTANCE.md)。

## 隐私与源码

截图保存到 `Pictures/Screenshots`，视频保存到 `Movies/Screenrecords`，只保存在手表本地。更新检查只在用户主动操作时访问 GitHub 或插件声明的 HTTPS 源；没有账号、遥测、后台定时更新或静默安装。

发布包不包含签名私钥、口令、ADB 私钥、个人课表、设备备份或个人设备识别信息。详见 [隐私说明](PRIVACY.md)、[源码与打包](SOURCE.md) 和 [第三方组件](THIRD-PARTY.md)。管理器完整源码包包含三个官方 launcher-v1 包源、shell-v1 与 launcher-v1 示例、创建/校验/打包工具和发布说明；开发工具只在这个完整源码包中提供。第三方 1.0.1 示例源码已导出，Windows 包会在 `plugins/examples/launcher-keep-awake/` 提供示例源码与可直接导入的 `launcher-keep-awake-1.0.1.owo`；该示例不进入官方更新清单。

