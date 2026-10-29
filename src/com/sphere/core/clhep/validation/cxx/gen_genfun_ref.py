"""Writes genfun_ref.cc (against native CLHEP + GSL) and its Java twin GenfunReference.java from one spec.

Every snippet is given twice, C++ and Java, side by side, so that the two programs make the same draws in
the same order and print the same lines. One draw per C++ statement.
"""
import pathlib
import sys

C = pathlib.Path(sys.argv[1])        # this folder (cxx/): genfun_ref.cc
J = pathlib.Path(sys.argv[2])        # the validation package: GenfunReference.java

cpp, java = [], []


def both(c, j):
    cpp.append(c)
    java.append(j)


def num(x):
    s = repr(float(x))
    return s


# ---- 1-D functions: name, C++ declaration of f, Java declaration of f, parameters, x range, count, derivative
def fn(name, cdecl, jdecl, params=(), lo=-5, hi=5, count=8, deriv=True, cextra="", jextra=""):
    c = ["  {", "    " + cdecl]
    j = ["        {", "            " + jdecl]
    for acc, draw in params:
        c.append("    { double v = %s; f.%s.setValue(v); }" % (draw, acc))
        j.append("            { final double v = %s; f.%s.setValue(v); }" % (draw, acc))
    if cextra:
        c.append("    " + cextra)
        j.append("            " + jextra)
    ev = "eval1" if deriv else "eval0"
    c.append('    %s("%s", f, %s, %s, %d);' % (ev, name, num(lo), num(hi), count))
    j.append('            %s("%s", f, %s, %s, %d);' % (ev, name, num(lo), num(hi), count))
    c.append("  }")
    j.append("        }")
    both("\n".join(c), "\n".join(j))


def simple(name, ctype, jexpr, lo, hi, count=8, deriv=True):
    fn(name, "%s f;" % ctype if "(" not in ctype else "%s;" % ctype.replace("(", " f(", 1),
       "final AbsFunction f = %s;" % jexpr, (), lo, hi, count, deriv)


# ================================================================ elementary functions
simple("Sin", "Sin", "new Sin()", -6, 6)
simple("Cos", "Cos", "new Cos()", -6, 6)
simple("Tan", "Tan", "new Tan()", -1.4, 1.4)
simple("ASin", "ASin", "new ASin()", -0.9, 0.9)
simple("ACos", "ACos", "new ACos()", -0.9, 0.9)
simple("ATan", "ATan", "new ATan()", -5, 5)
simple("Exp", "Exp", "new Exp()", -5, 5)
simple("Ln", "Ln", "new Ln()", 0.1, 10)
simple("Sqrt", "Sqrt", "new Sqrt()", 0.1, 10)
simple("Square", "Square", "new Square()", -5, 5)
simple("Abs", "Abs", "new Abs()", -5, 5)
simple("Theta", "Theta", "new Theta()", -5, 5)
simple("LogGamma", "LogGamma", "new LogGamma()", 0.2, 10)
simple("Gamma", "Gamma", "new Gamma()", 0.2, 6)
simple("Erf", "Erf", "new Erf()", -3, 3)
simple("KroneckerDelta", "KroneckerDelta", "new KroneckerDelta()", -2, 2)
simple("X", "X_", "new X_()", -5, 5)
simple("Variable", "Variable", "new Variable()", -5, 5, 4)
simple("Power3", "Power(3)", "new Power(3)", -3, 3)
simple("Power-2", "Power(-2)", "new Power(-2)", 0.5, 3)
simple("Power0", "Power(0)", "new Power(0)", -2, 2, 3)
simple("Power2.5", "Power(2.5)", "new Power(2.5)", 0.1, 4)
simple("Mod", "Mod(1.5)", "new Mod(1.5)", -5, 5)
for n in range(0, 7):
    simple("Hermite%d" % n, "HermitePolynomial(%d)" % n, "new HermitePolynomial(%d)" % n, -3, 3, 5)
for n, k in [(0, 0), (1, 2), (2, 1), (3, 0), (4, 2), (5, 3)]:
    simple("Laguerre%d.%d" % (n, k), "AssociatedLaguerre(%d, %d)" % (n, k), "new AssociatedLaguerre(%d, %d)" % (n, k), 0, 10, 5)
for k in range(1, 6):
    simple("ChiSq%d" % k, "CumulativeChiSquare(%d)" % k, "new CumulativeChiSquare(%d)" % k, 0.1, 15, 5)

# ================================================================ functions of parameters
fn("Rectangular", "Rectangular f;", "final Rectangular f = new Rectangular();",
   [("x0()", "pos(1.0, -2.0)"), ("x1()", "pos(1.0, 1.0)"), ("baseline()", "pos(1.0, 0.0)"), ("height()", "pos(1.0, 1.0)")], -3, 3)
fn("PeriodicRectangular", "PeriodicRectangular f;", "final PeriodicRectangular f = new PeriodicRectangular();",
   [("a()", "pos(0.5, 0.5)"), ("b()", "pos(0.5, 0.5)"), ("height()", "pos(1.0, 1.0)")], -5, 5)
fn("IncompleteGamma", "IncompleteGamma f;", "final IncompleteGamma f = new IncompleteGamma();", [("a()", "pos(4.5, 0.5)")], 0.1, 8)
fn("Exponential", "Exponential f;", "final Exponential f = new Exponential();", [("decayConstant()", "pos(2.5, 0.5)")], 0, 5)
fn("ReverseExponential", "ReverseExponential f;", "final ReverseExponential f = new ReverseExponential();",
   [("decayConstant()", "pos(2.5, 0.5)")], -5, 0.5)
fn("Gaussian", "Gaussian f;", "final Gaussian f = new Gaussian();", [("mean()", "sym(1.0)"), ("sigma()", "pos(1.5, 0.5)")], -4, 4)
fn("Landau", "Landau f;", "final Landau f = new Landau();", [("peak()", "sym(1.0)"), ("width()", "pos(1.5, 0.5)")], -3, 10)
fn("Logistic", "LogisticFunction f;", "final LogisticFunction f = new LogisticFunction();",
   [("x0()", "pos(0.6, 0.2)"), ("a()", "pos(3.0, 1.0)")], -1, 40, 10)
fn("NonrelBW", "NonrelativisticBWDistribution f;", "final NonrelativisticBWDistribution f = new NonrelativisticBWDistribution();",
   [("mass()", "pos(20.0, 40.0)"), ("width()", "pos(8.0, 2.0)")], 20, 80)
fn("RelBW", "RelativisticBWDistribution f;", "final RelativisticBWDistribution f = new RelativisticBWDistribution();",
   [("mass()", "pos(20.0, 40.0)"), ("width()", "pos(8.0, 2.0)")], 20, 80)
fn("Voigt", "VoigtProfile f;", "final VoigtProfile f = new VoigtProfile();",
   [("mass()", "pos(20.0, 40.0)"), ("width()", "pos(8.0, 2.0)"), ("sigma()", "pos(7.0, 1.0)")], 20, 80)
fn("Punctured", "PuncturedSmearedExp f;", "final PuncturedSmearedExp f = new PuncturedSmearedExp();",
   [("lifetime()", "pos(1.5, 0.5)"), ("sigma()", "pos(0.8, 0.2)")], -1, 5, 8, True,
   "for (int k = 0; k < 3; k++) { double a = pos(1.0, 0.2 + k); double b = pos(1.0, 0.7 + k); f.puncture(a, b); }",
   "for (int k = 0; k < 3; k++) { final double a = pos(1.0, 0.2 + k); final double b = pos(1.0, 0.7 + k); f.puncture(a, b); }")
fn("PtRel", "PtRelFcn f;", "final PtRelFcn f = new PtRelFcn();",
   [("P0()", "pos(0.6, 0.2)"), ("P1()", "pos(1.0, 0.5)"), ("P2()", "pos(2.0, 1.0)"), ("P3()", "pos(1.0, 0.5)"),
    ("P4()", "pos(1.5, 0.5)"), ("P5()", "pos(3.0, 0.0)")], -0.5, 8)
fn("Beta", "BetaDistribution f;", "final BetaDistribution f = new BetaDistribution();",
   [("alpha()", "pos(3.0, 1.0)"), ("beta()", "pos(3.0, 1.0)")], 0.05, 0.95)
fn("GammaDist", "GammaDistribution f;", "final GammaDistribution f = new GammaDistribution();",
   [("alpha()", "pos(3.0, 1.0)"), ("beta()", "pos(1.5, 0.5)")], 0.1, 10)
for t in ["MIXED", "UNMIXED", "SMEARED_EXP", "SMEARED_COS_EXP", "SMEARED_SIN_EXP", "SMEARED_NEG_EXP"]:
    fn("AC." + t, "AnalyticConvolution f(AnalyticConvolution::%s);" % t,
       "final AnalyticConvolution f = new AnalyticConvolution(AnalyticConvolution.Type.%s);" % t,
       [("lifetime()", "pos(1.5, 0.5)"), ("frequency()", "pos(1.8, 0.2)"), ("sigma()", "pos(0.9, 0.1)"), ("offset()", "sym(0.2)")],
       -2, 6)
for t in ["MIXED", "SMEARED_COS_EXP", "SMEARED_SIN_EXP"]:
    fn("AC0." + t, "AnalyticConvolution f(AnalyticConvolution::%s);" % t,
       "final AnalyticConvolution f = new AnalyticConvolution(AnalyticConvolution.Type.%s);" % t,
       [("lifetime()", "pos(1.5, 0.5)"), ("frequency()", "pos(1.8, 0.2)")], 0.01, 6, 5, False,
       "f.sigma().setValue(0.0);", "f.sigma().setValue(0.0);")
fn("AC6.UNMIXED", "AnalyticConvolution f(AnalyticConvolution::UNMIXED);",
   "final AnalyticConvolution f = new AnalyticConvolution(AnalyticConvolution.Type.UNMIXED);",
   [("frequency()", "pos(1.8, 0.2)")], -2, 6, 5, True,
   "f.lifetime().setValue(0.1); f.sigma().setValue(1.0);", "f.lifetime().setValue(0.1); f.sigma().setValue(1.0);")
