package dev.codex.watchworkshop;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 腕上工坊通用原生组件（首发 UI 对照实现）。
 *
 * <p>取值来源：outputs/腕上工坊-界面设计/设计变量.json 与 work/watch-ui/app.css。
 * 原型画布 402x476px / density 2，因此原型 px 除以 2 即本类的 dp：
 * 行 96px→48dp、按钮 88px→44dp、行图标 44px→22dp、页头命中区 88px→44dp、
 * 行圆角 30px→15dp、按钮圆角 28px→14dp、块间距 12px→6dp。
 *
 * <p>职责边界：页面左右 8dp、顶部 10dp、尾部 32dp 的内边距与 ScrollView 由
 * MainActivity 负责；本类只把内容块追加进构造函数传入的 body，
 * 每个块自带 6dp 下间距，不修改 body 的 padding。
 *
 * <p>所有文字使用 sp，跟随系统 fontScale；所有图标用 Canvas 矢量描边绘制，
 * 不使用 unicode 字形。
 */
public final class WorkshopUi {

    // ------------------------------------------------------------------
    // 语义色（设计变量.json）
    // ------------------------------------------------------------------
    public static final int BG = 0xff000000;
    public static final int SURFACE = 0xff12161e;
    public static final int SOFT = 0xff191f29;
    public static final int OUTLINE = 0xff273141;
    public static final int FG = 0xfff0f3fa;
    public static final int MUTED = 0xffa4aec0;
    public static final int WEAK = 0xff748095;
    public static final int ACCENT = 0xffebc16c;
    public static final int ACCENT_SURFACE = 0xff282216;
    public static final int ON_ACCENT = 0xff1e1a11;
    public static final int SUCCESS = 0xff99d5b4;
    public static final int WARNING = 0xffffc857;
    public static final int DANGER = 0xffff6b6b;
    /** note 语义底色。 */
    public static final int WARNING_BG = 0xff282315;
    public static final int SUCCESS_BG = 0xff16251d;
    public static final int DANGER_BG = 0xff291719;
    /** danger 按钮文字色。 */
    public static final int DANGER_INK = 0xff240b0b;
    /** 概览卡片副文本色。 */
    public static final int SUMMARY_LABEL = 0xffe1d8c0;
    public static final int SUMMARY_STATUS = 0xffc6b996;
    /** 控件内部件。 */
    public static final int TRACK_OFF = 0xff39424f;
    public static final int KNOB_OFF = 0xffb2bdcb;
    public static final int KNOB_ON = 0xff362e1c;
    public static final int SELECT_RING = 0xff6f6040;

    // ------------------------------------------------------------------
    // 尺寸常量（dp），与 设计变量.json / app.css 一一对应
    // ------------------------------------------------------------------
    public static final int GAP = 6;           // .watch-content gap 12px
    public static final int PAGE_SIDE = 8;     // .scroll 16px（由 MainActivity 应用）
    public static final int PAGE_TOP = 10;     // .scroll 20px（由 MainActivity 应用）
    public static final int PAGE_BOTTOM = 32;  // .scroll 64px（由 MainActivity 应用）
    public static final int HIT = 44;          // 44x44dp 命中区 / 主按钮 88px
    public static final int ROW_MIN = 48;      // .w-row 96px
    public static final int ROW_RADIUS = 15;   // .w-row 30px
    public static final int BTN_RADIUS = 14;   // .w-btn 28px
    public static final int NOTE_RADIUS = 12;  // .w-note 24px
    public static final int CARD_RADIUS = 16;  // .summary-card 32px
    public static final int HEAD_ICON = 20;    // .watch-head svg 40px
    public static final int ROW_ICON = 22;     // .row-icon 44px
    public static final int CHEVRON = 12;      // .row-chevron 24px
    public static final int TITLE_SP = 20;     // .watch-head.root h2 40px
    public static final int HEAD_SP = 17;      // .watch-head h2 34px
    public static final int BODY_SP = 13;      // 26px
    public static final int SECONDARY_SP = 11; // 22px
    public static final int SECTION_SP = 12;   // .w-section 24px
    public static final int HERO_TITLE_SP = 18;

    private static final int RIPPLE = 0x33ffffff;

