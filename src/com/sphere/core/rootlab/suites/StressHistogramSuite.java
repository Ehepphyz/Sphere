package com.sphere.core.rootlab.suites;

import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootlab.bench.LabContext;
import com.sphere.core.rootlab.bench.LabFailure;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.core.rootlab.bench.LabTest;
import com.sphere.core.rootlab.hist.HistCompare;
import com.sphere.core.rootlab.hist.LabAxis;
import com.sphere.core.rootlab.hist.LabHist;
import com.sphere.core.rootlab.hist.LabProfile;
import com.sphere.core.rootlab.hist.LabSparse;
import com.sphere.core.rootlab.random.TRandom2;

import java.util.List;

/**
 * stressHistogram's test 24, "Reference File Read for Histograms and
 * Profiles": the histograms ROOT 5.18 wrote into stressHistogram.5.18.00.root
 * are read back and compared -- every cell, every error, Chi2Test both ways,
 * means, deviations, sums of weights, entries -- with the same histograms
 * filled now from the same random numbers.
 *
 * Passing it takes three things to be right at once: the reader (an object
 * written by another ROOT twenty years of versions ago), TRandom2 (the numbers
 * bit for bit), and the histogram arithmetic (TAxis::FindBin at every edge,
 * the statistics of TH1, TH2, TH3 and of the three profiles).
 */
public final class StressHistogramSuite implements LabSuite {

    private static final String FILE = "stressHistogram.5.18.00.root";
    private static final double MIN_RANGE = 1;
    private static final double MAX_RANGE = 5;
    private static final int N_EVENTS = 1000;
    private static final int BINS = 10;
    private static final double ERROR_LIMIT = 1.E-10;

    @Override
    public String id() {
        return "stressHistogram";
    }

    @Override
    public String title() {
        return "Reference File Read for Histograms and Profiles";
    }

    @Override
    public String origin() {
        return "root/test/stressHistogram.cxx, test 24 (testRefRead*), reference file written by ROOT 5.18";
    }

    @Override
    public String description() {
        return "Histograms and profiles in 1, 2 and 3 dimensions and a THnSparse written by ROOT 5.18, "
             + "compared bin by bin with the same ones filled in Java from TRandom2 (seed 8652).";
    }

    @Override
    public List<LabTest> tests() {
        // One generator for the whole suite: each test draws after the previous one.
        final TRandom2 r = new TRandom2();
        r.setSeed(8652);
        return List.of(
            new LabTest("refRead1D", "Ref Read Hist 1D", ctx -> refRead1D(ctx, r)),
            new LabTest("refReadProf1D", "Ref Read Prof 1D", ctx -> refReadProf1D(ctx, r)),
            new LabTest("refRead2D", "Ref Read Hist 2D", ctx -> refRead2D(ctx, r)),
            new LabTest("refReadProf2D", "Ref Read Prof 2D", ctx -> refReadProf2D(ctx, r)),
            new LabTest("refRead3D", "Ref Read Hist 3D", ctx -> refRead3D(ctx, r)),
            new LabTest("refReadProf3D", "Ref Read Prof 3D", ctx -> refReadProf3D(ctx, r)),
            new LabTest("refReadSparse", "Ref Read Sparse", ctx -> refReadSparse(ctx, r)));
    }

    private static double draw(TRandom2 r) {
        return r.uniform(0.9 * MIN_RANGE, 1.1 * MAX_RANGE);
    }

    private static RObject reference(String name) throws Exception {
        try (RootIO io = RootIO.open(LabContext.resource(FILE))) {
            final Object o = io.read(name);
            if (!(o instanceof RObject r) || r.has("@unread")) {
                throw new LabFailure("Error reading " + name + " from " + FILE);
            }
            return r;
        }
    }

    private static void verdict(LabContext ctx, LabHist h1, LabHist h2) throws LabFailure {
        final HistCompare.Outcome out = HistCompare.equals(h1, h2, HistCompare.OPT_STATS, ERROR_LIMIT);
        ctx.detail(h1 + " read from " + FILE);
        ctx.detail(String.format(java.util.Locale.ROOT, "mean %.10g, std dev %.10g, entries %.0f, Chi2Test p = %.12g",
            h1.mean(1), h1.stdDev(1), h1.entries, HistCompare.chi2Test(h1, h2, "WW OF UF")));
        if (!out.ok()) {
            throw new LabFailure(out.differences + " quantities differ from the reference", out.details);
        }
    }