both("""  {
    double v = sym(5.0);
    FixedConstant f(v);
    eval1("FixedConstant", f, -5.0, 5.0, 3);
  }
  {
    Parameter p("p", 2.0);
    double v = sym(5.0);
    p.setValue(v);
    FloatingConstant f(p);
    eval1("FloatingConstant", f, -5.0, 5.0, 3);
  }""", """        {
            final double v = sym(5.0);
            final FixedConstant f = new FixedConstant(v);
            eval1("FixedConstant", f, -5.0, 5.0, 3);
        }
        {
            final Parameter p = new Parameter("p", 2.0);
            final double v = sym(5.0);
            p.setValue(v);
            final FloatingConstant f = new FloatingConstant(p);
            eval1("FloatingConstant", f, -5.0, 5.0, 3);
        }""")

# ================================================================ algebra of functions and parameters
ALG = [
    ("sum", "f + g", "f.plus(g)"), ("diff", "f - g", "f.minus(g)"), ("prod", "f * g", "f.times(g)"),
    ("quot", "f / g", "f.div(g)"), ("neg", "-f", "f.negate()"), ("ct", "2.5 * f", "AbsFunction.times(2.5, f)"),
    ("tc", "f * 2.5", "f.times(2.5)"), ("cp", "2.5 + f", "AbsFunction.plus(2.5, f)"), ("pc", "f + 2.5", "f.plus(2.5)"),
    ("cm", "2.5 - f", "AbsFunction.minus(2.5, f)"), ("mc", "f - 2.5", "f.minus(2.5)"),
    ("co", "2.5 / g", "AbsFunction.div(2.5, g)"), ("oc", "f / 2.5", "f.div(2.5)"), ("comp", "f(g)", "f.of(g)"),
    ("comp2", "g(h)", "g.of(h)"), ("big", "(f * g + h) / (2.0 + h)", "f.times(g).plus(h).div(AbsFunction.plus(2.0, h))"),
    ("nest", "f(g(f) * h) - 3.0 * h(f)", "f.of(g.of(f).times(h)).minus(AbsFunction.times(3.0, h.of(f)))"),
]
c = ["  {", "    Sin f;", "    Exp g;", "    Square h;"]
j = ["        {", "            final Sin f = new Sin();", "            final Exp g = new Exp();", "            final Square h = new Square();"]
for n, ce, je in ALG:
    c.append('    eval1("alg.%s", %s, -2.0, 2.0, 6);' % (n, ce))
    j.append('            eval1("alg.%s", %s, -2.0, 2.0, 6);' % (n, je))
c.append("  }")
j.append("        }")
both("\n".join(c), "\n".join(j))

PAR = [
    ("sum", "p + q", "p.plus(q)"), ("diff", "p - q", "p.minus(q)"), ("prod", "p * q", "p.times(q)"), ("quot", "p / q", "p.div(q)"),
    ("neg", "-p", "p.negate()"), ("ct", "3.5 * p", "AbsParameter.times(3.5, p)"), ("tc", "p * 3.5", "p.times(3.5)"),
    ("cp", "3.5 + p", "AbsParameter.plus(3.5, p)"), ("pc", "p + 3.5", "p.plus(3.5)"),
    ("cm", "3.5 - p", "AbsParameter.minus(3.5, p)"), ("mc", "p - 3.5", "p.minus(3.5)"),
    ("co", "3.5 / p", "AbsParameter.div(3.5, p)"), ("oc", "p / 3.5", "p.div(3.5)"),
    ("comp", "Sin()(p)", "new Sin().of(p)"), ("nest", "(p * q - 1.0) / (2.0 + p)", "p.times(q).minus(1.0).div(AbsParameter.plus(2.0, p))"),
]
FPAR = [
    ("fpp", "Sin() + p", "new Sin().plus(p)"), ("ftp", "Sin() * p", "new Sin().times(p)"), ("fmp", "Sin() - p", "new Sin().minus(p)"),
    ("fdp", "Sin() / p", "new Sin().div(p)"), ("ptf", "p * Sin()", "p.times(new Sin())"), ("ppf", "p + Sin()", "p.plus(new Sin())"),
    ("pmf", "p - Sin()", "p.minus(new Sin())"), ("pdf", "p / Exp()", "p.div(new Exp())"), ("float", "FloatingConstant(p * q)", "new FloatingConstant(p.times(q))"),
    ("fsum", "Sin() * (p + q)", "new Sin().times(p.plus(q))"),
]
c = ["  {", '    Parameter p("p", 1.0), q("q", 2.0);', "    { double v = pos(2.0, 1.0); p.setValue(v); }", "    { double v = pos(2.0, 1.0); q.setValue(v); }"]
j = ["        {", '            final Parameter p = new Parameter("p", 1.0);', '            final Parameter q = new Parameter("q", 2.0);',
     "            { final double v = pos(2.0, 1.0); p.setValue(v); }", "            { final double v = pos(2.0, 1.0); q.setValue(v); }"]
for n, ce, je in PAR:
    c.append('    d("par.%s", (%s).getValue());' % (n, ce))
    j.append('            d("par.%s", %s.getValue());' % (n, je))
for n, ce, je in FPAR:
    c.append('    eval1("par.%s", %s, -3.0, 3.0, 4);' % (n, ce))
    j.append('            eval1("par.%s", %s, -3.0, 3.0, 4);' % (n, je))
c += ['    {', '      GENFUNCTION fp = Sin() * p;', '      double v = pos(2.0, 1.0);', '      p.setValue(v);', '      eval0("par.follow", fp, -3.0, 3.0, 3);', '    }',
      '    Parameter r("r", 0.0);', '    r.connectFrom(&p);', '    d("par.conn", r.getValue());', '    r.setValue(7.0);', '    d("par.conn2", r.getValue());',
      '    d("par.lim", r.getLowerLimit());', '    d("par.lim2", r.getUpperLimit());', '    text("par.name", r.getName());', "  }"]
j += ['            {', '                final AbsFunction fp = new Sin().times(p);', '                final double v = pos(2.0, 1.0);', '                p.setValue(v);',
      '                eval0("par.follow", fp, -3.0, 3.0, 3);', '            }',
      '            final Parameter r = new Parameter("r", 0.0);', '            r.connectFrom(p);', '            d("par.conn", r.getValue());',
      '            r.setValue(7.0);', '            d("par.conn2", r.getValue());', '            d("par.lim", r.getLowerLimit());',
      '            d("par.lim2", r.getUpperLimit());', '            text("par.name", r.getName());', "        }"]
both("\n".join(c), "\n".join(j))

# ================================================================ functions of several variables
both("""  {
    Variable X(0, 2), Y(1, 2);
    GENFUNCTION F = X * X + Sin()(Y) * X;
    GENFUNCTION D = Sin() % Exp();
    GENFUNCTION T = (Square() % Cos()) % Exp();
    Derivative F0 = F.partial(0), F1 = F.partial(1), D0 = D.partial(0), D1 = D.partial(1), T2 = T.partial(2);
    i("md.dimF", F.dimensionality());
    i("md.dimT", T.dimensionality());
    for (int k = 0; k < 6; k++) {
      double a = sym(2.0);
      double b = sym(2.0);
      double c3 = sym(2.0);
      Argument A = arg2(a, b);
      Argument A3 = arg3(a, b, c3);
      d(K("md.F", k), F(A));
      d(K("md.F0", k), F0(A));
      d(K("md.F1", k), F1(A));
      d(K("md.D", k), D(A));
      d(K("md.D0", k), D0(A));
      d(K("md.D1", k), D1(A));
      d(K("md.T", k), T(A3));
      d(K("md.T2", k), T2(A3));
    }
  }
  {
    Gaussian g;
    Exponential e;
    eval1("conv", convolve(g, e, -1.0, 5.0), -2.0, 6.0, 4);
    Sigma s;
    s.accumulate(Sin());
    s.accumulate(Square());
    s.accumulate(Exp());
    eval1("sigma", s, -2.0, 2.0, 6);
    eval1("F1D", F1D(cubic), -2.0, 2.0, 4);
  }
  {
    double v[7];
    for (int k = 0; k < 7; k++) v[k] = sym(3.0);
    ArrayFunction f(v, v + 7);
    eval1("array", f, -1.0, 8.0, 10);
  }
  {
    CubicSplinePolynomial f;
    for (int k = 0; k < 8; k++) {
      double x = pos(10.0, 0.0);
      double y = sym(3.0);
      f.addPoint(x, y);
    }
    eval1("spline", f, -1.0, 11.0, 15);
    double lo, hi;
    f.getRange(lo, hi);
    d("spline.lo", lo);
    d("spline.hi", hi);
    CubicSplinePolynomial f0;
    d("spline0", f0(1.0));
    CubicSplinePolynomial f1;
    f1.addPoint(1.0, 2.0);
    d("spline1", f1(3.0));
    f1.getRange(lo, hi);
    d("spline1.lo", lo);
    CubicSplinePolynomial f2;
    f2.getRange(lo, hi);
    d("spline2.lo", lo);
    d("spline2.hi", hi);
  }
  {
    InterpolatingPolynomial f;
    for (int k = 0; k < 6; k++) {
      double x = pos(10.0, 0.0);
      double y = sym(3.0);
      f.addPoint(x, y);
    }
    eval1("interp", f, -0.5, 10.5, 12);
    double lo, hi;
    f.getRange(lo, hi);
    d("interp.lo", lo);
    d("interp.hi", hi);
  }""", """        {
            final Variable X = new Variable(0, 2);
            final Variable Y = new Variable(1, 2);
            final AbsFunction F = X.times(X).plus(new Sin().of(Y).times(X));
            final AbsFunction D = new Sin().directProduct(new Exp());
            final AbsFunction T = new Square().directProduct(new Cos()).directProduct(new Exp());
            final FunctionNoop F0 = F.partial(0);
            final FunctionNoop F1 = F.partial(1);
            final FunctionNoop D0 = D.partial(0);
            final FunctionNoop D1 = D.partial(1);
            final FunctionNoop T2 = T.partial(2);
            i("md.dimF", F.dimensionality());
            i("md.dimT", T.dimensionality());
            for (int k = 0; k < 6; k++) {
                final double a = sym(2.0);
                final double b = sym(2.0);
                final double c3 = sym(2.0);
                final Argument A = arg2(a, b);
                final Argument A3 = arg3(a, b, c3);
                d(K("md.F", k), F.call(A));
                d(K("md.F0", k), F0.call(A));
                d(K("md.F1", k), F1.call(A));
                d(K("md.D", k), D.call(A));
                d(K("md.D0", k), D0.call(A));
                d(K("md.D1", k), D1.call(A));
                d(K("md.T", k), T.call(A3));
                d(K("md.T2", k), T2.call(A3));
            }
        }
        {
            final Gaussian g = new Gaussian();
            final Exponential e = new Exponential();
            eval1("conv", AbsFunction.convolve(g, e, -1.0, 5.0), -2.0, 6.0, 4);
            final Sigma s = new Sigma();
            s.accumulate(new Sin());
            s.accumulate(new Square());
            s.accumulate(new Exp());
            eval1("sigma", s, -2.0, 2.0, 6);
            eval1("F1D", new F1D(x -> x * x * x - 2 * x), -2.0, 2.0, 4);
        }
        {
            final double[] v = new double[7];
            for (int k = 0; k < 7; k++) v[k] = sym(3.0);
            final ArrayFunction f = new ArrayFunction(v, 0, 7);
            eval1("array", f, -1.0, 8.0, 10);
        }
        {
            final CubicSplinePolynomial f = new CubicSplinePolynomial();
            for (int k = 0; k < 8; k++) {
                final double x = pos(10.0, 0.0);
                final double y = sym(3.0);
                f.addPoint(x, y);
            }
            eval1("spline", f, -1.0, 11.0, 15);
            double[] r = f.getRange();
            d("spline.lo", r[0]);
            d("spline.hi", r[1]);
            final CubicSplinePolynomial f0 = new CubicSplinePolynomial();
            d("spline0", f0.call(1.0));
            final CubicSplinePolynomial f1 = new CubicSplinePolynomial();
            f1.addPoint(1.0, 2.0);
            d("spline1", f1.call(3.0));
            r = f1.getRange();
            d("spline1.lo", r[0]);
            final CubicSplinePolynomial f2 = new CubicSplinePolynomial();
            r = f2.getRange();
            d("spline2.lo", r[0]);
            d("spline2.hi", r[1]);
        }
        {
            final InterpolatingPolynomial f = new InterpolatingPolynomial();
            for (int k = 0; k < 6; k++) {
                final double x = pos(10.0, 0.0);
                final double y = sym(3.0);
                f.addPoint(x, y);
            }
            eval1("interp", f, -0.5, 10.5, 12);
            final double[] r = f.getRange();
            d("interp.lo", r[0]);
            d("interp.hi", r[1]);
        }""")

