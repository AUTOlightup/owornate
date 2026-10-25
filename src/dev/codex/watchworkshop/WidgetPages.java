package dev.codex.watchworkshop;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import dev.codex.watchworkshop.ModuleManagement.Action;
import java.io.IOException;
import java.util.EnumSet;

/**
 * 负一屏小组件：接入状态、介绍页、接入方案和管理页。
 *
 * <p>状态只投影 {@link RuntimeStateStore}；check/install/repair 由 {@link WidgetHostManager}
 * 在后台写入共享缓存。读取错误、操作错误和成功提示分开保存，操作失败不会显示成读取失败。
 * 设备操作都排在 {@link DeviceQueues#WIDGETS} 上。
 */
final class WidgetPages implements ModuleStatusView, ModuleManagement {
    private final MainActivity a;
    private String host = "";
    private String keep = "";
    private String owner = "";
    private String readError = "";
    private String operationError = "";
    private String notice = "";
    private boolean refreshing;
    private boolean installing;
    private boolean repairing;
    private boolean breenoBusy;
    private boolean openingEditor;
    private boolean changing;
    private final ModuleManagementUi managementUi = new ModuleManagementUi();
    private StatusObservation<WidgetHostManager.Status> observation;

    WidgetPages(MainActivity activity) {
        a = activity;
        sync();
    }

    boolean on() { return host.equals("installed") || host.equals("ready"); }
    /** installed 指功能文件已存在；enabled 才代表功能开关已打开。 */
    @Override public boolean installed() {
        return on() || host.equals("disabled") || host.equals("update");
    }
    @Override public boolean known() { return !host.isEmpty(); }
    /** 旧版 widgetHost 迁移来的值 confirmedAt=0，不能算作已确认可接入。 */
    @Override public boolean available() {
        return host.equals("available") && confirmed() && observation.error.isEmpty() && !failed();
    }
    @Override public boolean failed() { return !readError.isEmpty(); }
    boolean needsSetup() { return host.equals("setup") && !failed(); }
    boolean incompatible() { return host.equals("conflict") && !failed(); }
    @Override public String error() { return readError; }
    @Override public boolean refreshing() { return refreshing; }
    /** 安装、后台修复和小布写入共用一条设备连接，避免并行写共享 Hook。 */
    boolean busy() { return installing || repairing || breenoBusy || changing || openingEditor; }
    boolean removable() { return installed() && owner.equals("own"); }
    boolean blocksBack(String page) { return page.equals(PageIds.WIDGET_PLAN) && installing; }

    boolean confirmed() { return observation != null && observation.confirmedAt > 0; }

    /** 读取共享缓存并更新本页投影；MainActivity 的监听器也调用这里。 */
    void sync() {
        observation = RuntimeStateStore.widgets(a);
        host = observation.value == null ? "" : observation.value.host;
        keep = observation.value == null ? "" : observation.value.keep;
        owner = observation.value == null ? "" : observation.value.owner;
        readError = observation.error;
    }

    @Override public String statusText() {
        if (failed()) return on() ? "上次已接入 · 本次读取失败" : readError;
        if (refreshing && host.isEmpty()) return "正在读取…";
        if (host.isEmpty()) return "状态待确认";
        if (!confirmed()) return "上次：" + shortState();
        if (on()) return shortState();
        if (host.equals("disabled")) return "已安装 · 已停用";
        if (host.equals("update")) return "已安装 · 可更新";
        if (host.equals("available")) return "未接入";
        if (host.equals("setup")) return "首次使用需要部署管理器";
        if (host.equals("conflict")) return "已有运行组件不兼容";
        return "状态待确认";
    }

    private String shortState() {
        if (host.equals("ready")) return owner.equals("shared") ? "已接入 · 共享组件" : "已接入";
        if (host.equals("installed")) return "已接入（表盘加载未确认）";
        if (host.equals("disabled")) return "已安装 · 已停用";
        if (host.equals("update")) return "已安装 · 可更新";
        if (host.equals("setup")) return "首次使用需要部署管理器";
        if (host.equals("conflict")) return "已有运行组件不兼容";
        return "未接入";
    }

    @Override public String discoverLine() {
        if (failed()) return "读取失败";
        if (!known() || !confirmed() || refreshing) return "待确认";
        if (needsSetup()) return "待设置";
        if (incompatible()) return "不兼容";
        if (available()) return "可安装";
        if (host.equals("update")) return "可更新";
        if (host.equals("installed")) return "加载待确认";
        return on() ? "已启用" : "已停用";
    }

