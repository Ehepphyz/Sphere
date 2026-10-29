package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.matrix.HepDiagMatrix;
import com.sphere.core.clhep.matrix.HepGenMatrix;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.matrix.MatrixLinear;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.COStream;

import java.util.Locale;

/**
 * The Java twin of matrix_ref.cc: every operator of the four matrix classes, the inversions of each size
 * and kind, determinants, the QR and Householder algorithms, diagonalization, the text out. Started with
 * this thread's statistics of the 5x5 and 6x6 inversions new, as in a fresh C++ program.
 */
public final class MatrixReference extends Reference {

    @Override
    public String name() {
        return "matrix";
    }

    private void pm(String n, HepGenMatrix m) {
        final StringBuilder b = new StringBuilder(n).append(' ').append(m.num_row()).append('x').append(m.num_col());
        for (int r = 1; r <= m.num_row(); r++) {
            for (int c = 1; c <= m.num_col(); c++) {
                b.append(String.format(Locale.ROOT, " %016x", Double.doubleToRawLongBits(m.get(r, c))));
            }
        }
        line(b.toString());
    }

    private static final HepMatrix.ElementFunction fapply = (x, r, c) -> x * r - c + 0.5;
    private static final HepVector.ElementFunction fvec = (x, r) -> x * x - r;

    private HepMatrix gen(int r, int c) {
        final HepMatrix m = new HepMatrix(r, c);
        for (int a = 1; a <= r; a++) {
            for (int b = 1; b <= c; b++) m.set(a, b, sym(5.0));
        }
        return m;
    }

    private HepSymMatrix gens(int n) {
        final HepSymMatrix m = new HepSymMatrix(n);
        for (int a = 1; a <= n; a++) {
            for (int b = 1; b <= a; b++) m.set(a, b, sym(5.0));
        }
        return m;
    }

    private HepDiagMatrix gend(int n) {
        final HepDiagMatrix m = new HepDiagMatrix(n);
        for (int a = 1; a <= n; a++) m.set(a, a, sym(5.0));
        return m;
    }

    private HepVector genv(int n) {
        final HepVector v = new HepVector(n);
        for (int a = 1; a <= n; a++) v.set(a, sym(5.0));
        return v;
    }

    private HepSymMatrix genpd(int n) {
        final HepMatrix a = gen(n, n);
        final HepSymMatrix s = new HepSymMatrix(n);
        for (int r = 1; r <= n; r++) {
            for (int c = 1; c <= r; c++) {
                double t = 0;
                for (int k = 1; k <= n; k++) t += a.get(r, k) * a.get(c, k);
                s.set(r, c, t + (r == c ? n : 0));
            }
        }
        return s;
    }

