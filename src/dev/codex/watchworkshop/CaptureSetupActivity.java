package dev.codex.watchworkshop;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import dev.codex.watchworkshop.ModuleManagement.Action;
import dev.codex.watchworkshop.ModuleManagement.State;
import java.util.EnumSet;

/**
 * 截屏与录屏模块管理：共享页头、状态和管理操作，本页保留功能入口与业务设置。
 * 部署与启用状态只投影 {@link RuntimeStateStore}；设备操作排在 {@link DeviceQueues#GENERAL}。
 */
public final class CaptureSetupActivity extends WatchActivity implements ModuleManagement {
    private boolean busy;
    private boolean installing;
    private String operationError = "";
    private String notice = "";
    private StatusObservation<CaptureModuleState> observation;
    private final ModuleManagementUi managementUi = new ModuleManagementUi();
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (store, key) -> {
        if (!destroyed() && "capture".equals(key)) {
            sync();
            render();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        observeWhileStarted(RuntimeStateStore.preferences(this), changed);
        sync();
        render();
        perform("read");
    }

    @Override protected void onStart() {
        super.onStart();
        sync();
        render();
    }

    @Override public void onBackPressed() {
        if (!installing) super.onBackPressed();
    }

    private void sync() {
        observation = RuntimeStateStore.capture(this);
    }

    @Override public String moduleTitle() {
        return "截屏与录屏";
    }

    @Override public State managementState() {
        CaptureModuleState module = observation == null ? null : observation.value;
        String readError = observation == null ? "" : observation.error;
        boolean confirmed = observation != null && module != null
                && observation.confirmedAt > 0 && readError.isEmpty();
        Boolean enabled = module != null && module.enabledKnown()
                ? Boolean.valueOf(module.enabled())
                : module != null && "available".equals(module.kind) ? Boolean.FALSE : null;
        EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
        if (module != null) {
            boolean own = "own".equals(module.owner);
            boolean shared = "shared".equals(module.owner);
            if ("available".equals(module.kind) && (own || shared)) actions.add(Action.INSTALL);
            if (module.installed() && !"update".equals(module.kind) && (own || shared)) {
                actions.add(Action.RECONNECT);
            }
            if ("update".equals(module.kind) && own) actions.add(Action.UPDATE);
            if (module.enabledKnown() && (own || shared)) {
                actions.add(Action.ENABLE);
                actions.add(Action.DISABLE);
            }
            if (module.installed() && own) actions.add(Action.UNINSTALL);
        }
        return new State(module != null && module.installed(), enabled, confirmed, busy,
                statusText(observation), readError, operationError, notice,
                limitation(module, confirmed), actions);
    }

    /** 上次确认值与本次读取错误分开显示；enabled 不冒充已确认可以开始采集。 */
    private String statusText(StatusObservation<CaptureModuleState> observed) {
        if (observed == null || observed.value == null) {
            return observed != null && !observed.error.isEmpty()
                    ? "尚未取得模块状态。" : "正在读取模块状态…";
        }
        if (!observed.error.isEmpty()) return "上次：" + observed.value.message();
        if (observed.confirmedAt <= 0) return "尚未确认：" + observed.value.message();
        return observed.value.message();
    }

    /** 只说明本页实际提供的能力和来源限制，不把启用写成运行或可采集已确认。 */
    private String limitation(CaptureModuleState module, boolean confirmed) {
        if (module == null) return "";
        if ("setup".equals(module.kind)) {
            return "首次使用仍需在设置中完成部署；部署完成后再安装截屏与录屏模块。";
        }
        if ("conflict".equals(module.kind)) {
            return "已有运行组件不兼容，请检查运行环境。";
        }
        boolean own = "own".equals(module.owner);
        boolean shared = "shared".equals(module.owner);
        if (module.installed() && !own && !shared) {
            return "运行组件来源尚未确认，不能更新、启停或卸载；请重新读取状态。";
        }
        if (!confirmed) return "请重新读取状态后再操作。";
        StringBuilder text = new StringBuilder();
        if (shared) {
            text.append("共享运行组件不能单独卸载；可停用截屏与录屏，其他功能不受影响。");
        }
        if ("available".equals(module.kind)) {
            text.append("安装会启用快捷入口并重载一次表盘，屏幕可能短暂黑屏。");
        } else if (module.installed() && !"update".equals(module.kind)) {
            text.append("重新接入会启用快捷入口并重载一次表盘，屏幕可能短暂黑屏。");
            text.append("停用会先停止当前录屏，再隐藏两个按钮；已保存文件和画质设置保留。");
        }
        return text.toString();
    }

    @Override public String uninstallMessage() {
        return "卸载前会先停止当前录制；管理器、保存的媒体和画质设置保留。";
    }

    @Override public void executeManagement(Action action) {
        if (action == null) return;
        switch (action) {
            case INSTALL:
            case RECONNECT: perform("install"); break;
            case UPDATE: perform("update"); break;
            case ENABLE: perform("enable"); break;
            case DISABLE: perform("disable"); break;
            case UNINSTALL: perform("uninstall"); break;
            case REFRESH: perform("read"); break;
            default: break;
        }
    }

    /** 旧页面回调也要重新核对当前来源、启停值和确认时间；读取不受写入门控限制。 */
    private boolean canPerform(String action) {
        if ("read".equals(action)) return true;
        State current = managementState();
        switch (action) {
            case "install": return current.can(Action.INSTALL) || current.can(Action.RECONNECT);
            case "update": return current.can(Action.UPDATE);
            case "enable": return current.can(Action.ENABLE);
            case "disable": return current.can(Action.DISABLE);
            case "uninstall": return current.can(Action.UNINSTALL);
            default: return false;
        }
    }

    /** read / install / update / enable / disable / uninstall；结束后总会再读一次模块状态。 */
    private void perform(String action) {
        if (busy || !canPerform(action)) return;
        busy = true;
        installing = "install".equals(action) || "update".equals(action)
                || "uninstall".equals(action);
        operationError = "";
        notice = "";
        render();
        Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> runAction(app, action), (problem, unused) -> {
            busy = false;
            installing = false;
            operationError = problem;
            sync();
            if (problem.isEmpty() && "install".equals(action)) {
                notice = "截屏与录屏已接入并启用。到控制中心第二页使用两个按钮，长按进入对应设置。";
            } else if (problem.isEmpty() && "update".equals(action)) {
                notice = "模块已更新，原有启停状态已保留。";
            } else if (problem.isEmpty() && "uninstall".equals(action)) {
                notice = "截屏与录屏模块已卸载，保存的媒体和画质设置保留。";
            } else if (problem.isEmpty() && "enable".equals(action)) {
                notice = "已启用截屏与录屏快捷入口。";
            } else if (problem.isEmpty() && "disable".equals(action)) {
                notice = "已停用，媒体和画质设置保留。";
            }
            render();
            if (!"read".equals(action)) returnAfterLauncherReload();
        });
    }

