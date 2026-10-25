package dev.codex.watchworkshop;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * OWOrnate 管理器原生视觉组件：黑底、暖金强调、中性卡面与一致的触控反馈。
 * 页面与编辑器共享此样式；小组件 RemoteViews 及其原样预览独立渲染。
 * 所有正文使用 sp，交互命中区至少 44dp；本类只管理内容块与块间距。
 */
public final class WorkshopUi {

    // ------------------------------------------------------------------
    // 管理器语义色（不用于小组件 RemoteViews）
    // ------------------------------------------------------------------
    public static final int BG = 0xff000000;
    public static final int SURFACE = 0xff15181c;
    public static final int SOFT = 0xff20252b;
    public static final int OUTLINE = 0xff2b3037;
    public static final int FG = 0xfff4f1eb;
    public static final int MUTED = 0xffadb2ba;
    public static final int WEAK = 0xff838a95;
    public static final int ACCENT = 0xfff1c779;
    public static final int ACCENT_SURFACE = 0xff2b251a;
    public static final int ON_ACCENT = 0xff241c0e;
    public static final int SUCCESS = 0xff9bd9bb;
    public static final int WARNING = 0xffedc77f;
    public static final int DANGER = 0xffff9991;
    /** note 语义底色。 */
    public static final int WARNING_BG = 0xff282218;
    public static final int SUCCESS_BG = 0xff17251e;
    public static final int DANGER_BG = 0xff2c1b1d;
    /** danger 按钮文字色。 */
    public static final int DANGER_INK = 0xff32120f;
    /** 概览卡片副文本色。 */
    public static final int SUMMARY_LABEL = 0xffede0c5;
    public static final int SUMMARY_STATUS = 0xffc7b998;
    /** 控件内部件。 */
    public static final int TRACK_OFF = 0xff383f49;
    public static final int KNOB_OFF = 0xffc2c8d0;
    public static final int KNOB_ON = 0xff392b16;
    public static final int SELECT_RING = 0xff75603b;

    // ------------------------------------------------------------------
    // 管理器尺寸（dp）；维持原有 44dp 最小触控范围
    // ------------------------------------------------------------------
    public static final int GAP = 6;           // .watch-content gap 12px
    public static final int PAGE_SIDE = 8;     // .scroll 16px（由 MainActivity 应用）
    public static final int PAGE_TOP = 8; // 页面上边距
    public static final int PAGE_BOTTOM = 24; // 滚动尾部留白
    public static final int HIT = 44;          // 44x44dp 命中区 / 主按钮 88px
    public static final int ROW_MIN = 48;      // .w-row 96px
    public static final int ROW_RADIUS = 17; // 导航与选择行
    public static final int BTN_RADIUS = 16; // 操作按钮
    public static final int NOTE_RADIUS = 14; // 状态与输入框
    public static final int CARD_RADIUS = 20; // 概览与说明卡
    public static final int HEAD_ICON = 18; // 页头图标
    public static final int ROW_ICON = 18; // 图标底座内的图形
    public static final int CHEVRON = 12;      // .row-chevron 24px
    public static final int TITLE_SP = 20;     // .watch-head.root h2 40px
    public static final int HEAD_SP = 16; // 详情标题
    public static final int BODY_SP = 13;      // 26px
    public static final int SECONDARY_SP = 11; // 22px
    public static final int SECTION_SP = 11; // 分组与辅助标题
    public static final int HERO_TITLE_SP = 18;