    private void ops(int k) {
        final int n = 1 + k % 7;
        final int q = 1 + (k / 7) % 5;
        final String t = "ops";
        final HepMatrix a = gen(n, n);
        final HepMatrix b = gen(n, n);
        final HepMatrix r = gen(n, q);
        final HepSymMatrix s = gens(n);
        final HepSymMatrix s2 = gens(n);
        final HepDiagMatrix dg = gend(n);
        final HepDiagMatrix dg2 = gend(n);
        final HepVector v = genv(n);
        final HepVector w = genv(n);
        final double x = sym(3.0);
        pm(t + ".a", a);
        pm(t + ".s", s);
        pm(t + ".dg", dg);
        pm(t + ".v", v);
        pm(t + ".mm+", a.plus(b));
        pm(t + ".mm-", a.minus(b));
        pm(t + ".mm*", a.times(b));
        pm(t + ".mr*", a.times(r));
        pm(t + ".ms+", a.plus(s));
        pm(t + ".sm+", s.plus(a));
        pm(t + ".ms-", a.minus(s));
        pm(t + ".sm-", s.minus(a));
        pm(t + ".ms*", a.times(s));
        pm(t + ".sm*", s.times(a));
        pm(t + ".ss*", s.times(s2));
        pm(t + ".ss+", s.plus(s2));
        pm(t + ".ss-", s.minus(s2));
        pm(t + ".md+", a.plus(dg));
        pm(t + ".dm+", dg.plus(a));
        pm(t + ".md-", a.minus(dg));
        pm(t + ".dm-", dg.minus(a));
        pm(t + ".md*", a.times(dg));
        pm(t + ".dm*", dg.times(a));
        pm(t + ".dd*", dg.times(dg2));
        pm(t + ".dd+", dg.plus(dg2));
        pm(t + ".dd-", dg.minus(dg2));
        pm(t + ".sd+", s.plus(dg));
        pm(t + ".ds+", dg.plus(s));
        pm(t + ".sd-", s.minus(dg));
        pm(t + ".ds-", dg.minus(s));
        pm(t + ".mv*", a.times(v));
        pm(t + ".sv*", s.times(v));
        pm(t + ".dv*", dg.times(v));
        pm(t + ".vv+", v.plus(w));
        pm(t + ".vv-", v.minus(w));
        pm(t + ".vmT*", v.times(v.T()));
        pm(t + ".mv+", new HepMatrix(v).plus(w));
        pm(t + ".vm+", w.plus(new HepMatrix(v)));
        pm(t + ".mv-", new HepMatrix(v).minus(w));
        pm(t + ".vm-", w.minus(new HepMatrix(v)));
        pm(t + ".mx*", a.times(x));
        pm(t + ".xm*", HepMatrix.times(x, a));
        pm(t + ".mx/", a.div(x));
        pm(t + ".sx*", s.times(x));
        pm(t + ".xs*", HepSymMatrix.times(x, s));
        pm(t + ".sx/", s.div(x));
        pm(t + ".dx*", dg.times(x));
        pm(t + ".xd*", HepDiagMatrix.times(x, dg));
        pm(t + ".dx/", dg.div(x));
        pm(t + ".vx*", v.times(x));
        pm(t + ".xv*", HepVector.times(x, v));
        pm(t + ".vx/", v.div(x));
        pm(t + ".mneg", a.negate());
        pm(t + ".sneg", s.negate());
        pm(t + ".dneg", dg.negate());
        pm(t + ".vneg", v.negate());
        {
            final HepMatrix u = new HepMatrix(a);
            u.plusAssign(b);
            u.minusAssign(s);
            u.plusAssign(dg);
            u.timesAssign(x);
            u.divAssign(1.5);
            pm(t + ".massign", u);
        }
        {
            final HepSymMatrix u = new HepSymMatrix(s);
            u.plusAssign(s2);
            u.minusAssign(dg);
            u.timesAssign(x);
            u.divAssign(2.5);
            u.plusAssign(dg2);
            pm(t + ".sassign", u);
        }
        {
            final HepDiagMatrix u = new HepDiagMatrix(dg);
            u.plusAssign(dg2);
            u.minusAssign(dg);
            u.timesAssign(x);
            u.divAssign(0.5);
            pm(t + ".dassign", u);
        }
        {
            final HepVector u = new HepVector(v);
            u.plusAssign(w);
            u.minusAssign(new HepMatrix(v));
            u.timesAssign(x);
            u.divAssign(3.0);
            u.plusAssign(new HepMatrix(w));
            pm(t + ".vassign", u);
        }
        {
            final HepMatrix u = new HepMatrix(n + 1, 2);
            u.assign(s);
            pm(t + ".m=s", u);
            u.assign(dg);
            pm(t + ".m=d", u);
            u.assign(v);
            pm(t + ".m=v", u);
            u.assign(b);
            pm(t + ".m=m", u);
        }
        {
            final HepSymMatrix u = new HepSymMatrix(2);
            u.assign(dg);
            pm(t + ".s=d", u);
            u.assign(a);
            pm(t + ".s.assign", u);
            u.assign(s2);
            pm(t + ".s=s", u);
        }
        {
            final HepDiagMatrix u = new HepDiagMatrix(1);
            u.assign(a);
            pm(t + ".d.assignm", u);
            u.assign(s);
            pm(t + ".d.assigns", u);
        }
        {
            final HepVector u = new HepVector();
            u.assign(new HepMatrix(w));
            pm(t + ".v=m", u);
        }
        pm(t + ".mT", a.T());
        pm(t + ".rT", r.T());
        pm(t + ".sT", s.T());
        pm(t + ".dT", dg.T());
        pm(t + ".vT", v.T());
        pm(t + ".mapply", a.apply(fapply));
        pm(t + ".sapply", s.apply(fapply));
        pm(t + ".dapply", dg.apply(fapply));
        pm(t + ".vapply", v.apply(fvec));
        pm(t + ".simm", s.similarity(r.T()));
        pm(t + ".sims", s.similarity(s2));
        pm(t + ".simT", s.similarityT(r));
        d(t + ".simv", s.similarity(v));
        pm(t + ".dsimm", dg.similarity(r.T()));
        pm(t + ".dsimT", dg.similarityT(r));
        d(t + ".dsimv", dg.similarity(v));
        d(t + ".dot", HepVector.dot(v, w));
        d(t + ".normsq", v.normsq());
        d(t + ".norm", v.norm());
        pm(t + ".vTv", HepSymMatrix.vT_times_v(v));
        d(t + ".trm", a.trace());
        d(t + ".trs", s.trace());
        d(t + ".trd", dg.trace());
        d(t + ".trr", r.trace());
        d(t + ".detm", a.determinant());
        d(t + ".dets", s.determinant());
        d(t + ".detd", dg.determinant());
        d(t + ".norm1", HepGenMatrix.norm1(a));
        d(t + ".norminf", HepGenMatrix.norm_infinity(a));
        d(t + ".normm", HepGenMatrix.norm(a));
        i(t + ".eqmm", a.isEqual(a));
        i(t + ".eqms", a.isEqual(s));
        i(t + ".eqss", new HepMatrix(s).isEqual(s));
        i(t + ".eqdd", dg.isEqual(dg2));
        if (n >= 2) {
            pm(t + ".msub", a.sub(1, n - 1, 2, n));
            pm(t + ".ssub", s.sub(2, n));
            pm(t + ".dsub", dg.sub(1, n - 1));
            pm(t + ".vsub", v.sub(2, n));
            final HepMatrix u = new HepMatrix(a);
            u.sub(2, 1, b.sub(1, n - 1, 1, 1));
            pm(t + ".msubset", u);
            final HepSymMatrix su = new HepSymMatrix(s);
            su.sub(2, s2.sub(1, n - 1));
            pm(t + ".ssubset", su);
            final HepDiagMatrix du = new HepDiagMatrix(dg);
            du.sub(2, dg2.sub(1, n - 1));
            pm(t + ".dsubset", du);
            final HepVector vu = new HepVector(v);
            vu.sub(2, w.sub(1, n - 1));
            pm(t + ".vsubset", vu);
        }
        pm(t + ".dsumm", HepMatrix.dsum(a, r));
        pm(t + ".dsums", HepSymMatrix.dsum(s, s2));
        pm(t + ".dsumd", HepDiagMatrix.dsum(dg, dg2));
        pm(t + ".dsumv", HepVector.dsum(v, w));
        {
            final HepMatrix u = new HepMatrix(a);
            final HepMatrix u2 = new HepMatrix(b);
            HepMatrix.swap(u, u2);
            pm(t + ".swap1", u);
            pm(t + ".swap2", u2);
        }
        {
            final HepVector u = new HepVector(v);
            final HepVector u2 = genv(n + 1);
            HepVector.swap(u, u2);
            pm(t + ".vswap1", u);
            pm(t + ".vswap2", u2);
        }
        d(t + ".row", a.row(n - 1).get(0));
        d(t + ".srow", s.row(0).get(n - 1));
        d(t + ".drow", dg.row(n - 1).get(n - 1));
    }

