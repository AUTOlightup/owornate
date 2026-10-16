package dev.codex.watchworkshop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 拾光课表（ShiGuangSchedule）单表 JSON 解析器：把导出的 JSON 规范化为 {@link TimetableData}。
 *
 * <p>格式来源（取证 commit feabb68f75c4058319b449287980b5f645eb8d61）：
 * {@code CourseImportExport.kt} 的 {@code CourseTableImportModel}；节次/自定义时间校验对齐
 * {@code CourseConversionRepository.validateTimeSlotsOrThrow} 与
 * {@code validateCustomCourseTimeOrThrow}；作息切换对齐
 * {@code TimeScheduleRepository.matchComboTargetTableId} 与 {@code alignTimeSlots}。
 *
 * <p>契约：{@code static TimetableData parse(String json, TimetableData previous)}。
 * 只用 Android 平台自带的 {@code org.json} 与 {@code java.time}，不打包第三方运行时。
 * 失败一律抛 {@link IllegalArgumentException}（带可读中文说明与出错位置）；失败时
 * {@code previous} 与磁盘都不被改动，部分解析结果不会外泄。
 *
 * <p>字段口径：
 * <ul>
 *   <li>顶层只有 {@code courses} 必填（数组，可为空）；未知字段与未知根键一律忽略。</li>
 *   <li>{@code weeks} 省略/null/空数组都解析为空集，绝不当作"全周"。</li>
 *   <li>{@code day} 1=周一 … 7=周日；{@code startSection}/{@code endSection} 含端点
 *       （结束时间取结束节次的 {@code endTime}）。</li>
 *   <li>自定义时间只认严格 {@code HH:mm}（两位、24 小时制、无秒），且不依赖节次表。</li>
 *   <li>{@code timeSlots} 缺省或空数组 → 沿用 {@code previous.slots}，没有 previous 则为空表。</li>
 *   <li>{@code config} 各字段缺省/null → 先用 {@code previous} 的对应值，否则
 *       {@code semesterTotalWeeks=20}、{@code firstDayOfWeek=1}、{@code semesterStartDate=null}
 *       （开学日期由 UI 填，不猜）。{@code semesterStartDate} 为空串视为未填写。</li>
 *   <li>{@code comboSchedule.publicSchedules} 按原顺序保留为 {@link TimetableData.Rule}：
 *       生效日期含端点、首次命中优先，无命中回退基准节次；模板节次按
 *       {@code alignTimeSlots} 的骨架对齐（节次编号以基准表为准，模板缺号保留基准时间，
 *       模板多出的编号丢弃）。{@code comboSchedule} 缺失/空列表 → 只有基准节次。</li>
 *   <li>课程引用不存在的节次、只给一半节次、{@code day} 越界、节次编号不连续/重叠/起止倒置、
 *       自定义时间格式或次序错误、规则日期非法：报错说明位置，不静默丢课。</li>
 *   <li>{@code startSection=endSection=0} 表示"未指定节次"（自定义时间课，或上游允许的 null），
 *       {@link TimetableFocus} 会跳过这类没有时间的课。</li>
 *   <li>上限：课程 1000 条，周次与学期周数 1..100；JSON 体积上限由调用方把关。</li>
 *   <li>{@code color}/{@code remark}/{@code credit}/{@code alias} 与时长字段不参与规范化，直接忽略。</li>
 * </ul>
 */
final class ShiguangJson {

    static final int MAX_COURSES = 1000;
    static final int MAX_WEEKS = 100;
    static final int DEFAULT_TOTAL_WEEKS = 20;
    static final int DEFAULT_FIRST_DAY_OF_WEEK = 1;

    /** 与上游 timeRegex 一致：HH:mm，两位、24 小时制、无秒。 */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("^(0[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$");

    private static final Comparator<TimetableData.Slot> BY_NUMBER =
            new Comparator<TimetableData.Slot>() {
                @Override
                public int compare(TimetableData.Slot left, TimetableData.Slot right) {
                    return Integer.compare(left.number, right.number);
                }
            };

    private ShiguangJson() {
    }

