package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.evaluator.Evaluator;

import java.util.Locale;

/**
 * The Java twin of evaluator_ref.cc: expressions written by hand (errors, their positions, the C library's
 * errno) and random ones drawn from xorshift64, the same in both programs.
 */
public final class EvaluatorReference extends Reference {

    private Evaluator ev;

    @Override
    public String name() {
        return "evaluator";
    }

    private void show(String expr) {
        final double v = ev.evaluate(expr);
        line(String.format(Locale.ROOT, "[%s] status %d pos %d value %016x name [%s]", expr, ev.status(),
            ev.error_position(), Double.doubleToRawLongBits(v), ev.error_name()));
    }

    private static final String[] NAMES = {"pi", "e", "mm", "cm", "GeV", "MeV", "deg", "x", "y", "zz", "tesla", "ns"};
    private static final String[] OPS = {"+", "-", "*", "/", "^", "**", "==", "!=", "<", "<=", ">", ">=", "&&", "||"};
    private static final String[] FUNS = {"sin", "cos", "sqrt", "exp", "log", "abs", "atan", "f1"};
    private static final String[] FUNS2 = {"pow", "atan2", "min", "max", "f2"};

    /** A random expression, one draw per statement as in the C++. */
    private String gen(int depth) {
        final double r = rnd();
        if (depth > 3 || r < 0.3) {
            final double t = rnd();
            if (t < 0.45) {
                final int n = (int) (rnd() * 100);
                final double frac = rnd();
                String s = Integer.toString(n);
                if (frac < 0.3) s += "." + (int) (rnd() * 10);
                else if (frac < 0.4) s += "e" + ((int) (rnd() * 5) - 2);
                return s;
            }
            if (t < 0.9) return NAMES[(int) (rnd() * 12)];
            final double g = rnd();
            if (g < 0.3) return "";
            if (g < 0.6) return "(";
            return ")";
        }
        final double k = rnd();
        if (k < 0.45) {
            final String a = gen(depth + 1);
            final String op = OPS[(int) (rnd() * 14)];
            final String sp = rnd() < 0.3 ? " " : "";
            final String b = gen(depth + 1);
            return a + sp + op + sp + b;
        }
        if (k < 0.6) {
            final String a = gen(depth + 1);
            return "(" + a + ")";
        }
        if (k < 0.7) {
            final String a = gen(depth + 1);
            return (rnd() < 0.5 ? "-" : "+") + a;
        }
        if (k < 0.85) {
            final String f = FUNS[(int) (rnd() * 8)];
            final String a = gen(depth + 1);
            return f + "(" + a + ")";
        }
        final String f = FUNS2[(int) (rnd() * 5)];
        final String a = gen(depth + 1);
        final String b = gen(depth + 1);
        return f + "(" + a + "," + b + ")";
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }

