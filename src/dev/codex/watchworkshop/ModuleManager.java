package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/**
 * 莫奈功能的固定生命周期入口。
 *
 * <p>运行组件与本功能分开管理：{@code kind} 只报告 own/shared/setup/conflict，功能状态由
 * {@code status monet} 报告。安装先以停用状态保存文件，再单独启用并等待运行确认；更新保留
 * 现有启停选择，修复只在独立运行组件上刷新文件并重启已启用的功能。</p>
 */
final class ModuleManager {
    private static final String SOURCE = Payloads.FILES + "monet";
    private static final String[] PAYLOAD = {
        "runtime-core.js", "runtime-commit.js", "runtime-service.sh",
        "runtime-manage.sh", "module-manage.sh"
    };
    private static final List<String> ACTIONS = Arrays.asList(
            "kind", "status", "install", "enable", "disable", "uninstall", "update", "repair");
    private static final int CONNECT_MS = 1800;
    /** 安装、更新和启停会在设备内等待运行组件就绪与旁路准备。 */
    private static final int READ_MS = 35000;
    private static final int SCRIPT_MS = 45000;
    /** 共享组件停用前的配色，重新启用时恢复。 */
    private static final String BEFORE_DISABLE = "monetBeforeDisable";

    private ModuleManager() {}

    /** 释放莫奈目录内的全部运行载荷；小组件脚本转发到同一运行管理器前也会调用。 */
    public static void prepare(Context context) throws IOException {
        synchronized (RuntimeSupport.LOCK) {
            Payloads.extract(context, "monet", PAYLOAD);
            LauncherPlugins.prepare(context);
        }
    }

    static ModuleState read(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            ModuleState state;
            try {
                state = session(context, adb -> readCurrent(context, adb));
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
            if (state.installed() && !state.activeKnown && !state.kind.equals("update")) {
                IOException error = new IOException(state.message);
                RuntimeStateStore.failMonet(context, error, state);
                throw error;
            }
            RuntimeStateStore.recordMonet(context, state);
            return state;
        }
    }

