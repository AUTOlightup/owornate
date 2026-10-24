# Launcher 插件与第三方运行插件

## code30 / 1.0.1-rc21：开放第三方 launcher-v1

code30 起，launcher-v1 除保留莫奈、小组件、捕获三个官方角色外，也接受普通的第三方 Launcher Hook。第三方 ID 使用与 shell-v1 相同的 safeID 规则：最多 80 字符的小写反向域名，并拒绝短名 monet、widgets、capture 以及 dev.owornate.monet、dev.owornate.widgets、dev.owornate.capture 及内部状态名 monet.disabled、widgets.disabled、capture.disabled。官方三包的 ID、清单语义、运行接口及文件集合保持原契约；只有 dev.owornate.monet 可以携带 theme.jar。

第三方包只含 plugin.json 与 hook.js。settings 必须为空数组，且不得声明 nativeSettings。code30 没有开放自定义 Activity、任意原生设置页或通用配置 SDK；需要普通 root 命令和声明式配置时继续使用 shell-v1。

code30引入本节接口；当前管理器code31 / 1.0.1-rc22修复无行尾换行JSON的ADB解析，插件初始化错误可显示ID和详情。原无线表已完成v3→v4迁移、第三方升级/启停/卸载重装、常亮效果、失败插件恢复、v4真实重启与共享环境限制验收，测试后原环境与数据已恢复。共享环境下经真实界面确认卸载，仅移除第三方导入包，原插件仓库、共享组件和Launcher进程保持不变。三个官方Hook/JAR及全部code30运行assets未变。code31发布材料按本版源码另行导出，尚未上传GitHub。

### Hook API

hook.js 的内容直接作为 factory 的函数体。加载时由管理器补出 factory 并登记：

~~~javascript
Java.perform(function () {
  __watchWorkshop.register(id, factory);
});
~~~

作者文件不再自行包裹 Java.perform，也不重复写插件 ID。factory 的参数 plugin 提供：

- plugin.id：当前插件 ID，只读。
- plugin.context：Launcher 的 Android Application 对象。
- plugin.report(error)：异步回调、计时器或以后发生的错误通过它报告。

factory 已运行在 Java.perform 内，可以直接调用 Java.use。初始化期间的同步异常由公共核心捕获，并连同插件 ID 显示；作者自行捕获同步错误后应调用 plugin.report(error)，再按原有语义处理。异步错误不会由注册调用本身捕获，必须调用 plugin.report(error)。

多个插件可能修改同一个 Java 方法。Hook 冲突及调用链的保留由作者处理；code30 不增加猜测式的仲裁层、优先级或自动恢复。停用仍通过原 Launcher 重载撤去已注入代码，不建立常驻 daemon 或独立停止接口。

### 示例：桌面调试常亮

实际示例位于 plugins/examples/launcher-keep-awake，ID 为 dev.example.launcher-keep-awake，名称为“桌面调试常亮”。1.0.1 / versionCode 2 改为直接 hook com.heytap.wearable.launcher.Launcher 的 onResume 与 onPause；1.0.0 的通用 Activity Hook 在 A.92 实机没有产生窗口效果，不能作为可用示例。

1. 先调用原生命周期方法。
2. 仅当 getPackageName() 为 com.heytap.wearable.launcher 时处理窗口。
3. onResume 对窗口调用 addFlags(128)，onPause 对窗口调用 clearFlags(128)。
4. 原生命周期方法的异常上报后继续抛出；插件自身的窗口操作失败只上报，不中断原生命周期。

示例不修改全局 Settings，不启动 daemon，不触碰 QQ。返回桌面活动时添加窗口常亮，离开桌面活动时移除；停用后由原 Launcher 重载撤去代码。窄Java mock验证调用顺序、包名过滤、128标志与错误上报；1.0.1无trace示例已另在A.92实机验证超过10秒熄屏仍常亮、停用后恢复睡眠，v4重启恢复在code31补验。

### 创建、打包与校验

默认 new-plugin.ps1 仍创建 shell-v1 熄屏示例。选择 Launcher 模板时使用：