# ================================================================ adaptors, cuts
both("""  {
    Gaussian g;
    { double v = sym(1.0); g.mean().setValue(v); }
    ParamToArgAdaptor<Gaussian> f(g, &Gaussian::sigma);
    { double v = pos(1.0, 0.5); f.scaleFactor().setValue(v); }
    i("p2a.dim", f.dimensionality());
    for (int k = 0; k < 5; k++) {
      double a = sym(3.0);
      double b = pos(2.0, 0.5);
      d(K("p2a", k), f(arg2(a, b)));
    }
    d("p2a.scalar", f(1.0));
    d("p2a.bad", f(arg3(1.0, 1.0, 1.0)));
    ParamToArgAdaptor<Gaussian> fc(f);
    d("p2a.copy", fc(arg2(0.5, 1.5)));
    DoubleParamToArgAdaptor<Gaussian> f2(g, &Gaussian::mean, &Gaussian::sigma);
    { double v = pos(1.0, 0.5); f2.scaleFactor0().setValue(v); }
    { double v = pos(1.0, 0.5); f2.scaleFactor1().setValue(v); }
    for (int k = 0; k < 5; k++) {
      double a = sym(3.0);
      double b = pos(2.0, 0.5);
      d(K("dp2a", k), f2(arg2(a, b)));
    }
    Variable X(0, 2), Y(1, 2);
    GENFUNCTION pexp = 0.5 + Y * Y;
    SymToArgAdaptor<Gaussian> f3(g, X, &Gaussian::sigma, &pexp);
    i("s2a.dim", f3.dimensionality());
    for (int k = 0; k < 5; k++) {
      double a = sym(3.0);
      double b = sym(1.5);
      d(K("s2a", k), f3(arg2(a, b)));
    }
  }
  {
    double t1 = sym(1.0);
    double t2 = sym(1.0);
    Above a(t1), b(t2);
    Cut<double>::AND c1 = a && b;
    Cut<double>::OR c2 = a || !b;
    Cut<double>::NOT c3 = !(a && b);
    Cut<double>::Predicate c4(c2);
    Cut<double>::OR c5 = (a || b) || !(a && b);
    for (int k = 0; k < 8; k++) {
      double x = sym(1.5);
      i(K("cut.and", k), c1(x));
      i(K("cut.or", k), c2(x));
      i(K("cut.not", k), c3(x));
      i(K("cut.pred", k), c4(x));
      i(K("cut.all", k), c5(x));
    }
  }""", """        {
            final Gaussian g = new Gaussian();
            { final double v = sym(1.0); g.mean().setValue(v); }
            final ParamToArgAdaptor<Gaussian> f = new ParamToArgAdaptor<>(g, Gaussian::sigma);
            { final double v = pos(1.0, 0.5); f.scaleFactor().setValue(v); }
            i("p2a.dim", f.dimensionality());
            for (int k = 0; k < 5; k++) {
                final double a = sym(3.0);
                final double b = pos(2.0, 0.5);
                d(K("p2a", k), f.call(arg2(a, b)));
            }
            d("p2a.scalar", f.call(1.0));
            d("p2a.bad", f.call(arg3(1.0, 1.0, 1.0)));
            final ParamToArgAdaptor<Gaussian> fc = new ParamToArgAdaptor<>(f);
            d("p2a.copy", fc.call(arg2(0.5, 1.5)));
            final DoubleParamToArgAdaptor<Gaussian> f2 = new DoubleParamToArgAdaptor<>(g, Gaussian::mean, Gaussian::sigma);
            { final double v = pos(1.0, 0.5); f2.scaleFactor0().setValue(v); }
            { final double v = pos(1.0, 0.5); f2.scaleFactor1().setValue(v); }
            for (int k = 0; k < 5; k++) {
                final double a = sym(3.0);
                final double b = pos(2.0, 0.5);
                d(K("dp2a", k), f2.call(arg2(a, b)));
            }
            final Variable X = new Variable(0, 2);
            final Variable Y = new Variable(1, 2);
            final AbsFunction pexp = AbsFunction.plus(0.5, Y.times(Y));
            final SymToArgAdaptor<Gaussian> f3 = new SymToArgAdaptor<>(g, X, Gaussian::sigma, pexp);
            i("s2a.dim", f3.dimensionality());
            for (int k = 0; k < 5; k++) {
                final double a = sym(3.0);
                final double b = sym(1.5);
                d(K("s2a", k), f3.call(arg2(a, b)));
            }
        }
        {
            final double t1 = sym(1.0);
            final double t2 = sym(1.0);
            final Above a = new Above(t1);
            final Above b = new Above(t2);
            final Cut<Double> c1 = a.and(b);
            final Cut<Double> c2 = a.or(b.not());
            final Cut<Double> c3 = a.and(b).not();
            final Cut<Double> c4 = new Cut.Predicate<>(c2);
            final Cut<Double> c5 = a.or(b).or(a.and(b).not());
            for (int k = 0; k < 8; k++) {
                final double x = sym(1.5);
                i(K("cut.and", k), c1.test(x));
                i(K("cut.or", k), c2.test(x));
                i(K("cut.not", k), c3.test(x));
                i(K("cut.pred", k), c4.test(x));
                i(K("cut.all", k), c5.test(x));
            }
        }""")

