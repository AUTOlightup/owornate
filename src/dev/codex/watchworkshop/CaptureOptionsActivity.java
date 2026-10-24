package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * 录屏设置页：插件声明的三档画质与开始按钮。控制中心短按录屏（未在录制时）和长按都打开这里。
 *
 * <p>首帧不等真实会话：先按投影显示，同时在录制队列上确认一次；没确认前不能开始新录制。
 * 点“开始录屏”后本页先退出，窗口消失后再等一小会儿才开始，录下来的第一帧就是原来的界面。
 */
public final class CaptureOptionsActivity extends WatchActivity {
    /** 窗口退出后再等这么久才开始录制；服务端不再额外等待。 */
    private static final int START_DELAY_MS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PluginManifest.Setting setting;
    private int[] rates = new int[0];
    private String[] rateLabels = new String[0];
    private TextView[] quality = new TextView[0];
    private int bitrate;
    private int screenWidth;
    private int screenHeight;
    private boolean pendingStart;
    private boolean reading;
    private boolean statusConfirmed;
    private boolean details;
    private boolean startedBefore;
    private String readError = "";
    private String settingError = "";
    private String saveError = "";
    private TextView qualityTitle, summary, problem, primary;
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged = (store, key) -> {
        if (!destroyed() && !isFinishing() && CaptureManager.statusKey(key)) updateState();
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener settingChanged = (store, key) -> {
        if (!destroyed() && !isFinishing() && !pendingStart) reloadStoredChoice();
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        observeWhileStarted(CaptureManager.preferences(this), runtimeChanged);
        observeWhileStarted(CaptureSettings.preferences(this), settingChanged);
        reloadQuality();
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        details = saved != null && saved.getBoolean("details");
        if (saved != null) remember(details ? "record-details" : "record", saved.getInt("scroll", 0));
        body.setPadding(dp(8), dp(4), dp(8), 0);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(8), 0, dp(8), dp(6));
        primary = new WorkshopUi(this, footer).button("正在确认状态…", true, this::primaryAction);
        pinBelowContent(footer);
        showPage();
    }

    @Override protected void onStart() {
        super.onStart();
        if (startedBefore) {
            boolean structureChanged = reloadQuality();
            if (structureChanged && !details) {
                showPage();
            } else {
                if (!details) updateQuality();
                updateState();
            }
        } else {
            startedBefore = true;
        }
        refreshStatus();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putBoolean("details", details);
        saved.putInt("scroll", scrollY());
    }

    @Override protected void onStop() {
        super.onStop();
        if (!pendingStart) return;
        pendingStart = false;
        final Context app = getApplicationContext();
        final int selected = bitrate;
        handler.postDelayed(() -> CaptureService.startRecording(app, selected), START_DELAY_MS);
    }

    @Override public void onBackPressed() {
        if (details) {
            details = false;
            showPage();
        } else {
            cancel();
        }
    }

    /** 在录制队列上确认真实会话；确认之前不能开始新录制。 */
    private void refreshStatus() {
        if (reading) return;
        reading = true;
        statusConfirmed = false;
        readError = "";
        updateState();
        CaptureService.refreshStatus(this, error -> {
            if (destroyed() || isFinishing()) return;
            reading = false;
            statusConfirmed = error == null;
            readError = error == null ? "" : WatchActivity.message(error);
            updateState();
        });
    }

