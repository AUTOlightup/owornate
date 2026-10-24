package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Binds package declarations to the existing native preference, without a second copy. */
final class CaptureSettings {
    static final String PLUGIN_ID = "dev.owornate.capture";
    private static final String KEY = "bitrate";

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("capture_options", Context.MODE_PRIVATE);
    }

    static PluginManifest.Setting definition(Context context) {
        return definition(ExternalPlugins.repository(context).load(PLUGIN_ID));
    }

    static PluginManifest.Setting definition(PluginManifest manifest) {
        if (manifest != null && PLUGIN_ID.equals(manifest.id) && !manifest.nativeSettings.isEmpty()) {
            return manifest.nativeSettings.get(0);
        }
        // Legacy packages and installations without an imported package retain their UI/default.
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("600000", "低");
        options.put("1200000", "标准");
        options.put("2400000", "高");
        return new PluginManifest.Setting(KEY, "录屏画质", "1200000", Collections.unmodifiableMap(options));
    }

    static int read(Context context, PluginManifest.Setting definition) {
        int fallback = Integer.parseInt(definition.defaultValue);
        int stored = preferences(context).getInt(KEY, fallback);
        return definition.options.containsKey(Integer.toString(stored)) ? stored : fallback;
    }

    static synchronized void save(Context context, int bitrate) throws IOException {
        if (bitrate != 600000 && bitrate != 1200000 && bitrate != 2400000) {
            throw new IllegalArgumentException("不支持的录屏码率");
        }
        if (!preferences(context).edit().putInt(KEY, bitrate).commit()) {
            throw new IOException("录屏画质保存失败");
        }
    }

    static Map<String, String> config(Context context, PluginManifest manifest) {
        if (manifest.nativeSettings.isEmpty()) return Collections.emptyMap();
        return Collections.singletonMap(KEY, Integer.toString(read(context, definition(manifest))));
    }

    static Map<String, String> configure(Context context, String id, Map<String, String> values)
            throws IOException {
        synchronized (RuntimeSupport.LOCK) {
            PluginManifest manifest = ExternalPlugins.repository(context).load(id);
            if (manifest == null || !PLUGIN_ID.equals(id) || manifest.nativeSettings.isEmpty()) {
                throw new IOException("插件未声明可用的原生设置");
            }
            if (values == null || values.size() != 1 || !values.containsKey(KEY)
                    || !definition(manifest).options.containsKey(values.get(KEY))) {
                throw new IOException("不支持的插件设置值");
            }
            save(context, Integer.parseInt(values.get(KEY)));
            return config(context, manifest);
        }
    }

    /** Freeze an implicit old default before upgrade/removal changes the declaration. */
    static synchronized void preserve(Context context, PluginManifest current) throws IOException {
        if (current != null && PLUGIN_ID.equals(current.id) && !preferences(context).contains(KEY)) {
            save(context, read(context, definition(current)));
        }
    }

    private CaptureSettings() {}
}