    @Override public String homeLine() {
        int cards = WidgetCards.totalCount(a);
        if (failed() || !on()) return statusText();
        return cards == 0 ? "已接入 · 尚未添加卡片" : "负一屏上 " + cards + " 张卡片";
    }

    @Override public boolean showOnHome() {
        return installed() || failed() || host.equals("setup") || host.equals("conflict");
    }

    void registerPages(PageRegistry pages) {
        pages.add(PageIds.WIDGET_DETAIL, "负一屏小组件", this::detail);
        pages.add(PageIds.WIDGET_PLAN, () -> on() ? "重新接入" : "接入方案", this::plan);
        pages.addManaged(PageIds.WIDGETS, this, this::manage);
    }

    @Override public String moduleTitle() { return "负一屏小组件"; }

    @Override public ModuleManagement.State managementState() {
        EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
        Boolean enabled = on() ? Boolean.TRUE : host.equals("disabled") || host.equals("available")
                ? Boolean.FALSE : null;
        String limitation = "";
        if (available() && (owner.equals("own") || owner.equals("shared"))) actions.add(Action.INSTALL);
        if (installed()) {
            if (owner.equals("own")) {
                actions.add(Action.UNINSTALL);
                if (host.equals("update")) actions.add(Action.UPDATE);
                else {
                    actions.add(Action.ENABLE);
                    actions.add(Action.DISABLE);
                    actions.add(Action.RECONNECT);
                }
            } else if (owner.equals("shared")) {
                actions.add(Action.RECONNECT);
                limitation = "共享运行组件没有小组件独立开关，不能单独启停或卸载；卡片配置保留。";
            } else limitation = "运行组件来源尚未确认，请重新读取状态。";
        } else if (host.equals("setup")) limitation = "请先完成管理器首次部署。";
        else if (host.equals("conflict")) limitation = "已有运行组件不兼容，请检查运行环境。";
        String status = failed() ? known() ? "上次：" + shortState() : "状态待确认" : statusText();
        return new ModuleManagement.State(installed(), enabled, confirmed(),
                a.working() || refreshing, status, readError, operationError, notice,
                limitation, actions);
    }

    @Override public String uninstallMessage() {
        return "只移除小组件功能，保留管理器、莫奈和录屏及卡片配置；表盘会重新加载一次。";
    }

    @Override public void executeManagement(Action action) {
        switch (action) {
            case REFRESH: refresh(true); break;
            case INSTALL:
            case RECONNECT: a.go(PageIds.WIDGET_PLAN); break;
            case ENABLE: setWidgetEnabled(true); break;
            case DISABLE: setWidgetEnabled(false); break;
            case UPDATE: update(); break;
            case UNINSTALL: uninstall(); break;
            default: break;
        }
    }

    /** 页面能直接展示的错误说明：已知的 IOException 带着中文原因，其余用 fallback。 */
    private static String describe(Exception e, String fallback) {
        return e instanceof IOException && e.getMessage() != null ? e.getMessage() : fallback;
    }

    private String operationErrorOf(Exception e) {
        return describe(e, "操作未完成，请重试");
    }

    /** 写操作开始：先同步共享缓存，并清掉上一次操作的提示。 */
    private void beginOperation() {
        sync();
        operationError = "";
        notice = "";
    }

    /** 只读状态：先同步共享缓存，再按 observation.fresh 决定是否探测。 */
    void refresh(boolean force) {
        if (a.destroyed()) return;
        sync();
        if (a.working() || refreshing) return;
        if (!force && observation.fresh(System.currentTimeMillis(), MainActivity.FRESH_MS)) return;
        refreshing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.check(app), (status, problem) -> {
            refreshing = false;
            sync();
            if (problem != null && readError.isEmpty()) readError = describe(problem, "读取失败，请检查管理器首次授权");
            a.render();
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
        if (!managementState().can(installed() ? Action.RECONNECT : Action.INSTALL)) return;
        beginOperation();
        installing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.install(app), (status, problem) -> {
            installing = false;
            sync();
            if (problem == null) notice = installNotice(host, keep);
            else operationError = operationErrorOf(problem);
            a.leave(PageIds.WIDGET_PLAN);
            a.returnAfterLauncherReload();
        });
    }

