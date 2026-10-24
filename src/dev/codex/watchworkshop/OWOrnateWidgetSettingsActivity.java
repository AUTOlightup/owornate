package dev.codex.watchworkshop;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.IOException;

/**
 * OWOrnate 信息小组件设置页。所有信息卡片共用一份显示设置；三个显示项点了就生效，页首预览同步变化。
 *
 * <p>本页不改动优化运行状态，也不自动轮询：只有用户点“读取最新状态”时才在后台调用一次
 * {@link ModuleManager#read}（与莫奈读写同在 {@link DeviceQueues#GENERAL}），缓存与卡片的更新由该方法负责。
 */
public final class OWOrnateWidgetSettingsActivity extends WatchActivity {
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged = (store, key) -> {
        if (!destroyed()) render();
    };

    private boolean reading;
    private boolean readFailed;
    private boolean showStatus;
    private boolean showTheme;
    private boolean showVersion;
    private String error = "";
    private String readNote = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!claimCard(OWOrnateWidgetProvider.class)) return;
        observeWhileStarted(RuntimeStateStore.preferences(this), runtimeChanged);
        loadOptions();
        if (state != null) remember("edit", state.getInt("scroll", 0));
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (destroyed() || !claimCard(OWOrnateWidgetProvider.class)) return;
        error = "";
        readNote = "";
        readFailed = false;
        loadOptions();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("scroll", scrollY());
    }

    @Override public void onBackPressed() {
        finish();
    }

    private void loadOptions() {
        OWOrnateWidgetStore.Options stored = OWOrnateWidgetStore.options(this);
        showStatus = stored.showStatus;
        showTheme = stored.showTheme;
        showVersion = stored.showVersion;
    }

    /** 切换一项后立即落盘并刷新卡片；失败时回到已保存的值并提示。 */
    private void apply(boolean status, boolean theme, boolean version) {
        try {
            OWOrnateWidgetStore.saveOptions(this, new OWOrnateWidgetStore.Options(status, theme, version));
            OWOrnateWidgetProvider.updateAll(this);
            error = "";
        } catch (IOException failure) {
            error = "没有保存成功：" + message(failure);
        }
        loadOptions();
        render();
    }

    private void readLatest() {
        if (reading) return;
        reading = true;
        readNote = "";
        readFailed = false;
        render();
        inBackground(DeviceQueues.GENERAL, () -> ModuleManager.read(getApplicationContext()), (state, problem) -> {
            reading = false;
            if (problem != null) {
                readFailed = true;
                readNote = "读取未完成：" + message(problem);
            } else if (state.installed() && state.activeKnown) {
                readNote = "已确认：莫奈" + (state.active ? "已启用。" : "已停用。");
            } else if ("available".equals(state.kind)) {
                readNote = "已确认：莫奈未安装。";
            } else {
                readFailed = true;
                readNote = "未确认：" + state.message;
            }
            render();
        });
    }

    private void render() {
        rebuild("edit", this::edit);
    }

    private void edit() {
        ui.header("OWOrnate 信息", false, this::onBackPressed, null);
        preview(() -> OWOrnateWidgetProvider.render(this, NO_CARD));
        if (!error.isEmpty()) ui.note(error, "danger");
        ui.title("卡片显示项");
        ui.toggle("显示状态", "卡片显示上次确认的状态", showStatus,
                () -> apply(!showStatus, showTheme, showVersion));
        ui.toggle("显示主题", "卡片显示上次确认的配色", showTheme,
                () -> apply(showStatus, !showTheme, showVersion));
        ui.toggle("显示版本", "卡片显示 OWOrnate 版本", showVersion,
                () -> apply(showStatus, showTheme, !showVersion));
        ui.note("切换后立即生效。状态和主题与管理器共用上次确认结果；不会自动轮询手表。");
        ui.button(reading ? "正在读取…" : "读取最新状态", false, !reading, this::readLatest);
        if (!readNote.isEmpty()) ui.note(readNote, readFailed ? "danger" : "");
    }
}
