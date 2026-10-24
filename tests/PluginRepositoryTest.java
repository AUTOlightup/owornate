package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Targeted checks for PluginManifest and PluginRepository using the production org.json jar. */
final class PluginRepositoryTest {
    private static final String SETTINGS =
            "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"模式\","
                    + "\"default\":\"quiet\",\"options\":["
                    + "{\"value\":\"quiet\",\"label\":\"安静\"},"
                    + "{\"value\":\"loud\",\"label\":\"响亮\"}]},"
                    + "{\"type\":\"choice\",\"key\":\"enabled\",\"title\":\"状态\","
                    + "\"default\":\"yes\",\"options\":["
                    + "{\"value\":\"yes\",\"label\":\"开\"},"
                    + "{\"value\":\"no\",\"label\":\"关\"}]}]";
    private static final String SETTINGS_WITH_EXTRA = SETTINGS.substring(0, SETTINGS.length() - 1)
            + ",{\"type\":\"choice\",\"key\":\"limit\",\"title\":\"限制\","
            + "\"default\":\"short\",\"options\":["
            + "{\"value\":\"short\",\"label\":\"短\"},"
            + "{\"value\":\"long\",\"label\":\"长\"}]}]";
    private static final String SETTINGS_WITHOUT_MODE =
            "[{\"type\":\"choice\",\"key\":\"enabled\",\"title\":\"状态\","
                    + "\"default\":\"yes\",\"options\":["
                    + "{\"value\":\"yes\",\"label\":\"开\"},"
                    + "{\"value\":\"no\",\"label\":\"关\"}]}]";
    private static final String SETTINGS_DEFAULTS_LOUD_NO = SETTINGS
            .replace("\"default\":\"quiet\"", "\"default\":\"loud\"")
            .replace("\"default\":\"yes\"", "\"default\":\"no\"");

    private static Path runtimeRoot;
    private static int checks;
    private static int cases;

    private PluginRepositoryTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(System.getProperty("plugin.test.root")).toAbsolutePath()
                .normalize();
        Files.createDirectories(runtimeRoot);

        manifestContract();
        manifestRejections();
        launcherPayloads();
        launcherExtensions();
        launcherRejections();
        launcherLimitsAndPayloadAccess();
        installAndRestart();
        configAndRemoval();
        duplicateInstallIsRejected();
        compatibleUpgradePreservesConfig();
        upgradePersistsOldDefaults();
        upgradeRejections();
        runtimeMismatchCannotEnterUpgrade();
        verifierFailureRestoresPackage();
        interruptedUpgradeRecovery();
        launcherUpgradeReplacesPayload();
        pendingLifecycle();
        zipRejections();
        limitsAndClosing();
        pathAndSymlinkSafety();

