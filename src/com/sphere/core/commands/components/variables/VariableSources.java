package com.sphere.components.variables;

import com.sphere.utils.AppLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The languages that are still running and can be asked again.
 *
 * A program that has ended leaves a file and nothing more. One that is still
 * alive, such as the Python kernel or the ROOT interpreter, can be questioned,
 * and registers here so that asking for a refresh reaches it.
 */
public final class VariableSources {

    /** A language that can be asked what it is holding. */
    public interface Source {
        String name();

        /** Asked off the event thread, so it may take its time. */
        void refresh();
    }

    private static final Map<String, Source> LIVE = new LinkedHashMap<>();

    private VariableSources() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Registers a source, replacing any earlier one under the same name. */
    public static void register(Source source) {
        if (source == null || source.name() == null) {
            return;
        }
        synchronized (LIVE) {
            LIVE.put(source.name(), source);
        }
    }

    public static void unregister(String name) {
        synchronized (LIVE) {
            LIVE.remove(name);
        }
    }

    public static List<String> names() {
        synchronized (LIVE) {
            return new ArrayList<>(LIVE.keySet());
        }
    }

    /** Asks every live language again, on a thread of its own. */
    public static void refreshAll() {
        final List<Source> asked;
        synchronized (LIVE) {
            asked = new ArrayList<>(LIVE.values());
        }
        if (asked.isEmpty()) {
            return;
        }
        Thread worker = new Thread(() -> {
            for (Source source : asked) {
                try {
                    source.refresh();
                } catch (RuntimeException refused) {
                    AppLogger.error("Could not read the variables of " + source.name()
                                    + ": " + refused.getMessage());
                }
            }
        }, "sphere-variable-refresh");
        worker.setDaemon(true);
        worker.start();
    }
}