# ================================================================ integrals and functionals
both("""  {
    Gaussian g;
    { double v = sym(0.5); g.mean().setValue(v); }
    { double v = pos(1.0, 0.5); g.sigma().setValue(v); }
    double a = pos(1.0, -2.0);
    double b = pos(1.0, 1.0);
    DefiniteIntegral I1(a, b);
    integral("int.closed", I1, g);
    DefiniteIntegral I2(a, b, DefiniteIntegral::OPEN);
    integral("int.open", I2, g);
    DefiniteIntegral I3(0.0, 1.0, DefiniteIntegral::OPEN);
    integral("int.isqrt", I3, Power(-0.5));
    DefiniteIntegral I4(0.0, 3.0);
    I4.setEpsilon(1e-10);
    I4.setMinOrder(7);
    integral("int.exp", I4, Exp());
    DefiniteIntegral I5(0.0, 1.0);
    I5.setMaxIter(3);
    integral("int.fail", I5, Exp());
    DefiniteIntegral I6(I4);
    integral("int.copy", I6, Sin());
    DefiniteIntegral I7(-1.0, 2.0, DefiniteIntegral::OPEN);
    I7.setEpsilon(1e-9);
    integral("int.landau", I7, Landau());
  }
  {
    Gaussian g;
    { double v = sym(0.5); g.mean().setValue(v); }
    { double v = pos(1.0, 0.5); g.sigma().setValue(v); }
    ArgumentList list;
    for (int k = 0; k < 20; k++) {
      double x = sym(3.0);
      Argument A(1);
      A[0] = x;
      list.push_back(A);
    }
    LikelihoodFunctional L(list);
    d("like", L[g]);
    ArgumentList bad;
    { Argument A(1); A[0] = 2.0; bad.push_back(A); }
    LikelihoodFunctional Lb(bad);
    try { d("like.bad", Lb[Sin() - 2.0]); } catch (std::runtime_error& e) { text("like.bad", e.what()); }
    ArgumentList elist;
    for (int k = 0; k < 20; k++) {
      double x = sym(3.0);
      double pass = rnd();
      Argument A(2);
      A[0] = x;
      A[1] = pass;
      elist.push_back(A);
    }
    EfficiencyFunctional E(elist);
    d("eff", E[0.5 + 0.4 * Erf()]);
  }""", """        {
            final Gaussian g = new Gaussian();
            { final double v = sym(0.5); g.mean().setValue(v); }
            { final double v = pos(1.0, 0.5); g.sigma().setValue(v); }
            final double a = pos(1.0, -2.0);
            final double b = pos(1.0, 1.0);
            final DefiniteIntegral I1 = new DefiniteIntegral(a, b);
            integral("int.closed", I1, g);
            final DefiniteIntegral I2 = new DefiniteIntegral(a, b, DefiniteIntegral.Type.OPEN);
            integral("int.open", I2, g);
            final DefiniteIntegral I3 = new DefiniteIntegral(0.0, 1.0, DefiniteIntegral.Type.OPEN);
            integral("int.isqrt", I3, new Power(-0.5));
            final DefiniteIntegral I4 = new DefiniteIntegral(0.0, 3.0);
            I4.setEpsilon(1e-10);
            I4.setMinOrder(7);
            integral("int.exp", I4, new Exp());
            final DefiniteIntegral I5 = new DefiniteIntegral(0.0, 1.0);
            I5.setMaxIter(3);
            integral("int.fail", I5, new Exp());
            final DefiniteIntegral I6 = new DefiniteIntegral(I4);
            integral("int.copy", I6, new Sin());
            final DefiniteIntegral I7 = new DefiniteIntegral(-1.0, 2.0, DefiniteIntegral.Type.OPEN);
            I7.setEpsilon(1e-9);
            integral("int.landau", I7, new Landau());
        }
        {
            final Gaussian g = new Gaussian();
            { final double v = sym(0.5); g.mean().setValue(v); }
            { final double v = pos(1.0, 0.5); g.sigma().setValue(v); }
            final List<Argument> list = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                final double x = sym(3.0);
                final Argument A = new Argument(1);
                A.set(0, x);
                list.add(A);
            }
            final LikelihoodFunctional L = new LikelihoodFunctional(list);
            d("like", L.apply(g));
            final List<Argument> bad = new ArrayList<>();
            { final Argument A = new Argument(1); A.set(0, 2.0); bad.add(A); }
            final LikelihoodFunctional Lb = new LikelihoodFunctional(bad);
            try { d("like.bad", Lb.apply(new Sin().minus(2.0))); } catch (RuntimeException e) { text("like.bad", e.getMessage()); }
            final List<Argument> elist = new ArrayList<>();
            for (int k = 0; k < 20; k++) {
                final double x = sym(3.0);
                final double pass = rnd();
                final Argument A = new Argument(2);
                A.set(0, x);
                A.set(1, pass);
                elist.add(A);
            }
            final EfficiencyFunctional E = new EfficiencyFunctional(elist);
            d("eff", E.apply(AbsFunction.plus(0.5, AbsFunction.times(0.4, new Erf()))));
        }""")

# ================================================================ multivariate Gaussians, hydrogen
both("""  {
    BivariateGaussian f;
    { double v = sym(1.0); f.mean0().setValue(v); }
    { double v = sym(1.0); f.mean1().setValue(v); }
    { double v = pos(1.5, 0.5); f.sigma0().setValue(v); }
    { double v = pos(1.5, 0.5); f.sigma1().setValue(v); }
    { double v = sym(0.8); f.corr01().setValue(v); }
    Derivative f0 = f.partial(0), f1 = f.partial(1);
    i("biv.dim", f.dimensionality());
    for (int k = 0; k < 6; k++) {
      double a = sym(3.0);
      double b = sym(3.0);
      Argument A = arg2(a, b);
      d(K("biv", k), f(A));
      d(K("biv.0", k), f0(A));
      d(K("biv.1", k), f1(A));
    }
    d("biv.scalar", f(1.0));
  }
  {
    TrivariateGaussian f;
    { double v = sym(1.0); f.mean0().setValue(v); }
    { double v = sym(1.0); f.mean1().setValue(v); }
    { double v = sym(1.0); f.mean2().setValue(v); }
    { double v = pos(1.5, 0.5); f.sigma0().setValue(v); }
    { double v = pos(1.5, 0.5); f.sigma1().setValue(v); }
    { double v = pos(1.5, 0.5); f.sigma2().setValue(v); }
    { double v = sym(0.4); f.corr01().setValue(v); }
    { double v = sym(0.4); f.corr02().setValue(v); }
    { double v = sym(0.4); f.corr12().setValue(v); }
    Derivative f2 = f.partial(2);
    for (int k = 0; k < 6; k++) {
      double a = sym(3.0);
      double b = sym(3.0);
      double c3 = sym(3.0);
      Argument A = arg3(a, b, c3);
      d(K("triv", k), f(A));
      d(K("triv.2", k), f2(A));
    }
  }
  {
    const int nlm[6][3] = {{1, 0, 0}, {2, 1, 0}, {2, 1, 1}, {3, 2, 1}, {3, 1, 0}, {4, 3, 2}};
    for (int s = 0; s < 6; s++) {
      Psi2Hydrogen f(nlm[s][0], nlm[s][1], nlm[s][2]);
      std::string n = "psi2." + std::to_string(nlm[s][0]) + std::to_string(nlm[s][1]) + std::to_string(nlm[s][2]);
      for (int k = 0; k < 4; k++) {
        double r = pos(8.0, 0.1);
        double ct = sym(0.95);
        double phi = pos(6.28, 0.0);
        d(K(n, k), f(arg3(r, ct, phi)));
      }
    }
  }""", """        {
            final BivariateGaussian f = new BivariateGaussian();
            { final double v = sym(1.0); f.mean0().setValue(v); }
            { final double v = sym(1.0); f.mean1().setValue(v); }
            { final double v = pos(1.5, 0.5); f.sigma0().setValue(v); }
            { final double v = pos(1.5, 0.5); f.sigma1().setValue(v); }
            { final double v = sym(0.8); f.corr01().setValue(v); }
            final FunctionNoop f0 = f.partial(0);
            final FunctionNoop f1 = f.partial(1);
            i("biv.dim", f.dimensionality());
            for (int k = 0; k < 6; k++) {
                final double a = sym(3.0);
                final double b = sym(3.0);
                final Argument A = arg2(a, b);
                d(K("biv", k), f.call(A));
                d(K("biv.0", k), f0.call(A));
                d(K("biv.1", k), f1.call(A));
            }
            d("biv.scalar", f.call(1.0));
        }
        {
            final TrivariateGaussian f = new TrivariateGaussian();
            { final double v = sym(1.0); f.mean0().setValue(v); }
            { final double v = sym(1.0); f.mean1().setValue(v); }
            { final double v = sym(1.0); f.mean2().setValue(v); }
            { final double v = pos(1.5, 0.5); f.sigma0().setValue(v); }
            { final double v = pos(1.5, 0.5); f.sigma1().setValue(v); }
            { final double v = pos(1.5, 0.5); f.sigma2().setValue(v); }
            { final double v = sym(0.4); f.corr01().setValue(v); }
            { final double v = sym(0.4); f.corr02().setValue(v); }
            { final double v = sym(0.4); f.corr12().setValue(v); }
            final FunctionNoop f2 = f.partial(2);
            for (int k = 0; k < 6; k++) {
                final double a = sym(3.0);
                final double b = sym(3.0);
                final double c3 = sym(3.0);
                final Argument A = arg3(a, b, c3);
                d(K("triv", k), f.call(A));
                d(K("triv.2", k), f2.call(A));
            }
        }
        {
            final int[][] nlm = {{1, 0, 0}, {2, 1, 0}, {2, 1, 1}, {3, 2, 1}, {3, 1, 0}, {4, 3, 2}};
            for (int s = 0; s < 6; s++) {
                final Psi2Hydrogen f = new Psi2Hydrogen(nlm[s][0], nlm[s][1], nlm[s][2]);
                final String n = "psi2." + nlm[s][0] + nlm[s][1] + nlm[s][2];
                for (int k = 0; k < 4; k++) {
                    final double r = pos(8.0, 0.1);
                    final double ct = sym(0.95);
                    final double phi = pos(6.28, 0.0);
                    d(K(n, k), f.call(arg3(r, ct, phi)));
                }
            }
        }""")

# ================================================================ GSL special functions
simple("Airy.Ai", "Airy(Airy::Ai)", "new Airy(Airy.Type.Ai)", -5, 3, 6)
simple("Airy.Bi", "Airy(Airy::Bi)", "new Airy(Airy.Type.Bi)", -5, 3, 6)
for n in range(4):
    simple("BesselJ%d" % n, "IntegralOrder::Bessel(IntegralOrder::Bessel::J, %d)" % n, "new Bessel.IntegralOrder(Bessel.Type.J, %d)" % n, 0.5, 10, 4)
    simple("BesselY%d" % n, "IntegralOrder::Bessel(IntegralOrder::Bessel::Y, %d)" % n, "new Bessel.IntegralOrder(Bessel.Type.Y, %d)" % n, 0.5, 10, 4)
for t in ["J", "Y"]:
    fn("BesselNu" + t, "FractionalOrder::Bessel f(FractionalOrder::Bessel::%s);" % t,
       "final Bessel.FractionalOrder f = new Bessel.FractionalOrder(Bessel.Type.%s);" % t, [("order()", "pos(3.0, 0.2)")], 0.5, 10, 6)
