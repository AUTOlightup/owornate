# 安装、首次设置与插件导入

**当前发布候选：1.0.1-rc23 / code32。本地构建已通过，尚待上传和自动回退实机验收。** 本版修复 A.92 上普通应用无法直接启动 `MANAGE_UNKNOWN_APP_SOURCES` 设置页的问题：只在 `ActivityNotFoundException` 或 `SecurityException` 时回退标准应用信息页，并要求用户手动打开“允许来自此来源的应用”。授权和系统安装确认都不自动完成。launcher-v1 继续使用 code30 的第三方接口与 v4 运行环境；共享 WatchHide 环境不部署第三方 Hook。Launcher 插件协议见同一文档目录中的 `LAUNCHER-PLUGINS.md`，验收边界见 [验证记录](VALIDATION.md)。

## 适用范围

OPPO Watch 2 46mm / OW20W1 / A.92 / API27 / armeabi-v7a，已有 root adbd。安装包不负责获取 root。共享接入识别已知 Launcher Hook v9/v10/v11/v12/v13 与 helper v6；未知版本、未知独立组件或硬件不匹配时拒绝接管。

launcher-v1 保留莫奈、小组件、捕获三个官方接口，并接受第三方两文件 Hook 包；尚未提供自定义原生 Activity、服务或设置页 SDK。普通 root 脚本继续使用 shell-v1，协议见 [本地插件开发](PLUGIN-DEVELOPMENT.md)。

## 候选分发内容

本次 rc23 候选维持 9 个顶层正式附件：管理器 APK、Windows 包、管理器源码包、莫奈 helper 源码包、三个官方 `.owo`、`owornate-updates.json` 和 `SHA256SUMS.txt`。文件名随 rc23 重命名，三个官方包版本不变。实际归档和摘要核对属于发布流程；文件名与验收边界见 [GitHub 发布说明](GITHUB-RELEASE.md)。

Windows 包完整解压后包含：

```text
OWOrnate-v1.0.1-rc23-Windows/
  00-Setup.cmd
  01-Status.cmd
  02-EnableWidgets.cmd
  release-manage.ps1
  manifest.json
  README.md
  docs/
  licenses/
  tools/adb/
  payload/OWOrnate-v1.0.1-rc23.apk
  payload/frida-inject
  payload/plugins/monet-1.1.0.owo
  payload/plugins/widgets-1.0.0.owo
  payload/plugins/capture-1.1.0.owo
  plugins/examples/screen-timeout/
  plugins/examples/launcher-keep-awake/
```

`plugins/examples/launcher-keep-awake/` 提供第三方示例源码和可直接导入的 `launcher-keep-awake-1.0.1.owo`。该示例不占顶层正式附件，也不进入官方更新清单。插件创建、校验、打包和独立清单工具只随完整管理器源码包提供。

候选 APK 拟命名为 `OWOrnate-v1.0.1-rc23.apk`：341,073 bytes，SHA-256 `cc9126262425a90e9861667e24d0cf2ecd66abc494f197948e7d638f42a5a8fd`。它沿用原证书并保持非 debuggable；许可资产检查通过，14 个 APK assets 与 code31 相同。code32 只增加安装来源授权页的回退处理。

## 电脑首次设置

完整解压 Windows 包，连接已开启 root adbd 的适配手表，在解压目录运行：

```text
00-Setup.cmd
```

Setup 只准备管理器运行所需的基础环境，**不安装功能，也不导入或部署 `payload/plugins` 中的 `.owo`**：

1. 校验发布清单、设备型号、系统版本、ABI、Launcher 版本和已知组件指纹。
2. 同签安装或覆盖管理器 APK，等待管理器生成本机 ADB 公钥并加入系统授权。
3. 部署固定版本的 `frida-inject`，加入管理器无障碍服务、启用恢复入口，并仅为管理器加入 doze 白名单。
4. 首次设置前保存 ADB 端口、无障碍服务和 doze 白名单快照；已有本包快照时保留原值。
5. 把 ADB 本机通道固定到 5555；需要切换时执行 `adb tcpip 5555`、重连并重新确认 root、硬件和兼容性；局域网连接改用同一地址的 5555 端口。
6. 写入 `manager-ready` 后，用一次性 GUID 启动管理器认证。管理器通过自身 root 回环连接读取结果，并原子写入应用私有 `files/setup-check.json`。Setup 只接受令牌完全匹配且 `ok:true` 的结果；15 秒内没有匹配结果、`ok:false` 或回写错误都会让 Setup 失败。

