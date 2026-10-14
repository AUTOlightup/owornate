# 安装与部署

本文件覆盖 OWOrnate 1.0.0 / versionCode 7 的首次部署、状态检查、重启验证与移除管理器。示例参数使用占位符，请替换成实际值；文档不记录真实设备序列号、账号或网络地址。

## 前置条件

- 电脑：Windows，PowerShell 5.1 及以上。
- 手表：OPPO Watch 2 46mm（OW20W1），固件 A.92 / API 27 / armeabi-v7a，已 root 且 root adbd 可用。
- 完整安装包：解压后包含 `release-manage.ps1`、`manifest.json`、`payload/`、`tools/`、`licenses/` 与 `scripts/`。请完整解压运行，不要只拷贝单个文件。
- 本版没有在线安装与 OTA，不承诺 APK 降级。

## 部署入口与参数

脚本入口：

```powershell
.\release-manage.ps1 -Action Setup|Status|RemoveManager|VerifyBoot `
  [-Serial target] [-ExpectedHardware hardware] [-Adb path] [-BundleRoot completepackage]
```

| 参数 | 说明 |
| --- | --- |
| `-Action` | `Setup` / `Status` / `RemoveManager` / `VerifyBoot`，默认 `Status`；`Install` 是 `Setup` 的等价别名 |
| `-Serial` | 目标设备标识；首次可省略 |
| `-ExpectedHardware` | 预期硬件标识，用于防止连错设备 |
| `-Adb` | adb 路径；默认用安装包内 `tools/adb/adb.exe` |
| `-BundleRoot` | 完整安装包根目录；默认为脚本所在目录 |

Windows 双击入口，等价于对应 Action：

- `00-Setup.cmd` → Setup
- `01-Status.cmd` → Status

`RemoveManager` 与 `VerifyBoot` 用脚本 Action 运行：`.\release-manage.ps1 -Action RemoveManager` 与 `.\release-manage.ps1 -Action VerifyBoot`。

首次运行时 `-Serial` 可以省略：只有恰好一台 online 设备时才会自动选用，否则拒绝执行并要求显式指定。

## 先判运行模式

连接设备后先判断属于哪一种，再按对应步骤操作：

- 独立模式：设备上没有原综合 watchhide，也没有旧分享版 `watch-monet`。
- 共享模式：设备上已有原综合 watchhide（v9）。主题能力复用既有共享组件，不部署独立莫奈、不启用 APP 恢复服务；APP 安装与公钥授权照常。

判断不了就先停在原状，不要在两套组合之间覆盖。

## 独立模式：Setup

```powershell
.\release-manage.ps1 -Action Setup -ExpectedHardware <hardware> -BundleRoot <completepackage>
```

Setup 依次完成：

1. 校验完整安装包：读取 `manifest.json`，逐个核对清单内文件的 SHA256。
2. 校验设备：OW20W1 / A.92 / API27 / armeabi-v7a，并核对 Launcher 版本；不符合就停止。
3. 安装管理器 APP，并启动一次。
4. 等待 APP 自己的 ADB 公钥就绪，再把它追加到设备既有授权列表。
5. 建立本包私有运行目录 `/data/local/tmp/watchworkshop-runtime`，推送 Frida 基础运行文件并逐个校验，写入基础状态与 `manager-ready` 标记。
6. 启用管理器的无障碍恢复入口、加入设备待机白名单，并将既有 root adbd 的 TCP 端口持久化为 5555，供管理器通过本机地址连接。安装前设置保存在基础环境记录中；仅已安装且未停用的模块会自动恢复。

Setup 不安装莫奈模块。安装完成后在手表打开 OWOrnate，在“发现优化”进入“莫奈取色”介绍页，点“安装并启用”（仍有一次安装确认）。模块文件取自 APK 内置资源，离线在表端完成，不需要电脑或联网；安装方案不下载文件，也不会重新部署基础环境。

独立模式安装并启用莫奈后，可重启手表并运行 `release-manage.ps1 -Action VerifyBoot` 验收开机恢复。该命令用于开发验收，日常安装和管理功能不需要它。

## 共享模式：Setup

设备已有原综合 watchhide（v9）时，Setup 仍然安装 APP 并写入 APP 自己的公钥授权；主题能力由既有共享组件提供，不启动独立基础运行件，也不启用 APP 恢复服务。既有组件需为 Launcher Hook v9 + helper v6，版本不符时拒绝接管。

## 重启验证：VerifyBoot

```powershell
.\release-manage.ps1 -Action VerifyBoot
```

在整机重启后、首次打开 OWOrnate 之前运行。它核对本次开机的 boot 记录与当前主题加载是否一致；尚未由 APP 完成本次开机恢复时会提示再次运行。

## 状态检查：Status

```powershell
.\release-manage.ps1 -Action Status
```

输出 root 身份、固件、基础运行件与主题状态，用于只读确认。Status 不改动设备。

## 手表端模块生命周期

`Setup` 之后，莫奈的安装、启停、配置与卸载都在手表端完成：未安装时在“发现优化 → 莫奈取色”介绍页点“安装并启用”（仍有一次安装确认）；已安装后在“我的优化”进入莫奈配置页，用右上角开关启用 / 停用，在配置页底卸载或做单步“恢复上一次设置”。

- 安装：在“安装方案”点“安装并启用”，把 APK 内置的 `theme.jar`、`hook.js`、`monet-service.sh` 复制到运行目录并写入 `installed` 标记；安装来源为管理器内置，不下载，也不重新部署基础环境。
- 停用：写入 `disabled` 标记并停止主题服务。
- 启用：清除 `disabled` 并重新启动主题服务。
- 卸载：只删除 `theme.jar`、`hook.js`、`monet-service.sh` 与 `installed` / `disabled` 标记，保留基础运行文件、APP 恢复服务、ADB 授权、草稿与主题偏好，之后可再次安装。

已有共享组件时不能单独卸载共享主题，只能停用：停用会把主题设为系统原色并关闭连续圆角，启用时恢复之前的配色。

## 移除管理器：RemoveManager

```powershell
.\release-manage.ps1 -Action RemoveManager
```

独立模式会移除本管理器部署的基础运行文件，关闭本 APP 的无障碍恢复入口，并按安装前状态恢复待机白名单；当前 adbd 端口仍为 5555 时恢复记录中的原值。APP 数据与草稿、自己的 ADB 公钥、系统 root 和主题偏好保留。若仍安装着莫奈，先在手表莫奈配置页底卸载莫奈再执行。共享模式不移除原有组件。

模块卸载保留管理器，RemoveManager 移除管理器部署的基础运行件，两者不同：前者用于收回莫奈功能，后者用于撤掉本次首次部署。

## 与旧分享版 watch-monet

本包不支持与旧独立分享版 `watch-monet` 并存。检测到旧包时安装会被拒绝：请先按旧包的卸载说明移除其运行组件，再安装本包；本管理器自身的数据保留。

## 验收边界

- 独立模式下的首次 Setup 部署、手表端模块生命周期与整机重启恢复已在本轮独立实机环境通过（原表临时隔离生产组件、只重建本包数据、已有 root，不是出厂重置或新表）；验收结束后原表已恢复到原 code6 环境。空白 root 表与其他机型 / 固件仍待复跑。
- 共享模式主题应用、恢复上一次设置、停用/启用沿用既有记录，见 [验证记录](VALIDATION.md)；这些记录来自 code5 及更早候选。code7 的实时配置与商店介绍流程见同一文件的 code7 验收节。
- 公开仓库地址：https://github.com/AUTOlightup/owornate（主题源码仓库：https://github.com/AUTOlightup/watch2-monet）。