fn("EllF", "EllipticIntegral::FirstKind f;", "final EllipticIntegral.FirstKind f = new EllipticIntegral.FirstKind();",
   [("k()", "pos(0.8, 0.1)")], 0, 1.5, 6)
fn("EllE", "EllipticIntegral::SecondKind f;", "final EllipticIntegral.SecondKind f = new EllipticIntegral.SecondKind();",
   [("k()", "pos(0.8, 0.1)")], 0, 1.5, 6)
fn("EllP", "EllipticIntegral::ThirdKind f;", "final EllipticIntegral.ThirdKind f = new EllipticIntegral.ThirdKind();",
   [("k()", "pos(0.8, 0.1)"), ("n()", "pos(0.8, 0.0)")], 0, 1.5, 6)
for l in range(6):
    simple("Legendre%d" % l, "Legendre(%d)" % l, "new Legendre(%d)" % l, -0.9, 0.9, 4)
for l, m in [(1, 0), (2, 1), (3, 2), (4, 0), (5, 3)]:
    simple("AssocLegendre%d.%d" % (l, m), "AssociatedLegendre(%d, %d)" % (l, m), "new AssociatedLegendre(%d, %d)" % (l, m), -0.9, 0.9, 4)
for l in range(4):
    simple("SphBessel%d" % l, "SphericalBessel(%d)" % l, "new SphericalBessel(%d)" % l, 0.5, 10, 4)
    simple("SphNeumann%d" % l, "SphericalNeumann(%d)" % l, "new SphericalNeumann(%d)" % l, 0.5, 10, 4)
both("""  {
    try { d("gsl.err", Airy(Airy::Ai)(1e300)); } catch (int s) { text("gsl.err", "SIGFPE"); }
    try { d("gsl.err2", AssociatedLegendre(2, 3)(0.5)); } catch (int s) { text("gsl.err2", "SIGFPE"); }
  }""", """        {
            try { d("gsl.err", new Airy(Airy.Type.Ai).call(1e300)); } catch (ArithmeticException e) { text("gsl.err", "SIGFPE"); }
            try { d("gsl.err2", new AssociatedLegendre(2, 3).call(0.5)); } catch (ArithmeticException e) { text("gsl.err2", "SIGFPE"); }
        }""")

# ================================================================ expansions and fits
both("""  {
    LegendreCoefficientSet cs(4);
    for (int l = 0; l <= 4; l++) {
      double re = sym(1.0);
      double im = sym(1.0);
      cs(l) = std::complex<double>(re, im);
    }
    std::ostringstream o;
    o << cs;
    lines("lcs", o.str());
    i("lcs.lmax", cs.getLMax());
    for (int t = 0; t < 4; t++) {
      LegendreExpansion f((LegendreExpansion::Type) t, cs);
      eval1(K("lexp", t), f, -0.9, 0.9, 5);
    }
    LegendreCoefficientSet cs2(cs);
    cs2(1) = std::complex<double>(0.25, 0.5);
    c("lcs.copy", cs2(1));
    c("lcs.orig", cs(1));
  }
  {
    LegendreFit f(3);
    for (int k = 0; k < 3; k++) {
      double v = pos(0.9, 0.05);
      f.getFraction(k)->setValue(v);
      double p = pos(6.0, 0.1);
      f.getPhase(k)->setValue(p);
    }
    i("lfit.order", f.order());
    eval1("lfit", f, -0.9, 0.9, 6);
    const LegendreCoefficientSet& A = f.coefficientsA();
    for (unsigned int l = 0; l <= A.getLMax(); l++) c(K("lfit.A", l), A(l));
    const LegendreCoefficientSet& A2 = f.coefficientsASq();
    for (unsigned int l = 0; l <= A2.getLMax(); l++) c(K("lfit.A2", l), A2(l));
    LegendreFit fc(f);
    d("lfit.copy", fc(0.3));
  }
  {
    SphericalHarmonicCoefficientSet cs(3);
    for (int l = 0; l <= 3; l++) {
      for (int m = -l; m <= l; m++) {
        double re = sym(1.0);
        double im = sym(1.0);
        cs(l, m) = std::complex<double>(re, im);
      }
    }
    std::ostringstream o;
    o << cs;
    lines("shcs", o.str());
    for (int t = 0; t < 4; t++) {
      SphericalHarmonicExpansion f((SphericalHarmonicExpansion::Type) t, cs);
      for (int k = 0; k < 5; k++) {
        double x = sym(0.95);
        double phi = pos(6.28, 0.0);
        d(K(K("shexp", t), k), f(arg2(x, phi)));
      }
    }
    SphericalHarmonicCoefficientSet sq = squareExpansionCoefficients(cs);
    for (int l = 0; l <= (int) sq.getLMax(); l++) {
      for (int m = -l; m <= l; m++) c(K(K("shcs.sq", l), m), sq(l, m));
    }
    SphericalHarmonicCoefficientSet cs2(cs);
    cs2 *= std::complex<double>(0.5, -1.5);
    c("shcs.dot", dot(cs, cs2));
    cs2 += cs;
    c("shcs.plus", cs2(2, -1));
    cs2 -= cs;
    cs2 -= cs;
    c("shcs.minus", cs2(3, 2));
    SphericalHarmonicCoefficientSet small(1);
    try { c("shcs.dot.err", dot(cs, small)); } catch (std::runtime_error& e) { text("shcs.dot.err", e.what()); }
  }
  {
    SphericalHarmonicFit f(2);
    for (unsigned int l = 1; l <= 2; l++) {
      { double v = pos(0.9, 0.05); f.getFractionLOrHigher(l)->setValue(v); }
      { double v = pos(6.0, -3.0); f.getPhaseLM0(l)->setValue(v); }
      for (unsigned int m = 1; m <= l; m++) {
        { double v = pos(0.9, 0.05); f.getFractionAbsMOrHigher(l, m)->setValue(v); }
        { double v = pos(0.9, 0.05); f.getFractionMPositive(l, m)->setValue(v); }
        { double v = pos(6.0, -3.0); f.getPhaseMPlus(l, m)->setValue(v); }
        { double v = pos(6.0, -3.0); f.getPhaseMMinus(l, m)->setValue(v); }
      }
    }
    i("shfit.n", f.numComponents());
    i("shfit.lmax", f.lMax());
    i("shfit.dim", f.dimensionality());
    for (int k = 0; k < 6; k++) {
      double x = sym(0.95);
      double phi = pos(6.28, 0.0);
      d(K("shfit", k), f(arg2(x, phi)));
    }
    const SphericalHarmonicCoefficientSet& A = f.coefficientsA();
    for (int l = 0; l <= (int) A.getLMax(); l++) {
      for (int m = -l; m <= l; m++) c(K(K("shfit.A", l), m), A(l, m));
    }
    const SphericalHarmonicCoefficientSet& A2 = f.coefficientsASq();
    for (int l = 0; l <= (int) A2.getLMax(); l++) {
      for (int m = -l; m <= l; m++) c(K(K("shfit.A2", l), m), A2(l, m));
    }
    SphericalHarmonicFit fc(f);
    d("shfit.copy", fc(arg2(0.3, 1.2)));
    f.getFractionLOrHigher(1)->setValue(1.5);
    d("shfit.neg", f(arg2(0.3, 1.2)));
  }
  {
    ClebschGordanCoefficientSet cg;
    for (unsigned int l1 = 0; l1 <= 2; l1++) {
      for (unsigned int l2 = 0; l2 <= 2; l2++) {
        for (int L = std::abs((int) l1 - (int) l2); L <= (int) (l1 + l2); L++) {
          for (int m1 = -(int) l1; m1 <= (int) l1; m1++) {
            for (int m2 = -(int) l2; m2 <= (int) l2; m2++) {
              int M = m1 + m2;
              if (std::abs(M) > L) continue;
              d("cg." + std::to_string(l1) + "." + std::to_string(l2) + "." + std::to_string(L) + "." + std::to_string(m1) + "." + std::to_string(m2), cg(l1, l2, m1, m2, L, M));
            }
          }
        }
      }
    }
    d("cg.zero", cg(1, 1, 1, 0, 1, 0));
  }
  {
    FourierFit f(3);
    for (int k = 0; k < 3; k++) {
      double v = pos(0.9, 0.05);
      f.getFraction(k)->setValue(v);
      double p = pos(6.0, 0.1);
      f.getPhase(k)->setValue(p);
    }
    i("ffit.order", f.order());
    eval1("ffit", f, 0.0, 6.28, 6);
  }""", """        {
            final LegendreCoefficientSet cs = new LegendreCoefficientSet(4);
            for (int l = 0; l <= 4; l++) {
                final double re = sym(1.0);
                final double im = sym(1.0);
                cs.set(l, new Complex(re, im));
            }
            lines("lcs", cs.toString());
            i("lcs.lmax", cs.getLMax());
            for (int t = 0; t < 4; t++) {
                final LegendreExpansion f = new LegendreExpansion(LegendreExpansion.Type.values()[t], cs);
                eval1(K("lexp", t), f, -0.9, 0.9, 5);
            }
            final LegendreCoefficientSet cs2 = new LegendreCoefficientSet(cs);
            cs2.set(1, new Complex(0.25, 0.5));
            c("lcs.copy", cs2.get(1));
            c("lcs.orig", cs.get(1));
        }
        {
            final LegendreFit f = new LegendreFit(3);
            for (int k = 0; k < 3; k++) {
                final double v = pos(0.9, 0.05);
                f.getFraction(k).setValue(v);
                final double p = pos(6.0, 0.1);
                f.getPhase(k).setValue(p);
            }
            i("lfit.order", f.order());
            eval1("lfit", f, -0.9, 0.9, 6);
            final LegendreCoefficientSet A = f.coefficientsA();
            for (int l = 0; l <= A.getLMax(); l++) c(K("lfit.A", l), A.get(l));
            final LegendreCoefficientSet A2 = f.coefficientsASq();
            for (int l = 0; l <= A2.getLMax(); l++) c(K("lfit.A2", l), A2.get(l));
            final LegendreFit fc = new LegendreFit(f);
            d("lfit.copy", fc.call(0.3));
        }
        {
            final SphericalHarmonicCoefficientSet cs = new SphericalHarmonicCoefficientSet(3);
            for (int l = 0; l <= 3; l++) {
                for (int m = -l; m <= l; m++) {
                    final double re = sym(1.0);
                    final double im = sym(1.0);
                    cs.set(l, m, new Complex(re, im));
                }
            }
            lines("shcs", cs.toString());
            for (int t = 0; t < 4; t++) {
                final SphericalHarmonicExpansion f = new SphericalHarmonicExpansion(SphericalHarmonicExpansion.Type.values()[t], cs);
                for (int k = 0; k < 5; k++) {
                    final double x = sym(0.95);
                    final double phi = pos(6.28, 0.0);
                    d(K(K("shexp", t), k), f.call(arg2(x, phi)));
                }
            }
            final SphericalHarmonicCoefficientSet sq = SphericalHarmonicCoefficientSet.squareExpansionCoefficients(cs);
            for (int l = 0; l <= sq.getLMax(); l++) {
                for (int m = -l; m <= l; m++) c(K(K("shcs.sq", l), m), sq.get(l, m));
            }
            final SphericalHarmonicCoefficientSet cs2 = new SphericalHarmonicCoefficientSet(cs);
            cs2.timesAssign(new Complex(0.5, -1.5));
            c("shcs.dot", SphericalHarmonicCoefficientSet.dot(cs, cs2));
            cs2.plusAssign(cs);
            c("shcs.plus", cs2.get(2, -1));
            cs2.minusAssign(cs);
            cs2.minusAssign(cs);
            c("shcs.minus", cs2.get(3, 2));
            final SphericalHarmonicCoefficientSet small = new SphericalHarmonicCoefficientSet(1);
            try { c("shcs.dot.err", SphericalHarmonicCoefficientSet.dot(cs, small)); } catch (RuntimeException e) { text("shcs.dot.err", e.getMessage()); }
        }
        {
            final SphericalHarmonicFit f = new SphericalHarmonicFit(2);
            for (int l = 1; l <= 2; l++) {
                { final double v = pos(0.9, 0.05); f.getFractionLOrHigher(l).setValue(v); }
                { final double v = pos(6.0, -3.0); f.getPhaseLM0(l).setValue(v); }
                for (int m = 1; m <= l; m++) {
                    { final double v = pos(0.9, 0.05); f.getFractionAbsMOrHigher(l, m).setValue(v); }
                    { final double v = pos(0.9, 0.05); f.getFractionMPositive(l, m).setValue(v); }
                    { final double v = pos(6.0, -3.0); f.getPhaseMPlus(l, m).setValue(v); }
                    { final double v = pos(6.0, -3.0); f.getPhaseMMinus(l, m).setValue(v); }
                }
            }
            i("shfit.n", f.numComponents());
            i("shfit.lmax", f.lMax());
            i("shfit.dim", f.dimensionality());
            for (int k = 0; k < 6; k++) {
                final double x = sym(0.95);
                final double phi = pos(6.28, 0.0);
                d(K("shfit", k), f.call(arg2(x, phi)));
            }
            final SphericalHarmonicCoefficientSet A = f.coefficientsA();
            for (int l = 0; l <= A.getLMax(); l++) {
                for (int m = -l; m <= l; m++) c(K(K("shfit.A", l), m), A.get(l, m));
            }
            final SphericalHarmonicCoefficientSet A2 = f.coefficientsASq();
            for (int l = 0; l <= A2.getLMax(); l++) {
                for (int m = -l; m <= l; m++) c(K(K("shfit.A2", l), m), A2.get(l, m));
            }
            final SphericalHarmonicFit fc = new SphericalHarmonicFit(f);
            d("shfit.copy", fc.call(arg2(0.3, 1.2)));
            f.getFractionLOrHigher(1).setValue(1.5);
            d("shfit.neg", f.call(arg2(0.3, 1.2)));
        }
        {
            final ClebschGordanCoefficientSet cg = new ClebschGordanCoefficientSet();
            for (int l1 = 0; l1 <= 2; l1++) {
                for (int l2 = 0; l2 <= 2; l2++) {
                    for (int L = Math.abs(l1 - l2); L <= l1 + l2; L++) {
                        for (int m1 = -l1; m1 <= l1; m1++) {
                            for (int m2 = -l2; m2 <= l2; m2++) {
                                final int M = m1 + m2;
                                if (Math.abs(M) > L) continue;
                                d("cg." + l1 + "." + l2 + "." + L + "." + m1 + "." + m2, cg.get(l1, l2, m1, m2, L, M));
                            }
                        }
                    }
                }
            }
            d("cg.zero", cg.get(1, 1, 1, 0, 1, 0));
        }
        {
            final FourierFit f = new FourierFit(3);
            for (int k = 0; k < 3; k++) {
                final double v = pos(0.9, 0.05);
                f.getFraction(k).setValue(v);
                final double p = pos(6.0, 0.1);
                f.getPhase(k).setValue(p);
            }
            i("ffit.order", f.order());
            eval1("ffit", f, 0.0, 6.28, 6);
        }""")

