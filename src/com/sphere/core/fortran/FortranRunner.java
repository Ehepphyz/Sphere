package com.sphere.core.fortran;

import com.sphere.components.variables.PythonProbe;
import com.sphere.components.variables.VariablesPanel;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a Fortran source and runs what comes out.
 *
 * Fortran has no interpreter to keep alive and no names left at run time, so a
 * run is all there is: what sat before the bracket configures the compiler, what
 * sat inside it reaches the program. Its variables reach the Variables tab only
 * if the program writes them down, which is what the Fortran helper is for, or
 * if it is stopped under the debugger.
 */
public final class FortranRunner {

    /** How long a build is given before it is called lost. */
    private static final long BUILD_TIMEOUT_SECONDS = 300;

    private FortranRunner() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void run(File source, List<String> compileFlags, List<String> arguments,
                           Path workingDirectory, SettingsManager settings) {
        final String compiler = settings == null ? "gfortran"
                              : settings.resolveTool("ENV_FC", "gfortran");
        if (compiler == null) {
            // A blank key is a decision the user wrote down; an absent tool is not.
            AppLogger.error(settings != null && settings.isDeclaredEmpty("ENV_FC")
                ? "ENV_FC is empty in settings.conf, which disables Fortran."
                : "No Fortran compiler: set ENV_FC in settings.conf.");
            return;
        }

        File binary = binaryFor(source);
        final List<String> flags = new ArrayList<>(compileFlags);
        flags.addAll(FortranBridge.flagsFor(source, compiler));
        if (!build(compiler, source, flags, binary, workingDirectory)) {
            return;
        }
        execute(binary, arguments, workingDirectory);
    }

    /**
     * Compiles without running, and hands back what the compiler refused, or null
     * when it accepted. This is what lets a program be checked as it is written.
     */
    public static String check(File source, SettingsManager settings) {
        final String compiler = settings == null ? "gfortran"
                              : settings.resolveTool("ENV_FC", "gfortran");
        if (compiler == null) {
            return settings != null && settings.isDeclaredEmpty("ENV_FC")
                ? "ENV_FC is empty in settings.conf, which disables Fortran."
                : "No Fortran compiler: set ENV_FC in settings.conf.";
        }
        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.add("-fsyntax-only");
        // A program that uses the bridge module needs its .mod to be checked.
        command.addAll(FortranBridge.includeFlags(source, compiler));
        command.add(source.getAbsolutePath());
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(source.getAbsoluteFile().getParentFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder said = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    said.append(line).append('\n');
                }
            }
            if (!process.waitFor(BUILD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "The Fortran check did not finish in "
                     + BUILD_TIMEOUT_SECONDS + " s.";
            }
            return process.exitValue() == 0 ? null : said.toString().stripTrailing();
        } catch (IOException unreachable) {
            return "Could not run " + compiler + ": " + unreachable.getMessage();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static File binaryFor(File source) {
        final String name = source.getName();
        final int dot = name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return new File(source.getAbsoluteFile().getParentFile(),
                        base + "_run" + (windows ? ".exe" : ""));
    }

    private static boolean build(String compiler, File source, List<String> flags,
                                 File binary, Path workingDirectory) {
        final long started = System.nanoTime();
        List<String> command = new ArrayList<>();
        command.add(compiler);
        command.addAll(flags);
        command.add(source.getAbsolutePath());
        command.add("-o");
        command.add(binary.getAbsolutePath());

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder said = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    said.append(line).append('\n');
                }
            }
            if (!process.waitFor(BUILD_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                AppLogger.error("The Fortran build did not finish in "
                                + BUILD_TIMEOUT_SECONDS + " s.");
                return false;
            }
            final long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
            int[] found = com.sphere.core.telemetry.CommandFacts.diagnosticsIn(said.toString());
            com.sphere.core.telemetry.RunLog.add(
                com.sphere.core.telemetry.RunRecord.compile("fortran", source.getPath(),
                    new File(compiler).getName(), flags, millis,
                    binary.isFile() ? binary.length() : com.sphere.core.telemetry.RunRecord.UNKNOWN,
                    process.exitValue(), found[0], found[1], false));
            if (process.exitValue() != 0) {
                AppLogger.error("Fortran build failed:\n" + said);
                return false;
            }
            if (said.length() > 0) {
                AppLogger.stream(said.toString().stripTrailing());
            }
            return true;
        } catch (IOException unreachable) {
            AppLogger.error("Could not run " + compiler + ": " + unreachable.getMessage());
            return false;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void execute(File binary, List<String> arguments, Path workingDirectory) {
        final long started = System.nanoTime();
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.addAll(arguments);

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            final String variables = PythonProbe.folder();
            if (variables != null) {
                builder.environment().put(PythonProbe.FOLDER_VARIABLE, variables);
            }
            com.sphere.core.bridge.Bridge.environment(builder.environment());
            Process process = builder.start();
            com.sphere.core.telemetry.ProcessMemory.Watcher memory =
                com.sphere.core.telemetry.ProcessMemory.watch(process);

            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    AppLogger.stream(line);
                }
            }
            final int status = process.waitFor();
            memory.close();
            com.sphere.core.telemetry.RunLog.add(
                com.sphere.core.telemetry.RunRecord.run("fortran", binary.getPath(),
                    binary.getName(), arguments,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - started),
                    status, memory.peakKilobytes(), false));
            if (status != 0) {
                AppLogger.error(binary.getName() + " ended with status " + status + ".");
            }
        } catch (IOException unreachable) {
            AppLogger.error("Could not run " + binary.getName() + ": "
                            + unreachable.getMessage());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            // The run is over, so anything it wrote down is final.
            try {
                VariablesPanel.instance().reread();
            } catch (RuntimeException unreadable) {
                AppLogger.error("Could not read the variables of this run: " + unreadable);
            }
        }
    }
}
