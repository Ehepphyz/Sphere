package com.sphere.core.rootlab.suites;

import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * stressLinear's stress_backward_io ("Streamers"): linearIO.root was written
 * by ROOT 5.05 in 2005, compressed with the old ROOT algorithm (the "CS"
 * blocks) and with the hand-written streamer of TMatrixTSym. It must still
 * read back as the matrices the test builds now: 11 x 11 Hilbert matrices
 * over [-5, 5], with pi at (1, 2), their symmetric and sparse forms, and the
 * row 3 as a vector -- equal element for element, as TMatrixT::operator==
 * compares them (memcmp).
 */
public final class StressLinearSuite implements LabSuite {

    private static final String FILE = "linearIO.root";
    private static final int LWB = -5;
    private static final int N = 11;

    @Override
    public String id() {
        return "stressLinear";
    }

    @Override
    public String title() {
        return "Streamers (backward compatibility with ROOT 5.05)";
    }

    @Override
    public String origin() {
        return "root/test/stressLinear.cxx, stress_backward_io, reference file linearIO.root (ROOT 5.05)";
    }

    @Override
    public String description() {
        return "Matrices and vectors written by ROOT 5.05 with the old compression routine and custom "
             + "streamers, compared element for element with the ones the test builds.";
    }

    @Override
    public List<LabTest> tests() {
        return List.of(
            new LabTest("mf1", "TMatrixF (Hilbert, with pi)", ctx -> dense(ctx, "mf1", true, false)),
            new LabTest("mf2", "TMatrixFSym (custom streamer, upper triangle)", ctx -> dense(ctx, "mf2", true, true)),
            new LabTest("vf_row", "TVectorF (row 3 of the matrix)", ctx -> vector(ctx, "vf_row", true)),
            new LabTest("md1", "TMatrixD (Hilbert, with pi)", ctx -> dense(ctx, "md1", false, false)),
            new LabTest("md2", "TMatrixDSym (custom streamer, upper triangle)", ctx -> dense(ctx, "md2", false, true)),
            new LabTest("md3", "TMatrixDSparse (row and column indices)", ctx -> sparse(ctx)),
            new LabTest("vd_row", "TVectorD (row 3 of the matrix)", ctx -> vector(ctx, "vd_row", false)));
    }

    /** THilbertMatrix(-5,5,-5,5): 1/(i+j+1) over 0-based indices; mf1/md1 also have pi at (1, 2). */
    private static double[] hilbert(boolean withPi) {
        final double[] m = new double[N * N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                m[i * N + j] = 1. / (i + j + 1.);
            }
        }
        if (withPi) {
            m[(1 - LWB) * N + (2 - LWB)] = Math.PI;
        }
        return m;
    }

    private static RObject read(String name) throws Exception {
        try (RootIO io = RootIO.open(LabContext.resource(FILE))) {
            final Object o = io.read(name);
            if (!(o instanceof RObject r) || r.has("@unread")) {
                throw new LabFailure(name + " could not be read: " + (o instanceof RObject r ? r.get("@unread") : o));
            }
            return r;
        }
    }

    private static void shape(LabContext ctx, RObject o, boolean matrix) throws LabFailure {
        ctx.detail(o.className + " v" + o.version + ": " + o.integer("fNrows")
                   + (matrix ? " x " + o.integer("fNcols") : "") + ", lower bound " + o.integer("fRowLwb"));
        ctx.check(o.integer("fNrows") == N, "fNrows is " + o.integer("fNrows") + ", not " + N);
        ctx.check(o.integer("fRowLwb") == LWB, "fRowLwb is " + o.integer("fRowLwb") + ", not " + LWB);
        if (matrix) {
            ctx.check(o.integer("fNcols") == N, "fNcols is " + o.integer("fNcols") + ", not " + N);
            ctx.check(o.integer("fColLwb") == LWB, "fColLwb is " + o.integer("fColLwb") + ", not " + LWB);
        }
    }

    private static void dense(LabContext ctx, String name, boolean single, boolean symmetric) throws Exception {
        final RObject o = read(name);
        shape(ctx, o, true);
        compare(ctx, o.get("fElements"), hilbert(!symmetric), single);
    }

    private static void vector(LabContext ctx, String name, boolean single) throws Exception {
        final RObject o = read(name);
        shape(ctx, o, false);
        final double[] m = hilbert(true);
        final double[] row = new double[N];
        System.arraycopy(m, (3 - LWB) * N, row, 0, N);
        compare(ctx, o.get("fElements"), row, single);
    }

    private static void sparse(LabContext ctx) throws Exception {
        final RObject o = read("md3");
        shape(ctx, o, true);
        final int[] rowIndex = o.ints("fRowIndex");
        final int[] colIndex = o.ints("fColIndex");
        final Object elements = o.get("fElements");
        ctx.check(elements instanceof double[], "fElements is not a double array");
        final double[] values = (double[]) elements;
        ctx.check(rowIndex.length == N + 1, "fRowIndex has " + rowIndex.length + " entries, not " + (N + 1));
        final double[] dense = new double[N * N];
        for (int i = 0; i < N; i++) {
            for (int k = rowIndex[i]; k < rowIndex[i + 1]; k++) {
                ctx.check(k < colIndex.length && k < values.length, "the sparse indices run past the elements");
                dense[i * N + colIndex[k]] = values[k];
            }
        }
        ctx.detail(values.length + " non-zero elements in " + (N * N) + " cells");
        compare(ctx, dense, hilbert(true), false);
    }

    private static void compare(LabContext ctx, Object got, double[] want, boolean single) throws LabFailure {
        final List<String> wrong = new ArrayList<>();
        int n;
        if (single) {
            if (!(got instanceof float[] f)) {
                throw new LabFailure("the elements are not single precision: " + got);
            }
            n = f.length;
            for (int i = 0; i < Math.min(n, want.length); i++) {
                if (Float.floatToIntBits(f[i]) != Float.floatToIntBits((float) want[i])) {
                    wrong.add(String.format(Locale.ROOT, "element %d: %.9g, expected %.9g", i, f[i], (float) want[i]));
                }
            }
        } else {
            if (!(got instanceof double[] d)) {
                throw new LabFailure("the elements are not double precision: " + got);
            }
            n = d.length;
            for (int i = 0; i < Math.min(n, want.length); i++) {
                if (Double.doubleToLongBits(d[i]) != Double.doubleToLongBits(want[i])) {
                    wrong.add(String.format(Locale.ROOT, "element %d: %.17g, expected %.17g", i, d[i], want[i]));
                }
            }
        }
        if (n != want.length) {
            wrong.add(0, n + " elements, expected " + want.length);
        }
        if (!wrong.isEmpty()) {
            throw new LabFailure(wrong.size() + " element(s) differ from what the test builds",
                                 wrong.subList(0, Math.min(10, wrong.size())));
        }
        ctx.detail(want.length + " elements equal bit for bit");
    }
}
