package dev.codex.watchworkshop;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * 课表小组件：突出当前或下一节课。所有卡片共用一份课表（{@link TimetableStore}），
 * 渲染只读本地数据；刷新时刻由 {@link WidgetClock} 统一排闹钟。
 */
public final class TimetableWidgetProvider extends AppWidgetProvider {
    /** 内容宽度：181dp 卡片减左右各 12dp 内边距。 */
    private static final float CONTENT_DP = 157;
    /** 顶行除文字外的固定占用：状态胶囊左右各 7dp 内边距，再留约 4dp 与右侧时间隔开。 */
    private static final float LABEL_GAP_DP = 18;

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        updateAll(c);
        WidgetClock.schedule(c);
    }

    @Override public void onDeleted(Context c, int[] ids) {
        WidgetClock.schedule(c);
    }

    @Override public void onDisabled(Context c) {
        WidgetClock.schedule(c);
    }

    static int[] ids(Context c) {
        return AppWidgetManager.getInstance(c)
                .getAppWidgetIds(new ComponentName(c, TimetableWidgetProvider.class));
    }

    static void updateAll(Context c) {
        for (int id : ids(c)) AppWidgetManager.getInstance(c).updateAppWidget(id, render(c, id));
    }

    /** 下一次要重画的时刻：焦点课程变化的边界；上课中再加上下一个整分钟（“还有 N 分钟”）。所有卡片共用一份课表，只算一次。 */
    static long nextUpdate(Context c, long now) {
        if (ids(c).length == 0) return Long.MAX_VALUE;
        try {
            TimetableData data = TimetableStore.read(c);
            if (data == null) return Long.MAX_VALUE;
            TimetableFocus.State s = TimetableFocus.at(data, Instant.ofEpochMilli(now).atZone(TimetableStore.zone(c)));
            return s.ongoing ? Math.min(s.nextChange, (now / 60000 + 1) * 60000) : s.nextChange;
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** id 只决定点按打开哪张卡片；显示内容所有卡片相同。id 为 -1 时用于设置页预览。 */
    static RemoteViews render(Context c, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.timetable_widget);
        try {
            TimetableData data = TimetableStore.read(c);
            TimetableFocus.State s = TimetableFocus.at(data, ZonedDateTime.now(TimetableStore.zone(c)));
            if (s.focus == null) empty(v, s.label, s.footer);
            else bindFocus(c, v, s);
        } catch (Exception error) {
            empty(v, "暂时无法读取课表", "点按重新导入");
        }
        Intent edit = new Intent(c, TimetableSettingsActivity.class)
                .setData(Uri.parse("owornate://timetable/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        v.setOnClickPendingIntent(R.id.timetable_root, PendingIntent.getActivity(c, id, edit,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    /** 有焦点课程：底色和胶囊按“上课中 / 今天 / 之后”三档，课程名放不下时改用两行版式。 */
    private static void bindFocus(Context c, RemoteViews v, TimetableFocus.State s) {
        v.setViewVisibility(R.id.timetable_empty, View.GONE);
        v.setViewVisibility(R.id.timetable_content, View.VISIBLE);
        v.setInt(R.id.timetable_root, "setBackgroundResource", s.today
                ? (s.ongoing ? R.drawable.timetable_bg_current : R.drawable.timetable_bg_today)
                : R.drawable.timetable_bg_future);
        v.setTextViewText(R.id.timetable_label, s.label);
        v.setTextColor(R.id.timetable_label, s.today ? (s.ongoing ? 0xff06222f : 0xff8ad8fb) : 0xffcbc2fa);
        v.setInt(R.id.timetable_label, "setBackgroundResource", s.today
                ? (s.ongoing ? R.drawable.timetable_pill_current : R.drawable.timetable_pill_today)
                : R.drawable.timetable_pill_future);
        v.setTextViewText(R.id.timetable_time, fitTime(c, s));
        String name = s.focus.course.name;
        boolean longName = width(c, name, 20) > CONTENT_DP;
        v.setViewVisibility(R.id.timetable_name, longName ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.timetable_name_long, longName ? View.VISIBLE : View.GONE);
        v.setTextViewText(longName ? R.id.timetable_name_long : R.id.timetable_name, name);
        v.setViewVisibility(R.id.timetable_details, longName ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.timetable_details_long, longName ? View.VISIBLE : View.GONE);
        v.setTextViewText(longName ? R.id.timetable_details_long : R.id.timetable_details,
                details(s.focus.course));
        // 两行课程名时让出底部的分隔线和底行。
        v.setViewVisibility(R.id.timetable_line, longName ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.timetable_footer, longName ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.timetable_footer, s.footer);
    }

    private static void empty(RemoteViews v, String title, String hint) {
        v.setViewVisibility(R.id.timetable_content, View.GONE);
        v.setViewVisibility(R.id.timetable_empty, View.VISIBLE);
        v.setTextViewText(R.id.timetable_empty_title, title);
        v.setTextViewText(R.id.timetable_empty_hint, hint);
    }

    /** 文本在 sp 字号下的宽度（dp）。 */
    private static float width(Context c, String text, int sp) {
        Paint p = new Paint();
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(sp * c.getResources().getDisplayMetrics().scaledDensity);
        return p.measureText(text) / c.getResources().getDisplayMetrics().density;
    }

    /** 顶行状态胶囊（11sp）加右侧时间（12sp）是否放得下。 */
    private static boolean fits(Context c, String label, String time) {
        return width(c, label, 11) + width(c, time, 12) + LABEL_GAP_DP <= CONTENT_DP;
    }

    /** 右上角文字：上课中显示“下课时刻 · 还有 N 分钟”，宽度不够时依次去掉“下课”、再去掉时刻；其余状态沿用原文案。 */
    private static String fitTime(Context c, TimetableFocus.State s) {
        if (!s.ongoing || s.focus == null) {
            return fits(c, s.label, s.time) ? s.time : s.time.substring(0, 5);
        }
        long remaining = s.focus.end.toInstant().toEpochMilli() - System.currentTimeMillis();
        long minutes = Math.max(1, (remaining + 59999) / 60000);
        String left = minutes >= 60
                ? "还有 " + minutes / 60 + " 小时" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " 分钟")
                : "还有 " + minutes + " 分钟";
        String end = s.time.substring(0, 5);
        String[] options = {end + " 下课 · " + left, end + " · " + left, left};
        for (String option : options) if (fits(c, s.label, option)) return option;
        return options[options.length - 1];
    }

    /** 课程名下面的小字只留教师名称；没有教师时这一行留空。 */
    private static String details(TimetableData.Course course) {
        return course.teacher;
    }
}
