package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.core.python.jupyterlab.JupylabXedParser;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Python interpreter kept alive for the whole notebook, driven over
 * stdin/stdout. Running a fresh "python -c" per cell lost every variable between
 * cells, blocked the interface for as long as the code ran, and could not be
 * stopped; this holds the state, streams the output as it comes and answers an
 * interrupt.
 */
public final class PythonKernelProcess {

    /** Everything the kernel says, already sorted by kind. */
    public interface Listener {
        void onReady(String pythonVersion, String executable);
        void onStream(int execId, boolean stderr, String text);
        void onDisplay(int execId, Map<String, Object> bundle);
        void onResult(int execId, int count, Map<String, Object> bundle);
        void onError(int execId, String ename, String evalue, List<String> traceback);
        void onDone(int execId, Integer count, long millis);
        void onInputRequest(int execId, String prompt);
        void onCompletion(int execId, int start, List<String> matches);
        void onVariables(int execId, List<Map<String, Object>> items);
        void onExit(String reason);
    }

    private final SettingsManager settings;
    private final Listener listener;
    private final AtomicInteger nextId = new AtomicInteger(1);

    private Process process;
    private BufferedWriter toKernel;
    private volatile boolean running;
    private volatile int busyWith = -1;
    private String pythonVersion = "";

    public PythonKernelProcess(SettingsManager settings, Listener listener) {
        this.settings = settings;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    /** Execution id of the cell currently running, or -1. */
    public int busyWith() {
        return busyWith;
    }

    public String pythonVersion() {
        return pythonVersion;
    }

    /**
     * Launches the interpreter settings.conf points at. PYTHON_EXEC is the same key
     * the rest of Sphere uses, so the notebook lands in the user's own environment
     * instead of whatever "python" happens to mean on the machine.
     */
    public synchronized void start() throws IOException {
        if (isRunning()) {
            return;
        }
        String executable = settings == null ? null
                          : settings.resolveTool("PYTHON_EXEC", "python3");
        if (executable == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            throw new IOException(settings != null && settings.isDeclaredEmpty("PYTHON_EXEC")
                ? "PYTHON_EXEC is empty in settings.conf, which disables the notebook kernel."
                : "No Python interpreter: set PYTHON_EXEC in settings.conf.");
        }
        Path script = KernelScript.materialize();

        ProcessBuilder builder = new ProcessBuilder(executable, "-u",
                                                    script.toAbsolutePath().toString());
        builder.redirectErrorStream(false);
        process = builder.start();
        toKernel = new BufferedWriter(new OutputStreamWriter(
            process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;
        busyWith = -1;

        Thread reader = new Thread(this::readLoop, "jupylab-kernel-reader");
        reader.setDaemon(true);
        reader.start();

        Thread errors = new Thread(this::readErrors, "jupylab-kernel-stderr");
        errors.setDaemon(true);
        errors.start();
    }

    /** Ends the interpreter. Cell state is lost, which is the point of a restart. */
    public synchronized void shutdown() {
        running = false;
        send(Map.of("op", "shutdown"));
        Process live = process;
        if (live != null) {
            try {
                if (!live.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    live.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                live.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        busyWith = -1;
    }

    public synchronized void restart() throws IOException {
        shutdown();
        start();
    }

    /** Queues one cell. The kernel runs them one at a time, in order. */
    public int execute(String code) {
        int id = nextId.getAndIncrement();
        busyWith = id;
        send(Map.of("op", "exec", "id", id, "code", code));
        return id;
    }

    /**
     * Asks the running cell to stop. The kernel raises a real SIGINT in itself,
     * which breaks out of a blocking call rather than waiting for it to end.
     */
    public void interrupt() {
        send(Map.of("op", "interrupt"));
    }

    /** Wipes the namespace and the execution counter without restarting Python. */
    public void reset() {
        send(Map.of("op", "reset"));
    }

    public int complete(String code, int cursor) {
        int id = nextId.getAndIncrement();
        send(Map.of("op", "complete", "id", id, "code", code, "cursor", cursor));
        return id;
    }

    public int variables() {
        int id = nextId.getAndIncrement();
        send(Map.of("op", "vars", "id", id));
        return id;
    }

    public void answerInput(String text) {
        send(Map.of("op", "input", "text", text));
    }

    // ---- Wire --------------------------------------------------------------

    private synchronized void send(Map<String, Object> message) {
        BufferedWriter out = toKernel;
        if (out == null) {
            return;
        }
        try {
            out.write(encode(message));
            out.write("\n");
            out.flush();
        } catch (IOException ex) {
            running = false;
            listener.onExit("the kernel stopped accepting commands");
        }
    }

    private void readLoop() {
        Process live = process;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                live.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException ignored) {
            // pipe closed by the shutdown below
        }
        running = false;
        busyWith = -1;
        listener.onExit(null);
    }

    private void readErrors() {
        Process live = process;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                live.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                // Only what escapes before the driver redirects its own streams,
                // which means the interpreter itself failed to start.
                AppLogger.error("JupyLab kernel: " + line);
            }
        } catch (IOException ignored) {
            // pipe closed
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatch(String line) {
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
        String kind = String.valueOf(message.get("t"));
        int id = intOf(message.get("id"), -1);
        switch (kind) {
            case "ready" -> {
                pythonVersion = String.valueOf(message.get("python"));
                listener.onReady(pythonVersion, String.valueOf(message.get("executable")));
            }
            case "stream" -> listener.onStream(id, "stderr".equals(message.get("name")),
                                               String.valueOf(message.get("text")));
            case "display" -> listener.onDisplay(id, mapOf(message.get("bundle")));
            case "result" -> listener.onResult(id, intOf(message.get("count"), 0),
                                               mapOf(message.get("bundle")));
            case "error" -> listener.onError(id,
                                             String.valueOf(message.get("ename")),
                                             String.valueOf(message.get("evalue")),
                                             stringsOf(message.get("traceback")));
            case "done" -> {
                if (busyWith == id) {
                    busyWith = -1;
                }
                Object count = message.get("count");
                listener.onDone(id, count == null ? null : intOf(count, 0),
                                intOf(message.get("ms"), 0));
            }
            case "input" -> listener.onInputRequest(id, String.valueOf(message.get("prompt")));
            case "complete" -> listener.onCompletion(id, intOf(message.get("start"), 0),
                                                     stringsOf(message.get("matches")));
            case "vars" -> listener.onVariables(id, itemsOf(message.get("items")));
            default -> { }
        }
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static List<String> stringsOf(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    /** Minimal encoder: the kernel only ever receives flat objects. */
    private static String encode(Map<String, Object> message) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : message.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
