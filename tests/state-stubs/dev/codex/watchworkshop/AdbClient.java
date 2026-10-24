package dev.codex.watchworkshop;

/** Startup-test transport; production RuntimeSupport is compiled unchanged. */
final class AdbClient {
    String shell(String command, long timeout) { return RootShell.output; }
    int exitCode() { return RootShell.code; }
}
