package com.sphere.core.julia;

import com.sphere.components.variables.PythonProbe;
import com.sphere.components.variables.VariableSources;
import com.sphere.components.variables.VariableStore;
import com.sphere.components.variables.VariablesPanel;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Julia interpreter kept alive between commands.
 *
 * Julia is compiled as it runs and keeps its values in a heap of its own, so
 * there is nothing for a debugger built on debug tables to read, and nothing
 * left at all once the process ends. Holding the interpreter open is what makes
 * its variables visible: it is asked, as the Python kernel is.
 */
public final class JuliaSession implements VariableSources.Source {

    public static final String SOURCE = "julia";

    /** How long a command may take before the session is assumed lost. */
    private static final long ANSWER_TIMEOUT_MS = 600000;

    /** How long the interpreter is given to start. */
    private static final long START_TIMEOUT_MS = 120000;

    private static JuliaSession shared;

    private final SettingsManager settings;

    private Process process;
    private BufferedWriter toJulia;
    /**
     * The answers the interpreter gives, one per command. A queue that keeps
     * them rather than a hand-over: the sender now looks up between waits to see
     * whether the process died, and a hand-over made while it looked would be lost.
     */
    private final LinkedBlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private volatile boolean running;

    /**
     * What the interpreter was started with, before the driver: -t 4,
     * --project=., -O3. A session takes them at start only, so asking for other
     * ones means another session.
     */
    private List<String> flags = List.of();

    private JuliaSession(SettingsManager settings) {
        this.settings = settings;
    }

