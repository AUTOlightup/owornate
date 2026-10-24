package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Map;

/** 信息卡片只拥有显示设置；运行状态由统一存储投影，不再单独复制。 */
final class OWOrnateWidgetStore {
    /** 无论负一屏上有几张信息卡片，都读同一份显示设置。 */
    private static final String SLOT = "recent.";

    private static boolean migrated;

    static final class Options {
        public final boolean showStatus, showTheme, showVersion;

        Options(boolean status, boolean theme, boolean version) {
            showStatus = status;
            showTheme = theme;
            showVersion = version;
        }
    }

    private OWOrnateWidgetStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("owornate_widget", 0);
    }

    /** code11 及更早按卡片分别存；全局槽还没有数据时挑一份搬过来，每个进程只检查一次。 */
    private static synchronized void migrate(Context context) {
        if (migrated) return;
        migrated = true;
        SharedPreferences p = prefs(context);
        if (p.contains(SLOT + "status")) return;
        String source = p.contains("default.status") ? "default." : null;
        if (source == null) {
            for (Map.Entry<String, ?> entry : p.getAll().entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("widget.") && key.endsWith(".status")) {
                    source = key.substring(0, key.length() - "status".length());
                    break;
                }
            }
        }
        if (source == null) return;
        p.edit()
                .putBoolean(SLOT + "status", p.getBoolean(source + "status", true))
                .putBoolean(SLOT + "theme", p.getBoolean(source + "theme", true))
                .putBoolean(SLOT + "version", p.getBoolean(source + "version", true))
                .commit();
    }

    static Options options(Context context) {
        migrate(context);
        SharedPreferences p = prefs(context);
        return new Options(
                p.getBoolean(SLOT + "status", true),
                p.getBoolean(SLOT + "theme", true),
                p.getBoolean(SLOT + "version", true));
    }

    static synchronized void saveOptions(Context context, Options options) throws IOException {
        migrate(context);
        boolean saved = prefs(context).edit()
                .putBoolean(SLOT + "status", options.showStatus)
                .putBoolean(SLOT + "theme", options.showTheme)
                .putBoolean(SLOT + "version", options.showVersion)
                .commit();
        if (!saved) throw new IOException("组件设置未保存，请重试");
    }

    /** 卡片显示用的莫奈快照：只取已确认的值，不读编辑中的草稿。 */
    static OWOrnateWidgetSnapshot snapshot(Context context) {
        StatusObservation<ModuleState> observation = RuntimeStateStore.monet(context);
        ModuleState state = observation.value;
        return new OWOrnateWidgetSnapshot(
                state == null ? null : state.kind,
                state != null && state.activeKnown && observation.confirmedAt > 0,
                state != null && state.active,
                state == null ? null : state.theme,
                observation.confirmedAt,
                observation.error);
    }
}
