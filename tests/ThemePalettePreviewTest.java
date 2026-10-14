import dev.codex.watchworkshop.palette.ThemePalettePreview;
import dev.codex.watchworkshop.palette.hct.Hct;

/**
 * 腕上工坊真实 HCT 预览的离线验证：纯 Java main 断言，无 JUnit、无 Android、不连设备。
 *
 * <p>覆盖口径：
 * <ul>
 *   <li>6 个界面预设种子（与 MainActivity.colors() 一致）与实机实际种子 #F0BD50 的期待色逐字段比对；
 *   <li>#F0BD50 的 5 个字段使用独立锚点（生产 ThemePaletteTest 断言值 + 实机 status JSON 记录值）；
 *   <li>按官方算法语义复核 tone / 色相跟随，并独立复算一遍 HCT；
 *   <li>黑底文字对比度下限、seed 字段保留与 alpha 不参与取色、可重复性。
 * </ul>
 */
public final class ThemePalettePreviewTest {

  /** 生产 ThemePalette 的取色参数，测试内独立复算时使用。 */
  private static final double ACCENT_CHROMA = 36.0;
  private static final double NEUTRAL_CHROMA = 6.0;
  private static final double STATUS_CHROMA = 4.0;

  private static final int TONE_ACTIVE = 80;
  private static final int TONE_ON_ACTIVE = 20;
  private static final int TONE_TRACK = 30;
  private static final int TONE_OFF = 15;
  private static final int TONE_ON_OFF = 90;
  private static final int TONE_SECONDARY = 70;
  private static final int TONE_MUTED = 50;
  private static final int TONE_STATUS = 95;

  /** 6 个界面预设 + 实机实际种子 #F0BD50；预设与 MainActivity.colors() 的取值一致。 */
  private static final int[] SEEDS = {
    0xFFEBC16C, // 暖金
    0xFF91BDFF, // 雾蓝
    0xFFBCA8F5, // 淡紫
    0xFF99D5B4, // 薄荷
    0xFFEFA898, // 珊瑚
    0xFFE3E9F1, // 月白
    0xFFF0BD50  // 实机 status JSON 记录的 seed
  };

  private static final String[] NAMES = {
    "暖金 EBC16C", "雾蓝 91BDFF", "淡紫 BCA8F5", "薄荷 99D5B4", "珊瑚 EFA898", "月白 E3E9F1", "实际 F0BD50"
  };

  /**
   * 期待色（ARGB），列顺序：active、onActive、off、onOff、track、secondary、muted、status。
   *
   * <p>F0BD50 一行的前 5 列来自生产 ThemePaletteTest 的固定断言（active=EBC16C、onActive=402D00、
   * off=2A251D、onOff=EBE1D4、track=5C4200），与实机 status JSON（seed=#F0BD50、active=#EBC16C）互相印证。
   */
  private static final int[][] EXPECTED = {
    {0xFFEBC16C, 0xFF402D00, 0xFF2A251D, 0xFFEBE1D4, 0xFF5C4200, 0xFFB3AA9E, 0xFF7D766B, 0xFFF8EFE7},
    {0xFFA6C8FF, 0xFF01315E, 0xFF23262B, 0xFFE1E2E9, 0xFF224876, 0xFFAAABB1, 0xFF75777D, 0xFFF1F0F4},
    {0xFFCEBDFE, 0xFF35275D, 0xFF27252B, 0xFFE6E1E9, 0xFF4C3E76, 0xFFAEA9B1, 0xFF79767D, 0xFFF4EFF4},
    {0xFF8FD5B0, 0xFF003824, 0xFF212723, 0xFFDEE4DE, 0xFF005236, 0xFFA7ADA7, 0xFF737874, 0xFFEFF1ED},
    {0xFFFFB4A3, 0xFF561F12, 0xFF2D2321, 0xFFF1DFDB, 0xFF733426, 0xFFB8A8A4, 0xFF827471, 0xFFFBEEEB},
    {0xFF97CCF8, 0xFF00344F, 0xFF22262A, 0xFFE0E3E8, 0xFF004B71, 0xFFA8ABB0, 0xFF74777C, 0xFFF0F0F3},
    {0xFFEBC16C, 0xFF402D00, 0xFF2A251D, 0xFFEBE1D4, 0xFF5C4200, 0xFFB3AA9E, 0xFF7D766B, 0xFFF8EFE7}
  };

