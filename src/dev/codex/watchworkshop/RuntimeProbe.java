package dev.codex.watchworkshop;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/**
 * 运行环境页的设备检测：只跑一条固定的只读命令，不接受调用方传入的命令或路径。
 * 结果由 {@link SystemPages} 存进 "probe" 偏好，页面显示其中的组件指纹和检测时间。
 */
final class RuntimeProbe {
    private static final String COMMAND =
            "id -u; echo __BUILD__; getprop ro.build.display.id; "
                    + "echo __HOOK__; if [ -f /data/local/tmp/watchhide/hook.js ]; then sha256sum /data/local/tmp/watchhide/hook.js; else sha256sum /data/local/tmp/watchworkshop-runtime/hook.js 2>/dev/null; fi; "
                    + "echo __THEME_HASH__; if [ -f /data/local/tmp/watchface-theme/theme.jar ]; then sha256sum /data/local/tmp/watchface-theme/theme.jar; else sha256sum /data/local/tmp/watchworkshop-runtime/theme.jar 2>/dev/null; fi; "
                    + "echo __THEME_STATUS__; cat /data/user_de/0/com.heytap.wearable.launcher/files/watchface-theme-status.json 2>/dev/null; "
                    + "echo; echo __GUARD__; cat /data/local/tmp/wearqq-powerguard/status 2>/dev/null; "
                    + "echo __BLUE__; test -f /data/local/tmp/wearqq-blue-ui/blue-point.jar && echo present; "
                    + "echo __END__; true";
    /** helper 状态文件里允许带进快照的字段；其余字段一律丢弃。 */
    private static final String[] THEME_FIELDS = {
        "ready", "mode", "seed", "active", "onActive", "off", "onOff", "helperVersion", "corners", "error"
    };

    private RuntimeProbe() {}

    static JSONObject read(Context context) throws Exception {
        return RootShell.session(context, 1800, 3500, adb -> {
            String raw = adb.shell(COMMAND, 14000);
            if (adb.exitCode() != 0 || !raw.contains("__END__")) throw new IOException("设备检测未完成");
            return parse(raw);
        });
    }

    private static JSONObject parse(String raw) throws Exception {
        if (!raw.substring(0, raw.indexOf("__BUILD__")).trim().equals("0")) {
            throw new IOException("本机通道没有 root 权限");
        }
        JSONObject result = new JSONObject();
        result.put("checkedAt", System.currentTimeMillis());
        result.put("root", true);
        result.put("build", section(raw, "__BUILD__", "__HOOK__"));
        result.put("hookSha", firstWord(section(raw, "__HOOK__", "__THEME_HASH__")));
        result.put("themeSha", firstWord(section(raw, "__THEME_HASH__", "__THEME_STATUS__")));
        result.put("theme", themeStatus(section(raw, "__THEME_STATUS__", "__GUARD__")));
        result.put("guard", keyValues(section(raw, "__GUARD__", "__BLUE__")));
        result.put("bluePresent", section(raw, "__BLUE__", "__END__").equals("present"));
        return result;
    }

    /** start 与 end 两个标记之间的文字；任一标记缺失时为空串。 */
    private static String section(String text, String start, String end) {
        int from = text.indexOf(start), to = text.indexOf(end, from + start.length());
        return from < 0 || to < 0 ? "" : text.substring(from + start.length(), to).trim();
    }

    /** sha256sum 输出的第一列。 */
    private static String firstWord(String text) {
        return text.split("\\s+")[0];
    }

    /** helper 状态里的白名单字段；读不出来时为 JSON null。 */
    private static Object themeStatus(String text) {
        try {
            JSONObject source = new JSONObject(text), safe = new JSONObject();
            for (String key : THEME_FIELDS) if (source.has(key)) safe.put(key, source.get(key));
            return safe;
        } catch (Exception ignored) {
            return JSONObject.NULL;
        }
    }

    /** 每行一个 key=value。 */
    private static JSONObject keyValues(String text) throws Exception {
        JSONObject values = new JSONObject();
        for (String line : text.split("[\\r\\n]+")) {
            int eq = line.indexOf('=');
            if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return values;
    }
}
