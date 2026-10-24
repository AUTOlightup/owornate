package dev.codex.watchworkshop;

import java.util.Locale;

/** 控制中心主题配置：配色方式、种子色和连续圆角。编辑中的草稿与 helper 回执共用这个模型。 */
public final class ThemeConfig {
    public final String mode;
    public final int seed;
    public final boolean corners;

    public ThemeConfig(String mode, int seed, boolean corners) {
        if (!"auto".equals(mode) && !"fixed".equals(mode) && !"original".equals(mode)) {
            throw new IllegalArgumentException("Unsupported mode");
        }
        this.mode = mode;
        this.seed = seed | 0xff000000;
        this.corners = corners;
    }

    public String seedHex() {
        return String.format(Locale.ROOT, "#%08X", seed);
    }

    public String modeLabel() {
        return modeLabel(mode);
    }

    /** 配色方式的中文名；认不出的写法（例如回滚记录里的旧值）显示“未知”。 */
    static String modeLabel(String mode) {
        if ("auto".equals(mode)) return "自动取色";
        if ("fixed".equals(mode)) return "固定颜色";
        if ("original".equals(mode)) return "系统原色";
        return "未知";
    }

    /** 三项设置都相同（按值比较，不是同一个对象）。 */
    boolean sameAs(ThemeConfig other) {
        return other != null && mode.equals(other.mode) && seed == other.seed && corners == other.corners;
    }
}
