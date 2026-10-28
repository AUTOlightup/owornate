package dev.codex.watchworkshop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded JVM checks for declared boot restoration. No Android, ADB, Bash, or device state. */
final class PluginRestoreTest {
    private static final String ID = "com.example.restore";
    private static final String SCRIPT = "#!/system/bin/sh\nexit 0\n";
    private static final String INTENT_FILE = ".restore-enabled.json";
    private static final String ENABLED = "OWORNATE_PLUGIN_V1 enabled";
    private static final String DISABLED = "OWORNATE_PLUGIN_V1 disabled";
    private static final String[] ACTIONS = {
            "status", "enable", "disable", "configure", "uninstall", "restore"
    };

    private static Path runtimeRoot;
    private static int cases;
    private static int checks;

    private PluginRestoreTest() {}

    public static void main(String[] args) throws Exception {
        String configuredRoot = System.getProperty("plugin.restore.test.root");
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            throw new IllegalArgumentException("plugin.restore.test.root is required");
        }
        runtimeRoot = Paths.get(configuredRoot).toAbsolutePath().normalize();
        Files.createDirectories(runtimeRoot);

        manifestContract();
        newImportDoesNotEnableOrRestore();
        confirmedEnablePersistsIntent();
        unconfirmedEnableDoesNotRecord();
        failedEnableDoesNotRecord();
        undeclaredEnableDoesNotRecord();
        disableClearsBeforeScriptOrStatusFailure();
        configureKeepsIntent();
        uninstallAndReimportDoNotRestore();
        failedUninstallClearsIntent();
        restoreRequiresDeclarationAndIntent();
        declaredEnabledRestoreRuns();
        waitingForNfcServiceIsAllowed();
        restoreFailureKeepsIntentAndError();
        restoreStatusMismatchFails();
        commandAllowsRestore();

