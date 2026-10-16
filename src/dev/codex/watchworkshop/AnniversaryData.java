package dev.codex.watchworkshop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.CRC32;

/**
 * 系统快应用“纪念日”（com.nearme.watch.datecalculate 1.1.6）的数据：只读解析和选取规则，
 * 不依赖 Android（org.json 除外），可在 JVM 上直接测试。
 *
 * <p>纪念日用快应用的 system.storage 存数据，引擎把它落成一份 MMKV 文件：
 * <ul>
 *   <li>文件头 4 字节小端长度 N，随后 N 字节内容；</li>
 *   <li>内容先是一个 varint（条目数占位），之后依次追加 [varint 键长][键][varint 值长][值]，
 *       同一个键以最后一次为准，值为空表示已删除；字符串值里再包一层 [varint 长度][UTF-8]；</li>
 *   <li>旁边 .crc 文件开头 4 字节小端是内容的 CRC32，对不上就是读到了写了一半的数据。</li>
 * </ul>
 * 纪念日只用一个键 {@code dateArr}，值是 JSON 数组
 * {@code [{timestamp, dateTitle, createdAt, updatedAt}]}，timestamp 是所选日期当地零点的毫秒数。
 * 条目没有 id，用 createdAt 当作稳定标识。
 */
final class AnniversaryData {

    static final String KEY = "dateArr";

    private AnniversaryData() {
    }

    /** CRC 对不上：读到的是正在写入的数据，稍后重读即可。 */
    static final class TornReadException extends IOException {
        TornReadException() {
            super("纪念日数据正在写入");
        }
    }

    static final class Entry {
        final String id;
        final String title;
        final LocalDate date;
        /** 纪念日里添加这一条的日子；进度条从这天起算。可能为空。 */
        final LocalDate added;

        Entry(String id, String title, LocalDate date, LocalDate added) {
            this.id = id;
            this.title = title;
            this.date = date;
            this.added = added;
        }
    }

    /**
     * 从 MMKV 文件和它的 .crc 里取出 dateArr 的 JSON 文本。
     * 没有这个键或已删除返回 null（纪念日从没存过，等同于没有日子）。
     */
    static String storedJson(byte[] data, byte[] meta) throws IOException {
        if (data == null || data.length < 4) throw new IOException("纪念日数据文件不完整");
        if (meta == null || meta.length < 4) throw new IOException("纪念日校验文件不完整");
        long size = le32(data, 0);
        if (size > data.length - 4L) throw new IOException("纪念日数据长度不符");
        int end = 4 + (int) size;
        CRC32 crc = new CRC32();
        crc.update(data, 4, (int) size);
        if (crc.getValue() != le32(meta, 0)) throw new TornReadException();

        int[] at = {4};
        if (at[0] < end) varint(data, at, end);
        String value = null;
        while (at[0] < end) {
            int keyLength = length(data, at, end);
            String key = new String(data, at[0], keyLength, StandardCharsets.UTF_8);
            at[0] += keyLength;
            int valueLength = length(data, at, end);
            if (KEY.equals(key)) {
                if (valueLength == 0) {
                    value = null;
                } else {
                    int[] inner = {at[0]};
                    int valueEnd = at[0] + valueLength;
                    int textLength = length(data, inner, valueEnd);
                    value = new String(data, inner[0], textLength, StandardCharsets.UTF_8);
                }
            }
            at[0] += valueLength;
        }
        return value;
    }

