package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Focused native declaration, acknowledged-state, failure, and import boundaries. */
final class MonetSettingsTest {
    private static final String MONET = MonetSettings.PLUGIN_ID;
    private static final byte[] HOOK = "// monet settings test\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] THEME = "theme-fixture".getBytes(StandardCharsets.UTF_8);
    private static final String[][] MODE_OPTIONS = {
            {"auto", "自动取色"}, {"fixed", "固定颜色"}, {"original", "系统原色"}
    };
    private static final String[][] CORNER_OPTIONS = {
            {"true", "开启"}, {"false", "关闭"}
    };

    private static Path runtimeRoot;
    private static Path actualPackage;
    private static int checks;
    private static int cases;

    private MonetSettingsTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(requiredProperty("monet.settings.test.root"))
                .toAbsolutePath().normalize();
        actualPackage = Paths.get(requiredProperty("monet.package.file"))
                .toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);
        check(Files.isRegularFile(actualPackage, LinkOption.NOFOLLOW_LINKS),
                "actual monet package exists");

        ArrayList<String> failures = new ArrayList<>();
        runGroup(failures, "native declarations and legacy fallback",
                MonetSettingsTest::declarationsAndFallback);
        runGroup(failures, "read uses acknowledged actual state",
                MonetSettingsTest::readUsesActualAck);
        runGroup(failures, "configure patches fresh actual state",
                MonetSettingsTest::configurePatchesFreshActual);
        runGroup(failures, "write gates", MonetSettingsTest::writeGates);
        runGroup(failures, "apply failure keeps pending",
                MonetSettingsTest::applyFailureAndRecovery);
        runGroup(failures, "commit failure is reported",
                MonetSettingsTest::commitFailures);
        runGroup(failures, "import and upgrade do not change state",
                MonetSettingsTest::importAndUpgrade);

        System.out.println("MonetSettingsTest: " + cases + " cases, " + checks
                + " checks, " + failures.size() + " failed groups");
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " groups failed: " + failures);
        }
    }

    private static void declarationsAndFallback() throws Exception {
        PluginManifest standard = PluginManifest.parse(standardManifest(2).toString());
        equal(2, standard.nativeSettings.size(), "monet native setting count");
        assertMode(MonetSettings.definition(standard, "mode"), "标准 mode");
        assertCorners(MonetSettings.definition(standard, "corners"), "标准 corners");

        PluginManifest legacy = PluginManifest.parse(monetManifest(1).toString());
        check(legacy.nativeSettings.isEmpty(), "legacy monet has no native declaration");
        assertFallbackMode(MonetSettings.definition(legacy, "mode"), "legacy mode fallback");
        assertFallbackCorners(MonetSettings.definition(legacy, "corners"),
                "legacy corners fallback");
        Context noPackage = newCase("no-package-fallback");
        assertFallbackMode(MonetSettings.definition(noPackage, "mode"),
                "no-package mode fallback");
        assertFallbackCorners(MonetSettings.definition(noPackage, "corners"),
                "no-package corners fallback");

        JSONObject extraMode = copy(standardManifest(2));
        nativeSetting(extraMode, "mode").getJSONArray("options")
                .put(option("dark", "暗色"));
        reject(extraMode, "extra mode option");

        JSONObject missingMode = copy(standardManifest(2));
        nativeSetting(missingMode, "mode").getJSONArray("options").remove(0);
        reject(missingMode, "missing mode option");

        JSONObject extraCorners = copy(standardManifest(2));
        nativeSetting(extraCorners, "corners").getJSONArray("options")
                .put(option("auto", "自动"));
        reject(extraCorners, "extra corners option");

        JSONObject missingCornersOption = copy(standardManifest(2));
        nativeSetting(missingCornersOption, "corners").getJSONArray("options").remove(1);
        reject(missingCornersOption, "missing corners option");

        JSONObject duplicate = copy(standardManifest(2));
        JSONObject duplicatedMode = nativeSetting(duplicate, "mode");
        duplicate.getJSONArray("nativeSettings")
                .put(new JSONObject(duplicatedMode.toString()));
        reject(duplicate, "duplicate native setting");

        JSONObject missingSetting = copy(standardManifest(2));
        missingSetting.getJSONArray("nativeSettings").remove(1);
        reject(missingSetting, "missing native setting");

        JSONObject wrongType = copy(standardManifest(2));
        nativeSetting(wrongType, "mode").put("type", "text");
        reject(wrongType, "wrong native type");

        JSONObject wrongDefault = copy(standardManifest(2));
        nativeSetting(wrongDefault, "corners").put("default", "sometimes");
        reject(wrongDefault, "wrong native default");

        JSONObject shellRuntime = copy(standardManifest(2));
        shellRuntime.put("runtime", PluginManifest.SHELL_RUNTIME);
        shellRuntime.put("id", "dev.owornate.example.monet");
        reject(shellRuntime, "native settings on shell runtime");

        JSONObject unknownRuntime = copy(standardManifest(2));
        unknownRuntime.put("runtime", "native-v1");
        reject(unknownRuntime, "unknown runtime");

        JSONObject widgets = copy(standardManifest(2));
        widgets.put("id", "dev.owornate.widgets");
        reject(widgets, "native settings on widgets");

        JSONObject reorderedJson = monetManifest(2);
        JSONArray changed = new JSONArray();
        changed.put(nativeSetting("corners", "圆角开关", "true", new String[][] {
                {"false", "关闭"}, {"true", "开启"}
        }));
        changed.put(nativeSetting("mode", "取色策略", "auto", new String[][] {
                {"fixed", "固定颜色"}, {"auto", "自动取色"}, {"original", "系统原色"}
        }));
        reorderedJson.put("nativeSettings", changed);
        PluginManifest reordered = PluginManifest.parse(reorderedJson.toString());
        PluginManifest.Setting changedMode = MonetSettings.definition(reordered, "mode");
        equal("取色策略", changedMode.title, "editable mode title");
        equal(Arrays.asList("fixed", "auto", "original"),
                new ArrayList<>(changedMode.options.keySet()), "editable mode order");
        equal(Arrays.asList("固定颜色", "自动取色", "系统原色"),
                new ArrayList<>(changedMode.options.values()), "editable mode labels");
        PluginManifest.Setting changedCorners = MonetSettings.definition(reordered, "corners");
        equal("圆角开关", changedCorners.title, "editable corners title");
        equal(Arrays.asList("false", "true"),
                new ArrayList<>(changedCorners.options.keySet()), "editable corners order");
        finish("native declarations, exact options, rejection, and legacy fallback");
    }

    private static void readUsesActualAck() throws Exception {
        Context context = newCase("actual-ack");
        importManifest(context, standardManifest(2));
        seedWorkshop(context, "original", 0xff010203, true, false);
        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF334455", true);

        Map<String, String> actual = MonetSettings.config(context);
        equal("fixed", actual.get("mode"), "read returns ack mode");
        equal("true", actual.get("corners"), "read returns ack corners");
        equal(Collections.singletonList("read"), ThemeRuntime.actions(),
                "read performs one actual read");
        SharedPreferences preferences = workshop(context);
        equal("fixed", preferences.getString("mode", ""), "ack mode is persisted");
        equal(0xff334455, preferences.getInt("seed", 0), "ack seed is persisted");
        equal(true, preferences.getBoolean("corners", false), "ack corners are persisted");
        equal(false, preferences.getBoolean("pendingTheme", true), "ack clears pending");
        equal(1, RuntimeStateStore.themeRecords(), "ack records one confirmed theme");
        finish("manifest defaults and old preferences do not supply runtime values");
    }

    private static void configurePatchesFreshActual() throws Exception {
        Context context = newCase("patch-actual");
        importManifest(context, standardManifest(2));
        seedWorkshop(context, "auto", 0xff010203, false, false);
        ModuleManager.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.reset();
        ThemeRuntime.queueRead("fixed", "#FF112233", false);
        ThemeRuntime.queueApply("original", "#FF112233", false);

        Map<String, String> changedMode = MonetSettings.configure(context, "mode", "original");
        equal("original", changedMode.get("mode"), "mode result");
        equal("false", changedMode.get("corners"), "mode preserves actual corners");
        List<ThemeRuntime.Exchange> calls = ThemeRuntime.exchanges();
        equal(2, calls.size(), "mode configure exchanges read and apply");
        equal("read", calls.get(0).action, "mode configure reads actual first");
        equal("apply", calls.get(1).action, "mode configure applies second");
        ThemeConfig applied = calls.get(1).config;
        equal("original", applied.mode, "mode patch changes mode");
        equal(0xff112233, applied.seed, "mode patch preserves actual seed");
        equal(false, applied.corners, "mode patch preserves actual corners");
        equal(1, ModuleManager.remembered(), "mode success remembers enabled");

        ThemeRuntime.reset();
        ModuleManager.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF778899", false);
        ThemeRuntime.queueApply("fixed", "#FF778899", true);
        Map<String, String> changedCorners = MonetSettings.configure(context, "corners", "true");
        equal("fixed", changedCorners.get("mode"), "corners preserves actual mode");
        equal("true", changedCorners.get("corners"), "corners result");
        ThemeConfig cornerPatch = ThemeRuntime.exchanges().get(1).config;
        equal("fixed", cornerPatch.mode, "corners patch preserves fresh actual mode");
        equal(0xff778899, cornerPatch.seed, "corners patch preserves fresh actual seed");
        equal(true, cornerPatch.corners, "corners patch changes corners");
        SharedPreferences preferences = workshop(context);
        equal(0xff778899, preferences.getInt("seed", 0), "confirmed seed replaces old seed");
        equal(false, preferences.getBoolean("pendingTheme", true), "confirmed patch clears pending");
        equal(1, RuntimeStateStore.themeRecords(), "corners records one confirmed theme");
        finish("only the selected field is patched onto a fresh actual state");
    }

    private static void writeGates() throws Exception {
        ModuleState[] blocked = {
                new ModuleState("disabled", "own", false, "disabled", true, null),
                new ModuleState("installed", "own", false, "unknown", false, null),
                new ModuleState("setup", "setup", false, "setup", true, null),
                new ModuleState("conflict", "conflict", false, "conflict", true, null)
        };
        String[] labels = {"disabled", "unknown", "setup", "conflict"};
        for (int i = 0; i < blocked.length; i++) {
            Context context = newCase("gate-" + labels[i]);
            importManifest(context, standardManifest(2));
            seedWorkshop(context, "auto", 0xff102030, true, false);
            Map<String, ?> before = workshop(context).getAll();
            ModuleManager.reset();
            ModuleManager.setState(blocked[i]);
            ThemeRuntime.reset();
            RuntimeStateStore.reset();
            expect(IOException.class, () -> MonetSettings.configure(context, "mode", "fixed"),
                    labels[i] + " rejects configure");
            equal(before, workshop(context).getAll(), labels[i] + " changes no preference");
            check(ThemeRuntime.actions().isEmpty(), labels[i] + " performs no theme action");
            equal(0, ModuleManager.remembered(), labels[i] + " remembers no write");
            equal(0, RuntimeStateStore.monetFailures(),
                    labels[i] + " gate writes no observation failure");
        }

        Context legacy = newCase("gate-no-declaration");
        importManifest(legacy, monetManifest(1));
        seedWorkshop(legacy, "auto", 0xff405060, false, false);
        Map<String, ?> beforeLegacy = workshop(legacy).getAll();
        ModuleManager.reset();
        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        expect(IOException.class, () -> MonetSettings.configure(legacy, "mode", "fixed"),
                "legacy package rejects configure");
        equal(beforeLegacy, workshop(legacy).getAll(), "legacy configure changes no preference");
        check(ThemeRuntime.actions().isEmpty(), "legacy configure performs no theme action");
        equal(0, ModuleManager.reads(), "legacy declaration is rejected before runtime read");

        Context missing = newCase("gate-no-package");
        seedWorkshop(missing, "auto", 0xff506070, false, false);
        Map<String, ?> beforeMissing = workshop(missing).getAll();
        ThemeRuntime.reset();
        expect(IOException.class, () -> MonetSettings.configure(missing, "mode", "fixed"),
                "missing package rejects configure");
        equal(beforeMissing, workshop(missing).getAll(), "missing package changes no preference");
        check(ThemeRuntime.actions().isEmpty(), "missing package performs no theme action");
        finish("disabled, unknown, setup, conflict, and undeclared writes are blocked");
    }

    private static void applyFailureAndRecovery() throws Exception {
        Context context = newCase("apply-failure");
        importManifest(context, standardManifest(2));
        seedWorkshop(context, "auto", 0xff010203, false, false);
        ModuleManager.reset();
        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF445566", true);
        ThemeRuntime.failNextApply(new IOException("apply failed"));

        expect(IOException.class, () -> MonetSettings.configure(context, "mode", "original"),
                "apply failure is reported");
        equal(Arrays.asList("read", "apply"), ThemeRuntime.actions(),
                "failed apply was attempted after read");
        SharedPreferences preferences = workshop(context);
        equal("auto", preferences.getString("mode", ""), "failed target is not saved");
        equal(0xff010203, preferences.getInt("seed", 0), "failed apply keeps old seed record");
        equal(false, preferences.getBoolean("corners", true), "failed apply keeps old corners");
        equal(true, preferences.getBoolean("pendingTheme", false), "failed apply keeps pending");
        equal(0, ModuleManager.remembered(), "failed apply does not remember success");
        equal(0, RuntimeStateStore.themeRecords(), "failed apply records no confirmed theme");
        equal(1, RuntimeStateStore.monetFailures(), "failed apply reports observation failure");

        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF445566", true);
        Map<String, String> recovered = MonetSettings.config(context);
        equal("fixed", recovered.get("mode"), "refresh returns actual mode after failure");
        equal("true", recovered.get("corners"), "refresh returns actual corners after failure");
        equal(Collections.singletonList("read"), ThemeRuntime.actions(),
                "pending refresh uses one actual read");
        equal(false, preferences.getBoolean("pendingTheme", true),
                "successful actual read clears pending");
        equal("fixed", preferences.getString("mode", ""),
                "successful actual read replaces the stale record");
        finish("apply failure retains pending until config reads actual state");
    }

    private static void commitFailures() throws Exception {
        Context beforeApply = newCase("pending-commit-failure");
        importManifest(beforeApply, standardManifest(2));
        SharedPreferences beforeDelegate = workshop(beforeApply);
        seedWorkshop(beforeApply, "auto", 0xff010203, false, false);
        beforeApply.setSharedPreferences("workshop", new FailCommitPreferences(beforeDelegate, 1));
        ModuleManager.reset();
        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF112233", false);

        expect(IOException.class, () -> MonetSettings.configure(beforeApply, "mode", "original"),
                "pending commit failure is reported");
        equal(Collections.singletonList("read"), ThemeRuntime.actions(),
                "pending commit failure prevents apply");
        equal(true, beforeDelegate.getBoolean("pendingTheme", false),
                "failed disk commit can still update in-memory pending");
        equal(0, RuntimeStateStore.themeRecords(), "failed pending commit records no theme");

        Context afterApply = newCase("confirmed-commit-failure");
        importManifest(afterApply, standardManifest(2));
        SharedPreferences afterDelegate = workshop(afterApply);
        seedWorkshop(afterApply, "auto", 0xff010203, false, false);
        afterApply.setSharedPreferences("workshop", new FailCommitPreferences(afterDelegate, 2));
        ModuleManager.reset();
        ThemeRuntime.reset();
        RuntimeStateStore.reset();
        ThemeRuntime.queueRead("fixed", "#FF112233", false);
        ThemeRuntime.queueApply("original", "#FF112233", false);

        expect(IOException.class, () -> MonetSettings.configure(afterApply, "mode", "original"),
                "confirmed commit failure is reported");
        equal(Arrays.asList("read", "apply"), ThemeRuntime.actions(),
                "confirmed commit failure follows a real apply");
        equal("original", afterDelegate.getString("mode", ""),
                "failed disk commit can expose acknowledged mode in memory");
        equal(0xff112233, afterDelegate.getInt("seed", 0),
                "failed disk commit can expose acknowledged seed in memory");
        equal(false, afterDelegate.getBoolean("pendingTheme", true),
                "failed disk commit does not guarantee pending rollback in memory");
        equal(0, RuntimeStateStore.themeRecords(),
                "failed confirmed commit records no fake success");
        finish("commit false prevents apply or is surfaced without fake success");
    }

    private static void importAndUpgrade() throws Exception {
        Context current = newCase("actual-package-import");
        seedWorkshop(current, "fixed", 0xff0a0b0c, true, false);
        Map<String, ?> currentBefore = workshop(current).getAll();
        ThemeRuntime.reset();
        PluginManifest installed = importPackage(current, actualPackage);
        equal(MONET, installed.id, "actual package id");
        equal(2, installed.versionCode, "actual package version");
        equal(2, installed.nativeSettings.size(), "actual package native settings");
        assertMode(MonetSettings.definition(installed, "mode"), "actual package mode");
        assertCorners(MonetSettings.definition(installed, "corners"),
                "actual package corners");
        equal(currentBefore, workshop(current).getAll(),
                "actual package import changes no workshop preference");
        check(ThemeRuntime.actions().isEmpty(), "actual package import calls no ThemeRuntime");

        Context upgrade = newCase("legacy-upgrade");
        seedWorkshop(upgrade, "fixed", 0xff203040, true, false);
        Map<String, ?> upgradeBefore = workshop(upgrade).getAll();
        ThemeRuntime.reset();
        PluginManifest old = importManifest(upgrade, monetManifest(1));
        check(old.nativeSettings.isEmpty(), "legacy package remains importable");
        assertFallbackMode(MonetSettings.definition(upgrade, "mode"),
                "legacy installed mode fallback");
        equal(upgradeBefore, workshop(upgrade).getAll(),
                "legacy import changes no workshop preference");

        PluginManifest upgraded = importManifest(upgrade,
                nativeManifest(2, "original", "false"));
        equal("original", MonetSettings.definition(upgraded, "mode").defaultValue,
                "upgrade exposes new default");
        equal("false", MonetSettings.definition(upgraded, "corners").defaultValue,
                "upgrade exposes new corners default");
        equal(upgradeBefore, workshop(upgrade).getAll(),
                "upgrade defaults do not change workshop preferences");
        check(ThemeRuntime.actions().isEmpty(),
                "legacy import and upgrade call no ThemeRuntime");
        finish("actual import and legacy upgrade leave preferences and runtime untouched");
    }

    private static JSONObject standardManifest(int versionCode) {
        return nativeManifest(versionCode, "auto", "true");
    }

    private static JSONObject nativeManifest(int versionCode, String modeDefault,
            String cornersDefault) {
        JSONObject manifest = monetManifest(versionCode);
        manifest.put("nativeSettings", new JSONArray()
                .put(nativeSetting("mode", "配色方式", modeDefault, MODE_OPTIONS))
                .put(nativeSetting("corners", "连续圆角", cornersDefault, CORNER_OPTIONS)));
        return manifest;
    }

    private static JSONObject monetManifest(int versionCode) {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("runtime", PluginManifest.LAUNCHER_RUNTIME);
        manifest.put("permissions", new JSONArray().put("root"));
        manifest.put("device", new JSONObject()
                .put("model", "OW20W1")
                .put("firmware", "OW20W1_11_A.92")
                .put("sdk", 27)
                .put("abi", "armeabi-v7a"));
        manifest.put("id", MONET);
        manifest.put("name", "莫奈取色");
        manifest.put("description", "MonetSettingsTest fixture");
        manifest.put("versionName", versionCode == 2 ? "1.1.0" : versionCode + ".0.0");
        manifest.put("versionCode", versionCode);
        manifest.put("settings", new JSONArray());
        return manifest;
    }

    private static JSONObject nativeSetting(String key, String title, String defaultValue,
            String[][] options) {
        return new JSONObject().put("type", "choice").put("key", key).put("title", title)
                .put("default", defaultValue).put("options", optionArray(options));
    }

    private static JSONArray optionArray(String[][] options) {
        JSONArray result = new JSONArray();
        for (String[] item : options) result.put(option(item[0], item[1]));
        return result;
    }

    private static JSONObject option(String value, String label) {
        return new JSONObject().put("value", value).put("label", label);
    }

    private static JSONObject nativeSetting(JSONObject manifest, String key) {
        JSONArray settings = manifest.getJSONArray("nativeSettings");
        for (int i = 0; i < settings.length(); i++) {
            JSONObject item = settings.getJSONObject(i);
            if (key.equals(item.optString("key"))) return item;
        }
        throw new AssertionError("missing native setting " + key);
    }

    private static JSONObject copy(JSONObject value) {
        return new JSONObject(value.toString());
    }

    private static void assertMode(PluginManifest.Setting setting, String label) {
        equal("mode", setting.key, label + " key");
        equal("auto", setting.defaultValue, label + " default");
        equal(Arrays.asList("auto", "fixed", "original"),
                new ArrayList<>(setting.options.keySet()), label + " exact option set");
    }

    private static void assertCorners(PluginManifest.Setting setting, String label) {
        equal("corners", setting.key, label + " key");
        equal("true", setting.defaultValue, label + " default");
        equal(Arrays.asList("true", "false"),
                new ArrayList<>(setting.options.keySet()), label + " exact option set");
    }

    private static void assertFallbackMode(PluginManifest.Setting setting, String label) {
        equal("配色方式", setting.title, label + " title");
        assertMode(setting, label);
    }

    private static void assertFallbackCorners(PluginManifest.Setting setting, String label) {
        equal("连续圆角", setting.title, label + " title");
        assertCorners(setting, label);
    }

    private static void reject(JSONObject manifest, String label) throws Exception {
        expect(IOException.class, () -> PluginManifest.parse(manifest.toString()),
                label + " is rejected");
    }

    private static PluginManifest importManifest(Context context, JSONObject manifest)
            throws Exception {
        PluginRepository repository = ExternalPlugins.repository(context);
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifest.toString().getBytes(StandardCharsets.UTF_8), HOOK, THEME)));
        return ExternalPlugins.importPackage(context, pending);
    }

    private static PluginManifest importPackage(Context context, Path packageFile)
            throws Exception {
        PluginRepository repository = ExternalPlugins.repository(context);
        try (InputStream input = Files.newInputStream(packageFile)) {
            return ExternalPlugins.importPackage(context, repository.stage(input));
        }
    }

    private static byte[] zip(byte[] manifest, byte[] hook, byte[] theme) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, "plugin.json", manifest);
            writeEntry(zip, "hook.js", hook);
            writeEntry(zip, "theme.jar", theme);
        }
        return output.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static SharedPreferences workshop(Context context) {
        return context.getSharedPreferences("workshop", Context.MODE_PRIVATE);
    }

    private static void seedWorkshop(Context context, String mode, int seed, boolean corners,
            boolean pending) throws IOException {
        if (!workshop(context).edit().putString("mode", mode).putInt("seed", seed)
                .putBoolean("corners", corners).putBoolean("pendingTheme", pending).commit()) {
            throw new IOException("seed workshop preference failed");
        }
    }

    private static Context newCase(String name) throws IOException {
        Path root = runtimeRoot.resolve("cases").resolve(name).normalize();
        if (!root.startsWith(runtimeRoot)) throw new IOException("case escapes runtime root");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) deleteTree(root);
        Path files = root.resolve("files");
        Files.createDirectories(files);
        return new Context(files.toFile());
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            Iterator<Path> iterator = children.iterator();
            while (iterator.hasNext()) deleteTree(iterator.next());
        }
        Files.deleteIfExists(path);
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing -D" + key);
        return value;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action,
            String message) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(message + ": wrong exception "
                    + error.getClass().getName(), error);
        }
        throw new AssertionError(message + ": no exception");
    }

    private static void finish(String name) {
        cases++;
        System.out.println("  PASS " + name);
    }

    private static void runGroup(ArrayList<String> failures, String name,
            ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            failures.add(name + ": " + error);
            System.out.println("  FAIL " + name + ": " + error);
        } finally {
            ThemeRuntime.reset();
            ModuleManager.reset();
            RuntimeStateStore.reset();
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class FailCommitPreferences implements SharedPreferences {
        private final SharedPreferences delegate;
        private final int failOn;
        private int commits;

        FailCommitPreferences(SharedPreferences delegate, int failOn) {
            this.delegate = delegate;
            this.failOn = failOn;
        }

        @Override public Map<String, ?> getAll() { return delegate.getAll(); }
        @Override public String getString(String key, String fallback) {
            return delegate.getString(key, fallback);
        }
        @Override public boolean getBoolean(String key, boolean fallback) {
            return delegate.getBoolean(key, fallback);
        }
        @Override public int getInt(String key, int fallback) {
            return delegate.getInt(key, fallback);
        }
        @Override public long getLong(String key, long fallback) {
            return delegate.getLong(key, fallback);
        }
        @Override public boolean contains(String key) { return delegate.contains(key); }
        @Override public Editor edit() {
            final Editor target = delegate.edit();
            return new Editor() {
                @Override public Editor putString(String key, String value) {
                    target.putString(key, value);
                    return this;
                }
                @Override public Editor putBoolean(String key, boolean value) {
                    target.putBoolean(key, value);
                    return this;
                }
                @Override public Editor putInt(String key, int value) {
                    target.putInt(key, value);
                    return this;
                }
                @Override public Editor putLong(String key, long value) {
                    target.putLong(key, value);
                    return this;
                }
                @Override public Editor remove(String key) {
                    target.remove(key);
                    return this;
                }
                @Override public Editor clear() {
                    target.clear();
                    return this;
                }
                @Override public boolean commit() {
                    commits++;
                    boolean committed = target.commit();
                    return commits != failOn && committed;
                }
                @Override public void apply() { target.apply(); }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            delegate.registerOnSharedPreferenceChangeListener(listener);
        }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            delegate.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }
}
