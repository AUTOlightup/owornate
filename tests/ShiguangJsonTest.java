package dev.codex.watchworkshop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * ShiguangJson 的离线验证：直接编译运行工程里的真实 parser 与 TimetableData，
 * 用固定 fixture（全为合成数据）、固定日期，不连设备、不写工程、不读系统当前时间。
 *
 * <p>用法：{@code java -cp <classes>;<org.json.jar> dev.codex.watchworkshop.ShiguangJsonTest [fixtureDir]}。
 * fixtureDir 省略时看环境变量 WATCHWORKSHOP_FIXTURES，再回落到当前工作目录下的 tests/fixtures。
 */
public final class ShiguangJsonTest {

    private static final String MINIMAL = "fixture_synthetic_minimal.json";
    private static final String COMBO = "fixture_synthetic_combo.json";
    private static final String ABSOLUTE_MINIMAL = "fixture_synthetic_absolutely_minimal.json";

    private static final String SLOT_1 = "{\"number\":1,\"startTime\":\"08:00\",\"endTime\":\"08:45\"}";
    private static final String SLOT_2 = "{\"number\":2,\"startTime\":\"08:55\",\"endTime\":\"09:40\"}";
    private static final String SLOTS_12 = "\"timeSlots\":[" + SLOT_1 + "," + SLOT_2 + "]";
    private static final String COURSE_1 = "{\"name\":\"课\",\"teacher\":\"师\",\"position\":\"地\","
            + "\"day\":1,\"startSection\":1,\"endSection\":1,\"weeks\":[1]}";

    private static File fixtureDir;
    private static final List<String> failures = new ArrayList<>();
    private static int passed;
    private static int failed;
    private static int checks;

    public static void main(String[] args) throws Exception {
        fixtureDir = resolveFixtureDir(args);
        if (!fixtureDir.isDirectory()) {
            throw new IllegalStateException("fixture 目录不存在: " + fixtureDir.getAbsolutePath()
                    + "（用第一个参数或 WATCHWORKSHOP_FIXTURES 指定）");
        }
        final String hashBefore = fixtureHashes();
        System.out.println("fixture 目录: " + fixtureDir.getAbsolutePath());
        System.out.println("fixture SHA-256: " + hashBefore);
        System.out.println();

        run("fixture 完整文件：课程/节次/配置/周次/自定义时间", ShiguangJsonTest::fullFixture);
        run("fixture 组合作息：顺序、含端点、首次命中、无匹配回退", ShiguangJsonTest::comboFixture);
        run("组合作息：日期重叠时取第一条命中的规则", ShiguangJsonTest::firstRuleWins);
        run("组合作息：模板节次按基准骨架对齐", ShiguangJsonTest::templateAlignment);
        run("组合作息：模板多出的编号按基准骨架丢弃", ShiguangJsonTest::templateExtraSlotDropped);
        run("自定义时间不依赖节次，未知字段忽略", ShiguangJsonTest::customTimeAndUnknownFields);
        run("合法下界：只有 courses", ShiguangJsonTest::absoluteMinimalFixture);
        run("weeks 省略/null/空数组都是空集，不是全周", ShiguangJsonTest::weeksNeverBecomeWholeSemester);
        run("空 courses 与空 timeSlots 合法", ShiguangJsonTest::emptyCoursesAndSlots);
        run("previous 继承：节次与配置缺省沿用，且 previous 不被修改", ShiguangJsonTest::previousInheritance);
        run("上限：课程 1000、周次与学期周数 100", ShiguangJsonTest::bounds);
        run("坏数据报错位置清晰", ShiguangJsonTest::errorCases);
        run("报错时 previous 完全不变", ShiguangJsonTest::failedParseKeepsPrevious);
        run("解析器不写磁盘：fixture 字节不变", () -> eq(hashBefore, fixtureHashes(), "fixture 摘要"));

        System.out.println();
        System.out.println("ShiguangJsonTest: " + passed + " passed, " + failed + " failed, "
                + checks + " checks");
        if (failed > 0) {
            System.out.println("失败用例:");
            for (String failure : failures) {
                System.out.println("  - " + failure);
            }
            System.exit(1);
        }
    }

