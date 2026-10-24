package dev.codex.watchworkshop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Installed items sort last; stable sort preserves the catalog order within each group. */
final class ModuleCatalog {
    static final class Entry {
        final String id, title;
        final boolean installed;

        Entry(String id, String title, boolean installed) {
            this.id = id;
            this.title = title;
            this.installed = installed;
        }
    }

    static List<Entry> sorted(List<Entry> catalog) {
        ArrayList<Entry> result = new ArrayList<>(catalog);
        Collections.sort(result, (a, b) -> Boolean.compare(a.installed, b.installed));
        return result;
    }
}
