package dev.codex.watchworkshop;

import android.content.Context;
import java.io.IOException;

/** Runtime-state substitute for the Monet settings scenarios. */
final class ModuleManager {
    private static ModuleState state = enabled();
    private static int reads;
    private static int remembered;

    private ModuleManager() {}

    static synchronized void reset() {
        state = enabled();
        reads = 0;
        remembered = 0;
    }

    static synchronized void setState(ModuleState value) {
        state = value;
    }

    static synchronized ModuleState read(Context context) throws Exception {
        reads++;
        return state;
    }

    static synchronized void rememberThemeEnabled(Context context) throws IOException {
        remembered++;
    }

    static synchronized int reads() { return reads; }

    static synchronized int remembered() { return remembered; }

    private static ModuleState enabled() {
        return new ModuleState("installed", "own", true, "ready", true, null);
    }
}