    // 用例

    private static void fullFixture() throws Exception {
        TimetableData data = ShiguangJson.parse(fixture(MINIMAL), null);
        eq(3, data.courses.size(), "courses 数量");
        eq(4, data.slots.size(), "timeSlots 数量");
        eq(0, data.rules.size(), "comboSchedule 为 null → 没有规则");
        eq(LocalDate.of(2026, 3, 2), data.semesterStart, "semesterStartDate");
        eq(20, data.totalWeeks, "semesterTotalWeeks");
        eq(1, data.firstDayOfWeek, "firstDayOfWeek");
        eq(LocalDate.of(2026, 3, 2), data.alignedStart(), "firstDay=1 对齐到周一");
        eq(1, data.week(LocalDate.of(2026, 3, 2)), "开学当天是第 1 周");
        eq(1, data.week(LocalDate.of(2026, 3, 8)), "第 1 周的周日仍在第 1 周");
        eq(2, data.week(LocalDate.of(2026, 3, 9)), "次周周一进入第 2 周");

        TimetableData.Course first = data.courses.get(0);
        eq("合成课程·甲", first.name, "课程按 JSON 原顺序保留");
        eq(1, first.day, "day=1 是周一");
        eq(1, first.startSection, "startSection");
        eq(2, first.endSection, "endSection");
        eq(range(1, 20), first.weeks, "全周 weeks 原样保留");
        check(first.customStart == null && first.customEnd == null, "非自定义时间课没有 custom 时间");
        eq(LocalTime.of(8, 0), slotOf(data, first.startSection).start, "起始节次开始时间 08:00");
        eq(LocalTime.of(9, 40), slotOf(data, first.endSection).end, "结束节次含端点 → 结束 09:40");

        TimetableData.Course second = data.courses.get(1);
        eq(set(1, 3, 5, 7, 9, 11, 13, 15), second.weeks, "单周 weeks 原样保留");
        eq(3, second.day, "day=3 是周三");

        TimetableData.Course third = data.courses.get(2);
        eq(LocalTime.of(19, 0), third.customStart, "自定义开始时间");
        eq(LocalTime.of(20, 30), third.customEnd, "自定义结束时间");
        eq(0, third.startSection, "自定义时间课不落节次，0 表示未指定");
        eq(0, third.endSection, "自定义时间课不落节次，0 表示未指定");
        eq(set(2, 4, 6, 8, 10, 12, 14, 16), third.weeks, "双周 weeks 原样保留");
        eq(4, data.slotsFor(LocalDate.of(2026, 5, 1)).size(), "没有规则时任何日期都用基准节次");
    }

    private static void comboFixture() throws Exception {
        TimetableData data = ShiguangJson.parse(fixture(COMBO), null);
        eq(2, data.rules.size(), "publicSchedules 两条规则");
        TimetableData.Rule spring = data.rules.get(0);
        TimetableData.Rule summer = data.rules.get(1);
        eq(LocalDate.of(2026, 3, 2), spring.start, "规则顺序＝JSON 顺序：先春季");
        eq(LocalDate.of(2026, 4, 30), spring.end, "春季结束日期");
        eq(LocalDate.of(2026, 5, 1), summer.start, "夏季起始日期");
        eq(LocalDate.of(2026, 7, 19), summer.end, "夏季结束日期");
        eq(4, spring.slots.size(), "模板与基准节次同编号时骨架长度不变");
        eq(LocalTime.of(9, 40), spring.slots.get(1).end, "春季模板时间生效");
        eq(LocalTime.of(9, 30), summer.slots.get(1).end, "夏季模板时间生效");
        eq(LocalTime.of(8, 45), data.slotsFor(LocalDate.of(2026, 4, 30)).get(0).end,
                "规则结束日含端点 → 仍是春季作息");
        eq(LocalTime.of(8, 40), data.slotsFor(LocalDate.of(2026, 5, 1)).get(0).end,
                "规则起始日含端点 → 已是夏季作息");
        eq(LocalTime.of(9, 30), data.slotsFor(LocalDate.of(2026, 7, 19)).get(1).end,
                "夏季最后一天仍在规则内");
        eq(LocalTime.of(9, 40), data.slotsFor(LocalDate.of(2026, 7, 20)).get(1).end,
                "规则外回退基准节次");
    }

