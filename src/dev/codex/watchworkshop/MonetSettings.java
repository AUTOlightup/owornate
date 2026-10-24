package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Package-owned choices backed by the existing acknowledged Launcher theme protocol. */
final class MonetSettings {
    static final String PLUGIN_ID = "dev.owornate.monet";

    static PluginManifest.Setting definition(Context context, String key) throws IOException {
        return definition(ExternalPlugins.repository(context).load(PLUGIN_ID), key);
    }

    static PluginManifest.Setting definition(PluginManifest manifest, String key) {
        if (manifest != null && PLUGIN_ID.equals(manifest.id)) {
            for (PluginManifest.Setting item : manifest.nativeSettings) {
                if (key.equals(item.key)) return item;
            }
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        if ("mode".equals(key)) {
            options.put("auto", "自动取色");
            options.put("fixed", "固定颜色");
            options.put("original", "系统原色");
            return new PluginManifest.Setting(key, "配色方式", "auto", Collections.unmodifiableMap(options));
        }
        if (!"corners".equals(key)) throw new IllegalArgumentException("不支持的莫奈设置");
        options.put("true", "开启");
        options.put("false", "关闭");
        return new PluginManifest.Setting(key, "连续圆角", "true", Collections.unmodifiableMap(options));
    }

    /** Read only after the module was observed enabled; declarations never supply runtime values. */
    static Map<String, String> config(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                JSONObject ack = ThemeRuntime.exchange(context, "read", null);
                persistConfirmed(context, ack);
                return values(ThemeRuntime.config(ack.getJSONObject("state")));
            } catch (Exception problem) {
                RuntimeStateStore.failMonet(context, problem);
                throw problem;
            }
        }
    }

    static Map<String, String> configure(Context context, String key, String value) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            PluginManifest manifest = ExternalPlugins.repository(context).load(PLUGIN_ID);
            PluginManifest.Setting selected = null;
            if (manifest != null) for (PluginManifest.Setting item : manifest.nativeSettings) {
                if (item.key.equals(key)) selected = item;
            }
            if (selected == null || !selected.options.containsKey(value)) {
                throw new IOException("插件未声明此莫奈设置或设置值不受支持");
            }
            ModuleState state = ModuleManager.read(context);
            if (!state.enabled()) throw new IOException("请先启用莫奈并重新读取状态");
            try {
                // Patch fresh actual state, never a stale UI map or the package defaults.
                ThemeConfig actual = ThemeRuntime.config(
                        ThemeRuntime.exchange(context, "read", null).getJSONObject("state"));
                ThemeConfig next = new ThemeConfig("mode".equals(key) ? value : actual.mode,
                        actual.seed, "corners".equals(key) ? Boolean.parseBoolean(value) : actual.corners);
                markPending(context);
                JSONObject ack = ThemeRuntime.exchange(context, "apply", next);
                ModuleManager.rememberThemeEnabled(context);
                persistConfirmed(context, ack);
                return values(ThemeRuntime.config(ack.getJSONObject("state")));
            } catch (Exception problem) {
                // No optimistic success/rollback: require a fresh read after failure.
                RuntimeStateStore.failMonet(context, problem);
                throw problem;
            }
        }
    }

    static void markPending(Context context) throws IOException {
        if (!preferences(context).edit().putBoolean("pendingTheme", true).commit()) {
            throw new IOException("无法保存待确认状态，本次未提交");
        }
    }

    static void persistConfirmed(Context context, JSONObject ack) throws Exception {
        ThemeConfig actual = ThemeRuntime.config(ack.getJSONObject("state"));
        if (!preferences(context).edit().putBoolean("pendingTheme", false)
                .putString("mode", actual.mode).putInt("seed", actual.seed)
                .putBoolean("corners", actual.corners).commit()) {
            throw new IOException("已读取实际设置，但本地记录保存失败");
        }
        RuntimeStateStore.recordTheme(context, actual);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("workshop", Context.MODE_PRIVATE);
    }

    private static Map<String, String> values(ThemeConfig theme) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("mode", theme.mode);
        result.put("corners", Boolean.toString(theme.corners));
        return result;
    }

    private MonetSettings() {}
}
