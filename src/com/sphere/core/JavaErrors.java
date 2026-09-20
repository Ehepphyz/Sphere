package com.sphere.core;

import com.sphere.utils.AppLogger;

import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where every Java error ends up.
 *
 * Without this, an exception raised by a click, a repaint or a background thread
 * is written to the standard error stream and lost: Sphere started from a desktop
 * entry has no terminal, so the action simply does not happen and nothing is
 * said. Everything is funnelled to the console instead, where the user is
 * already looking.
 */
public final class JavaErrors {

    /** How many frames of the trace are kept. */
    private static final int FRAMES = 10;

    /** This class's own frames say nothing about where the failure came from. */
    private static final String OWN_PACKAGE = JavaErrors.class.getName();

    /** How long the same error stays quiet after being reported. */
    private static final long REPEAT_SILENCE_MS = 5000;

    /** Beyond this the map is cleared: a program failing in that many ways is lost anyway. */
    private static final int MAX_SIGNATURES = 500;

    private static final Map<String, Long> LAST_SEEN = new ConcurrentHashMap<>();

    private static boolean installed;

    private JavaErrors() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Puts the net in place: one handler for every thread, and one for the event
     * thread, which AWT serves separately and which would otherwise swallow its
     * own exceptions.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        Thread.setDefaultUncaughtExceptionHandler(
            (thread, error) -> report("thread " + thread.getName(), error));

        SwingUtilities.invokeLater(() -> {
            try {
                Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
                    @Override
                    protected void dispatchEvent(AWTEvent event) {
                        try {
                            super.dispatchEvent(event);
                        } catch (Throwable failure) {
                            // Not rethrown: the point is that one broken action does
                            // not take the interface down with it.
                            report("the interface", failure);
                        }
                    }
                });
            } catch (RuntimeException refused) {
                AppLogger.error("Java errors from the interface cannot be caught: "
                                + refused.getMessage());
            }
        });
    }

    /**
     * Reports one error, saying what was being done when it happened.
     *
     * A failure inside a paint or a renderer repeats with every redraw, so the
     * same error is said once and then held back for a few seconds rather than
     * filling the console with one line per frame.
     */
    public static void report(String doing, Throwable error) {
        if (error == null) {
            return;
        }
        final String signature = signatureOf(error);
        final long now = System.currentTimeMillis();
        final Long seen = LAST_SEEN.get(signature);
        if (seen != null && now - seen < REPEAT_SILENCE_MS) {
            return;
        }
        if (LAST_SEEN.size() > MAX_SIGNATURES) {
            LAST_SEEN.clear();
        }
        LAST_SEEN.put(signature, now);

        AppLogger.error("Java error in " + doing + ": " + describe(error));
    }

    // ---- reading the error ---------------------------------------------------

    private static String signatureOf(Throwable error) {
        StackTraceElement[] frames = error.getStackTrace();
        final String top = frames.length > 0 ? frames[0].toString() : "";
        return error.getClass().getName() + "|" + error.getMessage() + "|" + top;
    }

    /**
     * The error as a few readable lines: what it is, then where in Sphere it came
     * from. Frames of the runtime itself are kept only when Sphere's own are
     * missing, which happens when the failure is entirely inside a library.
     */
    private static String describe(Throwable error) {
        StringBuilder written = new StringBuilder();
        written.append(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            written.append(": ").append(error.getMessage());
        }

        StackTraceElement[] frames = error.getStackTrace();
        int shown = 0;
        for (StackTraceElement frame : frames) {
            if (shown >= FRAMES) {
                break;
            }
            if (frame.getClassName().startsWith("com.sphere.")
                && !frame.getClassName().startsWith(OWN_PACKAGE)) {
                written.append("\n    at ").append(frame);
                shown++;
            }
        }
        if (shown == 0) {
            for (StackTraceElement frame : frames) {
                if (shown >= 3) {
                    break;
                }
                written.append("\n    at ").append(frame);
                shown++;
            }
        }

        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            written.append("\n    caused by ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                written.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        return written.toString();
    }
}
