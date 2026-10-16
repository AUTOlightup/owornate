package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * 纪念日数据在本应用里的镜像：最近一次读成功的 dateArr 原文、读取结果和时刻。
 * 卡片渲染只读这里，不在渲染路径上碰 root；真正去读纪念日的是 {@link AnniversarySync}。
 */
final class AnniversaryStore {
    /** 还没读过。 */
    static final String UNKNOWN = "";
    static final String OK = "ok";
    /** 纪念日没装，或从没打开过（还没有数据文件）。 */
    static final String MISSING = "missing";
    /** 文件在，但格式认不出来或一直读到半截。 */
    static final String UNREADABLE = "unreadable";
    /** 本机授权通道不可用。 */
    static final String NO_ACCESS = "denied";

    private static final String FILE = "anniversary_mirror";

    static final class Snapshot {
        final String state;
        /** null 表示手上没有可用的数据（从没读成功，或纪念日不在了）。 */
        final List<AnniversaryData.Entry> entries;
        final long readAt;
        final String error;

        Snapshot(String state, List<AnniversaryData.Entry> entries, long readAt, String error) {
            this.state = state;
            this.entries = entries;
            this.readAt = readAt;
            this.error = error;
        }
    }

    private static String memoJson;
    private static ZoneId memoZone;
    private static List<AnniversaryData.Entry> memo;

    private AnniversaryStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, 0);
    }

    static synchronized Snapshot snapshot(Context context) {
        SharedPreferences p = prefs(context);
        String state = p.getString("state", UNKNOWN);
        String json = p.getString("json", null);
        long readAt = p.getLong("readAt", 0L);
        String error = p.getString("error", "");
        if (json == null) return new Snapshot(state, null, readAt, error);
        ZoneId zone = ZoneId.systemDefault();
        if (!json.equals(memoJson) || !zone.equals(memoZone)) {
            try {
                memo = Collections.unmodifiableList(AnniversaryData.entries(json, zone));
            } catch (IOException invalid) {
                memo = null;
            }
            memoJson = json;
            memoZone = zone;
        }
        if (memo == null) return new Snapshot(UNREADABLE, null, readAt, "纪念日数据格式无法识别");
        return new Snapshot(state, memo, readAt, error);
    }

    /**
     * 记下一次读取的结果。json 为 null 时保留上次读成功的内容（读失败不清空卡片）；
     * MISSING 会清空，因为纪念日已经不在了。返回卡片需不需要重画。
     */
    static synchronized boolean record(Context context, String state, String json, String error) {
        SharedPreferences p = prefs(context);
        String oldJson = p.getString("json", null);
        String oldState = p.getString("state", UNKNOWN);
        String nextJson = MISSING.equals(state) ? null : json != null ? json : oldJson;
        SharedPreferences.Editor editor = p.edit()
                .putString("state", state)
                .putLong("readAt", System.currentTimeMillis())
                .putString("error", error == null ? "" : error);
        if (nextJson == null) editor.remove("json"); else editor.putString("json", nextJson);
        editor.apply();
        boolean sameJson = nextJson == null ? oldJson == null : nextJson.equals(oldJson);
        return !sameJson || !state.equals(oldState);
    }
}
