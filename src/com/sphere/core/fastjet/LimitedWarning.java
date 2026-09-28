package com.sphere.core.fastjet;

import java.util.function.Consumer;

/**
 * A warning printed only the first few times it occurs.
 *
 * The messages go to a sink that the host application may replace; by default
 * they go to standard error.
 */
public final class LimitedWarning {

    private static final int DEFAULT_MAX = 5;
    private static volatile Consumer<String> sink = System.err::println;

    private final int max;
    private int count;

    public LimitedWarning() {
        this(DEFAULT_MAX);
    }

    public LimitedWarning(int max) {
        this.max = max;
    }

    /** Where every warning is sent. */
    public static void setSink(Consumer<String> newSink) {
        sink = newSink == null ? System.err::println : newSink;
    }

    public synchronized void warn(String message) {
        if (count < max) {
            count++;
            String text = "WARNING from FastJet (Java): " + message;
            if (count == max) {
                text += " (LAST SUCH WARNING)";
            }
            sink.accept(text);
        }
    }

    public synchronized int nWarnings() {
        return count;
    }
}
