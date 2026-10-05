package com.sphere.core.minuit2.validation;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.minuit2.ContoursError;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FCNGradientBase;
import com.sphere.core.minuit2.FumiliStandardChi2FCN;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.MinosError;
import com.sphere.core.minuit2.Minuit2Minimizer;
import com.sphere.core.minuit2.MnContours;
import com.sphere.core.minuit2.MnFumiliMinimize;
import com.sphere.core.minuit2.MnHesse;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.minuit2.MnMinos;
import com.sphere.core.minuit2.MnPlot;
import com.sphere.core.minuit2.MnPrint;
import com.sphere.core.minuit2.MnScan;
import com.sphere.core.minuit2.MnStrategy;
import com.sphere.core.minuit2.MnUserParameterState;
import com.sphere.core.minuit2.MnUserParameters;
import com.sphere.core.minuit2.SimplexMinimizer;
import com.sphere.core.minuit2.VariableMetricMinimizer;
import com.sphere.core.minuit2.validation.MnSim.GaussDataGen;
import com.sphere.core.minuit2.validation.MnSim.GaussFcn;
import com.sphere.core.minuit2.validation.MnSim.GaussFcn2;
import com.sphere.core.minuit2.validation.MnSim.GaussRandomGen;
import com.sphere.core.minuit2.validation.MnSim.GaussianModelFunction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The test programs of Minuit2 (test/MnTutorial and test/MnSim of ROOT),
 * line by line: each main writes to cout what the C++ main writes to
 * std::cout, so that the two texts can be compared.
 */
public final class Programs {

    private Programs() {
    }

    /** std::cout, std::cerr and the input files of a run. */
    public interface Io {
        COStream cout();

        COStream cerr();

        /** The text of an input file of the test directory, null when missing. */
        String input(String name);
    }

    /** A test: its name and main. */
    public record Program(String name, Function<Io, Integer> main) {
    }

    public static List<Program> all() {
        return List.of(
            new Program("Quad1FMain", Programs::quad1F),
            new Program("Quad4FMain", Programs::quad4F),
            new Program("Quad8FMain", Programs::quad8F),
            new Program("Quad12FMain", Programs::quad12F),
            new Program("DemoGaussSim", Programs::demoGaussSim),
            new Program("DemoFumili", Programs::demoFumili),
            new Program("PaulTest", Programs::paulTest),
            new Program("PaulTest2", Programs::paulTest2),
            new Program("PaulTest3", Programs::paulTest3),
            new Program("PaulTest4", Programs::paulTest4),
            new Program("ReneTest", Programs::reneTest),
            new Program("ParallelTest", Programs::parallelTest),
            new Program("demoMinimizer", Programs::demoMinimizer),
            new Program("Probe", io -> {
                io.cout().put(new Probe().run());
                return 0;
            }));
    }

    /* ---- MnTutorial ----------------------------------------------------------- */

    static final class Quad1F implements FCNGradientBase {
        private double errorDef = 1.;

        @Override
        public double value(double[] par) {
            final double x = par[0];
            return x * x;
        }

        @Override
        public double[] gradient(double[] par) {
            return new double[] {2. * par[0]};
        }

        @Override
        public void setErrorDef(double up) {
            errorDef = up;
        }

        @Override
        public double up() {
            return errorDef;
        }
    }

