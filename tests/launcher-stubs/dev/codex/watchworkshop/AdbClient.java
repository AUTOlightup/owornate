package dev.codex.watchworkshop;

import java.io.IOException;

/** Native client shape required to compile the real ExternalPlugins class. */
final class AdbClient {
    interface Shell {
        Reply run(String command, long totalTimeoutMs) throws IOException;
    }

    static final class Reply {
        final int exitCode;
        final String output;

        Reply(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private final Shell injectedShell;
    private int exitCode = -1;

    AdbClient() {
        this(null);
    }

    AdbClient(Shell shell) {
        injectedShell = shell;
    }

    String shell(String command, long totalTimeoutMs) throws IOException {
        if (injectedShell == null) {
            throw new UnsupportedOperationException("device access is outside adapter tests");
        }
        Reply reply = injectedShell.run(command, totalTimeoutMs);
        if (reply == null) throw new IOException("injected ADB shell returned no reply");
        exitCode = reply.exitCode;
        return reply.output;
    }

    int exitCode() { return exitCode; }
}
