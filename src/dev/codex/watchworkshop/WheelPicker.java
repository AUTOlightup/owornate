package dev.codex.watchworkshop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

/**
 * 竖向滚轮选择器（类似 iOS 闹钟的时间选择）：拖动、甩动后吸附到最近一项，点上下相邻项也会滚过去。
 *
 * <p>全部自绘，不依赖系统 NumberPicker 的样式。循环模式下首尾相接（月、日、时、分）；非循环模式
 * （年）在两端停住。选中项变化时立即回调，所以配置页可以边滚边更新预览。
 */
public final class WheelPicker extends View {

    /** 选中项变化（滚动途中经过的每一项都会回调，不只是松手后的最终值）。 */
    public interface Listener {
        void onSelected(int index);
    }

    private static final int ROWS = 5;
    private static final int RANGE = 100_000_000;

    private final String name;
    private final int itemHeight;
    private final float baseSize;
    private final int touchSlop;
    private final int minFling;
    private final int maxFling;
    private final OverScroller scroller;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bandRect = new RectF();
    private final Typeface selectedFace;
    private final Typeface itemFace;
    private final float bandRadius;

    private String[] items = new String[0];
    private boolean circular;
    /** 像素位置：第 i 项正好居中时等于 i * itemHeight。 */
    private float scrollY;
    private boolean dragging;
    private boolean needsSnap;
    private float lastY;
    private float downY;
    private float downX;
    private long downTime;
    private int reported = -1;
    private Listener listener;
    private VelocityTracker tracker;

