package android.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal SharedPreferences contract with atomic commit and change callbacks. */
public interface SharedPreferences {
    interface Editor {
        Editor putString(String key, String value);
        Editor putBoolean(String key, boolean value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor remove(String key);
        Editor clear();
        boolean commit();
        void apply();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key);
    }

    Map<String, ?> getAll();
    String getString(String key, String defaultValue);
    boolean getBoolean(String key, boolean defaultValue);
    int getInt(String key, int defaultValue);
    long getLong(String key, long defaultValue);
    boolean contains(String key);
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
}

final class InMemorySharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

    @Override public synchronized Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Override public synchronized String getString(String key, String defaultValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    @Override public synchronized boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    @Override public synchronized int getInt(String key, int defaultValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    @Override public synchronized long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }

    @Override public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override public Editor edit() {
        return new MemoryEditor();
    }

    @Override public synchronized void registerOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(
            OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear;

        @Override public Editor putString(String key, String value) {
            pending.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public Editor putInt(String key, int value) {
            pending.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public Editor putLong(String key, long value) {
            pending.put(key, value);
            removed.remove(key);
            return this;
        }

        @Override public Editor remove(String key) {
            pending.remove(key);
            removed.add(key);
            return this;
        }

        @Override public Editor clear() {
            clear = true;
            pending.clear();
            removed.clear();
            return this;
        }

        @Override public boolean commit() {
            List<String> changed = new ArrayList<>();
            synchronized (InMemorySharedPreferences.this) {
                if (clear) {
                    changed.addAll(values.keySet());
                    values.clear();
                }
                for (String key : removed) {
                    if (values.remove(key) != null && !changed.contains(key)) changed.add(key);
                }
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    Object old = values.put(entry.getKey(), entry.getValue());
                    if ((old == null && entry.getValue() != null)
                            || (old != null && !old.equals(entry.getValue()))) {
                        if (!changed.contains(entry.getKey())) changed.add(entry.getKey());
                    }
                }
                for (String key : changed) {
                    for (OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners)) {
                        listener.onSharedPreferenceChanged(InMemorySharedPreferences.this, key);
                    }
                }
            }
            return true;
        }

        @Override public void apply() {
            commit();
        }
    }
}