    /** 只修复开机后台白名单，不重载表盘。 */
    private void repairBackground() {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        repairing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.repairBackground(app), (status, problem) -> {
            repairing = false;
            sync();
            if (problem == null) {
                notice = keep.equals("added") ? "后台保活已加入白名单，重启一次手表后生效。" : "后台保活已开启。";
            } else {
                operationError = operationErrorOf(problem);
            }
            a.render();
        });
    }

    /** 独立运行组件才提供该功能的开关；共享 v13 的小组件没有独立门控。 */
    private void setWidgetEnabled(boolean enabled) {
        if (a.destroyed() || a.working() || !removable()) return;
        if (!managementState().can(enabled ? Action.ENABLE : Action.DISABLE)) return;
        beginOperation();
        changing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.setEnabled(app, enabled),
                (status, problem) -> {
                    changing = false;
                    sync();
                    if (problem == null) notice = enabled ? "小组件功能已启用。" : "小组件功能已停用，卡片配置保留。";
                    else operationError = operationErrorOf(problem);
                    a.render();
                    a.returnAfterLauncherReload();
                });
    }

    private void update() {
        if (a.destroyed() || a.working()) return;
        if (!managementState().can(Action.UPDATE)) return;
        beginOperation();
        changing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.update(app),
                (status, problem) -> {
                    changing = false;
                    sync();
                    if (problem == null) notice = "小组件运行组件已更新，原有启停状态已保留。";
                    else operationError = operationErrorOf(problem);
                    a.render();
                    a.returnAfterLauncherReload();
                });
    }

    private void uninstall() {
        if (a.destroyed() || a.working() || !removable()) return;
        if (!managementState().can(Action.UNINSTALL)) return;
        beginOperation();
        changing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> WidgetHostManager.uninstall(app),
                (status, problem) -> {
                    changing = false;
                    sync();
                    if (problem == null) notice = "小组件功能已卸载，已添加的卡片设置数据保留。";
                    else operationError = operationErrorOf(problem);
                    a.render();
                    a.returnAfterLauncherReload();
                });
    }

    /** 小布助手当前是否隐藏：读系统设置，无需 root，随时可读。 */
    private boolean breenoHidden() {
        try {
            return Settings.System.getInt(a.getContentResolver(), "codex_qc_hide_breeno", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 切换隐藏小布助手：写系统设置要 root，走后台；hook 下次打开负一屏时读取生效，不重载表盘。 */
    private void setBreeno(boolean hide) {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        breenoBusy = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> {
            WidgetHostManager.breeno(app, hide);
            return null;
        }, (unused, problem) -> {
            breenoBusy = false;
            sync();
            if (problem != null) operationError = operationErrorOf(problem);
            a.render();
        });
    }

    /** 读取失败的说明与重试按钮；介绍页和管理页共用。 */
    private void readFailure(boolean primaryRetry) {
        a.ui.note((on() ? "上次已接入 · 本次读取失败：" : "读取状态失败：") + readError, "warning");
        a.ui.button("重新读取状态", primaryRetry, !refreshing, () -> refresh(true));
    }

    private void openEditor() {
        if (a.destroyed() || a.working()) return;
        beginOperation();
        openingEditor = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.WIDGETS, () -> {
            WidgetHostManager.openEditor(app);
            return null;
        }, (unused, problem) -> {
            openingEditor = false;
            if (problem != null) operationError = operationErrorOf(problem);
            a.render();
        });
    }

    /** 本次操作的结果提示；介绍页和管理页共用。 */
    private void operationNotes() {
        if (!notice.isEmpty()) a.ui.note(notice, "success");
        if (!operationError.isEmpty()) a.ui.note(operationError, "warning");
    }

    private void detail() {
        WorkshopUi ui = a.ui;
        ui.hero("layers", "负一屏小组件", "信息、倒计时和课表，直接放在负一屏", "");
        operationNotes();
        if (failed()) readFailure(!installed());
        if (installed()) {
            if (!failed()) ui.note(statusText(), on() ? "success" : "warning");
            ui.button("打开小组件", true, () -> a.go(PageIds.WIDGETS));
        } else if (available()) {
            ui.note("适配当前手表 · 可接入", "success");
            ui.button("接入负一屏", true, managementState().can(Action.INSTALL), () -> request(Action.INSTALL));
        } else if (host.equals("setup")) {
            ui.note("完成一次首次部署后，可在这里接入小组件。", "warning");
            ui.row("首次设置", "", "book", () -> a.go(PageIds.GUIDE));
        } else if (host.equals("conflict")) {
            ui.note("现有运行组件不兼容，请先检查环境。", "warning");
            ui.row("运行环境", "", "shield", () -> a.go(PageIds.CHECK));
            ui.button("重新读取状态", false, !a.working(), () -> refresh(true));
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
        boolean again = installed();
        ui.hero("layers", again ? "重新接入" : "接入负一屏", "让原生负一屏能添加 OWOrnate 的卡片", "");
        if (owner.equals("shared")) {
            ui.value("本次操作", "检查现有共享 v13，必要时替换并在替换前保留原 Hook 备份；然后重新加载表盘");
        } else if (again) {
            ui.value("本次操作", "检查本包独立运行组件，启用小组件并重新加载表盘；保留现有设置");
        } else {
            ui.value("本次操作", "准备本包独立运行组件，先保存为停用再启用小组件；保留现有设置");
        }
        if (on() && (keep.equals("missing") || keep.equals("unavailable"))) {
            ui.value("后台更新", "白名单缺失时一并修复");
        }
        ui.value("不会改动", "应用数据、莫奈取色、QQ 和输入法词库");
        ui.note("表盘会重新加载一次，屏幕可能短暂黑屏。", "warning");
        if (installing) ui.note("正在接入，请稍候，不要退出…");
        ui.button(again ? "重新接入" : "接入并重新加载表盘", true,
                managementState().can(again ? Action.RECONNECT : Action.INSTALL), this::install);
        ui.button("暂不接入", false, !busy(), a::onBackPressed);
    }

    private void manage() {
        WorkshopUi ui = a.ui;
        ModuleManagementUi.status(ui, this);
        hostNotes();
        backgroundNotes();
        ui.row("OWOrnate 信息", WidgetCards.infoSummary(a), "info",
                () -> a.startActivity(new Intent(a, OWOrnateWidgetSettingsActivity.class)));
        ui.row("倒计时", WidgetCards.countdownSummary(a), "clock",
                () -> a.startActivity(new Intent(a, CountdownSettingsActivity.class)));
        ui.row("课表", WidgetCards.timetableSummary(a), "book",
                () -> a.startActivity(new Intent(a, TimetableSettingsActivity.class)));
        ui.title("添加或移除卡片");
        ui.note("在负一屏的编辑页添加或移除卡片，点卡片本身可以再次设置。每类卡片只有一份设置，同类卡片共用。");
        ui.button(openingEditor ? "正在打开…" : "打开系统卡片编辑页", false,
                on() && !a.working() && !failed(), this::openEditor);
        if (on()) breenoSection();
        managementUi.actions(ui, this, a::render);
    }

    /** 管理页顶部：读取失败、尚未接入或加载未确认时的说明。 */
    private void hostNotes() {
        WorkshopUi ui = a.ui;
        if (host.equals("installed")) {
            ui.note("已接入 · 表盘加载状态未确认。负一屏里找不到卡片时，可在页底重新接入。");
        } else if (host.equals("update")) {
            ui.note("现有小组件运行文件需要更新。更新会保留功能启停状态。", "warning");
        } else if (host.equals("setup")) {
            ui.row("首次设置", "", "book", () -> a.go(PageIds.GUIDE));
        } else if (host.equals("conflict")) {
            ui.row("运行环境", "", "shield", () -> a.go(PageIds.CHECK));
        }
    }

    /** 后台更新白名单：缺失时给出修复入口；刚加入时提示重启一次。 */
    private void backgroundNotes() {
        WorkshopUi ui = a.ui;
        if (on() && (keep.equals("missing") || keep.equals("unavailable"))) {
            ui.note(keep.equals("missing")
                    ? "后台更新白名单未加入，息屏后卡片可能停止更新。"
                    : "无法确认后台更新白名单，请检查系统权限后重试。", "warning");
            ui.button("修复后台更新", true, !a.working(), this::repairBackground);
            if (repairing) ui.note("正在修复后台更新…");
        }
        if (WidgetHostManager.keepPendingReboot(a)) {
            ui.note("已加入系统后台白名单。重启一次手表后，卡片在息屏后也会自动更新。", "warning");
        }
    }

    private void breenoSection() {
        WorkshopUi ui = a.ui;
        ui.title("负一屏外观");
        boolean hidden = breenoHidden();
        ui.toggle("隐藏小布助手", hidden ? "负一屏不再显示小布那一行" : "负一屏顶部保留小布那一行",
                hidden, () -> setBreeno(!breenoHidden()));
        if (breenoBusy) ui.note("正在应用，下次打开负一屏生效…");
        else ui.note("隐藏后，下面的卡片会顶上来；需要小布时随时可以打开。");
    }
}
