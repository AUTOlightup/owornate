package dev.codex.watchworkshop;

import dev.codex.watchworkshop.ModuleManagement.Action;
import dev.codex.watchworkshop.ModuleManagement.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModuleManagementTest {
    private static int passed;

    public static void main(String[] args) {
        run("未知、读取失败和未确认状态禁用一般写入但可刷新",
                ModuleManagementTest::nonWritableStates);
        run("已安装且提供 REPAIR 时忽略确认与读取错误", ModuleManagementTest::repairRecovery);
        run("shared widgets 显示开启但不提供切换", ModuleManagementTest::sharedWidgetsSnapshot);
        run("update 的未知启停值仍可更新", ModuleManagementTest::updateWithUnknownEnabled);
        run("busy 禁止全部操作", ModuleManagementTest::busyBlocksAllActions);
        run("正常状态用 caption，错误与通知保留且不重复",
                ModuleManagementTest::statusPresentation);
        run("旧按钮回调不能越过新状态", ModuleManagementTest::staleButtonCallback);
        run("启停不乐观修改模块状态", ModuleManagementTest::toggleIsNotOptimistic);
        run("卸载需要二次确认", ModuleManagementTest::uninstallNeedsConfirmation);
        run("卸载确认时重新核验状态", ModuleManagementTest::uninstallConfirmationRechecksState);
        run("插件包文案沿用卸载确认与同一动作", ModuleManagementTest::packageActionLabel);
        run("管理折叠保留原控制器的恢复能力与忙碌门控", ModuleManagementTest::managementSection);
        run("普通页与 managed 页注册路由", ModuleManagementTest::pageRegistration);
        run("actions 快照不受原集合修改影响", ModuleManagementTest::actionsSnapshot);
        run("外部插件错误态允许尝试清理，忙碌时仍禁止", ModuleManagementTest::pluginCleanup);
        System.out.println("ModuleManagementTest: OK (" + passed + " cases)");
    }

    private static void pluginCleanup() {
        State failed = new State(true, null, false, false, "", "bad script", "", "", "",
                EnumSet.of(Action.UNINSTALL, Action.REFRESH, Action.ENABLE), true);
        check(failed.can(Action.UNINSTALL), "显式允许时可尝试清理");
        check(!failed.can(Action.ENABLE), "错误态不能启用");
        State busy = new State(true, null, false, true, "", "bad script", "", "", "",
                EnumSet.of(Action.UNINSTALL), true);
        check(!busy.can(Action.UNINSTALL), "不能重叠清理");
    }

    private static void packageActionLabel() {
        TestModule module = new TestModule(state(true, true, true, false, "",
                Action.REFRESH, Action.UNINSTALL));
        module.packageLabels = true;
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        Runnable[] render = new Runnable[1];
        render[0] = () -> management.actions(ui, module, render[0]);
        render[0].run();
        check(ui.lastButton("移除插件包") != null, "应显示导入包的准确动作名称");
        ui.lastButton("移除插件包").action.run();
        check(module.executed.isEmpty(), "更改文案不能跳过确认");
        ui.lastButton("确认卸载").action.run();
        check(module.executed.equals(Arrays.asList(Action.UNINSTALL)), "确认后仍执行原卸载动作");
    }

    private static void managementSection() {
        TestModule module = new TestModule(state(true, true, true, false, "",
                Action.RECONNECT, Action.REPAIR, Action.REFRESH));
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        AtomicInteger packageVisits = new AtomicInteger();
        Runnable[] render = new Runnable[1];
        render[0] = () -> management.section(ui, module, render[0], packageVisits::incrementAndGet);
        render[0].run();
        check(ui.buttons.isEmpty(), "默认收起管理动作");
        ui.disclosures.get(0).action.run();
        check(ui.lastButton(Action.RECONNECT.label) != null, "包管理不能挤掉重新接入");
        check(ui.lastButton(Action.REPAIR.label) != null, "包管理不能挤掉原修复能力");
        ui.lastButton(Action.RECONNECT.label).action.run();
        check(module.executed.equals(Arrays.asList(Action.RECONNECT)), "仍通过原控制器执行恢复");
        WorkshopUi.Row packageRow = ui.rows.get(0);
        packageRow.action.run();
        check(packageVisits.get() == 1, "可单独进入插件包管理");
        module.change(state(true, true, true, true, "", Action.REFRESH));
        packageRow.action.run();
        check(packageVisits.get() == 1, "旧入口不能越过新的忙碌状态");
    }

    private static void run(String name, Runnable test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            System.err.println("FAIL " + name);
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void nonWritableStates() {
        State unknown = state(false, null, true, false, "",
                Action.REFRESH, Action.ENABLE, Action.DISABLE);
        assertGeneralWritesBlocked(unknown);

        State readFailed = state(true, true, true, false, "读取超时",
                Action.REFRESH, Action.UPDATE, Action.ENABLE, Action.DISABLE,
                Action.UNINSTALL);
        check(readFailed.enabled == null, "读取失败时不能保留确定的开关值");
        assertGeneralWritesBlocked(readFailed);

        State unconfirmed = state(true, true, false, false, "",
                Action.REFRESH, Action.UPDATE, Action.ENABLE, Action.DISABLE,
                Action.UNINSTALL);
        check(unconfirmed.enabled == null, "confirmed=false 时启停值必须为未知");
        assertGeneralWritesBlocked(unconfirmed);

        TestModule module = new TestModule(readFailed);
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        ModuleManagementUi.header(ui, () -> {}, module);
        ModuleManagementUi.status(ui, module);
        management.actions(ui, module, () -> {});
        check(ui.lastHeader().checked == null, "读取失败页头应显示未知开关");
        check(!ui.lastHeader().enabled, "读取失败页头开关必须禁用");
        check(ui.hasNote("读取状态失败：读取超时"), "读取错误应单独显示");
        check(ui.lastButton(Action.REFRESH.label).enabled, "读取失败后刷新仍应可用");
        check(!ui.lastButton(Action.UPDATE.label).enabled, "读取失败后写入必须禁用");
    }

    private static void repairRecovery() {
        State recoverable = state(true, null, false, false, "helper 启动失败",
                Action.REFRESH, Action.REPAIR, Action.UPDATE, Action.ENABLE,
                Action.DISABLE, Action.UNINSTALL);
        check(recoverable.can(Action.REPAIR),
                "own installed 的错误态必须保留 REPAIR 入口");
        assertGeneralWritesBlocked(recoverable);

        TestModule module = new TestModule(recoverable);
        WorkshopUi ui = new WorkshopUi();
        new ModuleManagementUi().actions(ui, module, () -> {});
        WorkshopUi.Button repair = ui.lastButton(Action.REPAIR.label);
        check(repair != null && repair.enabled, "错误态修复按钮应可用");
        repair.action.run();
        check(module.executed.equals(Arrays.asList(Action.REPAIR)),
                "修复按钮回调应执行 REPAIR");

        State missing = state(false, null, false, false, "helper 启动失败",
                Action.REFRESH, Action.REPAIR);
        check(!missing.can(Action.REPAIR), "未安装时不能修复");

        State unsupported = state(true, null, false, false, "helper 启动失败",
                Action.REFRESH);
        check(!unsupported.can(Action.REPAIR), "未提供 REPAIR 时不能修复");

        State busy = state(true, null, false, true, "helper 启动失败",
                Action.REFRESH, Action.REPAIR);
        check(!busy.can(Action.REPAIR), "busy 时不能修复");
    }

    private static void sharedWidgetsSnapshot() {
        State shared = state(true, true, true, false, "",
                Action.REFRESH, Action.RECONNECT);
        TestModule module = new TestModule(shared);
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi.header(ui, () -> {}, module);
        WorkshopUi.Header header = ui.lastHeader();
        check(Boolean.TRUE.equals(header.checked), "shared 快照的开启状态仍应显示");
        check(!header.enabled, "没有 ENABLE/DISABLE 时页头不能切换");
        header.action.run();
        check(module.executed.isEmpty(), "不存在切换动作时回调不能写");
    }

    private static void updateWithUnknownEnabled() {
        State update = state(true, null, true, false, "",
                Action.REFRESH, Action.UPDATE);
        TestModule module = new TestModule(update);
        WorkshopUi ui = new WorkshopUi();
        new ModuleManagementUi().actions(ui, module, () -> {});
        WorkshopUi.Button button = ui.lastButton(Action.UPDATE.label);
        check(button != null, "update 状态必须提供更新按钮");
        check(button.primary, "更新按钮应为主要操作");
        check(button.enabled, "unknown enabled 不应阻止更新");
        button.action.run();
        check(module.executed.equals(Arrays.asList(Action.UPDATE)), "更新回调应执行 UPDATE");
    }

    private static void busyBlocksAllActions() {
        State busy = state(true, true, true, true, "", Action.values());
        for (Action action : Action.values()) {
            check(!busy.can(action), "busy 时 " + action + " 必须不可执行");
        }
        TestModule module = new TestModule(busy);
        for (Action action : Action.values()) module.request(action);
        check(module.executed.isEmpty(), "busy 时 request 不能执行动作");

        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        ModuleManagementUi.header(ui, () -> {}, module);
        ModuleManagementUi.status(ui, module);
        management.actions(ui, module, () -> {});
        check(!ui.lastHeader().enabled, "busy 时页头必须禁用");
        check(ui.hasNote("正在处理，请稍候…"), "busy 时应显示等待状态");
        for (WorkshopUi.Button button : ui.buttons) {
            check(!button.enabled, "busy 时按钮必须禁用：" + button.label);
        }
    }

    private static void staleButtonCallback() {
        State writable = state(true, false, true, false, "",
                Action.REFRESH, Action.ENABLE, Action.DISABLE, Action.UPDATE);
        TestModule module = new TestModule(writable);
        WorkshopUi ui = new WorkshopUi();
        new ModuleManagementUi().actions(ui, module, () -> {});
        WorkshopUi.Button stale = ui.lastButton(Action.UPDATE.label);
        check(stale.enabled, "初始状态应允许更新");

        module.change(state(true, false, true, false, "读取失败",
                Action.REFRESH, Action.UPDATE));
        stale.action.run();
        check(module.executed.isEmpty(), "状态变化后旧更新回调不能写入");
    }

    private static void statusPresentation() {
        ModuleManagementUi management = new ModuleManagementUi();

        WorkshopUi normalUi = new WorkshopUi();
        management.status(normalUi,
                new TestModule(displayState("测试状态", "", "", "")));
        check(normalUi.captions.contains("测试状态"), "正常状态应显示为 caption");
        check(normalUi.notes.isEmpty(), "正常状态不应再占用 note");

        WorkshopUi readFailedUi = new WorkshopUi();
        management.status(readFailedUi, new TestModule(
                displayState("测试状态", "读取超时", "读取超时", "已重新连接")));
        check(readFailedUi.hasNote("读取状态失败：读取超时"), "读取错误仍应可见");
        check(readFailedUi.hasNote("已重新连接"), "通知仍应可见");
        check(!readFailedUi.hasNote("操作未完成：读取超时"), "同一错误不能重复显示");
        check(readFailedUi.captions.contains("测试状态"), "错误态仍应保留状态说明");

        WorkshopUi writeFailedUi = new WorkshopUi();
        management.status(writeFailedUi, new TestModule(
                displayState("测试状态", "读取超时", "写入失败", "")));
        check(writeFailedUi.hasNote("读取状态失败：读取超时"), "读取错误仍应可见");
        check(writeFailedUi.hasNote("操作未完成：写入失败"), "不同错误应分别显示");

        WorkshopUi deferredUi = new WorkshopUi();
        State limited = new State(true, true, true, false, "已启用", "读取失败", "写入失败", "",
                "限制说明", EnumSet.of(Action.REFRESH));
        ModuleManagementUi.status(deferredUi, new TestModule(limited), false);
        check(!deferredUi.hasNote("限制说明"), "长篇限制可由页面放在设置之后");
        check(deferredUi.hasNote("读取状态失败：读取失败"), "移动限制不能隐藏读取错误");
        check(deferredUi.hasNote("操作未完成：写入失败"), "移动限制不能隐藏操作错误");
    }

    private static State displayState(String status, String readError,
            String operationError, String notice) {
        return new State(true, null, true, false, status, readError, operationError,
                notice, "", actions(Action.REFRESH));
    }

    private static void toggleIsNotOptimistic() {
        State disabled = state(true, false, true, false, "",
                Action.REFRESH, Action.ENABLE, Action.DISABLE);
        TestModule module = new TestModule(disabled);
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi.header(ui, () -> {}, module);
        WorkshopUi.Header header = ui.lastHeader();
        check(Boolean.FALSE.equals(header.checked), "停用状态应显示关闭");
        check(header.enabled, "已知停用值应允许切换");
        header.action.run();
        check(module.executed.equals(Arrays.asList(Action.ENABLE)), "页头应请求 ENABLE");
        check(Boolean.FALSE.equals(module.managementState().enabled),
                "执行请求不能乐观改写管理状态");
    }

    private static void uninstallNeedsConfirmation() {
        State own = state(true, true, true, false, "",
                Action.REFRESH, Action.UNINSTALL);
        TestModule module = new TestModule(own);
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        AtomicInteger renders = new AtomicInteger();
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            renders.incrementAndGet();
            management.actions(ui, module, render[0]);
        };
        management.actions(ui, module, render[0]);

        WorkshopUi.Button uninstall = ui.lastButton(Action.UNINSTALL.label);
        check(uninstall != null && uninstall.enabled, "own installed 应可开始卸载确认");
        uninstall.action.run();
        check(module.executed.isEmpty(), "第一次点击只进入确认，不能卸载");
        check(renders.get() == 1, "第一次点击应重新渲染确认页");
        check(ui.hasNote(module.uninstallMessage()), "确认页应显示卸载影响");

        WorkshopUi.Button confirm = ui.lastButton("确认卸载");
        check(confirm != null && confirm.enabled, "确认卸载按钮应可用");
        confirm.action.run();
        check(module.executed.equals(Arrays.asList(Action.UNINSTALL)),
                "第二次确认后才执行卸载");
    }

    private static void uninstallConfirmationRechecksState() {
        State own = state(true, true, true, false, "",
                Action.REFRESH, Action.UNINSTALL);
        TestModule module = new TestModule(own);
        WorkshopUi ui = new WorkshopUi();
        ModuleManagementUi management = new ModuleManagementUi();
        Runnable[] render = new Runnable[1];
        render[0] = () -> management.actions(ui, module, render[0]);
        management.actions(ui, module, render[0]);
        ui.lastButton(Action.UNINSTALL.label).action.run();
        WorkshopUi.Button staleConfirm = ui.lastButton("确认卸载");
        check(staleConfirm != null && staleConfirm.enabled, "确认按钮初始应可用");

        module.change(state(true, true, false, false, "",
                Action.REFRESH, Action.UNINSTALL));
        staleConfirm.action.run();
        check(module.executed.isEmpty(), "确认时状态未确认，旧确认回调不能卸载");
    }

    private static void pageRegistration() {
        PageRegistry pages = new PageRegistry();
        TestModule module = new TestModule(state(false, null, true, false, "",
                Action.REFRESH));
        AtomicInteger plainRenders = new AtomicInteger();
        AtomicInteger managedRenders = new AtomicInteger();
        pages.add("plain", "普通页", plainRenders::incrementAndGet);
        pages.addManaged("managed", module, managedRenders::incrementAndGet);

        check(pages.contains("plain"), "普通页应注册");
        check(pages.contains("managed"), "managed 页应注册");
        check("普通页".equals(pages.title("plain")), "普通页标题路由错误");
        check(module.moduleTitle().equals(pages.title("managed")), "managed 页标题路由错误");
        check(pages.management("plain") == null, "普通页不应关联管理模块");
        check(pages.management("managed") == module, "managed 页应关联原模块");
        check(pages.render("plain"), "普通页渲染路由失败");
        check(pages.render("managed"), "managed 页渲染路由失败");
        check(plainRenders.get() == 1 && managedRenders.get() == 1,
                "页面渲染入口必须分别命中");
        check(!pages.render("missing"), "未知页面不应渲染");
        check(pages.management("missing") == null, "未知页面不应关联管理模块");
        List<String> ids = new ArrayList<>(pages.ids());
        check(ids.equals(Arrays.asList("plain", "managed")), "页面注册顺序应稳定");
    }

    private static void actionsSnapshot() {
        EnumSet<Action> offered = actions(Action.REFRESH, Action.UPDATE);
        State state = stateWith(true, null, true, false, "", offered);
        offered.clear();
        offered.add(Action.DISABLE);
        check(state.offers(Action.REFRESH), "快照应保留 REFRESH");
        check(state.offers(Action.UPDATE), "快照应保留 UPDATE");
        check(!state.offers(Action.DISABLE), "构造后的集合修改不能进入状态快照");
        check(state.can(Action.UPDATE), "快照保留的更新能力仍应有效");
    }

    private static void assertGeneralWritesBlocked(State state) {
        check(state.can(Action.REFRESH), "不可写状态仍应允许刷新");
        for (Action action : Action.values()) {
            if (action != Action.REFRESH && action != Action.REPAIR) {
                check(!state.can(action), "不可写状态不能执行 " + action);
            }
        }
    }

    private static State state(boolean installed, Boolean enabled, boolean confirmed,
            boolean busy, String readError, Action... offered) {
        return stateWith(installed, enabled, confirmed, busy, readError, actions(offered));
    }

    private static State stateWith(boolean installed, Boolean enabled, boolean confirmed,
            boolean busy, String readError, Set<Action> offered) {
        return new State(installed, enabled, confirmed, busy, "测试状态", readError,
                "", "", "测试限制", offered);
    }

    private static EnumSet<Action> actions(Action... values) {
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        actions.addAll(Arrays.asList(values));
        return actions;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestModule implements ModuleManagement {
        private State state;
        boolean packageLabels;
        final List<Action> executed = new ArrayList<>();

        TestModule(State state) {
            this.state = state;
        }

        void change(State next) {
            state = next;
        }

        @Override public String moduleTitle() {
            return "测试模块";
        }

        @Override public State managementState() {
            return state;
        }

        @Override public String uninstallMessage() {
            return "卸载会先停止录制；媒体和配置保留。";
        }

        @Override public String actionLabel(Action action) {
            return packageLabels && action == Action.UNINSTALL ? "移除插件包" : action.label;
        }

        @Override public void executeManagement(Action action) {
            executed.add(action);
        }
    }
}