电脑端 `adb root` 成功不能单独证明管理器可用；回环认证失败时不会报告部署成功。依次检查手表是否解锁、管理器是否报错、root adbd 是否仍可用后，重新运行 `00-Setup.cmd`。不要在压缩包内直接运行脚本；必须保持 `payload/`、`tools/` 与 `release-manage.ps1` 的相对目录完整。

## 首次导入与部署

首次装好管理器后，仍需按以下步骤导入并部署功能：

1. 把 Windows 包 `payload/plugins/` 下所需官方 `.owo` 复制到手表 `Download` 目录。使用系统文件选择器时也可以直接从其他可见位置选取。
2. 打开 OWOrnate → `发现优化` → `本地插件`，选择包，确认名称、版本、ID、描述和 root 权限说明，再点击“信任并导入”。
3. 导入只注册包；shell-v1 会在确认导入后读取一次 `status`，但不会自动启用。launcher-v1 也不会因为导入而自动部署 Hook、重载 Launcher 或替换共享组件。
4. 在插件管理页或对应功能页完成部署/接入，再按需要启用：

| 功能 | 包 | 首次动作 |
| --- | --- | --- |
| 莫奈取色 | `monet-1.1.0.owo` | 导入并信任 → 安装并启用；声明式设置要求管理器 code29+ |
| 负一屏小组件 | `widgets-1.0.0.owo` | 导入并信任 → 接入负一屏 |
| 截屏与录屏 | `capture-1.1.0.owo` | 导入并信任 → 安装模块；声明式画质要求管理器 code28+ |

`打开功能设置` 只进入既有莫奈、小组件或采集设置页，不代替导入或部署。导入包保存在管理器私有目录，部署载荷位于管理器自有运行环境；普通功能页卸载运行组件会保留导入包，本地插件页卸载会先清理自有运行组件，再移除导入包。两条路径均保留用户配置、卡片、主题和媒体。

第三方 launcher-v1 使用自己的 safeID，包只含 `plugin.json` 与 `hook.js`。导入不会自动部署；用户之后安装或更新组件时，多个 Hook 共用单个 supervisor 和一次注入。Hook 是用户明确信任的进程内代码，没有沙箱隔离。

## 已有共享环境

已经安装已知共享 Hook 的手表仍使用同一个 `00-Setup.cmd`。已知共享分支继续完成公钥授权、`frida-inject`、无障碍/恢复配置、本机 ADB 通道、设置快照、`manager-ready` 和应用内回环认证。Setup 不替换共享 Hook，也不修改 QQ 或其他共享组件。

共享环境可以保留第三方导入包供以后迁移，但不能部署新的第三方 Hook。限制页把第三方开关显示为禁用；已验收的卸载模块入口会先要求确认，完成后返回三个官方插件列表。该操作不覆盖共享 Hook、helper、白名单或用户偏好。`02-EnableWidgets.cmd` 仅为旧入口兼容别名：它打开管理器并提示在表内操作，不更新 Hook、不重载 Launcher，也不写系统设置。

## 升级

安装 code27 或更高版本后，可从“设置 → 管理器更新”检查 GitHub 并下载；插件从管理页“检查插件更新”检查。rc22 已于 2026-10-24 公开发布，rc23 已构建但清单尚未上传；不能把本地 rc23 清单当作在线源。下载内容会先校验 ID、版本、大小和 SHA-256；APK 还要求原包名、严格更高 versionCode 与原签名，再交给系统安装器确认。

A.92 实测中，普通应用直接启动 `Settings$ManageAppExternalSourcesActivity` 会被系统拒绝，异常为 `not exported`。code32 仅在启动遇到 `ActivityNotFoundException` 或 `SecurityException` 时回退到标准 `ACTION_APPLICATION_DETAILS_SETTINGS` 应用信息页；实际可打开 `SingleAppManagerActivity`，其中显示“允许来自此来源的应用”开关。用户必须手动开启该开关，返回管理器后再次点击确认安装并通过系统安装器确认。管理器不会自动授予该权限，也不会自动确认安装。

