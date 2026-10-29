package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.fields.XF;
import com.sphere.core.clhep.genfun.AbsFunction;
import com.sphere.core.clhep.genfun.Argument;
import com.sphere.core.clhep.genfun.Sin;
import com.sphere.core.clhep.genfun.Variable;
import com.sphere.core.clhep.geometry.Rotate3D;
import com.sphere.core.clhep.geometry.RotateZ3D;
import com.sphere.core.clhep.geometry.Transform3D;
import com.sphere.core.clhep.geometry.Translate3D;
import com.sphere.core.clhep.geometry.Vector3D;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.ProcessState;
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.clhep.randomobjects.HepRandomVector;
import com.sphere.core.clhep.randomobjects.RandMultiGauss;

import java.util.Locale;

/**
 * The Java twin of randobj_ref.cc: RandMultiGauss on several engines, dimensions and covariances, with its
 * default and explicit mu and S, fireArray; the flat vectors of HepRandomVector; XF's Pow, products and pre-
 * and post-multiplications of transformation functions. Started as a fresh C++ process.
 */
public final class RandomObjectsReference extends Reference {

    @Override
    public String name() {
        return "randobj";
    }

    private static String K(String n, int k) {
        return n + "." + k;
    }

    private void pv(String n, HepVector v) {
        final StringBuilder b = new StringBuilder(n).append(' ').append(v.num_row());
        for (int r = 1; r <= v.num_row(); r++) b.append(String.format(Locale.ROOT, " %016x", Double.doubleToRawLongBits(v.get(r))));
        line(b.toString());
    }

    private void pt(String n, Transform3D t) {
        final double[] e = {t.xx(), t.xy(), t.xz(), t.dx(), t.yx(), t.yy(), t.yz(), t.dy(), t.zx(), t.zy(), t.zz(), t.dz()};
        final StringBuilder b = new StringBuilder(n);
        for (double x : e) b.append(String.format(Locale.ROOT, " %016x", Double.doubleToRawLongBits(x)));
        line(b.toString());
    }

    private HepVector genv(int n) {
        final HepVector v = new HepVector(n);
        for (int a = 1; a <= n; a++) v.set(a, sym(3.0));
        return v;
    }

    private HepSymMatrix genpd(int n) {
        final HepMatrix a = new HepMatrix(n, n);
        for (int r = 1; r <= n; r++) {
            for (int c = 1; c <= n; c++) a.set(r, c, sym(2.0));
        }
        final HepSymMatrix s = new HepSymMatrix(n);
        for (int r = 1; r <= n; r++) {
            for (int c = 1; c <= r; c++) {
                double t = 0;
                for (int k = 1; k <= n; k++) t += a.get(r, k) * a.get(c, k);
                s.set(r, c, t + (r == c ? 0.5 : 0));
            }
        }
        return s;
    }

    private void multi(String n, HepRandomEngine eng, int dim, int dim2) {
        final HepVector mu = genv(dim);
        final HepSymMatrix S = genpd(dim);
        final RandMultiGauss dist = new RandMultiGauss(eng, mu, S);
        for (int k = 0; k < 12; k++) pv(K(n, k), dist.fire());
        final HepVector[] arr = new HepVector[5];
        dist.fireArray(5, arr);
        for (int k = 0; k < 5; k++) pv(K(n + ".arr", k), arr[k]);
        final HepVector mu2 = genv(dim2);
        final HepSymMatrix S2 = genpd(dim2);
        for (int k = 0; k < 6; k++) pv(K(n + ".mu", k), dist.fire(mu2, S2));
        final HepVector[] arr2 = new HepVector[4];
        dist.fireArray(4, arr2, mu2, S2);
        for (int k = 0; k < 4; k++) pv(K(n + ".arr2", k), arr2[k]);
        pv(n + ".op", dist.fire());
        pv(n + ".op2", dist.fire(mu2, S2));
    }

    @Override
    protected void run() {
        ProcessState.restart();
        multi("mg.triple", new TripleRand(12345L), 3, 4);
        multi("mg.mixmax", new MixMaxRng(777L), 1, 5);
        multi("mg.james", new HepJamesRandom(31415L), 6, 2);
        multi("mg.james2", new HepJamesRandom(2718L), 2, 7);
        {
            final HepRandomVector rv = new HepRandomVector(54321L);
            pv("rv.flat", rv.flat());
            final HepVector[] a = {new HepVector(2), new HepVector(3), new HepVector(1)};
            rv.flatArray(3, a);
            for (int k = 0; k < 3; k++) pv(K("rv.arr", k), a[k]);
            final MixMaxRng e2 = new MixMaxRng(99L);
            rv.flatArray(e2, 3, a);
            for (int k = 0; k < 3; k++) pv(K("rv.arr2", k), a[k]);
            pv("rv.op", rv.fire());
            pv("rv.flat2", rv.flat(e2));
            final HepRandomVector rv0 = new HepRandomVector();
            final HepVector[] b = {new HepVector(4)};
            rv0.flatArray(1, b);
            pv("rv0.arr", b[0]);
        }
        {
            final double angle = pos(2.0, 0.2);
            final double ax = sym(1.0);
            final double ay = sym(1.0);
            final double az = pos(1.0, 0.5);
            final double tx = sym(2.0);
            final double ty = sym(2.0);
            final double tz = sym(2.0);
            final Transform3D T = new Translate3D(tx, ty, tz).times(new Rotate3D(angle, new Vector3D(ax, ay, az)));
            final double angle2 = pos(1.0, 0.1);
            final Transform3D T2 = new RotateZ3D(angle2).times(new Translate3D(0.5, 0.25, -1.0));
            final Variable x = new Variable();
            final XF.Pow P = new XF.Pow(T, x);
            final XF.Pow P2 = new XF.Pow(T2, AbsFunction.times(2.0, x).times(x).minus(1.0));
            final XF.Pow P3 = new XF.Pow(T, new Sin());
            final XF.Product prod = P.times(P2);
            final XF.Product prod3 = prod.times(P3);
            final XF.PreMult pre = XF.times(T2, P);
            final XF.PostMult post = P2.times(T);
            final XF.PostMult mixed = XF.times(T, P3).times(T2);
            i("xf.dim", prod.dimensionality());
            i("xf.dim2", post.dimensionality());
            for (int k = 0; k < 6; k++) {
                final double v = pos(3.0, -1.0);
                pt(K("xf.pow", k), P.call(v));
                pt(K("xf.pow2", k), P2.call(v));
                pt(K("xf.pow3", k), P3.call(v));
                pt(K("xf.prod", k), prod.call(v));
                pt(K("xf.prod3", k), prod3.call(v));
                pt(K("xf.pre", k), pre.call(v));
                pt(K("xf.post", k), post.call(v));
                pt(K("xf.mixed", k), mixed.call(v));
                final Argument A = new Argument(1);
                A.set(0, v);
                pt(K("xf.arg", k), prod.call(A));
            }
            final XF.Pow copy = new XF.Pow(P);
            pt("xf.copy", copy.call(0.75));
        }
    }
}
