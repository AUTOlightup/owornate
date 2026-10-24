package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import dev.codex.watchworkshop.ModuleManagement.Action;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Declarative capture settings, persistence bridge, upgrade, and removal tests. */
final class CaptureSettingsTest {
    private static final String CAPTURE = CaptureSettings.PLUGIN_ID;
    private static final byte[] HOOK = "// capture native settings test\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final String[][] STANDARD_OPTIONS = {
            {"600000", "低"}, {"1200000", "标准"}, {"2400000", "高"}
    };

    private static Path runtimeRoot;
    private static Path packageDir;
    private static Path actualCapturePackage;
    private static int checks;
    private static int cases;

    private CaptureSettingsTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(System.getProperty("capture.settings.test.root"))
                .toAbsolutePath().normalize();
        packageDir = Paths.get(System.getProperty("capture.package.dir"))
                .toAbsolutePath().normalize();
        actualCapturePackage = findCapturePackage();
        Files.createDirectories(runtimeRoot);
        check(Files.isRegularFile(actualCapturePackage, LinkOption.NOFOLLOW_LINKS),
                "capture package exists");

        ArrayList<String> failures = new ArrayList<>();
        runGroup(failures, "fallback and shell settings",
                CaptureSettingsTest::fallbackAndShellSettings);
        runGroup(failures, "real new declaration",
                CaptureSettingsTest::realNewDeclarationAccepted);
        runGroup(failures, "declaration changes and preference bridge",
                CaptureSettingsTest::declarationChangesAndBridge);
        runGroup(failures, "upgrade keeps legacy choice",
                CaptureSettingsTest::upgradeKeepsLegacyChoice);
        runGroup(failures, "upgrade freezes implicit default",
                CaptureSettingsTest::upgradeFreezesImplicitDefault);
        runGroup(failures, "shared removal keeps implicit default",
                CaptureSettingsTest::sharedRemovalKeepsImplicitDefault);
        runGroup(failures, "invalid declarations",
                CaptureSettingsTest::invalidDeclarationsRejected);
        runGroup(failures, "failing preference commit",
                CaptureSettingsTest::failingCommitReported);
        runGroup(failures, "corrupt installed package",
                CaptureSettingsTest::corruptPackageReported);

        System.out.println("CaptureSettingsTest: " + cases + " cases, " + checks
                + " checks, " + failures.size() + " failed groups");
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " groups failed: " + failures);
        }
    }

    private static void fallbackAndShellSettings() throws Exception {
        PluginManifest old = PluginManifest.parse(legacyCaptureManifest(1).toString());
        assertStandardDefinition(CaptureSettings.definition(old), "old package fallback");

        Context noPackage = newCase("no-package-fallback");
        assertStandardDefinition(CaptureSettings.definition(noPackage), "no package fallback");

        PluginManifest shell = PluginManifest.parse(shellManifest().toString());
        check(shell.nativeSettings.isEmpty(), "shell settings do not become native settings");
        equal(1, shell.settings.size(), "shell setting count");
        equal("熄屏时长", shell.displaySettings().get(0).title,
                "shell declarative setting remains visible");
        equal(Collections.singletonMap("timeout", "30000"), shell.defaults(),
                "shell defaults remain unchanged");
        assertStandardDefinition(CaptureSettings.definition(shell),
                "shell plugin keeps capture fallback");
        finish("old/no package fallback and shell settings unchanged");
    }

    private static void realNewDeclarationAccepted() throws Exception {
        Context context = newCase("real-new-declaration");
        PluginRepository repository = ExternalPlugins.repository(context);
        PluginManifest installed;
        try (InputStream input = Files.newInputStream(actualCapturePackage)) {
            PluginRepository.Pending pending = repository.stage(input);
            installed = ExternalPlugins.importPackage(context, pending);
        }

        equal(CAPTURE, installed.id, "real capture id");
        equal("1.1.0", installed.versionName, "real capture versionName");
        equal(2, installed.versionCode, "real capture versionCode");
        check(installed.settings.isEmpty(), "capture shell settings stay empty");
        equal(1, installed.nativeSettings.size(), "capture declares one native setting");
        assertStandardDefinition(CaptureSettings.definition(context), "real declaration");
        equal(Collections.singletonMap("bitrate", "1200000"),
                CaptureSettings.config(context, installed), "real declaration initial value");
        assertNoRepositoryConfig(repository, "real declaration");
        finish("real capture 1.1.0 declarative settings accepted");
    }

    private static void declarationChangesAndBridge() throws Exception {
        Context context = newCase("declaration-changes");
        String[][] changedOptions = {
                {"2400000", "高清"}, {"600000", "省流"}, {"1200000", "均衡"}
        };
        importManifest(context, nativeCaptureManifest(1, "600000", "自定义录屏画质",
                changedOptions).toString());

        PluginManifest.Setting definition = CaptureSettings.definition(context);
        equal("bitrate", definition.key, "native key reflected");
        equal("自定义录屏画质", definition.title, "native title reflected");
        equal("600000", definition.defaultValue, "native default reflected");
        equal(Arrays.asList("2400000", "600000", "1200000"),
                new ArrayList<>(definition.options.keySet()), "native option order reflected");
        equal(Arrays.asList("高清", "省流", "均衡"),
                new ArrayList<>(definition.options.values()), "native labels reflected");

        SharedPreferences preferences = CaptureSettings.preferences(context);
        check(preferences.getAll().isEmpty(), "import does not write a native preference");
        check(preferences.edit().putInt("bitrate", 1200000).commit(),
                "seed legacy native preference");
        equal(1200000, CaptureSettings.read(context, definition),
                "native read uses capture_options/bitrate");
        equal(Collections.singletonMap("bitrate", "2400000"),
                CaptureSettings.configure(context, CAPTURE,
                        Collections.singletonMap("bitrate", "2400000")),
                "plugin configure writes native preference");
        equal(2400000, preferences.getInt("bitrate", -1),
                "plugin configure writes the same preference key");
        CaptureSettings.save(context, 600000);
        equal(Collections.singletonMap("bitrate", 600000), preferences.getAll(),
                "capture_options contains only bitrate");
        equal(Collections.singletonMap("bitrate", "600000"),
                CaptureSettings.config(context,
                        ExternalPlugins.repository(context).load(CAPTURE)),
                "native config reads the saved preference");
        assertNoRepositoryConfig(ExternalPlugins.repository(context), "preference bridge");
        finish("changed title/order/labels/default and shared preference bridge");
    }

    private static void upgradeKeepsLegacyChoice() throws Exception {
        Context context = newCase("legacy-choice-upgrade");
        CaptureSettings.save(context, 2400000);
        importManifest(context, legacyCaptureManifest(1).toString());
        equal(Collections.singletonMap("bitrate", 2400000),
                CaptureSettings.preferences(context).getAll(),
                "legacy choice survives first old-package import");

        PluginManifest upgraded = importManifest(context,
                nativeCaptureManifest(2, "1200000", "录屏画质", STANDARD_OPTIONS).toString());
        equal(2, upgraded.versionCode, "upgraded capture version");
        equal("1200000", CaptureSettings.definition(context).defaultValue,
                "new declaration has a different default");
        equal(2400000, CaptureSettings.read(context, CaptureSettings.definition(context)),
                "upgrade preserves the legacy stored choice");
        equal(Collections.singletonMap("bitrate", 2400000),
                CaptureSettings.preferences(context).getAll(),
                "upgrade leaves the native preference unchanged");
        assertNoRepositoryConfig(ExternalPlugins.repository(context), "legacy choice upgrade");
        finish("legacy stored choice survives the first native declaration");
    }

    private static void upgradeFreezesImplicitDefault() throws Exception {
        Context context = newCase("implicit-default-upgrade");
        importManifest(context, legacyCaptureManifest(1).toString());
        check(CaptureSettings.preferences(context).getAll().isEmpty(),
                "old package does not seed a preference");

        importManifest(context, nativeCaptureManifest(2, "600000", "录屏画质",
                STANDARD_OPTIONS).toString());
        equal(Collections.singletonMap("bitrate", 1200000),
                CaptureSettings.preferences(context).getAll(),
                "upgrade freezes the old implicit default");
        equal("600000", CaptureSettings.definition(context).defaultValue,
                "the new declaration default changed immediately");

        PluginManifest latest = importManifest(context,
                nativeCaptureManifest(3, "2400000", "录屏画质", STANDARD_OPTIONS).toString());
        equal(3, latest.versionCode, "latest capture version");
        equal("2400000", CaptureSettings.definition(context).defaultValue,
                "later declaration changes its default");
        equal(1200000, CaptureSettings.read(context, CaptureSettings.definition(context)),
                "later default does not replace the frozen choice");
        equal(Collections.singletonMap("bitrate", 1200000),
                CaptureSettings.preferences(context).getAll(),
                "later upgrade keeps the frozen preference");
        assertNoRepositoryConfig(ExternalPlugins.repository(context), "implicit default upgrade");
        finish("implicit old default is frozen before a later default change");
    }

    private static void sharedRemovalKeepsImplicitDefault() throws Exception {
        Context context = newCase("shared-removal");
        PluginRepository repository = ExternalPlugins.repository(context);
        importManifest(context, nativeCaptureManifest(1, "2400000", "录屏画质",
                STANDARD_OPTIONS).toString());
        check(CaptureSettings.preferences(context).getAll().isEmpty(),
                "removal starts without a stored choice");

        WidgetHostManager.reset();
        WidgetHostManager.setCapture("ready", "shared");
        ManagerCalls.reset();
        LauncherPlugins.execute(context, "capture", Action.UNINSTALL);

        check(ManagerCalls.calls.isEmpty(), "shared removal calls no native manager");
        check(repository.load(CAPTURE) == null, "shared removal deletes the imported package");
        equal(Collections.singletonMap("bitrate", 2400000),
                CaptureSettings.preferences(context).getAll(),
                "shared removal preserves the declared default");
        PluginManifest.Setting fallback = CaptureSettings.definition(context);
        assertStandardDefinition(fallback, "fallback after shared removal");
        equal(2400000, CaptureSettings.read(context, fallback),
                "stored declared default outlives the package");
        finish("LauncherPlugins shared uninstall preserves declared default before removal");
    }

    private static void invalidDeclarationsRejected() throws Exception {
        JSONObject unknownRuntime = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        unknownRuntime.put("runtime", "native-v1");
        reject(unknownRuntime, "unsupported runtime");

        JSONObject shellRuntime = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        shellRuntime.put("runtime", "shell-v1");
        shellRuntime.put("id", "com.example.native");
        reject(shellRuntime, "native setting on shell runtime");

        JSONObject wrongRole = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        wrongRole.put("id", "dev.owornate.widgets");
        reject(wrongRole, "native setting on non-capture launcher");

        JSONObject wrongKey = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        wrongKey.getJSONArray("nativeSettings").getJSONObject(0).put("key", "quality");
        reject(wrongKey, "native setting key");

        JSONObject unsupportedRate = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        unsupportedRate.getJSONArray("nativeSettings").getJSONObject(0)
                .getJSONArray("options").getJSONObject(0).put("value", "480000");
        reject(unsupportedRate, "unsupported native rate");

        JSONObject missingRate = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        missingRate.getJSONArray("nativeSettings").getJSONObject(0)
                .getJSONArray("options").remove(0);
        reject(missingRate, "missing native rate");

        JSONObject wrongType = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        wrongType.getJSONArray("nativeSettings").getJSONObject(0).put("type", "text");
        reject(wrongType, "native setting type");

        JSONObject wrongDefault = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        wrongDefault.getJSONArray("nativeSettings").getJSONObject(0)
                .put("default", "900000");
        reject(wrongDefault, "native setting default");

        JSONObject duplicate = nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS);
        JSONArray duplicates = duplicate.getJSONArray("nativeSettings");
        duplicates.put(new JSONObject(duplicates.getJSONObject(0).toString()));
        reject(duplicate, "more than one native setting");
        finish("invalid native declarations are rejected");
    }

    private static void failingCommitReported() throws Exception {
        Context context = newCase("failed-commit");
        importManifest(context, nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS).toString());
        SharedPreferences delegate = CaptureSettings.preferences(context);
        check(delegate.edit().putInt("bitrate", 1200000).commit(),
                "seed preference before failed commit");
        context.setSharedPreferences("capture_options",
                new FailingCommitPreferences(delegate));

        expect(IOException.class, () -> CaptureSettings.save(context, 600000),
                "commit false throws IOException");
        equal(1200000, delegate.getInt("bitrate", -1),
                "failed commit does not reach the delegate");
        finish("commit false is reported as an error");
    }

    private static void corruptPackageReported() throws Exception {
        Context context = newCase("corrupt-installed-package");
        PluginRepository repository = ExternalPlugins.repository(context);
        importManifest(context, nativeCaptureManifest(1, "1200000", "录屏画质",
                STANDARD_OPTIONS).toString());
        Path manifest = repository.packageDir(CAPTURE).toPath().resolve("plugin.json");
        Files.write(manifest, "{\"schemaVersion\":".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        expect(IllegalStateException.class, () -> CaptureSettings.definition(context),
                "corrupt installed package is not replaced with fallback");
        finish("corrupt installed package throws instead of falling back");
    }

    private static PluginManifest importManifest(Context context, String manifest)
            throws Exception {
        PluginRepository repository = ExternalPlugins.repository(context);
        PluginRepository.Pending pending = repository.stage(
                new ByteArrayInputStream(zip(manifest, HOOK)));
        return ExternalPlugins.importPackage(context, pending);
    }

    private static Path findCapturePackage() throws IOException {
        Path found = null;
        int count = 0;
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(packageDir,
                "capture-*.owo")) {
            for (Path match : matches) {
                if (!Files.isRegularFile(match, LinkOption.NOFOLLOW_LINKS)) continue;
                found = match;
                count++;
            }
        }
        if (count != 1) {
            throw new IOException("expected exactly one capture-*.owo in " + packageDir
                    + ", found " + count);
        }
        return found;
    }

    private static JSONObject legacyCaptureManifest(int versionCode) {
        return captureManifest(versionCode);
    }

    private static JSONObject nativeCaptureManifest(int versionCode, String defaultValue,
            String title, String[][] options) {
        JSONObject manifest = captureManifest(versionCode);
        JSONObject setting = new JSONObject();
        setting.put("type", "choice");
        setting.put("key", "bitrate");
        setting.put("title", title);
        setting.put("default", defaultValue);
        setting.put("options", optionArray(options));
        manifest.put("nativeSettings", new JSONArray().put(setting));
        return manifest;
    }

    private static JSONObject captureManifest(int versionCode) {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("runtime", "launcher-v1");
        manifest.put("permissions", new JSONArray().put("root"));
        JSONObject device = new JSONObject();
        device.put("model", "OW20W1");
        device.put("firmware", "OW20W1_11_A.92");
        device.put("sdk", 27);
        device.put("abi", "armeabi-v7a");
        manifest.put("device", device);
        manifest.put("id", CAPTURE);
        manifest.put("name", "截屏与录屏");
        manifest.put("description", "仅用于声明式设置测试");
        manifest.put("versionName", versionCode == 2 ? "1.1.0" : versionCode + ".0.0");
        manifest.put("versionCode", versionCode);
        manifest.put("settings", new JSONArray());
        return manifest;
    }

    private static JSONObject shellManifest() {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", 1);
        manifest.put("runtime", "shell-v1");
        manifest.put("permissions", new JSONArray().put("root"));
        JSONObject device = new JSONObject();
        device.put("model", "OW20W1");
        device.put("firmware", "OW20W1_11_A.92");
        device.put("sdk", 27);
        device.put("abi", "armeabi-v7a");
        manifest.put("device", device);
        manifest.put("id", "dev.owornate.example.screen-timeout");
        manifest.put("name", "熄屏时长示例");
        manifest.put("description", "shell 设置保持原协议");
        manifest.put("versionName", "1.0.0");
        manifest.put("versionCode", 1);
        JSONObject setting = new JSONObject();
        setting.put("type", "choice");
        setting.put("key", "timeout");
        setting.put("title", "熄屏时长");
        setting.put("default", "30000");
        setting.put("options", optionArray(new String[][] {
                {"15000", "15 秒"}, {"30000", "30 秒"}, {"60000", "60 秒"}
        }));
        manifest.put("settings", new JSONArray().put(setting));
        return manifest;
    }

    private static JSONArray optionArray(String[][] options) {
        JSONArray result = new JSONArray();
        for (String[] option : options) {
            JSONObject item = new JSONObject();
            item.put("value", option[0]);
            item.put("label", option[1]);
            result.put(item);
        }
        return result;
    }

    private static void assertStandardDefinition(PluginManifest.Setting definition,
            String label) {
        equal("bitrate", definition.key, label + " key");
        equal("录屏画质", definition.title, label + " title");
        equal("1200000", definition.defaultValue, label + " default");
        equal(Arrays.asList("600000", "1200000", "2400000"),
                new ArrayList<>(definition.options.keySet()), label + " option order");
        equal(Arrays.asList("低", "标准", "高"),
                new ArrayList<>(definition.options.values()), label + " option labels");
    }

    private static void assertNoRepositoryConfig(PluginRepository repository, String label) {
        equal(Collections.emptyMap(), repository.config(CAPTURE), label + " repository config");
        Path file = repository.dataDir(CAPTURE).toPath().resolve("config.json");
        check(!Files.exists(file, LinkOption.NOFOLLOW_LINKS),
                label + " must not create config.json");
    }

    private static void reject(JSONObject manifest, String label) throws Exception {
        expect(IOException.class, () -> PluginManifest.parse(manifest.toString()),
                label + " is rejected");
    }

    private static byte[] zip(String manifest, byte[] hook) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            writeEntry(zip, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            writeEntry(zip, "hook.js", hook);
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
        try (Stream<Path> stream = Files.list(path)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) deleteTree(iterator.next());
        }
        Files.deleteIfExists(path);
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
            throw new AssertionError(message + ": wrong exception " + error.getClass().getName(),
                    error);
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
            RootShell.clearInjectedSession();
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class FailingCommitPreferences implements SharedPreferences {
        private final SharedPreferences delegate;

        FailingCommitPreferences(SharedPreferences delegate) {
            this.delegate = delegate;
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
                @Override public boolean commit() { return false; }
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
