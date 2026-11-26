package com.sphere.core.golem95;

import com.sphere.core.bridge.Bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Fortran Golem95 beside the Java one: an installation found on this machine, or the library Sphere
 * builds itself from the Golem95 1.3.4 and OneLOop 3.6.1 sources it carries, with this machine's
 * gfortran, once per compiler. A Fortran program run from Sphere that uses Golem95's modules is linked
 * against it without asking (com.sphere.core.fortran.FortranBridge), as a program that calls LHAPDF is
 * linked against the bridge. The carried sources have Sphere's fixes ({@link #FIXES}), as the Java port,
 * translated from them, has; an installation of Golem95 1.3.4 of the user's own has not.
 */
public final class GolemNative {

    /** The sources, beside this class in the jar (or under src when Sphere runs from its sources). */
    private static final String SOURCES = "fortran/golem95-" + Golem95.VERSION + "/";

    /**
     * Sphere's own fixes to the Golem95 sources it carries, counted: 1, the rank-7 hexagons, whose reduction
     * read an array entry Golem95 1.3.4 never sets (src/higherrank/generic_function_np.f90). A build made from
     * sources with fewer fixes is not used again: it is made anew.
     */
    static final int FIXES = 1;

    /** The file in a build folder that says which fixes its library has. */
    private static final String FIXES_FILE = "sphere-fixes.txt";

    private GolemNative() {
    }

    /**
     * A Fortran Golem95.
     *
     * @param prefix  the installation (or Sphere's build folder)
     * @param include where the .mod files are
     * @param archive the library itself
     * @param version as the installation says it, or null
     * @param origin  how it was found
     */
    public record Library(Path prefix, Path include, Path archive, String version, String origin) {

        public List<String> compileFlags() {
            return List.of("-I" + include);
        }

        /** What goes after the program's source: a static library is searched for what came before it. */
        public List<String> linkFlags() {
            return List.of(archive.toString());
        }
    }

    /* ---------------------------------------------------------------- finding */

    /** An installation of the Fortran Golem95: the user's first (GOLEM95_DIR, pkg-config), then Sphere's builds. */
    public static Library find() {
        for (Path prefix : prefixes()) {
            final Library l = at(prefix, "installation at " + prefix);
            if (l != null) {
                return l;
            }
        }
        for (Path pc : pkgConfigFiles()) {
            final Library l = fromPc(pc);
            if (l != null) {
                return l;
            }
        }
        return builtAny();
    }

