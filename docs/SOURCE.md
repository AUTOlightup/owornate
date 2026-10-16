# 源码与打包

**预发布版本：1.0.1-rc6 / versionCode 15。** 已生成同签名 APK `dist/OWOrnate-v1.0.1-rc6.apk`；Windows 包、源码包与摘要由 `make-release.ps1` 统一生成，随 [v1.0.1-rc6](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc6) 提供。

## 源码范围

发布源码归档需包含管理器源码、构建与安装脚本、`widget-host/` 目录及其脚本（含 `WatchHide-Launcher-Hook-v11.js` 与 `widget-manage.sh`）、`assets/monet/` 与 `runtime-helper/`，并保留仓库许可证和第三方许可证。不得包含签名私钥、密钥口令、ADB 私钥、设备备份或个人日志。

保留根目录 `.gitattributes`：`assets/`、`widget-host/` 与 `standalone/` 按原始字节存入和检出，确保 Windows Git 的换行转换不改写管理器校验的 Hook 指纹。

`widget-host/` 的共享环境接入材料用于已知 Hook v9/v10/v11 + helper v6 环境。源码包含这些脚本不表示支持任意 Hook、helper、设备或独立宿主环境。独立莫奈仍可用；独立宿主的小组件接入暂未支持。

负一屏“统一编辑器 / 首页重排”与滑动修复已放弃：源码与打包管线中不再有 `quickcenter/` 模块、`quickcenter.jar` 或相关测试，`make-release.ps1` 的源码清单也已移除该项（Hook 里的 “QuickCenter” 是负一屏自身的类名，保留）。

## 构建与发布

遵循仓库根目录构建说明生成候选 APK 与 Windows 包。发布包需要包含电脑备用入口 `02-EnableWidgets.cmd`。源码归档清单应验证 `widget-host/` 与脚本确实进入压缩包，并检查许可证保留和敏感文件排除。

本版 APK 为 `OWOrnate-v1.0.1-rc6.apk`，versionCode 15，沿用原签名且不可调试，250,364 bytes，SHA256 `31098f0462072b47653170895e28e369773ea5e8b8de5ce747e44755883bb055`。文件摘要见交付目录 SHA256SUMS.txt。本轮发布不重建 APK，附件沿用该 APK 字节。

打包内容：Windows 包与管理器源码包带 `docs/`，`README-public.md` 复制为包根 `README.md`，其中 `docs/README-public.md` 的链接前缀会相应调整；`docs/screenshots/` 是历史验证记录引用的证据，随引用它们的记录一起保留；工程根目录的 `screenshots/` 不进包。源码包另带 `runtime-helper/`，主题包带 `standalone/`、`licenses/` 与 `PROTOCOL.md`。打包脚本一旦在源码清单里发现签名或密钥材料（`*.jks`、`*.keystore`、`adbkey`、`signing*.properties` 等）就中止。

## 功能范围

首页直接列出已安装的功能（负一屏小组件、莫奈取色），“发现优化”只在还有可安装项时出现。三类小组件为 OWOrnate 信息、倒计时和课表，卡片尺寸 181×100dp；倒计时可显示系统“纪念日”里的日子（只读接入）。课表支持拾光单表 JSON，不支持 ICS、完整备份或通用皮肤导入。没有在线目录，也没有应用内自动更新。

历史验证（非本版）：code8 课表四项用户实测通过；code9 共享 Hook v10 的 Launcher 重载恢复通过；另有生命周期 68/68、倒计时 94、解析 149、焦点 136 等记录。code15 构建后没有整体重跑离线测试。

## 从源码构建

构建需要 JDK 21 与同时含 `platforms`、`build-tools` 的 Android SDK；`build.ps1` 优先使用 build-tools 35.0.0 与 `platforms\android-35`，缺失时自动改用已安装的版本并给出提示。在源码根目录执行：

```powershell
./build.ps1 -PublicRelease -SdkRoot '<Android SDK>' -JdkHome '<JDK>' -BuildRoot '<纯英文构建目录>' -SigningDir '<工程外签名目录>'
./make-release.ps1 -AdbDirectory '<platform-tools>' -FridaInject '<已有匹配版本的frida-inject>' -ReleaseRoot '<不存在的输出目录>'
```

签名材料不随源码分发；换签名无法覆盖已有正式安装。构建只产出 APK，不连接设备或运行测试。打包脚本从 `AndroidManifest.xml` 读取版本（当前 15 / 1.0.1-rc6），生成 Windows 入口、源码归档和 SHA256SUMS.txt。

## 构建前提

- 签名：`build.ps1` 需要 keystore 与口令（`-SigningDir` 下的 `signing.properties`，或用 `-KeystorePath` 与环境变量传入）。缺少签名文件或口令时直接失败，脚本不会生成新证书，也没有内置调试签名可回退，因此换签名无法覆盖已安装的正版。
- `runtime-helper/build.ps1`（重建 `theme.jar`）只使用本机已安装的 `platforms/android-35/android.jar` 与 `build-tools/35.0.0/lib/d8.jar`，没有版本回退，缺任一项就直接失败；app 主构建脚本有版本回退，不受此限制。发布包已含 `assets/monet/theme.jar`（helper v6，SHA256 `706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74`），只有需要重建主题运行件时才跑它。

## 测试前提

离线 runner 在 `tests/`（纪念日、倒计时、课表、小组件快照、调色板、密钥、模块生命周期等），不连接设备，也不写工程。运行前提：

- PowerShell 7（`pwsh`）；部分 runner 没有 BOM，Windows PowerShell 5.1 会解析失败。
- `run-tests.ps1`（调色板）调用 `rg --files`，需要已安装 ripgrep。
- 模块生命周期 `module_lifecycle_test.py` 需要 Python 与 bash（参数 `--bash <bash 路径>`）。
- 测试用 `org.json`（`json-20240303.jar`）不进 APK：`run-timetable-tests.ps1` 在本地缺失时会从 Maven Central 下载并校验 SHA-256，可用 `-JsonJar` 指定本地 jar；`run-anniversary-tests.ps1` 接受 `-JsonJar`，否则用开发工作目录里缓存的 jar。

本轮只改文档：没有重新构建 APK，也没有运行应用测试。