    private final Activity activity;
    private final LinearLayout body;
    private final float density;
    private final Typeface regular = Typeface.SANS_SERIF;
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** 复用构造函数：MainActivity 提供页面 body，本类只负责内容块。 */
    public WorkshopUi(Activity activity, LinearLayout body) {
        this.activity = activity;
        this.body = body;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------------
    // 页面骨架
    // ------------------------------------------------------------------

    /**
     * 页头：左侧 44x44dp 返回命中区、可换行短标题、首页右侧 44x44dp 设置入口。
     * 与内容一起滚动，无吸顶；root=true 时标题 20sp，否则 17sp。
     * back/settings 传 null 表示不显示对应按钮。
     */
    public void header(String title, boolean root, Runnable back, Runnable settings) {
        LinearLayout head = headBase(title, root, false, back);
        if (settings != null) {
            ImageView hit = hitIcon("sliders", HEAD_ICON, FG, "设备与设置");
            final Runnable tap = settings;
            hit.setOnClickListener(v -> tap.run());
            head.addView(hit, new LinearLayout.LayoutParams(dp(HIT), dp(HIT)));
        }
        add(head);
    }

    /**
     * 详情页页头：左侧返回 + 标题 + 右上三态启停开关（首页设置入口不在此）。
     *
     * <p>checked=null 表示状态未知/读取中，绘制加载符号而不是确定的关闭；非 null 时
     * 使用与 {@link #toggle} 相同的 SwitchMark 开关外观。开关命中区 44dp，
     * 标题占满剩余宽度并在必要时单行省略，确保返回、标题、开关在画布上不重叠。
     *
     * <p>内容描述随状态变化：true「停用莫奈」、false「启用莫奈」、null「正在读取莫奈状态」。
     * enabled=false 时关闭点击并把开关压暗以给出明确反馈。
     */
    public void headerToggle(String title, Runnable back, Boolean checked, boolean enabled,
                             Runnable action) {
        LinearLayout head = headBase(title, false, true, back);
        TriSwitchMark mark = new TriSwitchMark(activity);
        mark.setState(checked);
        mark.setBackground(press(0x00000000, 12));
        mark.setContentDescription(toggleDescription(checked));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        mark.setFocusable(true);
        if (enabled && action != null) {
            final Runnable tap = action;
            mark.setOnClickListener(v -> tap.run());
        } else {
            mark.setEnabled(false);
            mark.setFocusable(false);
            mark.setAlpha(0.4f);
        }
        head.addView(mark, new LinearLayout.LayoutParams(dp(HIT), dp(HIT)));
        add(head);
    }

    /**
     * 页头基座：可选的 44x44dp 返回命中区 + 占满剩余宽度的标题。
     * root 决定标题 20sp/17sp 与左侧 6dp 间距；singleLine 时单行省略。
     */
    private LinearLayout headBase(String title, boolean root, boolean singleLine, Runnable back) {
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(HIT));
        if (back != null) {
            ImageView hit = hitIcon("back", HEAD_ICON, FG, "返回");
            final Runnable tap = back;
            hit.setOnClickListener(v -> tap.run());
            head.addView(hit, new LinearLayout.LayoutParams(dp(HIT), dp(HIT)));
        }
        TextView label = text(title, root ? TITLE_SP : HEAD_SP, FG, medium);
        label.setLineSpacing(0f, 1.25f);
        if (singleLine) {
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
        }
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (root) {
            titleParams.leftMargin = dp(6);
        }
        head.addView(label, titleParams);
        return head;
    }

    private static String toggleDescription(Boolean checked) {
        if (checked == null) {
            return "正在读取莫奈状态";
        }
        return checked ? "停用莫奈" : "启用莫奈";
    }

    // ------------------------------------------------------------------
    // 内容块
    // ------------------------------------------------------------------

    /** 正文 13sp / 行高 1.55。 */
    public TextView text(String value) {
        TextView v = text(value, BODY_SP, FG, regular);
        v.setLineSpacing(0f, 1.55f);
        v.setPadding(dp(3), dp(2), dp(3), dp(4));
        return (TextView) add(v);
    }

    /** 说明卡 11sp，默认 surface 底。 */
    public TextView note(String value) {
        return note(value, "");
    }

    /** 说明卡。tone: "" 中性 / warning / success / danger。 */
    public TextView note(String value, String tone) {
        int background = SURFACE;
        int ink = MUTED;
        if ("warning".equals(tone)) {
            background = WARNING_BG;
            ink = WARNING;
        } else if ("success".equals(tone)) {
            background = SUCCESS_BG;
            ink = SUCCESS;
        } else if ("danger".equals(tone)) {
            background = DANGER_BG;
            ink = DANGER;
        }
        TextView v = text(value, SECONDARY_SP, ink, regular);
        v.setLineSpacing(0f, 1.55f);
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        v.setBackground(shape(background, NOTE_RADIUS));
        return (TextView) add(v);
    }

    /** 小节标题 12sp。 */
    public TextView title(String value) {
        TextView v = text(value, SECTION_SP, MUTED, regular);
        v.setLineSpacing(0f, 1.4f);
        v.setPadding(dp(4), dp(8), dp(4), 0);
        return (TextView) add(v);
    }

