package dev.codex.watchworkshop;

import dev.codex.watchworkshop.ModuleManagement.Action;
import dev.codex.watchworkshop.ModuleManagement.State;

/** 所有模块共用页头、状态反馈、管理操作和卸载确认，业务页面不再自行画总开关。 */
final class ModuleManagementUi {
    private boolean confirmUninstall;

    static void header(WorkshopUi ui, Runnable back, ModuleManagement module) {
        State state = module.managementState();
        Action action = state.toggleAction();
        ui.headerToggle(module.moduleTitle(), back, state.enabled, state.can(action),
                () -> module.request(action));
    }

    static void status(WorkshopUi ui, ModuleManagement module) {
        State state = module.managementState();
        if (!state.status.isEmpty()) ui.note(state.status);
        if (!state.readError.isEmpty()) ui.note("读取状态失败：" + state.readError, "warning");
        if (!state.operationError.isEmpty() && !state.operationError.equals(state.readError)) {
            ui.note("操作未完成：" + state.operationError, "warning");
        }
        if (!state.notice.isEmpty()) ui.note(state.notice, "success");
        if (state.busy) ui.note("正在处理，请稍候…");
        if (!state.limitation.isEmpty()) ui.note(state.limitation);
    }

    void actions(WorkshopUi ui, ModuleManagement module, Runnable render) {
        State state = module.managementState();
        if (!state.offers(Action.UNINSTALL)) confirmUninstall = false;
        ui.title("模块管理");
        for (Action action : Action.values()) {
            if (action == Action.ENABLE || action == Action.DISABLE || !state.offers(action)) continue;
            if (action == Action.UNINSTALL && confirmUninstall) {
                ui.note(module.uninstallMessage(), "warning");
                ui.button("确认卸载", true, state.can(action), () -> {
                    confirmUninstall = false;
                    module.request(action);
                });
                ui.button("取消", false, !state.busy, () -> {
                    confirmUninstall = false;
                    render.run();
                });
            } else {
                ui.button(action.label, action == Action.INSTALL || action == Action.UPDATE,
                        state.can(action), () -> {
                    if (!module.managementState().can(action)) return;
                    if (action == Action.UNINSTALL) {
                        confirmUninstall = true;
                        render.run();
                    } else {
                        confirmUninstall = false;
                        module.request(action);
                    }
                });
            }
        }
    }
}
