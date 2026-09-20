package com.sphere.core.exec;

import com.sphere.components.variables.VariablesPanel;
import com.sphere.core.cpp.CppBackend;
import com.sphere.core.julia.JuliaSession;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Runs what a permanent mode has collected.
 *
 * Three of the four languages have an interpreter that can be held open, so a
 * block leaves its names behind for the next one. Fortran has none: its block is
 * wrapped into a program, compiled and run, and nothing survives it.
 */
public final class ExecRunner {

    /** How long a C++ block may take inside the interpreter. */
    private static final long CLING_TIMEOUT_MS = 120000;

    private ExecRunner() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    // ---- Python --------------------------------------------------------------

    public static void python(String code, SettingsManager settings) {
        try {
            ConsolePython.instance(settings).run(code);
        } catch (IOException unavailable) {
            AppLogger.error("Could not start the Python interpreter: "
                            + unavailable.getMessage());
        }
    }

    // ---- Julia ---------------------------------------------------------------

    public static void julia(String code, SettingsManager settings) {
        JuliaSession session = JuliaSession.instance(settings);
        try {
            session.start();
        } catch (IOException unavailable) {
            AppLogger.error(unavailable.getMessage());
            return;
        }
        session.run(code);
    }

    // ---- C++, held open ------------------------------------------------------

    /** Runs the block inside ROOT's interpreter, where its names stay defined. */
    public static void cling(String code, RootBackend root) {
        if (root == null) {
            AppLogger.error("ROOT is not available, so the C++ interpreter cannot be"
                            + " reached. Use \":exec build\" to compile the block instead.");
            return;
        }
        final String answer = root.executeClingAwait(code, CLING_TIMEOUT_MS);
        if (answer == null) {
            AppLogger.error("The C++ interpreter did not answer. Use \":exec build\""
                            + " to compile the block instead.");
            return;
        }
        if (!answer.isBlank()) {
            AppLogger.stream(answer.stripTrailing());
        }
        reread();
    }

    // ---- C++, compiled -------------------------------------------------------

    /** Compiles the block as a program and runs it. Nothing stays defined after. */
    public static void build(String code, CppBackend backend) {
        if (backend == null) {
            AppLogger.error("The C++ backend is not available.");
            return;
        }
        Path folder = null;
        try {
            folder = Files.createTempDirectory("sphere-exec");
            Path source = folder.resolve("sphere_exec.cpp");
            Files.writeString(source, code.endsWith("\n") ? code : code + "\n",
                              StandardCharsets.UTF_8);
            backend.executeSource(source.toString(), List.of(), List.of(), false, null);
            reread();
        } catch (IOException unwritable) {
            AppLogger.error("Could not write the C++ block: " + unwritable.getMessage());
        } finally {
            discard(folder);
        }
    }

    // ---- Fortran -------------------------------------------------------------

    /**
     * Compiles the block and runs it. A block that does not declare a program of
     * its own is taken as the body of one, so that a few lines of arithmetic run
     * without the ceremony around them.
     */
    public static void fortran(String code, Path workingDirectory, SettingsManager settings) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("sphere-exec");
            Path source = folder.resolve("sphere_exec.f90");
            Files.writeString(source, wrap(code), StandardCharsets.UTF_8);
            com.sphere.core.fortran.FortranRunner.run(source.toFile(), List.of(),
                List.of(), workingDirectory, settings);
        } catch (IOException unwritable) {
            AppLogger.error("Could not write the Fortran block: " + unwritable.getMessage());
        } finally {
            discard(folder);
        }
    }

    /** Compiles the block without running it; null when the compiler accepted it. */
    public static String checkFortran(String code, SettingsManager settings) {
        Path folder = null;
        try {
            folder = Files.createTempDirectory("sphere-check");
            Path source = folder.resolve("sphere_exec.f90");
            Files.writeString(source, wrap(code), StandardCharsets.UTF_8);
            final String refused =
                com.sphere.core.fortran.FortranRunner.check(source.toFile(), settings);
            // The compiler names a temporary file nobody can open; the location it
            // gives refers to the wrapped source, not to what was typed.
            return refused == null ? null
                 : refused.replaceAll("(?m)^.*sphere_exec\\.f90:[0-9:]*\\s*$", "")
                          .replaceAll("(?m)^\\s*$\n", "").strip();
        } catch (IOException unwritable) {
            return "Could not write the Fortran block: " + unwritable.getMessage();
        } finally {
            discard(folder);
        }
    }

    private static String wrap(String code) {
        if (declaresProgram(code)) {
            return code.endsWith("\n") ? code : code + "\n";
        }
        return "program sphere_exec\n" + code
             + (code.endsWith("\n") ? "" : "\n")
             + "end program sphere_exec\n";
    }

    private static boolean declaresProgram(String code) {
        for (String line : code.split("\n", -1)) {
            final String cleaned = line.strip().toLowerCase();
            if (cleaned.startsWith("program ") || cleaned.equals("program")) {
                return true;
            }
        }
        return false;
    }

    // ---- shared --------------------------------------------------------------

    private static void reread() {
        try {
            VariablesPanel.instance().reread();
        } catch (RuntimeException unreadable) {
            AppLogger.error("Could not read the variables of this block: " + unreadable);
        }
    }

    private static void discard(Path folder) {
        if (folder == null) {
            return;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException leftBehind) {
            // A temporary folder the system will collect on its own.
        }
    }
}
