package dev.codex.watchworkshop;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite startup recovery for this package's standalone installation only. */
final class RuntimeSupport {
    /** Feature installs, shared Hook changes and startup recovery share one runtime. */
    static final Object LOCK = new Object();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final String COMMAND =
            "test \"$(id -u)\" = 0 || exit 1; "
                    + "test ! -f /data/local/tmp/watchhide/hook.js || exit 0; "
                    + "test ! -f /data/local/tmp/watch-monet/hook.js || exit 0; "
                    + "base=/data/local/tmp/watchworkshop-runtime; "
                    + "if test -f $base/runtime-version; then "
                    + "active=0; for f in $base/modules/*; do "
                    + "if test -d $f; then "
                    + "if test -f $f/installed && test ! -f $f/disabled; then active=1; fi; "
                    + "else case $f in *.disabled) continue;; esac; "
                    + "if test -f $f && test ! -f $f.disabled; then active=1; fi; fi; done; "
                    + "test $active = 1 || exit 0; service=runtime-service.sh; "
                    + "else test -f $base/installed && test ! -f $base/disabled || exit 0; service=monet-service.sh; fi; "
                    + "printf '__BOOT__'; cat /proc/sys/kernel/random/boot_id; "
                    + "sh $base/$service ensure || exit 1; "
                    + "n=0; while :; do "
                    + "status=$(sh $base/$service status) || exit 1; "
                    + "if printf '%s\\n' \"$status\" | grep -Eq '^(runtime_live|theme_live)=yes$'; then break; fi; "
                    + "test $n -lt 20 || break; sleep 1; n=$((n+1)); done; "
                    + "printf '__STATUS__\\n%s\\n' \"$status\"";

    static void ensureAsync(Context context, boolean boot) {
        final Context app = context.getApplicationContext();
        new Thread(() -> ensure(app, boot), "workshop-startup").start();
    }

    static void ensure(Context context, boolean boot) {
        if (!RUNNING.compareAndSet(false, true)) return;
        try { synchronized (LOCK) {
            RootShell.session(
                    context,
                    1200,
                    4500,
                    adb -> {
                        String raw = adb.shell(COMMAND, 45000);
                        int rc = adb.exitCode();
                        String id = bootId(raw);
                        boolean ready = rc == 0 && ready(raw);
                        android.content.SharedPreferences.Editor e =
                                context.getSharedPreferences("runtime", 0)
                                        .edit()
                                        .putLong("time", System.currentTimeMillis())
                                        .putString("result", rc != 0 ? "启动未完成，请重新检查"
                                                : ready ? "功能已就绪"
                                                : id.isEmpty() ? "当前无需恢复独立组件"
                                                : "服务已启动，功能就绪尚未确认，请重新检查");
                        if (boot && !id.isEmpty()) {
                            e.putString("bootId", id).putBoolean("bootReady", ready).remove("bootOk");
                        }
                        return e.commit();
                    });
        } } catch (Exception e) {
            android.content.SharedPreferences.Editor state = context.getSharedPreferences("runtime", 0)
                    .edit().putString("result", "启动未完成，请检查本机授权");
            if (boot) state.remove("bootReady").remove("bootOk");
            state.apply();
        } finally {
            RUNNING.set(false);
        }
    }

    static String lastResult(Context context) {
        return context.getSharedPreferences("runtime", 0).getString("result", "尚无独立启动记录");
    }

    private static String bootId(String raw) {
        int start = raw.indexOf("__BOOT__");
        if (start < 0) return "";
        String id = raw.substring(start + 8).split("[\\r\\n]", 2)[0].trim();
        return id.matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}") ? id : "";
    }

    /** The service status already validates current PID, mapped agent and module mask. */
    private static boolean ready(String raw) {
        int start = raw.indexOf("__STATUS__\n");
        if (start < 0) start = raw.indexOf("__STATUS__\r\n");
        if (start < 0) return false;
        String status = raw.substring(start + 10);
        return status.matches("(?s).*\\bservice_pid=[1-9][0-9]*[\\r\\n].*")
                && status.matches("(?s).*\\blauncher_pid=[1-9][0-9]*[\\r\\n].*")
                && (hasLine(status, "runtime_live=yes") || hasLine(status, "theme_live=yes"));
    }

    private static boolean hasLine(String raw, String expected) {
        for (String line : raw.split("[\\r\\n]+")) if (expected.equals(line)) return true;
        return false;
    }
}
