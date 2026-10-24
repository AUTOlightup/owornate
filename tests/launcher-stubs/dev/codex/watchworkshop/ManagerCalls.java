package dev.codex.watchworkshop;

import java.util.ArrayList;
import java.util.List;

/** Ordered lifecycle calls received by the simulated feature managers. */
final class ManagerCalls {
    static final List<String> calls = new ArrayList<>();

    private ManagerCalls() {}

    static void reset() { calls.clear(); }

    static void add(String value) { calls.add(value); }
}