    static int quad1F(Io io) {
        final COStream cout = io.cout();
        {
            final Quad1F fcn = new Quad1F();
            final MnUserParameters upar = new MnUserParameters();
            upar.add("x", 1., 0.1);
            final MnMigrad migrad = new MnMigrad(fcn, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("min= ");
            min.print(cout);
            cout.endl();
        }
        {
            final Quad1F fcn = new Quad1F();
            final VariableMetricMinimizer mini = new VariableMetricMinimizer();
            final FunctionMinimum min = mini.minimize(fcn, new MnUserParameterState(new double[] {1.}, new double[] {0.1}));
            cout.put("min= ");
            min.print(cout);
            cout.endl();
        }
        {
            final Quad1F fcn = new Quad1F();
            final VariableMetricMinimizer mini = new VariableMetricMinimizer();
            final FunctionMinimum min = mini.minimize(fcn, new MnUserParameterState(new double[] {1.}, new double[] {0.1}));
            final MnMinos minos = new MnMinos(fcn, min);
            final double[] e0 = minos.errors(0);
            cout.put("par0: ").put(min.userState().value(0)).put(" ").put(e0[0]).put(" ").put(e0[1]).endl();
            fcn.setErrorDef(4.);
            final MnMinos minos2 = new MnMinos(fcn, min);
            final double[] e02 = minos2.errors(0);
            cout.put("par0: ").put(min.userState().value(0)).put(" ").put(e02[0]).put(" ").put(e02[1]).endl();
        }
        return 0;
    }

    static double quad4(double x, double y, double z, double w) {
        return ((1. / 70.) * (21 * x * x + 20 * y * y + 19 * z * z - 14 * x * z - 20 * y * z) + w * w);
    }

    static int quad4F(Io io) {
        final COStream cout = io.cout();
        final FCNBase fcn = new FCNBase() {
            @Override
            public double value(double[] p) {
                return quad4(p[0], p[1], p[2], p[3]);
            }

            @Override
            public double up() {
                return 1.;
            }
        };
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("x", 1., 0.1);
            upar.add("y", 1., 0.1);
            upar.add("z", 1., 0.1);
            upar.add("w", 1., 0.1);
            final MnMigrad migrad = new MnMigrad(fcn, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            final FCNGradientBase gfcn = new FCNGradientBase() {
                @Override
                public double value(double[] p) {
                    return quad4(p[0], p[1], p[2], p[3]);
                }

                @Override
                public double[] gradient(double[] par) {
                    final double x = par[0], y = par[1], z = par[2], w = par[3];
                    final double[] g = new double[4];
                    g[0] = (1. / 70.) * (42. * x - 14. * z);
                    g[1] = (1. / 70.) * (40. * y - 20. * z);
                    g[2] = (1. / 70.) * (38. * z - 14. * x - 20. * y);
                    g[3] = 2. * w;
                    return g;
                }

                @Override
                public double up() {
                    return 1.;
                }
            };
            final MnUserParameters upar = new MnUserParameters();
            upar.add("x", 1., 0.1);
            upar.add("y", 1., 0.1);
            upar.add("z", 1., 0.1);
            upar.add("w", 1., 0.1);
            final MnMigrad migrad = new MnMigrad(gfcn, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("minimum with grad calculation : ");
            min.print(cout);
            cout.endl();
            final MnHesse hesse = new MnHesse();
            hesse.apply(gfcn, min);
            cout.put("minimum after hesse: ");
            min.print(cout);
            cout.endl();
        }
        return 0;
    }

    static int quad8F(Io io) {
        final FCNBase fcn = new FCNBase() {
            @Override
            public double value(double[] p) {
                return ((1. / 70.) * (21 * p[0] * p[0] + 20 * p[1] * p[1] + 19 * p[2] * p[2] - 14 * p[0] * p[2] - 20 * p[1] * p[2])
                    + p[3] * p[3]
                    + (1. / 70.) * (21 * p[4] * p[4] + 20 * p[5] * p[5] + 19 * p[6] * p[6] - 14 * p[4] * p[6] - 20 * p[5] * p[6])
                    + p[7] * p[7]);
            }

            @Override
            public double up() {
                return 1.;
            }
        };
        return quadN(io, fcn, new String[] {"x", "y", "z", "w", "x0", "y0", "z0", "w0"});
    }

    static int quad12F(Io io) {
        final FCNBase fcn = new FCNBase() {
            @Override
            public double value(double[] p) {
                return ((1. / 70.) * (21 * p[0] * p[0] + 20 * p[1] * p[1] + 19 * p[2] * p[2] - 14 * p[0] * p[2] - 20 * p[1] * p[2])
                    + p[3] * p[3]
                    + (1. / 70.) * (21 * p[4] * p[4] + 20 * p[5] * p[5] + 19 * p[6] * p[6] - 14 * p[4] * p[6] - 20 * p[5] * p[6])
                    + p[7] * p[7]
                    + (1. / 70.) * (21 * p[8] * p[8] + 20 * p[9] * p[9] + 19 * p[10] * p[10] - 14 * p[8] * p[10]
                        - 20 * p[9] * p[10])
                    + p[11] * p[11]);
            }

            @Override
            public double up() {
                return 1.;
            }
        };
        return quadN(io, fcn, new String[] {"x", "y", "z", "w", "x0", "y0", "z0", "w0", "x1", "y1", "z1", "w1"});
    }

    private static int quadN(Io io, FCNBase fcn, String[] names) {
        final COStream cout = io.cout();
        final MnUserParameters upar = new MnUserParameters();
        for (String n : names) upar.add(n, 1., 0.1);
        final MnMigrad migrad = new MnMigrad(fcn, upar);
        final FunctionMinimum min = migrad.minimize();
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        return 0;
    }

    /* ---- MnSim ------------------------------------------------------------------ */

    /** mean, rms (or sqrt(rms2)), area of the data as the tests start from them. */
    private record Start(double mean, double rms2, double area) {
        static Start of(double[] meas, double[] pos) {
            double x = 0.;
            double x2 = 0.;
            double norm = 0.;
            final double dx = pos[1] - pos[0];
            double area = 0.;
            for (int i = 0; i < meas.length; i++) {
                norm += meas[i];
                x += (meas[i] * pos[i]);
                x2 += (meas[i] * pos[i] * pos[i]);
                area += dx * meas[i];
            }
            final double mean = x / norm;
            final double rms2 = x2 / norm - mean * mean;
            return new Start(mean, rms2, area);
        }

        double rms() {
            return rms2 > 0. ? Math.sqrt(rms2) : 1.;
        }
    }

    static int demoGaussSim(Io io) {
        final COStream cout = io.cout();
        final GaussDataGen gdg = new GaussDataGen(100);
        final double[] pos = gdg.positions();
        final double[] meas = gdg.measurements();
        final double[] var = gdg.variances();
        final GaussFcn fFCN = new GaussFcn(meas, pos, var);
        final Start s = Start.of(meas, pos);
        final double mean = s.mean(), rms = s.rms(), area = s.area();
        {
            final VariableMetricMinimizer fMinimizer = new VariableMetricMinimizer();
            final FunctionMinimum min = fMinimizer.minimize(fFCN,
                new MnUserParameterState(new double[] {mean, rms, area}, new double[] {0.1, 0.1, 0.1}));
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms, 0.1);
            upar.add("area", area, 0.1);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms, 0.1);
            upar.add("area", area, 0.1);
            upar.setLimits("mean", mean - 0.01, mean + 0.01);
            upar.setLimits(1, rms - 0.1, rms + 0.1);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            migrad.state().fix("mean");
            final FunctionMinimum min = migrad.minimize();
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
            migrad.state().release("mean");
            migrad.state().fix(1);
            final FunctionMinimum min1 = migrad.minimize();
            cout.put("minimum1: ");
            min1.print(cout);
            cout.endl();
            migrad.state().release(1);
            final FunctionMinimum min2 = migrad.minimize();
            cout.put("minimum2: ");
            min2.print(cout);
            cout.endl();
            migrad.state().removeLimits("mean");
            migrad.state().removeLimits("sigma");
            final FunctionMinimum min3 = migrad.minimize();
            cout.put("minimum3: ");
            min3.print(cout);
            cout.endl();
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms - 1., 0.1);
            upar.add("area", area, 0.1);
            upar.setLowerLimit("mean", mean - 0.01);
            upar.setUpperLimit("sigma", rms - 0.5);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("test Lower limit minimim= ");
            min.print(cout);
            cout.endl();
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms, 0.1);
            upar.add("area", area, 0.1);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            final MnMinos minos = new MnMinos(fFCN, min);
            {
                final double[] e0 = minos.errors(0);
                final double[] e1 = minos.errors(1);
                final double[] e2 = minos.errors(2);
                cout.put("1-sigma Minos errors: ").endl();
                cout.put("par0: ").put(min.userState().value("mean")).put(" ").put(e0[0]).put(" ").put(e0[1]).endl();
                cout.put("par1: ").put(min.userState().value(1)).put(" ").put(e1[0]).put(" ").put(e1[1]).endl();
                cout.put("par2: ").put(min.userState().value("area")).put(" ").put(e2[0]).put(" ").put(e2[1]).endl();
            }
            {
                fFCN.setErrorDef(4.);
                final MinosError e0 = minos.minos(0);
                final MinosError e1 = minos.minos(1);
                final MinosError e2 = minos.minos(2);
                cout.put("2-sigma Minos errors: ").endl();
                e0.print(cout);
                cout.endl();
                e1.print(cout);
                cout.endl();
                e2.print(cout);
                cout.endl();
            }
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms, 0.1);
            upar.add("area", area, 0.1);
            final double meanLow = -50.03;
            final double rmsUp = 1.55;
            cout.put("sigma Limit: ").put(rmsUp).put("\tmean limit: ").put(meanLow).endl();
            upar.setLowerLimit("mean", meanLow);
            upar.setUpperLimit("sigma", rmsUp);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            final MnMinos minos = new MnMinos(fFCN, min);
            {
                fFCN.setErrorDef(9.);
                final double[] e0 = minos.errors(0);
                final double[] e1 = minos.errors(1);
                final double[] e2 = minos.errors(2);
                cout.put("3-sigma Minos errors with limits: ").endl();
                cout.precision(16);
                cout.put("par0: ").put(min.userState().value("mean")).put(" ").put(e0[0]).put(" ").put(e0[1]).endl();
                cout.put("par1: ").put(min.userState().value(1)).put(" ").put(e1[0]).put(" ").put(e1[1]).endl();
                cout.put("par2: ").put(min.userState().value("area")).put(" ").put(e2[0]).put(" ").put(e2[1]).endl();
            }
        }
        {
            final MnUserParameters upar = new MnUserParameters();
            upar.add("mean", mean, 0.1);
            upar.add("sigma", rms, 0.1);
            upar.add("area", area, 0.1);
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            final MnContours contours = new MnContours(fFCN, min);
            fFCN.setErrorDef(2.41);
            final List<MnPrint.Point> cont = new ArrayList<>(contours.points(0, 1, 20));
            fFCN.setErrorDef(5.99);
            final ContoursError cont4 = contours.contour(0, 1, 20);
            final MnPlot plot = new MnPlot();
            cont.addAll(cont4.points());
            cout.put(plot.render(min.userState().value("mean"), min.userState().value("sigma"), cont));
            cont4.print(cout);
            cout.endl();
        }
        return 0;
    }

    static int demoFumili(Io io) {
        final COStream cout = io.cout();
        final GaussDataGen gdg = new GaussDataGen(100);
        final double[] pos = gdg.positions();
        final double[] meas = gdg.measurements();
        final double[] var = gdg.variances();
        final Start s = Start.of(meas, pos);
        final MnUserParameters upar = new MnUserParameters();
        upar.add("mean", s.mean(), 0.1);
        upar.add("sigma", s.rms(), 0.1);
        upar.add("area", s.area(), 0.1);
        final GaussianModelFunction modelFunction = new GaussianModelFunction();
        final FumiliStandardChi2FCN fFCN = new FumiliStandardChi2FCN(modelFunction, meas, pos, var);
        {
            cout.put("Minimize using FUMILI : \n").endl();
            final MnFumiliMinimize fumili = new MnFumiliMinimize(fFCN, upar);
            final FunctionMinimum min = fumili.minimize();
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            cout.put("Minimize using MIGRAD : \n").endl();
            final MnMigrad migrad = new MnMigrad(fFCN, upar);
            final FunctionMinimum min = migrad.minimize();
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        return 0;
    }

    /** The numbers of a text file, read as istream &gt;&gt; double reads them. */
    static double[] numbers(String text) {
        final String[] tok = text.trim().split("\\s+");
        final List<Double> l = new ArrayList<>();
        for (String t : tok) {
            if (t.isEmpty()) continue;
            try {
                l.add(Double.parseDouble(t));
            } catch (NumberFormatException e) {
                break;
            }
        }
        return MnSim.toArray(l);
    }

    static int paulTest(Io io) {
        final COStream cout = io.cout();
        final String in = io.input("paul.txt");
        if (in == null) {
            io.cerr().put("Error opening input data file").endl();
            return 1;
        }
        final List<Double> positions = new ArrayList<>(), measurements = new ArrayList<>(), var = new ArrayList<>();
        int nmeas = 0;
        final double[] v = numbers(in);
        for (int k = 0; k + 3 < v.length; k += 4) {
            positions.add(v[k]);
            final double ni = v[k + 1] * v[k + 2];
            measurements.add(ni);
            var.add(ni);
            nmeas += (int) ni;
        }
        cout.put("size= ").put(var.size()).endl();
        cout.put("nmeas: ").put(nmeas).endl();
        final GaussFcn fFCN = new GaussFcn(MnSim.toArray(measurements), MnSim.toArray(positions), MnSim.toArray(var));
        final Start s = Start.of(fFCN.measurements(), fFCN.positions());
        cout.put("initial mean: ").put(s.mean()).endl();
        cout.put("initial sigma: ").put(Math.sqrt(s.rms2())).endl();
        cout.put("initial area: ").put(s.area()).endl();
        final MnUserParameters upar = new MnUserParameters();
        upar.add("mean", s.mean(), 0.1);
        upar.add("sigma", Math.sqrt(s.rms2()), 0.1);
        upar.add("area", s.area(), 0.1);
        final MnMigrad migrad = new MnMigrad(fFCN, upar);
        cout.put("start migrad ").endl();
        final FunctionMinimum min = migrad.minimize();
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        cout.put("start Minos").endl();
        final MnMinos minos = new MnMinos(fFCN, min);
        final double[] e0 = minos.errors(0);
        final double[] e1 = minos.errors(1);
        final double[] e2 = minos.errors(2);
        cout.put("par0: ").put(min.userState().value("mean")).put(" ").put(e0[0]).put(" ").put(e0[1]).endl();
        cout.put("par1: ").put(min.userState().value("sigma")).put(" ").put(e1[0]).put(" ").put(e1[1]).endl();
        cout.put("par2: ").put(min.userState().value("area")).put(" ").put(e2[0]).put(" ").put(e2[1]).endl();
        return 0;
    }

    /** Reads x y width err un1 un2, keeping err &gt;= 1e-8: (positions, measurements, variances, nmeas). */
    private record Six(List<Double> positions, List<Double> measurements, List<Double> var, double nmeas) {
        static Six read(String text) {
            final List<Double> positions = new ArrayList<>(), measurements = new ArrayList<>(), var = new ArrayList<>();
            double nmeas = 0;
            final double[] v = numbers(text);
            for (int k = 0; k + 5 < v.length; k += 6) {
                final double x = v[k], y = v[k + 1], err = v[k + 3];
                if (err < 1.e-8) continue;
                positions.add(x);
                measurements.add(y);
                var.add(err * err);
                nmeas += y;
            }
            return new Six(positions, measurements, var, nmeas);
        }
    }

    static int paulTest2(Io io) {
        final COStream cout = io.cout();
        final String in = io.input("paul2.txt");
        if (in == null) {
            io.cerr().put("Error opening input data file").endl();
            return 1;
        }
        final Six d = Six.read(in);
        cout.put("size= ").put(d.var().size()).endl();
        cout.put("nmeas: ").put(d.nmeas()).endl();
        final GaussFcn fFCN = new GaussFcn(MnSim.toArray(d.measurements()), MnSim.toArray(d.positions()), MnSim.toArray(d.var()));
        final Start s = Start.of(fFCN.measurements(), fFCN.positions());
        cout.put("initial mean: ").put(s.mean()).endl();
        cout.put("initial sigma: ").put(Math.sqrt(s.rms2())).endl();
        cout.put("initial area: ").put(s.area()).endl();
        final double[] initVal = {s.mean(), Math.sqrt(s.rms2()), s.area()};
        cout.put("initial fval: ").put(fFCN.value(initVal)).endl();
        final MnUserParameters upar = new MnUserParameters();
        upar.add("mean", s.mean(), 1.);
        upar.add("sigma", Math.sqrt(s.rms2()), 1.);
        upar.add("area", s.area(), 10.);
        final MnMigrad migrad = new MnMigrad(fFCN, upar);
        cout.put("start migrad ").endl();
        final FunctionMinimum min = migrad.minimize();
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        cout.put("start Minos").endl();
        final MnMinos minos = new MnMinos(fFCN, min);
        final double[] e0 = minos.errors(0);
        final double[] e1 = minos.errors(1);
        final double[] e2 = minos.errors(2);
        cout.put("par0: ").put(min.userState().value("mean")).put(" ").put(e0[0]).put(" ").put(e0[1]).endl();
        cout.put("par1: ").put(min.userState().value(1)).put(" ").put(e1[0]).put(" ").put(e1[1]).endl();
        cout.put("par2: ").put(min.userState().value(2)).put(" ").put(e2[0]).put(" ").put(e2[1]).endl();
        return 0;
    }

    static int paulTest3(Io io) {
        final COStream cout = io.cout();
        final String in = io.input("paul3.txt");
        if (in == null) {
            io.cerr().put("Error opening input data file").endl();
            return 1;
        }
        final Six d = Six.read(in);
        cout.put("size= ").put(d.var().size()).endl();
        cout.put("nmeas: ").put(d.nmeas()).endl();
        final GaussFcn2 fFCN = new GaussFcn2(MnSim.toArray(d.measurements()), MnSim.toArray(d.positions()),
            MnSim.toArray(d.var()));
        final Start s = Start.of(fFCN.measurements(), fFCN.positions());
        cout.put("initial mean: ").put(s.mean()).endl();
        cout.put("initial sigma: ").put(Math.sqrt(s.rms2())).endl();
        cout.put("initial area: ").put(s.area()).endl();
        final double sig = Math.sqrt(s.rms2());
        final double[] initVal = {s.mean(), sig, s.area(), s.mean(), sig, s.area()};
        cout.put("initial fval: ").put(fFCN.value(initVal)).endl();
        final MnUserParameters upar = new MnUserParameters();
        upar.add("mean1", s.mean(), 10.);
        upar.add("sig1", sig, 10.);
        upar.add("area1", s.area(), 10.);
        upar.add("mean2", s.mean(), 10.);
        upar.add("sig2", sig, 10.);
        upar.add("area2", s.area(), 10.);
        final MnMigrad migrad = new MnMigrad(fFCN, upar);
        cout.put("start migrad ").endl();
        FunctionMinimum min = migrad.minimize();
        if (!min.isValid()) {
            cout.put("FM is invalid, try with strategy = 2.").endl();
            final MnMigrad migrad2 = new MnMigrad(fFCN, new MnUserParameterState(upar), new MnStrategy(2));
            min = migrad2.minimize();
        }
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        return 0;
    }

    static double powerLaw(double p0, double p1, double x) {
        return p1 * MnSimMath.exp(MnSimMath.log(x) * p0);
    }

    /** exp and log of the C library (a name to keep powerLaw on one line). */
    private static final class MnSimMath {
        static double exp(double x) {
            return com.sphere.core.minuit2.Cxx.exp(x);
        }

        static double log(double x) {
            return com.sphere.core.minuit2.Cxx.log(x);
        }
    }

    static int paulTest4(Io io) {
        final COStream cout = io.cout();
        final String in = io.input("paul4.txt");
        if (in == null) {
            io.cerr().put("Error opening input data file").endl();
            return 1;
        }
        final List<Double> positions = new ArrayList<>(), measurements = new ArrayList<>(), var = new ArrayList<>();
        final double[] v = numbers(in);
        for (int k = 0; k + 2 < v.length; k += 3) {
            positions.add(v[k]);
            measurements.add(v[k + 1]);
            var.add(v[k + 2] * v[k + 2]);
        }
        cout.put("size= ").put(var.size()).endl();
        final double[] meas = MnSim.toArray(measurements), pos = MnSim.toArray(positions), mvar = MnSim.toArray(var);
        final FCNBase chi2 = new FCNBase() {
            @Override
            public double value(double[] par) {
                double c2 = 0.;
                for (int n = 0; n < meas.length; n++) {
                    c2 += ((powerLaw(par[0], par[1], pos[n]) - meas[n]) * (powerLaw(par[0], par[1], pos[n]) - meas[n]) / mvar[n]);
                }
                return c2;
            }

            @Override
            public double up() {
                return 1.;
            }
        };
        final FCNBase mlh = new FCNBase() {
            @Override
            public double value(double[] par) {
                double logsum = 0.;
                for (int n = 0; n < meas.length; n++) {
                    final double k = meas[n];
                    final double mu = powerLaw(par[0], par[1], pos[n]);
                    logsum += (k * MnSimMath.log(mu) - mu);
                }
                return -logsum;
            }

            @Override
            public double up() {
                return 0.5;
            }
        };
        {
            cout.put(">>> test Chi2").endl();
            final MnUserParameters upar = new MnUserParameters();
            upar.add("p0", -2.3, 0.2);
            upar.add("p1", 1100., 10.);
            final MnMigrad migrad = new MnMigrad(chi2, upar);
            cout.put("start migrad ").endl();
            FunctionMinimum min = migrad.minimize();
            if (!min.isValid()) {
                cout.put("FM is invalid, try with strategy = 2.").endl();
                min = new MnMigrad(chi2, new MnUserParameterState(upar), new MnStrategy(2)).minimize();
            }
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            cout.put(">>> test log LikeliHood").endl();
            final MnUserParameters upar = new MnUserParameters();
            upar.add("p0", -2.1, 0.2);
            upar.add("p1", 1000., 10.);
            final MnMigrad migrad = new MnMigrad(mlh, upar);
            cout.put("start migrad ").endl();
            FunctionMinimum min = migrad.minimize();
            if (!min.isValid()) {
                cout.put("FM is invalid, try with strategy = 2.").endl();
                min = new MnMigrad(mlh, new MnUserParameterState(upar), new MnStrategy(2)).minimize();
            }
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
        }
        {
            cout.put(">>> test Simplex").endl();
            final double[] par = {-2.3, 1100.};
            final double[] err = {1., 1.};
            final SimplexMinimizer simplex = new SimplexMinimizer();
            cout.put("start simplex").endl();
            final FunctionMinimum min = simplex.minimize(chi2, new MnUserParameterState(par, err));
            cout.put("minimum: ");
            min.print(cout);
            cout.endl();
            final FunctionMinimum min2 = simplex.minimize(mlh, new MnUserParameterState(par, err));
            cout.put("minimum: ");
            min2.print(cout);
            cout.endl();
        }
        return 0;
    }

    static final double[] RENE = {38., 36., 46., 52., 54., 52., 61., 52., 64., 77., 60., 56., 78., 71., 81., 83., 89., 96.,
        118., 96., 109., 111., 107., 107., 135., 156., 196., 137., 160., 153., 185., 222., 251., 270., 329., 422., 543.,
        832., 1390., 2835., 3462., 2030., 1130., 657., 469., 411., 375., 295., 281., 281., 289., 273., 297., 256., 274.,
        287., 280., 274., 286., 279., 293., 314., 285., 322., 307., 313., 324., 351., 314., 314., 301., 361., 332., 342.,
        338., 396., 356., 344., 395., 416., 406., 411., 422., 393., 393., 409., 455., 427., 448., 459., 403., 441., 510.,
        501., 502., 482., 487., 506., 506., 526., 517., 534., 509., 482., 591., 569., 518., 609., 569., 598., 627., 617.,
        610., 662., 666., 652., 671., 647., 650., 701.};

    static final class ReneFcn implements FCNBase {
        private final double[] measurements;

        ReneFcn(double[] meas) {
            measurements = meas.clone();
        }

        @Override
        public double value(double[] par) {
            final double a = par[2];
            final double b = par[1];
            final double c = par[0];
            final double p0 = par[3];
            final double p1 = par[4];
            final double p2 = par[5];
            double fval = 0.;
            for (int i = 0; i < measurements.length; i++) {
                final double ni = measurements[i];
                if (ni < 1.e-10) continue;
                final double xi = (i + 1.) / 40. - 1. / 80.;
                final double ei = ni;
                final double nexp = a * xi * xi + b * xi + c
                    + (p0 * p1 / MnSim.TWO_PI) / com.sphere.core.minuit2.Cxx.max(1.e-10, (xi - p2) * (xi - p2) + 0.25 * p1 * p1);
                fval += (ni - nexp) * (ni - nexp) / ei;
            }
            return fval;
        }

        @Override
        public double up() {
            return 1.;
        }
    }

    static int reneTest(Io io) {
        final COStream cout = io.cout();
        final ReneFcn fFCN = new ReneFcn(RENE);
        final MnUserParameters upar = new MnUserParameters();
        upar.add("p0", 100., 10.);
        upar.add("p1", 100., 10.);
        upar.add("p2", 100., 10.);
        upar.add("p3", 100., 10.);
        upar.add("p4", 1., 0.3);
        upar.add("p5", 1., 0.3);
        cout.put("initial parameters: ");
        upar.print(cout);
        cout.endl();
        cout.put("start migrad ").endl();
        final MnMigrad migrad = new MnMigrad(fFCN, upar);
        FunctionMinimum min = migrad.minimize();
        if (!min.isValid()) {
            cout.put("FM is invalid, try with strategy = 2.").endl();
            final MnMigrad migrad2 = new MnMigrad(fFCN, min.userState(), new MnStrategy(2));
            min = migrad2.minimize();
        }
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        final double[] ones = {1., 1., 1., 1., 1., 1.};
        {
            final MnScan scan = new MnScan(fFCN, new MnUserParameterState(ones, ones));
            cout.put("scan parameters: ");
            scan.parameters().print(cout);
            cout.endl();
            final MnPlot plot = new MnPlot();
            for (int i = 0; i < upar.variableParameters(); i++) {
                final List<MnPrint.Point> xy = scan.scan(i);
                cout.put(plot.render(xy));
            }
            scan.parameters().print(cout);
            cout.endl();
        }
        {
            final MnScan scan = new MnScan(fFCN, new MnUserParameterState(ones, ones));
            cout.put("scan parameters: ");
            scan.parameters().print(cout);
            cout.endl();
            scan.minimize();
            scan.parameters().print(cout);
            cout.endl();
        }
        return 0;
    }

    static double gaussPdf(double x, double x0, double sigma) {
        final double tmp = (x - x0) / sigma;
        return (1.0 / (Math.sqrt(MnSim.TWO_PI) * Math.abs(sigma))) * com.sphere.core.minuit2.Cxx.exp(-tmp * tmp / 2);
    }

    static int parallelTest(Io io) {
        final COStream cout = io.cout();
        final int ndim = 20;
        final int ndata = 1000;
        cout.put("do fit of ").put(ndim).put(" dimensional data on ").put(ndata).put(" events ").endl();
        final double[][] data = new double[ndata][];
        final double[] event = new double[ndim];
        final double[] mean = new double[ndim];
        final double[] sigma = new double[ndim];
        for (int k = 0; k < ndim; ++k) {
            mean[k] = -(double) ndim / 2 + k;
            sigma[k] = 1. + 0.1 * k;
        }
        for (int i = 0; i < ndata; ++i) {
            for (int k = 0; k < ndim; ++k) {
                final GaussRandomGen rgaus = new GaussRandomGen(mean[k], sigma[k]);
                event[k] = rgaus.next();
            }
            data[i] = event.clone();
        }
        final FCNBase fcn = new FCNBase() {
            @Override
            public double value(double[] p) {
                double logl = 0;
                for (double[] x : data) {
                    double f = 0;
                    for (int k = 0; k < x.length; ++k) {
                        double y = gaussPdf(x[k], p[2 * k], p[2 * k + 1]);
                        y = com.sphere.core.minuit2.Cxx.max(y, 1.E-300);
                        f += com.sphere.core.minuit2.Cxx.log(y);
                    }
                    logl -= f;
                }
                return logl;
            }

            @Override
            public double up() {
                return 0.5;
            }
        };
        final double[] initPar = new double[2 * ndim];
        for (int k = 0; k < ndim; ++k) {
            initPar[2 * k] = 0;
            initPar[2 * k + 1] = 1;
        }
        final double[] initErr = new double[2 * ndim];
        java.util.Arrays.fill(initErr, 0.1);
        final VariableMetricMinimizer fMinimizer = new VariableMetricMinimizer();
        final FunctionMinimum min = fMinimizer.minimize(fcn, new MnUserParameterState(initPar, initErr));
        cout.put("minimum: ");
        min.print(cout);
        cout.endl();
        return 0;
    }

    static int demoMinimizer(Io io) {
        final COStream cout = io.cout();
        final String algoName = "";
        final Minuit2Minimizer min = new Minuit2Minimizer(algoName);
        min.setMaxFunctionCalls(1000000);
        min.setTolerance(0.001);
        min.setPrintLevel(0);
        final java.util.function.ToDoubleFunction<double[]> rosenbrock = x -> {
            final double tmp1 = (x[1] - x[0] * x[0]);
            final double tmp2 = (1. - x[0]);
            return 100. * tmp1 * tmp1 + tmp2 * tmp2;
        };
        min.setFunction(2, rosenbrock);
        min.setVariable(0, "x", -1., 0.01);
        min.setVariable(1, "y", 1.2, 0.01);
        min.minimize();
        final double[] xs = min.x();
        cout.put("Minimum: f(").put(xs[0]).put(",").put(xs[1]).put("): ").put(min.minValue()).endl();
        if (min.minValue() < 1.E-4 && rosenbrock.applyAsDouble(xs) < 1.E-4) {
            cout.put("Minuit2 -  ").put(algoName).put("   converged to the right minimum").endl();
            return 0;
        }
        io.cerr().put("ERROR:  Minuit2 - ").put(algoName).put("   failed to converge !!!").endl();
        return -1;
    }
}
