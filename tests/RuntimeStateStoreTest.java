package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Directed behavior tests for the real persisted observation projection.
 *
 * <p>The Android dependencies are limited to the in-memory Context,
 * SharedPreferences and Log stubs. RuntimeStateStore, StatusObservation,
 * ModuleState, ThemeConfig, OWOrnateWidgetStore and OWOrnateWidgetSnapshot
 * are the production classes compiled directly from the project source.
 */
public final class RuntimeStateStoreTest {
    private static final long TTL = 30_000L;
    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        // Keep this first: OWOrnateWidgetStore migrates once per process.
        run("旧信息卡显示选项一次性迁移且保存后不丢",
                RuntimeStateStoreTest::migratesLegacyWidgetOptionsOnce);
        run("旧 workshop 莫奈状态迁移为 timestamp=0 且仅迁移一次",
                RuntimeStateStoreTest::migratesLegacyWorkshopMonetAtZero);
        run("旧信息卡莫奈状态与主题迁移为 timestamp=0",
                RuntimeStateStoreTest::migratesLegacyCardObservationAtZero);
        run("旧 workshop 小组件宿主提示迁移为 timestamp=0",
                RuntimeStateStoreTest::migratesLegacyWidgetHostAtZero);
        run("timeout/connect 失败保留确认值、时间和主题并强制不 fresh",
                RuntimeStateStoreTest::ordinaryFailuresPreserveConfirmedValue);
        run("初次失败不伪造 setup，下一次成功清除错误",
                RuntimeStateStoreTest::firstFailureDoesNotInventSetup);
        run("fresh 的 TTL 边界、时间回拨和 error 失效",
                RuntimeStateStoreTest::freshBoundariesAndError);
        run("partial-read 同 kind 保留历史完整值",
                RuntimeStateStoreTest::partialReadSameKindKeepsHistory);
        run("partial-read 无历史或 kind 不同只保留恢复提示",
                RuntimeStateStoreTest::partialReadWithoutMatchingHistoryKeepsHint);
        run("canonical 成功后旧 prefs 变化不再成为 source",
                RuntimeStateStoreTest::canonicalWinsOverLaterLegacyChanges);
        run("manager 与卡片读取同一确认结果，主题确认后同步更新",
                RuntimeStateStoreTest::managerAndCardShareConfirmedThemeUpdate);
        run("shared 中性主题尊重已保存启停意图",
                RuntimeStateStoreTest::neutralSharedThemeUsesSavedIntent);
        run("单个 monet key 通知后其他 reader 看到完整 snapshot",
                RuntimeStateStoreTest::singleKeyNotificationPublishesCompleteSnapshot);
        run("损坏 JSON 不伪造 setup",
                RuntimeStateStoreTest::corruptJsonDoesNotInventSetup);
        run("widget 域失败保留值且独立于 monet",
                RuntimeStateStoreTest::widgetFailureIsIndependentFromMonet);
        run("截屏录屏模块停用仍已安装，读取失败保留确认值",
                RuntimeStateStoreTest::captureModuleFailureRetainsState);
        run("截屏录屏模块状态不覆盖正在进行的录制会话",
                RuntimeStateStoreTest::captureModuleDoesNotOverwriteSession);