# ================================================================ Runge-Kutta
TABS = [("Euler", "EulerTableau"), ("Midpoint", "MidpointTableau"), ("Trapezoid", "TrapezoidTableau"), ("RK31", "RK31Tableau"),
        ("RK32", "RK32Tableau"), ("ClassicalRK", "ClassicalRungeKuttaTableau"), ("ThreeEighths", "ThreeEighthsRuleTableau")]
XTABS = [("HeunEuler", "HeunEulerXtTableau"), ("BogackiShampine", "BogackiShampineXtTableau"),
         ("Fehlberg", "FehlbergRK45F2XtTableau"), ("CashKarp", "CashKarpXtTableau")]
c, j = ["  {"], ["        {"]
for n, t in TABS:
    c.append('    { std::ostringstream o; o << %s(); lines("tab.%s", o.str()); i("tab.%s.n", %s().nSteps()); }' % (t, n, n, t))
    j.append('            lines("tab.%s", new ButcherTableau.%s().toString());' % (n, t))
    j.append('            i("tab.%s.n", new ButcherTableau.%s().nSteps());' % (n, t))
for n, t in XTABS:
    c.append('    { std::ostringstream o; o << %s(); lines("xtab.%s", o.str()); i("xtab.%s.n", %s().nSteps()); }' % (t, n, n, t))
    j.append('            lines("xtab.%s", new ExtendedButcherTableau.%s().toString());' % (n, t))
    j.append('            i("xtab.%s.n", new ExtendedButcherTableau.%s().nSteps());' % (n, t))
c.append('    rkDecay("rk.default", nullptr);')
j.append('            rkDecay("rk.default", null);')
steps = {"Euler": "0.01", "Midpoint": "0.05", "Trapezoid": "0.05", "RK31": "0.05", "RK32": "0.05", "ClassicalRK": "0.1", "ThreeEighths": "0.1"}
for n, t in TABS:
    c.append('    { %s tab; SimpleRKStepper s(tab, %s); rkDecay("rk.simple.%s", &s); }' % (t, steps[n], n))
    j.append('            rkDecay("rk.simple.%s", new SimpleRKStepper(new ButcherTableau.%s(), %s));' % (n, t, steps[n]))
for n, t in XTABS:
    c.append('    { %s tab; EmbeddedRKStepper e(tab); AdaptiveRKStepper s(&e); rkDecay("rk.embedded.%s", &s); }' % (t, n))
    j.append('            rkDecay("rk.embedded.%s", new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.%s())));' % (n, t))
c.append('    { BogackiShampineXtTableau tab; EmbeddedRKStepper e(tab); AdaptiveRKStepper s(&e); s.tolerance() = 1e-9; rkDecay("rk.tight", &s); }')
j.append('            { final AdaptiveRKStepper s = new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.BogackiShampineXtTableau())); s.setTolerance(1e-9); rkDecay("rk.tight", s); }')
c.append('    { CashKarpXtTableau tab; EmbeddedRKStepper e(tab); AdaptiveRKStepper s(&e); s.startingStepsize() = 0.2; s.safetyFactor() = 0.8; s.rmax() = 3.0; s.rmin() = 0.1; rkDecay("rk.tuned", &s); }')
j.append('            { final AdaptiveRKStepper s = new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.CashKarpXtTableau())); s.setStartingStepsize(0.2); s.setSafetyFactor(0.8); s.setRmax(3.0); s.setRmin(0.1); rkDecay("rk.tuned", s); }')
for n, t in [("ClassicalRK", "ClassicalRungeKuttaTableau"), ("Midpoint", "MidpointTableau"), ("ThreeEighths", "ThreeEighthsRuleTableau")]:
    c.append('    { %s tab; StepDoublingRKStepper e(tab); AdaptiveRKStepper s(&e); rkDecay("rk.doubling.%s", &s); }' % (t, n))
    j.append('            rkDecay("rk.doubling.%s", new AdaptiveRKStepper(new StepDoublingRKStepper(new ButcherTableau.%s())));' % (n, t))
