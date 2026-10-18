package dev.codex.watchworkshop;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/**
 * OWOrnate 信息小组件设置页。所有信息卡片共用一份显示设置；三个显示项点了就生效，页首预览同步变化。
 *
 * <p>本页不改动优化运行状态，也不自动轮询：只有用户点“读取最新状态”时才在后台调用一次
 * {@link ModuleManager#read}，缓存与卡片的更新由该方法负责。
 */
public final class OWOrnateWidgetSettingsActivity extends WatchActivity {

    /** 与莫奈读写共用队列；后台线程只调 ModuleManager，不访问任何 view。 */
    private static final ExecutorService io = MonetPages.IO;
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged = (store, key) -> {
        if (!destroyed()) render();
    };

    @Override protected void onStart() {
        super.onStart();
        RuntimeStateStore.preferences(this).registerOnSharedPreferenceChangeListener(runtimeChanged);
    }

    @Override protected void onStop() {
        RuntimeStateStore.preferences(this).unregisterOnSharedPreferenceChangeListener(runtimeChanged);
        super.onStop();
    }

    private boolean reading, readFailed;
    private int card = NO_CARD;
    private boolean showStatus, showTheme, showVersion;
    private String error = "", readNote = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!resolveTarget()) return;
        loadOptions();
        if (state != null) remember("edit", state.getInt("scroll", 0));
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (destroyed() || !resolveTarget()) return;
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

    /** 带了卡片 id 时必须是信息卡片，否则不写数据直接结束；管理器直接打开时不带 id。 */
    private boolean resolveTarget() {
        card = cardTarget(OWOrnateWidgetProvider.class);
        if (card == REJECTED) {
            setResult(RESULT_CANCELED);
            finish();
            return false;
        }
        acceptCard(card);
        return true;
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
        io.execute(() -> {
            String report;
            boolean confirmed;
            try {
                ModuleState state = ModuleManager.read(getApplicationContext());
                if (state.installed() && state.activeKnown) {
                    report = "已确认：莫奈" + (state.active ? "已启用。" : "已停用。");
                    confirmed = true;
                } else if ("available".equals(state.kind)) {
                    report = "已确认：莫奈未安装。";
                    confirmed = true;
                } else {
                    report = "未确认：" + state.message;
                    confirmed = false;
                }
            } catch (Exception problem) {
                report = "读取未完成：" + message(problem);
                confirmed = false;
            }
            final String text = report;
            final boolean ok = confirmed;
            runOnUiThread(() -> {
                if (destroyed()) return;
                reading = false;
                readFailed = !ok;
                readNote = text;
                render();
            });
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
