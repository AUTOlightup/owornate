package dev.codex.watchworkshop;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 倒计时卡片的全部计算规则（纯 java.time，没有任何 Android 依赖，可在 JVM 上直接测试）。
 *
 * <p>规则来源：负一屏小组件设计 v0.1 第 2 节。天数按自然日计算，也就是目标日期减今天
 * 日期，所以前一天就显示“还有 1 天”，不按 24 小时取整。日期按实例保存的时区计算，
 * 默认跟随手表（{@link #SYSTEM_ZONE}）。
 *
 * <p>本类只回答两件事：现在该显示什么（{@link Frame}），以及下一次必须重画是什么时候
 * （{@link Frame#nextUpdate}）。它不读不写配置、不申请闹钟、不轮询。只有“下一个本地午夜”
 * 和“目标时刻”两个刷新点；配置保存由调用方另外触发一次重画。
 */
public final class CountdownModel {

    /** 时区标记：跟随手表。保存成这个字符串，而不是保存当前时区 ID。 */
    public static final String SYSTEM_ZONE = "system";

    /** 标题上限，按 code point 计（不是 UTF-16 长度）。 */
    public static final int TITLE_MAX_CODEPOINTS = 20;

    /** 未配置 / 无数据。 */
    public static final int STATE_EMPTY = 0;
    /** 目标日在今天之后：还有 N 天。 */
    public static final int STATE_NORMAL = 1;
    /** 目标就是今天、设了时刻且还没到：Chronometer 倒计时。 */
    public static final int STATE_COUNTDOWN = 2;
    /** 目标就是今天，没设时刻或时刻已过：就是 今天。 */
    public static final int STATE_TODAY = 3;
    /** 目标日已过且不重复：已过 N 天。 */
    public static final int STATE_PAST = 4;
    /** 配置读不出来：暂时无法读取。 */
    public static final int STATE_ERROR = 5;

    /** 数值用主文字色（text-1）。 */
    public static final int TONE_PRIMARY = 0;
    /** 数值用次要文字色（text-2），已过状态用。 */
    public static final int TONE_SECONDARY = 1;
    /** 数值用倒计时强调色（“今天”）。 */
    public static final int TONE_ACCENT = 2;

    private CountdownModel() {
    }

    /**
     * 一个实例的配置原始值。{@code start} 为空表示未指定起始日，进度计算会退回
     * {@code created}（创建当天）；两者都为空时进度直接隐藏。
     */
    public static final class Config {
        public final String title;
        public final String target;
        public final String time;
        public final boolean yearly;
        public final boolean progress;
        public final String start;
        public final String created;
        public final String zone;
        /**
         * 纪念日口径：过去的日子是“已有 N 天”的正计时，不算“已结束”，数值用主文字色。
         * 自己设置的倒计时为 false。
         */
        public final boolean since;

        public Config(String title, String target, String time, boolean yearly, boolean progress,
                      String start, String created, String zone) {
            this(title, target, time, yearly, progress, start, created, zone, false);
        }

        public Config(String title, String target, String time, boolean yearly, boolean progress,
                      String start, String created, String zone, boolean since) {
            this.since = since;
            this.title = title == null ? "" : title;
            this.target = target == null ? "" : target;
            this.time = time == null ? "" : time;
            this.yearly = yearly;
            this.progress = progress;
            this.start = start == null ? "" : start;
            this.created = created == null ? "" : created;
            this.zone = zone == null || zone.isEmpty() ? SYSTEM_ZONE : zone;
        }

        /** 全空默认值：未配置实例和新组件默认设置的兜底。 */
        public static Config blank() {
            return new Config("", "", "", false, false, "", "", SYSTEM_ZONE);
        }

        /** 目标日期为空时这张卡片没有内容。 */
        public boolean configured() {
            return !target.trim().isEmpty();
        }
    }

    /** 一次渲染需要的全部文本与数值，以及下一次重画时刻。 */
    public static final class Frame {
        public final int state;
        public final int tone;
        /** 0-100；负数表示不显示进度。 */
        public final int progressPercent;
        /** 自然日差（绝对值）；非天数状态为 0。 */
        public final long days;
        public final String title;
        /** “每年” / “已结束”，空表示不显示胶囊。 */
        public final String badge;
        /** “还有” / “就是” / “已过”。 */
        public final String prefix;
        /** 天数或“今天”；Chronometer 状态为空。 */
        public final String value;
        /** “天”；其余状态为空。 */
        public final String unit;
        /** 底行左侧：目标日期，时刻型为“今天 HH:MM”。 */
        public final String detail;
        public final String emptyMain;
        public final String emptyHint;
        public final boolean chronometer;
        /** 距目标的剩余毫秒，只用于算 Chronometer 基点。 */
        public final long countdownMillis;
        /** 下一次必须重画的时刻；{@link Long#MAX_VALUE} 表示没有边界。 */
        public final long nextUpdate;
        public final ZoneId zone;

        Frame(int state, int tone, int progressPercent, long days, String title, String badge,
              String prefix, String value, String unit, String detail, String emptyMain,
              String emptyHint, boolean chronometer, long countdownMillis, long nextUpdate,
              ZoneId zone) {
            this.state = state;
            this.tone = tone;
            this.progressPercent = progressPercent;
            this.days = days;
            this.title = title;
            this.badge = badge;
            this.prefix = prefix;
            this.value = value;
            this.unit = unit;
            this.detail = detail;
            this.emptyMain = emptyMain;
            this.emptyHint = emptyHint;
            this.chronometer = chronometer;
            this.countdownMillis = countdownMillis;
            this.nextUpdate = nextUpdate;
            this.zone = zone;
        }

        /** 空状态（未配置或读不出来）：卡片走兜底版式。 */
        public boolean empty() {
            return state == STATE_EMPTY || state == STATE_ERROR;
        }

        public boolean progressVisible() {
            return progressPercent >= 0;
        }

        public boolean badgeVisible() {
            return !badge.isEmpty();
        }

        public boolean unitVisible() {
            return !unit.isEmpty();
        }
    }

    /** 算出这一刻该显示什么。nowMillis 用系统墙钟，watchZone 是手表当前时区。 */
    public static Frame frame(Config config, long nowMillis, ZoneId watchZone) {
        Config source = config == null ? Config.blank() : config;
        ZoneId zone = zone(source.zone, watchZone);
        String heading = title(source.title);
        if (!source.configured()) {
            return emptyFrame(heading, zone);
        }
        LocalDate stored = parseDate(source.target);
        if (stored == null) {
            return errorFrame(heading, zone);
        }

        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        LocalDate today = now.toLocalDate();
        // 每年重复：起点取“存储年份”和“今天年份”里较晚的一个，过当天才跳下一年。
        LocalDate target = source.yearly ? yearlyOccurrence(stored, today) : stored;
        LocalTime time = parseTime(source.time);
        long targetMillis = time == null
                ? 0L : target.atTime(time).atZone(zone).toInstant().toEpochMilli();
        long midnight = nextMidnight(today, zone);

        int state;
        int tone = TONE_PRIMARY;
        long days = 0L;
        long countdownMillis = 0L;
        boolean chronometer = false;
        String prefix;
        String value = "";
        String unit = "";
        String detail = dateText(target, today);
        String badge = source.yearly ? "每年" : "";

        if (target.isAfter(today)) {
            state = STATE_NORMAL;
            days = ChronoUnit.DAYS.between(today, target);
            prefix = "还有";
            value = String.valueOf(days);
            unit = "天";
        } else if (target.isEqual(today)) {
            // 时刻型只在目标当天出现；到点（含刚好等于）之后一律切到“就是今天”。
            if (time != null && targetMillis > nowMillis) {
                state = STATE_COUNTDOWN;
                prefix = "还有";
                chronometer = true;
                countdownMillis = targetMillis - nowMillis;
                detail = "今天 " + normalizeTime(source.time);
            } else {
                state = STATE_TODAY;
                prefix = "就是";
                value = "今天";
                tone = TONE_ACCENT;
                if (time != null) {
                    detail = "今天 " + normalizeTime(source.time);
                }
            }
        } else if (source.since) {
            // 纪念日：过去的日子照常显示，是“已有 N 天”的正计时。
            state = STATE_PAST;
            days = ChronoUnit.DAYS.between(target, today);
            prefix = "已有";
            value = String.valueOf(days);
            unit = "天";
        } else {
            state = STATE_PAST;
            days = ChronoUnit.DAYS.between(target, today);
            prefix = "已过";
            value = String.valueOf(days);
            unit = "天";
            tone = TONE_SECONDARY;
            badge = "已结束";
        }

        // 时刻型要在目标时刻切回“就是今天”，所以那一刻也是一次刷新边界。
        long next = state == STATE_COUNTDOWN ? Math.min(midnight, targetMillis) : midnight;
        return new Frame(state, tone, progressPercent(source, today, target), days, heading, badge,
                prefix, value, unit, detail, "", "", chronometer, countdownMillis, next, zone);
    }

    /** 只要下一次重画时刻。没有边界时返回 {@link Long#MAX_VALUE}。 */
    public static long nextUpdate(Config config, long nowMillis, ZoneId watchZone) {
        return frame(config, nowMillis, watchZone).nextUpdate;
    }

    /** 配置读不出来时的兜底画面。 */
    public static Frame unavailable(ZoneId watchZone) {
        return errorFrame("", zone(SYSTEM_ZONE, watchZone));
    }

    /** 没有可显示的日子时的提示画面（纪念日还没读到、找不到、选中的已删除）；走空状态版式。 */
    public static Frame notice(String main, String hint, ZoneId watchZone) {
        return new Frame(STATE_EMPTY, TONE_PRIMARY, -1, 0L, "", "", "", "", "", "",
                main == null ? "" : main, hint == null ? "" : hint, false, 0L, Long.MAX_VALUE,
                zone(SYSTEM_ZONE, watchZone));
    }

    private static Frame emptyFrame(String heading, ZoneId zone) {
        return new Frame(STATE_EMPTY, TONE_PRIMARY, -1, 0L, heading, "", "", "", "", "",
                "请设置目标日期", "点按开始设置", false, 0L, Long.MAX_VALUE, zone);
    }

    private static Frame errorFrame(String heading, ZoneId zone) {
        return new Frame(STATE_ERROR, TONE_PRIMARY, -1, 0L, heading, "", "", "", "", "",
                "暂时无法读取", "点按打开腕上工坊", false, 0L, Long.MAX_VALUE, zone);
    }

    /**
     * 进度百分比：(今天 − 起始日) ÷ (目标日 − 起始日)，取整到 1%。
     * 起始日不早于目标日时整段区间没有长度，直接隐藏进度，避免除零。
     */
    static int progressPercent(Config config, LocalDate today, LocalDate target) {
        if (config == null || !config.progress) {
            return -1;
        }
        LocalDate from = parseDate(config.start);
        if (from == null) {
            from = parseDate(config.created);
        }
        if (from == null || !from.isBefore(target)) {
            return -1;
        }
        long total = ChronoUnit.DAYS.between(from, target);
        long done = ChronoUnit.DAYS.between(from, today);
        long percent = Math.round(100.0d * done / total);
        return (int) Math.max(0L, Math.min(100L, percent));
    }

    /**
     * 每年重复的目标日：先看今年（不早于存储年份），已经过去才用下一年。
     * 当天算“今天”，也就是过当天才跳下一年。
     */
    static LocalDate yearlyOccurrence(LocalDate stored, LocalDate today) {
        int year = Math.max(stored.getYear(), today.getYear());
        LocalDate candidate = sameMonthDay(stored, year);
        if (candidate.isBefore(today)) {
            candidate = sameMonthDay(stored, year + 1);
        }
        return candidate;
    }

    /** 2 月 29 日在非闰年夹到 2 月 28 日（沿用常见的生日重复惯例），其余日月原样。 */
    static LocalDate sameMonthDay(LocalDate stored, int year) {
        int month = stored.getMonthValue();
        int day = stored.getDayOfMonth();
        if (month == 2 && day == 29 && !Year.isLeap(year)) {
            return LocalDate.of(year, 2, 28);
        }
        return LocalDate.of(year, month, day);
    }

    /**
     * 下一个本地午夜的绝对时刻。用 atStartOfDay(zone) 而不是加 24 小时：
     * 夏令时切换那天本地日长不是 24 小时。
     */
    static long nextMidnight(LocalDate today, ZoneId zone) {
        return today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** 时区选择：显式存的合法时区优先，system 或读不出来时跟随手表。 */
    public static ZoneId zone(String zone, ZoneId watchZone) {
        if (zone != null && !zone.isEmpty() && !SYSTEM_ZONE.equals(zone)) {
            try {
                return ZoneId.of(zone);
            } catch (RuntimeException unknown) {
                // 存了本机不认识的时区 ID 时退回手表时区，不让卡片整体不可用。
            }
        }
        return watchZone == null ? ZoneId.systemDefault() : watchZone;
    }

    /** 标题：去首尾空白，按 code point 截到 TITLE_MAX_CODEPOINTS 个。 */
    public static String title(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.codePointCount(0, text.length()) <= TITLE_MAX_CODEPOINTS) {
            return text;
        }
        // 按 code point 截断：辅助平面字符不会被切成半个代理对。
        return text.substring(0, text.offsetByCodePoints(0, TITLE_MAX_CODEPOINTS));
    }

    /** 日期输入规范成 yyyy-MM-dd；空或非法返回空串。 */
    public static String normalizeDate(String raw) {
        LocalDate date = parseDate(raw);
        return date == null ? "" : date.toString();
    }

    /** 时刻输入规范成 HH:mm；空或非法返回空串。 */
    public static String normalizeTime(String raw) {
        LocalTime time = parseTime(raw);
        if (time == null) {
            return "";
        }
        return pad(time.getHour()) + ":" + pad(time.getMinute());
    }

    /** 日期行文本；跨年时带年份。 */
    public static String dateText(LocalDate date, LocalDate today) {
        String monthDay = date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        return date.getYear() == today.getYear() ? monthDay : date.getYear() + "年" + monthDay;
    }

    /**
     * 时刻型可能显示的最宽文本，用来在渲染前量宽定字号。
     *
     * <p>系统 Chronometer 的格式是 H:MM:SS（不足一小时是 M:SS），剩余时间最多 23:59:59，
     * 所以按剩余时长取这一刻可能出现的更长那一档，避免走字过程中突然变宽而溢出。
     */
    public static String timerSample(long countdownMillis) {
        long seconds = countdownMillis > 0L ? countdownMillis / 1000L : 0L;
        if (seconds >= 3600L) {
            return "23:59:59";
        }
        if (seconds >= 600L) {
            return "59:59";
        }
        return "9:59";
    }

    static LocalDate parseDate(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    /** 时刻解析：接受 HH:mm，也接受 9:30 / 9:5 这种没补零的写法。 */
    static LocalTime parseTime(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        int colon = text.indexOf(':');
        if (colon > 0 && text.indexOf(':', colon + 1) < 0) {
            String hour = text.substring(0, colon).trim();
            String minute = text.substring(colon + 1).trim();
            if (hour.length() == 1) {
                hour = "0" + hour;
            }
            if (minute.length() == 1) {
                minute = "0" + minute;
            }
            text = hour + ":" + minute;
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    private static String pad(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
