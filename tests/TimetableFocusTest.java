package dev.codex.watchworkshop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * TimetableFocus 的离线验证：焦点课、页脚、上下课边界与唤醒时刻，全部用固定日期与固定时区
 * （Asia/Shanghai），不读系统当前时间、不连设备、不写工程。
 *
 * <p>口径：一节课的边界是 [start,end)——起点算进行中，结束那一刻已经下课；页面焦点是"当天
 * 第一节还没结束的课"，当天没有就往后找，所以周日晚上自然落到明天周一；weeks 是显式周次集合，
 * 空集永不出现在课表里；组合作息按日期换季，无命中回退基准节次。
 *
 * <p>用法：java -cp &lt;classes&gt;;&lt;org.json.jar&gt; dev.codex.watchworkshop.TimetableFocusTest [fixtureDir]。
 * fixtureDir 省略时看环境变量 WATCHWORKSHOP_FIXTURES，再回落到当前工作目录下的 tests/fixtures。
 */
public final class TimetableFocusTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String COMBO = "fixture_synthetic_combo.json";

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
        System.out.println("时区: " + ZONE + "（固定，不使用系统当前时间）");
        System.out.println();

        run("排课：按开始时间排序、节次含端点、学期外不排课", TimetableFocusTest::lessonsOrderAndTimes);
        run("焦点：上课起点算进行中（起点含端点）", TimetableFocusTest::focusStartInclusive);
        run("焦点：下课那一刻已下课（结束排他）", TimetableFocusTest::focusEndExclusive);
        run("焦点：今天第一节、下一节、今天最后一节", TimetableFocusTest::todayFirstAndNext);
        run("焦点：今天上完后跨到本周下一门", TimetableFocusTest::afterLastLessonOfDay);
        run("焦点：周日晚上优先明天周一", TimetableFocusTest::sundayNightPrefersTomorrowMonday);
        run("焦点：空 weeks 的课当周不出现", TimetableFocusTest::emptyWeeksNeverShow);
        run("周次对齐：firstDayOfWeek 1 与 7", TimetableFocusTest::weekAlignment);
        run("周次：单单双双只在上课周出现", TimetableFocusTest::oddEvenWeeks);
        run("组合作息换季：含端点、无命中回退基准", TimetableFocusTest::comboSeasonSwitch);
        run("自定义时间课不依赖节次", TimetableFocusTest::customTimeLessons);
        run("焦点：跨多周后落到第 3 周", TimetableFocusTest::farFutureLesson);
        run("无后续：本学期课程已结束", TimetableFocusTest::noFollowUpAndSemesterEnd);
        run("学期前：显示第 1 周第一节，每天 00:00 唤醒", TimetableFocusTest::beforeSemester);
        run("没有课表或没有开学日期：提示导入", TimetableFocusTest::noSemesterStart);
        run("fixture 组合作息与焦点联动", TimetableFocusTest::comboFixtureIntegration);

        System.out.println();
        System.out.println("TimetableFocusTest: " + passed + " passed, " + failed + " failed, "
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

    private static void lessonsOrderAndTimes() {
        TimetableData data = weekdayTable();
        List<TimetableFocus.Lesson> monday = TimetableFocus.lessons(data, LocalDate.of(2026, 3, 2), ZONE);
        eq(2, monday.size(), "周一两节课");
        eq("早课", monday.get(0).course.name, "按开始时间排序：早课在前");
        eq("上午", monday.get(1).course.name, "按开始时间排序：上午在后");
        eq(LocalTime.of(8, 0), monday.get(0).start.toLocalTime(), "早课开始 08:00");
        eq(LocalTime.of(9, 40), monday.get(0).end.toLocalTime(), "早课结束 09:40（结束节次含端点）");
        eq(1, monday.get(0).week, "第 1 周");
        eq(ZONE, monday.get(0).start.getZone(), "课次用传入时区");
        eq(2, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 9), ZONE).size(), "第 2 周周一同样两节");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 7), ZONE).size(), "周六没有课");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 7, 20), ZONE).size(), "第 21 周（学期外）不排课");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 2, 23), ZONE).size(), "开学前不排课");
        eq(0, TimetableFocus.lessons(null, LocalDate.of(2026, 3, 2), ZONE).size(), "没有课表返回空列表");
    }

    private static void focusStartInclusive() {
        TimetableData data = weekdayTable();
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 2, 8, 0));
        check(state.focus != null, "08:00 正好上课 → 有焦点");
        eq("早课", state.focus.course.name, "焦点课程");
        check(state.ongoing, "起点含端点：08:00 视为进行中");
        check(state.today, "属于今天");
        eq("上课中", state.label, "标签");
        eq("09:40 下课", state.time, "进行中显示下课时间");
        eq(millis(2026, 3, 2, 9, 40), state.nextChange, "下次变化＝本节课下课");
    }

    private static void focusEndExclusive() {
        TimetableData data = weekdayTable();
        TimetableFocus.State before = TimetableFocus.at(data, at(2026, 3, 2, 9, 39));
        eq("早课", before.focus.course.name, "09:39 仍在早课");
        check(before.ongoing, "09:39 进行中");
        eq(millis(2026, 3, 2, 9, 40), before.nextChange, "09:39 的下次变化＝09:40 下课");

        TimetableFocus.State exact = TimetableFocus.at(data, at(2026, 3, 2, 9, 40));
        check(exact.focus != null, "09:40 仍有后续课");
        eq("上午", exact.focus.course.name, "结束那一刻（09:40）已下课，焦点切到下一节");
        check(!exact.ongoing, "结束那一刻不再算进行中");
        check(exact.today, "仍是今天");
        eq("下一节", exact.label, "标签");
        eq("10:00 上课", exact.time, "显示下一节上课时间");
        eq(millis(2026, 3, 2, 10, 0), exact.nextChange, "下次变化＝下一节上课");
    }

    private static void todayFirstAndNext() {
        TimetableData data = weekdayTable();
        TimetableFocus.State first = TimetableFocus.at(data, at(2026, 3, 2, 7, 0));
        eq("今天 第一节", first.label, "今天第一节标签");
        eq("08:00 上课", first.time, "今天第一节时间");
        eq("今天共 2 节 · 11:40 结束", first.footer, "今天第一节页脚");
        eq(millis(2026, 3, 2, 8, 0), first.nextChange, "下次变化＝第一节上课");
        check(!first.ongoing, "07:00 还没上课");

        TimetableFocus.State ongoing = TimetableFocus.at(data, at(2026, 3, 2, 9, 0));
        check(ongoing.ongoing, "09:00 早课进行中");
        eq("下一节 10:00 上午", ongoing.footer, "进行中页脚显示下一节");
        eq(millis(2026, 3, 2, 9, 40), ongoing.nextChange, "进行中下次变化＝本节课下课");

        TimetableFocus.State last = TimetableFocus.at(data, at(2026, 3, 2, 11, 0));
        eq("上午", last.focus.course.name, "11:00 焦点是第二节");
        check(last.ongoing, "11:00 进行中");
        eq("11:40 下课", last.time, "进行中显示下课");
        eq("今天最后一节", last.footer, "今天最后一节页脚");
        eq(millis(2026, 3, 2, 11, 40), last.nextChange, "下次变化＝本节课下课");
    }

    private static void afterLastLessonOfDay() {
        TimetableData data = weekdayTable();
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 2, 11, 40));
        check(state.focus != null, "11:40（结束排他）之后还有本周的课");
        eq("周三单周", state.focus.course.name, "跨到本周周三的单周课");
        check(!state.ongoing, "不是进行中");
        check(!state.today, "不是今天");
        eq("周三 第一节", state.label, "标签用星期");
        eq("周三共 1 节 · 08:45 结束", state.footer, "页脚");
        eq(millis(2026, 3, 3, 0, 0), state.nextChange, "下次变化＝次日 00:00");
    }

    private static void sundayNightPrefersTomorrowMonday() {
        TimetableData data = weekdayTable();
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 8, 21, 0));
        check(state.focus != null, "周日晚上应指向明天周一");
        eq("早课", state.focus.course.name, "周日 21:00 → 明天周一第一节");
        eq(LocalDate.of(2026, 3, 9), state.focus.start.toLocalDate(), "落点是周一（第 2 周）");
        eq(2, state.focus.week, "第 2 周");
        eq("明天 第一节", state.label, "标签：明天第一节");
        eq("08:00 上课", state.time, "时间");
        eq("明天共 2 节 · 11:40 结束", state.footer, "页脚");
        eq(millis(2026, 3, 9, 0, 0), state.nextChange, "下次变化＝明天 00:00");
        check(!state.ongoing && !state.today, "不是今天进行中的课");

        TimetableFocus.State lateSunday = TimetableFocus.at(data, at(2026, 3, 8, 23, 59));
        eq("早课", lateSunday.focus.course.name, "周日 23:59 仍指向明天周一第一节");
    }

    private static void emptyWeeksNeverShow() {
        TimetableData data = weekdayTable();
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 3), ZONE).size(),
                "weeks 为空的课当周不出现");
        for (TimetableFocus.Lesson lesson : TimetableFocus.lessons(data, LocalDate.of(2026, 3, 3), ZONE)) {
            check(!"周二空周".equals(lesson.course.name), "空 weeks 的课不排课");
        }
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 3, 9, 0));
        check(state.focus != null, "周二有后续课");
        eq("周三单周", state.focus.course.name, "跳过空 weeks 的课，落到周三单周课");
        eq("明天 第一节", state.label, "标签");
        eq("明天共 1 节 · 08:45 结束", state.footer, "页脚");
    }

    private static void weekAlignment() {
        TimetableData mondayFirst = table(LocalDate.of(2026, 3, 2), 1, 20,
                slots(slot(1, "08:00", "08:45")),
                courses(course("周日课", 7, 1, 1, 1)),
                noRules());
        eq(LocalDate.of(2026, 3, 2), mondayFirst.alignedStart(), "firstDay=1 对齐到周一");
        eq(0, TimetableFocus.lessons(mondayFirst, LocalDate.of(2026, 3, 1), ZONE).size(),
                "firstDay=1 时 03-01 属于第 0 周，不排课");
        eq(1, TimetableFocus.lessons(mondayFirst, LocalDate.of(2026, 3, 8), ZONE).size(),
                "firstDay=1 时第 1 周周日（03-08）有课");

        TimetableData sundayFirst = table(LocalDate.of(2026, 3, 2), 7, 20,
                slots(slot(1, "08:00", "08:45")),
                courses(course("周日课", 7, 1, 1, 1)),
                noRules());
        eq(LocalDate.of(2026, 3, 1), sundayFirst.alignedStart(), "firstDay=7 对齐到前一个周日");
        eq(1, sundayFirst.week(LocalDate.of(2026, 3, 1)), "对齐后的周日是第 1 周");
        eq(1, TimetableFocus.lessons(sundayFirst, LocalDate.of(2026, 3, 1), ZONE).size(),
                "firstDay=7 时第 1 周周日（03-01）有课");
        eq(0, TimetableFocus.lessons(sundayFirst, LocalDate.of(2026, 3, 8), ZONE).size(),
                "firstDay=7 时第 2 周周日没有课");
        TimetableFocus.State state = TimetableFocus.at(sundayFirst, at(2026, 3, 1, 7, 0));
        check(state.focus != null, "firstDay=7 时对齐周的周日照常显示");
        eq("周日课", state.focus.course.name, "焦点课程");
        eq("今天 第一节", state.label, "标签");
        eq("08:00 上课", state.time, "时间");
    }

    private static void oddEvenWeeks() {
        TimetableData data = weekdayTable();
        eq(1, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 4), ZONE).size(), "第 1 周周三有单周课");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 11), ZONE).size(), "第 2 周周三没有单周课");
        eq(1, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 18), ZONE).size(), "第 3 周周三有单周课");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 5), ZONE).size(), "第 1 周周四没有双周课");
        eq(1, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 12), ZONE).size(), "第 2 周周四有双周课");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 3, 19), ZONE).size(), "第 3 周周四没有双周课");
    }

    private static void comboSeasonSwitch() {
        List<TimetableData.Slot> base = slots(slot(1, "08:00", "08:45"), slot(2, "08:55", "09:40"));
        List<TimetableData.Rule> rules = rules(
                rule("2026-03-02", "2026-04-30", slot(1, "08:00", "08:45"), slot(2, "08:55", "09:40")),
                rule("2026-05-01", "2026-06-30", slot(1, "08:00", "08:40"), slot(2, "08:50", "09:30")));
        List<TimetableData.Course> courses = new ArrayList<>();
        for (int day = 1; day <= 5; day++) {
            courses.add(course("课" + day, day, 1, 2, weeks(1, 20)));
        }
        TimetableData data = table(LocalDate.of(2026, 3, 2), 1, 20, base, courses, rules);

        eq(LocalTime.of(9, 40), onlyLesson(data, LocalDate.of(2026, 4, 27)).end.toLocalTime(), "春季作息");
        eq(LocalTime.of(9, 40), onlyLesson(data, LocalDate.of(2026, 4, 30)).end.toLocalTime(),
                "规则结束日含端点（仍是春季）");
        eq(LocalTime.of(9, 30), onlyLesson(data, LocalDate.of(2026, 5, 1)).end.toLocalTime(),
                "规则起始日含端点（已是夏季）");
        eq(LocalTime.of(9, 30), onlyLesson(data, LocalDate.of(2026, 6, 30)).end.toLocalTime(),
                "夏季作息最后一天");
        eq(LocalTime.of(9, 40), onlyLesson(data, LocalDate.of(2026, 7, 1)).end.toLocalTime(),
                "无匹配回退基准节次");
        eq(0, TimetableFocus.lessons(data, LocalDate.of(2026, 7, 20), ZONE).size(),
                "学期外（第 21 周）不排课");

        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 5, 1, 8, 20));
        check(state.ongoing, "换季当天 08:20 进行中");
        eq("课5", state.focus.course.name, "周五课");
        eq("09:30 下课", state.time, "换季后的下课时间取夏季作息");
        eq(millis(2026, 5, 1, 9, 30), state.nextChange, "下次变化＝夏季作息下课");
    }

    private static void customTimeLessons() {
        TimetableData data = table(LocalDate.of(2026, 3, 2), 1, 20,
                slots(slot(1, "08:00", "08:45")),
                courses(customCourse("晚课", 2, "19:00", "20:30", 1)),
                noRules());
        TimetableFocus.Lesson lesson = onlyLesson(data, LocalDate.of(2026, 3, 3));
        eq(LocalTime.of(19, 0), lesson.start.toLocalTime(), "自定义时间起点");
        eq(LocalTime.of(20, 30), lesson.end.toLocalTime(), "自定义时间终点");
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 3, 19, 30));
        check(state.ongoing, "自定义时间课进行中");
        eq("20:30 下课", state.time, "自定义时间下课");
        eq("今天最后一节", state.footer, "页脚");
        eq(millis(2026, 3, 3, 20, 30), state.nextChange, "下次变化＝自定义下课时间");
    }

    private static void farFutureLesson() {
        TimetableData data = table(LocalDate.of(2026, 3, 2), 1, 20,
                slots(slot(1, "08:00", "08:45")),
                courses(course("第三周周三", 3, 1, 1, 3)),
                noRules());
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 3, 2, 10, 0));
        check(state.focus != null, "往后找到第 3 周的课");
        eq(LocalDate.of(2026, 3, 18), state.focus.end.toLocalDate(), "落点 2026-03-18");
        eq(3, state.focus.week, "第 3 周");
        eq("3月18日 第一节", state.label, "标签用日期");
        eq("第 3 周 · 单周", state.footer, "页脚显示周次与单双");
    }

    private static void noFollowUpAndSemesterEnd() {
        TimetableData data = table(LocalDate.of(2026, 3, 2), 1, 1,
                slots(slot(1, "08:00", "08:45"), slot(2, "08:55", "09:40")),
                courses(course("唯一一节", 1, 1, 2, 1)),
                noRules());
        TimetableFocus.State inside = TimetableFocus.at(data, at(2026, 3, 3, 10, 0));
        check(inside.focus == null, "本周内已没有后续课 → 无焦点");
        eq("本学期课程已结束", inside.label, "标签");
        eq("点按导入新课表", inside.footer, "页脚提示导入新课表");
        eq("", inside.time, "时间为空");
        eq(millis(2026, 3, 4, 0, 0), inside.nextChange, "下次变化＝次日 00:00");

        TimetableFocus.State after = TimetableFocus.at(data, at(2026, 5, 1, 10, 0));
        check(after.focus == null, "学期结束后无焦点");
        eq("本学期课程已结束", after.label, "标签");
        eq(millis(2026, 5, 2, 0, 0), after.nextChange, "下次变化＝次日 00:00");
    }

    private static void beforeSemester() {
        TimetableData data = weekdayTable();
        TimetableFocus.State state = TimetableFocus.at(data, at(2026, 2, 20, 10, 0));
        check(state.focus != null, "学期前也给出第一节课");
        eq("早课", state.focus.course.name, "第一节课");
        eq(LocalDateTime.of(2026, 3, 2, 8, 0), state.focus.start.toLocalDateTime(), "落点是第 1 周周一 08:00");
        check(state.label.endsWith("第一节"), "标签以第一节结尾：" + state.label);
        eq("距开学 10 天", state.footer, "开学前 10 天显示倒计时");
        eq(millis(2026, 2, 21, 0, 0), state.nextChange, "学期前每天 00:00 唤醒");
        check(!state.ongoing && !state.today, "不是今天进行中的课");

        // 开学前一周内（距开学 <= 7 天）不再显示倒计时，改为显示该节课所属周次。
        TimetableFocus.State near = TimetableFocus.at(data, at(2026, 2, 27, 20, 0));
        check(near.focus != null, "开学前 3 天仍给出第一节课");
        eq("早课", near.focus.course.name, "第一节课");
        eq("第 1 周 · 单周", near.footer, "开学前一周内显示周次与单双");
        eq(millis(2026, 2, 28, 0, 0), near.nextChange, "每天 00:00 唤醒");

        TimetableFocus.State lastDayBefore = TimetableFocus.at(data, at(2026, 3, 1, 22, 0));
        check(lastDayBefore.focus != null, "开学前一天晚上仍指向第 1 周周一");
        eq("明天 第一节", lastDayBefore.label, "标签：明天第一节");
    }

    private static void noSemesterStart() {
        TimetableFocus.State missing = TimetableFocus.at(null, at(2026, 3, 2, 8, 0));
        check(missing.focus == null, "没有课表 → 无焦点");
        eq("尚未导入课表", missing.label, "标签");
        eq("点按导入课程", missing.footer, "页脚");
        eq(Long.valueOf(Long.MAX_VALUE), Long.valueOf(missing.nextChange), "不安排唤醒");

        TimetableFocus.State undated = TimetableFocus.at(weekdayTable().withSemesterStart(null),
                at(2026, 3, 2, 8, 0));
        check(undated.focus == null, "没有开学日期 → 无焦点");
        eq("尚未导入课表", undated.label, "标签");
        eq(Long.valueOf(Long.MAX_VALUE), Long.valueOf(undated.nextChange), "不安排唤醒");
    }

    private static void comboFixtureIntegration() throws Exception {
        TimetableData data = ShiguangJson.parse(fixture(COMBO), null);
        eq(LocalTime.of(9, 40), onlyLesson(data, LocalDate.of(2026, 3, 2)).end.toLocalTime(),
                "fixture 春季作息（周一 1-2 节）");
        eq(LocalTime.of(9, 30), onlyLesson(data, LocalDate.of(2026, 5, 4)).end.toLocalTime(),
                "fixture 夏季作息（周一 1-2 节）");
        TimetableFocus.State spring = TimetableFocus.at(data, at(2026, 3, 2, 8, 30));
        eq("09:40 下课", spring.time, "fixture 春季作息下课时间");
        eq(1, spring.focus.week, "第 1 周");
        TimetableFocus.State summer = TimetableFocus.at(data, at(2026, 5, 4, 8, 30));
        eq("09:30 下课", summer.time, "fixture 夏季作息下课时间");
        eq(10, summer.focus.week, "第 10 周");
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

    private static TimetableData weekdayTable() {
        return table(LocalDate.of(2026, 3, 2), 1, 20,
                slots(slot(1, "08:00", "08:45"), slot(2, "08:55", "09:40"),
                        slot(3, "10:00", "10:45"), slot(4, "10:55", "11:40")),
                courses(
                        course("早课", 1, 1, 2, weeks(1, 20)),
                        course("上午", 1, 3, 4, weeks(1, 20)),
                        course("周三单周", 3, 1, 1, oddWeeks()),
                        course("周四双周", 4, 1, 1, evenWeeks()),
                        course("周二空周", 2, 1, 1, new int[0])),
                noRules());
    }

    private static TimetableData table(LocalDate semesterStart, int firstDayOfWeek, int totalWeeks,
                                       List<TimetableData.Slot> slots, List<TimetableData.Course> courses,
                                       List<TimetableData.Rule> rules) {
        return new TimetableData(courses, slots, rules, semesterStart, totalWeeks, firstDayOfWeek);
    }

    private static TimetableData.Slot slot(int number, String start, String end) {
        return new TimetableData.Slot(number, LocalTime.parse(start), LocalTime.parse(end));
    }

    private static TimetableData.Course course(String name, int day, int startSection, int endSection,
                                              int... weeks) {
        return new TimetableData.Course(name, "师", "地", day, startSection, endSection, weekSet(weeks),
                null, null);
    }

    private static TimetableData.Course customCourse(String name, int day, String start, String end,
                                                     int... weeks) {
        return new TimetableData.Course(name, "师", "地", day, 0, 0, weekSet(weeks),
                LocalTime.parse(start), LocalTime.parse(end));
    }

    private static TimetableData.Rule rule(String start, String end, TimetableData.Slot... slotValues) {
        return new TimetableData.Rule(LocalDate.parse(start), LocalDate.parse(end),
                Arrays.asList(slotValues));
    }

    private static List<TimetableData.Slot> slots(TimetableData.Slot... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<TimetableData.Course> courses(TimetableData.Course... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<TimetableData.Rule> rules(TimetableData.Rule... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<TimetableData.Rule> noRules() {
        return new ArrayList<TimetableData.Rule>();
    }

    private static Set<Integer> weekSet(int... weeks) {
        Set<Integer> out = new TreeSet<>();
        for (int week : weeks) {
            out.add(Integer.valueOf(week));
        }
        return out;
    }

    private static int[] weeks(int from, int to) {
        int[] out = new int[to - from + 1];
        for (int index = 0; index < out.length; index++) {
            out[index] = from + index;
        }
        return out;
    }

    private static int[] oddWeeks() {
        return new int[] {1, 3, 5, 7, 9, 11, 13, 15, 17, 19};
    }

    private static int[] evenWeeks() {
        return new int[] {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};
    }

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), ZONE);
    }

    private static long millis(int year, int month, int day, int hour, int minute) {
        return at(year, month, day, hour, minute).toInstant().toEpochMilli();
    }

    private static TimetableFocus.Lesson onlyLesson(TimetableData data, LocalDate date) {
        List<TimetableFocus.Lesson> lessons = TimetableFocus.lessons(data, date, ZONE);
        if (lessons.size() != 1) {
            throw new AssertionError(date + " 期望正好 1 节课，实际 " + lessons.size() + " 节");
        }
        return lessons.get(0);
    }

    private static String fixture(String name) throws Exception {
        File file = new File(fixtureDir, name);
        if (!file.isFile()) {
            throw new AssertionError("缺少 fixture: " + file.getAbsolutePath());
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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
}
