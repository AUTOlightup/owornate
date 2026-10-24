package dev.codex.watchworkshop;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** 内置功能与外部插件共用的管理契约；设备操作与业务设置由各实现负责。 */
interface ModuleManagement {
    enum Action {
        INSTALL("安装模块"), UPDATE("更新组件"), REPAIR("修复运行组件"),
        RECONNECT("重新接入"), UNINSTALL("卸载模块"), REFRESH("重新读取状态"),
        ENABLE("启用"), DISABLE("停用");

        final String label;
        Action(String label) { this.label = label; }
    }

    String moduleTitle();
    State managementState();
    String uninstallMessage();

    /** 由 request 检查最新能力后调用；INSTALL 可以先打开现有安装确认页。 */
    void executeManagement(Action action);

    default void request(Action action) {
        if (managementState().can(action)) executeManagement(action);
    }

    /** 不含 Android 依赖的页面快照；未知、上次状态和本次错误不能混成一个开关值。 */
    final class State {
        final boolean installed, busy;
        final Boolean enabled;
        final String status, readError, operationError, notice, limitation;
        private final boolean confirmed;
        private final Set<Action> actions;
        private final boolean uninstallRecovery;

        State(boolean installed, Boolean enabled, boolean confirmed, boolean busy,
                String status, String readError, String operationError, String notice,
                String limitation, Set<Action> actions) {
            this(installed, enabled, confirmed, busy, status, readError, operationError,
                    notice, limitation, actions, false);
        }

        /** 自有外部插件的清理不依赖 status 成功；卸载实现仍须确认停用，失败保留包。 */
        State(boolean installed, Boolean enabled, boolean confirmed, boolean busy,
                String status, String readError, String operationError, String notice,
                String limitation, Set<Action> actions, boolean uninstallRecovery) {
            this.installed = installed;
            this.confirmed = confirmed;
            this.busy = busy;
            this.status = status;
            this.readError = readError;
            this.operationError = operationError;
            this.notice = notice;
            this.limitation = limitation;
            this.enabled = confirmed && readError.isEmpty() ? enabled : null;
            EnumSet<Action> copy = EnumSet.noneOf(Action.class);
            copy.addAll(actions);
            this.actions = Collections.unmodifiableSet(copy);
            this.uninstallRecovery = uninstallRecovery;
        }

        boolean offers(Action action) { return actions.contains(action); }

        boolean can(Action action) {
            if (busy || !offers(action)) return false;
            if (action == Action.REFRESH) return true;
            // 功能启动失败时仍需能修复。适配器只为可修复的自有安装提供此动作，
            // ModuleManager.repair 会在设备端重新确认安装和归属后才写入。
            if (action == Action.REPAIR) return installed;
            if (action == Action.UNINSTALL && uninstallRecovery) return installed;
            if (!confirmed || !readError.isEmpty()) return false;
            if (action == Action.ENABLE) return installed && Boolean.FALSE.equals(enabled);
            if (action == Action.DISABLE) return installed && Boolean.TRUE.equals(enabled);
            return true;
        }

        Action toggleAction() {
            return Boolean.TRUE.equals(enabled) ? Action.DISABLE : Action.ENABLE;
        }
    }
}
