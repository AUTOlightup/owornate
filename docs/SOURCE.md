# 从源码构建

本文件说明如何从源码构建管理器 APK 与内置莫奈模块。构建不联网、不安装、不连接设备，只把产物写到本项目输出目录。

## 应用（管理器 + 内置莫奈模块）

入口是仓库根的 `build.ps1`。参数与环境变量均无硬编码机器路径，命令行参数优先于环境变量，其次才查 PATH 与常见安装位置。

| 参数 | 环境变量 | 说明 |
| --- | --- | --- |
| `-SdkRoot` | `WATCHWORKSHOP_ANDROID_SDK` / `ANDROID_SDK_ROOT` / `ANDROID_HOME` | 含 build-tools 与 platforms 的 Android SDK 根目录 |
| `-JdkHome` | `WATCHWORKSHOP_JDK` / `JAVA_HOME` | 含 bin\javac.exe 的 JDK 根目录 |
| `-BuildRoot` | `WATCHWORKSHOP_BUILD_DIR` | 纯 ASCII 中间构建目录 |
| `-SigningDir` | `WATCHWORKSHOP_SIGNING_DIR` | 工程之外的私有签名目录 |
| `-KeystorePath` | `WATCHWORKSHOP_KEYSTORE` | 签名库完整路径 |
| `-KeyAlias` | `WATCHWORKSHOP_KEY_ALIAS` | 签名别名 |
| `-SigningProperties` | — | 私有 properties 文件，提供口令与通道标记 |
| `-NoClean` | — | 保留已有构建目录内容 |
| `-PublicRelease` | — | 按公开发布名产出，并校验产物不是 debuggable |

口令只走环境变量或工程外的私有 properties，不写入源码。

构建公开发布候选件：

```powershell
pwsh -File .\build.ps1 -SdkRoot <AndroidSDK> -JdkHome <JDK> `
  -SigningProperties <私有properties> -PublicRelease
```

产物为 `dist/OWOrnate-v1.0.0.apk`，内置莫奈模块（`assets/monet/`）。不带 `-PublicRelease` 时按签名通道命名。工具链找不到时带明确提示失败，不会猜一个 SDK 或 JDK 继续。

## 内置莫奈模块（theme.jar）

入口是 `runtime-helper/build.ps1`，只编译 `native-src/` 与 `vendor/`，使用 JDK 与 Android SDK 的 platform、build-tools 中的 d8：

```powershell
pwsh -File .\runtime-helper\build.ps1 -SdkRoot <AndroidSDK> -JdkHome <JDK>
```

产物为 `runtime-helper/dist/WatchTheme-helper-v6.jar`。源码包已携带当前适配的 `assets/monet/theme.jar`，随管理器 APK 一起签名。重建 helper 后，需要手动将新 JAR 复制到该资产位置。`build.ps1` 会从 `standalone/` 同步三个脚本到 APK 资产目录。

## 可复现性

helper JAR 重建后，ZIP 时间戳可能改变文件 SHA256。更换内置 JAR 时同步更新 `standalone/module-manage.sh` 的 `THEME_SHA`、`build.ps1` 的资产指纹、`RuntimeProbe.THEME_SHA` 与 `release-manage.ps1` 的兼容指纹，再构建 APP 和打包。协议版本和 helperVersion 必须与实际接口一致。

发布签名库不公开。第三方构建应使用自己的签名，不能同签覆盖官方 APK；不要用卸载官方 APP 的方式假称保留它的草稿。

release 源码包同时保留管理器源码与 `runtime-helper/`（native-src、vendor、build.ps1），用于复现 `theme.jar`。单独发布主题模块见 [MONET-README.md](MONET-README.md)。

## 签名与密钥

- APK 私钥与口令保留在工程之外的私有目录，不进入源码或发布内容。
- 设备 ADB 私钥由部署流程在设备侧生成与授权，源码与安装包不含预制 keys。
- 打包前会扫描源码清单，出现 keystore、adbkey 或签名 properties 时停止打包。

## 发布准备

实时提交与目录排序的纯 JVM 检查直接运行当前源码，无需 Android 设备：

```powershell
pwsh -File .\tests\run-owornate-tests.ps1 -JdkHome <JDK> -BuildRoot <测试输出目录>
```

本版 6 组测试覆盖串行提交、最新选择合并、失败停止与恢复，以及已安装项稳定沉底；模块生命周期模拟测试本轮 56/56 通过。

- 预发布标签建议 `v1.0.0-rc2`，以 GitHub 预发布形式提供。
- 独立首次部署、模块生命周期与整机重启恢复已在本轮独立实机环境通过（原表临时隔离生产组件、只重建本包数据、已有 root），见 VALIDATION.md。
- 发布准备在本地进行，不自动推送远端。
- 在线目录与下载尚未接入，本版没有在线安装与 OTA。
