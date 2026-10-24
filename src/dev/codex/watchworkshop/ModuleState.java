package dev.codex.watchworkshop;

/** Confirmed module installation/runtime state, shared by the manager and info widget. */
final class ModuleState {
    final String kind, owner, message;
    final boolean active, activeKnown;
    final ThemeConfig theme;

    ModuleState(String kind, boolean active, String message) {
        this(kind, "", active, message, true, null);
    }

    ModuleState(String kind, boolean active, String message, boolean known) {
        this(kind, "", active, message, known, null);
    }

    ModuleState(String kind, boolean active, String message, boolean known, ThemeConfig theme) {
        this(kind, "", active, message, known, theme);
    }

    ModuleState(String kind, String owner, boolean active, String message, boolean known,
            ThemeConfig theme) {
        this.kind = kind;
        this.owner = owner == null ? "" : owner;
        this.active = active;
        this.message = message;
        this.activeKnown = known;
        this.theme = theme;
    }

    boolean installed() {
        return "installed".equals(kind) || "shared".equals(kind) || "disabled".equals(kind)
                || "update".equals(kind);
    }

    boolean enabled() {
        return installed() && activeKnown && active;
    }

    boolean removable() {
        return installed() && "own".equals(owner) && !"shared".equals(kind);
    }
}