    /** 键值信息：名称与值上下排列，长标识可断行。 */
    public View value(String label, String content) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(4), dp(6), dp(4), dp(6));
        block.addView(text(label, SECONDARY_SP, MUTED, regular));
        TextView val = text(content, BODY_SP, FG, regular);
        val.setLineSpacing(0f, 1.4f);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.topMargin = dp(2);
        block.addView(val, valueParams);
        return add(block);
    }

    /** 按钮：primary=true 用暖金主操作，false 用 surfaceSoft 次级操作。 */
    public TextView button(String label, boolean primary, Runnable action) {
        return button(label, primary, true, action);
    }

    /**
     * 按钮四态：正常、按压（Ripple）、忙碌（Main 传 enabled=false 并保留说明）、
     * 不可执行（enabled=false 时降低不透明度、不接收点击、说明原因可读）。
     */
    public TextView button(String label, boolean primary, boolean enabled, Runnable action) {
        int background = primary ? ACCENT : SOFT;
        int ink = primary ? ON_ACCENT : FG;
        TextView v = text(label, BODY_SP, ink, primary ? medium : regular);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(HIT));
        v.setPadding(dp(10), dp(8), dp(10), dp(8));
        v.setLineSpacing(0f, 1.4f);
        v.setBackground(press(background, BTN_RADIUS));
        if (enabled) {
            v.setContentDescription(label);
            final Runnable tap = action;
            v.setOnClickListener(x -> tap.run());
        } else {
            v.setEnabled(false);
            v.setAlpha(0.45f);
            v.setContentDescription(label + "，暂不可执行");
        }
        return (TextView) add(v);
    }

    /** 导航行：22dp 功能图标 + 名称 + 一条说明 + 箭头，整行打开同一目标。 */
    public View row(String label, String sub, String icon, Runnable action) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(ROW_MIN));
        r.setPadding(dp(9), dp(8), dp(9), dp(8));
        r.setBackground(press(SURFACE, ROW_RADIUS));
        if (icon != null && !icon.isEmpty()) {
            ImageView glyph = iconView(icon, ROW_ICON, ACCENT);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(ROW_ICON), dp(ROW_ICON));
            iconParams.rightMargin = dp(7);
            r.addView(glyph, iconParams);
        }
        r.addView(copy(label, sub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = iconView("chevron", CHEVRON, WEAK);
        LinearLayout.LayoutParams chevronParams =
                new LinearLayout.LayoutParams(dp(CHEVRON), dp(CHEVRON));
        chevronParams.leftMargin = dp(4);
        r.addView(chevron, chevronParams);
        r.setContentDescription(sub == null || sub.isEmpty() ? label : label + "，" + sub);
        final Runnable tap = action;
        r.setOnClickListener(v -> tap.run());
        return add(r);
    }

    /** 单选行：整行选择，右侧 radio 反映真实选中状态；选中时用强调色低亮面。 */
    public View choice(String label, String sub, boolean checked, Runnable action) {
        CheckRow r = new CheckRow(activity, "android.widget.RadioButton");
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(ROW_MIN));
        r.setPadding(dp(9), dp(8), dp(9), dp(8));
        r.setBackground(choiceBackground(checked));
        r.addView(copy(label, sub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        RadioMark mark = new RadioMark(activity);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        markParams.leftMargin = dp(7);
        r.addView(mark, markParams);
        r.attach(mark);
        r.setChecked(checked);
        r.setContentDescription(sub == null || sub.isEmpty() ? label : label + "，" + sub);
        final Runnable tap = action;
        r.setOnClickListener(v -> tap.run());
        return add(r);
    }

    /** 开关行：点击整个 48dp 行，右侧为 Canvas 绘制的连续圆角 Switch。 */
    public View toggle(String label, String sub, boolean checked, Runnable action) {
        CheckRow r = new CheckRow(activity, "android.widget.Switch");
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(ROW_MIN));
        r.setPadding(dp(9), dp(8), dp(9), dp(8));
        r.setBackground(press(SURFACE, ROW_RADIUS));
        r.addView(copy(label, sub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        SwitchMark mark = new SwitchMark(activity);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(34), dp(20));
        markParams.leftMargin = dp(7);
        r.addView(mark, markParams);
        r.attach(mark);
        r.setChecked(checked);
        r.setContentDescription(sub == null || sub.isEmpty() ? label : label + "，" + sub);
        final Runnable tap = action;
        r.setOnClickListener(v -> tap.run());
        return add(r);
    }

    /** 首页概览卡：数字 + 说明 + 状态点。 */
    public View summary(Integer count, String label, String status) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(7), dp(12), dp(7));
        card.setBackground(shape(ACCENT_SURFACE, CARD_RADIUS));

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.BOTTOM);
        TextView number = text(count==null?"—":String.valueOf(count), 24, ACCENT, medium);
        number.setFontFeatureSettings("tnum");
        number.setLineSpacing(0f, 1.1f);
        line.addView(number);
        TextView caption = text(label, SECTION_SP, SUMMARY_LABEL, regular);
        caption.setLineSpacing(0f, 1.4f);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.leftMargin = dp(6);
        captionParams.bottomMargin = dp(1);
        line.addView(caption, captionParams);
        card.addView(line);

        LinearLayout statusLine = new LinearLayout(activity);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(activity);
        dot.setBackground(oval(count==null?SUMMARY_STATUS:count>0?SUCCESS:WARNING, 0, 0));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statusLine.addView(dot, new LinearLayout.LayoutParams(dp(5), dp(5)));
        TextView statusText = text(status, SECONDARY_SP, SUMMARY_STATUS, regular);
        statusText.setLineSpacing(0f, 1.4f);
        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = dp(4);
        statusLine.addView(statusText, statusParams);
        LinearLayout.LayoutParams statusLineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLineParams.topMargin = dp(1);
        card.addView(statusLine, statusLineParams);
        card.setContentDescription((count==null?"优化数量待确认":count + " " + label) + "，" + status);
        return add(card);
    }

    /** 结果/空态头部：33dp 图标 + 结果标题 + 说明。tone: "" accent / success / warning / danger。 */
    public View hero(String icon, String title, String sub, String tone) {
        int ink = ACCENT;
        if ("success".equals(tone)) {
            ink = SUCCESS;
        } else if ("warning".equals(tone)) {
            ink = WARNING;
        } else if ("danger".equals(tone)) {
            ink = DANGER;
        }
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(4), dp(4), dp(4), dp(8));
        ImageView glyph = iconView(icon, 33, ink);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(33), dp(33));
        iconParams.bottomMargin = dp(9);
        block.addView(glyph, iconParams);
        TextView headline = text(title, HERO_TITLE_SP, FG, medium);
        headline.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(5);
        block.addView(headline, titleParams);
        TextView detail = text(sub, SECTION_SP, MUTED, regular);
        detail.setLineSpacing(0f, 1.5f);
        block.addView(detail);
        return add(block);
    }

    // ------------------------------------------------------------------
    // 内部：追加 / 文本 / 形状
    // ------------------------------------------------------------------

    private int dp(float value) {
        return (int) (value * density + 0.5f);
    }

    private static int px(float density, float dp) {
        return (int) (dp * density + 0.5f);
    }

    private View add(View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(GAP);
        body.addView(view, lp);
        return view;
    }

    private LinearLayout copy(String label, String sub) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(label, BODY_SP, FG, regular);
        name.setLineSpacing(0f, 1.4f);
        block.addView(name);
        if (sub != null && !sub.isEmpty()) {
            TextView detail = text(sub, SECONDARY_SP, MUTED, regular);
            detail.setLineSpacing(0f, 1.4f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(1);
            block.addView(detail, lp);
        }
        return block;
    }

    private TextView text(String value, float sizeSp, int color, Typeface face) {
        TextView v = new TextView(activity);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTypeface(face);
        v.setFontFeatureSettings("kern");
        return v;
    }

    private GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable oval(int fill, int strokeWidthPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    private Drawable press(int color, int radiusDp) {
        return pressDrawable(shape(color, radiusDp), radiusDp);
    }

    private Drawable pressDrawable(GradientDrawable content, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content,
                shape(0xffffffff, radiusDp));
    }

    private Drawable choiceBackground(boolean checked) {
        GradientDrawable content = shape(checked ? ACCENT_SURFACE : SURFACE, ROW_RADIUS);
        if (checked) {
            content.setStroke(dp(1), SELECT_RING);
        }
        return pressDrawable(content, ROW_RADIUS);
    }

    // ------------------------------------------------------------------
    // 内部：Checkable 行 + radio / switch 绘制
    // ------------------------------------------------------------------

    private interface Mark {
        void setChecked(boolean checked);
    }

    /** 整行可点击的选中控件，对无障碍暴露真实的 checked 状态。 */
    private static final class CheckRow extends LinearLayout implements android.widget.Checkable {
        private final String className;
        private Mark mark;
        private boolean checked;

        CheckRow(Context context, String className) {
            super(context);
            this.className = className;
            setOrientation(HORIZONTAL);
            setFocusable(true);
            setClickable(true);
        }

        void attach(Mark mark) {
            this.mark = mark;
        }

        @Override
        public void setChecked(boolean value) {
            checked = value;
            if (mark != null) {
                mark.setChecked(value);
            }
        }

        @Override
        public boolean isChecked() {
            return checked;
        }

        @Override
        public void toggle() {
            setChecked(!checked);
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(className);
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    /** 16dp 单选标记：1dp 圆环 + 选中内点 8dp。 */
    private static final class RadioMark extends View implements Mark {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        RadioMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public void setChecked(boolean value) {
            if (checked != value) {
                checked = value;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(unit(16), widthSpec), resolveSize(unit(16), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(unit(1));
            paint.setColor(checked ? ACCENT : WEAK);
            canvas.drawCircle(cx, cy, radius - unit(0.5f), paint);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ACCENT);
                canvas.drawCircle(cx, cy, unit(4), paint);
            }
        }

        private int unit(float dp) {
            return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    /** 34x20dp 连续圆角开关，打开时轨道用强调色。 */
    private static final class SwitchMark extends View implements Mark {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;

        SwitchMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        public void setChecked(boolean value) {
            if (checked != value) {
                checked = value;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(unit(34), widthSpec), resolveSize(unit(20), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawSwitchTrack(canvas, paint, 0f, 0f, getWidth(), getHeight(), checked,
                    getResources().getDisplayMetrics().density);
        }

        private int unit(float dp) {
            return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    /** 连续圆角开关的轨道与滑块绘制；SwitchMark 与页头三态开关共用。 */
    private static void drawSwitchTrack(Canvas canvas, Paint paint, float left, float top,
                                        float width, float height, boolean checked, float density) {
        float trackRadius = height / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? ACCENT : TRACK_OFF);
        canvas.drawRoundRect(left, top, left + width, top + height, trackRadius, trackRadius, paint);
        float knob = px(density, 15) / 2f;
        float cy = top + height / 2f;
        float cx = checked ? left + width - px(density, 2.5f) - knob
                : left + px(density, 2.5f) + knob;
        paint.setColor(checked ? KNOB_ON : KNOB_OFF);
        canvas.drawCircle(cx, cy, knob, paint);
    }

    /** 状态未知/读取中：空心轨道 + 加载环，避免看起来像确定的关闭。 */
    private static void drawUnknownSwitch(Canvas canvas, Paint paint, float left, float top,
                                          float width, float height, float density) {
        float radius = height / 2f;
        float stroke = px(density, 1.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        paint.setColor(TRACK_OFF);
        canvas.drawRoundRect(left + stroke / 2f, top + stroke / 2f,
                left + width - stroke / 2f, top + height - stroke / 2f, radius, radius, paint);
        float ring = Math.min(width, height) / 2f - px(density, 3.2f);
        if (ring <= 0f) {
            return;
        }
        float cx = left + width / 2f;
        float cy = top + height / 2f;
        paint.setStrokeWidth(px(density, 2f));
        paint.setColor(MUTED);
        canvas.drawArc(new RectF(cx - ring, cy - ring, cx + ring, cy + ring),
                -90f, 260f, false, paint);
    }

    /** 页头三态开关：非 null 用开关外观，null 显示加载符号；命中区由外部设为 44dp。 */
    private static final class TriSwitchMark extends View implements Mark {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Boolean state;

        TriSwitchMark(Context context) {
            super(context);
        }

        void setState(Boolean value) {
            if (state == null ? value != null : !state.equals(value)) {
                state = value;
                invalidate();
            }
        }

        @Override
        public void setChecked(boolean value) {
            setState(value);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(resolveSize(unit(44), widthSpec), resolveSize(unit(44), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float width = unit(34);
            float height = unit(20);
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - height) / 2f;
            if (state == null) {
                drawUnknownSwitch(canvas, paint, left, top, width, height, density);
                return;
            }
            drawSwitchTrack(canvas, paint, left, top, width, height, state, density);
        }

        private int unit(float dp) {
            return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
        }
    }

    // ------------------------------------------------------------------
    // 内部：Canvas 矢量图标（非 unicode 字形）
    // ------------------------------------------------------------------

    private ImageView iconView(String name, int sizeDp, int color) {
        ImageView view = new ImageView(activity);
        Glyph glyph = new Glyph(shapeOf(name));
        glyph.setColor(color);
        glyph.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        view.setImageDrawable(glyph);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private ImageView hitIcon(String name, int sizeDp, int color, String description) {
        ImageView view = iconView(name, sizeDp, color);
        int inset = dp((HIT - sizeDp) / 2f);
        view.setPadding(inset, inset, inset, inset);
        view.setContentDescription(description);
        view.setBackground(press(0x00000000, 12));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setFocusable(true);
        return view;
    }

    private static final HashMap<String, String> ICONS = new HashMap<>();
    private static final HashMap<String, Shape> SHAPES = new HashMap<>();

    private static final class Shape {
        final Path path = new Path();
        final List<float[]> dots = new ArrayList<>();
    }

    private static Shape shapeOf(String name) {
        String data = ICONS.get(name);
        if (data == null) {
            name = "info";
            data = ICONS.get(name);
        }
        synchronized (SHAPES) {
            Shape cached = SHAPES.get(name);
            if (cached == null) {
                cached = new Shape();
                parseShape(cached, data);
                SHAPES.put(name, cached);
            }
            return cached;
        }
    }

    /** 24x24 视口的描边图标；线宽 1.65 与原型 stroke-width 一致。 */
    private static final class Glyph extends Drawable {
        private final Shape shape;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = FG;

        Glyph(Shape shape) {
            this.shape = shape;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.65f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            dotPaint.setStyle(Paint.Style.FILL);
        }

        void setColor(int value) {
            color = value;
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            dotPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            dotPaint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float scale = Math.min(bounds.width(), bounds.height()) / 24f;
            if (scale <= 0f) {
                return;
            }
            paint.setColor(color);
            dotPaint.setColor(color);
            int save = canvas.save();
            canvas.translate(bounds.left + (bounds.width() - 24f * scale) / 2f,
                    bounds.top + (bounds.height() - 24f * scale) / 2f);
            canvas.scale(scale, scale);
            canvas.drawPath(shape.path, paint);
            for (float[] dot : shape.dots) {
                canvas.drawCircle(dot[0], dot[1], 0.825f, dotPaint);
            }
            canvas.restoreToCount(save);
        }
    }

    private static boolean isCommand(char c) {
        return "MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0;
    }

    private static boolean isSeparator(char c) {
        return c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t';
    }

    private static float number(String data, int[] pos) {
        int i = pos[0];
        int n = data.length();
        while (i < n && isSeparator(data.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (data.charAt(i) == '-' || data.charAt(i) == '+')) {
            i++;
        }
        boolean digit = false;
        while (i < n && (Character.isDigit(data.charAt(i)) || data.charAt(i) == '.')) {
            digit = true;
            i++;
        }
        if (i < n && (data.charAt(i) == 'e' || data.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < n && (data.charAt(i) == '-' || data.charAt(i) == '+')) {
                i++;
            }
            int exponentStart = i;
            while (i < n && Character.isDigit(data.charAt(i))) {
                i++;
            }
            if (i == exponentStart) {
                i = save;
            }
        }
        pos[0] = i;
        if (!digit) {
            return 0f;
        }
        try {
            return Float.parseFloat(data.substring(start, i));
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }

    /** 解析 SVG 路径数据（M/L/H/V/C/S/Q/T/A/Z 及相对形式），与原型图标数据一致。 */
    private static void parseShape(Shape shape, String data) {
        Path path = shape.path;
        int[] pos = new int[]{0};
        int length = data.length();
        char command = 0;
        float cx = 0;
        float cy = 0;
        float sx = 0;
        float sy = 0;
        float lx = 0;
        float ly = 0;
        boolean cubicBefore = false;
        while (pos[0] < length) {
            if (isSeparator(data.charAt(pos[0]))) {
                pos[0]++;
                continue;
            }
            char ch = data.charAt(pos[0]);
            if (isCommand(ch)) {
                command = ch;
                pos[0]++;
                if (command == 'Z' || command == 'z') {
                    path.close();
                    cx = sx;
                    cy = sy;
                    command = 0;
                    cubicBefore = false;
                    continue;
                }
            } else if (command == 0) {
                pos[0]++;
                continue;
            }
            float x;
            float y;
            switch (command) {
                case 'M':
                case 'm':
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 'm') {
                        x += cx;
                        y += cy;
                    }
                    path.moveTo(x, y);
                    cx = x;
                    cy = y;
                    sx = x;
                    sy = y;
                    command = command == 'M' ? 'L' : 'l';
                    cubicBefore = false;
                    break;
                case 'L':
                case 'l':
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 'l') {
                        x += cx;
                        y += cy;
                    }
                    dotOrLine(shape, path, cx, cy, x, y);
                    cx = x;
                    cy = y;
                    cubicBefore = false;
                    break;
                case 'H':
                case 'h':
                    x = number(data, pos);
                    if (command == 'h') {
                        x += cx;
                    }
                    dotOrLine(shape, path, cx, cy, x, cy);
                    cx = x;
                    cubicBefore = false;
                    break;
                case 'V':
                case 'v':
                    y = number(data, pos);
                    if (command == 'v') {
                        y += cy;
                    }
                    dotOrLine(shape, path, cx, cy, cx, y);
                    cy = y;
                    cubicBefore = false;
                    break;
                case 'C':
                case 'c': {
                    float x1 = number(data, pos);
                    float y1 = number(data, pos);
                    float x2 = number(data, pos);
                    float y2 = number(data, pos);
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 'c') {
                        x1 += cx;
                        y1 += cy;
                        x2 += cx;
                        y2 += cy;
                        x += cx;
                        y += cy;
                    }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lx = x2;
                    ly = y2;
                    cx = x;
                    cy = y;
                    cubicBefore = true;
                    break;
                }
                case 'S':
                case 's': {
                    float x2 = number(data, pos);
                    float y2 = number(data, pos);
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 's') {
                        x2 += cx;
                        y2 += cy;
                        x += cx;
                        y += cy;
                    }
                    float x1 = cubicBefore ? 2 * cx - lx : cx;
                    float y1 = cubicBefore ? 2 * cy - ly : cy;
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lx = x2;
                    ly = y2;
                    cx = x;
                    cy = y;
                    cubicBefore = true;
                    break;
                }
                case 'Q':
                case 'q': {
                    float x1 = number(data, pos);
                    float y1 = number(data, pos);
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 'q') {
                        x1 += cx;
                        y1 += cy;
                        x += cx;
                        y += cy;
                    }
                    path.quadTo(x1, y1, x, y);
                    lx = x1;
                    ly = y1;
                    cx = x;
                    cy = y;
                    cubicBefore = false;
                    break;
                }
                case 'T':
                case 't': {
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 't') {
                        x += cx;
                        y += cy;
                    }
                    float x1 = cubicBefore ? 2 * cx - lx : cx;
                    float y1 = cubicBefore ? 2 * cy - ly : cy;
                    path.quadTo(x1, y1, x, y);
                    lx = x1;
                    ly = y1;
                    cx = x;
                    cy = y;
                    cubicBefore = false;
                    break;
                }
                case 'A':
                case 'a': {
                    float rx = number(data, pos);
                    float ry = number(data, pos);
                    float rotation = number(data, pos);
                    boolean largeArc = number(data, pos) != 0f;
                    boolean sweep = number(data, pos) != 0f;
                    x = number(data, pos);
                    y = number(data, pos);
                    if (command == 'a') {
                        x += cx;
                        y += cy;
                    }
                    arcTo(path, cx, cy, rx, ry, rotation, largeArc, sweep, x, y);
                    cx = x;
                    cy = y;
                    cubicBefore = false;
                    break;
                }
                default:
                    pos[0]++;
                    break;
            }
        }
    }

    /** 零长线段（原型 h.01）改为圆点，避免描边退化。 */
    private static void dotOrLine(Shape shape, Path path, float x0, float y0, float x1, float y1) {
        if (Math.abs(x1 - x0) < 0.05f && Math.abs(y1 - y0) < 0.05f) {
            shape.dots.add(new float[]{x1, y1});
            return;
        }
        path.lineTo(x1, y1);
    }

    /** 端点椭圆弧转三次贝塞尔（SVG 1.1 F.6.5）。 */
    private static void arcTo(Path path, float x0, float y0, float rx, float ry,
                              float rotationDeg, boolean largeArc, boolean sweep,
                              float x1, float y1) {
        if (rx == 0f || ry == 0f) {
            path.lineTo(x1, y1);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(rotationDeg % 360f);
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);
        double dx2 = (x0 - x1) / 2.0;
        double dy2 = (y0 - y1) / 2.0;
        double x1p = cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1.0) {
            double factor = Math.sqrt(lambda);
            rx *= factor;
            ry *= factor;
        }
        double numerator = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double denominator = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double sign = largeArc != sweep ? 1.0 : -1.0;
        double coefficient = denominator == 0.0 ? 0.0
                : sign * Math.sqrt(Math.max(0.0, numerator / denominator));
        double cxp = coefficient * rx * y1p / ry;
        double cyp = -coefficient * ry * x1p / rx;
        double centerX = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0;
        double centerY = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0;
        double ux = (x1p - cxp) / rx;
        double uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx;
        double vy = (-y1p - cyp) / ry;
        double startAngle = Math.atan2(uy, ux);
        double deltaAngle = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweep && deltaAngle > 0.0) {
            deltaAngle -= 2.0 * Math.PI;
        }
        if (sweep && deltaAngle < 0.0) {
            deltaAngle += 2.0 * Math.PI;
        }
        int segments = (int) Math.ceil(Math.abs(deltaAngle) / (Math.PI / 2.0));
        if (segments == 0) {
            path.lineTo(x1, y1);
            return;
        }
        double delta = deltaAngle / segments;
        double alpha = 4.0 / 3.0 * Math.tan(delta / 4.0);
        double angle = startAngle;
        for (int i = 0; i < segments; i++) {
            double angle2 = angle + delta;
            double cos1 = Math.cos(angle);
            double sin1 = Math.sin(angle);
            double cos2 = Math.cos(angle2);
            double sin2 = Math.sin(angle2);
            double e1x = centerX + rx * cosPhi * cos1 - ry * sinPhi * sin1;
            double e1y = centerY + rx * sinPhi * cos1 + ry * cosPhi * sin1;
            double e2x = centerX + rx * cosPhi * cos2 - ry * sinPhi * sin2;
            double e2y = centerY + rx * sinPhi * cos2 + ry * cosPhi * sin2;
            double d1x = -rx * cosPhi * sin1 - ry * sinPhi * cos1;
            double d1y = -rx * sinPhi * sin1 + ry * cosPhi * cos1;
            double d2x = -rx * cosPhi * sin2 - ry * sinPhi * cos2;
            double d2y = -rx * sinPhi * sin2 + ry * cosPhi * cos2;
            path.cubicTo((float) (e1x + alpha * d1x), (float) (e1y + alpha * d1y),
                    (float) (e2x - alpha * d2x), (float) (e2y - alpha * d2y),
                    (float) e2x, (float) e2y);
            angle = angle2;
        }
    }

    static {
        ICONS.put("spark", "M12 3 14.8 9.2 21 12l-6.2 2.8L12 21l-2.8-6.2L3 12l6.2-2.8Z");
        ICONS.put("chevron", "m9 5 7 7-7 7");
        ICONS.put("back", "m14 5-7 7 7 7");
        ICONS.put("check", "m5 12 4 4L19 6");
        ICONS.put("close", "m6 6 12 12M18 6 6 18");
        ICONS.put("layers", "m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5");
        ICONS.put("compass", "m16 8-3 5-5 3 3-5 5-3ZM21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0");
        ICONS.put("palette", "M21 12a9 9 0 1 0-9 9h2a2 2 0 0 0 0-4 2 2 0 0 1 0-4h5a2 2 0 0 0 2-1ZM7 8h.01M11 6h.01M16 8h.01");
        ICONS.put("eye", "M2 12s3-6 10-6 10 6 10 6-3 6-10 6S2 12 2 12Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0");
        ICONS.put("battery", "M3 7h16v10H3V7Zm19 3v4M6 10h8v4H6");
        ICONS.put("dot", "M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0");
        ICONS.put("app", "M4 4h6v6H4V4Zm10 0h6v6h-6V4ZM4 14h6v6H4v-6Zm10 0h6v6h-6v-6");
        ICONS.put("history", "M3 12a9 9 0 1 0 3-6M3 3v6h6m3-2v5l3 2");
        ICONS.put("scan", "M8 3H3v5m13-5h5v5M3 16v5h5m8 0h5v-5M7 12h10");
        ICONS.put("key", "M14 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Zm-1 3 8 8v3h-3v-3h-3v-3");
        ICONS.put("watch", "M8 6V2h8v4m-8 12v4h8v-4M7 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Zm5 3v3l2 1");
        ICONS.put("info", "M12 10v7m0-10h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0");
        ICONS.put("book", "M12 5C8 2 4 3 3 4v15c3-2 6-1 9 1 3-2 6-3 9-1V4c-3-2-6-1-9 1Zm0 0v15");
        ICONS.put("download", "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5");
        ICONS.put("cloud", "M7 19a5 5 0 0 1-1-10 7 7 0 0 1 13 2 4 4 0 0 1-1 8H7");
        ICONS.put("refresh", "M20 8a9 9 0 0 0-15-3L2 8m0-5v5h5m-3 8a9 9 0 0 0 15 3l3-3m0 5v-5h-5");
        ICONS.put("file", "M5 3h9l5 5v13H5V3Zm9 0v6h5M8 13h8m-8 4h5");
        ICONS.put("shield", "m12 3 9 4v5c0 5-9 9-9 9s-9-4-9-9V7l9-4Zm-4 9 3 3 5-6");
        ICONS.put("undo", "M8 5 3 10l5 5M3 10h11a6 6 0 0 1 0 12");
        ICONS.put("warning", "m12 3 10 18H2L12 3Zm0 6v5m0 3h.01");
        ICONS.put("storage", "M4 3h16v18H4V3Zm0 6h16M8 6h.01M8 13h8m-8 4h5");
        ICONS.put("sliders", "M4 6h16M4 12h16M4 18h16M8 3v6m8 0v6m-8 0v6");
        ICONS.put("pause", "M7 4v16M17 4v16");
        ICONS.put("trash", "M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7");
        ICONS.put("search", "M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0Zm-2 5 6 6");
        ICONS.put("flask", "M9 3h6M10 3v7L4 20h16l-6-10V3M7 15h10");
        ICONS.put("list", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01");
        ICONS.put("settings", "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8Zm0-6v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2");
    }
}
