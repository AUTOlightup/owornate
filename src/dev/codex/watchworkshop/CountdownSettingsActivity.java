package dev.codex.watchworkshop;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.time.LocalDate;

/**
 * 倒计时小组件设置页。管理器里的“倒计时”和负一屏上的卡片点击都走这里；所有倒计时卡片共用一份配置。
 *
 * <p>数据来源二选一：自己设置（标题用键盘，日期和时刻用滚轮），或纪念日模式——显示系统快应用
 * “纪念日”里的某一条，默认自动选最近的日子；每年重复和进度按条目分别记。页首的预览随草稿实时变化。
 *
 * <p>保存：和莫奈、信息卡片一样不需要点保存。离开本页（返回、右滑、按键回表盘）时，用户动过且与
 * 已保存内容不同就写入 {@link CountdownStore}，再重画卡片、请 {@link WidgetClock} 重排下一次刷新。
 * 只是打开看一眼不会写入任何东西。标题可以留空，卡片上显示“倒计时”。
 */
public final class CountdownSettingsActivity extends WatchActivity {

    // 草稿
    private String title = "";
    private LocalDate target = LocalDate.now();
    private boolean timeOn;
    private int hour = 9;
    private int minute;
    private boolean yearly;
    private boolean progress;
    private boolean startCustom;
    private LocalDate startDate = LocalDate.now();
    /** 用户动过自己设置的某一项；只有动过才会在离开时保存，打开看一眼不会把今天写成目标日期。 */
    private boolean touched;

    /** 已保存的配置：“有没有修改”的比较基线，也提供不进草稿的元数据。 */
    private CountdownModel.Config stored = CountdownModel.Config.blank();

    /** 数据来源（自己设置 / 纪念日）的已保存值和草稿；和自己设置的内容分开记“动过没有”。 */
    private CountdownStore.Source storedSource = new CountdownStore.Source(false, "", null);
    private CountdownStore.Source source = storedSource;
    private boolean sourceTouched;

