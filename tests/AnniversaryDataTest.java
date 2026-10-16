package dev.codex.watchworkshop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * AnniversaryData 与纪念日口径倒计时的纯 JVM 测试。用合成的 MMKV 字节和合成条目，
 * 不连设备、不读任何真实纪念日数据。
 *
 * <p>用法：{@code java -cp <classes>;<org.json.jar> dev.codex.watchworkshop.AnniversaryDataTest}。
 */
public final class AnniversaryDataTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        storage();
        tornAndBroken();
        entries();
        picking();
        countUp();
        System.out.println("AnniversaryDataTest: PASS " + passed + " / FAIL " + failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // MMKV 存储
    // ------------------------------------------------------------------

    private static void storage() throws Exception {
        String first = "[{\"timestamp\":1,\"dateTitle\":\"甲\"}]";
        String second = "[{\"timestamp\":2,\"dateTitle\":\"乙\"}]";
        byte[][] file = mmkv(32768, kv("other", "x"), kv("dateArr", first), kv("dateArr", second));
        check("存储 / 同一个键取最后一次", second, AnniversaryData.storedJson(file[0], file[1]));

        byte[][] deleted = mmkv(4096, kv("dateArr", first), kvEmpty("dateArr"));
        check("存储 / 值为空表示已删除", null, AnniversaryData.storedJson(deleted[0], deleted[1]));

        byte[][] none = mmkv(4096, kv("other", "x"));
        check("存储 / 没有这个键", null, AnniversaryData.storedJson(none[0], none[1]));

        byte[][] empty = mmkv(4096);
        check("存储 / 只有占位的空文件", null, AnniversaryData.storedJson(empty[0], empty[1]));

        StringBuilder longTitle = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) longTitle.append(',');
            longTitle.append("{\"timestamp\":").append(i).append(",\"dateTitle\":\"很长的标题").append(i).append("\"}");
        }
        String big = longTitle.append(']').toString();
        byte[][] large = mmkv(8192, kv("dateArr", big));
        check("存储 / 多字节 varint 长度", big, AnniversaryData.storedJson(large[0], large[1]));
    }

    private static void tornAndBroken() throws Exception {
        byte[][] file = mmkv(4096, kv("dateArr", "[]"));
        byte[] torn = file[0].clone();
        torn[10] ^= 0x01;
        expect("存储 / CRC 对不上是写到一半", AnniversaryData.TornReadException.class,
                () -> AnniversaryData.storedJson(torn, file[1]));

        byte[] tooLong = file[0].clone();
        tooLong[0] = (byte) 0xFF;
        tooLong[1] = (byte) 0xFF;
        expect("存储 / 声明长度超过文件", IOException.class,
                () -> AnniversaryData.storedJson(tooLong, file[1]));

        // 键长越界：CRC 照真实内容重算，确认是格式检查拦下的。
        byte[] content = concat(varint(0x00FFFFFF), varint(200), "ab".getBytes(StandardCharsets.UTF_8));
        byte[][] broken = wrap(content, 4096);
        expect("存储 / 条目越界", IOException.class,
                () -> AnniversaryData.storedJson(broken[0], broken[1]));

        expect("存储 / 缺校验文件", IOException.class,
                () -> AnniversaryData.storedJson(file[0], new byte[0]));
    }

    // ------------------------------------------------------------------
    // 条目
    // ------------------------------------------------------------------

    private static void entries() throws Exception {
        String json = "[{\"timestamp\":" + midnight(2027, 6, 7) + ",\"dateTitle\":\"考试\",\"createdAt\":1000},"
                + "{\"timestamp\":" + midnight(2008, 6, 7) + ",\"dateTitle\":\"  \",\"createdAt\":2000},"
                + "{\"dateTitle\":\"缺日期\",\"createdAt\":3000},"
                + "{\"timestamp\":" + midnight(2026, 1, 1) + ",\"dateTitle\":\"无创建时间\"},"
                + "\"不是对象\"]";
        List<AnniversaryData.Entry> list = AnniversaryData.entries(json, ZONE);
        check("条目 / 跳过缺日期和非对象", 3, list.size());
        check("条目 / 按日期升序", "2008-06-07|2026-01-01|2027-06-07",
                list.get(0).date + "|" + list.get(1).date + "|" + list.get(2).date);
        check("条目 / 空标题显示纪念日", "纪念日", list.get(0).title);
        check("条目 / createdAt 当标识", "1000", list.get(2).id);
        check("条目 / 没有 createdAt 时退回日期加标题", true, list.get(1).id.startsWith("t"));
        check("条目 / 没有 createdAt 时没有添加日", null, list.get(1).added);
        check("条目 / 空串是空列表", 0, AnniversaryData.entries("", ZONE).size());
        expect("条目 / 不是数组", IOException.class, () -> AnniversaryData.entries("{\"a\":1}", ZONE));
    }

    private static void picking() throws Exception {
        String json = "[{\"timestamp\":" + midnight(2008, 6, 7) + ",\"dateTitle\":\"生日\",\"createdAt\":1},"
                + "{\"timestamp\":" + midnight(2026, 5, 16) + ",\"dateTitle\":\"接单\",\"createdAt\":2},"
                + "{\"timestamp\":" + midnight(2027, 6, 7) + ",\"dateTitle\":\"考试\",\"createdAt\":3},"
                + "{\"timestamp\":" + midnight(2026, 12, 20) + ",\"dateTitle\":\"初试\",\"createdAt\":4}]";
        List<AnniversaryData.Entry> list = AnniversaryData.entries(json, ZONE);
        LocalDate today = LocalDate.of(2026, 10, 16);
        Set<String> yearly = new HashSet<>();

        check("选取 / 自动取最近的未来日子", "初试",
                AnniversaryData.pick(list, "", yearly::contains, today).title);
        check("选取 / 按标识选", "生日", AnniversaryData.pick(list, "1", yearly::contains, today).title);
        check("选取 / 选中的删了返回空", null, AnniversaryData.pick(list, "99", yearly::contains, today));
        check("选取 / 空列表", null, AnniversaryData.pick(java.util.Collections.emptyList(), "", yearly::contains, today));

        yearly.add("2"); // 接单 每年 5 月 16 日：下一次 2027-05-16，仍晚于初试
        yearly.add("1"); // 生日 每年 6 月 7 日：下一次 2027-06-07
        check("选取 / 每年重复按下一次比较", "初试",
                AnniversaryData.pick(list, "", yearly::contains, LocalDate.of(2026, 10, 16)).title);
        check("选取 / 今天就是那天", "接单",
                AnniversaryData.pick(list, "", yearly::contains, LocalDate.of(2027, 5, 16)).title);

        Set<String> none = new HashSet<>();
        List<AnniversaryData.Entry> past = AnniversaryData.entries(
                "[{\"timestamp\":" + midnight(2008, 6, 7) + ",\"dateTitle\":\"远\",\"createdAt\":1},"
                        + "{\"timestamp\":" + midnight(2026, 5, 16) + ",\"dateTitle\":\"近\",\"createdAt\":2}]", ZONE);
        check("选取 / 没有未来日子时取最近过去的", "近",
                AnniversaryData.pick(past, "", none::contains, today).title);

        List<AnniversaryData.Entry> ordered = AnniversaryData.ordered(list, none::contains, today);
        StringBuilder order = new StringBuilder();
        for (AnniversaryData.Entry entry : ordered) order.append(entry.title).append(' ');
        check("排序 / 未来近到远，过去近到远", "初试 考试 接单 生日", order.toString().trim());
    }

    // ------------------------------------------------------------------
    // 纪念日口径的画面
    // ------------------------------------------------------------------

    private static void countUp() throws Exception {
        List<AnniversaryData.Entry> list = AnniversaryData.entries(
                "[{\"timestamp\":" + midnight(2026, 10, 6) + ",\"dateTitle\":\"入坑\",\"createdAt\":"
                        + midnight(2026, 10, 6) + "},"
                        + "{\"timestamp\":" + midnight(2026, 12, 20) + ",\"dateTitle\":\"初试\",\"createdAt\":"
                        + midnight(2026, 10, 1) + "}]", ZONE);
        long now = java.time.ZonedDateTime.of(2026, 10, 16, 9, 0, 0, 0, ZONE).toInstant().toEpochMilli();

        CountdownModel.Frame since = CountdownModel.frame(AnniversaryData.config(list.get(0), false, false), now, ZONE);
        check("画面 / 过去是已有 N 天", "已有|10|天", since.prefix + "|" + since.value + "|" + since.unit);
        check("画面 / 过去不标已结束", "", since.badge);
        check("画面 / 过去用主文字色", CountdownModel.TONE_PRIMARY, since.tone);

        CountdownModel.Frame ahead = CountdownModel.frame(AnniversaryData.config(list.get(1), false, true), now, ZONE);
        check("画面 / 未来是还有 N 天", "还有|65", ahead.prefix + "|" + ahead.value);
        check("画面 / 进度从添加那天算", 19, ahead.progressPercent);

        CountdownModel.Frame birthday = CountdownModel.frame(AnniversaryData.config(list.get(0), true, false), now, ZONE);
        check("画面 / 每年重复跳到明年", "还有|355|每年", birthday.prefix + "|" + birthday.value + "|" + birthday.badge);

        CountdownModel.Frame manual = CountdownModel.frame(new CountdownModel.Config("x", "2026-10-06", "",
                false, false, "", "", CountdownModel.SYSTEM_ZONE), now, ZONE);
        check("画面 / 自己设置的仍是已过 + 已结束", "已过|已结束", manual.prefix + "|" + manual.badge);

        CountdownModel.Frame notice = CountdownModel.notice("没有找到纪念日数据", "点按打开纪念日", ZONE);
        check("画面 / 提示走空状态", true, notice.empty());
        check("画面 / 提示没有刷新边界", Long.MAX_VALUE, notice.nextUpdate);
    }

    // ------------------------------------------------------------------
    // 合成 MMKV
    // ------------------------------------------------------------------

    private static byte[] kv(String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        byte[] inner = concat(varint(v.length), v);
        return concat(varint(k.length), k, varint(inner.length), inner);
    }

    private static byte[] kvEmpty(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        return concat(varint(k.length), k, varint(0));
    }

    /** 文件头 + ItemSizeHolder + 条目，补零到 fileSize；第二个元素是 .crc 的前 4 字节。 */
    private static byte[][] mmkv(int fileSize, byte[]... items) {
        return wrap(concat(varint(0x00FFFFFF), concat(items)), fileSize);
    }

    private static byte[][] wrap(byte[] content, int fileSize) {
        byte[] file = new byte[Math.max(fileSize, content.length + 4)];
        le32(file, 0, content.length);
        System.arraycopy(content, 0, file, 4, content.length);
        CRC32 crc = new CRC32();
        crc.update(content, 0, content.length);
        byte[] meta = new byte[64];
        le32(meta, 0, crc.getValue());
        meta[4] = 3;
        return new byte[][]{file, meta};
    }

    private static void le32(byte[] out, int offset, long value) {
        for (int i = 0; i < 4; i++) out[offset + i] = (byte) (value >>> (8 * i));
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    private static long midnight(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    private interface Call {
        Object run() throws Exception;
    }

    private static void expect(String name, Class<? extends Exception> type, Call call) {
        try {
            call.run();
            fail(name, "没有抛出 " + type.getSimpleName());
        } catch (Exception thrown) {
            if (type.isInstance(thrown)) pass(); else fail(name, "抛出了 " + thrown);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) pass();
        else fail(name, "期望 " + expected + "，实际 " + actual);
    }

    private static void pass() {
        passed++;
    }

    private static void fail(String name, String detail) {
        failed++;
        System.out.println("FAIL " + name + "：" + detail);
    }
}
