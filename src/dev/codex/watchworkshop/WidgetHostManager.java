package dev.codex.watchworkshop;

import android.content.Context;
import android.os.SystemClock;
import java.io.IOException;

/**
 * 固定脚本入口：负一屏宿主、小组件与截屏录屏功能的设备状态都经这里读取和修改。
 *
 * <p>{@code widget-manage.sh} 只接受本类写死的动作。运行组件可能属于本包的独立运行目录，
 * 也可能来自已有 WatchHide 共享安装；脚本的 owner 行是判断能否单独卸载的依据。所有设备
 * 会话共用 {@link RuntimeSupport#LOCK}，避免宿主接入和功能启停同时改写运行文件。</p>
 */
final class WidgetHostManager {
    /** 小组件状态与宿主接入检查。 */
    static final class Status {
        final String host, keep, owner;

        Status(String host, String keep) {
            this(host, keep, "");
        }

        Status(String host, String keep, String owner) {
            this.host = host;
            this.keep = keep;
            this.owner = owner == null ? "" : owner;
        }

        boolean installed() {
            return "ready".equals(host) || "installed".equals(host) || "disabled".equals(host)
                    || "update".equals(host);
        }

        boolean enabled() {
            return "ready".equals(host) || "installed".equals(host);
        }

        boolean removable() {
            return installed() && "own".equals(owner);
        }
    }

    private static final String SOURCE = Payloads.FILES + "widget-host";
    private static final String[] PAYLOAD = {"widget-manage.sh", "WatchHide-Launcher-Hook-v13.js"};
    /** 状态读取与系统开关：脚本应在 5 秒内有输出，整体 10 秒。 */
    private static final int QUICK_READ_MS = 5000, QUICK_TOTAL_MS = 10000;
    /** 安装、更新或启停运行组件：脚本内的启动与旁路准备留给 35 秒读取、45 秒总时限。 */
    private static final int LIFECYCLE_READ_MS = 35000, LIFECYCLE_TOTAL_MS = 45000;
    /** 白名单刚加入时的开机时刻（"workshop" 偏好），用来提示“重启一次后生效”。 */
    private static final String KEEP_BOOT = "widgetKeepBoot";

    private WidgetHostManager() {}

