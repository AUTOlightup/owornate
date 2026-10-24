package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.function.Function;
import org.json.JSONObject;

/**
 * 每个子系统（莫奈、小组件接入、截屏录屏模块）各存一份完整的观察结果：上次确认的值、确认时刻
 * 和最近一次读取的错误分开记（{@link StatusObservation}）。页面和卡片只投影这里的值；
 * 只有各 Manager 在真实读写设备之后写入。
 *
 * <p>另存莫奈的“启用意图”：共享组件在系统原色、无圆角时，分不清是停用还是用户选了原色，
 * 以用户上次的启停选择为准（{@link #monetActive}）。
 */
final class RuntimeStateStore {
    /** 莫奈启用意图所在的偏好文件；停用前的配色也存在这里（见 {@link ModuleManager}）。 */
    static final String MODULE_PREFS = "modules";

    private static final String MONET_ENABLED = "monetEnabled";
    private static final Domain<ModuleState> MONET = new Domain<ModuleState>(
            "monet", "已保存的莫奈状态无法读取，请重新检查", "Cannot encode module observation",
            RuntimeStateStore::legacyMonet) {
        @Override
        ModuleState decode(JSONObject value) throws Exception {
            String kind = value.getString("kind");
            if (!moduleKind(kind)) throw new IllegalArgumentException("Unknown module state");
            String owner = value.optString("owner", "shared".equals(kind) ? "shared" : "");
            return new ModuleState(kind, owner, value.optBoolean("active"),
                    value.optString("message"), value.optBoolean("activeKnown"), readTheme(value));
        }

        @Override
        JSONObject encode(ModuleState value) throws Exception {
            JSONObject state = new JSONObject()
                    .put("kind", value.kind)
                    .put("owner", value.owner)
                    .put("active", value.active)
                    .put("activeKnown", value.activeKnown)
                    .put("message", value.message);
            if (value.theme != null) {
                state.put("theme", new JSONObject()
                        .put("mode", value.theme.mode)
                        .put("seed", value.theme.seed)
                        .put("corners", value.theme.corners));
            }
            return state;
        }

        @Override
        void afterRecord(Context context) {
            OWOrnateWidgetProvider.updateAll(context);
        }
    };

    private static final Domain<WidgetHostManager.Status> WIDGETS =
            new Domain<WidgetHostManager.Status>(
                    "widgets", "已保存的小组件状态无法读取，请重新检查", "Cannot encode widget observation",
                    RuntimeStateStore::legacyWidgets) {
                @Override
                WidgetHostManager.Status decode(JSONObject value) throws Exception {
                    String host = value.getString("host");
                    if (!hostKind(host)) throw new IllegalArgumentException("Unknown widget host state");
                    return new WidgetHostManager.Status(host, value.optString("keep"),
                            value.optString("owner"));
                }

                @Override
                JSONObject encode(WidgetHostManager.Status value) throws Exception {
                    return new JSONObject().put("host", value.host).put("keep", value.keep)
                            .put("owner", value.owner);
                }
            };

    private static final Domain<CaptureModuleState> CAPTURE = new Domain<CaptureModuleState>(
            "capture", "已保存的截屏录屏模块状态无法读取，请重试", "Cannot encode capture module observation",
            null) {
        @Override
        CaptureModuleState decode(JSONObject value) throws Exception {
            return new CaptureModuleState(value.getString("kind"), value.optString("owner"));
        }

        @Override
        JSONObject encode(CaptureModuleState value) throws Exception {
            return new JSONObject().put("kind", value.kind).put("owner", value.owner);
        }
    };