    private EditText titleField;
    private FrameLayout previewBox;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable previewTask = this::updatePreview;
    /** 纪念日读取开始或结束：纪念日模式下重建页面（自己设置时不动，免得打断标题输入）。 */
    private final Runnable mirrorChanged = () -> {
        if (!destroyed() && source.anniversary) render();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!claimCard(CountdownWidgetProvider.class)) return;
        loadStored();
        if (state != null) restore(state);
        AnniversarySync.listen(mirrorChanged);
        render();
    }

    /** 回到本页（含从纪念日回来）时，纪念日模式下重读一次。 */
    @Override protected void onResume() {
        super.onResume();
        if (source.anniversary) AnniversarySync.refresh(this, true);
    }

    /** 已在前台时再次被打开：先保存手上的修改，再按新意图重新校验。 */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        commit();
        setIntent(intent);
        if (destroyed() || !claimCard(CountdownWidgetProvider.class)) return;
        loadStored();
        render();
    }

    @Override protected void onPause() {
        super.onPause();
        commit();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        captureTitle();
        state.putInt("scroll", scrollY());
        state.putString("title", title);
        state.putString("target", target.toString());
        state.putBoolean("timeOn", timeOn);
        state.putInt("hour", hour);
        state.putInt("minute", minute);
        state.putBoolean("yearly", yearly);
        state.putBoolean("progress", progress);
        state.putBoolean("startCustom", startCustom);
        state.putString("startDate", startDate.toString());
        state.putBoolean("touched", touched);
        state.putBoolean("anniversary", source.anniversary);
        state.putString("pick", source.pick);
        state.putSerializable("flags", new java.util.HashMap<>(source.flags));
        state.putBoolean("sourceTouched", sourceTouched);
    }

    @SuppressWarnings("unchecked")
    private void restore(Bundle state) {
        remember("edit", state.getInt("scroll", 0));
        title = state.getString("title", title);
        try {
            target = LocalDate.parse(state.getString("target", target.toString()));
            startDate = LocalDate.parse(state.getString("startDate", startDate.toString()));
        } catch (RuntimeException ignored) {
            // 日期坏了就保留从存储里读到的值。
        }
        timeOn = state.getBoolean("timeOn", timeOn);
        hour = state.getInt("hour", hour);
        minute = state.getInt("minute", minute);
        yearly = state.getBoolean("yearly", yearly);
        progress = state.getBoolean("progress", progress);
        startCustom = state.getBoolean("startCustom", startCustom);
        touched = state.getBoolean("touched", touched);
        java.io.Serializable flags = state.getSerializable("flags");
        source = new CountdownStore.Source(state.getBoolean("anniversary", source.anniversary),
                state.getString("pick", source.pick),
                flags instanceof java.util.Map ? (java.util.Map<String, Integer>) flags : source.flags);
        sourceTouched = state.getBoolean("sourceTouched", sourceTouched);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(previewTask);
        AnniversarySync.unlisten(mirrorChanged);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        finish();
    }

    private void loadStored() {
        stored = CountdownStore.config(this);
        title = stored.title;
        LocalDate today = LocalDate.now();
        target = parseDate(stored.target, today);
        String time = CountdownModel.normalizeTime(stored.time);
        timeOn = !time.isEmpty();
        hour = timeOn ? Integer.parseInt(time.substring(0, 2)) : 9;
        minute = timeOn ? Integer.parseInt(time.substring(3, 5)) : 0;
        yearly = stored.yearly;
        progress = stored.progress;
        startCustom = !stored.start.trim().isEmpty();
        startDate = parseDate(stored.start, parseDate(stored.created, today));
        touched = false;
        storedSource = CountdownStore.source(this);
        source = storedSource;
        sourceTouched = false;
    }

    private static LocalDate parseDate(String raw, LocalDate fallback) {
        String clean = CountdownModel.normalizeDate(raw == null ? "" : raw);
        if (clean.isEmpty()) return fallback;
        try {
            return LocalDate.parse(clean);
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    /** 把输入框里的标题收进草稿；重建页面和保存之前必须调用。 */
    private void captureTitle() {
        if (titleField != null) title = titleField.getText().toString();
    }

    private CountdownModel.Config draft() {
        String stamp = CountdownModel.normalizeDate(stored.created);
        if (stamp.isEmpty()) stamp = LocalDate.now().toString();
        return new CountdownModel.Config(CountdownModel.title(title), target.toString(),
                timeOn ? String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute) : "",
                yearly, progress, progress && startCustom ? startDate.toString() : "", stamp, stored.zone);
    }

    private boolean dirty() {
        CountdownModel.Config now = draft();
        return !now.title.equals(CountdownModel.title(stored.title))
                || !now.target.equals(CountdownModel.normalizeDate(stored.target))
                || !now.time.equals(CountdownModel.normalizeTime(stored.time))
                || now.yearly != stored.yearly || now.progress != stored.progress
                || !now.start.equals(CountdownModel.normalizeDate(stored.start));
    }

    /**
     * 离开时保存：自己设置的内容和数据来源各自“动过且不同”才写；失败时提示一次，不拦住离开。
     * 切换模式时顺带授予或撤回“使用情况访问”（纪念日模式的亮屏检查要用）。
     */
    private void commit() {
        captureTitle();
        boolean manual = touched && dirty();
        boolean picked = sourceTouched && !source.same(storedSource);
        if (!manual && !picked) return;
        try {
            if (manual) CountdownStore.save(this, draft());
            if (picked) CountdownStore.saveSource(this, source);
        } catch (IOException failure) {
            Toast.makeText(this, "倒计时没有保存：" + message(failure), Toast.LENGTH_LONG).show();
            return;
        }
        stored = CountdownStore.config(this);
        storedSource = CountdownStore.source(this);
        touched = false;
        sourceTouched = false;
        // 已经对上时 usageAccess 什么也不做；上次授予失败的话这里会再试一次。
        if (picked) AnniversarySync.usageAccess(this, storedSource.anniversary);
        CountdownWidgetProvider.updateAll(this);
        WidgetClock.schedule(this);
    }

    private void changed() {
        touched = true;
        // 预览整张重绘较重：滚动途中连续触发时合并，停下后再画一次。
        handler.removeCallbacks(previewTask);
        handler.postDelayed(previewTask, 120);
    }

    /** 开关切换会增减下面的行，所以重建页面；滚轮和标题只更新预览。 */
    private void flip(Runnable change) {
        captureTitle();
        change.run();
        touched = true;
        render();
    }

    private void render() {
        captureTitle();
        rebuild("edit", this::edit);
    }

    private void edit() {
        titleField = null;
        ui.header("倒计时", false, this::onBackPressed, null);
        previewBox = preview(() -> CountdownWidgetProvider.preview(this, source, draft()));

        ui.title("数据来源");
        ui.choice("手动设置", "", !source.anniversary, () -> pickSource(false));
        ui.choice("纪念日", "读取纪念日应用", source.anniversary, () -> pickSource(true));
        if (source.anniversary) {
            anniversary();
        } else {
            manual();
        }
    }

    private void manual() {
        ui.title("标题");
        titleField = field("最多 20 字，留空显示“倒计时”", title);
        titleField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                title = s.toString();
                changed();
            }
        });

        ui.title("目标日期");
        Wheels.date(this, ui, target, date -> {
            target = date;
            changed();
        });

        ui.toggle("设置时刻", timeOn ? "到点前显示倒计时" : "只按自然日算天数", timeOn,
                () -> flip(() -> timeOn = !timeOn));
        if (timeOn) {
            Wheels.time(this, ui, hour, minute, (h, m) -> {
                hour = h;
                minute = m;
                changed();
            });
        }
        ui.toggle("每年重复", "过了当天自动换到明年", yearly, () -> flip(() -> yearly = !yearly));
        ui.toggle("显示进度", "起始日到目标日的百分比", progress, () -> flip(() -> progress = !progress));
        if (progress) {
            ui.toggle("自定义起始日", startCustom ? "从下面的日期起算" : "从第一次保存那天起算",
                    startCustom, () -> flip(() -> startCustom = !startCustom));
            if (startCustom) {
                Wheels.date(this, ui, startDate, date -> {
                    startDate = date;
                    changed();
                });
            }
        }
        ui.caption("返回时自动保存并更新卡片。");
    }

    /** 纪念日模式：读取状态、选哪一条、这一条的显示方式，以及打开纪念日。 */
    private void anniversary() {
        AnniversaryStore.Snapshot mirror = AnniversaryStore.snapshot(this);
        boolean reading = AnniversarySync.reading();
        LocalDate today = LocalDate.now();
        if (mirror.entries == null) {
            if (reading || AnniversaryStore.UNKNOWN.equals(mirror.state)) {
                ui.note("正在读取纪念日…");
            } else if (AnniversaryStore.MISSING.equals(mirror.state)) {
                ui.note("没有找到纪念日的数据。先打开纪念日添加一个日子，回来后会自动读到。", "warning");
            } else {
                ui.note("读不到纪念日数据：" + mirror.error, "warning");
                ui.button("重新读取", false, !reading, () -> AnniversarySync.refresh(this, true));
            }
        } else {
            if (!reading && !AnniversaryStore.OK.equals(mirror.state)) {
                ui.note("这次没读到最新数据，下面是上次读到的内容。", "warning");
                ui.button("重新读取", false, true, () -> AnniversarySync.refresh(this, true));
            }
            if (mirror.entries.isEmpty()) {
                ui.note("纪念日里还没有日子。");
            } else {
                entries(mirror.entries, today);
            }
        }
        ui.row("打开纪念日", "在纪念日里添加或删除日子", "clock", () -> AnniversarySync.open(this));
        ui.note("只读取纪念日的数据，不会改动它。在纪念日里改过之后，亮屏或回到这一页时自动更新。返回时自动保存。");
    }

    private void entries(java.util.List<AnniversaryData.Entry> list, LocalDate today) {
        ui.title("显示哪一个");
        AnniversaryData.Entry auto = AnniversaryData.pick(list, "", source::yearly, today);
        ui.choice("自动", auto == null ? "最近的一个日子" : "最近的日子：" + auto.title,
                source.pick.isEmpty(), () -> choose(""));
        boolean found = source.pick.isEmpty();
        for (AnniversaryData.Entry entry : AnniversaryData.ordered(list, source::yearly, today)) {
            if (entry.id.equals(source.pick)) found = true;
            ui.choice(entry.title, line(entry), entry.id.equals(source.pick), () -> choose(entry.id));
        }
        if (!found) ui.note("之前选的日子已在纪念日里删除，请重新选一个。", "warning");

        AnniversaryData.Entry shown = AnniversaryData.pick(list, source.pick, source::yearly, today);
        if (shown == null) return;
        ui.title("“" + shown.title + "”的显示方式");
        ui.toggle("每年重复", "过了当天自动换到明年，适合生日", source.yearly(shown.id),
                () -> flag(shown.id, CountdownStore.Source.YEARLY));
        ui.toggle("显示进度", "从在纪念日里添加那天算起", source.progress(shown.id),
                () -> flag(shown.id, CountdownStore.Source.PROGRESS));
    }

    /** 列表里每一条的副标题：“还有 234 天 · 2027年6月7日”，和卡片的算法一致。 */
    private String line(AnniversaryData.Entry entry) {
        CountdownModel.Frame frame = CountdownModel.frame(
                AnniversaryData.config(entry, source.yearly(entry.id), false),
                System.currentTimeMillis(), java.time.ZoneId.systemDefault());
        String count = frame.countText();
        return frame.detail.isEmpty() ? count : count + " · " + frame.detail;
    }

    private void pickSource(boolean anniversary) {
        if (source.anniversary == anniversary) return;
        captureTitle();
        source = source.with(anniversary, source.pick);
        sourceTouched = true;
        if (anniversary) AnniversarySync.refresh(this, true);
        render();
    }

    private void choose(String id) {
        if (source.pick.equals(id)) return;
        source = source.with(true, id);
        sourceTouched = true;
        render();
    }

    private void flag(String id, int bit) {
        boolean on = bit == CountdownStore.Source.YEARLY ? source.yearly(id) : source.progress(id);
        source = source.flag(id, bit, !on);
        sourceTouched = true;
        render();
    }

    private void updatePreview() {
        if (previewBox != null && !destroyed()) {
            fillPreview(previewBox, () -> CountdownWidgetProvider.preview(this, source, draft()));
        }
    }

    /** 单行输入框：13sp、44dp 命中高度。 */
    private EditText field(String hint, String value) {
        EditText input = ui.input(hint, value, WorkshopUi.BODY_SP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(titleLimit());
        input.setMinHeight(dp(WorkshopUi.HIT));
        return input;
    }

    /** 标题按 code point 限长；用 UTF-16 长度会在 emoji 之后误判。 */
    private static InputFilter[] titleLimit() {
        InputFilter limit = (source, start, end, dest, dstart, dend) -> {
            String next = dest.subSequence(0, dstart).toString()
                    + source.subSequence(start, end)
                    + dest.subSequence(dend, dest.length());
            return next.codePointCount(0, next.length()) <= CountdownModel.TITLE_MAX_CODEPOINTS
                    ? null : "";
        };
        return new InputFilter[]{limit};
    }
}
