package dev.codex.watchworkshop;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 所有卡片共用一个闹钟：只排在下一个真实的日期或换课边界上，没有周期轮询。 */
public final class WidgetClock extends BroadcastReceiver {
    private static final String ACTION = "dev.codex.watchworkshop.WIDGET_BOUNDARY";
    private static final int REQUEST = 8201;

    @Override public void onReceive(Context context, Intent intent) {
        CountdownWidgetProvider.updateAll(context);
        TimetableWidgetProvider.updateAll(context);
        schedule(context);
        // 纪念日模式：换日后的第一次边界顺带读一次，漏掉的改动最晚在这里补上；课表的换课边界不读。
        AnniversarySync.daily(context);
    }

    static synchronized void schedule(Context context) {
        long now = System.currentTimeMillis();
        long next = Math.min(CountdownWidgetProvider.nextUpdate(context, now),
                TimetableWidgetProvider.nextUpdate(context, now));
        PendingIntent pending = PendingIntent.getBroadcast(context, REQUEST,
                new Intent(context, WidgetClock.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(pending);
        // 这台表对第三方唤醒闹钟会降级或拒绝，用普通闹钟；息屏期间到点的，亮屏后补送。
        if (next != Long.MAX_VALUE) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC, Math.max(now + 1000, next), pending);
        }
    }
}
