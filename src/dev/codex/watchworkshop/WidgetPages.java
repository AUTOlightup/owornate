package dev.codex.watchworkshop;

import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 负一屏小组件：接入状态、介绍页、接入方案和管理页。
 *
 * <p>状态只投影 {@link RuntimeStateStore}；check/install/repair 由 {@link WidgetHostManager}
 * 在后台写入共享缓存。读取错误、操作错误和成功提示分开保存，操作失败不会显示成读取失败。
 */
final class WidgetPages {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final MainActivity a;
    private String host = "";
    private String keep = "";
    private String readError = "";
    private String operationError = "";
    private String notice = "";
    private boolean refreshing, installing, repairing, breenoBusy;
    private StatusObservation<WidgetHostManager.Status> observation;

    WidgetPages(MainActivity activity) {
        a = activity;
        sync();
    }

    boolean on() { return host.equals("installed") || host.equals("ready"); }
    boolean known() { return !host.isEmpty(); }
    /** Legacy widgetHost migrations have confirmedAt=0 and must not count as a confirmed availability. */
    boolean available() {
        return host.equals("available") && confirmed()
                && (observation.error == null || observation.error.isEmpty()) && !failed();
    }
    boolean failed() { return !readError.isEmpty(); }
    String error() { return readError; }
    boolean refreshing() { return refreshing; }
    /** 安装、后台修复和小布写入共用一条设备连接，避免并行写共享 Hook。 */
    boolean busy() { return installing || repairing || breenoBusy; }
    boolean blocksBack(String page) { return page.equals("wplan") && installing; }

    private boolean confirmed() { return observation != null && observation.confirmedAt > 0; }

    /** 读取共享缓存并更新本页投影；MainActivity 的监听器也调用这里。 */
    void sync() {
        StatusObservation<WidgetHostManager.Status> seen = RuntimeStateStore.widgets(a);
        observation = seen;
        if (seen == null) return;
        if (seen.value != null) {
            host = seen.value.host;
            keep = seen.value.keep;
        } else {
            host = "";
            keep = "";
        }
        readError = seen.error == null ? "" : seen.error;
    }

    String statusText() {
        if (failed()) return on() ? "上次已接入 · 本次读取失败" : readError;
        if (refreshing && host.isEmpty()) return "正在读取…";
        if (host.isEmpty()) return "状态待确认";
        if (!confirmed()) {
            return "上次：" + (on() ? (host.equals("ready") ? "已接入" : "已接入（加载未确认）") : "未接入");
        }
        if (on()) return host.equals("ready") ? "已接入" : "已接入（表盘加载未确认）";
        if (host.equals("available")) return "未接入";
        return "状态待确认";
    }

    String discoverLine() {
        if (failed()) return statusText();
        if (on()) return "已接入 · 信息、倒计时与课表";
        if (available()) return "可接入 · 信息、倒计时与课表";
        return statusText();
    }

    void homeRow() {
        int cards = WidgetCards.totalCount(a);
        a.ui.row("负一屏小组件", failed() ? statusText() : cards == 0 ? "已接入 · 尚未添加卡片" : "负一屏上 " + cards + " 张卡片", "layers", () -> a.go("widgets"));
    }

    String title(String page) {
        switch (page) {
            case "wdetail": case "widgets": return "负一屏小组件";
            case "wplan": return on() ? "重新接入" : "接入方案";
            default: return null;
        }
    }

    boolean render(String page) {
        switch (page) {
            case "wdetail": detail(); return true;
            case "wplan": plan(); return true;
            case "widgets": manage(); return true;
            default: return false;
        }
    }

    private static String errorOf(Exception e) {
        return e instanceof IOException && e.getMessage() != null ? e.getMessage() : "读取失败，请检查管理器首次授权";
    }

    private static String operationErrorOf(Exception e) {
        return e instanceof IOException && e.getMessage() != null ? e.getMessage() : "操作未完成，请重试";
    }

    /** 开机时刻（毫秒）：判断“白名单刚加入、还没重启”时用。 */
    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private void beginOperation() {
        sync();
        operationError = "";
        notice = "";
    }

    private void setOperationError(String message) {
        operationError = message == null ? "" : message;
    }

