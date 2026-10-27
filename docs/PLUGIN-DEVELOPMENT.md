# 本地插件开发：shell-v1

第 3 步新增的 Launcher 载荷格式见 [Launcher 插件](LAUNCHER-PLUGINS.md)。本页的包大小、文件集合和动作表仅描述 shell-v1；两种格式使用同一导入器及管理框架。

code26 已通过 Download 导入、信任确认、启用、配置、停用恢复和卸载实机流程。code27 已装表，并通过同 ID 升级保留配置、状态校验失败回滚和更新页真实 GitHub 检查；公开源尚无清单，公网下载仍待后续。见 [更新协议](UPDATES.md)。旧 code25 不支持本地插件。安装含本功能的管理器后，新增符合协议的插件无需修改或重编译管理器。

## 导入与管理

1. 在“发现优化 → 本地插件”选择一个 .owo 文件。优先使用系统文件选择器；没有选择器的手表可将包放在 Download 目录，再选择“从下载目录导入”。只有后者会申请读取存储权限。
2. 管理器校验包并显示名称、版本、标识、描述和 root 权限说明。此时尚未执行脚本。
3. 点击“信任并导入”后注册插件，并执行一次 status。随后从页面右上开关启停，从设置行配置，从“管理与更新”刷新、检查插件包更新或卸载。导入不自动启用。
4. 首页列出插件名称；点击 shell-v1 插件直接进入声明式设置页，返回回到原入口。其设置、开关、错误反馈和管理操作共用 PluginActivity。官方 Launcher 功能从 code38 起直达自己的完整原生设置页，详见 [Launcher 插件](LAUNCHER-PLUGINS.md)。

code38 没有增加 .owo 内的 HTML、APK 或任意设置页载荷。包内自定义页面保留为长期方向，现行包结构及 root 执行边界保持。

插件是用户明确选择信任的 root 代码，包含 status 在内的脚本调用均有高级权限。管理器校验包结构和协议，不审计脚本行为，也不提供脚本沙箱或官方认证。包未确认导入前不会运行。

当前格式适配 OW20W1 / OW20W1_11_A.92 / API 27 / armeabi-v7a，运行前再次检查设备和 root。复用管理器已有的本机 ADB 认证，不导出供其他应用执行 shell 的入口。

## 包结构与清单

.owo 是 ZIP，根目录必须且只能有两个普通文件：

~~~text
plugin.json
plugin.sh
~~~

不带包裹文件夹、目录项或其他文件。包实际输入不超过 512KiB，解压总量不超过 256KiB，清单不超过 32KiB。两文件为 UTF-8；脚本必须无 BOM、使用 LF 换行，不得为空或含 NUL。重复文件、越界路径、截断包和额外文件被拒绝。

完整可复制的清单见 [示例 plugin.json](../plugins/examples/screen-timeout/plugin.json)。

| 字段 | 要求 |
| --- | --- |
| schemaVersion | 整数 1 |
| runtime | shell-v1 |
| permissions | 精确为 ["root"] |
| device | {"model":"OW20W1","firmware":"OW20W1_11_A.92","sdk":27,"abi":"armeabi-v7a"} |
| id | 小写反向域名风格，至少一个点，最多 80 字符；段内可含数字、下划线和连字符，首尾是小写字母或数字。例如 dev.example.my-plugin |
| name / description / versionName | 非空白字符串，分别最多 60 / 800 / 40 字符 |
| versionCode | 正整数；同 ID 升级要求严格增大 |
| updateUrl | 可选 HTTPS 更新清单或 GitHub releases API 地址；省略时使用管理器默认仓库，详见更新协议 |
| settings | 必须提供数组，可为空；最多 12 项 |

所有层级只接受协议中的字段。每项设置有 key、title、type、default、options：

~~~json
{
  "key": "timeout",
  "title": "熄屏时长",
  "type": "choice",
  "default": "30000",
  "options": [
    {"value": "15000", "label": "15 秒"},
    {"value": "30000", "label": "30 秒"}
  ]
}
~~~

只支持 choice。key 匹配 [a-z][a-z0-9_]{0,31}，不得重复；每项 2–12 个唯一选项，value 匹配 [A-Za-z0-9_.-]{1,64}；default 必须是其中一个值。缺少的配置键用默认值补齐，未知键和值会报错。没有 Java/HTML 自定义界面，也没有自由文本命令输入。