    public static synchronized JuliaSession instance(SettingsManager settings) {
        if (shared == null) {
            shared = new JuliaSession(settings);
        }
        return shared;
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    @Override
    public String name() {
        return SOURCE;
    }

    /** Asks the interpreter to write down what it holds, then reads it back. */
    @Override
    public void refresh() {
        if (isRunning()) {
            send("VARS", "");
        }
    }

    // ---- the session --------------------------------------------------------

    /** The flags the session runs, or will next start, with. */
    public synchronized List<String> flags() {
        return flags;
    }

    /**
     * Starts the session with these interpreter flags, as "::jul -t 4 [@ f.jl]"
     * and ":julia start -t 4" ask. A session already running with the same ones
     * is kept. One running with others cannot take them, since Julia reads its
     * flags when it starts, so it is restarted, and saying so matters: what it
     * held goes with it.
     */
    public synchronized void start(List<String> wanted) throws IOException {
        final List<String> asked = List.copyOf(wanted);
        if (isRunning()) {
            if (asked.equals(flags)) {
                return;
            }
            AppLogger.warn("Julia restarts with " + describe(asked) + " (it ran with "
                           + describe(flags) + "): the variables of the session are gone.");
            shutdown();
        }
        flags = asked;
        start();
    }

    private static String describe(List<String> flags) {
        return flags.isEmpty() ? "no flags" : String.join(" ", flags);
    }

    /** Starts the session, with the flags it last had, unless it is running. */
    public synchronized void start() throws IOException {
        if (isRunning()) {
            return;
        }
        final String executable = settings == null ? "julia"
                                : settings.resolveTool("JULIA_DIR", "julia");
        if (executable == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            throw new IOException(settings != null && settings.isDeclaredEmpty("JULIA_DIR")
                ? "JULIA_DIR is empty in settings.conf, which disables Julia."
                : "Julia was not found. Set JULIA_DIR in settings.conf.");
        }
        Path script = JuliaScript.materialize();

        // The user's flags after Sphere's own, so that one of theirs overrides
        // it (--startup-file=yes), and before the driver, where Julia would
        // otherwise hand them to the driver as its ARGS.
        final List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--startup-file=no");
        command.addAll(flags);
        command.add(script.toAbsolutePath().toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        final String folder = PythonProbe.folder();
        if (folder != null) {
            builder.environment().put(PythonProbe.FOLDER_VARIABLE, folder);
        }
        com.sphere.core.bridge.Bridge.environment(builder.environment());
        process = builder.start();
        toJulia = new BufferedWriter(new OutputStreamWriter(
            process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;

        Thread output = new Thread(this::readOutput, "sphere-julia-out");
        output.setDaemon(true);
        output.start();

        Thread errors = new Thread(this::readErrors, "sphere-julia-err");
        errors.setDaemon(true);
        errors.start();

        VariableSources.register(this);
        // Julia takes a moment to start; the first answer is what says it is up.
        if (!send("VARS", "", START_TIMEOUT_MS)) {
            // A flag Julia refuses ends it at once, its reason already on the
            // console. The next start is not made to fail the same way.
            final List<String> refused = flags;
            flags = List.of();
            shutdown();
            throw new IOException("Julia did not start"
                + (refused.isEmpty() ? "." : " with " + describe(refused)
                   + "; the next start uses no flags."));
        }
    }

    public synchronized void shutdown() {
        if (process == null) {
            running = false;
            return;
        }
        running = false;
        try {
            write("QUIT ");
        } catch (IOException ignored) {
            // The process is going away in any case.
        }
        Process live = process;
        process = null;
        VariableSources.unregister(SOURCE);
        VariableStore.clear(SOURCE);
        try {
            if (!live.waitFor(3, TimeUnit.SECONDS)) {
                live.destroyForcibly();
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            live.destroyForcibly();
        }
    }

    /** Runs a line or a block of Julia in the session's own Main. */
    public void run(String code) {
        if (code != null && !code.isBlank()) {
            send("RUN", code);
        }
    }

    /** Runs a file in the session, so what it defines stays afterwards. */
    public void runFile(File file) {
        runFile(file, java.util.List.of());
    }

    /**
     * Runs a file with arguments of its own, which reach it through ARGS.
     *
     * A session is not a fresh process, so ARGS has to be set for this run and
     * cleared afterwards rather than being what the interpreter was started with.
     */
    public void runFile(File file, java.util.List<String> arguments) {
        if (file == null) {
            return;
        }
        StringBuilder payload = new StringBuilder(file.getAbsolutePath());
        for (String argument : arguments) {
            payload.append('\n').append(argument);
        }
        send("FILE", payload.toString());
    }

    // ---- talking to it ------------------------------------------------------

    private void send(String operation, String payload) {
        send(operation, payload, ANSWER_TIMEOUT_MS);
    }

    /** Sends one command and waits for its answer; false when none came. */
    private boolean send(String operation, String payload, long timeoutMillis) {
        if (!isRunning()) {
            AppLogger.error("No Julia session. Start one with :julia start.");
            return false;
        }
        final long started = System.nanoTime();
        final Process live = process;
        try {
            answers.clear();                // drop anything a lost command left
            write(operation + " " + Base64.getEncoder().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8)));
            // Waited for in slices, so that an interpreter that died (a flag it
            // refused, a crash) is noticed at once rather than after ten minutes.
            final long deadline = System.nanoTime()
                                  + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            String answer = null;
            while (answer == null && System.nanoTime() < deadline) {
                answer = answers.poll(250, TimeUnit.MILLISECONDS);
                if (answer == null && live != null && !live.isAlive()) {
                    answer = answers.poll();
                    if (answer == null) {
                        AppLogger.error("Julia stopped (exit status " + live.exitValue() + ").");
                        running = false;
                        record(operation, payload, started, live.exitValue(), false);
                        return false;
                    }
                }
            }
            if (answer == null) {
                AppLogger.error("Julia did not answer in "
                                + (timeoutMillis / 1000) + " s.");
                record(operation, payload, started, -1, true);
                return false;
            }
            record(operation, payload, started, 0, false);
            VariablesPanel.instance().reread();
            return true;
        } catch (IOException unreachable) {
            AppLogger.error("Could not reach the Julia session: "
                            + unreachable.getMessage());
            running = false;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /**
     * Keeps what the session was asked to do, so a run can be compared with the
     * ones before it. The interpreter holds one process for the whole session, so
     * its memory belongs to the session rather than to any single command.
     */
    private void record(String operation, String payload, long started,
                        int exitCode, boolean timedOut) {
        if ("VARS".equals(operation)) {
            return;
        }
        final Process live = process;
        final String source = "FILE".equals(operation)
            ? payload.split("\n", 2)[0] : "<console>";
        com.sphere.core.telemetry.RunLog.add(
            com.sphere.core.telemetry.RunRecord.run("julia", source, "julia",
                java.util.List.of(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), exitCode,
                live == null ? com.sphere.core.telemetry.RunRecord.UNKNOWN
                             : com.sphere.core.telemetry.ProcessMemory.residentKilobytes(live.pid()),
                timedOut));
    }

    private void write(String line) throws IOException {
        BufferedWriter writer = toJulia;
        if (writer == null) {
            throw new IOException("the session is closed");
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    /**
     * Reads what the interpreter prints.
     *
     * A line that starts with the control character is the session's own answer
     * and belongs to no one else; everything else is the program's output and
     * goes to the console as any other program's does.
     */
    private void readOutput() {
        final Process live = process;
        if (live == null) {
            return;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                live.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == JuliaScript.CONTROL) {
                    answers.offer(line.substring(1));
                } else {
                    AppLogger.stream(line);
                }
            }
        } catch (IOException closed) {
            // The session ended; nothing to report beyond that.
        }
        running = false;
    }

    private void readErrors() {
        final Process live = process;
        if (live == null) {
            return;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                live.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                AppLogger.error(line);
            }
        } catch (IOException closed) {
            // The session ended; nothing to report beyond that.
        }
    }
}