    @Override
    protected void run() {
        final Evaluator e = new Evaluator();
        ev = e;
        show("1+2");
        show("pi");
        show("");
        show("   ");
        e.setStdMath();
        line("std status " + e.status());
        e.setSystemOfUnits();
        line("units status " + e.status());

        final String[] hand = {
            "1+2*3", "(1+2)*3", "2^3^2", "2**3**2", "-2^2", "2^-2", "-(-3)", "+-+2", "2*-3", "2--3", "2- -3",
            "1<2<3", "3>2>1", "1==1==1", "1!=2", "0||0", "0||3", "2&&0", "2&&3", "1+1==2&&2*2==4", "1 + 2 * (3 - 4) / 5",
            "sin(pi/6)", "cos(0)", "tan(pi/4)", "asin(1)", "acos(-1)", "atan(1)", "atan2(1,1)", "atan2(0,0)",
            "sinh(1)", "cosh(1)", "tanh(1)", "exp(1)", "log(e)", "log10(1000)", "sqrt(2)", "pow(2,10)", "abs(-3)",
            "min(2,3)", "max(2,3)", "min(max(1,2),3)", "f0()", "f1(3)", "f2(10,4)", "f3(2,3,4)", "f4(1,2,3,4)",
            "f5(1,2,3,4,5)", "f5(1,2,f3(1,2,3),4,5)", "x", "y", "zz", "2*x+y", "x/y",
            "(1+2", "1+2)", "((1)", "f2(1,)", "f2(,1)", "f2(1,,2)", "sin()", "f0( )", "sin(1,2)", "unknown(1)",
            "abc", "1 2", "1+*2", "1+", "*1", "()", "(", ")", "1|2", "1&2", "1=2", "!1", "1!2", "1#2", "a b", "sin(1",
            "sin 1", "1.5e", "1e+", "1.2.3", ".", "..", "x.", "1/0", "0/0", "1/(1-1)",
            "sqrt(-1)", "sqrt(-0)", "log(0)", "log(-1)", "log10(0)", "log10(-2)", "asin(2)", "acos(-2)",
            "pow(-8,1/3)", "pow(-8,3)", "pow(0,-1)", "pow(0,0)", "pow(10,400)", "pow(10,-400)", "pow(10,-320)",
            "(-8)^(1/3)", "0^-1", "10^400", "10^-400", "exp(1000)", "exp(-1000)", "exp(-740)", "exp(709)",
            "sinh(1000)", "cosh(-1000)", "sinh(-1000)", "tanh(1000)", "sin(inf)", "cos(-inf)", "tan(inf)",
            "atan(inf)", "exp(inf)", "exp(-inf)", "log(inf)", "sqrt(inf)", "sqrt(-inf)", "pow(inf,2)", "pow(-inf,0.5)",
            "pow(2,inf)", "sqrt(nan)", "nan==nan", "nan!=nan", "nan||0", "nan&&1", "-nan", "sin(nan)", "asin(nan)",
            "1e-310", "4.9e-324", "2.2250738585072014e-308", "1e308", "1e309", "1e400", "1e-400", "0x1p-1074", "0x1p-1030", "0x1p-1080", "2.2250738585072009e-308", "0x1p3",
            "0x1.8p1", "0x", "0xg", ".5", "5.", "00012", "0.000", "0e5", "1e5", "1E5", "123456789012345678901234567890",
            "0.1+0.2", "3.14159265358979323846", "1e-5*mm",
            "1*GeV", "3*tesla", "2*kGs", "1*mCi", "1*parsec", "1*eV", "1*barn", "1*atm", "60*minute", "1*year",
            "1*L", "1*cc", "1*mrad", "1*deg", "1*sr", "1*ohm", "1*farad", "1*henry", "1*lux", "1*Gy", "1*Sv",
            "1*kilogray", "1*angstrom", "1*fermi", "1*picobarn", "1*dpt", "1*mg", "1*ps", "1*kHz", "1*kbar"
        };
        e.setVariable("x", 3.5);
        e.setVariable("y", "x*2+1");
        e.setVariable("zz", "y-x");
        e.setVariable("inf", Double.POSITIVE_INFINITY);
        e.setVariable("nan", Double.NaN);
        e.setFunction("f0", () -> 42.0);
        e.setFunction("f1", (Evaluator.F1) a -> a * 2 + 1);
        e.setFunction("f2", (Evaluator.F2) (a, b) -> a - b);
        e.setFunction("f3", (Evaluator.F3) (a, b, c) -> a * b - c);
        e.setFunction("f4", (Evaluator.F4) (a, b, c, d) -> a + b * c - d);
        e.setFunction("f5", (Evaluator.F5) (a, b, c, d, f) -> a - b + c * d / f);
        for (String h : hand) show(h);

        e.setVariable("x", 4.0);
        line("set x again " + e.status());
        e.setVariable(" x ", 5.0);
        line("set ' x ' " + e.status());
        e.setVariable("2x", 1.0);
        line("set 2x " + e.status());
        e.setVariable("a-b", 1.0);
        line("set a-b " + e.status());
        e.setVariable("", 1.0);
        line("set empty " + e.status());
        e.setVariable("  ", 1.0);
        line("set blank " + e.status());
        e.setFunction("sin", (Evaluator.F1) a -> a * 2 + 1);
        line("set sin " + e.status());
        e.setFunction("sin", (Evaluator.F2) (a, b) -> a - b);
        line("set sin/2 " + e.status());
        show("sin(1)");
        show("sin(1,2)");
        show("x");
        line("find " + b(e.findVariable("x")) + " " + b(e.findVariable(" x")) + " " + b(e.findVariable("q")) + " "
            + b(e.findVariable("1sin")) + " " + b(e.findFunction("sin", 1)) + " " + b(e.findFunction("sin", 3)));
        e.removeVariable("x");
        show("x");
        e.removeFunction("sin", 2);
        show("sin(1,2)");
        e.setVariable("bad", "1+");
        show("bad");
        e.setVariable("1sin", 2.0);
        show("sin(3)");

        for (int k = 0; k < 3000; k++) {
            final String s = gen(0);
            show(s);
        }

        e.clear();
        line("cleared " + e.status());
        show("pi");
        e.setStdMath();
        e.setSystemOfUnits(1.e+3, 1. / 1.60217733e-25, 1.e+9, 1. / 1.60217733e-10, 1.0, 1.0, 1.0);
        final String[] g4 = {"m", "kg", "s", "A", "K", "mol", "cd", "GeV", "MeV", "keV", "eV", "tesla", "gauss",
            "kilogauss", "volt", "kV", "ns", "ps", "us", "ms", "minute", "year", "cm", "um", "nm", "km",
            "barn", "mbarn", "g", "mg", "liter", "joule", "watt", "newton", "pascal", "atmosphere",
            "bar", "coulomb", "ampere", "ohm", "farad", "henry", "weber", "becquerel", "curie",
            "gray", "lumen", "lux", "parsec", "angstrom", "fermi", "deg", "mrad", "sr", "hertz",
            "MHz", "1.5*cm+2*mm", "10*GeV/(2*mm)"};
        for (String n : g4) show(n);
    }
}
