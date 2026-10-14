package dev.codex.watchworkshop;

/** Pure model shared by the draft editor and the future theme adapter. */
public final class ThemeConfig {
    public final String mode;
    public final int seed;
    public final boolean corners;
    public ThemeConfig(String mode, int seed, boolean corners) {
        if (!"auto".equals(mode) && !"fixed".equals(mode) && !"original".equals(mode))
            throw new IllegalArgumentException("Unsupported mode");
        this.mode=mode; this.seed=seed | 0xff000000; this.corners=corners;
    }
    public String seedHex(){return String.format(java.util.Locale.ROOT,"#%08X",seed);}
    public String modeLabel(){return "auto".equals(mode)?"自动取色":"fixed".equals(mode)?"固定颜色":"系统原色";}
}
