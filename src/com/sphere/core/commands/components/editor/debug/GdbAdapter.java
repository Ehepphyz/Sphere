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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Debugs C, C++ and Fortran through gdb's machine interface. The source is compiled
 * with -g -O0 first, because a binary without line information cannot stop anywhere.
 *
 * gdb runs asynchronously (mi-async), so it takes commands while the program runs:
 * Pause interrupts it, and a breakpoint set, removed or given a condition while it
 * runs is applied by interrupting it, changing the breakpoints and letting it go on,
 * which the user never sees. Every command carries a token and its reply is routed
 * to whoever asked, so the stack, the variables of a frame, an evaluation and a
 * breakpoint number each reach their own caller.
 */
public final class GdbAdapter implements DebugAdapter {
    /** How long a debug build is given before it is stopped. */
    private static final int COMPILE_TIMEOUT_SECONDS = 300;
    /**
     * The code with which the GCC unwinder raises, on Windows, a C++ exception that
     * nothing catches; gdb reports it as an unknown signal in RaiseException.
     */
    private static final String GCC_EXCEPTION = "0x20474343";

    private final SettingsManager settings;
    private final AtomicInteger token = new AtomicInteger(1);
    /** Replies awaited, by the token of the command that asked. */
    private final Map<Integer, Consumer<MiParser.Record>> replies = new ConcurrentHashMap<>();

    private Process gdb;
    private BufferedWriter toGdb;
    private Listener listener;
    private volatile State state = State.IDLE;
    private File sourceFile;
    private volatile String threadId = "1";

    /** The breakpoints gdb holds, by the model line they stand for. Guarded by this. */
    private final Map<Integer, Installed> installed = new LinkedHashMap<>();
    /** A line gdb bound to a later one, until the model follows: requested to effective. Guarded by this. */
    private final Map<Integer, Integer> aliases = new LinkedHashMap<>();
    /** Logpoints by gdb number: hit, they print and the program goes on. */
    private final Map<String, String> logpoints = new ConcurrentHashMap<>();
    private BreakpointModel model;

    private volatile ExceptionMode exceptions = ExceptionMode.UNCAUGHT;
    /** The -catch-throw catchpoint, set only to stop on every exception. */
    private volatile String catchNumber;
    private volatile boolean catchRequested;

    /** Breakpoints changed while the program ran: gdb takes them at the next stop. */
    private volatile boolean syncWanted;
    /** Stopped by us to change the breakpoints: they are changed and the program goes on. */
    private volatile boolean interruptSent;
    /** Stopped because Pause was pressed. */
    private volatile boolean userPause;
    /** gdb's last log line, which names an unknown exception before the stop that follows it. */
    private volatile String lastLog = "";
    private volatile List<StackFrame> stack = List.of();

    private record Installed(String number, BreakpointModel.Spec spec) {
    }

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
        this.model = breakpoints;
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

