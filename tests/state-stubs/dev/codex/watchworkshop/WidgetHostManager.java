package dev.codex.watchworkshop;

/** Minimal status DTO used by RuntimeStateStore tests. */
final class WidgetHostManager {
    static final class Status {
        final String host, keep, owner;
        Status(String host, String keep) {
            this(host, keep, "");
        }
        Status(String host, String keep, String owner) {
            this.host = host;
            this.keep = keep;
            this.owner = owner;
        }
        boolean removable() {
            return ("ready".equals(host) || "installed".equals(host)
                    || "disabled".equals(host) || "update".equals(host)) && "own".equals(owner);
        }
    }

    private WidgetHostManager() {
    }
}