        System.out.println("PluginRestoreTest: OK (" + cases + " cases, " + checks
                + " checks)");
    }

    private static void manifestContract() throws Exception {
        PluginManifest omitted = PluginManifest.parse(shellManifest(ID, ""));
        check(!omitted.restoreOnBoot, "missing restoreOnBoot must default false");

        PluginManifest declared = PluginManifest.parse(shellManifest(ID,
                ",\"restoreOnBoot\":true"));
        check(declared.restoreOnBoot, "shell restoreOnBoot=true must parse");

        PluginManifest explicitlyDisabled = PluginManifest.parse(shellManifest(ID,
                ",\"restoreOnBoot\":false"));
        check(!explicitlyDisabled.restoreOnBoot, "shell restoreOnBoot=false must parse");

        PluginManifest launcherOmitted = PluginManifest.parse(launcherManifest(ID, ""));
        check(!launcherOmitted.restoreOnBoot, "launcher default must remain false");
        PluginManifest launcherDisabled = PluginManifest.parse(launcherManifest(ID,
                ",\"restoreOnBoot\":false"));
        check(!launcherDisabled.restoreOnBoot, "launcher false must remain valid");
        expectIOException(() -> PluginManifest.parse(launcherManifest(ID,
                ",\"restoreOnBoot\":true")), "launcher true must be rejected", null);

        expectIOException(() -> PluginManifest.parse(shellManifest(ID,
                ",\"restoreOnBoot\":\"true\"")), "string boolean must be rejected",
                null);
        expectIOException(() -> PluginManifest.parse(shellManifest(ID,
                ",\"restoreOnBoot\":1")), "numeric boolean must be rejected", null);
        expectIOException(() -> PluginManifest.parse(shellManifest(ID,
                ",\"restoreOnBoot\":null")), "null boolean must be rejected", null);
        finish("manifest strict compatibility");
    }

    private static void newImportDoesNotEnableOrRestore() throws Exception {
        Fixture fixture = fixture("new-import", false);
        check(!fixture.repository.restoreRequested(ID), "new import must not request restore");
        Path intent = fixture.repository.dataDir(ID).toPath().resolve(INTENT_FILE);
        check(!Files.exists(intent, LinkOption.NOFOLLOW_LINKS),
                "new import must not persist restore intent");

        PluginRepository reopened = new PluginRepository(fixture.root.toFile());
        check(!reopened.restoreRequested(ID), "reopen must retain the absent intent");
        check(!fixture.runner.restore(ID), "new import must not restore");
        check(fixture.shell.actions.isEmpty(), "new import restore must not invoke the shell");
        finish("new import is inert");
    }

    private static void confirmedEnablePersistsIntent() throws Exception {
        Fixture fixture = fixture("confirmed-enable", true);
        check(fixture.runner.perform(ID, "enable", null), "declared enable must succeed");
        check(fixture.repository.restoreRequested(ID), "confirmed enable must record intent");

        Path intent = fixture.repository.dataDir(ID).toPath().resolve(INTENT_FILE);
        check(Files.isRegularFile(intent, LinkOption.NOFOLLOW_LINKS),
                "confirmed enable must persist an intent file");
        PluginRepository reopened = new PluginRepository(fixture.root.toFile());
        check(reopened.restoreRequested(ID), "reopened repository must keep intent");
        finish("confirmed enable persists intent");
    }

    private static void unconfirmedEnableDoesNotRecord() throws Exception {
        Fixture fixture = fixture("unconfirmed-enable", true);
        fixture.shell.enableStatusMismatch = true;
        expectIOException(() -> fixture.runner.perform(ID, "enable", null),
                "enable without confirmed status must fail", null);
        check(!fixture.repository.restoreRequested(ID),
                "unconfirmed enable must not record intent");
        finish("enable requires status confirmation");
    }

    private static void failedEnableDoesNotRecord() throws Exception {
        Fixture fixture = fixture("failed-enable", true);
        fixture.shell.failEnable = true;
        expectIOException(() -> fixture.runner.perform(ID, "enable", null),
                "failed enable must throw", "enable failed sentinel");
        check(!fixture.repository.restoreRequested(ID),
                "failed enable must not record intent");
        finish("failed enable does not record");
    }

    private static void undeclaredEnableDoesNotRecord() throws Exception {
        Fixture fixture = fixture("undeclared-enable", false);
        check(fixture.runner.perform(ID, "enable", null),
                "undeclared plugin can still be enabled");
        check(!fixture.repository.restoreRequested(ID),
                "old undeclared plugin must not record intent");
        finish("undeclared enable stays non-restoring");
    }

    private static void disableClearsBeforeScriptOrStatusFailure() throws Exception {
        Fixture scriptFailure = fixture("disable-script-failure", true);
        scriptFailure.runner.perform(ID, "enable", null);
        scriptFailure.shell.failDisable = true;
        expectIOException(() -> scriptFailure.runner.perform(ID, "disable", null),
                "failed disable must throw", "disable failed sentinel");
        check(!scriptFailure.repository.restoreRequested(ID),
                "failed disable must clear intent");
        check(Boolean.FALSE.equals(scriptFailure.shell.intentAtDisable),
                "disable script must observe cleared intent");
        PluginRepository reopened = new PluginRepository(scriptFailure.root.toFile());
        check(!reopened.restoreRequested(ID), "failed disable must persist cleared intent");

        Fixture statusFailure = fixture("disable-status-failure", true);
        statusFailure.runner.perform(ID, "enable", null);
        statusFailure.shell.disableStatusMismatch = true;
        expectIOException(() -> statusFailure.runner.perform(ID, "disable", null),
                "unconfirmed disable must throw", null);
        check(!statusFailure.repository.restoreRequested(ID),
                "status failure must leave intent clear");
        check(Boolean.FALSE.equals(statusFailure.shell.intentAtDisable),
                "disable must be cleared before its script runs");
        finish("disable clears intent before failure");
    }

    private static void configureKeepsIntent() throws Exception {
        Fixture fixture = fixture("configure-keeps", true);
        fixture.runner.perform(ID, "enable", null);
        check(fixture.repository.restoreRequested(ID), "enable fixture must be armed");

        check(fixture.runner.perform(ID, "configure", Collections.<String, String>emptyMap()),
                "configure while enabled must succeed");
        check(fixture.repository.restoreRequested(ID),
                "successful configure must preserve intent");

        fixture.shell.failConfigure = true;
        expectIOException(() -> fixture.runner.perform(ID, "configure",
                Collections.<String, String>emptyMap()), "failed configure must throw",
                "configure failed sentinel");
        check(fixture.repository.restoreRequested(ID),
                "failed configure must preserve intent");
        finish("configure preserves intent");
    }

    private static void uninstallAndReimportDoNotRestore() throws Exception {
        Fixture fixture = fixture("uninstall-reimport", true);
        fixture.runner.perform(ID, "enable", null);
        check(fixture.repository.restoreRequested(ID), "enable fixture must be armed");
        check(!fixture.runner.perform(ID, "uninstall", null), "uninstall must finish disabled");
        check(fixture.repository.load(ID) == null, "uninstall must remove the package");
        check(Boolean.FALSE.equals(fixture.shell.intentAtUninstall),
                "uninstall must observe cleared intent");

        fixture.repository.install(fixture.repository.stage(
                new ByteArrayInputStream(fixture.archive)));
        PluginRepository reopened = new PluginRepository(fixture.root.toFile());
        check(!reopened.restoreRequested(ID),
                "reimport must not recover old restore intent");
        FakeShell reimportedShell = new FakeShell(reopened, ID);
        PluginRunner reimportedRunner = new PluginRunner(reopened, reimportedShell);
        check(!reimportedRunner.restore(ID), "reimported plugin must not restore");
        check(reimportedShell.actions.isEmpty(), "reimport restore must not invoke shell");
        finish("uninstall and reimport do not restore");
    }

    private static void failedUninstallClearsIntent() throws Exception {
        Fixture fixture = fixture("failed-uninstall", true);
        fixture.runner.perform(ID, "enable", null);
        fixture.shell.failUninstall = true;
        expectIOException(() -> fixture.runner.perform(ID, "uninstall", null),
                "failed uninstall must throw", "uninstall failed sentinel");
        check(fixture.repository.load(ID) != null,
                "failed uninstall must retain the package");
        check(!fixture.repository.restoreRequested(ID),
                "failed uninstall must leave intent clear");
        check(Boolean.FALSE.equals(fixture.shell.intentAtUninstall),
                "uninstall script must observe cleared intent");
        check(!fixture.runner.restore(ID), "failed uninstall must not restore later");
        finish("uninstall clears before failure");
    }

    private static void restoreRequiresDeclarationAndIntent() throws Exception {
        Fixture undeclared = fixture("restore-undeclared", false);
        undeclared.repository.setRestoreRequested(ID, true);
        check(!undeclared.runner.restore(ID),
                "intent alone must not authorize undeclared restore");
        check(undeclared.shell.actions.isEmpty(),
                "undeclared restore must not invoke the shell");
        check(undeclared.repository.restoreRequested(ID),
                "unauthorized restore must not alter intent");

        Fixture notEnabled = fixture("restore-not-enabled", true);
        check(!notEnabled.repository.restoreRequested(ID),
                "new declared plugin must start unarmed");
        check(!notEnabled.runner.restore(ID), "unarmed restore must return false");
        check(notEnabled.shell.actions.isEmpty(),
                "unarmed restore must not invoke the shell");
        finish("restore requires declaration and intent");
    }

    private static void declaredEnabledRestoreRuns() throws Exception {
        Fixture fixture = fixture("restore-runs", true);
        fixture.runner.perform(ID, "enable", null);
        fixture.shell.actions.clear();
        check(fixture.runner.restore(ID), "declared armed restore must succeed");
        equal(Collections.singletonList("restore"), firstActions(fixture.shell.actions, 1),
                "restore must run before status");
        check(fixture.shell.actions.contains("status"),
                "restore must confirm a real status");
        check(fixture.repository.restoreRequested(ID), "restore must retain intent");
        finish("declared armed restore runs");
    }

    private static void waitingForNfcServiceIsAllowed() throws Exception {
        Fixture fixture = fixture("restore-waiting", true);
        fixture.runner.perform(ID, "enable", null);
        fixture.shell.restoreResultEnabled = false;
        fixture.shell.actions.clear();
        check(!fixture.runner.restore(ID), "waiting NFC service must return false");
        equal(Collections.singletonList("restore"), firstActions(fixture.shell.actions, 1),
                "waiting restore must run restore first");
        check(fixture.shell.actions.contains("status"),
                "waiting restore must confirm disabled status");
        check(fixture.repository.restoreRequested(ID),
                "waiting restore must retain intent");
        finish("waiting NFC status is valid");
    }

    private static void restoreFailureKeepsIntentAndError() throws Exception {
        Fixture fixture = fixture("restore-failure", true);
        fixture.runner.perform(ID, "enable", null);
        fixture.shell.failRestore = true;
        expectIOException(() -> fixture.runner.restore(ID), "failed restore must throw",
                "restore failed sentinel");
        check(fixture.repository.restoreRequested(ID),
                "failed restore must retain intent");
        finish("restore failure retains intent and error");
    }

    private static void restoreStatusMismatchFails() throws Exception {
        Fixture fixture = fixture("restore-mismatch", true);
        fixture.runner.perform(ID, "enable", null);
        fixture.shell.restoreStatusMismatch = true;
        expectIOException(() -> fixture.runner.restore(ID),
                "restore/status disagreement must throw", null);
        check(fixture.repository.restoreRequested(ID),
                "mismatched restore must retain intent");
        finish("restore status disagreement fails");
    }

    private static void commandAllowsRestore() throws Exception {
        Path directory = newCase("restore-command");
        String command = PluginRunner.command(directory.resolve("package").toFile(),
                directory.resolve("data").toFile(), "restore",
                Collections.<String, String>emptyMap());
        check(command.endsWith("'restore'"), "restore must be a fixed command action");
        expectIOException(() -> PluginRunner.command(directory.resolve("package").toFile(),
                directory.resolve("data").toFile(), "restore --force",
                Collections.<String, String>emptyMap()), "restore must reject extra actions",
                null);
        finish("restore command is fixed");
    }

    private static Fixture fixture(String name, boolean restoreOnBoot) throws Exception {
        Path root = newCase(name);
        PluginRepository repository = new PluginRepository(root.toFile());
        byte[] archive = zip(shellManifest(ID, restoreOnBoot
                ? ",\"restoreOnBoot\":true" : ""), SCRIPT);
        repository.install(repository.stage(new ByteArrayInputStream(archive)));
        FakeShell shell = new FakeShell(repository, ID);
        return new Fixture(root, archive, repository, shell,
                new PluginRunner(repository, shell));
    }

    private static List<String> firstActions(List<String> actions, int count) {
        return new ArrayList<>(actions.subList(0, Math.min(actions.size(), count)));
    }

    private static String shellManifest(String id, String restoreField) {
        return "{\"schemaVersion\":1,\"runtime\":\"shell-v1\","
                + "\"permissions\":[\"root\"],"
                + "\"device\":{\"model\":\"OW20W1\","
                + "\"firmware\":\"OW20W1_11_A.92\",\"sdk\":27,"
                + "\"abi\":\"armeabi-v7a\"},"
                + "\"id\":\"" + id + "\",\"name\":\"Restore Test\","
                + "\"description\":\"Local JVM test\",\"versionName\":\"1.0.0\","
                + "\"versionCode\":1" + restoreField + ",\"settings\":[]}";
    }

    private static String launcherManifest(String id, String restoreField) {
        return "{\"schemaVersion\":1,\"runtime\":\"launcher-v1\","
                + "\"permissions\":[\"root\"],"
                + "\"device\":{\"model\":\"OW20W1\","
                + "\"firmware\":\"OW20W1_11_A.92\",\"sdk\":27,"
                + "\"abi\":\"armeabi-v7a\"},"
                + "\"id\":\"" + id + "\",\"name\":\"Restore Test\","
                + "\"description\":\"Local JVM test\",\"versionName\":\"1.0.0\","
                + "\"versionCode\":1" + restoreField + ",\"settings\":[]}";
    }

    private static byte[] zip(String manifest, String script) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            addZip(zip, "plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
            addZip(zip, "plugin.sh", script.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void addZip(ZipOutputStream zip, String name, byte[] value)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
    }

    private static Path newCase(String name) throws IOException {
        Path path = runtimeRoot.resolve("cases").resolve(name);
        deleteTree(path);
        Files.createDirectories(path);
        return path;
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) deleteTree(child);
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

    private static void expectIOException(ThrowingAction action, String label,
            String contains) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable error) {
            if (!(error instanceof IOException)) {
                throw new AssertionError(label + ": wrong exception " + error.getClass().getName(),
                        error);
            }
            if (contains != null && (error.getMessage() == null
                    || !error.getMessage().contains(contains))) {
                throw new AssertionError(label + ": missing message " + contains + " in "
                        + error.getMessage(), error);
            }
            return;
        }
        throw new AssertionError(label + ": no exception");
    }

    private static void finish(String name) {
        cases++;
        System.out.println("  PASS " + name);
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class Fixture {
        final Path root;
        final byte[] archive;
        final PluginRepository repository;
        final FakeShell shell;
        final PluginRunner runner;

        Fixture(Path root, byte[] archive, PluginRepository repository, FakeShell shell,
                PluginRunner runner) {
            this.root = root;
            this.archive = archive;
            this.repository = repository;
            this.shell = shell;
            this.runner = runner;
        }
    }

    /** Records intents at action boundaries and returns deterministic protocol statuses. */
    private static final class FakeShell implements PluginRunner.Shell {
        private final PluginRepository repository;
        private final String id;
        final List<String> actions = new ArrayList<>();
        boolean enabled;
        boolean failEnable;
        boolean failDisable;
        boolean failConfigure;
        boolean failUninstall;
        boolean failRestore;
        boolean enableStatusMismatch;
        boolean disableStatusMismatch;
        boolean restoreResultEnabled = true;
        boolean restoreStatusMismatch;
        Boolean intentAtDisable;
        Boolean intentAtUninstall;
        private Boolean nextStatusEnabled;

        FakeShell(PluginRepository repository, String id) {
            this.repository = repository;
            this.id = id;
        }

        @Override
        public PluginRunner.Result run(String command) throws Exception {
            String action = action(command);
            actions.add(action);
            if ("status".equals(action)) return status();
            if ("enable".equals(action)) {
                if (failEnable) return failure(41, "enable failed sentinel");
                enabled = true;
                if (enableStatusMismatch) nextStatusEnabled = Boolean.FALSE;
                return reply(enabled);
            }
            if ("disable".equals(action)) {
                intentAtDisable = repository.restoreRequested(id);
                if (failDisable) return failure(42, "disable failed sentinel");
                enabled = false;
                if (disableStatusMismatch) nextStatusEnabled = Boolean.TRUE;
                return reply(false);
            }
            if ("configure".equals(action)) {
                if (failConfigure) return failure(43, "configure failed sentinel");
                return reply(enabled);
            }
            if ("uninstall".equals(action)) {
                intentAtUninstall = repository.restoreRequested(id);
                if (failUninstall) return failure(44, "uninstall failed sentinel");
                enabled = false;
                return reply(false);
            }
            if ("restore".equals(action)) {
                if (failRestore) return failure(51, "restore failed sentinel");
                enabled = restoreResultEnabled;
                if (restoreStatusMismatch) nextStatusEnabled = !enabled;
                return reply(enabled);
            }
            throw new IOException("unexpected fake action: " + action);
        }

        private PluginRunner.Result status() {
            boolean value = enabled;
            if (nextStatusEnabled != null) {
                value = nextStatusEnabled;
                nextStatusEnabled = null;
            }
            return reply(value);
        }

        private static PluginRunner.Result reply(boolean value) {
            return new PluginRunner.Result(0, value ? ENABLED : DISABLED);
        }

        private static PluginRunner.Result failure(int code, String output) {
            return new PluginRunner.Result(code, output);
        }

        private static String action(String command) throws IOException {
            for (String action : ACTIONS) {
                if (command.endsWith("'" + action + "'")) return action;
            }
            throw new IOException("fake shell could not parse command: " + command);
        }
    }
}
