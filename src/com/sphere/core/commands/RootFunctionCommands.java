package com.sphere.core.commands;

/**
 * ROOT's function classes beyond TF1, and the objects that live next to them.
 *
 * TF1 had forty commands and every other function class had none: a TF2 could
 * not be made, integrated or minimized, a TF12 (the one-dimensional cut of a
 * TF2 along x or y) did not exist at all, and neither did the two ways ROOT
 * builds a function from others -- TF1Convolution (a resolution folded into a
 * shape) and TF1NormSum (signal plus background with yields as parameters).
 * These are the forms a fit model actually takes in an analysis.
 *
 * Next to them: graphical cuts (TCutG), efficiency graphs with honest
 * asymmetric errors (TGraphAsymmErrors::Divide), graph smoothing
 * (TGraphSmooth), polygon-binned histograms (TH2Poly), FitSlicesY, and a
 * general minimizer (ROOT::Math::Minimizer) on any formula of up to ten
 * variables.
 *
 * Every command is one C++ expression for the engine's interpreter. Those that
 * report more than one number are a lambda that writes a small table, so the
 * whole answer comes back in one round trip.
 */
public final class RootFunctionCommands {

    /** A minimization or a 3D integral can take longer than a plain call. */
    private static final long LONG_MS = 60_000L;

    private RootFunctionCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static String[] w(String i, String command) {
        return Handlers.words(Handlers.args(i, command));
    }

    private static String q(String s) {
        return "\"" + Handlers.lit(s) + "\"";
    }