  private static int checks = 0;
  private static int failures = 0;

  public static void main(String[] args) {
    testSixPresetsAndRealSeedExpectedColors();
    testRealSeedDeviceAnchors();
    testToneHueAndOfficialAlgorithm();
    testContrastFloorsAndOrdering();
    testSeedPreservedAndRepeatable();

    System.out.println("ThemePalettePreviewTest: " + (checks - failures) + "/" + checks + " checks passed");
    if (failures > 0) {
      System.out.println("RESULT: FAIL");
      System.exit(1);
    }
    System.out.println("RESULT: OK");
  }

  /** 7 个种子逐字段比对期待色。 */
  private static void testSixPresetsAndRealSeedExpectedColors() {
    for (int i = 0; i < SEEDS.length; i++) {
      ThemePalettePreview p = ThemePalettePreview.fromSeed(SEEDS[i]);
      int[] e = EXPECTED[i];
      String tag = NAMES[i];
      check(tag + " seed preserved", p.seed == SEEDS[i]);
      check(tag + " active", p.active == e[0]);
      check(tag + " onActive", p.onActive == e[1]);
      check(tag + " off", p.off == e[2]);
      check(tag + " onOff", p.onOff == e[3]);
      check(tag + " track", p.track == e[4]);
      check(tag + " secondary", p.secondary == e[5]);
      check(tag + " muted", p.muted == e[6]);
      check(tag + " status", p.status == e[7]);
      check(tag + " all fields opaque", isFullyOpaque(p));
    }
  }

  /** 实机实际种子 #F0BD50 的独立锚点：生产 ThemePaletteTest 断言 + 实机 status JSON 记录值。 */
  private static void testRealSeedDeviceAnchors() {
    ThemePalettePreview p = ThemePalettePreview.fromSeed(0xFFF0BD50);
    check("F0BD50 active matches device status #EBC16C", p.active == 0xFFEBC16C);
    check("F0BD50 onActive matches production #402D00", p.onActive == 0xFF402D00);
    check("F0BD50 off matches production #2A251D", p.off == 0xFF2A251D);
    check("F0BD50 onOff matches production #EBE1D4", p.onOff == 0xFFEBE1D4);
    check("F0BD50 track matches production #5C4200", p.track == 0xFF5C4200);

    // 仅 RRGGBB 的种子（模拟 status JSON 的 #F0BD50）与不透明 ARGB 结果一致。
    ThemePalettePreview fromRgb = ThemePalettePreview.fromSeed(0x00F0BD50);
    check("F0BD50 hue independent of alpha", sameFields(p, fromRgb));
  }

  /** 官方算法语义：tone 精确、色相跟随种子，并与测试内独立复算一致。 */
  private static void testToneHueAndOfficialAlgorithm() {
    int[] tones = {TONE_ACTIVE, TONE_ON_ACTIVE, TONE_OFF, TONE_ON_OFF, TONE_TRACK, TONE_SECONDARY, TONE_MUTED, TONE_STATUS};
    for (int i = 0; i < SEEDS.length; i++) {
      ThemePalettePreview p = ThemePalettePreview.fromSeed(SEEDS[i]);
      String tag = NAMES[i];
      double seedHue = Hct.fromInt(p.seed).getHue();
      int[] fields = {p.active, p.onActive, p.off, p.onOff, p.track, p.secondary, p.muted, p.status};

      for (int j = 0; j < fields.length; j++) {
        Hct hct = Hct.fromInt(fields[j]);
        check(tag + " field" + j + " tone ~" + tones[j], Math.abs(hct.getTone() - tones[j]) <= 0.5);
        check(tag + " field" + j + " keeps seed hue", hueDistance(hct.getHue(), seedHue) <= 12.0);
      }

      // 测试内按生产参数独立复算，验证 preview 只是把参数正确交给 HCT。
      double hue = seedHue;
      check(tag + " recompute active", p.active == Hct.from(hue, ACCENT_CHROMA, TONE_ACTIVE).toInt());
      check(tag + " recompute onActive", p.onActive == Hct.from(hue, ACCENT_CHROMA, TONE_ON_ACTIVE).toInt());
      check(tag + " recompute track", p.track == Hct.from(hue, ACCENT_CHROMA, TONE_TRACK).toInt());
      check(tag + " recompute off", p.off == Hct.from(hue, NEUTRAL_CHROMA, TONE_OFF).toInt());
      check(tag + " recompute onOff", p.onOff == Hct.from(hue, NEUTRAL_CHROMA, TONE_ON_OFF).toInt());
      check(tag + " recompute secondary", p.secondary == Hct.from(hue, NEUTRAL_CHROMA, TONE_SECONDARY).toInt());
      check(tag + " recompute muted", p.muted == Hct.from(hue, NEUTRAL_CHROMA, TONE_MUTED).toInt());
      check(tag + " recompute status", p.status == Hct.from(hue, STATUS_CHROMA, TONE_STATUS).toInt());
    }
  }

