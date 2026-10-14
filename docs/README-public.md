# OWOrnate · 公开发布说明

本文件对应 OWOrnate 1.0.0 / versionCode 7（建议标签 `v1.0.0-rc2`）。这是预发布候选材料，不是正式验收版。主仓库 https://github.com/AUTOlightup/owornate，主题源码仓库 https://github.com/AUTOlightup/watch2-monet，本版预发布 https://github.com/AUTOlightup/owornate/releases/tag/v1.0.0-rc2。

## 这是什么

OWOrnate（包名 `dev.codex.watchworkshop`）是运行在 OPPO Watch 2 上的原生 Android 管理器，用 Java / 原生 Views 编写。它只包含一个模块：控制中心莫奈取色。莫奈模块随签名 APK 内置，首次用电脑完成一次部署后，安装、启停、配置和卸载都在手表上完成，不需要电脑，也不需要联网下载。

## 发布状态：候选发布件

- 版本：1.0.0 / versionCode 7；APK 文件名 `OWOrnate-v1.0.0.apk`，152,717 bytes，SHA256 `39e96a6ac06ef7d43aae96af530edebc71478f48605e1eec10ea0f4786f5d9f5`。
- 独立首次部署、手表端模块生命周期与整机重启恢复已在本轮独立实机环境通过：在原表临时隔离生产组件、只重建本包自己的数据、已有 root，不是出厂重置或新表；电脑保留 ADB 取证与输入连接，未声称物理断开。
- 建议以 `v1.0.0-rc2` 作为 GitHub 预发布标签。预发布标签说明本版为 RC2 候选，也不构成对未测边界（长期功耗、MCU 换色、其他机型与固件、空白表复跑）的验收结论。
- 0.x、1.0（旧 code3）、1.1.0（code4 / code5）与 1.0.0（code6）记录属于历史基线，只作背景，不作为本版的验收结论。

## 工作方式：电脑一次，手表日常

1. 首次：在电脑上运行 Windows 包里的 `00-Setup.cmd`（等价于 `release-manage.ps1 -Action Setup`）。它只做三件事：安装管理器 APP、把管理器自己的 ADB 公钥加入设备授权、部署 Frida 基础运行文件与 APP 恢复服务（仅独立设备启用）。它不安装莫奈模块。
2. 之后：打开手表上的 OWOrnate，在“发现优化”里打开“莫奈取色”的介绍页，点“安装”进入方案，再点“安装并启用”，模块从 APK 内置资源离线安装。装好后的配置在“我的优化”里的莫奈配置页完成：启停用同一页右上角的开关，卸载与恢复在页底。全流程都在手表上，不需要电脑或联网下载。

## 适用设备

- OPPO Watch 2 46mm，型号 OW20W1，固件 A.92，Android 8.1 / API 27，armeabi-v7a。
- 需要设备已有 root，并且 root adbd 可用。
- 首次部署需要电脑协助；日常操作在手表上进行。
- 其他机型、固件或 ABI 未适配，不在本版范围；没有 root 或 root adbd 不可用的设备无法使用。

## 导航与入口

首页顶部是运行状态摘要，下面只有两个栏目：我的优化、发现优化；右上角齿轮进入设置。栏目只显示图标与主标题，不再有第二行子标题。右滑是返回手势：二级页面右滑返回上一级，首页右滑退出到表盘（系统自带的右滑关闭已关闭）。首页没有“返回表盘”按钮，也没有“最近操作”入口。

- 我的优化：列出已纳入管理的优化，进入莫奈配置页查看真实状态并直接调整，不再经过管理页中转；还没有优化时给出去“发现优化”的入口，状态读取失败时提供“重新读取状态”。
- 发现优化：始终列出内置的“莫奈取色”介绍入口（标注内置来源与安装状态）。点击进入商店式的功能介绍页：未安装提供“安装”（仍有一次安装确认），已安装（含已停用与共享接入）统一沉底并把主按钮改为“打开配置”。
- 设置：进入“运行环境”（手表信息与授权状态）与“关于 OWOrnate”（版本、许可与首次设置，技术详情移入此处）。

发现优化中的已安装项先展示介绍，由用户显式“打开配置”；安装完成后回到介绍页，卸载后回到“我的优化”。本版只有一个模块，实际列表顺序看不出变化，排序按“未安装在前、已安装沉底”处理。

首发只内置莫奈一个模块，其他模块与在线目录不在界面展示；在线目录尚未接入。

## 配色

- 配色方式：自动取色（跟随表盘）、固定颜色、系统原色。
- 固定颜色只在固定模式下生效；其他模式可保留颜色草稿。
- 连续圆角是独立开关。
- 实时生效：在莫奈配置页选择配色方式、固定颜色或连续圆角后直接提交，页面内给出轻量的“应用中 / 已生效”反馈，不再有“应用更改”按钮和常规应用确认页。
- 应用只改动控制中心，提交后立即生效，无需重启表盘。
- 页底提供“当前版本”、卸载与单步“恢复上一次设置”，恢复回到最近一次成功提交前的设置。
- “导出配置”移到技术详情，语义为“已确认设备配置快照”，不再是未应用的草稿导出。

