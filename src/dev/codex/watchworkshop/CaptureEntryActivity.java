package dev.codex.watchworkshop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * 控制中心按钮的受保护入口（只有持 STATUS_BAR 权限的 Launcher 能打开）。四种固定动作：
 * screenshot、record、screenshot-settings、record-settings。所有 root 操作都在私有的
 * {@link CaptureService} 里完成；本页不显示界面，立即结束。
 */
public final class CaptureEntryActivity extends Activity {
    private boolean pendingCapture;

    /** 从控制中心直接打开的页面：独立任务、不进最近任务、不播放切换动画。 */
    static Intent quickPage(Context context, Class<?> target) {
        return new Intent(context, target).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent().getStringExtra("action");
        if (action == null) action = "";
        switch (action) {
            case "record":
                record();
                break;
            case "screenshot":
                // 等本窗口真正关掉（onDestroy）再截，画面里不会留下控制中心或本页。
                pendingCapture = true;
                break;
            case "screenshot-settings":
                startActivity(quickPage(this, CaptureScreenshotActivity.class));
                break;
            case "record-settings":
                startActivity(quickPage(this, CaptureOptionsActivity.class));
                break;
            default:
                break;
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        boolean capture = pendingCapture && isFinishing();
        pendingCapture = false;
        if (capture) CaptureService.screenshotAfterWindowClosed(getApplicationContext());
        super.onDestroy();
    }

    /**
     * 录屏按钮：图标投影显示正在录制就交给服务去停止，否则打开录屏设置页。投影只决定入口，
     * 不作为开始或停止的依据——服务和设置页都会先向 root worker 确认真实会话。
     */
    private void record() {
        String phase = Settings.System.getString(getContentResolver(), "codex_capture_phase");
        if (CaptureManager.activePhase(phase)) CaptureService.request(this, "record");
        else startActivity(quickPage(this, CaptureOptionsActivity.class));
    }
}