    private static void firstRuleWins() throws Exception {
        String json = "{\"courses\":[" + COURSE_1 + "],"
                + "\"timeSlots\":[" + SLOT_1 + ","
                + "{\"number\":2,\"startTime\":\"09:50\",\"endTime\":\"10:35\"}],"
                + "\"comboSchedule\":{\"name\":\"重叠\",\"publicSchedules\":["
                + "{\"name\":\"甲\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-06-30\","
                + "\"timeSlots\":[{\"number\":1,\"startTime\":\"08:00\",\"endTime\":\"08:40\"}]},"
                + "{\"name\":\"乙\",\"startDate\":\"2026-05-01\",\"endDate\":\"2026-07-31\","
                + "\"timeSlots\":[{\"number\":1,\"startTime\":\"09:00\",\"endTime\":\"09:30\"}]}]}}";
        TimetableData data = ShiguangJson.parse(json, null);
        eq(2, data.rules.size(), "两条重叠规则都保留");
        eq(LocalTime.of(8, 40), data.slotsFor(LocalDate.of(2026, 5, 15)).get(0).end,
                "重叠区间取第一条命中的规则");
        eq(LocalTime.of(9, 30), data.slotsFor(LocalDate.of(2026, 7, 15)).get(0).end,
                "第一条不覆盖时取第二条");
        eq(2, data.slotsFor(LocalDate.of(2026, 5, 15)).size(), "模板只给 1 号节次时按基准骨架补齐");
    }

    private static void templateAlignment() throws Exception {
        String json = "{\"courses\":[" + COURSE_1 + "],"
                + "\"timeSlots\":[" + SLOT_1 + "," + SLOT_2 + ","
                + "{\"number\":3,\"startTime\":\"10:00\",\"endTime\":\"10:45\"}],"
                + "\"comboSchedule\":{\"publicSchedules\":["
                + "{\"name\":\"骨架\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-06-30\","
                + "\"timeSlots\":[{\"number\":1,\"startTime\":\"08:00\",\"endTime\":\"08:30\"},"
                + "{\"number\":2,\"startTime\":\"09:00\",\"endTime\":\"09:30\"}]},"
                + "{\"name\":\"空模板\",\"startDate\":\"2026-07-01\",\"endDate\":\"2026-07-31\","
                + "\"timeSlots\":[]}]}}";
        TimetableData data = ShiguangJson.parse(json, null);
        List<TimetableData.Slot> aligned = data.rules.get(0).slots;
        eq(3, aligned.size(), "模板只给前两节时，骨架长度仍是基准的 3");
        eq(LocalTime.of(8, 0), aligned.get(0).start, "模板覆盖到的节次用模板时间");
        eq(LocalTime.of(8, 30), aligned.get(0).end, "模板覆盖到的节次用模板时间");
        eq(LocalTime.of(9, 0), aligned.get(1).start, "模板覆盖到的节次用模板时间");
        eq(LocalTime.of(9, 30), aligned.get(1).end, "模板覆盖到的节次用模板时间");
        eq(LocalTime.of(10, 0), aligned.get(2).start, "模板没给的节次保留基准时间");
        eq(LocalTime.of(10, 45), aligned.get(2).end, "模板没给的节次保留基准时间");
        eq(3, data.rules.get(1).slots.size(), "空模板整段沿用基准骨架");
        eq(LocalTime.of(8, 0), data.rules.get(1).slots.get(0).start, "空模板整段沿用基准骨架");
    }

