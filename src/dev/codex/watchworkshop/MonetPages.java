package dev.codex.watchworkshop;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.codex.watchworkshop.palette.ThemePalettePreview;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 莫奈取色：安装/启停/卸载、配置页和它们的状态。
 *
 * <p>只读刷新不锁界面：先显示上次确认的状态（存在 prefs），后台读完再原地更新。
 * 配置页平时只有配色方式、固定颜色和圆角三项；刷新配色、恢复上一次和卸载放进“更多选项”。
 */
final class MonetPages {
    /** 莫奈的 ADB 操作和设备检测共用这一条线；小组件的状态读取走自己的线，互不排队。 */
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int[] PRESETS = {WorkshopUi.ACCENT, 0xff91bdff, 0xffbca8f5, 0xff99d5b4, 0xffefa898, 0xffe3e9f1};
    private static final String[] COLOR_NAMES = {"暖金", "雾蓝", "淡紫", "薄荷", "珊瑚", "月白"};

    private final MainActivity a;
    private final SharedPreferences prefs;
    private LatestThemeQueue<ThemeConfig> writes;
    private ModuleState state;
    private StatusObservation<ModuleState> observation;
    private boolean refreshing, opBusy, themeBusy, saveBusy, connected, canRollback;
    private String error = "", operation = "", themeMessage = "", exportPath = "";
    private Integer autoSeed;
    private JSONObject rollbackConfig;
    private ThemeConfig draft;
    private String resultAction, resultError;
    private boolean resultOk;