    private void inversions(int k) {
        final int n = 1 + k % 9;
        final String t = "inv";
        {
            final HepMatrix a = gen(n, n);
            final int[] ierr = {0};
            final HepMatrix b = a.inverse(ierr);
            i(t + ".mierr", ierr[0]);
            pm(t + ".minv", b);
            d(t + ".mdet", a.determinant());
            pm(t + ".mid", a.times(b));
            pm(t + ".solve", HepVector.solve(a, genv(n)));
            pm(t + ".qrinv", MatrixLinear.qr_inverse(a));
            pm(t + ".qrsolve", MatrixLinear.qr_solve(a, genv(n)));
            pm(t + ".qrsolvem", MatrixLinear.qr_solve(a, gen(n, 2)));
        }
        {
            final HepSymMatrix s = (k % 4 == 1) ? gens(n) : genpd(n);
            final int[] ierr = {0};
            final HepSymMatrix b = s.inverse(ierr);
            i(t + ".sierr", ierr[0]);
            pm(t + ".sinv", b);
            d(t + ".sdet", s.determinant());
            final HepSymMatrix c = new HepSymMatrix(s);
            c.invertBunchKaufman(ierr);
            i(t + ".bkierr", ierr[0]);
            pm(t + ".bk", c);
            d(t + ".cond", MatrixLinear.condition(s));
            final HepSymMatrix e = new HepSymMatrix(s);
            final HepMatrix u = MatrixLinear.diagonalize(e);
            pm(t + ".eigval", e);
            pm(t + ".eigvec", u);
            final HepSymMatrix f = new HepSymMatrix(s);
            pm(t + ".tridiag", MatrixLinear.tridiagonal(f));
            pm(t + ".tridiagS", f);
            if (n == 5 || n == 6) {
                final HepSymMatrix g = new HepSymMatrix(s);
                final HepSymMatrix h = new HepSymMatrix(s);
                if (n == 5) {
                    g.invertCholesky5(ierr);
                    i(t + ".ch5", ierr[0]);
                    h.invertHaywood5(ierr);
                } else {
                    g.invertCholesky6(ierr);
                    i(t + ".ch6", ierr[0]);
                    h.invertHaywood6(ierr);
                }
                pm(t + ".chol", g);
                pm(t + ".hay", h);
            }
        }
        {
            final HepDiagMatrix dg = gend(n);
            final int[] ierr = {0};
            final HepDiagMatrix b = dg.inverse(ierr);
            i(t + ".dierr", ierr[0]);
            pm(t + ".dinv", b);
        }
    }

