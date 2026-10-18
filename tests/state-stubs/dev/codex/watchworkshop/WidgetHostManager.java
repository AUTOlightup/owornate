package dev.codex.watchworkshop;

/** Minimal status DTO used by RuntimeStateStore tests. */
final class WidgetHostManager {
    static final class Status {
        final String host, keep;
        Status(String host, String keep) {
            this.host = host;
            this.keep = keep;
        }
    }

    private WidgetHostManager() {
    }
}