    MonetPages(MainActivity activity) {
        a = activity;
        prefs = activity.prefs;
        try {
            draft = new ThemeConfig(prefs.getString("mode", "auto"), prefs.getInt("seed", WorkshopUi.ACCENT), prefs.getBoolean("corners", true));
        } catch (Exception bad) {
            draft = new ThemeConfig("auto", WorkshopUi.ACCENT, true);
        }
        sync();
        resultAction = prefs.getString("resultAction", "");
        resultOk = prefs.getBoolean("resultOk", false);
        resultError = prefs.getString("resultError", "");
        writes = new LatestThemeQueue<>(IO, new LatestThemeQueue.Worker<ThemeConfig>() {
            public void apply(ThemeConfig target) throws Exception {
                markPending();
                JSONObject ack = ThemeRuntime.exchange(a, "apply", target);
                ModuleManager.rememberThemeEnabled(a);
                persistConfirmed(ack);
                a.runOnUiThread(() -> {
                    if (a.destroyed()) return;
                    acceptTheme(ack, draft == target);
                    themeMessage = "";
                    a.render();
                });
            }
            public void failed(Exception problem) { recoverTheme(problem); }
            public void idle() {
                a.runOnUiThread(() -> {
                    if (a.destroyed()) return;
                    themeBusy = writes.isRunning();
                    a.render();
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // 给首页和其他页面用的状态
    // ------------------------------------------------------------------

    boolean known() { return state != null; }
    boolean installed() { return state != null && state.installed(); }
    boolean available() { return state != null && "available".equals(state.kind) && !failed() && observation.confirmedAt > 0; }
    boolean incompatible() { return state != null && "conflict".equals(state.kind); }
    boolean needsSetup() { return state != null && "setup".equals(state.kind); }
    boolean refreshing() { return refreshing; }
    boolean failed() { return !error.isEmpty(); }
    String error() { return error; }
    boolean hasResult() { return !resultAction.isEmpty(); }
    boolean working() { return themeBusy || saveBusy || opBusy || refreshing || writes.isRunning(); }
    boolean reachable() { return state != null && state.installed() && state.activeKnown && state.active && !failed() && observation.confirmedAt > 0; }
    long confirmedAt() { return observation.confirmedAt; }
    boolean independent() { return state != null && state.removable(); }

    /** Called on resume and when any reader publishes a new observation. */
    void sync() {
        observation = RuntimeStateStore.monet(a);
        state = observation.value;
        error = observation.error;
        if (!reachable()) connected = false;
    }

    String statusText() {
        if (refreshing && state == null) return "正在读取状态…";
        if (state == null) return error.isEmpty() ? "运行状态待检查" : "状态读取失败";
        if (failed()) return "上次：" + state.message + " · 本次读取失败";
        if (observation.confirmedAt == 0 || refreshing) return "上次：" + state.message;
        return state.message;
    }

    String discoverLine() {
        if (installed()) return "已安装 · " + statusText();
        if (available()) return "内置 · 可离线安装";
        return statusText();
    }

    void homeRow() {
        a.ui.row("莫奈取色", statusText(), "palette", () -> a.go(installed() ? "theme" : "detail"));
    }

    String title(String page) {
        switch (page) {
            case "detail": case "theme": return "莫奈取色";
            case "theme-more": return "更多选项";
            case "theme-restore": return "恢复设置";
            case "modes": return "配色方式";
            case "colors": return "固定颜色";
            case "install": return "安装方案";
            case "uninstall": return "卸载优化";
            case "progress": return "正在应用";
            case "result": return "操作结果";
            case "export": return "导出配置";
            default: return null;
        }
    }

    boolean render(String page) {
        switch (page) {
            case "detail": detail(); return true;
            case "theme": theme(); return true;
            case "theme-more": themeMore(); return true;
            case "theme-restore": themeRestore(); return true;
            case "modes": modes(); return true;
            case "colors": colors(); return true;
            case "install": installPlan(); return true;
            case "uninstall": uninstallConfirm(); return true;
            case "progress": progress(); return true;
            case "result": result(); return true;
            case "export": exportPage(); return true;
            default: return false;
        }
    }

    void themeHeader() {
        boolean known = state != null && state.installed() && state.activeKnown && !failed() && observation.confirmedAt > 0;
        a.ui.headerToggle("莫奈取色", a::onBackPressed, known ? Boolean.valueOf(state.active) : null,
                !a.working() && known, () -> moduleAction(state.active ? "disable" : "enable"));
    }

    /** 进入配置页：已确认可用就读一次当前配色，否则先读状态。 */
    void enterTheme() {
        if (reachable()) themeAction("preview");
        else refresh(true);
    }

    // ------------------------------------------------------------------
    // 只读状态
    // ------------------------------------------------------------------

    void refresh(boolean force) {
        sync();
        if (a.destroyed() || a.working() || refreshing) return;
        if (!force && observation.fresh(System.currentTimeMillis(), MainActivity.FRESH_MS)) return;
        refreshing = true;
        a.render();
        IO.execute(() -> {
            try {
                ModuleManager.read(a);
            } catch (Exception e) {
                // ModuleManager publishes the failure without discarding the last value.
            }
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                refreshing = false;
                sync();
                a.render();
                if (a.page.equals("theme")) {
                    if (state != null && !failed() && !state.installed()) a.leave("theme");
                    else if (reachable()) themeAction("preview");
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // 主题读写
    // ------------------------------------------------------------------

    void themeAction(String action) {
        if (a.destroyed() || a.working() || !reachable()) return;
        final boolean mutation = "rollback".equals(action);
        themeBusy = true;
        themeMessage = "";
        a.render();
        IO.execute(() -> {
            try {
                JSONObject ack;
                String feedback = "";
                if (mutation) {
                    markPending();
                    ack = ThemeRuntime.exchange(a, "rollback", null);
                    ModuleManager.rememberThemeEnabled(a);
                } else {
                    // 读当前设置和取预览在同一个连接上完成。
                    JSONObject[] pair = RootShell.session(a, 1800, 12000, adb -> {
                        JSONObject current = ThemeRuntime.exchange(adb, "read", null);
                        try {
                            return new JSONObject[]{ThemeRuntime.exchange(adb, "preview", configFromState(current.getJSONObject("state"))), null};
                        } catch (Exception previewError) {
                            return new JSONObject[]{current, current};
                        }
                    });
                    ack = pair[0];
                    if (pair[1] != null) feedback = "预览读取未完成，已显示当前实际设置。";
                }
                persistConfirmed(ack);
                final JSONObject confirmed = ack;
                final String message = feedback;
                a.runOnUiThread(() -> {
                    if (a.destroyed()) return;
                    themeBusy = false;
                    acceptTheme(confirmed, true);
                    themeMessage = mutation ? "已恢复上一次设置" : message;
                    a.render();
                });
            } catch (Exception e) {
                if (mutation) recoverTheme(e);
                else {
                    RuntimeStateStore.failMonet(a, e);
                    a.runOnUiThread(() -> {
                    if (a.destroyed()) return;
                    themeBusy = false;
                    connected = false;
                    themeMessage = WatchActivity.message(e);
                    a.render();
                });
                }
            }
        });
    }

    private static ThemeConfig configFromState(JSONObject state) throws Exception {
        return new ThemeConfig(state.getString("mode"), ThemeRuntime.color(state.getString("seed")), state.getBoolean("corners"));
    }

    private void markPending() throws IOException {
        if (!prefs.edit().putBoolean("pendingTheme", true).commit()) throw new IOException("无法保存待确认状态，本次未提交");
    }

    private void persistConfirmed(JSONObject ack) throws Exception {
        JSONObject current = ack.getJSONObject("state");
        ThemeConfig actual = configFromState(current);
        SharedPreferences.Editor edit = prefs.edit().putBoolean("pendingTheme", false)
                .putString("mode", actual.mode).putInt("seed", actual.seed).putBoolean("corners", actual.corners);
        if (!edit.commit()) throw new IOException("已读取实际设置，但本地记录保存失败");
        RuntimeStateStore.recordTheme(a, actual);
    }

    private void acceptTheme(JSONObject ack, boolean syncSelection) {
        sync();
        try {
            JSONObject current = ack.getJSONObject("state");
            connected = true;
            ThemeConfig actual = configFromState(current);
            if (syncSelection) draft = actual;
            JSONObject palette = ack.optJSONObject("preview");
            autoSeed = actual.mode.equals("auto") ? (palette == null ? actual.seed : ThemeRuntime.color(palette.getString("seed"))) : null;
            canRollback = ack.optBoolean("canRollback");
            rollbackConfig = ack.optJSONObject("rollbackConfig");
        } catch (Exception e) {
            connected = false;
            themeMessage = WatchActivity.message(e);
        }
    }

    private void recoverTheme(Exception problem) {
        RuntimeStateStore.failMonet(a, problem);
        a.runOnUiThread(() -> {
            if (a.destroyed()) return;
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
        a.runOnUiThread(() -> {
            if (a.destroyed()) return;
            themeBusy = writes.isRunning();
            connected = false;
            if (actual != null) acceptTheme(actual, true);
            themeMessage = detail;
            a.render();
        });
    }

    private boolean editable() {
        return !a.destroyed() && connected && reachable() && !opBusy && !saveBusy && !refreshing && (!themeBusy || writes.isRunning());
    }

    /** 选了就提交；同时只有一个写入在途，后来的选择替换排队中的那一个。back=true 时选完回上一页。 */
    private void save(ThemeConfig next, boolean back) {
        if (!editable()) {
            Toast.makeText(a, "请先启用并读取当前设置", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean same = draft.mode.equals(next.mode) && draft.seed == next.seed && draft.corners == next.corners;
        if (!same) {
            if (!writes.submit(next)) {
                Toast.makeText(a, "正在确认上次操作，请稍候", Toast.LENGTH_SHORT).show();
                return;
            }
            draft = next;
            themeBusy = true;
            themeMessage = "";
        }
        if (back && !a.stack.isEmpty()) a.page = a.stack.pop();
        a.render();
    }

    // ------------------------------------------------------------------
    // 安装、启停、卸载
    // ------------------------------------------------------------------

    private void moduleAction(String action) {
        if (a.destroyed() || a.working()) return;
        final boolean inline = action.equals("enable") || action.equals("disable");
        opBusy = true;
        operation = action;
        themeMessage = "";
        connected = false;
        if (inline) a.render();
        else a.replace("progress");
        IO.execute(() -> {
            String problem = "";
            try {
                if (action.equals("install")) ModuleManager.install(a);
                else if (action.equals("enable")) ModuleManager.setEnabled(a, true);
                else if (action.equals("disable")) ModuleManager.setEnabled(a, false);
                else if (action.equals("uninstall")) ModuleManager.uninstall(a);
            } catch (Exception e) {
                problem = WatchActivity.message(e);
                try {
                    ModuleManager.read(a);
                } catch (Exception readError) {
                    problem += "；状态读取失败：" + WatchActivity.message(readError);
                }
            }
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                opBusy = false;
                sync();
                if (inline) {
                    themeMessage = message;
                    a.render();
                    if (a.page.equals("theme") && reachable()) themeAction("preview");
                } else {
                    showResult(action, message.isEmpty(), message);
                }
            });
        });
    }

    private void showResult(String action, boolean ok, String problem) {
        resultAction = action;
        resultOk = ok;
        resultError = problem;
        prefs.edit().putString("resultAction", action).putBoolean("resultOk", ok).putString("resultError", problem).apply();
        if (a.page.equals("progress")) a.replace("result");
        else a.render();
    }

    private String destination() {
        if (resultAction.equals("uninstall") && !installed()) return "home";
        return resultAction.equals("install") ? "detail" : "theme";
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
            ui.note("已安装 · " + statusText(), state.activeKnown && state.active ? "success" : "");
            ui.button("打开配置", true, () -> a.go("theme"));
        } else if (state != null && state.kind.equals("available")) {
            ui.note("适配当前手表 · 可安装", "success");
            ui.button("安装", true, () -> a.go("install"));
        } else if (state != null && state.kind.equals("setup")) {
            ui.note("完成一次首次部署，即可在手表离线安装。");
            ui.row("首次设置", "", "book", () -> a.go("guide"));
        } else if (state != null) {
            ui.note("已有组件不兼容，请检查运行环境。", "warning");
            ui.row("运行环境", "", "shield", () -> a.go("check"));
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
        if (state != null && !state.installed()) {
            ui.note(statusText(), incompatible() ? "warning" : "");
            ui.row("查看功能介绍", "", "compass", () -> a.go("detail"));
            return;
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(74));
        lp.bottomMargin = a.dp(WorkshopUi.GAP);
        a.body.addView(new ThemePreview(a), lp);
        ui.row("配色方式", draft.modeLabel(), "palette", () -> a.go("modes"));
        if (draft.mode.equals("fixed")) ui.row("固定颜色", colorName(draft.seed), "dot", () -> a.go("colors"));
        View corners = ui.toggle("连续圆角", "调整控制中心按钮轮廓", draft.corners,
                () -> save(new ThemeConfig(draft.mode, draft.seed, !draft.corners), false));
        dim(corners);
        statusNotes();
        if (state != null && state.installed() && !state.activeKnown) {
            ui.button("重试启用", false, !a.working(), () -> moduleAction("enable"));
        }
        ui.row("更多选项", "刷新配色、恢复上一次、卸载", "sliders", () -> a.go("theme-more"));
    }

    /** 只在有事可说时出声：操作中、已停用、读取失败。正常可用时不显示提示。 */
    private void statusNotes() {
        WorkshopUi ui = a.ui;
        if (opBusy) ui.note(operation.equals("disable") ? "正在停用…" : "正在启用…");
        else if (refreshing || themeBusy) ui.note(writes.isRunning() ? "正在应用…" : "正在读取当前设置…");
        else if (state != null && state.activeKnown && !state.active) ui.note("已停用，打开右上开关后可调整设置。");
        else if (!connected && themeMessage.isEmpty() && error.isEmpty()) ui.note("当前设置尚未确认。");
        if (!error.isEmpty()) ui.note(error, "warning");
        if (!themeMessage.isEmpty()) ui.note(themeMessage, connected ? "" : "warning");
        if (!refreshing && !themeBusy && !opBusy && (!connected || !themeMessage.isEmpty()) && !(state != null && state.activeKnown && !state.active)) {
            ui.button("重新读取状态", false, !a.working(), () -> refresh(true));
        }
    }

    private void themeMore() {
        WorkshopUi ui = a.ui;
        statusNotes();
        ui.button("刷新表盘配色", false, !a.working() && reachable(), () -> themeAction("preview"));
        ui.button("恢复上一次设置", false, canRollback && connected && reachable() && !a.working(), () -> a.go("theme-restore"));
        ui.value("当前版本", "莫奈组件 v6");
        if (state != null && state.removable()) ui.button("卸载", false, !a.working(), () -> a.go("uninstall"));
        else if (installed()) ui.note("共享组件，无法单独卸载。可用右上开关停用。");
    }

    private static String modeName(String mode) {
        return "auto".equals(mode) ? "自动取色" : "fixed".equals(mode) ? "固定颜色" : "original".equals(mode) ? "系统原色" : "未知";
    }

    private void themeRestore() {
        WorkshopUi ui = a.ui;
        ui.text("恢复上一次设置？");
        if (rollbackConfig != null) {
            ui.value("配色方式", modeName(rollbackConfig.optString("mode")));
            if ("fixed".equals(rollbackConfig.optString("mode"))) ui.value("颜色", rollbackConfig.optString("seedArgb"));
            ui.value("圆角", rollbackConfig.optBoolean("corners") ? "连续圆角" : "原生圆角");
        }
        ui.note("恢复最近一次成功应用之前的设置。");
        ui.button("确认恢复", true, connected && reachable() && !a.working() && canRollback && rollbackConfig != null, () -> {
            a.onBackPressed();
            themeAction("rollback");
        });
        ui.button("取消", false, a::onBackPressed);
    }

    private void modes() {
        dim(a.ui.choice("自动取色", "跟随表盘配色", draft.mode.equals("auto"),
                () -> save(new ThemeConfig("auto", draft.seed, draft.corners), true)));
        dim(a.ui.choice("固定颜色", "使用选择的主题色", draft.mode.equals("fixed"),
                () -> save(new ThemeConfig("fixed", draft.seed, draft.corners), true)));
        dim(a.ui.choice("系统原色", "恢复默认配色", draft.mode.equals("original"),
                () -> save(new ThemeConfig("original", draft.seed, draft.corners), true)));
    }

    private void dim(View view) {
        view.setEnabled(editable());
        view.setAlpha(editable() ? 1f : .4f);
    }

    private static String colorName(int color) {
        for (int i = 0; i < PRESETS.length; i++) if (color == PRESETS[i]) return COLOR_NAMES[i];
        return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
    }

    /** 两行三列的预设色；选中后自动应用并回到配置页。 */
    private void colors() {
        for (int y = 0; y < 2; y++) {
            LinearLayout line = new LinearLayout(a);
            for (int x = 0; x < 3; x++) {
                final int i = y * 3 + x;
                boolean chosen = draft.seed == PRESETS[i];
                LinearLayout cell = new LinearLayout(a);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setMinimumHeight(a.dp(56));
                cell.setPadding(a.dp(4), a.dp(7), a.dp(4), a.dp(7));
                GradientDrawable bg = a.shape(WorkshopUi.SURFACE, 12);
                if (chosen) bg.setStroke(a.dp(1), WorkshopUi.ACCENT);
                cell.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff), bg, null));
                cell.addView(new ColorDot(a, PRESETS[i], chosen), new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));
                TextView label = new TextView(a);
                label.setText(COLOR_NAMES[i]);
                label.setTextColor(WorkshopUi.FG);
                label.setTextSize(11);
                label.setGravity(Gravity.CENTER);
                cell.addView(label);
                cell.setContentDescription(COLOR_NAMES[i] + (chosen ? "，已选择" : ""));
                dim(cell);
                cell.setOnClickListener(v -> save(new ThemeConfig(draft.mode, PRESETS[i], draft.corners), true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (x < 2) lp.rightMargin = a.dp(6);
                line.addView(cell, lp);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = a.dp(WorkshopUi.GAP);
            a.body.addView(line, rowParams);
        }
        a.ui.note("选择后自动应用。仅固定配色时生效。");
    }

    private void installPlan() {
        WorkshopUi ui = a.ui;
        ui.value("本次安装", "莫奈取色 · 组件 v6");
        ui.value("同时安装", "莫奈运行组件");
        ui.value("安装来源", "管理器内置 · 无需下载");
        ui.note("基础环境已就绪。安装后启用控制中心主题。");
        ui.button("安装并启用", true, !a.working() && state != null && state.kind.equals("available"), () -> moduleAction("install"));
        ui.button("暂不安装", false, a::onBackPressed);
    }

    private void uninstallConfirm() {
        WorkshopUi ui = a.ui;
        ui.text("卸载莫奈取色？控制中心将恢复系统外观。");
        ui.note("移除莫奈运行组件，保留应用与基础环境，可再次安装。");
        boolean removable = state != null && state.removable();
        if (removable) ui.note("操作会重新加载表盘，可能短暂黑屏。", "warning");
        ui.button("确认卸载", true, !a.working() && removable, () -> moduleAction("uninstall"));
        ui.button("取消", false, a::onBackPressed);
    }

    private void progress() {
        a.ui.hero("refresh", "正在应用更改", operation.equals("install") ? "安装内置组件并确认运行状态" : "正在等待手表确认", "");
        a.ui.note("完成后显示实际结果，请稍候。");
    }

    private void result() {
        WorkshopUi ui = a.ui;
        String heading = resultAction.equals("rollback") ? "已经恢复" : resultAction.equals("install") ? "安装完成"
                : resultAction.equals("disable") ? "已停用" : resultAction.equals("uninstall") ? "已卸载" : "已生效";
        ui.hero(resultOk ? "check" : "warning", resultOk ? heading : "结果待确认",
                resultOk ? resultAction.equals("uninstall") ? "管理器保留，可在手表上重新安装" : "更改已经应用到手表" : "请重新读取实际状态后再操作。",
                resultOk ? "success" : "warning");
        if (!resultOk) ui.note(resultError);
        String target = destination();
        ui.button(resultOk ? target.equals("detail") ? "返回功能介绍" : target.equals("theme") ? "继续配置" : "返回首页" : "重新读取状态",
                true, !a.working(), this::finishResult);
    }

    // ------------------------------------------------------------------
    // 导出（技术详情页的入口）
    // ------------------------------------------------------------------

    boolean exportable() { return !a.working() && reachable(); }

    void exportDraft() {
        if (!exportable()) return;
        saveBusy = true;
        a.render();
        IO.execute(() -> {
            try {
                ThemeConfig actual = configFromState(ThemeRuntime.exchange(a, "read", null).getJSONObject("state"));
                JSONObject j = new JSONObject();
                j.put("schemaVersion", 1).put("moduleId", "control-center-theme").put("mode", actual.mode).put("seedArgb", actual.seedHex())
                        .put("corners", actual.corners).put("applied", false).put("deviceStateConfirmed", true)
                        .put("capturedAt", System.currentTimeMillis()).put("origin", a.getPackageName() + "/" + SystemPages.appVersion(a));
                File base = a.getExternalFilesDir(null);
                if (base == null) base = a.getFilesDir();
                File dir = new File(base, "exports");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建导出目录");
                File file = new File(dir, "theme-config.json");
                AtomicFile af = new AtomicFile(file);
                FileOutputStream out = null;
                try {
                    out = af.startWrite();
                    out.write(j.toString(2).getBytes("UTF-8"));
                    af.finishWrite(out);
                } catch (Exception e) {
                    if (out != null) af.failWrite(out);
                    throw e;
                }
                if (!prefs.edit().putString("lastExport", file.getAbsolutePath()).commit()) throw new IOException("导出位置保存失败");
                a.runOnUiThread(() -> {
                    saveBusy = false;
                    if (a.destroyed()) return;
                    exportPath = file.getAbsolutePath();
                    if (a.page.equals("technical")) a.go("export");
                    else a.render();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    saveBusy = false;
                    if (a.destroyed()) return;
                    Toast.makeText(a, "导出失败：" + WatchActivity.message(e), Toast.LENGTH_LONG).show();
                    a.render();
                });
            }
        });
    }

    private void exportPage() {
        WorkshopUi ui = a.ui;
        ui.text("配置已导出");
        ui.note("这是当前已确认设置的快照，导出文件不执行更改。");
        String path = exportPath.isEmpty() ? prefs.getString("lastExport", "") : exportPath;
        ui.value("保存位置", path);
        ui.button("复制文件路径", false, () -> {
            ((ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("配置路径", path));
            Toast.makeText(a, "路径已复制", Toast.LENGTH_SHORT).show();
        });
    }

    // ------------------------------------------------------------------
    // 预览控件
    // ------------------------------------------------------------------

    private static final class ColorDot extends View {
        private final int color;
        private final boolean chosen;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ColorDot(Context context, int color, boolean chosen) {
            super(context);
            this.color = color;
            this.chosen = chosen;
        }

        @Override protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, paint);
            if (chosen) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * d);
                paint.setColor(WorkshopUi.ON_ACCENT);
                Path path = new Path();
                path.moveTo(5 * d, 10 * d);
                path.lineTo(8.5f * d, 13 * d);
                path.lineTo(15 * d, 6.5f * d);
                canvas.drawPath(path, paint);
            }
        }
    }

    /** 配置页顶部的控制中心配色示意：四个按钮 + 强调色值；系统原色或取色中时显示说明。 */
    private final class ThemePreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ThemePreview(Context context) {
            super(context);
            setMinimumHeight(a.dp(74));
            setContentDescription("莫奈配色预览，设置自动应用");
        }

        private float sp(float value) { return value * getResources().getDisplayMetrics().scaledDensity; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(WorkshopUi.SURFACE);
            canvas.drawRoundRect(0, 0, getWidth(), a.dp(74), a.dp(15), a.dp(15), paint);
            boolean original = draft.mode.equals("original"), waiting = draft.mode.equals("auto") && autoSeed == null;
            if (original || waiting) {
                paint.setColor(WorkshopUi.FG);
                paint.setTextSize(sp(13));
                canvas.drawText(original ? "系统原色" : "正在读取表盘配色", a.dp(12), a.dp(31), paint);
                paint.setColor(WorkshopUi.MUTED);
                paint.setTextSize(sp(10));
                canvas.drawText(original ? "使用系统原生颜色" : "取色成功后显示预览", a.dp(12), a.dp(50), paint);
                return;
            }
            int seed = draft.mode.equals("fixed") ? draft.seed : autoSeed;
            ThemePalettePreview p = ThemePalettePreview.fromSeed(seed);
            float rad = a.dp(draft.corners ? 9 : 3);
            for (int i = 0; i < 4; i++) {
                paint.setColor(i == 1 || i == 2 ? p.off : p.active);
                float x = a.dp(10 + (i % 2) * 26), y = a.dp(10 + (i / 2) * 26);
                canvas.drawRoundRect(x, y, x + a.dp(23), y + a.dp(23), rad, rad, paint);
                paint.setColor(i == 1 || i == 2 ? p.onOff : p.onActive);
                canvas.drawCircle(x + a.dp(11.5f), y + a.dp(11.5f), a.dp(3), paint);
            }
            paint.setColor(WorkshopUi.FG);
            paint.setTextSize(sp(12));
            canvas.drawText(draft.mode.equals("fixed") ? "固定配色" : "表盘配色", a.dp(68), a.dp(31), paint);
            paint.setColor(p.secondary);
            paint.setTextSize(sp(10));
            canvas.drawText(String.format("强调 #%06X", p.active & 0xffffff), a.dp(68), a.dp(49), paint);
        }
    }
}
