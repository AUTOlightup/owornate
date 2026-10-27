# 支付宝全局碰一下 · OWOrnate 插件

dev.owornate.alipay-global，0.1.0/code1，使用现有 shell-v1。已完成原表导入、接管、启停、配置、NFC 服务重启后冷启动及卸载重导入；随 OWOrnate 1.0.1-rc29 发布，安装与下载见 [发布页](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc29)。

## 使用

1. 先准备 OW20W1 / OW20W1_11_A.92 / API 27 / armv7 设备、root 权限，以及已部署的 Frida 16.7.19 注入运行环境。
2. 自行安装并登录原 OPPO 渠道支付宝 10.6.41.260917 / versionCode 3152。插件发布包不含原支付宝 APK，不会代为安装或升级支付宝。
3. 在 OWOrnate 的“发现优化 → 本地插件”导入 .owo，确认信任后打开插件页并启用。
4. 开启后，插件在真实 NFC 进程 com.android.nfc 中监听普通 NfcA 标签；匹配碰一下标签后拉起原 OPPO 渠道支付宝的碰一下入口，并请求 80ms 短震动。

唯一设置是 allow_screen_off，默认 true：

- 允许息屏：息屏时也参与标签发现。
- 仅亮屏：只在亮屏时处理。

## 环境与边界

- 原 OPPO 渠道支付宝 10.6.41.260917 / versionCode 3152、root 权限和 Frida 16.7.19 注入运行环境均需用户在设备上自行准备。插件不替换应用，也不修改账号、支付请求或支付确认。
- Hook 保留系统钱包和原有 SE/AID 路由，不启用 reader mode。钱包手动选中模拟卡时的信息读取与标签发现已共存验证；实际闸机通行仍未验收。
- 同一 NFC 进程会话内可接管和重新开启；每个 NFC PID 只注入一次。停用是惰性 Hook，不热卸载。手表开机或 NFC 进程重启后，需要从管理器手动重新开启。
- 已验收亮屏与真正息屏状态下的碰一下唤起、80ms 短震动，以及插件与钱包中一张模拟卡信息读取共存。
- 2026-10-27 22:23 UTC+8，用户反馈外出实际使用碰一碰付款成功。该结论基于用户当次反馈，未从设备日志或支付记录独立核验。
- 带屏蓝环、锁屏状态、轻智能模式、长期功耗和实际门禁闸机通行仍未验收。
- 源码和 .owo 只包含插件清单与生成脚本，不含原支付宝 APK、付款照片、私钥、支付 token、账号数据或日志。

## 构建

在工程根目录执行：

    ./plugins/alipay-global/build.ps1 -OutputFile 'C:/output/alipay-global-0.1.0.owo'

输出文件必须不存在。构建器读取控制器和 global-tap.js，生成含 emit_payload() 的 plugin.sh，再交给已有 pack-plugin.ps1。.owo 只包含 plugin.json 和 plugin.sh；plugin.sh 使用 UTF-8 无 BOM、LF。构建产生的 alipay-global-package-* 是检查用中间目录。

生成的 plugin.sh 顺序为：控制器内容、emit_payload()、root 检查和动作分发。分发把 status、enable、disable、configure、uninstall 分别执行同名控制器函数。control.sh 负责定义 fail、report 和这些函数，不包含顶层分发。

管理器还会导出 OW_PLUGIN_DATA 与 OW_SETTING_allow_screen_off（值为 true 或 false）。控制器成功结束时，最后一行必须返回 OWORNATE_PLUGIN_V1 enabled 或 OWORNATE_PLUGIN_V1 disabled，供管理器核对真实状态。

针对性控制器检查：`python tests/alipay_global_controller_test.py --package <.owo> --bash <Git-Bash>`；包内脚本实际在合成Android命令下执行，28项覆盖接管/生命周期/配置/重启及失败注入保护。

无需安装“碰一下试验”测试APK。2026-10-27已从原表移除该工具和两处旧试验运行目录，并验证OWOrnate独立启停。保留的是插件自身会话记录与共用Frida基础环境。
