package dev.codex.watchworkshop;

import android.content.Context;
import android.content.Intent;

/**
 * 截屏与录屏：模块状态与首页、发现列表入口。
 *
 * <p>状态只投影 {@link RuntimeStateStore}；接入配置仍在独立的
 * {@link CaptureSetupActivity}。</p>
 */
final class CapturePages implements ModuleStatusView {
    private final MainActivity a;
    private StatusObservation<CaptureModuleState> observation;
    private CaptureModuleState state;
    private String error = "";
    private boolean refreshing;

    CapturePages(MainActivity activity) {
        a = activity;
        sync();
    }

    @Override public boolean known() { return state != null; }
    @Override public boolean installed() { return state != null && state.installed(); }
    /** 只有确认完成的 available 才算可安装；读取失败仍保留上次状态。 */
    @Override public boolean available() {
        return state != null && "available".equals(state.kind) && !failed()
                && observation.confirmedAt > 0;
    }
    @Override public boolean failed() { return !error.isEmpty(); }
    boolean needsSetup() { return state != null && "setup".equals(state.kind) && !failed(); }
    boolean incompatible() { return state != null && "conflict".equals(state.kind) && !failed(); }
    @Override public String error() { return error; }
    @Override public boolean refreshing() { return refreshing; }
    boolean confirmed() { return observation != null && observation.confirmedAt > 0; }

    void sync() {
        observation = RuntimeStateStore.capture(a);
        state = observation.value;
        error = observation.error;
    }

    @Override public String statusText() {
        if (failed()) return state == null ? error : "上次：" + state.message() + " · 本次读取失败";
        if (refreshing && state == null) return "正在读取状态…";
        if (state == null) return "状态待检查";
        if (observation.confirmedAt == 0 || refreshing) return "上次：" + state.message();
        return state.message();
    }

    @Override public String discoverLine() {
        if (failed()) return statusText();
        if (installed()) return statusText();
        if (available()) return "内置 · 可离线接入";
        return statusText();
    }

    @Override public boolean showOnHome() {
        return installed() || failed() || (state != null
                && ("setup".equals(state.kind) || "conflict".equals(state.kind)));
    }

    void open() {
        a.startActivity(new Intent(a, CaptureSetupActivity.class));
    }

    /** 先同步共享观察值，再按 30 秒新鲜度决定是否读取；失败保留上次值。 */
    void refresh(boolean force) {
        if (a.destroyed()) return;
        sync();
        if (a.working() || refreshing) return;
        if (!force && observation.fresh(System.currentTimeMillis(), MainActivity.FRESH_MS)) return;
        refreshing = true;
        a.render();
        Context app = a.getApplicationContext();
        a.inBackground(DeviceQueues.GENERAL, () -> WidgetHostManager.captureState(app), (read, problem) -> {
            refreshing = false;
            sync();
            if (problem != null && error.isEmpty()) error = WatchActivity.message(problem);
            a.render();
        });
    }
}
