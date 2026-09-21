package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A parton distribution read and interpolated by Sphere itself.
 *
 * The global fits -- CT18, NNPDF, MSHT -- are not formulas. They are grids of
 * numbers, and reading one has until now meant installing LHAPDF and linking
 * against it. The data files are plain text and their format is documented by
 * the code that writes them, so the library is not actually required: what is
 * required is the reading, and the interpolation between the knots. Both are
 * here, and a pipeline that uses them needs nothing installed.
 *
 * The interpolation follows LHAPDF's own: natural logarithms of x and Q squared,
 * a cubic Hermite polynomial in log x whose coefficients are computed once when
 * the file is read, and a second cubic in log Q squared whose slopes are
 * estimated from the neighboring knots. Reproducing it exactly matters -- a
 * result that depends on whose interpolator was used is not a result -- so the
 * formulas are the same ones, and the test compares against knots the file
 * itself carries.
 *
 * Subgrids are what a heavy-quark threshold looks like in a file: the Q knots
 * of each block are concatenated, and a repeated Q value marks the seam where
 * the distribution is allowed to jump. The seam is detected the same way here.
 */
public final class RootPdfGrid {

    /** The particle codes a grid can carry, in the order the interpolator wants. */
    public static final int[] FLAVORS = {-6, -5, -4, -3, -2, -1, 21, 1, 2, 3, 4, 5, 6, 22};

    /**
     * Which slope estimate feeds the cubic.
     *
     * LHAPDF averages the two one-sided slopes without weighting them, which is
     * second order only where the two spacings are equal. Real grids are log
     * spaced at small x and linearly spaced towards one, so at the join the
     * spacings differ and the average loses an order, taking the cubic with it.
     * LHAPDF reproduces that exactly, which is what a result meant to be
     * checked against LHAPDF needs. WEIGHTED uses the three-point estimate for
     * an uneven mesh instead and recovers the order.
     */
    public enum Accuracy { LHAPDF, WEIGHTED }

    /**
     * Which rule joins the knots.
     *
     * LHAPDF offers four and defaults to the cubic in the logarithms, which is
     * what a published number was computed with unless its author said
     * otherwise. The linear ones are faster and visibly coarser; the two that
     * work in x and Q squared directly rather than their logarithms are there
     * for a grid whose knots are spaced linearly, where the logarithm gains
     * nothing.
     */
    public enum Interpolation { LOG_BICUBIC, BICUBIC, LOG_BILINEAR, BILINEAR }

    /**
     * What happens outside the grid.
     *
     * CONTINUATION is LHAPDF's default and the one its published numbers use:
     * the shape is carried on, and below the smallest scale an anomalous
     * dimension is estimated so the distribution falls to zero rather than
     * running away. NEAREST answers the closest point on the edge, which is
     * flat and honest about knowing nothing. ERROR refuses, which is what a
     * calculation that must not silently leave its range wants. SPHERE is this
     * program's own simpler continuation.
     */
    public enum Extrapolation { CONTINUATION, NEAREST, ERROR, SPHERE }

    /** Something about the grid worth saying before it is trusted. */
    public record Finding(boolean fatal, String message) {
        @Override
        public String toString() {
            return (fatal ? "error: " : "warning: ") + message;
        }
    }

    /** How the slopes are estimated for this grid. */
    private Accuracy accuracy = Accuracy.LHAPDF;

    /** Which rule joins the knots, and what happens outside them. */
    private Interpolation interpolation = Interpolation.LOG_BICUBIC;
    private Extrapolation extrapolation = Extrapolation.CONTINUATION;

    /** What the file says about itself, before the first separator. */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private double[] xs;
    private double[] logxs;
    private double[] q2s;
    private double[] logq2s;
    private int[] pids;

    /** xf values, indexed x-major then Q then flavor. */
    private double[] grid;

    /** Hermite coefficients in log x: four per (x, Q, flavor). */
    private double[] coeffs;

    /** Where each particle code sits in pids, or -1 when the grid has none. */
    private final Map<Integer, Integer> lookup = new LinkedHashMap<>();

    private Path source;

    private RootPdfGrid() { }

    /* ------------------------------------------------------------------ */
    /* Reading                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Reads one member file.
     *
     * The blocks are separated by a line holding only three dashes. The first
     * holds the metadata; each one after it holds the x knots, the Q knots, the
     * flavor codes, and then one line per (x, Q) pair carrying one value per
     * flavor. The x knots and the flavors repeat identically in every block,
     * and the file is rejected when they do not: a mismatch there would put
     * every value that follows in the wrong place, silently.
     */
    public static RootPdfGrid read(Path file) throws IOException {
        return read(file, Accuracy.LHAPDF);
    }

