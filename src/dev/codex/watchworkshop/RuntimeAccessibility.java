package dev.codex.watchworkshop;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * 系统级启动触发：服务连接时、以及 Launcher 有界面事件时（每分钟最多一次）请求一次启动恢复。
 * 不读取节点、文字或手势。原表上默认未启用。
 */
public final class RuntimeAccessibility extends AccessibilityService {
    private static final long THROTTLE_MS = 60000;
    private long last = -THROTTLE_MS;

    @Override protected void onServiceConnected() {
        last = SystemClock.elapsedRealtime();
        RuntimeSupport.ensureAsync(this, true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence source = event.getPackageName();
        if ("com.heytap.wearable.launcher".contentEquals(source == null ? "" : source)) arm();
    }

    @Override public void onInterrupt() {}

    private void arm() {
        long now = SystemClock.elapsedRealtime();
        if (now - last < THROTTLE_MS) return;
        last = now;
        RuntimeSupport.ensureAsync(this, false);
    }
}
