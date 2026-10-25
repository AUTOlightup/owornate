package dev.codex.watchworkshop;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.codex.watchworkshop.MonetOperationState.Phase;
import dev.codex.watchworkshop.ModuleManagement.Action;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.EnumSet;
import java.util.Map;
import org.json.JSONObject;

/**
 * 莫奈取色：安装/启停/卸载、配置页和它们的状态。
 *
 * <p>只读刷新不锁界面：先显示上次确认的状态（{@link RuntimeStateStore}），后台读完再原地更新。
 * 配色方式、固定颜色和圆角保持专用设置；启停、更新与卸载使用共用管理框架。
 * 所有设备操作都排在 {@link DeviceQueues#GENERAL} 上。
 */
final class MonetPages implements ModuleStatusView, ModuleManagement {
    /** 预设色；与 {@link #COLOR_NAMES} 按下标一一对应。 */
    private static final int[] PRESETS = {
        WorkshopUi.ACCENT, 0xff91bdff, 0xffbca8f5, 0xff99d5b4, 0xffefa898, 0xffe3e9f1
    };
    private static final String[] COLOR_NAMES = {"暖金", "雾蓝", "淡紫", "薄荷", "珊瑚", "月白"};

    private final MainActivity a;
    private final SharedPreferences prefs;
    private final LatestThemeQueue<ThemeConfig> writes;
    private final MonetOperationState operations = new MonetOperationState();
    private final ModuleManagementUi managementUi = new ModuleManagementUi();
    private String moduleError = "";
    private String moduleNotice = "";
    private ModuleState state;
    private StatusObservation<ModuleState> observation;
    /** 最近一次设备回执是否成功；设备不可达或回执解析失败时为 false。 */
    private boolean connected;
    /** 设备回执里是否还留有可恢复的上一次设置。 */
    private boolean canRollback;
    /** 本次状态读取的失败原因；空串表示没有失败。 */
    private String error = "";
    /** 配色操作的即时提示；写入成功后清空。 */
    private String themeMessage = "";
    /** 本次会话导出的文件路径；为空时回落到偏好里记录的上次导出。 */
    private String exportPath = "";
    private Integer autoSeed;
    private JSONObject rollbackConfig;
    private ThemeConfig draft;
    /** 结果页内容：上一次操作类型、是否成功、失败原因；同时存进偏好，进程重启后仍可恢复。 */
    private String resultAction;
    private String resultError;
    private boolean resultOk;
    private PluginManifest.Setting modeDefinition;
    private PluginManifest.Setting cornersDefinition;
    private String definitionError = "";

