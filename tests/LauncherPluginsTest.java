package dev.codex.watchworkshop;

import android.content.Context;
import dev.codex.watchworkshop.ModuleManagement.Action;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** Adapter-only tests for imported launcher payloads and existing Manager lifecycles. */
final class LauncherPluginsTest {
    private static final String[] FEATURES = {"monet", "widgets", "capture"};
    private static Path runtimeRoot;
    private static Path packageDir;
    private static int checks;
    private static int cases;

    private LauncherPluginsTest() {}

    public static void main(String[] args) throws Exception {
        runtimeRoot = Paths.get(System.getProperty("launcher.test.root")).toAbsolutePath()
                .normalize();
        packageDir = Paths.get(System.getProperty("launcher.package.dir")).toAbsolutePath()
                .normalize();
        Files.createDirectories(runtimeRoot);
        for (String feature : FEATURES) {
            check(Files.isRegularFile(packageFile(feature).toPath()), "missing package: " + feature);
        }

        ArrayList<String> failures = new ArrayList<>();
        runGroup(failures, "prepare", LauncherPluginsTest::prepareMaterializesActualPackages);
        for (String feature : FEATURES) {
            runGroup(failures, "lifecycle " + feature,
                    () -> managerLifecycleAndUninstallSuccess(feature));
        }
        runGroup(failures, "uninstall failure", LauncherPluginsTest::uninstallFailureRetainsPackage);
        runGroup(failures, "shared", LauncherPluginsTest::sharedEnvironmentRemovesOnlyLocalPackage);
        runGroup(failures, "stale", LauncherPluginsTest::executeRejectsStaleSnapshot);
        runGroup(failures, "third-party", LauncherPluginsTest::thirdPartyLifecycle);
        runGroup(failures, "third-party failure and shared", LauncherPluginsTest::thirdPartyFailureAndShared);

        System.out.println("LauncherPluginsTest: " + cases + " cases, " + checks + " checks, "
                + failures.size() + " failed groups");
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError(failures.size() + " groups failed: "
                    + failures);
            throw error;
        }
    }

    private static void prepareMaterializesActualPackages() throws Exception {
        Context context = newCase("prepare-actual");
        PluginRepository repository = importPackages(context, FEATURES);
        byte[] monetHook = repository.payload(LauncherPlugins.id("monet"), "hook.js");
        byte[] monetTheme = repository.payload(LauncherPlugins.id("monet"), "theme.jar");
        byte[] widgetsHook = repository.payload(LauncherPlugins.id("widgets"), "hook.js");
        byte[] captureHook = repository.payload(LauncherPlugins.id("capture"), "hook.js");

        Payloads.writes = 0;
        LauncherPlugins.prepare(context);
        equal(4, Payloads.writes, "first prepare writes four launcher payload files");
        bytes(monetHook, source(context, "monet", "hook.js"), "monet hook bytes");
        bytes(monetTheme, source(context, "monet", "theme.jar"), "monet theme bytes");
        bytes(widgetsHook, source(context, "widgets", "hook.js"), "widgets hook bytes");
        bytes(captureHook, source(context, "capture", "hook.js"), "capture hook bytes");
        check(!source(context, "widgets", "theme.jar").exists(), "widgets has no theme.jar");
        check(!source(context, "capture", "theme.jar").exists(), "capture has no theme.jar");

        Payloads.writes = 0;
        LauncherPlugins.prepare(context);
        equal(0, Payloads.writes, "unchanged second prepare performs no payload writes");

        repository.remove(LauncherPlugins.id("widgets"));
        write(source(context, "widgets", "theme.jar").toPath(), new byte[] {1, 2, 3});
        Payloads.writes = 0;
        LauncherPlugins.prepare(context);
        equal(0, Payloads.writes, "removing missing package performs no payload writes");
        check(!source(context, "widgets", "hook.js").exists(), "missing package removes old hook");
        check(!source(context, "widgets", "theme.jar").exists(), "missing package removes stale jar");

        for (String feature : FEATURES) {
            PluginManifest manifest = repository.load(LauncherPlugins.id(feature));
            if (manifest != null) repository.remove(manifest.id);
        }
        LauncherPlugins.prepare(context);
        for (String feature : FEATURES) {
            check(!source(context, feature, "hook.js").exists(),
                    feature + " missing package leaves no hook source");
            check(!source(context, feature, "theme.jar").exists(),
                    feature + " missing package leaves no theme source");
        }
        finish("prepare actual packages and remove missing sources");
    }

    private static final String EXTENSION = "dev.example.launcher-keep-awake";

    private static PluginRepository importExtension(Context context) throws Exception {
        PluginRepository repo = ExternalPlugins.repository(context);
        try (FileInputStream input = new FileInputStream(packageDir.resolve("launcher-keep-awake-1.0.1.owo").toFile())) {
            repo.install(repo.stage(input));
        }
        return repo;
    }

    private static final class ExtensionDevice {
        String owner = "own", state = "available";
        boolean failEnable, failUninstall;
        final List<String> actions = new ArrayList<>();

        AdbClient.Reply command(String command, long timeout) throws IOException {
            if (command.startsWith("cat /data/user_de/0/")) {
                return new AdbClient.Reply(0, "{\"error\":\"" + EXTENSION + ": initialization failed\"}");
            }
            for (String action : new String[]{"kind","status","install","enable","disable","update","uninstall"}) {
                if (!command.endsWith(" " + action + " '" + EXTENSION + "'")) continue;
                actions.add(action);
                if (action.equals("kind")) return new AdbClient.Reply(0, owner);
                if (action.equals("status")) return new AdbClient.Reply(0, state);
                if (action.equals("install")) state = "disabled";
                if (action.equals("enable")) {
                    state = failEnable ? "installed" : "ready";
                    if (failEnable) return new AdbClient.Reply(1, "START_UNCONFIRMED");
                }
                if (action.equals("disable")) state = "disabled";
                if (action.equals("uninstall")) {
                    if (failUninstall) return new AdbClient.Reply(1, "STOP_FAILED");
                    state = "available";
                }
                return new AdbClient.Reply(0, "DONE");
            }
            throw new IOException("Unexpected command: " + command);
        }

        void attach() {
            RootShell.setInjectedSession((context, connect, read, body) -> body.run(new AdbClient(this::command)));
        }
    }

    private static void thirdPartyLifecycle() throws Exception {
        Context context = newCase("third-party");
        PluginRepository repo = importExtension(context);
        ExtensionDevice device = new ExtensionDevice();device.attach();
        try {
            LauncherPlugins.prepare(context);
            bytes(repo.payload(EXTENSION,"hook.js"),source(context,EXTENSION,"hook.js"),"actual extension copied for staging");
            check(LauncherPlugins.read(context,EXTENSION).actions().contains(Action.INSTALL),"extension is installable");
            LauncherPlugins.Snapshot installed=LauncherPlugins.execute(context,EXTENSION,Action.INSTALL);
            equal(Boolean.TRUE,installed.enabled,"install confirms ready");
            check(device.actions.indexOf("install")<device.actions.indexOf("enable"),"install then enable");
            equal(Boolean.FALSE,LauncherPlugins.execute(context,EXTENSION,Action.DISABLE).enabled,"header can disable");
            equal(Boolean.FALSE,LauncherPlugins.execute(context,EXTENSION,Action.UPDATE).enabled,"update preserves disabled");
            equal(Boolean.TRUE,LauncherPlugins.execute(context,EXTENSION,Action.ENABLE).enabled,"header can enable");
            LauncherPlugins.execute(context,EXTENSION,Action.UNINSTALL);
            check(repo.load(EXTENSION)==null,"confirmed uninstall removes import");
            check(!source(context,EXTENSION,"hook.js").exists(),"obsolete staged hook removed");
            int calls=device.actions.size();
            expect(IllegalArgumentException.class,()->LauncherPlugins.read(context,"dev.example';reboot"),"unsafe id rejected before shell");
            equal(calls,device.actions.size(),"unsafe id did not reach shell");
        } finally {RootShell.clearInjectedSession();}
        finish("third-party prepare, install, switches, update and uninstall");
    }

    private static void thirdPartyFailureAndShared() throws Exception {
        Context context=newCase("third-party-failure");
        PluginRepository repo=importExtension(context);
        ExtensionDevice device=new ExtensionDevice();device.failEnable=true;device.attach();
        try {
            expect(IOException.class,()->LauncherPlugins.execute(context,EXTENSION,Action.INSTALL),"failed init is not install success");
            check(repo.load(EXTENSION)!=null,"failed init retains import for recovery");
            LauncherPlugins.Snapshot failed=LauncherPlugins.read(context,EXTENSION);
            check(failed.text.contains("启动未确认") && failed.text.contains(EXTENSION),"runtime error is visible with plugin id");
            LauncherPlugins.execute(context,EXTENSION,Action.DISABLE);
            device.failUninstall=true;
            expect(IOException.class,()->LauncherPlugins.execute(context,EXTENSION,Action.UNINSTALL),"failed cleanup is not uninstall success");
            check(repo.load(EXTENSION)!=null,"failed cleanup retains import");
            device.owner="shared";
            check(!LauncherPlugins.read(context,EXTENSION).actions().contains(Action.INSTALL),"shared runtime cannot deploy extension");
            int mutations=device.actions.size();
            LauncherPlugins.execute(context,EXTENSION,Action.UNINSTALL);
            check(repo.load(EXTENSION)==null,"shared mode can remove only import");
            check(!device.actions.subList(mutations,device.actions.size()).contains("uninstall"),"shared code not touched");
        } finally {RootShell.clearInjectedSession();}
        finish("third-party failed init/cleanup recovery and shared boundary");
    }

    private static void managerLifecycleAndUninstallSuccess(String feature) throws Exception {
        Context context = newCase("lifecycle-" + feature);
        PluginRepository repository = importPackages(context, feature);
        resetManagers();
        setAvailable(feature);
        Path data = repository.dataDir(LauncherPlugins.id(feature)).toPath();
        Path marker = data.resolve("keep.bin");
        write(marker, new byte[] {7, 8, 9});

        Action[] actions = {Action.INSTALL, Action.UPDATE, Action.ENABLE,
                Action.DISABLE, Action.UNINSTALL};
        for (Action action : actions) LauncherPlugins.execute(context, feature, action);

        equal(Arrays.asList(feature + ".install", feature + ".update",
                feature + ".enable", feature + ".disable", feature + ".uninstall"),
                new ArrayList<>(ManagerCalls.calls), feature + " manager calls");
        check(repository.load(LauncherPlugins.id(feature)) == null,
                feature + " package removed after confirmed uninstall");
        check(Files.isRegularFile(marker), feature + " data retained after package removal");
        check(!source(context, feature, "hook.js").exists(),
                feature + " deployed hook removed after uninstall");
        finish(feature + " manager lifecycle and successful uninstall");
    }

    private static void uninstallFailureRetainsPackage() throws Exception {
        Context context = newCase("uninstall-failure");
        PluginRepository repository = importPackages(context, "monet");
        resetManagers();
        ModuleManager.setState("installed", "own", true, true);
        Path marker = repository.dataDir(LauncherPlugins.id("monet")).toPath()
                .resolve("failure-keep.bin");
        write(marker, new byte[] {4, 5, 6});
        LauncherPlugins.prepare(context);
        ModuleManager.failUninstall = true;
        try {
            expect(IOException.class,
                    () -> LauncherPlugins.execute(context, "monet", Action.UNINSTALL),
                    "manager uninstall failure propagates");
        } finally {
            ModuleManager.failUninstall = false;
        }
        equal(Arrays.asList("monet.uninstall"), new ArrayList<>(ManagerCalls.calls),
                "failed uninstall manager call");
        check(repository.load(LauncherPlugins.id("monet")) != null,
                "failed uninstall keeps imported package");
        check(Files.isRegularFile(marker), "failed uninstall keeps package data");
        check(Files.isRegularFile(source(context, "monet", "hook.js").toPath()),
                "failed uninstall keeps prepared hook");
        finish("failed uninstall retains package");
    }

    private static void sharedEnvironmentRemovesOnlyLocalPackage() throws Exception {
        Context context = newCase("shared");
        PluginRepository repository = importPackages(context, FEATURES);
        for (String feature : FEATURES) {
            Path marker = repository.dataDir(LauncherPlugins.id(feature)).toPath()
                    .resolve("shared-keep.bin");
            write(marker, new byte[] {(byte) (feature.hashCode() & 0xff)});
        }
        ModuleManager.setState("shared", "shared", true, true);
        WidgetHostManager.setWidget("ready", "present", "shared");
        WidgetHostManager.setCapture("ready", "shared");
        LauncherPlugins.prepare(context);

        for (String feature : FEATURES) {
            for (Action action : new Action[] {Action.UPDATE, Action.ENABLE, Action.DISABLE}) {
                ManagerCalls.reset();
                expect(IOException.class,
                        () -> LauncherPlugins.execute(context, feature, action),
                        feature + " rejects " + action + " for shared runtime");
                check(ManagerCalls.calls.isEmpty(), feature + " shared manager not called");
            }
            ManagerCalls.reset();
            LauncherPlugins.execute(context, feature, Action.UNINSTALL);
            check(ManagerCalls.calls.isEmpty(), feature + " shared uninstall not called");
            check(repository.load(LauncherPlugins.id(feature)) == null,
                    feature + " local package removed in shared runtime");
            check(Files.isRegularFile(repository.dataDir(LauncherPlugins.id(feature)).toPath()
                    .resolve("shared-keep.bin")), feature + " shared removal keeps data");
            check(!source(context, feature, "hook.js").exists(),
                    feature + " shared removal clears local source");
        }
        finish("shared runtime keeps ownership and removes local package only");
    }

    private static void executeRejectsStaleSnapshot() throws Exception {
        Context context = newCase("stale-action");
        PluginRepository repository = importPackages(context, "monet");
        resetManagers();
        ModuleManager.setState("disabled", "own", false, true);
        LauncherPlugins.Snapshot stale = LauncherPlugins.read(context, "monet");
        check(stale.actions().contains(Action.ENABLE), "stale snapshot initially offers enable");

        ModuleManager.setState("shared", "shared", true, true);
        ManagerCalls.reset();
        expect(IOException.class,
                () -> LauncherPlugins.execute(context, "monet", Action.ENABLE),
                "stale enable rejected by fresh snapshot");
        check(ManagerCalls.calls.isEmpty(), "stale action does not call the manager");
        check(repository.load(LauncherPlugins.id("monet")) != null,
                "stale rejection leaves package installed");
        finish("execute rereads snapshot before action");
    }

    private static PluginRepository importPackages(Context context, String... features)
            throws Exception {
        PluginRepository repository = ExternalPlugins.repository(context);
        for (String feature : features) {
            try (FileInputStream input = new FileInputStream(packageFile(feature))) {
                repository.install(repository.stage(input));
            }
        }
        return repository;
    }

    private static File packageFile(String feature) throws IOException {
        Path found = null;
        int count = 0;
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(packageDir,
                feature + "-*.owo")) {
            for (Path match : matches) {
                if (!Files.isRegularFile(match, LinkOption.NOFOLLOW_LINKS)) continue;
                found = match;
                count++;
            }
        }
        if (count != 1) {
            throw new IOException("expected exactly one " + feature + "-*.owo in "
                    + packageDir + ", found " + count);
        }
        return found.toFile();
    }

    private static Context newCase(String name) throws IOException {
        Path root = runtimeRoot.resolve("cases").resolve(name).normalize();
        if (!root.startsWith(runtimeRoot)) throw new IOException("case escapes runtime root");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) deleteTree(root);
        Path files = root.resolve("files");
        Files.createDirectories(files);
        return new Context(files.toFile());
    }

    private static File source(Context context, String feature, String name) {
        return new File(new File(context.getFilesDir(), "monet/plugins"), feature + "/" + name);
    }

    private static void resetManagers() {
        ManagerCalls.reset();
        ModuleManager.reset();
        WidgetHostManager.reset();
    }

    private static void setAvailable(String feature) {
        if ("monet".equals(feature)) ModuleManager.setState("available", "own", false, true);
        else if ("widgets".equals(feature)) {
            WidgetHostManager.setWidget("available", "present", "own");
        } else WidgetHostManager.setCapture("available", "own");
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void bytes(byte[] expected, File actual, String message) throws IOException {
        checks++;
        byte[] found = Files.readAllBytes(actual.toPath());
        if (!Arrays.equals(expected, found)) throw new AssertionError(message);
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

    private static void runGroup(ArrayList<String> failures, String name, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable error) {
            failures.add(name + ": " + error);
            System.out.println("  FAIL " + name + ": " + error);
        }
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
