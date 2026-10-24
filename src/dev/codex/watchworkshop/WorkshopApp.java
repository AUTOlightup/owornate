package dev.codex.watchworkshop;

import android.app.Application;

/**
 * 进程每次启动（打开管理器、点卡片、被闹钟拉起）都重画一次卡片并重排闹钟。
 * 手表系统可能整包强停本应用，强停会清掉全部闹钟；这里让任何一次启动都能自愈。
 * 倒计时在纪念日模式时，顺带登记亮屏检查并读一次纪念日。
 */
public final class WorkshopApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        bestEffort(() -> CountdownWidgetProvider.updateAll(this));
        bestEffort(() -> TimetableWidgetProvider.updateAll(this));
        bestEffort(() -> WidgetClock.schedule(this));
        bestEffort(() -> AnniversarySync.start(this));
    }

    /** 某一步失败不能影响其余步骤，也不能让进程启动失败。 */
    private static void bestEffort(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException ignored) {
            // 下次进程启动或下一个边界闹钟会再试。
        }
    }
}
