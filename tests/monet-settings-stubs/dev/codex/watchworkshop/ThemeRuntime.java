package dev.codex.watchworkshop;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Deterministic theme protocol substitute used only by MonetSettingsTest. */
final class ThemeRuntime {
    static final class Exchange {
        final String action;
        final ThemeConfig config;

        Exchange(String action, ThemeConfig config) {
            this.action = action;
            this.config = config;
        }
    }

    private static final List<Exchange> exchanges = new ArrayList<>();
    private static final Deque<Object> reads = new ArrayDeque<>();
    private static final Deque<Object> applies = new ArrayDeque<>();

    private ThemeRuntime() {}

    static synchronized void reset() {
        exchanges.clear();
        reads.clear();
        applies.clear();
    }

    static synchronized void resetCalls() {
        exchanges.clear();
    }

    static synchronized void queueRead(String mode, String seed, boolean corners) {
        reads.add(ack(mode, seed, corners));
    }

    static synchronized void queueApply(String mode, String seed, boolean corners) {
        applies.add(ack(mode, seed, corners));
    }

    static synchronized void failNextApply(Exception failure) {
        applies.add(failure);
    }

    static synchronized List<Exchange> exchanges() {
        return Collections.unmodifiableList(new ArrayList<>(exchanges));
    }

    static synchronized List<String> actions() {
        ArrayList<String> result = new ArrayList<>();
        for (Exchange exchange : exchanges) result.add(exchange.action);
        return result;
    }

    static synchronized JSONObject exchange(Context context, String action, ThemeConfig config)
            throws Exception {
        exchanges.add(new Exchange(action, config));
        Object reply;
        if ("read".equals(action)) reply = reads.pollFirst();
        else if ("apply".equals(action)) reply = applies.pollFirst();
        else throw new AssertionError("unexpected theme action: " + action);
        if (reply == null) throw new AssertionError("no queued " + action + " reply");
        if (reply instanceof Exception) throw (Exception) reply;
        return (JSONObject) reply;
    }

    static ThemeConfig config(JSONObject state) throws JSONException {
        return new ThemeConfig(state.getString("mode"), color(state.getString("seed")),
                state.getBoolean("corners"));
    }

    static int color(String hex) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
            throw new IllegalArgumentException("bad test seed");
        }
        return (int) Long.parseLong(hex.substring(1), 16) | 0xff000000;
    }

    private static JSONObject ack(String mode, String seed, boolean corners) {
        try {
            return new JSONObject().put("ok", true).put("state", new JSONObject()
                    .put("ready", true).put("helperVersion", 6)
                    .put("mode", mode).put("seed", seed).put("corners", corners));
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
