package dev.codex.watchworkshop;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONObject;

/** End-to-end importPackage checks using the production importer, runner, and repository. */
final class PluginUpgradeIntegrationTest {
    private static final String SHELL_ID = "dev.owornate.example.screen-timeout";
    private static final String DISABLED = "OWORNATE_PLUGIN_V1 disabled";
    private static final String ENABLED = "OWORNATE_PLUGIN_V1 enabled";
    private static final List<String> LAUNCHER_FEATURES =
            Arrays.asList("monet", "widgets", "capture");

    private static Path runtimeRoot;
    private static Path fixtureRoot;
    private static final List<String> failures = new ArrayList<>();
    private static int cases;
    private static int checks;

    private PluginUpgradeIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(System.getProperty("plugin.upgrade.test.root"))
                .toAbsolutePath().normalize();
        fixtureRoot = Paths.get(System.getProperty("plugin.upgrade.fixtures"))
                .toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);
        requireDirectory(fixtureRoot.resolve("examples/screen-timeout"), "shell fixture");
        for (String feature : LAUNCHER_FEATURES) {
            requireDirectory(fixtureRoot.resolve("launcher").resolve(feature),
                    feature + " fixture");
        }

        runGroup("shell enabled rejects upgrade", PluginUpgradeIntegrationTest::shellEnabledRejected);
        runGroup("disabled shell upgrades", PluginUpgradeIntegrationTest::disabledShellUpgrades);
        runGroup("failed new status rolls back", PluginUpgradeIntegrationTest::shellStatusFailureRollsBack);
        for (String feature : LAUNCHER_FEATURES) {
            runGroup("launcher " + feature + " upgrades without device", () ->
                    launcherUpgradeSkipsDevice(feature));
        }

        System.out.println("PluginUpgradeIntegrationTest: " + cases + " cases, "
                + checks + " checks, " + failures.size() + " failed groups");
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.size() + " groups failed: " + failures);
        }
    }

    private static void shellEnabledRejected() throws Exception {
        Context context = newCase("shell-enabled-reject");
        PluginRepository repository = install(context, shellFixture(), 1);
        saveShellConfig(repository);
        Path marker = repository.dataDir(SHELL_ID).toPath().resolve("keep.bin");
        write(marker, bytes("enabled-keep"));
        Map<String, byte[]> packageBefore = snapshot(repository.packageDir(SHELL_ID).toPath());
        Map<String, byte[]> dataBefore = snapshot(repository.dataDir(SHELL_ID).toPath());

        FakeDevice device = new FakeDevice();
        device.reply(0, ENABLED);
        RootShell.setInjectedSession(device);
        PluginRepository pendingRepository = ExternalPlugins.repository(context);
        PluginRepository.Pending pending = pendingRepository.stage(
                new ByteArrayInputStream(packageBytes(shellFixture(), 2)));
        try {
            expectIOException("enabled shell upgrade", "请先停用此插件", () ->
                    ExternalPlugins.importPackage(context, pending));
        } finally {
            pendingRepository.cancel(pending);
            RootShell.clearInjectedSession();
        }

        PluginRepository observed = ExternalPlugins.repository(context);
        equal(1, observed.load(SHELL_ID).versionCode, "enabled rejection keeps v1");
        sameSnapshot(packageBefore,
                snapshot(observed.packageDir(SHELL_ID).toPath()),
                "enabled rejection package bytes");
        sameSnapshot(dataBefore, snapshot(observed.dataDir(SHELL_ID).toPath()),
                "enabled rejection data and config");
        equal(1, device.sessions, "enabled rejection root sessions");
        equal(1, device.commands.size(), "enabled rejection status reads");
        check(device.commands.get(0).endsWith("'status'"),
                "enabled rejection reads status");
    }

    private static void disabledShellUpgrades() throws Exception {
        Context context = newCase("shell-disabled-upgrade");
        PluginRepository repository = install(context, shellFixture(), 1);
        saveShellConfig(repository);
        Path marker = repository.dataDir(SHELL_ID).toPath().resolve("keep.bin");
        write(marker, bytes("disabled-keep"));
        Map<String, byte[]> dataBefore = snapshot(repository.dataDir(SHELL_ID).toPath());

        FakeDevice device = new FakeDevice();
        device.reply(0, DISABLED);
        device.reply(0, DISABLED);
        device.reply(0, DISABLED);
        RootShell.setInjectedSession(device);
        final PluginManifest upgraded;
        final boolean active;
        try {
            PluginRepository pendingRepository = ExternalPlugins.repository(context);
            PluginRepository.Pending pending = pendingRepository.stage(
                    new ByteArrayInputStream(packageBytes(shellFixture(), 2)));
            upgraded = ExternalPlugins.importPackage(context, pending);
            PluginRepository observed = ExternalPlugins.repository(context);
            active = ExternalPlugins.run(context, SHELL_ID, "status",
                    observed.config(SHELL_ID));
        } finally {
            RootShell.clearInjectedSession();
        }

        equal(2, upgraded.versionCode, "disabled shell upgraded to v2");
        check(!active, "new package status is disabled");
        PluginRepository observed = ExternalPlugins.repository(context);
        equal(Collections.singletonMap("timeout", "15000"),
                observed.config(SHELL_ID), "disabled upgrade keeps config");
        sameSnapshot(dataBefore, snapshot(observed.dataDir(SHELL_ID).toPath()),
                "disabled upgrade keeps config and data bytes");
        equal(2, device.sessions, "disabled upgrade root sessions");
        equal(3, device.commands.size(), "disabled upgrade status reads");
        for (String command : device.commands) {
            check(command.endsWith("'status'"), "disabled upgrade only reads status");
        }
    }

    private static void shellStatusFailureRollsBack() throws Exception {
        Context context = newCase("shell-status-rollback");
        PluginRepository repository = install(context, shellFixture(), 1);
        saveShellConfig(repository);
        Path marker = repository.dataDir(SHELL_ID).toPath().resolve("keep.bin");
        write(marker, bytes("rollback-keep"));
        Map<String, byte[]> packageBefore = snapshot(repository.packageDir(SHELL_ID).toPath());
        Map<String, byte[]> dataBefore = snapshot(repository.dataDir(SHELL_ID).toPath());

        FakeDevice device = new FakeDevice();
        device.reply(0, DISABLED);
        device.reply(1, "new status failed");
        RootShell.setInjectedSession(device);
        PluginRepository pendingRepository = ExternalPlugins.repository(context);
        PluginRepository.Pending pending = pendingRepository.stage(
                new ByteArrayInputStream(packageBytes(shellFixture(), 2)));
        try {
            expectIOException("new status failure", "插件执行失败", () ->
                    ExternalPlugins.importPackage(context, pending));
        } finally {
            pendingRepository.cancel(pending);
            RootShell.clearInjectedSession();
        }

        PluginRepository observed = ExternalPlugins.repository(context);
        equal(1, observed.load(SHELL_ID).versionCode, "status failure restores v1");
        sameSnapshot(packageBefore,
                snapshot(observed.packageDir(SHELL_ID).toPath()),
                "status failure restores old package bytes");
        sameSnapshot(dataBefore, snapshot(observed.dataDir(SHELL_ID).toPath()),
                "status failure keeps config and data bytes");
        equal(1, device.sessions, "status failure root sessions");
        equal(2, device.commands.size(), "status failure reads old and new status");
    }

    private static void launcherUpgradeSkipsDevice(String feature) throws Exception {
        Context context = newCase("launcher-" + feature);
        Path fixture = fixtureRoot.resolve("launcher").resolve(feature);
        PluginRepository repository = install(context, fixture, 1);
        PluginManifest installed = repository.load(fixtureId(fixture));
        repository.saveConfig(installed.id, Collections.emptyMap());
        Path dataMarker = repository.dataDir(installed.id).toPath().resolve("keep.bin");
        write(dataMarker, bytes("launcher-data-" + feature));
        Map<String, byte[]> dataBefore = snapshot(repository.dataDir(installed.id).toPath());

        Path deployedRoot = deployedRoot(context, feature);
        copyPayload(repository, installed.id, "hook.js", deployedRoot.resolve("hook.js"));
        if (fixture.resolve("theme.jar").toFile().isFile()) {
            copyPayload(repository, installed.id, "theme.jar",
                    deployedRoot.resolve("theme.jar"));
        }
        Map<String, byte[]> deployedBefore = snapshot(deployedRoot);

        FakeDevice device = new FakeDevice();
        RootShell.setInjectedSession(device);
        final PluginManifest upgraded;
        try {
            PluginRepository pendingRepository = ExternalPlugins.repository(context);
            PluginRepository.Pending pending = pendingRepository.stage(
                    new ByteArrayInputStream(packageBytes(fixture, 2)));
            ManagerCalls.reset();
            upgraded = ExternalPlugins.importPackage(context, pending);
        } finally {
            RootShell.clearInjectedSession();
        }

        equal(2, upgraded.versionCode, feature + " launcher upgraded to v2");
        equal(0, device.sessions, feature + " launcher opens no root session");
        check(device.commands.isEmpty(), feature + " launcher runs no shell command");
        check(ManagerCalls.calls.isEmpty(),
                feature + " launcher executes no native manager action");
        PluginRepository observed = ExternalPlugins.repository(context);
        equal(Collections.emptyMap(), observed.config(installed.id),
                feature + " launcher config remains empty");
        sameSnapshot(dataBefore, snapshot(observed.dataDir(installed.id).toPath()),
                feature + " launcher config and data bytes");
        sameSnapshot(deployedBefore, snapshot(deployedRoot),
                feature + " launcher deployed payload bytes");
    }

    private static PluginRepository install(Context context, Path fixture, int version)
            throws Exception {
        PluginRepository repository = ExternalPlugins.repository(context);
        PluginRepository.Pending pending = repository.stage(
                new ByteArrayInputStream(packageBytes(fixture, version)));
        PluginManifest installed = ExternalPlugins.importPackage(context, pending);
        equal(version, installed.versionCode, "initial install version");
        return ExternalPlugins.repository(context);
    }

    private static void saveShellConfig(PluginRepository repository) throws Exception {
        LinkedHashMap<String, String> config = new LinkedHashMap<>();
        config.put("timeout", "15000");
        repository.saveConfig(SHELL_ID, config);
    }

    private static Path shellFixture() {
        return fixtureRoot.resolve("examples/screen-timeout");
    }

    private static Path deployedRoot(Context context, String feature) {
        return new File(context.getFilesDir(), "monet/plugins").toPath().resolve(feature);
    }

    private static String fixtureId(Path fixture) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(fixture.resolve("plugin.json")),
                StandardCharsets.UTF_8)).getString("id");
    }

    private static void copyPayload(PluginRepository repository, String id, String name,
            Path target) throws Exception {
        write(target, repository.payload(id, name));
    }

    private static byte[] packageBytes(Path directory, int version) throws Exception {
        Path manifestFile = directory.resolve("plugin.json");
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(manifestFile),
                StandardCharsets.UTF_8));
        manifest.put("versionCode", version);
        manifest.put("versionName", version + ".0.0");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "plugin.json",
                    manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            for (String name : Arrays.asList("plugin.sh", "hook.js", "theme.jar")) {
                Path file = directory.resolve(name);
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    writeEntry(zip, name, Files.readAllBytes(file));
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        TreeMap<String, byte[]> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .collect(Collectors.toList());
            for (Path file : files) {
                result.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readAllBytes(file));
            }
        }
        return result;
    }

    private static void sameSnapshot(Map<String, byte[]> expected,
            Map<String, byte[]> actual, String label) {
        equal(expected.keySet(), actual.keySet(), label + " file set");
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            byte[] found = actual.get(entry.getKey());
            check(found != null && Arrays.equals(entry.getValue(), found),
                    label + ": " + entry.getKey());
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

    private static void requireDirectory(Path path, String label) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing " + label + ": " + path);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void deleteTree(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) deleteTree(child);
        }
        Files.deleteIfExists(path);
    }

    private static void expectIOException(String label, String snippet, Action action)
            throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            String message = expected.getMessage() == null ? "" : expected.getMessage();
            if (!message.contains(snippet)) {
                throw new AssertionError(label + ": expected '" + snippet
                        + "', got '" + message + "'", expected);
            }
            check(true, label);
            return;
        } catch (Exception unexpected) {
            throw new AssertionError(label + ": expected IOException, got "
                    + unexpected.getClass().getName(), unexpected);
        }
        throw new AssertionError(label + ": expected IOException");
    }

    private static void equal(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
        check(true, label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private interface Action {
        void run() throws Exception;
    }

    private interface TestGroup {
        void run() throws Exception;
    }

    private static void runGroup(String label, TestGroup group) {
        cases++;
        try {
            group.run();
            System.out.println("PASS " + label + " (" + checks + " checks so far)");
        } catch (Throwable failure) {
            failures.add(label);
            System.out.println("FAIL " + label + ": " + failure.getMessage());
            failure.printStackTrace(System.out);
        } finally {
            RootShell.clearInjectedSession();
        }
    }

    private static final class FakeDevice implements RootShell.Session {
        final List<String> commands = new ArrayList<>();
        final Deque<AdbClient.Reply> replies = new ArrayDeque<>();
        int sessions;

        void reply(int exitCode, String output) {
            replies.addLast(new AdbClient.Reply(exitCode, output));
        }

        @Override
        public Object run(Context context, int connectMs, int readMs,
                RootShell.Body<?> body) throws Exception {
            sessions++;
            AdbClient adb = new AdbClient((command, timeoutMs) -> {
                commands.add(command);
                AdbClient.Reply reply = replies.pollFirst();
                if (reply == null) throw new IOException("unexpected injected shell command");
                return reply;
            });
            return body.run(adb);
        }
    }
}
