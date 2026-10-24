package dev.codex.watchworkshop;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;

/**
 * APK 内置的 root 脚本与组件文件：原样放到应用私有目录，供 root shell 按固定路径读取。
 *
 * <p>root 命令里的路径都写死为 {@link #FILES} 下的目录，不接受外部输入；所以运行前核对
 * 应用私有目录确实就是这个路径（原表上 /data/user/0 是 /data/data 的符号链接，按规范路径比较）。
 * 每个目录每个进程只写一次，写入用 {@link AtomicFile}，不会留下半个文件。
 */
final class Payloads {
    /** root 脚本看到的本应用私有 files 目录。 */
    static final String FILES = "/data/user/0/dev.codex.watchworkshop/files/";

    private static final HashSet<String> prepared = new HashSet<>();

    private Payloads() {}

    /** 把 assets/{dir}/ 下的 names 写到 files/{dir}/；本进程已写过就直接返回。 */
    static synchronized void extract(Context context, String dir, String... names)
            throws IOException {
        if (prepared.contains(dir)) return;
        File target = new File(context.getFilesDir(), dir);
        if (!target.getCanonicalPath().equals(new File(FILES + dir).getCanonicalPath())) {
            throw new IOException("请在手表主用户中运行管理器");
        }
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法准备组件文件");
        for (String name : names) {
            try (InputStream in = context.getAssets().open(dir + "/" + name)) {
                write(new File(target, name), in, null);
            }
        }
        prepared.add(dir);
    }

    /** 原子地写入整份内容：成功才替换旧文件，失败时旧文件保持不变。 */
    static void writeAtomically(File file, byte[] content) throws IOException {
        write(file, null, content);
    }

    private static void write(File file, InputStream source, byte[] content) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            if (source == null) {
                out.write(content);
            } else {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            atomic.finishWrite(out);
            out = null;
        } finally {
            if (out != null) atomic.failWrite(out);
        }
    }
}
