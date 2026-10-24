package dev.codex.watchworkshop;

import java.util.ArrayList;
import java.util.List;

/**
 * OWOrnateWidgetSnapshot 真实状态模型测试：直接编译并运行 src 中的真实
 * OWOrnateWidgetSnapshot.java 与 ThemeConfig.java（两者都无 Android 依赖），
 * 只断言构造函数与 statusText()/themeText()/installed()/seedHex()/modeLabel()
 * 的运行时行为，不对源码文本做字符串匹配。
 *
 * <p>覆盖的边界：未确认（activeKnown=false 或 kind=null）不得泄漏旧主题、active 必须为
 * false、确认时间必须归 0；已停用不显示旧主题；未安装（available）与 installed/shared、
 * 以及主题为 original 且 corners=false 时仍可以 active=true；负的确认时间归 0。
 */
public final class OWOrnateWidgetSnapshotTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("未确认(kind=null)：不泄漏旧主题、active=false、时间归0",
                OWOrnateWidgetSnapshotTest::unconfirmedWithNullKindLeaksNothing);
        run("未确认(kind=installed)：activeKnown=false 同样不泄漏",
                OWOrnateWidgetSnapshotTest::unconfirmedInstalledKindLeaksNothing);
        run("已停用(installed)：不显示旧主题",
                OWOrnateWidgetSnapshotTest::stoppedShowsNoPreviousTheme);
        run("已停用(disabled)与新版本(update)：仍识别为已安装",
                OWOrnateWidgetSnapshotTest::disabledAndUpdateRemainInstalled);
        run("已启用(installed, original+corners=false)：仍 active 且保留主题",
                OWOrnateWidgetSnapshotTest::enabledKeepsOriginalTheme);
        run("已启用(shared)：与 installed 同等并保留主题",
                OWOrnateWidgetSnapshotTest::sharedBehavesLikeInstalled);
        run("未安装(available)：active 仍可为 true 但主题为空",
                OWOrnateWidgetSnapshotTest::availableCanBeActiveWithoutTheme);
        run("已启用但传入主题为空：配色待确认",
                OWOrnateWidgetSnapshotTest::enabledWithoutThemeAsksConfirmation);
        run("确认时间：负值归0、正值保留、未确认恒为0",
                OWOrnateWidgetSnapshotTest::confirmedAtClampsNegativeValues);
        run("状态文案：setup 与未知 kind",
                OWOrnateWidgetSnapshotTest::statusTextForSetupAndUnknownKind);
        run("installed() 判定边界",
                OWOrnateWidgetSnapshotTest::installedKindBoundary);
        run("主题不跨快照残留",
                OWOrnateWidgetSnapshotTest::themeNeverCarriesOverBetweenSnapshots);
        run("ThemeConfig：alpha 强制与 seedHex",
                OWOrnateWidgetSnapshotTest::themeConfigForcesAlphaAndFormatsSeed);
        run("ThemeConfig：modeLabel、mode 保留与非法 mode",
                OWOrnateWidgetSnapshotTest::themeConfigValidatesMode);

        System.out.println();
        System.out.println("OWOrnateWidgetSnapshotTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            for (String failure : failures) {
                System.out.println("  FAILED: " + failure);
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // 未确认：不得泄漏旧主题、active=false、时间归 0
    // ------------------------------------------------------------------

    private static void unconfirmedWithNullKindLeaksNothing() {
        ThemeConfig previous = theme("fixed", 0x00112233, true);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot(null, false, true, previous, 12345L);

        eq("kind 归一为 unknown", "unknown", snapshot.kind);
        ok("activeKnown 反映未确认", !snapshot.activeKnown);
        ok("active 必须为 false（即使传入 true）", !snapshot.active);
        ok("不泄漏旧主题", snapshot.theme == null);
        eq("确认时间归 0", 0L, snapshot.confirmedAt);
        eq("状态文案为待确认", "莫奈 · 状态待确认", snapshot.statusText());
        eq("主题文案引导重新读取", "打开组件读取状态", snapshot.themeText());
        ok("主题文案不含旧主题标签", !snapshot.themeText().contains(previous.modeLabel()));
    }

    private static void unconfirmedInstalledKindLeaksNothing() {
        ThemeConfig previous = theme("auto", 0x00112233, true);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("installed", false, true, previous, 999L);

        eq("kind 原样保留", "installed", snapshot.kind);
        ok("activeKnown 为 false", !snapshot.activeKnown);
        ok("未确认时 active 必须为 false", !snapshot.active);
        ok("未确认时主题为空", snapshot.theme == null);
        eq("未确认时时间归 0", 0L, snapshot.confirmedAt);
        eq("未确认时状态文案", "莫奈 · 状态待确认", snapshot.statusText());
        eq("未确认时主题文案", "打开组件读取状态", snapshot.themeText());
    }

    // ------------------------------------------------------------------
    // 已停用：不显示旧主题
    // ------------------------------------------------------------------

    private static void stoppedShowsNoPreviousTheme() {
        ThemeConfig previous = theme("auto", 0x00445566, true);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("installed", true, false, previous, 4242L);

        ok("activeKnown 为 true", snapshot.activeKnown);
        ok("active 为 false", !snapshot.active);
        ok("停用时不保留旧主题", snapshot.theme == null);
        eq("确认时间保留", 4242L, snapshot.confirmedAt);
        eq("状态文案为已停用", "莫奈 · 已停用", snapshot.statusText());
        eq("主题文案引导管理页", "打开 OWOrnate 管理", snapshot.themeText());
        ok("主题文案不含旧主题标签", !snapshot.themeText().contains(previous.modeLabel()));
    }

    private static void disabledAndUpdateRemainInstalled() {
        ThemeConfig previous = theme("auto", 0x00445566, true);
        OWOrnateWidgetSnapshot disabled =
                new OWOrnateWidgetSnapshot("disabled", true, false, previous, 4243L);
        ok("disabled 视为已安装", disabled.installed());
        eq("disabled 状态文案", "莫奈 · 已停用", disabled.statusText());
        eq("disabled 主题文案", "打开 OWOrnate 管理", disabled.themeText());
        ok("disabled 不保留旧主题", disabled.theme == null);

        OWOrnateWidgetSnapshot update =
                new OWOrnateWidgetSnapshot("update", false, false, previous, 4244L);
        ok("update 视为已安装", update.installed());
        eq("update 状态文案", "莫奈 · 可更新", update.statusText());
        eq("update 主题文案", "打开组件读取状态", update.themeText());
        ok("update 不保留旧主题", update.theme == null);
    }

    // ------------------------------------------------------------------
    // 已启用：保留当前主题
    // ------------------------------------------------------------------

    private static void enabledKeepsOriginalTheme() {
        ThemeConfig current = theme("original", 0x00112233, false);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("installed", true, true, current, 7L);

        ok("original+corners=false 仍可 active=true", snapshot.active);
        ok("主题为同一实例", snapshot.theme == current);
        ok("installed() 为真", snapshot.installed());
        eq("状态文案为已启用", "莫奈 · 已启用", snapshot.statusText());
        eq("主题文案为模式+圆角", "系统原色 · 原生圆角", snapshot.themeText());
        eq("确认时间保留", 7L, snapshot.confirmedAt);
    }

    private static void sharedBehavesLikeInstalled() {
        ThemeConfig current = theme("auto", 0x00000000, true);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("shared", true, true, current, 11L);

        ok("shared 视为已安装", snapshot.installed());
        ok("shared 可 active=true", snapshot.active);
        ok("shared 保留主题", snapshot.theme == current);
        eq("shared 状态文案", "莫奈 · 已启用", snapshot.statusText());
        eq("shared 主题文案", "自动取色 · 连续圆角", snapshot.themeText());
    }

    private static void availableCanBeActiveWithoutTheme() {
        ThemeConfig passed = theme("fixed", 0x00112233, false);
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("available", true, true, passed, 88L);

        ok("available 不算已安装", !snapshot.installed());
        ok("未安装时 active 仍可为 true", snapshot.active);
        ok("未安装时不保留主题", snapshot.theme == null);
        eq("状态文案为未安装", "莫奈 · 未安装", snapshot.statusText());
        eq("主题文案引导管理页", "打开 OWOrnate 管理", snapshot.themeText());
        eq("确认时间保留", 88L, snapshot.confirmedAt);
    }

    private static void enabledWithoutThemeAsksConfirmation() {
        OWOrnateWidgetSnapshot snapshot =
                new OWOrnateWidgetSnapshot("installed", true, true, null, 5L);

        ok("active=true", snapshot.active);
        ok("主题为空", snapshot.theme == null);
        eq("主题文案为配色待确认", "配色待确认", snapshot.themeText());
        eq("状态文案仍为已启用", "莫奈 · 已启用", snapshot.statusText());
    }

    // ------------------------------------------------------------------
    // 确认时间与状态文案
    // ------------------------------------------------------------------

    private static void confirmedAtClampsNegativeValues() {
        ThemeConfig current = theme("auto", 0x00112233, false);
        OWOrnateWidgetSnapshot minusOne =
                new OWOrnateWidgetSnapshot("installed", true, true, current, -1L);
        eq("确认时间 -1 归 0", 0L, minusOne.confirmedAt);

        OWOrnateWidgetSnapshot minValue =
                new OWOrnateWidgetSnapshot("installed", true, true, current, Long.MIN_VALUE);
        eq("确认时间 Long.MIN_VALUE 归 0", 0L, minValue.confirmedAt);

        OWOrnateWidgetSnapshot zero =
                new OWOrnateWidgetSnapshot("installed", true, true, current, 0L);
        eq("确认时间 0 保持 0", 0L, zero.confirmedAt);

        OWOrnateWidgetSnapshot positive =
                new OWOrnateWidgetSnapshot("installed", true, true, current, 1750000000000L);
        eq("确认时间正值原样保留", 1750000000000L, positive.confirmedAt);

        OWOrnateWidgetSnapshot maxValue =
                new OWOrnateWidgetSnapshot("installed", true, true, current, Long.MAX_VALUE);
        eq("确认时间 Long.MAX_VALUE 保留", Long.MAX_VALUE, maxValue.confirmedAt);

        OWOrnateWidgetSnapshot unconfirmed =
                new OWOrnateWidgetSnapshot("installed", false, true, current, 123L);
        eq("未确认时正值也归 0", 0L, unconfirmed.confirmedAt);
    }

    private static void statusTextForSetupAndUnknownKind() {
        OWOrnateWidgetSnapshot setup =
                new OWOrnateWidgetSnapshot("setup", true, false, null, 3L);
        ok("setup 不算已安装", !setup.installed());
        eq("setup 状态文案", "莫奈 · 等待连接", setup.statusText());
        eq("setup 主题文案引导管理页", "打开 OWOrnate 管理", setup.themeText());

        OWOrnateWidgetSnapshot other =
                new OWOrnateWidgetSnapshot("something-else", true, false, null, 3L);
        ok("未知 kind 不算已安装", !other.installed());
        eq("未知 kind 状态文案", "莫奈 · 环境待检查", other.statusText());
        eq("未知 kind 主题文案引导管理页", "打开 OWOrnate 管理", other.themeText());
    }

    private static void installedKindBoundary() {
        ok("installed 判定为真",
                new OWOrnateWidgetSnapshot("installed", true, false, null, 0L).installed());
        ok("shared 判定为真",
                new OWOrnateWidgetSnapshot("shared", true, false, null, 0L).installed());
        ok("disabled 判定为真",
                new OWOrnateWidgetSnapshot("disabled", true, false, null, 0L).installed());
        ok("update 判定为真",
                new OWOrnateWidgetSnapshot("update", false, false, null, 0L).installed());
        ok("available 判定为假",
                !new OWOrnateWidgetSnapshot("available", true, false, null, 0L).installed());
        ok("setup 判定为假",
                !new OWOrnateWidgetSnapshot("setup", true, false, null, 0L).installed());
        ok("unknown(kind=null) 判定为假",
                !new OWOrnateWidgetSnapshot(null, true, false, null, 0L).installed());
        ok("仅大小写不同的 kind 判定为假",
                !new OWOrnateWidgetSnapshot("Installed", true, false, null, 0L).installed());
    }

    // ------------------------------------------------------------------
    // 主题不跨快照残留
    // ------------------------------------------------------------------

    private static void themeNeverCarriesOverBetweenSnapshots() {
        ThemeConfig first = theme("auto", 0x00112233, true);
        OWOrnateWidgetSnapshot enabled =
                new OWOrnateWidgetSnapshot("installed", true, true, first, 100L);
        ok("首次快照保留主题", enabled.theme == first);

        OWOrnateWidgetSnapshot stopped =
                new OWOrnateWidgetSnapshot("installed", true, false, first, 200L);
        ok("停用快照不携带首个主题", stopped.theme == null);
        eq("停用快照文案不复述旧主题", "打开 OWOrnate 管理", stopped.themeText());

        OWOrnateWidgetSnapshot missing =
                new OWOrnateWidgetSnapshot("installed", true, true, null, 300L);
        ok("主题缺失时不会回填旧主题", missing.theme == null);
        eq("主题缺失文案", "配色待确认", missing.themeText());

        ThemeConfig second = theme("fixed", 0x00AABBCC, false);
        OWOrnateWidgetSnapshot reenabled =
                new OWOrnateWidgetSnapshot("installed", true, true, second, 400L);
        ok("重新启用使用当前传入主题", reenabled.theme == second);
        eq("重新启用文案", "固定颜色 · 原生圆角", reenabled.themeText());
        eq("确认时间随快照更新", 400L, reenabled.confirmedAt);
    }

    // ------------------------------------------------------------------
    // ThemeConfig
    // ------------------------------------------------------------------

    private static void themeConfigForcesAlphaAndFormatsSeed() {
        ThemeConfig low = theme("auto", 0x00112233, true);
        eq("低位保留并强制 alpha 为 FF", "#FF112233", low.seedHex());

        ThemeConfig zero = theme("fixed", 0, false);
        eq("seed=0 时输出 FF000000", "#FF000000", zero.seedHex());

        ThemeConfig alphaSet = theme("original", 0xFFAABBCC, true);
        eq("已带 alpha 的 seed 保持不变", "#FFAABBCC", alphaSet.seedHex());

        ThemeConfig negative = theme("auto", -1, false);
        eq("seed=-1 输出 FFFFFFFF", "#FFFFFFFF", negative.seedHex());

        ok("corners 原样保留", theme("auto", 1, true).corners);
        ok("corners=false 原样保留", !theme("auto", 1, false).corners);
    }

    private static void themeConfigValidatesMode() {
        eq("auto 标签", "自动取色", theme("auto", 1, true).modeLabel());
        eq("fixed 标签", "固定颜色", theme("fixed", 1, true).modeLabel());
        eq("original 标签", "系统原色", theme("original", 1, true).modeLabel());
        eq("mode 原样保存", "original", theme("original", 1, true).mode);

        ok("非法 mode 抛 IllegalArgumentException",
                throwsIllegalArgument(() -> new ThemeConfig("rgb", 1, true)));
        ok("null mode 抛 IllegalArgumentException",
                throwsIllegalArgument(() -> new ThemeConfig(null, 1, true)));
        ok("大小写不同的 mode 抛 IllegalArgumentException",
                throwsIllegalArgument(() -> new ThemeConfig("Auto", 1, true)));
        ok("空 mode 抛 IllegalArgumentException",
                throwsIllegalArgument(() -> new ThemeConfig("", 1, true)));
    }

    // ------------------------------------------------------------------
    // 断言与工具
    // ------------------------------------------------------------------

    private interface Body {
        void run() throws Exception;
    }

    private interface Factory {
        ThemeConfig create();
    }

    private static ThemeConfig theme(String mode, int seed, boolean corners) {
        return new ThemeConfig(mode, seed, corners);
    }

    private static boolean throwsIllegalArgument(Factory factory) {
        try {
            factory.create();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + " :: " + t);
            System.out.println("FAIL  " + name + " :: " + t);
        }
    }

    private static void ok(String what, boolean condition) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void eq(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private OWOrnateWidgetSnapshotTest() {
    }
}
