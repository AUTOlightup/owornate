package android.util;

/** No-op logger for the pure JVM state tests. */
public final class Log {
    public static int w(String tag, String message) {
        return 0;
    }

    private Log() {
    }
}
