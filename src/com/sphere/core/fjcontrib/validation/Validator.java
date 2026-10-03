package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.Precision;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the ported examples and compares their output with the C++'s, as
 * fjcontrib's "make check" does (lines starting with '#' ignored), and more
 * finely: when the text is not identical, every number of every line is
 * compared, so that a last-digit rounding is told from a different result.
 *
 * The reference files and the data are those of the fjcontrib release, kept
 * in the jar under refdata/ with the release's own layout; a directory with
 * that layout can be given instead (the system property fjcontrib.dir).
 */
public final class Validator {

    private static final String RESOURCES = "/com/sphere/core/fjcontrib/validation/refdata/";

    private Validator() {
    }

    /** What one example gave. */
    public record Outcome(Example example, boolean identical, int lines, int differingLines, int numbers,
                          int differingNumbers, double maxRelativeDeviation, boolean textDiffers,
                          String firstDifference, String error, double millis) {

        /** Identical text, or only numbers differing below the relative tolerance. */
        public boolean agrees(double tolerance) {
            return error == null && (identical || (!textDiffers && maxRelativeDeviation <= tolerance));
        }
    }

    /** The text of a file of the release, from the jar or from fjcontrib.dir. */
    public static String resource(String relative) throws IOException {
        final String dir = System.getProperty("fjcontrib.dir");
        if (dir != null) {
            final Path p = Path.of(dir, relative);
            if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
        }
        try (InputStream s = Validator.class.getResourceAsStream(RESOURCES + relative)) {
            if (s == null) throw new IOException(relative + " is not in the jar (and fjcontrib.dir is not set)");
            return new String(s.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    public static boolean haveReference(Example ex) {
        try {
            resource(ex.contrib() + "/" + ex.name() + ".ref");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The output of the port, as check.sh keeps it (no '#' lines). */
    public static String output(Example ex) throws Exception {
        final String data = resource("data/" + ex.data());
        final Cout cout = new Cout();
        final Precision saved = Precision.defaultPrecision();
        final int[] ghostSeeds = com.sphere.core.fastjet.GhostedAreaSpec.sharedGeneratorStatus();
        synchronized (Validator.class) {
            try {
                Precision.setDefault(Precision.DOUBLE);
                // each C++ example is a process of its own: rand() starts from
                // seed 1 and FastJet's ghosts from their default seeds
                com.sphere.core.fjcontrib.internal.GlibcRandom.srand(1);
                com.sphere.core.fastjet.GhostedAreaSpec.setSharedGeneratorStatus(new int[]{12345, 67890});
                ex.program().run(new BufferedReader(new StringReader(data)), cout, ex.args());
            } finally {
                Precision.setDefault(saved);
                com.sphere.core.fastjet.GhostedAreaSpec.setSharedGeneratorStatus(ghostSeeds);
            }
        }
        return cout.text();
    }

    public static Outcome run(Example ex) {
        final long t0 = System.nanoTime();
        final List<String> got;
        final List<String> ref;
        try {
            ref = lines(resource(ex.contrib() + "/" + ex.name() + ".ref"));
        } catch (IOException e) {
            return new Outcome(ex, false, 0, 0, 0, 0, 0, false, null, "no reference: " + e.getMessage(), 0);
        }
        try {
            got = lines(output(ex));
        } catch (Throwable e) {
            return new Outcome(ex, false, ref.size(), ref.size(), 0, 0, 0, true, null,
                e.getClass().getSimpleName() + ": " + e.getMessage(), (System.nanoTime() - t0) / 1e6);
        }
        final double ms = (System.nanoTime() - t0) / 1e6;
        int differingLines = 0;
        int numbers = 0;
        int differingNumbers = 0;
        double maxDev = 0;
        boolean textDiffers = got.size() != ref.size();
        String first = null;
        final int n = Math.max(got.size(), ref.size());
        for (int k = 0; k < n; k++) {
            final String a = k < got.size() ? got.get(k) : "";
            final String b = k < ref.size() ? ref.get(k) : "";
            final String[] ta = tokens(a);
            final String[] tb = tokens(b);
            for (String t : tb) if (isNumber(t)) numbers++;
            if (a.equals(b)) continue;
            differingLines++;
            if (first == null) first = "line " + (k + 1) + "\n    Java: " + a + "\n    C++ : " + b;
            if (ta.length != tb.length) {
                textDiffers = true;
                continue;
            }
            for (int t = 0; t < ta.length; t++) {
                if (ta[t].equals(tb[t])) continue;
                if (isNumber(ta[t]) && isNumber(tb[t])) {
                    final double x = Double.parseDouble(ta[t]);
                    final double y = Double.parseDouble(tb[t]);
                    differingNumbers++;
                    final double scale = Math.max(Math.abs(x), Math.abs(y));
                    final double dev = scale == 0 ? 0 : Math.abs(x - y) / scale;
                    if (!(dev <= maxDev)) maxDev = Double.isNaN(dev) ? Double.POSITIVE_INFINITY : dev;
                } else {
                    textDiffers = true;
                }
            }
        }
        return new Outcome(ex, differingLines == 0, ref.size(), differingLines, numbers, differingNumbers, maxDev,
            textDiffers, first, null, ms);
    }

    private static List<String> lines(String text) throws IOException {
        final List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                out.add(line.replaceAll("\\s+$", ""));
            }
        }
        return out;
    }

    private static String[] tokens(String line) {
        final String t = line.trim();
        return t.isEmpty() ? new String[0] : t.split("[\\s,;:=()\\[\\]{}|]+");
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        final char c = s.charAt(0);
        if (!(Character.isDigit(c) || c == '-' || c == '+' || c == '.')) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
