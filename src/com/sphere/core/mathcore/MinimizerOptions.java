package com.sphere.core.mathcore;

import com.sphere.core.hepmc3.cxx.CFormat;

import java.io.PrintStream;

/**
 * ROOT::Math::MinimizerOptions: the options of a minimizer (print level,
 * maximum calls and iterations, strategy, error definition, tolerance,
 * precision, minimizer type and algorithm, extra options), and the defaults
 * every new Minimizer starts from, set with the static SetDefault... methods.
 *
 * <p>Defaults as ROOT's: Minuit2 / Migrad, error definition 1, tolerance
 * 0.01, precision -1 (left to the minimizer), 0 calls and iterations (left
 * to the minimizer), strategy 1, print level 0. ROOT reads the default
 * minimizer from Root.Fitter in system.rootrc, which is Minuit2.
 */
public class MinimizerOptions {

    /* ---- the defaults (ROOT::Math::Minim) ---- */
    private static String gDefaultMinimizer = "";
    private static String gDefaultMinimAlgo = "Migrad";
    private static double gDefaultErrorDef = 1.;
    private static double gDefaultTolerance = 1.E-2;
    private static double gDefaultPrecision = -1;
    private static int gDefaultMaxCalls = 0;
    private static int gDefaultMaxIter = 0;
    private static int gDefaultStrategy = 1;
    private static int gDefaultPrintLevel = 0;
    private static IOptions gDefaultExtraOptions = null;

    public static synchronized void SetDefaultMinimizer(String type, String algo) {
        if (type != null) gDefaultMinimizer = type;
        if (algo != null) gDefaultMinimAlgo = algo;
        if (gDefaultMinimAlgo.isEmpty() && (gDefaultMinimizer.equals("Minuit") || gDefaultMinimizer.equals("Minuit2"))) {
            gDefaultMinimAlgo = "Migrad";
        }
    }

    public static void SetDefaultMinimizer(String type) {
        SetDefaultMinimizer(type, null);
    }

    public static synchronized void SetDefaultErrorDef(double up) {
        gDefaultErrorDef = up;
    }

    public static synchronized void SetDefaultTolerance(double tol) {
        gDefaultTolerance = tol;
    }

    public static synchronized void SetDefaultPrecision(double prec) {
        gDefaultPrecision = prec;
    }

    public static synchronized void SetDefaultMaxFunctionCalls(int maxcall) {
        gDefaultMaxCalls = maxcall;
    }

    public static synchronized void SetDefaultMaxIterations(int maxiter) {
        gDefaultMaxIter = maxiter;
    }

    public static synchronized void SetDefaultStrategy(int strat) {
        gDefaultStrategy = strat;
    }

    public static synchronized void SetDefaultPrintLevel(int level) {
        gDefaultPrintLevel = level;
    }

    public static synchronized void SetDefaultExtraOptions(IOptions extraoptions) {
        gDefaultExtraOptions = extraoptions != null ? extraoptions.Clone() : null;
    }

    public static synchronized String DefaultMinimizerAlgo() {
        if (gDefaultMinimAlgo.equals("Migrad") && !gDefaultMinimizer.equals("Minuit") && !gDefaultMinimizer.equals("Minuit2")) {
            gDefaultMinimAlgo = "";
        }
        return gDefaultMinimAlgo;
    }

    public static synchronized double DefaultErrorDef() {
        return gDefaultErrorDef;
    }

    public static synchronized double DefaultTolerance() {
        return gDefaultTolerance;
    }

    public static synchronized double DefaultPrecision() {
        return gDefaultPrecision;
    }

    public static synchronized int DefaultMaxFunctionCalls() {
        return gDefaultMaxCalls;
    }

    public static synchronized int DefaultMaxIterations() {
        return gDefaultMaxIter;
    }

    public static synchronized int DefaultStrategy() {
        return gDefaultStrategy;
    }

    public static synchronized int DefaultPrintLevel() {
        return gDefaultPrintLevel;
    }

    public static synchronized IOptions DefaultExtraOptions() {
        return gDefaultExtraOptions;
    }

    /** The default minimizer: what was set, else Root.Fitter of ROOT's system.rootrc, Minuit2. */
    public static synchronized String DefaultMinimizerType() {
        if (!gDefaultMinimizer.isEmpty()) return gDefaultMinimizer;
        gDefaultMinimizer = "Minuit2";
        return gDefaultMinimizer;
    }

    public static IOptions Default(String name) {
        return GenAlgoOptions.Default(name);
    }

    public static IOptions FindDefault(String name) {
        return GenAlgoOptions.FindDefault(name);
    }

    public static void PrintDefault(String name, PrintStream os) {
        final MinimizerOptions tmp = new MinimizerOptions();
        tmp.Print(os);
        if (tmp.ExtraOptions() == null) {
            if (name != null) {
                final IOptions opt = FindDefault(name);
                os.print("Specific options for " + name + "\n");
                if (opt != null) opt.Print(os);
            } else {
                GenAlgoOptions.PrintAllDefault(os);
            }
        }
    }

    public static void PrintDefault() {
        PrintDefault(null, System.out);
    }