    private static void templateExtraSlotDropped() throws Exception {
        String json = "{\"courses\":[" + COURSE_1 + "],"
                + "\"timeSlots\":[" + SLOT_1 + "," + SLOT_2 + "],"
                + "\"comboSchedule\":{\"publicSchedules\":["
                + "{\"name\":\"多一节\",\"startDate\":\"2026-03-01\",\"endDate\":\"2026-06-30\","
                + "\"timeSlots\":[" + SLOT_1 + "," + SLOT_2 + ","
                + "{\"number\":3,\"startTime\":\"12:00\",\"endTime\":\"12:45\"}]}]}}";
        TimetableData data = ShiguangJson.parse(json, null);
        eq(2, data.rules.get(0).slots.size(), "模板多出的节次编号按基准骨架丢弃");
        eq(LocalTime.of(9, 40), data.rules.get(0).slots.get(1).end, "保留的节次用模板时间");
    }

    private static void customTimeAndUnknownFields() throws Exception {
        String json = "{\"courses\":[{\"name\":\"晚课\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":2,\"startSection\":9,\"endSection\":9,\"isCustomTime\":true,"
                + "\"customStartTime\":\"19:00\",\"customEndTime\":\"20:30\",\"weeks\":[1],"
                + "\"color\":7,\"remark\":\"备注\",\"credit\":\"不是数字\",\"未知课程字段\":[1,2]}],"
                + "\"未知根键\":{\"a\":1},\"config\":{\"semesterStartDate\":\"2026-03-02\","
                + "\"defaultClassDuration\":50,\"defaultBreakDuration\":5}}";
        TimetableData data = ShiguangJson.parse(json, null);
        eq(1, data.courses.size(), "未知字段不影响解析");
        TimetableData.Course course = data.courses.get(0);
        eq("晚课", course.name, "课程名");
        eq(2, course.day, "day");
        eq(0, course.startSection, "自定义时间课忽略节次引用（不报错）");
        eq(0, course.endSection, "自定义时间课忽略节次引用（不报错）");
        eq(LocalTime.of(19, 0), course.customStart, "自定义开始时间");
        eq(LocalTime.of(20, 30), course.customEnd, "自定义结束时间");
        eq(set(1), course.weeks, "weeks");
        check(data.slots.isEmpty(), "没有 timeSlots 也没有 previous → 空节次表");
        eq(LocalDate.of(2026, 3, 2), data.semesterStart, "config 存在的字段照常采用");
        eq(20, data.totalWeeks, "config 未给 semesterTotalWeeks → 默认 20");
    }

    private static void absoluteMinimalFixture() throws Exception {
        TimetableData data = ShiguangJson.parse(fixture(ABSOLUTE_MINIMAL), null);
        eq(1, data.courses.size(), "只有 courses 也能解析");
        TimetableData.Course course = data.courses.get(0);
        eq("合成课程·最小", course.name, "课程名");
        check(course.weeks.isEmpty(), "weeks 省略 → 空集");
        eq(0, course.startSection, "节次省略 → 0 表示未指定");
        eq(0, course.endSection, "节次省略 → 0 表示未指定");
        check(course.customStart == null && course.customEnd == null, "没有自定义时间");
        check(data.slots.isEmpty(), "timeSlots 省略且没有 previous → 空节次表");
        check(data.rules.isEmpty(), "没有组合作息");
        check(data.semesterStart == null, "config 省略 → 开学日期为 null（由 UI 填，不猜）");
        eq(20, data.totalWeeks, "config 省略 → 学期周数默认 20");
        eq(1, data.firstDayOfWeek, "config 省略 → 每周第一天默认 1");
    }