~~~powershell
./plugins/new-plugin.ps1 -PluginDirectory 'work/my-launcher-plugin' -Id 'dev.example.my-launcher-plugin' -Name '我的桌面 Hook' -Runtime 'launcher-v1'
~~~

生成目录包含 plugin.json 和 hook.js，版本为 1.0.0 / 1，设置数组为空；修改 Hook 后再打包。输出文件必须尚不存在。

~~~powershell
./plugins/pack-plugin.ps1 -PluginDirectory 'work/my-launcher-plugin' -OutputFile 'work/my-launcher-plugin-1.0.0.owo'
~~~

打包工具对第三方 launcher-v1 只接受 plugin.json、hook.js 两个顶层文件；额外文件、目录和 theme.jar 均拒绝。hook.js 必须为非空 UTF-8 无 BOM、LF、无 NUL，打包不改变 Hook 原字节。PowerShell 5.1 中文工具在发布包中可按构建规则使用 BOM，但不能把该规则套到 Hook 或修改现有官方 Hook 的字节。

在含 code30 生产解析器的源码树上校验实际包：

~~~powershell
./plugins/test-plugin.ps1 -PackageFile 'work/my-launcher-plugin-1.0.0.owo' -JdkHome 'C:/path/to/jdk' -JsonJar 'C:/path/to/json-20240303.jar' -BuildRoot 'work/my-launcher-check-v1'
~~~

该校验读取生产清单和 ZIP 约束，不执行 Hook。打包成功、mock 测试或校验通过都不能替代真实 Launcher 行为验证。

### 导入

把 .owo 放进手表 Download 或通过管理器选取，阅读权限后显式信任。导入只保存清单和 Hook，要求完整文件集合与合法 safeID；第三方 launcher-v1 的 nativeSettings 会被拒绝。导入本身不部署代码、不重载 Launcher。

### 部署

第三方 Hook 只部署到管理器自有运行环境。该环境升级为 version 4；已有 version 3 可以迁移，并继续使用同一 supervisor 与同一次注入。启动时多个选中插件的 factory 共用公共运行时，不建立每插件一个注入进程。

检测到共享 WatchHide 环境时，保留原有共享 Hook 和安全边界，不能同时部署新的第三方 launcher-v1 Hook，也不能借此创建第二套 supervisor 或注入。导入包可以保留供以后迁移；共享组件与用户数据不能被覆盖。

### 错误

- factory 初始化同步失败：公共核心捕获并在状态中带上插件 ID。
- Hook 后续异步失败：作者调用 plugin.report(error)，错误同样按插件 ID 归属。
- 清单、ID、设置或文件集合错误在导入时拒绝。导入不做 JavaScript 语法编译；Hook 语法错误在 Frida 载入时失败，部署不能显示就绪，可检查运行目录的 inject.log。
- 同方法冲突：由插件作者保留所需调用链并协商处理，运行时不做自动仲裁。

导入代码仍是用户明确信任的进程内代码，不是沙箱。错误展示用于定位，不提供安全隔离。

### 升级与停用

同一 ID、同一 launcher-v1 协议可以使用更高 versionCode 导入升级。包升级、载荷部署和启用是三个独立步骤；新 Hook 只有在重新部署并通过原 Launcher 重载后才在进程中生效。已部署旧版仍按当前负载运行，不能把“导入成功”显示成“新 Hook 已生效”。

停用第三方 Hook 依赖原 Launcher 重载撤去注入；不要在 Hook 内常驻修改系统值。导入包升级失败会保留原导入包及配置，不改变已部署载荷。部署时若 Hook 启动失败，载荷可能已经保存；页面显示“启动未确认”和运行记录，保留导入包供更新、停用或卸载，不宣称自动回滚到旧运行载荷。

## 历史：code29 独立部署补验


