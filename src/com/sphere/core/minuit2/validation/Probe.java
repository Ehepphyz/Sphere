package com.sphere.core.minuit2.validation;

import com.sphere.core.minuit2.ContoursError;
import com.sphere.core.minuit2.Cxx;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FCNGradientBase;
import com.sphere.core.minuit2.FumiliMinimizer;
import com.sphere.core.minuit2.FumiliStandardChi2FCN;
import com.sphere.core.minuit2.FumiliStandardMaximumLikelihoodFCN;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.LASymMatrix;
import com.sphere.core.minuit2.LAVector;
import com.sphere.core.minuit2.MinimumState;
import com.sphere.core.minuit2.MinosError;
import com.sphere.core.minuit2.MinuitParameter;
import com.sphere.core.minuit2.Minuit2Minimizer;
import com.sphere.core.minuit2.MnContours;
import com.sphere.core.minuit2.MnEigen;
import com.sphere.core.minuit2.MnFumiliMinimize;
import com.sphere.core.minuit2.MnGlobalCorrelationCoeff;
import com.sphere.core.minuit2.MnHesse;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.minuit2.MnMinimize;
import com.sphere.core.minuit2.MnMinos;
import com.sphere.core.minuit2.MnParameterScan;
import com.sphere.core.minuit2.MnPrint;
import com.sphere.core.minuit2.MnScan;
import com.sphere.core.minuit2.MnSimplex;
import com.sphere.core.minuit2.MnStrategy;
import com.sphere.core.minuit2.MnUserCovariance;
import com.sphere.core.minuit2.MnUserParameterState;
import com.sphere.core.minuit2.MnUserParameters;
import com.sphere.core.minuit2.ParametricFunction;
import com.sphere.core.minuit2.VariableMetricBuilder;
import com.sphere.core.minuit2.VariableMetricMinimizer;

import java.util.List;
import java.util.Locale;

/**
 * The twin of the C++ probe (m2probe.cxx): the same scenarios, every number
 * written as its 64 bits, so that the Java port and the C++ can be compared
 * bit for bit, not only to the ten digits the test programs print.
 */
public final class Probe {

    private final StringBuilder out = new StringBuilder();

    private void printf(String fmt, Object... args) {
        out.append(String.format(Locale.ROOT, fmt, args));
    }

    static String h(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    static String hv(LAVector v) {
        final StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.size(); i++) {
            if (i > 0) s.append(',');
            s.append(h(v.get(i)));
        }
        return s.length() == 0 ? "-" : s.toString();
    }

    static String hs(LASymMatrix m) {
        return hd(m.data());
    }