    /* ---- the options of one minimizer ---- */
    private int fLevel;
    private int fMaxCalls;
    private int fMaxIter;
    private int fStrategy;
    private double fErrorDef;
    private double fTolerance;
    private double fPrecision;
    private String fMinimType;
    private String fAlgoType;
    private IOptions fExtraOptions;

    public MinimizerOptions() {
        ResetToDefaultOptions();
    }

    public MinimizerOptions(MinimizerOptions opt) {
        assign(opt);
    }

    /** operator=: a copy of opt (its extra options cloned). */
    public MinimizerOptions assign(MinimizerOptions opt) {
        if (this == opt) return this;
        fLevel = opt.fLevel;
        fMaxCalls = opt.fMaxCalls;
        fMaxIter = opt.fMaxIter;
        fStrategy = opt.fStrategy;
        fErrorDef = opt.fErrorDef;
        fTolerance = opt.fTolerance;
        fPrecision = opt.fPrecision;
        fMinimType = opt.fMinimType;
        fAlgoType = opt.fAlgoType;
        fExtraOptions = opt.fExtraOptions != null ? opt.fExtraOptions.Clone() : null;
        return this;
    }

    public int PrintLevel() {
        return fLevel;
    }

    public int MaxFunctionCalls() {
        return fMaxCalls;
    }

    public int MaxIterations() {
        return fMaxIter;
    }

    public int Strategy() {
        return fStrategy;
    }

    public double Tolerance() {
        return fTolerance;
    }

    public double Precision() {
        return fPrecision;
    }

    public double ErrorDef() {
        return fErrorDef;
    }

    public IOptions ExtraOptions() {
        return fExtraOptions;
    }

    public String MinimizerType() {
        return fMinimType;
    }

    public String MinimizerAlgorithm() {
        return fAlgoType;
    }

    public void ResetToDefaultOptions() {
        synchronized (MinimizerOptions.class) {
            fLevel = gDefaultPrintLevel;
            fMaxCalls = gDefaultMaxCalls;
            fMaxIter = gDefaultMaxIter;
            fStrategy = gDefaultStrategy;
            fErrorDef = gDefaultErrorDef;
            fTolerance = gDefaultTolerance;
            fPrecision = gDefaultPrecision;
            fMinimType = DefaultMinimizerType();
            fAlgoType = gDefaultMinimAlgo;
        }
        if (fMinimType.equals("TMinuit")) {
            fMinimType = "Minuit";
        } else if (fMinimType.equals("Fumili2")) {
            fMinimType = "Minuit2";
            fAlgoType = "Fumili";
        } else if (fMinimType.equals("GSLMultiMin") && fAlgoType.equals("Migrad")) {
            fAlgoType = "BFGS2";
        } else if (!fMinimType.equals("Minuit") && !fMinimType.equals("Minuit2") && fAlgoType.equals("Migrad")) {
            fAlgoType = "";
        }
        fExtraOptions = null;
        final IOptions def = DefaultExtraOptions();
        if (def != null) {
            fExtraOptions = def.Clone();
        } else {
            final IOptions gopts = FindDefault(fMinimType);
            if (gopts != null) fExtraOptions = gopts.Clone();
        }
    }

    public void SetPrintLevel(int level) {
        fLevel = level;
    }

    public void SetMaxFunctionCalls(int maxfcn) {
        fMaxCalls = maxfcn;
    }

    public void SetMaxIterations(int maxiter) {
        fMaxIter = maxiter;
    }

    public void SetTolerance(double tol) {
        fTolerance = tol;
    }

    public void SetPrecision(double prec) {
        fPrecision = prec;
    }

    public void SetStrategy(int stra) {
        fStrategy = stra;
    }

    public void SetErrorDef(double err) {
        fErrorDef = err;
    }

    public void SetMinimizerType(String type) {
        fMinimType = type;
    }

    public void SetMinimizerAlgorithm(String type) {
        fAlgoType = type;
    }

    public void SetExtraOptions(IOptions opt) {
        fExtraOptions = opt.Clone();
    }

    /** Print: one option a line, the name right-aligned on 25 characters, the value on 15. */
    public void Print(PrintStream os) {
        line(os, "Minimizer Type", CFormat.sprintf("%15s", fMinimType));
        line(os, "Minimizer Algorithm", CFormat.sprintf("%15s", fAlgoType));
        line(os, "Strategy", CFormat.sprintf("%15d", fStrategy));
        line(os, "Tolerance", CFormat.sprintf("%15g", fTolerance));
        line(os, "Max func calls", CFormat.sprintf("%15d", fMaxCalls));
        line(os, "Max iterations", CFormat.sprintf("%15d", fMaxIter));
        line(os, "Func Precision", CFormat.sprintf("%15g", fPrecision));
        line(os, "Error definition", CFormat.sprintf("%15g", fErrorDef));
        line(os, "Print Level", CFormat.sprintf("%15d", fLevel));
        if (ExtraOptions() != null) {
            os.print(fMinimType + " specific options :\n");
            ExtraOptions().Print(os);
        }
    }

    public void Print() {
        Print(System.out);
    }

    private static void line(PrintStream os, String name, String value) {
        os.print(CFormat.sprintf("%25s", name) + " : " + value + "\n");
    }
}
