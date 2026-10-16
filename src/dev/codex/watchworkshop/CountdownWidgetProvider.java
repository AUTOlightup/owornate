package dev.codex.watchworkshop;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 倒计时小组件（API 27 RemoteViews，181x100dp，设计 v0.1 第 2 节）。
 *
 * <p>渲染只读本地数据并算一次当前画面，不联网、不用 root、不轮询。所有卡片共用一份配置。
 * 数据来源有两种：自己设置的日子（点卡片打开设置页），或纪念日模式下系统快应用“纪念日”里的
 * 某一条（读 {@link AnniversaryStore} 的镜像；点卡片打开纪念日）。画面变化只在两个边界发生：
 * 下一个本地午夜和目标时刻；这两个时刻由 {@link WidgetClock} 统一排闹钟，本类只通过
 * {@link #nextUpdate} 报出最早的那个。
 *
 * <p>共享契约（由主 agent 的调度侧调用）：
 * <ul>
 *   <li>{@link #updateAll(Context)}：重画所有绑定到本 provider 的实例。</li>
 *   <li>{@link #nextUpdate(Context, long)}：下一个必须重画的绝对时刻；只考虑绑定组件，
 *       没有边界时返回 {@link Long#MAX_VALUE}。</li>
 * </ul>
 */
public final class CountdownWidgetProvider extends AppWidgetProvider {

    /** 墨夜外观（设计 v0.1 颜色 token；本轮唯一外观，没有外观导入）。 */
    private static final int TEXT_1 = 0xFFF2F2F4;
    private static final int TEXT_2 = 0xFFABABB2;
    private static final int ACCENT_TEXT = 0xFFFFB066;

    private static final String TAG = "CountdownWidget";
    /** 每个实例一个数据 URI，让 PendingIntent 各自独立。 */
    private static final String EDIT_URI = "countdown://widget/";
    private static final String OPEN_URI = "countdown://anniversary/";
    /** 内容宽度：181dp 卡片减左右各 12dp 内边距。 */
    private static final float CONTENT_WIDTH_DP = 157f;
    /** 计时器行里除数字以外的固定占用：前缀“还有”12sp（24dp）+ 与数字之间的 6dp。 */
    private static final float TIMER_LEAD_DP = 30f;
    /** 计时器字号档位：设计值 30sp 起，放不下就逐档缩。 */
    private static final int[] TIMER_SIZES_SP = {30, 28, 26, 24};
    /** 量宽后的安全余量，覆盖 OEM 自带字体比 Roboto / monospace 更宽的情况。 */
    private static final float WIDTH_SAFETY = 1.05f;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            update(context, manager, id);
        }
        WidgetClock.schedule(context);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        WidgetClock.schedule(context);
    }

    @Override
    public void onRestored(Context context, int[] oldIds, int[] newIds) {
        updateAll(context);
    }

    @Override
    public void onEnabled(Context context) {
        WidgetClock.schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetClock.schedule(context);
    }

    /** 重画所有绑定到本 provider 的实例；配置保存成功后调用。 */
    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) {
                return;
            }
            for (int id : manager.getAppWidgetIds(component(context))) {
                update(context, manager, id);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to update countdown widgets", failure);
        }
    }

    /**
     * 下一次必须重画的时刻；没有绑定的卡片或没有边界时返回 {@link Long#MAX_VALUE}。
     * 所有卡片共用一份配置，所以只算一次。
     */
    static long nextUpdate(Context context, long nowMillis) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null || manager.getAppWidgetIds(component(context)).length == 0) {
                return Long.MAX_VALUE;
            }
            return shown(context, CountdownStore.source(context), CountdownStore.config(context),
                    nowMillis).frame.nextUpdate;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to compute the next countdown update", failure);
            return Long.MAX_VALUE;
        }
    }

    /** 这一刻卡片显示什么，以及点按去哪里（纪念日里的日子点开纪念日，其余打开设置页）。 */
    static final class Shown {
        final CountdownModel.Frame frame;
        final boolean openAnniversary;

        Shown(CountdownModel.Frame frame, boolean openAnniversary) {
            this.frame = frame;
            this.openAnniversary = openAnniversary;
        }
    }

    /**
     * 按数据来源算出画面。纪念日模式只读 {@link AnniversaryStore} 的镜像，不在这里读纪念日本身。
     * source、manual 是设置页的草稿或已保存的配置。
     */
    static Shown shown(Context context, CountdownStore.Source source,
                       CountdownModel.Config manual, long nowMillis) {
        ZoneId zone = ZoneId.systemDefault();
        if (source == null || !source.anniversary) {
            return new Shown(CountdownModel.frame(manual, nowMillis, zone), false);
        }
        AnniversaryStore.Snapshot mirror = AnniversaryStore.snapshot(context);
        if (mirror.entries == null) {
            if (AnniversaryStore.MISSING.equals(mirror.state)) {
                return new Shown(CountdownModel.notice("没有找到纪念日数据", "点按打开纪念日", zone), true);
            }
            if (AnniversaryStore.UNKNOWN.equals(mirror.state)) {
                return new Shown(CountdownModel.notice("正在读取纪念日", "稍候自动更新", zone), false);
            }
            return new Shown(CountdownModel.notice("读不到纪念日数据", "点按打开腕上工坊", zone), false);
        }
        if (mirror.entries.isEmpty()) {
            return new Shown(CountdownModel.notice("纪念日里还没有日子", "点按打开纪念日添加", zone), true);
        }
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        AnniversaryData.Entry entry = AnniversaryData.pick(mirror.entries, source.pick, source::yearly, today);
        if (entry == null) {
            return new Shown(CountdownModel.notice("选中的纪念日已删除", "点按重新选择", zone), false);
        }
        return new Shown(CountdownModel.frame(AnniversaryData.config(entry, source.yearly(entry.id),
                source.progress(entry.id)), nowMillis, zone), true);
    }

    private static Shown shownSafely(Context context, CountdownStore.Source source,
                                     CountdownModel.Config manual) {
        try {
            return shown(context, source == null ? CountdownStore.source(context) : source,
                    manual == null ? CountdownStore.config(context) : manual, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to read countdown settings", failure);
            return new Shown(CountdownModel.unavailable(ZoneId.systemDefault()), false);
        }
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, CountdownWidgetProvider.class);
    }

    private static void update(Context context, AppWidgetManager manager, int id) {
        manager.updateAppWidget(id, render(context, id));
    }

    /** 渲染一个实例；配置读不出来时落到“暂时无法读取”的兜底画面。 */
    static RemoteViews render(Context context, int id) {
        Shown shown = shownSafely(context, null, null);
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.countdown_widget);
        bind(context, view, shown.frame, shown.openAnniversary);
        Intent tap = shown.openAnniversary
                ? new Intent(context, AnniversaryLaunchActivity.class).setData(Uri.parse(OPEN_URI + id))
                : new Intent(context, CountdownSettingsActivity.class)
                        .setData(Uri.parse(EDIT_URI + id))
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        view.setOnClickPendingIntent(R.id.countdown_root, PendingIntent.getActivity(context, id,
                tap, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return view;
    }

    /** 用设置页的草稿渲染外观，不带点击动作；参数为空时读已保存的配置。 */
    static RemoteViews preview(Context context, CountdownStore.Source source,
                               CountdownModel.Config manual) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.countdown_widget);
        Shown shown = shownSafely(context, source, manual);
        bind(context, view, shown.frame, shown.openAnniversary);
        return view;
    }

    private static void bind(Context context, RemoteViews view, CountdownModel.Frame frame,
                             boolean openAnniversary) {
        boolean empty = frame.empty();
        view.setViewVisibility(R.id.countdown_body, empty ? View.GONE : View.VISIBLE);
        view.setViewVisibility(R.id.countdown_empty, empty ? View.VISIBLE : View.GONE);
        if (empty) {
            view.setViewVisibility(R.id.countdown_glow, View.GONE);
            view.setTextViewText(R.id.countdown_empty_main, frame.emptyMain);
            view.setTextViewText(R.id.countdown_empty_hint, frame.emptyHint);
            view.setContentDescription(R.id.countdown_root,
                    "倒计时，" + frame.emptyMain + "，" + frame.emptyHint);
            return;
        }

        int glow = glow(frame);
        view.setViewVisibility(R.id.countdown_glow, glow == 0 ? View.GONE : View.VISIBLE);
        if (glow != 0) {
            view.setImageViewResource(R.id.countdown_glow, glow);
        }

        view.setTextViewText(R.id.countdown_title,
                frame.title.isEmpty() ? "倒计时" : frame.title);
        view.setViewVisibility(R.id.countdown_badge,
                frame.badgeVisible() ? View.VISIBLE : View.GONE);
        view.setTextViewText(R.id.countdown_badge, frame.badge);
        view.setImageViewResource(R.id.countdown_dot, frame.state == CountdownModel.STATE_PAST
                && frame.tone == CountdownModel.TONE_SECONDARY
                ? R.drawable.countdown_dot_past : R.drawable.countdown_dot);

        view.setTextViewText(R.id.countdown_prefix, frame.prefix);
        view.setTextViewText(R.id.countdown_unit, frame.unit);
        view.setViewVisibility(R.id.countdown_unit,
                frame.unitVisible() ? View.VISIBLE : View.GONE);
        if (frame.chronometer) {
            view.setViewVisibility(R.id.countdown_value, View.GONE);
            view.setViewVisibility(R.id.countdown_timer, View.VISIBLE);
            view.setTextViewTextSize(R.id.countdown_timer, TypedValue.COMPLEX_UNIT_SP,
                    timerTextSize(context, CountdownModel.timerSample(frame.countdownMillis)));
            // 先定方向再设基点：setBase 会立刻按当前方向算出文本。
            view.setChronometerCountDown(R.id.countdown_timer, true);
            view.setChronometer(R.id.countdown_timer,
                    SystemClock.elapsedRealtime() + frame.countdownMillis, "%s", true);
        } else {
            view.setViewVisibility(R.id.countdown_timer, View.GONE);
            view.setViewVisibility(R.id.countdown_value, View.VISIBLE);
            view.setTextViewText(R.id.countdown_value, frame.value);
            view.setTextColor(R.id.countdown_value, valueColor(frame.tone));
        }

        view.setTextViewText(R.id.countdown_detail, frame.detail);
        boolean progress = frame.progressVisible();
        view.setViewVisibility(R.id.countdown_progress, progress ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.countdown_percent, progress ? View.VISIBLE : View.GONE);
        if (progress) {
            view.setProgressBar(R.id.countdown_progress, 100, frame.progressPercent, false);
            view.setTextViewText(R.id.countdown_percent, frame.progressPercent + "%");
        }

        view.setContentDescription(R.id.countdown_root, description(frame, openAnniversary));
    }

    /** 光晕强度：越接近“现在”越亮；过期和空状态不用光晕。 */
    private static int glow(CountdownModel.Frame frame) {
        switch (frame.state) {
            case CountdownModel.STATE_TODAY:
            case CountdownModel.STATE_COUNTDOWN:
                return R.drawable.countdown_glow_near;
            case CountdownModel.STATE_NORMAL:
                return frame.days <= 7
                        ? R.drawable.countdown_glow_soon : R.drawable.countdown_glow_far;
            default:
                return 0;
        }
    }

    /**
     * 计时器字号：按这一刻可能出现的实际文本（含前缀“还有”）量宽，从设计的 30sp 起逐档缩到
     * 放得下为止。8 位“23:59:59”在 30sp 等宽字体下约 144dp，加前缀会超过 157dp 的内容宽度，
     * 所以这里不能写死字号。
     *
     * <p>量宽同时取 sans-serif 与 monospace 的较宽者：宿主把 Chronometer 换成等宽字体也不会溢出。
     * 用 sp 换算，系统字体放大时字号会自动降档，保持整行仍在卡内。
     */
    private static int timerTextSize(Context context, String sample) {
        try {
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            for (int size : TIMER_SIZES_SP) {
                if (measuredWidthDp(sample, size, metrics) * WIDTH_SAFETY + TIMER_LEAD_DP
                        <= CONTENT_WIDTH_DP) {
                    return size;
                }
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to measure the countdown timer, using the smallest size", failure);
        }
        return TIMER_SIZES_SP[TIMER_SIZES_SP.length - 1];
    }

    /** 文本在指定 sp 下的宽度（dp），取 sans-serif 与 monospace 的较宽者。 */
    private static float measuredWidthDp(String text, int sizeSp, DisplayMetrics metrics) {
        float pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics);
        float widest = 0f;
        for (String family : new String[]{"sans-serif", "monospace"}) {
            Paint paint = new Paint();
            paint.setTypeface(Typeface.create(family, Typeface.NORMAL));
            paint.setTextSize(pixels);
            widest = Math.max(widest, paint.measureText(text));
        }
        return widest / (metrics.density <= 0f ? 1f : metrics.density);
    }

    private static int valueColor(int tone) {
        if (tone == CountdownModel.TONE_ACCENT) {
            return ACCENT_TEXT;
        }
        return tone == CountdownModel.TONE_SECONDARY ? TEXT_2 : TEXT_1;
    }

    /** 整卡的朗读文本；画面上只写绝对值，这里把状态补全。 */
    private static String description(CountdownModel.Frame frame, boolean openAnniversary) {
        StringBuilder text = new StringBuilder("倒计时，");
        if (!frame.title.isEmpty()) {
            text.append(frame.title).append('，');
        }
        text.append(frame.prefix);
        if (frame.chronometer) {
            text.append("计时中");
        } else {
            text.append(' ').append(frame.value);
            if (frame.unitVisible()) {
                text.append(frame.unit);
            }
        }
        if (!frame.detail.isEmpty()) {
            text.append('，').append(frame.detail);
        }
        return text.append(openAnniversary ? "，点按打开纪念日" : "，点按编辑组件").toString();
    }
}
