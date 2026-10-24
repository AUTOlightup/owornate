package dev.codex.watchworkshop;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 课表页面上的文字：星期、日期、节次、周次和条目摘要。纯 Java，不依赖 Android。 */
final class TimetableText {
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private TimetableText() {}

    /** “周一”…“周日”。 */
    static String weekday(DayOfWeek day) {
        return WEEKDAYS[day.getValue() - 1];
    }

    /** “3月9日”。 */
    static String monthDay(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    static String sectionText(TimetableData.Course course) {
        if (course.customStart != null) return "自定义时段";
        if (course.startSection == 0 && course.endSection == 0) return "未指定节次";
        return course.startSection == course.endSection
                ? "第 " + course.startSection + " 节"
                : "第 " + course.startSection + "–" + course.endSection + " 节";
    }

    /** 课程卡片的第三行：节次、地点、教师，缺哪项就略过哪项。 */
    static String lessonDetail(TimetableData.Course course) {
        StringBuilder out = new StringBuilder();
        if (course.customStart == null && course.startSection > 0) out.append(sectionText(course));
        if (!course.position.isEmpty()) out.append(out.length() == 0 ? "" : " · ").append(course.position);
        if (!course.teacher.isEmpty()) out.append(out.length() == 0 ? "" : " · ").append(course.teacher);
        return out.toString();
    }

    /** 导入预览的条目：周一 第 1–2 节 · 课程 · 地点 · 老师 · 周次。 */
    static String entry(TimetableData.Course course) {
        int day = Math.max(1, Math.min(7, course.day));
        StringBuilder line = new StringBuilder(WEEKDAYS[day - 1])
                .append(' ').append(sectionText(course))
                .append(" · ").append(course.name.isEmpty() ? "未命名课程" : course.name);
        if (!course.position.isEmpty()) line.append(" · ").append(course.position);
        if (!course.teacher.isEmpty()) line.append(" · ").append(course.teacher);
        line.append(" · ").append(weeks(course.weeks));
        return line.toString();
    }

    /** 把周次折成 1-5,7,9-16 这样的一段文字，过长时截断显示。 */
    static String weeks(Set<Integer> weeks) {
        if (weeks.isEmpty()) return "未指定周次";
        List<Integer> sorted = new ArrayList<>(weeks);
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < sorted.size()) {
            int from = sorted.get(index);
            int to = from;
            while (index + 1 < sorted.size() && sorted.get(index + 1) == to + 1) {
                to = sorted.get(++index);
            }
            if (out.length() > 0) out.append(',');
            out.append(from == to ? String.valueOf(from) : from + "-" + to);
            index++;
        }
        String text = out.append(" 周").toString();
        return text.length() > 30 ? text.substring(0, 29) + "…" : text;
    }

    /** 那天没有课时说明原因：没开学、已结束，还是当天确实没排课。 */
    static String emptyNote(TimetableData data, LocalDate date, boolean isToday) {
        int week = data.week(date);
        if (week < 1) return "还没到第 1 周：第 1 周从 " + data.alignedStart() + " 开始。";
        if (data.totalWeeks > 0 && week > data.totalWeeks) return "本学期课程已经结束。";
        return isToday ? "今天没有课。" : "这天没有课。";
    }

    /** 总览页翻页条的标题：开学前 / 学期结束后 / 第 N 周。 */
    static String weekTitle(TimetableData data, int week) {
        if (week < 1) return "开学前";
        if (data.totalWeeks > 0 && week > data.totalWeeks) return "学期结束后";
        return "第 " + week + " 周";
    }
}