    private static final int RIPPLE = 0x22ffffff;
    private static final int ICON_SURFACE = 0xff24292f;

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
     * 与内容一起滚动，无吸顶；root=true 时标题 20sp，否则 16sp。
     * back/settings 传 null 表示不显示对应按钮。
     */
    public void header(String title, boolean root, Runnable back, Runnable settings) {
        LinearLayout head = headBase(title, root, false, back);
        if (settings != null) {
            ImageView hit = hitIcon("sliders", HEAD_ICON, ACCENT, "设备与设置");
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
     * 标题占满剩余宽度，最多两行，确保大字体下返回、标题、开关互不重叠。
     *
     * <p>内容描述随模块标题和启停状态变化。
     * enabled=false 时关闭点击并把开关压暗以给出明确反馈。
     */
    public void headerToggle(String title, Runnable back, Boolean checked, boolean enabled,
                             Runnable action) {
        LinearLayout head = headBase(title, false, true, back);
        TriSwitchMark mark = new TriSwitchMark(activity);
        mark.setState(checked);
        mark.setBackground(press(0x00000000, 12));
        mark.setContentDescription(toggleDescription(title, checked));
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
     * root 决定标题 20sp/16sp 与左侧 6dp 间距；compact 时最多两行。
     */
    private LinearLayout headBase(String title, boolean root, boolean compact, Runnable back) {
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
        if (compact) {
            label.setMaxLines(2);
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

    private static String toggleDescription(String title, Boolean checked) {
        if (checked == null) {
            return "正在读取" + title + "状态";
        }
        return (checked ? "停用" : "启用") + title;
    }

    // ------------------------------------------------------------------
    // 内容块
    // ------------------------------------------------------------------

    /** 正文 13sp / 行高 1.4。 */
    public TextView text(String value) {
        TextView v = text(value, BODY_SP, FG, regular);
        v.setLineSpacing(0f, 1.4f);
        v.setPadding(dp(4), dp(3), dp(4), dp(5));
        return (TextView) add(v);
    }

    /** 轻量辅助说明，不使用卡片底；错误与重要状态仍用 note。 */
    public TextView caption(String value) {
        TextView v = text(value, SECONDARY_SP, MUTED, regular);
        v.setLineSpacing(0f, 1.35f);
        v.setPadding(dp(5), dp(2), dp(5), dp(4));
        return (TextView) add(v);
    }

    /** 说明卡 11sp，默认 surface 底。 */
    public TextView note(String value) {
        return note(value, "");
    }

    /** 说明卡。tone: "" 中性 / warning / success / danger。 */
    public TextView note(String value, String tone) {
        TextView v = text(value, SECONDARY_SP, toneInk(tone, MUTED), regular);
        v.setLineSpacing(0f, 1.4f);
        v.setPadding(dp(11), dp(9), dp(11), dp(9));
        v.setBackground(panel(toneBackground(tone), NOTE_RADIUS));
        return (TextView) add(v);
    }

    /** 小节标题 11sp，与下一组内容保持紧凑间距。 */
    public TextView title(String value) {
        TextView v = text(value, SECTION_SP, MUTED, medium);
        v.setLineSpacing(0f, 1.25f);
        v.setPadding(dp(5), dp(7), dp(5), dp(2));
        return (TextView) add(v);
    }

    /** 键值信息：名称与值上下排列，长标识可断行。 */
    public View value(String label, String content) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(11), dp(9), dp(11), dp(10));
        block.setBackground(panel(SURFACE, NOTE_RADIUS));
        block.addView(text(label, SECONDARY_SP, MUTED, regular));
        TextView val = text(content, BODY_SP, FG, regular);
        val.setLineSpacing(0f, 1.25f);
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
        TextView v = text(label, BODY_SP, ink, medium);
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
            v.setAlpha(0.5f);
            v.setContentDescription(label + "，暂不可执行");
        }
        return (TextView) add(v);
    }

    /** 导航行：28dp 图标底座 + 名称 + 可选说明 + 箭头，整行打开同一目标。 */
    public View row(String label, String sub, String icon, Runnable action) {
        return navigationRow(label, sub, icon, false, action);
    }

    /** 插件库入口：与普通功能共享布局，以暖金底色突出。 */
    public View highlightedRow(String label, String icon, Runnable action) {
        return navigationRow(label, "", icon, true, action);
    }

    private View navigationRow(String label, String sub, String icon, boolean highlighted, Runnable action) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(ROW_MIN));
        r.setPadding(dp(10), dp(10), dp(10), dp(10));
        r.setBackground(highlighted ? pressDrawable(accentPanel(ROW_RADIUS), ROW_RADIUS)
                : press(SURFACE, ROW_RADIUS));
        if (icon != null && !icon.isEmpty()) {
            ImageView glyph = iconView(icon, ROW_ICON, ACCENT);
            glyph.setBackground(shape(highlighted ? 0xff453620 : ICON_SURFACE, 10));
            glyph.setPadding(dp(5), dp(5), dp(5), dp(5));
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(28), dp(28));
            iconParams.rightMargin = dp(8);
            r.addView(glyph, iconParams);
        }
        r.addView(copy(label, sub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView chevron = iconView("chevron", CHEVRON, WEAK);
        LinearLayout.LayoutParams chevronParams =
                new LinearLayout.LayoutParams(dp(CHEVRON), dp(CHEVRON));
        chevronParams.leftMargin = dp(4);
        r.addView(chevron, chevronParams);
        r.setContentDescription(describe(label, sub));
        final Runnable tap = action;
        r.setOnClickListener(v -> tap.run());
        return add(r);
    }

    /** 单选行：整行选择，右侧 radio 反映真实选中状态；选中时用强调色低亮面。 */
    public View choice(String label, String sub, boolean checked, Runnable action) {
        CheckRow r = new CheckRow(activity, "android.widget.RadioButton");
        return checkRow(r, choiceBackground(checked), label, sub, new RadioMark(activity),
                dp(16), dp(16), checked, action);
    }

    /** 开关行：点击整个 48dp 行，右侧为 Canvas 绘制的连续圆角 Switch。 */
    public View toggle(String label, String sub, boolean checked, Runnable action) {
        CheckRow r = new CheckRow(activity, "android.widget.Switch");
        return checkRow(r, press(SURFACE, ROW_RADIUS), label, sub, new SwitchMark(activity),
                dp(34), dp(20), checked, action);
    }

    /** choice 与 toggle 共用的行：文字在左，选中标记在右，整行可点。 */
    private <M extends View & Mark> View checkRow(CheckRow r, Drawable background, String label,
            String sub, M mark, int markWidth, int markHeight, boolean checked, Runnable action) {
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(ROW_MIN));
        r.setPadding(dp(11), dp(9), dp(11), dp(9));
        r.setBackground(background);
        r.addView(copy(label, sub),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(markWidth, markHeight);
        markParams.leftMargin = dp(7);
        r.addView(mark, markParams);
        r.attach(mark);
        r.setChecked(checked);
        r.setContentDescription(describe(label, sub));
        final Runnable tap = action;
        r.setOnClickListener(v -> tap.run());
        return add(r);
    }

    /**
     * 输入框：surface 圆角底、10/8dp 内边距、提示用弱化色；调用方再设输入类型、行数等。
     * 和其他内容块一样追加到页面末尾，自带 6dp 下间距。
     */
    public EditText input(String hint, String value, float sizeSp) {
        EditText input = new EditText(activity);
        input.setText(value);
        input.setHint(hint);
        input.setHintTextColor(WEAK);
        input.setTextColor(FG);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        input.setTypeface(regular);
        input.setMinHeight(dp(HIT));
        GradientDrawable focused = panel(SURFACE, NOTE_RADIUS);
        focused.setStroke(dp(1), ACCENT);
        StateListDrawable field = new StateListDrawable();
        field.addState(new int[] { android.R.attr.state_focused }, focused);
        field.addState(new int[0], panel(SURFACE, NOTE_RADIUS));
        input.setBackground(field);
        input.setBackgroundTintList(null);
        input.setPadding(dp(11), dp(10), dp(11), dp(10));
        return (EditText) add(input);
    }

    /**
     * 滚轮行：几个 {@link WheelPicker} 并排放进一块 surface 底的容器，中间选中带连成一条。
     * 由 {@link Wheels} 组装日期和时刻，调用方只需要处理回调。
     */
    public View wheels(WheelPicker... pickers) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(4), dp(2), dp(4), dp(2));
        r.setBackground(panel(SURFACE, ROW_RADIUS));
        for (WheelPicker picker : pickers) {
            r.addView(picker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return add(r);
    }

    /**
     * 翻页条：左右各一个 44dp 箭头，中间是标题和一行说明，点中间触发 center（例如“回到本周”）。
     * prev/next/center 传 null 表示该处不可点。
     */
    public View pager(String title, String sub, Runnable prev, Runnable next, Runnable center) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(panel(SURFACE, ROW_RADIUS));
        ImageView left = hitIcon("back", 16, prev == null ? WEAK : FG, "上一页");
        if (prev != null) left.setOnClickListener(v -> prev.run());
        r.addView(left, new LinearLayout.LayoutParams(dp(HIT), dp(HIT)));
        LinearLayout middle = new LinearLayout(activity);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        middle.setMinimumHeight(dp(HIT));
        middle.setPadding(dp(2), dp(6), dp(2), dp(6));
        TextView head = text(title, BODY_SP, FG, medium);
        head.setGravity(Gravity.CENTER);
        head.setLineSpacing(0f, 1.2f);
        middle.addView(head);
        if (sub != null && !sub.isEmpty()) {
            TextView line = text(sub, SECONDARY_SP, MUTED, regular);
            line.setGravity(Gravity.CENTER);
            line.setFontFeatureSettings("tnum");
            middle.addView(line);
        }
        if (center != null) {
            middle.setBackground(press(0x00000000, 12));
            middle.setOnClickListener(v -> center.run());
        }
        middle.setContentDescription(describe(title, sub));
        r.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView right = hitIcon("chevron", 16, next == null ? WEAK : FG, "下一页");
        if (next != null) right.setOnClickListener(v -> next.run());
        r.addView(right, new LinearLayout.LayoutParams(dp(HIT), dp(HIT)));
        return add(r);
    }
    /** 首页概览卡：数字 + 说明 + 状态点。 */
    public View summary(Integer count, String label, String status) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(9), dp(13), dp(9));
        card.setBackground(accentPanel(CARD_RADIUS));

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView number = text(count==null?"—":String.valueOf(count), 28, ACCENT, medium);
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

        if (status != null && !status.isEmpty()) {
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
        }
        card.setContentDescription(describe(count == null ? "功能数量待确认" : count + " " + label, status));
        return add(card);
    }

    /** 结果/空态头部：33dp 图标 + 结果标题 + 说明。tone: "" accent / success / warning / danger。 */
    public View hero(String icon, String title, String sub, String tone) {
        int ink = toneInk(tone, ACCENT);
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(12), dp(12), dp(13));
        block.setBackground(panel(SURFACE, CARD_RADIUS));
        ImageView glyph = iconView(icon, 22, ink);
        glyph.setPadding(dp(7), dp(7), dp(7), dp(7));
        glyph.setBackground(shape(tone.isEmpty() ? ICON_SURFACE : toneBackground(tone), 12));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        iconParams.bottomMargin = dp(8);
        block.addView(glyph, iconParams);
        TextView headline = text(title, HERO_TITLE_SP, FG, medium);
        headline.setLineSpacing(0f, 1.35f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(5);
        block.addView(headline, titleParams);
        if (sub != null && !sub.isEmpty()) {
            TextView detail = text(sub, SECONDARY_SP, MUTED, regular);
            detail.setLineSpacing(0f, 1.4f);
            block.addView(detail);
        }
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

    /** 自绘控件里的 dp 换算。 */
    private static int px(View view, float dp) {
        return px(view.getResources().getDisplayMetrics().density, dp);
    }

    /** 语义文字色：success / warning / danger，其余（含空串）用 fallback。 */
    private static int toneInk(String tone, int fallback) {
        if ("success".equals(tone)) return SUCCESS;
        if ("warning".equals(tone)) return WARNING;
        if ("danger".equals(tone)) return DANGER;
        return fallback;
    }

    /** 说明卡的语义底色；中性为 surface。 */
    private static int toneBackground(String tone) {
        if ("success".equals(tone)) return SUCCESS_BG;
        if ("warning".equals(tone)) return WARNING_BG;
        if ("danger".equals(tone)) return DANGER_BG;
        return SURFACE;
    }

    private View add(View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(GAP);
        body.addView(view, lp);
        return view;
    }

    /** 无障碍朗读文本：名称，有说明时再接上说明。 */
    private static String describe(String label, String sub) {
        return sub == null || sub.isEmpty() ? label : label + "，" + sub;
    }

    private LinearLayout copy(String label, String sub) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(label, BODY_SP, FG, regular);
        name.setLineSpacing(0f, 1.25f);
        block.addView(name);
        if (sub != null && !sub.isEmpty()) {
            TextView detail = text(sub, SECONDARY_SP, MUTED, regular);
            detail.setLineSpacing(0f, 1.25f);
            detail.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(3);
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
        v.setIncludeFontPadding(false);
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

    private GradientDrawable panel(int color, int radiusDp) {
        GradientDrawable drawable = shape(color, radiusDp);
        drawable.setStroke(Math.max(1, dp(0.5f)), color == ACCENT_SURFACE ? SELECT_RING : OUTLINE);
        return drawable;
    }

    private GradientDrawable accentPanel(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xff352c1d, 0xff211e17 });
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(Math.max(1, dp(0.5f)), SELECT_RING);
        return drawable;
    }

    private Drawable press(int color, int radiusDp) {
        GradientDrawable content = color == SURFACE || color == SOFT || color == ACCENT_SURFACE
                ? panel(color, radiusDp) : shape(color, radiusDp);
        return pressDrawable(content, radiusDp);
    }

    private Drawable pressDrawable(GradientDrawable content, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content,
                shape(0xffffffff, radiusDp));
    }

    private Drawable choiceBackground(boolean checked) {
        GradientDrawable content = panel(checked ? ACCENT_SURFACE : SURFACE, ROW_RADIUS);
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
            setMeasuredDimension(resolveSize(px(this, 16), widthSpec), resolveSize(px(this, 16), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = Math.min(getWidth(), getHeight()) / 2f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(px(this, 1));
            paint.setColor(checked ? ACCENT : WEAK);
            canvas.drawCircle(cx, cy, radius - px(this, 0.5f), paint);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ACCENT);
                canvas.drawCircle(cx, cy, px(this, 4), paint);
            }
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
            setMeasuredDimension(resolveSize(px(this, 34), widthSpec), resolveSize(px(this, 20), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawSwitchTrack(canvas, paint, 0f, 0f, getWidth(), getHeight(), checked,
                    getResources().getDisplayMetrics().density);
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
            setMeasuredDimension(resolveSize(px(this, 44), widthSpec), resolveSize(px(this, 44), heightSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float width = px(this, 34);
            float height = px(this, 20);
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - height) / 2f;
            if (state == null) {
                drawUnknownSwitch(canvas, paint, left, top, width, height, density);
                return;
            }
            drawSwitchTrack(canvas, paint, left, top, width, height, state, density);
        }
    }

    // ------------------------------------------------------------------
    // 内部：矢量图标（res/drawable/icon_*.xml，24x24 描边；非 unicode 字形）
    // ------------------------------------------------------------------

    private ImageView iconView(String name, int sizeDp, int color) {
        ImageView view = new ImageView(activity);
        Drawable glyph = activity.getDrawable(iconRes(name)).mutate();
        glyph.setTint(color);
        view.setImageDrawable(glyph);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private ImageView hitIcon(String name, int sizeDp, int color, String description) {
        ImageView view = iconView(name, sizeDp, color);
        int inset = dp((HIT - sizeDp) / 2f);
        view.setContentDescription(description);
        view.setBackground(new InsetDrawable(press(SURFACE, 12), dp(6)));
        // InsetDrawable 会提供自己的 padding，图形留白必须最后设置。
        view.setPadding(inset, inset, inset, inset);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setFocusable(true);
        return view;
    }

    /** 图标名到矢量资源；未知名字退回 info。 */
    private static int iconRes(String name) {
        switch (name) {
            case "spark": return R.drawable.icon_spark;
            case "chevron": return R.drawable.icon_chevron;
            case "clock": return R.drawable.icon_clock;
            case "back": return R.drawable.icon_back;
            case "check": return R.drawable.icon_check;
            case "layers": return R.drawable.icon_layers;
            case "compass": return R.drawable.icon_compass;
            case "palette": return R.drawable.icon_palette;
            case "dot": return R.drawable.icon_dot;
            case "scan": return R.drawable.icon_scan;
            case "key": return R.drawable.icon_key;
            case "info": return R.drawable.icon_info;
            case "book": return R.drawable.icon_book;
            case "refresh": return R.drawable.icon_refresh;
            case "file": return R.drawable.icon_file;
            case "shield": return R.drawable.icon_shield;
            case "warning": return R.drawable.icon_warning;
            case "sliders": return R.drawable.icon_sliders;
            default: return R.drawable.icon_info;
        }
    }
}