    private void primaryAction() {
        if (pendingStart || reading) return;
        if (!statusConfirmed) {
            refreshStatus();
            return;
        }
        if (active()) {
            cancel();
            return;
        }
        if (!settingError.isEmpty()) {
            updateState();
            return;
        }
        // Starting with the displayed default also counts as a saved user choice.
        try {
            CaptureSettings.save(this, bitrate);
        } catch (IOException | RuntimeException failure) {
            saveFailed(failure);
            return;
        }
        saveError = "";
        pendingStart = true;
        updateState();
        setResult(RESULT_OK);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean active() {
        return CaptureManager.activePhase(CaptureManager.lastPhase(this));
    }

    // ------------------------------------------------------------------
    // 页面
    // ------------------------------------------------------------------

    private void showPage() {
        rebuild(details ? "record-details" : "record", () -> {
            ui.header(details ? "录屏说明" : "录屏", false, this::onBackPressed, null);
            problem = ui.text("");
            problem.setTextSize(11);
            problem.setTextColor(WorkshopUi.WARNING);
            if (details) detailsPage();
            else qualityPage();
        });
        updateState();
    }

    private void detailsPage() {
        ui.value("实际分辨率", screenWidth + " × " + screenHeight + " px");
        ui.value("帧率", "可变帧率，随画面更新");
        ui.value("格式与时长", "H.264 MP4 · 无声 · 最长3分钟");
        ui.value("保存位置", "Movies/Screenrecords");
        ui.text("退出设置后开始录制。再次短按控制中心录屏按钮停止，长按打开本页设置。画质会保留到下一次。");
        ui.button("返回画质选择", false, () -> {
            details = false;
            showPage();
        });
    }

    /** 三档画质横排一行，下面是码率说明和“更多说明”。 */
    private void qualityPage() {
        qualityTitle = ui.title(setting == null ? "画质设置" : setting.title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        quality = new TextView[rates.length];
        for (int i = 0; i < rates.length; i++) {
            final int rate = rates[i];
            quality[i] = new WorkshopUi(this, row).button(rateLabels[i], false, () -> choose(rate));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            quality[i].setLayoutParams(lp);
        }
        if (rates.length > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            body.addView(row, lp);
        }
        summary = ui.text("");
        summary.setTextSize(11);
        ui.button("更多说明", false, () -> {
            details = true;
            showPage();
        });
        updateQuality();
    }

    /** 会话变化只改已有控件的文字，不重建页面：选择和滚动位置保持不动。 */
    private void updateState() {
        boolean active = active();
        String label = primaryLabel(active);
        primary.setText(label);
        primary.setContentDescription(label);
        boolean enabled = !pendingStart && !reading && (statusConfirmed || !readError.isEmpty())
                && (active || settingError.isEmpty());
        primary.setEnabled(enabled);
        primary.setAlpha(enabled ? 1f : 0.45f);
        if (!details && summary != null) {
            if (!settingError.isEmpty() && !active) {
                summary.setText("画质设置不可用，暂不能开始录屏");
            } else {
                summary.setText(statusConfirmed && active
                        ? "录制/保存中 · 下一段画质"
                        : (bitrate / 1000000f) + " Mbps · 约" + estimatedMegabytes() + " MB/分");
            }
        }
        StringBuilder errors = new StringBuilder();
        if (!settingError.isEmpty()) appendError(errors, "画质设置读取失败：" + settingError);
        if (!saveError.isEmpty()) appendError(errors, "画质没有保存：" + saveError);
        if (!readError.isEmpty()) {
            appendError(errors, "状态读取失败：" + readError);
        } else if (!CaptureManager.lastError(this).isEmpty()) {
            appendError(errors, CaptureManager.lastError(this));
        }
        problem.setText(errors);
        problem.setVisibility(errors.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private String primaryLabel(boolean active) {
        if (pendingStart) return "正在返回…";
        if (reading || (!statusConfirmed && readError.isEmpty())) return "正在确认状态…";
        if (!statusConfirmed) return "重新读取状态";
        if (active) return "返回原界面";
        return settingError.isEmpty() ? "开始录屏" : "画质设置不可用";
    }

    private void choose(int rate) {
        if (pendingStart || !settingError.isEmpty() || bitrate == rate) return;
        try {
            CaptureSettings.save(this, rate);
        } catch (IOException failure) {
            saveFailed(failure);
            return;
        } catch (RuntimeException failure) {
            saveFailed(failure);
            return;
        }
        bitrate = rate;
        saveError = "";
        updateQuality();
        updateState();
    }

    private void updateQuality() {
        boolean editable = !pendingStart && settingError.isEmpty();
        for (int i = 0; i < quality.length && i < rates.length; i++) {
            boolean selected = bitrate == rates[i];
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(WorkshopUi.BTN_RADIUS));
            background.setColor(selected ? WorkshopUi.ACCENT : WorkshopUi.SOFT);
            quality[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), background, null));
            quality[i].setTextColor(selected ? WorkshopUi.ON_ACCENT : WorkshopUi.FG);
            quality[i].setSelected(selected);
            quality[i].setEnabled(editable);
            quality[i].setAlpha(editable ? 1f : 0.45f);
            quality[i].setContentDescription(quality[i].getText() + "，" + (rates[i] / 1000000f) + " Mbps"
                    + (selected ? "，已选择" : ""));
        }
    }

    /** 重新读取插件声明和保存值；返回 true 表示按钮文字或顺序发生了变化。 */
    private boolean reloadQuality() {
        final QualityState next;
        try {
            next = loadQuality();
        } catch (RuntimeException failure) {
            settingError = message(failure);
            return false;
        }
        boolean structureChanged = !Arrays.equals(rates, next.rates)
                || !Arrays.equals(rateLabels, next.labels);
        setting = next.setting;
        rates = next.rates;
        rateLabels = next.labels;
        bitrate = next.choice;
        settingError = "";
        saveError = "";
        if (qualityTitle != null) qualityTitle.setText(setting.title);
        return structureChanged;
    }

    private QualityState loadQuality() {
        PluginManifest.Setting loaded = CaptureSettings.definition(this);
        int[] values = new int[loaded.options.size()];
        String[] labels = new String[values.length];
        int index = 0;
        for (Map.Entry<String, String> option : loaded.options.entrySet()) {
            values[index] = Integer.parseInt(option.getKey());
            labels[index] = option.getValue();
            index++;
        }
        return new QualityState(loaded, values, labels, CaptureSettings.read(this, loaded));
    }

    /** 偏好变化或保存结果未确认时回读实际选择；只刷新控件，不重建页面。 */
    private void reloadStoredChoice() {
        if (setting == null || !settingError.isEmpty()) {
            boolean structureChanged = reloadQuality();
            if (structureChanged && !details) showPage();
            else {
                if (!details) updateQuality();
                updateState();
            }
            return;
        }
        try {
            bitrate = CaptureSettings.read(this, setting);
            settingError = "";
        } catch (RuntimeException failure) {
            settingError = message(failure);
        }
        if (!details) updateQuality();
        updateState();
    }

    private void saveFailed(Exception failure) {
        saveError = message(failure);
        reloadStoredChoice();
    }

    private static void appendError(StringBuilder errors, String message) {
        if (errors.length() > 0) errors.append('\n');
        errors.append(message);
    }

    private static final class QualityState {
        final PluginManifest.Setting setting;
        final int[] rates;
        final String[] labels;
        final int choice;

        QualityState(PluginManifest.Setting setting, int[] rates, String[] labels, int choice) {
            this.setting = setting;
            this.rates = rates;
            this.labels = labels;
            this.choice = choice;
        }
    }

    private String estimatedMegabytes() {
        if (bitrate == CaptureManager.LOW_BITRATE) return "4.5";
        return bitrate == CaptureManager.HIGH_BITRATE ? "18" : "9";
    }
}
