package com.sphere.core.geantbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Builds geant4-bridge into geantbackend/, next to Sphere, as Sphere builds
 * its ROOT engine into rootbackend/ -- separately: this one needs Geant4 and
 * nothing of ROOT.
 *
 * The C++ sources travel inside Sphere (com/sphere/core/geantbackend). They
 * are copied to geantbackend/src, built with CMake against the Geant4 the
 * locator finds (settings.conf, PATH, conda, virtual environments), or with
 * the compiler and geant4-config when CMake is not there. A digest of the
 * sources is kept beside the binary, so a changed source rebuilds once and an
 * unchanged one never does. A build that worked leaves no src/ nor build/.
 */
public final class Geant4BridgeCompiler {

    private Geant4BridgeCompiler() {
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final String BINARY = WINDOWS ? "geant4-bridge.exe" : "geant4-bridge";
    private static final String STAMP = ".geant4-bridge.build";
    private static final String RESOURCE = "/com/sphere/core/geantbackend";
    /** Where the sources are copied for a build, in geantbackend/. */
    private static final String SOURCES = "src";

    /** geantbackend/ in Sphere's working directory. */
    public static Path runtimeDir() {
        final Path p = Path.of(System.getProperty("user.dir"), "geantbackend");
        try {
            Files.createDirectories(p);
        } catch (IOException ignored) {
            // reported when something is written there
        }
        return p;
    }

    public static Path binary() {
        return runtimeDir().resolve(BINARY);
    }

    /** What a build needs and where it comes from: one result per attempt. */
    public record Result(Path binary, boolean built, String message, Geant4Locator.Install install) {
        public boolean ok() {
            return binary != null && Files.isRegularFile(binary);
        }
    }

    /**
     * The engine binary, built first when it is missing or its sources changed.
     *
     * @param settings key lookup into settings.conf (GEANT4_DIR, CMAKE_DIR, GPP_DIR, PYTHON_EXEC)
     * @param log      build output, line by line
     */
    public static synchronized Result getOrBuild(Function<String, String> settings, Consumer<String> log, boolean force) {
        final Path dir = runtimeDir();
        final Path src = dir.resolve(SOURCES);
        final Map<String, byte[]> sources;
        try {
            sources = readSources();
        } catch (IOException e) {
            return new Result(null, false, "cannot read the bridge sources: " + e.getMessage(), null);
        }
        final String digest = fingerprint(sources);
        final Geant4Locator.Install g4 = Geant4Locator.best(settings);
        if (g4 != null && g4.wsl() != null) {
            return getOrBuildInWsl(settings, log, force, sources, digest, g4);
        }
        final Path bin = dir.resolve(BINARY);
        final String stamp = readStamp(dir, STAMP);
        if (!force && Files.isRegularFile(bin) && digest != null && digest.equals(stamp)) {
            removeBuildFiles(dir, log);
            return new Result(bin, false, "up to date", g4);
        }
        if (g4 == null) {
            return new Result(Files.isRegularFile(bin) ? bin : null, false,
                "no Geant4 found to build geant4-bridge: set GEANT4_DIR in settings.conf, put geant4-config on the PATH, "
                    + "or activate a conda environment with Geant4 (conda install -c conda-forge geant4). "
                    + "G4editor works without it; only runs in Geant4 need the engine.", null);
        }
        try {
            writeSources(sources, src);
        } catch (IOException e) {
            return new Result(Files.isRegularFile(bin) ? bin : null, false,
                "cannot copy the bridge sources to " + src + ": " + e.getMessage(), g4);
        }
        log.accept("Building geant4-bridge against " + g4);
        boolean ok;
        try {
            final String cmake = tool(settings, "CMAKE_DIR", WINDOWS ? "cmake.exe" : "cmake");
            if (cmake != null && g4.cmakeDir() != null) {
                ok = buildWithCMake(cmake, settings, src, dir.resolve("build"), g4, log);
            } else {
                ok = buildDirect(settings, src, bin, g4, log);
            }
        } catch (IOException e) {
            log.accept(e.getMessage());
            ok = false;
        }
        if (ok && Files.isRegularFile(bin)) {
            writeStamp(dir, STAMP, digest);
            removeBuildFiles(dir, log);
            return new Result(bin, true, "built geant4-bridge with " + g4, g4);
        }
        return new Result(Files.isRegularFile(bin) ? bin : null, false, "the build failed; see the output above", g4);
    }

    // ------------------------------------------------------------------
    // A Geant4 inside WSL, seen from Windows
    // ------------------------------------------------------------------

    /** The engine built inside WSL: a Linux program, kept in geantbackend/ beside the Windows one. */
    public static final String WSL_BINARY = "geant4-bridge-wsl";
    private static final String WSL_STAMP = ".geant4-bridge-wsl.build";

    /**
     * Builds inside the distribution, with its CMake (or its g++ and geant4-config), in a build directory of
     * its own file system (a build on /mnt/d is several times slower), then copies the program next to the
     * Windows one. Arguments: the sources, the CMake package, geant4-config, where the program goes, the
     * jobs, and a tag naming the build directory after the sources and the Geant4 it is built against.
     */
    private static final String WSL_BUILD = """
        set -e
        exec 2>&1
        src="$1"; cmakedir="$2"; config="$3"; out="$4"; jobs="$5"; tag="$6"
        [ "$cmakedir" = "-" ] && cmakedir=""
        [ "$config" = "-" ] && config=""
        b="${XDG_CACHE_HOME:-$HOME/.cache}/sphere/geant4-bridge-$tag"
        mkdir -p "$b"
        if [ -n "$cmakedir" ] && command -v cmake >/dev/null 2>&1; then
          echo "$ cmake -S $src -B $b -DCMAKE_BUILD_TYPE=Release -DGeant4_DIR=$cmakedir"
          cmake -S "$src" -B "$b" -DCMAKE_BUILD_TYPE=Release -DGeant4_DIR="$cmakedir"
          echo "$ cmake --build $b --parallel $jobs"
          cmake --build "$b" --parallel "$jobs"
        else
          echo "$ g++ with the flags of $config"
          g++ -std=c++17 -O2 -I"$src/inc" $(sh "$config" --cflags) "$src/src/main.cpp" "$src/src/g4b_shm.cpp" \
            "$src/src/g4b_common.cpp" "$src/src/g4b_geant4.cpp" -o "$b/geant4-bridge" $(sh "$config" --libs) -lpthread
        fi
        cp "$b/geant4-bridge" "$out"
        chmod +x "$out" 2>/dev/null || true
        rm -rf "$b"
        echo "geant4-bridge: $out"
        """;

    private static Result getOrBuildInWsl(Function<String, String> settings, Consumer<String> log, boolean force,
                                          Map<String, byte[]> sources, String digest, Geant4Locator.Install g4) {
        final Path dir = runtimeDir();
        final Path src = dir.resolve(SOURCES);
        final Path bin = dir.resolve(WSL_BINARY);
        final Geant4Locator.Wsl w = g4.wsl();
        // Rebuilt for changed sources, and for another Geant4 or distribution
        final String key = digest + " " + w.distro() + ":" + w.prefix();
        if (!force && Files.isRegularFile(bin) && digest != null && key.equals(readStamp(dir, WSL_STAMP))) {
            removeBuildFiles(dir, log);
            return new Result(bin, false, "up to date", g4);
        }
        final boolean withCmake = w.cmake() != null && w.cmakeDir() != null;
        if (!withCmake && (w.config() == null || w.gpp() == null)) {
            return new Result(Files.isRegularFile(bin) ? bin : null, false, "WSL " + w.distro() + " has " + g4
                + " but no CMake nor g++ to build geant4-bridge with: install them there"
                + " (sudo apt install cmake g++ on Debian or Ubuntu)", g4);
        }
        try {
            writeSources(sources, src);
        } catch (IOException e) {
            return new Result(Files.isRegularFile(bin) ? bin : null, false,
                "cannot copy the bridge sources to " + src + ": " + e.getMessage(), g4);
        }
        log.accept("Building geant4-bridge inside WSL " + w.distro() + " against " + g4);
        final String lsrc = Geant4Locator.toWslPath(src, g4, settings);
        final String lout = Geant4Locator.toWslPath(bin, g4, settings);
        if (lsrc == null || lout == null) {
            return new Result(null, false, "WSL " + w.distro() + " cannot reach " + dir + " (is the drive mounted there?)", g4);
        }
        final List<String> cmd = Geant4Locator.wslCommand(g4, settings);
        cmd.addAll(List.of("-e", "sh", "-s", "--", lsrc, w.cmakeDir() == null ? "-" : w.cmakeDir(),
            w.config() == null ? "-" : w.config(), lout, String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors())),
            Integer.toHexString((lsrc + "|" + w.prefix()).hashCode())));
        int code;
        try {
            code = runScript(cmd, WSL_BUILD, dir, log);
        } catch (IOException e) {
            log.accept(e.getMessage());
            code = -1;
        }
        if (code == 0 && Files.isRegularFile(bin)) {
            writeStamp(dir, WSL_STAMP, key);
            removeBuildFiles(dir, log);
            return new Result(bin, true, "built geant4-bridge inside WSL " + w.distro() + " with " + g4, g4);
        }
        return new Result(Files.isRegularFile(bin) ? bin : null, false, "the build inside WSL failed; see the output above", g4);
    }

    /**
     * Starts the engine a build gave, with its installation's data sets and libraries: here through shared
     * memory, or, for a Geant4 inside WSL, there through its standard input and output (Windows and the WSL
     * machine share no memory). The engine then names this machine's files the way it sees them.
     */
    public static Geant4Bridge start(Result r, Function<String, String> settings, Consumer<String> log) throws IOException {
        final Geant4Locator.Install g4 = r.install() != null ? r.install() : Geant4Locator.best(settings);
        final Path dir = runtimeDir();
        if (g4 == null || g4.wsl() == null || !r.binary().getFileName().toString().equals(WSL_BINARY)) {
            return Geant4Bridge.start(List.of(r.binary().toString()), Geant4Locator.runtimeEnvironment(g4), dir,
                Geant4Bridge.Transport.SHM, log);
        }
        final String ldir = Geant4Locator.toWslPath(dir, g4, settings);
        final String lbin = Geant4Locator.toWslPath(r.binary(), g4, settings);
        if (ldir == null || lbin == null) {
            throw new IOException("WSL " + g4.wsl().distro() + " cannot reach " + dir);
        }
        final List<String> cmd = Geant4Locator.wslCommand(g4, settings);
        cmd.addAll(List.of("--cd", ldir, "-e", "env"));
        Geant4Locator.runtimeEnvironment(g4).forEach((k, v) -> cmd.add(k + "=" + v));
        cmd.add(lbin);
        // No parent pid: a Windows one means nothing inside WSL, and the engine stops when its input closes
        final Geant4Bridge b = Geant4Bridge.start(cmd, Map.of(), dir, Geant4Bridge.Transport.STDIO, log, false);
        final Path base = dir.toAbsolutePath().normalize();
        b.mapPaths(p -> {
            final Path a = p.toAbsolutePath().normalize();
            if (a.startsWith(base)) {
                return ldir + "/" + base.relativize(a).toString().replace('\\', '/');
            }
            final String m = com.sphere.utils.SettingsManager.toNativePath(a.toString(),
                com.sphere.utils.OSValidator.Platform.WSL);
            return m == null ? a.toString() : m;
        });
        return b;
    }

    /** Runs a shell script fed on the standard input of a command, its output line by line to the log. */
    private static int runScript(List<String> cmd, String script, Path dir, Consumer<String> log) throws IOException {
        log.accept("$ " + String.join(" ", cmd));
        final Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        try (var w = new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write(script);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.accept(line);
            }
        }
        try {
            if (!p.waitFor(60, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static boolean buildWithCMake(String cmake, Function<String, String> settings, Path src, Path build,
                                          Geant4Locator.Install g4, Consumer<String> log) throws IOException {
        Files.createDirectories(build);
        final List<String> configure = new ArrayList<>(List.of(cmake, "-S", src.toString(), "-B", build.toString(),
            "-DCMAKE_BUILD_TYPE=Release", "-DGeant4_DIR=" + g4.cmakeDir()));
        final String gpp = tool(settings, "GPP_DIR", WINDOWS ? "g++.exe" : "g++");
        if (WINDOWS && gpp != null && Geant4Locator.which("cl.exe") == null) {
            configure.add("-G");
            configure.add("MinGW Makefiles");
            configure.add("-DCMAKE_CXX_COMPILER=" + gpp);
        }
        if (run(configure, build, log, Map.of()) != 0) {
            return false;
        }
        final List<String> compile = List.of(cmake, "--build", build.toString(), "--config", "Release", "--parallel",
            String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors())));
        if (run(compile, build, log, Map.of()) != 0) {
            return false;
        }
        // Single- and multi-config generators put the binary in different places
        for (Path p : List.of(build.resolve(BINARY), build.resolve("Release").resolve(BINARY))) {
            if (Files.isRegularFile(p)) {
                Files.copy(p, runtimeDir().resolve(BINARY), StandardCopyOption.REPLACE_EXISTING);
                runtimeDir().resolve(BINARY).toFile().setExecutable(true);
                return true;
            }
        }
        log.accept("CMake finished but " + BINARY + " is not in " + build);
        return false;
    }

    /** Without CMake: the compiler with geant4-config's flags. */
    private static boolean buildDirect(Function<String, String> settings, Path src, Path bin, Geant4Locator.Install g4,
                                       Consumer<String> log) throws IOException {
        if (g4.geant4Config() == null) {
            log.accept("Neither CMake nor geant4-config is available to build against " + g4.prefix());
            return false;
        }
        final String cflags = Geant4Locator.run(g4.geant4Config(), "--cflags");
        final String libs = Geant4Locator.run(g4.geant4Config(), "--libs");
        if (cflags == null || libs == null) {
            log.accept("geant4-config did not answer");
            return false;
        }
        final String gpp = tool(settings, "GPP_DIR", WINDOWS ? "g++.exe" : "g++");
        if (gpp == null) {
            log.accept("No C++ compiler: set GPP_DIR in settings.conf");
            return false;
        }
        final List<String> cmd = new ArrayList<>(List.of(gpp, "-std=c++17", "-O2", "-I" + src.resolve("inc")));
        cmd.addAll(List.of(cflags.trim().split("\\s+")));
        for (String f : List.of("main.cpp", "g4b_shm.cpp", "g4b_common.cpp", "g4b_geant4.cpp")) {
            cmd.add(src.resolve("src").resolve(f).toString());
        }
        cmd.add("-o");
        cmd.add(bin.toString());
        cmd.addAll(List.of(libs.trim().split("\\s+")));
        cmd.add("-lpthread");
        return run(cmd, runtimeDir(), log, Map.of()) == 0;
    }

    /** Builds the engine without Geant4, the transport test engine. */
    public static synchronized Result buildTestEngine(Function<String, String> settings, Consumer<String> log) {
        final Path dir = runtimeDir();
        final Path src = dir.resolve(SOURCES);
        try {
            writeSources(readSources(), src);
            final String gpp = tool(settings, "GPP_DIR", WINDOWS ? "g++.exe" : "g++");
            if (gpp == null) {
                return new Result(null, false, "no C++ compiler (GPP_DIR)", null);
            }
            final Path bin = dir.resolve(WINDOWS ? "geant4-bridge-test.exe" : "geant4-bridge-test");
            final List<String> cmd = new ArrayList<>(List.of(gpp, "-std=c++17", "-O2", "-I" + src.resolve("inc")));
            for (String f : List.of("main.cpp", "g4b_shm.cpp", "g4b_common.cpp", "g4b_nogeant4.cpp")) {
                cmd.add(src.resolve("src").resolve(f).toString());
            }
            if (WINDOWS) {
                cmd.add("-static");
            } else {
                cmd.add("-lpthread");
            }
            cmd.add("-o");
            cmd.add(bin.toString());
            if (run(cmd, dir, log, Map.of()) != 0) {
                return new Result(null, false, "the test engine did not build", null);
            }
            removeBuildFiles(dir, log);
            return new Result(bin, true, "built " + bin.getFileName(), null);
        } catch (IOException e) {
            return new Result(null, false, e.getMessage(), null);
        }
    }

    // ------------------------------------------------------------------

    static String tool(Function<String, String> settings, String key, String exe) {
        final String v = settings == null ? null : settings.apply(key);
        if (v != null && !v.isBlank() && Files.isRegularFile(Path.of(v.trim()))) {
            return v.trim();
        }
        final Path p = Geant4Locator.which(exe);
        return p == null ? null : p.toString();
    }

    private static int run(List<String> cmd, Path dir, Consumer<String> log, Map<String, String> env) throws IOException {
        log.accept("$ " + String.join(" ", cmd));
        final ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(env);
        // A compiler from MSYS2 needs its own bin directory for its DLLs
        final Path exe = Path.of(cmd.get(0));
        if (WINDOWS && exe.getParent() != null) {
            pb.environment().merge("PATH", exe.getParent().toString(), (a, b) -> b + ";" + a);
        }
        final Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.accept(line);
            }
        }
        try {
            if (!p.waitFor(60, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    /** The C++ tree (CMakeLists.txt, inc/, src/) in Sphere's classes or jar: relative path, with /, to content. */
    static Map<String, byte[]> readSources() throws IOException {
        final Map<String, byte[]> out = new TreeMap<>();
        final URL url = Geant4BridgeCompiler.class.getResource(RESOURCE);
        if (url == null) {
            // Running from the source tree (tests, IDE): take it from there
            final Path tree = Path.of("src", "com", "sphere", "core", "geantbackend");
            if (Files.isDirectory(tree)) {
                readTree(tree, out);
                return out;
            }
            throw new IOException("the bridge sources are not in Sphere's classes (run.bat copies them)");
        }
        if ("jar".equals(url.getProtocol())) {
            final JarURLConnection c = (JarURLConnection) url.openConnection();
            // A JarFile of its own: the cached one is shared with every reader of Sphere's jar, and closing it
            // broke whatever was being read at that moment (the fonts at startup: "Stream closed")
            c.setUseCaches(false);
            try (JarFile jar = c.getJarFile()) {
                final String prefix = RESOURCE.substring(1) + "/";
                final Enumeration<JarEntry> en = jar.entries();
                while (en.hasMoreElements()) {
                    final JarEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(prefix) || !isSource(e.getName())) {
                        continue;
                    }
                    try (InputStream in = jar.getInputStream(e)) {
                        out.put(e.getName().substring(prefix.length()), in.readAllBytes());
                    }
                }
            }
            return out;
        }
        try {
            readTree(Path.of(url.toURI()), out);
        } catch (URISyntaxException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (out.isEmpty()) {
            // Classes compiled without their resources: an empty digest would rebuild for nothing
            throw new IOException("the bridge sources are not in Sphere's classes (run.bat copies them)");
        }
        return out;
    }

    private static void readTree(Path from, Map<String, byte[]> out) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p) && isSource(p.toString())) {
                    out.put(from.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
        }
    }

    /** Copies the sources out for a build: geantbackend/src, removed again once the build worked. */
    private static void writeSources(Map<String, byte[]> sources, Path target) throws IOException {
        for (Map.Entry<String, byte[]> e : sources.entrySet()) {
            final Path out = target.resolve(e.getKey());
            Files.createDirectories(out.getParent());
            writeIfChanged(out, e.getValue());
        }
    }

    private static boolean isSource(String name) {
        final String n = name.replace('\\', '/');
        return n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".hh") || n.endsWith("CMakeLists.txt");
    }

    /** Keeps file times when nothing changed, so make does not rebuild everything. */
    private static void writeIfChanged(Path out, byte[] bytes) throws IOException {
        if (Files.isRegularFile(out) && java.util.Arrays.equals(Files.readAllBytes(out), bytes)) {
            return;
        }
        Files.write(out, bytes);
    }

    /**
     * SHA-256 of the names and contents, in the order of the files' paths: the digest the copies on disk
     * gave before, so an engine built then is still up to date.
     */
    static String fingerprint(Map<String, byte[]> sources) {
        try {
            final Path base = Path.of(SOURCES);
            final List<String> names = sources.keySet().stream().sorted(Comparator.comparing(base::resolve)).toList();
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String n : names) {
                md.update(n.getBytes(StandardCharsets.UTF_8));
                md.update(sources.get(n));
            }
            final StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) {
                b.append(String.format("%02x", x));
            }
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Once the engine is built (or already up to date): the copied sources and CMake's build tree are of no
     * further use, so geantbackend/ keeps only the engine, its stamp and the logs. source/ is where the
     * sources went before they went to src/.
     */
    private static void removeBuildFiles(Path dir, Consumer<String> log) {
        for (String sub : List.of("build", SOURCES, "source")) {
            final Path p = dir.resolve(sub);
            if (!Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(p)) {
                for (Path q : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(q);
                }
            } catch (IOException | RuntimeException e) {
                log.accept("could not remove " + p + ": " + e.getMessage());
            }
        }
    }

    private static String readStamp(Path dir, String name) {
        try {
            return Files.readString(dir.resolve(name)).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeStamp(Path dir, String name, String digest) {
        if (digest == null) {
            return;
        }
        try {
            Files.writeString(dir.resolve(name), digest + "\n");
        } catch (IOException ignored) {
            // rebuilt next time
        }
    }
}