c.append("  }")
j.append("        }")
both("\n".join(c), "\n".join(j))
both("""  {
    RKIntegrator rk;
    Variable x(0, 2), v(1, 2);
    Parameter* w = rk.createControlParameter("w", 1.5, 0.0, 10.0);
    GENFUNCTION dx = v;
    GENFUNCTION dv = -((*w) * (*w)) * x;
    rk.addDiffEquation(&dx, "x", 1.0);
    Parameter* v0 = rk.addDiffEquation(&dv, "v", 0.0);
    const AbsFunction& X = *rk.getFunction(0);
    const AbsFunction& V = *rk.getFunction(1);
    for (int k = 0; k < 8; k++) {
      double t = pos(5.0, 0.0);
      d(K("osc.x", k), X(t));
      d(K("osc.v", k), V(t));
    }
    { double vv = sym(1.0); v0->setValue(vv); }
    for (int k = 0; k < 4; k++) {
      double t = pos(5.0, 0.0);
      d(K("osc.x2", k), X(t));
    }
    { double ww = pos(1.0, 0.5); w->setValue(ww); }
    for (int k = 0; k < 4; k++) {
      double t = pos(5.0, 0.0);
      d(K("osc.v3", k), V(t));
    }
  }
  {
    RKIntegrator rk;
    Variable y(0, 2);
    rk.addDiffEquation(&y, "y", 1.0);
    try { d("rk.lock", (*rk.getFunction(0))(1.0)); } catch (std::runtime_error& e) { text("rk.lock", e.what()); }
  }
  {
    EulerTableau tab;
    SimpleRKStepper s(tab, -0.1);
    RKIntegrator rk(&s);
    Variable y;
    GENFUNCTION ny = -y;
    rk.addDiffEquation(&ny, "y", 1.0);
    try { d("rk.negstep", (*rk.getFunction(0))(1.0)); } catch (std::runtime_error& e) { text("rk.negstep", e.what()); }
  }
  {
    Classical::PhaseSpace ps(1);
    const Classical::PhaseSpace::Component& X = ps.coordinates();
    const Classical::PhaseSpace::Component& P = ps.momenta();
    Variable q = X[0], p = P[0];
    GENFUNCTION H = p * p / 2.0 + q * q / 2.0;
    { double v = sym(1.0); ps.start(q, v); }
    { double v = sym(1.0); ps.start(p, v); }
    d("cl.q0", ps.startValue(q));
    d("cl.p0", ps.startValue(p));
    i("cl.dim", ps.dim());
    i("cl.qi", q.index());
    i("cl.pi", p.index());
    Classical::RungeKuttaSolver solver(H, ps);
    GENFUNCTION Q = solver.equationOf(q);
    GENFUNCTION Pt = solver.equationOf(p);
    GENFUNCTION E = solver.energy();
    for (int k = 0; k < 6; k++) {
      double t = pos(4.0, 0.0);
      d(K("cl.q", k), Q(t));
      d(K("cl.p", k), Pt(t));
      d(K("cl.E", k), E(t));
    }
    { double v = sym(2.0); solver.takeQ0(0)->setValue(v); }
    { double v = sym(2.0); solver.takeP0(0)->setValue(v); }
    for (int k = 0; k < 3; k++) {
      double t = pos(4.0, 0.0);
      d(K("cl.q'", k), Q(t));
    }
    d("cl.H", solver.hamiltonian()(arg2(0.3, 0.4)));
    Parameter* cp = solver.createControlParameter("c", 1.0, 0.0, 2.0);
    d("cl.cp", cp->getValue());
  }
  {
    Classical::PhaseSpace ps(2);
    const Classical::PhaseSpace::Component& X = ps.coordinates();
    const Classical::PhaseSpace::Component& P = ps.momenta();
    Variable x = X[0], y = X[1], px = P[0], py = P[1];
    GENFUNCTION H = (px * px + py * py) / 2.0 + (x * x + y * y) / 2.0 + 0.1 * x * x * y;
    { double v = sym(0.8); ps.start(x, v); }
    { double v = sym(0.8); ps.start(y, v); }
    { double v = sym(0.8); ps.start(px, v); }
    { double v = sym(0.8); ps.start(py, v); }
    Classical::RungeKuttaSolver solver(H, ps);
    GENFUNCTION E = solver.energy();
    for (int k = 0; k < 5; k++) {
      double t = pos(3.0, 0.0);
      d(K("cl2.x", k), solver.equationOf(x)(t));
      d(K("cl2.y", k), solver.equationOf(y)(t));
      d(K("cl2.px", k), solver.equationOf(px)(t));
      d(K("cl2.py", k), solver.equationOf(py)(t));
      d(K("cl2.E", k), E(t));
    }
  }""", """        {
            final RKIntegrator rk = new RKIntegrator();
            final Variable x = new Variable(0, 2);
            final Variable v = new Variable(1, 2);
            final Parameter w = rk.createControlParameter("w", 1.5, 0.0, 10.0);
            final AbsFunction dx = v;
            final AbsFunction dv = w.times(w).negate().times(x);
            rk.addDiffEquation(dx, "x", 1.0);
            final Parameter v0 = rk.addDiffEquation(dv, "v", 0.0);
            final AbsFunction X = rk.getFunction(0);
            final AbsFunction V = rk.getFunction(1);
            for (int k = 0; k < 8; k++) {
                final double t = pos(5.0, 0.0);
                d(K("osc.x", k), X.call(t));
                d(K("osc.v", k), V.call(t));
            }
            { final double vv = sym(1.0); v0.setValue(vv); }
            for (int k = 0; k < 4; k++) {
                final double t = pos(5.0, 0.0);
                d(K("osc.x2", k), X.call(t));
            }
            { final double ww = pos(1.0, 0.5); w.setValue(ww); }
            for (int k = 0; k < 4; k++) {
                final double t = pos(5.0, 0.0);
                d(K("osc.v3", k), V.call(t));
            }
        }
        {
            final RKIntegrator rk = new RKIntegrator();
            final Variable y = new Variable(0, 2);
            rk.addDiffEquation(y, "y", 1.0);
            try { d("rk.lock", rk.getFunction(0).call(1.0)); } catch (RuntimeException e) { text("rk.lock", e.getMessage()); }
        }
        {
            final SimpleRKStepper s = new SimpleRKStepper(new ButcherTableau.EulerTableau(), -0.1);
            final RKIntegrator rk = new RKIntegrator(s);
            final Variable y = new Variable();
            final AbsFunction ny = y.negate();
            rk.addDiffEquation(ny, "y", 1.0);
            try { d("rk.negstep", rk.getFunction(0).call(1.0)); } catch (RuntimeException e) { text("rk.negstep", e.getMessage()); }
        }
        {
            final PhaseSpace ps = new PhaseSpace(1);
            final PhaseSpace.Component X = ps.coordinates();
            final PhaseSpace.Component P = ps.momenta();
            final Variable q = X.get(0);
            final Variable p = P.get(0);
            final AbsFunction H = p.times(p).div(2.0).plus(q.times(q).div(2.0));
            { final double v = sym(1.0); ps.start(q, v); }
            { final double v = sym(1.0); ps.start(p, v); }
            d("cl.q0", ps.startValue(q));
            d("cl.p0", ps.startValue(p));
            i("cl.dim", ps.dim());
            i("cl.qi", q.index());
            i("cl.pi", p.index());
            final RungeKuttaSolver solver = new RungeKuttaSolver(H, ps);
            final AbsFunction Q = solver.equationOf(q);
            final AbsFunction Pt = solver.equationOf(p);
            final AbsFunction E = solver.energy();
            for (int k = 0; k < 6; k++) {
                final double t = pos(4.0, 0.0);
                d(K("cl.q", k), Q.call(t));
                d(K("cl.p", k), Pt.call(t));
                d(K("cl.E", k), E.call(t));
            }
            { final double v = sym(2.0); solver.takeQ0(0).setValue(v); }
            { final double v = sym(2.0); solver.takeP0(0).setValue(v); }
            for (int k = 0; k < 3; k++) {
                final double t = pos(4.0, 0.0);
                d(K("cl.q'", k), Q.call(t));
            }
            d("cl.H", solver.hamiltonian().call(arg2(0.3, 0.4)));
            final Parameter cp = solver.createControlParameter("c", 1.0, 0.0, 2.0);
            d("cl.cp", cp.getValue());
        }
        {
            final PhaseSpace ps = new PhaseSpace(2);
            final PhaseSpace.Component X = ps.coordinates();
            final PhaseSpace.Component P = ps.momenta();
            final Variable x = X.get(0);
            final Variable y = X.get(1);
            final Variable px = P.get(0);
            final Variable py = P.get(1);
            final AbsFunction H = px.times(px).plus(py.times(py)).div(2.0).plus(x.times(x).plus(y.times(y)).div(2.0))
                .plus(AbsFunction.times(0.1, x).times(x).times(y));
            { final double v = sym(0.8); ps.start(x, v); }
            { final double v = sym(0.8); ps.start(y, v); }
            { final double v = sym(0.8); ps.start(px, v); }
            { final double v = sym(0.8); ps.start(py, v); }
            final RungeKuttaSolver solver = new RungeKuttaSolver(H, ps);
            final AbsFunction E = solver.energy();
            for (int k = 0; k < 5; k++) {
                final double t = pos(3.0, 0.0);
                d(K("cl2.x", k), solver.equationOf(x).call(t));
                d(K("cl2.y", k), solver.equationOf(y).call(t));
                d(K("cl2.px", k), solver.equationOf(px).call(t));
                d(K("cl2.py", k), solver.equationOf(py).call(t));
                d(K("cl2.E", k), E.call(t));
            }
        }""")

HEADERS = """AbsFunction Variable FixedConstant FloatingConstant Parameter Sin Cos Tan ASin ACos ATan Exp Ln Sqrt Square Abs Theta
LogGamma Gamma IncompleteGamma Erf Power Mod Rectangular PeriodicRectangular Exponential ReverseExponential Gaussian Landau
LogisticFunction NonrelativisticBW RelativisticBW VoigtProfile PuncturedSmearedExp PtRelFcn CumulativeChiSquare
BetaDistribution GammaDistribution HermitePolynomial AssociatedLaguerre KroneckerDelta Sigma X F1D ArrayFunction
AnalyticConvolution BivariateGaussian TrivariateGaussian Psi2Hydrogen CubicSplinePolynomial InterpolatingPolynomial
DefiniteIntegral LikelihoodFunctional EfficiencyFunctional ArgumentList ParamToArgAdaptor DoubleParamToArgAdaptor
SymToArgAdaptor CutBase Airy Bessel EllipticIntegral Legendre AssociatedLegendre SphericalBessel SphericalNeumann
LegendreCoefficientSet LegendreExpansion LegendreFit SphericalHarmonicCoefficientSet SphericalHarmonicExpansion
SphericalHarmonicFit ClebschGordanCoefficientSet FourierFit ButcherTableau ExtendedButcherTableau RKIntegrator
SimpleRKStepper AdaptiveRKStepper EmbeddedRKStepper StepDoublingRKStepper PhaseSpace ClassicalSolver
RungeKuttaClassicalSolver""".split()

