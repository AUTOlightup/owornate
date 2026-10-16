package dev.codex.watchworkshop;

import android.app.Activity;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;

/** Ready-made wheel groups for dates (年/月/日) and times (时/分). */
final class Wheels {
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2099;

    private Wheels() { }

    interface DateListener {
        void onDate(LocalDate date);
    }

    interface TimeListener {
        void onTime(int hour, int minute);
    }

    private static String[] days(int length) {
        String[] out = new String[length];
        for (int i = 0; i < length; i++) out[i] = (i + 1) + "日";
        return out;
    }

    /** 年（到两端停住）、月和日（循环）三个滚轮；换月或换年时日列表自动收进当月天数。 */
    static void date(Activity activity, WorkshopUi ui, LocalDate initial, DateListener listener) {
        final WheelPicker year = new WheelPicker(activity, "年");
        final WheelPicker month = new WheelPicker(activity, "月");
        final WheelPicker day = new WheelPicker(activity, "日");
        String[] years = new String[MAX_YEAR - MIN_YEAR + 1];
        for (int i = 0; i < years.length; i++) years[i] = (MIN_YEAR + i) + "年";
        String[] months = new String[12];
        for (int i = 0; i < 12; i++) months[i] = (i + 1) + "月";
        int startYear = Math.max(MIN_YEAR, Math.min(MAX_YEAR, initial.getYear()));
        year.setItems(years, startYear - MIN_YEAR, false);
        month.setItems(months, initial.getMonthValue() - 1, true);
        day.setItems(days(YearMonth.of(startYear, initial.getMonthValue()).lengthOfMonth()),
                initial.getDayOfMonth() - 1, true);
        WheelPicker.Listener changed = index -> {
            int y = MIN_YEAR + year.getSelectedIndex();
            int m = month.getSelectedIndex() + 1;
            int length = YearMonth.of(y, m).lengthOfMonth();
            if (day.itemCount() != length) {
                day.setItems(days(length), Math.min(day.getSelectedIndex(), length - 1), true);
            }
            listener.onDate(LocalDate.of(y, m, day.getSelectedIndex() + 1));
        };
        year.setListener(changed);
        month.setListener(changed);
        day.setListener(changed);
        ui.wheels(year, month, day);
    }

    /** 时（0–23）和分（0–59）两个循环滚轮。 */
    static void time(Activity activity, WorkshopUi ui, int hour, int minute, TimeListener listener) {
        final WheelPicker hours = new WheelPicker(activity, "时");
        final WheelPicker minutes = new WheelPicker(activity, "分");
        String[] h = new String[24];
        for (int i = 0; i < 24; i++) h[i] = String.format(Locale.ROOT, "%02d时", i);
        String[] m = new String[60];
        for (int i = 0; i < 60; i++) m[i] = String.format(Locale.ROOT, "%02d分", i);
        hours.setItems(h, hour, true);
        minutes.setItems(m, minute, true);
        WheelPicker.Listener changed = index ->
                listener.onTime(hours.getSelectedIndex(), minutes.getSelectedIndex());
        hours.setListener(changed);
        minutes.setListener(changed);
        ui.wheels(hours, minutes);
    }
}
