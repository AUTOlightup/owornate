package dev.codex.watchworkshop;

import android.content.Context;

/** Counts widget refresh calls without pulling AppWidgetManager into the JVM test. */
final class OWOrnateWidgetProvider {
    private static int updates;

    static synchronized void updateAll(Context context) {
        updates++;
    }

    static synchronized int updateCount() {
        return updates;
    }

    static synchronized void resetUpdateCount() {
        updates = 0;
    }

    private OWOrnateWidgetProvider() {
    }
}