应用结果以设备返回的回执为准；读取到的状态、预览颜色和草稿都只是展示数据，不代表已经生效。快速连续修改会串行提交并收敛到最新一次选择；提交失败或超时会明确提示未确认并回读实际状态。

## 共享组件（已有 watchhide v9 + helper v6）

设备上已有原综合 watchhide（v9）与 helper v6 时，主题能力属于既有共享组件。管理器直接识别复用，不做隔离，也不在现有生产表上假装成空白环境：

- 停用 = 把主题设为系统原色并关闭连续圆角。
- 启用 = 恢复停用前保存的配色。
- 不能单独卸载共享主题：共享运行件同时承担原综合优化，单独删除会破坏既有功能。共享接入项的配置页底显示“共享组件，无法单独卸载”，仍可通过右上开关停用 / 启用。

## 部署入口

- 脚本：`release-manage.ps1 -Action Setup|Status|RemoveManager|VerifyBoot [-Serial target] [-ExpectedHardware hardware] [-Adb path] [-BundleRoot completepackage]`
- Windows 双击入口：`00-Setup.cmd`（Setup）、`01-Status.cmd`（Status）；`RemoveManager` 与 `VerifyBoot` 用脚本 Action 运行。
- `Setup` 保留 `Install` 作为等价别名。

请完整解压后运行，不要只拷贝单个文件。首次运行时 `-Serial` 可以省略：只有恰好一台 online 设备时才会自动选用，否则要求显式指定。

- `Setup`：安装管理器 APP、授权本机 ADB 公钥、部署 Frida 基础文件与 APP 恢复服务（仅独立设备启用）。不安装莫奈模块。
- `Status`：只读输出 root 身份、固件与基础运行件状态。
- `RemoveManager`：先在手表配置页底卸载莫奈后，移除本管理器部署的基础运行件并停用 APP 恢复服务，保留 APP 数据、ADB 授权、系统 root 与主题偏好；共享模式下不删除共享运行件。
- `VerifyBoot`：整机重启后、首次打开 OWOrnate 之前运行，核对本次开机由 APP 恢复的记录与当前主题加载。

## 卸载

模块卸载在手表莫奈配置页底完成，只删除 `theme.jar`、`hook.js`、`monet-service.sh` 与 `installed` / `disabled` 标记，保留基础运行文件、APP 恢复服务、ADB 授权、草稿与主题偏好，所以之后可以再次安装。共享接入项不能单独卸载。

`RemoveManager` 用于彻底移除管理器部署的基础运行件。它不会清理 QQ 数据、登录状态或输入法词库，也不卸载系统 root。在系统里卸载 APP 会清除 APP 自己保存的草稿。

## 从源码构建

构建方式与内置莫奈模块源码见 [docs/SOURCE.md](SOURCE.md)。release 源码包保留 `runtime-helper/`，用于复现 `theme.jar`。单独发布主题模块的说明见 [docs/MONET-README.md](MONET-README.md)。

<img src="screenshots/home-owornate.png" width="201" alt="首页" /> <img src="screenshots/store-owornate.png" width="201" alt="商店介绍" /> <img src="screenshots/theme-owornate.png" width="201" alt="实时配置" /> <img src="screenshots/footer-owornate.png" width="201" alt="详情底部" />

截图取自原共享手表上的 code6 界面实测；code7 界面与之相同，独立验收结果见 [docs/VALIDATION.md](VALIDATION.md)。

## 文档索引

- [docs/INSTALL.md](INSTALL.md)：首次部署、状态检查、重启验证与移除管理器。
- [docs/CHANGELOG.md](CHANGELOG.md)：版本记录。
- [docs/PRIVACY.md](PRIVACY.md)：权限与数据处理说明。
- [docs/ACCEPTANCE.md](ACCEPTANCE.md)：验收状态与候选发布声明。
- [docs/UI-alignment.md](UI-alignment.md)：与设计原稿的对应关系。
- [docs/SOURCE.md](SOURCE.md)：从源码构建。
- [docs/MONET-README.md](MONET-README.md)：内置与单独发布的莫奈主题源码说明。
- [docs/VALIDATION.md](VALIDATION.md)：实机验证记录（含历史候选）。
- [docs/THIRD-PARTY.md](THIRD-PARTY.md)：第三方组件与许可。

## 许可

自有代码使用 MIT，见仓库根 `LICENSE`。第三方组件与许可完整清单见 [docs/THIRD-PARTY.md](THIRD-PARTY.md)，原文随安装包在 `licenses/` 提供。

## 尚未验证

- 独立环境的首次 `Setup` 部署、表端独立安装 / 停用 / 启用 / 卸载 / 重装与整机重启恢复已通过（本轮在原表临时隔离生产组件、只重建本包数据、已有 root）；空白 root 表与出厂重置的新表仍待复跑。
- 本版实时配置、共享启停、商店介绍与逐级返回已在原共享表实测；队列/排序6组、导航方法26项离线检查与模块生命周期模拟测试 56/56 通过。极端字体倍率下的完整检查仍待实机验收。
- 在线模块目录与下载尚未接入，本版只有 APK 内置的莫奈可离线在表端安装；其他模块与在线目录入口在界面隐藏。