    /** A number as typed, or the fallback when it is not one. */
    private static String num(String s, String fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            Double.parseDouble(s.trim());
            return s.trim();
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** Any function ROOT knows by name, of the given class. */
    private static String fn(String type, String name) {
        return Handlers.obj(type, name);
    }

    /**
     * A graph by name, whichever way it was made: bound as a handle by
     * :root gerr new or :root gae divide, or registered with ROOT.
     */
    static String anyGraph(String name) {
        final String n = q(name);
        return "([]() -> TGraph * {"
             + " try { return SphereBridge::Held<TGraph>(" + n + "); } catch (...) {}"
             + " try { return SphereBridge::Held<TGraphErrors>(" + n + "); } catch (...) {}"
             + " try { return SphereBridge::Held<TGraphAsymmErrors>(" + n + "); } catch (...) {}"
             + " return SphereBridge::Need<TGraph>(" + n + ", \"TGraph\"); }())";
    }

    /**
     * The headers these commands lean on. The interpreter autoloads most ROOT
     * classes, but not std::setw, and not every class behind a namespace, so
     * they are declared before the expression that uses them is parsed: a
     * declaration inside the same expression would come too late.
     */
    static final String HEADERS =
        "#include <iomanip>\n#include <sstream>\n#include <vector>\n#include <memory>\n"
      + "#include <cmath>\n#include <algorithm>\n#include <numeric>\n"
      + "#include \"TF1.h\"\n#include \"TF2.h\"\n#include \"TF3.h\"\n#include \"TF12.h\"\n"
      + "#include \"TF1Convolution.h\"\n#include \"TF1NormSum.h\"\n#include \"TCutG.h\"\n"
      + "#include \"TGraphAsymmErrors.h\"\n#include \"TGraphSmooth.h\"\n#include \"TH2Poly.h\"\n"
      + "#include \"TFormula.h\"\n#include \"TRandom3.h\"\n#include \"TMath.h\"\n"
      + "#include \"Math/Factory.h\"\n#include \"Math/Functor.h\"\n#include \"Math/Minimizer.h\"\n";

    static void prepare(CommandExecutionContext c) {
        final var b = Handlers.backend(c);
        if (b != null && b.isAvailable()) {
            b.executeClingAwait("gInterpreter->Declare(\"" + Handlers.lit(HEADERS).replace("\n", "\\n")
                + "\")", Handlers.TIMEOUT_MS);
        }
    }

    private static void run(CommandExecutionContext c, String cpp) {
        prepare(c);
        Handlers.cling(c, cpp, LONG_MS);
    }

    /* ------------------------------------------------------------------ */
    /* any function                                                        */
    /* ------------------------------------------------------------------ */

    /** Every function ROOT holds, with its class, dimension and parameters. */
    public static void tfList(String i, CommandExecutionContext c) {
        run(c, "([]() { std::ostringstream o; TIter next(gROOT->GetListOfFunctions());"
             + " int n = 0; while (TObject *obj = next()) { TF1 *f = dynamic_cast<TF1 *>(obj);"
             + " if (!f) continue; ++n; o << std::left << std::setw(18) << f->GetName()"
             + " << std::setw(16) << f->ClassName() << f->GetNdim() << \"D  npar=\" << f->GetNpar()"
             + " << \"  [\" << f->GetXmin() << \", \" << f->GetXmax() << \"]  \" << f->GetExpFormula() << \"\\n\"; }"
             + " if (n == 0) o << \"no function yet\"; return o.str(); }())");
    }

    /** One function in full: class, dimension, range, parameters with errors. */
    public static void tfInfo(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf info");
        if (a.length < 1) {
            Handlers.usage(":root tf info <name>");
            return;
        }
        run(c, "([](TF1 *f) { std::ostringstream o; o << f->GetName() << \"  \" << f->ClassName()"
             + " << \"  \" << f->GetNdim() << \"D\\n  formula  \" << f->GetExpFormula()"
             + " << \"\\n  x        [\" << f->GetXmin() << \", \" << f->GetXmax() << \"]\";"
             + " if (TF2 *g = dynamic_cast<TF2 *>(f)) o << \"\\n  y        [\" << g->GetYmin() << \", \" << g->GetYmax() << \"]\";"
             + " if (TF3 *g = dynamic_cast<TF3 *>(f)) o << \"\\n  z        [\" << g->GetZmin() << \", \" << g->GetZmax() << \"]\";"
             + " for (int k = 0; k < f->GetNpar(); ++k) o << \"\\n  p\" << k << \" \" << std::left << std::setw(12)"
             + " << f->GetParName(k) << f->GetParameter(k) << \" +- \" << f->GetParError(k);"
             + " if (f->GetNDF() > 0) o << \"\\n  chi2/ndf \" << f->GetChisquare() << \"/\" << f->GetNDF()"
             + " << \"  prob \" << f->GetProb(); return o.str(); }(" + fn("TF1", a[0]) + "))");
    }

    /** f(x), f(x,y) or f(x,y,z), whatever the function's dimension. */
    public static void tfEval(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf eval");
        if (a.length < 2) {
            Handlers.usage(":root tf eval <name> <x> [y] [z]");
            return;
        }
        run(c, fn("TF1", a[0]) + "->Eval(" + num(a[1], "0") + ", "
             + (a.length > 2 ? num(a[2], "0") : "0") + ", "
             + (a.length > 3 ? num(a[3], "0") : "0") + ")");
    }

    /** The gradient with respect to the parameters at x: what a fit sees. */
    public static void tfGradient(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf gradpar");
        if (a.length < 2) {
            Handlers.usage(":root tf gradpar <name> <x> [y] [z]");
            return;
        }
        run(c, "([](TF1 *f) { double x[3] = {" + num(a[1], "0") + ", "
             + (a.length > 2 ? num(a[2], "0") : "0") + ", " + (a.length > 3 ? num(a[3], "0") : "0")
             + "}; std::vector<double> g(f->GetNpar() > 0 ? f->GetNpar() : 1); f->GradientPar(x, g.data());"
             + " std::ostringstream o; for (int k = 0; k < f->GetNpar(); ++k) o << \"d f / d \" << f->GetParName(k)"
             + " << \" = \" << g[k] << \"\\n\"; if (f->GetNpar() == 0) o << \"no parameter\"; return o.str(); }("
             + fn("TF1", a[0]) + "))");
    }

    /** Writes a function's sampled values into a file ROOT reads back, or a .C. */
    public static void tfSave(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf save");
        if (a.length < 2) {
            Handlers.usage(":root tf save <name> <file.root|file.C>");
            return;
        }
        if (a[1].endsWith(".C") || a[1].endsWith(".cxx")) {
            run(c, "(" + fn("TF1", a[0]) + "->SaveAs(" + q(a[1]) + "), std::string(\"written " + Handlers.lit(a[1]) + "\"))");
        } else {
            run(c, "([](TF1 *f) { TFile out(" + q(a[1]) + ", \"UPDATE\"); f->Write(); out.Close();"
                 + " return std::string(\"written to " + Handlers.lit(a[1]) + "\"); }(" + fn("TF1", a[0]) + "))");
        }
    }

    /* ------------------------------------------------------------------ */
    /* TF2                                                                 */
    /* ------------------------------------------------------------------ */

    public static void tf2New(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 new");
        if (a.length < 2) {
            Handlers.usage(":root tf2 new <name> <formula> [xmin xmax ymin ymax]");
            return;
        }
        // A formula with spaces arrives as several words; the range is the
        // last four words only when all four are numbers.
        String formula = Handlers.join(a, 1);
        String range = "0, 1, 0, 1";
        if (a.length >= 6 && isNum(a[a.length - 1]) && isNum(a[a.length - 2])
                && isNum(a[a.length - 3]) && isNum(a[a.length - 4])) {
            formula = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length - 4));
            range = a[a.length - 4] + ", " + a[a.length - 3] + ", "
                  + a[a.length - 2] + ", " + a[a.length - 1];
        }
        run(c, "([]() { TF2 *f = new TF2(" + q(a[0]) + ", " + q(formula) + ", " + range + ");"
             + " return std::string(f->GetName()) + \"  TF2  npar=\" + std::to_string(f->GetNpar()); }())");
    }

    public static void tf2Eval(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 eval");
        if (a.length < 3) {
            Handlers.usage(":root tf2 eval <name> <x> <y>");
            return;
        }
        run(c, fn("TF2", a[0]) + "->Eval(" + num(a[1], "0") + ", " + num(a[2], "0") + ")");
    }

    public static void tf2Integral(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 integral");
        if (a.length < 1) {
            Handlers.usage(":root tf2 integral <name> [ax bx ay by] [epsrel]");
            return;
        }
        if (a.length >= 5) {
            run(c, fn("TF2", a[0]) + "->Integral(" + Handlers.csv(Handlers.join(a, 1)) + ")");
        } else {
            run(c, "([](TF2 *f) { return f->Integral(f->GetXmin(), f->GetXmax(), f->GetYmin(), f->GetYmax()); }("
                 + fn("TF2", a[0]) + "))");
        }
    }

    public static void tf2Min(String i, CommandExecutionContext c) {
        extremum2(i, c, ":root tf2 min", "GetMinimumXY", "minimum");
    }

    public static void tf2Max(String i, CommandExecutionContext c) {
        extremum2(i, c, ":root tf2 max", "GetMaximumXY", "maximum");
    }

    private static void extremum2(String i, CommandExecutionContext c, String cmd,
                                  String call, String word) {
        final String[] a = w(i, cmd);
        if (a.length < 1) {
            Handlers.usage(cmd + " <name>");
            return;
        }
        run(c, "([](TF2 *f) { double x = 0, y = 0; double v = f->" + call + "(x, y); std::ostringstream o;"
             + " o << \"" + word + " \" << v << \" at (x, y) = (\" << x << \", \" << y << \")\"; return o.str(); }("
             + fn("TF2", a[0]) + "))");
    }

    /** The function read as a density: means, spreads and correlation over its range. */
    public static void tf2Stats(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 stats");
        if (a.length < 1) {
            Handlers.usage(":root tf2 stats <name>");
            return;
        }
        run(c, "([](TF2 *f) { double ax = f->GetXmin(), bx = f->GetXmax(), ay = f->GetYmin(), by = f->GetYmax();"
             + " double mx = f->Mean2X(ax, bx, ay, by), my = f->Mean2Y(ax, bx, ay, by);"
             + " double vx = f->Variance2X(ax, bx, ay, by), vy = f->Variance2Y(ax, bx, ay, by);"
             + " double cxy = f->Covariance2XY(ax, bx, ay, by); std::ostringstream o;"
             + " o << \"integral \" << f->Integral(ax, bx, ay, by) << \"\\nmean x   \" << mx << \"\\nmean y   \" << my"
             + " << \"\\nsigma x  \" << std::sqrt(vx) << \"\\nsigma y  \" << std::sqrt(vy)"
             + " << \"\\ncov xy   \" << cxy << \"\\nrho      \" << (vx > 0 && vy > 0 ? cxy / std::sqrt(vx * vy) : 0.0);"
             + " return o.str(); }(" + fn("TF2", a[0]) + "))");
    }

    /** Draws n pairs from the function and says what they look like. */
    public static void tf2Random(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 random");
        if (a.length < 1) {
            Handlers.usage(":root tf2 random <name> [n]");
            return;
        }
        final String n = a.length > 1 ? num(a[1], "10") : "10";
        run(c, "([](TF2 *f) { std::ostringstream o; double x, y; long n = " + n + ";"
             + " double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;"
             + " for (long k = 0; k < n; ++k) { f->GetRandom2(x, y); if (k < 10) o << x << \"  \" << y << \"\\n\";"
             + " sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; }"
             + " double mx = sx / n, my = sy / n, vx = sxx / n - mx * mx, vy = syy / n - my * my;"
             + " o << n << \" drawn, mean (\" << mx << \", \" << my << \"), rho \""
             + " << ((vx > 0 && vy > 0) ? (sxy / n - mx * my) / std::sqrt(vx * vy) : 0.0); return o.str(); }("
             + fn("TF2", a[0]) + "))");
    }

    /** A TH2D filled from the function, either sampled or tabulated. */
    public static void tf2Hist(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 hist");
        if (a.length < 2) {
            Handlers.usage(":root tf2 hist <name> <hist> [entries|0 to tabulate] [nx] [ny]");
            return;
        }
        final String entries = a.length > 2 ? num(a[2], "0") : "0";
        final String nx = a.length > 3 ? num(a[3], "50") : "50";
        final String ny = a.length > 4 ? num(a[4], "50") : "50";
        run(c, "([](TF2 *f) { TH2D *h = new TH2D(" + q(a[1]) + ", f->GetTitle(), " + nx
             + ", f->GetXmin(), f->GetXmax(), " + ny + ", f->GetYmin(), f->GetYmax());"
             + " long n = (long) " + entries + "; if (n > 0) h->FillRandom(f->GetName(), n); else h->Eval(f);"
             + " return std::string(h->GetName()) + (n > 0 ? \" filled with \" + std::to_string(n) + \" draws\""
             + " : std::string(\" holds the function's values\")); }(" + fn("TF2", a[0]) + "))");
    }

    public static void tf2Npx(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 npx");
        if (a.length < 3) {
            Handlers.usage(":root tf2 npx <name> <nx> <ny>");
            return;
        }
        run(c, "([](TF2 *f) { f->SetNpx(" + num(a[1], "100") + "); f->SetNpy(" + num(a[2], "100")
             + "); return std::string(\"sampling set\"); }(" + fn("TF2", a[0]) + "))");
    }

    public static void tf2Draw(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf2 draw");
        if (a.length < 1) {
            Handlers.usage(":root tf2 draw <name> [colz|surf1|cont1|lego2]");
            return;
        }
        run(c, "(" + fn("TF2", a[0]) + "->Draw(" + q(a.length > 1 ? a[1] : "colz")
             + "), std::string(\"drawn\"))");
    }

    /* ------------------------------------------------------------------ */
    /* TF3                                                                 */
    /* ------------------------------------------------------------------ */

    public static void tf3New(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 new");
        if (a.length < 2) {
            Handlers.usage(":root tf3 new <name> <formula> [xmin xmax ymin ymax zmin zmax]");
            return;
        }
        String formula = Handlers.join(a, 1);
        String range = "0, 1, 0, 1, 0, 1";
        if (a.length >= 8) {
            boolean all = true;
            for (int k = a.length - 6; k < a.length; k++) {
                all &= isNum(a[k]);
            }
            if (all) {
                formula = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length - 6));
                range = String.join(", ", java.util.Arrays.copyOfRange(a, a.length - 6, a.length));
            }
        }
        run(c, "([]() { TF3 *f = new TF3(" + q(a[0]) + ", " + q(formula) + ", " + range + ");"
             + " return std::string(f->GetName()) + \"  TF3  npar=\" + std::to_string(f->GetNpar()); }())");
    }

    public static void tf3Eval(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 eval");
        if (a.length < 4) {
            Handlers.usage(":root tf3 eval <name> <x> <y> <z>");
            return;
        }
        run(c, fn("TF3", a[0]) + "->Eval(" + num(a[1], "0") + ", " + num(a[2], "0") + ", "
             + num(a[3], "0") + ")");
    }

    public static void tf3Integral(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 integral");
        if (a.length < 1) {
            Handlers.usage(":root tf3 integral <name> [ax bx ay by az bz] [epsrel]");
            return;
        }
        if (a.length >= 7) {
            run(c, fn("TF3", a[0]) + "->Integral(" + Handlers.csv(Handlers.join(a, 1)) + ")");
        } else {
            run(c, "([](TF3 *f) { return f->Integral(f->GetXmin(), f->GetXmax(), f->GetYmin(),"
                 + " f->GetYmax(), f->GetZmin(), f->GetZmax()); }(" + fn("TF3", a[0]) + "))");
        }
    }

    public static void tf3Min(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 min");
        if (a.length < 1) {
            Handlers.usage(":root tf3 min <name>");
            return;
        }
        run(c, "([](TF3 *f) { double x = 0, y = 0, z = 0; double v = f->GetMinimumXYZ(x, y, z);"
             + " std::ostringstream o; o << \"minimum \" << v << \" at (\" << x << \", \" << y << \", \" << z << \")\";"
             + " return o.str(); }(" + fn("TF3", a[0]) + "))");
    }

    public static void tf3Random(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 random");
        if (a.length < 1) {
            Handlers.usage(":root tf3 random <name> [n]");
            return;
        }
        final String n = a.length > 1 ? num(a[1], "10") : "10";
        run(c, "([](TF3 *f) { std::ostringstream o; double x, y, z, sx = 0, sy = 0, sz = 0; long n = " + n + ";"
             + " for (long k = 0; k < n; ++k) { f->GetRandom3(x, y, z); if (k < 10) o << x << \"  \" << y << \"  \" << z << \"\\n\";"
             + " sx += x; sy += y; sz += z; } o << n << \" drawn, mean (\" << sx / n << \", \" << sy / n << \", \" << sz / n << \")\";"
             + " return o.str(); }(" + fn("TF3", a[0]) + "))");
    }

    public static void tf3Draw(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf3 draw");
        if (a.length < 1) {
            Handlers.usage(":root tf3 draw <name> [option]");
            return;
        }
        run(c, "(" + fn("TF3", a[0]) + "->Draw(" + q(a.length > 1 ? a[1] : "") + "), std::string(\"drawn\"))");
    }

    /* ------------------------------------------------------------------ */
    /* TF12: a TF2 seen along one axis                                     */
    /* ------------------------------------------------------------------ */

    /** f(x, y0) or f(x0, y): the slice of a TF2 at a fixed value of the other variable. */
    public static void tf12New(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 new");
        if (a.length < 3) {
            Handlers.usage(":root tf12 new <name> <tf2> <fixed-value> [x|y]");
            return;
        }
        final String axis = a.length > 3 && a[3].equalsIgnoreCase("y") ? "y" : "x";
        run(c, "([](TF2 *g) { TF12 *f = new TF12(" + q(a[0]) + ", g, " + num(a[2], "0") + ", \"" + axis + "\");"
             + " std::ostringstream o; o << f->GetName() << \"  TF12 of \" << g->GetName() << \" along " + axis
             + ", the other at \" << f->GetXY() << \"  [\" << f->GetXmin() << \", \" << f->GetXmax() << \"]\";"
             + " return o.str(); }(" + fn("TF2", a[1]) + "))");
    }

    public static void tf12Set(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 set");
        if (a.length < 2) {
            Handlers.usage(":root tf12 set <name> <fixed-value>");
            return;
        }
        run(c, "(" + fn("TF12", a[0]) + "->SetXY(" + num(a[1], "0") + "), std::string(\"moved to "
             + a[1] + "\"))");
    }

    public static void tf12Eval(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 eval");
        if (a.length < 2) {
            Handlers.usage(":root tf12 eval <name> <t>");
            return;
        }
        run(c, fn("TF12", a[0]) + "->Eval(" + num(a[1], "0") + ")");
    }

    public static void tf12Integral(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 integral");
        if (a.length < 1) {
            Handlers.usage(":root tf12 integral <name> [a b]");
            return;
        }
        run(c, "([](TF12 *f) { return f->Integral(" + (a.length > 2 ? num(a[1], "0") + ", " + num(a[2], "1")
             : "f->GetXmin(), f->GetXmax()") + "); }(" + fn("TF12", a[0]) + "))");
    }

    public static void tf12Draw(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 draw");
        if (a.length < 1) {
            Handlers.usage(":root tf12 draw <name> [option]");
            return;
        }
        run(c, "(" + fn("TF12", a[0]) + "->Draw(" + q(a.length > 1 ? a[1] : "") + "), std::string(\"drawn\"))");
    }

    /**
     * A TF2 cut into n slices: for each fixed value, the slice's integral, its
     * peak and where the peak sits. A ridge that drifts, a width that opens,
     * a peak that splits -- what a 2D model does is read off this table.
     */
    public static void tf12Slices(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tf12 slices");
        if (a.length < 1) {
            Handlers.usage(":root tf12 slices <tf2> [x|y] [n]");
            return;
        }
        final String axis = a.length > 1 && a[1].equalsIgnoreCase("y") ? "y" : "x";
        final String n = a.length > 2 ? num(a[2], "10") : "10";
        run(c, "([](TF2 *g) { std::ostringstream o; int n = " + n + "; bool alongX = " + axis.equals("x")
             + "; double lo = alongX ? g->GetYmin() : g->GetXmin(), hi = alongX ? g->GetYmax() : g->GetXmax();"
             + " o << std::left << std::setw(14) << (alongX ? \"y fixed\" : \"x fixed\") << std::setw(14) << \"integral\""
             + " << std::setw(14) << \"peak\" << \"peak at\\n\";"
             + " TF12 s(\"__sphere_slice\", g, lo, alongX ? \"x\" : \"y\");"
             + " for (int k = 0; k < n; ++k) { double v = n == 1 ? lo : lo + (hi - lo) * k / (n - 1); s.SetXY(v);"
             + " o << std::setw(14) << v << std::setw(14) << s.Integral(s.GetXmin(), s.GetXmax())"
             + " << std::setw(14) << s.GetMaximum() << s.GetMaximumX() << \"\\n\"; } return o.str(); }("
             + fn("TF2", a[0]) + "))");
    }

    /* ------------------------------------------------------------------ */
    /* TF1Convolution and TF1NormSum                                       */
    /* ------------------------------------------------------------------ */

    /** A shape folded with a resolution: (f1 * f2)(x), as a TF1 ready to fit. */
    public static void tfConvNew(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tfconv new");
        if (a.length < 5) {
            Handlers.usage(":root tfconv new <name> <f1> <f2> <xmin> <xmax> [nofft] [points]");
            return;
        }
        final boolean fft = !(a.length > 5 && a[5].equalsIgnoreCase("nofft"));
        final String points = a.length > 6 ? num(a[6], "1000") : (a.length > 5 && isNum(a[5]) ? a[5] : "1000");
        run(c, "([](TF1 *f1, TF1 *f2) { TF1Convolution *conv = new TF1Convolution(f1, f2, "
             + num(a[3], "0") + ", " + num(a[4], "1") + ", " + fft + "); conv->SetNofPointsFFT(" + points + ");"
             + " TF1 *f = new TF1(" + q(a[0]) + ", *conv, " + num(a[3], "0") + ", " + num(a[4], "1")
             + ", conv->GetNpar()); for (int k = 0; k < conv->GetNpar(); ++k) {"
             + " f->SetParName(k, TString(conv->GetParName(k)).Data()); }"
             + " int k = 0; for (int j = 0; j < f1->GetNpar(); ++j) f->SetParameter(k++, f1->GetParameter(j));"
             + " for (int j = 0; j < f2->GetNpar(); ++j) if (k < f->GetNpar()) f->SetParameter(k++, f2->GetParameter(j));"
             + " std::ostringstream o; o << f->GetName() << \" = \" << f1->GetName() << \" (x) \" << f2->GetName()"
             + " << (" + fft + " ? \"  by FFT\" : \"  by numerical integration\") << \"  npar=\" << f->GetNpar();"
             + " for (int j = 0; j < f->GetNpar(); ++j) o << \"\\n  p\" << j << \" \" << f->GetParName(j) << \" = \" << f->GetParameter(j);"
             + " return o.str(); }(" + fn("TF1", a[1]) + ", " + fn("TF1", a[2]) + "))");
    }

    /**
     * A normalized sum: N1 f1 + N2 f2 + ..., each fi normalized, so the
     * coefficients are yields and their fit errors are the yields' errors.
     */
    public static void tfNormSumNew(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root tfnormsum new");
        if (a.length < 3) {
            Handlers.usage(":root tfnormsum new <name> <f1>[:yield] <f2>[:yield] [more...]");
            return;
        }
        final StringBuilder funcs = new StringBuilder();
        final StringBuilder coeffs = new StringBuilder();
        for (int k = 1; k < a.length; k++) {
            final String[] part = a[k].split(":", 2);
            if (funcs.length() > 0) {
                funcs.append(", ");
                coeffs.append(", ");
            }
            funcs.append(fn("TF1", part[0]));
            coeffs.append(part.length > 1 ? num(part[1], "1") : "1");
        }
        run(c, "([]() { std::vector<TF1 *> fs = {" + funcs + "}; std::vector<double> cs = {" + coeffs + "};"
             + " TF1NormSum *sum = new TF1NormSum(fs, cs); double lo = fs[0]->GetXmin(), hi = fs[0]->GetXmax();"
             + " TF1 *f = new TF1(" + q(a[0]) + ", *sum, lo, hi, sum->GetNpar());"
             + " f->SetParameters(sum->GetParameters().data());"
             + " for (int k = 0; k < sum->GetNpar(); ++k) f->SetParName(k, TString(sum->GetParName(k)).Data());"
             + " std::ostringstream o; o << f->GetName() << \"  normalized sum of \" << fs.size() << \" shapes, npar=\" << f->GetNpar();"
             + " for (int k = 0; k < f->GetNpar(); ++k) o << \"\\n  p\" << k << \" \" << f->GetParName(k) << \" = \" << f->GetParameter(k);"
             + " return o.str(); }())");
    }

    /* ------------------------------------------------------------------ */
    /* TCutG                                                               */
    /* ------------------------------------------------------------------ */

    /** A polygon in the plane of two tree variables, usable as a cut by name. */
    public static void cutgNew(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root cutg new");
        if (a.length < 9 || (a.length - 3) % 2 != 0) {
            Handlers.usage(":root cutg new <name> <varx> <vary> x1 y1 x2 y2 x3 y3 [...]");
            return;
        }
        final int n = (a.length - 3) / 2;
        final StringBuilder pts = new StringBuilder();
        for (int k = 0; k < n; k++) {
            pts.append("g->SetPoint(").append(k).append(", ").append(num(a[3 + 2 * k], "0"))
               .append(", ").append(num(a[4 + 2 * k], "0")).append("); ");
        }
        // Closed by repeating the first point, as TCutG::IsInside expects.
        pts.append("g->SetPoint(").append(n).append(", ").append(num(a[3], "0")).append(", ")
           .append(num(a[4], "0")).append("); ");
        run(c, "([]() { TCutG *old = (TCutG *) gROOT->GetListOfSpecials()->FindObject(" + q(a[0]) + ");"
             + " if (old) { gROOT->GetListOfSpecials()->Remove(old); delete old; }"
             + " TCutG *g = new TCutG(" + q(a[0]) + ", " + (n + 1) + "); g->SetVarX(" + q(a[1]) + "); g->SetVarY("
             + q(a[2]) + "); " + pts + " std::ostringstream o; o << g->GetName() << \": \" << " + n
             + " << \" corners in (" + Handlers.lit(a[1]) + ", " + Handlers.lit(a[2])
             + "), area \" << g->Area(); return o.str(); }())");
    }

    public static void cutgInside(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root cutg inside");
        if (a.length < 3) {
            Handlers.usage(":root cutg inside <name> <x> <y>");
            return;
        }
        run(c, "(" + fn("TCutG", a[0]) + "->IsInside(" + num(a[1], "0") + ", " + num(a[2], "0")
             + ") ? std::string(\"inside\") : std::string(\"outside\"))");
    }

    public static void cutgArea(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root cutg area");
        if (a.length < 1) {
            Handlers.usage(":root cutg area <name>");
            return;
        }
        run(c, "([](TCutG *g) { double cx = 0, cy = 0; g->Centroid(cx, cy); std::ostringstream o;"
             + " o << \"area \" << g->Area() << \", centroid (\" << cx << \", \" << cy << \")\"; return o.str(); }("
             + fn("TCutG", a[0]) + "))");
    }

    /** How many entries of a tree fall inside, alone and under an extra cut. */
    public static void cutgCount(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root cutg count");
        if (a.length < 2) {
            Handlers.usage(":root cutg count <tree> <cutg> [extra selection]");
            return;
        }
        final String sel = a.length > 2 ? a[1] + " && (" + Handlers.join(a, 2) + ")" : a[1];
        run(c, "([](TTree *t) { Long64_t all = t->GetEntries(); Long64_t in = t->GetEntries(" + q(sel) + ");"
             + " std::ostringstream o; o << in << \" of \" << all << \" entries inside (\""
             + " << (all > 0 ? 100.0 * in / all : 0.0) << \" %)\"; return o.str(); }(" + Handlers.obj("TTree", a[0]) + "))");
    }

    public static void cutgDraw(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root cutg draw");
        if (a.length < 1) {
            Handlers.usage(":root cutg draw <name>");
            return;
        }
        run(c, "(" + fn("TCutG", a[0]) + "->Draw(\"L same\"), std::string(\"drawn\"))");
    }

    /* ------------------------------------------------------------------ */
    /* TGraphAsymmErrors                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * An efficiency per bin, pass over total, with intervals that stay inside
     * [0, 1]: Clopper-Pearson by default, or any option Divide takes
     * (cl=0.95, b(1,1) mode, w, n, ac, wilson, fc, midp...).
     */
    public static void gaeDivide(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root gae divide");
        if (a.length < 3) {
            Handlers.usage(":root gae divide <name> <pass-hist> <total-hist> [option]");
            return;
        }
        final String option = a.length > 3 ? Handlers.join(a, 3) : "cp";
        run(c, Handlers.keep(a[0], "TGraphAsymmErrors", "([](TH1 *p, TH1 *t) { TGraphAsymmErrors *g ="
             + " new TGraphAsymmErrors(); g->Divide(p, t, " + q(option) + "); g->SetName(" + q(a[0])
             + "); return g; }(" + Handlers.obj("TH1", a[1]) + ", " + Handlers.obj("TH1", a[2]) + "))"));
    }

    public static void gaeNew(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root gae new");
        if (a.length < 2) {
            Handlers.usage(":root gae new <name> <points>");
            return;
        }
        run(c, Handlers.keep(a[0], "TGraphAsymmErrors", "new TGraphAsymmErrors(" + Handlers.asInt(a[1], 1) + ")"));
    }

    public static void gaeSet(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root gae set");
        if (a.length < 8) {
            Handlers.usage(":root gae set <name> <i> <x> <y> <exlow> <exhigh> <eylow> <eyhigh>");
            return;
        }
        run(c, "([](TGraphAsymmErrors *g) { g->SetPoint(" + a[1] + ", " + a[2] + ", " + a[3] + "); g->SetPointError("
             + a[1] + ", " + a[4] + ", " + a[5] + ", " + a[6] + ", " + a[7] + "); return std::string(\"set\"); }("
             + Handlers.held(a[0], "TGraphAsymmErrors") + "))");
    }

    public static void gaePrint(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root gae print");
        if (a.length < 1) {
            Handlers.usage(":root gae print <name>");
            return;
        }
        run(c, "([](TGraphAsymmErrors *g) { std::ostringstream o; o << std::left << std::setw(12) << \"x\""
             + " << std::setw(12) << \"y\" << std::setw(12) << \"-ey\" << \"+ey\\n\";"
             + " for (int k = 0; k < g->GetN(); ++k) o << std::setw(12) << g->GetPointX(k) << std::setw(12)"
             + " << g->GetPointY(k) << std::setw(12) << g->GetErrorYlow(k) << g->GetErrorYhigh(k) << \"\\n\";"
             + " return o.str(); }(" + Handlers.held(a[0], "TGraphAsymmErrors") + "))");
    }

    public static void gaeDraw(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root gae draw");
        if (a.length < 1) {
            Handlers.usage(":root gae draw <name> [option]");
            return;
        }
        run(c, "(" + Handlers.held(a[0], "TGraphAsymmErrors") + "->Draw(" + q(a.length > 1 ? a[1] : "AP")
             + "), std::string(\"drawn\"))");
    }

    /* ------------------------------------------------------------------ */
    /* TGraphSmooth                                                        */
    /* ------------------------------------------------------------------ */

    /** A smoothed copy of a graph: kernel, LOWESS or Friedman's super smoother. */
    public static void graphSmooth(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root graph smooth");
        if (a.length < 3) {
            Handlers.usage(":root graph smooth <graph> <new-name> <kern|lowess|supsmu> [bandwidth|span|bass]");
            return;
        }
        final String how = a[2].toLowerCase(java.util.Locale.ROOT);
        final String call = switch (how) {
            case "kern", "kernel" -> "s->SmoothKern(g, \"normal\", " + (a.length > 3 ? num(a[3], "0.5") : "0.5") + ")";
            case "supsmu", "super" -> "s->SmoothSuper(g, \"\", " + (a.length > 3 ? num(a[3], "0") : "0") + ")";
            default -> "s->SmoothLowess(g, \"\", " + (a.length > 3 ? num(a[3], "0.67") : "0.67") + ")";
        };
        run(c, Handlers.keep(a[1], "TGraph", "([](TGraph *g) { TGraphSmooth *s = new TGraphSmooth(\"__sphere_smooth\");"
             + " TGraph *r = (TGraph *) " + call + "->Clone(" + q(a[1]) + "); delete s; return r; }("
             + anyGraph(a[0]) + "))"));
    }

    /* ------------------------------------------------------------------ */
    /* TH2Poly                                                             */
    /* ------------------------------------------------------------------ */

    public static void polyNew(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root poly new");
        if (a.length < 5) {
            Handlers.usage(":root poly new <name> <xmin> <xmax> <ymin> <ymax> [honeycomb-size]");
            return;
        }
        String extra = "";
        if (a.length > 5) {
            extra = " double s = " + num(a[5], "1") + "; int nx = (int) ((" + a[2] + " - " + a[1]
                  + ") / s) + 1, ny = (int) ((" + a[4] + " - " + a[3] + ") / s) + 1;"
                  + " h->Honeycomb(" + a[1] + ", " + a[3] + ", s, nx, ny);";
        }
        run(c, "([]() { TH2Poly *h = new TH2Poly(" + q(a[0]) + ", " + q(a[0]) + ", " + num(a[1], "0") + ", "
             + num(a[2], "1") + ", " + num(a[3], "0") + ", " + num(a[4], "1") + ");" + extra
             + " return std::string(h->GetName()) + \"  \" + std::to_string(h->GetNumberOfBins()) + \" bins\"; }())");
    }

    /** One bin, as the polygon through the corners given. */
    public static void polyBin(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root poly bin");
        if (a.length < 7 || (a.length - 1) % 2 != 0) {
            Handlers.usage(":root poly bin <name> x1 y1 x2 y2 x3 y3 [...]");
            return;
        }
        final int n = (a.length - 1) / 2;
        final StringBuilder xs = new StringBuilder();
        final StringBuilder ys = new StringBuilder();
        for (int k = 0; k < n; k++) {
            xs.append(k > 0 ? ", " : "").append(num(a[1 + 2 * k], "0"));
            ys.append(k > 0 ? ", " : "").append(num(a[2 + 2 * k], "0"));
        }
        run(c, "([](TH2Poly *h) { double x[] = {" + xs + "}; double y[] = {" + ys + "};"
             + " int b = h->AddBin(" + n + ", x, y); return std::string(\"bin \") + std::to_string(b); }("
             + fn("TH2Poly", a[0]) + "))");
    }

    public static void polyFill(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root poly fill");
        if (a.length < 3) {
            Handlers.usage(":root poly fill <name> <x> <y> [weight]");
            return;
        }
        run(c, fn("TH2Poly", a[0]) + "->Fill(" + num(a[1], "0") + ", " + num(a[2], "0") + ", "
             + (a.length > 3 ? num(a[3], "1") : "1") + ")");
    }

    /* ------------------------------------------------------------------ */
    /* TH2 slices                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Fits every x slice of a TH2 along y (FitSlicesY) and hands back the
     * mean and width per slice: the resolution curve of a detector study.
     */
    public static void histFitSlices(String i, CommandExecutionContext c) {
        final String[] a = w(i, ":root hist fitslices");
        if (a.length < 1) {
            Handlers.usage(":root hist fitslices <th2> [function=gaus] [min-entries]");
            return;
        }
        final String func = a.length > 1 ? a[1] : "gaus";
        final String cut = a.length > 2 ? num(a[2], "0") : "0";
        run(c, "([](TH2 *h) { TF1 *f = (TF1 *) gROOT->GetFunction(" + q(func) + "); if (!f) f = new TF1(\"__sphere_slice_f\", "
             + q(func) + ");"
             + " h->FitSlicesY(f, 0, -1, " + cut + ", \"QNR\"); std::string base = h->GetName();"
             + " TH1 *mean = (TH1 *) gDirectory->Get((base + \"_1\").c_str());"
             + " TH1 *sigma = (TH1 *) gDirectory->Get((base + \"_2\").c_str()); std::ostringstream o;"
             + " if (!mean) return std::string(\"no slice could be fitted\");"
             + " o << std::left << std::setw(12) << \"x\" << std::setw(24) << \"mean\" << \"width\\n\";"
             + " for (int b = 1; b <= mean->GetNbinsX(); ++b) { if (mean->GetBinError(b) == 0) continue;"
             + " o << std::setw(12) << mean->GetBinCenter(b) << std::setw(24)"
             + " << (std::to_string(mean->GetBinContent(b)) + \" +- \" + std::to_string(mean->GetBinError(b)));"
             + " if (sigma) o << sigma->GetBinContent(b) << \" +- \" << sigma->GetBinError(b); o << \"\\n\"; }"
             + " o << \"histograms \" << base << \"_0 (constant), _1 (mean), _2 (width), _chi2\"; return o.str(); }("
             + fn("TH2", a[0]) + "))");
    }

    /* ------------------------------------------------------------------ */
    /* A general minimizer                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Minimizes any formula of x, y, z and t, with Minuit2 by
     * default. The variables are found in the formula itself; the starting
     * point is optional. Errors come from the Hessian, so the answer can be
     * read as a fit when the formula is a chi-square or a -log L.
     */
    public static void minimize(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root minimize");
        if (a.isBlank()) {
            Handlers.usage(":root minimize <formula> [start=v1,v2,...] [algo=Minuit2|Migrad|Simplex|GSLMultiMin|Genetic]");
            return;
        }
        String formula = a;
        String start = "";
        String algo = "Minuit2";
        final java.util.regex.Matcher s = java.util.regex.Pattern.compile("\\bstart=([^\\s]+)").matcher(formula);
        if (s.find()) {
            start = s.group(1);
            formula = formula.replace(s.group(0), "");
        }
        final java.util.regex.Matcher al = java.util.regex.Pattern.compile("\\balgo=([^\\s]+)").matcher(formula);
        if (al.find()) {
            algo = al.group(1);
            formula = formula.replace(al.group(0), "");
        }
        formula = formula.trim();

        // x, y, z, t become the variables of a TFormula: x[0], x[1], x[2], x[3].
        final java.util.List<String> vars = new java.util.ArrayList<>();
        for (String v : new String[] {"x", "y", "z", "t"}) {
            if (java.util.regex.Pattern.compile("(?<![A-Za-z_\\.:])" + v + "(?![A-Za-z_0-9])")
                    .matcher(formula).find()) {
                vars.add(v);
            }
        }
        if (vars.isEmpty()) {
            vars.add("x");
        }
        final String type = algo.equalsIgnoreCase("Migrad") || algo.equalsIgnoreCase("Simplex")
            ? "Minuit2\", \"" + algo : algo;
        final StringBuilder setup = new StringBuilder();
        final String[] starts = start.isEmpty() ? new String[0] : start.split(",");
        for (int k = 0; k < vars.size(); k++) {
            setup.append("m->SetVariable(").append(k).append(", \"").append(vars.get(k)).append("\", ")
                 .append(k < starts.length ? num(starts[k], "0") : "0").append(", 0.1); ");
        }
        run(c, "([]() { TFormula form(\"__sphere_min\", " + q(formula) + "); if (!form.IsValid())"
             + " return std::string(\"the formula does not compile\"); int nd = " + vars.size() + ";"
             + " ROOT::Math::Functor fn([&form](const double *p) { double v[4] = {0, 0, 0, 0};"
             + " for (int k = 0; k < 4 && k < " + vars.size() + "; ++k) v[k] = p[k]; return form.Eval(v[0], v[1], v[2], v[3]); }, nd);"
             + " std::unique_ptr<ROOT::Math::Minimizer> m(ROOT::Math::Factory::CreateMinimizer(\"" + type + "\"));"
             + " if (!m) return std::string(\"no minimizer called " + Handlers.lit(algo) + "\");"
             + " m->SetMaxFunctionCalls(100000); m->SetTolerance(1e-6); m->SetPrintLevel(0); m->SetFunction(fn); "
             + setup + " bool ok = m->Minimize(); m->Hesse(); const double *xs = m->X(); const double *es = m->Errors();"
             + " std::ostringstream o; o << (ok ? \"converged\" : \"did NOT converge\") << \"  f = \" << m->MinValue()"
             + " << \"  edm \" << m->Edm() << \"  calls \" << m->NCalls();"
             + " for (int k = 0; k < nd; ++k) {"
             + " o << \"\\n  \" << " + varNames(vars) + "[k] << \" = \" << xs[k]; if (es) o << \" +- \" << es[k]; }"
             + "return o.str(); }())");
    }

    private static String varNames(java.util.List<String> vars) {
        final StringBuilder out = new StringBuilder("std::vector<std::string>{");
        for (int k = 0; k < vars.size(); k++) {
            out.append(k > 0 ? ", " : "").append('"').append(vars.get(k)).append('"');
        }
        return out.append('}').toString();
    }

    private static boolean isNum(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }
}