    MonetPages(MainActivity activity) {
        a = activity;
        prefs = activity.prefs;
        try {
            draft = new ThemeConfig(
                    prefs.getString("mode", "auto"),
                    prefs.getInt("seed", WorkshopUi.ACCENT),
                    prefs.getBoolean("corners", true));
        } catch (Exception bad) {
            draft = new ThemeConfig("auto", WorkshopUi.ACCENT, true);
        }
        sync();
        resultAction = prefs.getString("resultAction", "");
        resultOk = prefs.getBoolean("resultOk", false);
        resultError = prefs.getString("resultError", "");
        writes = new LatestThemeQueue<>(DeviceQueues.GENERAL, new LatestThemeQueue.Worker<ThemeConfig>() {
            @Override public void apply(ThemeConfig target) throws Exception {
                markPending();
                JSONObject ack = ThemeRuntime.exchange(a, "apply", target);
                ModuleManager.rememberThemeEnabled(a);
                persistConfirmed(ack);
                a.onUi(() -> {
                    acceptTheme(ack, draft == target);
                    themeMessage = "";
                    a.render();
                });
            }

            @Override public void failed(Exception problem) {
                recoverTheme(problem);
            }

            @Override public void idle() {
                a.onUi(() -> {
                    operations.writesFinished(writes.isRunning());
                    a.render();
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // 给首页和其他页面用的状态
    // ------------------------------------------------------------------

    @Override public boolean known() { return state != null; }
    @Override public boolean installed() { return state != null && state.installed(); }
    @Override public boolean available() {
        return state != null && "available".equals(state.kind) && !failed()
                && observation.confirmedAt > 0;
    }
    boolean incompatible() { return state != null && "conflict".equals(state.kind); }
    boolean needsSetup() { return state != null && "setup".equals(state.kind); }
    @Override public boolean refreshing() { return operations.refreshing(); }
    @Override public boolean failed() { return !error.isEmpty(); }
    @Override public String error() { return error; }
    boolean hasResult() { return !resultAction.isEmpty(); }
    boolean working() { return operations.working(writes.isRunning()); }
    boolean reachable() { return confirmedInstall() && state.active; }
    long confirmedAt() { return observation.confirmedAt; }
    boolean independent() { return state != null && state.removable(); }
    boolean ownRuntime() { return state != null && "own".equals(state.owner); }

    /** 已安装、启停状态已确认，且本次读取没有失败。 */
    private boolean confirmedInstall() {
        return state != null && state.installed() && state.activeKnown && !failed() && observation.confirmedAt > 0;
    }

    /** Called on resume and when any reader publishes a new observation. */
    void sync() {
        observation = RuntimeStateStore.monet(a);
        state = observation.value;
        error = observation.error;
        if (!reachable()) connected = false;
    }

    @Override public String statusText() {
        if (refreshing() && state == null) return "正在读取状态…";
        if (state == null) return error.isEmpty() ? "运行状态待检查" : "状态读取失败";
        if (failed()) return "上次：" + state.message + " · 本次读取失败";
        if (observation.confirmedAt == 0 || refreshing()) return "上次：" + state.message;
        return state.message;
    }

    @Override public String discoverLine() {
        if (failed()) return "读取失败";
        if (state == null || observation.confirmedAt == 0 || refreshing()) return "待确认";
        if (needsSetup()) return "待设置";
        if (incompatible()) return "不兼容";
        if (available()) return "可安装";
        if ("update".equals(state.kind)) return "可更新";
        if (!state.activeKnown) return "待确认";
        return state.active ? "已启用" : "已停用";
    }

    @Override public boolean showOnHome() {
        return installed() || incompatible() || needsSetup();
    }

    @Override public String homeWarning() {
        if (incompatible()) return "莫奈组件不兼容，请检查环境";
        if (needsSetup()) return "莫奈管理通道需要检查";
        return "";
    }

    void registerPages(PageRegistry pages) {
        pages.add(PageIds.DETAIL, "莫奈取色", this::detail);
        pages.addManaged(PageIds.THEME, this, this::theme);
        pages.add(PageIds.THEME_MORE, "更多选项", this::themeMore);
        pages.add(PageIds.THEME_RESTORE, "恢复设置", this::themeRestore);
        pages.add(PageIds.MODES, this::modeTitle, this::modes);
        pages.add(PageIds.COLORS, "固定颜色", this::colors);
        pages.add(PageIds.INSTALL, "安装方案", this::installPlan);
        pages.add(PageIds.UNINSTALL, "卸载优化", this::uninstallConfirm);
        pages.add(PageIds.PROGRESS, "正在应用", this::progress);
        pages.add(PageIds.RESULT, "操作结果", this::result);
        pages.add(PageIds.EXPORT, "导出配置", this::exportPage);
    }

    @Override public String moduleTitle() { return "莫奈取色"; }

    @Override public ModuleManagement.State managementState() {
        EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
        Boolean enabled = null;
        String limitation = "";
        if (state != null) {
            if (available()) {
                enabled = false;
                if ("own".equals(state.owner) || "shared".equals(state.owner)) actions.add(Action.INSTALL);
            }
            if (installed()) {
                boolean own = "own".equals(state.owner);
                boolean shared = "shared".equals(state.owner);
                if (state.activeKnown && !"update".equals(state.kind)) enabled = state.active;
                if ((own || shared) && !"update".equals(state.kind)) {
                    actions.add(Action.ENABLE);
                    actions.add(Action.DISABLE);
                }
                if (own) {
                    actions.add(Action.UNINSTALL);
                    actions.add("update".equals(state.kind) ? Action.UPDATE : Action.REPAIR);
                } else {
                    limitation = shared ? "共享运行组件不能单独卸载；可停用莫奈，其他功能不受影响。"
                            : "运行组件来源尚未确认，请重新读取状态。";
                }
            } else if (needsSetup()) limitation = "请先完成管理器首次部署。";
            else if (incompatible()) limitation = "已有运行组件不兼容，请检查运行环境。";
        }
        String status = failed() ? state == null ? "状态待确认" : "上次：" + state.message : statusText();
        return new ModuleManagement.State(installed(), enabled, observation.confirmedAt > 0,
                a.working(), status, error, moduleError, moduleNotice, limitation, actions);
    }

    @Override public String uninstallMessage() {
        return "卸载莫奈取色后控制中心恢复系统外观。保留管理器、其他功能及保存的配置；表盘会重新加载一次。";
    }

    @Override public void executeManagement(Action action) {
        switch (action) {
            case REFRESH: refresh(true); break;
            case INSTALL: a.go(PageIds.INSTALL); break;
            case ENABLE: moduleAction(Phase.ENABLING); break;
            case DISABLE: moduleAction(Phase.DISABLING); break;
            case UPDATE: moduleAction(Phase.UPDATING); break;
            case REPAIR: moduleAction(Phase.REPAIRING); break;
            case UNINSTALL: moduleAction(Phase.UNINSTALLING); break;
            default: break;
        }
    }

    /** 进入配置页：已确认可用就读一次当前配色，否则先读状态。 */
    void enterTheme() {
        if (reachable()) {
            loadDefinitions();
            themeAction("preview");
        } else refresh(true);
    }

    // ------------------------------------------------------------------
    // 只读状态
    // ------------------------------------------------------------------

    void refresh(boolean force) {
        sync();
        if (a.destroyed() || a.working()) return;
        loadDefinitions();
        if (!force && observation.fresh(System.currentTimeMillis(), MainActivity.FRESH_MS)) return;
        if (!operations.begin(Phase.REFRESHING, writes.isRunning())) return;
        a.render();
        // ModuleManager 自己把成功或失败写进共享状态（失败时保留上次的值），这里只需重新投影。
        a.inBackground(DeviceQueues.GENERAL, () -> ModuleManager.read(a), (read, problem) -> {
            operations.finish(Phase.REFRESHING);
            sync();
            a.render();
            if (a.page.equals(PageIds.THEME)) {
                if (state != null && !failed() && !state.installed()) a.leave(PageIds.THEME);
                else if (reachable()) themeAction("preview");
            }
        });
    }

    // ------------------------------------------------------------------
    // 主题读写
    // ------------------------------------------------------------------

    /** preview：读当前设置并取预览；rollback：恢复上一次设置。 */
    void themeAction(String action) {
        if (a.destroyed() || a.working() || !reachable()) return;
        boolean rollback = "rollback".equals(action);
        if (!operations.begin(rollback ? Phase.ROLLING_BACK : Phase.READING_THEME, writes.isRunning())) return;
        themeMessage = "";
        a.render();
        DeviceQueues.GENERAL.execute(rollback ? this::rollbackTheme : this::readTheme);
    }

    /** 读当前设置的结果：要显示的回执，以及预览是否没取到。 */
    private static final class Shown {
        final JSONObject ack;
        final boolean previewFailed;

        Shown(JSONObject ack, boolean previewFailed) {
            this.ack = ack;
            this.previewFailed = previewFailed;
        }
    }

    /** 后台：读当前设置，再在同一个连接上按它取预览；预览失败时显示当前实际设置。 */
    private void readTheme() {
        try {
            Shown shown = RootShell.session(a, 1800, 12000, adb -> {
                JSONObject current = ThemeRuntime.exchange(adb, "read", null);
                try {
                    ThemeConfig actual = ThemeRuntime.config(current.getJSONObject("state"));
                    return new Shown(ThemeRuntime.exchange(adb, "preview", actual), false);
                } catch (Exception previewError) {
                    return new Shown(current, true);
                }
            });
            persistConfirmed(shown.ack);
            String feedback = shown.previewFailed ? "预览读取未完成，已显示当前实际设置。" : "";
            a.onUi(() -> {
                operations.finish(Phase.READING_THEME);
                acceptTheme(shown.ack, true);
                themeMessage = feedback;
                a.render();
            });
        } catch (Exception e) {
            RuntimeStateStore.failMonet(a, e);
            a.onUi(() -> {
                operations.finish(Phase.READING_THEME);
                connected = false;
                themeMessage = WatchActivity.message(e);
                a.render();
            });
        }
    }

    /** 后台：恢复上一次设置；没有确认时按实际状态回读。 */
    private void rollbackTheme() {
        try {
            markPending();
            JSONObject ack = ThemeRuntime.exchange(a, "rollback", null);
            ModuleManager.rememberThemeEnabled(a);
            persistConfirmed(ack);
            a.onUi(() -> {
                operations.finish(Phase.ROLLING_BACK);
                acceptTheme(ack, true);
                themeMessage = "已恢复上一次设置";
                a.render();
            });
        } catch (Exception e) {
            recoverTheme(e);
        }
    }

    private void markPending() throws IOException {
        MonetSettings.markPending(a);
    }

    /** 回执里的实际设置记到本地，并写进共享状态。 */
    private void persistConfirmed(JSONObject ack) throws Exception {
        MonetSettings.persistConfirmed(a, ack);
    }

    /** 在进入配置页或刷新时读取一次插件声明并保留；render 只使用这两个字段。 */
    private void loadDefinitions() {
        try {
            modeDefinition = MonetSettings.definition(a, "mode");
            cornersDefinition = MonetSettings.definition(a, "corners");
            definitionError = "";
        } catch (Exception e) {
            modeDefinition = null;
            cornersDefinition = null;
            definitionError = "插件设置声明读取失败：" + WatchActivity.message(e);
        }
    }

    /** 主线程：按回执更新页面状态；syncSelection 为 true 时把草稿换成实际设置。 */
    private void acceptTheme(JSONObject ack, boolean syncSelection) {
        sync();
        try {
            connected = true;
            ThemeConfig actual = ThemeRuntime.config(ack.getJSONObject("state"));
            if (syncSelection) draft = actual;
            JSONObject palette = ack.optJSONObject("preview");
            autoSeed = actual.mode.equals("auto")
                    ? (palette == null ? actual.seed : ThemeRuntime.color(palette.getString("seed")))
                    : null;
            canRollback = ack.optBoolean("canRollback");
            rollbackConfig = ack.optJSONObject("rollbackConfig");
        } catch (Exception e) {
            connected = false;
            themeMessage = WatchActivity.message(e);
        }
    }

    /** 后台：写入没有得到确认时，先提示，再读一次实际状态，按读到的结果更新。 */
    private void recoverTheme(Exception problem) {
        RuntimeStateStore.failMonet(a, problem);
        a.onUi(() -> {
            connected = false;
            themeMessage = "操作未确认，正在重新读取…";
            a.render();
        });
        JSONObject recovered = null;
        String message = "操作未确认：" + WatchActivity.message(problem);
        try {
            recovered = ThemeRuntime.exchange(a, "read", null);
            persistConfirmed(recovered);
            message += "；已按实际状态更新。";
        } catch (Exception readError) {
            RuntimeStateStore.failMonet(a, readError);
            message += "；请重新读取状态。";
        }
        final JSONObject actual = recovered;
        final String detail = message;
        a.onUi(() -> {
            operations.finish(Phase.ROLLING_BACK);
            operations.writesFinished(writes.isRunning());
            connected = false;
            if (actual != null) acceptTheme(actual, true);
            themeMessage = detail;
            a.render();
        });
    }

    private boolean editable() {
        return settingsReady() && !a.destroyed() && connected && reachable()
                && operations.editable(writes.isRunning());
    }

    private boolean settingsReady() {
        return modeDefinition != null && cornersDefinition != null && definitionError.isEmpty();
    }

    /** 选了就提交；同时只有一个写入在途，后来的选择替换排队中的那一个。back=true 时选完回上一页。 */
    private void save(ThemeConfig next, boolean back) {
        if (!editable()) {
            Toast.makeText(a, "请先启用并读取当前设置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!draft.sameAs(next)) {
            if (!writes.submit(next)) {
                Toast.makeText(a, "正在确认上次操作，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            draft = next;
            operations.themeSubmitted();
            themeMessage = "";
        }
        if (back && !a.stack.isEmpty()) a.page = a.stack.pop();
        a.render();
    }

    // ------------------------------------------------------------------
    // 安装、启停、卸载
    // ------------------------------------------------------------------

    /** enable/disable 在配置页原地进行；install/uninstall 先进进度页，结束后显示结果页。 */
    private void moduleAction(Phase phase) {
        if (a.destroyed() || a.working()) return;
        Action actionKind;
        switch (phase) {
            case INSTALLING: actionKind = Action.INSTALL; break;
            case UPDATING: actionKind = Action.UPDATE; break;
            case REPAIRING: actionKind = Action.REPAIR; break;
            case ENABLING: actionKind = Action.ENABLE; break;
            case DISABLING: actionKind = Action.DISABLE; break;
            case UNINSTALLING: actionKind = Action.UNINSTALL; break;
            default: return;
        }
        if (!managementState().can(actionKind)) return;
        if (!operations.begin(phase, writes.isRunning())) return;
        String action = phase.command;
        boolean inline = phase == Phase.ENABLING || phase == Phase.DISABLING;
        themeMessage = "";
        moduleError = "";
        moduleNotice = "";
        connected = false;
        if (inline) a.render();
        else a.replace(PageIds.PROGRESS);
        a.inBackground(DeviceQueues.GENERAL, () -> runModuleAction(action), (problem, unused) -> {
            operations.finish(phase);
            sync();
            moduleError = problem;
            if (inline) {
                if (problem.isEmpty()) moduleNotice = phase == Phase.DISABLING
                        ? "已停用，配色设置保留。" : "已启用莫奈取色。";
                a.render();
                if (a.page.equals(PageIds.THEME) && reachable()) themeAction("preview");
            } else {
                showResult(action, problem.isEmpty(), problem);
            }
            a.returnAfterLauncherReload();
        });
    }

    /** 后台执行一次模块操作；返回给用户看的问题，成功时为空串。失败后再读一次实际状态。 */
    private String runModuleAction(String action) {
        try {
            switch (action) {
                case "install": ModuleManager.install(a); break;
                case "update": ModuleManager.update(a); break;
                case "repair": ModuleManager.repair(a); break;
                case "enable": ModuleManager.setEnabled(a, true); break;
                case "disable": ModuleManager.setEnabled(a, false); break;
                case "uninstall": ModuleManager.uninstall(a); break;
                default: break;
            }
            return "";
        } catch (Exception e) {
            String problem = WatchActivity.message(e);
            try {
                ModuleManager.read(a);
            } catch (Exception readError) {
                problem += "；状态读取失败：" + WatchActivity.message(readError);
            }
            return problem;
        }
    }

    private void showResult(String action, boolean ok, String problem) {
        resultAction = action;
        resultOk = ok;
        resultError = problem;
        prefs.edit()
                .putString("resultAction", action)
                .putBoolean("resultOk", ok)
                .putString("resultError", problem)
                .apply();
        if (a.page.equals(PageIds.PROGRESS)) a.replace(PageIds.RESULT);
        else a.render();
    }

    private String destination() {
        if (resultAction.equals("uninstall") && !installed()) return PageIds.HOME;
        return resultAction.equals("install") ? PageIds.DETAIL : PageIds.THEME;
    }

    void finishResult() {
        if (a.working()) return;
        a.jump(destination());
        refresh(true);
    }

    // ------------------------------------------------------------------
    // 页面
    // ------------------------------------------------------------------

    private void detail() {
        WorkshopUi ui = a.ui;
        ui.hero("palette", "莫奈取色", "让控制中心与表盘配色协调", "");
        if (installed()) {
            ui.note(statusText(), state.enabled() ? "success" : "");
            ui.button("打开配置", true, () -> a.go(PageIds.THEME));
            if (state.kind.equals("update") && !state.owner.equals("shared")) {
                ui.button(Action.UPDATE.label, true, managementState().can(Action.UPDATE), () -> request(Action.UPDATE));
            }
        } else if (state != null && state.kind.equals("available")) {
            ui.note("适配当前手表 · 可安装", "success");
            ui.button("安装", true, managementState().can(Action.INSTALL), () -> request(Action.INSTALL));
        } else if (state != null && state.kind.equals("setup")) {
            ui.note("完成一次首次部署，即可在手表离线安装。");
            ui.row("首次设置", "", "book", () -> a.go(PageIds.GUIDE));
        } else if (state != null) {
            ui.note(statusText(), "warning");
            ui.row("运行环境", "", "shield", () -> a.go(PageIds.CHECK));
            ui.button("重新读取状态", false, !a.working(), () -> refresh(true));
        } else {
            ui.note(error.isEmpty() ? "正在读取安装状态…" : error);
            ui.button("重新读取状态", true, !a.working(), () -> refresh(true));
        }
        ui.title("功能介绍");
        ui.text("自动取色：跟随当前表盘的真实配色。\n固定颜色：选择喜欢的控制中心颜色。\n系统原色：保留系统配色。\n连续圆角：独立调整按钮轮廓。");
        ui.value("适配设备", "OPPO Watch 2 46mm · A.92");
        ui.value("模块版本", "莫奈组件 v6");
        ui.value("安装来源", "应用内置 · 无需联网");
        ui.note("仅影响 Android 控制中心。");
    }

    private void theme() {
        WorkshopUi ui = a.ui;
        ModuleManagementUi.status(ui, this);
        if (state != null && !state.installed()) {
            ui.row("查看功能介绍", "", "compass", () -> a.go(PageIds.DETAIL));
            managementUi.actions(ui, this, a::render);
            return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(MonetViews.ThemePreview.HEIGHT_DP));
        lp.bottomMargin = a.dp(WorkshopUi.GAP);
        a.body.addView(new MonetViews.ThemePreview(a, draft, autoSeed), lp);
        View mode = ui.row(modeTitle(), modeValue(draft.mode), "palette", () -> a.go(PageIds.MODES));
        if (!settingsReady()) dim(mode);
        if (draft.mode.equals("fixed")) {
            View fixed = ui.row("固定颜色", colorName(draft.seed), "dot", () -> a.go(PageIds.COLORS));
            if (!settingsReady()) dim(fixed);
        }
        View corners = ui.toggle(cornersTitle(), cornersValue(draft.corners), draft.corners,
                () -> save(new ThemeConfig(draft.mode, draft.seed, !draft.corners), false));
        dim(corners);
        statusNotes();
        ui.row("更多选项", "刷新配色、恢复上一次", "sliders", () -> a.go(PageIds.THEME_MORE));
        managementUi.actions(ui, this, a::render);
    }

    /** 配色业务的补充提示；模块状态、读取错误和操作反馈由共用框架展示。 */
    private void statusNotes() {
        WorkshopUi ui = a.ui;
        boolean disabled = state != null && state.activeKnown && !state.active;
        definitionNote(ui);
        if (disabled) ui.note("已停用，打开右上开关后可调整设置。");
        else if (!connected && themeMessage.isEmpty() && error.isEmpty() && !a.working()) ui.note("当前设置尚未确认。");
        if (!themeMessage.isEmpty()) ui.note(themeMessage, connected ? "" : "warning");
    }

    private void themeMore() {
        WorkshopUi ui = a.ui;
        ModuleManagementUi.status(ui, this);
        statusNotes();
        ui.button("刷新表盘配色", false, !a.working() && reachable(), () -> themeAction("preview"));
        ui.button("恢复上一次设置", false,
                canRollback && connected && reachable() && !a.working(),
                () -> a.go(PageIds.THEME_RESTORE));
        ui.value("当前版本", "莫奈组件 v6");
        ui.row("模块管理", "", "sliders", () -> a.go(PageIds.THEME));
    }

    private void themeRestore() {
        WorkshopUi ui = a.ui;
        ui.text("恢复上一次设置？");
        if (rollbackConfig != null) {
            ui.value("配色方式", ThemeConfig.modeLabel(rollbackConfig.optString("mode")));
            if ("fixed".equals(rollbackConfig.optString("mode"))) ui.value("颜色", rollbackConfig.optString("seedArgb"));
            ui.value("圆角", rollbackConfig.optBoolean("corners") ? "连续圆角" : "原生圆角");
        }
        ui.note("恢复最近一次成功应用之前的设置。");
        ui.button("确认恢复", true,
                connected && reachable() && !a.working() && canRollback && rollbackConfig != null, () -> {
            a.onBackPressed();
            themeAction("rollback");
        });
        ui.button("取消", false, a::onBackPressed);
    }

    private void modes() {
        if (!settingsReady()) {
            definitionNote(a.ui);
            return;
        }
        for (Map.Entry<String, String> option : modeDefinition.options.entrySet()) {
            String mode = option.getKey();
            dim(a.ui.choice(option.getValue(), modeSubtitle(mode), draft.mode.equals(mode),
                    () -> save(new ThemeConfig(mode, draft.seed, draft.corners), true)));
        }
    }

    private String modeTitle() {
        return modeDefinition == null ? "配色方式" : modeDefinition.title;
    }

    private String modeValue(String mode) {
        return modeDefinition == null ? ThemeConfig.modeLabel(mode) : modeDefinition.options.get(mode);
    }

    private String cornersTitle() {
        return cornersDefinition == null ? "连续圆角" : cornersDefinition.title;
    }

    private String cornersValue(boolean enabled) {
        return cornersDefinition == null
                ? (enabled ? "连续圆角" : "原生圆角")
                : cornersDefinition.options.get(Boolean.toString(enabled));
    }

    private static String modeSubtitle(String mode) {
        if ("auto".equals(mode)) return "跟随表盘配色";
        if ("fixed".equals(mode)) return "使用选择的主题色";
        if ("original".equals(mode)) return "恢复默认配色";
        return "";
    }

    private void definitionNote(WorkshopUi ui) {
        if (settingsReady()) return;
        if (!definitionError.isEmpty()) ui.note(definitionError, "warning");
        else ui.note("正在读取插件设置声明…");
    }

    private void dim(View view) {
        view.setEnabled(editable());
        view.setAlpha(editable() ? 1f : .4f);
    }

    private static String colorName(int color) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (color == PRESETS[i]) return COLOR_NAMES[i];
        }
        return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
    }

    /** 两行三列的预设色；选中后自动应用并回到配置页。 */
    private void colors() {
        if (!settingsReady()) {
            definitionNote(a.ui);
            return;
        }
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(a);
            for (int x = 0; x < 3; x++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (x < 2) lp.rightMargin = a.dp(6);
                line.addView(colorCell(y * 3 + x), lp);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = a.dp(WorkshopUi.GAP);
            a.body.addView(line, rowParams);
        }
        a.ui.note("选择后自动应用。仅固定配色时生效。");
    }

    private View colorCell(int index) {
        boolean chosen = draft.seed == PRESETS[index];
        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setMinimumHeight(a.dp(56));
        cell.setPadding(a.dp(4), a.dp(7), a.dp(4), a.dp(7));
        GradientDrawable bg = a.shape(chosen ? WorkshopUi.ACCENT_SURFACE : WorkshopUi.SURFACE, WorkshopUi.NOTE_RADIUS);
        bg.setStroke(chosen ? a.dp(1) : Math.max(1, a.dp(0.5f)),
                chosen ? WorkshopUi.SELECT_RING : WorkshopUi.OUTLINE);
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22ffffff), bg,
                a.shape(0xffffffff, WorkshopUi.NOTE_RADIUS)));
        cell.addView(new MonetViews.ColorDot(a, PRESETS[index], chosen),
                new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
        TextView label = new TextView(a);
        label.setText(COLOR_NAMES[index]);
        label.setTextColor(chosen ? WorkshopUi.ACCENT : WorkshopUi.FG);
        label.setTextSize(WorkshopUi.SECONDARY_SP);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = a.dp(4);
        cell.addView(label, labelParams);
        cell.setSelected(chosen);
        cell.setContentDescription(COLOR_NAMES[index] + (chosen ? "，已选择" : ""));
        dim(cell);
        cell.setOnClickListener(v -> save(new ThemeConfig(draft.mode, PRESETS[index], draft.corners), true));
        return cell;
    }

    private void installPlan() {
        WorkshopUi ui = a.ui;
        ui.value("本次安装", "莫奈取色 · 组件 v6");
        ui.value("安装步骤", "先保存为停用，再启动并确认运行状态");
        ui.value("安装来源", "管理器内置 · 无需下载");
        ui.note("基础运行环境已就绪。安装只增加本功能，不改变其他已安装功能。");
        ui.button("安装并启用", true,
                managementState().can(Action.INSTALL),
                () -> moduleAction(Phase.INSTALLING));
        ui.button("暂不安装", false, a::onBackPressed);
    }

    private void uninstallConfirm() {
        WorkshopUi ui = a.ui;
        ui.text("卸载莫奈取色？控制中心将恢复系统外观。");
        ui.note("只移除本功能文件；管理器、运行组件、小组件、录屏功能及保存的配置都会保留。");
        boolean removable = state != null && state.removable();
        if (removable) ui.note("操作会重新加载表盘，可能短暂黑屏。", "warning");
        ui.button("确认卸载", true, managementState().can(Action.UNINSTALL), () -> request(Action.UNINSTALL));
        ui.button("取消", false, a::onBackPressed);
    }

    private void progress() {
        String detail;
        switch (operations.phase()) {
            case INSTALLING: detail = "保存内置组件，再启动并确认运行状态"; break;
            case UPDATING: detail = "刷新组件文件并保留当前启停选择"; break;
            case REPAIRING: detail = "刷新运行文件并恢复已启用功能"; break;
            case UNINSTALLING: detail = "移除莫奈文件并重新加载表盘"; break;
            default: detail = "正在等待手表确认"; break;
        }
        a.ui.hero("refresh", "正在应用更改", detail, "");
        a.ui.note("完成后显示实际结果，请稍候。");
    }

    private void result() {
        WorkshopUi ui = a.ui;
        if (resultOk) {
            String detail = resultAction.equals("uninstall") ? "管理器保留，可在手表上重新安装" : "更改已经应用到手表";
            ui.hero("check", resultHeading(), detail, "success");
        } else {
            ui.hero("warning", "结果待确认", "请重新读取实际状态后再操作。", "warning");
            ui.note(resultError);
        }
        ui.button(resultOk ? returnLabel(destination()) : "重新读取状态", true, !a.working(), this::finishResult);
    }

    private String resultHeading() {
        switch (resultAction) {
            case "rollback": return "已经恢复";
            case "install": return "安装完成";
            case "update": return "更新完成";
            case "repair": return "修复完成";
            case "disable": return "已停用";
            case "uninstall": return "已卸载";
            default: return "已生效";
        }
    }

    private static String returnLabel(String target) {
        if (target.equals(PageIds.DETAIL)) return "返回功能介绍";
        return target.equals(PageIds.THEME) ? "继续配置" : "返回首页";
    }

    // ------------------------------------------------------------------
    // 导出（技术详情页的入口）
    // ------------------------------------------------------------------

    boolean exportable() { return !a.working() && reachable(); }

    /** 读一次实际设置并导出成 JSON 快照；导出文件不执行任何更改。 */
    void exportDraft() {
        if (!exportable()) return;
        if (!operations.begin(Phase.EXPORTING, writes.isRunning())) return;
        a.render();
        a.inBackground(DeviceQueues.GENERAL, () -> {
            ThemeConfig actual = ThemeRuntime.config(ThemeRuntime.exchange(a, "read", null).getJSONObject("state"));
            File file = writeExport(a, actual);
            if (!prefs.edit().putString("lastExport", file.getAbsolutePath()).commit()) {
                throw new IOException("导出位置保存失败");
            }
            return file;
        }, (file, problem) -> {
            operations.finish(Phase.EXPORTING);
            if (problem != null) {
                Toast.makeText(a, "导出失败：" + WatchActivity.message(problem), Toast.LENGTH_LONG).show();
                a.render();
                return;
            }
            exportPath = file.getAbsolutePath();
            if (a.page.equals(PageIds.TECHNICAL)) a.go(PageIds.EXPORT);
            else a.render();
        });
    }

    private static File writeExport(Context context, ThemeConfig actual) throws Exception {
        JSONObject json = new JSONObject()
                .put("schemaVersion", 1).put("moduleId", "control-center-theme")
                .put("mode", actual.mode).put("seedArgb", actual.seedHex()).put("corners", actual.corners)
                .put("applied", false).put("deviceStateConfirmed", true)
                .put("capturedAt", System.currentTimeMillis())
                .put("origin", context.getPackageName() + "/" + SystemPages.appVersion(context));
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "exports");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建导出目录");
        File file = new File(dir, "theme-config.json");
        Payloads.writeAtomically(file, json.toString(2).getBytes("UTF-8"));
        return file;
    }

    private void exportPage() {
        WorkshopUi ui = a.ui;
        ui.text("配置已导出");
        ui.note("这是当前已确认设置的快照，导出文件不执行更改。");
        String path = exportPath.isEmpty() ? prefs.getString("lastExport", "") : exportPath;
        ui.value("保存位置", path);
        ui.button("复制文件路径", false, () -> {
            ClipboardManager clipboard = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("配置路径", path));
            Toast.makeText(a, "路径已复制", Toast.LENGTH_SHORT).show();
        });
    }
}
