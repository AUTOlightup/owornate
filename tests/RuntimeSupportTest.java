package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

/** Verify persisted startup claims through the real RuntimeSupport entry point. */
public final class RuntimeSupportTest {
    private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
    private static int passed;

    public static void main(String[] args) {
        checkBoot("service running without injected runtime", "service_pid=7\nlauncher_pid=42\nruntime_live=no\n", 0, false);
        checkBoot("current runtime ready", "service_pid=7\nlauncher_pid=42\nruntime_live=yes\n", 0, true);
        checkBoot("legacy standalone ready", "service_pid=7\nlauncher_pid=42\ntheme_live=yes\n", 0, true);
        checkBoot("stopped service", "service_pid=\nlauncher_pid=42\nruntime_live=yes\n", 0, false);
        checkBoot("missing launcher", "service_pid=7\nlauncher_pid=\nruntime_live=yes\n", 0, false);
        checkBoot("failed command cannot claim ready", "service_pid=7\nlauncher_pid=42\nruntime_live=yes\n", 1, false);
        checkBoot("unrecognized response", "service already running\n", 0, false);
        checkBoot("CRLF response", "service_pid=7\r\nlauncher_pid=42\r\nruntime_live=yes\r\n", 0, true);

        Context old = new Context();
        prefs(old).edit().putString("bootId", BOOT).putBoolean("bootOk", true).commit();
        RootShell.output = "__BOOT__" + BOOT + "\nservice started\n";
        RuntimeSupport.ensure(old, true);
        require(!prefs(old).getBoolean("bootReady", false), "old service-only claim is not trusted");
        require(!prefs(old).contains("bootOk"), "old ambiguous flag is retired after a new boot check");
        passed++;

        Context skipped = new Context();
        RootShell.output = "";
        RuntimeSupport.ensure(skipped, true);
        require(!prefs(skipped).contains("bootId") && !prefs(skipped).getBoolean("bootReady", false),
                "shared/disabled environment creates no startup claim");
        passed++;

        Context manual = new Context();
        RootShell.output = reply("service_pid=7\nlauncher_pid=42\nruntime_live=yes\n");
        RuntimeSupport.ensure(manual, false);
        require(!prefs(manual).contains("bootId"), "manual recovery is not boot recovery proof");
        require("功能已就绪".equals(RuntimeSupport.lastResult(manual)), "manual readiness result is visible");
        passed++;

        Context disconnected = new Context();
        prefs(disconnected).edit().putString("bootId", BOOT).putBoolean("bootReady", true).commit();
        RootShell.failure = new IOException("offline");
        RuntimeSupport.ensure(disconnected, true);
        RootShell.failure = null;
        require(!prefs(disconnected).getBoolean("bootReady", false), "failed boot check cannot retain a success claim");
        passed++;
        System.out.println("RuntimeSupportTest: " + passed + " scenarios passed");
    }

    private static String reply(String status) { return "__BOOT__" + BOOT + "\nservice started\n__STATUS__\n" + status; }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("runtime", 0); }
    private static void checkBoot(String name, String status, int code, boolean expected) {
        Context context = new Context();
        RootShell.output = reply(status);
        RootShell.code = code;
        RuntimeSupport.ensure(context, true);
        require(BOOT.equals(prefs(context).getString("bootId", "")), name + ": boot identity");
        require(prefs(context).getBoolean("bootReady", false) == expected, name + ": readiness");
        RootShell.code = 0;
        passed++;
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