code30 → code31 主链已在设备上完成：设备以临时 code30 为起点，GitHub API 可发现更新，但 `github.com` 附件直连超时；经已有 PC 代理，应用下载 code31 并通过包名、版本、签名与 SHA-256 校验。用户随后手动进入标准应用信息页开启来源开关，返回管理器确认安装，再由 `HeytapPackageInstallerActivity` 点击安装；安装后的管理器 APK 摘要确认已是 code31。该成功路径依赖手动导航到系统应用信息页。

code32 的自动回退代码已经进入本地候选，但尚未在新一轮设备流程中触发。必须重新从安装来源未授权状态验证它能直接打开应用信息页，并保留用户手动开启开关、返回确认和系统安装器确认这几步，才可把自动回退记为实机通过。

插件也可以直接导入新版 `.owo`。脚本插件必须先停用；同 ID、同 runtime、严格更高 versionCode 才可升级。升级保留 `config.json`、旧默认值和 `data/<id>`；新包 status 无法确认停用或配置不兼容时恢复旧包。launcher-v1 升级只替换导入包，部署由用户另行确认。

已有授权用户可同签覆盖安装新 APK，应用数据保留：

```text
adb install -r OWOrnate-v1.0.1-rc23.apk
```

仅执行 `adb install` 不建立或修复管理器的本机 root 授权。认证失效、更换电脑、恢复 root adbd 后，应重新运行完整 `00-Setup.cmd`。未知或混合的旧组件不自动覆盖；共享 Hook 是否升级以管理器显示的已知版本和用户操作确认为准。

## 启动恢复与确认

“服务已启动”表示守护进程已运行；只有服务确认当前 Launcher 的运行时已经就绪，管理器才显示“功能已就绪”。开机记录使用 `bootReady`，旧 `bootOk` 不作为就绪证据，手动恢复也不生成开机成功记录。`VerifyBoot` 同步检查新记录及实际运行状态；共享环境只检查既有 Hook/helper，不证明管理器自有组件开机恢复。

code31 / rc22 已在自有 v4 环境完成真实重启，首次打开管理器前取得当前 bootId 对应的就绪状态和实际运行映射。该历史证据沿用；code30 已验证的 v3→v4 迁移保留已部署载荷与启停选择，旧 v2 的真实迁移仍未验收。code32 尚未完成新一轮应用内更新或重启验收。

## 独立生命周期与移除

管理器基础环境与各个 Launcher 插件各自独立。第三方状态使用 `modules/<id>/installed` 和可选的 `disabled`；官方功能安装会写入 `/data/local/tmp/watchworkshop-runtime/modules/{monet,widgets,capture}` 安装标记；停用只改变对应启用设置，安装标记仍保留。在管理器内卸载功能不会删除本机授权、`frida-inject`、`manager-ready` 或设置快照。

需要显式移除管理器基础环境时，在完整 Windows 包目录运行：

```text
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\release-manage.ps1 -Action RemoveManager
```

RemoveManager 会拒绝任何仍存在的 `modules/monet`、`modules/widgets` 或 `modules/capture` 标记，也拒绝旧版 `installed` 独立莫奈标记。先回到管理器逐个卸载功能，再执行该命令。成功后只恢复首次设置保存的端口、无障碍和 doze 状态，删除本包的 `manager-ready`、`frida-inject` 与设置快照；共享 Hook、QQ 和未知文件保持不动。基础环境移除后才可在系统中卸载管理器 APP。

## 历史说明

code25 及更早版本使用 APK 内置的功能载荷；code26 起三个官方 launcher-v1 载荷改为用户导入的 `.owo`，code27 增加同 ID 升级和在线检查，code28 增加捕获画质声明，code29 增加莫奈配色/圆角声明，code30 开放第三方 launcher-v1 与 v4 运行环境，code31 修复插件错误输出详情并已作为 rc22 公开，code32 修复 A.92 安装来源授权页不可启动时的回退。旧版本安装步骤与验收记录保留在 [VALIDATION.md](VALIDATION.md) 和 [ACCEPTANCE.md](ACCEPTANCE.md)，不作为当前安装流程。
