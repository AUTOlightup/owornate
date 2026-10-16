package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 倒计时小组件的配置，全局只有一份：负一屏上无论放几张倒计时卡片，都读这一份，卡片增删不影响它。
 * 这里只存用户的选择（自己设置的内容，或纪念日模式选了哪一条），本地读写；
 * 纪念日数据本身的镜像在 {@link AnniversaryStore}。
 */
final class CountdownStore {

    private static final String FILE = "countdown_widget";
    private static final String SLOT = "recent.";
    private static final String[] TEXT = {"title", "target", "time", "start", "created", "zone"};
    private static final String[] FLAG = {"yearly", "progress"};
    private static boolean migrated;

    private CountdownStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, 0);
    }

    /**
     * code11 及更早按卡片分别存（"default." 或 "widget.&lt;id&gt;."）。全局槽还没有数据时挑一份搬过来；
     * 每个进程只检查一次。
     */
    private static synchronized void migrate(Context context) {
        if (migrated) {
            return;
        }
        migrated = true;
        SharedPreferences p = prefs(context);
        if (p.contains(SLOT + "title")) {
            return;
        }
        String source = p.contains("default.title") ? "default." : null;
        if (source == null) {
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("widget.") && key.endsWith(".title")) {
                    source = key.substring(0, key.length() - "title".length());
                    break;
                }
            }
        }
        if (source == null) {
            return;
        }
        SharedPreferences.Editor editor = p.edit();
        for (String name : TEXT) {
            editor.putString(SLOT + name, p.getString(source + name, ""));
        }
        for (String name : FLAG) {
            editor.putBoolean(SLOT + name, p.getBoolean(source + name, false));
        }
        editor.commit();
    }

    /** 读配置；没有保存过时是全空配置（卡片显示“未设置”）。 */
    static CountdownModel.Config config(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        return new CountdownModel.Config(
                p.getString(SLOT + "title", ""),
                p.getString(SLOT + "target", ""),
                p.getString(SLOT + "time", ""),
                p.getBoolean(SLOT + "yearly", false),
                p.getBoolean(SLOT + "progress", false),
                p.getString(SLOT + "start", ""),
                p.getString(SLOT + "created", ""),
                p.getString(SLOT + "zone", CountdownModel.SYSTEM_ZONE));
    }

    /** 写入完整配置；设置页离开时自动保存走到这里。 */
    static synchronized void save(Context context, CountdownModel.Config value)
            throws IOException {
        migrate(context);
        boolean landed = prefs(context).edit()
                .putString(SLOT + "title", value.title)
                .putString(SLOT + "target", value.target)
                .putString(SLOT + "time", value.time)
                .putBoolean(SLOT + "yearly", value.yearly)
                .putBoolean(SLOT + "progress", value.progress)
                .putString(SLOT + "start", value.start)
                .putString(SLOT + "created", value.created)
                .putString(SLOT + "zone", value.zone)
                .commit();
        if (!landed) {
            throw new IOException("组件配置未保存，请重试");
        }
    }

    /**
     * 数据来源：自己设置，或显示系统快应用“纪念日”里的某一条（快应用模式）。
     * 纪念日模式下 {@code pick} 是条目标识（空表示自动选最近的日子），
     * 每年重复和进度按条目分别记，所以换着选也不会丢。
     */
    static final class Source {
        final boolean anniversary;
        final String pick;
        final Map<String, Integer> flags;

        static final int YEARLY = 1;
        static final int PROGRESS = 2;

        Source(boolean anniversary, String pick, Map<String, Integer> flags) {
            this.anniversary = anniversary;
            this.pick = pick == null ? "" : pick;
            this.flags = flags == null ? new HashMap<>() : new HashMap<>(flags);
        }

        boolean yearly(String id) {
            Integer bits = flags.get(id);
            return bits != null && (bits & YEARLY) != 0;
        }

        boolean progress(String id) {
            Integer bits = flags.get(id);
            return bits != null && (bits & PROGRESS) != 0;
        }

        Source with(boolean anniversary, String pick) {
            return new Source(anniversary, pick, flags);
        }

        Source flag(String id, int bit, boolean on) {
            Map<String, Integer> next = new HashMap<>(flags);
            int bits = next.containsKey(id) ? next.get(id) : 0;
            bits = on ? bits | bit : bits & ~bit;
            if (bits == 0) next.remove(id); else next.put(id, bits);
            return new Source(anniversary, pick, next);
        }

        boolean same(Source other) {
            return other != null && anniversary == other.anniversary && pick.equals(other.pick)
                    && flags.equals(other.flags);
        }
    }

    private static final String ANNIV = "anniv.";

    static Source source(Context context) {
        SharedPreferences p = prefs(context);
        Map<String, Integer> flags = new HashMap<>();
        for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
            if (entry.getKey().startsWith(ANNIV) && entry.getValue() instanceof Integer) {
                flags.put(entry.getKey().substring(ANNIV.length()), (Integer) entry.getValue());
            }
        }
        return new Source("anniversary".equals(p.getString(SLOT + "source", "")),
                p.getString(SLOT + "pick", ""), flags);
    }

    static synchronized void saveSource(Context context, Source value) throws IOException {
        SharedPreferences p = prefs(context);
        SharedPreferences.Editor editor = p.edit()
                .putString(SLOT + "source", value.anniversary ? "anniversary" : "")
                .putString(SLOT + "pick", value.pick);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(ANNIV) && !value.flags.containsKey(key.substring(ANNIV.length()))) {
                editor.remove(key);
            }
        }
        for (Map.Entry<String, Integer> entry : value.flags.entrySet()) {
            editor.putInt(ANNIV + entry.getKey(), entry.getValue());
        }
        if (!editor.commit()) throw new IOException("数据来源未保存，请重试");
    }
}