    static String hd(double[] v) {
        final StringBuilder s = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) s.append(',');
            s.append(h(v[i]));
        }
        return s.length() == 0 ? "-" : s.toString();
    }

    static String hd(List<Double> v) {
        final double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return hd(a);
    }

    static int b(boolean x) {
        return x ? 1 : 0;
    }

    void dumpState(String label, MnUserParameterState st) {
        printf("%s ustate valid=%d cov=%d covstat=%d fval=%s edm=%s nfcn=%d\n", label, b(st.isValid()), b(st.hasCovariance()),
            st.covarianceStatus(), h(st.fval()), h(st.edm()), st.nfcn());
        for (MinuitParameter p : st.minuitParameters()) {
            printf("%s   p %s val=%s err=%s fix=%d const=%d lim=%d%d\n", label, p.name(), h(p.value()), h(p.error()),
                b(p.isFixed()), b(p.isConst()), b(p.hasLowerLimit()), b(p.hasUpperLimit()));
        }
        printf("%s   int %s\n", label, hd(st.intParameters()));
        if (st.hasCovariance()) {
            printf("%s   cov %s\n", label, hd(st.covariance().data()));
            printf("%s   icov %s\n", label, hd(st.intCovariance().data()));
            final MnGlobalCorrelationCoeff g = st.globalCC();
            if (g.isValid()) printf("%s   gcc %s\n", label, hd(g.globalCC()));
        }
    }

    void dump(String label, FunctionMinimum min) {
        printf("%s valid=%d nfcn=%d fval=%s edm=%s states=%d above=%d limit=%d up=%s\n", label, b(min.isValid()), min.nfcn(),
            h(min.fval()), h(min.edm()), min.states().size(), b(min.isAboveMaxEdm()), b(min.hasReachedCallLimit()), h(min.up()));
        for (MinimumState s : min.states()) {
            printf("%s   s nfcn=%d fval=%s edm=%s err=%d dcov=%s vec=%s grad=%s g2=%s\n", label, s.nfcn(), h(s.fval()), h(s.edm()),
                s.error().status().ordinal(), h(s.error().dcovar()), hv(s.vec()), hv(s.gradient().vec()), hv(s.gradient().g2()));
            if (s.error().isAvailable()) printf("%s   e %s\n", label, hs(s.error().invHessian()));
        }
        dumpState(label, min.userState());
    }

    void dumpMinos(String label, MinosError me) {
        printf("%s minos par=%d min=%s nfcn=%d lv=%d uv=%d ll=%d ul=%d lm=%d um=%d ln=%d un=%d", label, me.parameter(), h(me.min()),
            me.nfcn(), b(me.lowerValid()), b(me.upperValid()), b(me.atLowerLimit()), b(me.atUpperLimit()), b(me.atLowerMaxFcn()),
            b(me.atUpperMaxFcn()), b(me.lowerNewMin()), b(me.upperNewMin()));
        if (me.lowerValid() || me.atLowerLimit()) printf(" lower=%s", h(me.lower()));
        if (me.upperValid() || me.atUpperLimit()) printf(" upper=%s", h(me.upper()));
        printf("\n");
    }

    void dumpPoints(String label, List<MnPrint.Point> pts) {
        printf("%s points n=%d\n", label, pts.size());
        for (MnPrint.Point p : pts) printf("%s   %s %s\n", label, h(p.x()), h(p.y()));
    }

    /* ---- functions --------------------------------------------------------------- */

    static final class Rosen implements FCNBase {
        @Override
        public double value(double[] x) {
            final double a = x[1] - x[0] * x[0];
            final double b = 1. - x[0];
            return 100. * a * a + b * b;
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class Data {
        final double[] x, y, v;

        Data() {
            this(60);
        }

        Data(int n) {
            x = new double[n];
            y = new double[n];
            v = new double[n];
            for (int i = 0; i < n; i++) {
                final double xi = -4. + 0.15 * i;
                final double yi = 12. * Cxx.exp(-0.5 * (xi - 0.4) * (xi - 0.4) / (1.1 * 1.1)) + 0.3 * Cxx.sin(3.7 * i) + 0.5;
                x[i] = xi;
                y[i] = yi;
                v[i] = 0.04 + 0.01 * yi;
            }
        }
    }

    static final class GaussChi2 implements FCNBase {
        final Data d = new Data();
        double up = 1.;

        @Override
        public double value(double[] p) {
            double c = 0.;
            for (int i = 0; i < d.x.length; i++) {
                final double t = (d.x[i] - p[0]) / p[1];
                final double f = p[2] * Cxx.exp(-0.5 * t * t) + p[3];
                final double r = f - d.y[i];
                c += r * r / d.v[i];
            }
            return c;
        }

        @Override
        public double up() {
            return up;
        }

        @Override
        public void setErrorDef(double u) {
            up = u;
        }
    }

    static final class Quartic implements FCNGradientBase {
        @Override
        public double value(double[] p) {
            final double x = p[0], y = p[1], z = p[2];
            final double t1 = x - 1.;
            return t1 * t1 * t1 * t1 + 2. * (y + 0.5) * (y + 0.5) + Cxx.cos(z) + 0.1 * x * y + z * z * 0.05;
        }

        @Override
        public double[] gradient(double[] p) {
            final double x = p[0], y = p[1], z = p[2];
            return new double[] {4. * (x - 1.) * (x - 1.) * (x - 1.) + 0.1 * y, 4. * (y + 0.5) + 0.1 * x, -Cxx.sin(z) + 0.1 * z};
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class WithHessian implements FCNBase {
        @Override
        public boolean hasGradient() {
            return true;
        }

        @Override
        public boolean hasHessian() {
            return true;
        }

        @Override
        public boolean hasG2() {
            return true;
        }

        @Override
        public double value(double[] p) {
            final double x = p[0], y = p[1];
            return (x - 2.) * (x - 2.) * 3. + Cxx.exp(0.3 * y) - 0.6 * y + x * y * 0.2;
        }

        @Override
        public double[] gradient(double[] p) {
            final double x = p[0], y = p[1];
            return new double[] {6. * (x - 2.) + 0.2 * y, 0.3 * Cxx.exp(0.3 * y) - 0.6 + 0.2 * x};
        }

        @Override
        public double[] g2(double[] p) {
            return new double[] {6., 0.09 * Cxx.exp(0.3 * p[1])};
        }

        @Override
        public double[] hessian(double[] p) {
            return new double[] {6., 0.2, 0.2, 0.09 * Cxx.exp(0.3 * p[1])};
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class Internal implements FCNBase {
        @Override
        public boolean hasGradient() {
            return true;
        }

        @Override
        public GradientParameterSpace gradParameterSpace() {
            return GradientParameterSpace.Internal;
        }

        @Override
        public double value(double[] p) {
            return (p[0] - 0.7) * (p[0] - 0.7) + 3. * (p[1] + 1.2) * (p[1] + 1.2) + p[0] * p[1] * 0.5;
        }

        @Override
        public double[] gradient(double[] p) {
            return new double[] {2. * (p[0] - 0.7) + 0.5 * p[1], 6. * (p[1] + 1.2) + 0.5 * p[0]};
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class DoubleWell implements FCNBase {
        @Override
        public double value(double[] p) {
            final double x = p[0], y = p[1];
            return (x * x - 1.) * (x * x - 1.) + (y - 0.3 * x) * (y - 0.3 * x) + 0.05 * x;
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class Model extends ParametricFunction {
        Model() {
            super(1);
            setParameters(new double[] {0.});
        }

        @Override
        public double value(double[] x) {
            final double t = (par[0] - x[0]) / x[1];
            return x[2] * Cxx.exp(-0.5 * t * t) + x[3];
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static final class Expo extends ParametricFunction {
        Expo() {
            super(1);
            setParameters(new double[] {0.});
        }

        @Override
        public double value(double[] x) {
            return x[0] * Cxx.exp(-x[0] * par[0]);
        }

        @Override
        public double up() {
            return 0.5;
        }
    }

    static double rosenbrock(double[] x) {
        final double a = x[1] - x[0] * x[0];
        final double b = 1. - x[0];
        return 100. * a * a + b * b;
    }

    /** Runs every scenario; the text, line for line the C++ probe's. */
    public String run() {
        {
            final Rosen f = new Rosen();
            for (int s = 0; s < 3; s++) {
                final MnUserParameters u = new MnUserParameters();
                u.add("x", -1.2, 0.1);
                u.add("y", 1., 0.1);
                final MnMigrad m = new MnMigrad(f, new MnUserParameterState(u), new MnStrategy(s));
                dump("rosen_m" + s, m.minimize());
            }
            {
                final VariableMetricMinimizer bfgs = new VariableMetricMinimizer(VariableMetricBuilder.ErrorUpdatorType.kBFGS);
                dump("rosen_bfgs", bfgs.minimize(f, new MnUserParameterState(new double[] {-1.2, 1.}, new double[] {0.1, 0.1})));
            }
            {
                final MnUserParameters u = new MnUserParameters();
                u.add("x", -1.2, 0.1);
                u.add("y", 1., 0.1);
                final MnSimplex sx = new MnSimplex(f, u);
                dump("rosen_simplex", sx.minimize());
                final MnMinimize mm = new MnMinimize(f, u);
                dump("rosen_minimize", mm.minimize());
                final MnScan sc = new MnScan(f, u);
                dump("rosen_scan", sc.minimize());
                dumpPoints("rosen_pscan", sc.scan(0, 21, -2., 2.));
                final MnParameterScan ps = new MnParameterScan(f, u);
                dumpPoints("rosen_ps", ps.scan(1));
            }
            {
                final MnUserParameters u = new MnUserParameters();
                u.add("x", -1.2, 0.1);
                u.add("y", 1., 0.1);
                final MnMigrad m = new MnMigrad(f, u);
                dump("rosen_calllimit", m.minimize(30));
                final MnMigrad m0 = new MnMigrad(f, u);
                m0.minimizer().builder().setStorageLevel(0);
                dump("rosen_storage0", m0.minimize());
            }
        }
        {
            final GaussChi2 f = new GaussChi2();
            final MnUserParameters u = new MnUserParameters();
            u.add("mean", 0., 0.2, -1., 2.);
            u.add("sigma", 1.5, 0.1);
            u.setLowerLimit("sigma", 0.2);
            u.add("area", 10., 1.);
            u.setUpperLimit("area", 50.);
            u.add("bkg", 0.3, 0.1);
            final MnMigrad m = new MnMigrad(f, u);
            final FunctionMinimum min = m.minimize();
            dump("gauss_lim", min);
            new MnHesse().apply(f, min);
            dump("gauss_lim_hesse", min);
            final MnMinos mn = new MnMinos(f, min);
            for (int i = 0; i < 4; i++) dumpMinos("gauss_lim_minos" + i, mn.minos(i));
            final MnContours c = new MnContours(f, min);
            final ContoursError ce = c.contour(0, 1, 12);
            dumpPoints("gauss_lim_contour", ce.points());
            dumpMinos("gauss_lim_cx", ce.xMinosError());
            final MnUserParameterState s3 = new MnHesse(3).apply(f, min.userState());
            dumpState("gauss_hesse3", s3);
            dumpState("gauss_hesse2", new MnHesse(2).apply(f, min.userState()));
            f.setErrorDef(4.);
            final MnMinos mn4 = new MnMinos(f, min);
            dumpMinos("gauss_lim_minos4", mn4.minos(1));
            f.setErrorDef(1.);
            final MnUserParameters u2 = new MnUserParameters();
            u2.add("mean", 0., 0.2, -1., 0.2);
            u2.add("sigma", 1.5, 0.1);
            u2.add("area", 10., 1.);
            u2.add("bkg", 0.3, 0.1);
            final MnMigrad m2 = new MnMigrad(f, u2);
            final FunctionMinimum min2 = m2.minimize();
            dump("gauss_atlimit", min2);
            dumpMinos("gauss_atlimit_minos0", new MnMinos(f, min2).minos(0));
            final MnUserParameterState st = new MnUserParameterState(min.userState());
            st.fix("bkg");
            dumpState("st_fix", st);
            st.release("bkg");
            st.setValue("mean", 0.35);
            st.setLimits("area", 0., 30.);
            st.removeLimits("sigma");
            dumpState("st_edit", st);
            final MnMigrad m3 = new MnMigrad(f, st, new MnStrategy(2));
            dump("gauss_edit", m3.minimize());
            final MnUserParameters u4 = new MnUserParameters();
            u4.add("mean", 0.3, 0.1);
            u4.add("sigma", 1.2, 0.1);
            u4.add("area", 11., 0.5);
            u4.add("bkg", 0.4, 0.1);
            final double[] cov = {0.01, 0.001, 0.02, 0., 0., 0.3, 0., 0., 0.001, 0.005};
            final MnUserParameterState st4 = new MnUserParameterState(u4, new MnUserCovariance(cov, 4));
            dumpState("st_cov", st4);
            final MnMigrad m4 = new MnMigrad(f, st4);
            final FunctionMinimum min4 = m4.minimize();
            dump("gauss_cov", min4);
            final MnUserCovariance hsn = min4.userState().hessian();
            printf("gauss_cov hessian %s\n", hd(hsn.data()));
            printf("gauss_cov eigen %s\n", hd(new MnEigen().eigenvalues(min4.userCovariance())));
            final MnUserParameterState st5 = new MnUserParameterState(min4.userState());
            st5.fix(1);
            dumpState("st_squeeze", st5);
        }
        {
            final Quartic f = new Quartic();
            final MnUserParameters u = new MnUserParameters();
            u.add("x", 3., 0.5);
            u.add("y", 1., 0.5);
            u.add("z", 1.0, 0.3);
            final MnMigrad m = new MnMigrad(f, u);
            final FunctionMinimum min = m.minimize();
            dump("quartic", min);
            new MnHesse().apply(f, min);
            dump("quartic_hesse", min);
        }
        {
            final WithHessian f = new WithHessian();
            final MnUserParameters u = new MnUserParameters();
            u.add("x", 0.5, 0.2);
            u.add("y", 1., 0.2);
            u.setLowerLimit("y", -3.);
            final MnMigrad m = new MnMigrad(f, new MnUserParameterState(u), new MnStrategy(2));
            final FunctionMinimum min = m.minimize();
            dump("withhess", min);
            new MnHesse().apply(f, min);
            dump("withhess_hesse", min);
            final MnUserParameters u2 = new MnUserParameters();
            u2.add("x", 0.5, 0.2, -1., 5.);
            u2.add("y", 1., 0.2);
            u2.setUpperLimit("y", 4.);
            dump("withhess_lim", new MnMigrad(f, u2).minimize());
        }
        {
            final Internal f = new Internal();
            final MnUserParameters u = new MnUserParameters();
            u.add("a", 0., 0.1);
            u.add("b", 0., 0.1);
            dump("internal", new MnMigrad(f, u).minimize());
        }
        {
            final DoubleWell f = new DoubleWell();
            final MnUserParameters u = new MnUserParameters();
            u.add("x", 0.02, 0.1);
            u.add("y", 0., 0.1);
            dump("doublewell", new MnMigrad(f, u).minimize());
            dump("doublewell_s0", new MnMigrad(f, new MnUserParameterState(u), new MnStrategy(0)).minimize());
        }
        {
            final Data d = new Data();
            final Model model = new Model();
            final FumiliStandardChi2FCN chi2 = new FumiliStandardChi2FCN(model, d.y, d.x, d.v);
            final MnUserParameters u = new MnUserParameters();
            u.add("mean", 0.2, 0.1);
            u.add("sigma", 1.4, 0.1);
            u.add("area", 10., 0.5);
            u.add("bkg", 0.2, 0.1);
            for (String method : new String[] {"tr", "trs", "ls"}) {
                final FumiliMinimizer fm = new FumiliMinimizer();
                fm.setMethod(method);
                dump("fumili_" + method, fm.minimize(chi2, new MnUserParameterState(u), new MnStrategy(1), 0, 0.1));
            }
            dump("fumili_app", new MnFumiliMinimize(chi2, u).minimize());
            final double[] times = new double[80];
            for (int i = 0; i < 80; i++) {
                times[i] = 0.05 + 0.9 * Math.abs(Cxx.sin(0.37 * i + 0.1)) * (1. + 0.5 * Cxx.cos(1.3 * i));
            }
            final FumiliStandardMaximumLikelihoodFCN ml = new FumiliStandardMaximumLikelihoodFCN(new Expo(), times);
            final MnUserParameters ue = new MnUserParameters();
            ue.add("lambda", 1.5, 0.2);
            dump("fumili_ml", new MnFumiliMinimize(ml, ue).minimize());
        }
        {
            final Minuit2Minimizer mm = new Minuit2Minimizer();
            mm.setFunction(2, Probe::rosenbrock);
            mm.setMaxFunctionCalls(100000);
            mm.setTolerance(0.001);
            mm.setLimitedVariable(0, "x", -1., 0.01, -2., 2.);
            mm.setVariable(1, "y", 1.2, 0.01);
            mm.setValidError(true);
            final boolean ok = mm.minimize();
            printf("api ok=%d status=%d covstat=%d fval=%s edm=%s ncalls=%d x=%s,%s err=%s,%s cov01=%s corr=%s\n", b(ok), mm.status(),
                mm.covMatrixStatus(), h(mm.minValue()), h(mm.edm()), mm.nCalls(), h(mm.x()[0]), h(mm.x()[1]), h(mm.errors()[0]),
                h(mm.errors()[1]), h(mm.covMatrix(0, 1)), h(mm.correlation(0, 1)));
            final double[] e = new double[2];
            final boolean mok = mm.getMinosError(0, e);
            printf("api minos ok=%d lo=%s up=%s status=%d\n", b(mok), h(e[0]), h(e[1]), mm.status());
            final boolean hok = mm.hesse();
            printf("api hesse ok=%d covstat=%d err=%s,%s\n", b(hok), mm.covMatrixStatus(), h(mm.errors()[0]), h(mm.errors()[1]));
            final int ns = 9;
            final double[] xs = new double[ns], ys = new double[ns];
            mm.scan(1, ns, xs, ys, 0., 0.);
            printf("api scan");
            for (int i = 0; i < ns; i++) printf(" %s:%s", h(xs[i]), h(ys[i]));
            printf("\n");
            final int np = 8;
            final double[] cx = new double[np], cy = new double[np];
            final boolean cok = mm.contour(0, 1, np, cx, cy);
            printf("api contour ok=%d", b(cok));
            for (int i = 0; cok && i < np; i++) printf(" %s:%s", h(cx[i]), h(cy[i]));
            printf("\n");

            final GaussChi2 g = new GaussChi2();
            final Minuit2Minimizer mg = new Minuit2Minimizer("simplex");
            mg.setFunction(4, g::value);
            mg.setVariable(0, "mean", 0.1, 0.1);
            mg.setLowerLimitedVariable(1, "sigma", 1.3, 0.1, 0.1);
            mg.setVariable(2, "area", 9., 0.5);
            mg.setFixedVariable(3, "bkg", 0.5);
            final boolean gok = mg.minimize();
            printf("api_simplex ok=%d status=%d fval=%s x=%s,%s,%s,%s ncalls=%d\n", b(gok), mg.status(), h(mg.minValue()),
                h(mg.x()[0]), h(mg.x()[1]), h(mg.x()[2]), h(mg.x()[3]), mg.nCalls());
            final Minuit2Minimizer mb = new Minuit2Minimizer("bfgs");
            mb.setFunction(4, g::value);
            mb.setStrategy(2);
            mb.setVariable(0, "mean", 0.1, 0.1);
            mb.setVariable(1, "sigma", 1.3, 0.1);
            mb.setUpperLimitedVariable(2, "area", 9., 0.5, 40.);
            mb.setVariable(3, "bkg", 0.5, 0.1);
            final boolean bok = mb.minimize();
            printf("api_bfgs ok=%d status=%d fval=%s x=%s,%s,%s,%s ncalls=%d\n", b(bok), mb.status(), h(mb.minValue()),
                h(mb.x()[0]), h(mb.x()[1]), h(mb.x()[2]), h(mb.x()[3]), mb.nCalls());
            printf("api_bfgs gcc %s\n", hd(mb.globalCC()));
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        final StringBuilder err = new StringBuilder();
        MnPrint.setSink(l -> err.append(l).append('\n'));
        final String text = new Probe().run();
        MnPrint.setSink(null);
        if (args.length > 0) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), text);
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[0] + ".err"), err.toString());
        } else {
            System.out.print(text);
            System.err.print(err);
        }
    }
}