    private static void refRead1D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist h1 = LabHist.fromObject(reference("rr1D-h1"));
        h1.statOverflows = true;
        final LabHist h2 = LabHist.th1d("rr1D-h2", "h2-Title", BINS, MIN_RANGE, MAX_RANGE);
        h2.sumw2();
        h2.statOverflows = true;
        for (int e = 0; e < N_EVENTS; ++e) {
            h2.fill(draw(r), 1.0);
        }
        verdict(ctx, h1, h2);
    }

    private static void refReadProf1D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist p1 = LabHist.fromObject(reference("rr1D-p1"));
        p1.statOverflows = true;
        // TH1::SetDefaultSumw2(false) in ROOT's test: this profile keeps no sum of w^2
        final LabProfile p2 = LabProfile.tprofile("rr1D_p2", "p2-Title", BINS, MIN_RANGE, MAX_RANGE);
        p2.statOverflows = true;
        for (int e = 0; e < N_EVENTS; ++e) {
            final double x = draw(r);
            final double y = draw(r);
            p2.fillProfile(x, y, 1.0);
        }
        verdict(ctx, p1, p2);
    }

    private static void refRead2D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist h1 = LabHist.fromObject(reference("rr2D-h1"));
        h1.statOverflows = true;
        final LabHist h2 = LabHist.th2d("rr2D-h2", "h2-Title", BINS, MIN_RANGE, MAX_RANGE, BINS, MIN_RANGE, MAX_RANGE);
        h2.sumw2();
        h2.statOverflows = true;
        for (int e = 0; e < N_EVENTS * N_EVENTS; ++e) {
            final double x = draw(r);
            final double y = draw(r);
            h2.fill(x, y, 1.0);
        }
        verdict(ctx, h1, h2);
    }

    private static void refReadProf2D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist p1 = LabHist.fromObject(reference("rr2D-p1"));
        p1.statOverflows = true;
        final LabProfile p2 = LabProfile.tprofile2d("rr2D_p2", "p2-Title", BINS, MIN_RANGE, MAX_RANGE,
                                                    BINS, MIN_RANGE, MAX_RANGE);
        p2.sumw2();
        p2.statOverflows = true;
        for (int e = 0; e < N_EVENTS * N_EVENTS; ++e) {
            final double x = draw(r);
            final double y = draw(r);
            final double z = draw(r);
            p2.fillProfile(x, y, z, 1.0);
        }
        verdict(ctx, p1, p2);
    }

    private static void refRead3D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist h1 = LabHist.fromObject(reference("rr3D-h1"));
        h1.statOverflows = true;
        final LabHist h2 = LabHist.th3d("rr3D-h2", "h2-Title", BINS, MIN_RANGE, MAX_RANGE, BINS, MIN_RANGE, MAX_RANGE,
                                        BINS, MIN_RANGE, MAX_RANGE);
        h2.sumw2();
        h2.statOverflows = true;
        for (int e = 0; e < N_EVENTS * N_EVENTS; ++e) {
            final double x = draw(r);
            final double y = draw(r);
            final double z = draw(r);
            h2.fill(x, y, z, 1.0);
        }
        verdict(ctx, h1, h2);
    }

    private static void refReadProf3D(LabContext ctx, TRandom2 r) throws Exception {
        final LabHist p1 = LabHist.fromObject(reference("rr3D-p1"));
        p1.statOverflows = true;
        final LabProfile p2 = LabProfile.tprofile3d("rr3D_p2", "p2-Title", BINS, MIN_RANGE, MAX_RANGE,
                                                    BINS, MIN_RANGE, MAX_RANGE, BINS, MIN_RANGE, MAX_RANGE);
        p2.sumw2();
        p2.statOverflows = true;
        for (int e = 0; e < N_EVENTS * N_EVENTS; ++e) {
            final double x = draw(r);
            final double y = draw(r);
            final double z = draw(r);
            final double t = draw(r);
            p2.fillProfile(x, y, z, t, 1.0);
        }
        verdict(ctx, p1, p2);
    }

    private static void refReadSparse(LabContext ctx, TRandom2 r) throws Exception {
        // the number of bins of each axis is drawn first, as in ROOT's test
        final int[] bsize = {HistCompare.nint(r.uniform(1, 5)), HistCompare.nint(r.uniform(1, 5)),
                             HistCompare.nint(r.uniform(1, 5))};
        final LabSparse s1 = LabSparse.fromObject(reference("rr-s1"));
        final LabAxis[] axes = new LabAxis[3];
        for (int d = 0; d < 3; d++) {
            axes[d] = new LabAxis(bsize[d], MIN_RANGE, MAX_RANGE);
        }
        final LabSparse s2 = new LabSparse("rr-s1", axes);
        for (int e = 0; e < N_EVENTS * N_EVENTS; ++e) {
            final double[] points = {r.uniform(MIN_RANGE * .9, MAX_RANGE * 1.1),
                                     r.uniform(MIN_RANGE * .9, MAX_RANGE * 1.1),
                                     r.uniform(MIN_RANGE * .9, MAX_RANGE * 1.1)};
            s2.fill(points, 1.0);
        }
        ctx.detail("THnSparseD rr-s1: axes " + bsize[0] + " x " + bsize[1] + " x " + bsize[2]
                   + " bins, " + s1.filledCells() + " filled cells in the file, " + s2.filledCells() + " filled now");
        final HistCompare.Outcome out = LabSparse.equals(s1, s2, true, ERROR_LIMIT);
        if (!out.ok()) {
            throw new LabFailure(out.differences + " cells differ from the reference", out.details);
        }
    }
}
