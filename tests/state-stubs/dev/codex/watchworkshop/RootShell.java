package dev.codex.watchworkshop;

import android.content.Context;

final class RootShell {
    static String output = "";
    static int code;
    static Exception failure;
    interface Body<T> { T run(AdbClient adb) throws Exception; }
    static <T> T session(Context context, int connect, int read, Body<T> body) throws Exception {
        if (failure != null) throw failure;
        return body.run(new AdbClient());
    }
}