    /** 只读状态：先同步共享缓存，再按 observation.fresh 决定是否探测。 */
    void refresh(boolean force) {
        if (a.destroyed()) return;
        sync();
        if (a.working() || refreshing) return;
        StatusObservation<WidgetHostManager.Status> seen = observation;
        if (!force && seen != null && seen.fresh(System.currentTimeMillis(), MainActivity.FRESH_MS)) return;
        refreshing = true;
        a.render();
        IO.execute(() -> {
            String problem = "";
            try {
                WidgetHostManager.check(a.getApplicationContext());
            } catch (Exception e) {
                problem = errorOf(e);
            }
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                refreshing = false;
                sync();
                if (readError.isEmpty() && !message.isEmpty()) readError = message;
                a.render();
            });
        });
    }

    private static String installNotice(String host, String keep) {
        String lead = "ready".equals(host) ? "已接入，表盘已重新加载。" : "接入文件已安装，表盘加载未确认。";
        if ("added".equals(keep)) return lead + "已加入后台更新白名单，重启一次手表后生效。到负一屏的编辑页添加卡片。";
        if ("missing".equals(keep) || "unavailable".equals(keep)) return lead + "后台保活未完成，请在管理页点“修复后台更新”。";
        return lead + "到负一屏的编辑页添加卡片。";
    }

    /** 接入或重新接入：会重新加载表盘一次，期间不能返回，也不能做其他写操作。 */
    private void install() {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        installing = true;
        a.render();
        IO.execute(() -> {
            boolean success = false;
            String problem = "";
            try {
                WidgetHostManager.install(a.getApplicationContext());
                success = true;
            } catch (Exception e) {
                problem = operationErrorOf(e);
            }
            final boolean ok = success;
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                installing = false;
                sync();
                if (ok) {
                    notice = installNotice(host, keep);
                } else {
                    setOperationError(message);
                }
                a.leave("wplan");
            });
        });
    }

    /** 只修复开机后台白名单，不重载表盘。 */
    private void repairBackground() {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        repairing = true;
        a.render();
        IO.execute(() -> {
            boolean success = false;
            String problem = "";
            try {
                WidgetHostManager.repairBackground(a.getApplicationContext());
                success = true;
            } catch (Exception e) {
                problem = operationErrorOf(e);
            }
            final boolean ok = success;
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                repairing = false;
                sync();
                if (ok) {
                    notice = keep.equals("added")
                            ? "后台保活已加入白名单，重启一次手表后生效。"
                            : "后台保活已开启。";
                } else {
                    setOperationError(message);
                }
                a.render();
            });
        });
    }

    /** 小布助手当前是否隐藏：读系统设置，无需 root，随时可读。 */
    private boolean breenoHidden() {
        try { return Settings.System.getInt(a.getContentResolver(), "codex_qc_hide_breeno", 0) == 1; }
        catch (Exception e) { return false; }
    }

    /** 切换隐藏小布助手：写系统设置要 root，走后台；hook 下次打开负一屏时读取生效，不重载表盘。 */
    private void setBreeno(boolean hide) {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        breenoBusy = true;
        a.render();
        IO.execute(() -> {
            String problem = "";
            try {
                WidgetHostManager.breeno(a.getApplicationContext(), hide);
            } catch (Exception e) {
                problem = operationErrorOf(e);
            }
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                breenoBusy = false;
                sync();
                if (!message.isEmpty()) setOperationError(message);
                a.render();
            });
        });
    }

    private void detail() {
        WorkshopUi ui = a.ui;
        ui.hero("layers", "负一屏小组件", "信息、倒计时和课表，直接放在负一屏", "");
        if (!notice.isEmpty()) ui.note(notice, "success");
        if (!operationError.isEmpty()) ui.note(operationError, "warning");
        if (failed()) {
            ui.note((on() ? "上次已接入 · 本次读取失败：" : "读取状态失败：") + readError, "warning");
            ui.button("重新读取状态", !on(), !refreshing, () -> refresh(true));
        }
        if (on()) {
            if (!failed()) ui.note(statusText(), confirmed() ? "success" : "warning");
            ui.button("打开小组件", true, () -> a.go("widgets"));
        } else if (available()) {
            ui.note("适配当前手表 · 可接入", "success");
            ui.button("接入负一屏", true, !a.working(), () -> a.go("wplan"));
        } else if (!failed() && operationError.isEmpty()) {
            ui.note("正在读取接入状态…");
        }
        ui.title("包含的卡片");
        ui.value("OWOrnate 信息", "状态、配色和版本");
        ui.value("倒计时", "按天或按时刻，可每年重复；也可显示纪念日里的日子");
        ui.value("课表", "导入拾光课表，突出当前和下一节课");
        ui.value("适配设备", "OPPO Watch 2 46mm · A.92");
        ui.value("安装来源", "应用内置 · 无需联网");
    }

    private void plan() {
        WorkshopUi ui = a.ui;
        boolean again = on();
        ui.hero("layers", again ? "重新接入" : "接入负一屏", "让原生负一屏能添加 OWOrnate 的卡片", "");
        ui.value("本次操作", again ? "不替换文件，重新加载表盘；白名单缺失时一并修复"
                : "换成放行 OWOrnate 卡片的组件文件，原文件已备份，然后重新加载表盘；白名单缺失时一并修复");
        ui.value("不会改动", "应用数据、莫奈取色、QQ 和输入法词库");
        ui.note("表盘会重新加载一次，屏幕可能短暂黑屏，约 10 秒。", "warning");
        if (installing) ui.note("正在接入，请稍候，不要退出…");
        ui.button(again ? "重新接入" : "接入并重新加载表盘", true, !a.working(), this::install);
        ui.button("暂不接入", false, !busy(), a::onBackPressed);
    }

    private void manage() {
        WorkshopUi ui = a.ui;
        if (!notice.isEmpty()) ui.note(notice, "success");
        if (!operationError.isEmpty()) ui.note(operationError, "warning");
        if (failed()) {
            ui.note((on() ? "上次已接入 · 本次读取失败：" : "读取状态失败：") + readError, "warning");
            ui.button("重新读取状态", false, !refreshing, () -> refresh(true));
        } else if (host.equals("available")) {
            if (available()) {
                ui.note("尚未接入负一屏", "warning");
                ui.button("去接入", true, !a.working(), () -> a.go("wplan"));
            } else {
                ui.note("正在确认接入状态…");
            }
        } else if (host.equals("installed")) {
            ui.note("已接入 · 表盘加载状态未确认。负一屏里找不到卡片时，可在页底重新接入。");
        } else if (host.isEmpty()) {
            ui.note("正在读取接入状态…");
        }
        if (on() && (keep.equals("missing") || keep.equals("unavailable"))) {
            ui.note(keep.equals("missing")
                    ? "后台更新白名单未加入，息屏后卡片可能停止更新。"
                    : "无法确认后台更新白名单，请检查系统权限后重试。", "warning");
            ui.button("修复后台更新", true, !a.working(), this::repairBackground);
            if (repairing) ui.note("正在修复后台更新…");
        }
        long keepBoot = a.prefs.getLong("widgetKeepBoot", 0);
        if (keepBoot != 0 && Math.abs(bootTime() - keepBoot) < 60000) {
            ui.note("已加入系统后台白名单。重启一次手表后，卡片在息屏后也会自动更新。", "warning");
        }
        ui.row("OWOrnate 信息", WidgetCards.infoSummary(a), "info",
                () -> a.startActivity(new Intent(a, OWOrnateWidgetSettingsActivity.class)));
        ui.row("倒计时", WidgetCards.countdownSummary(a), "clock",
                () -> a.startActivity(new Intent(a, CountdownSettingsActivity.class)));
        ui.row("课表", WidgetCards.timetableSummary(a), "book",
                () -> a.startActivity(new Intent(a, TimetableSettingsActivity.class)));
        ui.title("添加或移除卡片");
        ui.note("在负一屏的编辑页添加或移除卡片，点卡片本身可以再次设置。每类卡片只有一份设置，同类卡片共用。");
        if (on()) {
            ui.title("负一屏外观");
            boolean hidden = breenoHidden();
            ui.toggle("隐藏小布助手", hidden ? "负一屏不再显示小布那一行" : "负一屏顶部保留小布那一行",
                    hidden, () -> setBreeno(!breenoHidden()));
            if (breenoBusy) ui.note("正在应用，下次打开负一屏生效…");
            else ui.note("隐藏后，下面的卡片会顶上来；需要小布时随时可以打开。");
        }
        if (on()) ui.row("重新接入", "负一屏找不到卡片时使用", "refresh", () -> a.go("wplan"));
    }
}