2026-10-24补验：code29在已授权的原生产表临时独立环境完成Windows Setup、三插件信任导入、实际安装/右上开关启停/逐项卸载/重装与真实重启恢复。重启后在未打开管理器、无电脑手动ensure的情况下取得当前bootId、bootReady与三项实际就绪。测试后原共享环境与用户数据恢复；已有root和Launcher数据保留，不等同于另一台全新设备，旧v2迁移仍待实测。下方旧版本未验收描述为当时历史，其中“Launcher 只支持三个固定角色”是 code30 之前的契约。

## code29：莫奈配色方式与圆角声明化

`1.0.1-rc20 / code29` 支持莫奈包 `1.1.0 / versionCode 2` 的 `nativeSettings`。统一插件页直接展示配色方式和圆角；专用主题页使用同一声明的标题、选项文案和配色选项顺序，保留圆角开关、固定颜色选择、预览和恢复流程。

莫奈声明必须恰好包含 `mode` 和 `corners` 两个 `choice`，可以调整排列顺序、标题、选项标签及合法 default。`mode` 必须完整保留 `auto`、`fixed`、`original`；`corners` 必须完整保留字符串 `true`、`false`。完整示例见源码 `plugins/launcher/monet/plugin.json`。未声明字段的旧包与没有导入包的既有环境继续使用原文案。

莫奈的 default 仅是声明元数据，**不覆盖实际主题，也不会在导入、升级或移除包时应用**。页面从已启用的 Launcher 读取实际值；用户选择后，管理器通过原 helper v6 / protocol 1 通道应用，收到回执后才显示成功。每次修改都先重新读取实际主题，仅替换所选字段，保留种子色与另一设置。仍用原 `workshop` 偏好保存已确认状态，不另建插件配置副本。状态未知、未部署或停用时，设置不可编辑，但组件管理入口保留；应用失败显示未确认，刷新回读后再操作。

先同签升级管理器到 code29，再导入莫奈 1.1.0；code28 只接受捕获的 nativeSettings，会拒绝新版莫奈包。新版莫奈仅改变清单，Hook/theme.jar 不变，无需因这些声明重新部署载荷。已启用的共享莫奈也可通过统一页修改主题；共享组件的安装、移除限制保持原样。2026-10-24，code29与莫奈/捕获1.1.0已在原无线共享表通过实际页面、触控、配色/圆角及画质双向同步验收；原设置与共享组件保留。全新环境、自有v3生命周期、重启和实际录制仍待后续。

## code28：捕获插件声明画质设置

`1.0.1-rc19 / code28` 开始支持捕获包中的可选 `nativeSettings`。捕获包 `1.1.0 / versionCode 2` 把画质标题、选项文案、排列顺序及默认值放入 `.owo`；统一插件管理页和原录屏页都读取这份声明。只改这些声明即可独立更新插件包，无需再次修改管理器。

```json
"nativeSettings": [{
  "key": "bitrate", "title": "录屏画质", "type": "choice",
  "default": "1200000",
  "options": [
    {"value": "600000", "label": "低"},
    {"value": "1200000", "label": "标准"},
    {"value": "2400000", "label": "高"}
  ]
}]
```

捕获包的该字段必须是一个 `bitrate` choice，完整保留三个原有码率值；默认值必须属于选项。标题和选项标签沿用 choice 字符串规则，建议简短以适配圆屏。不能借此新增任意设置键、改变编码器支持范围或注入原生页面。`settings` 仍须为空；shell-v1 继续使用自己的 `settings` 与脚本 configure 协议。code29 增加了上方莫奈契约，小组件仍不接受 nativeSettings。

画质的唯一保存位置仍是原有 `capture_options/bitrate`，插件目录不另存一份画质配置。原生页和插件页互相可见，修改只影响下一段录屏。原录屏页保留开始/返回、状态确认及退出窗口后延迟开始的流程；管理器依然提供采集服务。包升级前会保留已有设置及旧的有效默认值，移除包也保留画质。未导入包或旧包没有声明时继续使用原三档界面和标准默认值；已损坏的导入包会报错，不静默视为旧包。

先升级管理器到 code28，再导入新版捕获包；code27 及更早会拒绝带 `nativeSettings` 的包。普通导入、包更新与共享/自有运行载荷部署仍分开；画质声明导入后可用，不触发安装、启停、重载或当前录制操作。新版包的 Hook 字节保持不变。