    private static Set<Path> prefixes() {
        final Set<Path> out = new LinkedHashSet<>();
        for (String var : new String[]{"GOLEM95_DIR", "GOLEM95_ROOT", "GOLEM95", "GOLEM_DIR"}) {
            final String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                out.add(Path.of(v.trim()));
            }
        }
        return out;
    }

    private static Library at(Path prefix, String origin) {
        final Path include = prefix.resolve("include").resolve("golem95");
        if (!Files.isRegularFile(include.resolve("matrice_s.mod"))) {
            return null;
        }
        for (String lib : new String[]{"lib", "lib64"}) {
            for (String file : new String[]{"libgolem95.a", "libgolem95.so", "libgolem95.dylib", "libgolem95.dll.a"}) {
                final Path p = prefix.resolve(lib).resolve(file);
                if (Files.isRegularFile(p)) {
                    return new Library(prefix, include, p, null, origin);
                }
            }
        }
        return null;
    }

    private static List<Path> pkgConfigFiles() {
        final List<Path> out = new ArrayList<>();
        final String env = System.getenv("PKG_CONFIG_PATH");
        final List<String> dirs = new ArrayList<>();
        if (env != null) {
            for (String d : env.split(File.pathSeparator)) {
                dirs.add(d);
            }
        }
        dirs.add("/usr/local/lib/pkgconfig");
        dirs.add("/usr/lib/pkgconfig");
        dirs.add("/usr/lib64/pkgconfig");
        for (String d : dirs) {
            if (d.isBlank()) {
                continue;
            }
            final Path pc = Path.of(d.trim()).resolve("golem95.pc");
            if (Files.isRegularFile(pc)) {
                out.add(pc);
            }
        }
        return out;
    }

    private static final Pattern PC_VAR = Pattern.compile("(?m)^(prefix|libdir|includedir|exec_prefix)\\s*=\\s*(.+)$");
    private static final Pattern PC_VERSION = Pattern.compile("(?m)^Version:\\s*(\\S+)");

    private static Library fromPc(Path pc) {
        try {
            final String text = Files.readString(pc, StandardCharsets.UTF_8);
            final java.util.Map<String, String> vars = new java.util.HashMap<>();
            final Matcher m = PC_VAR.matcher(text);
            while (m.find()) {
                String v = m.group(2).trim();
                for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
                    v = v.replace("${" + e.getKey() + "}", e.getValue());
                }
                vars.put(m.group(1), v);
            }
            final String prefix = vars.get("prefix");
            if (prefix == null) {
                return null;
            }
            final Library l = at(Path.of(prefix), "pkg-config " + pc);
            if (l == null) {
                return null;
            }
            final Matcher v = PC_VERSION.matcher(text);
            return new Library(l.prefix(), l.include(), l.archive(), v.find() ? v.group(1) : null, l.origin());
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Sphere's build for this compiler, if there is one. */
    public static Library built(String compiler) {
        final Path dir = folder(compiler);
        return complete(dir) ? library(dir) : null;
    }

    /** Any build of Sphere's (for whichever compiler). */
    private static Library builtAny() {
        final Path root = Bridge.buildFolder();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().startsWith("golem95-") && complete(p))
                .findFirst().map(GolemNative::library).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** A whole build, from the sources with every fix Sphere has. */
    private static boolean complete(Path dir) {
        if (!Files.isRegularFile(dir.resolve("lib").resolve("libgolem95.a"))
            || !Files.isRegularFile(dir.resolve("include").resolve("golem95").resolve("matrice_s.mod"))) {
            return false;
        }
        try {
            return Files.readString(dir.resolve(FIXES_FILE), StandardCharsets.UTF_8).trim().equals(String.valueOf(FIXES));
        } catch (IOException none) {
            return false;
        }
    }

    private static Library library(Path dir) {
        return new Library(dir, dir.resolve("include").resolve("golem95"), dir.resolve("lib").resolve("libgolem95.a"),
            Golem95.VERSION, "built by Sphere from the sources it carries (with its " + FIXES + " fix" + (FIXES == 1 ? "" : "es")
            + "), in " + dir);
    }

    private static Path folder(String compiler) {
        return Bridge.buildFolder().resolve("golem95-" + Golem95.VERSION + "-" + digest(new File(compiler).getAbsolutePath(),
            Golem95.VERSION, "fixes " + FIXES).substring(0, 10));
    }

    /* ---------------------------------------------------------------- building */

    /** The library for this compiler: Sphere's build, made now if there is none. */
    public static synchronized Library build(String compiler, Consumer<String> progress) throws IOException {
        final Library done = built(compiler);
        if (done != null) {
            return done;
        }
        final Path dir = folder(compiler);
        final Path src = dir.resolve("src");
        final Path obj = dir.resolve("obj");
        final Path mod = dir.resolve("include").resolve("golem95");
        final Path lib = dir.resolve("lib");
        for (Path d : new Path[]{src, obj, mod, lib}) {
            Files.createDirectories(d);
        }
        final List<String> order = new ArrayList<>();
        for (String line : new String(carried("build-order.txt"), StandardCharsets.UTF_8).split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                order.add(line.trim());
            }
        }
        final String toolDir = new File(compiler).getAbsoluteFile().getParent();
        final List<String> objects = new ArrayList<>();
        int done2 = 0;
        for (String rel : order) {
            final Path file = src.resolve(rel);
            Files.createDirectories(file.getParent());
            Files.write(file, carried(rel));
            final String base = rel.substring(rel.lastIndexOf('/') + 1).replaceFirst("\\.f90$", "");
            final Path o = obj.resolve(base + ".o");
            final Bridge.Outcome r = Bridge.run(List.of(compiler, "-O2", "-w", "-c", file.toString(), "-J", mod.toString(),
                "-I", mod.toString(), "-o", o.toString()), dir, 900, toolDir);
            if (!r.ok()) {
                throw new IOException("Golem95's " + rel + " does not compile with " + compiler + ":\n" + r.output());
            }
            objects.add(o.toString());
            done2++;
            if (progress != null && (done2 % 10 == 0 || done2 == order.size())) {
                progress.accept(String.format(java.util.Locale.ROOT, "  %d of %d sources compiled", done2, order.size()));
            }
        }
        final List<String> ar = new ArrayList<>();
        final Path besides = Path.of(toolDir == null ? "." : toolDir).resolve(isWindows() ? "ar.exe" : "ar");
        ar.add(Files.isRegularFile(besides) ? besides.toString() : "ar");
        ar.add("rcs");
        ar.add(lib.resolve("libgolem95.a").toString());
        ar.addAll(objects);
        final Bridge.Outcome r = Bridge.run(ar, dir, 300, toolDir);
        if (!r.ok()) {
            throw new IOException("ar could not make libgolem95.a:\n" + r.output());
        }
        Files.writeString(dir.resolve(FIXES_FILE), FIXES + "\n", StandardCharsets.UTF_8);
        return library(dir);
    }

    /** A carried source: from the jar, or from the sources when Sphere runs from them. */
    static byte[] carried(String rel) throws IOException {
        try (InputStream in = GolemNative.class.getResourceAsStream(SOURCES + rel)) {
            if (in != null) {
                return in.readAllBytes();
            }
        }
        final Path p = Path.of("src", "com", "sphere", "core", "golem95").resolve(SOURCES + rel);
        if (Files.isRegularFile(p)) {
            return Files.readAllBytes(p);
        }
        throw new IOException("the Golem95 source " + rel + " is missing from this build of Sphere");
    }

    /** Whether this build of Sphere carries the Fortran sources at all. */
    public static boolean sourcesCarried() {
        try {
            carried("build-order.txt");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    static String digest(String... parts) {
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                sha.update(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The modules of Golem95 a Fortran program may use; seeing one, Sphere links Golem95 in. */
    public static final Pattern USES = Pattern.compile("(?im)^\\s*use\\s+(matrice_s|form_factor_(?:type|[1-6]p|higher_ranks)"
        + "|precision_golem|tens_rec|tens_comb|tensor_integrals|generic_function_(?:np|[1-4]p)|avh_olo(?:_dp)?|s_matrix_type"
        + "|sortie_erreur|spinor|parametre)\\b");
}
