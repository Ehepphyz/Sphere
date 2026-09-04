package com.sphere.components.editor.debug;

import com.sphere.components.editor.BreakpointModel;
import com.sphere.components.editor.DebugAdapter;
import com.sphere.core.python.jupyterlab.JupylabXedParser;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debugs Python through a bdb driver, using the interpreter settings.conf names.
 * Same contract as the gdb side, so the panel drives either without knowing which.
 */
public final class PdbAdapter implements DebugAdapter {

    private final SettingsManager settings;

    private Process process;
    private BufferedWriter toDriver;
    private Listener listener;
    private volatile State state = State.IDLE;

    public PdbAdapter(SettingsManager settings) {
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
        setState(State.STARTING);

        String python = settings == null ? null : settings.resolveTool("PYTHON_EXEC", "python3");
        if (python == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            fail(settings != null && settings.isDeclaredEmpty("PYTHON_EXEC")
                 ? "PYTHON_EXEC is empty in settings.conf, which disables Python debugging."
                 : "No Python interpreter: set PYTHON_EXEC in settings.conf.");
            return;
        }
        try {
            Path driver = DebugScript.materialize();
            List<String> command = new ArrayList<>();
            command.add(python);
            command.add("-u");
            command.add(driver.toAbsolutePath().toString());
            command.add(program.getAbsolutePath());
            if (arguments != null) {
                command.addAll(arguments);
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(program.getAbsoluteFile().getParentFile());
            process = builder.start();
            toDriver = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            fail("The Python debugger did not start: " + ex.getMessage());
            return;
        }

        Thread reader = new Thread(this::readLoop, "pdb-reader");
        reader.setDaemon(true);
        reader.start();

        Thread errors = new Thread(this::readErrors, "pdb-stderr");
        errors.setDaemon(true);
        errors.start();

        if (breakpoints != null) {
            for (int line : breakpoints.lines()) {
                if (breakpoints.isEnabled(line)) {
                    send("{\"op\":\"break\",\"line\":" + line + "}");
                }
            }
        }
        send("{\"op\":\"run\"}");
        setState(State.RUNNING);
    }

    /** A command sent to a finished session used to report the state as running. */
    private boolean isLive() {
        return state == State.RUNNING || state == State.PAUSED || state == State.STARTING;
    }

    @Override
    public void resume() {
        if (!isLive()) {
            return;
        }
        send("{\"op\":\"continue\"}");
        setState(State.RUNNING);
    }

    @Override
    public void stepOver() {
        if (!isLive()) {
            return;
        }
        send("{\"op\":\"next\"}");
        setState(State.RUNNING);
    }

    @Override
    public void stepInto() {
        if (!isLive()) {
            return;
        }
        send("{\"op\":\"step\"}");
        setState(State.RUNNING);
    }

    @Override
    public void stepOut() {
        if (!isLive()) {
            return;
        }
        send("{\"op\":\"return\"}");
        setState(State.RUNNING);
    }

    @Override
    public void terminate() {
        send("{\"op\":\"terminate\"}");
        Process live = process;
        if (live != null) {
            try {
                if (!live.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    live.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                live.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        setState(State.TERMINATED);
    }

    /** Adds or removes a breakpoint while the session is live. */
    public void syncBreakpoint(int line, boolean enabled) {
        send("{\"op\":\"" + (enabled ? "break" : "clear") + "\",\"line\":" + line + "}");
    }

    private synchronized void send(String message) {
        if (toDriver == null) {
            return;
        }
        try {
            toDriver.write(message);
            toDriver.write("\n");
            toDriver.flush();
        } catch (IOException ex) {
            setState(State.TERMINATED);
        }
    }

    // ---- Reading -----------------------------------------------------------

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                handle(line);
            }
        } catch (IOException ignored) {
            // pipe closed on exit
        }
        setState(State.TERMINATED);
    }

    private void readErrors() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                output(line, true);
            }
        } catch (IOException ignored) {
            // pipe closed
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(String line) {
        Map<String, Object> message;
        try {
            Object parsed = new JupylabXedParser(line).parse();
            if (!(parsed instanceof Map)) {
                return;
            }
            message = (Map<String, Object>) parsed;
        } catch (RuntimeException ex) {
            return;
        }
        switch (String.valueOf(message.get("t"))) {
            case "output" -> output(String.valueOf(message.get("text")),
                                    "stderr".equals(message.get("name")));
            case "running" -> setState(State.RUNNING);
            case "stopped" -> handleStopped(message);
            case "exception" -> {
                Object traceback = message.get("traceback");
                if (traceback instanceof List<?> lines) {
                    for (Object entry : lines) {
                        output(String.valueOf(entry), true);
                    }
                }
            }
            case "exited" -> {
                Object code = message.get("code");
                output("[program exited with code " + code + "]", false);
                setState(State.TERMINATED);
            }
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleStopped(Map<String, Object> message) {
        setState(State.PAUSED);
        List<StackFrame> stack = new ArrayList<>();
        Object frames = message.get("stack");
        if (frames instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> frame) {
                    stack.add(new StackFrame(
                        String.valueOf(frame.get("func")),
                        new File(String.valueOf(frame.get("file"))),
                        intOf(frame.get("line"))));
                }
            }
        }
        Map<String, String> variables = new LinkedHashMap<>();
        Object locals = message.get("locals");
        if (locals instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                variables.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        StackFrame top = stack.isEmpty()
            ? new StackFrame(String.valueOf(message.get("func")),
                             new File(String.valueOf(message.get("file"))),
                             intOf(message.get("line")))
            : stack.get(0);
        if (listener != null) {
            listener.paused(top, stack, variables);
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
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
