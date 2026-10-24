package dev.codex.watchworkshop;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual imported example with local fake Android commands. No ADB/device access. */
public final class PluginRunnerTest {
    static Path root, archive;
    static String bash;
    static int passed;
    interface Checked { void run() throws Exception; }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void fails(Checked action, String contains) throws Exception {
        try { action.run(); } catch (Exception e) {
            check(e.getMessage() != null && e.getMessage().contains(contains), "Wrong error: " + e);
            return;
        }
        throw new AssertionError("Expected failure: " + contains);
    }
    static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    static final class Fixture {
        final Path dir, system, bin;
        final PluginRepository repository;
        final String id;
        final Map<String,String> environment = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final PluginRunner runner;
        Fixture() throws Exception {
            dir = Files.createTempDirectory(root, "plugin 'case-");
            system = dir.resolve("system-setting");
            bin = Files.createDirectory(dir.resolve("bin"));
            write(system, "10000\n");
            write(bin.resolve("id"), "#!/bin/sh\necho \"$FAKE_UID\"\n");
            write(bin.resolve("getprop"), "#!/bin/sh\ncase \"$1\" in\n"
                    + "ro.product.model) echo \"$FAKE_MODEL\";;\n"
                    + "ro.build.display.id) echo OW20W1_11_A.92;;\n"
                    + "ro.build.version.sdk) echo 27;;\n"
                    + "ro.product.cpu.abi) echo armeabi-v7a;;\nesac\n");
            write(bin.resolve("settings"), "#!/bin/sh\nset -eu\n"
                    + "[ \"$2\" = system ] && [ \"$3\" = screen_off_timeout ] || exit 2\n"
                    + "case \"$1\" in\nget) cat \"$FAKE_SETTING_FILE\";;\n"
                    + "put) [ \"$FAKE_WRITE_FAIL\" != 1 ] || exit 7; printf '%s\\n' \"$4\" > \"$FAKE_SETTING_FILE\";;\n"
                    + "delete) printf 'null\\n' > \"$FAKE_SETTING_FILE\";;\n*) exit 2;;\nesac\n");
            environment.put("FAKE_UID","0"); environment.put("FAKE_MODEL","OW20W1");
            environment.put("FAKE_WRITE_FAIL","0");
            repository = new PluginRepository(dir.resolve("repository").toFile());
            PluginRepository.Pending staged = repository.stage(Files.newInputStream(archive));
            check(repository.list().isEmpty(), "Stage must not register");
            id = repository.install(staged).id;
            check(value().equals("10000"), "Import must not run code");
            runner = new PluginRunner(repository, this::shell);
        }
        String value() throws Exception { return read(system).trim(); }
        PluginRunner.Result shell(String command) throws Exception {
            // Test-only Windows-to-MSYS path conversion; production Android commands are unchanged.
            command = posix(command);
            command = "export PATH=" + PluginRunner.quote(posix(bin.toString()) + ":/usr/bin:/bin") + "; " + command;
            calls.add(command);
            ProcessBuilder builder = new ProcessBuilder(bash, "-s").redirectErrorStream(true);
            builder.environment().put("PATH", posix(bin.toString()) + ":/usr/bin:/bin");
            builder.environment().put("FAKE_SETTING_FILE", posix(system.toString()));
            builder.environment().putAll(environment);
            Process process = builder.start();
            try (OutputStream input = process.getOutputStream()) {
                input.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("fake shell timeout"); }
            String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
            write(dir.resolve("last-command.sh"), command + "\n");
            write(dir.resolve("last-output.txt"), output);
            return new PluginRunner.Result(process.exitValue(), output);
        }
    }
    static String posix(String value) {
        value = value.replace('\\','/');
        for (char drive = 'A'; drive <= 'Z'; drive++) {
            value = value.replace(drive + ":/", "/" + Character.toLowerCase(drive) + "/");
        }
        return value;
    }
    static Map<String,String> timeout(String value) { return Collections.singletonMap("timeout", value); }
    static void write(Path file, String value) throws Exception { Files.write(file, value.getBytes(StandardCharsets.UTF_8)); }
    static String read(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[1024]; int n;
        while ((n = input.read(bytes)) != -1) out.write(bytes,0,n);
        return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        root = Paths.get(args[0]); archive = Paths.get(args[1]); bash = args[2];
        Files.createDirectories(root);
        test("real imported script lifecycle, restart and retained configuration", () -> {
            Fixture f = new Fixture();
            check(!f.runner.read(f.id), "initial disabled");
            check(!f.runner.perform(f.id,"configure",timeout("15000")), "configure disabled");
            check(f.value().equals("10000"), "disabled configure must not modify system");
            check(f.runner.perform(f.id,"enable",null), "enabled");
            check(f.value().equals("15000"), "selected timeout applied");
            check(f.runner.perform(f.id,"configure",timeout("60000")), "configure while enabled");
            check(f.runner.perform(f.id,"enable",null), "repeated enable");
            PluginRepository reopened = new PluginRepository(f.dir.resolve("repository").toFile());
            check(reopened.config(f.id).get("timeout").equals("60000"), "restart config");
            check(!f.runner.perform(f.id,"disable",null), "disabled");
            check(f.value().equals("10000"), "restore first original");
            f.runner.perform(f.id,"enable",null);
            check(!f.runner.perform(f.id,"uninstall",null), "uninstalled");
            check(f.value().equals("10000") && f.repository.load(f.id) == null, "cleaned and removed");
            f.repository.install(f.repository.stage(Files.newInputStream(archive)));
            check(f.repository.config(f.id).get("timeout").equals("60000"), "reimport keeps config");
            check(!f.runner.read(f.id), "reimport disabled");
        });
        test("external system change: unknown status, uninstall keeps external value", () -> {
            Fixture f = new Fixture(); f.runner.perform(f.id,"enable",null);
            write(f.system, "45000\n");
            fails(() -> f.runner.read(f.id), "screen timeout changed");
            f.runner.perform(f.id,"uninstall",null);
            check(f.value().equals("45000"), "must preserve external change");
        });
        test("failed setting write reports failure and cleanup remains possible", () -> {
            Fixture f = new Fixture(); f.environment.put("FAKE_WRITE_FAIL","1");
            fails(() -> f.runner.perform(f.id,"enable",null), "7");
            check(f.repository.load(f.id) != null, "failed enable retains package");
            f.runner.perform(f.id,"uninstall",null);
            check(f.value().equals("10000"), "unchanged system");
        });
        test("root/device rejection and busy lock never run the plugin", () -> {
            Fixture f = new Fixture(); f.environment.put("FAKE_UID","2000");
            fails(() -> f.runner.read(f.id), "root");
            f.environment.put("FAKE_UID","0"); f.environment.put("FAKE_MODEL","wrong");
            fails(() -> f.runner.read(f.id), "OW20W1");
            f.environment.put("FAKE_MODEL","OW20W1");
            Path lock = f.repository.dataDir(f.id).toPath().resolve(".operation-lock");
            Files.createDirectory(lock); fails(() -> f.runner.read(f.id), "仍在执行");
            Files.delete(lock);
            check(!f.runner.read(f.id) && f.value().equals("10000"), "lock releases after run");
        });
        test("invalid protocol and action/config inputs rejected", () -> {
            Fixture f = new Fixture(); int calls = f.calls.size();
            fails(() -> f.runner.perform(f.id,"reboot",null), "不支持");
            fails(() -> f.runner.perform(f.id,"configure",timeout("$(reboot)")), "不受支持");
            check(f.calls.size() == calls, "invalid inputs must not invoke shell");
            fails(() -> PluginRunner.parseStatus("enabled"), "有效状态");
            fails(() -> PluginRunner.parseStatus("OWORNATE_PLUGIN_V1 enabled\njunk"), "有效状态");
            check(PluginRunner.parseStatus("log\nOWORNATE_PLUGIN_V1 enabled\r\n"), "final protocol line");
        });
        test("unconfirmed enable or cleanup never reports success/removes package", () -> {
            Fixture f = new Fixture();
            PluginRunner lies = new PluginRunner(f.repository, cmd -> new PluginRunner.Result(0,
                    cmd.endsWith("'enable'") ? "OWORNATE_PLUGIN_V1 enabled" : "OWORNATE_PLUGIN_V1 disabled"));
            fails(() -> lies.perform(f.id,"enable",null), "未确认");
            PluginRunner cleanupFails = new PluginRunner(f.repository, cmd -> new PluginRunner.Result(8,"cleanup failed"));
            fails(() -> cleanupFails.perform(f.id,"uninstall",null), "cleanup failed");
            check(f.repository.load(f.id) != null, "cleanup failure retains package");
            PluginRunner enabledReply = new PluginRunner(f.repository, cmd -> new PluginRunner.Result(0,"OWORNATE_PLUGIN_V1 enabled"));
            fails(() -> enabledReply.perform(f.id,"uninstall",null), "未确认停用");
            check(f.repository.load(f.id) != null, "bad disable retains package");
        });
        test("failed configure leaves saved configuration unchanged", () -> {
            Fixture f = new Fixture();
            PluginRunner bad = new PluginRunner(f.repository, cmd -> {
                if (cmd.endsWith("'configure'")) return new PluginRunner.Result(1,"configure failed");
                return new PluginRunner.Result(0,"OWORNATE_PLUGIN_V1 disabled");
            });
            fails(() -> bad.perform(f.id,"configure",timeout("60000")), "configure failed");
            check(f.repository.config(f.id).get("timeout").equals("30000"), "old config retained");
        });
        test("missing original setting restored by delete", () -> {
            Fixture f = new Fixture(); write(f.system,"null\n");
            f.runner.perform(f.id,"enable",null); f.runner.perform(f.id,"disable",null);
            check(f.value().equals("null"), "restore unset original");
        });
        System.out.println("PluginRunnerTest: OK (" + passed + " cases)");
    }
}
