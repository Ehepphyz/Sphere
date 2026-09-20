package com.sphere.components.variables;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every variable Sphere knows about, whatever produced it.
 *
 * A language is only ever a name here. Nothing in this class knows what Python
 * or Fortran are, so a language added later needs no change: it publishes under
 * its own name and appears beside the others.
 */
public final class VariableStore {

    /** One variable, as the language that produced it described it. */
    public record Variable(String source, String name, String type, String value) { }

    /** Told when what is held has changed. */
    public interface Listener {
        void variablesChanged();
    }

    /** A cap per source, so a program with thousands of symbols stays cheap. */
    private static final int MAX_PER_SOURCE = 4000;

    /** Source name, in the order first seen, to the variables it published. */
    private static final Map<String, List<Variable>> HELD = new LinkedHashMap<>();

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * The names the last publish created or gave a new value, as "source/name".
     * This is what lets the table show the effect of the line just typed instead
     * of leaving the reader to find it among hundreds of rows.
     */
    private static final java.util.Set<String> FRESH =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    private VariableStore() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Replaces everything held for one source.
     *
     * A language publishes its whole state at once rather than one variable at a
     * time, so a variable that has gone out of scope leaves the list instead of
     * staying behind for ever.
     */
    public static void publish(String source, List<Variable> variables) {
        if (source == null || source.isBlank()) {
            return;
        }
        final String key = source.trim().toLowerCase(java.util.Locale.ROOT);
        List<Variable> kept = new ArrayList<>();
        if (variables != null) {
            for (Variable variable : variables) {
                if (variable == null || variable.name() == null) {
                    continue;
                }
                kept.add(new Variable(key, variable.name(),
                                      variable.type() == null ? "" : variable.type(),
                                      variable.value() == null ? "" : variable.value()));
                if (kept.size() >= MAX_PER_SOURCE) {
                    break;
                }
            }
        }
        synchronized (HELD) {
            markFresh(key, kept, HELD.get(key));
            if (kept.isEmpty()) {
                HELD.remove(key);
            } else {
                HELD.put(key, kept);
            }
        }
        announce();
    }

    /** Drops one source, or every source when given null. */
    public static void clear(String source) {
        synchronized (HELD) {
            if (source == null) {
                HELD.clear();
            } else {
                HELD.remove(source.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        announce();
    }

    /** Everything held, source by source, each source's variables in order. */
    public static List<Variable> all() {
        List<Variable> everything = new ArrayList<>();
        synchronized (HELD) {
            for (List<Variable> variables : HELD.values()) {
                everything.addAll(variables);
            }
        }
        return everything;
    }

    /** The sources that have published something, in the order first seen. */
    public static List<String> sources() {
        synchronized (HELD) {
            return new ArrayList<>(HELD.keySet());
        }
    }

    public static int count() {
        int total = 0;
        synchronized (HELD) {
            for (List<Variable> variables : HELD.values()) {
                total += variables.size();
            }
        }
        return total;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Whether this row is one the last publish created or changed. */
    public static boolean isFresh(Variable variable) {
        return variable != null
            && FRESH.contains(variable.source() + "/" + variable.name());
    }

    /**
     * Compares what a source now holds with what it held before, and keeps the
     * difference. Only this source's marks are replaced: a Python line must not
     * clear what the Julia session just changed.
     */
    private static void markFresh(String key, List<Variable> now, List<Variable> before) {
        FRESH.removeIf(marked -> marked.startsWith(key + "/"));
        if (before == null) {
            // Nothing to compare against: a first publish marks nothing, or the
            // whole namespace would light up at once.
            return;
        }
        Map<String, String> previous = new LinkedHashMap<>();
        for (Variable variable : before) {
            previous.put(variable.name(), variable.value());
        }
        for (Variable variable : now) {
            final String had = previous.get(variable.name());
            if (had == null || !had.equals(variable.value())) {
                FRESH.add(key + "/" + variable.name());
            }
        }
    }

    private static void announce() {
        for (Listener listener : LISTENERS) {
            listener.variablesChanged();
        }
    }
}
