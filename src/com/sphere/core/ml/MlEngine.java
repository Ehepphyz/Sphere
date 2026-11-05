package com.sphere.core.ml;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.geantbackend.G4Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Sphere's client of sphere_ml, its learning engine: a Python holding PyTorch, Keras (on TensorFlow, JAX or
 * PyTorch) and pyhf, started once and kept, spoken to in JSON lines as geant4-bridge is -- {"id", "cmd",
 * "args"} out; progress lines, then one reply, back -- with {"cancel": id} to stop a training after its batch.
 * One engine per interpreter: PyTorch may live in one environment and TensorFlow in WSL. What the frameworks
 * print goes to config/ml/sphere-ml.log; the engine stops when Sphere does (its standard input closes).
 */
public final class MlEngine implements AutoCloseable {

    /** A command failed in the engine. */
    public static final class EngineException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EngineException(String m) {
            super(m);
        }
    }

    private static final Map<String, MlEngine> ENGINES = new LinkedHashMap<>();

    private final Process process;
    private final MlLocator.Interpreter interpreter;
    private final BufferedWriter stdin;
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, BiConsumer<Double, String>> progress = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final String description;
    private final Map<String, String> chosen = MlLocator.chosen();
    private volatile boolean closed;

    private MlEngine(Process p, MlLocator.Interpreter i, String description) {
        this.process = p;
        this.interpreter = i;
        this.description = description;
        this.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Where the engines keep their log. */
    public static Path folder() {
        return Path.of("config", "ml").toAbsolutePath().normalize();
    }

    /**
     * The engine of an interpreter, started when it is not running -- and started again, when idle, if
     * settings.conf has chosen otherwise since (a Keras backend is taken once per process).
     */
    public static synchronized MlEngine shared(MlLocator.Interpreter i) throws IOException {
        final MlEngine e = ENGINES.get(i.label());
        if (e != null && e.alive()) {
            if (e.chosen.equals(MlLocator.chosen()) || e.busy()) {
                return e;
            }
            e.close();
        }
        final MlEngine started = start(i);
        ENGINES.put(i.label(), started);
        return started;
    }

    public static synchronized Collection<MlEngine> running() {
        ENGINES.values().removeIf(e -> !e.alive());
        return new ArrayList<>(ENGINES.values());
    }

    public static synchronized void stopAll() {
        for (MlEngine e : ENGINES.values()) {
            e.close();
        }
        ENGINES.clear();
    }

    private static MlEngine start(MlLocator.Interpreter i) throws IOException {
        final Path lib = Bridge.libraries();
        final Path work = folder();
        Files.createDirectories(work);
        final Map<String, String> env = new HashMap<>();
        Bridge.environment(env);
        env.put("PYTHONUNBUFFERED", "1");
        env.put("TF_CPP_MIN_LOG_LEVEL", "2");
        env.putAll(MlLocator.chosen());
        final List<String> cmd;
        if (i.wsl()) {
            final Map<String, String> vars = new LinkedHashMap<>(MlLocator.chosen());
            vars.put("PYTHONPATH", MlLocator.toWsl(lib));
            vars.put("SPHERE_BRIDGE", MlLocator.toWsl(Bridge.folder()));
            vars.put("PYTHONUNBUFFERED", "1");
            vars.put("TF_CPP_MIN_LOG_LEVEL", "2");
            cmd = MlLocator.withEnvironment(i.command(), i.distro(), vars);
            cmd.add(MlLocator.toWsl(lib.resolve("sphere_ml.py")));
        } else {
            cmd = new ArrayList<>(i.command());
            cmd.add(lib.resolve("sphere_ml.py").toString());
        }
        cmd.add("--stdio");
        final ProcessBuilder pb = new ProcessBuilder(cmd).directory(work.toFile());
        pb.environment().putAll(env);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(work.resolve("sphere-ml.log").toFile()));
        final Process p = pb.start();
        final BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        final String first = out.readLine();
        if (first == null) {
            p.destroyForcibly();
            throw new IOException("sphere-ml did not start with " + i.label() + "; see " + work.resolve("sphere-ml.log"));
        }
        final MlEngine e = new MlEngine(p, i, G4Json.str(G4Json.parse(first), "engine", "sphere-ml"));
        final Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = out.readLine()) != null) {
                    e.deliver(line);
                }
            } catch (IOException ignored) {
                // the engine went away
            }
            e.failAll("sphere-ml stopped (" + i.label() + ")");
        }, "sphere-ml-reader");
        reader.setDaemon(true);
        reader.start();
        return e;
    }

    private static final java.util.regex.Pattern NON_FINITE = java.util.regex.Pattern.compile(
        "(?<=[:\\[,]\\s{0,8})-?(?:Infinity|NaN)(?=\\s*[,}\\]])");

    /**
     * JSON as Python writes it: its json module writes Infinity and NaN, which JSON has not and G4Json refuses
     * (a rejection with no background left is infinite). Read as null, where a value is expected.
     */
    public static Map<String, Object> json(String text) {
        try {
            return G4Json.object(text);
        } catch (RuntimeException e) {
            return G4Json.object(NON_FINITE.matcher(text).replaceAll("null"));
        }
    }

    @SuppressWarnings("unchecked")
    private void deliver(String text) {
        final Map<String, Object> msg;
        try {
            msg = json(text);
        } catch (RuntimeException ex) {
            // a reply that cannot be read fails its request, rather than leaving it waiting for ever
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(text);
            final CompletableFuture<Map<String, Object>> f = m.find() ? pending.remove(Integer.parseInt(m.group(1))) : null;
            if (f != null) {
                f.completeExceptionally(new EngineException("unreadable reply (" + ex.getMessage() + "): "
                    + text.substring(0, Math.min(200, text.length()))));
            }
            return;
        }
        final int id = (int) G4Json.num(msg, "id", -1);
        if (msg.containsKey("progress")) {
            final BiConsumer<Double, String> p = progress.get(id);
            if (p != null) {
                p.accept(G4Json.num(msg, "progress", 0), G4Json.str(msg, "message", ""));
            }
            return;
        }
        final CompletableFuture<Map<String, Object>> f = pending.remove(id);
        progress.remove(id);
        if (f == null) {
            return;
        }
        if (Boolean.TRUE.equals(msg.get("ok"))) {
            final Object r = msg.get("result");
            f.complete(r instanceof Map ? (Map<String, Object>) r : Map.of("value", r == null ? "" : r));
        } else {
            f.completeExceptionally(new EngineException(G4Json.str(msg, "error", "sphere-ml failed")));
        }
    }

    private void failAll(String why) {
        for (CompletableFuture<Map<String, Object>> f : pending.values()) {
            f.completeExceptionally(new EngineException(why));
        }
        pending.clear();
    }

    /** Sends a command; the future completes with its result, or an EngineException. */
    public CompletableFuture<Map<String, Object>> call(String cmd, Map<String, Object> args,
                                                       BiConsumer<Double, String> onProgress) {
        final int id = ids.incrementAndGet();
        final CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (!alive()) {
            f.completeExceptionally(new EngineException("sphere-ml is not running"));
            return f;
        }
        pending.put(id, f);
        if (onProgress != null) {
            progress.put(id, onProgress);
        }
        lastId = id;
        final Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", id);
        req.put("cmd", cmd);
        req.put("args", args == null ? Map.of() : args);
        try {
            send(G4Json.write(req));
        } catch (IOException e) {
            pending.remove(id);
            f.completeExceptionally(new EngineException("cannot reach sphere-ml: " + e.getMessage()));
        }
        return f;
    }

    public CompletableFuture<Map<String, Object>> call(String cmd, Map<String, Object> args) {
        return call(cmd, args, null);
    }

    private volatile int lastId;

    /** Stops the command running now (a training ends after its current batch, keeping its best epoch). */
    public void cancel() {
        try {
            send("{\"cancel\":" + lastId + "}");
        } catch (IOException ignored) {
            // gone already
        }
    }

    private synchronized void send(String line) throws IOException {
        stdin.write(line);
        stdin.write('\n');
        stdin.flush();
    }

    public boolean busy() {
        return !pending.isEmpty();
    }

    public boolean alive() {
        return !closed && process.isAlive();
    }

    public String description() {
        return description;
    }

    public MlLocator.Interpreter interpreter() {
        return interpreter;
    }

    /** A file of this machine as the engine names it. */
    public String path(Path p) {
        return interpreter.path(p);
    }

    /** The last lines the engine wrote to its log: a framework's own messages when something failed. */
    public static String logTail(int lines) {
        try {
            final List<String> all = Files.readAllLines(folder().resolve("sphere-ml.log"), StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        try {
            if (process.isAlive()) {
                call("quit", Map.of()).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // stopped below anyway
        }
        closed = true;
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        failAll("sphere-ml closed");
    }
}
