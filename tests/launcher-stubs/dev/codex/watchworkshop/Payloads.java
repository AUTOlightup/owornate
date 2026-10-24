package dev.codex.watchworkshop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** File-side effect used by LauncherPlugins.prepare; the production adapter remains real. */
final class Payloads {
    static int writes;

    private Payloads() {}

    static void writeAtomically(File file, byte[] content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create parent directory: " + parent);
        }
        writes++;
        Files.write(file.toPath(), content, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
