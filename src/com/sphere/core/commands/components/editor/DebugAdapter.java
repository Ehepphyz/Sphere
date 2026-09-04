package com.sphere.components.editor;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * What the editor needs from a debugger, kept free of any wire format so a DAP
 * client can be dropped in behind it. The editor already provides the breakpoint
 * model and the execution-line marker that a session drives.
 */
public interface DebugAdapter {

    enum State { IDLE, STARTING, RUNNING, PAUSED, TERMINATED }

    final class StackFrame {
        public final String name;
        public final File file;
        public final int line;

        public StackFrame(String name, File file, int line) {
            this.name = name;
            this.file = file;
            this.line = line;
        }
    }

    interface Listener {
        void stateChanged(State state);

        /** Called with the frame the session stopped on, so the editor can mark it. */
        void paused(StackFrame frame, List<StackFrame> callStack, Map<String, String> variables);

        void output(String line, boolean stderr);

        /** gdb binds a breakpoint to the next line carrying code; the dot follows. */
        default void breakpointMoved(int requested, int effective) { }
    }

    State state();

    void start(File program, List<String> arguments, BreakpointModel breakpoints,
               Listener listener);

    void resume();
    void stepOver();
    void stepInto();
    void stepOut();
    void terminate();

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
