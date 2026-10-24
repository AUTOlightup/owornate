package dev.codex.watchworkshop;

import android.content.Intent;
import android.os.Bundle;

/** OWOrnate 截屏页，也是控制中心截屏按钮长按打开的页面。 */
public final class CaptureScreenshotActivity extends WatchActivity {
    /** 用户点了“返回后截屏”：等本窗口真正关掉（onDestroy）再截。 */
    private boolean pending;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        rebuild("screenshot", () -> {
            ui.header("截屏", false, this::onBackPressed, null);
            ui.note("OWOrnate · 截屏与录屏模块");
            ui.value("图片格式", "原屏 PNG");
            ui.value("保存位置", "Pictures/Screenshots");
            ui.note("短按控制中心截屏按钮，面板收起后截图；长按进入本页。");
            ui.button("返回后截屏", true, this::closeAndCapture);
            ui.row("模块管理", "安装、启用与停用", "layers",
                    () -> startActivity(new Intent(this, CaptureSetupActivity.class)));
        });
    }

    @Override protected void onDestroy() {
        boolean capture = pending && isFinishing();
        pending = false;
        if (capture) CaptureService.screenshotAfterWindowClosed(getApplicationContext());
        super.onDestroy();
    }

    private void closeAndCapture() {
        if (pending) return;
        pending = true;
        if (isTaskRoot()) finishAndRemoveTask();
        else finish();
        overridePendingTransition(0, 0);
    }
}