    /**
     * 解析单表 JSON。{@code previous} 只作为缺省字段的来源，成功时返回全新的
     * {@link TimetableData}，失败时不改动 {@code previous}。
     */
    static TimetableData parse(String json, TimetableData previous) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("课表 JSON 为空");
        }
        JSONObject root = rootObject(json);
        JSONObject config = optObject(root, "config", "根对象");

        int totalWeeks = previous == null ? DEFAULT_TOTAL_WEEKS : previous.totalWeeks;
        int firstDayOfWeek = previous == null ? DEFAULT_FIRST_DAY_OF_WEEK : previous.firstDayOfWeek;
        LocalDate semesterStart = previous == null ? null : previous.semesterStart;
        if (config != null) {
            Integer weeks = optInt(config, "semesterTotalWeeks", "config");
            if (weeks != null) {
                totalWeeks = weeks.intValue();
            }
            Integer firstDay = optInt(config, "firstDayOfWeek", "config");
            if (firstDay != null) {
                firstDayOfWeek = firstDay.intValue();
            }
            String startText = optString(config, "semesterStartDate", "config");
            if (startText != null) {
                String trimmed = startText.trim();
                semesterStart = trimmed.isEmpty() ? null : parseDate(trimmed, "config.semesterStartDate");
            }
        }
        if (totalWeeks < 1 || totalWeeks > MAX_WEEKS) {
            throw new IllegalArgumentException(
                    "config.semesterTotalWeeks 超出范围 1.." + MAX_WEEKS + "：实际 " + totalWeeks);
        }
        if (firstDayOfWeek < 1 || firstDayOfWeek > 7) {
            throw new IllegalArgumentException(
                    "config.firstDayOfWeek 超出范围 1..7（1=周一 … 7=周日）：实际 " + firstDayOfWeek);
        }

        List<TimetableData.Slot> slots = readSlots(root, previous);
        List<TimetableData.Course> courses = readCourses(requireArray(root, "courses", "根对象"), slots);
        List<TimetableData.Rule> rules = readRules(root, slots);
        return new TimetableData(courses, slots, rules, semesterStart, totalWeeks, firstDayOfWeek);
    }

    // 作息：基准节次与组合作息规则

    private static List<TimetableData.Slot> readSlots(JSONObject root, TimetableData previous) {
        JSONArray array = optArray(root, "timeSlots", "根对象");
        if (array == null) {
            return copySlots(previous);
        }
        List<TimetableData.Slot> parsed = parseSlots(array, "timeSlots");
        return parsed.isEmpty() ? copySlots(previous) : parsed;
    }

    private static List<TimetableData.Slot> copySlots(TimetableData previous) {
        return previous == null
                ? new ArrayList<TimetableData.Slot>()
                : new ArrayList<>(previous.slots);
    }

    /**
     * 校验并规范化节次表：编号按 number 升序必须从 1 连续、时间为严格 HH:mm、
     * 单条 start<end、相邻条不重叠（允许相接）。与上游 validateTimeSlotsOrThrow 一致。
     */
    private static List<TimetableData.Slot> parseSlots(JSONArray array, String where) {
        List<TimetableData.Slot> slots = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            Object element = array.opt(index);
            String itemWhere = where + "[" + index + "]";
            if (!(element instanceof JSONObject)) {
                throw new IllegalArgumentException(itemWhere + " 必须是对象");
            }
            JSONObject slot = (JSONObject) element;
            int number = requireInt(slot, "number", itemWhere);
            String startText = requireString(slot, "startTime", itemWhere);
            String endText = requireString(slot, "endTime", itemWhere);
            LocalTime start = parseTime(startText, itemWhere + ".startTime");
            LocalTime end = parseTime(endText, itemWhere + ".endTime");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException(itemWhere + " 开始时间必须早于结束时间："
                        + startText + " -> " + endText);
            }
            slots.add(new TimetableData.Slot(number, start, end));
        }
        slots.sort(BY_NUMBER);
        LocalTime previousEnd = null;
        for (int index = 0; index < slots.size(); index++) {
            TimetableData.Slot slot = slots.get(index);
            int expectedNumber = index + 1;
            if (slot.number != expectedNumber) {
                throw new IllegalArgumentException(where + " 节次编号必须从 1 连续（按 number 升序比对，"
                        + "重复编号同样不允许）：第 " + expectedNumber + " 条实际是 " + slot.number);
            }
            if (previousEnd != null && slot.start.isBefore(previousEnd)) {
                throw new IllegalArgumentException(where + " 节次 " + slot.number
                        + " 与上一节次时间重叠：" + slot.start + " 早于上一节次的结束 " + previousEnd);
            }
            previousEnd = slot.end;
        }
        return slots;
    }

    private static List<TimetableData.Rule> readRules(JSONObject root, List<TimetableData.Slot> baseSlots) {
        List<TimetableData.Rule> rules = new ArrayList<>();
        JSONObject combo = optObject(root, "comboSchedule", "根对象");
        if (combo == null) {
            return rules;
        }
        JSONArray templates = optArray(combo, "publicSchedules", "comboSchedule");
        if (templates == null) {
            return rules;
        }
        for (int index = 0; index < templates.length(); index++) {
            Object element = templates.opt(index);
            String where = "comboSchedule.publicSchedules[" + index + "]";
            if (!(element instanceof JSONObject)) {
                throw new IllegalArgumentException(where + " 必须是对象");
            }
            JSONObject template = (JSONObject) element;
            LocalDate start = parseDate(requireString(template, "startDate", where), where + ".startDate");
            LocalDate end = parseDate(requireString(template, "endDate", where), where + ".endDate");
            if (end.isBefore(start)) {
                throw new IllegalArgumentException(where + " 结束日期早于开始日期："
                        + start + " -> " + end);
            }
            JSONArray slotArray = optArray(template, "timeSlots", where);
            List<TimetableData.Slot> templateSlots = slotArray == null
                    ? new ArrayList<TimetableData.Slot>()
                    : parseSlots(slotArray, where + ".timeSlots");
            rules.add(new TimetableData.Rule(start, end, alignToBase(baseSlots, templateSlots)));
        }
        return rules;
    }

    /** 以基准节次为骨架对齐模板时间（对齐上游 alignTimeSlots）。 */
    private static List<TimetableData.Slot> alignToBase(List<TimetableData.Slot> base,
                                                        List<TimetableData.Slot> template) {
        if (base.isEmpty()) {
            return template;
        }
        if (template.isEmpty()) {
            return base;
        }
        Map<Integer, TimetableData.Slot> byNumber = new HashMap<>();
        for (TimetableData.Slot slot : template) {
            byNumber.put(Integer.valueOf(slot.number), slot);
        }
        List<TimetableData.Slot> aligned = new ArrayList<>(base.size());
        for (TimetableData.Slot baseSlot : base) {
            TimetableData.Slot override = byNumber.get(Integer.valueOf(baseSlot.number));
            aligned.add(override == null
                    ? baseSlot
                    : new TimetableData.Slot(baseSlot.number, override.start, override.end));
        }
        return aligned;
    }

    // 课程

    private static List<TimetableData.Course> readCourses(JSONArray array, List<TimetableData.Slot> slots) {
        if (array.length() > MAX_COURSES) {
            throw new IllegalArgumentException(
                    "课程数量 " + array.length() + " 超过上限 " + MAX_COURSES);
        }
        Set<Integer> available = new TreeSet<>();
        for (TimetableData.Slot slot : slots) {
            available.add(Integer.valueOf(slot.number));
        }
        List<TimetableData.Course> courses = new ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            Object element = array.opt(index);
            String where = "courses[" + index + "]";
            if (!(element instanceof JSONObject)) {
                throw new IllegalArgumentException(where + " 必须是对象");
            }
            JSONObject course = (JSONObject) element;
            String name = requireString(course, "name", where);
            String teacher = requireString(course, "teacher", where);
            String position = requireString(course, "position", where);
            int day = requireInt(course, "day", where);
            if (day < 1 || day > 7) {
                throw new IllegalArgumentException(where + ".day 超出范围 1..7（1=周一 … 7=周日）：实际 "
                        + day + "（课程 " + name + "）");
            }

            int startSection = 0;
            int endSection = 0;
            LocalTime customStart = null;
            LocalTime customEnd = null;
            if (optBoolean(course, "isCustomTime", where, false)) {
                String startText = optString(course, "customStartTime", where);
                String endText = optString(course, "customEndTime", where);
                if (startText == null || startText.trim().isEmpty()
                        || endText == null || endText.trim().isEmpty()) {
                    throw new IllegalArgumentException(where
                            + " 标记了 isCustomTime=true，但 customStartTime/customEndTime 不完整（课程 "
                            + name + "）");
                }
                customStart = parseTime(startText.trim(), where + ".customStartTime");
                customEnd = parseTime(endText.trim(), where + ".customEndTime");
                if (!customStart.isBefore(customEnd)) {
                    throw new IllegalArgumentException(where + " 自定义时间必须开始早于结束："
                            + startText + " -> " + endText + "（课程 " + name + "）");
                }
            } else {
                Integer start = optInt(course, "startSection", where);
                Integer end = optInt(course, "endSection", where);
                if (start == null && end == null) {
                    startSection = 0;
                    endSection = 0;
                } else if (start == null || end == null) {
                    throw new IllegalArgumentException(where
                            + " 只提供了 startSection/endSection 其中之一（课程 " + name
                            + "）：无法确定上课时间");
                } else {
                    if (!available.contains(start) || !available.contains(end)) {
                        throw new IllegalArgumentException(where + " 引用了不存在的节次（课程 " + name
                                + "）：startSection=" + start + "、endSection=" + end
                                + "，当前可用节次 " + available);
                    }
                    if (start.intValue() > end.intValue()) {
                        throw new IllegalArgumentException(where + " 起始节次晚于结束节次（课程 " + name
                                + "）：" + start + " -> " + end);
                    }
                    startSection = start.intValue();
                    endSection = end.intValue();
                }
            }

            courses.add(new TimetableData.Course(name, teacher, position, day, startSection, endSection,
                    readWeeks(course, where, name), customStart, customEnd));
        }
        return courses;
    }

    private static Set<Integer> readWeeks(JSONObject course, String where, String name) {
        Set<Integer> weeks = new TreeSet<>();
        JSONArray array = optArray(course, "weeks", where);
        if (array == null) {
            return weeks;
        }
        for (int index = 0; index < array.length(); index++) {
            String itemWhere = where + ".weeks[" + index + "]";
            int week = toInt(array.opt(index), itemWhere);
            if (week < 1 || week > MAX_WEEKS) {
                throw new IllegalArgumentException(itemWhere + " 周次超出范围 1.." + MAX_WEEKS
                        + "（课程 " + name + "）");
            }
            weeks.add(Integer.valueOf(week));
        }
        return weeks;
    }

    // JSON 读取与类型校验

    private static JSONObject rootObject(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException error) {
            throw new IllegalArgumentException("课表 JSON 顶层必须是对象：" + error.getMessage());
        }
    }

    private static boolean isPresent(JSONObject object, String key) {
        return object.has(key) && !object.isNull(key);
    }

    private static JSONObject optObject(JSONObject parent, String key, String where) {
        if (!isPresent(parent, key)) {
            return null;
        }
        Object value = parent.opt(key);
        if (!(value instanceof JSONObject)) {
            throw new IllegalArgumentException(where + "." + key + " 必须是对象");
        }
        return (JSONObject) value;
    }

    private static JSONArray requireArray(JSONObject parent, String key, String where) {
        if (!isPresent(parent, key)) {
            throw new IllegalArgumentException(where + " 缺少必填数组字段 " + key);
        }
        Object value = parent.opt(key);
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException(where + "." + key + " 必须是数组");
        }
        return (JSONArray) value;
    }

    private static JSONArray optArray(JSONObject parent, String key, String where) {
        if (!isPresent(parent, key)) {
            return null;
        }
        Object value = parent.opt(key);
        if (!(value instanceof JSONArray)) {
            throw new IllegalArgumentException(where + "." + key + " 必须是数组");
        }
        return (JSONArray) value;
    }

    private static String requireString(JSONObject object, String key, String where) {
        if (!isPresent(object, key)) {
            throw new IllegalArgumentException(where + " 缺少必填字符串字段 " + key);
        }
        return stringValue(object.opt(key), where + "." + key);
    }

    private static String optString(JSONObject object, String key, String where) {
        if (!isPresent(object, key)) {
            return null;
        }
        return stringValue(object.opt(key), where + "." + key);
    }

    private static String stringValue(Object value, String where) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(where + " 必须是字符串");
        }
        return (String) value;
    }

    private static int requireInt(JSONObject object, String key, String where) {
        if (!isPresent(object, key)) {
            throw new IllegalArgumentException(where + " 缺少必填整数字段 " + key);
        }
        return toInt(object.opt(key), where + "." + key);
    }

    private static Integer optInt(JSONObject object, String key, String where) {
        if (!isPresent(object, key)) {
            return null;
        }
        return Integer.valueOf(toInt(object.opt(key), where + "." + key));
    }

    private static boolean optBoolean(JSONObject object, String key, String where, boolean fallback) {
        if (!isPresent(object, key)) {
            return fallback;
        }
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(where + "." + key + " 必须是 true/false");
        }
        return ((Boolean) value).booleanValue();
    }

    private static int toInt(Object value, String where) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(where + " 必须是整数");
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)
                || asDouble != Math.rint(asDouble)) {
            throw new IllegalArgumentException(where + " 必须是整数");
        }
        long asLong = number.longValue();
        if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(where + " 超出整数范围");
        }
        return (int) asLong;
    }

    private static LocalTime parseTime(String text, String where) {
        if (!TIME_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException(where
                    + " 必须是严格的 HH:mm（两位、24 小时制、无秒）：实际 \"" + text + "\"");
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(where + " 不是有效时刻：\"" + text + "\"");
        }
    }

    private static LocalDate parseDate(String text, String where) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(where + " 必须是 yyyy-MM-dd 日期：实际 \"" + text + "\"");
        }
    }
}
