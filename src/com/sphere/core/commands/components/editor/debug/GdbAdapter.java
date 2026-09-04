package com.sphere.components.editor.debug;

import com.sphere.components.editor.BreakpointModel;
import com.sphere.components.editor.DebugAdapter;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debugs C and C++ through gdb's machine interface. The source is compiled with
 * -g -O0 first, because a binary without line information cannot stop anywhere.
 */
public final class GdbAdapter implements DebugAdapter {

    private final SettingsManager settings;
    private final AtomicInteger token = new AtomicInteger(1);

    private Process gdb;
    private BufferedWriter toGdb;
    private Listener listener;
    private volatile State state = State.IDLE;
    private File sourceFile;

    // Filled from the stop event, then read back by the panel.
    private final List<StackFrame> callStack = new ArrayList<>();
    private final Map<String, String> variables = new LinkedHashMap<>();

    public GdbAdapter(SettingsManager settings) {
        this.settings = settings;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void start(File program, List<String> arguments, BreakpointModel breakpoints,
                      Listener listener) {
        this.listener = listener;
        this.sourceFile = program;
        setState(State.STARTING);

        String gdbPath = settings == null ? "gdb" : settings.resolveTool("GDB_DIR", "gdb");
        if (gdbPath == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            fail(settings != null && settings.isDeclaredEmpty("GDB_DIR")
                 ? "GDB_DIR is empty in settings.conf, which disables C and C++ debugging."
                 : "gdb was not found. Set GDB_DIR in settings.conf.");
            return;
        }

        File binary;
        try {
            binary = compile(program);
        } catch (IOException | InterruptedException ex) {
            fail("Compilation failed: " + ex.getMessage());
            return;
        }
        if (binary == null) {
            return;                              // compile() already reported why
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(
                gdbPath, "--interpreter=mi2", "-q", binary.getAbsolutePath());
            builder.directory(program.getAbsoluteFile().getParentFile());
            builder.redirectErrorStream(true);
            gdb = builder.start();
            toGdb = new BufferedWriter(new OutputStreamWriter(
                gdb.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            fail("gdb did not start: " + ex.getMessage());
            return;
        }

        Thread reader = new Thread(this::readLoop, "gdb-reader");
        reader.setDaemon(true);
        reader.start();

        String name = program.getName();
        for (int line : breakpoints == null ? java.util.Set.<Integer>of() : breakpoints.lines()) {
            if (breakpoints.isEnabled(line)) {
                send("-break-insert " + name + ":" + line);
            }
        }
        if (arguments != null && !arguments.isEmpty()) {
            send("-exec-arguments " + String.join(" ", arguments));
        }
        send("-exec-run");
        setState(State.RUNNING);
    }

    /**
     * Builds the debug binary next to the source. Optimisation is turned off so the
     * line the debugger stops on is the line that was written.
     */
    private File compile(File source) throws IOException, InterruptedException {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        File binary = new File(source.getAbsoluteFile().getParentFile(),
                               base + "_debug" + (windows ? ".exe" : ""));

        boolean isC = name.endsWith(".c");
        String key = isC ? "GCC_DIR" : "GPP_DIR";
        String fallback = isC ? "gcc" : "g++";
        String compiler = settings == null ? fallback : settings.resolveTool(key, fallback);
        if (compiler == null) {
            fail("No compiler: set " + key + " in settings.conf.");
            return null;
        }

        ProcessBuilder builder = new ProcessBuilder(compiler, "-g", "-O0",
            source.getAbsolutePath(), "-o", binary.getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (process.waitFor() != 0) {
            fail("Compilation failed:\n" + output);
            return null;
        }
        return binary;
    }

    // ---- Commands ----------------------------------------------------------

    /** A command sent to a finished session used to report the state as running. */
    private boolean isLive() {
        return state == State.RUNNING || state == State.PAUSED || state == State.STARTING;
    }

    @Override
    public void resume() {
        if (!isLive()) {
            return;
        }
        send("-exec-continue");
        setState(State.RUNNING);
    }

    @Override
    public void stepOver() {
        if (!isLive()) {
            return;
        }
        send("-exec-next");
        setState(State.RUNNING);
    }

    @Override
    public void stepInto() {
        if (!isLive()) {
            return;
        }
        send("-exec-step");
        setState(State.RUNNING);
    }

    @Override
    public void stepOut() {
        if (!isLive()) {
            return;
        }
        send("-exec-finish");
        setState(State.RUNNING);
    }

    @Override
    public void terminate() {
        if (gdb != null && gdb.isAlive()) {
            send("-gdb-exit");
            try {
                if (!gdb.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    gdb.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                gdb.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        setState(State.TERMINATED);
    }

    /** Adds or removes a breakpoint while the session is live. */
    public void syncBreakpoint(int line, boolean enabled) {
        if (gdb == null || !gdb.isAlive() || sourceFile == null) {
            return;
        }
        if (enabled) {
            send("-break-insert " + sourceFile.getName() + ":" + line);
        } else {
            send("-break-delete " + sourceFile.getName() + ":" + line);
        }
    }

    private synchronized void send(String command) {
        if (toGdb == null) {
            return;
        }
        try {
            toGdb.write(token.getAndIncrement() + command);
            toGdb.write("\n");
            toGdb.flush();
        } catch (IOException ex) {
            fail("gdb stopped accepting commands.");
        }
    }

    // ---- Reading -----------------------------------------------------------

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                gdb.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                handle(MiParser.parse(line));
            }
        } catch (IOException ignored) {
            // pipe closed on exit
        }
        setState(State.TERMINATED);
    }

    private void handle(MiParser.Record record) {
        switch (record.kind) {
            case PROGRAM, TARGET -> output(record.text, false);
            case CONSOLE -> {
                // gdb's own prose is noise once the panel shows frame and variables;
                // only its errors are worth the user's attention.
                if (record.text != null && record.text.startsWith("No symbol")) {
                    output(record.text, true);
                }
            }
            case RESULT -> handleResult(record);
            case EXEC -> handleExec(record);
            default -> { }
        }
    }

    private void handleResult(MiParser.Record record) {
        if ("error".equals(record.klass)) {
            String message = record.string("msg");
            output(message == null ? "gdb error" : message, true);
            return;
        }
        if (!"done".equals(record.klass)) {
            return;
        }
        Map<String, Object> bkpt = record.tuple("bkpt");
        if (bkpt != null) {
            reportBreakpointLine(bkpt);
            return;
        }
        if (record.fields.containsKey("stack")) {
            callStack.clear();
            for (Object entry : record.list("stack")) {
                if (entry instanceof Map<?, ?> frame) {
                    callStack.add(toFrame(frame));
                }
            }
            send("-stack-list-variables --all-values");
            return;
        }
        if (record.fields.containsKey("variables")) {
            variables.clear();
            for (Object entry : record.list("variables")) {
                if (entry instanceof Map<?, ?> variable) {
                    Object name = variable.get("name");
                    Object value = variable.get("value");
                    if (name != null) {
                        variables.put(name.toString(),
                                      value == null ? "" : value.toString());
                    }
                }
            }
            if (listener != null && !callStack.isEmpty()) {
                listener.paused(callStack.get(0), new ArrayList<>(callStack),
                                new LinkedHashMap<>(variables));
            }
        }
    }

    /**
     * A breakpoint asked for on a blank line or a declaration is bound to the next
     * line that carries code. Left unsaid, the dot sits where nothing ever stops.
     */
    private void reportBreakpointLine(Map<String, Object> bkpt) {
        Object effective = bkpt.get("line");
        Object origin = bkpt.get("original-location");
        if (effective == null || origin == null || listener == null) {
            return;
        }
        String text = origin.toString();
        int colon = text.lastIndexOf(':');
        if (colon < 0) {
            return;
        }
        try {
            int requested = Integer.parseInt(text.substring(colon + 1).trim());
            int actual = Integer.parseInt(effective.toString().trim());
            if (requested != actual) {
                listener.breakpointMoved(requested, actual);
            }
        } catch (NumberFormatException ignored) {
            // a breakpoint set by function name rather than line
        }
    }

    private void handleExec(MiParser.Record record) {
        if ("running".equals(record.klass)) {
            setState(State.RUNNING);
            return;
        }
        if (!"stopped".equals(record.klass)) {
            return;
        }
        String reason = record.string("reason");
        if (reason != null && reason.startsWith("exited")) {
            String code = record.string("exit-code");
            output("[program exited" + (code == null ? " normally" : " with code " + code) + "]",
                   false);
            terminate();
            return;
        }
        setState(State.PAUSED);
        // The frame arrives with the stop; the stack and variables are asked for
        // right away so the panel is filled by the time the user looks at it.
        send("-stack-list-frames");
    }

    private static StackFrame toFrame(Map<?, ?> frame) {
        Object full = frame.get("fullname");
        Object file = full != null ? full : frame.get("file");
        Object func = frame.get("func");
        int line = 0;
        try {
            line = Integer.parseInt(String.valueOf(frame.get("line")));
        } catch (NumberFormatException ignored) {
            // a frame without source, for example inside libc
        }
        return new StackFrame(func == null ? "?" : func.toString(),
                              file == null ? null : new File(file.toString()), line);
    }

    private void setState(State next) {
        if (state != next) {
            state = next;
            if (listener != null) {
                listener.stateChanged(next);
            }
        }
    }

    private void output(String text, boolean stderr) {
        if (listener != null && text != null && !text.isEmpty()) {
            listener.output(text, stderr);
        }
    }

    private void fail(String message) {
        output(message, true);
        setState(State.TERMINATED);
    }
}
