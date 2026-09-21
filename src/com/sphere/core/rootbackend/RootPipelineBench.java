package com.sphere.core.rootbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a pipeline costs, and what the build flags are worth.
 *
 * The form offers -O3, -march=native and OpenMP, and until now nothing said
 * whether any of them helped. They are not free choices: -march=native ties the
 * library to one kind of processor, and on some code it is slower than plain
 * -O3. So each set is built and run, and the answer is a number rather than an
 * opinion.
 *
 * The measurement happens outside the engine, in a small program built for the
 * purpose. Timing through the interpreter would time the interpreter, and
 * loading three differently built copies of one library into a running ROOT
 * would leave the process holding the wrong one.
 */
public final class RootPipelineBench {

    private static final int RUN_TIMEOUT_SECONDS = 120;

    /** One set of flags, and what the pipeline cost under it. */
    public record Timing(String label, List<String> flags, double nanosPerCall,
                         boolean succeeded, String message) {

        /** Nanoseconds as a physicist reads them, or the reason there is none. */
        public String reading() {
            if (!succeeded) {
                return "--";
            }
            if (nanosPerCall < 1.0) {
                return String.format("%.3f ns", nanosPerCall);
            }
            if (nanosPerCall < 1000.0) {
                return String.format("%.2f ns", nanosPerCall);
            }
            return String.format("%.2f us", nanosPerCall / 1000.0);
        }
    }

    private RootPipelineBench() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The sets of flags worth comparing for this pipeline. */
    public static List<List<String>> flagSets(RootPipelineManifest manifest) {
        List<List<String>> sets = new ArrayList<>();
        List<String> links = RootPipelineModules.linkFlagsOf(manifest.modules);

        sets.add(with(links, "-O0"));
        sets.add(with(links, "-O3"));
        sets.add(with(links, "-O3", "-march=native"));
        if (manifest.openMp) {
            sets.add(with(links, "-O3", "-fopenmp"));
        }
        return sets;
    }

    private static List<String> with(List<String> links, String... flags) {
        List<String> out = new ArrayList<>(List.of(flags));
        out.addAll(links);
        return out;
    }

    /** The name a set of flags goes by in the table. */
    private static String labelOf(List<String> flags) {
        StringBuilder out = new StringBuilder();
        for (String flag : flags) {
            if (flag.startsWith("-l")) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(flag);
        }
        return out.length() == 0 ? "(no flags)" : out.toString();
    }

    /**
     * Builds and times the pipeline once per set of flags.
     *
     * Everything is written into a folder of its own and removed afterwards, so
     * a measurement leaves nothing behind in the user's includes/.
     */
    public static List<Timing> compare(RootUserCompiler compiler,
                                       RootPipelineManifest manifest,
                                       Path source, long rounds) {
        List<Timing> results = new ArrayList<>();
        if (!RootPipelineTemplates.canBeTimed(manifest)) {
            results.add(new Timing("", List.of(), 0.0, false,
                "Only a transform or a filter taking numbers can be timed."));
            return results;
        }

        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("sphere-bench-");
            Path program = workspace.resolve(manifest.name + "_bench.cpp");
            Files.writeString(program,
                RootPipelineTemplates.benchProgram(manifest, source), StandardCharsets.UTF_8);

            int n = 0;
            for (List<String> flags : flagSets(manifest)) {
                Path binary = workspace.resolve(manifest.name + "_bench_" + (n++));
                RootUserCompiler.Build built = compiler.buildProgram(program, binary, flags);
                if (!built.succeeded()) {
                    results.add(new Timing(labelOf(flags), flags, 0.0, false,
                        firstProblem(built)));
                    continue;
                }
                results.add(time(binary, flags, rounds));
            }
        } catch (IOException cannotWrite) {
            results.add(new Timing("", List.of(), 0.0, false,
                "Could not prepare the measurement: " + cannotWrite.getMessage()));
        } finally {
            remove(workspace);
        }
        return results;
    }

    /** Runs one built program and reads the nanoseconds it prints. */
    private static Timing time(Path binary, List<String> flags, long rounds) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                binary.toAbsolutePath().toString(), Long.toString(Math.max(1L, rounds)));
            builder.redirectErrorStream(true);
            Process process = builder.start();

            String last = "";
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String read;
                while ((read = reader.readLine()) != null) {
                    if (!read.isBlank()) {
                        last = read.strip();
                    }
                }
            }
            if (!process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Timing(labelOf(flags), flags, 0.0, false,
                    "The measurement did not finish. Try fewer rounds.");
            }
            if (process.exitValue() != 0) {
                return new Timing(labelOf(flags), flags, 0.0, false,
                    "The pipeline stopped during the measurement.");
            }
            return new Timing(labelOf(flags), flags, Double.parseDouble(last), true, "");

        } catch (NumberFormatException notANumber) {
            return new Timing(labelOf(flags), flags, 0.0, false,
                "The measurement answered something that is not a number.");
        } catch (IOException cannotRun) {
            return new Timing(labelOf(flags), flags, 0.0, false, cannotRun.getMessage());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return new Timing(labelOf(flags), flags, 0.0, false, "Interrupted.");
        }
    }

    /**
     * The fastest of a set of results, which is what the flags should be.
     *
     * Null when nothing was measured, so a caller can tell an empty table from
     * a table where everything failed.
     */
    public static Timing best(List<Timing> results) {
        Timing best = null;
        for (Timing one : results) {
            if (one.succeeded() && (best == null || one.nanosPerCall() < best.nanosPerCall())) {
                best = one;
            }
        }
        return best;
    }

    /** How much faster than the slowest measurement, for the table's last column. */
    public static String relativeTo(Timing one, List<Timing> results) {
        if (!one.succeeded()) {
            return "";
        }
        double slowest = 0.0;
        for (Timing other : results) {
            if (other.succeeded() && other.nanosPerCall() > slowest) {
                slowest = other.nanosPerCall();
            }
        }
        if (slowest <= 0.0 || one.nanosPerCall() <= 0.0) {
            return "";
        }
        return String.format("%.1fx", slowest / one.nanosPerCall());
    }

    /** The first line of the compiler's answer that says something. */
    private static String firstProblem(RootUserCompiler.Build built) {
        for (String line : built.lines()) {
            if (line.contains("error")) {
                return line;
            }
        }
        List<String> lines = built.lines();
        return lines.isEmpty() ? "The compiler refused it." : lines.get(0);
    }

    /** Takes the working folder away, whatever happened in it. */
    private static void remove(Path folder) {
        if (folder == null) {
            return;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(one -> {
                try {
                    Files.deleteIfExists(one);
                } catch (IOException leaveIt) {
                    // A file the system still holds is left to the temp folder.
                }
            });
        } catch (IOException leaveIt) {
            // Nothing here is the user's, so a leftover is not worth reporting.
        }
    }
}
