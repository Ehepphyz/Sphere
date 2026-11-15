package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Jupyter kernels installed on this machine, found where jupyter_client
 * looks for them, plus three of Sphere's own: its homemade Jupyter kernel
 * (sphere-hep, see {@link SphereJupyKernel}), always offered first whatever else is
 * installed; an ipykernel in the interpreter settings.conf names; and the stdio
 * driver.
 *
 * A notebook remembers the kernel it was written with (metadata.kernelspec);
 * when that kernel is installed here, it is the one the notebook opens on.
 */
public final class KernelSpecs {

    /** Name of the stdio driver, which runs without ipykernel. */
    public static final String SPHERE_DRIVER = "sphere-driver";
    /** Name of the ipykernel Sphere starts in PYTHON_EXEC when no installed spec fits. */
    public static final String SPHERE_IPYKERNEL = "sphere-ipykernel";
    /** Name of Sphere's homemade Jupyter kernel: the protocol on the standard library alone. */
    public static final String SPHERE_HEP = "sphere-hep";
    /** The launcher's place in the homemade kernel's argv, filled in when it starts. */
    public static final String LAUNCHER_PLACEHOLDER = "{sphere_kernel_launcher}";

    public record Spec(String name, String displayName, String language, List<String> argv,
                       Map<String, String> env, String interruptMode, Path resourceDir) {

        /** Runs on Sphere's stdio driver rather than on the Jupyter protocol. */
        public boolean isSphereDriver() {
            return SPHERE_DRIVER.equals(name);
        }

        /** Sphere's homemade kernel: the Jupyter protocol, Sphere's side channel and namespace tools. */
        public boolean isHomemade() {
            return SPHERE_HEP.equals(name);
        }

        /** A Python ipykernel: Sphere starts it through its bootstrap, which adds the side channel. */
        public boolean isIpykernel() {
            if (!"python".equalsIgnoreCase(language)) {
                return false;
            }
            for (int i = 0; i + 1 < argv.size(); i++) {
                if ("-m".equals(argv.get(i))) {
                    String module = argv.get(i + 1);
                    return module.equals("ipykernel_launcher") || module.equals("ipykernel");
                }
            }
            return false;
        }

        public String label() {
            return displayName + (isSphereDriver() || isHomemade() || SPHERE_IPYKERNEL.equals(name) ? "" : "  [" + name + "]");
        }
    }

    private KernelSpecs() {
    }