    @Override
    protected void run() {
        HepSymMatrix.resetThread();
        for (int k = 0; k < 140; k++) ops(k);
        for (int k = 0; k < 120; k++) inversions(k);

        for (int n = 1; n <= 8; n++) {
            final HepMatrix z = new HepMatrix(n, n, 0);
            for (int c = 1; c <= n; c++) z.set(1, c, c);
            if (n > 1) {
                for (int c = 1; c <= n; c++) z.set(2, c, 2 * c);
            }
            final int[] ierr = {0};
            final HepMatrix zi = z.inverse(ierr);
            i("sing.mierr", ierr[0]);
            pm("sing.m", zi);
            d("sing.mdet", z.determinant());
            pm("sing.solve", HepVector.solve(z, new HepVector(n, 1)));
            final HepSymMatrix zs = new HepSymMatrix(n, 0);
            for (int c = 1; c <= n; c++) zs.set(1, c, c);
            final HepSymMatrix zsi = zs.inverse(ierr);
            i("sing.sierr", ierr[0]);
            pm("sing.s", zsi);
            final HepDiagMatrix zd = new HepDiagMatrix(n, 1);
            zd.set(n, n, 0);
            zd.invert(ierr);
            i("sing.dierr", ierr[0]);
            pm("sing.d", zd);
        }
        try {
            new HepMatrix(2, 2, 0).inverse();
            text("throw.m", "none");
        } catch (RuntimeException e) {
            text("throw.m", e.getMessage());
        }
        try {
            new HepSymMatrix(2, 0).inverse();
            text("throw.s", "none");
        } catch (RuntimeException e) {
            text("throw.s", e.getMessage());
        }
        try {
            new HepDiagMatrix(2, 0).inverse();
            text("throw.d", "none");
        } catch (RuntimeException e) {
            text("throw.d", e.getMessage());
        }

        for (int k = 0; k < 60; k++) {
            final int n = 5 + k % 2;
            final HepSymMatrix s = (k / 2) % 5 == 0 ? genpd(n) : gens(n);
            final int[] ierr = {0};
            s.invert(ierr);
            i("adapt.ierr", ierr[0]);
            pm("adapt.inv", s);
        }

        for (int k = 0; k < 40; k++) {
            final int n = 2 + k % 5;
            final HepMatrix a = gen(n, n);
            final HepSymMatrix s = gens(n);
            pm("house.m", MatrixLinear.house(a, 1 + k % n, 1));
            pm("house.s", MatrixLinear.house(s, 1 + k % n, 1));
            {
                final HepMatrix b = new HepMatrix(a);
                MatrixLinear.house_with_update(b, 1, 1);
                pm("house.upd", b);
            }
            {
                final HepMatrix b = new HepMatrix(a);
                final HepMatrix v = new HepMatrix(n, n, 0);
                MatrixLinear.house_with_update(b, v, 1, 1);
                pm("house.upd2a", b);
                pm("house.upd2v", v);
            }
            {
                final HepSymMatrix b = new HepSymMatrix(s);
                final HepMatrix v = new HepMatrix(n, n, 0);
                MatrixLinear.house_with_update2(b, v, 2 <= n ? 2 : 1, 1);
                pm("house.upd3a", b);
                pm("house.upd3v", v);
            }
            {
                final double[] cs = MatrixLinear.givens(a.get(1, 1), a.get(n, 1));
                d("givens.c", cs[0]);
                d("givens.s", cs[1]);
                final HepMatrix b = new HepMatrix(a);
                MatrixLinear.row_givens(b, cs[0], cs[1], 1, n);
                pm("givens.row", b);
                final HepMatrix e = new HepMatrix(a);
                MatrixLinear.col_givens(e, cs[0], cs[1], 1, n);
                pm("givens.col", e);
            }
            {
                final HepMatrix b = new HepMatrix(a);
                final HepMatrix q = MatrixLinear.qr_decomp(b);
                pm("qr.q", q);
                pm("qr.r", b);
                final HepVector y = genv(n);
                MatrixLinear.back_solve(b, y);
                pm("qr.backv", y);
                final HepMatrix ym = gen(n, 2);
                MatrixLinear.back_solve(b, ym);
                pm("qr.backm", ym);
            }
            {
                final HepSymMatrix b = new HepSymMatrix(s);
                MatrixLinear.diag_step(b, 1, n);
                pm("diagstep", b);
            }
        }
        {
            final HepVector[] A = {genv(3), genv(3), new HepVector(3, 0)};
            final HepVector[] B = {genv(3), genv(3), genv(3)};
            pm("minline", MatrixLinear.min_line_dist(A, B, 3));
        }

        {
            final HepRotation r = new HepRotation();
            r.rotateX(0.3);
            r.rotateY(-1.1);
            r.rotateZ(2.0);
            final HepMatrix m = new HepMatrix(1, 1);
            m.assign(r);
            pm("eq.rot", m);
            final HepVector v = new HepVector(5, 1);
            v.assign(new Hep3Vector(1.5, -2.5, 3.5));
            pm("eq.3v", v);
        }

        {
            final MixMaxRng e = new MixMaxRng(77);
            final RandGauss g = new RandGauss(e, 1.0, 2.0);
            final RandFlat f = new RandFlat(e);
            pm("rand.m", new HepMatrix(3, 4, g));
            pm("rand.s", new HepSymMatrix(4, f));
            pm("rand.d", new HepDiagMatrix(3, g));
            pm("rand.v", new HepVector(5, f));
            pm("init.m", new HepMatrix(3, 3, 1));
            pm("init.s", new HepSymMatrix(3, 1));
            pm("init.d", new HepDiagMatrix(3, 1));
            pm("init.v", new HepVector(3, 1));
        }

        {
            final HepMatrix a = gen(3, 2);
            final HepSymMatrix s = gens(3);
            final HepDiagMatrix dg = gend(2);
            final HepVector v = genv(3);
            {
                final COStream os = new COStream();
                a.print(os);
                s.print(os);
                dg.print(os);
                v.print(os);
                text("out.default", os.str());
            }
            {
                final COStream os = new COStream().fixed().precision(3);
                a.print(os);
                s.print(os);
                dg.print(os);
                v.print(os);
                text("out.fixed", os.str());
            }
            {
                final COStream os = new COStream().scientific().precision(10);
                a.print(os);
                v.print(os);
                text("out.sci", os.str());
            }
        }
    }
}