## 脚本接口

管理器以 sh plugin.sh ACTION 调用；脚本不依赖当前工作目录。环境变量为：

- OW_PLUGIN_DATA：管理器为此 ID 创建的数据目录。保存自己的恢复记录和状态文件。不要改写其中的 config.json、.config-* 和 .operation-lock，它们由管理器使用。
- OW_SETTING_key：清单中的每个配置值，例如 OW_SETTING_timeout。参数已经按清单校验并进行 shell 引用。

脚本必须短时执行并退出，不启动常驻子进程，不修改共享 Hook。管理器提供单次操作串行化，单条命令等待上限 20 秒。连接超时不保证系统副作用已撤销，也不保证子进程被杀死；返回界面后通过刷新重新确认。进程内与设备侧操作锁防止正常情况下重叠执行；异常强杀留下的设备侧锁不自动删除，需作者确认无运行进程后人工恢复。

[支付宝碰一下](../plugins/alipay-tap/README.md) 使用本协议按需加载独立应用进程内的 Hook：控制器短时退出，目标进程通过 FileObserver 接收启停。状态回执必须匹配当前进程与会话；停用恢复本插件的行为，卸载解绑自己安装的方法。它不属于 launcher-v1，不修改共享 Launcher，也不提供自动重新注入或开机恢复。插件包仍只有 plugin.json 与 plugin.sh 两个文件。

[支付宝全局碰一下](../plugins/alipay-global/README.md) 则封装已验证的 NFC 进程 R6，使用受权限保护的广播及逐次 requestId 确认启停，可接管原型的兼容会话。它仍使用 shell-v1，不修改管理器或共享 Launcher；停用后方法透传，惰性 Hook 随目标进程退出而移除，卸载保留防重复注入所需的会话标记。手表开机或 NFC 进程重启后需手动重新开启。用户需自行准备原 OPPO 渠道支付宝 3152、root 和 Frida 16.7.19 注入运行环境；插件发布包不含原支付宝 APK。2026-10-27 22:23 UTC+8 已有用户外出碰一碰付款成功的反馈，未从设备日志独立核验；带屏蓝环、锁屏、轻智能模式、长期功耗和实际门禁闸机仍未验收。声明式配置及打包控制器测试见该插件 README。

每次成功调用必须 exit 0，输出最后一行只能为以下之一：

~~~text
OWORNATE_PLUGIN_V1 enabled
OWORNATE_PLUGIN_V1 disabled
~~~

成功回执总长不超过 8192 字符。失败返回非零并输出简短原因；管理器显示错误，不将失败当作关闭。

| ACTION | 插件责任 | 管理器确认 |
| --- | --- | --- |
| status | 根据实际系统状态和自己保存的记录读取启停状态；不得改变功能状态 | 更新三态开关；错误显示未知 |
| enable | 初始化所需文件/设置并启用；重复调用不覆盖原始恢复记录 | 先读状态，执行后另读 status，两次结果都需 enabled |
| disable | 停用并恢复自己拥有的改动，保留用户配置；支持重复调用 | 执行后另读 status，两次结果都需 disabled |
| configure | 用新的 OW_SETTING_* 应用配置；已停用时保持停用 | 先读原启停，执行后核对启停未改变，成功才保存新配置 |
| uninstall | 完成清理，保留用户数据；之后 status 仍能报告 disabled | 先 disable 且确认其回执，再 uninstall 和 status；全部成功才删除安装包 |

没有 install 动作：导入只注册文件；真正初始化放在 enable 内。status 无法读取时总开关和设置不可写，仍允许用户确认卸载以尝试清理；卸载不依赖第一次 status 成功。清理失败保留脚本供排查，不提供跳过脚本的强制卸载。

脚本应将原值和变更归属记录在 OW_PLUGIN_DATA 中，不能只凭自己的 enabled 标记声称系统已生效。脚本作者负责副作用与失败恢复。配置写入失败或 configure 执行部分成功会显示错误，管理器不假定脚本具备通用回滚能力。再次打开或刷新时重新读取真实状态。

## 本地存储与版本

