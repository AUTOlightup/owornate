package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;

/** One persisted observation per subsystem. Pages and widgets only project these values. */
final class RuntimeStateStore {
    private static final String MONET = "monet", WIDGETS = "widgets", CAPTURE = "capture";

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("runtime_observations", Context.MODE_PRIVATE);
    }

    static synchronized StatusObservation<ModuleState> monet(Context context) {
        SharedPreferences p = preferences(context);
        if (!p.contains(MONET)) migrateMonet(context);
        try {
            JSONObject json = new JSONObject(p.getString(MONET, "{}"));
            JSONObject v = json.optJSONObject("value");
            ModuleState state = null;
            if (v != null) {
                String kind = v.getString("kind");
                if (!moduleKind(kind)) throw new IllegalArgumentException("Unknown module state");
                state = new ModuleState(kind, v.optBoolean("active"), v.optString("message"),
                        v.optBoolean("activeKnown"), readTheme(v));
            }
            return new StatusObservation<>(state, json.optLong("confirmedAt"), json.optString("error"));
        } catch (Exception bad) {
            return new StatusObservation<>(null, 0, "已保存的莫奈状态无法读取，请重新检查");
        }
    }

    static synchronized StatusObservation<WidgetHostManager.Status> widgets(Context context) {
        SharedPreferences p = preferences(context);
        if (!p.contains(WIDGETS)) {
            String host = context.getSharedPreferences("workshop", 0).getString("widgetHost", "");
            WidgetHostManager.Status old = hostKind(host) ? new WidgetHostManager.Status(host, "") : null;
            writeWidgets(context, new StatusObservation<>(old, 0, ""));
        }
        try {
            JSONObject json = new JSONObject(p.getString(WIDGETS, "{}"));
            JSONObject v = json.optJSONObject("value");
            WidgetHostManager.Status state = null;
            if (v != null) {
                String host = v.getString("host");
                if (!hostKind(host)) throw new IllegalArgumentException("Unknown widget host state");
                state = new WidgetHostManager.Status(host, v.optString("keep"));
            }
            return new StatusObservation<>(state, json.optLong("confirmedAt"), json.optString("error"));
        } catch (Exception bad) {
            return new StatusObservation<>(null, 0, "已保存的小组件状态无法读取，请重新检查");
        }
    }

    static synchronized void recordMonet(Context context, ModuleState value) {
        writeMonet(context, new StatusObservation<>(value, System.currentTimeMillis(), ""));
        OWOrnateWidgetProvider.updateAll(context);
    }

    static synchronized void failMonet(Context context, Exception error) {
        writeMonet(context, monet(context).failure(message(error)));
        OWOrnateWidgetProvider.updateAll(context);
    }

    /** The files were identified, but the running helper could not be read. Keep the
     * last full value when it belongs to that same installation; otherwise retain
     * only the installation hint so recovery remains reachable. */
    static synchronized void failMonet(Context context, Exception error, ModuleState installation) {
        StatusObservation<ModuleState> old = monet(context);
        if (old.value == null || !old.value.kind.equals(installation.kind))
            old = new StatusObservation<>(installation, 0, "");
        writeMonet(context, old.failure(message(error)));
        OWOrnateWidgetProvider.updateAll(context);
    }

    static synchronized void recordWidgets(Context context, WidgetHostManager.Status value) {
        writeWidgets(context, new StatusObservation<>(value, System.currentTimeMillis(), ""));
    }

    static synchronized StatusObservation<CaptureModuleState> capture(Context context) {
        SharedPreferences p=preferences(context);
        if(!p.contains(CAPTURE))return new StatusObservation<>(null,0,"");
        try {
            JSONObject json=new JSONObject(p.getString(CAPTURE,"{}"));
            JSONObject value=json.optJSONObject("value");
            return new StatusObservation<>(value==null?null:new CaptureModuleState(value.getString("kind")),
                    json.optLong("confirmedAt"),json.optString("error"));
        }catch(Exception e){return new StatusObservation<>(null,0,"已保存的截屏录屏模块状态无法读取，请重试");}
    }

    static synchronized void recordCapture(Context context,CaptureModuleState value) {
        saveCapture(context,new StatusObservation<>(value,System.currentTimeMillis(),""));
    }
    static synchronized void failCapture(Context context,Exception error) {
        saveCapture(context,capture(context).failure(message(error)));
    }
    private static void saveCapture(Context context,StatusObservation<CaptureModuleState> observation) {
        try {
            JSONObject json=envelope(observation);
            if(observation.value!=null)json.put("value",new JSONObject().put("kind",observation.value.kind));
            save(context,CAPTURE,json);
        }catch(Exception e){throw new IllegalStateException("Cannot encode capture module observation",e);}
    }

    static synchronized void failWidgets(Context context, Exception error) {
        writeWidgets(context, widgets(context).failure(message(error)));
    }

    /** Called only with a validated read/apply/rollback ACK, never with an editor draft. */
    static synchronized void recordTheme(Context context, ThemeConfig theme) {
        ModuleState old = monet(context).value;
        if (old == null || !old.installed()) return;
        boolean neutral = "original".equals(theme.mode) && !theme.corners;
        boolean active = !"shared".equals(old.kind) || !neutral
                || context.getSharedPreferences("modules", 0).getBoolean("monetEnabled", !neutral);
        recordMonet(context, new ModuleState(old.kind, active, active ? "已启用" : "已停用", true, theme));
    }

    private static void migrateMonet(Context context) {
        SharedPreferences old = context.getSharedPreferences("workshop", 0);
        SharedPreferences card = context.getSharedPreferences("owornate_widget", 0);
        ModuleState value = null;
        String kind = old.getString("monetKind", "");
        // Legacy caches may disagree and have no common observation time. Preserve a hint,
        // but never treat it as fresh or let it suppress the first device read.
        if (moduleKind(kind)) {
            value = new ModuleState(kind, old.getBoolean("monetActive", false),
                    old.getString("monetMessage", "待重新确认"), old.getBoolean("monetKnown", false), null);
        } else {
            kind = card.getString("kind", "");
            if (moduleKind(kind)) {
                boolean active = card.getBoolean("active", false);
                ThemeConfig theme = null;
                try {
                    if (card.contains("mode")) theme = new ThemeConfig(card.getString("mode", ""),
                            card.getInt("seed", 0xfff0bd50), card.getBoolean("corners", false));
                } catch (IllegalArgumentException ignored) { }
                value = new ModuleState(kind, active, "待重新确认", card.getBoolean("known", false), theme);
            }
        }
        writeMonet(context, new StatusObservation<>(value, 0, ""));
    }

    private static boolean moduleKind(String kind) {
        return "shared".equals(kind) || "installed".equals(kind) || "available".equals(kind)
                || "setup".equals(kind) || "conflict".equals(kind);
    }

    private static boolean hostKind(String host) {
        return "ready".equals(host) || "installed".equals(host) || "available".equals(host);
    }

    private static ThemeConfig readTheme(JSONObject v) throws Exception {
        JSONObject t = v.optJSONObject("theme");
        return t == null ? null : new ThemeConfig(t.getString("mode"), t.getInt("seed"), t.getBoolean("corners"));
    }

    private static JSONObject envelope(StatusObservation<?> observation) throws Exception {
        return new JSONObject().put("confirmedAt", observation.confirmedAt).put("error", observation.error);
    }

    private static void writeMonet(Context context, StatusObservation<ModuleState> observation) {
        try {
            JSONObject json = envelope(observation);
            ModuleState v = observation.value;
            if (v != null) {
                JSONObject state = new JSONObject().put("kind", v.kind).put("active", v.active)
                        .put("activeKnown", v.activeKnown).put("message", v.message);
                if (v.theme != null) state.put("theme", new JSONObject().put("mode", v.theme.mode)
                        .put("seed", v.theme.seed).put("corners", v.theme.corners));
                json.put("value", state);
            }
            save(context, MONET, json);
        } catch (Exception bad) { throw new IllegalStateException("Cannot encode module observation", bad); }
    }

    private static void writeWidgets(Context context, StatusObservation<WidgetHostManager.Status> observation) {
        try {
            JSONObject json = envelope(observation);
            if (observation.value != null) json.put("value", new JSONObject().put("host", observation.value.host)
                    .put("keep", observation.value.keep));
            save(context, WIDGETS, json);
        } catch (Exception bad) { throw new IllegalStateException("Cannot encode widget observation", bad); }
    }

    private static void save(Context context, String key, JSONObject value) {
        // SharedPreferences publishes one complete value atomically to every reader/listener.
        // A cache disk failure must not turn a confirmed device operation into a failed operation.
        if (!preferences(context).edit().putString(key, value.toString()).commit())
            Log.w("RuntimeStateStore", "Unable to persist " + key + " observation");
    }

    private static String message(Exception error) {
        if (error instanceof java.net.ConnectException) return "本机连接失败，请检查连接或授权后重试";
        if (error instanceof java.net.SocketTimeoutException) return "读取状态超时，请重试";
        return error.getMessage() == null || error.getMessage().isEmpty() ? "状态读取失败，请重试" : error.getMessage();
    }
}
