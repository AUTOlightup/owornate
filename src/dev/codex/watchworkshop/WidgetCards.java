package dev.codex.watchworkshop;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

/** Read-only view of the cards currently on the watch, named by what they show. */
final class WidgetCards {
    private WidgetCards() { }

    private static int[] ids(Context context, Class<?> provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return new int[0];
        int[] all = manager.getAppWidgetIds(new ComponentName(context, provider));
        if (all == null) return new int[0];
        int[] sorted = all.clone();
        Arrays.sort(sorted);
        return sorted;
    }

    static int infoCount(Context context) {
        return ids(context, OWOrnateWidgetProvider.class).length;
    }

    static int countdownCount(Context context) {
        return ids(context, CountdownWidgetProvider.class).length;
    }

    static int timetableCount(Context context) {
        return ids(context, TimetableWidgetProvider.class).length;
    }

    static int totalCount(Context context) {
        return infoCount(context) + countdownCount(context) + timetableCount(context);
    }

    /** "尚未添加" or "负一屏上 N 张". */
    static String countText(int count) {
        return count == 0 ? "尚未添加" : "负一屏上 " + count + " 张";
    }

    /** 信息卡片只有一份显示设置：一句话说明卡片会显示什么，再加上负一屏上的张数。 */
    static String infoSummary(Context context) {
        OWOrnateWidgetStore.Options options = OWOrnateWidgetStore.options(context);
        StringBuilder shown = new StringBuilder();
        if (options.showStatus) shown.append("状态 ");
        if (options.showTheme) shown.append("主题 ");
        if (options.showVersion) shown.append("版本 ");
        String what = shown.length() == 0 ? "不显示状态、主题和版本" : "显示 " + shown.toString().trim();
        return what + " · " + countText(infoCount(context));
    }

    /** 倒计时只有一份配置：标题加当前的剩余时间，没设置过就提示去设置；纪念日模式前面标上“纪念日”。 */
    static String countdownSummary(Context context) {
        String added = countText(countdownCount(context));
        CountdownStore.Source source = CountdownStore.source(context);
        if (source.anniversary) {
            try {
                CountdownModel.Frame frame = CountdownWidgetProvider.shown(context, source,
                        CountdownStore.config(context), System.currentTimeMillis()).frame;
                String state = frame.empty() ? frame.emptyMain
                        : (frame.title + " · " + (frame.prefix + " " + frame.value + frame.unit).trim());
                return "纪念日 · " + state + " · " + added;
            } catch (RuntimeException failure) {
                return "纪念日 · " + added;
            }
        }
        CountdownModel.Config config = CountdownStore.config(context);
        if (!config.configured()) return "尚未设置目标日期 · " + added;
        String title = CountdownModel.title(config.title);
        String state;
        try {
            CountdownModel.Frame frame = CountdownModel.frame(config, System.currentTimeMillis(), ZoneId.systemDefault());
            state = frame.chronometer ? frame.detail + " 到点"
                    : (frame.prefix + " " + frame.value + frame.unit).trim();
        } catch (RuntimeException failure) {
            state = config.target;
        }
        return (title.isEmpty() ? "倒计时" : title) + " · " + state + " · " + added;
    }
    /** One line for the shared timetable: import state plus how many cards show it. */
    static String timetableSummary(Context context) {
        String table;
        try {
            TimetableData data = TimetableStore.read(context);
            if (data == null) {
                table = "尚未导入课表";
            } else if (data.semesterStart == null) {
                table = "已导入，但缺少开学日期";
            } else {
                int week = data.week(LocalDate.now(TimetableStore.zone(context)));
                String progress = week < 1 ? "还没到第 1 周"
                        : data.totalWeeks > 0 && week > data.totalWeeks ? "课程已结束"
                        : "第 " + week + " 周";
                table = "已导入 " + data.courses.size() + " 门课程 · " + progress;
            }
        } catch (Exception failure) {
            table = "已保存的课表读不出来";
        }
        int cards = timetableCount(context);
        return table + " · " + (cards == 0 ? "尚未添加卡片" : "负一屏上 " + cards + " 张");
    }
}
