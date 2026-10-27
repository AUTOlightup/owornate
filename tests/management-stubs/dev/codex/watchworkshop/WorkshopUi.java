package dev.codex.watchworkshop;

import java.util.ArrayList;
import java.util.List;

/** 管理契约测试用最小 WorkshopUi，只记录调用和回调。 */
final class WorkshopUi {
    static final class Header {
        final String title;
        final Runnable back;
        final Boolean checked;
        final boolean enabled;
        final Runnable action;

        Header(String title, Runnable back, Boolean checked, boolean enabled, Runnable action) {
            this.title = title;
            this.back = back;
            this.checked = checked;
            this.enabled = enabled;
            this.action = action;
        }
    }

    static final class Note {
        final String text;
        final String tone;

        Note(String text, String tone) {
            this.text = text;
            this.tone = tone;
        }
    }

    static final class Button {
        final String label;
        final boolean primary;
        final boolean enabled;
        final Runnable action;

        Button(String label, boolean primary, boolean enabled, Runnable action) {
            this.label = label;
            this.primary = primary;
            this.enabled = enabled;
            this.action = action;
        }
    }

    final List<Header> headers = new ArrayList<>();
    final List<Note> notes = new ArrayList<>();
    final List<Button> buttons = new ArrayList<>();
    final List<String> titles = new ArrayList<>();
    final List<String> captions = new ArrayList<>();
    final List<Row> rows = new ArrayList<>();
    final List<Row> disclosures = new ArrayList<>();

    static final class Row {
        final String label;
        final Runnable action;
        boolean enabled = true;
        Row(String label, Runnable action) { this.label = label; this.action = action; }
        Row setEnabled(boolean value) { enabled = value; return this; }
    }

    Row row(String label, String sub, String icon, Runnable action) {
        Row row = new Row(label, action);
        rows.add(row);
        return row;
    }

    Row disclosure(String label, String sub, boolean expanded, Runnable action) {
        Row row = new Row(label, action);
        disclosures.add(row);
        return row;
    }

    void headerToggle(String title, Runnable back, Boolean checked, boolean enabled,
            Runnable action) {
        headers.add(new Header(title, back, checked, enabled, action));
    }

    void note(String value) {
        note(value, "");
    }

    void note(String value, String tone) {
        notes.add(new Note(value, tone));
    }

    void caption(String value) {
        captions.add(value);
    }

    void title(String value) {
        titles.add(value);
    }

    void button(String label, boolean primary, boolean enabled, Runnable action) {
        buttons.add(new Button(label, primary, enabled, action));
    }

    Header lastHeader() {
        return headers.isEmpty() ? null : headers.get(headers.size() - 1);
    }

    Button lastButton(String label) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            if (buttons.get(i).label.equals(label)) return buttons.get(i);
        }
        return null;
    }

    boolean hasNote(String expected) {
        for (Note note : notes) {
            if (note.text.equals(expected)) return true;
        }
        return false;
    }
}
