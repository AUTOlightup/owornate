package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/**
 * 只包装系统自带的 screencap / screenrecord。录制会话的权威是 root worker（capture.sh）：
 * 它持有 token、PID 和文件路径；这里只发固定命令，并把结果投影到 "capture_runtime" 偏好。
 *
 * <p>read/screenshot/start/stop 用本类的锁串行；{@link #awaitEnd} 刻意不持锁，
 * 在另一条连接上阻塞等待录制结束，不挡住停止命令。
 */
final class CaptureManager {
    static final int LOW_BITRATE = 600_000;
    static final int DEFAULT_BITRATE = 1_200_000;
    static final int HIGH_BITRATE = 2_400_000;

    private static final String SCRIPT = Payloads.FILES + "capture/capture.sh";
    private static final List<String> PHASES =
            Arrays.asList("idle", "starting", "recording", "stopping", "error", "screenshot");
    /** 投影里会被录屏设置页关心的两个键。 */
    private static final String PHASE = "phase", ERROR = "error";

    private CaptureManager() {}

    static final class Status {
        final String phase, token, path, error;
        final long startedAt;

        Status(JSONObject json) {
            phase = json.optString(PHASE, "error");
            token = json.optString("token", "");
            path = json.optString("path", "");
            startedAt = json.optLong("startedAt");
            error = message(json.optString(ERROR, ""));
        }

        boolean active() {
            return activePhase(phase);
        }
    }

    /** 正在启动、录制或保存：这时控制中心按钮是“停止”。 */
    static boolean activePhase(String phase) {
        return "starting".equals(phase) || "recording".equals(phase) || "stopping".equals(phase);
    }

    static boolean supportedBitrate(int bitrate) {
        return bitrate == LOW_BITRATE || bitrate == DEFAULT_BITRATE || bitrate == HIGH_BITRATE;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("capture_runtime", 0);
    }

    /** 投影里最近一次的录制阶段；从没读过时算 idle。 */
    static String lastPhase(Context context) {
        return preferences(context).getString(PHASE, "idle");
    }

    static String lastError(Context context) {
        return preferences(context).getString(ERROR, "");
    }

    /** 偏好变化是否影响录屏页显示的阶段或错误。 */
    static boolean statusKey(String key) {
        return PHASE.equals(key) || ERROR.equals(key);
    }

    static synchronized Status read(Context context) throws Exception {
        return run(context, "refresh", 10000, 13000);
    }

    static synchronized Status screenshot(Context context) throws Exception {
        return run(context, "screenshot", 12000, 16000);
    }

    static synchronized Status start(Context context, int bitrate) throws Exception {
        if (!supportedBitrate(bitrate)) throw new IllegalArgumentException("bitrate");
        String token = UUID.randomUUID().toString().replace("-", "");
        return run(context, "start " + bitrate + " " + token, 12000, 15000);
    }

    static synchronized Status stop(Context context) throws Exception {
        return run(context, "stop", 12000, 16000);
    }

    /** 阻塞到 token 对应的录制结束（最长约 3 分钟加保存时间）；结果不自动投影。 */
    static Status awaitEnd(Context context, String token) throws Exception {
        if (!token.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("session");
        return run(context, "wait " + token, 200000, 195000, false);
    }

    /** 记下失败原因，但保留上次看到的录制和路径：连接失败不能证明录制已经停止。 */
    static void fail(Context context, Exception error) {
        preferences(context).edit().putString(ERROR, WatchActivity.message(error)).apply();
    }

    static void accept(Context context, Status status) {
        if ("screenshot".equals(status.phase)) return;
        preferences(context)
                .edit()
                .putString(PHASE, status.phase)
                .putString("token", status.token)
                .putString("path", status.path)
                .putLong("startedAt", status.startedAt)
                .putString(ERROR, status.error)
                .apply();
    }

    private static Status run(Context context, String action, int readMs, long totalMs)
            throws Exception {
        return run(context, action, readMs, totalMs, true);
    }

    private static Status run(Context context, String action, int readMs, long totalMs,
            boolean publish) throws Exception {
        prepare(context);
        try {
            Status status = RootShell.session(context, 1800, readMs, adb -> {
                String result = adb.shell("sh " + SCRIPT + " " + action, totalMs).trim();
                if (adb.exitCode() != 0) throw new IOException(message(result));
                JSONObject json = new JSONObject(result);
                if (!PHASES.contains(json.optString(PHASE))) {
                    throw new IOException("未收到有效的录制状态，请重试");
                }
                return new Status(json);
            });
            if (publish) accept(context, status);
            return status;
        } catch (Exception error) {
            if (publish) fail(context, error);
            throw error;
        }
    }

    /** 与原实现一样短暂持有本类的锁：正在进行的启停完成后，等待线程才开始准备脚本。 */
    private static synchronized void prepare(Context context) throws IOException {
        Payloads.extract(context, "capture", "capture.sh");
    }

    private static String message(String code) {
        if (code == null || code.isEmpty()) return "";
        if (code.contains("ROOT_REQUIRED")) return "本机授权未完成，请先完成首次部署";
        if (code.contains("MODULE_DISABLED")) return "截屏录屏模块已停用，请先在 OWOrnate 启用";
        if (code.contains("DEVICE_UNSUPPORTED")) return "当前仅适配 OPPO Watch 2 / A.92";
        if (code.contains("CAPTURE_BUSY")) return "录制正在启动或保存，请稍候再试";
        if (code.contains("STORAGE_LOW")) return "剩余空间不足，请清理空间或选择较低码率";
        if (code.contains("STORAGE_UNAVAILABLE")) return "无法访问保存目录，请检查存储状态";
        if (code.contains("STATE_WRITE_FAILED")) return "录制状态未保存，请检查剩余空间后重试";
        if (code.contains("SCREENSHOT_FAILED")) return "截屏失败，请重试";
        if (code.contains("RECORDING_FAILED") || code.contains("START_FAILED")) {
            return "录制未完成，请检查剩余空间后重试";
        }
        if (code.contains("INTERRUPTED")) return "录制进程已退出，文件保存状态未确认";
        if (code.contains("STOP_FAILED")) return "停止未确认，请重新打开控制中心重试";
        return code;
    }
}
