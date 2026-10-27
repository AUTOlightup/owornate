# 支付宝碰一下 · OWOrnate 插件

**开发候选，暂勿用于外出或发布。** 桌面检查通过，但实机入口未出现、停用命令未响应。用户出门前要求暂停，候选已从手表移除；继续使用旧试验0.3。下文是目标用法，尚非实机验收结论。接续见工作区 `outputs/OWOrnate-支付宝插件-开发状态.md`。

`dev.owornate.alipay-tap`，0.1.0/code1，使用现有 `shell-v1` 协议。可由 OWOrnate code36 导入，不需要重新安装管理器。

## 使用

1. 将 `.owo` 放到手表 Download，进入 OWOrnate → 发现优化 → 本地插件 → 从下载目录导入，选择“支付宝碰一下”并确认信任。
2. 打开插件页右上开关；插件会打开原支付宝，再进入支付宝的“碰一下”。
3. 本次支付宝进程内有效。进程被系统结束或手表重启后，插件状态变为停用，下次从 OWOrnate 重新开启。
4. 停用通过进程内命令恢复原行为；卸载会解绑本插件的 Hook。账号与原支付宝数据保留。

支持 `OW20W1 / OW20W1_11_A.92 / API27 / armeabi-v7a` 和 OPPO 渠道支付宝 `10.6.41.260917 / 3152`，依赖基础环境已有的 Frida 16.7.19。先前“碰一下试验”若仍有正在运行的 Hook，需先在旧应用中停止试验，避免两个版本同时挂钩。

测试时收付款使用不同账号。此前试验版已由用户实测官方贴纸进入“输入金额”页；这不代表完整付款、带屏幕蓝环、息屏或轻智能模式已经验证。新插件的验证记录见交付目录。

## 实现范围

保留原试验中的旧型号排除修复、NFC 能力缓存刷新及扫描页厂商轮询控制。关闭时原方法透传，恢复能力缓存并暂停本插件恢复的轮询；卸载时解除自己的方法替换与 FileObserver。异常结束进程时的厂商轮询恢复另待实测。

不改机型、原 APK、签名、账号、支付请求或支付确认。不带旧试验的支付路由观测与历史日志。无常驻 shell、开机服务或 Launcher 变更；只在支付宝当前进程中保留事件驱动的脚本。进程活着时停用后保留可重新开启的命令监听，卸载才解除监听与方法替换。

状态绑定 boot ID、PID、进程启动时刻和随机会话标识；遇到注入未确认，界面显示错误并保留会话记录，避免重复注入。关闭支付宝再重新开启可建立新会话。

## 构建与验证

在工程根目录执行，输出文件必须不存在：

```powershell
./plugins/alipay-tap/build.ps1 -OutputFile 'C:/output/alipay-tap-0.1.0.owo'
./plugins/test-plugin.ps1 -PackageFile 'C:/output/alipay-tap-0.1.0.owo' -JdkHome '<JDK>' -JsonJar '<org.json jar>'
node ./tests/alipay_tap_hook_test.js
python ./tests/alipay_tap_controller_test.py --package 'C:/output/alipay-tap-0.1.0.owo' --bash '<Git Bash bash.exe>'
```

构建将 `control.sh` 与 `hook.js` 合成包内 `plugin.sh`，复用生产打包器。测试分别验证生产导入约束、Hook 方法行为和真实打包控制器的模拟生命周期；它们不能替代 Android/Frida 与真实贴纸测试。构建产生的 `alipay-package-*` 是可检查的中间源目录，不要放进插件源码目录。
