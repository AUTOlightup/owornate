# GitHub 发布说明

当前预发布为 [v1.0.1-rc9 / code18](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc9)。

新增快捷录屏首屏，并调整截屏、录屏保存目录。

- 新截图保存到 `Pictures/Screenshots`，录屏保存到 `Movies/Screenrecords`；旧版本文件保留原位，新文件继续加入系统媒体库。
- 未录制时直接显示轻量录屏页，后台确认真实会话，减少打开前的重复查询和等待；开始录屏仍在页面退出后执行。
- 三档画质横排、记住上次选择、开始按钮固定首屏底部。分辨率、格式、保存位置和时长收进“更多说明”，状态变化原地更新。

已有授权用户同签覆盖安装APK即可。已接入v12的共享Hook和helper v6保持原字节，本次更新无需因这些改动重新接入或重载Launcher。首次安装仍使用Windows包 `00-Setup.cmd`，再从管理器对应模块页接入。

本版已在OPPO Watch 2 / A.92同签装表，验证首屏、画质记忆、更多说明、返回后开始、录制中设置、手动停止、新目录和媒体库索引；19.03秒402×476 H.264样本解码通过，结束后服务及亮屏锁释放。原生捕获模拟14/14、控制中心桥接6/6和完整构建通过。180秒自动结束等其余完整验证属于code17历史证据，本版未整体重跑。

原生录屏仍为无声、单段最多180秒，实际帧率随画面更新变化，不提供固定60fps。三档画质与高运动帧率、第三方应用完整动线、长期功耗、MCU和整机重启恢复仍待验收。

附件：APK、Windows包、管理器源码、主题helper v6源码及SHA256SUMS。APK沿用已装表验证的原始签名字节，本次发布未重新编译。APK SHA256：`8409dc95a72a274e35aeff119e6b6d5c4e9e803d24493b3e5ab7c010738b0367`。

[安装说明](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc9/docs/INSTALL.md) · [验证范围](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc9/docs/VALIDATION.md) · [原生截屏录屏](https://github.com/AUTOlightup/owornate/blob/v1.0.1-rc9/docs/截屏录屏-原生版.md)
