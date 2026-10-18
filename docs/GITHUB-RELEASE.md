# GitHub 发布说明

当前预发布为 [v1.0.1-rc8 / code17](https://github.com/AUTOlightup/owornate/releases/tag/v1.0.1-rc8)。

## 版本内容

新增与莫奈取色、负一屏小组件并列的“截屏与录屏”模块，提供控制中心短按采集、录屏设置与停止、长按对应 OWOrnate 页面。系统原生 PNG 与无声 H.264/MP4，原分辨率、单段最多 180 秒、码率 0.6 / 1.2 / 2.4 Mbps；实际帧率随画面更新变化，不保证固定 60fps。独立编码 helper 延后。

同时包含 code16 的共享 Hook 识别及错误展示修复，以及 code17 的状态来源、缓存、读写边界整理。读取失败保留上次确认值并显示错误；打开管理器和读取状态不再隐式恢复组件或修改后台白名单。

首次安装与授权使用 Windows 包 `00-Setup.cmd`；已有授权用户同签覆盖 APK 后，从管理器模块页安装或重新接入。共享接入识别已知 Hook v9/v10/v11/v12 + helper v6，更新目标为 v12。详见 [INSTALL.md](INSTALL.md)。

## 附件

- `OWOrnate-v1.0.1-rc8.apk`：295,828 bytes，SHA256 `a2ed3957d5a384dbde7ebfa8c391f1216140788766039705360194da884c2d2f`。
- `OWOrnate-v1.0.1-rc8-Windows.zip`：首次安装、授权和电脑备用入口。
- `owornate-v1.0.1-rc8-source.zip`：管理器、共享 Hook、原生捕获脚本、运行 helper、测试与文档。
- `watch2-monet-v6-source.zip`：主题 helper v6 源码，主题运行件版本不变。
- `SHA256SUMS.txt`：上述四个文件的摘要。

发布由 `make-release.ps1` 按白名单导出，不包含本机 HANDOFF/AGENTS、签名材料、ADB 私钥、设备备份或个人日志。Windows PowerShell 脚本导出为 UTF-8 BOM；部署载荷保留原始字节，Git 换行规则由 `.gitattributes` 固定。

## 已验证与限制

code17 已同签装表，原表完成捕获模块启停、两个按钮与长按、表盘 PNG、手动停止和 180 秒自动结束；视频解码及服务/亮屏锁收尾通过。状态17、信息卡13、捕获14、控制中心6、小组件管理97、生命周期85项离线检查通过。

第三方应用动线、码率/高运动帧率比较、旧卡片完整交互、长期功耗、锁屏/MCU/轻智能及整机重启恢复仍待验收。静态画面输出帧少，MP4 播放时长可能短于实际录制墙钟时长。详见 [VALIDATION.md](VALIDATION.md) 和 [ACCEPTANCE.md](ACCEPTANCE.md)。
