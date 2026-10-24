package dev.codex.watchworkshop;

import android.content.Context;

/** Observation sink substitute; failures must not alter the workshop preferences. */
final class RuntimeStateStore {
    private static int monetFailures;
    private static int themeRecords;
    private static ThemeConfig lastTheme;
    private static Exception lastFailure;

    private RuntimeStateStore() {}

    static synchronized void reset() {
        monetFailures = 0;
        themeRecords = 0;
        lastTheme = null;
        lastFailure = null;
    }

    static synchronized void failMonet(Context context, Exception failure) {
        monetFailures++;
        lastFailure = failure;
    }

    static synchronized void recordTheme(Context context, ThemeConfig theme) {
        themeRecords++;
        lastTheme = theme;
    }

    static synchronized int monetFailures() { return monetFailures; }

    static synchronized int themeRecords() { return themeRecords; }

    static synchronized ThemeConfig lastTheme() { return lastTheme; }

    static synchronized Exception lastFailure() { return lastFailure; }
}