    /** 后台执行一次操作；返回给用户看的问题，成功时为空串。写操作失败后仍补读一次状态。 */
    private String runAction(Context app, String action) {
        try {
            switch (action) {
                case "install": WidgetHostManager.enableCapture(app); break;
                case "update": WidgetHostManager.updateCapture(app); break;
                case "enable": WidgetHostManager.setCaptureEnabled(app, true); break;
                case "disable": WidgetHostManager.setCaptureEnabled(app, false); break;
                case "uninstall": WidgetHostManager.uninstallCapture(app); break;
                default: break;
            }
            WidgetHostManager.captureState(app);
            return "";
        } catch (Exception e) {
            String problem = message(e);
            if (!"read".equals(action)) {
                try {
                    WidgetHostManager.captureState(app);
                } catch (Exception ignored) {
                    // 原操作的错误已经足够说明问题。
                }
            }
            return problem;
        }
    }

    private void render() {
        rebuild("capture-module", () -> {
            ModuleManagementUi.header(ui, this::onBackPressed, this);
            ui.note("OWOrnate 功能模块 · 控制中心快捷入口");
            ModuleManagementUi.status(ui, this);
            ui.title("功能设置");
            ui.row("截屏", "PNG · 保存位置与操作", "layers",
                    () -> startActivity(new Intent(this, CaptureScreenshotActivity.class)));
            ui.row("录屏", "码率与画质 · 最长 3 分钟", "layers",
                    () -> startActivity(new Intent(this, CaptureOptionsActivity.class).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)));
            ui.value("快捷操作", "短按执行；长按打开 OWOrnate 对应页面");
            ui.value("录屏格式", "原屏 H.264 MP4 · 无声 · 帧率跟随屏幕");
            ui.value("模块版本", "运行组件 v2 · 控制中心接入 v13");
            managementUi.actions(ui, this, this::render);
        });
    }
}
