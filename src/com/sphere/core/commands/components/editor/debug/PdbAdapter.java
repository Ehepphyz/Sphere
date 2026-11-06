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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Debugs Python through a bdb driver, using the interpreter settings.conf names.
 * Same contract as the gdb side, so the panel drives either without knowing which.
 *
 * The driver takes breakpoints and Pause at any time, even while the program runs;
 * evaluations, frames and the line to run to are answered while it is paused.
 */
public final class PdbAdapter implements DebugAdapter {

    private final SettingsManager settings;
    private final AtomicInteger requests = new AtomicInteger(1);
    private final Map<Integer, BiConsumer<Boolean, String>> evaluations = new ConcurrentHashMap<>();

    private Process process;
    private java.net.Socket commandSocket;
    private BufferedWriter toDriver;
    private Listener listener;
    private volatile State state = State.IDLE;
    private volatile ExceptionMode exceptions = ExceptionMode.UNCAUGHT;
    private BreakpointModel model;
    /** The breakpoints the driver holds, by model line. Guarded by this. */
    private final Map<Integer, BreakpointModel.Spec> installed = new LinkedHashMap<>();
    /** A line the driver bound to a later one, until the model follows. Guarded by this. */
    private final Map<Integer, Integer> aliases = new LinkedHashMap<>();
    private volatile int frameCount;

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
        this.model = breakpoints;
        setState(State.STARTING);

        String python = settings == null ? null : settings.resolveTool("PYTHON_EXEC", "python");
        if (python == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            fail(settings != null && settings.isDeclaredEmpty("PYTHON_EXEC")
                 ? "PYTHON_EXEC is empty in settings.conf, which disables Python debugging."
                 : "No Python interpreter: set PYTHON_EXEC in settings.conf.");
            return;
        }
        // Commands go over a loopback socket rather than the driver's stdin: on Windows
        // a read held open on a pipe stalls every DLL that looks at the standard handles
        // as it loads, and "import numpy" never returned. The token keeps any other
        // local process from taking the place of the driver.
        String token = java.util.UUID.randomUUID().toString();
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
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
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("SPHERE_DEBUG_HOST", server.getInetAddress().getHostAddress());
            builder.environment().put("SPHERE_DEBUG_PORT", String.valueOf(server.getLocalPort()));
            builder.environment().put("SPHERE_DEBUG_TOKEN", token);
            process = builder.start();
            // The program's own stdin: closed, so input() ends instead of waiting for ever.
            process.getOutputStream().close();

            Thread reader = new Thread(this::readLoop, "pdb-reader");
            reader.setDaemon(true);
            reader.start();
            Thread errors = new Thread(this::readErrors, "pdb-stderr");
            errors.setDaemon(true);
            errors.start();

            java.net.Socket channel = accept(server, token);
            if (channel == null) {
                fail("The Python debugger did not connect back to Sphere.");
                process.destroyForcibly();
                return;
            }
            commandSocket = channel;
            toDriver = new BufferedWriter(new OutputStreamWriter(channel.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            fail("The Python debugger did not start: " + ex.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
            return;
        }

        send("{\"op\":\"exceptions\",\"mode\":\"" + exceptions.name().toLowerCase(Locale.ROOT) + "\"}");
        sync();
        send("{\"op\":\"run\"}");
        setState(State.RUNNING);
    }

    /**
     * Waits for the driver to connect and say the token, as long as the process
     * lives and for at most 30 seconds (a first import of the interpreter can be slow).
     */
    private java.net.Socket accept(java.net.ServerSocket server, String token) throws IOException {
        long deadline = System.currentTimeMillis() + 30_000;
        server.setSoTimeout(400);
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            java.net.Socket s;
            try {
                s = server.accept();
            } catch (java.net.SocketTimeoutException ex) {
                continue;
            }
            try {
                s.setSoTimeout(5000);
                String hello = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
                if (hello != null && hello.contains("\"" + token + "\"")) {
                    s.setSoTimeout(0);
                    return s;
                }
            } catch (IOException ignored) {
                // not the driver
            }
            s.close();
        }
        return null;
    }

    /** A command sent to a finished session used to report the state as running. */
    private boolean isLive() {
        return state == State.RUNNING || state == State.PAUSED || state == State.STARTING;
    }

