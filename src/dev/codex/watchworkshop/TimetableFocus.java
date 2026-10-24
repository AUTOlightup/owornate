package dev.codex.watchworkshop;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/** 纯日历计算：某天有哪些课，以及此刻卡片该突出哪一节。区间为 [start, end)，按课表时区计算。 */
final class TimetableFocus {
    static final class Lesson {
        final TimetableData.Course course;
        final ZonedDateTime start, end;
        final int week;

        Lesson(TimetableData.Course c, ZonedDateTime start, ZonedDateTime end, int week) {
            course = c;
            this.start = start;
            this.end = end;
            this.week = week;
        }
    }

    /** 卡片要显示的焦点课程与三段文字，以及下一次必须重画的时刻。 */
    static final class State {
        final Lesson focus;
        final boolean ongoing, today;
        final String label, time, footer;
        final long nextChange;

        State(Lesson focus, boolean ongoing, boolean today, String label, String time, String footer,
                long next) {
            this.focus = focus;
            this.ongoing = ongoing;
            this.today = today;
            this.label = label;
            this.time = time;
            this.footer = footer;
            nextChange = next;
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private TimetableFocus() {}

    /** date 当天的课，按开始时间（再按课名）排序；学期外、缺节次的课不出现。 */
    static List<Lesson> lessons(TimetableData data, LocalDate date, ZoneId zone) {
        ArrayList<Lesson> out = new ArrayList<>();
        if (data == null || data.semesterStart == null) return out;
        int week = data.week(date);
        if (week < 1 || week > data.totalWeeks) return out;
        HashMap<Integer, TimetableData.Slot> slots = new HashMap<>();
        for (TimetableData.Slot s : data.slotsFor(date)) slots.put(s.number, s);
        for (TimetableData.Course c : data.courses) {
            if (c.day != date.getDayOfWeek().getValue() || !c.weeks.contains(week)) continue;
            LocalTime start = c.customStart, end = c.customEnd;
            if (start == null) {
                TimetableData.Slot s = slots.get(c.startSection), e = slots.get(c.endSection);
                if (s == null || e == null) continue;
                start = s.start;
                end = e.end;
            }
            ZonedDateTime from = date.atTime(start).atZone(zone), to = date.atTime(end).atZone(zone);
            if (to.isAfter(from)) out.add(new Lesson(c, from, to, week));
        }
        Collections.sort(out, (a, b) -> {
            int c = a.start.compareTo(b.start);
            return c != 0 ? c : a.course.name.compareTo(b.course.name);
        });
        return out;
    }

    /** 从今天（开学前则从第 1 周）往后找第一节还没结束的课作为焦点。 */
    static State at(TimetableData data, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        ZoneId zone = now.getZone();
        long midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if (data == null || data.semesterStart == null) {
            return new State(null, false, false, "尚未导入课表", "", "点按导入课程", Long.MAX_VALUE);
        }
        LocalDate last = data.alignedStart().plusWeeks(data.totalWeeks).minusDays(1);
        LocalDate date = today.isBefore(data.alignedStart()) ? data.alignedStart() : today;
        for (; !date.isAfter(last); date = date.plusDays(1)) {
            List<Lesson> all = lessons(data, date, zone);
            for (int i = 0; i < all.size(); i++) {
                if (!all.get(i).end.isAfter(now)) continue;
                long next = Math.min(midnight, nextBoundary(all, now));
                return focusState(data, all, i, today, date, now, next);
            }
        }
        return new State(null, false, false, "本学期课程已结束", "", "点按导入新课表", midnight);
    }

    /** 当天之后最早的上课或下课时刻；重叠的课提前下课也要醒来，不只看焦点那一节。 */
    private static long nextBoundary(List<Lesson> day, ZonedDateTime now) {
        long next = Long.MAX_VALUE;
        for (Lesson lesson : day) {
            if (lesson.start.isAfter(now)) next = Math.min(next, lesson.start.toInstant().toEpochMilli());
            if (lesson.end.isAfter(now)) next = Math.min(next, lesson.end.toInstant().toEpochMilli());
        }
        return next;
    }

    private static State focusState(TimetableData data, List<Lesson> all, int index, LocalDate today,
            LocalDate date, ZonedDateTime now, long next) {
        Lesson focus = all.get(index);
        boolean same = date.equals(today);
        boolean ongoing = same && !focus.start.isAfter(now);
        String dayLabel = dayLabel(today, date);
        String label = ongoing ? "上课中" : same ? (index == 0 ? "今天 第一节" : "下一节") : dayLabel + " 第一节";
        String time = (ongoing ? focus.end : focus.start).format(TIME) + (ongoing ? " 下课" : " 上课");
        String footer = footer(data, all, index, today, date, ongoing, dayLabel);
        return new State(focus, ongoing, same, label, time, footer, next);
    }

    /** 卡片底行：同一天的下一节、距开学天数、单双周，或那天的课数与结束时间。 */
    private static String footer(TimetableData data, List<Lesson> all, int index, LocalDate today,
            LocalDate date, boolean ongoing, String dayLabel) {
        boolean same = date.equals(today);
        long ahead = ChronoUnit.DAYS.between(today, date);
        if (same && (ongoing || index > 0)) {
            if (index + 1 >= all.size()) return "今天最后一节";
            Lesson after = all.get(index + 1);
            return (ongoing ? "下一节 " : "之后 ") + after.start.format(TIME) + " " + after.course.name;
        }
        if (today.isBefore(data.semesterStart) && ahead > 7) {
            return "距开学 " + ChronoUnit.DAYS.between(today, data.semesterStart) + " 天";
        }
        if (!same && ahead > 1 && ChronoUnit.DAYS.between(monday(today), date) >= 7) {
            int week = all.get(index).week;
            return "第 " + week + " 周 · " + (week % 2 == 1 ? "单周" : "双周");
        }
        return (same ? "今天" : dayLabel) + "共 " + all.size() + " 节 · "
                + all.get(all.size() - 1).end.format(TIME) + " 结束";
    }

    /** 明天 / 周X / 下周X / M月D日。 */
    private static String dayLabel(LocalDate today, LocalDate date) {
        long days = ChronoUnit.DAYS.between(today, date);
        if (days == 1) return "明天";
        LocalDate monday = monday(today);
        int day = date.getDayOfWeek().getValue();
        String weekday = "一二三四五六日".substring(day - 1, day);
        if (date.isBefore(monday.plusWeeks(1))) return "周" + weekday;
        if (date.isBefore(monday.plusWeeks(2))) return "下周" + weekday;
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    private static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
