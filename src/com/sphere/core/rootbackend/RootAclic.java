package com.sphere.core.rootbackend;

import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACLiC: a macro compiled rather than interpreted, "macro.C+".
 *
 * Two things stand between the suffix and a compiled macro. The first is the
 * suffix itself: "macro.C+" is not a file, so everything that looks for the
 * file has to look for "macro.C" and keep the "+" for ROOT. The second is on
 * Windows only. ROOT there is built with Microsoft's compiler, and ACLiC calls
 * cl.exe, which works only inside the environment Visual Studio's vcvars
 * script sets up: its PATH, INCLUDE and LIB. Started from anywhere else, and
 * Sphere is anywhere else, ".x macro.C+" fails on a compiler it cannot find.
 * So that environment is found and loaded here, once, from the Visual Studio
 * the machine has, for the architecture root.exe was built for.
 */
public final class RootAclic {

    /**
     * A C or C++ file followed by ACLiC's suffix: "+" compiles when the source
     * changed, "++" always, then any of ROOT's option letters (k keep, f force,
     * g debug, O optimise, c compile only, s silent, v verbose, d debug ACLiC).
     */
    private static final Pattern SUFFIX = Pattern.compile(
        "^(.+?\\.(?:C|c|cc|cxx|cpp|h|hh|hxx|hpp))(\\+\\+?[kfgOcsvd-]*)$");

    /** The environment vcvars gave, per script, for the life of Sphere: it takes seconds to get. */
    private static final Map<Path, Map<String, String>> CAPTURED = new LinkedHashMap<>();

    /** The vcvars script that worked, per architecture. */
    private static final Map<String, Path> CHOSEN = new java.util.concurrent.ConcurrentHashMap<>();

    private RootAclic() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** { file, suffix }: "macro.C++g" gives { "macro.C", "++g" }, a plain name { name, "" }. */
    public static String[] split(String name) {
        if (name == null) return new String[]{null, ""};
        final Matcher m = SUFFIX.matcher(name);
        return m.matches() ? new String[]{m.group(1), m.group(2)} : new String[]{name, ""};
    }