    /** Reads one member file with the slope estimate named. */
    public static RootPdfGrid read(Path file, Accuracy accuracy) throws IOException {
        return read(file, accuracy, Interpolation.LOG_BICUBIC, Extrapolation.CONTINUATION);
    }

    /** Reads one member file with every choice named. */
    public static RootPdfGrid read(Path file, Accuracy accuracy,
                                   Interpolation interpolation,
                                   Extrapolation extrapolation) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("No such grid file: " + file);
        }
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        RootPdfGrid pdf = new RootPdfGrid();
        pdf.source = file;
        pdf.accuracy = accuracy == null ? Accuracy.LHAPDF : accuracy;
        pdf.interpolation = interpolation == null ? Interpolation.LOG_BICUBIC : interpolation;
        pdf.extrapolation = extrapolation == null ? Extrapolation.CONTINUATION : extrapolation;

        List<Double> xKnots = new ArrayList<>();
        List<Double> q2Knots = new ArrayList<>();
        List<Integer> flavors = new ArrayList<>();
        // One entry per block: the values as they were read, x-major then Q.
        List<double[]> blockValues = new ArrayList<>();
        List<Integer> blockQCount = new ArrayList<>();

        int block = 0;
        int blockLine = 0;
        List<Double> current = null;
        int qInThisBlock = 0;

        for (int n = 0; n < lines.size(); n++) {
            final String line = lines.get(n).trim();
            if (line.startsWith("#")) {
                continue;
            }
            if (line.equals("---")) {
                if (block > 0 && current != null) {
                    blockValues.add(toArray(current));
                    blockQCount.add(qInThisBlock);
                }
                block++;
                blockLine = 0;
                current = new ArrayList<>();
                qInThisBlock = 0;
                continue;
            }
            blockLine++;

            if (block == 0) {
                final int colon = line.indexOf(':');
                if (colon > 0) {
                    pdf.metadata.put(line.substring(0, colon).trim(),
                                     line.substring(colon + 1).trim());
                }
                continue;
            }

            if (blockLine == 1) {
                if (block == 1) {
                    for (String word : line.split("\\s+")) {
                        if (!word.isEmpty()) xKnots.add(number(word, file, n + 1));
                    }
                    if (xKnots.isEmpty()) {
                        throw new IOException("Empty x knots on line " + (n + 1));
                    }
                } else {
                    int at = 0;
                    for (String word : line.split("\\s+")) {
                        if (word.isEmpty()) continue;
                        if (at >= xKnots.size()
                            || number(word, file, n + 1) != xKnots.get(at)) {
                            throw new IOException(
                                "The x knots of block " + block + " differ from the first, on line "
                                + (n + 1));
                        }
                        at++;
                    }
                }
            } else if (blockLine == 2) {
                // The file lists Q; everything downstream works in Q squared.
                for (String word : line.split("\\s+")) {
                    if (word.isEmpty()) continue;
                    final double q = number(word, file, n + 1);
                    q2Knots.add(q * q);
                    qInThisBlock++;
                }
                if (qInThisBlock == 0) {
                    throw new IOException("Empty Q knots on line " + (n + 1));
                }
            } else if (blockLine == 3) {
                if (block == 1) {
                    for (String word : line.split("\\s+")) {
                        if (!word.isEmpty()) flavors.add(code(word, file, n + 1));
                    }
                    if (flavors.isEmpty()) {
                        throw new IOException("No flavor codes on line " + (n + 1));
                    }
                } else {
                    int at = 0;
                    for (String word : line.split("\\s+")) {
                        if (word.isEmpty()) continue;
                        if (at >= flavors.size()
                            || code(word, file, n + 1) != flavors.get(at)) {
                            throw new IOException(
                                "The flavors of block " + block + " differ from the first, on line "
                                + (n + 1));
                        }
                        at++;
                    }
                }
            } else {
                for (String word : line.split("\\s+")) {
                    if (!word.isEmpty()) current.add(number(word, file, n + 1));
                }
            }
        }
        if (block > 0 && current != null && !current.isEmpty()) {
            blockValues.add(toArray(current));
            blockQCount.add(qInThisBlock);
        }
        if (xKnots.isEmpty() || q2Knots.isEmpty() || flavors.isEmpty()) {
            throw new IOException(file.getFileName() + " holds no grid.");
        }

        pdf.xs = toArray(xKnots);
        pdf.q2s = toArray(q2Knots);
        pdf.pids = new int[flavors.size()];
        for (int i = 0; i < flavors.size(); i++) {
            pdf.pids[i] = flavors.get(i);
        }
        pdf.fillLogKnots();
        pdf.fillLookup();
        pdf.assemble(blockValues, blockQCount);
        pdf.computeCoefficients();
        return pdf;
    }

    /**
     * One number from the file, with the line it was on if it is not one.
     *
     * A grid is millions of numbers on thousands of lines, and the way one goes
     * wrong is a download that stopped in the middle or a disk that flipped a
     * byte. Letting the parser throw on its own gives the bad text and nothing
     * else, which is no help at all against a file that size.
     *
     * The words nan and inf are read rather than refused. A fit that failed to
     * converge writes them, the file is otherwise intact, and refusing to open
     * it would hide what is wrong; the check below counts them and says so.
     */
    private static double number(String word, Path file, int line) throws IOException {
        final String bare = word.trim().toLowerCase(Locale.ROOT);
        if (bare.endsWith("nan")) {
            return Double.NaN;
        }
        if (bare.equals("inf") || bare.equals("+inf") || bare.equals("infinity")) {
            return Double.POSITIVE_INFINITY;
        }
        if (bare.equals("-inf") || bare.equals("-infinity")) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(word);
        } catch (NumberFormatException notANumber) {
            throw new IOException(file.getFileName() + " line " + line
                + ": \"" + word + "\" is not a number.");
        }
    }

    private static int code(String word, Path file, int line) throws IOException {
        try {
            return Integer.parseInt(word.trim());
        } catch (NumberFormatException notACode) {
            throw new IOException(file.getFileName() + " line " + line
                + ": \"" + word + "\" is not a particle code.");
        }
    }

    /**
     * Lays the blocks end to end into one array.
     *
     * Each block carries the same x knots but its own stretch of Q, so a block
     * contributes a slice of the Q axis at every x. The order inside a block is
     * x-major, then Q, then flavor, which is the order the file is written in.
     */
    private void assemble(List<double[]> blocks, List<Integer> qCount) throws IOException {
        final int nx = xs.length;
        final int nq = q2s.length;
        final int nf = pids.length;
        grid = new double[nx * nq * nf];

        int qOffset = 0;
        for (int b = 0; b < blocks.size(); b++) {
            final double[] values = blocks.get(b);
            final int qHere = qCount.get(b);
            final int expected = nx * qHere * nf;
            if (values.length != expected) {
                throw new IOException("Block " + (b + 1) + " holds " + values.length
                    + " values where " + expected + " were expected ("
                    + nx + " x knots, " + qHere + " Q knots, " + nf + " flavors).");
            }
            int at = 0;
            for (int ix = 0; ix < nx; ix++) {
                for (int iq = 0; iq < qHere; iq++) {
                    for (int f = 0; f < nf; f++) {
                        grid[ix * nq * nf + (qOffset + iq) * nf + f] = values[at++];
                    }
                }
            }
            qOffset += qHere;
        }
        if (qOffset != nq) {
            throw new IOException("The blocks cover " + qOffset + " Q knots but "
                + nq + " were declared.");
        }
    }

    private void fillLogKnots() {
        logxs = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            logxs[i] = Math.log(xs[i]);
        }
        logq2s = new double[q2s.length];
        for (int i = 0; i < q2s.length; i++) {
            logq2s[i] = Math.log(q2s[i]);
        }
    }

    private void fillLookup() {
        lookup.clear();
        for (int code : FLAVORS) {
            int at = -1;
            for (int i = 0; i < pids.length; i++) {
                if (pids[i] == code) {
                    at = i;
                    break;
                }
            }
            lookup.put(code, at);
        }
    }

    /* ------------------------------------------------------------------ */
    /* The cubic in log x, computed once                                   */
    /* ------------------------------------------------------------------ */

    /**
     * The slope at one knot, in log x.
     *
     * Central where there is a knot on each side, one-sided at the two ends.
     * This is what decides the shape between knots, so it is the part that has
     * to match LHAPDF exactly rather than merely closely.
     */
    private double slope(int ix, int iq, int f) {
        final int nx = xs.length;
        final double[] axis = logX() ? logxs : xs;
        if (ix != 0 && ix != nx - 1) {
            final double back = axis[ix] - axis[ix - 1];
            final double ahead = axis[ix + 1] - axis[ix];
            final double left = (xf(ix, iq, f) - xf(ix - 1, iq, f)) / back;
            final double right = (xf(ix + 1, iq, f) - xf(ix, iq, f)) / ahead;
            if (accuracy == Accuracy.WEIGHTED) {
                return (ahead * left + back * right) / (back + ahead);
            }
            return (left + right) / 2.0;
        }
        if (ix == 0) {
            return (xf(1, iq, f) - xf(0, iq, f)) / (axis[1] - axis[0]);
        }
        return (xf(nx - 1, iq, f) - xf(nx - 2, iq, f)) / (axis[nx - 1] - axis[nx - 2]);
    }

    /** True when this interpolation works in the logarithm rather than the value. */
    private boolean logX() {
        return interpolation == Interpolation.LOG_BICUBIC
            || interpolation == Interpolation.LOG_BILINEAR;
    }

    /** True when the cubic is used rather than the straight line. */
    private boolean cubicRule() {
        return interpolation == Interpolation.LOG_BICUBIC
            || interpolation == Interpolation.BICUBIC;
    }

    private void computeCoefficients() {
        final int nx = xs.length;
        final int nq = q2s.length;
        final int nf = pids.length;
        coeffs = new double[(nx - 1) * nq * nf * 4];

        for (int ix = 0; ix < nx - 1; ix++) {
            final double[] axis = logX() ? logxs : xs;
            final double dlogx = axis[ix + 1] - axis[ix];
            for (int iq = 0; iq < nq; iq++) {
                for (int f = 0; f < nf; f++) {
                    final double vl = xf(ix, iq, f);
                    final double vh = xf(ix + 1, iq, f);
                    final double vdl = slope(ix, iq, f) * dlogx;
                    final double vdh = slope(ix + 1, iq, f) * dlogx;
                    final int at = ix * nq * nf * 4 + iq * nf * 4 + f * 4;
                    coeffs[at] = vdh + vdl - 2.0 * vh + 2.0 * vl;
                    coeffs[at + 1] = 3.0 * vh - 3.0 * vl - 2.0 * vdl - vdh;
                    coeffs[at + 2] = vdl;
                    coeffs[at + 3] = vl;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Asking it for a value                                               */
    /* ------------------------------------------------------------------ */

    /** The value a grid point holds. */
    public double xf(int ix, int iq, int f) {
        return grid[ix * q2s.length * pids.length + iq * pids.length + f];
    }

    /** The largest knot index strictly below a value, clamped to the last interval. */
    private static int below(double value, double[] knots) {
        int low = 0;
        int high = knots.length;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            if (knots[mid] <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        // low is the first index strictly above the value. The interval wanted
        // is the one starting below it, and the two ends are pulled inside so
        // that index and index+1 are always real knots.
        if (low >= knots.length) {
            low = knots.length - 1;
        }
        return low == 0 ? 0 : low - 1;
    }

    private static double cubic(double t, double[] c, int at) {
        final double t2 = t * t;
        return c[at] * t2 * t + c[at + 1] * t2 + c[at + 2] * t + c[at + 3];
    }

    private static double hermite(double t, double vl, double vdl, double vh, double vdh) {
        final double t2 = t * t;
        final double t3 = t * t2;
        return (2 * t3 - 3 * t2 + 1) * vl
             + (t3 - 2 * t2 + t) * vdl
             + (-2 * t3 + 3 * t2) * vh
             + (t3 - t2) * vdh;
    }

    /**
     * xf(x, Q squared) for one particle code.
     *
     * Outside the grid the value is continued rather than refused, the way the
     * default LHAPDF extrapolator does, because an analysis that reaches one
     * entry past the edge should not stop; but the continuation is linear in
     * the logarithms and is not a measurement, which is why the caller can ask
     * whether it was inside.
     */
    public double xfxQ2(int pid, double x, double q2) {
        final Integer at = lookup.get(pid);
        if (at == null || at < 0) {
            return 0.0;
        }
        if (x <= 0.0 || q2 <= 0.0) {
            return 0.0;
        }
        if (inRange(x, q2)) {
            return interpolate(at, x, q2);
        }
        return extrapolate(at, x, q2);
    }

    /** The same, from Q rather than Q squared. */
    public double xfxQ(int pid, double x, double q) {
        return xfxQ2(pid, x, q * q);
    }

    /** True when the point is inside the knots, so the answer is interpolated. */
    public boolean inRange(double x, double q2) {
        return x >= xs[0] && x <= xs[xs.length - 1]
            && q2 >= q2s[0] && q2 <= q2s[q2s.length - 1];
    }

    private double interpolate(int f, double x, double q2) {
        if (!cubicRule()) {
            return straight(f, x, q2);
        }
        final int ix = below(x, xs);
        final int iq = below(q2, q2s);
        final int nq = q2s.length;
        final int nf = pids.length;

        // The two non-logarithmic rules run the same arithmetic on x and Q
        // squared themselves, so the axes are chosen once here rather than
        // written out twice.
        final double[] xAxis = logX() ? logxs : xs;
        final double[] qAxis = logX() ? logq2s : q2s;
        final double logx = logX() ? Math.log(x) : x;
        final double logq2 = logX() ? Math.log(q2) : q2;

        // A repeated Q knot is the seam between two subgrids: the slope must not
        // be taken across it, because the distribution is allowed to jump there.
        final boolean atLowSeam = (iq == 0) || (q2s[iq] == q2s[iq - 1]);
        final boolean atHighSeam = (iq + 1 == nq - 1) || (q2s[iq + 1] == q2s[iq + 2]);

        final double dlogx = xAxis[ix + 1] - xAxis[ix];
        final double tx = (logx - xAxis[ix]) / dlogx;
        final double dlogq1 = qAxis[iq + 1] - qAxis[iq];
        final double tq = (logq2 - qAxis[iq]) / dlogq1;

        // Two knots and two seams: there is nothing to build a cubic from.
        if (atLowSeam && atHighSeam) {
            final double fl = linear(logx, xAxis[ix], xAxis[ix + 1], xf(ix, iq, f), xf(ix + 1, iq, f));
            final double fh = linear(logx, xAxis[ix], xAxis[ix + 1],
                                     xf(ix, iq + 1, f), xf(ix + 1, iq + 1, f));
            return linear(logq2, qAxis[iq], qAxis[iq + 1], fl, fh);
        }

        final int base = ix * nq * nf * 4 + f * 4;
        final double vl = cubic(tx, coeffs, base + iq * nf * 4);
        final double vh = cubic(tx, coeffs, base + (iq + 1) * nf * 4);

        double vdl;
        double vdh;
        if (atLowSeam) {
            vdl = vh - vl;
            final double vhh = cubic(tx, coeffs, base + (iq + 2) * nf * 4);
            final double dlogq2 = 1.0 / (qAxis[iq + 2] - qAxis[iq + 1]);
            vdh = (vdl + (vhh - vh) * dlogq1 * dlogq2) * 0.5;
        } else if (atHighSeam) {
            vdh = vh - vl;
            final double vll = cubic(tx, coeffs, base + (iq - 1) * nf * 4);
            final double dlogq0 = 1.0 / (qAxis[iq] - qAxis[iq - 1]);
            vdl = (vdh + (vl - vll) * dlogq1 * dlogq0) * 0.5;
        } else {
            final double vll = cubic(tx, coeffs, base + (iq - 1) * nf * 4);
            final double dlogq0 = 1.0 / (qAxis[iq] - qAxis[iq - 1]);
            vdl = ((vh - vl) + (vl - vll) * dlogq1 * dlogq0) * 0.5;
            final double vhh = cubic(tx, coeffs, base + (iq + 2) * nf * 4);
            final double dlogq2 = 1.0 / (qAxis[iq + 2] - qAxis[iq + 1]);
            vdh = ((vh - vl) + (vhh - vh) * dlogq1 * dlogq2) * 0.5;
        }
        return hermite(tq, vl, vdl, vh, vdh);
    }

    /**
     * The straight line between four knots, which is the two linear rules.
     *
     * Interpolating in x first and in Q squared after is not the same as the
     * reverse only when the four corners do not lie on a plane, which is the
     * usual case; the order here is LHAPDF's.
     */
    private double straight(int f, double x, double q2) {
        final int ix = below(x, xs);
        final int iq = below(q2, q2s);
        final double[] xAxis = logX() ? logxs : xs;
        final double[] qAxis = logX() ? logq2s : q2s;
        final double atX = logX() ? Math.log(x) : x;
        final double atQ = logX() ? Math.log(q2) : q2;

        final double low = linear(atX, xAxis[ix], xAxis[ix + 1],
                                  xf(ix, iq, f), xf(ix + 1, iq, f));
        final double high = linear(atX, xAxis[ix], xAxis[ix + 1],
                                   xf(ix, iq + 1, f), xf(ix + 1, iq + 1, f));
        return linear(atQ, qAxis[iq], qAxis[iq + 1], low, high);
    }

    private static double linear(double x, double xl, double xh, double yl, double yh) {
        return yl + (x - xl) / (xh - xl) * (yh - yl);
    }

    /**
     * Continues the distribution past the edge of the grid.
     *
     * In the logarithms, and through the logarithm of the value itself when
     * both anchors are comfortably positive, so that a distribution which falls
     * steeply does not cross zero on the way out.
     */
    private double extrapolate(int f, double x, double q2) {
        return switch (extrapolation) {
            case ERROR -> throw new IllegalArgumentException(String.format(Locale.ROOT,
                "x = %.6g, Q2 = %.6g is outside the grid: x in [%.6g, %.6g], "
                + "Q2 in [%.6g, %.6g].", x, q2, xs[0], xs[xs.length - 1],
                q2s[0], q2s[q2s.length - 1]));
            case NEAREST -> interpolate(f, nearest(x, xs), nearest(q2, q2s));
            case CONTINUATION -> continuation(f, x, q2);
            case SPHERE -> ownContinuation(f, x, q2);
        };
    }

    /**
     * The knot nearest a value, which is what the flat extrapolator answers.
     *
     * Nearest in the value rather than in its logarithm, as LHAPDF has it: at
     * small x the two disagree, and a result that depends on which was meant is
     * worse than one that is merely flat.
     */
    private static double nearest(double value, double[] knots) {
        if (value >= knots[0] && value <= knots[knots.length - 1]) {
            return value;
        }
        int at = 0;
        while (at < knots.length && knots[at] < value) {
            at++;
        }
        final double above = knots[Math.min(at, knots.length - 1)];
        final double under = at == 0 ? above : knots[at - 1];
        return Math.abs(value - above) < Math.abs(value - under) ? above : under;
    }

    /**
     * LHAPDF's own continuation, as the MSTW standalone code wrote it.
     *
     * Below the smallest x the shape is carried on straight in the logarithms.
     * Below the smallest scale it is not: the rate at which the distribution
     * changes with the scale is measured at the edge, bounded from below, and
     * then bent towards one as the scale falls, so the distribution goes to
     * zero at zero scale instead of running away. Above the largest x it
     * refuses, because there is no sense in a momentum fraction above one.
     */
    private double continuation(int f, double x, double q2) {
        final int nx = xs.length;
        final int nq = q2s.length;
        final double xMin = xs[0];
        final double xMin1 = xs[1];
        final double xMax = xs[nx - 1];
        final double q2Min = q2s[0];
        final double q2Max1 = q2s[nq - 2];
        final double q2Max = q2s[nq - 1];

        if (x > xMax) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                "x = %.6g is above the last knot at %.6g.", x, xMax));
        }

        if (x < xMin && q2 >= q2Min && q2 <= q2Max) {
            return alongX(f, x, xMin, xMin1, q2);
        }
        if (x >= xMin && q2 > q2Max) {
            return alongLog(q2, q2Max, q2Max1,
                            interpolate(f, x, q2Max), interpolate(f, x, q2Max1));
        }
        if (x < xMin && q2 > q2Max) {
            final double atMin = alongLog(q2, q2Max, q2Max1,
                interpolate(f, xMin, q2Max), interpolate(f, xMin, q2Max1));
            final double atMin1 = alongLog(q2, q2Max, q2Max1,
                interpolate(f, xMin1, q2Max), interpolate(f, xMin1, q2Max1));
            return alongLog(x, xMin, xMin1, atMin, atMin1);
        }
        if (q2 < q2Min) {
            final double atEdge;
            final double justAbove;
            if (x < xMin) {
                atEdge = alongX(f, x, xMin, xMin1, q2Min);
                justAbove = alongX(f, x, xMin, xMin1, 1.01 * q2Min);
            } else {
                atEdge = interpolate(f, x, q2Min);
                justAbove = interpolate(f, x, 1.01 * q2Min);
            }
            // How fast the distribution moves with the scale, at the edge. A
            // value too small to divide by carries no information, so the
            // dimension is then taken as one.
            final double anomalous = Math.abs(atEdge) >= 1e-5
                ? Math.max(-2.5, (justAbove - atEdge) / atEdge / 0.01)
                : 1.0;
            final double ratio = q2 / q2Min;
            return atEdge * Math.pow(ratio, anomalous * ratio + 1.0 - ratio);
        }
        return interpolate(f, x, q2);
    }

    private double alongX(int f, double x, double xa, double xb, double q2) {
        return alongLog(x, xa, xb, interpolate(f, xa, q2), interpolate(f, xb, q2));
    }

    /** Straight in the logs, keeping the value positive where both ends are. */
    private static double alongLog(double x, double xa, double xb, double fa, double fb) {
        final double t = (Math.log(x) - Math.log(xa)) / (Math.log(xb) - Math.log(xa));
        if (fa > 1e-3 && fb > 1e-3) {
            return Math.exp(Math.log(fa) + t * (Math.log(fb) - Math.log(fa)));
        }
        return fa + t * (fb - fa);
    }

    private double ownContinuation(int f, double x, double q2) {
        final double xLow = xs[0];
        final double xHigh = xs[xs.length - 1];
        final double q2Low = q2s[0];
        final double q2High = q2s[q2s.length - 1];

        final double xIn = x < xLow ? xLow : (x > xHigh ? xHigh : x);
        final double q2In = q2 < q2Low ? q2Low : (q2 > q2High ? q2High : q2);

        if (x < xLow || x > xHigh) {
            final double xa = x < xLow ? xs[0] : xs[xs.length - 1];
            final double xb = x < xLow ? xs[1] : xs[xs.length - 2];
            final double fa = interpolate(f, xa, q2In);
            final double fb = interpolate(f, xb, q2In);
            final double along = continued(x, xa, xb, fa, fb);
            if (q2 >= q2Low && q2 <= q2High) {
                return along;
            }
            // Off both edges: continue in Q from the two nearest Q knots.
            final double q2a = q2 < q2Low ? q2s[0] : q2s[q2s.length - 1];
            final double q2b = q2 < q2Low ? q2s[1] : q2s[q2s.length - 2];
            final double ga = continuedAtQ(f, x, xa, xb, q2a);
            final double gb = continuedAtQ(f, x, xa, xb, q2b);
            return continued(q2, q2a, q2b, ga, gb);
        }

        final double q2a = q2 < q2Low ? q2s[0] : q2s[q2s.length - 1];
        final double q2b = q2 < q2Low ? q2s[1] : q2s[q2s.length - 2];
        return continued(q2, q2a, q2b, interpolate(f, xIn, q2a), interpolate(f, xIn, q2b));
    }

    private double continuedAtQ(int f, double x, double xa, double xb, double q2) {
        return continued(x, xa, xb, interpolate(f, xa, q2), interpolate(f, xb, q2));
    }

    private static double continued(double x, double xa, double xb, double fa, double fb) {
        final double t = (Math.log(x) - Math.log(xa)) / (Math.log(xb) - Math.log(xa));
        if (fa > 1e-3 && fb > 1e-3) {
            return Math.exp(Math.log(fa) + t * (Math.log(fb) - Math.log(fa)));
        }
        return fa + t * (fb - fa);
    }

    /* ------------------------------------------------------------------ */
    /* What the grid is                                                    */
    /* ------------------------------------------------------------------ */

    public Path file() { return source; }
    public int xKnots() { return xs.length; }
    public int q2Knots() { return q2s.length; }
    public int[] flavors() { return pids.clone(); }
    public double xMin() { return xs[0]; }
    public double xMax() { return xs[xs.length - 1]; }
    public double q2Min() { return q2s[0]; }
    public double q2Max() { return q2s[q2s.length - 1]; }
    public double qMin() { return Math.sqrt(q2s[0]); }
    public double qMax() { return Math.sqrt(q2s[q2s.length - 1]); }
    public String meta(String key, String fallback) {
        return metadata.getOrDefault(key, fallback);
    }
    public Map<String, String> metadata() {
        return java.util.Collections.unmodifiableMap(metadata);
    }
    public boolean carries(int pid) {
        final Integer at = lookup.get(pid);
        return at != null && at >= 0;
    }

    /** The x knots, for a test that wants to land exactly on one. */
    public double[] xKnotValues() { return xs.clone(); }

    /** The Q squared knots, same. */
    public double[] q2KnotValues() { return q2s.clone(); }

    /**
     * What is wrong with the grid, before anything is asked of it.
     *
     * The interesting case is two x knots that fall on top of each other. A set
     * built by joining a log spaced range to a linearly spaced one repeats the
     * value where they meet, and nothing complains: the file parses, every knot
     * reads back exactly, and the interpolation between the two is harmless
     * because the interval is empty. What it costs is the slope at that knot,
     * computed across an arm of no length, and through it the two neighboring
     * intervals. Measured on a grid built that way the error between knots is
     * a hundred times what the same grid gives once the repeat is dropped, and
     * there is nothing in the answer to say so.
     */
    public List<Finding> check() {
        List<Finding> found = new ArrayList<>();

        int repeated = 0;
        double worstRatio = 1.0;
        double worstRatioAt = 0.0;
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] <= xs[i - 1]) {
                found.add(new Finding(true, String.format(Locale.ROOT,
                    "The x knots do not increase: %.17g then %.17g at index %d.",
                    xs[i - 1], xs[i], i)));
                continue;
            }
            if (logxs[i] - logxs[i - 1] < 1e-9) {
                repeated++;
                if (repeated == 1) {
                    found.add(new Finding(false, String.format(Locale.ROOT,
                        "Two x knots fall on the same point, %.17g and %.17g. The slope "
                        + "there is computed across nothing, and the intervals on both "
                        + "sides lose about two digits of accuracy.", xs[i - 1], xs[i])));
                }
            } else if (i > 1 && logxs[i - 1] - logxs[i - 2] > 1e-9) {
                final double a = logxs[i - 1] - logxs[i - 2];
                final double b = logxs[i] - logxs[i - 1];
                final double ratio = Math.max(a / b, b / a);
                if (ratio > worstRatio) {
                    worstRatio = ratio;
                    worstRatioAt = xs[i - 1];
                }
            }
        }
        if (repeated > 1) {
            found.add(new Finding(false, repeated + " pairs of x knots fall on the same point."));
        }
        if (worstRatio > 20.0) {
            found.add(new Finding(false, String.format(Locale.ROOT,
                "Around x = %.6g one step in log x is %.0f times its neighbor. LHAPDF's "
                + "slope estimate is not weighted, so accuracy drops there. Reading with "
                + "Accuracy.WEIGHTED recovers it.", worstRatioAt, worstRatio)));
        }

        for (int i = 1; i < q2s.length; i++) {
            if (q2s[i] < q2s[i - 1]) {
                found.add(new Finding(true, String.format(Locale.ROOT,
                    "The Q knots go backwards: %.6g then %.6g GeV at index %d.",
                    Math.sqrt(q2s[i - 1]), Math.sqrt(q2s[i]), i)));
            }
        }
        for (int i = 2; i < q2s.length; i++) {
            if (q2s[i] == q2s[i - 1] && q2s[i - 1] == q2s[i - 2]) {
                found.add(new Finding(true, String.format(Locale.ROOT,
                    "Q = %.6g GeV appears three times. A seam joins two subgrids, not three.",
                    Math.sqrt(q2s[i]))));
                break;
            }
        }

        int notANumber = 0;
        for (double value : grid) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                notANumber++;
            }
        }
        if (notANumber > 0) {
            found.add(new Finding(true, notANumber + " of " + grid.length
                + " values are not numbers."));
        }

        if (!carries(21)) {
            found.add(new Finding(false, "The grid carries no gluon."));
        }
        return found;
    }

    /** True when nothing found would stop the grid being used. */
    public boolean usable() {
        return check().stream().noneMatch(Finding::fatal);
    }

    /** Which slope estimate this grid was read with. */
    public Accuracy accuracy() { return accuracy; }

    /** Which rule joins the knots. */
    public Interpolation interpolation() { return interpolation; }

    /** What happens outside the knots. */
    public Extrapolation extrapolation() { return extrapolation; }

    /** Where the subgrids meet: a Q value that appears twice. */
    public List<Double> seams() {
        List<Double> found = new ArrayList<>();
        for (int i = 1; i < q2s.length; i++) {
            if (q2s[i] == q2s[i - 1]) {
                found.add(Math.sqrt(q2s[i]));
            }
        }
        return found;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
            "%s  %d x knots [%.3g, %.3g]  %d Q knots [%.3g, %.3g] GeV  %d flavors",
            source == null ? "grid" : source.getFileName().toString(),
            xs.length, xMin(), xMax(), q2s.length, qMin(), qMax(), pids.length);
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
