package dev.codex.watchworkshop;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 规范化后的拾光单表课表：星期按 ISO（1=周一），周次是显式集合。 */
final class TimetableData {
    static final class Slot {
        final int number;
        final LocalTime start, end;

        Slot(int number, LocalTime start, LocalTime end) {
            this.number = number;
            this.start = start;
            this.end = end;
        }
    }

    static final class Course {
        final String name, teacher, position;
        final int day, startSection, endSection;
        final Set<Integer> weeks;
        final LocalTime customStart, customEnd;

        Course(
                String name,
                String teacher,
                String position,
                int day,
                int start,
                int end,
                Set<Integer> weeks,
                LocalTime customStart,
                LocalTime customEnd) {
            this.name = name;
            this.teacher = teacher;
            this.position = position;
            this.day = day;
            startSection = start;
            endSection = end;
            this.weeks = Collections.unmodifiableSet(new TreeSet<>(weeks));
            this.customStart = customStart;
            this.customEnd = customEnd;
        }
    }

    static final class Rule {
        final LocalDate start, end;
        final List<Slot> slots;

        Rule(LocalDate start, LocalDate end, List<Slot> slots) {
            this.start = start;
            this.end = end;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }
    }

    final List<Course> courses;
    final List<Slot> slots;
    final List<Rule> rules;
    final LocalDate semesterStart;
    final int totalWeeks, firstDayOfWeek;

    TimetableData(
            List<Course> courses,
            List<Slot> slots,
            List<Rule> rules,
            LocalDate start,
            int weeks,
            int firstDay) {
        this.courses = Collections.unmodifiableList(new ArrayList<>(courses));
        this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        semesterStart = start;
        totalWeeks = weeks;
        firstDayOfWeek = firstDay;
    }

    TimetableData withSemesterStart(LocalDate date) {
        return new TimetableData(courses, slots, rules, date, totalWeeks, firstDayOfWeek);
    }

    LocalDate alignedStart() {
        return semesterStart.minusDays(
                Math.floorMod(semesterStart.getDayOfWeek().getValue() - firstDayOfWeek, 7));
    }

    int week(LocalDate date) {
        return (int) Math.floorDiv(ChronoUnit.DAYS.between(alignedStart(), date), 7) + 1;
    }

    List<Slot> slotsFor(LocalDate date) {
        for (Rule r : rules) if (!date.isBefore(r.start) && !date.isAfter(r.end)) return r.slots;
        return slots;
    }
}