        System.out.println();
        System.out.println("RuntimeStateStoreTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            for (String failure : failures) System.out.println("  FAILED: " + failure);
            System.exit(1);
        }
    }

    private static void captureModuleFailureRetainsState() {
        Context context=new Context();
        RuntimeStateStore.recordCapture(context,new CaptureModuleState("disabled"));
        StatusObservation<CaptureModuleState> old=RuntimeStateStore.capture(context);
        check(old.value.installed(),"disabled module remains installed");
        check(!old.value.enabled(),"disabled module is not enabled");
        RuntimeStateStore.failCapture(context,new ConnectException("offline"));
        StatusObservation<CaptureModuleState> failed=RuntimeStateStore.capture(context);
        eq("capture kind retained","disabled",failed.value.kind);
        eq("capture time retained",old.confirmedAt,failed.confirmedAt);
        check(!failed.error.isEmpty(),"capture failure visible");
        check(!failed.fresh(System.currentTimeMillis(),TTL),"capture failure never fresh");
        RuntimeStateStore.recordCapture(context,new CaptureModuleState("ready"));
        check(RuntimeStateStore.capture(context).value.enabled(),"module re-enabled");
        check(RuntimeStateStore.capture(context).error.isEmpty(),"successful read clears error");
    }

    private static void captureModuleDoesNotOverwriteSession() {
        Context context=new Context();
        context.getSharedPreferences("capture_runtime",0).edit().putString("phase","recording").commit();
        RuntimeStateStore.recordCapture(context,new CaptureModuleState("ready"));
        eq("recording session retained","recording",context.getSharedPreferences("capture_runtime",0).getString("phase",""));
        RuntimeStateStore.recordCapture(context,new CaptureModuleState("disabled"));
        eq("projection does not mutate the process session","recording",context.getSharedPreferences("capture_runtime",0).getString("phase",""));
    }

    private static void migratesLegacyWidgetOptionsOnce() throws Exception {
        Context context = new Context();
        SharedPreferences card = context.getSharedPreferences("owornate_widget", 0);
        check(card.edit().putBoolean("default.status", false)
                .putBoolean("default.theme", true)
                .putBoolean("default.version", false).commit(), "seed legacy options");

        OWOrnateWidgetStore.Options migrated = OWOrnateWidgetStore.options(context);
        eq("migrated showStatus", false, migrated.showStatus);
        eq("migrated showTheme", true, migrated.showTheme);
        eq("migrated showVersion", false, migrated.showVersion);
        check(card.contains("recent.status"), "global status slot written");
        check(card.contains("recent.theme"), "global theme slot written");
        check(card.contains("recent.version"), "global version slot written");

        check(card.edit().putBoolean("default.status", true)
                .putBoolean("default.theme", false)
                .putBoolean("default.version", true).commit(), "change old options");
        OWOrnateWidgetStore.Options second = OWOrnateWidgetStore.options(context);
        eq("migration remains one-time status", false, second.showStatus);
        eq("migration remains one-time theme", true, second.showTheme);
        eq("migration remains one-time version", false, second.showVersion);

        OWOrnateWidgetStore.saveOptions(context, new OWOrnateWidgetStore.Options(true, false, true));
        OWOrnateWidgetStore.Options saved = OWOrnateWidgetStore.options(context);
        eq("saved status", true, saved.showStatus);
        eq("saved theme", false, saved.showTheme);
        eq("saved version", true, saved.showVersion);
    }

    private static void migratesLegacyWorkshopMonetAtZero() {
        Context context = new Context();
        SharedPreferences old = context.getSharedPreferences("workshop", 0);
        check(old.edit().putString("monetKind", "installed")
                .putBoolean("monetActive", true)
                .putString("monetMessage", "上次确认已启用")
                .putBoolean("monetKnown", true).commit(), "seed workshop state");

        StatusObservation<ModuleState> first = RuntimeStateStore.monet(context);
        eq("workshop kind", "installed", first.value.kind);
        eq("workshop active", true, first.value.active);
        eq("workshop activeKnown", true, first.value.activeKnown);
        eq("workshop message", "上次确认已启用", first.value.message);
        eq("workshop theme absent", null, first.value.theme);
        eq("workshop timestamp", 0L, first.confirmedAt);
        eq("workshop error", "", first.error);
        check(!first.fresh(System.currentTimeMillis(), TTL), "migrated hint cannot be fresh");
        check(RuntimeStateStore.preferences(context).contains("monet"), "migration persisted");

        check(old.edit().putString("monetKind", "available")
                .putBoolean("monetActive", false).commit(), "change old workshop state");
        StatusObservation<ModuleState> second = RuntimeStateStore.monet(context);
        eq("workshop migration is one-time", "installed", second.value.kind);
        eq("workshop migration keeps time", 0L, second.confirmedAt);
    }

    private static void migratesLegacyCardObservationAtZero() {
        Context context = new Context();
        SharedPreferences card = context.getSharedPreferences("owornate_widget", 0);
        check(card.edit().putString("kind", "shared")
                .putBoolean("active", true)
                .putBoolean("known", true)
                .putString("mode", "fixed")
                .putInt("seed", 0x00112233)
                .putBoolean("corners", true).commit(), "seed legacy card state");

        StatusObservation<ModuleState> observation = RuntimeStateStore.monet(context);
        eq("card kind", "shared", observation.value.kind);
        eq("card active", true, observation.value.active);
        eq("card activeKnown", true, observation.value.activeKnown);
        eq("card message", "待重新确认", observation.value.message);
        eq("card theme mode", "fixed", observation.value.theme.mode);
        eq("card theme seed", 0xFF112233, observation.value.theme.seed);
        eq("card theme corners", true, observation.value.theme.corners);
        eq("card timestamp", 0L, observation.confirmedAt);
        check(!observation.fresh(System.currentTimeMillis(), TTL), "card hint cannot be fresh");
    }

    private static void migratesLegacyWidgetHostAtZero() {
        Context context = new Context();
        SharedPreferences old = context.getSharedPreferences("workshop", 0);
        check(old.edit().putString("widgetHost", "ready").commit(), "seed widget host");

        StatusObservation<WidgetHostManager.Status> first = RuntimeStateStore.widgets(context);
        eq("widget host migrated", "ready", first.value.host);
        eq("widget keep migrated", "", first.value.keep);
        eq("widget host timestamp", 0L, first.confirmedAt);
        check(!first.fresh(System.currentTimeMillis(), TTL), "widget hint cannot be fresh");

        check(old.edit().putString("widgetHost", "available").commit(), "change old widget host");
        StatusObservation<WidgetHostManager.Status> second = RuntimeStateStore.widgets(context);
        eq("widget migration is one-time", "ready", second.value.host);
    }

    private static void ordinaryFailuresPreserveConfirmedValue() throws Exception {
        Context context = new Context();
        OWOrnateWidgetProvider.resetUpdateCount();
        ThemeConfig theme = theme("fixed", 0x00112233, true);
        RuntimeStateStore.recordMonet(context,
                new ModuleState("installed", true, "已启用", true, theme));
        StatusObservation<ModuleState> confirmed = RuntimeStateStore.monet(context);
        check(confirmed.confirmedAt > 0L, "successful confirmation has a time");
        eq("record refreshed widget once", 1, OWOrnateWidgetProvider.updateCount());

        Thread.sleep(5L);
        RuntimeStateStore.failMonet(context, new SocketTimeoutException("timeout-detail"));
        StatusObservation<ModuleState> timeout = RuntimeStateStore.monet(context);
        assertModule("timeout value", confirmed.value, timeout.value);
        eq("timeout keeps confirmedAt", confirmed.confirmedAt, timeout.confirmedAt);
        eq("timeout error", "读取状态超时，请重试", timeout.error);
        check(!timeout.fresh(System.currentTimeMillis(), TTL), "timeout cannot be fresh");
        OWOrnateWidgetSnapshot timeoutSnapshot = OWOrnateWidgetStore.snapshot(context);
        eq("timeout snapshot error", "读取状态超时，请重试", timeoutSnapshot.error);
        eq("timeout snapshot status", "莫奈 · 本次读取失败", timeoutSnapshot.statusText());
        eq("timeout snapshot theme", theme.mode, timeoutSnapshot.theme.mode);
        check(timeoutSnapshot.themeText().startsWith("上次："),
                "failed read labels retained theme as previous");

        Thread.sleep(5L);
        RuntimeStateStore.failMonet(context, new ConnectException("connect-detail"));
        StatusObservation<ModuleState> connect = RuntimeStateStore.monet(context);
        assertModule("connect value", timeout.value, connect.value);
        eq("connect keeps confirmedAt", timeout.confirmedAt, connect.confirmedAt);
        eq("connect error", "本机连接失败，请检查连接或授权后重试", connect.error);
        check(!connect.fresh(System.currentTimeMillis(), TTL), "connect failure cannot be fresh");
        eq("record and two failures each refreshed widgets", 3,
                OWOrnateWidgetProvider.updateCount());
    }

    private static void firstFailureDoesNotInventSetup() throws Exception {
        Context context = new Context();
        OWOrnateWidgetProvider.resetUpdateCount();
        RuntimeStateStore.failMonet(context, new IOException("first read failed"));
        StatusObservation<ModuleState> failed = RuntimeStateStore.monet(context);
        eq("first failure has no value", null, failed.value);
        eq("first failure has no time", 0L, failed.confirmedAt);
        eq("first failure error", "first read failed", failed.error);
        check(!failed.fresh(System.currentTimeMillis(), TTL), "first failure cannot be fresh");
        OWOrnateWidgetSnapshot failedSnapshot = OWOrnateWidgetStore.snapshot(context);
        eq("first failure kind stays unknown", "unknown", failedSnapshot.kind);
        check(!"setup".equals(failedSnapshot.kind), "failure is not fabricated setup");
        check(!failedSnapshot.activeKnown, "first failure is unconfirmed");

        Thread.sleep(5L);
        RuntimeStateStore.recordMonet(context,
                new ModuleState("setup", false, "等待首次连接"));
        StatusObservation<ModuleState> success = RuntimeStateStore.monet(context);
        eq("success kind", "setup", success.value.kind);
        eq("success clears error", "", success.error);
        check(success.confirmedAt > 0L, "success has confirmedAt");
        OWOrnateWidgetSnapshot successSnapshot = OWOrnateWidgetStore.snapshot(context);
        eq("success status", "莫奈 · 等待连接", successSnapshot.statusText());
        check(successSnapshot.activeKnown, "success is confirmed");
        eq("failure and success each refreshed widgets", 2,
                OWOrnateWidgetProvider.updateCount());
    }

    private static void freshBoundariesAndError() {
        StatusObservation<String> confirmed = new StatusObservation<>("ready", 1_000L, "");
        check(confirmed.fresh(1_000L, TTL), "now == confirmedAt is fresh");
        check(confirmed.fresh(1_000L + TTL - 1L, TTL), "inside TTL is fresh");
        check(!confirmed.fresh(1_000L + TTL, TTL), "TTL boundary is expired");
        check(!confirmed.fresh(999L, TTL), "clock rollback is not fresh");
        check(!confirmed.fresh(1_000L, 0L), "zero TTL is expired");
        check(!confirmed.fresh(1_000L, -1L), "negative TTL is expired");
        StatusObservation<String> failed = confirmed.failure("read failed");
        eq("failure retains value", "ready", failed.value);
        eq("failure retains time", 1_000L, failed.confirmedAt);
        check(!failed.fresh(1_000L, TTL), "error forces stale");
        StatusObservation<String> noTime = new StatusObservation<>("ready", 0L, "");
        check(!noTime.fresh(1L, TTL), "confirmedAt zero is not fresh");
        StatusObservation<String> noValue = new StatusObservation<>(null, 1_000L, "");
        check(!noValue.fresh(1_000L, TTL), "missing value is not fresh");
    }

    private static void partialReadSameKindKeepsHistory() {
        Context context = new Context();
        ThemeConfig theme = theme("auto", 0x00224466, true);
        RuntimeStateStore.recordMonet(context,
                new ModuleState("installed", true, "已启用", true, theme));
        StatusObservation<ModuleState> old = RuntimeStateStore.monet(context);

        RuntimeStateStore.failMonet(context, new IOException("helper unavailable"),
                new ModuleState("installed", false, "已确认安装，状态未确认", false, null));
        StatusObservation<ModuleState> after = RuntimeStateStore.monet(context);
        assertModule("same kind keeps full value", old.value, after.value);
        eq("same kind keeps time", old.confirmedAt, after.confirmedAt);
        eq("same kind records error", "helper unavailable", after.error);
        check(!after.fresh(System.currentTimeMillis(), TTL), "partial failure cannot be fresh");
        OWOrnateWidgetSnapshot snapshot = OWOrnateWidgetStore.snapshot(context);
        eq("same kind snapshot theme", theme.mode, snapshot.theme.mode);
        eq("same kind snapshot status", "莫奈 · 本次读取失败", snapshot.statusText());
        check(snapshot.themeText().startsWith("上次："),
                "partial failure explicitly labels retained theme");
    }

    private static void partialReadWithoutMatchingHistoryKeepsHint() {
        String hint = "已检测到共享组件，运行状态未确认";
        Context noHistory = new Context();
        RuntimeStateStore.failMonet(noHistory, new IOException("helper unavailable"),
                new ModuleState("shared", false, hint, false, null));
        StatusObservation<ModuleState> hintOnly = RuntimeStateStore.monet(noHistory);
        eq("no-history kind", "shared", hintOnly.value.kind);
        eq("no-history active", false, hintOnly.value.active);
        eq("no-history activeKnown", false, hintOnly.value.activeKnown);
        eq("no-history message", hint, hintOnly.value.message);
        eq("no-history theme", null, hintOnly.value.theme);
        eq("no-history time", 0L, hintOnly.confirmedAt);
        eq("no-history error", "helper unavailable", hintOnly.error);
        check(!hintOnly.fresh(System.currentTimeMillis(), TTL), "hint cannot be fresh");
        OWOrnateWidgetSnapshot noHistorySnapshot = OWOrnateWidgetStore.snapshot(noHistory);
        check(noHistorySnapshot.installed(), "recovery entry remains installed/shared");
        check(!noHistorySnapshot.activeKnown, "recovery entry is not confirmed");
        eq("recovery entry time", 0L, noHistorySnapshot.confirmedAt);
        eq("recovery entry status", "莫奈 · 本次读取失败", noHistorySnapshot.statusText());

        Context differentKind = new Context();
        RuntimeStateStore.recordMonet(differentKind,
                new ModuleState("available", false, "未安装"));
        RuntimeStateStore.failMonet(differentKind, new IOException("helper unavailable"),
                new ModuleState("shared", false, hint, false, null));
        StatusObservation<ModuleState> replaced = RuntimeStateStore.monet(differentKind);
        eq("different kind replaces value", "shared", replaced.value.kind);
        eq("different kind activeKnown", false, replaced.value.activeKnown);
        eq("different kind message", hint, replaced.value.message);
        eq("different kind time", 0L, replaced.confirmedAt);
        check(!replaced.fresh(System.currentTimeMillis(), TTL), "replaced hint cannot be fresh");
    }

    private static void canonicalWinsOverLaterLegacyChanges() {
        Context context = new Context();
        SharedPreferences workshop = context.getSharedPreferences("workshop", 0);
        SharedPreferences card = context.getSharedPreferences("owornate_widget", 0);
        check(workshop.edit().putString("monetKind", "installed")
                .putBoolean("monetActive", true).putBoolean("monetKnown", true).commit(),
                "seed old workshop");
        check(card.edit().putString("kind", "installed").putString("mode", "fixed")
                .putInt("seed", 0x00111111).putBoolean("corners", true)
                .putBoolean("active", true).putBoolean("known", true).commit(),
                "seed old card");

        ThemeConfig canonicalTheme = theme("auto", 0x00335577, false);
        RuntimeStateStore.recordMonet(context,
                new ModuleState("shared", true, "已启用", true, canonicalTheme));
        StatusObservation<ModuleState> canonical = RuntimeStateStore.monet(context);

        check(workshop.edit().putString("monetKind", "available")
                .putBoolean("monetActive", false).putBoolean("monetKnown", true).commit(),
                "change old workshop after canonical");
        check(card.edit().putString("kind", "installed").putBoolean("active", false)
                .putString("mode", "original").putBoolean("corners", false).commit(),
                "change old card after canonical");

        StatusObservation<ModuleState> reread = RuntimeStateStore.monet(context);
        assertModule("canonical state wins", canonical.value, reread.value);
        eq("canonical time wins", canonical.confirmedAt, reread.confirmedAt);
        eq("canonical error stays clear", "", reread.error);
        OWOrnateWidgetSnapshot snapshot = OWOrnateWidgetStore.snapshot(context);
        eq("canonical card kind", "shared", snapshot.kind);
        eq("canonical card active", true, snapshot.active);
        eq("canonical card time", canonical.confirmedAt, snapshot.confirmedAt);
        eq("canonical card theme", "auto", snapshot.theme.mode);
    }

    private static void managerAndCardShareConfirmedThemeUpdate() throws Exception {
        Context context = new Context();
        RuntimeStateStore.recordMonet(context, new ModuleState("installed", true, "已启用", true,
                theme("fixed", 0x00112233, true)));
        StatusObservation<ModuleState> manager = RuntimeStateStore.monet(context);
        OWOrnateWidgetSnapshot card = OWOrnateWidgetStore.snapshot(context);
        eq("initial manager kind", manager.value.kind, card.kind);
        eq("initial manager active", manager.value.active, card.active);
        eq("initial manager time", manager.confirmedAt, card.confirmedAt);
        eq("initial manager theme", manager.value.theme.mode, card.theme.mode);

        Thread.sleep(10L);
        int beforeUpdates = OWOrnateWidgetProvider.updateCount();
        ThemeConfig updated = theme("auto", 0x00AABBCC, false);
        RuntimeStateStore.recordTheme(context, updated);
        eq("theme confirmation refreshed widget", beforeUpdates + 1,
                OWOrnateWidgetProvider.updateCount());
        StatusObservation<ModuleState> newManager = RuntimeStateStore.monet(context);
        OWOrnateWidgetSnapshot newCard = OWOrnateWidgetStore.snapshot(context);
        check(newManager.confirmedAt > manager.confirmedAt, "confirmedAt advances on theme ACK");
        eq("theme update manager error", "", newManager.error);
        eq("theme update message", "已启用", newManager.value.message);
        eq("theme update mode", "auto", newManager.value.theme.mode);
        eq("theme update seed", 0xFFAABBCC, newManager.value.theme.seed);
        eq("theme update corners", false, newManager.value.theme.corners);
        eq("card follows manager kind", newManager.value.kind, newCard.kind);
        eq("card follows manager active", newManager.value.active, newCard.active);
        eq("card follows manager time", newManager.confirmedAt, newCard.confirmedAt);
        eq("card follows manager theme mode", "auto", newCard.theme.mode);
        eq("card theme text", "自动取色 · 原生圆角", newCard.themeText());
    }

    private static void neutralSharedThemeUsesSavedIntent() throws Exception {
        Context context = new Context();
        SharedPreferences modules = context.getSharedPreferences("modules", 0);
        RuntimeStateStore.recordMonet(context, new ModuleState("shared", true, "已启用", true,
                theme("fixed", 0x00112233, true)));
        ThemeConfig neutral = theme("original", 0x00333333, false);

        RuntimeStateStore.recordTheme(context, neutral);
        StatusObservation<ModuleState> stopped = RuntimeStateStore.monet(context);
        eq("neutral shared stops without saved intent", false, stopped.value.active);
        eq("neutral shared stopped message", "已停用", stopped.value.message);
        eq("neutral shared keeps confirmed theme mode", "original", stopped.value.theme.mode);
        OWOrnateWidgetSnapshot stoppedCard = OWOrnateWidgetStore.snapshot(context);
        eq("stopped card state", "莫奈 · 已停用", stoppedCard.statusText());
        eq("stopped card hides theme", null, stoppedCard.theme);

        Thread.sleep(10L);
        check(modules.edit().putBoolean("monetEnabled", true).commit(), "save enabled intent");
        RuntimeStateStore.recordTheme(context, neutral);
        StatusObservation<ModuleState> enabled = RuntimeStateStore.monet(context);
        eq("neutral shared keeps enabled intent", true, enabled.value.active);
        eq("neutral shared enabled message", "已启用", enabled.value.message);
        OWOrnateWidgetSnapshot enabledCard = OWOrnateWidgetStore.snapshot(context);
        eq("enabled card active", true, enabledCard.active);
        eq("enabled card retains neutral theme", "original", enabledCard.theme.mode);
        eq("enabled card theme text", "系统原色 · 原生圆角", enabledCard.themeText());
    }

    private static void singleKeyNotificationPublishesCompleteSnapshot() {
        Context context = new Context();
        SharedPreferences runtime = RuntimeStateStore.preferences(context);
        final Seen seen = new Seen();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
            seen.keys.add(key);
            seen.notifications++;
            StatusObservation<ModuleState> observed = RuntimeStateStore.monet(context);
            OWOrnateWidgetSnapshot card = OWOrnateWidgetStore.snapshot(context);
            seen.kind = observed.value == null ? null : observed.value.kind;
            seen.active = observed.value != null && observed.value.active;
            seen.time = observed.confirmedAt;
            seen.error = observed.error;
            seen.themeMode = observed.value == null || observed.value.theme == null
                    ? null : observed.value.theme.mode;
            seen.cardKind = card.kind;
            seen.cardActive = card.active;
            seen.cardThemeMode = card.theme == null ? null : card.theme.mode;
        };
        runtime.registerOnSharedPreferenceChangeListener(listener);
        try {
            ThemeConfig theme = theme("auto", 0x00445566, true);
            RuntimeStateStore.recordMonet(context,
                    new ModuleState("installed", true, "已启用", true, theme));
            eq("one key notification", 1, seen.notifications);
            eq("only monet key published", java.util.Collections.singletonList("monet"), seen.keys);
            eq("listener sees kind", "installed", seen.kind);
            eq("listener sees active", true, seen.active);
            eq("listener sees theme", "auto", seen.themeMode);
            eq("listener sees no error", "", seen.error);
            check(seen.time > 0L, "listener sees confirmedAt");
            eq("card listener sees same kind", "installed", seen.cardKind);
            eq("card listener sees same active", true, seen.cardActive);
            eq("card listener sees complete theme", "auto", seen.cardThemeMode);
            StatusObservation<ModuleState> after = RuntimeStateStore.monet(context);
            eq("reader time equals notification snapshot", after.confirmedAt, seen.time);
        } finally {
            runtime.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    private static void corruptJsonDoesNotInventSetup() {
        Context context = new Context();
        SharedPreferences runtime = RuntimeStateStore.preferences(context);
        check(runtime.edit().putString("monet", "{not-json").commit(), "write corrupt JSON");
        StatusObservation<ModuleState> observation = RuntimeStateStore.monet(context);
        eq("corrupt value", null, observation.value);
        eq("corrupt time", 0L, observation.confirmedAt);
        eq("corrupt error", "已保存的莫奈状态无法读取，请重新检查", observation.error);
        check(!observation.fresh(System.currentTimeMillis(), TTL), "corrupt JSON cannot be fresh");
        OWOrnateWidgetSnapshot snapshot = OWOrnateWidgetStore.snapshot(context);
        eq("corrupt snapshot kind", "unknown", snapshot.kind);
        check(!"setup".equals(snapshot.kind), "corrupt JSON is not setup");
        check(!snapshot.activeKnown, "corrupt snapshot is unconfirmed");
        eq("corrupt snapshot time", 0L, snapshot.confirmedAt);
        eq("corrupt snapshot status", "莫奈 · 本次读取失败", snapshot.statusText());
        eq("corrupt snapshot error", observation.error, snapshot.error);
    }

    private static void widgetFailureIsIndependentFromMonet() throws Exception {
        Context context = new Context();
        RuntimeStateStore.recordWidgets(context, new WidgetHostManager.Status("ready", "added"));
        StatusObservation<WidgetHostManager.Status> widget = RuntimeStateStore.widgets(context);
        ThemeConfig theme = theme("fixed", 0x00556677, true);
        RuntimeStateStore.recordMonet(context,
                new ModuleState("installed", true, "已启用", true, theme));
        StatusObservation<ModuleState> monet = RuntimeStateStore.monet(context);

        Thread.sleep(5L);
        RuntimeStateStore.failWidgets(context, new ConnectException("widget connect failed"));
        StatusObservation<WidgetHostManager.Status> failedWidget = RuntimeStateStore.widgets(context);
        eq("widget host retained", widget.value.host, failedWidget.value.host);
        eq("widget keep retained", widget.value.keep, failedWidget.value.keep);
        eq("widget time retained", widget.confirmedAt, failedWidget.confirmedAt);
        eq("widget error recorded", "本机连接失败，请检查连接或授权后重试", failedWidget.error);
        check(!failedWidget.fresh(System.currentTimeMillis(), TTL), "failed widget is stale");

        StatusObservation<ModuleState> monetAfter = RuntimeStateStore.monet(context);
        assertModule("widget failure leaves monet value alone", monet.value, monetAfter.value);
        eq("widget failure leaves monet time alone", monet.confirmedAt, monetAfter.confirmedAt);
        eq("widget failure leaves monet error alone", "", monetAfter.error);
    }

    private static final class Seen {
        final List<String> keys = new ArrayList<>();
        int notifications;
        String kind, error, themeMode, cardKind, cardThemeMode;
        boolean active, cardActive;
        long time;
    }

    private static ThemeConfig theme(String mode, int seed, boolean corners) {
        return new ThemeConfig(mode, seed, corners);
    }

    private static void assertModule(String what, ModuleState expected, ModuleState actual) {
        check(actual != null, what + ": value present");
        eq(what + " kind", expected.kind, actual.kind);
        eq(what + " active", expected.active, actual.active);
        eq(what + " activeKnown", expected.activeKnown, actual.activeKnown);
        eq(what + " message", expected.message, actual.message);
        if (expected.theme == null) {
            eq(what + " theme", null, actual.theme);
        } else {
            check(actual.theme != null, what + ": theme present");
            eq(what + " theme mode", expected.theme.mode, actual.theme.mode);
            eq(what + " theme seed", expected.theme.seed, actual.theme.seed);
            eq(what + " theme corners", expected.theme.corners, actual.theme.corners);
        }
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void run(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable failure) {
            failed++;
            failures.add(name + " :: " + failure);
            System.out.println("FAIL  " + name + " :: " + failure);
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private RuntimeStateStoreTest() {
    }
}