    private RuntimeStateStore() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("runtime_observations", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // 莫奈
    // ------------------------------------------------------------------

    static synchronized StatusObservation<ModuleState> monet(Context context) {
        return MONET.read(context);
    }

    static synchronized void recordMonet(Context context, ModuleState value) {
        MONET.record(context, value);
    }

    static synchronized void failMonet(Context context, Exception error) {
        MONET.fail(context, error);
    }

    /**
     * 组件文件已识别，但运行中的 helper 读不出来：上次的完整值属于同一种安装时保留它，
     * 否则只留下安装提示，让恢复入口仍然可达。
     */
    static synchronized void failMonet(Context context, Exception error, ModuleState installation) {
        StatusObservation<ModuleState> old = monet(context);
        if (old.value == null || !old.value.kind.equals(installation.kind)) {
            old = new StatusObservation<>(installation, 0, "");
        }
        MONET.fail(context, old, error);
    }

    /** 只接受经过校验的 read/apply/rollback 回执，不接受编辑中的草稿。 */
    static synchronized void recordTheme(Context context, ThemeConfig theme) {
        ModuleState old = monet(context).value;
        if (old == null || !old.installed()) return;
        boolean active = monetActive(context, old.kind, theme);
        recordMonet(context, new ModuleState(old.kind, old.owner, active,
                active ? "已启用" : "已停用", true, theme));
    }

    /**
     * 莫奈是否算启用。独立安装和非原色配置都算启用；共享组件处于“系统原色、无圆角”时，
     * 以用户上次的启停选择为准（没有记录时算停用）。
     */
    static boolean monetActive(Context context, String kind, ThemeConfig theme) {
        boolean neutral = "original".equals(theme.mode) && !theme.corners;
        if (!"shared".equals(kind) || !neutral) return true;
        return context.getSharedPreferences(MODULE_PREFS, 0).getBoolean(MONET_ENABLED, false);
    }

    /** 记住用户的启停选择；返回是否已落盘。 */
    static boolean rememberMonetEnabled(Context context, boolean enabled) {
        return context.getSharedPreferences(MODULE_PREFS, 0)
                .edit()
                .putBoolean(MONET_ENABLED, enabled)
                .commit();
    }

    // ------------------------------------------------------------------
    // 负一屏小组件接入
    // ------------------------------------------------------------------

    static synchronized StatusObservation<WidgetHostManager.Status> widgets(Context context) {
        return WIDGETS.read(context);
    }

    static synchronized void recordWidgets(Context context, WidgetHostManager.Status value) {
        WIDGETS.record(context, value);
    }

    static synchronized void failWidgets(Context context, Exception error) {
        WIDGETS.fail(context, error);
    }

    // ------------------------------------------------------------------
    // 截屏与录屏模块（部署与启用；录制会话本身在 CaptureManager）
    // ------------------------------------------------------------------

    static synchronized StatusObservation<CaptureModuleState> capture(Context context) {
        return CAPTURE.read(context);
    }

    static synchronized void recordCapture(Context context, CaptureModuleState value) {
        CAPTURE.record(context, value);
    }

    static synchronized void failCapture(Context context, Exception error) {
        CAPTURE.fail(context, error);
    }

    // ------------------------------------------------------------------
    // 旧缓存迁移：只作提示，确认时刻为 0，不能顶替第一次设备读取
    // ------------------------------------------------------------------

    private static ModuleState legacyMonet(Context context) {
        SharedPreferences old = context.getSharedPreferences("workshop", 0);
        SharedPreferences card = context.getSharedPreferences("owornate_widget", 0);
        ModuleState value = null;
        String kind = old.getString("monetKind", "");
        // 两份旧缓存可能互相矛盾，也没有共同的观察时间。
        if (moduleKind(kind)) {
            value = new ModuleState(kind, old.getBoolean("monetActive", false),
                    old.getString("monetMessage", "待重新确认"), old.getBoolean("monetKnown", false), null);
        } else {
            kind = card.getString("kind", "");
            if (moduleKind(kind)) {
                value = new ModuleState(kind, card.getBoolean("active", false), "待重新确认",
                        card.getBoolean("known", false), legacyCardTheme(card));
            }
        }
        return value;
    }

    private static ThemeConfig legacyCardTheme(SharedPreferences card) {
        try {
            if (card.contains("mode")) {
                return new ThemeConfig(card.getString("mode", ""), card.getInt("seed", 0xfff0bd50),
                        card.getBoolean("corners", false));
            }
        } catch (IllegalArgumentException ignored) {
            // 旧缓存里的 mode 认不出来就不带主题。
        }
        return null;
    }

    private static WidgetHostManager.Status legacyWidgets(Context context) {
        String host = context.getSharedPreferences("workshop", 0).getString("widgetHost", "");
        return hostKind(host) ? new WidgetHostManager.Status(host, "") : null;
    }

    // ------------------------------------------------------------------
    // 编码与落盘
    // ------------------------------------------------------------------

    private static boolean moduleKind(String kind) {
        return "shared".equals(kind) || "installed".equals(kind) || "available".equals(kind)
                || "disabled".equals(kind) || "update".equals(kind) || "setup".equals(kind)
                || "conflict".equals(kind);
    }

    private static boolean hostKind(String host) {
        return "ready".equals(host) || "installed".equals(host) || "available".equals(host)
                || "disabled".equals(host) || "update".equals(host) || "setup".equals(host)
                || "conflict".equals(host);
    }

    private static ThemeConfig readTheme(JSONObject v) throws Exception {
        JSONObject t = v.optJSONObject("theme");
        return t == null
                ? null
                : new ThemeConfig(t.getString("mode"), t.getInt("seed"), t.getBoolean("corners"));
    }

    /** 只由持有 RuntimeStateStore 类锁的入口调用，保持三个域共用原来的锁。 */
    private abstract static class Domain<T> {
        private final String key;
        private final String readError;
        private final String encodeError;
        private final Function<Context, T> legacy;

        Domain(String key, String readError, String encodeError, Function<Context, T> legacy) {
            this.key = key;
            this.readError = readError;
            this.encodeError = encodeError;
            this.legacy = legacy;
        }

        abstract T decode(JSONObject value) throws Exception;

        abstract JSONObject encode(T value) throws Exception;

        StatusObservation<T> read(Context context) {
            SharedPreferences p = preferences(context);
            if (!p.contains(key)) {
                if (legacy == null) return new StatusObservation<>(null, 0, "");
                // 迁移只写待确认提示，不触发真实读写完成后的刷新。
                write(context, new StatusObservation<>(legacy.apply(context), 0, ""));
            }
            try {
                JSONObject json = new JSONObject(p.getString(key, "{}"));
                JSONObject value = json.optJSONObject("value");
                return new StatusObservation<>(value == null ? null : decode(value),
                        json.optLong("confirmedAt"), json.optString("error"));
            } catch (Exception bad) {
                return new StatusObservation<>(null, 0, readError);
            }
        }

        void record(Context context, T value) {
            write(context, new StatusObservation<>(value, System.currentTimeMillis(), ""));
            afterRecord(context);
        }

        void fail(Context context, Exception error) {
            fail(context, read(context), error);
        }

        void fail(Context context, StatusObservation<T> old, Exception error) {
            write(context, old.failure(message(error)));
            afterRecord(context);
        }

        void afterRecord(Context context) {}

        private void write(Context context, StatusObservation<T> observation) {
            try {
                JSONObject json = new JSONObject()
                        .put("confirmedAt", observation.confirmedAt)
                        .put("error", observation.error);
                if (observation.value != null) json.put("value", encode(observation.value));
                save(context, key, json);
            } catch (Exception bad) {
                throw new IllegalStateException(encodeError, bad);
            }
        }
    }

    private static void save(Context context, String key, JSONObject value) {
        // SharedPreferences 一次把完整的值原子地发布给所有读者和监听者。
        // 缓存写盘失败不能把已经在设备上确认的操作变成失败。
        if (!preferences(context).edit().putString(key, value.toString()).commit()) {
            Log.w("RuntimeStateStore", "Unable to persist " + key + " observation");
        }
    }

    private static String message(Exception error) {
        if (error instanceof java.net.ConnectException) return "本机连接失败，请检查连接或授权后重试";
        if (error instanceof java.net.SocketTimeoutException) return "读取状态超时，请重试";
        return error.getMessage() == null || error.getMessage().isEmpty()
                ? "状态读取失败，请重试"
                : error.getMessage();
    }
}
