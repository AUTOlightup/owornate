package dev.codex.watchworkshop;

import android.content.Context;
import java.io.File;

/**
 * 本机 root adbd 的唯一入口：进程内只读一次密钥文件，一次操作只开一个连接。
 *
 * <p>调用方把同一操作里的几条命令放进一个 {@link Body}，它们共用一次握手和一次 RSA 签名
 * （{@link AdbClient#shell} 支持在一个连接上连续开流）。命令本身仍然由各调用方固定拼好，
 * 这里不接受也不转发任何外部输入。
 */
final class RootShell {
    interface Body<T> {
        T run(AdbClient adb) throws Exception;
    }

    private static RsaKeyStore keys;

    private RootShell() { }

    static synchronized RsaKeyStore keys(Context context) {
        if (keys == null) keys = new RsaKeyStore(new File(context.getApplicationContext().getFilesDir(), "adb"));
        return keys;
    }

    /** 提前生成或读入密钥；第一次生成 2048 位密钥要几秒，放在后台线程调用。 */
    static void prime(Context context) throws Exception {
        keys(context).publicKeyLine();
    }

    static <T> T session(Context context, int connectMs, int readMs, Body<T> body) throws Exception {
        RsaKeyStore store = keys(context);
        try (AdbClient adb = AdbClient.open(connectMs, readMs, store.privateKey(), store.publicKeyLine())) {
            return body.run(adb);
        }
    }
}
