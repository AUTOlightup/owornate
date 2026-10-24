package dev.codex.watchworkshop;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/** APK identity checks, independent of Android's archive reader. */
final class UpdatePolicy {
    private UpdatePolicy() {}
    static void requireApk(String expectedId, int currentCode, List<String> currentSigners,
            String nextId, int nextCode, List<String> nextSigners) throws IOException {
        if (!expectedId.equals(nextId)) throw new IOException("下载文件不是 OWOrnate 管理器 APK");
        if (nextCode <= currentCode) throw new IOException("安装包版本不高于当前版本");
        if (currentSigners == null || nextSigners == null || currentSigners.isEmpty()
                || currentSigners.size() != nextSigners.size()
                || new HashSet<>(currentSigners).size() != currentSigners.size()
                || !new HashSet<>(currentSigners).equals(new HashSet<>(nextSigners))) {
            throw new IOException("APK 签名与当前管理器不同，不能覆盖安装");
        }
    }
}