    private static void weeksNeverBecomeWholeSemester() throws Exception {
        String omitted = "{\"courses\":[{\"name\":\"省略\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":1,\"endSection\":1}]," + SLOTS_12 + "}";
        String nulled = "{\"courses\":[{\"name\":\"null\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":1,\"endSection\":1,\"weeks\":null}]," + SLOTS_12 + "}";
        String empty = "{\"courses\":[{\"name\":\"空\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":1,\"endSection\":1,\"weeks\":[]}]," + SLOTS_12 + "}";
        for (String json : new String[] {omitted, nulled, empty}) {
            TimetableData data = ShiguangJson.parse(json, null);
            check(data.courses.get(0).weeks.isEmpty(),
                    "weeks 省略/null/空都是空集，不是全周：" + data.courses.get(0).weeks);
        }
        TimetableData data = ShiguangJson.parse("{\"courses\":[{\"name\":\"去重\",\"teacher\":\"师\","
                + "\"position\":\"地\",\"day\":1,\"startSection\":1,\"endSection\":1,"
                + "\"weeks\":[3,1,3,2]}]," + SLOTS_12 + "}", null);
        eq(set(1, 2, 3), data.courses.get(0).weeks, "weeks 去重并升序");
    }

    private static void emptyCoursesAndSlots() throws Exception {
        TimetableData data = ShiguangJson.parse("{\"courses\":[],\"timeSlots\":[]}", null);
        check(data.courses.isEmpty(), "空 courses 合法");
        check(data.slots.isEmpty(), "空 timeSlots（无 previous）合法");
        check(data.rules.isEmpty(), "没有组合作息");
        TimetableData withConfig = ShiguangJson.parse("{\"courses\":[],\"config\":{"
                + "\"semesterStartDate\":\"2026-09-07\",\"semesterTotalWeeks\":16,"
                + "\"firstDayOfWeek\":7}}", null);
        eq(LocalDate.of(2026, 9, 7), withConfig.semesterStart, "只有 config 也解析");
        eq(16, withConfig.totalWeeks, "semesterTotalWeeks");
        eq(7, withConfig.firstDayOfWeek, "firstDayOfWeek=7（周日开头）");
        eq(LocalDate.of(2026, 9, 6), withConfig.alignedStart(), "firstDay=7 时对齐到前一个周日");
        eq(1, withConfig.week(LocalDate.of(2026, 9, 6)), "对齐后的周日是第 1 周");
        TimetableData blank = ShiguangJson.parse("{\"courses\":[],\"config\":{"
                + "\"semesterStartDate\":\"   \"}}", null);
        check(blank.semesterStart == null, "空串开学日期视为未填写");
    }

    private static void previousInheritance() throws Exception {
        TimetableData previous = ShiguangJson.parse(fixture(MINIMAL), null);
        List<TimetableData.Course> coursesBefore = previous.courses;
        List<TimetableData.Slot> slotsBefore = previous.slots;
        Set<Integer> weeksBefore = previous.courses.get(0).weeks;

        TimetableData inherited = ShiguangJson.parse("{\"courses\":[{\"name\":\"新课\","
                + "\"teacher\":\"师\",\"position\":\"地\",\"day\":3,\"startSection\":1,"
                + "\"endSection\":2,\"weeks\":[1]}]}", previous);
        eq(4, inherited.slots.size(), "timeSlots 缺省 → 沿用 previous.slots");
        same(previous.slots.get(0), inherited.slots.get(0), "沿用同一批 Slot 实例");
        eq(LocalDate.of(2026, 3, 2), inherited.semesterStart, "config 缺省 → 沿用 previous.semesterStart");
        eq(20, inherited.totalWeeks, "config 缺省 → 沿用 previous.totalWeeks");
        eq(1, inherited.firstDayOfWeek, "config 缺省 → 沿用 previous.firstDayOfWeek");
        check(inherited.rules.isEmpty(), "JSON 没有 comboSchedule → 只有基准节次");
        eq(1, inherited.courses.size(), "courses 始终以本次 JSON 为准");

        TimetableData emptySlots = ShiguangJson.parse("{\"courses\":[],\"timeSlots\":[]}", previous);
        eq(4, emptySlots.slots.size(), "空 timeSlots 数组也沿用 previous.slots");
        same(previous.slots.get(3), emptySlots.slots.get(3), "沿用同一批 Slot 实例");
        check(emptySlots.courses.isEmpty(), "空 courses 是合法的空课表");

        TimetableData partial = ShiguangJson.parse("{\"courses\":[],\"config\":{"
                + "\"firstDayOfWeek\":7}}", previous);
        eq(7, partial.firstDayOfWeek, "config 给了的字段以其为准");
        eq(LocalDate.of(2026, 3, 2), partial.semesterStart, "config 缺的字段沿用 previous");
        eq(20, partial.totalWeeks, "config 缺的字段沿用 previous");

        same(coursesBefore, previous.courses, "previous.courses 未被替换");
        same(slotsBefore, previous.slots, "previous.slots 未被替换");
        same(weeksBefore, previous.courses.get(0).weeks, "previous 课程的 weeks 集合未被替换");
        eq(LocalDate.of(2026, 3, 2), previous.semesterStart, "previous.semesterStart 未变");
        eq(20, previous.totalWeeks, "previous.totalWeeks 未变");
        eq(1, previous.firstDayOfWeek, "previous.firstDayOfWeek 未变");
    }

