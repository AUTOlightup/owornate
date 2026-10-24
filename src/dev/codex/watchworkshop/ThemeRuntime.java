package dev.codex.watchworkshop;

import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 与 Launcher 内主题 helper 的封闭协议：读 hello 核对身份，写一次请求文件，等对应的 ack。
 * 命令全部写死，不接受调用方传入的 shell 片段或文件路径。
 */
final class ThemeRuntime {
    private static final String DIR =
            "/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-theme";
    private static final int PROTOCOL = 1;
    private static final int HELPER_VERSION = 6;
    private static final List<String> ACTIONS = Arrays.asList("read", "preview", "apply", "rollback");

    private ThemeRuntime() {}

    /** helper 的 hello：会话号与所在 Launcher 进程，回执必须与它一致。 */
    private static final class Hello {
        final String session;
        final int pid;

        Hello(String session, int pid) {
            this.session = session;
            this.pid = pid;
        }
    }

    /** 单独一次交互：自己开一个连接。 */
    static JSONObject exchange(Context context, String action, ThemeConfig config)
            throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            return RootShell.session(context, 1800, 12000, adb -> exchange(adb, action, config));
        }
    }

    /** 在调用方已经打开的连接上交互；读 hello 和提交请求共用这一个连接。 */
    static synchronized JSONObject exchange(AdbClient adb, String action, ThemeConfig config)
            throws Exception {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Theme action");
        Hello hello = hello(adb);
        String id = UUID.randomUUID().toString();
        String encoded = Base64.encodeToString(
                request(hello, id, action, config).toString().getBytes("UTF-8"), Base64.NO_WRAP);
        String response = shell(adb, submitAndWait(encoded, id));
        int marker = response.indexOf("__ACK__");
        if (marker < 0) throw new IOException("等待主题确认超时，请重新读取状态");
        JSONObject ack = new JSONObject(response.substring(marker + 7).trim());
        verify(ack, hello, id, action, config);
        return ack;
    }

    /** helper 状态（mode / seed / corners）里的实际配置。 */
    static ThemeConfig config(JSONObject state) throws JSONException {
        return new ThemeConfig(
                state.getString("mode"), color(state.getString("seed")), state.getBoolean("corners"));
    }

    static int color(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new IllegalArgumentException("主题组件返回的配色格式无效");
        }
        return (int) Long.parseLong(hex.substring(1), 16) | 0xff000000;
    }

    // ------------------------------------------------------------------
    // 协议步骤
    // ------------------------------------------------------------------

    private static String shell(AdbClient adb, String command) throws Exception {
        String result = adb.shell(command, 15000);
        if (adb.exitCode() != 0) throw new IOException("本机主题通道未完成操作");
        return result;
    }

    /** 读 hello 并核对协议、helper 版本和它报告的 pid 就是当前 Launcher。 */
    private static Hello hello(AdbClient adb) throws Exception {
        String raw = shell(adb, "test \"$(id -u)\" = 0 && cat " + DIR
                + "/hello.json && echo; pidof com.heytap.wearable.launcher");
        int end = raw.lastIndexOf('}');
        if (end < 0) throw new IOException("主题组件未加载，请按首次设置安装或检查运行环境");
        JSONObject hello = new JSONObject(raw.substring(0, end + 1));
        if (hello.getInt("protocol") != PROTOCOL
                || hello.getInt("helperVersion") != HELPER_VERSION
                || !Integer.toString(hello.getInt("pid")).equals(raw.substring(end + 1).trim())) {
            throw new IOException("主题接口版本或进程不匹配");
        }
        return new Hello(hello.getString("session"), hello.getInt("pid"));
    }

    private static JSONObject request(Hello hello, String id, String action, ThemeConfig config)
            throws JSONException {
        JSONObject request = new JSONObject()
                .put("protocol", PROTOCOL)
                .put("id", id)
                .put("session", hello.session)
                .put("action", action)
                .put("createdAt", System.currentTimeMillis());
        if (config != null) {
            request.put("config", new JSONObject()
                    .put("mode", config.mode)
                    .put("seedArgb", config.seedHex())
                    .put("corners", config.corners));
        }
        return request;
    }

    /** 原子放下请求文件（属主与 mailbox 目录一致），再最多等 10 秒带同一 id 的 ack。 */
    private static String submitAndWait(String encoded, String id) {
        return "test \"$(id -u)\" = 0 && test -d " + DIR + " && printf '%s' '" + encoded + "' | base64 -d > " + DIR + "/request.tmp && "
                + "chown \"$(stat -c '%u:%g' " + DIR + ")\" " + DIR + "/request.tmp && chmod 600 " + DIR + "/request.tmp && mv " + DIR + "/request.tmp " + DIR + "/request.json && "
                + "n=0; while [ \"$n\" -lt 100 ]; do if grep -q '" + id + "' " + DIR + "/ack.json 2>/dev/null; then echo __ACK__; cat " + DIR + "/ack.json; echo; exit 0; fi; printf '.'; sleep 0.1; n=$((n+1)); done; exit 1";
    }

    private static void verify(JSONObject ack, Hello hello, String id, String action,
            ThemeConfig config) throws Exception {
        if (ack.getInt("protocol") != PROTOCOL
                || !id.equals(ack.getString("id"))
                || !hello.session.equals(ack.getString("session"))
                || ack.getInt("pid") != hello.pid) {
            throw new IOException("主题回执身份不匹配");
        }
        if (!ack.getBoolean("ok")) throw new IOException(message(ack.optString("code")));
        JSONObject state = ack.optJSONObject("state");
        if (state == null || !state.optBoolean("ready")
                || state.optInt("helperVersion") != HELPER_VERSION) {
            throw new IOException("主题 helper 未就绪");
        }
        if ("apply".equals(action) && config != null && !applied(config, state)) {
            throw new IOException("主题实际配置未匹配，请重新读取");
        }
    }

    /** 应用之后 helper 报告的配置与请求一致；固定颜色还要比对种子色。 */
    private static boolean applied(ThemeConfig config, JSONObject state) throws JSONException {
        return config.mode.equals(state.optString("mode"))
                && config.corners == state.optBoolean("corners")
                && (!"fixed".equals(config.mode) || color(state.getString("seed")) == config.seed);
    }

    private static String message(String code) {
        if ("SESSION_CHANGED".equals(code)) return "Launcher 已重载，请重新读取后操作";
        if ("SAMPLE_FAILED".equals(code)) return "暂时无法读取当前表盘配色，原设置保留";
        if ("EXPIRED".equals(code)) return "请求已过期，请重新操作";
        if ("BUSY".equals(code)) return "主题正在处理，请稍后重试";
        return "主题操作未确认，请重新读取或恢复上一次设置";
    }
}