  /** 前景/背景对比度下限与明暗方向。 */
  private static void testContrastFloorsAndOrdering() {
    for (int i = 0; i < SEEDS.length; i++) {
      ThemePalettePreview p = ThemePalettePreview.fromSeed(SEEDS[i]);
      String tag = NAMES[i];
      check(tag + " active/onActive >= 4.5", contrast(p.active, p.onActive) >= 4.5);
      check(tag + " off/onOff >= 4.5", contrast(p.off, p.onOff) >= 4.5);
      check(tag + " secondary on black >= 7", contrast(0xFF000000, p.secondary) >= 7.0);
      check(tag + " status on black >= 10", contrast(0xFF000000, p.status) >= 10.0);
      check(tag + " muted on black >= 4.4", contrast(0xFF000000, p.muted) >= 4.4);
      check(tag + " active lighter than onActive", luminance(p.active) > luminance(p.onActive));
      check(tag + " onOff lighter than off", luminance(p.onOff) > luminance(p.off));
      check(tag + " status lighter than secondary", luminance(p.status) > luminance(p.secondary));
      check(tag + " secondary lighter than muted", luminance(p.secondary) > luminance(p.muted));
    }
  }

  /** seed 原样保存、alpha 不影响取色、同输入结果可重复。 */
  private static void testSeedPreservedAndRepeatable() {
    check("seed keeps alpha", ThemePalettePreview.fromSeed(0x40F0BD50).seed == 0x40F0BD50);
    check("seed keeps exact value", ThemePalettePreview.fromSeed(0x12345678).seed == 0x12345678);
    check("repeatable", sameFields(ThemePalettePreview.fromSeed(0xFF91BDFF), ThemePalettePreview.fromSeed(0xFF91BDFF)));

    ThemePalettePreview opaque = ThemePalettePreview.fromSeed(0xFFBCA8F5);
    ThemePalettePreview ghost = ThemePalettePreview.fromSeed(0x01BCA8F5);
    check("alpha does not change derived colors", sameFields(opaque, ghost));
  }

  // ---- helpers -----------------------------------------------------------

  private static boolean sameFields(ThemePalettePreview a, ThemePalettePreview b) {
    return a.active == b.active
        && a.onActive == b.onActive
        && a.off == b.off
        && a.onOff == b.onOff
        && a.track == b.track
        && a.secondary == b.secondary
        && a.muted == b.muted
        && a.status == b.status;
  }

  private static boolean isFullyOpaque(ThemePalettePreview p) {
    return alpha(p.seed) == 0xFF
        && alpha(p.active) == 0xFF
        && alpha(p.onActive) == 0xFF
        && alpha(p.off) == 0xFF
        && alpha(p.onOff) == 0xFF
        && alpha(p.track) == 0xFF
        && alpha(p.secondary) == 0xFF
        && alpha(p.muted) == 0xFF
        && alpha(p.status) == 0xFF;
  }

  private static int alpha(int argb) {
    return (argb >>> 24) & 0xFF;
  }

  private static double luminance(int argb) {
    return 0.2126 * channel((argb >> 16) & 0xFF)
        + 0.7152 * channel((argb >> 8) & 0xFF)
        + 0.0722 * channel(argb & 0xFF);
  }

  private static double channel(int component) {
    double value = component / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
  }

  private static double contrast(int a, int b) {
    double la = luminance(a);
    double lb = luminance(b);
    double lighter = Math.max(la, lb);
    double darker = Math.min(la, lb);
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double hueDistance(double a, double b) {
    double diff = Math.abs(a - b) % 360.0;
    return diff > 180.0 ? 360.0 - diff : diff;
  }

  private static void check(String name, boolean condition) {
    checks++;
    if (!condition) {
      failures++;
      System.out.println("FAIL: " + name);
    }
  }
}
