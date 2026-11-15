package com.sphere.core.ml;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * TensorFlow, PyTorch, Keras, JAX and pyhf connected to the rest of Sphere: the gateway of sphere_mlink.py, seen from
 * Java, and what every engine is told about it.
 *
 * The learning engines of {@link MlEngine} serve Sphere's own commands. The gateway serves programs: a ROOT macro, a
 * Geant4 application, C++, Fortran, Julia, a Python without the frameworks, on this side of WSL or the other. Each
 * process Sphere starts gets, from {@link #environment}:
 *
 * <pre>
 *   SPHERE_MLINK          the gateway's folder; its frameworks.txt says what is there, its start scripts start it
 *   SPHERE_ML_PYTHON      the Python that holds the frameworks
 *   SPHERE_ML_FRAMEWORKS  "torch=2.11.0;tensorflow=2.22.0;pyhf=0.7.6": what a program, a CMakeLists or a shell can test
 *   WSLENV                so that these, and the bridge's, cross into WSL with their paths translated
 * </pre>
 *
 * Nothing here starts a framework or blocks an engine's start: the Pythons are probed once, in the background, and the
 * gateway starts when the first program asks for it.
 */
public final class MlLink {

    private MlLink() {
    }

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final List<String> WANTED = List.of("torch", "tensorflow", "pyhf", "keras", "jax");
    private static final AtomicBoolean PREPARING = new AtomicBoolean();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final String NONCE = Long.toHexString(System.nanoTime() & 0xffffffL);
    private static volatile String token = "";

    /** A failure of the gateway, or of the code it was given. */
    public static final class LinkException extends IOException {
        private static final long serialVersionUID = 1L;

        LinkException(String m) {
            super(m);
        }
    }

    /** Numbers and their shape, the last index fastest. */
    public record Array(double[] data, long[] shape) {
        public Array(double... data) {
            this(data, new long[] {data.length});
        }
    }

    /** What the gateway answered. */
    public record Reply(Map<String, String> meta, Map<String, double[]> columns) {

        public boolean ok() {
            return "1".equals(meta.get("Ok"));
        }

        public String error() {
            return meta.getOrDefault("Error", "");
        }

        public String output() {
            return meta.getOrDefault("Output", "");
        }

        public String type() {
            return meta.getOrDefault("Type", "none");
        }

        public double seconds() {
            return parse(meta.getOrDefault("Seconds", "0"));
        }

        public String text() {
            return meta.getOrDefault("Value", "");
        }

        /** The value of a code that gave one number. */
        public double number() throws LinkException {
            if (!"number".equals(type())) {
                throw new LinkException("the result is not a number but of type " + type());
            }
            return parse(meta.get("Value"));
        }

        /** One number of a nested result, by its path: number("backends.numpy.cls_obs"). */
        public double number(String path) throws LinkException {
            final String v = meta.get("R." + path);
            if (v == null) {
                throw new LinkException("the result has no number " + path);
            }
            return parse(v);
        }

        public String text(String path) {
            return meta.getOrDefault("S." + path, "");
        }

        /** An array by its path in the result; "result" for a code that gave one array. */
        public Array array(String path) throws LinkException {
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (e.getKey().startsWith("Array.") && e.getValue().equals(path)) {
                    final String column = e.getKey().substring(6);
                    return new Array(columns.get(column), shape(meta.getOrDefault("Shape." + column, "")));
                }
            }
            throw new LinkException("the result has no array " + path);
        }

        public Array array() throws LinkException {
            return array("result");
        }
    }

    private static long[] shape(String text) {
        final String t = text.strip();
        if (t.isEmpty()) {
            return new long[0];
        }
        return java.util.Arrays.stream(t.split("\\s+")).mapToLong(Long::parseLong).toArray();
    }

    private static double parse(String text) {
        return switch (text) {
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
    }

    // ------------------------------------------------------------------
    // Where it is, what is there
    // ------------------------------------------------------------------

    /** The gateway's folder. */
    public static Path folder() {
        return Bridge.folder().resolve("mlink");
    }

    private static Function<String, String> settings() {
        final SettingsManager s = new SettingsManager();
        return key -> {
            try {
                return s.getProperty(key);
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    private static Map<String, String> keys(Path file) {
        final Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    out.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
                }
            }
        } catch (IOException | RuntimeException e) {
            out.clear();    // not written yet, or being replaced
        }
        return out;
    }

    /** What the gateway's Python holds, from frameworks.txt; empty before the first probe. Starts nothing. */
    public static Map<String, String> detect() {
        return keys(folder().resolve("frameworks.txt"));
    }

    /** "torch=2.11.0;tensorflow=2.22.0;pyhf=0.7.6". */
    private static String summary(Map<String, String> found) {
        final List<String> parts = new ArrayList<>();
        for (String f : WANTED) {
            if (found.containsKey(f)) {
                parts.add(f + "=" + found.get(f));
            }
        }
        return String.join(";", parts);
    }

    /** "torch 2.11.0, tensorflow 2.22.0, keras 3.16 on tensorflow, jax 0.9.2, pyhf 0.7.6". */
    public static String describe(Map<String, String> found) {
        final List<String> parts = new ArrayList<>();
        for (String f : WANTED) {
            if (found.containsKey(f)) {
                parts.add(f + " " + found.get(f) + (f.equals("keras") && found.containsKey("keras_backend")
                    ? " on " + found.get("keras_backend") : ""));
            }
        }
        return parts.isEmpty() ? "no framework" : String.join(", ", parts);
    }

    /** A path as WSL names it (/mnt/d/...). */
    public static String wslPath(Path p) {
        return MlLocator.toWsl(p);
    }

    /** The WSL distributions of this machine. */
    public static List<String> wslDistros() {
        return WINDOWS ? MlLocator.distros(settings()) : List.of();
    }

    public static String wslExe() {
        return MlLocator.wslExe(settings());
    }

    // ------------------------------------------------------------------
    // What every engine is told
    // ------------------------------------------------------------------

    /** Called for every process Sphere starts, by Bridge.environment. */
    public static void environment(Map<String, String> env) {
        env.put("SPHERE_MLINK", folder().toString());
        final Map<String, String> found = detect();
        if (!found.isEmpty()) {
            env.put("SPHERE_ML_FRAMEWORKS", summary(found));
            if (found.containsKey("python")) {
                env.put("SPHERE_ML_PYTHON", found.get("python"));
            }
        }
        if (WINDOWS) {
            crossIntoWsl(env, List.of("SPHERE_BRIDGE/p", "SPHERE_BRIDGE_LIB/p", "SPHERE_PLOTS/p", "SPHERE_MLINK/p", "SPHERE_ML_PYTHON/p",
                "SPHERE_ML_FRAMEWORKS", "CPLUS_INCLUDE_PATH/l", "ROOT_INCLUDE_PATH/l"));
        }
        prepare();
    }

    /**
     * WSLENV names the variables that cross into a Linux process wsl.exe starts: /p translates a path, /l a list.
     * PYTHONPATH stays out: a Windows one would hand Linux's Python the Windows standard library.
     */
    private static void crossIntoWsl(Map<String, String> env, List<String> names) {
        final String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("WSLENV")).findFirst().orElse("WSLENV");
        final List<String> all = new ArrayList<>();
        final String was = env.get(key);
        if (was != null && !was.isBlank()) {
            all.addAll(List.of(was.split(":")));
        }
        for (String n : names) {
            final String bare = n.contains("/") ? n.substring(0, n.indexOf('/')) : n;
            if (all.stream().noneMatch(a -> a.equals(bare) || a.startsWith(bare + "/"))) {
                all.add(n);
            }
        }
        env.put(key, String.join(":", all));
    }

    /** Once a session, in the background: which Python has the most, and the files that say so. */
    public static void prepare() {
        if (PREPARING.compareAndSet(false, true)) {
            final Thread t = new Thread(() -> {
                try {
                    install(false);
                } catch (IOException | RuntimeException e) {
                    AppLogger.warn("ml link: " + e.getMessage());
                }
            }, "sphere-mlink-prepare");
            t.setDaemon(true);
            t.start();
        }
    }

    private static int score(MlLocator.Interpreter i) {
        int n = 0;
        for (String f : WANTED) {
            n += i.has(f) ? 1 : 0;
        }
        return n;
    }

    /** The Python holding the most frameworks, the first found among equals. WSL is looked at only when asked. */
    public static MlLocator.Interpreter choose(boolean withWsl) {
        MlLocator.Interpreter best = null;
        for (MlLocator.Interpreter i : MlLocator.find(settings(), withWsl)) {
            if (best == null || score(i) > score(best)) {
                best = i;
            }
        }
        return best;
    }

    /** Writes frameworks.txt and the start scripts, for the Python chosen. Answers it. */
    public static MlLocator.Interpreter install(boolean withWsl) throws IOException {
        MlLocator.Interpreter i = choose(false);
        if (withWsl && (i == null || score(i) == 0)) {
            i = choose(true);
        }
        if (i == null) {
            return null;
        }
        final Path lib = Bridge.libraries();
        Files.createDirectories(folder());
        final List<String> cmd = MlLocator.withEnvironment(i.command(), i.distro(), Map.of());
        cmd.add(i.path(lib.resolve("sphere_mlink.py")));
        cmd.add("--install");
        cmd.add(i.path(folder()));
        MlLocator.chosen().forEach((k, v) -> {
            cmd.add("--env");
            cmd.add(k + "=" + v);
        });
        final String idle = settings().apply("ML_LINK_IDLE");
        if (idle != null && idle.strip().matches("\\d+(\\.\\d+)?")) {
            cmd.add("--idle");
            cmd.add(idle.strip());
        }
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        final Process p = pb.start();
        p.getOutputStream().close();
        try {
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("the Python chosen did not answer: " + i.label());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        writeRoutes(i, withWsl);
        return i;
    }

    /** Which Python serves what the chosen one lacks: the gateway forwards those requests there by itself. */
    private static void writeRoutes(MlLocator.Interpreter chosen, boolean withWsl) throws IOException {
        final StringBuilder routes = new StringBuilder();
        final List<MlLocator.Interpreter> all = MlLocator.find(settings(), withWsl);
        for (String f : WANTED) {
            if (chosen.has(f)) {
                continue;
            }
            for (MlLocator.Interpreter i : all) {
                if (i.has(f)) {
                    routes.append(f).append('\t').append(String.join("\t", i.command())).append('\n');
                    break;
                }
            }
        }
        Files.writeString(folder().resolve("routes.txt"), routes.toString(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // The gateway
    // ------------------------------------------------------------------

    /** Its pid and token while one has announced itself; empty otherwise. It may have died since: alive() asks it. */
    public static Map<String, String> state() {
        return keys(folder().resolve("gateway.txt"));
    }

    /** Does a gateway answer? Starts nothing. */
    public static boolean alive() {
        token = state().getOrDefault("Token", "");
        if (token.isEmpty()) {
            return false;
        }
        try {
            return send("ping", Map.of(), Map.of(), 2.0).ok();
        } catch (IOException e) {
            // gone, or busy with someone's long computation: that one still beats
            final String before = beat();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !before.isEmpty() && !before.equals(beat());
        }
    }

    /** Starts the gateway unless one answers, and waits for it. */
    public static void start() throws IOException {
        if (alive()) {
            return;
        }
        final Path script = folder().resolve(WINDOWS ? "start.bat" : "start.sh");
        if (!Files.isRegularFile(script) && install(true) == null) {
            throw new LinkException("no Python found: name one in settings.conf (ML_PYTHON or PYTHON_EXEC)");
        }
        runStartScript();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        try {
            do {
                if (alive()) {
                    return;
                }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new LinkException("the gateway did not start; see " + folder().resolve("gateway.log"));
    }

    private static void runStartScript() throws IOException {
        final Path script = folder().resolve(WINDOWS ? "start.bat" : "start.sh");
        final ProcessBuilder pb = WINDOWS ? new ProcessBuilder("cmd.exe", "/c", script.toString())
            : new ProcessBuilder("sh", script.toString());
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        final Process p = pb.start();
        p.getOutputStream().close();
        try {
            p.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Asks the gateway to stop, if one is there. */
    public static void stop() {
        if (!state().isEmpty()) {
            try {
                Files.writeString(folder().resolve("stop"), "");
            } catch (IOException e) {
                // no folder, no gateway
            }
        }
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        final StringBuilder out = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            final char c = s.charAt(k);
            if (c == '\\' && k + 1 < s.length() && "\\nr".indexOf(s.charAt(k + 1)) >= 0) {
                final char n = s.charAt(++k);
                out.append(n == 'n' ? '\n' : n == 'r' ? '\r' : '\\');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Reply send(String op, Map<String, String> fields, Map<String, Array> arrays, double timeout) throws IOException {
        return send(op, fields, arrays, timeout, false);
    }

    private static Reply send(String op, Map<String, String> fields, Map<String, Array> arrays, double timeout, boolean retryIfGone)
        throws IOException {
        final String id = "java-" + ProcessHandle.current().pid() + "-" + IDS.incrementAndGet() + "-" + NONCE;
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Op", op);
        meta.put("Protocol", "1");
        meta.put("Token", token);
        fields.forEach((k, v) -> meta.put(k, escape(v)));
        final Spx.Writer w = new Spx.Writer(Spx.Kind.TABLE, "mlink");
        arrays.forEach((k, v) -> {
            final StringBuilder s = new StringBuilder();
            for (long d : v.shape()) {
                s.append(s.length() == 0 ? "" : " ").append(d);
            }
            meta.put("Shape." + k, s.toString());
        });
        w.meta(meta);
        arrays.forEach((k, v) -> w.f64(k, v.data()));
        place(w, folder().resolve("in").resolve(id + ".spx"));
        final Path answer = folder().resolve("out").resolve(id + ".spx");
        final long started = System.nanoTime();
        long seen = started;
        String beat = beat();
        boolean kicked = false;
        long pause = 200_000;
        try {
            while (!Files.isRegularFile(answer)) {
                java.util.concurrent.locks.LockSupport.parkNanos(pause);
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                pause = Math.min(pause * 3 / 2, 20_000_000L);
                final long now = System.nanoTime();
                if (timeout > 0 && (now - started) / 1e9 > timeout) {
                    Files.writeString(folder().resolve("in").resolve(id + ".cancel"), "");
                    throw new LinkException("no answer to '" + op + "' after " + timeout + " s");
                }
                if ((now - seen) / 1e9 > 5) {
                    if (retryIfGone && !token.equals(state().getOrDefault("Token", ""))) {
                        return null;    // gone, or another run: nobody will answer what was left for this one
                    }
                    final String current = beat();
                    if (!current.equals(beat)) {
                        beat = current;
                        seen = now;
                        kicked = false;
                    } else {
                        if (retryIfGone && !kicked && (now - seen) / 1e9 > 15) {   // dead, it is replaced; alive, its lock turns the new one away
                            runStartScript();
                            kicked = true;
                        }
                        if ((now - seen) / 1e9 > 90) {
                            throw new LinkException("the gateway has stopped answering; see " + folder().resolve("gateway.log"));
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkException("interrupted");
        }
        final Reply reply = read(answer);
        try {
            Files.deleteIfExists(answer);
        } catch (IOException held) {
            // the next gateway clears what is left in its folders
        }
        return reply;
    }

    /** On Windows a scanner may hold a new file for a moment. The name matters: it is what the answer is filed under. */
    private static void place(Spx.Writer w, Path target) throws IOException {
        for (int attempt = 0;; attempt++) {
            try {
                Path written = w.write(target);
                if (!written.equals(target)) {
                    Files.move(written, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException held) {
                if (attempt >= 100) {
                    throw held;
                }
                pause();
            }
        }
    }

    private static Reply read(Path answer) throws IOException {
        for (int attempt = 0;; attempt++) {
            final Map<String, String> back = new LinkedHashMap<>();
            final Map<String, double[]> columns = new LinkedHashMap<>();
            try (Spx.Reader r = new Spx.Reader(answer)) {
                r.meta().forEach((k, v) -> back.put(k, unescape(v)));
                for (Spx.Section s : r.sections()) {
                    if (s.type() == Spx.F64) {
                        columns.put(s.name(), r.f64(s.name()));
                    }
                }
                return new Reply(back, columns);
            } catch (IOException held) {
                if (attempt >= 500) {
                    throw held;
                }
                pause();
            }
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkException("interrupted");
        }
    }

    /** A request, the gateway started if need be; a failure of the code comes back as a LinkException. */
    public static Reply request(String op, Map<String, String> fields, Map<String, Array> arrays, double timeout) throws IOException {
        token = state().getOrDefault("Token", "");
        if (token.isEmpty()) {     // the gateway stops when idle: the next call starts it again
            start();
        }
        Reply r = send(op, fields, arrays, timeout, true);
        if (r == null) {           // replaced while this one waited: asked again, of the new one
            start();
            r = send(op, fields, arrays, timeout);
        }
        if ("1".equals(r.meta().get("Retry"))) {
            token = state().getOrDefault("Token", "");
            r = send(op, fields, arrays, timeout);
        }
        if (!r.ok()) {
            final String trace = r.meta().getOrDefault("Traceback", "");
            throw new LinkException(r.error() + (trace.isEmpty() ? "" : "\n" + trace));
        }
        return r;
    }

    /** Runs Python in the gateway; the answer is the value of its last expression. */
    public static Reply eval(String code, Map<String, Array> arrays) throws IOException {
        return request("py", Map.of("Code", code, "Session", "java"), arrays, 0);
    }

    /** One channel of counts by pyhf: CLs at mu; points above 0 scan mu for the 95 % CL upper limit too. */
    public static Reply pyhf(double[] signal, double[] background, double[] uncertainty, double[] observed, double mu, int points)
        throws IOException {
        return request("pyhf", Map.of("Mu", Double.toString(mu), "Points", Integer.toString(points), "Backends", "numpy"),
            Map.of("signal", new Array(signal), "background", new Array(background), "bkg_uncertainty", new Array(uncertainty),
                "observed", new Array(observed)), 0);
    }

    /** A saved model on arrays: TorchScript, torch.export, Keras, a SavedModel, a Sphere network or graph. */
    public static Reply predict(String model, Map<String, Array> arrays) throws IOException {
        return request("predict", Map.of("Model", Path.of(model).toAbsolutePath().toString(), "Inputs", String.join(",", arrays.keySet())),
            arrays, 0);
    }

    private static String beat() {
        try {
            return Files.readString(folder().resolve("heartbeat.txt"));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
