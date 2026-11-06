package com.sphere.components.editor;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * What the editor needs from a debugger, kept free of any wire format so a DAP
 * client can be dropped in behind it. The editor already provides the breakpoint
 * model and the execution-line marker that a session drives.
 *
 * The first methods are the transport every debugger has. The defaults below them
 * are what a richer adapter adds (evaluating in a frame, walking the stack, running
 * to a line, pausing a running program, following breakpoints while it runs); an
 * adapter without them simply keeps the default, and the panel greys the feature out.
 */
public interface DebugAdapter {

    enum State { IDLE, STARTING, RUNNING, PAUSED, TERMINATED }

    /** When the program stops for an exception. */
    enum ExceptionMode {
        /** Only when nothing catches it: the program would end on it. */
        UNCAUGHT("Uncaught exceptions"),
        /** Wherever one is raised, caught later or not. */
        ALL("All exceptions"),
        /** Never: an uncaught exception ends the program as it would without a debugger. */
        NEVER("No exceptions");

        public final String label;

        ExceptionMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    final class StackFrame {
        public final String name;
        public final File file;
        public final int line;

        public StackFrame(String name, File file, int line) {
            this.name = name;
            this.file = file;
            this.line = line;
        }

        /** True when the frame has source the editor can show. */
        public boolean hasSource() {
            return file != null && line > 0 && file.isFile();
        }
    }

    interface Listener {
        void stateChanged(State state);

        /** Called with the frame the session stopped on, so the editor can mark it. */
        void paused(StackFrame frame, List<StackFrame> callStack, Map<String, String> variables);

        void output(String line, boolean stderr);

        /** gdb binds a breakpoint to the next line carrying code; the dot follows. */
        default void breakpointMoved(int requested, int effective) { }

        /**
         * Why the session paused, said before {@link #paused}: "Breakpoint", "Step",
         * "Exception: ZeroDivisionError: division by zero". A problem (an exception, a
         * signal) is shown as one.
         */
        default void stopReason(String text, boolean problem) { }

        /** The variables of a frame chosen with {@link DebugAdapter#selectFrame}. */
        default void frameSelected(int index, Map<String, String> variables) { }
    }

    State state();

    void start(File program, List<String> arguments, BreakpointModel breakpoints,
               Listener listener);

    void resume();
    void stepOver();
    void stepInto();
    void stepOut();
    void terminate();

    // ---- What a richer adapter adds ------------------------------------------

    /** Whether {@link #evaluate} answers: watches, the debug console and hover values use it. */
    default boolean canEvaluate() {
        return false;
    }

    /**
     * Evaluates an expression in a frame of the paused program (0 is the innermost)
     * and answers on any thread: ok and the value, or not ok and why.
     */
    default void evaluate(String expression, int frame, BiConsumer<Boolean, String> reply) {
        reply.accept(false, "This debugger does not evaluate expressions.");
    }

    /** Asks for the variables of another frame; they come back through {@link Listener#frameSelected}. */
    default void selectFrame(int index) { }

    /** Runs on until the given line of the program's source (Run to Cursor). */
    default void runToLine(int line) { }

    /** Stops a running program where it is (Pause). */
    default void pause() { }

    /**
     * Brings the session's breakpoints in line with the model: added, removed,
     * enabled, disabled or given a condition while the session runs.
     */
    default void syncBreakpoints(BreakpointModel model) { }

    /** Sets when the program stops for an exception; before start, or live. */
    default void setExceptionMode(ExceptionMode mode) { }

    /**
     * Placeholder used until a real adapter is installed. Refuses to start rather
     * than pretending a session is running.
     */
    final class Unavailable implements DebugAdapter {
        private final String reason;

        public Unavailable(String reason) {
            this.reason = reason == null ? "No debug adapter configured." : reason;
        }

        @Override public State state() { return State.IDLE; }

        @Override
        public void start(File program, List<String> arguments, BreakpointModel breakpoints,
                          Listener listener) {
            if (listener != null) {
                listener.output(reason, true);
                listener.stateChanged(State.TERMINATED);
            }
        }

        @Override public void resume() { }
        @Override public void stepOver() { }
        @Override public void stepInto() { }
        @Override public void stepOut() { }
        @Override public void terminate() { }
    }
}