本轮是原生设置声明化的第一处实际接入。莫奈、小组件的复杂设置仍由现有页面提供；三个 Launcher 角色仍固定，采集服务没有迁出 APK。下方记录载荷拆分及历史实机范围；code28 的桌面验证不能当作新界面实机验收。

## 历史：code29 及更早的载荷拆分与使用

以下至文末保留第 3 步到 code29 的实现和验证记录。code30 的现行契约以上方为准，尤其是第三方 safeID、仅两文件、version 4 与 shared 环境限制；旧段落中的“三个角色固定”属于历史。

本阶段将莫奈、小组件、捕获的自有 Launcher 运行载荷拆为三个独立 .owo 包。管理器 APK 提供公共协调器、supervisor、生命周期与现有原生设置/采集服务；功能 Hook 和莫奈 theme.jar 由用户导入，不再由 APK 中的合成 Hook 回退提供。

2026-10-22：已构建并同签装表 code26，包含第 1–3 步。三个包的导入及共享状态展示已实测；系统选择器选取返回、捕获设置入口、共享环境移除导入包/重新导入和首页合并也已通过。自有 v3 生命周期仍待实机。旧 code25 不支持这些包。完整边界见发布包 VALIDATION.md。

## 使用流程

1. 将所需 .owo 包放到手表 Download 目录，或通过系统文件选择器选取。
2. “发现优化 → 本地插件 → 导入”，阅读插件权限后确认信任。导入只保存载荷并读取当前部署状态，不会自动安装或启用 Hook。
3. 插件管理页显示导入版本、当前功能状态、统一右上开关和管理操作。点击安装部署到管理器自有运行环境；点击“打开功能设置”进入既有莫奈、小组件或采集设置页。
4. 主页将对应功能与导入包合并展示，避免同一功能重复计数。没有导入包时，原功能入口仍可读取和管理已经部署的组件。

两层状态分开：导入包保存在管理器私有目录，运行载荷部署在 watchworkshop-runtime。普通功能页卸载运行组件会保留导入包以便重装；本地插件页卸载会先清理自有运行组件，再移除导入包。两条路径均保留用户配置、卡片、主题和媒体。

## 包与兼容契约

同一个导入器支持 shell-v1 与 launcher-v1；shell-v1 保持第 2 步格式与行为。launcher-v1 的 schemaVersion 为 1，设备和 root 权限与 shell-v1 相同，settings 必须为空；code28 的捕获、code29 的莫奈可另用上方 nativeSettings 声明各自支持的原生设置。

| ID | 包内文件 | 管理器提供 |
| --- | --- | --- |
| dev.owornate.monet | plugin.json、hook.js、theme.jar | 主题设置与现有 helper v6 / protocol 1 调用 |
| dev.owornate.widgets | plugin.json、hook.js | AppWidget 配置、信息/课表/倒计时数据、系统编辑器入口 |
| dev.owornate.capture | plugin.json、hook.js | 原生截图/录屏服务、声明式画质绑定、权限约束和媒体保存 |

这三个 ID 为 launcher-v1 保留，shell-v1 不可占用。当前 Launcher 协议只支持这三个角色；它不是任意新增原生 Activity、采集服务或 Hook 角色的 SDK。一般自开发高级权限功能仍可使用 shell-v1。更改现有角色的插件实现可独立打包，但必须保持其管理器接口；破坏 helper/服务协议会导致运行错误。

包必须精确包含对应文件集合，不带目录、额外脚本或其他文件。launcher-v1 的压缩输入和总展开大小分别最多 2MiB，清单最多 32KiB。hook.js 必须非空、UTF-8 无 BOM、LF、无 NUL；theme.jar 为非空二进制载荷。包结构校验不等于代码审计、签名或沙箱，导入的 Hook/JAR 是用户明确信任的代码。

Hook 使用现有 Frida Java API，与公共协调器共处同一脚本环境：

