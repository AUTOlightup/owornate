package dev.codex.watchworkshop.palette;

import dev.codex.watchworkshop.palette.hct.Hct;

/**
 * 表盘主题调色板离线预览：与生产 helper 的 {@code dev.codex.watchtheme.ThemePalette} 使用同一套
 * 官方 Material Color Utilities（MCU）HCT 派生参数，用于在腕上工坊内本地复算颜色。
 *
 * <p>本类只做计算，不代表已在设备生效；界面必须自行标注「预览，未生效」。类无状态、无 Android 依赖，
 * 可在纯 JVM 中离线测试。
 *
 * <p>取色参数与生产 {@code ThemePalette.fromSeed} 完全一致：强调色 C36、中性色 C6、状态色 C4；
 * tone 依次为 active 80 / onActive 20 / track 30 / off 15 / onOff 90 / secondary 70 / muted 50 / status 95。
 * 字段包含生产调色板的全部字段（含 track），保证与生产参数一一对应。
 */
public final class ThemePalettePreview {

  /** 强调色（accent）色度，决定主色饱和程度。 */
  private static final double ACCENT_CHROMA = 36.0;
  /** 中性色（neutral）色度，保持选中/轨道底色接近灰阶。 */
  private static final double NEUTRAL_CHROMA = 6.0;

  private static final int TONE_ACTIVE = 80;
  private static final int TONE_ON_ACTIVE = 20;
  private static final int TONE_TRACK = 30;
  private static final int TONE_OFF = 15;
  private static final int TONE_ON_OFF = 90;

  private static final int TONE_SECONDARY = 70;
  private static final int TONE_MUTED = 50;
  private static final int TONE_STATUS = 95;

  /** 用于派生本调色板的种子色（ARGB）。 */
  public final int seed;
  /** 强调色主色（accent / T80）。 */
  public final int active;
  /** 强调色上的前景色（accent / T20）。 */
  public final int onActive;
  /** 中性底色（neutral / T15）。 */
  public final int off;
  /** 中性底色上的前景色（neutral / T90）。 */
  public final int onOff;
  /** 强调色轨道/次要底色（accent / T30）。 */
  public final int track;
  /** 次要中性色（neutral / T70），用于黑底上的次级文字。 */
  public final int secondary;
  /** 弱化中性色（neutral / T50），用于黑底上的更弱文字。 */
  public final int muted;
  /** 状态栏近白主题色（同色相 / C4 / T95）。 */
  public final int status;

  private ThemePalettePreview(
      int seed,
      int active,
      int onActive,
      int off,
      int onOff,
      int track,
      int secondary,
      int muted,
      int status) {
    this.seed = seed;
    this.active = active;
    this.onActive = onActive;
    this.off = off;
    this.onOff = onOff;
    this.track = track;
    this.secondary = secondary;
    this.muted = muted;
    this.status = status;
  }

  /**
   * 由单一 ARGB 种子色派生调色板。使用种子色的 HCT 色相，分别生成强调色与中性色两套固定色阶。
   *
   * @param seed ARGB 种子色；alpha 不参与取色，仅保存在 {@link #seed} 字段中。
   * @return 由该种子色派生的调色板。
   */
  public static ThemePalettePreview fromSeed(int seed) {
    double hue = Hct.fromInt(seed).getHue();
    int active = Hct.from(hue, ACCENT_CHROMA, TONE_ACTIVE).toInt();
    int onActive = Hct.from(hue, ACCENT_CHROMA, TONE_ON_ACTIVE).toInt();
    int track = Hct.from(hue, ACCENT_CHROMA, TONE_TRACK).toInt();
    int off = Hct.from(hue, NEUTRAL_CHROMA, TONE_OFF).toInt();
    int onOff = Hct.from(hue, NEUTRAL_CHROMA, TONE_ON_OFF).toInt();
    int secondary = Hct.from(hue, NEUTRAL_CHROMA, TONE_SECONDARY).toInt();
    int muted = Hct.from(hue, NEUTRAL_CHROMA, TONE_MUTED).toInt();
    int status = Hct.from(hue, 4.0, TONE_STATUS).toInt();
    return new ThemePalettePreview(seed, active, onActive, off, onOff, track, secondary, muted, status);
  }
}