管理器私有 files/plugins 下面：

~~~text
pending/<token>/             尚未确认的包，不运行
installed/<id>/             已信任注册的 plugin.json 与 plugin.sh
data/<id>/config.json       管理器保存的配置
data/<id>/...               插件自己的状态与恢复记录
~~~

确认导入在同一文件系统原子移动到 installed。重启管理器从目录重建注册表，不依赖写死的插件列表。停用保留包和数据；卸载成功删除 installed/<id>，保留 data/<id>。

code27 支持相同 ID、相同 runtime、严格更大 versionCode 的兼容包升级。先停用，再导入并确认“信任并升级”；当前状态不可读或仍启用时拒绝替换。新包 status 必须确认停用，否则恢复旧包。保留配置、旧默认值和用户数据；已有设置键和值必须继续有效，新设置使用新默认值，不提供任意配置迁移。包回滚不撤销脚本自行写入的 root 数据。

管理页“检查插件更新”使用 updateUrl 或默认 GitHub 仓库，下载后仍进入信任确认页。HTTPS 与文件摘要用于传输校验，不等于作者认证或插件签名。具体清单与发布方式见 [更新协议](UPDATES.md)。

## 示例与打包

从零创建自己的插件请先看 [插件开发快速开始](PLUGIN-QUICKSTART.md)：包含创建工程、桌面校验、两版升级示例和独立更新源。以下协议说明供修改行为时查阅。

[熄屏时长示例](../plugins/examples/screen-timeout/plugin.sh) 通过 settings 修改 system.screen_off_timeout。首次启用保存原值，配置更新和重复启用不覆盖原值；停用/卸载时恢复原值。若该设置已被系统或用户另行更改，status 报错；卸载会放弃本插件的记录并保留外部修改。系统/OEM 电源策略是否遵循此设置仍需实机确认，本示例不是强制常亮工具。

在工程根目录执行（输出文件必须尚不存在）：

~~~powershell
pwsh -File ./plugins/pack-plugin.ps1 -PluginDirectory ./plugins/examples/screen-timeout -OutputFile ./screen-timeout.owo
~~~

打包脚本只收集两个协议文件。电脑端可运行 `plugins/test-plugin.ps1 -PackageFile '<包.owo>' -JdkHome '<JDK 11+>' -JsonJar '<org.json jar>'`，使用生产 PluginManifest/PluginRepository 校验结构、清单、编码与大小；不会执行脚本，也不代表设备兼容性验收。管理器导入时仍会校验。修改 id、名称、设置和 plugin.sh 就可以开发自己的插件；示例包没有编进 APK 的 assets。

## 源码接入点与本机验证

- PluginManifest：协议解析、设置约束。
- PluginRepository：导入暂存、注册表、配置、移除包。
- PluginRunner：固定动作、shell 引用、状态回执和操作后确认。
- ExternalPlugins：接入已有 RootShell 和 RuntimeSupport.LOCK。
- PluginActivity：文件选择、信任导入、统一管理 UI 与声明式设置。
- MainActivity：动态列出已导入插件和入口。

复跑测试：

~~~powershell
pwsh -File ./tests/run-plugin-repository-tests.ps1 -JdkHome '<JDK>' -JsonJar '<org.json jar>' -BuildRoot '<仓库测试输出>'
pwsh -File ./tests/run-plugin-runner-tests.ps1 -JdkHome '<JDK>' -JsonJar '<org.json jar>' -Bash '<Git Bash bash.exe>' -BuildRoot '<脚本测试输出>'
pwsh -File ./tests/run-management-tests.ps1 -JdkHome '<JDK>' -BuildRoot '<管理页测试输出>'
~~~

运行测试会打包真实示例、使用生产解析/注册/执行代码，并以本机 fake id/getprop/settings 执行真实脚本，不访问 ADB、系统 Android 设置或手表。Java 编译和这些模拟不能代替 Android 文件选择、权限请求、小屏 UI、root 环境及实机启停恢复验收。

历史第 2 步保留现有三模块载荷。第 3 步已将自有 Launcher 功能载荷拆为独立包，详见 [Launcher 插件](LAUNCHER-PLUGINS.md)；code27 接入的升级与在线检查见 [更新协议](UPDATES.md)。