    private static void bounds() throws Exception {
        StringBuilder many = new StringBuilder("{\"courses\":[");
        for (int index = 0; index <= ShiguangJson.MAX_COURSES; index++) {
            if (index > 0) {
                many.append(',');
            }
            many.append(COURSE_1);
        }
        rejects(many.append("]}").toString(), null, "超过上限", "课程数超过 1000 要报错");
        rejects(timetable("[{\"name\":\"周次越界\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":1,\"endSection\":1,\"weeks\":[101]}]", SLOTS_12),
                null, "周次", "周次 101 要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"config\":{\"semesterTotalWeeks\":101}"),
                null, "semesterTotalWeeks", "学期周数 101 要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"config\":{\"semesterTotalWeeks\":0}"),
                null, "semesterTotalWeeks", "学期周数 0 要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"config\":{\"firstDayOfWeek\":8}"),
                null, "firstDayOfWeek", "firstDayOfWeek=8 要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"config\":{\"firstDayOfWeek\":0}"),
                null, "firstDayOfWeek", "firstDayOfWeek=0 要报错");
    }

    private static void errorCases() throws Exception {
        rejects("", null, "空", "空 JSON 要报错");
        rejects("[]", null, "对象", "顶层不是对象要报错");
        rejects("{}", null, "courses", "缺少 courses 要报错");
        rejects("{\"courses\":{}}", null, "数组", "courses 不是数组要报错");
        rejects("{\"courses\":[],\"timeSlots\":{}}", null, "数组", "timeSlots 不是数组要报错");
        rejects(timetable("[{\"name\":\"缺老师\",\"position\":\"地\",\"day\":1,"
                + "\"startSection\":1,\"endSection\":1}]", SLOTS_12), null, "teacher",
                "缺少 teacher 要报错");
        rejects(timetable("[{\"teacher\":\"师\",\"position\":\"地\",\"day\":1,"
                + "\"startSection\":1,\"endSection\":1}]", SLOTS_12), null, "name",
                "缺少 name 要报错");
        rejects(timetable("[{\"name\":\"day 越界\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":8,\"startSection\":1,\"endSection\":1}]", SLOTS_12), null, "day",
                "day=8 要报错");
        rejects(timetable("[{\"name\":\"day 类型\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":\"1\",\"startSection\":1,\"endSection\":1}]", SLOTS_12), null,
                "必须是整数", "day 是字符串要报错");
        rejects(timetable("[{\"name\":\"缺节次\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":3,\"endSection\":3}]", SLOTS_12), null, "节次",
                "引用不存在的节次要报错");
        rejects(timetable("[{\"name\":\"缺结束节次\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":2,\"endSection\":4}]", SLOTS_12), null, "节次",
                "endSection 不存在要报错");
        rejects(timetable("[{\"name\":\"半个节次\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":1}]", SLOTS_12), null, "startSection",
                "只给一半节次要报错");
        rejects(timetable("[{\"name\":\"倒置节次\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":2,\"endSection\":1}]", SLOTS_12), null, "晚于",
                "起始节次晚于结束节次要报错");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":[" + SLOT_1 + ","
                + "{\"number\":3,\"startTime\":\"08:55\",\"endTime\":\"09:40\"}]"), null,
                "连续", "节次编号不连续要报错");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":[" + SLOT_1 + ","
                + "{\"number\":2,\"startTime\":\"08:30\",\"endTime\":\"09:30\"}]"), null,
                "重叠", "节次时间重叠要报错");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":["
                + "{\"number\":1,\"startTime\":\"09:00\",\"endTime\":\"08:00\"}]"), null,
                "早于", "节次结束时刻不晚于开始要报错");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":["
                + "{\"number\":1,\"startTime\":\"8:00\",\"endTime\":\"08:45\"}]"), null,
                "HH:mm", "节次时间必须严格 HH:mm");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":["
                + "{\"number\":1,\"startTime\":\"08:00\",\"endTime\":\"08:45:00\"}]"), null,
                "HH:mm", "节次时间不接受秒");
        rejects(timetable("[{\"name\":\"自定义格式\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"isCustomTime\":true,\"customStartTime\":\"19:0\","
                + "\"customEndTime\":\"20:00\",\"weeks\":[1]}]", SLOTS_12), null, "HH:mm",
                "自定义时间必须严格 HH:mm");
        rejects(timetable("[{\"name\":\"自定义倒置\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"isCustomTime\":true,\"customStartTime\":\"20:00\","
                + "\"customEndTime\":\"19:00\",\"weeks\":[1]}]", SLOTS_12), null, "早于",
                "自定义时间结束必须晚于开始");
        rejects(timetable("[{\"name\":\"自定义缺结束\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"isCustomTime\":true,\"customStartTime\":\"19:00\","
                + "\"weeks\":[1]}]", SLOTS_12), null, "customEndTime",
                "isCustomTime=true 缺少结束时间要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12
                + ",\"config\":{\"semesterStartDate\":\"2026-3-2\"}"), null, "yyyy-MM-dd",
                "开学日期格式不合法要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"comboSchedule\":{\"publicSchedules\":["
                + "{\"name\":\"坏规则\",\"startDate\":\"2026-05-01\",\"endDate\":\"2026-03-01\","
                + "\"timeSlots\":[" + SLOT_1 + "]}]}"), null, "结束日期早于",
                "规则结束日期早于开始日期要报错");
        rejects(timetable("[" + COURSE_1 + "]", SLOTS_12 + ",\"comboSchedule\":{\"publicSchedules\":["
                + "{\"name\":\"坏日期\",\"startDate\":\"2026/05/01\",\"endDate\":\"2026-06-01\","
                + "\"timeSlots\":[" + SLOT_1 + "]}]}"), null, "yyyy-MM-dd",
                "规则日期格式不合法要报错");
    }

    private static void failedParseKeepsPrevious() throws Exception {
        TimetableData previous = ShiguangJson.parse(fixture(MINIMAL), null);
        List<TimetableData.Course> coursesBefore = previous.courses;
        List<TimetableData.Slot> slotsBefore = previous.slots;
        Set<Integer> weeksBefore = previous.courses.get(0).weeks;
        String hashBefore = fixtureHashes();

        rejects(timetable("[{\"name\":\"坏课\",\"teacher\":\"师\",\"position\":\"地\","
                + "\"day\":1,\"startSection\":9,\"endSection\":9,\"weeks\":[1]}]", SLOTS_12),
                previous, "节次", "引用不存在节次时失败");
        rejects(timetable("[" + COURSE_1 + "]", "\"timeSlots\":["
                + "{\"number\":1,\"startTime\":\"08:00\",\"endTime\":\"07:00\"}]"),
                previous, "早于", "节次结束时序错误时失败");

        same(coursesBefore, previous.courses, "previous.courses 未被替换");
        same(slotsBefore, previous.slots, "previous.slots 未被替换");
        same(weeksBefore, previous.courses.get(0).weeks, "previous 课程的 weeks 集合未被替换");
        eq(3, previous.courses.size(), "previous.courses 数量未变");
        eq(4, previous.slots.size(), "previous.slots 数量未变");
        eq(LocalDate.of(2026, 3, 2), previous.semesterStart, "previous.semesterStart 未变");
        eq(20, previous.totalWeeks, "previous.totalWeeks 未变");
        eq(1, previous.firstDayOfWeek, "previous.firstDayOfWeek 未变");
        eq(hashBefore, fixtureHashes(), "fixture 文件未被改动");
    }

    // 工具

    private static File resolveFixtureDir(String[] args) {
        if (args.length > 0 && !args[0].trim().isEmpty()) {
            return new File(args[0].trim());
        }
        String fromEnv = System.getenv("WATCHWORKSHOP_FIXTURES");
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return new File(fromEnv.trim());
        }
        return new File("tests", "fixtures");
    }

    private static String timetable(String coursesArrayJson, String extraKeysJson) {
        StringBuilder json = new StringBuilder("{\"courses\":").append(coursesArrayJson);
        if (extraKeysJson != null && !extraKeysJson.isEmpty()) {
            json.append(',').append(extraKeysJson);
        }
        return json.append('}').toString();
    }

    private static String fixture(String name) throws Exception {
        File file = new File(fixtureDir, name);
        if (!file.isFile()) {
            throw new AssertionError("缺少 fixture: " + file.getAbsolutePath());
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String fixtureHashes() throws Exception {
        StringBuilder summary = new StringBuilder();
        for (String name : new String[] {MINIMAL, COMBO, ABSOLUTE_MINIMAL}) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(new File(fixtureDir, name).toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", Byte.valueOf(value)));
            }
            if (summary.length() > 0) {
                summary.append(' ');
            }
            summary.append(name).append('=').append(hex);
        }
        return summary.toString();
    }

    private static TimetableData.Slot slotOf(TimetableData data, int number) {
        for (TimetableData.Slot slot : data.slots) {
            if (slot.number == number) {
                return slot;
            }
        }
        throw new AssertionError("节次表里没有 number=" + number);
    }

    private static Set<Integer> set(int... values) {
        Set<Integer> out = new TreeSet<>();
        for (int value : values) {
            out.add(Integer.valueOf(value));
        }
        return out;
    }

    private static Set<Integer> range(int from, int to) {
        Set<Integer> out = new TreeSet<>();
        for (int value = from; value <= to; value++) {
            out.add(Integer.valueOf(value));
        }
        return out;
    }

    private interface Case {
        void run() throws Exception;
    }

    private static void run(String name, Case body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable error) {
            failed++;
            failures.add(name + " -> " + error);
            System.out.println("FAIL  " + name + " -> " + error);
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void same(Object expected, Object actual, String what) {
        checks++;
        if (expected != actual) {
            throw new AssertionError(what + "：不是同一个对象");
        }
    }

    private static void rejects(String json, TimetableData previous, String fragment, String what) {
        checks++;
        try {
            TimetableData parsed = ShiguangJson.parse(json, previous);
            throw new AssertionError(what + "：本应报错，却解析成功（courses=" + parsed.courses.size() + "）");
        } catch (IllegalArgumentException expected) {
            String message = String.valueOf(expected.getMessage());
            if (!message.contains(fragment)) {
                throw new AssertionError(what + "：报错信息里没有 \"" + fragment + "\"：" + message);
            }
        } catch (AssertionError error) {
            throw error;
        } catch (Throwable other) {
            throw new AssertionError(what + "：抛出的不是 IllegalArgumentException，而是 " + other);
        }
    }
}