        System.out.println("PluginRepositoryTest: OK (" + cases + " cases, " + checks
                + " checks)");
    }

    private static void manifestContract() throws Exception {
        PluginManifest manifest = PluginManifest.parse(manifest("com.example.plugin", 7));
        equal("shell-v1", manifest.runtime, "shell runtime");
        equal("", manifest.feature(), "shell feature");
        equal("com.example.plugin", manifest.id, "id");
        equal("测试插件", manifest.name, "name");
        equal("仅用于本地测试", manifest.description, "description");
        equal("1.2.3", manifest.versionName, "versionName");
        equal(7, manifest.versionCode, "versionCode");
        equal("", manifest.updateUrl, "missing updateUrl defaults empty");
        equal(2, manifest.settings.size(), "setting count");
        equal("mode", manifest.settings.get(0).key, "first setting key");
        equal(Arrays.asList("quiet", "loud"),
                new ArrayList<>(manifest.settings.get(0).options.keySet()), "option order");
        equal("安静", manifest.settings.get(0).options.get("quiet"), "option label");

        Map<String, String> defaults = manifest.defaults();
        equal(Arrays.asList("mode", "enabled"), new ArrayList<>(defaults.keySet()),
                "default order");
        equal("quiet", defaults.get("mode"), "default mode");

        String updateUrl = "https://updates.example.com/owornate/plugin.owo";
        PluginManifest updated = PluginManifest.parse(manifest("com.example.plugin", 7)
                .replace("\"versionCode\":7,", "\"versionCode\":7,\"updateUrl\":\""
                        + updateUrl + "\","));
        equal(updateUrl, updated.updateUrl, "updateUrl parses");

        String maxId = repeat("a", 77) + ".bc";
        equal(maxId, PluginManifest.parse(manifest(maxId, 1)).id,
                "id accepts 80 characters with a dot");

        LinkedHashMap<String, String> supplied = new LinkedHashMap<>();
        supplied.put("enabled", "no");
        Map<String, String> validated = manifest.validateConfig(supplied);
        equal(Arrays.asList("mode", "enabled"), new ArrayList<>(validated.keySet()),
                "validated order");
        equal("quiet", validated.get("mode"), "default filled");
        equal("no", validated.get("enabled"), "provided value retained");

        expect(UnsupportedOperationException.class,
                () -> manifest.settings.add(manifest.settings.get(0)), "immutable settings");
        expect(UnsupportedOperationException.class,
                () -> manifest.settings.get(0).options.put("x", "x"), "immutable options");
        expect(UnsupportedOperationException.class,
                () -> defaults.put("x", "x"), "immutable defaults");

        LinkedHashMap<String, String> extra = new LinkedHashMap<>();
        extra.put("other", "value");
        expect(IllegalArgumentException.class, () -> manifest.validateConfig(extra),
                "unknown config key");
        LinkedHashMap<String, String> bad = new LinkedHashMap<>();
        bad.put("mode", "missing");
        expect(IllegalArgumentException.class, () -> manifest.validateConfig(bad),
                "invalid config value");
        finish("manifest contract");
    }

    private static void manifestRejections() throws Exception {
        ArrayList<String> invalid = new ArrayList<>();
        invalid.add(manifest("com.example.plugin", 1).replace(
                "\"schemaVersion\":1", "\"schemaVersion\":2"));
        invalid.add(manifest("com.example.plugin", 1).replace(
                "\"runtime\":\"shell-v1\"", "\"runtime\":\"shell-v2\""));
        invalid.add(manifest("com.example.plugin", 1).replace(
                "\"permissions\":[\"root\"]", "\"permissions\":[\"root\",\"network\"]"));
        invalid.add(manifest("com.example.plugin", 1).replace("OW20W1_11_A.92",
                "OW20W1_11_A.91"));
        invalid.add(manifest("com.example.plugin", 1).replace("\"model\":\"OW20W1\"",
                "\"model\":\"OTHER\""));
        invalid.add(manifest("com.example.plugin", 1).replace("\"sdk\":27", "\"sdk\":26"));
        invalid.add(manifest("com.example.plugin", 1).replace("\"abi\":\"armeabi-v7a\"",
                "\"abi\":\"arm64-v8a\""));
        invalid.add(manifest("com.example.plugin", 1).replace("\"name\":",
                "\"network\":true,\"name\":"));
        invalid.add(manifest("com.example.plugin", 0));
        invalid.add(manifest("monet", 1));
        invalid.add(manifest("widgets", 1));
        invalid.add(manifest("capture", 1));
        invalid.add(manifest("dev.owornate.monet", 1));
        invalid.add(manifest("dev.owornate.widgets", 1));
        invalid.add(manifest("dev.owornate.capture", 1));
        invalid.add(manifest("single", 1));
        invalid.add(manifest("Bad.ID", 1));
        invalid.add(manifest("com..example", 1));
        invalid.add(manifest(repeat("a", 78) + ".bc", 1));
        invalid.add(manifest("com.example.plugin", 1).replace("\"name\":\"测试插件\"",
                "\"name\":\"" + repeat("n", 61) + "\""));
        invalid.add(manifest("com.example.plugin", 1).replace("\"name\":\"测试插件\"",
                "\"name\":\"\""));
        invalid.add(manifest("com.example.plugin", 1).replace("\"name\":\"测试插件\"",
                "\"name\":\"   \""));
        invalid.add(manifest("com.example.plugin", 1).replace("仅用于本地测试",
                repeat("d", 801)));
        invalid.add(manifest("com.example.plugin", 1).replace("仅用于本地测试",
                "   "));
        invalid.add(manifest("com.example.plugin", 1).replace("\"versionName\":\"1.2.3\"",
                "\"versionName\":\"" + repeat("v", 41) + "\""));
        invalid.add(manifest("com.example.plugin", 1).replace("\"versionName\":\"1.2.3\"",
                "\"versionName\":\"   \""));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"toggle\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"Mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"a\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"c\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a/b\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"a\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}],\"extra\":1}]"));

        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]},"
                        + "{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"y\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"" + repeat("k", 33) + "\","
                        + "\"title\":\"x\",\"default\":\"a\",\"options\":["
                        + "{\"value\":\"a\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"a\",\"options\":["
                        + "{\"value\":\"" + repeat("v", 65) + "\",\"label\":\"A\"},"
                        + "{\"value\":\"b\",\"label\":\"B\"}]}]"));

        StringBuilder tooManyOptions = new StringBuilder(
                "{\"type\":\"choice\",\"key\":\"mode\",\"title\":\"x\","
                        + "\"default\":\"v0\",\"options\":[");
        for (int i = 0; i <= 12; i++) {
            if (i > 0) tooManyOptions.append(',');
            tooManyOptions.append("{\"value\":\"v").append(i)
                    .append("\",\"label\":\"L").append(i).append("\"}");
        }
        tooManyOptions.append("]}");
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                "[" + tooManyOptions + "]"));

        StringBuilder tooManySettings = new StringBuilder("[");
        for (int i = 0; i <= 12; i++) {
            if (i > 0) tooManySettings.append(',');
            tooManySettings.append("{\"type\":\"choice\",\"key\":\"s").append(i)
                    .append("\",\"title\":\"x\",\"default\":\"a\",\"options\":["
                            + "{\"value\":\"a\",\"label\":\"A\"},"
                            + "{\"value\":\"b\",\"label\":\"B\"}]}");
        }
        tooManySettings.append(']');
        invalid.add(manifestWithSettings("com.example.plugin", 1,
                tooManySettings.toString()));

        String versionField = "\"versionCode\":1,";
        invalid.add(manifest("com.example.plugin", 1).replace(versionField,
                versionField + "\"updateUrl\":\"http://updates.example.com/p.owo\","));
        invalid.add(manifest("com.example.plugin", 1).replace(versionField,
                versionField + "\"updateUrl\":\"https://user@example.com/p.owo\","));
        invalid.add(manifest("com.example.plugin", 1).replace(versionField,
                versionField + "\"updateUrl\":\"https://example.com/p.owo#part\","));
        invalid.add(manifest("com.example.plugin", 1).replace(versionField,
                versionField + "\"updateUrl\":\"https://example.com/" + repeat("a", 2048)
                        + "\","));

        int index = 0;
        for (String json : invalid) {
            final int caseIndex = ++index;
            expect(IOException.class, () -> PluginManifest.parse(json),
                    "incompatible manifest " + caseIndex);
        }
        expect(IOException.class, () -> PluginManifest.parse("{\"schemaVersion\":1"),
                "truncated manifest JSON");
        finish("manifest rejections");
    }

    private static void launcherPayloads() throws Exception {
        String[] ids = {"dev.owornate.monet", "dev.owornate.widgets", "dev.owornate.capture"};
        String[] features = {"monet", "widgets", "capture"};
        byte[] binaryTheme = new byte[] {0, 1, 2, (byte) 0xff, 0, 13, 10, 42};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i], feature = features[i];
            Path root = newCase("launcher-" + feature);
            byte[] hook = ("Java.perform(function () {\n"
                    + "  console.log('" + feature + "');\n});\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] theme = "monet".equals(feature) ? binaryTheme : null;
            PluginRepository repository = new PluginRepository(root.toFile());
            PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                    launcherZip(launcherManifest(id, 9), hook, theme)));
            equal("launcher-v1", pending.manifest.runtime, feature + " launcher runtime");
            equal(feature, pending.manifest.feature(), feature + " feature");
            check(pending.manifest.settings.isEmpty(), feature + " launcher settings empty");

            PluginRepository restarted = new PluginRepository(root.toFile());
            PluginRepository.Pending recovered = restarted.pending(pending.token);
            check(recovered != null, feature + " pending survives restart");
            PluginManifest installed = restarted.install(recovered);
            equal(feature, installed.feature(), feature + " installed feature");
            check(Arrays.equals(hook, restarted.payload(id, "hook.js")),
                    feature + " hook bytes retained");
            if (theme != null) {
                check(Arrays.equals(theme, restarted.payload(id, "theme.jar")),
                        "monet binary jar retained");
                equal(Arrays.asList("hook.js", "plugin.json", "theme.jar"),
                        fileNames(restarted.packageDir(id).toPath()), "monet package files");
            } else {
                equal(Arrays.asList("hook.js", "plugin.json"),
                        fileNames(restarted.packageDir(id).toPath()), feature + " package files");
                expect(IllegalStateException.class, () -> restarted.payload(id, "theme.jar"),
                        feature + " rejects theme.jar");
            }
            expect(IllegalStateException.class, () -> restarted.payload(id, "plugin.sh"),
                    feature + " rejects plugin.sh");
            expect(IllegalStateException.class, () -> restarted.payload(id, "../hook.js"),
                    feature + " rejects payload traversal");
            check(!Files.exists(restarted.packageDir(id).toPath().resolve("plugin.sh")),
                    feature + " package has no executable script");
        }
        finish("launcher payload roles");
    }

    private static void launcherExtensions() throws Exception {
        for (String reserved : new String[]{"monet.disabled", "widgets.disabled", "capture.disabled"}) {
            expect(IOException.class, () -> PluginManifest.parse(launcherManifest(reserved, 1)),
                    "official module state filename cannot be a Launcher plugin ID");
        }
        String id = "com.example.launcher";
        byte[] hook = "Java.use('android.app.Activity');\n".getBytes(StandardCharsets.UTF_8);
        PluginRepository repository = new PluginRepository(newCase("launcher-extension").toFile());
        PluginManifest m = repository.install(repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest(id, 1), hook, null))));
        equal(id, m.feature(), "third-party feature uses the actual ID");
        check(m.launcherExtension(), "third-party role identified");
        check(!PluginManifest.parse(launcherManifest("dev.owornate.monet", 1)).launcherExtension(), "official role retained");
        check(Arrays.equals(hook, repository.payload(id, "hook.js")), "third-party hook retained");
        expect(IllegalStateException.class, () -> repository.payload(id, "theme.jar"), "third-party cannot declare theme.jar");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest("com.example.extra", 1), hook, new byte[]{1}))), "extra jar rejected");
        finish("third-party launcher payload contract");
    }

    private static void launcherRejections() throws Exception {
        String hook = "console.log('hook');\n";
        byte[] binaryTheme = new byte[] {0, (byte) 0xff, 13, 10};
        ArrayList<String> invalidManifests = new ArrayList<>();
        invalidManifests.add(launcherManifest("com..example", 1));
        invalidManifests.add(launcherManifest("com.example.launcher", 1, SETTINGS));
        invalidManifests.add(launcherManifest("dev.owornate.monet", 1, SETTINGS));
        invalidManifests.add(launcherManifest("dev.owornate.widgets", 1).replace(
                "\"permissions\":[\"root\"]", "\"permissions\":[\"network\"]"));
        invalidManifests.add(launcherManifest("dev.owornate.capture", 1).replace(
                "\"sdk\":27", "\"sdk\":26"));
        for (String json : invalidManifests) {
            expect(IOException.class, () -> PluginManifest.parse(json), "invalid launcher manifest");
        }

        ArrayList<byte[]> invalidPackages = new ArrayList<>();
        String monet = launcherManifest("dev.owornate.monet", 1);
        String widgets = launcherManifest("dev.owornate.widgets", 1);
        String capture = launcherManifest("dev.owornate.capture", 1);
        invalidPackages.add(zipEntries(entry("plugin.json", monet), entry("hook.js", hook)));
        invalidPackages.add(launcherZip(widgets, hook.getBytes(StandardCharsets.UTF_8),
                binaryTheme));
        invalidPackages.add(launcherZip(capture, hook.getBytes(StandardCharsets.UTF_8),
                binaryTheme));
        invalidPackages.add(zipEntries(entry("plugin.json", widgets),
                entry("theme.jar", binaryTheme)));
        invalidPackages.add(launcherZip(monet, hook.getBytes(StandardCharsets.UTF_8),
                new byte[0]));
        invalidPackages.add(zipEntries(entry("plugin.json", widgets), entry("hook.js", hook),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")));
        invalidPackages.add(zipEntries(entry("plugin.json", widgets), entry("hook.js", hook),
                entry("README", "extra")));
        invalidPackages.add(launcherZip(widgets, new byte[0], null));
        invalidPackages.add(launcherZip(widgets,
                "\uFEFFconsole.log('hook');\n".getBytes(StandardCharsets.UTF_8), null));
        invalidPackages.add(launcherZip(widgets,
                "console.log('hook');\r\n".getBytes(StandardCharsets.UTF_8), null));
        invalidPackages.add(launcherZip(widgets,
                "console.log('hook');\0\n".getBytes(StandardCharsets.UTF_8), null));
        invalidPackages.add(launcherZip(widgets, new byte[] {(byte) 0xff, 0x0a}, null));
        invalidPackages.add(zipEntries(entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n"), entry("hook.js", hook)));
        invalidPackages.add(zipEntries(entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n"),
                entry("theme.jar", binaryTheme)));

        Path root = newCase("launcher-rejections");
        PluginRepository repository = new PluginRepository(root.toFile());
        int index = 0;
        for (byte[] bytes : invalidPackages) {
            final int caseIndex = ++index;
            expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(bytes)),
                    "invalid launcher package " + caseIndex);
        }
        check(listPending(root).isEmpty(), "launcher rejections leave no pending files");
        finish("launcher package rejections");
    }

    private static void launcherLimitsAndPayloadAccess() throws Exception {
        Path root = newCase("launcher-limits");
        PluginRepository repository = new PluginRepository(root.toFile());

        TrackingInput tooLargeInput = new TrackingInput(new byte[2 * 1024 * 1024 + 1]);
        expect(IOException.class, () -> repository.stage(tooLargeInput),
                "launcher compressed input limit");
        check(tooLargeInput.closed, "launcher input closes after size rejection");

        byte[] overTotal = new byte[2 * 1024 * 1024];
        Arrays.fill(overTotal, (byte) 'a');
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(launcherZip(
                launcherManifest("dev.owornate.widgets", 1), overTotal, null))),
                "launcher unpacked total limit");

        String largeManifest = launcherManifest("dev.owornate.widgets", 1)
                + repeat(" ", 33 * 1024);
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zipEntries(
                entry("plugin.json", largeManifest), entry("hook.js", "x\n")))),
                "launcher manifest remains 32KiB");

        String id = "dev.owornate.capture";
        byte[] hook = "console.log('capture');\n".getBytes(StandardCharsets.UTF_8);
        repository.install(repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest(id, 2), hook, null))));
        check(Arrays.equals(hook, repository.payload(id, "hook.js")),
                "payload exact bytes");

        Path hookFile = repository.packageDir(id).toPath().resolve("hook.js");
        Files.write(hookFile, new byte[2 * 1024 * 1024 + 1]);
        expect(IllegalStateException.class, () -> repository.payload(id, "hook.js"),
                "payload file limit");
        check(listPending(root).isEmpty(), "limit rejections leave no pending files");
        finish("launcher limits and payload access");
    }

    private static void installAndRestart() throws Exception {
        Path root = newCase("install-restart");
        String id = "com.example.install";
        Path marker = root.resolve("script-must-not-run");
        String script = "printf plugin-must-not-run > '" + slash(marker) + "'\n";
        byte[] zip = zip(manifest(id, 1), script);

        TrackingInput firstInput = new TrackingInput(zip);
        PluginRepository first = new PluginRepository(root.toFile());
        PluginRepository.Pending pending = first.stage(firstInput);
        check(firstInput.closed, "stage closes input");
        check(pending.token != null && pending.manifest.id.equals(id), "stage returns pending");
        check(Files.isRegularFile(root.resolve("pending").resolve(pending.token)
                .resolve("plugin.json")), "staged manifest persisted");

        PluginRepository restarted = new PluginRepository(root.toFile());
        PluginRepository.Pending recovered = restarted.pending(pending.token);
        check(recovered != null && recovered.manifest.id.equals(id), "pending survives restart");
        PluginManifest installed = restarted.install(recovered);
        equal(id, installed.id, "installed id");
        check(!Files.exists(root.resolve("pending").resolve(pending.token)),
                "pending directory consumed by registration");
        check(!Files.exists(marker), "install did not execute plugin.sh");

        PluginRepository reopened = new PluginRepository(root.toFile());
        List<PluginManifest> listed = reopened.list();
        equal(1, listed.size(), "installed list size after restart");
        equal(id, listed.get(0).id, "installed list id");
        equal(1, reopened.load(id).versionCode, "load after restart");
        equal("shell-v1", reopened.load(id).runtime, "shell runtime after restart");
        check(reopened.packageDir(id).isDirectory(), "packageDir exists");
        equal(script, readText(reopened.packageDir(id).toPath().resolve("plugin.sh")),
                "script bytes retained");
        check(Arrays.equals(script.getBytes(StandardCharsets.UTF_8),
                reopened.payload(id, "plugin.sh")), "shell payload accessor retained");
        finish("install and restart");
    }

    private static void configAndRemoval() throws Exception {
        Path root = newCase("config-removal");
        String id = "com.example.config";
        PluginRepository repository = new PluginRepository(root.toFile());
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nprintf ok\n")));
        repository.install(pending);

        Path ensuredData = repository.dataDir(id).toPath();
        check(Files.isDirectory(ensuredData), "dataDir ensures directory exists");
        equal(root.resolve("data").resolve(id).toAbsolutePath().normalize(), ensuredData
                .toAbsolutePath().normalize(), "dataDir points inside repository");
        Map<String, String> defaults = repository.config(id);
        equal("quiet", defaults.get("mode"), "config defaults mode");
        equal("yes", defaults.get("enabled"), "config defaults enabled");

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("enabled", "no");
        values.put("mode", "loud");
        repository.saveConfig(id, values);
        PluginRepository restarted = new PluginRepository(root.toFile());
        equal(values, restarted.config(id), "config survives repository recreation");
        check(Files.isRegularFile(root.resolve("data").resolve(id).resolve("config.json")),
                "config is stored under data");

        Path config = root.resolve("data").resolve(id).resolve("config.json");
        byte[] before = Files.readAllBytes(config);
        restarted.remove(id);
        check(restarted.load(id) == null, "removed package absent");
        check(!restarted.packageDir(id).exists(), "package directory removed");
        check(Arrays.equals(before, Files.readAllBytes(config)),
                "remove preserves config bytes");
        finish("config and removal");
    }

    private static void duplicateInstallIsRejected() throws Exception {
        Path root = newCase("duplicate-install");
        String id = "com.example.duplicate";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nexit 0\n"))));
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("mode", "loud");
        repository.saveConfig(id, values);
        Map<String, String> savedConfig = repository.config(id);

        PluginRepository.Pending second = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\necho replacement\n")));
        expect(IllegalStateException.class, () -> repository.install(second),
                "same id and same version rejected");
        equal(1, repository.list().size(), "original remains listed");
        equal(savedConfig, repository.config(id), "config remains after rejected install");
        check(readText(repository.packageDir(id).toPath().resolve("plugin.sh"))
                .contains("exit 0"), "original package files retained");
        repository.cancel(second);
        finish("duplicate install rejection");
    }

    private static void compatibleUpgradePreservesConfig() throws Exception {
        Path root = newCase("upgrade-compatible");
        String id = "com.example.upgrade";
        String oldScript = "#!/system/bin/sh\nprintf old\n";
        String newScript = "#!/system/bin/sh\nprintf new\n";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), oldScript))));

        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("mode", "loud");
        repository.saveConfig(id, values);
        Path data = repository.dataDir(id).toPath();
        Path config = data.resolve("config.json");
        byte[] configBefore = Files.readAllBytes(config);
        Files.write(data.resolve("notes.txt"), "keep me\n".getBytes(StandardCharsets.UTF_8));

        final PluginRepository active = repository;
        final Path activeConfig = config;
        final byte[] expectedConfig = configBefore;
        final PluginRepository secondRepository = new PluginRepository(root.toFile());
        final PluginRepository.Pending competing = secondRepository.stage(
                new ByteArrayInputStream(zip(manifest(id, 3),
                        "#!/system/bin/sh\nprintf competing\n")));
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifestWithSettings(id, 2, SETTINGS_WITH_EXTRA), newScript)));
        equal(1, repository.upgradeFrom(pending).versionCode,
                "upgradeFrom returns installed manifest");

        PluginManifest upgraded = repository.upgrade(pending,
                new UpgradeVerifier() {
                    @Override
                    public void verify(PluginManifest manifest) throws Exception {
                        equal(2, manifest.versionCode, "verifier sees candidate manifest");
                        check(readText(active.packageDir(manifest.id).toPath().resolve("plugin.sh"))
                                .contains("new"), "verifier sees new package installed");
                        Map<String, String> during = active.config(manifest.id);
                        equal("loud", during.get("mode"), "old config value visible in verifier");
                        equal("short", during.get("limit"),
                                "new config setting uses its default in verifier");
                        check(Arrays.equals(expectedConfig, Files.readAllBytes(activeConfig)),
                                "upgrade does not rewrite stored config during verification");
                        PluginRepository observer = new PluginRepository(root.toFile());
                        equal(2, observer.load(manifest.id).versionCode,
                                "second repository load does not recover active upgrade");
                        equal(2, observer.list().get(0).versionCode,
                                "second repository list does not recover active upgrade");
                        PluginRepository competitor = new PluginRepository(root.toFile());
                        expect(IllegalStateException.class, () -> competitor.upgrade(competing,
                                ignored -> {}), "same-root concurrent upgrade is rejected");
                    }
                });

        equal(2, upgraded.versionCode, "upgrade returns new manifest");
        equal(2, repository.load(id).versionCode, "upgraded manifest survives reload");
        equal(newScript, readText(repository.packageDir(id).toPath().resolve("plugin.sh")),
                "new package payload retained");
        equal("loud", repository.config(id).get("mode"), "old config value retained");
        equal("short", repository.config(id).get("limit"),
                "new config key defaults at read time");
        check(Arrays.equals(configBefore, Files.readAllBytes(config)),
                "stored config bytes remain unchanged");
        equal("keep me\n", readText(data.resolve("notes.txt")), "plugin data retained");
        check(!Files.exists(root.resolve("pending").resolve(pending.token)),
                "successful upgrade consumes pending package");
        check(fileNames(root.resolve("upgrade")).isEmpty(), "successful upgrade removes backup");
        secondRepository.cancel(competing);
        finish("compatible upgrade preserves config");
    }

    private static void upgradePersistsOldDefaults() throws Exception {
        Path root = newCase("upgrade-missing-config");
        String id = "com.example.upgrade.defaults";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nexit 0\n"))));
        Path config = root.resolve("data").resolve(id).resolve("config.json");
        check(!Files.exists(config), "missing config starts absent");
        final PluginRepository firstRepository = repository;
        final Path firstConfig = config;

        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifestWithSettings(id, 2, SETTINGS_DEFAULTS_LOUD_NO),
                        "#!/system/bin/sh\nprintf defaults\n")));
        repository.upgrade(pending, new UpgradeVerifier() {
            @Override
            public void verify(PluginManifest manifest) throws Exception {
                check(Files.isRegularFile(firstConfig),
                        "old defaults are persisted before verifier runs");
                equal("quiet", firstRepository.config(manifest.id).get("mode"),
                        "missing config keeps old default A");
                equal("yes", firstRepository.config(manifest.id).get("enabled"),
                        "missing config keeps both old defaults");
            }
        });
        equal("quiet", repository.config(id).get("mode"),
                "candidate default change cannot alter persisted old value");
        equal("yes", repository.config(id).get("enabled"),
                "candidate second default change cannot alter persisted value");
        finish("missing config defaults persisted");

        root = newCase("upgrade-partial-config");
        id = "com.example.upgrade.partial";
        repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nexit 0\n"))));
        Path partial = root.resolve("data").resolve(id).resolve("config.json");
        Files.createDirectories(partial.getParent());
        Files.write(partial, "{\"mode\":\"loud\"}\n".getBytes(StandardCharsets.UTF_8));
        pending = repository.stage(new ByteArrayInputStream(
                zip(manifestWithSettings(id, 2, SETTINGS_DEFAULTS_LOUD_NO),
                        "#!/system/bin/sh\nprintf partial\n")));
        repository.upgrade(pending, installed -> {});
        equal("loud", repository.config(id).get("mode"),
                "partial config keeps explicitly stored value");
        equal("yes", repository.config(id).get("enabled"),
                "partial config fills missing key with old default");
        check(readText(partial).contains("\"enabled\""),
                "partial config is completed before replacement");
        finish("partial config completed with old defaults");

        root = newCase("upgrade-defaults-failure");
        id = "com.example.upgrade.defaults.fail";
        repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nexit 0\n"))));
        config = root.resolve("data").resolve(id).resolve("config.json");
        pending = repository.stage(new ByteArrayInputStream(
                zip(manifestWithSettings(id, 2, SETTINGS_DEFAULTS_LOUD_NO),
                        "#!/system/bin/sh\nprintf fail-defaults\n")));
        try {
            repository.upgrade(pending, installed -> { throw new VerificationFailure(); });
            throw new AssertionError("expected verifier failure");
        } catch (VerificationFailure expected) {
            checks++;
        }
        equal(1, repository.load(id).versionCode, "failed upgrade restores old manifest");
        check(Files.isRegularFile(config),
                "failed upgrade keeps persisted old defaults");
        equal("quiet", repository.config(id).get("mode"),
                "failed upgrade keeps old default A");
        equal("yes", repository.config(id).get("enabled"),
                "failed upgrade keeps old default B");
        finish("failed upgrade retains old defaults");
    }

    private static void upgradeRejections() throws Exception {
        Path root = newCase("upgrade-rejections");
        String id = "com.example.upgrade.reject";
        String oldScript = "#!/system/bin/sh\nprintf old\n";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 2), oldScript))));
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("mode", "loud");
        repository.saveConfig(id, values);
        Path config = root.resolve("data").resolve(id).resolve("config.json");
        byte[] configBefore = Files.readAllBytes(config);

        PluginRepository.Pending same = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 2), "#!/system/bin/sh\nprintf same\n")));
        final boolean[] verifierCalled = {false};
        expect(IllegalStateException.class, () -> repository.upgrade(same,
                new UpgradeVerifier() {
                    @Override
                    public void verify(PluginManifest manifest) {
                        verifierCalled[0] = true;
                    }
                }), "same version upgrade rejected before verifier");
        check(!verifierCalled[0], "verifier not called for invalid version");
        repository.cancel(same);

        PluginRepository.Pending lower = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nprintf lower\n")));
        expect(IllegalStateException.class, () -> repository.upgradeFrom(lower),
                "lower version upgrade rejected");
        repository.cancel(lower);

        PluginRepository.Pending incompatible = repository.stage(new ByteArrayInputStream(
                zip(manifestWithSettings(id, 3, SETTINGS_WITHOUT_MODE),
                        "#!/system/bin/sh\nprintf incompatible\n")));
        expect(IllegalStateException.class, () -> repository.upgradeFrom(incompatible),
                "stored config value removed by candidate is rejected");
        repository.cancel(incompatible);
        check(!Files.exists(root.resolve("upgrade")),
                "preflight rejections do not create upgrade transactions");

        equal(2, repository.load(id).versionCode, "rejected upgrades leave installed version");
        equal(oldScript, readText(repository.packageDir(id).toPath().resolve("plugin.sh")),
                "rejected upgrades leave old package");
        check(Arrays.equals(configBefore, Files.readAllBytes(config)),
                "rejected upgrades leave config bytes");
        finish("upgrade rejections");
    }

    private static void runtimeMismatchCannotEnterUpgrade() throws Exception {
        Path root = newCase("upgrade-runtime-mismatch");
        PluginRepository repository = new PluginRepository(root.toFile());
        byte[] hook = "console.log('widgets');\n".getBytes(StandardCharsets.UTF_8);
        repository.install(repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest("dev.owornate.widgets", 1), hook, null))));

        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(
                zip(manifest("dev.owornate.widgets", 2), "#!/system/bin/sh\nexit 0\n"))),
                "launcher id cannot switch to shell runtime");
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest("com.example.runtime", 1), "#!/system/bin/sh\nexit 0\n"))));
        PluginRepository.Pending changedRuntime = repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest("com.example.runtime", 2), hook, null)));
        expect(IllegalStateException.class, () -> repository.upgradeFrom(changedRuntime),
                "shell id cannot switch to launcher runtime during upgrade");
        equal("shell-v1", repository.load("com.example.runtime").runtime,
                "third-party runtime mismatch preserves existing shell plugin");
        equal("launcher-v1", repository.load("dev.owornate.widgets").runtime,
                "runtime mismatch leaves installed package unchanged");
        finish("runtime mismatch rejected");
    }

    private static void verifierFailureRestoresPackage() throws Exception {
        Path root = newCase("upgrade-verifier-failure");
        String id = "com.example.upgrade.verify";
        String oldScript = "#!/system/bin/sh\nprintf old-verifier\n";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), oldScript))));
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("enabled", "no");
        repository.saveConfig(id, values);
        Path config = root.resolve("data").resolve(id).resolve("config.json");
        byte[] configBefore = Files.readAllBytes(config);
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 2), "#!/system/bin/sh\nprintf new-verifier\n")));

        final boolean[] sawNewPackage = {false};
        try {
            repository.upgrade(pending, new UpgradeVerifier() {
                @Override
                public void verify(PluginManifest manifest) throws Exception {
                    sawNewPackage[0] = readText(
                            repository.packageDir(manifest.id).toPath().resolve("plugin.sh"))
                            .contains("new-verifier");
                    throw new VerificationFailure();
                }
            });
            throw new AssertionError("verifier failure was not propagated");
        } catch (VerificationFailure expected) {
            checks++;
        }

        check(sawNewPackage[0], "verifier failure occurs after new package is readable");
        equal(1, repository.load(id).versionCode, "verifier failure restores old manifest");
        equal(oldScript, readText(repository.packageDir(id).toPath().resolve("plugin.sh")),
                "verifier failure restores old package");
        check(Arrays.equals(configBefore, Files.readAllBytes(config)),
                "verifier failure leaves config bytes");
        check(fileNames(root.resolve("upgrade")).isEmpty(),
                "verifier failure removes temporary backup");
        finish("verifier failure restores package");
    }

    private static void interruptedUpgradeRecovery() throws Exception {
        Path root = newCase("upgrade-interrupted");
        String id = "com.example.upgrade.interrupted";
        String oldScript = "#!/system/bin/sh\nprintf old-interrupted\n";
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), oldScript))));
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("mode", "loud");
        repository.saveConfig(id, values);

        PluginRepository.Pending prepared = repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 2), "#!/system/bin/sh\nprintf new-interrupted\n")));
        Path transaction = root.resolve("upgrade").resolve(id);
        Files.createDirectories(transaction);
        Files.write(transaction.resolve("state"), "prepared\n".getBytes(StandardCharsets.UTF_8));
        Path installed = root.resolve("installed").resolve(id);
        Path pendingDir = root.resolve("pending").resolve(prepared.token);
        Files.move(installed, transaction.resolve("package"));
        Files.move(pendingDir, installed);

        PluginRepository recovered = new PluginRepository(root.toFile());
        equal(1, recovered.list().get(0).versionCode,
                "prepared interruption rolls back to old version");
        equal(oldScript, readText(recovered.packageDir(id).toPath().resolve("plugin.sh")),
                "prepared interruption restores old payload");
        check(!Files.exists(transaction), "prepared interruption clears transaction");
        equal("loud", recovered.config(id).get("mode"), "interruption keeps config");

        PluginRepository.Pending committed = recovered.stage(new ByteArrayInputStream(
                zip(manifest(id, 2), "#!/system/bin/sh\nprintf committed\n")));
        Path committedTransaction = root.resolve("upgrade").resolve(id);
        Files.createDirectories(committedTransaction);
        Files.write(committedTransaction.resolve("state"),
                "committed\n".getBytes(StandardCharsets.UTF_8));
        Files.move(installed, committedTransaction.resolve("package"));
        Files.move(root.resolve("pending").resolve(committed.token), installed);

        PluginRepository committedRecovery = new PluginRepository(root.toFile());
        equal(2, committedRecovery.list().get(0).versionCode,
                "committed interruption keeps new version");
        check(!Files.exists(committedTransaction),
                "committed interruption removes stale backup");
        equal("loud", committedRecovery.config(id).get("mode"),
                "committed interruption keeps plugin data");
        finish("interrupted upgrade recovery");
    }

    private static void launcherUpgradeReplacesPayload() throws Exception {
        Path root = newCase("launcher-upgrade");
        String id = "dev.owornate.widgets";
        byte[] oldHook = "console.log('old');\n".getBytes(StandardCharsets.UTF_8);
        byte[] newHook = "console.log('new');\n".getBytes(StandardCharsets.UTF_8);
        PluginRepository repository = new PluginRepository(root.toFile());
        repository.install(repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest(id, 1), oldHook, null))));
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                launcherZip(launcherManifest(id, 2), newHook, null)));
        equal(1, repository.upgradeFrom(pending).versionCode, "launcher preflight old version");
        repository.upgrade(pending, new UpgradeVerifier() {
            @Override
            public void verify(PluginManifest manifest) {
                equal("launcher-v1", manifest.runtime, "launcher verifier runtime");
            }
        });
        equal(2, repository.load(id).versionCode, "launcher package version upgraded");
        check(Arrays.equals(newHook, repository.payload(id, "hook.js")),
                "launcher upgrade replaces imported payload only");
        finish("launcher package upgrade");
    }

    private static void pendingLifecycle() throws Exception {
        Path root = newCase("pending-lifecycle");
        PluginRepository repository = new PluginRepository(root.toFile());
        PluginRepository.Pending pending = repository.stage(new ByteArrayInputStream(
                zip(manifest("com.example.pending", 3), "#!/system/bin/sh\nexit 0\n")));
        check(repository.pending(pending.token) != null, "pending can be reopened");
        repository.cancel(pending);
        check(repository.pending(pending.token) == null, "cancelled pending is absent");
        check(!Files.exists(root.resolve("pending").resolve(pending.token)),
                "cancel removes pending directory");
        check(repository.pending("../../outside") == null, "pending rejects traversal token");
        finish("pending lifecycle");
    }

    private static void zipRejections() throws Exception {
        Path root = newCase("zip-rejections");
        PluginRepository repository = new PluginRepository(root.toFile());
        byte[] valid = zip(manifest("com.example.zip", 1), "#!/system/bin/sh\nexit 0\n");

        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(
                Arrays.copyOf(valid, valid.length - 8))), "truncated ZIP");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")))), "duplicate ZIP entry");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("../plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")))), "ZIP path traversal");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n"),
                entry("README", "extra")))), "extra ZIP item");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n"),
                entry("folder/", new byte[0])))), "ZIP directory entry");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", manifest("com.example.zip", 1))))), "missing script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zip(
                manifest("com.example.zip", 1), ""))), "empty script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zip(
                manifest("com.example.zip", 1), "\uFEFF#!/system/bin/sh\nexit 0\n"))),
                "UTF-8 BOM script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zip(
                manifest("com.example.zip", 1), "#!/system/bin/sh\0\n"))), "NUL script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zip(
                manifest("com.example.zip", 1), "#!/system/bin/sh\r\nexit 0\r\n"))),
                "CRLF script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", manifest("com.example.zip", 1)),
                entry("plugin.sh", new byte[] {(byte) 0xff, 0x0a})))), "invalid UTF-8 script");
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", new byte[] {(byte) 0xff}),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")))), "invalid UTF-8 manifest");
        byte[] manifestBytes = manifest("com.example.zip", 1)
                .getBytes(StandardCharsets.UTF_8);
        byte[] nulManifest = Arrays.copyOf(manifestBytes, manifestBytes.length + 1);
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", nulManifest),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")))), "NUL manifest");
        check(listPending(root).isEmpty(), "rejected packages leave no pending files");
        finish("ZIP rejections");
    }

    private static void limitsAndClosing() throws Exception {
        Path root = newCase("limits");
        PluginRepository repository = new PluginRepository(root.toFile());

        TrackingInput tooLargeInput = new TrackingInput(new byte[512 * 1024 + 1]);
        expect(IOException.class, () -> repository.stage(tooLargeInput), "compressed limit");
        check(tooLargeInput.closed, "stage closes input after size rejection");

        byte[] tooMuchScript = new byte[256 * 1024 + 1];
        Arrays.fill(tooMuchScript, (byte) 'x');
        tooMuchScript[tooMuchScript.length - 1] = (byte) '\n';
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(zip(
                manifest("com.example.limit", 1), tooMuchScript))), "unpacked total limit");

        byte[] largeManifest = (manifest("com.example.limit", 1) + repeat(" ", 33 * 1024))
                .getBytes(StandardCharsets.UTF_8);
        expect(IOException.class, () -> repository.stage(new ByteArrayInputStream(storedZip(
                entry("plugin.json", largeManifest),
                entry("plugin.sh", "#!/system/bin/sh\nexit 0\n")))), "manifest limit");
        check(listPending(root).isEmpty(), "limit rejections leave no pending files");
        finish("limits and closing");
    }

    private static void pathAndSymlinkSafety() throws Exception {
        Path root = newCase("path-safety");
        PluginRepository repository = new PluginRepository(root.toFile());
        expect(IllegalArgumentException.class, () -> repository.packageDir("../outside"),
                "packageDir rejects traversal");
        expect(IllegalArgumentException.class, () -> repository.remove("../outside"),
                "remove rejects traversal");
        expect(IllegalArgumentException.class, () -> repository.dataDir("a/b"),
                "dataDir rejects separator");

        String id = "com.example.symlink";
        repository.install(repository.stage(new ByteArrayInputStream(
                zip(manifest(id, 1), "#!/system/bin/sh\nexit 0\n"))));
        Path external = root.resolve("external-data");
        Files.createDirectories(external);
        Files.createDirectories(root.resolve("data"));
        Path link = root.resolve("data").resolve(id);
        try {
            Files.createSymbolicLink(link, external);
        } catch (IOException | UnsupportedOperationException e) {
            System.out.println("  skip symlink assertion (platform does not permit symlinks)");
            finish("path and symlink safety");
            return;
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("mode", "loud");
        expect(IllegalStateException.class, () -> repository.saveConfig(id, values),
                "saveConfig does not follow data symlink");
        check(!Files.exists(external.resolve("config.json")),
                "external symlink target untouched");
        finish("path and symlink safety");
    }

    private static String launcherManifest(String id, int versionCode) {
        return launcherManifest(id, versionCode, "[]");
    }

    private static String launcherManifest(String id, int versionCode, String settings) {
        return "{\"schemaVersion\":1,\"runtime\":\"launcher-v1\","
                + "\"permissions\":[\"root\"],"
                + "\"device\":{\"model\":\"OW20W1\",\"firmware\":\"OW20W1_11_A.92\","
                + "\"sdk\":27,\"abi\":\"armeabi-v7a\"},"
                + "\"id\":\"" + id + "\",\"name\":\"测试载荷\","
                + "\"description\":\"仅用于本地测试\",\"versionName\":\"1.0.0\","
                + "\"versionCode\":" + versionCode + ",\"settings\":" + settings + "}";
    }

    private static String manifest(String id, int versionCode) {
        return manifestWithSettings(id, versionCode, SETTINGS);
    }

    private static String manifestWithSettings(String id, int versionCode, String settings) {
        return "{\"schemaVersion\":1,\"runtime\":\"shell-v1\","
                + "\"permissions\":[\"root\"],"
                + "\"device\":{\"model\":\"OW20W1\",\"firmware\":\"OW20W1_11_A.92\","
                + "\"sdk\":27,\"abi\":\"armeabi-v7a\"},"
                + "\"id\":\"" + id + "\",\"name\":\"测试插件\","
                + "\"description\":\"仅用于本地测试\",\"versionName\":\"1.2.3\","
                + "\"versionCode\":" + versionCode + ",\"settings\":" + settings + "}";
    }

    private static byte[] zip(String manifest, String script) throws IOException {
        return zip(manifest, script.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] zip(String manifest, byte[] script) throws IOException {
        return zipEntries(entry("plugin.json", manifest), entry("plugin.sh", script));
    }

    private static byte[] launcherZip(String manifest, byte[] hook, byte[] theme)
            throws IOException {
        if (theme == null) {
            return zipEntries(entry("plugin.json", manifest), entry("hook.js", hook));
        }
        return zipEntries(entry("plugin.json", manifest), entry("hook.js", hook),
                entry("theme.jar", theme));
    }

    private static byte[] zipEntries(TestEntry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (TestEntry entry : entries) addZip(zip, entry.name, entry.data);
        }
        return out.toByteArray();
    }

    private static List<String> fileNames(Path dir) throws IOException {
        ArrayList<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(null);
        return names;
    }

    private static void addZip(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static TestEntry entry(String name, String value) {
        return new TestEntry(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static TestEntry entry(String name, byte[] value) {
        return new TestEntry(name, value);
    }

    /** Minimal stored ZIP writer, used where ZipOutputStream rejects duplicate names. */
    private static byte[] storedZip(TestEntry... entries) throws IOException {
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        int[] offsets = new int[entries.length];
        CRC32 crc32 = new CRC32();
        for (int i = 0; i < entries.length; i++) {
            TestEntry entry = entries[i];
            offsets[i] = local.size();
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            crc32.reset();
            crc32.update(entry.data);
            int crc = (int) crc32.getValue();
            u32(local, 0x04034b50);
            u16(local, 20);
            u16(local, 0x0800);
            u16(local, 0);
            u16(local, 0);
            u16(local, 0x21);
            u32(local, crc);
            u32(local, entry.data.length);
            u32(local, entry.data.length);
            u16(local, name.length);
            u16(local, 0);
            local.write(name);
            local.write(entry.data);
        }

        int centralOffset = local.size();
        for (int i = 0; i < entries.length; i++) {
            TestEntry entry = entries[i];
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            crc32.reset();
            crc32.update(entry.data);
            int crc = (int) crc32.getValue();
            u32(local, 0x02014b50);
            u16(local, 20);
            u16(local, 20);
            u16(local, 0x0800);
            u16(local, 0);
            u16(local, 0);
            u16(local, 0x21);
            u32(local, crc);
            u32(local, entry.data.length);
            u32(local, entry.data.length);
            u16(local, name.length);
            u16(local, 0);
            u16(local, 0);
            u16(local, 0);
            u16(local, 0);
            u32(local, 0);
            u32(local, offsets[i]);
            local.write(name);
        }
        int centralSize = local.size() - centralOffset;
        u32(local, 0x06054b50);
        u16(local, 0);
        u16(local, 0);
        u16(local, entries.length);
        u16(local, entries.length);
        u32(local, centralSize);
        u32(local, centralOffset);
        u16(local, 0);
        return local.toByteArray();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static List<Path> listPending(Path root) throws IOException {
        Path pending = root.resolve("pending");
        ArrayList<Path> result = new ArrayList<>();
        if (!Files.isDirectory(pending)) return result;
        try (Stream<Path> stream = Files.list(pending)) {
            stream.forEach(result::add);
        }
        return result;
    }

    private static Path newCase(String name) throws IOException {
        Path path = runtimeRoot.resolve("cases").resolve(name);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path);
        Files.createDirectories(path);
        return path;
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

    private static String readText(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String slash(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
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

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class VerificationFailure extends Exception {}

    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;

        TrackingInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TestEntry {
        final String name;
        final byte[] data;

        TestEntry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }
}
