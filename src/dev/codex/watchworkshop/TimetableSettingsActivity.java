package dev.codex.watchworkshop;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 课表页：负一屏课表卡片点进来就是这里，管理器的“课表”也打开这里。所有课表卡片共用一份课表。
 *
 * <p>有课表时进总览（卡片预览、按周翻页、七天选择、当天课程），没有时进导入页。带了卡片 id 但它不是
 * 课表卡片时不写任何数据，返回 RESULT_CANCELED 并结束。
 *
 * <p>读取边界：文件选择只走 {@link Intent#ACTION_OPEN_DOCUMENT}，读取与解析都在这一个单线程
 * 执行器里完成，单个 UTF-8 文件最多 1 MB；读不到文件选择器时提示改用“粘贴 JSON”入口。
 * 读取或解析失败只在页面上报错，不写任何数据，已保存的课表保持原样。
 *
 * <p>写入边界：读到的 JSON 先作为草稿留在内存里预览（来源、课程条目、开学日期），只有点
 * “保存课表”才调用 {@link TimetableStore#save}，随后 {@link TimetableWidgetProvider#updateAll}
 * 重画卡片并请 {@link WidgetClock} 重排下一次刷新。取消、返回、右滑都不写课表，也不会自动覆盖。
 *
 * <p>时区首版固定跟随手表：保存时传给 {@link TimetableStore#save} 的 zone 固定是 "system"，
 * 页面只展示当前生效的时区，不提供时区选择。
 */
public final class TimetableSettingsActivity extends WatchActivity {

    /** 单个课表 JSON 的上限，导入时提前拦下，与保存时的校验是同一个值。 */
    private static final int MAX_JSON_BYTES = TimetableStore.MAX_JSON_BYTES;
    /** 导入预览里最多列出的课程条数，避免一次渲染上百行。 */
    private static final int PREVIEW_LIMIT = 20;
    private static final int PICK_REQUEST = 4101;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** 读取文件、解析 JSON 与保存的专用单线程；后台任务不访问任何 view。 */
    private static final ExecutorService io = Executors.newSingleThreadExecutor();

    private String page = "";
    private boolean reading;
    private boolean pickerMissing;
    private boolean pasteLost;

    /** 已读取但尚未保存的课表：raw 可能接近 1 MB，只留在成员里，不进 Bundle。 */
    private String pendingRaw;
    private String pendingSource = "";
    private TimetableData pending;
    private boolean pendingReady;

    private String dateDraft = "";
    private String pasteDraft = "";

    /** 已保存的课表，用于总览页和“是否已导入”的判断。 */
    private TimetableData current;
    private String readFailure = "";

    private String error = "";

    /** 总览页当前选中的那一天；null 表示今天。 */
    private LocalDate shown;
    private EditText pasteField;

    /** 后台读到并解析好的一份课表：原文、来源名和解析结果。 */
    private static final class Import {
        final String raw, source;
        final TimetableData data;

        Import(String raw, String source, TimetableData data) {
            this.raw = raw;
            this.source = source;
            this.data = data;
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!claimCard(TimetableWidgetProvider.class)) return;
        if (state != null) restore(state);
        loadCurrent();
        settle();
        render();
    }

    /** 已在前台时再次被打开：按新意图重新校验目标，并丢掉上一轮未保存的导入草稿。 */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (destroyed()) return;
        if (!claimCard(TimetableWidgetProvider.class)) return;
        clearPending();
        pasteLost = false;
        shown = null;
        error = "";
        page = "";
        loadCurrent();
        settle();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        capture();
        state.putString("page", page);
        state.putInt("scroll", scrollY());
        state.putString("dateDraft", dateDraft);
        state.putBoolean("pendingReady", pendingReady);
        // 课表 JSON 可能接近 1 MB，不能进 Bundle；只记下粘贴框里原本有没有内容。
        state.putBoolean("pastePresent", !pasteDraft.isEmpty());
    }

    /** 逐级返回：预览→导入→总览→结束本页。 */
    @Override public void onBackPressed() {
        if (page.equals("preview")) {
            openImport();
            return;
        }
        if (page.equals("import") && hasTable()) {
            page = "today";
            error = "";
            render();
            return;
        }
        finish();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_REQUEST) return;
        // 用户放弃了选择：留在导入页，不动任何数据。
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        readFile(data.getData());
    }

    // ------------------------------------------------------------------
    // 已保存课表与页面状态
    // ------------------------------------------------------------------

    /** 读一次已保存的课表；读不出来时只记原因，让页面提示，不写任何数据。 */
    private void loadCurrent() {
        current = null;
        readFailure = "";
        try {
            current = TimetableStore.read(this);
        } catch (Exception failure) {
            readFailure = message(failure);
        }
    }

    /** “已导入”只在拿到开学日期时成立：没有日期就无法推算周次和当天课程。 */
    private boolean hasTable() {
        return current != null && current.semesterStart != null;
    }

    /** 兜底决定页面：有课表看今日课程，没有就进导入。 */
    private void settle() {
        if (pendingReady && pendingRaw == null) {
            // 进程被系统回收后重开：原始 JSON 只留在成员里，这里已经没有了。
            pendingReady = false;
            pending = null;
            page = "import";
            error = "重新打开后导入内容没有保留，请重新选择文件或粘贴 JSON。";
        }
        if (!page.equals("today") && !page.equals("import") && !page.equals("preview")) {
            page = hasTable() ? "today" : "import";
        }
        if (page.equals("preview") && (pending == null || pendingRaw == null)) page = "import";
        if (page.equals("today") && !hasTable()) page = "import";
    }

    /** 恢复重建前的状态。课表是全局一份，卡片在后台被移除也不影响继续导入。 */
    private void restore(Bundle state) {
        page = state.getString("page", page);
        remember(page, state.getInt("scroll", 0));
        dateDraft = state.getString("dateDraft", dateDraft);
        pendingReady = state.getBoolean("pendingReady", false);
        pasteLost = state.getBoolean("pastePresent", false) && pasteDraft.isEmpty();
    }

    /** 丢掉未保存的导入草稿。 */
    private void clearPending() {
        pendingRaw = null;
        pendingSource = "";
        pending = null;
        pendingReady = false;
    }

    // ------------------------------------------------------------------
    // 导入：文件选择、粘贴、读取与解析
    // ------------------------------------------------------------------

    private void openImport() {
        capture();
        error = "";
        page = "import";
        render();
    }

    /**
     * 选文件：只读一个 JSON/文本文件，不改写它。有些文件管理器按后缀过滤，所以除了
     * application/json 也允许 text/plain 和任意类型，避免 .json 选不中。
     */
    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/json", "text/plain", "*/*"})
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"));
        try {
            startActivityForResult(intent, PICK_REQUEST);
        } catch (ActivityNotFoundException missing) {
            pickerMissing = true;
            error = "这台设备没有可用的文件选择器，请用下面的“粘贴 JSON”导入。";
            render();
        }
    }

    /** 粘贴入口：和文件导入共用大小校验、后台解析与预览。 */
    private void parsePasted() {
        if (reading) return;
        capture();
        final String raw = pasteDraft;
        if (raw.trim().isEmpty()) {
            error = "粘贴框还是空的：请先把课表 JSON 全文粘贴进去。";
            render();
            return;
        }
        if (utf8Length(raw) > MAX_JSON_BYTES) {
            error = "粘贴的内容超过 1 MB，请改用文件导入或先裁剪。";
            render();
            return;
        }
        pasteLost = false;
        reading = true;
        error = "";
        render();
        inBackground(io, () -> new Import(raw, "粘贴内容", parse(raw)), this::finishRead);
    }

    /** 选中的文件在后台线程读取并解析。 */
    private void readFile(final Uri uri) {
        if (reading) return;
        reading = true;
        error = "";
        render();
        inBackground(io, () -> {
            String source = displayName(uri);
            String raw = readBytes(uri);
            return new Import(raw, source, parse(raw));
        }, this::finishRead);
    }

    /** 后台：按已保存的课表补缺省字段来解析；保存时用同一份基线，预览才和保存结果一致。 */
    private TimetableData parse(String raw) throws Exception {
        TimetableData parsed = ShiguangJson.parse(raw, stored());
        if (parsed == null) throw new IOException("没有解析出课表内容");
        return parsed;
    }

    private TimetableData stored() {
        try {
            return TimetableStore.read(getApplicationContext());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 主线程：读取完成后切到预览页，或在导入页报错。 */
    private void finishRead(Import read, Exception problem) {
        reading = false;
        if (problem != null) {
            error = "无法导入（" + message(problem) + "）。已保存的课表保持不变。";
            render();
            return;
        }
        pendingRaw = read.raw;
        pendingSource = read.source;
        pending = read.data;
        pendingReady = true;
        dateDraft = read.data.semesterStart == null ? "" : read.data.semesterStart.toString();
        error = "";
        page = "preview";
        render();
    }

    /** 文件名的可读形式：优先问文档提供方，退回 URI 末段。 */
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Exception ignored) {
            // 提供方不支持查询时退回 URI 末段。
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "课表文件" : last.trim();
    }

    /** 读整个文件，最多 1 MB；超出立刻停下，不继续占内存。 */
    private String readBytes(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("打不开这个文件");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_JSON_BYTES) throw new IOException("文件超过 1 MB");
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭失败不影响已经读到的内容。
            }
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * 只有点“保存课表”才写入：先校验日期与大小，再落盘，然后重画卡片、重排下一次刷新，
     * 最后回到总览页看导入结果。
     */
    private void save() {
        if (reading) return;
        capture();
        if (pendingRaw == null || pending == null) {
            error = "导入内容已经不在了，请重新选择文件或粘贴 JSON。";
            page = "import";
            render();
            return;
        }
        if (utf8Length(pendingRaw) > MAX_JSON_BYTES) {
            fail("课表超过 1 MB，请先裁剪再导入。");
            return;
        }
        final LocalDate start;
        try {
            start = startOverride();
        } catch (IOException invalid) {
            fail(invalid.getMessage());
            return;
        }
        final String raw = pendingRaw;
        final Context app = getApplicationContext();
        reading = true;
        render();
        inBackground(io, () -> {
            TimetableStore.save(app, raw, start, "system");
            TimetableWidgetProvider.updateAll(app);
            WidgetClock.schedule(app);
            return null;
        }, (unused, problem) -> {
            reading = false;
            if (problem != null) {
                fail("保存失败：" + message(problem));
                return;
            }
            clearPending();
            dateDraft = "";
            shown = null;
            error = "";
            loadCurrent();
            page = hasTable() ? "today" : "import";
            remember(page, 0);
            render();
        });
    }

    /**
     * 保存时用的开学日期：调整过就用草稿里的，没调整时为 null（沿用文件里的）。
     * 写法不对，或文件和草稿都没有开学日期时，抛出给用户看的原因。
     */
    private LocalDate startOverride() throws IOException {
        String draft = dateDraft.trim();
        if (draft.isEmpty()) {
            if (pending.semesterStart == null) throw new IOException("这个文件没有开学日期，请先调整第 1 周的日期。");
            return null;
        }
        try {
            return LocalDate.parse(draft);
        } catch (DateTimeParseException bad) {
            throw new IOException("开学日期要写成 yyyy-MM-dd，例如 2026-02-23。");
        }
    }

    private void fail(String message) {
        error = message;
        render();
    }

    // ------------------------------------------------------------------
    // 页面骨架
    // ------------------------------------------------------------------

    private void render() {
        if (destroyed()) return;
        capture();
        rebuild(page, () -> {
            pasteField = null;
            if (page.equals("preview")) {
                previewImport();
            } else if (page.equals("import")) {
                importPage();
            } else {
                today();
            }
        });
    }

    /** 课表总览页：卡片预览、按周翻页、七天选择，以及所选那天的课程卡片。课表读不出来时退回导入入口。 */
    private void today() {
        ui.header("课表", false, this::onBackPressed, null);
        if (!error.isEmpty()) ui.note(error, "danger");
        if (current == null) {
            ui.note("已保存的课表暂时读不出来，请重新导入。", "warning");
            ui.button("导入课表", true, this::openImport);
            return;
        }
        preview(() -> TimetableWidgetProvider.render(this, card));
        final ZoneId zone = TimetableStore.zone(this);
        final LocalDate today = LocalDate.now(zone);
        if (shown == null) shown = today;
        LocalDate weekStart = shown.minusDays(Math.floorMod(
                shown.getDayOfWeek().getValue() - current.firstDayOfWeek, 7));
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean thisWeek = !today.isBefore(weekStart) && !today.isAfter(weekEnd);
        String title = TimetableText.weekTitle(current, current.week(shown)) + (thisWeek ? " · 本周" : "");
        ui.pager(title, weekStart.getMonthValue() + "/" + weekStart.getDayOfMonth() + " – "
                + weekEnd.getMonthValue() + "/" + weekEnd.getDayOfMonth(),
                () -> moveWeek(-1), () -> moveWeek(1),
                shown.equals(today) ? null : () -> {
                    shown = today;
                    render();
                });
        dayTabs(weekStart, today, zone);
        dayLessons(today, zone);
        ui.title("课表信息");
        ui.value("课程", current.courses.size() + " 门 · 共 " + current.totalWeeks + " 周 · "
                + current.semesterStart + " 开学");
        ui.row("导入/更换课表", "所有课表卡片共用这一份", "file", this::openImport);
    }

    private void moveWeek(int weeks) {
        shown = shown.plusWeeks(weeks);
        render();
    }

    /** 一周七天的选择条；选中项高亮，今天的日期用强调色。 */
    private void dayTabs(LocalDate weekStart, LocalDate today, ZoneId zone) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = 0; index < 7; index++) {
            LocalDate date = weekStart.plusDays(index);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = dp(index == 0 ? 0 : 2);
            params.rightMargin = dp(index == 6 ? 0 : 2);
            row.addView(dayCell(date, today, TimetableFocus.lessons(current, date, zone).size()), params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(WorkshopUi.GAP);
        body.addView(row, rowParams);
    }

    /** 选择条里的一天：星期、日期和“这天有课”的小点。 */
    private View dayCell(LocalDate date, LocalDate today, int count) {
        boolean selected = date.equals(shown);
        boolean isToday = date.equals(today);
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(6), 0, dp(6));
        cell.setMinimumHeight(dp(56));
        GradientDrawable fill = shape(selected ? WorkshopUi.ACCENT_SURFACE : WorkshopUi.SURFACE, 12);
        if (selected) fill.setStroke(dp(1), WorkshopUi.SELECT_RING);
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), fill, shape(0xffffffff, 12)));
        int ink = selected || isToday ? WorkshopUi.ACCENT : WorkshopUi.FG;
        String weekday = TimetableText.weekday(date.getDayOfWeek());
        cell.addView(centered(text(weekday.substring(1), 11,
                selected ? WorkshopUi.ACCENT : WorkshopUi.MUTED, false)));
        cell.addView(centered(text(String.valueOf(date.getDayOfMonth()), 15, ink, true)));
        View dot = new View(this);
        dot.setBackground(shape(count > 0 ? (selected ? WorkshopUi.ACCENT : WorkshopUi.WEAK) : 0x00000000, 3));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(5), dp(5));
        dotParams.topMargin = dp(3);
        cell.addView(dot, dotParams);
        cell.setContentDescription(TimetableText.monthDay(date) + " " + weekday
                + (isToday ? "，今天" : "") + (count > 0 ? "，" + count + " 节课" : "，没有课"));
        cell.setOnClickListener(v -> {
            shown = date;
            render();
        });
        return cell;
    }

    /** 所选那天的课程：标题行说明日期和节数，每节课一张卡片；今天的卡片标出已结束、上课中和下一节。 */
    private void dayLessons(LocalDate today, ZoneId zone) {
        List<TimetableFocus.Lesson> lessons = TimetableFocus.lessons(current, shown, zone);
        boolean isToday = shown.equals(today);
        ui.title(TimetableText.weekday(shown.getDayOfWeek()) + " · " + TimetableText.monthDay(shown)
                + (isToday ? " · 今天" : "") + (lessons.isEmpty() ? "" : " · " + lessons.size() + " 节课"));
        if (lessons.isEmpty()) {
            ui.note(TimetableText.emptyNote(current, shown, isToday));
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        boolean nextMarked = false;
        for (TimetableFocus.Lesson lesson : lessons) {
            String state = "";
            if (isToday) {
                if (!lesson.end.isAfter(now)) state = "已结束";
                else if (!lesson.start.isAfter(now)) state = "上课中";
                else if (!nextMarked) {
                    state = "下一节";
                    nextMarked = true;
                }
            }
            lessonCard(lesson, state);
        }
    }

    /** 一节课：左侧状态色条，右侧三行文字（时间与状态、课程名、节次地点教师）。 */
    private void lessonCard(TimetableFocus.Lesson lesson, String state) {
        boolean ongoing = state.equals("上课中");
        boolean next = state.equals("下一节");
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable fill = shape(ongoing ? WorkshopUi.ACCENT_SURFACE : WorkshopUi.SURFACE, 15);
        if (ongoing) fill.setStroke(dp(1), WorkshopUi.SELECT_RING);
        tile.setBackground(fill);
        View bar = new View(this);
        bar.setBackground(shape(ongoing ? WorkshopUi.ACCENT : next ? WorkshopUi.MUTED : WorkshopUi.OUTLINE, 2));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(3),
                ViewGroup.LayoutParams.MATCH_PARENT);
        barParams.setMargins(dp(8), dp(10), 0, dp(10));
        tile.addView(bar, barParams);
        String detail = TimetableText.lessonDetail(lesson.course);
        tile.addView(lessonText(lesson, state, detail),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tile.setContentDescription((state.isEmpty() ? "" : state + "，") + lesson.start.format(TIME) + " 到 "
                + lesson.end.format(TIME) + "，" + lesson.course.name + (detail.isEmpty() ? "" : "，" + detail));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(WorkshopUi.GAP);
        body.addView(tile, params);
    }

    /** 课程卡片的文字列：第一行时间与状态，第二行课程名，第三行节次、地点和教师。 */
    private View lessonText(TimetableFocus.Lesson lesson, String state, String detail) {
        boolean ongoing = state.equals("上课中");
        boolean ended = state.equals("已结束");
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(9), dp(8), dp(10), dp(9));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView time = text(lesson.start.format(TIME) + "–" + lesson.end.format(TIME), 13,
                ongoing ? WorkshopUi.ACCENT : WorkshopUi.FG, true);
        head.addView(time, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!state.isEmpty()) {
            int stateColor = ongoing ? WorkshopUi.ACCENT : ended ? WorkshopUi.WEAK : WorkshopUi.MUTED;
            head.addView(text(state, 11, stateColor, false));
        }
        column.addView(head);
        TextView name = text(lesson.course.name.isEmpty() ? "未命名课程" : lesson.course.name, 15,
                ended ? WorkshopUi.MUTED : WorkshopUi.FG, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(3);
        column.addView(name, nameParams);
        if (!detail.isEmpty()) {
            TextView line = text(detail, 11, ended ? WorkshopUi.WEAK : WorkshopUi.MUTED, false);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineParams.topMargin = dp(2);
            column.addView(line, lineParams);
        }
        return column;
    }

    private static TextView centered(TextView view) {
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean medium) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(medium ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.SANS_SERIF);
        view.setLineSpacing(0f, 1.2f);
        return view;
    }

    /** 导入页：文件入口、粘贴入口与错误提示；两个入口都只读，点“保存课表”才写。 */
    private void importPage() {
        ui.header("导入课表", false, this::onBackPressed, null);
        if (!error.isEmpty()) ui.note(error, "danger");
        if (!readFailure.isEmpty()) {
            ui.note("已保存的课表暂时读不出来（" + readFailure + "）。重新导入会替换它。", "warning");
        }
        ui.text("导入拾光课表导出的 JSON，所有课表卡片共用这一份。可以选手表上的文件，也可以粘贴全文；单个文件不超过 1 MB，不联网。");
        if (reading) ui.note("正在读取并解析课表…");
        ui.button(reading ? "正在读取…" : "选择课表文件", true, !reading, this::pickFile);
        if (pickerMissing) {
            ui.note("这台设备没有可用的文件选择器，请用下面的“粘贴 JSON”导入。", "warning");
        }
        ui.title("粘贴 JSON");
        ui.note("没有文件选择器，或文件读不出来时，可以把课表 JSON 全文粘贴到这里。");
        if (pasteLost) ui.note("页面重建后粘贴框的内容没有保留，请重新粘贴。", "warning");
        pasteField = multilineField(pasteDraft);
        ui.button("解析粘贴内容", false, !reading, this::parsePasted);
        ui.title("保存方式");
        ui.note("导入先预览，点“保存课表”才写入；取消、返回和右滑都不会改动已保存的课表。");
    }

    /** 导入预览页：来源、课程条目、学期日期都在这里核对，保存后才覆盖已保存的课表。 */
    private void previewImport() {
        ui.header("导入预览", false, this::onBackPressed, null);
        if (pending == null || pendingRaw == null) {
            importPage();
            return;
        }
        if (!error.isEmpty()) ui.note(error, "danger");
        ui.value("来源", pendingSource.isEmpty() ? "课表 JSON" : pendingSource);
        ui.value("课程条目", pending.courses.size() + " 门");
        ui.value("教学周数", "共 " + pending.totalWeeks + " 周");
        ui.value("时间段", pending.slots.size() + " 个");
        ui.title("开学日期");
        if (pending.semesterStart == null) {
            ui.note("这个文件没有开学日期。请调到第 1 周里任意一天，保存时按它推算周次。",
                    "warning");
        } else {
            ui.note("文件里的第 1 周日期是 " + pending.semesterStart + "；不对的话可以在下面调整。");
        }
        final LocalDate base = dateOrToday(dateDraft);
        Wheels.date(this, ui, base, date -> dateDraft = date.toString());
        ui.title("课程条目");
        if (pending.courses.isEmpty()) {
            ui.note("文件里没有解析出课程条目，保存后课表是空的。", "warning");
        }
        int listed = Math.min(pending.courses.size(), PREVIEW_LIMIT);
        for (int index = 0; index < listed; index++) {
            ui.text(TimetableText.entry(pending.courses.get(index)));
        }
        if (pending.courses.size() > listed) {
            ui.note("还有 " + (pending.courses.size() - listed) + " 条没有列出，保存后会全部写入。");
        }
        ui.note("确认无误后点“保存课表”，这一步才会写入并替换已保存的课表。");
        ui.button(reading ? "正在保存…" : "保存课表", true, !reading, this::save);
    }

    /** 草稿日期；还没选过时从今天起步，但草稿保持为空，直到用户真的调整过。 */
    private static LocalDate dateOrToday(String draft) {
        try {
            return draft == null || draft.trim().isEmpty() ? LocalDate.now() : LocalDate.parse(draft.trim());
        } catch (DateTimeParseException bad) {
            return LocalDate.now();
        }
    }

    // ------------------------------------------------------------------
    // 输入框
    // ------------------------------------------------------------------

    /** 把输入框里的文字收进草稿；重建页面前必须调用，否则正在输入的内容会丢。 */
    private void capture() {
        if (pasteField != null) pasteDraft = pasteField.getText().toString();
    }

    /** 多行粘贴框：没有文件选择器时的主要入口，字体取次一级，长文本也能上下滚动查看。 */
    private EditText multilineField(String value) {
        EditText input = ui.input("把课表 JSON 全文粘贴到这里", value, WorkshopUi.SECONDARY_SP);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setSingleLine(false);
        input.setMinLines(4);
        input.setMaxLines(10);
        return input;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
