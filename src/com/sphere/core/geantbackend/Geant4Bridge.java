package com.sphere.core.geantbackend;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Sphere's client of geant4-bridge, the Geant4 engine: starts it, talks to it
 * and stops it with Sphere.
 *
 * Two transports carry the same JSON requests. The shared-memory channel
 * (G4ShmChannel) is the default: the engine and Sphere map one file under
 * geantbackend/ and exchange messages through lock-free rings, as Sphere does
 * with its ROOT engine -- but with its own region and its own code, so the
 * Geant4 engine runs without ROOT. JSON lines over the engine's stdin/stdout
 * serve a remote engine (WSL, ssh) or a region that cannot be mapped.
 */
public final class Geant4Bridge implements AutoCloseable {

    public enum Transport { SHM, STDIO }

    /** A command failed in the engine. */
    public static final class BridgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean restart;

        BridgeException(String m) {
            super(m);
            this.restart = m != null && m.startsWith("restart");
        }

        /** True when the engine asks to be restarted (new geometry or physics list after a run). */
        public boolean needsRestart() {
            return restart;
        }
    }

    private final Process process;
    private final Transport transport;
    private final Path workDir;
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, BiConsumer<Double, String>> progress = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private volatile Consumer<String> log = s -> { };
    private G4ShmChannel channel;
    private BufferedWriter stdin;
    private volatile boolean closed;
    private String description = "";

    private Geant4Bridge(Process p, Transport t, Path workDir) {
        this.process = p;
        this.transport = t;
        this.workDir = workDir;
    }

    /** How the engine names a file of this machine: the path itself, or inside WSL its /mnt/... form. */
    private volatile java.util.function.Function<Path, String> enginePaths = p -> p.toAbsolutePath().toString();

    public void mapPaths(java.util.function.Function<Path, String> f) {
        enginePaths = f;
    }

    /** A file of this machine as the engine reads it, for the paths sent in requests (load...). */
    public String enginePath(Path p) {
        return enginePaths.apply(p);
    }

    /**
     * Starts an engine and waits until it answers.
     *
     * @param command the program and any wrapper (e.g. "wsl", "-d", "Debian", "--", "/path/geant4-bridge")
     * @param env     environment additions (data sets, library path)
     */
    public static Geant4Bridge start(List<String> command, Map<String, String> env, Path workDir, Transport t,
                                     Consumer<String> log) throws IOException {
        return start(command, env, workDir, t, log, true);
    }

    /**
     * Same, saying whether the engine watches this process and stops with it. Inside WSL it must not: the
     * pid of a Windows process means nothing there, and the engine would stop at once. It stops when its
     * standard input closes instead.
     */
    public static Geant4Bridge start(List<String> command, Map<String, String> env, Path workDir, Transport t,
                                     Consumer<String> log, boolean watchParent) throws IOException {
        Files.createDirectories(workDir);
        final List<String> cmd = new ArrayList<>(command);
        final Path region = workDir.resolve("geant_backend.shm");
        if (t == Transport.SHM) {
            cmd.add("--shm");
            cmd.add(region.toAbsolutePath().toString());
            cmd.add("--size");
            cmd.add("64");
        } else {
            cmd.add("--stdio");
        }
        if (watchParent) {
            cmd.add("--parent-pid");
            cmd.add(Long.toString(ProcessHandle.current().pid()));
        }
        final ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        pb.environment().putAll(env);
        final Path logFile = workDir.resolve("geant4-bridge.log");
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        if (t == Transport.SHM) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        }
        final Process p = pb.start();
        final Geant4Bridge b = new Geant4Bridge(p, t, workDir);
        if (log != null) {
            b.log = log;
        }
        try {
            if (t == Transport.SHM) {
                b.attachShm(region, p);
            } else {
                b.attachStdio(p);
            }
        } catch (IOException | RuntimeException e) {
            p.destroyForcibly();
            throw e;
        }
        return b;
    }

    private void attachShm(Path region, Process p) throws IOException {
        final long deadline = System.currentTimeMillis() + 30_000;
        long sleep = 5;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                throw new IOException("geant4-bridge exited with code " + p.exitValue() + "; see "
                    + workDir.resolve("geant4-bridge.log"));
            }
            if (Files.exists(region)) {
                final G4ShmChannel ch = G4ShmChannel.attach(region);
                if (ch != null) {
                    // A region left by an earlier engine carries its pid: wait for ours
                    if (ch.enginePid() == p.pid() && ch.state() == G4ShmChannel.STATE_READY) {
                        channel = ch;
                        description = ch.description();
                        final Thread reader = new Thread(this::shmLoop, "geant4-bridge-shm");
                        reader.setDaemon(true);
                        reader.start();
                        return;
                    }
                    ch.close();
                }
            }
            sleep(sleep);
            sleep = Math.min(100, sleep * 2);
        }
        throw new IOException("geant4-bridge did not open its shared region within 30 s");
    }

    private void attachStdio(Process p) throws IOException {
        stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        final BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        final String first = out.readLine();
        if (first == null) {
            throw new IOException("geant4-bridge closed its output before answering; see "
                + workDir.resolve("geant4-bridge.log"));
        }
        description = G4Json.str(G4Json.parse(first), "engine", "geant4-bridge");
        final Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = out.readLine()) != null) {
                    deliver(line);
                }
            } catch (IOException ignored) {
                // the engine went away
            }
            failAll("geant4-bridge stopped");
        }, "geant4-bridge-stdio");
        reader.setDaemon(true);
        reader.start();
    }

    private void shmLoop() {
        int idle = 0;
        long lastBeatCheck = System.currentTimeMillis(), lastBeat = -1;
        while (!closed) {
            final G4ShmChannel.Message m;
            try {
                m = channel.poll();
            } catch (RuntimeException e) {
                log.accept("geant4-bridge channel error: " + e.getMessage());
                break;
            }
            if (m != null) {
                idle = 0;
                deliver(m.text());
                continue;
            }
            channel.clientBeat();
            if (!process.isAlive()) {
                break;
            }
            final long now = System.currentTimeMillis();
            if (now - lastBeatCheck > 5000) {
                final long beat = channel.engineBeat();
                if (beat == lastBeat && pending.isEmpty() && channel.state() != G4ShmChannel.STATE_BUSY) {
                    log.accept("geant4-bridge heartbeat is not moving");
                }
                lastBeat = beat;
                lastBeatCheck = now;
            }
            if (++idle < 500) {
                Thread.onSpinWait();
            } else if (idle < 5000) {
                Thread.yield();
            } else {
                sleep(idle < 50_000 ? 1 : 4);
            }
        }
        failAll("geant4-bridge stopped (exit " + (process.isAlive() ? "?" : String.valueOf(process.exitValue())) + ")");
    }

    @SuppressWarnings("unchecked")
    private void deliver(String text) {
        final Map<String, Object> msg;
        try {
            msg = G4Json.object(text);
        } catch (RuntimeException e) {
            log.accept("geant4-bridge: unreadable message " + text);
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
            f.completeExceptionally(new BridgeException(G4Json.str(msg, "error", "geant4-bridge failed")));
        }
    }

    private void failAll(String why) {
        for (Map.Entry<Integer, CompletableFuture<Map<String, Object>>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(new BridgeException(why));
        }
        pending.clear();
    }

    /** Sends a command; the future completes with the result object or a BridgeException. */
    public CompletableFuture<Map<String, Object>> call(String cmd, Map<String, Object> args,
                                                       BiConsumer<Double, String> onProgress) {
        final CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        if (closed || !process.isAlive()) {
            f.completeExceptionally(new BridgeException("geant4-bridge is not running"));
            return f;
        }
        final int id = ids.incrementAndGet();
        final Map<String, Object> req = new LinkedHashMap<>();
        req.put("id", id);
        req.put("cmd", cmd);
        req.put("args", args == null ? Map.of() : args);
        pending.put(id, f);
        if (onProgress != null) {
            progress.put(id, onProgress);
        }
        final String text = G4Json.write(req);
        try {
            if (transport == Transport.SHM) {
                final long deadline = System.currentTimeMillis() + 10_000;
                while (!channel.push(id, text)) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("the request ring stayed full");
                    }
                    sleep(1);
                }
            } else {
                synchronized (this) {
                    stdin.write(text);
                    stdin.write('\n');
                    stdin.flush();
                }
            }
        } catch (IOException | RuntimeException e) {
            pending.remove(id);
            progress.remove(id);
            f.completeExceptionally(new BridgeException("cannot send to geant4-bridge: " + e.getMessage()));
        }
        return f;
    }

    public CompletableFuture<Map<String, Object>> call(String cmd, Map<String, Object> args) {
        return call(cmd, args, null);
    }

    /** Asks the running command to stop early (a run ends after its current event). */
    public void cancel() {
        if (channel != null) {
            channel.requestCancel();
        }
    }

    public boolean alive() {
        return !closed && process.isAlive();
    }

    public String description() {
        return description;
    }

    public Transport transport() {
        return transport;
    }

    public void setLog(Consumer<String> l) {
        this.log = l == null ? s -> { } : l;
    }

    /** The last lines the engine wrote to its log. */
    public String logTail(int lines) {
        try {
            final List<String> all = Files.readAllLines(workDir.resolve("geant4-bridge.log"), StandardCharsets.UTF_8);
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
                call("quit", Map.of()).get(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // stopped below anyway
        }
        closed = true;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (channel != null) {
            channel.close();
        }
        failAll("geant4-bridge closed");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Appends to the engine's log, for what Sphere does around it. */
    void note(String line) {
        try {
            Files.writeString(workDir.resolve("geant4-bridge.log"), "[sphere] " + line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging is best effort
        }
    }
}