    /** Every kernel Sphere can start, in the order the switcher lists them. */
    public static List<Spec> discover(SettingsManager settings) {
        Map<String, Spec> found = new LinkedHashMap<>();
        String exec = settings == null ? null : settings.resolveTool("PYTHON_EXEC", "python");
        Path python = exec == null ? null : Path.of(exec);
        Path prefix = python == null ? null : prefixOf(python);

        if (python != null) {
            // Offered whatever is installed: it needs nothing but this Python.
            Map<String, String> env = new LinkedHashMap<>();
            String cxx = settings.resolveTool("GPP_DIR", "g++", "CLANGPP_DIR", "GCC_DIR");
            if (cxx != null) {
                env.put("SPHERE_CXX", cxx); // what %%cpp compiles with
            }
            found.put(SPHERE_HEP, new Spec(SPHERE_HEP, "Sphere HEP kernel (homemade, Python only)", "python",
                List.of(python.toString(), LAUNCHER_PLACEHOLDER, "-f", "{connection_file}"),
                Map.copyOf(env), "message", null));
        }
        if (python != null && hasIpykernel(prefix)) {
            found.put(SPHERE_IPYKERNEL, new Spec(SPHERE_IPYKERNEL, "Python (ipykernel, PYTHON_EXEC)", "python",
                List.of(python.toString(), "-Xfrozen_modules=off", "-m", "ipykernel_launcher", "-f", "{connection_file}"),
                Map.of(), "signal", null));
        }
        for (Path dir : searchPath(prefix)) {
            Path kernels = dir.resolve("kernels");
            if (!Files.isDirectory(kernels)) {
                continue;
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(kernels)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (found.containsKey(name)) {
                        continue; // the first directory that names a kernel wins, as in jupyter_client
                    }
                    Spec spec = read(entry, name, dir, python);
                    if (spec != null) {
                        found.put(name, spec);
                    }
                }
            } catch (IOException unreadable) {
                // a folder we may not list is a folder with no kernels for us
            }
        }
        found.put(SPHERE_DRIVER, new Spec(SPHERE_DRIVER, "Python (Sphere driver, no ipykernel)", "python",
                                          List.of(), Map.of(), "message", null));
        return new ArrayList<>(found.values());
    }

    /**
     * The kernel a notebook should open on: the one its metadata names when it is
     * installed, else an ipykernel for its language, else the stdio driver.
     */
    public static Spec choose(List<Spec> specs, Map<String, Object> notebookMetadata) {
        Map<String, Object> kernelspec = Json.map(notebookMetadata == null ? null : notebookMetadata.get("kernelspec"));
        String wanted = Json.string(kernelspec.get("name"), "").toLowerCase(Locale.ROOT);
        String language = Json.string(kernelspec.get("language"),
            Json.string(Json.map(notebookMetadata == null ? null : notebookMetadata.get("language_info")).get("name"), "python"));
        for (Spec s : specs) {
            if (!wanted.isEmpty() && s.name().equals(wanted)) {
                return s;
            }
        }
        for (Spec s : specs) {
            if (s.name().equals(SPHERE_IPYKERNEL) && "python".equalsIgnoreCase(language)) {
                return s;
            }
        }
        for (Spec s : specs) {
            if (s.isIpykernel() && "python".equalsIgnoreCase(language)) {
                return s;
            }
        }
        for (Spec s : specs) {
            // Without ipykernel, the homemade kernel: the whole protocol, where the driver has only a part
            if (s.isHomemade() && "python".equalsIgnoreCase(language)) {
                return s;
            }
        }
        for (Spec s : specs) {
            if (!s.isSphereDriver() && s.language().equalsIgnoreCase(language)) {
                return s;
            }
        }
        for (Spec s : specs) {
            if (s.isSphereDriver()) {
                return s;
            }
        }
        return specs.isEmpty() ? null : specs.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Spec read(Path dir, String name, Path dataDir, Path python) {
        Path file = dir.resolve("kernel.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> json = Json.object(Files.readString(file, StandardCharsets.UTF_8));
            List<String> argv = new ArrayList<>();
            for (Object a : Json.list(json.get("argv"))) {
                argv.add(String.valueOf(a));
            }
            if (argv.isEmpty()) {
                return null;
            }
            // "python" in a spec means the interpreter it was installed with, as jupyter_client reads it:
            // the environment's own when the spec lives in one, else the one settings.conf names.
            String first = argv.get(0);
            if (first.equals("python") || first.equals("python3") || first.matches("python3\\.\\d+")) {
                Path envPython = interpreterOf(dataDir.getParent() == null ? null : dataDir.getParent().getParent());
                if (envPython != null) {
                    argv.set(0, envPython.toString());
                } else if (python != null) {
                    argv.set(0, python.toString());
                }
            }
            Map<String, String> env = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : Json.map(json.get("env")).entrySet()) {
                env.put(e.getKey(), String.valueOf(e.getValue()));
            }
            return new Spec(name, Json.string(json.get("display_name"), name),
                            Json.string(json.get("language"), ""), List.copyOf(argv), env,
                            Json.string(json.get("interrupt_mode"), "signal"), dir);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Where jupyter_client looks: JUPYTER_PATH, the environment, the user, the system. */
    static List<Path> searchPath(Path pythonPrefix) {
        List<Path> dirs = new ArrayList<>();
        String jp = System.getenv("JUPYTER_PATH");
        if (jp != null) {
            for (String p : jp.split(java.io.File.pathSeparator)) {
                if (!p.isBlank()) {
                    dirs.add(Path.of(p));
                }
            }
        }
        if (pythonPrefix != null) {
            dirs.add(pythonPrefix.resolve("share").resolve("jupyter"));
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String home = System.getProperty("user.home");
        if (windows) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) {
                dirs.add(Path.of(appdata, "jupyter"));
            }
            String programdata = System.getenv("PROGRAMDATA");
            if (programdata != null) {
                dirs.add(Path.of(programdata, "jupyter"));
            }
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            dirs.add(Path.of(home, "Library", "Jupyter"));
            dirs.add(Path.of("/usr/local/share/jupyter"));
            dirs.add(Path.of("/usr/share/jupyter"));
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            dirs.add(xdg != null && !xdg.isBlank() ? Path.of(xdg, "jupyter") : Path.of(home, ".local", "share", "jupyter"));
            dirs.add(Path.of("/usr/local/share/jupyter"));
            dirs.add(Path.of("/usr/share/jupyter"));
        }
        return dirs;
    }

    /** The environment an interpreter belongs to: Scripts\python.exe or bin/python sit one level in. */
    static Path prefixOf(Path python) {
        Path parent = python.toAbsolutePath().getParent();
        if (parent == null) {
            return null;
        }
        String n = parent.getFileName() == null ? "" : parent.getFileName().toString();
        if (n.equalsIgnoreCase("Scripts") || n.equals("bin")) {
            return parent.getParent();
        }
        return parent;
    }

    static Path interpreterOf(Path prefix) {
        if (prefix == null) {
            return null;
        }
        for (Path candidate : List.of(prefix.resolve("Scripts").resolve("python.exe"), prefix.resolve("python.exe"),
                                      prefix.resolve("bin").resolve("python3"), prefix.resolve("bin").resolve("python"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Looked for on disk: starting an interpreter just to ask would cost a second per notebook. */
    public static boolean hasIpykernel(Path prefix) {
        if (prefix == null) {
            return false;
        }
        if (Files.isRegularFile(prefix.resolve("Lib").resolve("site-packages").resolve("ipykernel").resolve("__init__.py"))) {
            return true;
        }
        Path lib = prefix.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (DirectoryStream<Path> versions = Files.newDirectoryStream(lib, "python3*")) {
                for (Path v : versions) {
                    if (Files.isRegularFile(v.resolve("site-packages").resolve("ipykernel").resolve("__init__.py"))) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
                // no lib folder we can read
            }
        }
        return false;
    }
}