    /** True when the suffix asks for compilation only: the library is built, nothing is run. */
    public static boolean compileOnly(String suffix) {
        return suffix != null && suffix.replace("+", "").contains("c");
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /* ------------------------------------------------------------------ */
    /* The compiler's environment, on Windows                              */
    /* ------------------------------------------------------------------ */

    /**
     * Gives a process that will run ACLiC the compiler's environment, when it
     * does not have it already. Nothing happens off Windows, where ACLiC calls
     * the system compiler, nor when cl.exe is already reachable, as it is when
     * Sphere was started from a developer prompt.
     *
     * The settings keys CPP_MSVC_CL, CPP_MSVC_INCLUDE and CPP_MSVC_LIB win when
     * given, as they do for the C++ backend; otherwise the newest Visual Studio
     * with the C++ tools is found with vswhere and its vcvars script is run.
     *
     * @param binary a ROOT program (root.exe, or the engine) whose architecture decides the compiler's
     * @return true when the process can compile
     */
    public static boolean environment(Map<String, String> env, File binary) {
        return environment(env, binary, false);
    }

    /** The same; quiet says nothing when no compiler is found, for a process that may never need one. */
    public static boolean environment(Map<String, String> env, File binary, boolean quiet) {
        if (!windows()) return true;
        if (onPath(env, "cl.exe") && present(env, "INCLUDE") && present(env, "LIB")) return true;

        final SettingsManager settings = new SettingsManager();
        final String cl = settings.isDeclaredEmpty("CPP_MSVC_CL") ? null : settings.resolveTool("CPP_MSVC_CL", "cl.exe");
        final String include = settings.resolvePath("CPP_MSVC_INCLUDE", null);
        final String lib = settings.resolvePath("CPP_MSVC_LIB", null);
        if (cl != null && include != null && lib != null) {
            prepend(env, "PATH", new File(cl).getParent());
            env.put("INCLUDE", include);
            env.put("LIB", lib);
            return true;
        }

        // Newest first, and the next one when a script fails: an installation
        // can be left half there by an interrupted update, its vcvars64.bat
        // present and the vcvarsall.bat it calls gone. The one that worked is
        // remembered, so later runs neither ask vswhere nor run vcvars again.
        final String arch = architecture(binary);
        final Path known = CHOSEN.get(arch);
        final List<Path> candidates = known != null ? List.of(known) : vcvarsCandidates(arch);
        for (Path vcvars : candidates) {
            final Map<String, String> captured = capture(vcvars);
            if (captured != null) {
                CHOSEN.put(arch, vcvars);
                merge(env, captured);
                return true;
            }
        }
        if (!quiet) {
            AppLogger.warn("ACLiC on Windows compiles with Microsoft's cl.exe, and no Visual Studio with working "
                + "C++ tools was found. Install (or repair) Visual Studio or its Build Tools with \"Desktop "
                + "development with C++\", or set CPP_MSVC_CL, CPP_MSVC_INCLUDE and CPP_MSVC_LIB in settings.conf.");
        }
        return false;
    }

    /** The machine a Windows program is built for, read from its PE header: x64, x86 or arm64. */
    static String architecture(File binary) {
        if (binary != null && binary.isFile()) {
            try (RandomAccessFile in = new RandomAccessFile(binary, "r")) {
                in.seek(0x3C);
                final int pe = Integer.reverseBytes(in.readInt());
                in.seek(pe);
                if (in.readInt() == 0x50450000) {          // "PE\0\0"
                    final int machine = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
                    switch (machine) {
                        case 0x014C: return "x86";
                        case 0xAA64: return "arm64";
                        default: return "x64";
                    }
                }
            } catch (IOException | RuntimeException unreadable) {
                // Not a PE file, or not readable: the common case below.
            }
        }
        return "x64";
    }

    /**
     * The vcvars scripts of every Visual Studio carrying the C++ tools, newest
     * first, keeping only those whose vcvarsall.bat, which they all call, is
     * really there.
     */
    static List<Path> vcvarsCandidates(String arch) {
        final List<Path> out = new ArrayList<>();
        final String programs = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        final Path vswhere = Path.of(programs, "Microsoft Visual Studio", "Installer", "vswhere.exe");
        if (!Files.isRegularFile(vswhere)) return out;
        final String component = arch.equals("arm64") ? "Microsoft.VisualStudio.Component.VC.Tools.ARM64"
                                                       : "Microsoft.VisualStudio.Component.VC.Tools.x86.x64";
        // An arm64 ROOT on an x64 machine is built with the cross compiler.
        final List<String> scripts = switch (arch) {
            case "x86" -> List.of("vcvars32.bat");
            case "arm64" -> List.of("vcvarsarm64.bat", "vcvarsamd64_arm64.bat");
            default -> List.of("vcvars64.bat");
        };
        try {
            final Process p = new ProcessBuilder(vswhere.toString(), "-products", "*", "-requires", component,
                "-sort", "-property", "installationPath").redirectErrorStream(true).start();
            final String listed = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset()).trim();
            p.waitFor(30, TimeUnit.SECONDS);
            for (String line : listed.split("\\R")) {
                if (line.isBlank()) continue;
                final Path build = Path.of(line.trim(), "VC", "Auxiliary", "Build");
                if (!Files.isRegularFile(build.resolve("vcvarsall.bat"))) continue;
                for (String script : scripts) {
                    if (Files.isRegularFile(build.resolve(script))) {
                        out.add(build.resolve(script));
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException failed) {
            return out;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /**
     * Runs vcvars and reads back the environment it leaves, the way a developer
     * prompt gets it. Through a small batch file: the call needs quotes, and a
     * quote inside an argument does not survive the trip to cmd.exe on Windows.
     */
    static synchronized Map<String, String> capture(Path vcvars) {
        final Map<String, String> known = CAPTURED.get(vcvars);
        if (known != null) return known;
        AppLogger.info("ACLiC: loading the compiler's environment from " + vcvars + " (once per session)...");
        Path bat = null;
        try {
            bat = Files.createTempFile("sphere-vcvars", ".bat");
            // UTF-8 both ways: cmd otherwise writes in the console's code page,
            // and a folder with an accent in its name would come back garbled.
            Files.writeString(bat, "@echo off\r\nchcp 65001 >nul\r\ncall \"" + vcvars + "\" >nul 2>&1\r\n"
                + "if errorlevel 1 exit /b 1\r\nset\r\n", StandardCharsets.UTF_8);
            final Process p = new ProcessBuilder("cmd.exe", "/d", "/c", bat.toString()).redirectErrorStream(true).start();
            final Map<String, String> env = new LinkedHashMap<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    final int eq = line.indexOf('=');
                    if (eq > 0) env.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                AppLogger.error("ACLiC: " + vcvars.getFileName() + " did not finish in 120 s.");
                return null;
            }
            if (p.exitValue() != 0 || !containsKey(env, "INCLUDE")) {
                AppLogger.error("ACLiC: " + vcvars + " did not set the compiler up (status " + p.exitValue() + ").");
                return null;
            }
            CAPTURED.put(vcvars, env);
            return env;
        } catch (IOException failed) {
            AppLogger.error("ACLiC: could not run " + vcvars + ": " + failed.getMessage());
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (bat != null) {
                try {
                    Files.deleteIfExists(bat);
                } catch (IOException ignored) {
                    // A temporary file the system will collect.
                }
            }
        }
    }

    /**
     * Puts what vcvars added into a process environment. Lists of folders get
     * the new folders in front and keep what the process already had, which
     * Sphere may have added to; everything else vcvars set is taken as it is.
     */
    static void merge(Map<String, String> env, Map<String, String> captured) {
        final Map<String, String> before = System.getenv();
        for (Map.Entry<String, String> e : captured.entrySet()) {
            final String key = e.getKey();
            final String value = e.getValue();
            if (isList(key)) {
                final Set<String> had = new LinkedHashSet<>(folders(get(before, key)));
                final List<String> added = new ArrayList<>();
                for (String dir : folders(value)) {
                    if (!had.contains(dir)) added.add(dir);
                }
                for (int k = added.size() - 1; k >= 0; k--) prepend(env, key, added.get(k));
            } else if (!value.equals(get(before, key))) {
                env.put(key, value);
            }
        }
    }

    private static boolean isList(String key) {
        final String k = key.toUpperCase(Locale.ROOT);
        return k.equals("PATH") || k.equals("INCLUDE") || k.equals("LIB") || k.equals("LIBPATH");
    }

    private static List<String> folders(String value) {
        final List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split(File.pathSeparator)) {
            if (!part.isBlank()) out.add(part);
        }
        return out;
    }

    /** A key looked up the way Windows does, ignoring case. */
    private static String get(Map<String, String> env, String key) {
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static boolean containsKey(Map<String, String> env, String key) {
        return get(env, key) != null;
    }

    private static boolean present(Map<String, String> env, String key) {
        final String v = get(env, key);
        return v != null && !v.isBlank();
    }

    private static void prepend(Map<String, String> env, String key, String dir) {
        if (dir == null) return;
        String name = key;
        for (String k : env.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                name = k;
                break;
            }
        }
        final String was = env.get(name);
        env.put(name, was == null || was.isBlank() ? dir : dir + File.pathSeparator + was);
    }

    private static boolean onPath(Map<String, String> env, String program) {
        for (String dir : folders(get(env, "PATH"))) {
            if (new File(dir, program).isFile()) return true;
        }
        return false;
    }
}
