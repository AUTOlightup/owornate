package dev.codex.watchworkshop;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * CountdownModel 的纯 JVM 测试（不连设备、不编译整个 APP）。
 *
 * <p>覆盖设计 v0.1 第 2 节要求：自然日差、跨年、闰年 2/29、夏令时、目标边界、过期、
 * 每年重复的跳年时机、进度区间与除零、时区、标题码点限制、刷新边界。
 */
public final class CountdownModelTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        naturalDay();
        timeOfDay();
        targetBoundary();
        nextUpdateBoundary();
        expired();
        crossYear();
        leapYear();
        yearlyRepeat();
        daylightSaving();
        timeZone();
        progress();
        emptyAndBroken();
        titleLimit();
        inputFormats();
        longDayCount();
        timerWidth();

        System.out.println("CountdownModelTest: PASS " + passed + " / FAIL " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 自然日
    // ------------------------------------------------------------------

    private static void naturalDay() {
        CountdownModel.Frame frame = CountdownModel.frame(
                config("2026-10-16", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 23, 59), SINGAPORE);
        check("自然日 / 前一天显示还有 1 天", 1L, frame.days);
        check("自然日 / 状态是正常", CountdownModel.STATE_NORMAL, frame.state);
        check("自然日 / 前缀与单位", "还有|天", frame.prefix + "|" + frame.unit);
        check("自然日 / 数值", "1", frame.value);
        check("自然日 / 不用 Chronometer", false, frame.chronometer);
    }

    // ------------------------------------------------------------------
    // 时刻型
    // ------------------------------------------------------------------

    private static void timeOfDay() {
        CountdownModel.Config timed = config("2026-10-15", "09:30", false, false, "", "");
        CountdownModel.Frame soon = CountdownModel.frame(timed, at(SINGAPORE, 2026, 10, 15, 8, 0),
                SINGAPORE);
        check("时刻型 / 状态是倒计时", CountdownModel.STATE_COUNTDOWN, soon.state);
        check("时刻型 / 剩余毫秒", 90L * 60L * 1000L, soon.countdownMillis);
        check("时刻型 / 用 Chronometer", true, soon.chronometer);
        check("时刻型 / 底行写今天时刻", "今天 09:30", soon.detail);
        check("时刻型 / 下一次刷新就是目标时刻",
                at(SINGAPORE, 2026, 10, 15, 9, 30), soon.nextUpdate);

        CountdownModel.Frame passedToday = CountdownModel.frame(timed,
                at(SINGAPORE, 2026, 10, 15, 10, 0), SINGAPORE);
        check("时刻已过 / 就是今天", CountdownModel.STATE_TODAY, passedToday.state);
        check("时刻已过 / 数值是今天", "今天", passedToday.value);
        check("时刻已过 / 用强调色", CountdownModel.TONE_ACCENT, passedToday.tone);
        check("时刻已过 / 底行保留今天时刻", "今天 09:30", passedToday.detail);

        CountdownModel.Frame withoutTime = CountdownModel.frame(
                config("2026-10-15", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("当天无时刻 / 就是今天", CountdownModel.STATE_TODAY, withoutTime.state);
        check("当天无时刻 / 底行是日期", "10月15日", withoutTime.detail);
    }

    // ------------------------------------------------------------------
    // 目标边界
    // ------------------------------------------------------------------

    private static void targetBoundary() {
        CountdownModel.Config timed = config("2026-10-15", "09:30", false, false, "", "");
        long target = at(SINGAPORE, 2026, 10, 15, 9, 30);
        CountdownModel.Frame exact = CountdownModel.frame(timed, target, SINGAPORE);
        check("目标边界 / 刚好到点算就是今天", CountdownModel.STATE_TODAY, exact.state);
        CountdownModel.Frame almost = CountdownModel.frame(timed, target - 1L, SINGAPORE);
        check("目标边界 / 差 1 毫秒仍是倒计时", CountdownModel.STATE_COUNTDOWN, almost.state);
        check("目标边界 / 差 1 毫秒剩余 1ms", 1L, almost.countdownMillis);
        check("目标边界 / 差 1 毫秒仍然只等目标时刻", target, almost.nextUpdate);
    }

    // ------------------------------------------------------------------
    // 刷新边界
    // ------------------------------------------------------------------

    private static void nextUpdateBoundary() {
        CountdownModel.Frame tomorrow = CountdownModel.frame(
                config("2026-10-16", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 23, 30), SINGAPORE);
        check("刷新边界 / 正常状态等下一个本地午夜",
                at(SINGAPORE, 2026, 10, 16, 0, 0), tomorrow.nextUpdate);

        CountdownModel.Frame today = CountdownModel.frame(
                config("2026-10-15", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("刷新边界 / 就是今天也等下一个本地午夜",
                at(SINGAPORE, 2026, 10, 16, 0, 0), today.nextUpdate);

        CountdownModel.Frame expired = CountdownModel.frame(
                config("2026-10-05", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("刷新边界 / 过期卡片天数每天变，仍然等午夜",
                at(SINGAPORE, 2026, 10, 16, 0, 0), expired.nextUpdate);
    }

    // ------------------------------------------------------------------
    // 过期
    // ------------------------------------------------------------------

    private static void expired() {
        CountdownModel.Frame past = CountdownModel.frame(
                config("2026-10-05", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("过期 / 状态", CountdownModel.STATE_PAST, past.state);
        check("过期 / 已过 10 天", 10L, past.days);
        check("过期 / 数值", "10", past.value);
        check("过期 / 标签已结束", "已结束", past.badge);
        check("过期 / 数值改次要色", CountdownModel.TONE_SECONDARY, past.tone);
        check("过期 / 底行日期", "10月5日", past.detail);

        CountdownModel.Frame crossYear = CountdownModel.frame(
                config("2025-12-30", "", false, false, "", ""),
                at(SINGAPORE, 2026, 1, 2, 8, 0), SINGAPORE);
        check("过期 / 跨年已过 3 天", 3L, crossYear.days);
    }

    // ------------------------------------------------------------------
    // 跨年
    // ------------------------------------------------------------------

    private static void crossYear() {
        CountdownModel.Frame frame = CountdownModel.frame(
                config("2027-01-01", "", false, false, "", ""),
                at(SINGAPORE, 2026, 12, 31, 22, 0), SINGAPORE);
        check("跨年 / 还有 1 天", 1L, frame.days);
        check("跨年 / 底行带年份", "2027年1月1日", frame.detail);
        check("跨年 / 目标当晚仍是还有 1 天", CountdownModel.STATE_NORMAL, frame.state);

        CountdownModel.Frame midnight = CountdownModel.frame(
                config("2027-01-01", "", false, false, "", ""),
                at(SINGAPORE, 2027, 1, 1, 0, 30), SINGAPORE);
        check("跨年 / 零点后就是今天", CountdownModel.STATE_TODAY, midnight.state);
    }

    // ------------------------------------------------------------------
    // 闰年
    // ------------------------------------------------------------------

    private static void leapYear() {
        CountdownModel.Config yearly = config("2024-02-29", "", true, false, "", "");

        check("闰年 / 2026 目标夹到 2月28日", 1L,
                CountdownModel.frame(yearly, at(SINGAPORE, 2026, 2, 27, 9, 0), SINGAPORE).days);
        CountdownModel.Frame onClampedDay = CountdownModel.frame(yearly,
                at(SINGAPORE, 2026, 2, 28, 9, 0), SINGAPORE);
        check("闰年 / 夹过来的当天就是今天", CountdownModel.STATE_TODAY, onClampedDay.state);
        check("闰年 / 带每年标签", "每年", onClampedDay.badge);
        check("闰年 / 过当天才跳下一年 364 天", 364L,
                CountdownModel.frame(yearly, at(SINGAPORE, 2026, 3, 1, 9, 0), SINGAPORE).days);
        check("闰年 / 2028 是闰年回到 2月29日", 1L,
                CountdownModel.frame(yearly, at(SINGAPORE, 2028, 2, 28, 9, 0), SINGAPORE).days);
        check("闰年 / 2028-02-29 就是今天", CountdownModel.STATE_TODAY,
                CountdownModel.frame(yearly, at(SINGAPORE, 2028, 2, 29, 9, 0), SINGAPORE).state);
        check("闰年 / 2028 之后回到 2029-02-28", 364L,
                CountdownModel.frame(yearly, at(SINGAPORE, 2028, 3, 1, 9, 0), SINGAPORE).days);
    }

    // ------------------------------------------------------------------
    // 每年重复
    // ------------------------------------------------------------------

    private static void yearlyRepeat() {
        CountdownModel.Config yearly = config("2020-05-01", "", true, false, "", "");
        check("每年 / 当天不跳到下一年", CountdownModel.STATE_TODAY,
                CountdownModel.frame(yearly, at(SINGAPORE, 2026, 5, 1, 10, 0), SINGAPORE).state);
        check("每年 / 过当天跳下一年剩 364 天", 364L,
                CountdownModel.frame(yearly, at(SINGAPORE, 2026, 5, 2, 10, 0), SINGAPORE).days);
        check("每年 / 永远不进入已过状态", false,
                CountdownModel.frame(yearly, at(SINGAPORE, 2099, 5, 2, 10, 0), SINGAPORE).state
                        == CountdownModel.STATE_PAST);

        CountdownModel.Config future = config("2030-05-01", "", true, false, "", "");
        check("每年 / 首年之前不提前重复", 1460L,
                CountdownModel.frame(future, at(SINGAPORE, 2026, 5, 2, 10, 0), SINGAPORE).days);
    }

    // ------------------------------------------------------------------
    // 夏令时
    // ------------------------------------------------------------------

    private static void daylightSaving() {
        // 美国 2026-03-08 进入夏令时，本地日只有 23 小时；本地日差仍然按自然日算。
        CountdownModel.Frame spring = CountdownModel.frame(
                config("2026-03-08", "", false, false, "", ""),
                at(NEW_YORK, 2026, 3, 7, 23, 30), NEW_YORK);
        check("夏令时 / 进入夏令时当天还是还有 1 天", 1L, spring.days);
        check("夏令时 / 下一个本地午夜是本地 00:00",
                ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, NEW_YORK).toInstant().toEpochMilli(),
                spring.nextUpdate);
        check("夏令时 / 午夜本地时间", "2026-03-08T00:00",
                LocalDateTime.ofInstant(Instant.ofEpochMilli(spring.nextUpdate), NEW_YORK).toString());

        // 2026-11-01 回拨，本地日 25 小时。
        CountdownModel.Frame fall = CountdownModel.frame(
                config("2026-11-01", "", false, false, "", ""),
                at(NEW_YORK, 2026, 10, 31, 23, 30), NEW_YORK);
        check("夏令时 / 回拨当天还是还有 1 天", 1L, fall.days);
        check("夏令时 / 回拨后的下一个本地午夜",
                ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, NEW_YORK).toInstant().toEpochMilli(),
                fall.nextUpdate);
    }

    // ------------------------------------------------------------------
    // 时区
    // ------------------------------------------------------------------

    private static void timeZone() {
        long instant = at(UTC, 2026, 10, 14, 17, 0);
        CountdownModel.Config target = config("2026-10-16", "", false, false, "", "");
        check("时区 / 新加坡本地日是 10-15，还有 1 天", 1L,
                CountdownModel.frame(target, instant, SINGAPORE).days);
        check("时区 / 同一瞬间 UTC 还是 2 天", 2L,
                CountdownModel.frame(target, instant, UTC).days);

        CountdownModel.Config explicit = new CountdownModel.Config("目标", "2026-10-16", "",
                false, false, "", "", "UTC");
        check("时区 / 显式时区优先于手表时区", 2L,
                CountdownModel.frame(explicit, instant, SINGAPORE).days);
        CountdownModel.Config brokenZone = new CountdownModel.Config("目标", "2026-10-16", "",
                false, false, "", "", "Mars/Olympus");
        check("时区 / 读不出来的时区退回手表时区", 1L,
                CountdownModel.frame(brokenZone, instant, SINGAPORE).days);
    }

    // ------------------------------------------------------------------
    // 进度
    // ------------------------------------------------------------------

    private static void progress() {
        CountdownModel.Config range = config("2026-01-11", "", false, true, "2026-01-01", "");
        check("进度 / 一半是 50%", 50,
                CountdownModel.frame(range, at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE)
                        .progressPercent);
        check("进度 / 起始日之前是 0%", 0,
                CountdownModel.frame(range, at(SINGAPORE, 2025, 12, 31, 12, 0), SINGAPORE)
                        .progressPercent);
        check("进度 / 过期封顶 100%", 100,
                CountdownModel.frame(range, at(SINGAPORE, 2026, 2, 1, 12, 0), SINGAPORE)
                        .progressPercent);
        check("进度 / 关掉时不显示", -1,
                CountdownModel.frame(config("2026-01-11", "", false, false, "2026-01-01", ""),
                        at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE).progressPercent);
        check("进度 / 起始日等于目标日隐藏（不除零）", -1,
                CountdownModel.frame(config("2026-01-11", "", false, true, "2026-01-11", ""),
                        at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE).progressPercent);
        check("进度 / 起始日晚于目标日隐藏（不除零）", -1,
                CountdownModel.frame(config("2026-01-11", "", false, true, "2026-02-01", ""),
                        at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE).progressPercent);
        check("进度 / 起始日留空用创建当天", 50,
                CountdownModel.frame(config("2026-01-11", "", false, true, "", "2026-01-01"),
                        at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE).progressPercent);
        check("进度 / 没有起始日也不显示", -1,
                CountdownModel.frame(config("2026-01-11", "", false, true, "", ""),
                        at(SINGAPORE, 2026, 1, 6, 12, 0), SINGAPORE).progressPercent);
    }

    // ------------------------------------------------------------------
    // 空状态与坏配置
    // ------------------------------------------------------------------

    private static void emptyAndBroken() {
        CountdownModel.Frame blank = CountdownModel.frame(CountdownModel.Config.blank(),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("未配置 / 状态", CountdownModel.STATE_EMPTY, blank.state);
        check("未配置 / 主句", "请设置目标日期", blank.emptyMain);
        check("未配置 / 提示", "点按开始设置", blank.emptyHint);
        check("未配置 / 没有刷新边界", Long.MAX_VALUE, blank.nextUpdate);
        check("未配置 / 走空状态版式", true, blank.empty());

        CountdownModel.Frame broken = CountdownModel.frame(
                config("2026-13-40", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("坏配置 / 不可用状态", CountdownModel.STATE_ERROR, broken.state);
        check("坏配置 / 提示", "点按打开腕上工坊", broken.emptyHint);
        check("坏配置 / 没有刷新边界", Long.MAX_VALUE, broken.nextUpdate);

        CountdownModel.Frame unavailable = CountdownModel.unavailable(SINGAPORE);
        check("不可用 / 兜底画面", CountdownModel.STATE_ERROR, unavailable.state);

        check("nextUpdate / 无边界返回 Long.MAX_VALUE", Long.MAX_VALUE,
                CountdownModel.nextUpdate(CountdownModel.Config.blank(),
                        at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE));
    }

    // ------------------------------------------------------------------
    // 标题与输入格式
    // ------------------------------------------------------------------

    private static void titleLimit() {
        check("标题 / 去首尾空白", "国庆", CountdownModel.title("  国庆  "));
        check("标题 / 20 码点原样保留", "abcdefghijklmnopqrst",
                CountdownModel.title("abcdefghijklmnopqrst"));
        check("标题 / 超长截到 20 码点", "abcdefghijklmnopqrst",
                CountdownModel.title("abcdefghijklmnopqrstuvwxyz"));
        check("标题 / null 当空串", "", CountdownModel.title(null));

        String smile = new String(Character.toChars(0x1F600));
        StringBuilder many = new StringBuilder();
        for (int index = 0; index < 25; index++) {
            many.append(smile);
        }
        String truncated = CountdownModel.title(many.toString());
        check("标题 / 代理对按码点截断", 20,
                truncated.codePointCount(0, truncated.length()));
        char last = truncated.charAt(truncated.length() - 1);
        check("标题 / 截断不会留下半个代理对", false,
                last >= 0xD800 && last <= 0xDBFF);
    }

    private static void inputFormats() {
        check("输入 / 日期规范", "2026-10-18", CountdownModel.normalizeDate(" 2026-10-18 "));
        check("输入 / 日期非法", "", CountdownModel.normalizeDate("2026/10/18"));
        check("输入 / 时刻补零", "09:05", CountdownModel.normalizeTime("9:5"));
        check("输入 / 时刻非法", "", CountdownModel.normalizeTime("25:00"));
        check("输入 / 空时刻", "", CountdownModel.normalizeTime("   "));
        check("输入 / 空日期", "", CountdownModel.normalizeDate(""));
    }

    private static void longDayCount() {
        CountdownModel.Frame far = CountdownModel.frame(
                config("2046-10-15", "", false, false, "", ""),
                at(SINGAPORE, 2026, 10, 15, 12, 0), SINGAPORE);
        check("长天数 / 状态仍然是正常", CountdownModel.STATE_NORMAL, far.state);
        check("长天数 / 4 位天数一行放得下", 4, far.value.length());
        check("长天数 / 底行带年份", "2046年10月15日", far.detail);
    }

    // ------------------------------------------------------------------
    // 计时器行的量宽样本（字号由 provider 按实际宽度选，样本由模型给）
    // ------------------------------------------------------------------

    private static void timerWidth() {
        check("计时器量宽 / 超过 1 小时取 8 位样本", "23:59:59",
                CountdownModel.timerSample(23L * 3600000L + 59L * 60000L + 59000L));
        check("计时器量宽 / 刚好 1 小时取 8 位样本", "23:59:59",
                CountdownModel.timerSample(3600000L));
        check("计时器量宽 / 不足 1 小时取 5 位样本", "59:59",
                CountdownModel.timerSample(59L * 60000L));
        check("计时器量宽 / 刚好 10 分钟取 5 位样本", "59:59",
                CountdownModel.timerSample(600000L));
        check("计时器量宽 / 不足 10 分钟取 4 位样本", "9:59",
                CountdownModel.timerSample(9L * 60000L));
        check("计时器量宽 / 负数当 0 处理", "9:59", CountdownModel.timerSample(-1L));
        // provider 的字号档位兜底：等宽按 0.6em 估算，最小档 24sp 也必须放得下 8 位数字 + 前缀。
        check("计时器量宽 / 最小档 24sp 等宽兜底仍在 157dp 内",
                8f * 0.6f * 24f + 30f <= 157f);
    }

    // ------------------------------------------------------------------
    // 断言与工具
    // ------------------------------------------------------------------

    private static CountdownModel.Config config(String target, String time, boolean yearly,
                                               boolean progress, String start, String created) {
        return new CountdownModel.Config("目标", target, time, yearly, progress, start, created,
                CountdownModel.SYSTEM_ZONE);
    }

    private static long at(ZoneId zone, int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()
                .toEpochMilli();
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        report(name, same, "期望 " + expected + "，实际 " + actual);
    }

    private static void check(String name, boolean condition) {
        report(name, condition, "条件不成立");
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "：" + detail);
        }
    }
}
