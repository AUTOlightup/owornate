package dev.codex.watchworkshop;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;

/** One alarm for the next actual date/class boundary across all cards. No repeating timer. */
public final class WidgetClock extends BroadcastReceiver {
    private static final String ACTION="dev.codex.watchworkshop.WIDGET_BOUNDARY";
    @Override public void onReceive(Context c,Intent intent){
        CountdownWidgetProvider.updateAll(c);TimetableWidgetProvider.updateAll(c);schedule(c);
        // 纪念日模式：换日后的第一次边界顺带读一次，漏掉的改动最晚在这里补上；课表的换课边界不读。
        AnniversarySync.daily(c);
    }
    static synchronized void schedule(Context c){
        long now=System.currentTimeMillis(),next=Math.min(CountdownWidgetProvider.nextUpdate(c,now),TimetableWidgetProvider.nextUpdate(c,now));
        PendingIntent pending=PendingIntent.getBroadcast(c,8201,new Intent(c,WidgetClock.class).setAction(ACTION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(pending);
        // 这台表对第三方唤醒闹钟会降级或拒绝，用普通闹钟；息屏期间到点的，亮屏后补送。
        if(next!=Long.MAX_VALUE)alarms.setExactAndAllowWhileIdle(AlarmManager.RTC,Math.max(now+1000,next),pending);
    }
}
