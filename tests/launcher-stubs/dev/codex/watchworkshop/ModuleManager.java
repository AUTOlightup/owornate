package dev.codex.watchworkshop;

import android.content.Context;
import java.io.IOException;

/** Stateful Monet manager stub: records lifecycle calls without touching a device. */
final class ModuleManager {
    private static ModuleState state = available();
    static boolean failUninstall;

    private ModuleManager() {}

    static void prepare(Context context) throws IOException { LauncherPlugins.prepare(context); }

    static void reset() {
        state = available();
        failUninstall = false;
    }

    static void setState(String kind, String owner, boolean active, boolean known) {
        state = new ModuleState(kind, owner, active, message(kind), known, null);
    }

    static ModuleState read(Context context) { return state; }

    static ModuleState install(Context context) {
        ManagerCalls.add("monet.install");
        state = new ModuleState("installed", "own", true, "installed", true, null);
        return state;
    }

    static ModuleState update(Context context) {
        ManagerCalls.add("monet.update");
        boolean active = state.enabled();
        state = new ModuleState(active ? "installed" : "disabled", "own", active,
                active ? "installed" : "disabled", true, null);
        return state;
    }

    static ModuleState setEnabled(Context context, boolean enabled) {
        ManagerCalls.add(enabled ? "monet.enable" : "monet.disable");
        state = new ModuleState(enabled ? "installed" : "disabled", "own", enabled,
                enabled ? "installed" : "disabled", true, null);
        return state;
    }

    static ModuleState uninstall(Context context) throws IOException {
        ManagerCalls.add("monet.uninstall");
        if (failUninstall) throw new IOException("simulated monet uninstall failure");
        state = available();
        return state;
    }

    private static ModuleState available() {
        return new ModuleState("available", "own", false, "available", true, null);
    }

    private static String message(String kind) { return kind; }
}
