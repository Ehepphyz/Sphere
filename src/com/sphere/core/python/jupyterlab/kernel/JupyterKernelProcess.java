package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A Jupyter kernel driven over its own protocol: an ipykernel in any Python
 * environment, or any other kernel installed on the machine (Wolfram, Julia...).
 *
 * The stdio driver re-implemented a notebook's interpreter and so lacked what a
 * notebook takes for granted: magics (%timeit, %%time, !pip), display() and
 * update_display, clear_output for progress bars, completion that knows
 * dictionary keys and call parameters, documentation on Shift+Tab. This speaks
 * to the real thing, through {@link ZmtpSocket}, and reports to the same
 * listener the driver does, so the notebook does not care which one it runs on.
 *
 * A Python ipykernel is started through Sphere's bootstrap, which adds a side
 * channel over the process's own pipes: an interrupt that works on Windows
 * (where ipykernel only takes one from a parent-created event), the shared-memory
 * bridge, and an exit when Sphere goes away instead of an orphaned interpreter.
 */
public final class JupyterKernelProcess implements KernelClient {

    private enum Kind { EXECUTE, VARIABLES, ANALYZE, COMPLETE, INSPECT, INFO, PEEK, USAGE, EFFECT }

    private static final class Pending {
        final int id;
        final Kind kind;
        final long started = System.nanoTime();
        final String text;
        Integer count;
        boolean replied;
        boolean idle;
        boolean finished;

        Pending(int id, Kind kind, String text) {
            this.id = id;
            this.kind = kind;
            this.text = text;
        }
    }

    private static final String HOST = "127.0.0.1";
    private static final int STDERR_TAIL = 40;