    /** 全新安装 = 保存停用载荷 + 启用并确认；已有停用功能也可直接启用。 */
    static ModuleState install(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                ModuleState after = session(context, adb -> {
                    ModuleState before = readCurrent(context, adb);
                    if (before.kind.equals("available")) {
                        command(adb, "install");
                        ModuleState staged = readCurrent(context, adb);
                        if (!staged.installed() || staged.enabled()) {
                            throw new IOException("安装未停留在停用状态，请刷新后重试");
                        }
                    } else if (!before.kind.equals("disabled")) {
                        if (before.installed() && before.enabled()) return before;
                        if (before.kind.equals("update")) {
                            throw new IOException("组件需要先更新，请在莫奈页点更新");
                        }
                        throw new IOException("当前状态无法安装，请刷新首页");
                    }
                    enableOwn(context, adb);
                    return readCurrent(context, adb);
                });
                requireEnabled(after, "安装结果尚未确认，请刷新后重试");
                RuntimeStateStore.recordMonet(context, after);
                return after;
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
        }
    }

    static ModuleState update(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                ModuleState after = session(context, adb -> {
                    ModuleState before = readCurrent(context, adb);
                    if (!before.installed()) throw new IOException("请先安装莫奈取色");
                    if ("shared".equals(before.owner)) {
                        throw new IOException("共享运行组件请在负一屏或截屏录屏页重新接入");
                    }
                    command(adb, "update");
                    ModuleState updated = readCurrent(context, adb);
                    requirePreserved(before, updated);
                    return updated;
                });
                if (after.kind.equals("update")) throw new IOException("更新未完成，请重试");
                RuntimeStateStore.recordMonet(context, after);
                return after;
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
        }
    }

    static ModuleState setEnabled(Context context, boolean enabled) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                ModuleState after = session(context, adb -> {
                    ModuleState before = readCurrent(context, adb);
                    if (!before.installed()) throw new IOException("请先安装莫奈取色");
                    if (before.kind.equals("update")) {
                        throw new IOException("组件需要先更新，请在莫奈页点更新");
                    }
                    if (before.kind.equals("shared")) {
                        toggleShared(context, adb, before, enabled);
                    } else {
                        if (enabled) enableOwn(context, adb);
                        else command(adb, "disable");
                    }
                    return readCurrent(context, adb);
                });
                if (!after.installed() || !after.activeKnown || after.enabled() != enabled) {
                    throw new IOException("操作结果尚未确认，请刷新后重试");
                }
                RuntimeStateStore.recordMonet(context, after);
                return after;
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
        }
    }

    /** 修复只适用于本包独立运行组件；停用功能保持停用。 */
    static ModuleState repair(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                ModuleState after = session(context, adb -> {
                    ModuleState before = readCurrent(context, adb);
                    if (!before.installed()) throw new IOException("请先安装莫奈取色");
                    if (!"own".equals(before.owner)) {
                        throw new IOException("共享运行组件请使用更新或重新接入");
                    }
                    command(adb, "repair");
                    ModuleState repaired = readCurrent(context, adb);
                    requirePreserved(before, repaired);
                    return repaired;
                });
                RuntimeStateStore.recordMonet(context, after);
                return after;
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
        }
    }

    /** 主题写入或恢复已经生效：共享组件此后按“已启用”显示。 */
    static void rememberThemeEnabled(Context context) throws IOException {
        if (!RuntimeStateStore.rememberMonetEnabled(context, true)) {
            throw new IOException("主题已生效，但启用状态保存失败，请重新读取");
        }
    }

    static ModuleState uninstall(Context context) throws Exception {
        synchronized (RuntimeSupport.LOCK) {
            try {
                ModuleState after = session(context, adb -> {
                    ModuleState before = readCurrent(context, adb);
                    if (!before.removable()) throw new IOException("共享运行组件不能单独卸载，可停用莫奈");
                    command(adb, "uninstall");
                    return readCurrent(context, adb);
                });
                if (!after.kind.equals("available")) throw new IOException("卸载未确认，请刷新状态");
                RuntimeStateStore.recordMonet(context, after);
                return after;
            } catch (Exception e) {
                RuntimeStateStore.failMonet(context, e);
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // 连接内的步骤
    // ------------------------------------------------------------------

    private static <T> T session(Context context, RootShell.Body<T> body) throws Exception {
        prepare(context);
        return RootShell.session(context, CONNECT_MS, READ_MS, body);
    }

    private static String command(AdbClient adb, String action) throws Exception {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("module action");
        String output = adb.shell("sh " + SOURCE + "/module-manage.sh " + action,
                SCRIPT_MS).trim();
        if (adb.exitCode() != 0) throw new IOException(message(output));
        return output;
    }

    private static ModuleState readCurrent(Context context, AdbClient adb) throws Exception {
        String owner = firstLine(command(adb, "kind"));
        if (!oneOf(owner, "own", "shared", "setup", "conflict")) {
            throw new IOException("未收到有效的运行组件来源，请重新读取");
        }
        String status = firstLine(command(adb, "status"));
        switch (status) {
            case "available":
                if (!owner.equals("own")) return environmentState(owner);
                return new ModuleState("available", owner, false, "未安装 · 可直接在手表安装", true,
                        null);
            case "installed":
            case "ready":
                return readRunning(context, adb, owner.equals("shared") ? "shared" : "installed",
                        owner);
            case "shared":
                return readRunning(context, adb, "shared", owner);
            case "disabled":
                return new ModuleState("disabled", owner, false, "已安装 · 已停用", true, null);
            case "update":
                return new ModuleState("update", owner, false, "已安装 · 需要更新", false, null);
            case "setup":
                return new ModuleState("setup", owner, false, "首次使用需要部署管理器", true, null);
            case "conflict":
                return new ModuleState("conflict", owner, false, "已有组件版本不兼容", true, null);
            default:
                throw new IOException("未收到有效的莫奈状态，请重新读取");
        }
    }

    private static ModuleState environmentState(String owner) {
        if (owner.equals("setup")) {
            return new ModuleState("setup", owner, false, "首次使用需要部署管理器", true, null);
        }
        if (owner.equals("conflict")) {
            return new ModuleState("conflict", owner, false, "已有组件版本不兼容", true, null);
        }
        return new ModuleState("conflict", owner, false, "共享运行组件未包含可管理的莫奈功能", true,
                null);
    }

    /** 已安装时再读一次实际配色；读不到明确标记运行状态未确认。 */
    private static ModuleState readRunning(Context context, AdbClient adb, String kind, String owner) {
        String installed = "shared".equals(kind) ? "共享组件" : "已安装";
        try {
            JSONObject state = ThemeRuntime.exchange(adb, "read", null).getJSONObject("state");
            ThemeConfig theme = ThemeRuntime.config(state);
            boolean active = RuntimeStateStore.monetActive(context, kind, theme);
            return new ModuleState(kind, owner, active, installed + (active ? " · 已启用" : " · 已停用"),
                    true, theme);
        } catch (Exception e) {
            return new ModuleState(kind, owner, false,
                    installed + " · 配色状态读取失败，请重试", false, null);
        }
    }

    /** 独立运行组件的 enable 回执早于异步 helper 就绪；有限重试，失败则撤回启用以恢复。 */
    private static void enableOwn(Context context, AdbClient adb) throws Exception {
        command(adb, "enable");
        Exception last = awaitHelper(adb);
        if (last == null) return;
        try {
            command(adb, "disable");
        } catch (Exception ignored) {
            // 原启动错误优先；状态读取会显示实际结果。
        }
        throw new IOException("组件已安装，启动未确认。请点启用重试", last);
    }

    private static Exception awaitHelper(AdbClient adb) throws InterruptedException {
        Exception last = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            try {
                ThemeRuntime.exchange(adb, "read", null);
                return null;
            } catch (Exception e) {
                last = e;
                if (!waitingForHelper(e)) return e;
                Thread.sleep(1000);
            }
        }
        return last;
    }

    private static boolean waitingForHelper(Exception error) {
        String text = error.getMessage();
        return text == null || text.contains("主题组件未加载")
                || error instanceof java.net.SocketTimeoutException;
    }

    /** 共享 Hook 不归 runtime-manage 管理；沿用主题写入和启用意图，停用后仍可恢复原配色。 */
    private static void toggleShared(Context context, AdbClient adb, ModuleState before,
            boolean enabled) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(RuntimeStateStore.MODULE_PREFS, 0);
        JSONObject current = ThemeRuntime.exchange(adb, "read", null).getJSONObject("state");
        ThemeConfig target;
        if (enabled) {
            String saved = prefs.getString(BEFORE_DISABLE, "");
            target = saved.isEmpty()
                    ? new ThemeConfig("auto", WorkshopUi.ACCENT, true)
                    : ThemeRuntime.config(new JSONObject(saved));
        } else {
            if (before.active
                    && !prefs.edit().putString(BEFORE_DISABLE, snapshot(current)).commit()) {
                throw new IOException("停用前设置未保存，请重试");
            }
            target = new ThemeConfig("original", WorkshopUi.ACCENT, false);
        }
        ThemeRuntime.exchange(adb, "apply", target);
        if (!RuntimeStateStore.rememberMonetEnabled(context, enabled)) {
            throw new IOException("启停结果已返回，但本地状态保存失败，请重新读取");
        }
    }

    private static String snapshot(JSONObject state) throws Exception {
        return new JSONObject()
                .put("mode", state.getString("mode"))
                .put("seed", state.getString("seed"))
                .put("corners", state.getBoolean("corners"))
                .toString();
    }

    private static void requireEnabled(ModuleState state, String message) throws IOException {
        if (!state.enabled()) throw new IOException(message);
    }

    /** update/repair 必须保留原有启停选择；运行状态读不出来也算未确认。 */
    private static void requirePreserved(ModuleState before, ModuleState after) throws IOException {
        if (!before.activeKnown) return;
        if (!after.installed() || !after.activeKnown || after.enabled() != before.enabled()) {
            throw new IOException("更新或修复后运行状态未确认，请重新读取");
        }
    }

    private static String firstLine(String value) {
        int end = value.indexOf('\n');
        return (end < 0 ? value : value.substring(0, end)).trim();
    }

    private static boolean oneOf(String value, String... allowed) {
        for (String item : allowed) if (item.equals(value)) return true;
        return false;
    }

    private static String message(String code) {
        if (code.contains("PLUGIN_REQUIRED")) return "请先在本地插件中导入所需功能包；迁移旧运行组件需备齐原已安装功能的包";
        if (code.contains("PLUGIN_INVALID")) return "插件载荷不完整，请重新导入对应插件包";
        if (code.contains("SETUP_REQUIRED") || code.contains("ROOT_REQUIRED")) {
            return "首次部署未完成，请用电脑部署管理器一次";
        }
        if (code.contains("DEVICE_UNSUPPORTED")) return "当前仅适配 OPPO Watch 2 46mm / A.92";
        if (code.contains("OWN_REQUIRED")) return "共享运行组件不能单独卸载，可停用莫奈";
        if (code.contains("SHARED_RUNTIME")) return "现有共享组件不能单独安装或卸载";
        if (code.contains("UNKNOWN_FILES")) return "发现其他组件文件，安装已停止";
        if (code.contains("FOREIGN_RUNTIME")) return "表盘已有其他运行组件，无法安装第二套莫奈";
        if (code.contains("PAYLOAD_INVALID")) return "安装文件不完整，请重新安装管理器 APK";
        if (code.contains("UPDATE_REQUIRED")) return "组件需要先更新";
        if (code.contains("START_FAILED") || code.contains("NOT_READY")) {
            return "组件已安装，启动失败。请点启用重试";
        }
        return "功能操作未完成，请刷新后重试（"
                + code.replace('\n', ' ').substring(0, Math.min(code.length(), 100))
                + "）";
    }
}