        // Asynchronous, so Pause and breakpoints set while the program runs reach it;
        // pretty printers, so a std::vector reads as its elements rather than its guts.
        send("-gdb-set mi-async on");
        send("-gdb-set confirm off");
        // gdb colours its messages for a terminal; in a panel the escapes would show as text.
        send("-gdb-set style enabled off");
        send("-enable-pretty-printing");
        sync();
        if (arguments != null && !arguments.isEmpty()) {
            send("-exec-arguments " + String.join(" ", arguments));
        }
        send("-exec-run", r -> {
            if ("error".equals(r.klass)) {
                String msg = r.string("msg");
                fail("The program did not start: " + (msg == null ? "gdb refused to run it" : msg));
            }
        });
        setState(State.RUNNING);
    }

    /**
     * Builds the debug binary next to the source. Optimization is turned off so the
     * line the debugger stops on is the line that was written.
     */
    private File compile(File source) throws IOException, InterruptedException {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        File binary = new File(source.getAbsoluteFile().getParentFile(),
                               base + "_debug" + (windows ? ".exe" : ""));

        // The compiler follows the source, so gdb debugs a Fortran program the
        // same way it debugs a C++ one: the debug tables are what it reads, and
        // gfortran writes them just as g++ does.
        final String key;
        final String fallback;
        if (name.endsWith(".c")) {
            key = "GCC_DIR";
            fallback = "gcc";
        } else if (isFortran(name)) {
            key = "ENV_FC";
            fallback = "gfortran";
        } else {
            key = "GPP_DIR";
            fallback = "g++";
        }
        String compiler = settings == null ? fallback : settings.resolveTool(key, fallback);
        if (compiler == null) {
            fail("No compiler: set " + key + " in settings.conf.");
            return null;
        }

        output("Compiling " + name + " with -g -O0 ...", false);
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
        // A compiler that wedges -- a stuck network include path, a hung WSL
        // call -- would otherwise hold this thread for the rest of the session.
        if (!process.waitFor(COMPILE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("The compiler did not finish in " + COMPILE_TIMEOUT_SECONDS
                 + " s and was stopped.");
            return null;
        }
        if (process.exitValue() != 0) {
            fail("Compilation failed:\n" + output);
            return null;
        }
        return binary;
    }

    /** The suffixes gfortran takes, free form and fixed form alike. */
    static boolean isFortran(String name) {
        final String lower = name.toLowerCase();
        return lower.endsWith(".f90") || lower.endsWith(".f95")
            || lower.endsWith(".f03") || lower.endsWith(".f08")
            || lower.endsWith(".f") || lower.endsWith(".for")
            || lower.endsWith(".ftn");
    }

    private volatile int sourceLines = -1;

    /** Lines of the source, so a breakpoint moving to the next line with code stops at its end. */
    private int lineCount() {
        if (sourceLines < 0) {
            try (java.util.stream.Stream<String> lines =
                     java.nio.file.Files.lines(sourceFile.toPath(), StandardCharsets.ISO_8859_1)) {
                sourceLines = (int) lines.count();
            } catch (IOException | java.io.UncheckedIOException ex) {
                sourceLines = Integer.MAX_VALUE;
            }
        }
        return sourceLines;
    }

    private boolean isCpp() {
        if (sourceFile == null) {
            return false;
        }
        String n = sourceFile.getName();
        String lower = n.toLowerCase();
        return lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".cxx")
            || lower.endsWith(".c++") || n.endsWith(".C");
    }

    // ---- Commands ----------------------------------------------------------

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
        send("-exec-continue");
    }

    @Override
    public void stepOver() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("-exec-next --thread " + threadId);
    }

    @Override
    public void stepInto() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        send("-exec-step --thread " + threadId);
    }

    @Override
    public void stepOut() {
        if (state != State.PAUSED) {
            return;
        }
        setState(State.RUNNING);
        // From a frame without source (stopped in a library), finish leaves to the caller with source.
        send("-exec-finish --thread " + threadId, r -> {
            if ("error".equals(r.klass)) {
                output(r.string("msg"), true);
                setState(State.PAUSED);
            }
        });
    }

    @Override
    public void pause() {
        if (state == State.RUNNING) {
            userPause = true;
            send("-exec-interrupt");
        }
    }

    @Override
    public void runToLine(int line) {
        if (state != State.PAUSED || sourceFile == null || line < 1) {
            return;
        }
        send("-break-insert -t " + quote(sourceFile.getName() + ":" + line), r -> {
            if ("error".equals(r.klass)) {
                output("Cannot run to line " + line + ": " + r.string("msg"), true);
                return;
            }
            setState(State.RUNNING);
            send("-exec-continue");
        });
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
        replies.clear();
        setState(State.TERMINATED);
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
        evaluateNow(expression, frame, reply);
    }

    private void evaluateNow(String expression, int frame, BiConsumer<Boolean, String> reply) {
        send("-data-evaluate-expression --thread " + threadId + " --frame " + Math.max(0, frame) + " "
             + quote(expression), r -> {
            if ("error".equals(r.klass)) {
                String msg = r.string("msg");
                reply.accept(false, msg == null ? "gdb could not evaluate it" : msg);
            } else {
                String value = r.string("value");
                reply.accept(true, value == null ? "" : value);
            }
        });
    }

    @Override
    public void selectFrame(int index) {
        if (state != State.PAUSED || index < 0 || index >= stack.size()) {
            return;
        }
        send("-stack-list-variables --thread " + threadId + " --frame " + index + " --all-values", r -> {
            if (listener != null && !"error".equals(r.klass)) {
                listener.frameSelected(index, variablesOf(r));
            }
        });
    }

    @Override
    public void setExceptionMode(ExceptionMode mode) {
        exceptions = mode == null ? ExceptionMode.UNCAUGHT : mode;
        syncBreakpoints(model);
    }

    @Override
    public void syncBreakpoints(BreakpointModel breakpoints) {
        if (breakpoints != null) {
            model = breakpoints;
        }
        if (gdb == null || !gdb.isAlive()) {
            return;
        }
        if (state == State.RUNNING) {
            // gdb changes breakpoints in a stopped program: stop it, change them, go on.
            syncWanted = true;
            if (!interruptSent) {
                interruptSent = true;
                send("-exec-interrupt", r -> {
                    if ("error".equals(r.klass)) {
                        interruptSent = false;      // not running yet: the next stop takes them
                    }
                });
            }
            return;
        }
        syncWanted = false;
        sync();
    }

    /** Kept for callers of the former, line-by-line synchronization. */
    public void syncBreakpoint(int line, boolean enabled) {
        syncBreakpoints(model);
    }

    /**
     * Brings gdb's breakpoints in line with the model: removes the ones gone or
     * changed (by number, the only way gdb deletes one), inserts the new ones with
     * their condition and hit count, and sets or clears the exception catchpoint.
     */
    private synchronized void sync() {
        if (model == null || sourceFile == null) {
            return;
        }
        Map<Integer, BreakpointModel.Spec> wanted = new LinkedHashMap<>();
        for (int line : model.lines()) {
            if (model.isEnabled(line)) {
                wanted.put(line, model.spec(line));
            }
        }
        // A requested line gdb moved counts as installed at the line it moved to.
        Map<Integer, BreakpointModel.Spec> wantedInstalled = new LinkedHashMap<>();
        for (Map.Entry<Integer, BreakpointModel.Spec> w : wanted.entrySet()) {
            Integer moved = aliases.get(w.getKey());
            wantedInstalled.put(moved != null && installed.containsKey(moved) && !wanted.containsKey(moved)
                                ? moved : w.getKey(), w.getValue());
        }
        for (Iterator<Map.Entry<Integer, Installed>> it = installed.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Installed> e = it.next();
            BreakpointModel.Spec w = wantedInstalled.get(e.getKey());
            if (w == null || !w.equals(e.getValue().spec())) {
                String number = e.getValue().number();
                if (number != null && !number.isEmpty()) {
                    send("-break-delete " + number);
                    logpoints.remove(number);
                }
                it.remove();
            }
        }
        aliases.values().removeIf(effective -> !installed.containsKey(effective));
        for (Map.Entry<Integer, BreakpointModel.Spec> w : wantedInstalled.entrySet()) {
            if (!installed.containsKey(w.getKey())) {
                insert(w.getKey(), w.getValue());
            }
        }

        boolean wantCatch = exceptions == ExceptionMode.ALL && isCpp();
        if (wantCatch && catchNumber == null && !catchRequested) {
            catchRequested = true;
            send("-catch-throw", r -> {
                catchRequested = false;
                Map<String, Object> bkpt = r.tuple("bkpt");
                if (bkpt != null && bkpt.get("number") != null) {
                    catchNumber = bkpt.get("number").toString();
                }
            });
        } else if (!wantCatch && catchNumber != null) {
            send("-break-delete " + catchNumber);
            catchNumber = null;
        }
    }

    private void insert(int line, BreakpointModel.Spec spec) {
        installed.put(line, new Installed(null, spec));
        insertAt(line, spec, line, 0);
    }

    /** Asks gdb for a breakpoint standing for the model's line, at a source line that may be a later one. */
    private void insertAt(int line, BreakpointModel.Spec spec, int at, int attempt) {
        StringBuilder cmd = new StringBuilder("-break-insert");
        if (spec.condition() != null) {
            cmd.append(" -c ").append(quote(spec.condition()));
        }
        if (spec.hitCount() > 1) {
            cmd.append(" -i ").append(spec.hitCount() - 1);
        }
        cmd.append(' ').append(quote(sourceFile.getName() + ":" + at));
        send(cmd.toString(), r -> inserted(line, spec, at, attempt, r));
    }

    /**
     * A breakpoint asked for on a blank line or a declaration is bound to the next
     * line that carries code. Left unsaid, the dot sits where nothing ever stops.
     * gdb does it inside a function; between two functions it refuses the line, and
     * the next lines are tried until one carries code, as the Python side does.
     */
    private void inserted(int line, BreakpointModel.Spec spec, int at, int attempt, MiParser.Record r) {
        int moveTo = -1;
        synchronized (this) {
            Installed now = installed.get(line);
            if ("error".equals(r.klass)) {
                String msg = r.string("msg");
                boolean stillWanted = now != null && now.number() == null && spec.equals(now.spec());
                if (stillWanted && msg != null && attempt < 24
                        && (msg.startsWith("No compiled code for line") || msg.startsWith("No line "))
                        && at < lineCount()) {
                    insertAt(line, spec, at + 1, attempt + 1);
                    return;
                }
                if (stillWanted) {
                    // Remembered as failed, so the next change does not ask again in vain.
                    installed.put(line, new Installed("", spec));
                }
                output("The breakpoint on line " + line + " was not set: " + msg, true);
                return;
            }
            Map<String, Object> bkpt = r.tuple("bkpt");
            String number = bkpt == null || bkpt.get("number") == null ? null : bkpt.get("number").toString();
            if (number == null) {
                return;
            }
            if (now == null || now.number() != null || !spec.equals(now.spec())) {
                // The model changed while gdb was answering: this one is no longer wanted.
                send("-break-delete " + number);
                return;
            }
            if (spec.isLogpoint()) {
                logpoints.put(number, spec.logMessage());
            }
            int effective = parseInt(bkpt.get("line"));
            if (effective > 0 && effective != line && !installed.containsKey(effective)) {
                installed.remove(line);
                installed.put(effective, new Installed(number, spec));
                aliases.put(line, effective);
                moveTo = effective;
            } else {
                installed.put(line, new Installed(number, spec));
            }
        }
        if (moveTo > 0 && listener != null) {
            listener.breakpointMoved(line, moveTo);
        }
    }

    private void send(String command) {
        send(command, null);
    }

    private synchronized void send(String command, Consumer<MiParser.Record> onReply) {
        if (toGdb == null) {
            return;
        }
        int t = token.getAndIncrement();
        if (onReply != null) {
            replies.put(t, onReply);
        }
        try {
            toGdb.write(t + command);
            toGdb.write("\n");
            toGdb.flush();
        } catch (IOException ex) {
            replies.remove(t);
            fail("gdb stopped accepting commands.");
        }
    }

    /** An MI argument as a C string, so spaces, quotes and backslashes reach gdb whole. */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---- Reading -----------------------------------------------------------

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                gdb.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(MiParser.parse(line));
                } catch (RuntimeException ex) {
                    // one record the parser misread must not end the session
                    output("(gdb output not understood: " + ex.getMessage() + ")", true);
                }
            }
        } catch (IOException ignored) {
            // pipe closed on exit
        }
        setState(State.TERMINATED);
    }

    private void handle(MiParser.Record record) {
        switch (record.kind) {
            case PROGRAM, TARGET -> output(record.text, false);
            case LOG -> lastLog = record.text == null ? "" : record.text;
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
        Consumer<MiParser.Record> reply = null;
        if (record.token != null) {
            try {
                reply = replies.remove(Integer.parseInt(record.token));
            } catch (NumberFormatException ignored) {
                // not one of ours
            }
        }
        if (reply != null) {
            reply.accept(record);
            return;
        }
        if ("error".equals(record.klass)) {
            String message = record.string("msg");
            output(message == null ? "gdb error" : message, true);
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
        String thread = record.string("thread-id");
        if (thread != null && !thread.isEmpty()) {
            threadId = thread;
        }
        String reason = record.string("reason");
        if (reason != null && reason.startsWith("exited")) {
            output(exitMessage(record), false);
            terminate();
            return;
        }
        String signal = record.string("signal-name");
        boolean signalled = "signal-received".equals(reason);
        boolean interruption = signalled && ("0".equals(signal) || "SIGINT".equals(signal) || "SIGTRAP".equals(signal));
        if (interruptSent && interruption && !userPause) {
            // Our own stop, to change breakpoints: changed, and the program goes on unseen.
            interruptSent = false;
            syncWanted = false;
            sync();
            send("-exec-continue");
            return;
        }
        interruptSent = false;
        if (syncWanted) {
            syncWanted = false;
            sync();
        }
        boolean cppUncaught = signalled && "?".equals(signal) && lastLog.contains(GCC_EXCEPTION);
        if (cppUncaught && exceptions == ExceptionMode.NEVER) {
            send("-exec-continue");                 // let it end the program, as it would undebugged
            return;
        }
        String bkptno = record.string("bkptno");
        if ("breakpoint-hit".equals(reason) && bkptno != null && logpoints.containsKey(bkptno)) {
            log(logpoints.get(bkptno));
            return;
        }

        String text;
        boolean problem = false;
        if (userPause && interruption) {
            text = "Paused";
        } else if ("breakpoint-hit".equals(reason)) {
            if (bkptno != null && bkptno.equals(catchNumber)) {
                text = "C++ exception thrown";
                problem = true;
            } else if ("del".equals(record.string("disp"))) {
                text = "Reached the cursor";
            } else {
                text = "Breakpoint";
            }
        } else if ("end-stepping-range".equals(reason)) {
            text = "Step";
        } else if ("function-finished".equals(reason)) {
            String value = record.string("return-value");
            text = value == null ? "Stepped out" : "Stepped out, returned " + value;
        } else if ("location-reached".equals(reason)) {
            text = "Reached the cursor";
        } else if (cppUncaught) {
            text = "Uncaught C++ exception";
            problem = true;
        } else if (signalled) {
            String meaning = record.string("signal-meaning");
            if ("?".equals(signal)) {
                int at = lastLog.indexOf("exception 0x");
                text = "Windows exception" + (at >= 0 ? " " + lastLog.substring(at + 10).split("\\s")[0] : "");
            } else {
                text = "Signal " + signal + (meaning == null || meaning.isEmpty() ? "" : ": " + meaning);
            }
            problem = true;
        } else {
            text = reason == null ? "Paused" : reason.replace('-', ' ');
        }
        userPause = false;
        setState(State.PAUSED);
        final String why = text;
        final boolean bad = problem;
        // The frame arrives with the stop; the stack and variables are asked for
        // right away so the panel is filled by the time the user looks at it.
        send("-stack-list-frames --thread " + threadId, r -> framesListed(r, why, bad));
    }

    private void framesListed(MiParser.Record r, String why, boolean problem) {
        if ("error".equals(r.klass)) {
            output(r.string("msg"), true);
            return;
        }
        List<StackFrame> frames = new ArrayList<>();
        for (Object entry : r.list("stack")) {
            if (entry instanceof Map<?, ?> frame) {
                frames.add(toFrame(frame));
            }
        }
        if (frames.isEmpty()) {
            return;
        }
        stack = List.copyOf(frames);
        // Stopped inside a library (a signal, an exception, Pause): the first frame of
        // the program's own source is where the user looks, the rest stay in the stack.
        // A header of the standard library has source too, but it is not the program.
        int selected = -1;
        for (int i = 0; i < frames.size() && selected < 0; i++) {
            if (isSessionSource(frames.get(i))) {
                selected = i;
            }
        }
        for (int i = 0; i < frames.size() && selected < 0; i++) {
            if (frames.get(i).hasSource()) {
                selected = i;
            }
        }
        selected = Math.max(0, selected);
        final int sel = selected;
        send("-stack-list-variables --thread " + threadId + " --frame " + sel + " --all-values", v -> {
            Map<String, String> variables = "error".equals(v.klass) ? new LinkedHashMap<>() : variablesOf(v);
            if (listener != null) {
                listener.stopReason(why, problem);
                listener.paused(frames.get(sel), new ArrayList<>(frames), variables);
            }
        });
    }

    private boolean isSessionSource(StackFrame frame) {
        if (frame.file == null || sourceFile == null || frame.line <= 0) {
            return false;
        }
        try {
            return frame.file.getCanonicalFile().equals(sourceFile.getCanonicalFile());
        } catch (IOException ex) {
            return frame.file.getName().equals(sourceFile.getName());
        }
    }

    private static Map<String, String> variablesOf(MiParser.Record record) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (Object entry : record.list("variables")) {
            if (entry instanceof Map<?, ?> variable) {
                Object name = variable.get("name");
                Object value = variable.get("value");
                if (name != null) {
                    variables.put(name.toString(), value == null ? "" : value.toString());
                }
            }
        }
        return variables;
    }

    /** A logpoint hit: its {expressions} evaluated one after the other, the line printed, the program resumed. */
    private void log(String template) {
        List<String[]> parts = parseTemplate(template);
        StringBuilder out = new StringBuilder();
        logPart(parts, 0, out);
    }

    private void logPart(List<String[]> parts, int index, StringBuilder out) {
        if (index >= parts.size()) {
            output("» " + out, false);
            send("-exec-continue");
            return;
        }
        String[] part = parts.get(index);
        if (part[0] == null) {
            out.append(part[1]);
            logPart(parts, index + 1, out);
            return;
        }
        evaluateNow(part[0], 0, (ok, value) -> {
            out.append(ok ? value : "<" + value + ">");
            logPart(parts, index + 1, out);
        });
    }

    /**
     * A log message in pieces: {null, text} for literal text, {expression, null} for
     * what stands in braces. "{{" and "}}" are literal braces.
     */
    public static List<String[]> parseTemplate(String template) {
        List<String[]> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                text.append('{');
                i += 2;
            } else if (c == '}' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
                text.append('}');
                i += 2;
            } else if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close < 0) {
                    text.append(template.substring(i));
                    break;
                }
                if (text.length() > 0) {
                    parts.add(new String[] { null, text.toString() });
                    text.setLength(0);
                }
                String expr = template.substring(i + 1, close).trim();
                if (!expr.isEmpty()) {
                    parts.add(new String[] { expr, null });
                }
                i = close + 1;
            } else {
                text.append(c);
                i++;
            }
        }
        if (text.length() > 0) {
            parts.add(new String[] { null, text.toString() });
        }
        return parts;
    }

    private static String exitMessage(MiParser.Record record) {
        String reason = record.string("reason");
        if ("exited-signalled".equals(reason)) {
            String name = record.string("signal-name");
            String meaning = record.string("signal-meaning");
            return "[program ended by " + (name == null ? "a signal" : name)
                   + (meaning == null || meaning.isEmpty() ? "" : ": " + meaning) + "]";
        }
        String code = record.string("exit-code");
        if (code == null) {
            return "[program exited normally]";
        }
        // gdb writes the code in octal when it starts with a 0.
        long value;
        try {
            value = code.length() > 1 && code.startsWith("0") ? Long.parseLong(code, 8) : Long.parseLong(code);
        } catch (NumberFormatException ex) {
            return "[program exited with code " + code + "]";
        }
        if (value == 0x20474343L) {
            return "[program ended on an uncaught C++ exception (exit code 0x20474343)]";
        }
        return "[program exited with code " + value + "]";
    }

    private static StackFrame toFrame(Map<?, ?> frame) {
        Object full = frame.get("fullname");
        Object file = full != null ? full : frame.get("file");
        Object func = frame.get("func");
        int line = parseInt(frame.get("line"));
        return new StackFrame(func == null ? "?" : func.toString(),
                              file == null ? null : new File(file.toString()), line);
    }

    private static int parseInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
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