    /** The OEM editor owns widget IDs and ordering; open it without rebinding cards. */
    static void openEditor(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            Status before = check(context);
            if (!before.enabled()) throw new IOException("请先启用负一屏小组件");
            RootShell.session(context, 1800, QUICK_READ_MS, adb -> {
                String output = adb.shell("am start -n com.heytap.wearable.launcher/"
                        + "com.heytap.wearable.launcher.quickcenter.view.QuickEditActivity",
                        QUICK_TOTAL_MS);
                if (adb.exitCode() != 0 || output.contains("Error:")
                        || output.contains("Exception")) {
                    throw new IOException("系统卡片编辑页未打开，请从负一屏进入编辑");
                }
                return null;
            });
        }
    }

    // ------------------------------------------------------------------
    // 负一屏小组件
    // ------------------------------------------------------------------

    /** 只读状态：第一行功能状态，第二行后台白名单，第三行运行组件 owner（旧脚本可省略）。 */
    static Status check(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                return record(context, readStatus(context, "check", QUICK_READ_MS, QUICK_TOTAL_MS));
            } catch (Exception e) {
                RuntimeStateStore.failWidgets(context, e);
                throw e;
            }
        }
    }

    /** 安装或重新接入；安装完成后在同一设备连接里重新读取权威状态。 */
    static Status install(Context context) throws Exception {
        return mutate(context, "install", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
    }

    static Status update(Context context) throws Exception {
        Status before = check(context);
        // 共享 update 动作会沿用 capture-install 的启用副作用；共享重装路径保留原截屏开关。
        if ("shared".equals(before.owner)) {
            return mutate(context, "install", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
        }
        return mutate(context, "update", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
    }

    static Status setEnabled(Context context, boolean enabled) throws Exception {
        Status before = check(context);
        if (!before.installed()) throw new IOException("小组件尚未安装");
        if (!"own".equals(before.owner)) {
            throw new IOException("共享运行组件的负一屏开关由共享组件管理，本管理器不能单独启停");
        }
        Status after = mutate(context, enabled ? "enable" : "disable", LIFECYCLE_READ_MS,
                LIFECYCLE_TOTAL_MS);
        if (after.enabled() != enabled) throw new IOException("小组件开关未确认，请重试");
        return after;
    }

    /** 共享 v13 没有独立小组件门控；更新可用，卸载和单独启停不由本管理器提供。 */
    static Status uninstall(Context context) throws Exception {
        Status before = check(context);
        if (!before.installed()) throw new IOException("小组件尚未安装");
        if (!"own".equals(before.owner)) {
            throw new IOException(before.owner.isEmpty()
                    ? "无法确认运行组件来源；刷新状态后再试"
                    : "共享运行组件不能单独卸载或启停小组件；可在管理页重新接入");
        }
        return mutate(context, "uninstall", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
    }

    /** 只修复开机后台白名单，不重载 Launcher。 */
    static Status repairBackground(Context context) throws Exception {
        return mutate(context, "repair", QUICK_READ_MS, QUICK_TOTAL_MS);
    }

    static boolean keepPendingReboot(Context context) {
        long keepBoot = context.getSharedPreferences("workshop", 0).getLong(KEEP_BOOT, 0);
        return keepBoot != 0 && Math.abs(bootTime() - keepBoot) < 60000;
    }

    static void breeno(Context context, boolean hide) throws Exception {
        String result = run(context, hide ? "breeno-hide" : "breeno-show", QUICK_READ_MS,
                QUICK_TOTAL_MS);
        if (!result.equals("hidden") && !result.equals("shown")) {
            throw new IOException("设置未生效，请重试");
        }
    }

    // ------------------------------------------------------------------
    // 截屏与录屏
    // ------------------------------------------------------------------

    static CaptureModuleState captureState(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                CaptureModuleState state = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                RuntimeStateStore.recordCapture(context, state);
                return state;
            } catch (Exception e) {
                RuntimeStateStore.failCapture(context, e);
                throw e;
            }
        }
    }

    /** 安装动作本身会启用并等待运行状态；未确认时仍抛出错误，但保留补读结果。 */
    static void enableCapture(Context context) throws Exception {
        try {
            mutateCapture(context, "capture-install", true);
        } catch (Exception e) {
            readCaptureAfterFailure(context);
            throw e;
        }
    }

    static void updateCapture(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                CaptureModuleState before = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                if (!before.installed()) throw new IOException("截屏与录屏尚未安装");
                run(context, "capture-update", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
                CaptureModuleState after = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                if (before.enabledKnown() && after.enabled() != before.enabled()) {
                    run(context, before.enabled() ? "capture-enable" : "capture-disable",
                            LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
                    after = captureStatus(context, "capture-check", QUICK_READ_MS, QUICK_TOTAL_MS);
                }
                RuntimeStateStore.recordCapture(context, after);
                if (before.enabledKnown() && after.enabled() != before.enabled()) {
                    throw new IOException("更新未完成，原启停状态未能保留");
                }
            } catch (Exception e) {
                readCaptureAfterFailure(context);
                throw e;
            }
        }
    }

    static void setCaptureEnabled(Context context, boolean enabled) throws Exception {
        // 停止录制与切换开关要和每一次新的采集请求串行。
        synchronized (CaptureManager.class) {
            synchronized (RuntimeSupport.LOCK) {
                if (!enabled) {
                    CaptureManager.Status result = CaptureManager.stop(context);
                    if (result.active()) throw new IOException("录屏正在保存，完成后再停用模块");
                }
                run(context, enabled ? "capture-enable" : "capture-disable",
                        LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
                CaptureModuleState after = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                RuntimeStateStore.recordCapture(context, after);
                if (after.enabled() != enabled) {
                    throw new IOException("模块开关未确认，请重试");
                }
            }
        }
    }

    /** 先停止当前录制，再移除本包独立安装的截屏录屏功能。 */
    static void uninstallCapture(Context context) throws Exception {
        synchronized (CaptureManager.class) {
            synchronized (RuntimeSupport.LOCK) {
                CaptureModuleState before = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                if (!before.installed()) throw new IOException("截屏与录屏尚未安装");
                if (!"own".equals(before.owner)) {
                    throw new IOException(before.owner.isEmpty()
                            ? "无法确认运行组件来源；刷新状态后再试"
                            : "共享运行组件不能单独卸载，可停用截屏与录屏");
                }
                CaptureManager.Status recording = CaptureManager.stop(context);
                if (recording.active()) throw new IOException("录屏正在保存，完成后再卸载模块");
                run(context, "capture-uninstall", LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
                CaptureModuleState after = captureStatus(context, "capture-check", QUICK_READ_MS,
                        QUICK_TOTAL_MS);
                RuntimeStateStore.recordCapture(context, after);
                if (after.installed()) throw new IOException("卸载未确认，请刷新状态");
            }
        }
    }

    // ------------------------------------------------------------------
    // 设备会话
    // ------------------------------------------------------------------

    private static Status mutate(Context context, String action, int readMs, long totalMs)
            throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                Status after = process(context, LIFECYCLE_READ_MS, adb -> {
                    String result = shell(adb, action, totalMs);
                    Status checked = parseStatus(shell(adb, "check", QUICK_TOTAL_MS), "check");
                    if (result.contains("added")) {
                        checked = new Status(checked.host, "added", checked.owner);
                    }
                    return checked;
                });
                return record(context, after);
            } catch (Exception operation) {
                readAfterFailure(context);
                throw operation;
            }
        }
    }

    private static void mutateCapture(Context context, String action, boolean requireReady)
            throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            run(context, action, LIFECYCLE_READ_MS, LIFECYCLE_TOTAL_MS);
            CaptureModuleState after = captureStatus(context, "capture-check", QUICK_READ_MS,
                    QUICK_TOTAL_MS);
            RuntimeStateStore.recordCapture(context, after);
            if (!after.enabled() || (requireReady && !after.live())) {
                throw new IOException(requireReady
                        ? "模块文件已保存，启动未确认；请重新接入恢复"
                        : "模块更新未确认，请刷新状态");
            }
        }
    }

    private static void readCaptureAfterFailure(Context context) {
        try {
            CaptureModuleState state = captureStatus(context, "capture-check", QUICK_READ_MS,
                    QUICK_TOTAL_MS);
            RuntimeStateStore.recordCapture(context, state);
        } catch (Exception read) {
            RuntimeStateStore.failCapture(context, read);
        }
    }

    private static Status readStatus(Context context, String action, int readMs, long totalMs)
            throws Exception {
        return process(context, readMs, adb -> parseStatus(shell(adb, action, totalMs), action));
    }

    private static CaptureModuleState captureStatus(Context context, String action, int readMs,
            long totalMs) throws Exception {
        return process(context, readMs, adb -> {
            String[] lines = shell(adb, action, totalMs).split("\\r?\\n");
            String kind = lines.length > 0 ? lines[0].trim() : "";
            String owner = lines.length > 1 ? lines[1].trim() : "";
            if (!oneOf(kind, "available", "ready", "installed", "disabled", "update", "setup",
                    "conflict")) {
                throw new IOException("未收到完整的截屏录屏状态，请重新读取");
            }
            if (!owner.isEmpty() && !oneOf(owner, "own", "shared")) {
                throw new IOException("未收到可确认的运行组件来源，请重新读取");
            }
            return new CaptureModuleState(kind, owner);
        });
    }

    private static <T> T process(Context context, int readMs, RootShell.Body<T> body)
            throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            ModuleManager.prepare(context);
            Payloads.extract(context, "widget-host", PAYLOAD);
            return RootShell.session(context, 1800, readMs, body);
        }
    }

    private static String run(Context context, String action, int readMs, long totalMs)
            throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            ModuleManager.prepare(context);
            Payloads.extract(context, "widget-host", PAYLOAD);
            return RootShell.session(context, 1800, readMs,
                    adb -> shell(adb, action, totalMs));
        }
    }

    private static String shell(AdbClient adb, String action, long totalMs) throws Exception {
        if (!oneOf(action, "check", "install", "repair", "keepalive", "breeno-hide",
                "breeno-show", "update", "enable", "disable", "uninstall", "capture-check",
                "capture-status", "capture-install", "capture-update", "capture-enable",
                "capture-disable", "capture-uninstall")) {
            throw new IllegalArgumentException("widget action");
        }
        String output = adb.shell("sh " + SOURCE + "/widget-manage.sh " + action, totalMs).trim();
        if (adb.exitCode() != 0) throw new IOException(message(output));
        return output;
    }

    private static Status parseStatus(String output, String action) throws IOException {
        String[] lines = output.split("\\r?\\n");
        if (lines.length < 2) throw new IOException("未收到完整的接入状态，请重新读取");
        String host = lines[0].trim(), keep = lines[1].trim();
        // 共享 v13 的既有 check 只输出两行；新独立运行组件才附加第三行 owner。
        String owner = lines.length > 2 ? lines[2].trim() : "shared";
        boolean added = "added".equals(keep) && ("install".equals(action) || "repair".equals(action));
        if (!oneOf(host, "available", "installed", "ready", "disabled", "update", "setup",
                "conflict")
                || (!oneOf(keep, "present", "missing", "unavailable") && !added)) {
            throw new IOException("未收到完整的接入状态，请重新读取");
        }
        if (!owner.isEmpty() && !oneOf(owner, "own", "shared")) {
            throw new IOException("未收到可确认的运行组件来源，请重新读取");
        }
        return new Status(host, keep, owner);
    }

    private static Status record(Context context, Status status) {
        RuntimeStateStore.recordWidgets(context, status);
        if ("added".equals(status.keep)) {
            context.getSharedPreferences("workshop", 0).edit().putLong(KEEP_BOOT, bootTime()).apply();
        }
        return status;
    }

    private static void readAfterFailure(Context context) {
        try {
            record(context, readStatus(context, "check", QUICK_READ_MS, QUICK_TOTAL_MS));
        } catch (Exception read) {
            try {
                RuntimeStateStore.failWidgets(context, read);
            } catch (Exception ignored) {
                // 缓存也写不进去时只能放弃这次补读。
            }
        }
    }

    private static boolean oneOf(String value, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return true;
        return false;
    }

    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private static String message(String result) {
        if (result.contains("PLUGIN_REQUIRED")) return "请先在本地插件中导入所需功能包；迁移旧运行组件需备齐原已安装功能的包";
        if (result.contains("PLUGIN_INVALID")) return "插件载荷不完整，请重新导入对应插件包";
        if (result.contains("OWN_REQUIRED")) {
            return "共享运行组件不能单独卸载；小组件开关由共享组件管理";
        }
        if (result.contains("SHARED_REQUIRED")) return "需要适配的共享运行组件；当前环境暂不支持接入";
        if (result.contains("ROOT_REQUIRED")) return "本机授权未完成，请先完成管理器首次部署";
        if (result.contains("DEVICE_UNSUPPORTED")) return "当前仅适配 OPPO Watch 2 46mm / A.92";
        if (result.contains("SUPERVISOR_REQUIRED")) return "原有启动服务未运行，请先恢复共享运行组件";
        if (result.contains("START_UNCONFIRMED") || result.contains("NOT_READY")) {
            return "模块文件已保存，启动未确认；请重新接入恢复";
        }
        if (result.contains("BACKUP_MISMATCH")) return "原组件备份不匹配，未替换运行文件";
        if (result.contains("KEEP_UNAVAILABLE")) return "后台保活修复未完成，请检查系统权限后重试";
        if (result.contains("CAPTURE_SETTING_FAILED")) return "截屏录屏模块开关未保存，请重试";
        if (result.contains("MODULE_DISABLED")) return "该功能已停用，请先启用";
        if (result.contains("UPDATE_REQUIRED")) return "运行组件版本需要更新";
        if (result.contains("MODULE_NOT_INSTALLED")) return "该功能尚未安装";
        return "接入未完成，请检查首次授权或重新读取状态";
    }
}
