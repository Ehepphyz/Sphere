package com.sphere.core.rootbackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Looking inside a pipeline while it runs.
 *
 * A compiled pipeline answers one number, and when that number is wrong there
 * is nothing to look at: no console to print to, and a debugger cannot easily
 * be pointed at a library the interpreter loaded. Three ways in are offered
 * instead. Watch calls it once and hands back every value the user named with
 * SPHERE_WATCH, which is a variable inspector. Health sweeps the inputs and
 * counts the answers that are not numbers, which is how a pipeline that fails
 * on one entry in ten thousand is found before it has quietly spoiled a
 * histogram. Scan walks one input across a range while the others are held,
 * and a step or a spike in that curve is what a wrong branch looks like from
 * the outside.
 *
 * All three run in a program built for the purpose. Inspecting a pipeline must
 * not disturb the library a session already has loaded, and a pipeline that
 * crashes must take nothing with it but its own process.
 */
public final class RootPipelineProbe {

    private static final int RUN_TIMEOUT_SECONDS = 120;

    /** One value the pipeline named while it ran. */
    public record Watched(String name, double value) {
        @Override
        public String toString() {
            return name + " = " + value;
        }
    }

    /** What a sweep of the inputs found. */
    public record Health(long tried, long notANumber, long infinite,
                         double smallest, double largest, double mean,
                         boolean succeeded, String message) {

        /** True when nothing came back that was not a number. */
        public boolean isClean() {
            return succeeded && notANumber == 0 && infinite == 0;
        }

        public String summary() {
            if (!succeeded) {
                return message;
            }
            final long good = tried - notANumber - infinite;
            return good + " of " + tried + " answered a number"
                 + (notANumber > 0 ? ",  " + notANumber + " NaN" : "")
                 + (infinite > 0 ? ",  " + infinite + " infinite" : "")
                 + String.format(Locale.ROOT, ",  from %.6g to %.6g,  mean %.6g",
                                 smallest, largest, mean);
        }
    }

    /** One point of a scan. */
    public record Point(double x, double y) { }

    /** What a scan did, and what is odd about it. */
    public record Scan(List<Point> points, List<String> findings,
                       boolean succeeded, String message) { }

    private RootPipelineProbe() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */

    /** Calls the pipeline once and reads back every value it named. */
    public static List<Watched> watch(RootUserCompiler compiler,
                                      RootPipelineManifest manifest,
                                      Path source, double[] arguments) {
        List<Watched> found = new ArrayList<>();
        List<String> call = new ArrayList<>();
        call.add("watch");
        for (double one : arguments) {
            call.add(Double.toString(one));
        }
        final String answer = run(compiler, manifest, source, call);
        if (answer == null) {
            return found;
        }
        for (String line : answer.split("\\R")) {
            final int at = line.lastIndexOf('=');
            if (at <= 0) {
                continue;
            }
            try {
                found.add(new Watched(line.substring(0, at).trim(),
                                      number(line.substring(at + 1))));
            } catch (NumberFormatException notAValue) {
                // A line that is not a value is not worth reporting as one.
            }
        }
        return found;
    }

    /** Sweeps every input across a range and counts what came back wrong. */
    public static Health health(RootUserCompiler compiler, RootPipelineManifest manifest,
                                Path source, int points, double low, double high) {
        final String answer = run(compiler, manifest, source,
            List.of("probe", "-1", Double.toString(low), Double.toString(high),
                    Integer.toString(points), "1"));
        if (answer == null) {
            return new Health(0, 0, 0, 0, 0, 0, false,
                "The pipeline could not be built for inspection.");
        }
        long tried = 0, nan = 0, inf = 0;
        double min = 0, max = 0, mean = 0;
        for (String line : answer.split("\\R")) {
            final int at = line.indexOf('=');
            if (at <= 0) {
                continue;
            }
            final String key = line.substring(0, at).trim();
            final double value;
            try {
                value = number(line.substring(at + 1));
            } catch (NumberFormatException notAValue) {
                continue;
            }
            switch (key) {
                case "tried" -> tried = (long) value;
                case "nan" -> nan = (long) value;
                case "inf" -> inf = (long) value;
                case "min" -> min = value;
                case "max" -> max = value;
                case "mean" -> mean = value;
                default -> { }
            }
        }
        return new Health(tried, nan, inf, min, max, mean, true, "");
    }

