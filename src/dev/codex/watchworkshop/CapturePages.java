package dev.codex.watchworkshop;

import android.content.Intent;

/**
 * 截屏与录屏：模块状态与首页、发现列表入口。
 *
 * <p>状态只投影 {@link RuntimeStateStore}；接入配置仍在独立的
 * {@link CaptureSetupActivity}。</p>
 */
final class CapturePages {
    private final MainActivity a;
    private StatusObservation<CaptureModuleState> observation;
    private CaptureModuleState state;
    private String error = "";
    private boolean refreshing;

    CapturePages(MainActivity activity) {
        a = activity;
        sync();
    }

    boolean known() { return state != null; }
    boolean installed() { return state != null && state.installed(); }
    /** 只有确认完成的 available 才算可安装；读取失败仍保留上次状态。 */
    boolean available() {
        return state != null && "available".equals(state.kind) && !failed()
                && observation.confirmedAt > 0;
    }
    boolean failed() { return !error.isEmpty(); }
    String error() { return error; }
    boolean refreshing() { return refreshing; }

    void sync() {
        observation = RuntimeStateStore.capture(a);
        state = observation.value;
        error = observation.error == null ? "" : observation.error;
    }

    String statusText() {
        if (failed()) return state == null ? error : "上次：" + state.message() + " · 本次读取失败";
        if (refreshing && state == null) return "正在读取状态…";
        if (state == null) return "状态待检查";
        if (observation.confirmedAt == 0 || refreshing) return "上次：" + state.message();
        return state.message();
    }

    String discoverLine() {
        if (failed()) return statusText();
        if (installed()) return state.kind.equals("ready") ? "已安装 · 控制中心截屏与录屏" : statusText();
        if (available()) return "内置 · 可离线接入";
        return statusText();
    }

    void homeRow() {
        a.ui.row("截屏与录屏", statusText(), "scan", this::open);
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
        MonetPages.IO.execute(() -> {
            String problem = "";
            try {
                WidgetHostManager.captureState(a.getApplicationContext());
            } catch (Exception e) {
                problem = WatchActivity.message(e);
            }
            final String message = problem;
            a.runOnUiThread(() -> {
                if (a.destroyed()) return;
                refreshing = false;
                sync();
                if (error.isEmpty() && !message.isEmpty()) error = message;
                a.render();
            });
        });
    }
}
