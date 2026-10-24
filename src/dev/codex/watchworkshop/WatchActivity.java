package dev.codex.watchworkshop;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 管理器各页面共用的骨架：全屏、右滑返回、一个常驻的 ScrollView、卡片入口校验和预览，
 * 以及后台任务回主线程、页面可见期间的偏好监听这两件每页都要做的事。
 *
 * <p>页面内容通过 {@link #rebuild} 重建：同一页原地换内容，不新建视图、不切 contentView，
 * 滚动位置自然保留，读状态时不会闪；换页时回到该页上次的位置。
 */
abstract class WatchActivity extends Activity {
    /** 意图里没带卡片 id（从管理器直接打开）。 */
    static final int NO_CARD = -1;
    /** 带了卡片 id，但它不属于期望的 provider 或已被移除：调用方应不写数据并结束。 */
    static final int REJECTED = AppWidgetManager.INVALID_APPWIDGET_ID;
    /** 小组件在负一屏上的宽度，预览按同一宽度渲染。 */
    static final int CARD_WIDTH_DP = 181;

    /** 后台任务：在后台线程执行，不得访问 View。 */
    interface Work<T> {
        T run() throws Exception;
    }

    /** 后台任务的收尾：回到主线程调用；error 为 null 表示成功。页面已销毁时不会调用。 */
    interface Done<T> {
        void finished(T result, Exception error);
    }

    private final HashMap<String, Integer> positions = new HashMap<>();
    private final ArrayList<Observer> observers = new ArrayList<>();
    private ScrollView scroll;
    LinearLayout body;
    WorkshopUi ui;
    /** 本页面对应的卡片：{@link #claimCard} 之后才有意义；管理器直接打开时为 {@link #NO_CARD}。 */
    int card = NO_CARD;
    private String shown;
    private boolean destroyed;
    private float downX, downY;
    private boolean swipeTracking;

    /** onStart 注册、onStop 注销的偏好监听。 */
    private static final class Observer {
        final SharedPreferences prefs;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        Observer(SharedPreferences prefs, SharedPreferences.OnSharedPreferenceChangeListener listener) {
            this.prefs = prefs;
            this.listener = listener;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(WorkshopUi.BG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(WorkshopUi.BG);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(WorkshopUi.PAGE_SIDE), dp(WorkshopUi.PAGE_TOP),
                dp(WorkshopUi.PAGE_SIDE), dp(WorkshopUi.PAGE_BOTTOM));
        scroll.addView(body);
        setContentView(scroll);
        ui = new WorkshopUi(this, body);
    }

    @Override protected void onStart() {
        super.onStart();
        for (Observer observer : observers) {
            observer.prefs.registerOnSharedPreferenceChangeListener(observer.listener);
        }
    }

    @Override protected void onStop() {
        for (Observer observer : observers) {
            observer.prefs.unregisterOnSharedPreferenceChangeListener(observer.listener);
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    final boolean destroyed() {
        return destroyed;
    }

    /** 页面可见期间监听 prefs：onStart 注册、onStop 注销。在 onCreate 里调用一次。 */
    final void observeWhileStarted(SharedPreferences prefs,
            SharedPreferences.OnSharedPreferenceChangeListener listener) {
        observers.add(new Observer(prefs, listener));
    }

    /** 在 queue 上执行 work，结束后回到主线程调用 done；页面已销毁时不再回调。 */
    final <T> void inBackground(Executor queue, Work<T> work, Done<T> done) {
        queue.execute(() -> {
            T result = null;
            Exception error = null;
            try {
                result = work.run();
            } catch (Exception problem) {
                error = problem;
            }
            final T value = result;
            final Exception failure = error;
            onUi(() -> done.finished(value, failure));
        });
    }

    /** 回到主线程执行；页面已销毁时丢弃。 */
    final void onUi(Runnable action) {
        runOnUiThread(() -> {
            if (!destroyed) action.run();
        });
    }

    /** 用 content 重建 page 的内容。 */
    final void rebuild(String page, Runnable content) {
        if (destroyed) return;
        boolean same = page.equals(shown);
        if (!same && shown != null) positions.put(shown, scroll.getScrollY());
        body.removeAllViews();
        shown = page;
        content.run();
        if (!same) {
            final int y = positions.containsKey(page) ? positions.get(page) : 0;
            scroll.scrollTo(0, 0);
            if (y > 0) scroll.post(() -> scroll.scrollTo(0, y));
        }
    }

    final int scrollY() {
        return scroll.getScrollY();
    }

    /** Keep an explicit primary action visible while the page above it scrolls. */
    final void pinBelowContent(View footer) {
        ((ViewGroup) scroll.getParent()).removeView(scroll);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(WorkshopUi.BG);
        layout.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        layout.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(layout);
    }

    /** 指定某页下次显示时的滚动位置（恢复实例状态、或要求回到页首时用）。 */
    final void remember(String page, int y) {
        positions.put(page, y);
    }

    /** 右滑返回：水平右移超过 64dp 且明显比竖向移动大。系统自带的右滑关闭已在主题中关闭。 */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                swipeTracking = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                swipeTracking = false;
                break;
            case MotionEvent.ACTION_UP:
                if (swipeTracking) {
                    swipeTracking = false;
                    float dx = event.getRawX() - downX;
                    float dy = Math.abs(event.getRawY() - downY);
                    if (dx > dp(64) && dy < dx * 0.5f) {
                        event.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(event);
                        onBackPressed();
                        return true;
                    }
                }
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** 意图里的卡片：{@link #NO_CARD}、属于 provider 的有效 id，或 {@link #REJECTED}。 */
    final int cardTarget(Class<?> provider) {
        Intent intent = getIntent();
        if (intent == null || !intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) return NO_CARD;
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, REJECTED);
        return bound(provider, id) ? id : REJECTED;
    }

    /** 系统已知该 id，且它归属本工程的 provider。 */
    final boolean bound(Class<?> provider, int id) {
        if (id <= 0) return false;
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager == null) return false;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        return info != null && new ComponentName(this, provider).equals(info.provider);
    }

    /**
     * 卡片设置页的入口校验，结果记在 {@link #card}。带了卡片 id 就必须属于 provider：
     * 不属于时不写任何数据，返回 RESULT_CANCELED 并结束页面，此时返回 false。
     */
    final boolean claimCard(Class<?> provider) {
        card = cardTarget(provider);
        if (card == REJECTED) {
            setResult(RESULT_CANCELED);
            finish();
            return false;
        }
        acceptCard(card);
        return true;
    }

    /**
     * 卡片入口的结果：带了有效卡片就回 RESULT_OK（宿主添加流程里直接返回也不会把新卡片当取消删掉）。
     * 这几页的修改都即时或离开时保存，没有“取消”这一说。
     */
    final void acceptCard(int card) {
        if (card > 0) setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, card));
    }

    /** 在页面里放一块 181dp 宽的预览位，并画上 source 给的卡片。 */
    final FrameLayout preview(Supplier<RemoteViews> source) {
        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(WorkshopUi.GAP);
        body.addView(box, params);
        fillPreview(box, source);
        return box;
    }

    /** 重画预览；预览只做外观展示，整棵子树不可点，渲染失败时留空、不打断页面。 */
    final void fillPreview(FrameLayout box, Supplier<RemoteViews> source) {
        box.removeAllViews();
        try {
            RemoteViews views = source.get();
            if (views == null) return;
            View rendered = views.apply(this, box);
            if (rendered == null) return;
            inert(rendered);
            box.addView(rendered, new FrameLayout.LayoutParams(
                    dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (RuntimeException ignored) {
            // 预览只是辅助。
        }
    }

    private static void inert(View view) {
        view.setEnabled(false);
        view.setClickable(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                inert(group.getChildAt(index));
            }
        }
    }

    final int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    final GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    static String message(Exception problem) {
        return problem.getMessage() == null ? problem.getClass().getSimpleName() : problem.getMessage();
    }

    /** A.92 needs HOME to recreate Launcher; return to the user's operation result afterwards. */
    final void returnAfterLauncherReload() {
        if (!destroyed && !isFinishing() && !hasWindowFocus()) {
            startActivity(new Intent(this, getClass()).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }
}
