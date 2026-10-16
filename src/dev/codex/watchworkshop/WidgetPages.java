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
 * <p>状态读取走自己的线程，不和莫奈排队；一次读取只开一个连接（状态和系统白名单一起确认），
 * 通常不到半秒。读取失败时保留上次已知的接入状态。
 */
final class WidgetPages {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final MainActivity a;
    private String host;
    private String error = "", notice = "";
    private boolean refreshing, busy, breenoBusy;
    private long readAt;

    WidgetPages(MainActivity activity) {
        a = activity;
        host = activity.prefs.getString("widgetHost", "");
    }

    boolean on() { return host.equals("installed") || host.equals("ready"); }
    boolean known() { return !host.isEmpty(); }
    boolean refreshing() { return refreshing; }
    boolean busy() { return busy; }
    /** 接入进行中不能离开接入方案页：表盘正在重新加载。 */
    boolean blocksBack(String page) { return page.equals("wplan") && busy; }

    String statusText() {
        if (on()) return host.equals("ready") ? "已接入" : "已接入（表盘加载未确认）";
        if (host.equals("available")) return "未接入";
        if (refreshing) return "正在读取…";
        return error.isEmpty() ? "状态待确认" : error;
    }

    void homeRow() {
        int cards = WidgetCards.totalCount(a);
        a.ui.row("负一屏小组件", cards == 0 ? "已接入 · 尚未添加卡片" : "负一屏上 " + cards + " 张卡片", "layers", () -> a.go("widgets"));
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

    /** 开机时刻（毫秒）：判断“白名单刚加入、还没重启”时用。 */
    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** 只读状态：后台执行，不锁界面；失败时保留上次已知的接入状态。 */
    void refresh(boolean force) {
        if (a.destroyed() || refreshing || busy) return;
        if (!force && System.currentTimeMillis() - readAt < MainActivity.FRESH_MS) return;
        refreshing = true;
        error = "";
        IO.execute(() -> {
            WidgetHostManager.Status status = null;
            String problem = "";
            try {
                status = WidgetHostManager.check(a.getApplicationContext());
            } catch (Exception e) {
                problem = errorOf(e);
            }
            // 刚加入系统进程白名单：要重启一次手表才生效，记下加入时的开机时刻用于提示。
            if (status != null && status.keep.equals("added")) a.prefs.edit().putLong("widgetKeepBoot", bootTime()).apply();
            final WidgetHostManager.Status value = status;
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                refreshing = false;
                error = message;
                if (value != null) {
                    host = value.host;
                    readAt = System.currentTimeMillis();
                    a.prefs.edit().putString("widgetHost", host).apply();
                }
                a.render();
            });
        });
    }

    /** 接入或重新接入：会重新加载表盘一次，期间不能返回，也不能做其他写操作。 */
    private void install() {
        if (a.destroyed() || a.working()) return;
        busy = true;
        error = "";
        notice = "";
        a.render();
        IO.execute(() -> {
            String result = "", problem = "";
            try {
                result = WidgetHostManager.install(a.getApplicationContext());
            } catch (Exception e) {
                problem = errorOf(e);
            }
            final String value = result, message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                busy = false;
                if (message.isEmpty()) {
                    host = value;
                    readAt = 0;
                    a.prefs.edit().putString("widgetHost", value).apply();
                    notice = value.equals("ready") ? "已接入，表盘已重新加载。到负一屏的编辑页添加卡片。"
                            : "接入文件已安装，表盘加载未确认。可在负一屏的编辑页查看卡片。";
                } else {
                    error = message;
                }
                a.leave("wplan");
                refresh(false);
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
        if (a.destroyed() || a.working() || breenoBusy) return;
        breenoBusy = true;
        error = "";
        a.render();
        IO.execute(() -> {
            String problem = "";
            try {
                WidgetHostManager.breeno(a.getApplicationContext(), hide);
            } catch (Exception e) {
                problem = errorOf(e);
            }
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                breenoBusy = false;
                error = message;
                a.render();
            });
        });
    }

    private void detail() {        WorkshopUi ui = a.ui;
        ui.hero("layers", "负一屏小组件", "信息、倒计时和课表，直接放在负一屏", "");
        if (!notice.isEmpty()) ui.note(notice, "success");
        if (!error.isEmpty()) {
            ui.note(error, "warning");
            ui.button("重新读取状态", !on(), !refreshing, () -> refresh(true));
        }
        if (on()) {
            ui.note(statusText(), "success");
            ui.button("打开小组件", true, () -> a.go("widgets"));
        } else if (host.equals("available")) {
            ui.note("适配当前手表 · 可接入", "success");
            ui.button("接入负一屏", true, !a.working(), () -> a.go("wplan"));
        } else if (error.isEmpty()) {
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
        ui.value("本次操作", again ? "不替换文件，只重新加载表盘" : "换成放行 OWOrnate 卡片的组件文件，原文件已备份，然后重新加载表盘");
        ui.value("不会改动", "应用数据、莫奈取色、QQ 和输入法词库");
        ui.note("表盘会重新加载一次，屏幕可能短暂黑屏，约 10 秒。", "warning");
        if (busy) ui.note("正在接入，请稍候，不要退出…");
        ui.button(again ? "重新接入" : "接入并重新加载表盘", true, !a.working(), this::install);
        ui.button("暂不接入", false, !busy, a::onBackPressed);
    }

    private void manage() {
        WorkshopUi ui = a.ui;
        if (!notice.isEmpty()) ui.note(notice, "success");
        if (!error.isEmpty()) {
            ui.note(error, "warning");
            ui.button("重新读取状态", false, !refreshing, () -> refresh(true));
        } else if (host.equals("available")) {
            ui.note("尚未接入负一屏", "warning");
            ui.button("去接入", true, !a.working(), () -> a.go("wplan"));
        } else if (host.equals("installed")) {
            ui.note("已接入 · 表盘加载状态未确认。负一屏里找不到卡片时，可在页底重新接入。");
        } else if (host.isEmpty()) {
            ui.note("正在读取接入状态…");
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
