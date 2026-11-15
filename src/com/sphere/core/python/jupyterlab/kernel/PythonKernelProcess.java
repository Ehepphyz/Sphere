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
import com.sphere.core.rootbackend.RootBackend;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Python interpreter kept alive for the whole notebook, driven over
 * stdin/stdout. Running a fresh "python -c" per cell lost every variable between
 * cells, blocked the interface for as long as the code ran, and could not be
 * stopped; this holds the state, streams the output as it comes and answers an
 * interrupt.
 */
public final class PythonKernelProcess implements KernelClient {

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

        // ---- What a Jupyter kernel adds; the stdio driver never sends these ----

        /** A display that names itself, so a later update_display_data can replace it in place. */
        default void onDisplay(int execId, Map<String, Object> bundle, String displayId) {
            onDisplay(execId, bundle);
        }

        default void onUpdateDisplay(String displayId, Map<String, Object> bundle) {
        }

        /** clear_output: wait=true clears when the next output arrives, as progress bars expect. */
        default void onClearOutput(int execId, boolean wait) {
        }

        /** Completion with what each candidate is (function, module, keyword...), when the kernel says. */
        default void onCompletion(int execId, int start, int end, List<String> matches, List<String> types) {
            onCompletion(execId, start, matches);
        }

        default void onInspect(int execId, boolean found, Map<String, Object> bundle) {
        }

        /** busy, idle, starting. */
        default void onStatus(String state) {
        }

        /** Per cell: undefined names and the live kinds of the names it uses. */
        default void onAnalysis(int execId, List<Map<String, Object>> cells) {
        }

        /** What a value looks like (sphere_kernel_tools.peek): one map, in a list as the tools send it. */
        default void onPeek(int execId, List<Map<String, Object>> items) {
        }

        /** The content of a usage_reply: kernel_memory (bytes), kernel_cpu (%), host_virtual_memory... */
        default void onUsage(int execId, Map<String, Object> usage) {
        }
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

    private Path workingDirectory;

    /**
     * The folder the interpreter starts in: the notebook's, as in Jupyter, so
     * open("data.csv") finds the file beside the notebook rather than Sphere's own folder.
     */
    public void setWorkingDirectory(Path directory) {
        this.workingDirectory = directory;
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
        // Looked for as "python", which also accepts python3 and python3.12:
        // asked for as "python3", a PYTHON_EXEC ending in python.exe did not
        // count as the tool, and some other Python on the PATH ran instead,
        // without the user's packages.
        String executable = settings == null ? null
                          : settings.resolveTool("PYTHON_EXEC", "python");
        if (executable == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            throw new IOException(settings != null && settings.isDeclaredEmpty("PYTHON_EXEC")
                ? "PYTHON_EXEC is empty in settings.conf, which disables the notebook kernel."
                : "No Python interpreter: set PYTHON_EXEC in settings.conf.");
        }
        Path script = KernelScript.materialize();
        KernelScript.materializeShm();
        KernelScript.materializeTools();

        ProcessBuilder builder = new ProcessBuilder(executable, "-u",
                                                    script.toAbsolutePath().toString());
        builder.redirectErrorStream(false);
        if (workingDirectory != null && java.nio.file.Files.isDirectory(workingDirectory)) {
            builder.directory(workingDirectory.toFile());
        }
        // Tells the kernel which region to map. Without it the kernel starts
        // normally and simply has no shared-memory module.
        final RootBackend backend = RootBackend.getInstance();
        if (backend != null && !backend.shmRegionPath().isEmpty()) {
            builder.environment().put("SPHERE_SHM_PATH", backend.shmRegionPath());
        }
        com.sphere.core.bridge.Bridge.environment(builder.environment());
        // The kernel collects the figures itself after each run and sends them
        // on; plt.show() must leave them to it rather than take them first.
        builder.environment().put("SPHERE_MPL_KEEP", "1");
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
                    destroyTree(live);
                }
            } catch (InterruptedException ex) {
                destroyTree(live);
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        busyWith = -1;
    }

    /**
     * Ends a process and everything it started. A venv's python.exe is a launcher
     * that runs the real interpreter as its child: killing the launcher alone left
     * that interpreter, and every variable it held, running after the notebook closed.
     */
    static void destroyTree(Process live) {
        try {
            live.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException unsupported) {
            // a platform that cannot list children still gets the process itself killed
        }
        live.destroyForcibly();
    }

    @Override
    public int inspect(String code, int cursor) {
        return -1; // the stdio driver has no documentation lookup; the ipykernel path does
    }

    @Override
    public int analyze(List<String> cells) {
        int id = nextId.getAndIncrement();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("op", "analyze");
        message.put("id", id);
        message.put("cells", cells);
        send(message);
        return id;
    }

    @Override
    public String describe() {
        return pythonVersion.isEmpty() ? "Sphere driver" : "Python " + pythonVersion + " · Sphere driver";
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
        // Python counts code points, Java UTF-16 units: an emoji before the caret shifted the word
        completionText = code;
        send(Map.of("op", "complete", "id", id, "code", code,
                    "cursor", code.codePointCount(0, Math.max(0, Math.min(cursor, code.length())))));
        return id;
    }

    /** The text of the last completion asked for, to turn the answer's offset back into UTF-16. */
    private volatile String completionText = "";

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
            out.write(Json.encode(message));
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
            case "complete" -> {
                String text = completionText;
                int points = Math.max(0, Math.min(intOf(message.get("start"), 0), text.codePointCount(0, text.length())));
                listener.onCompletion(id, text.offsetByCodePoints(0, points), stringsOf(message.get("matches")));
            }
            case "vars" -> listener.onVariables(id, itemsOf(message.get("items")));
            case "analysis" -> listener.onAnalysis(id, itemsOf(message.get("cells")));
            case "shm_alloc" -> answerShmAlloc(message);
            case "shm_release" -> answerShmRelease(message);
            default -> { }
        }
    }

    /**
     * Hands the kernel a block of the shared region. Java owns the allocator, so
     * the claim happens in one place rather than in a second implementation on
     * the Python side. Only the request travels: the data itself never leaves the
     * region.
     */
    private void answerShmAlloc(Map<String, Object> message) {
        final int rid = intOf(message.get("rid"), 0);
        final int bytes = intOf(message.get("bytes"), 0);
        final int dtype = intOf(message.get("dtype"), 0);
        final RootBackend backend = RootBackend.getInstance();

        long offset = 0L;
        int generation = 0;
        if (backend != null && bytes > 0) {
            RootBackend.HeapHandle handle = backend.acquireForClient(bytes, (short) dtype);
            offset = handle.payloadOffset();
            generation = handle.generation();
        }
        send(Map.of("op", "shm_reply", "rid", rid,
                    "offset", offset, "generation", generation));
    }

    private void answerShmRelease(Map<String, Object> message) {
        final RootBackend backend = RootBackend.getInstance();
        if (backend == null) {
            return;
        }
        final Object offset = message.get("offset");
        backend.releaseForClient(offset instanceof Number n ? n.longValue() : 0L,
                                 intOf(message.get("generation"), 0));
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
}