CPP = """// CLHEP's GenericFunctions: every function class at random points with its derivative (analytic where the class
// has one, numerical otherwise), the algebra of functions and parameters, composition, direct products and
// convolution, the adaptors and cuts, the integrals and functionals, splines and interpolation, the GSL special
// functions, the expansions and fits, the Butcher tableaus, the Runge-Kutta integrators with every stepper and the
// classical solver. Inputs from xorshift64, one draw per statement. Its twin: validation.GenfunReference.
// Generated by gen_genfun_ref.py.
#include <cstdlib>
// CLHEP calls abs(l1-l2) on unsigned values, ambiguous with GCC 16: the int reading it means
inline int abs(unsigned int x) { return std::abs((int) x); }
#ifdef _WIN32
#include <cmath>
// the expansions call the BSD finite(), which MinGW does not have
inline int finite(double x) { return std::isfinite(x); }
#endif
// (the adaptors' .icc still define the _clone and operator() FUNCTION_OBJECT_DEF no longer declares:
// GCC 16 needs -Wno-template-body for those never-instantiated definitions)
%s
#include <gsl/gsl_errno.h>
#include <complex>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sstream>
#include <stdexcept>
#include <string>

using namespace Genfun;

static uint64_t state = 88172645463325252ULL;
static double rnd() {
  state ^= state << 13; state ^= state >> 7; state ^= state << 17;
  return (double)(state >> 11) * (1.0 / 9007199254740992.0);
}
static double sym(double s) { return (2 * rnd() - 1) * s; }
static double pos(double s, double o) { return rnd() * s + o; }
static uint64_t bits(double x) { uint64_t b; std::memcpy(&b, &x, 8); return b; }
static void d(const std::string& n, double x) { std::printf("%%s %%016llx\\n", n.c_str(), (unsigned long long)bits(x)); }
static void i(const std::string& n, long long v) { std::printf("%%s %%lld\\n", n.c_str(), v); }
static void text(const std::string& n, const std::string& t) { std::printf("%%s [%%s]\\n", n.c_str(), t.c_str()); }
static std::string K(const std::string& n, int k) { return n + "." + std::to_string(k); }
static void c(const std::string& n, const std::complex<double>& z) { d(n + ".re", z.real()); d(n + ".im", z.imag()); }
static void lines(const std::string& n, const std::string& t) {
  std::istringstream in(t);
  std::string l;
  int k = 0;
  while (std::getline(in, l)) text(K(n, k++), l);
}
static void eval1(const std::string& n, const AbsFunction& f, double lo, double hi, int count) {
  Derivative df = f.prime();
  for (int k = 0; k < count; k++) {
    double x = pos(hi - lo, lo);
    d(K(n, k), f(x));
    d(K(n, k) + "'", df(x));
  }
}
static void eval0(const std::string& n, const AbsFunction& f, double lo, double hi, int count) {
  for (int k = 0; k < count; k++) {
    double x = pos(hi - lo, lo);
    d(K(n, k), f(x));
  }
}
static Argument arg2(double a, double b) { Argument r(2); r[0] = a; r[1] = b; return r; }
static Argument arg3(double a, double b, double c3) { Argument r(3); r[0] = a; r[1] = b; r[2] = c3; return r; }
static double cubic(double x) { return x * x * x - 2 * x; }
static void integral(const std::string& n, const DefiniteIntegral& I, const AbsFunction& f) {
  try {
    d(n, I[f]);
    i(n + ".n", I.numFunctionCalls());
  } catch (std::runtime_error& e) {
    text(n, e.what());
  }
}
struct Above : public Cut<double> {
  double t;
  explicit Above(double xt) : t(xt) {}
  Above* clone() const override { return new Above(*this); }
  bool operator()(const double& x) const override { return x > t; }
};
static void rkDecay(const std::string& n, const RKIntegrator::RKStepper* stepper) {
  RKIntegrator rk(stepper);
  Variable y(0, 1);
  Parameter* k = rk.createControlParameter("k", 1.0, 0.0, 10.0);
  GENFUNCTION dydt = -(*k) * y;
  Parameter* y0 = rk.addDiffEquation(&dydt, "y", 1.0, 0.0, 10.0);
  const AbsFunction& Y = *rk.getFunction(0);
  try {
    for (int j = 0; j < 6; j++) {
      double t = pos(3.0, 0.0);
      d(K(n, j), Y(t));
    }
    d(n + ".t0", Y(0.0));
    d(n + ".neg", Y(-1.0));
    { double v = pos(2.0, 0.5); k->setValue(v); }
    for (int j = 0; j < 4; j++) {
      double t = pos(3.0, 0.0);
      d(K(n + ".k", j), Y(t));
    }
    { double v = pos(2.0, 0.5); y0->setValue(v); }
    for (int j = 0; j < 4; j++) {
      double t = pos(3.0, 0.0);
      d(K(n + ".y0", j), Y(t));
    }
  } catch (std::runtime_error& e) {
    text(n, e.what());
  }
}
// GSL errors: CLHEP raises SIGFPE; the handler turns it into an exception for the caller to report
static void fpe(int) { std::signal(SIGFPE, fpe); throw 1; }

int main() {
  gsl_set_error_handler_off();
  std::signal(SIGFPE, fpe);
%s
  return 0;
}
""" % ("\n".join('#include "CLHEP/GenericFunctions/%s.hh"' % h for h in HEADERS), "\n".join(cpp))

JAVA = """package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.genfun.*;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.ArrayList;
import java.util.List;

/**
 * The Java twin of genfun_ref.cc: every GenericFunctions class at random points with its derivative, the
 * algebra of functions and parameters, composition, direct products and convolution, the adaptors and cuts,
 * the integrals and functionals, splines and interpolation, the GSL special functions, the expansions and
 * fits, the Butcher tableaus, the Runge-Kutta integrators with every stepper and the classical solver.
 * What CLHEP writes to std::cout (SphericalHarmonicFit's corrections) is part of the output, as in the C++.
 * Generated by gen_genfun_ref.py.
 */
public final class GenfunReference extends Reference {

    @Override
    public String name() {
        return "genfun";
    }

    private static String K(String n, int k) {
        return n + "." + k;
    }

    private void c(String n, Complex z) {
        d(n + ".re", z.re());
        d(n + ".im", z.im());
    }

    /** The lines of a text, as std::getline reads them. */
    private void lines(String n, String t) {
        final String[] parts = t.split("\\n", -1);
        final int count = t.isEmpty() || t.endsWith("\\n") ? parts.length - 1 : parts.length;
        for (int k = 0; k < count; k++) text(K(n, k), parts[k]);
    }

    private void eval1(String n, AbsFunction f, double lo, double hi, int count) {
        final FunctionNoop df = f.prime();
        for (int k = 0; k < count; k++) {
            final double x = pos(hi - lo, lo);
            d(K(n, k), f.call(x));
            d(K(n, k) + "'", df.call(x));
        }
    }

    private void eval0(String n, AbsFunction f, double lo, double hi, int count) {
        for (int k = 0; k < count; k++) {
            final double x = pos(hi - lo, lo);
            d(K(n, k), f.call(x));
        }
    }

    private static Argument arg2(double a, double b) {
        final Argument r = new Argument(2);
        r.set(0, a);
        r.set(1, b);
        return r;
    }

    private static Argument arg3(double a, double b, double c3) {
        final Argument r = new Argument(3);
        r.set(0, a);
        r.set(1, b);
        r.set(2, c3);
        return r;
    }

    private void integral(String n, DefiniteIntegral I, AbsFunction f) {
        try {
            d(n, I.apply(f));
            i(n + ".n", I.numFunctionCalls());
        } catch (RuntimeException e) {
            text(n, e.getMessage());
        }
    }

    private static final class Above extends Cut<Double> {
        private final double t;

        Above(double t) {
            this.t = t;
        }

        @Override
        public Above copy() {
            return new Above(t);
        }

        @Override
        public boolean test(Double x) {
            return x > t;
        }
    }

    private void rkDecay(String n, RKIntegrator.RKStepper stepper) {
        final RKIntegrator rk = new RKIntegrator(stepper);
        final Variable y = new Variable(0, 1);
        final Parameter k = rk.createControlParameter("k", 1.0, 0.0, 10.0);
        final AbsFunction dydt = k.negate().times(y);
        final Parameter y0 = rk.addDiffEquation(dydt, "y", 1.0, 0.0, 10.0);
        final AbsFunction Y = rk.getFunction(0);
        try {
            for (int j = 0; j < 6; j++) {
                final double t = pos(3.0, 0.0);
                d(K(n, j), Y.call(t));
            }
            d(n + ".t0", Y.call(0.0));
            d(n + ".neg", Y.call(-1.0));
            { final double v = pos(2.0, 0.5); k.setValue(v); }
            for (int j = 0; j < 4; j++) {
                final double t = pos(3.0, 0.0);
                d(K(n + ".k", j), Y.call(t));
            }
            { final double v = pos(2.0, 0.5); y0.setValue(v); }
            for (int j = 0; j < 4; j++) {
                final double t = pos(3.0, 0.0);
                d(K(n + ".y0", j), Y.call(t));
            }
        } catch (RuntimeException e) {
            text(n, e.getMessage());
        }
    }

    @Override
    protected void run() {
        try {
            StdStreams.with(new StdStreams(this::line, null), () -> {
                body();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void body() {
%s
    }
}
""" % "\n".join(java)

(C / "genfun_ref.cc").write_text(CPP, encoding="utf-8")
(J / "GenfunReference.java").write_text(JAVA, encoding="utf-8")
print("C++ %d lines, Java %d lines" % (CPP.count("\n"), JAVA.count("\n")))