    /**
     * dateArr 的 JSON 转成条目，按日期升序（和纪念日自己的排序一样）。
     * 单条缺日期的跳过；整体不是数组时报错，调用方保留上次读到的结果。
     */
    static List<Entry> entries(String json, ZoneId zone) throws IOException {
        List<Entry> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException invalid) {
            throw new IOException("纪念日数据格式无法识别");
        }
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item == null || !(item.opt("timestamp") instanceof Number)) continue;
            long stamp = item.optLong("timestamp");
            long created = item.opt("createdAt") instanceof Number ? item.optLong("createdAt") : 0L;
            String title = CountdownModel.title(item.optString("dateTitle", ""));
            if (title.isEmpty()) title = "纪念日";
            String id = created > 0L ? Long.toString(created) : "t" + stamp + ":" + title;
            list.add(new Entry(id, title, day(stamp, zone), created > 0L ? day(created, zone) : null));
        }
        Collections.sort(list, (a, b) -> a.date.compareTo(b.date));
        return list;
    }

    /**
     * 卡片显示哪一条。pick 非空时按标识找，找不到（纪念日里删了）返回 null；
     * pick 为空是“自动”：最近一个今天或以后的日子，没有的话取最近过去的那一个。
     * yearly 告诉这里哪些条目设了每年重复（按下一次出现的日子比较）。
     */
    static Entry pick(List<Entry> list, String pick, Predicate<String> yearly, LocalDate today) {
        if (list == null || list.isEmpty()) return null;
        if (pick != null && !pick.isEmpty()) {
            for (Entry entry : list) if (entry.id.equals(pick)) return entry;
            return null;
        }
        Entry upcoming = null, recent = null;
        LocalDate upcomingDate = null, recentDate = null;
        for (Entry entry : list) {
            LocalDate date = occurrence(entry, yearly, today);
            if (!date.isBefore(today)) {
                if (upcomingDate == null || date.isBefore(upcomingDate)) {
                    upcoming = entry;
                    upcomingDate = date;
                }
            } else if (recentDate == null || date.isAfter(recentDate)) {
                recent = entry;
                recentDate = date;
            }
        }
        return upcoming != null ? upcoming : recent;
    }

    /** 列表顺序：今天和以后的按日期近到远，过去的接在后面，按最近到最远。 */
    static List<Entry> ordered(List<Entry> list, Predicate<String> yearly, LocalDate today) {
        List<Entry> ahead = new ArrayList<>(), behind = new ArrayList<>();
        for (Entry entry : list) {
            (occurrence(entry, yearly, today).isBefore(today) ? behind : ahead).add(entry);
        }
        Collections.sort(ahead, (a, b) -> occurrence(a, yearly, today).compareTo(occurrence(b, yearly, today)));
        Collections.sort(behind, (a, b) -> occurrence(b, yearly, today).compareTo(occurrence(a, yearly, today)));
        ahead.addAll(behind);
        return ahead;
    }

    /** 一条纪念日对应的倒计时配置：没有时刻，进度从添加那天起算，过去的日子是“已有 N 天”。 */
    static CountdownModel.Config config(Entry entry, boolean yearly, boolean progress) {
        return new CountdownModel.Config(entry.title, entry.date.toString(), "", yearly, progress, "",
                entry.added == null ? "" : entry.added.toString(), CountdownModel.SYSTEM_ZONE, true);
    }

    private static LocalDate occurrence(Entry entry, Predicate<String> yearly, LocalDate today) {
        return yearly != null && yearly.test(entry.id)
                ? CountdownModel.yearlyOccurrence(entry.date, today) : entry.date;
    }

    private static LocalDate day(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    private static long le32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL) | (bytes[offset + 1] & 0xFFL) << 8
                | (bytes[offset + 2] & 0xFFL) << 16 | (bytes[offset + 3] & 0xFFL) << 24;
    }

    /** varint 长度字段，并确认后面真有这么多字节。 */
    private static int length(byte[] bytes, int[] at, int end) throws IOException {
        long value = varint(bytes, at, end);
        if (value > end - at[0]) throw new IOException("纪念日数据条目越界");
        return (int) value;
    }

    private static long varint(byte[] bytes, int[] at, int end) throws IOException {
        long value = 0L;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= end) throw new IOException("纪念日数据条目不完整");
            int b = bytes[at[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("纪念日数据条目损坏");
    }
}