    public WheelPicker(Context context, String name) {
        super(context);
        this.name = name;
        float density = context.getResources().getDisplayMetrics().density;
        baseSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                context.getResources().getDisplayMetrics());
        itemHeight = Math.max(Math.round(32 * density), Math.round(baseSize * 1.6f));
        selectedFace = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        itemFace = Typeface.SANS_SERIF;
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minFling = config.getScaledMinimumFlingVelocity();
        maxFling = config.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFontFeatureSettings("tnum");
        band.setColor(WorkshopUi.ACCENT_SURFACE);
        bandEdge.setStyle(Paint.Style.STROKE);
        bandEdge.setStrokeWidth(Math.max(1f, density * 0.5f));
        bandEdge.setColor(WorkshopUi.SELECT_RING);
        bandRadius = Math.min(itemHeight * 0.34f,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, WorkshopUi.NOTE_RADIUS,
                        context.getResources().getDisplayMetrics()));
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setListener(Listener value) {
        listener = value;
    }

    /** 换一组选项并把 selected 放到中间；不触发回调。 */
    public void setItems(String[] values, int selected, boolean loop) {
        scroller.forceFinished(true);
        needsSnap = false;
        items = values;
        circular = loop && values.length > 1;
        int clamped = values.length == 0 ? 0 : Math.max(0, Math.min(values.length - 1, selected));
        scrollY = clamped * itemHeight;
        reported = clamped;
        updateDescription();
        invalidate();
    }

    public int itemCount() {
        return items.length;
    }

    public int getSelectedIndex() {
        return normalize(Math.round(scrollY / itemHeight));
    }

    private int normalize(int index) {
        int n = items.length;
        if (n == 0) return 0;
        if (circular) return Math.floorMod(index, n);
        return Math.max(0, Math.min(n - 1, index));
    }

    private float minScroll() {
        return circular ? -RANGE : 0;
    }

    private float maxScroll() {
        return circular ? RANGE : Math.max(0, (items.length - 1) * itemHeight);
    }

    private float bound(float value) {
        return Math.max(minScroll(), Math.min(maxScroll(), value));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? itemHeight * 2 : MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, itemHeight * ROWS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        float available = Math.max(1f, w - 8 * getResources().getDisplayMetrics().density);
        float cy = getHeight() / 2f;
        float inset = itemHeight * 0.06f;
        bandRect.set(inset, cy - itemHeight / 2f + inset, w - inset, cy + itemHeight / 2f - inset);
        canvas.drawRoundRect(bandRect, bandRadius, bandRadius, band);
        canvas.drawRoundRect(bandRect, bandRadius, bandRadius, bandEdge);
        int n = items.length;
        if (n == 0) return;
        float pos = scrollY / itemHeight;
        int center = Math.round(pos);
        int reach = ROWS / 2 + 1;
        for (int k = -reach; k <= reach; k++) {
            int i = center + k;
            if (!circular && (i < 0 || i >= n)) continue;
            float d = i - pos;
            float distance = Math.abs(d);
            if (distance > ROWS / 2f + 0.5f) continue;
            float scale = 1f - 0.10f * Math.min(distance, 2.5f);
            int alpha = (int) (255 * Math.max(0.12f, 1f - 0.36f * distance));
            boolean selected = distance < 0.5f;
            text.setTextSize(baseSize * scale);
            text.setTypeface(selected ? selectedFace : itemFace);
            String label = items[Math.floorMod(i, n)];
            float measured = text.measureText(label);
            if (measured > available) text.setTextSize(text.getTextSize() * available / measured);
            text.setColor(selected ? WorkshopUi.ACCENT
                    : (distance < 1.5f ? WorkshopUi.FG : WorkshopUi.MUTED));
            text.setAlpha(alpha);
            float y = cy + d * itemHeight - (text.ascent() + text.descent()) / 2f;
            canvas.drawText(label, w / 2f, y, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                needsSnap = false;
                if (tracker == null) tracker = VelocityTracker.obtain(); else tracker.clear();
                tracker.addMovement(event);
                lastY = downY = event.getY();
                downX = event.getX();
                downTime = event.getEventTime();
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) tracker.addMovement(event);
                scrollY = bound(scrollY - (event.getY() - lastY));
                lastY = event.getY();
                report();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                float velocity = 0f;
                if (tracker != null) {
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000, maxFling);
                    velocity = tracker.getYVelocity();
                    tracker.recycle();
                    tracker = null;
                }
                boolean up = event.getActionMasked() == MotionEvent.ACTION_UP;
                float moved = Math.max(Math.abs(event.getY() - downY), Math.abs(event.getX() - downX));
                if (up && moved < touchSlop && event.getEventTime() - downTime < 300) {
                    // 点击：点中间项不动，点上下相邻项就滚过去。
                    float offset = event.getY() - getHeight() / 2f;
                    int steps = Math.round(offset / itemHeight);
                    if (steps != 0) {
                        float target = Math.round(scrollY / itemHeight) * (float) itemHeight + steps * itemHeight;
                        glideTo(bound(target));
                        return true;
                    }
                    glideTo(Math.round(scrollY / itemHeight) * (float) itemHeight);
                    return true;
                }
                if (up && Math.abs(velocity) > minFling) {
                    needsSnap = true;
                    scroller.fling(0, Math.round(scrollY), 0, Math.round(-velocity), 0, 0,
                            Math.round(minScroll()), Math.round(maxScroll()));
                    postInvalidateOnAnimation();
                } else {
                    snap();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = bound(scroller.getCurrY());
            report();
            postInvalidateOnAnimation();
            return;
        }
        if (needsSnap && !dragging) {
            needsSnap = false;
            snap();
        } else if (!dragging && scroller.isFinished()) {
            float aligned = Math.round(scrollY / itemHeight) * (float) itemHeight;
            if (aligned != scrollY) {
                scrollY = bound(aligned);
                invalidate();
            }
        }
    }

    /** 从当前位置平滑吸附到最近一项。 */
    private void snap() {
        glideTo(bound(Math.round(scrollY / itemHeight) * (float) itemHeight));
    }

    private void glideTo(float target) {
        int delta = Math.round(target - scrollY);
        if (delta == 0) {
            scrollY = target;
            report();
            invalidate();
            return;
        }
        scroller.startScroll(0, Math.round(scrollY), 0, delta, 180);
        postInvalidateOnAnimation();
    }

    private void report() {
        int index = getSelectedIndex();
        if (index != reported) {
            reported = index;
            updateDescription();
            if (listener != null) listener.onSelected(index);
        }
    }

    private void updateDescription() {
        if (items.length == 0) return;
        setContentDescription(name + "，" + items[getSelectedIndex()]);
    }

    /** 无障碍：上下滑动动作各挪一项。 */
    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            int step = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1;
            glideTo(bound((Math.round(scrollY / itemHeight) + step) * (float) itemHeight));
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }
}