    private final KernelSpecs.Spec spec;
    private final PythonKernelProcess.Listener listener;
    private final Path workingDirectory;
    private final boolean bootstrapped;
    /** Sphere's homemade kernel: speaks the protocol itself and carries the side channel and the namespace tools. */
    private final boolean homemade;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Executions already done, kept for the output a background thread prints after its cell returned. */
    private final Map<String, Integer> recent = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 64;
        }
    });
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile Process process;
    private volatile ZmtpSocket shell;
    private volatile ZmtpSocket iopub;
    private volatile ZmtpSocket stdin;
    private volatile ZmtpSocket control;
    private volatile JupyterSession session;
    private volatile BufferedWriter sideChannel;
    private volatile boolean running;
    private volatile int busyWith = -1;
    private volatile Map<String, Object> inputRequestHeader;
    private volatile String infoMsgId = "";
    private volatile boolean infoReplied;
    private volatile boolean iopubHeard;
    private volatile String description = "";
    private volatile String languageVersion = "";
    private Path connectionFile;

    public JupyterKernelProcess(KernelSpecs.Spec spec, PythonKernelProcess.Listener listener, Path workingDirectory) {
        this.spec = spec;
        this.listener = listener;
        this.workingDirectory = workingDirectory;
        this.bootstrapped = spec.isIpykernel();
        this.homemade = spec.isHomemade();
        this.description = spec.displayName();
    }

    public KernelSpecs.Spec spec() {
        return spec;
    }

    @Override
    public boolean isRunning() {
        Process p = process;
        return running && p != null && p.isAlive();
    }

    @Override
    public String describe() {
        return description;
    }

    // ---- Life cycle --------------------------------------------------------

    @Override
    public synchronized void start() throws IOException {
        if (isRunning()) {
            return;
        }
        usagePending = null;
        usageMisses = 0; // a new kernel may answer usage_request where the last one did not
        int[] ports = freePorts(5);
        String key = HexFormat.of().formatHex(randomBytes(32));
        session = new JupyterSession(key);
        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("shell_port", ports[0]);
        conn.put("iopub_port", ports[1]);
        conn.put("stdin_port", ports[2]);
        conn.put("control_port", ports[3]);
        conn.put("hb_port", ports[4]);
        conn.put("ip", HOST);
        conn.put("key", key);
        conn.put("transport", "tcp");
        conn.put("signature_scheme", "hmac-sha256");
        conn.put("kernel_name", spec.name());
        connectionFile = Files.createTempFile("sphere-kernel-", ".json");
        Files.writeString(connectionFile, Json.encode(conn), StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder(command());
        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            // Jupyter starts a kernel in its notebook's folder: open("data.csv") means the file beside it.
            builder.directory(workingDirectory.toFile());
        }
        Map<String, String> env = builder.environment();
        env.putAll(spec.env());
        if (bootstrapped || homemade) {
            RootBackend backend = RootBackend.getInstance();
            if (backend != null && !backend.shmRegionPath().isEmpty()) {
                env.put("SPHERE_SHM_PATH", backend.shmRegionPath());
            }
            com.sphere.core.bridge.Bridge.environment(env);
            // The kernel draws figures inline unless a backend is named; the Plots-tab
            // backend the bridge names for scripts would take them from the cell.
            if ("module://sphere_mpl".equals(env.get("MPLBACKEND"))) {
                env.remove("MPLBACKEND");
            }
        }
        if (bootstrapped) {
            // ipykernel's debugger warns about frozen modules on every start otherwise
            env.put("PYDEVD_DISABLE_FILE_VALIDATION", "1");
        }
        if (homemade) {
            // Sphere's commands and the shared-memory bridge on the process's own pipes, as for an ipykernel
            env.put("SPHERE_SIDE_CHANNEL", "1");
            env.put("PYTHONIOENCODING", "utf-8");
        }
        synchronized (stderrTail) {
            stderrTail.clear();
        }
        Process p = builder.start();
        process = p;
        running = true;
        busyWith = -1;
        sideChannel = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        daemon("jupyter-kernel-stdout", () -> readSideChannel(p));
        daemon("jupyter-kernel-stderr", () -> readErrors(p));
        p.onExit().thenRun(() -> {
            if (process == p && running) {
                running = false;
                busyWith = -1;
                pending.clear();
                listener.onExit("the kernel exited (code " + p.exitValue() + ")" + lastError());
            }
        });

        long deadline = System.currentTimeMillis() + 60_000;
        BooleanSupplier alive = p::isAlive;
        byte[] identity = session.sessionId.getBytes(StandardCharsets.US_ASCII);
        try {
            // iopub first: a SUB socket only hears what is published after it subscribed
            iopub = ZmtpSocket.connect(HOST, ports[1], ZmtpSocket.Type.SUB, null, deadline, alive);
            // shell and stdin must share an identity: ipykernel routes input_request by the shell's
            shell = ZmtpSocket.connect(HOST, ports[0], ZmtpSocket.Type.DEALER, identity, deadline, alive);
            stdin = ZmtpSocket.connect(HOST, ports[2], ZmtpSocket.Type.DEALER, identity, deadline, alive);
            control = ZmtpSocket.connect(HOST, ports[3], ZmtpSocket.Type.DEALER, identity, deadline, alive);
        } catch (IOException e) {
            String why = e.getMessage() + lastError();
            shutdown();
            throw new IOException("the kernel did not open its ports: " + why, e);
        }
        daemon("jupyter-iopub", () -> readSocket(iopub, "iopub"));
        daemon("jupyter-shell", () -> readSocket(shell, "shell"));
        daemon("jupyter-stdin", () -> readSocket(stdin, "stdin"));
        daemon("jupyter-control", () -> readSocket(control, "control"));

        // kernel_info until both the reply and its iopub status came back: then the
        // subscription is live and no output of the first cell can be missed.
        for (int attempt = 0; attempt < 40 && isRunning(); attempt++) {
            infoReplied = false;
            iopubHeard = false;
            infoMsgId = send(shell, "kernel_info_request", Map.of(), new Pending(-1, Kind.INFO, null));
            long until = System.currentTimeMillis() + (attempt == 0 ? 20_000 : 1_000);
            while (System.currentTimeMillis() < until && !(infoReplied && iopubHeard) && isRunning()) {
                sleep(5);
            }
            if (infoReplied && iopubHeard) {
                break;
            }
        }
        if (!infoReplied) {
            String why = lastError();
            shutdown();
            throw new IOException("the kernel did not answer kernel_info" + why);
        }
        listener.onReady(languageVersion, description);
    }

    private List<String> command() throws IOException {
        List<String> argv = spec.argv();
        List<String> cmd = new ArrayList<>();
        String launcher = "";
        if (bootstrapped) {
            Path bootstrap = KernelScript.materializeIpykernel();
            KernelScript.materializeShm();
            KernelScript.materializeTools();
            int m = argv.indexOf("-m");
            cmd.addAll(argv.subList(0, m));
            cmd.add(bootstrap.toAbsolutePath().toString());
            cmd.addAll(argv.subList(m + 2, argv.size()));
        } else {
            if (homemade) {
                // the kernel, and beside it what Sphere's notebook asks of it (analysis, shared memory)
                launcher = SphereJupyKernel.materialize().toAbsolutePath().toString();
                KernelScript.materializeShm();
                KernelScript.materializeTools();
            }
            cmd.addAll(argv);
        }
        String conn = connectionFile.toAbsolutePath().toString();
        String resources = spec.resourceDir() == null ? "" : spec.resourceDir().toString();
        String launcherPath = launcher;
        cmd.replaceAll(a -> a.replace("{connection_file}", conn).replace("{resource_dir}", resources)
                             .replace(KernelSpecs.LAUNCHER_PLACEHOLDER, launcherPath));
        return cmd;
    }

    @Override
    public synchronized void shutdown() {
        boolean wasRunning = running;
        running = false;
        Process p = process;
        if (wasRunning && control != null && !control.isClosed()) {
            try {
                send(control, "shutdown_request", Map.of("restart", false), null);
            } catch (RuntimeException ignored) {
                // the kill below covers it
            }
        }
        if (p != null) {
            try {
                if (!p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    PythonKernelProcess.destroyTree(p);
                }
            } catch (InterruptedException ex) {
                PythonKernelProcess.destroyTree(p);
                Thread.currentThread().interrupt();
            }
        }
        for (ZmtpSocket s : new ZmtpSocket[] { shell, iopub, stdin, control }) {
            if (s != null) {
                s.close();
            }
        }
        shell = iopub = stdin = control = null;
        process = null;
        busyWith = -1;
        pending.clear();
        if (connectionFile != null) {
            try {
                Files.deleteIfExists(connectionFile);
            } catch (IOException ignored) {
                // a temp file left behind is harmless
            }
        }
    }

    @Override
    public synchronized void restart() throws IOException {
        shutdown();
        start();
    }

    // ---- Requests ----------------------------------------------------------

    @Override
    public int execute(String code) {
        int id = nextId.getAndIncrement();
        busyWith = id;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", code);
        content.put("silent", false);
        content.put("store_history", true);
        content.put("user_expressions", Map.of());
        content.put("allow_stdin", true);
        content.put("stop_on_error", true);
        send(shell, "execute_request", content, new Pending(id, Kind.EXECUTE, code));
        return id;
    }

    /**
     * Runs statements on the namespace as no cell: silent, so no counter, no
     * history, no output; any Jupyter kernel does it, ipykernel and Sphere's own.
     */
    @Override
    public int effect(String code) {
        if (!isRunning() || code == null || code.isBlank()) {
            return -1;
        }
        int id = nextId.getAndIncrement();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", code);
        content.put("silent", true);
        content.put("store_history", false);
        content.put("user_expressions", Map.of());
        content.put("allow_stdin", false);
        content.put("stop_on_error", false);
        send(shell, "execute_request", content, new Pending(id, Kind.EFFECT, code));
        return id;
    }

    /** Evaluates one expression in the user namespace without touching its history or counter. */
    private int silent(Kind kind, String expression) {
        if (!bootstrapped && !homemade) {
            return -1; // only Sphere's bootstrap and Sphere's own kernel carry sphere_kernel_tools
        }
        int id = nextId.getAndIncrement();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("code", "");
        content.put("silent", true);
        content.put("store_history", false);
        content.put("user_expressions", Map.of("sphere", expression));
        content.put("allow_stdin", false);
        content.put("stop_on_error", false);
        send(shell, "execute_request", content, new Pending(id, kind, null));
        return id;
    }

    @Override
    public int variables() {
        return silent(Kind.VARIABLES, "__import__('sphere_kernel_tools').variables(globals())");
    }

    @Override
    public int analyze(List<String> cells) {
        return silent(Kind.ANALYZE, "__import__('sphere_kernel_tools').analyze(" + Json.encode(cells) + ", globals())");
    }

    @Override
    public int peek(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return -1;
        }
        return silent(Kind.PEEK, "__import__('sphere_kernel_tools').peek('" + name + "', globals())");
    }

    /** The usage_request still unanswered, and how many in a row were: a kernel silent three times is not asked again. */
    private volatile String usagePending;
    private volatile int usageMisses;

    /** Jupyter's usage_request, on the control channel: answered even while a cell runs. */
    @Override
    public int usage() {
        if (control == null || !isRunning() || usageMisses >= 3) {
            return -1;
        }
        String unanswered = usagePending;
        if (unanswered != null) {
            pending.remove(unanswered);
            usageMisses++;
            if (usageMisses >= 3) {
                return -1;
            }
        }
        int id = nextId.getAndIncrement();
        usagePending = send(control, "usage_request", Map.of(), new Pending(id, Kind.USAGE, null));
        return id;
    }

    @Override
    public int complete(String code, int cursor) {
        int id = nextId.getAndIncrement();
        send(shell, "complete_request", Map.of("code", code, "cursor_pos", codePoints(code, cursor)),
             new Pending(id, Kind.COMPLETE, code));
        return id;
    }

    @Override
    public int inspect(String code, int cursor) {
        int id = nextId.getAndIncrement();
        send(shell, "inspect_request", Map.of("code", code, "cursor_pos", codePoints(code, cursor), "detail_level", 0),
             new Pending(id, Kind.INSPECT, code));
        return id;
    }

    @Override
    public void answerInput(String text) {
        Map<String, Object> parent = inputRequestHeader;
        if (parent != null) {
            send(stdin, "input_reply", Map.of("value", text == null ? "" : text), null, parent);
        }
    }

    /**
     * Stops the running cell. Through the bootstrap, the kernel raises SIGINT in
     * itself, which also wakes a time.sleep; any other kernel gets the protocol's
     * interrupt_request.
     */
    @Override
    public void interrupt() {
        if (bootstrapped && side(Map.of("op", "interrupt"))) {
            return;
        }
        send(control, "interrupt_request", Map.of(), null);
    }

    public int busyWith() {
        return busyWith;
    }

    // ---- Wire --------------------------------------------------------------

    private String send(ZmtpSocket socket, String type, Map<String, Object> content, Pending p) {
        return send(socket, type, content, p, null);
    }

    private String send(ZmtpSocket socket, String type, Map<String, Object> content, Pending p,
                        Map<String, Object> parent) {
        JupyterSession s = session;
        if (socket == null || s == null) {
            return "";
        }
        Map<String, Object> header = new LinkedHashMap<>();
        List<byte[]> frames = s.frames(type, content, parent, header);
        String msgId = Json.string(header.get("msg_id"), "");
        if (p != null) {
            pending.put(msgId, p); // before the send: a fast reply must find it
        }
        try {
            socket.send(frames);
        } catch (IOException e) {
            pending.remove(msgId);
            if (running) {
                running = false;
                listener.onExit("the kernel stopped accepting requests: " + e.getMessage());
            }
        }
        return msgId;
    }

    private void readSocket(ZmtpSocket socket, String channel) {
        if (socket == null) {
            return;
        }
        try {
            while (!socket.isClosed()) {
                List<byte[]> frames = socket.receive();
                JupyterSession s = session;
                JupyterSession.Message msg = s == null ? null : s.parse(frames);
                if (msg == null) {
                    continue;
                }
                try {
                    switch (channel) {
                        case "iopub" -> onIopub(msg);
                        case "stdin" -> onStdin(msg);
                        default -> onReply(msg);
                    }
                } catch (RuntimeException e) {
                    AppLogger.warn("Jupyter kernel: could not handle " + msg.type() + ": " + e);
                }
            }
        } catch (IOException closed) {
            // the socket went with the kernel
        }
    }

    /** -Dsphere.kernel.trace=true prints every message with its delay since the request. */
    private static final boolean TRACE = Boolean.getBoolean("sphere.kernel.trace");

    private static void trace(String channel, JupyterSession.Message msg, Pending p) {
        if (TRACE && p != null) {
            System.out.printf("[kernel] %6.2f ms  %-7s %s%n", (System.nanoTime() - p.started) / 1e6, channel,
                              msg.type() + ("status".equals(msg.type()) ? " " + msg.content().get("execution_state") : ""));
        }
    }

    private void onIopub(JupyterSession.Message msg) {
        String parentId = msg.parentId();
        Pending p = pending.get(parentId);
        trace("iopub", msg, p);
        Map<String, Object> c = msg.content();
        if (parentId.equals(infoMsgId)) {
            iopubHeard = true;
        }
        int execId = p != null && p.kind == Kind.EXECUTE ? p.id : recent.getOrDefault(parentId, -1);
        switch (msg.type()) {
            case "status" -> {
                String state = Json.string(c.get("execution_state"), "");
                if (p != null && "idle".equals(state)) {
                    p.idle = true;
                    maybeFinish(parentId, p);
                }
                if (p != null && p.kind == Kind.EXECUTE) {
                    listener.onStatus(state);
                }
            }
            case "execute_input" -> {
                if (p != null && p.kind == Kind.EXECUTE && c.get("execution_count") instanceof Number n) {
                    p.count = n.intValue();
                }
            }
            case "stream" -> {
                if (execId >= 0) {
                    listener.onStream(execId, "stderr".equals(c.get("name")), Json.string(c.get("text"), ""));
                }
            }
            case "display_data" -> {
                if (execId >= 0) {
                    listener.onDisplay(execId, Json.map(c.get("data")), displayId(c));
                }
            }
            case "update_display_data" -> listener.onUpdateDisplay(displayId(c), Json.map(c.get("data")));
            case "execute_result" -> {
                if (execId >= 0) {
                    listener.onResult(execId, Json.integer(c.get("execution_count"), 0), Json.map(c.get("data")));
                }
            }
            case "error" -> {
                if (execId >= 0) {
                    List<String> tb = new ArrayList<>();
                    for (Object line : Json.list(c.get("traceback"))) {
                        tb.add(String.valueOf(line));
                    }
                    listener.onError(execId, Json.string(c.get("ename"), ""), Json.string(c.get("evalue"), ""), tb);
                }
            }
            case "clear_output" -> {
                if (execId >= 0) {
                    listener.onClearOutput(execId, Boolean.TRUE.equals(c.get("wait")));
                }
            }
            default -> {
                // comm messages and the like: no widget support in this notebook
            }
        }
    }

    private static String displayId(Map<String, Object> content) {
        Object id = Json.map(content.get("transient")).get("display_id");
        return id == null ? null : String.valueOf(id);
    }

    private void onReply(JupyterSession.Message msg) {
        String parentId = msg.parentId();
        Pending p = pending.get(parentId);
        trace("shell", msg, p);
        Map<String, Object> c = msg.content();
        switch (msg.type()) {
            case "kernel_info_reply" -> {
                Map<String, Object> lang = Json.map(c.get("language_info"));
                languageVersion = Json.string(lang.get("version"), "");
                String name = Json.string(lang.get("name"), spec.language());
                String impl = Json.string(c.get("implementation"), "");
                String implVersion = Json.string(c.get("implementation_version"), "");
                description = capitalize(name) + (languageVersion.isEmpty() ? "" : " " + languageVersion)
                            + (impl.isEmpty() ? "" : " · " + impl + (implVersion.isEmpty() ? "" : " " + implVersion));
                infoReplied = true;
                pending.remove(parentId);
            }
            case "execute_reply" -> {
                if (p == null) {
                    return;
                }
                if (p.kind == Kind.EXECUTE) {
                    if (c.get("execution_count") instanceof Number n && !"aborted".equals(c.get("status"))) {
                        p.count = n.intValue();
                    }
                    // len? and %pdoc answer with a "page" payload, which JupyterLab opens in
                    // a pager; here it becomes an output of the cell that asked.
                    for (Object entry : Json.list(c.get("payload"))) {
                        Map<String, Object> payload = Json.map(entry);
                        if ("page".equals(payload.get("source"))) {
                            listener.onDisplay(p.id, Json.map(payload.get("data")), null);
                        }
                    }
                    p.replied = true;
                    maybeFinish(parentId, p);
                } else if (p.kind == Kind.EFFECT) {
                    pending.remove(parentId);
                    if (!"ok".equals(c.get("status"))) {
                        AppLogger.warn("Jupyter kernel: " + Json.string(c.get("ename"), "") + " " + Json.string(c.get("evalue"), "")
                                       + " in Sphere's namespace update");
                    }
                } else {
                    pending.remove(parentId);
                    String json = userExpression(c);
                    if (p.kind == Kind.VARIABLES) {
                        listener.onVariables(p.id, items(Json.list(Json.object("{\"v\":" + json + "}").get("v"))));
                    } else if (p.kind == Kind.PEEK) {
                        listener.onPeek(p.id, items(Json.list(Json.object("{\"v\":" + json + "}").get("v"))));
                    } else {
                        listener.onAnalysis(p.id, items(Json.list(Json.object("{\"v\":" + json + "}").get("v"))));
                    }
                }
            }
            case "complete_reply" -> {
                if (p == null) {
                    return;
                }
                pending.remove(parentId);
                String code = p.text == null ? "" : p.text;
                List<String> matches = new ArrayList<>();
                for (Object m : Json.list(c.get("matches"))) {
                    matches.add(String.valueOf(m));
                }
                List<String> types = new ArrayList<>(Collections.nCopies(matches.size(), ""));
                List<Object> typed = Json.list(Json.map(c.get("metadata")).get("_jupyter_types_experimental"));
                if (typed.size() == matches.size()) {
                    for (int i = 0; i < typed.size(); i++) {
                        types.set(i, Json.string(Json.map(typed.get(i)).get("type"), ""));
                    }
                }
                listener.onCompletion(p.id, javaOffset(code, Json.integer(c.get("cursor_start"), 0)),
                                      javaOffset(code, Json.integer(c.get("cursor_end"), 0)), matches, types);
            }
            case "inspect_reply" -> {
                if (p != null) {
                    pending.remove(parentId);
                    listener.onInspect(p.id, Boolean.TRUE.equals(c.get("found")), Json.map(c.get("data")));
                }
            }
            case "usage_reply" -> {
                usagePending = null;
                usageMisses = 0;
                if (p != null) {
                    pending.remove(parentId);
                    listener.onUsage(p.id, c);
                }
            }
            default -> pending.remove(parentId);
        }
    }

    /** The JSON the tools module returned, as its text/plain: the module prints raw JSON there. */
    private static String userExpression(Map<String, Object> content) {
        Map<String, Object> value = Json.map(Json.map(content.get("user_expressions")).get("sphere"));
        if (!"ok".equals(value.get("status"))) {
            AppLogger.warn("Jupyter kernel: Sphere's namespace query failed: "
                           + Json.string(value.get("ename"), "") + " " + Json.string(value.get("evalue"), ""));
            return "[]";
        }
        String text = Json.string(Json.map(value.get("data")).get("text/plain"), "[]").trim();
        return text.startsWith("[") ? text : "[]";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(List<Object> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    private void onStdin(JupyterSession.Message msg) {
        if (!"input_request".equals(msg.type())) {
            return;
        }
        inputRequestHeader = msg.header();
        Pending p = pending.get(msg.parentId());
        listener.onInputRequest(p == null ? busyWith : p.id, Json.string(msg.content().get("prompt"), ""));
    }

    /** A cell is done when its reply and its idle status are both in, whichever comes first. */
    private void maybeFinish(String msgId, Pending p) {
        if (p.kind != Kind.EXECUTE) {
            return;
        }
        synchronized (p) {
            if (p.finished || !p.replied || !p.idle) {
                return;
            }
            p.finished = true;
        }
        pending.remove(msgId);
        recent.put(msgId, p.id);
        if (busyWith == p.id) {
            busyWith = -1;
        }
        listener.onDone(p.id, p.count, (System.nanoTime() - p.started) / 1_000_000L);
    }

    // ---- The bootstrap's side channel --------------------------------------

    private boolean side(Map<String, Object> message) {
        BufferedWriter out = sideChannel;
        if (out == null) {
            return false;
        }
        synchronized (out) {
            try {
                out.write(Json.encode(message));
                out.write("\n");
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    private void readSideChannel(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("{\"t\":")) {
                    Map<String, Object> event = Json.object(line);
                    switch (Json.string(event.get("t"), "")) {
                        case "shm_alloc" -> answerShmAlloc(event);
                        case "shm_release" -> answerShmRelease(event);
                        default -> { }
                    }
                    continue;
                }
                // What C code writes on the process's stdout: Jupyter on Windows loses it, the cell shows it.
                int busy = busyWith;
                if (busy >= 0) {
                    listener.onStream(busy, false, line + "\n");
                }
            }
        } catch (IOException closed) {
            // the process ended
        }
    }

    private void readErrors(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                synchronized (stderrTail) {
                    stderrTail.addLast(line);
                    while (stderrTail.size() > STDERR_TAIL) {
                        stderrTail.removeFirst();
                    }
                }
                int busy = busyWith;
                if (busy >= 0) {
                    listener.onStream(busy, true, line + "\n");
                }
            }
        } catch (IOException closed) {
            // the process ended
        }
    }

    private String lastError() {
        synchronized (stderrTail) {
            if (stderrTail.isEmpty()) {
                return "";
            }
            List<String> lines = new ArrayList<>(stderrTail);
            return "\n" + String.join("\n", lines.subList(Math.max(0, lines.size() - 8), lines.size()));
        }
    }

    private void answerShmAlloc(Map<String, Object> message) {
        final int rid = Json.integer(message.get("rid"), 0);
        final int bytes = Json.integer(message.get("bytes"), 0);
        final int dtype = Json.integer(message.get("dtype"), 0);
        final RootBackend backend = RootBackend.getInstance();
        long offset = 0L;
        int generation = 0;
        if (backend != null && bytes > 0) {
            RootBackend.HeapHandle handle = backend.acquireForClient(bytes, (short) dtype);
            offset = handle.payloadOffset();
            generation = handle.generation();
        }
        side(Map.of("op", "shm_reply", "rid", rid, "offset", offset, "generation", generation));
    }

    private void answerShmRelease(Map<String, Object> message) {
        final RootBackend backend = RootBackend.getInstance();
        if (backend != null) {
            Object offset = message.get("offset");
            backend.releaseForClient(offset instanceof Number n ? n.longValue() : 0L,
                                     Json.integer(message.get("generation"), 0));
        }
    }

    // ---- Helpers -----------------------------------------------------------

    /** The protocol counts cursor positions in code points; Java strings count UTF-16 units. */
    static int codePoints(String code, int javaOffset) {
        return code.codePointCount(0, Math.max(0, Math.min(javaOffset, code.length())));
    }

    static int javaOffset(String code, int codePoints) {
        int total = code.codePointCount(0, code.length());
        return code.offsetByCodePoints(0, Math.max(0, Math.min(codePoints, total)));
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int[] freePorts(int n) throws IOException {
        int[] ports = new int[n];
        List<ServerSocket> held = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                held.add(s);
                ports[i] = s.getLocalPort();
            }
        } finally {
            for (ServerSocket s : held) {
                s.close();
            }
        }
        return ports;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static void daemon(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
