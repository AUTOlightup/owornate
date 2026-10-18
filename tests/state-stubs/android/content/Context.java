package android.content;

import java.util.HashMap;
import java.util.Map;

/** Minimal in-memory Context surface used by the state/cache tests. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> stores = new HashMap<>();

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences store = stores.get(name);
        if (store == null) {
            store = new InMemorySharedPreferences();
            stores.put(name, store);
        }
        return store;
    }
}
