package dev.codex.watchworkshop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import dev.codex.watchworkshop.palette.ThemePalettePreview;

/** 莫奈配置页的两个自绘控件：固定颜色的色点，以及页首的控制中心配色示意。 */
final class MonetViews {
    private MonetViews() {}

    /** 20dp 色点；选中时叠一个对勾。 */
    static final class ColorDot extends View {
        private final int color;
        private final boolean chosen;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ColorDot(Context context, int color, boolean chosen) {
            super(context);
            this.color = color;
            this.chosen = chosen;
        }

        @Override protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, paint);
            if (chosen) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * d);
                paint.setColor(WorkshopUi.ON_ACCENT);
                Path path = new Path();
                path.moveTo(5 * d, 10 * d);
                path.lineTo(8.5f * d, 13 * d);
                path.lineTo(15 * d, 6.5f * d);
                canvas.drawPath(path, paint);
            }
        }
    }

    /**
     * 配置页顶部的控制中心配色示意：四个按钮 + 强调色值；系统原色或取色中时显示说明。
     * 页面每次重建都新建一个，所以构造时的草稿就是要画的内容。
     */
    static final class ThemePreview extends View {
        static final int HEIGHT_DP = 74;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final ThemeConfig draft;
        /** 自动取色时 helper 采到的种子色；还没采到时为 null。 */
        private final Integer autoSeed;

        ThemePreview(Context context, ThemeConfig draft, Integer autoSeed) {
            super(context);
            this.draft = draft;
            this.autoSeed = autoSeed;
            setMinimumHeight(dp(HEIGHT_DP));
            setContentDescription("莫奈配色预览，设置自动应用");
        }

        private int dp(float value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }

        private float sp(float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(WorkshopUi.SURFACE);
            canvas.drawRoundRect(0, 0, getWidth(), dp(HEIGHT_DP), dp(15), dp(15), paint);
            boolean original = draft.mode.equals("original");
            boolean waiting = draft.mode.equals("auto") && autoSeed == null;
            if (original || waiting) {
                drawNotice(canvas, original);
                return;
            }
            int seed = draft.mode.equals("fixed") ? draft.seed : autoSeed;
            ThemePalettePreview palette = ThemePalettePreview.fromSeed(seed);
            drawTiles(canvas, palette);
            paint.setColor(WorkshopUi.FG);
            paint.setTextSize(sp(12));
            canvas.drawText(draft.mode.equals("fixed") ? "固定配色" : "表盘配色", dp(68), dp(31), paint);
            paint.setColor(palette.secondary);
            paint.setTextSize(sp(10));
            canvas.drawText(String.format("强调 #%06X", palette.active & 0xffffff), dp(68), dp(49), paint);
        }

        private void drawNotice(Canvas canvas, boolean original) {
            paint.setColor(WorkshopUi.FG);
            paint.setTextSize(sp(13));
            canvas.drawText(original ? "系统原色" : "正在读取表盘配色", dp(12), dp(31), paint);
            paint.setColor(WorkshopUi.MUTED);
            paint.setTextSize(sp(10));
            canvas.drawText(original ? "使用系统原生颜色" : "取色成功后显示预览", dp(12), dp(50), paint);
        }

        /** 2×2 的按钮示意：左上右下为开启色，另两个为关闭色。 */
        private void drawTiles(Canvas canvas, ThemePalettePreview palette) {
            float radius = dp(draft.corners ? 9 : 3);
            for (int i = 0; i < 4; i++) {
                boolean off = i == 1 || i == 2;
                paint.setColor(off ? palette.off : palette.active);
                float x = dp(10 + (i % 2) * 26), y = dp(10 + (i / 2) * 26);
                canvas.drawRoundRect(x, y, x + dp(23), y + dp(23), radius, radius, paint);
                paint.setColor(off ? palette.onOff : palette.onActive);
                canvas.drawCircle(x + dp(11.5f), y + dp(11.5f), dp(3), paint);
            }
        }
    }
}
