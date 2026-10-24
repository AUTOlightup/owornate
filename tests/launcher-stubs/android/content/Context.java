package android.content;

import java.io.File;

/** Minimal Context surface required by plugin storage and the launcher adapter. */
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final File filesDir;
    private final java.util.Map<String, SharedPreferences> stores = new java.util.HashMap<>();

    public Context(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir");
        this.filesDir = filesDir;
    }

    public Context getApplicationContext() { return this; }

    public File getFilesDir() { return filesDir; }

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences result = stores.get(name);
        if (result == null) { result = new InMemorySharedPreferences(); stores.put(name, result); }
        return result;
    }

    public void setSharedPreferences(String name, SharedPreferences preferences) { stores.put(name, preferences); }
}