    @Override
    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("{\"op\":\"continue\"}");
    }

    @Override
    public void stepOver() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("{\"op\":\"next\"}");
    }

    @Override
    public void stepInto() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("{\"op\":\"step\"}");
    }

    @Override
    public void stepOut() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("{\"op\":\"return\"}");
    }

    @Override
    public void pause() {
        if (state == State.RUNNING) {
            send("{\"op\":\"pause\"}");
        }
    }

    @Override
    public void runToLine(int line) {
        if (state != State.PAUSED || line < 1) {
            return;
        }
        setState(State.RUNNING);
        send("{\"op\":\"until\",\"line\":" + line + "}");
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
        closeChannel();
        failEvaluations();
        setState(State.TERMINATED);
    }

    private void closeChannel() {
        java.net.Socket s = commandSocket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    @Override
    public boolean canEvaluate() {
        return true;
    }

    @Override
    public void evaluate(String expression, int frame, BiConsumer<Boolean, String> reply) {
        if (state != State.PAUSED) {
            reply.accept(false, "The program is running; pause it to evaluate.");
            return;
        }
        int id = requests.getAndIncrement();
        evaluations.put(id, reply);
        send("{\"op\":\"eval\",\"id\":" + id + ",\"frame\":" + Math.max(0, frame)
             + ",\"expr\":" + json(expression) + "}");
    }

    @Override
    public void selectFrame(int index) {
        if (state == State.PAUSED && index >= 0 && index < frameCount) {
            send("{\"op\":\"frame\",\"index\":" + index + "}");
        }
    }

    @Override
    public void setExceptionMode(ExceptionMode mode) {
        exceptions = mode == null ? ExceptionMode.UNCAUGHT : mode;
        if (isLive()) {
            send("{\"op\":\"exceptions\",\"mode\":\"" + exceptions.name().toLowerCase(Locale.ROOT) + "\"}");
        }
    }

    @Override
    public void syncBreakpoints(BreakpointModel breakpoints) {
        if (breakpoints != null) {
            model = breakpoints;
        }
        if (isLive()) {
            sync();
        }
    }

    /** Kept for callers of the former, line-by-line synchronization. */
    public void syncBreakpoint(int line, boolean enabled) {
        syncBreakpoints(model);
    }

    /** Sends the driver what changed since it last heard: breakpoints gone, new or with another spec. */
    private synchronized void sync() {
        if (model == null) {
            return;
        }
        Map<Integer, BreakpointModel.Spec> wanted = new LinkedHashMap<>();
        for (int line : model.lines()) {
            if (model.isEnabled(line)) {
                Integer moved = aliases.get(line);
                int key = moved != null && installed.containsKey(moved) && !model.has(moved) ? moved : line;
                wanted.put(key, model.spec(line));
            }
        }
        for (Iterator<Map.Entry<Integer, BreakpointModel.Spec>> it = installed.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, BreakpointModel.Spec> e = it.next();
            BreakpointModel.Spec w = wanted.get(e.getKey());
            if (w == null || !w.equals(e.getValue())) {
                send("{\"op\":\"clear\",\"line\":" + e.getKey() + "}");
                it.remove();
            }
        }
        aliases.values().removeIf(effective -> !installed.containsKey(effective));
        for (Map.Entry<Integer, BreakpointModel.Spec> w : wanted.entrySet()) {
            if (!installed.containsKey(w.getKey())) {
                installed.put(w.getKey(), w.getValue());
                BreakpointModel.Spec s = w.getValue();
                StringBuilder msg = new StringBuilder("{\"op\":\"break\",\"line\":").append(w.getKey());
                if (s.condition() != null) {
                    msg.append(",\"cond\":").append(json(s.condition()));
                }
                if (s.hitCount() > 1) {
                    msg.append(",\"hit\":").append(s.hitCount());
                }
                if (s.logMessage() != null) {
                    msg.append(",\"log\":").append(json(s.logMessage()));
                }
                send(msg.append('}').toString());
            }
        }
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

    /** A JSON string, so quotes, backslashes and newlines reach Python whole. */
    static String json(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
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
        closeChannel();
        failEvaluations();
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
            // Not protocol: something wrote to the real stdout before the redirect.
            output(line, false);
            return;
        }
        switch (String.valueOf(message.get("t"))) {
            case "output" -> output(String.valueOf(message.get("text")),
                                    "stderr".equals(message.get("name")));
            case "log" -> output("» " + message.get("text"), false);
            case "running" -> setState(State.RUNNING);
            case "stopped" -> handleStopped(message);
            case "frame" -> {
                if (listener != null) {
                    listener.frameSelected(intOf(message.get("index")), variables(message.get("locals")));
                }
            }
            case "eval" -> {
                BiConsumer<Boolean, String> reply = evaluations.remove(intOf(message.get("id")));
                if (reply != null) {
                    reply.accept(Boolean.TRUE.equals(message.get("ok")), String.valueOf(message.get("value")));
                }
            }
            case "breakpoint" -> handleBreakpoint(message);
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
                failEvaluations();
                setState(State.TERMINATED);
            }
            default -> { }
        }
    }

    /** The driver set a breakpoint, on the line asked or on the next one carrying code. */
    private void handleBreakpoint(Map<String, Object> message) {
        int line = intOf(message.get("line"));
        int effective = intOf(message.get("effective"));
        if (!Boolean.TRUE.equals(message.get("set"))) {
            Object error = message.get("error");
            output("The breakpoint on line " + line + " was not set" + (error == null ? "" : ": " + error), true);
            return;
        }
        boolean moved = false;
        synchronized (this) {
            BreakpointModel.Spec spec = installed.get(line);
            if (effective > 0 && effective != line && spec != null && !installed.containsKey(effective)) {
                installed.remove(line);
                installed.put(effective, spec);
                aliases.put(line, effective);
                moved = true;
            }
        }
        if (moved && listener != null) {
            listener.breakpointMoved(line, effective);
        }
    }

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
        frameCount = stack.size();
        Map<String, String> variables = variables(message.get("locals"));
        int selected = intOf(message.get("selected"));
        StackFrame top = stack.isEmpty() || selected < 0 || selected >= stack.size()
            ? new StackFrame(String.valueOf(message.get("func")),
                             new File(String.valueOf(message.get("file"))),
                             intOf(message.get("line")))
            : stack.get(selected);
        if (listener != null) {
            String reason = String.valueOf(message.get("reason"));
            Object description = message.get("description");
            listener.stopReason(description == null ? reason : description.toString(),
                                "exception".equals(reason) || "uncaught".equals(reason));
            listener.paused(top, stack, variables);
        }
    }

    private static Map<String, String> variables(Object locals) {
        Map<String, String> variables = new LinkedHashMap<>();
        if (locals instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                variables.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return variables;
    }

    private void failEvaluations() {
        for (Integer id : new ArrayList<>(evaluations.keySet())) {
            BiConsumer<Boolean, String> reply = evaluations.remove(id);
            if (reply != null) {
                reply.accept(false, "The session ended.");
            }
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