~~~javascript
Java.perform(function () {
  const runtime = globalThis.__watchWorkshop;
  if (!runtime || !runtime.enabled('widgets')) return;
  try {
    // 为该角色登记 Hook。保留原生调用，初始化失败必须报告。
  } catch (error) {
    runtime.report('widgets', error);
  }
});
~~~

enabled 接受 monet/widgets/capture。report 将错误放入当前 PID 的就绪标记。commit 由公共尾部统一调用，插件不应自行提交成功或修改其他角色的开关。所有功能按 core → monet → widgets → capture → commit 组合；停用后通过已有 Launcher 重载流程撤去 Hook。此处仍是单个 supervisor、一次注入，不为每个插件创建注入进程。

## 自有运行环境迁移

新部署目录版本为 3，运行状态标记仍沿用 version 2 的 PID、错误和三个启停字段，兼容现有启动确认和状态读取。

- 全新基础环境只安装用户选中的插件，缺包会报错，不从旧 APK 合成 Hook 补齐。
- 已知 code24/code25 自有 v2 或旧独立莫奈可迁移。必须先导入所有原已安装功能对应的包，包含已停用的功能；缺包时保留旧环境，避免迁移顺便丢功能。
- 已部署 v3 的其他角色载荷从运行目录保留，目标更新只读取目标导入包。启停和卸载使用已部署载荷，不要求再次导入包。
- 归属判断继续拒绝未知旧配对、混合文件与外来注入。新 v3 用固定公共核心/服务摘要及固定文件名的部署摘要记录检查一致性。这些记录是完整性与归属依据，不是作者签名。
- 主题配置、模块启停、卡片与媒体沿用既有存储。最后一个自有功能移除后保留管理器基础环境。

共享 WatchHide 环境继续用原来的兼容路径，导入 launcher-v1 不会替换共享 Hook，也不会创建第二套运行时。其本地包管理页显示导入载荷尚未部署，组件管理只允许读取和移除导入包；已接入的画质和主题声明式设置仍可使用，其他共享功能从“打开功能设置”进入既有入口管理。不能把共享环境的覆盖安装视为自有 v3 验收。

## 源码与打包

独立源文件位于 plugins/launcher/monet、widgets、capture；之后修改这些文件即可更新对应包，不需重新组合管理器 APK。standalone/runtime-core.js 和 runtime-commit.js 是管理器公共核心。旧 runtime-base.js、runtime-hook.js 与旧 build-runtime.ps1 保留为历史兼容材料，已退出新 APK 的自有载荷构建路径。

打包三个包：

~~~powershell
pwsh -File ./plugins/pack-launcher-plugins.ps1 -OutputDirectory '<新的输出目录>'
~~~

也可用 plugins/pack-plugin.ps1 单独打包一个目录。code27 支持同 ID、更大 versionCode 的兼容包升级，并提供“检查插件更新”入口；见 [更新协议](UPDATES.md)。升级只替换导入包，不调用原生安装/启停流程，不修改已部署载荷。管理页“更新组件”用于将当前导入载荷部署或迁移到自有运行环境，下载或升级导入包不自动执行此操作。共享环境继续保留共享组件。

make-release.ps1 在 Windows 包 payload/plugins 中生成三个包，并将开发文档和独立源文件放入源码包；code27 起还会在发布目录顶层生成三个 .owo 与 owornate-updates.json 附件。code26 的 Launcher 导入流程已实机；code27 的 shell-v1 升级/回滚已实机，launcher-v1 包升级目前只有本机验证。全新环境 Setup 与自有 v3 生命周期仍待实机。

## 验证与边界

验证覆盖实际 ZIP 解析/注册、原 Manager 调用适配、拆分 Hook 初始化与组合顺序、真实生命周期脚本在 fake Android 环境中的运行，以及旧 shell-v1 回归。具体结果见发布包 VALIDATION.md。

本机模拟不会验证 Frida 在真实 Launcher 上的行为、Android 文件选择/授权、右上开关触控、重载后的真实状态、实际采集或重启恢复。后续装表应按当前用户安排先重新识别设备，保留生产共享组件和数据。