    /**
     * Walks one input across a range while the others are held.
     *
     * The curve is read back as well as returned: a value that is not a number,
     * a jump far larger than its neighbors, or a stretch that never moves are
     * each reported, because those are what a wrong branch, a missing guard and
     * a forgotten argument look like from here.
     */
    public static Scan scan(RootUserCompiler compiler, RootPipelineManifest manifest,
                            Path source, int which, double low, double high,
                            int points, double held) {
        final String answer = run(compiler, manifest, source,
            List.of("probe", Integer.toString(which), Double.toString(low),
                    Double.toString(high), Integer.toString(points),
                    Double.toString(held)));
        if (answer == null) {
            return new Scan(List.of(), List.of(), false,
                "The pipeline could not be built for inspection.");
        }
        List<Point> curve = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            final String[] pair = line.trim().split("\\s+");
            if (pair.length < 2) {
                continue;
            }
            try {
                curve.add(new Point(number(pair[0]), number(pair[1])));
            } catch (NumberFormatException notAPoint) {
                // A line that is not a point is skipped rather than guessed at.
            }
        }
        return new Scan(curve, read(curve), true, "");
    }

    /**
     * A number as C++ wrote it, NaN and infinity included.
     *
     * std::to_string writes "nan", "inf" and "-inf"; Java's own reader wants
     * "NaN" and "Infinity" and throws on the others. Letting it throw would
     * drop exactly the values worth looking at, since a pipeline is inspected
     * because something in it is not a number.
     */
    private static double number(String text) {
        final String bare = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (bare.endsWith("nan")) {
            return Double.NaN;
        }
        if (bare.equals("inf") || bare.equals("infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (bare.equals("-inf") || bare.equals("-infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        return Double.parseDouble(bare);
    }

    /** What is worth saying about a curve. */
    private static List<String> read(List<Point> curve) {
        List<String> findings = new ArrayList<>();
        if (curve.isEmpty()) {
            return findings;
        }
        int bad = 0;
        double biggest = 0.0;
        int biggestAt = -1;
        double previous = Double.NaN;
        boolean flat = true;

        for (int i = 0; i < curve.size(); i++) {
            final double y = curve.get(i).y();
            if (Double.isNaN(y) || Double.isInfinite(y)) {
                bad++;
                previous = Double.NaN;
                continue;
            }
            if (!Double.isNaN(previous)) {
                if (previous != y) {
                    flat = false;
                }
                final double jump = Math.abs(y - previous);
                if (jump > biggest) {
                    biggest = jump;
                    biggestAt = i;
                }
            }
            previous = y;
        }

        if (bad > 0) {
            findings.add(bad + " of " + curve.size()
                + " points are not a number. The guard for that case is missing.");
        }
        if (flat && curve.size() > 2) {
            findings.add("The answer never moves across the whole range. "
                + "This input may not be reaching the expression.");
        }
        // A jump many times the typical one is a branch, not a curve.
        double total = 0.0;
        int steps = 0;
        for (int i = 1; i < curve.size(); i++) {
            final double a = curve.get(i - 1).y();
            final double b = curve.get(i).y();
            if (!Double.isNaN(a) && !Double.isNaN(b)
                && !Double.isInfinite(a) && !Double.isInfinite(b)) {
                total += Math.abs(b - a);
                steps++;
            }
        }
        if (steps > 4 && biggestAt > 0) {
            final double typical = total / steps;
            if (typical > 0.0 && biggest > 20.0 * typical) {
                findings.add(String.format(Locale.ROOT,
                    "A jump of %.6g near x = %.6g, about %.0f times the usual step. "
                    + "Something changes branch there.",
                    biggest, curve.get(biggestAt).x(), biggest / typical));
            }
        }
        return findings;
    }

    /* ------------------------------------------------------------------ */

    /** Builds the inspection program if it has to, then runs it. */
    private static String run(RootUserCompiler compiler, RootPipelineManifest manifest,
                              Path source, List<String> arguments) {
        if (!RootPipelineTemplates.canBeTimed(manifest)) {
            return null;
        }
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("sphere-probe-");
            Path program = workspace.resolve(manifest.name + "_probe.cpp");
            Files.writeString(program,
                RootPipelineTemplates.inspectProgram(manifest, source),
                StandardCharsets.UTF_8);
            Path binary = workspace.resolve(manifest.name + "_probe");

            RootUserCompiler.Build built =
                compiler.buildProgram(program, binary, manifest.flags());
            if (!built.succeeded()) {
                return null;
            }

            List<String> command = new ArrayList<>();
            command.add(binary.toAbsolutePath().toString());
            command.addAll(arguments);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder said = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    said.append(line).append('\n');
                }
            }
            if (!process.waitFor(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            // A pipeline that stopped part way still said something before it
            // did, and that is usually the most interesting part.
            return said.toString();

        } catch (IOException cannotRun) {
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            remove(workspace);
        }
    }

    private static void remove(Path folder) {
        if (folder == null) {
            return;
        }
        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(one -> {
                try {
                    Files.deleteIfExists(one);
                } catch (IOException leaveIt) {
                    // Nothing here belongs to the user.
                }
            });
        } catch (IOException leaveIt) {
            // A leftover in the temp folder is not worth a message.
        }
    }
}
