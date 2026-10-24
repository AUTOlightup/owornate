package dev.codex.watchworkshop;

import android.content.Context;

/** Device-session stub; launcher payload preparation and adapter tests never connect ADB. */
final class RootShell {
    interface Body<T> {
        T run(AdbClient adb) throws Exception;
    }

    interface Session {
        Object run(Context context, int connectMs, int readMs, Body<?> body)
                throws Exception;
    }

    private static volatile Session injectedSession;

    private RootShell() {}

    static void setInjectedSession(Session session) {
        injectedSession = session;
    }

    static void clearInjectedSession() {
        injectedSession = null;
    }

    @SuppressWarnings("unchecked")
    static <T> T session(Context context, int connectMs, int readMs, Body<T> body)
            throws Exception {
        Session session = injectedSession;
        if (session == null) {
            throw new UnsupportedOperationException("device access is outside adapter tests");
        }
        return (T) session.run(context, connectMs, readMs, body);
    }
}
