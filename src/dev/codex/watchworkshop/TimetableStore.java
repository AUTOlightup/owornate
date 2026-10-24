package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 全局唯一的一份课表，所有课表卡片共用；移除卡片不会删掉课表。
 *
 * <p>解析结果按原始 JSON 缓存：上课期间卡片每分钟重画一次，同一份课表只解析一次，
 * 保存新课表后原始文本变了才重新解析。code10 及更早按卡片分别存的数据，第一次读取时搬一份过来。
 */
final class TimetableStore {
    /** 单个课表 JSON 的上限（UTF-8 字节）；导入页用同一个值提前拦下。 */
    static final int MAX_JSON_BYTES = 1024 * 1024;

    private static final String SLOT = "recent.";
    private static boolean migrated;
    private static String cachedRaw;
    private static TimetableData cachedData;

    private TimetableStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("timetable_widgets", 0);
    }

    /** 全局槽还没有数据时，把旧的默认/逐卡片副本搬进来；每个进程只检查一次。 */
    private static synchronized void migrate(Context context) {
        if (migrated) return;
        migrated = true;
        SharedPreferences p = prefs(context);
        if (p.contains(SLOT + "json")) return;
        String raw = p.getString("default.json", null), zone = p.getString("default.zone", "system");
        if (raw == null) {
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("widget.") && key.endsWith(".json") && entry.getValue() instanceof String) {
                    raw = (String) entry.getValue();
                    zone = p.getString(key.substring(0, key.length() - 4) + "zone", "system");
                    break;
                }
            }
        }
        if (raw != null) p.edit().putString(SLOT + "json", raw).putString(SLOT + "zone", zone).commit();
    }

    static String readJson(Context context) {
        migrate(context);
        return prefs(context).getString(SLOT + "json", null);
    }

    /** 已保存的课表；没有导入过时为 null。 */
    static synchronized TimetableData read(Context context) throws Exception {
        String raw = readJson(context);
        if (raw == null) return null;
        if (!raw.equals(cachedRaw)) {
            cachedData = ShiguangJson.parse(raw, null);
            cachedRaw = raw;
        }
        return cachedData;
    }

    static ZoneId zone(Context context) {
        migrate(context);
        String zone = prefs(context).getString(SLOT + "zone", "system");
        return "system".equals(zone) ? ZoneId.systemDefault() : ZoneId.of(zone);
    }

    /**
     * 校验并保存一份新课表。override 为 null 时用文件里的开学日期；缺省字段按已保存的课表补齐。
     * 存下的是规范化后的 JSON，任何一步失败都不改动已保存的课表。
     */
    static synchronized void save(Context context, String raw, LocalDate override, String zone)
            throws Exception {
        if (raw == null || raw.getBytes("UTF-8").length > MAX_JSON_BYTES) {
            throw new IOException("请选择不超过 1 MB 的拾光课表 JSON");
        }
        TimetableData parsed = ShiguangJson.parse(raw, previous(context));
        LocalDate start = override == null ? parsed.semesterStart : override;
        if (start == null) throw new IOException("文件未含开学日期，请填写第 1 周的日期");
        if (!"system".equals(zone)) ZoneId.of(zone);
        String normalized = normalize(raw, parsed, start);
        // 规范化后的文本必须能独立解析（不依赖旧课表补缺省值）。
        ShiguangJson.parse(normalized, null);
        boolean saved = prefs(context).edit()
                .putString(SLOT + "json", normalized)
                .putString(SLOT + "zone", zone)
                .putBoolean(SLOT + "initialized", true)
                .commit();
        if (!saved) throw new IOException("课表未保存，请重试");
    }

    private static TimetableData previous(Context context) {
        try {
            return read(context);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 把开学日期、学期周数、每周第一天和实际生效的节次写回 JSON。上游没给 timeSlots 时沿用的是
     * 旧课表的节次，所以也要一并存下。
     */
    private static String normalize(String raw, TimetableData parsed, LocalDate start) throws JSONException {
        JSONObject normalized = new JSONObject(raw);
        JSONObject config = normalized.optJSONObject("config");
        if (config == null) config = new JSONObject();
        config.put("semesterStartDate", start.toString())
                .put("semesterTotalWeeks", parsed.totalWeeks)
                .put("firstDayOfWeek", parsed.firstDayOfWeek);
        normalized.put("config", config);
        JSONArray slots = new JSONArray();
        for (TimetableData.Slot s : parsed.slots) {
            slots.put(new JSONObject()
                    .put("number", s.number)
                    .put("startTime", s.start.toString())
                    .put("endTime", s.end.toString()));
        }
        normalized.put("timeSlots", slots);
        return normalized.toString();
    }
}
