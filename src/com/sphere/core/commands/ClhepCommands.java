package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.InProcessEngine;
import com.sphere.core.clhep.Clhep;
import com.sphere.core.clhep.ClhepCitations;
import com.sphere.core.clhep.Quantities;
import com.sphere.core.clhep.genfun.AbsFunction;
import com.sphere.core.clhep.genfun.AdaptiveRKStepper;
import com.sphere.core.clhep.genfun.Airy;
import com.sphere.core.clhep.genfun.Argument;
import com.sphere.core.clhep.genfun.AssociatedLaguerre;
import com.sphere.core.clhep.genfun.AssociatedLegendre;
import com.sphere.core.clhep.genfun.Bessel;
import com.sphere.core.clhep.genfun.ButcherTableau;
import com.sphere.core.clhep.genfun.CumulativeChiSquare;
import com.sphere.core.clhep.genfun.DefiniteIntegral;
import com.sphere.core.clhep.genfun.EllipticIntegral;
import com.sphere.core.clhep.genfun.EmbeddedRKStepper;
import com.sphere.core.clhep.genfun.Erf;
import com.sphere.core.clhep.genfun.ExtendedButcherTableau;
import com.sphere.core.clhep.genfun.F1D;
import com.sphere.core.clhep.genfun.FunctionNumDeriv;
import com.sphere.core.clhep.genfun.Gamma;
import com.sphere.core.clhep.genfun.Gsl;
import com.sphere.core.clhep.genfun.HermitePolynomial;
import com.sphere.core.clhep.genfun.IncompleteGamma;
import com.sphere.core.clhep.genfun.Landau;
import com.sphere.core.clhep.genfun.Legendre;
import com.sphere.core.clhep.genfun.LogGamma;
import com.sphere.core.clhep.genfun.RKIntegrator;
import com.sphere.core.clhep.genfun.RelativisticBWDistribution;
import com.sphere.core.clhep.genfun.SimpleRKStepper;
import com.sphere.core.clhep.genfun.SphericalBessel;
import com.sphere.core.clhep.genfun.SphericalNeumann;
import com.sphere.core.clhep.genfun.StepDoublingRKStepper;
import com.sphere.core.clhep.genfun.VoigtProfile;
import com.sphere.core.clhep.geometry.Normal3D;
import com.sphere.core.clhep.geometry.Point3D;
import com.sphere.core.clhep.geometry.ReflectX3D;
import com.sphere.core.clhep.geometry.ReflectY3D;
import com.sphere.core.clhep.geometry.ReflectZ3D;
import com.sphere.core.clhep.geometry.Rotate3D;
import com.sphere.core.clhep.geometry.RotateX3D;
import com.sphere.core.clhep.geometry.RotateY3D;
import com.sphere.core.clhep.geometry.RotateZ3D;
import com.sphere.core.clhep.geometry.Scale3D;
import com.sphere.core.clhep.geometry.Transform3D;
import com.sphere.core.clhep.geometry.Translate3D;
import com.sphere.core.clhep.geometry.Vector3D;
import com.sphere.core.clhep.matrix.HepGenMatrix;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.matrix.MatrixLinear;
import com.sphere.core.clhep.random.DRand48Engine;
import com.sphere.core.clhep.random.DualRand;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.Hurd160Engine;
import com.sphere.core.clhep.random.Hurd288Engine;
import com.sphere.core.clhep.random.MTwistEngine;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.ProcessState;
import com.sphere.core.clhep.random.RandBinomial;
import com.sphere.core.clhep.random.RandBit;
import com.sphere.core.clhep.random.RandBreitWigner;
import com.sphere.core.clhep.random.RandChiSquare;
import com.sphere.core.clhep.random.RandEngine;
import com.sphere.core.clhep.random.RandExpZiggurat;
import com.sphere.core.clhep.random.RandExponential;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.random.RandGamma;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.random.RandGaussQ;
import com.sphere.core.clhep.random.RandGaussT;
import com.sphere.core.clhep.random.RandGaussZiggurat;
import com.sphere.core.clhep.random.RandLandau;
import com.sphere.core.clhep.random.RandPoisson;
import com.sphere.core.clhep.random.RandPoissonQ;
import com.sphere.core.clhep.random.RandPoissonT;
import com.sphere.core.clhep.random.RandSkewNormal;
import com.sphere.core.clhep.random.RandStudentT;
import com.sphere.core.clhep.random.RanecuEngine;
import com.sphere.core.clhep.random.Ranlux64Engine;
import com.sphere.core.clhep.random.RanluxEngine;
import com.sphere.core.clhep.random.RanluxppEngine;
import com.sphere.core.clhep.random.RanshiEngine;
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.clhep.units.PhysicalConstants;
import com.sphere.core.clhep.validation.ClhepValidation;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepAxisAngle;
import com.sphere.core.clhep.vector.HepEulerAngles;
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.hepmc3.cxx.NativeMath;
import com.sphere.core.hepmc3.cxx.StdStreams;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * CLHEP, the Class Library for High Energy Physics, in Java: the {@code :clhep} commands, on Sphere's own
 * port of CLHEP 2.4.7.2, which gives the C++'s bits ({@code :clhep validate} compares it with the C++).
 *
 * <p>Every number a command takes is an expression of CLHEP's Evaluator, with CLHEP's units and physical
 * constants ({@code 10*GeV}, {@code 30*deg}, {@code c_light}, {@code hbarc}) and the variables
 * {@code :clhep let} defines. What a command prints is in CLHEP's units (mm, ns, MeV, eplus) and in the unit
 * that reads best for its dimension, which is found by evaluating the quantity in other systems of units
 * ({@link Quantities}): {@code :clhep convert} refuses to turn an energy into a length. CLHEP's cout and
 * cerr come to the console.
 */
public final class ClhepCommands {

    /** The calculator of the session: the units, the constants and the variables defined. */
    private static Quantities quantities;

    /** CLHEP's cout and cerr, on the console. */
    private static final StdStreams CONSOLE = new StdStreams(AppLogger::raw, AppLogger::warn);

    private ClhepCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static synchronized Quantities quantities() {
        if (quantities == null) quantities = new Quantities();
        return quantities;
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                        */
    /* ------------------------------------------------------------------ */

    /** A command body, run with CLHEP's streams on the console. */
    @FunctionalInterface
    private interface Body {
        void run(Args a) throws Exception;
    }

    private static void reg(String sub, String description, Body body) {
        final String name = sub.isEmpty() ? ":clhep" : ":clhep " + sub;
        CommandDefinitions.register(name, description, (i, c) -> {
            final Args a = new Args(Handlers.args(i, name));
            try {
                StdStreams.with(CONSOLE, () -> {
                    body.run(a);
                    return null;
                });
            } catch (Exception e) {
                AppLogger.error(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    static void register() {
        reg("", "CLHEP in Java: units, vectors, geometry, matrices, random numbers, the Evaluator, GenericFunctions",
            a -> help());
        reg("help", "What the CLHEP commands do", a -> help());
        reg("version", "The version (CLHEP Java Sphere); references under Citations in the console menu", a ->
            AppLogger.raw(Clhep.VERSION_LINE));
        reg("eval", "A quantity with CLHEP's units and constants, read in the unit that suits it. Usage: :clhep eval <expression> [in <unit>]  (e.g. eplus*1.5*tesla*2*m*c_light, the p.c of a 2 m track in 1.5 T)",
            ClhepCommands::eval);
        reg("let", "Define a variable for every expression. Usage: :clhep let <name> = <expression>  (alone: the variables)",
            ClhepCommands::let);
        reg("forget", "Forget a variable :clhep let defined. Usage: :clhep forget <name>|--all", ClhepCommands::forget);
        reg("convert", "Convert a quantity to a unit of the same dimension. Usage: :clhep convert <expression> [to] <unit>  (e.g. 1*barn to cm2)",
            ClhepCommands::convert);
        reg("units", "CLHEP's units and physical constants, by kind, with their values. Usage: :clhep units [text]",
            ClhepCommands::units);
        reg("vector", "A 3-vector or a four-vector: magnitude, pT, eta, rapidity, mass..., then boosted or rotated in order. Usage: :clhep vector <x> <y> <z> [<t|E>] [--boost bx by bz] [--rotz a] [--rotate a ax ay az] [--plus ...] [--times s]",
            ClhepCommands::vector);
        reg("rotation", "A rotation from Euler angles or an axis and an angle: its matrix, axis, Euler angles. Usage: :clhep rotation <phi> <theta> <psi> | --axis ax ay az --angle a [--rotx a ...] [--on x y z]",
            ClhepCommands::rotation);
        reg("transform", "Place a point with HepGeom transformations applied in order, and decompose them. Usage: :clhep transform <x> <y> <z> [--rotz a] [--rotate a ax ay az] [--translate dx dy dz] [--scale s] [--reflect x|y|z] [--invert]",
            ClhepCommands::transform);
        reg("random", "Draw from a CLHEP distribution on a CLHEP engine; the moments against the expected ones. Usage: :clhep random <flat|gauss|exp|poisson|binomial|breitwigner|chisquare|gamma|studentt|landau|skewnormal|bit|...> [parameters] [--engine e] [--seed s] [--n N] [--hist]",
            ClhepCommands::random);
        reg("engines", "CLHEP's random engines, and the first numbers each gives as a fresh C++ program would. Usage: :clhep engines [--seed s]",
            ClhepCommands::engines);
        reg("matrix", "A matrix and what CLHEP computes of it. Usage: :clhep matrix <rows: 4 1; 1 3> [det|inverse|eigen|transpose|solve <b>|times <rows>]",
            ClhepCommands::matrix);
        reg("integrate", "The integral of f(x) by GenericFunctions' Romberg integration. Usage: :clhep integrate <f(x)> <a> <b> [--open] [--eps e]",
            ClhepCommands::integrate);
        reg("deriv", "The derivative of f(x) at a point, GenericFunctions' Richardson extrapolation. Usage: :clhep deriv <f(x)> <x0>",
            ClhepCommands::deriv);
        reg("plot", "Draw f(x) (or its derivative) in the Plots tab. Usage: :clhep plot <f(x)> <a> <b> [--points n] [--deriv]",
            ClhepCommands::plot);
        reg("ode", "Solve dy/dt = f(y) with GenericFunctions' Runge-Kutta integrator. Usage: :clhep ode <f1; f2; ...> [--vars y1,y2] [--y0 v1,v2] [--t t1,t2,...] [--stepper adaptive|rk4|euler|midpoint|heuneuler|bogacki|fehlberg|doubling] [--h step] [--tol t] [--plot tmax]",
            ClhepCommands::ode);
        reg("special", "A special function of GenericFunctions (GSL's where CLHEP takes them from GSL). Usage: :clhep special <airy-ai|besselj|legendre|ellint-f|gamma|erf|hermite|voigt|...> <arguments> [--plot a b]",
            ClhepCommands::special);
        reg("validate", "Run the reference programs and CLHEP's own tests, ported, against the C++ outputs. Usage: :clhep validate [name|tests|references|all]",
            ClhepCommands::validate);
        reg("bib", "Write the BibTeX of CLHEP (and of the generators and libraries used). Usage: :clhep bib <file.bib> [--all]",
            ClhepCommands::bib);
    }

    /* ------------------------------------------------------------------ */
    /* Arguments                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * The words after the command (a double-quoted text is one word), then --options, each with the words
     * after it up to the next, in the order typed.
     */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final List<Op> ops = new ArrayList<>();
        final String raw;

        record Op(String name, List<String> values) {
        }

        Args(String text) {
            raw = text;
            Op current = null;
            for (String w : tokens(text)) {
                if (w.startsWith("--") && w.length() > 2 && Character.isLetter(w.charAt(2))) {
                    current = new Op(w.substring(2).toLowerCase(Locale.ROOT), new ArrayList<>());
                    ops.add(current);
                } else if (current != null) {
                    current.values.add(w);
                } else {
                    plain.add(w);
                }
            }
        }

        boolean has(String name) {
            for (Op o : ops) {
                if (o.name.equals(name)) return true;
            }
            return false;
        }

        /** The first value of an option, or the fallback. */
        String opt(String name, String fallback) {
            for (Op o : ops) {
                if (o.name.equals(name)) return o.values.isEmpty() ? fallback : String.join(" ", o.values);
            }
            return fallback;
        }

        String word(int k, String fallback) {
            return k < plain.size() ? plain.get(k) : fallback;
        }
    }

    /** Whitespace-separated words, a double-quoted text being one. */
    static List<String> tokens(String s) {
        final List<String> out = new ArrayList<>();
        final StringBuilder w = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (any) out.add(w.toString());
                w.setLength(0);
                any = false;
            } else {
                w.append(ch);
                any = true;
            }
        }
        if (any) out.add(w.toString());
        return out;
    }

    /** An expression's value in CLHEP's units; an IllegalArgumentException saying where it fails. */
    private static double value(String expression) {
        final Quantities.Value v = quantities().value(expression);
        if (!v.ok()) {
            throw new IllegalArgumentException("cannot evaluate '" + expression + "': " + v.error() + " at '"
                + Quantities.at(expression, v.position()) + "'");
        }
        return v.value();
    }

    private static String num(double v) {
        return Quantities.num(v);
    }

    /** The value, and in the unit that reads best for the dimension of the expression it came from. */
    private static String show(double v, String expression) {
        final Quantities q = quantities();
        final String r = q.readable(v, q.dimension(expression));
        return r.isEmpty() || r.equals(num(v)) ? num(v) : num(v) + "  (" + r + ")";
    }

    private static String show(double v, Quantities.Dimension d) {
        final String r = quantities().readable(v, d);
        return r.isEmpty() ? num(v) : num(v) + "  (" + r + ")";
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static String deg(double radians) {
        return num(radians / com.sphere.core.clhep.units.SystemOfUnits.degree) + " deg";
    }

    /* ------------------------------------------------------------------ */
    /* help                                                                */
    /* ------------------------------------------------------------------ */

    private static void help() {
        AppLogger.result("CLHEP in Java (CLHEP " + Clhep.VERSION + " ported, bit for bit the C++). Every number is an expression with units:");
        AppLogger.raw("  quantities  :clhep eval eplus*1.5*tesla*2*m*c_light | let E = 7*TeV | convert 1*barn to cm2 | units energy");
        AppLogger.raw("  vectors     :clhep vector 3*GeV 4*GeV 0 13*GeV --boost 0 0 0.5 --rotz 30*deg | rotation 30*deg 45*deg 0");
        AppLogger.raw("  geometry    :clhep transform 1*m 0 0 --rotz 90*deg --translate 0 0 5*cm --scale 2   (HepGeom, decomposed)");
        AppLogger.raw("  matrices    :clhep matrix \"4 1; 1 3\" eigen | inverse | det | solve \"1; 2\"");
        AppLogger.raw("  random      :clhep random gauss 0 1 --n 100000 --hist | poisson 3.5 --engine ranlux --seed 7 | engines");
        AppLogger.raw("  functions   :clhep integrate \"exp(-x*x)\" 0 3 | deriv \"sin(x)\" 0.5 | plot \"x*x\" 0 2 | special besselj 1 2.5");
        AppLogger.raw("  equations   :clhep ode \"v; -w*w*x\" --vars x,v --y0 1,0 --t 1,2,3   (after :clhep let w = 2)");
        AppLogger.raw("  trust       :clhep validate | ping | diag      references: Citations > Citation CLHEP, :clhep bib <file.bib>");
        AppLogger.raw("  ':help clhep' gives the full usage of each.");
    }

    /* ------------------------------------------------------------------ */
    /* Quantities                                                          */
    /* ------------------------------------------------------------------ */

    private static void eval(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep eval <expression> [in <unit>]   (e.g. :clhep eval 10*GeV/c_light in GeV)");
            return;
        }
        String expression = String.join(" ", a.plain);
        String unit = null;
        final int in = expression.lastIndexOf(" in ");
        if (in > 0) {
            unit = expression.substring(in + 4).trim();
            expression = expression.substring(0, in).trim();
        }
        final Quantities q = quantities();
        final double v = value(expression);
        final Quantities.Dimension d = q.dimension(expression);
        if (unit != null) {
            inUnit(expression, v, d, unit, a.has("force"));
            return;
        }
        AppLogger.result(expression + " = " + num(v) + "   (in CLHEP's units: mm, ns, MeV, eplus)");
        describe(v, d, expression);
    }

    /** The dimension and the readable value of a quantity. */
    private static void describe(double v, Quantities.Dimension d, String expression) {
        final Quantities q = quantities();
        if (d == null) {
            AppLogger.raw("  dimension: unknown (the value is 0 or not finite)");
        } else if (!Quantities.consistent(d)) {
            AppLogger.warn("  the terms of this expression differ in dimension (a length plus a number, a function of an energy...)");
        } else if (d.dimensionless()) {
            final boolean angle = expression.matches("(?s).*\\b(deg|degree|rad|radian|mrad|milliradian)\\b.*");
            AppLogger.raw("  dimensionless" + (angle ? ": as an angle, " + deg(v) : ""));
        } else {
            final String kind = d.name();
            final String r = q.readable(v, d);
            AppLogger.raw("  " + (r.isEmpty() ? "" : "= " + r + "   ") + (kind.isEmpty() ? "" : kind + " ") + "[" + d.si() + "]");
        }
    }

    /** A quantity read in a unit, the two of one dimension (or --force). */
    private static void inUnit(String expression, double v, Quantities.Dimension d, String unit, boolean force) {
        final Quantities q = quantities();
        final double u = value(unit);
        final Quantities.Dimension du = q.dimension(unit);
        if (!force && Quantities.consistent(d) && Quantities.consistent(du) && !d.sameAs(du)) {
            AppLogger.error("Cannot read " + describeDim(d) + " in " + unit + ", " + describeDim(du)
                + " (--force reads it anyway).");
            return;
        }
        AppLogger.result(expression + " = " + num(v / u) + " " + unit);
    }

    private static String describeDim(Quantities.Dimension d) {
        if (d.dimensionless()) return "a number";
        final String kind = d.name();
        return (kind.isEmpty() ? "a quantity" : "a" + ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "n " : " ") + kind) + " [" + d.si() + "]";
    }

    private static void convert(Args a) {
        final List<String> w = new ArrayList<>(a.plain);
        if (w.size() < 2) {
            Handlers.usage(":clhep convert <expression> [to] <unit>   (e.g. :clhep convert 1*barn to cm2)");
            return;
        }
        final String unit = w.remove(w.size() - 1);
        if (!w.isEmpty() && (w.get(w.size() - 1).equalsIgnoreCase("to") || w.get(w.size() - 1).equalsIgnoreCase("in"))) {
            w.remove(w.size() - 1);
        }
        final String expression = String.join(" ", w);
        final double v = value(expression);
        inUnit(expression, v, quantities().dimension(expression), unit, a.has("force"));
    }

    private static void let(Args a) {
        final Quantities q = quantities();
        if (a.plain.isEmpty()) {
            final Map<String, String> lets = q.lets();
            if (lets.isEmpty()) {
                AppLogger.info("No variable defined: ':clhep let E = 7*TeV' defines one, for every expression.");
                return;
            }
            AppLogger.result(lets.size() + " variable(s):");
            for (Map.Entry<String, String> e : lets.entrySet()) {
                AppLogger.raw(f("  %-12s = %-24s = %s", e.getKey(), e.getValue(), show(q.value(e.getKey()).value(), e.getKey())));
            }
            return;
        }
        final String text = String.join(" ", a.plain);
        final int eq = text.indexOf('=');
        final String name = (eq >= 0 ? text.substring(0, eq) : a.plain.get(0)).trim();
        final String expression = (eq >= 0 ? text.substring(eq + 1) : String.join(" ", a.plain.subList(1, a.plain.size()))).trim();
        if (expression.isEmpty()) {
            Handlers.usage(":clhep let <name> = <expression>");
            return;
        }
        final String error = q.let(name, expression);
        if (error != null) {
            AppLogger.error("Not defined: " + error + ".");
            return;
        }
        AppLogger.result(name + " = " + show(q.value(name).value(), name));
    }

    private static void forget(Args a) {
        final Quantities q = quantities();
        if (a.has("all")) {
            for (String n : q.lets().keySet()) q.forget(n);
            AppLogger.result("Variables forgotten.");
            return;
        }
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep forget <name>|--all");
            return;
        }
        for (String n : a.plain) {
            if (q.forget(n)) AppLogger.result(n + " forgotten.");
            else AppLogger.warn("No variable " + n + " (':clhep let' lists them).");
        }
    }

    private static void units(Args a) {
        final Quantities q = quantities();
        final String filter = String.join(" ", a.plain).toLowerCase(Locale.ROOT);
        final Map<String, List<String>> byKind = new TreeMap<>();
        final List<String> rows = new ArrayList<>();
        int total = 0;
        for (Field field : Quantities.fields()) {
            final String n = field.getName();
            final double v;
            try {
                v = field.getDouble(null);
            } catch (IllegalAccessException e) {
                continue;
            }
            total++;
            final Quantities.Dimension d = q.dimension(n);
            final String kind = d == null ? "" : !Quantities.consistent(d) ? "" : d.dimensionless() ? "number" : d.name().isEmpty() ? "[" + d.si() + "]" : d.name();
            final boolean constant = field.getDeclaringClass() == PhysicalConstants.class;
            if (filter.isEmpty()) {
                byKind.computeIfAbsent((constant ? "constants: " : "") + kind, k -> new ArrayList<>()).add(n);
            } else if (n.toLowerCase(Locale.ROOT).contains(filter) || kind.toLowerCase(Locale.ROOT).contains(filter)) {
                final String r = q.readable(v, d);
                rows.add(f("  %-24s %-22s %s%s", n, num(v), r.isEmpty() || r.equals(num(v)) ? "" : "= " + r + "  ",
                    kind.isEmpty() ? "" : "(" + kind + ")"));
            }
        }
        if (filter.isEmpty()) {
            AppLogger.result(total + " units and physical constants of CLHEP (SystemOfUnits.h, PhysicalConstants.h), in CLHEP's "
                + "units (mm, ns, MeV, eplus); ':clhep units <text>' gives the values:");
            for (Map.Entry<String, List<String>> e : byKind.entrySet()) {
                AppLogger.raw("  " + (e.getKey().isEmpty() ? "other" : e.getKey()) + ": " + String.join(" ", e.getValue()));
            }
            return;
        }
        if (rows.isEmpty()) {
            AppLogger.warn("No unit or constant matches '" + filter + "' (':clhep units' lists them by kind).");
            return;
        }
        AppLogger.result(rows.size() + " match '" + filter + "' (values in CLHEP's units: mm, ns, MeV, eplus):");
        for (String r : rows) AppLogger.raw(r);
    }

    /* ------------------------------------------------------------------ */
    /* Vectors, rotations, placements                                      */
    /* ------------------------------------------------------------------ */

    private static double[] values(List<String> words, int n, String what) {
        if (words.size() < n) throw new IllegalArgumentException(what + " takes " + n + " value(s), " + words.size() + " given");
        final double[] v = new double[n];
        for (int k = 0; k < n; k++) v[k] = value(words.get(k));
        return v;
    }

    private static void vector(Args a) {
        if (a.plain.size() != 3 && a.plain.size() != 4) {
            Handlers.usage(":clhep vector <x> <y> <z> [<t|E>] [--boost bx by bz] [--boostz b] [--rotx a] [--roty a] [--rotz a] [--rotate a ax ay az] [--plus x y z [t]] [--times s]");
            return;
        }
        String dimensionOf = "0";
        for (String c : a.plain) {
            if (value(c) != 0) {
                dimensionOf = c;
                break;
            }
        }
        final Quantities.Dimension d = quantities().dimension(dimensionOf);
        if (a.plain.size() == 3) {
            final double[] c = values(a.plain, 3, "a 3-vector");
            final Hep3Vector v = new Hep3Vector(c[0], c[1], c[2]);
            for (Args.Op op : a.ops) {
                switch (op.name()) {
                    case "rotx" -> v.rotateX(values(op.values(), 1, "--rotx")[0]);
                    case "roty" -> v.rotateY(values(op.values(), 1, "--roty")[0]);
                    case "rotz" -> v.rotateZ(values(op.values(), 1, "--rotz")[0]);
                    case "rotate" -> {
                        final double[] r = values(op.values(), 4, "--rotate (angle, then the axis)");
                        v.rotate(r[0], new Hep3Vector(r[1], r[2], r[3]));
                    }
                    case "plus" -> {
                        final double[] p = values(op.values(), 3, "--plus");
                        v.plusAssign(new Hep3Vector(p[0], p[1], p[2]));
                    }
                    case "times" -> v.timesAssign(values(op.values(), 1, "--times")[0]);
                    default -> throw new IllegalArgumentException("--" + op.name() + " does not apply to a 3-vector (--rotx, --roty, --rotz, --rotate, --plus, --times)");
                }
            }
            AppLogger.result("Hep3Vector (" + num(v.x()) + ", " + num(v.y()) + ", " + num(v.z()) + ")" + unitNote(d));
            AppLogger.raw("  mag      " + show(v.mag(), d));
            AppLogger.raw("  perp     " + show(v.perp(), d));
            AppLogger.raw("  theta    " + deg(v.theta()) + "   cos(theta) " + num(v.cosTheta()));
            AppLogger.raw("  phi      " + deg(v.phi()));
            if (v.perp() > 0) AppLogger.raw("  eta      " + num(v.eta()));
            if (v.mag() > 0) {
                final Hep3Vector u = v.unit();
                AppLogger.raw("  unit     (" + num(u.x()) + ", " + num(u.y()) + ", " + num(u.z()) + ")");
            }
            return;
        }
        final double[] c = values(a.plain, 4, "a four-vector");
        final HepLorentzVector p = new HepLorentzVector(c[0], c[1], c[2], c[3]);
        for (Args.Op op : a.ops) {
            switch (op.name()) {
                case "boost" -> {
                    final double[] b = values(op.values(), 3, "--boost");
                    p.boost(b[0], b[1], b[2]);
                }
                case "boostx" -> p.boostX(values(op.values(), 1, "--boostx")[0]);
                case "boosty" -> p.boostY(values(op.values(), 1, "--boosty")[0]);
                case "boostz" -> p.boostZ(values(op.values(), 1, "--boostz")[0]);
                case "rotx" -> p.rotateX(values(op.values(), 1, "--rotx")[0]);
                case "roty" -> p.rotateY(values(op.values(), 1, "--roty")[0]);
                case "rotz" -> p.rotateZ(values(op.values(), 1, "--rotz")[0]);
                case "rotate" -> {
                    final double[] r = values(op.values(), 4, "--rotate (angle, then the axis)");
                    p.rotate(r[0], new Hep3Vector(r[1], r[2], r[3]));
                }
                case "plus" -> {
                    final double[] q = values(op.values(), 4, "--plus");
                    p.plusAssign(new HepLorentzVector(q[0], q[1], q[2], q[3]));
                }
                case "times" -> p.timesAssign(values(op.values(), 1, "--times")[0]);
                default -> throw new IllegalArgumentException("--" + op.name() + " does not apply to a four-vector (--boost, --boostz, --rotx, --rotz, --rotate, --plus, --times)");
            }
        }
        AppLogger.result("HepLorentzVector (" + num(p.x()) + ", " + num(p.y()) + ", " + num(p.z()) + "; " + num(p.t()) + ")" + unitNote(d));
        final double m2 = p.m2();
        AppLogger.raw("  m        " + (m2 >= 0 ? show(p.m(), d) : "imaginary: m2 = " + num(m2) + " (spacelike)"));
        AppLogger.raw("  |p|      " + show(p.vect().mag(), d));
        AppLogger.raw("  pT       " + show(p.perp(), d));
        AppLogger.raw("  mT, ET   " + show(p.mt(), d) + ", " + show(p.et(), d));
        if (p.perp() > 0) AppLogger.raw("  eta      " + num(p.eta()));
        if (Math.abs(p.z()) < Math.abs(p.t())) AppLogger.raw("  rapidity " + num(p.rapidity()));
        AppLogger.raw("  phi      " + deg(p.phi()) + "   theta " + deg(p.theta()));
        if (p.t() != 0 && m2 > 0) {
            final Hep3Vector b = p.boostVector();
            AppLogger.raw("  beta     " + num(p.beta()) + "   gamma " + num(p.gamma()) + "   boostVector (" + num(b.x()) + ", "
                + num(b.y()) + ", " + num(b.z()) + ")");
        }
    }

    private static String unitNote(Quantities.Dimension d) {
        if (!Quantities.consistent(d) || d.dimensionless()) return "";
        final String kind = d.name();
        return "   " + (kind.isEmpty() ? "" : kind + " ") + "[" + d.si() + "], in CLHEP's units";
    }

    private static void rotation(Args a) {
        HepRotation r;
        if (a.plain.size() == 3) {
            final double[] e = values(a.plain, 3, "Euler angles");
            r = new HepRotation(e[0], e[1], e[2]);
        } else if (a.plain.isEmpty()) {
            r = new HepRotation();
        } else {
            Handlers.usage(":clhep rotation <phi> <theta> <psi> | --axis ax ay az --angle a [--rotx a] [--roty a] [--rotz a] [--on x y z]");
            return;
        }
        if (a.has("axis") || a.has("angle")) {
            final double[] axis = values(tokens(a.opt("axis", "0 0 1")), 3, "--axis");
            final double angle = value(a.opt("angle", "0"));
            r = new HepRotation(new Hep3Vector(axis[0], axis[1], axis[2]), angle).times(r);
        }
        Hep3Vector on = null;
        for (Args.Op op : a.ops) {
            switch (op.name()) {
                case "rotx" -> r.rotateX(values(op.values(), 1, "--rotx")[0]);
                case "roty" -> r.rotateY(values(op.values(), 1, "--roty")[0]);
                case "rotz" -> r.rotateZ(values(op.values(), 1, "--rotz")[0]);
                case "on" -> {
                    final double[] v = values(op.values(), 3, "--on");
                    on = new Hep3Vector(v[0], v[1], v[2]);
                }
                case "axis", "angle" -> {
                    // taken above
                }
                default -> throw new IllegalArgumentException("--" + op.name() + " is not a rotation option (--axis, --angle, --rotx, --roty, --rotz, --on)");
            }
        }
        AppLogger.result("HepRotation:");
        AppLogger.raw(f("  | %14s %14s %14s |", num(r.xx()), num(r.xy()), num(r.xz())));
        AppLogger.raw(f("  | %14s %14s %14s |", num(r.yx()), num(r.yy()), num(r.yz())));
        AppLogger.raw(f("  | %14s %14s %14s |", num(r.zx()), num(r.zy()), num(r.zz())));
        final HepAxisAngle aa = r.axisAngle();
        final Hep3Vector axis = aa.axis();
        AppLogger.raw("  axis-angle  " + deg(aa.delta()) + " about (" + num(axis.x()) + ", " + num(axis.y()) + ", " + num(axis.z()) + ")");
        final HepEulerAngles euler = r.eulerAngles();
        AppLogger.raw("  Euler (phi, theta, psi)  " + deg(euler.phi()) + ", " + deg(euler.theta()) + ", " + deg(euler.psi())
            + "   (Goldstein's convention, as CLHEP's)");
        AppLogger.raw("  R R^-1 is the identity: " + r.times(r.inverse()).isNear(new HepRotation(), 1e-14)
            + (r.isIdentity() ? "   (R is the identity)" : ""));
        if (on != null) {
            final Hep3Vector v = r.times(on);
            AppLogger.raw("  R (" + num(on.x()) + ", " + num(on.y()) + ", " + num(on.z()) + ") = (" + num(v.x()) + ", " + num(v.y())
                + ", " + num(v.z()) + ")");
        }
    }

    private static void transform(Args a) {
        if (a.plain.size() != 3) {
            Handlers.usage(":clhep transform <x> <y> <z> [--rotx a] [--roty a] [--rotz a] [--rotate a ax ay az] [--translate dx dy dz] [--scale s|sx sy sz] [--reflect x|y|z] [--invert]");
            return;
        }
        final double[] c = values(a.plain, 3, "a point");
        Transform3D t = new Transform3D();
        final List<String> steps = new ArrayList<>();
        for (Args.Op op : a.ops) {
            final Transform3D step;
            switch (op.name()) {
                case "rotx" -> step = new RotateX3D(values(op.values(), 1, "--rotx")[0]);
                case "roty" -> step = new RotateY3D(values(op.values(), 1, "--roty")[0]);
                case "rotz" -> step = new RotateZ3D(values(op.values(), 1, "--rotz")[0]);
                case "rotate" -> {
                    final double[] r = values(op.values(), 4, "--rotate (angle, then the axis)");
                    step = new Rotate3D(r[0], new Vector3D(r[1], r[2], r[3]));
                }
                case "translate" -> {
                    final double[] d = values(op.values(), 3, "--translate");
                    step = new Translate3D(d[0], d[1], d[2]);
                }
                case "scale" -> {
                    if (op.values().size() >= 3) {
                        final double[] s = values(op.values(), 3, "--scale");
                        step = new Scale3D(s[0], s[1], s[2]);
                    } else {
                        step = new Scale3D(values(op.values(), 1, "--scale")[0]);
                    }
                }
                case "reflect" -> {
                    final String axis = op.values().isEmpty() ? "" : op.values().get(0).toLowerCase(Locale.ROOT);
                    step = switch (axis) {
                        case "x" -> new ReflectX3D();
                        case "y" -> new ReflectY3D();
                        case "z" -> new ReflectZ3D();
                        default -> throw new IllegalArgumentException("--reflect takes x, y or z");
                    };
                }
                case "invert" -> step = null;
                default -> throw new IllegalArgumentException("--" + op.name() + " is not a transformation (--rotx, --roty, --rotz, --rotate, --translate, --scale, --reflect, --invert)");
            }
            t = step == null ? t.inverse() : step.times(t);
            steps.add(op.name() + (op.values().isEmpty() ? "" : " " + String.join(" ", op.values())));
        }
        final Quantities.Dimension d = quantities().dimension(firstNonZero(a.plain));
        final Point3D p = t.times(new Point3D(c[0], c[1], c[2]));
        final Vector3D v = t.times(new Vector3D(c[0], c[1], c[2]));
        final Normal3D n = t.times(new Normal3D(c[0], c[1], c[2]));
        AppLogger.result("HepGeom::Transform3D" + (steps.isEmpty() ? " (the identity)" : " of " + String.join(", then ", steps)) + ":");
        AppLogger.raw(f("  | %14s %14s %14s | %14s |", num(t.xx()), num(t.xy()), num(t.xz()), num(t.dx())));
        AppLogger.raw(f("  | %14s %14s %14s | %14s |", num(t.yx()), num(t.yy()), num(t.yz()), num(t.dy())));
        AppLogger.raw(f("  | %14s %14s %14s | %14s |", num(t.zx()), num(t.zy()), num(t.zz()), num(t.dz())));
        AppLogger.raw("  the point    -> (" + num(p.x()) + ", " + num(p.y()) + ", " + num(p.z()) + ")" + unitNote(d));
        AppLogger.raw("  as a vector  -> (" + num(v.x()) + ", " + num(v.y()) + ", " + num(v.z()) + ")   (no translation)");
        AppLogger.raw("  as a normal  -> (" + num(n.x()) + ", " + num(n.y()) + ", " + num(n.z())
            + ")   (by the cofactor matrix, as CLHEP: the inverse transpose times the determinant)");
        final Scale3D scale = new Scale3D();
        final Rotate3D rot = new Rotate3D();
        final Translate3D tr = new Translate3D();
        t.getDecomposition(scale, rot, tr);
        final HepAxisAngle aa = rot.getRotation().axisAngle();
        AppLogger.raw("  = Translate3D(" + num(tr.dx()) + ", " + num(tr.dy()) + ", " + num(tr.dz()) + ") * Rotate3D(" + deg(aa.delta())
            + " about " + num(aa.axis().x()) + " " + num(aa.axis().y()) + " " + num(aa.axis().z()) + ") * Scale3D(" + num(scale.xx())
            + ", " + num(scale.yy()) + ", " + num(scale.zz()) + ")");
    }

    private static String firstNonZero(List<String> components) {
        for (String c : components) {
            if (value(c) != 0) return c;
        }
        return "0";
    }

    /* ------------------------------------------------------------------ */
    /* Random numbers                                                      */
    /* ------------------------------------------------------------------ */

    /** An engine: its name in the commands, CLHEP's class, the citation part, and how it is made. */
    private record Engine(String key, String clhepName, String cite, Supplier<HepRandomEngine> fresh,
                          Function<Long, HepRandomEngine> seeded, String note) {
    }

    private static final List<Engine> ENGINES = List.of(
        new Engine("mixmax", "MixMaxRng", "MixMax", MixMaxRng::new, MixMaxRng::new, "CLHEP's default engine (HepRandom), N=17"),
        new Engine("james", "HepJamesRandom", "", HepJamesRandom::new, HepJamesRandom::new, "Marsaglia-Zaman RANMAR, as F. James wrote it"),
        new Engine("ranecu", "RanecuEngine", "", RanecuEngine::new, s -> new RanecuEngine((int) (long) s), "L'Ecuyer's, its seed an index into its table"),
        new Engine("ranlux", "RanluxEngine", "Ranlux", RanluxEngine::new, s -> new RanluxEngine(s, 3), "Luscher's RANLUX, luxury 3"),
        new Engine("ranlux64", "Ranlux64Engine", "Ranlux64", Ranlux64Engine::new, s -> new Ranlux64Engine(s, 1), "RANLUX on 64 bits, luxury 1"),
        new Engine("ranluxpp", "RanluxppEngine", "Ranlux++", RanluxppEngine::new, RanluxppEngine::new, "RANLUX++, its LCG form"),
        new Engine("mtwist", "MTwistEngine", "MTwist", MTwistEngine::new, MTwistEngine::new, "the Mersenne Twister MT19937"),
        new Engine("dualrand", "DualRand", "", DualRand::new, DualRand::new, "a Tausworthe and an integer congruential, xor'd"),
        new Engine("triplerand", "TripleRand", "", TripleRand::new, TripleRand::new, "Tausworthe, congruential and Hurd 288, xor'd"),
        new Engine("hurd160", "Hurd160Engine", "", Hurd160Engine::new, Hurd160Engine::new, "Hurd's shift-register, 160 bits"),
        new Engine("hurd288", "Hurd288Engine", "", Hurd288Engine::new, Hurd288Engine::new, "Hurd's shift-register, 288 bits"),
        new Engine("ranshi", "RanshiEngine", "", RanshiEngine::new, RanshiEngine::new, "Krebs' spin-flip generator"),
        new Engine("drand48", "DRand48Engine", "", DRand48Engine::new, DRand48Engine::new, "the C library's drand48 (CLHEP's own on Windows)"),
        new Engine("rand", "RandEngine", "", RandEngine::new, RandEngine::new, "the C library's rand() (Microsoft's on Windows)"));

    private static Engine engine(String key) {
        final String k = key.toLowerCase(Locale.ROOT).replace("engine", "").replace("rng", "");
        for (Engine e : ENGINES) {
            if (e.key.equals(k) || e.clhepName.equalsIgnoreCase(key)) return e;
        }
        throw new IllegalArgumentException("no engine '" + key + "' (" + String.join(", ", ENGINES.stream().map(Engine::key).toList()) + ")");
    }

    /** The engine as a fresh C++ program has it: CLHEP's counters, C library and statics new. */
    private static HepRandomEngine make(Engine e, String seed) {
        ProcessState.restart();
        return seed == null ? e.fresh.get() : e.seeded.apply((long) value(seed));
    }

    /** A distribution: what it draws, and its expected mean and variance (NaN when it has none). */
    private record Draws(String name, DoubleSupplier draw, double mean, double variance) {
    }

    private static double param(List<String> p, int k, double fallback) {
        return k < p.size() ? value(p.get(k)) : fallback;
    }

    private static Draws distribution(String name, List<String> p, HepRandomEngine e) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "flat" -> {
                final double a = param(p, 0, 0);
                final double b = param(p, 1, 1);
                final RandFlat d = new RandFlat(e, a, b);
                yield new Draws(f("RandFlat [%s, %s)", num(a), num(b)), d::fire, (a + b) / 2, (b - a) * (b - a) / 12);
            }
            case "gauss", "gaussq", "gausst", "ziggurat" -> {
                final double m = param(p, 0, 0);
                final double s = param(p, 1, 1);
                final String n = name.toLowerCase(Locale.ROOT);
                final DoubleSupplier draw = switch (n) {
                    case "gaussq" -> new RandGaussQ(e, m, s)::fire;
                    case "gausst" -> new RandGaussT(e, m, s)::fire;
                    case "ziggurat" -> new RandGaussZiggurat(e, m, s)::fire;
                    default -> new RandGauss(e, m, s)::fire;
                };
                final String cls = switch (n) {
                    case "gaussq" -> "RandGaussQ";
                    case "gausst" -> "RandGaussT";
                    case "ziggurat" -> "RandGaussZiggurat";
                    default -> "RandGauss";
                };
                yield new Draws(f("%s mean %s, sigma %s", cls, num(m), num(s)), draw, m, s * s);
            }
            case "exp", "exponential" -> {
                final double m = param(p, 0, 1);
                final RandExponential d = new RandExponential(e, m);
                yield new Draws("RandExponential mean " + num(m), d::fire, m, m * m);
            }
            case "expziggurat" -> {
                final double m = param(p, 0, 1);
                final RandExpZiggurat d = new RandExpZiggurat(e, m);
                yield new Draws("RandExpZiggurat mean " + num(m), () -> d.fire(), m, m * m);
            }
            case "poisson", "poissonq", "poissont" -> {
                final double mu = param(p, 0, 1);
                final String n = name.toLowerCase(Locale.ROOT);
                final DoubleSupplier draw = switch (n) {
                    case "poissonq" -> {
                        final RandPoissonQ d = new RandPoissonQ(e, mu);
                        yield () -> d.fire();
                    }
                    case "poissont" -> {
                        final RandPoissonT d = new RandPoissonT(e, mu);
                        yield () -> d.fire();
                    }
                    default -> {
                        final RandPoisson d = new RandPoisson(e, mu);
                        yield () -> d.fire();
                    }
                };
                yield new Draws((n.equals("poissonq") ? "RandPoissonQ" : n.equals("poissont") ? "RandPoissonT" : "RandPoisson")
                    + " mean " + num(mu), draw, mu, mu);
            }
            case "binomial" -> {
                final long n = (long) param(p, 0, 1);
                final double pr = param(p, 1, 0.5);
                final RandBinomial d = new RandBinomial(e, n, pr);
                yield new Draws("RandBinomial n " + n + ", p " + num(pr), d::fire, n * pr, n * pr * (1 - pr));
            }
            case "breitwigner", "bw" -> {
                final double m = param(p, 0, 1);
                final double g = param(p, 1, 0.2);
                final RandBreitWigner d = new RandBreitWigner(e, m, g);
                if (p.size() > 2) {
                    final double cut = value(p.get(2));
                    yield new Draws("RandBreitWigner mean " + num(m) + ", gamma " + num(g) + ", cut " + num(cut), () -> d.fire(m, g, cut),
                        m, Double.NaN);
                }
                yield new Draws("RandBreitWigner mean " + num(m) + ", gamma " + num(g), d::fire, Double.NaN, Double.NaN);
            }
            case "chisquare", "chi2" -> {
                final double n = param(p, 0, 1);
                final RandChiSquare d = new RandChiSquare(e, n);
                yield new Draws("RandChiSquare ndf " + num(n), d::fire, n, 2 * n);
            }
            case "gamma" -> {
                final double k = param(p, 0, 1);
                final double l = param(p, 1, 1);
                final RandGamma d = new RandGamma(e, k, l);
                yield new Draws("RandGamma k " + num(k) + ", lambda " + num(l), d::fire, k / l, k / (l * l));
            }
            case "studentt", "student" -> {
                final double n = param(p, 0, 1);
                final RandStudentT d = new RandStudentT(e, n);
                yield new Draws("RandStudentT ndf " + num(n), d::fire, n > 1 ? 0 : Double.NaN, n > 2 ? n / (n - 2) : Double.NaN);
            }
            case "landau" -> {
                final RandLandau d = new RandLandau(e);
                yield new Draws("RandLandau", d::fire, Double.NaN, Double.NaN);
            }
            case "skewnormal" -> {
                final double alpha = param(p, 0, 0);
                final RandSkewNormal d = new RandSkewNormal(e, alpha);
                final double delta = alpha / Math.sqrt(1 + alpha * alpha);
                yield new Draws("RandSkewNormal shape " + num(alpha), d::fire, delta * Math.sqrt(2 / Math.PI), 1 - 2 * delta * delta / Math.PI);
            }
            case "bit" -> {
                final RandBit d = new RandBit(e);
                yield new Draws("RandBit", () -> d.fireBit(), 0.5, 0.25);
            }
            default -> throw new IllegalArgumentException("no distribution '" + name + "' (flat, gauss, gaussq, gausst, ziggurat, exp, "
                + "expziggurat, poisson, poissonq, poissont, binomial, breitwigner, chisquare, gamma, studentt, landau, skewnormal, bit)");
        };
    }

    private static void random(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep random <distribution> [parameters] [--engine mixmax|ranlux|mtwist|...] [--seed s] [--n N] [--hist]");
            return;
        }
        final Engine kind = engine(a.opt("engine", "mixmax"));
        final String seed = a.has("seed") ? a.opt("seed", "0") : null;
        final HepRandomEngine e = make(kind, seed);
        if (!kind.cite.isEmpty()) ClhepCitations.used(kind.cite);
        final Draws d = distribution(a.plain.get(0), a.plain.subList(1, a.plain.size()), e);
        final boolean hist = a.has("hist");
        final int n = (int) Math.max(1, value(a.opt("n", hist ? "100000" : "10")));
        final double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = d.draw.getAsDouble();
        AppLogger.result(d.name + " on " + kind.clhepName + (seed == null ? " (as a fresh program has it)" : " seeded " + seed) + ":");
        final StringBuilder first = new StringBuilder("  ");
        for (int i = 0; i < Math.min(n, 10); i++) first.append(num(x[i])).append(i + 1 < Math.min(n, 10) ? "  " : "");
        AppLogger.raw(first + (n > 10 ? "  ..." : ""));
        if (n > 1) {
            double s1 = 0;
            double s2 = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double v : x) {
                s1 += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            final double mean = s1 / n;
            for (double v : x) s2 += (v - mean) * (v - mean);
            final double var = s2 / (n - 1);
            AppLogger.raw(f("  %d draws: mean %s%s, variance %s%s, min %s, max %s", n, num(mean),
                Double.isNaN(d.mean) ? "" : f(" (expected %s, %+.1f sigma)", num(d.mean), (mean - d.mean) / Math.sqrt(Math.max(var, 1e-300) / n)),
                num(var), Double.isNaN(d.variance) ? "" : " (expected " + num(d.variance) + ")", num(min), num(max)));
        }
        if (hist) RootPlotsPanel.instance().showColumn("clhep " + a.plain.get(0), x);
    }

    private static void engines(Args a) {
        final String seed = a.has("seed") ? a.opt("seed", "0") : null;
        AppLogger.result(ENGINES.size() + " engines of CLHEP " + Clhep.VERSION + ", their first flat() "
            + (seed == null ? "as a fresh C++ program has them" : "seeded " + seed) + ":");
        for (Engine k : ENGINES) {
            final HepRandomEngine e = make(k, seed);
            AppLogger.raw(f("  %-11s %-15s %-12s %-12s %-12s  %s", k.key, k.clhepName, num(e.flat()), num(e.flat()), num(e.flat()), k.note));
        }
        ProcessState.restart();
        AppLogger.raw("  ':clhep random <distribution> --engine <name>' draws on one; RanecuEngine's seed is an index into its seed table.");
    }

    /* ------------------------------------------------------------------ */
    /* Matrices                                                            */
    /* ------------------------------------------------------------------ */

    /** "4 1; 1 3", "[[4,1],[1,3]]": rows separated by ';' or '],[', elements by ',' or spaces. */
    private static HepMatrix parseMatrix(String text) {
        final String t = text.trim().replaceAll("^\\[+", "").replaceAll("]+$", "");
        final String[] rows = t.split("\\]\\s*,?\\s*\\[|;");
        final List<double[]> values = new ArrayList<>();
        for (String row : rows) {
            final String r = row.replace("[", "").replace("]", "").trim();
            if (r.isEmpty()) continue;
            final String[] cells = r.contains(",") ? r.split("\\s*,\\s*") : r.split("\\s+");
            final double[] v = new double[cells.length];
            for (int k = 0; k < cells.length; k++) v[k] = value(cells[k]);
            values.add(v);
        }
        if (values.isEmpty()) throw new IllegalArgumentException("no matrix in '" + text + "'");
        final int cols = values.get(0).length;
        for (double[] v : values) {
            if (v.length != cols) throw new IllegalArgumentException("the rows of '" + text + "' differ in length");
        }
        final HepMatrix m = new HepMatrix(values.size(), cols);
        for (int i = 0; i < values.size(); i++) {
            for (int j = 0; j < cols; j++) m.set(i + 1, j + 1, values.get(i)[j]);
        }
        return m;
    }

    private static void printMatrix(String title, HepGenMatrix m) {
        AppLogger.raw("  " + title + " (" + m.num_row() + "x" + m.num_col() + "):");
        for (int i = 1; i <= m.num_row(); i++) {
            final StringBuilder b = new StringBuilder("    |");
            for (int j = 1; j <= m.num_col(); j++) b.append(f(" %14s", num(m.get(i, j))));
            AppLogger.raw(b.append(" |").toString());
        }
    }

    private static void matrix(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep matrix <rows: \"4 1; 1 3\"> [det|inverse|eigen|transpose|solve <b>|times <rows>]");
            return;
        }
        final HepMatrix m = parseMatrix(a.plain.get(0));
        final String op = a.word(1, "").toLowerCase(Locale.ROOT);
        final boolean square = m.num_row() == m.num_col();
        AppLogger.result("HepMatrix " + m.num_row() + "x" + m.num_col() + ":");
        printMatrix("A", m);
        switch (op) {
            case "", "det", "determinant" -> {
                if (square) AppLogger.raw("  det A = " + num(m.determinant()) + "   trace A = " + num(m.trace()));
                AppLogger.raw("  norms: 1 " + num(HepGenMatrix.norm1(m)) + ", infinity " + num(HepGenMatrix.norm_infinity(m)));
            }
            case "inverse", "inv" -> {
                if (!square) throw new IllegalArgumentException("only a square matrix has an inverse");
                final int[] ierr = new int[1];
                final HepMatrix inv = m.inverse(ierr);
                if (ierr[0] != 0) {
                    AppLogger.error("A is singular: CLHEP's inversion failed (ierr " + ierr[0] + ").");
                    return;
                }
                printMatrix("A^-1", inv);
                double off = 0;
                final HepMatrix id = m.times(inv);
                for (int i = 1; i <= id.num_row(); i++) {
                    for (int j = 1; j <= id.num_col(); j++) off = Math.max(off, Math.abs(id.get(i, j) - (i == j ? 1 : 0)));
                }
                AppLogger.raw("  A A^-1 differs from the identity by at most " + num(off));
            }
            case "eigen", "diagonalize" -> {
                if (!square) throw new IllegalArgumentException("only a square matrix has eigenvalues");
                final HepSymMatrix s = new HepSymMatrix(m.num_row());
                double asym = 0;
                for (int i = 1; i <= m.num_row(); i++) {
                    for (int j = 1; j <= i; j++) {
                        s.set(i, j, m.get(i, j));
                        asym = Math.max(asym, Math.abs(m.get(i, j) - m.get(j, i)));
                    }
                }
                if (asym > 0) AppLogger.warn("  A is not symmetric (by " + num(asym) + "): its lower triangle is diagonalized.");
                final HepSymMatrix work = new HepSymMatrix(s);
                final HepMatrix u = MatrixLinear.diagonalize(work);
                final StringBuilder ev = new StringBuilder("  eigenvalues:");
                for (int i = 1; i <= work.num_row(); i++) ev.append(' ').append(num(work.get(i, i)));
                AppLogger.raw(ev.toString());
                printMatrix("eigenvectors (the columns of U, A = U D U^T)", u);
            }
            case "transpose", "t" -> printMatrix("A^T", m.T());
            case "solve" -> {
                final HepMatrix b = parseMatrix(a.word(2, ""));
                final HepVector v = new HepVector(b.num_row() == 1 ? b.num_col() : b.num_row());
                for (int i = 1; i <= v.num_row(); i++) v.set(i, b.num_row() == 1 ? b.get(1, i) : b.get(i, 1));
                final HepVector x = HepVector.solve(m, v);
                printMatrix("x, solving A x = b", new HepMatrix(x));
            }
            case "times" -> printMatrix("A B", m.times(parseMatrix(a.word(2, ""))));
            default -> throw new IllegalArgumentException("no operation '" + op + "' (det, inverse, eigen, transpose, solve <b>, times <rows>)");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Functions                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * f(x) on an Evaluator expression, as a GenericFunctions function: x is set in the session's calculator
     * for each value, and put back as it was afterwards.
     */
    private static final class Expression implements AutoCloseable {
        private final Quantities q = quantities();
        private final String expression;
        private final String variable;
        private final Double before;

        Expression(String expression, String variable) {
            this.expression = expression;
            this.variable = variable;
            requireFree(variable);
            final Quantities.Value v = q.value(variable);
            before = v.ok() && q.lets().containsKey(variable) ? v.value() : null;
        }

        double at(double x) {
            q.let(variable, Double.toString(x));
            final Quantities.Value v = q.value(expression);
            if (!v.ok()) throw new IllegalArgumentException("cannot evaluate '" + expression + "' at " + variable + " = " + num(x)
                + ": " + v.error());
            return v.value();
        }

        AbsFunction function() {
            return new F1D(this::at);
        }

        @Override
        public void close() {
            if (before != null) q.let(variable, Double.toString(before));
            else q.forget(variable);
        }
    }

    /** A variable of a function or an equation may be a name of the user's, not a unit or a constant of CLHEP. */
    private static void requireFree(String variable) {
        final Quantities q = quantities();
        if (!q.lets().containsKey(variable) && q.value(variable).ok()) {
            throw new IllegalArgumentException("'" + variable + "' is a unit or a constant of CLHEP: choose another name for the variable");
        }
    }

    private static void integrate(Args a) {
        if (a.plain.size() < 3) {
            Handlers.usage(":clhep integrate <f(x)> <a> <b> [--open] [--eps e] [--var x]");
            return;
        }
        final String var = a.opt("var", "x");
        final double lo = value(a.plain.get(1));
        final double hi = value(a.plain.get(2));
        final DefiniteIntegral integral = new DefiniteIntegral(lo, hi, a.has("open") ? DefiniteIntegral.Type.OPEN : DefiniteIntegral.Type.CLOSED);
        if (a.has("eps")) integral.setEpsilon(value(a.opt("eps", "1e-6")));
        try (Expression e = new Expression(a.plain.get(0), var)) {
            final double v = integral.apply(e.function());
            AppLogger.result("integral of " + a.plain.get(0) + " d" + var + " from " + num(lo) + " to " + num(hi) + " = " + num(v));
            AppLogger.raw("  " + integral.numFunctionCalls() + " evaluations, " + (a.has("open") ? "extended midpoint (open)" : "trapezoid (closed)")
                + " rule, Romberg-extrapolated to a relative error below " + a.opt("eps", "1e-6"));
        }
    }

    private static void deriv(Args a) {
        if (a.plain.size() < 2) {
            Handlers.usage(":clhep deriv <f(x)> <x0> [--var x]");
            return;
        }
        final String var = a.opt("var", "x");
        final double x0 = value(a.plain.get(1));
        try (Expression e = new Expression(a.plain.get(0), var)) {
            final double v = new FunctionNumDeriv(e.function()).call(x0);
            AppLogger.result("d/d" + var + " " + a.plain.get(0) + " at " + num(x0) + " = " + num(v));
            AppLogger.raw("  Richardson extrapolation of central differences (CLHEP's FunctionNumDeriv, about 8 digits)");
        }
    }

    private static void plot(Args a) {
        if (a.plain.size() < 3) {
            Handlers.usage(":clhep plot <f(x)> <a> <b> [--points n] [--deriv] [--var x]");
            return;
        }
        final String var = a.opt("var", "x");
        final double lo = value(a.plain.get(1));
        final double hi = value(a.plain.get(2));
        final int n = (int) Math.max(2, value(a.opt("points", "400")));
        try (Expression e = new Expression(a.plain.get(0), var)) {
            final AbsFunction fn = a.has("deriv") ? new FunctionNumDeriv(e.function()) : e.function();
            curve(a.has("deriv") ? "d/d" + var + " " + a.plain.get(0) : a.plain.get(0), var, fn, lo, hi, n);
        }
    }

    private static void curve(String name, String var, AbsFunction fn, double lo, double hi, int n) {
        final double[] xs = new double[n];
        final double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = lo + (hi - lo) * i / (n - 1);
            ys[i] = fn.call(xs[i]);
        }
        RootPlotsPanel.instance().showCurve(var, name, xs, ys);
        AppLogger.result(name + " over [" + num(lo) + ", " + num(hi) + "], " + n + " points: in the Plots tab.");
    }

    /** The right-hand sides of dy/dt = f(y), on Evaluator expressions of the variables. */
    private static final class OdeSystem extends AbsFunction {
        private final List<String> vars;
        private final String rhs;
        private final Quantities q = quantities();

        OdeSystem(List<String> vars, String rhs) {
            this.vars = vars;
            this.rhs = rhs;
        }

        @Override
        public OdeSystem copy() {
            return new OdeSystem(vars, rhs);
        }

        @Override
        public int dimensionality() {
            return vars.size();
        }

        @Override
        public double call(double x) {
            final Argument a = new Argument(1);
            a.set(0, x);
            return call(a);
        }

        @Override
        public double call(Argument a) {
            for (int k = 0; k < vars.size(); k++) q.let(vars.get(k), Double.toString(a.get(k)));
            final Quantities.Value v = q.value(rhs);
            if (!v.ok()) throw new IllegalArgumentException("cannot evaluate '" + rhs + "': " + v.error());
            return v.value();
        }
    }

    private static void ode(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep ode <f1; f2; ...> [--vars y1,y2] [--y0 v1,v2] [--t t1,t2,...] [--stepper adaptive|rk4|euler|midpoint|heuneuler|bogacki|fehlberg|doubling] [--h step] [--tol t] [--plot tmax]");
            return;
        }
        final String[] rhs = String.join(" ", a.plain).split(";");
        final List<String> vars = new ArrayList<>();
        if (a.has("vars")) {
            for (String v : a.opt("vars", "").split("[,\\s]+")) if (!v.isBlank()) vars.add(v.trim());
        } else if (rhs.length == 1) {
            vars.add("y");
        } else {
            for (int k = 1; k <= rhs.length; k++) vars.add("y" + k);
        }
        if (vars.size() != rhs.length) {
            throw new IllegalArgumentException(rhs.length + " equation(s) for " + vars.size() + " variable(s) (" + String.join(", ", vars) + ")");
        }
        for (String v : vars) requireFree(v);
        final String[] y0 = a.opt("y0", "").isBlank() ? new String[0] : a.opt("y0", "").split("\\s*,\\s*|\\s+");
        final RKIntegrator.RKStepper stepper = stepper(a);
        final RKIntegrator rk = new RKIntegrator(stepper);
        final Quantities q = quantities();
        final Map<String, Double> before = new LinkedHashMap<>();
        for (String v : vars) {
            if (q.lets().containsKey(v)) before.put(v, q.value(v).value());
        }
        try {
            for (int k = 0; k < rhs.length; k++) {
                rk.addDiffEquation(new OdeSystem(vars, rhs[k].trim()), vars.get(k), k < y0.length ? value(y0[k]) : 0.0);
            }
            final List<Double> times = new ArrayList<>();
            for (String t : a.opt("t", "1").split("\\s*,\\s*|\\s+")) if (!t.isBlank()) times.add(value(t));
            AppLogger.result("d(" + String.join(", ", vars) + ")/dt = (" + String.join("; ", List.of(rhs).stream().map(String::trim).toList())
                + "), with " + stepperName(a) + ":");
            final StringBuilder head = new StringBuilder(f("  %14s", "t"));
            for (String v : vars) head.append(f(" %18s", v));
            AppLogger.raw(head.toString());
            final StringBuilder start = new StringBuilder(f("  %14s", "0"));
            for (int k = 0; k < vars.size(); k++) start.append(f(" %18s", num(rk.getFunction(k).call(0.0))));
            AppLogger.raw(start.toString());
            for (double t : times) {
                final StringBuilder row = new StringBuilder(f("  %14s", num(t)));
                for (int k = 0; k < vars.size(); k++) row.append(f(" %18s", num(rk.getFunction(k).call(t))));
                AppLogger.raw(row.toString());
            }
            if (a.has("plot")) {
                final double tmax = value(a.opt("plot", "10"));
                final int show = Math.max(0, vars.indexOf(a.opt("show", vars.get(0))));
                curve(vars.get(show) + "(t)", "t", rk.getFunction(show), 0, tmax, 400);
            }
        } finally {
            for (String v : vars) {
                if (before.containsKey(v)) q.let(v, Double.toString(before.get(v)));
                else q.forget(v);
            }
        }
    }

    private static String stepperName(Args a) {
        return switch (a.opt("stepper", "adaptive").toLowerCase(Locale.ROOT)) {
            case "rk4", "classical" -> "classical Runge-Kutta, step " + a.opt("h", "0.01");
            case "euler" -> "Euler, step " + a.opt("h", "0.001");
            case "midpoint" -> "the midpoint method, step " + a.opt("h", "0.01");
            case "heuneuler", "bogacki", "fehlberg", "cashkarp" -> "the adaptive " + a.opt("stepper", "") + " embedded pair, tolerance " + a.opt("tol", "1e-6");
            case "doubling" -> "adaptive step doubling of classical Runge-Kutta, tolerance " + a.opt("tol", "1e-6");
            default -> "the adaptive Cash-Karp embedded pair (CLHEP's default), tolerance " + a.opt("tol", "1e-6");
        };
    }

    private static RKIntegrator.RKStepper stepper(Args a) {
        final String s = a.opt("stepper", "adaptive").toLowerCase(Locale.ROOT);
        final double h = value(a.opt("h", s.equals("euler") ? "0.001" : "0.01"));
        final AdaptiveRKStepper adaptive = switch (s) {
            case "rk4", "classical" -> null;
            case "euler", "midpoint" -> null;
            case "heuneuler" -> new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.HeunEulerXtTableau()));
            case "bogacki" -> new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.BogackiShampineXtTableau()));
            case "fehlberg" -> new AdaptiveRKStepper(new EmbeddedRKStepper(new ExtendedButcherTableau.FehlbergRK45F2XtTableau()));
            case "doubling" -> new AdaptiveRKStepper(new StepDoublingRKStepper(new ButcherTableau.ClassicalRungeKuttaTableau()));
            default -> new AdaptiveRKStepper();
        };
        if (adaptive == null) {
            return switch (s) {
                case "euler" -> new SimpleRKStepper(new ButcherTableau.EulerTableau(), h);
                case "midpoint" -> new SimpleRKStepper(new ButcherTableau.MidpointTableau(), h);
                default -> new SimpleRKStepper(new ButcherTableau.ClassicalRungeKuttaTableau(), h);
            };
        }
        if (a.has("tol")) adaptive.setTolerance(value(a.opt("tol", "1e-6")));
        return adaptive;
    }

    /** A special function: its arguments, those before x being its parameters, and whether it is GSL's. */
    private record Special(String name, int params, String usage, boolean gsl, Function<double[], AbsFunction> make) {
    }

    private static final List<Special> SPECIALS = List.of(
        new Special("airy-ai", 0, "airy-ai x", true, p -> new Airy(Airy.Type.Ai)),
        new Special("airy-bi", 0, "airy-bi x", true, p -> new Airy(Airy.Type.Bi)),
        new Special("besselj", 1, "besselj n x", true, p -> new Bessel.IntegralOrder(Bessel.Type.J, (int) p[0])),
        new Special("bessely", 1, "bessely n x", true, p -> new Bessel.IntegralOrder(Bessel.Type.Y, (int) p[0])),
        new Special("besseljnu", 1, "besseljnu nu x", true, p -> {
            final Bessel.FractionalOrder b = new Bessel.FractionalOrder(Bessel.Type.J);
            b.order().setValue(p[0]);
            return b;
        }),
        new Special("besselynu", 1, "besselynu nu x", true, p -> {
            final Bessel.FractionalOrder b = new Bessel.FractionalOrder(Bessel.Type.Y);
            b.order().setValue(p[0]);
            return b;
        }),
        new Special("sphbessel", 1, "sphbessel l x", true, p -> new SphericalBessel((int) p[0])),
        new Special("sphneumann", 1, "sphneumann l x", true, p -> new SphericalNeumann((int) p[0])),
        new Special("legendre", 1, "legendre l x", true, p -> new Legendre((int) p[0])),
        new Special("assoclegendre", 2, "assoclegendre l m x", true, p -> new AssociatedLegendre((int) p[0], (int) p[1])),
        new Special("ellint-f", 1, "ellint-f k phi", true, p -> {
            final EllipticIntegral.FirstKind f = new EllipticIntegral.FirstKind();
            f.k().setValue(p[0]);
            return f;
        }),
        new Special("ellint-e", 1, "ellint-e k phi", true, p -> {
            final EllipticIntegral.SecondKind f = new EllipticIntegral.SecondKind();
            f.k().setValue(p[0]);
            return f;
        }),
        new Special("ellint-p", 2, "ellint-p k n phi", true, p -> {
            final EllipticIntegral.ThirdKind f = new EllipticIntegral.ThirdKind();
            f.k().setValue(p[0]);
            f.n().setValue(p[1]);
            return f;
        }),
        new Special("gamma", 0, "gamma x", false, p -> new Gamma()),
        new Special("lngamma", 0, "lngamma x", false, p -> new LogGamma()),
        new Special("erf", 0, "erf x", false, p -> new Erf()),
        new Special("incgamma", 1, "incgamma a x (regularized lower)", false, p -> {
            final IncompleteGamma g = new IncompleteGamma();
            g.a().setValue(p[0]);
            return g;
        }),
        new Special("hermite", 1, "hermite n x (normalized)", false, p -> new HermitePolynomial((int) p[0])),
        new Special("laguerre", 2, "laguerre n k x (associated)", false, p -> new AssociatedLaguerre((int) p[0], (int) p[1])),
        new Special("chi2cdf", 1, "chi2cdf ndf x", false, p -> new CumulativeChiSquare((int) p[0])),
        new Special("landau", 2, "landau peak width x", false, p -> {
            final Landau l = new Landau();
            l.peak().setValue(p[0]);
            l.width().setValue(p[1]);
            return l;
        }),
        new Special("voigt", 3, "voigt mass width sigma x", false, p -> {
            final VoigtProfile v = new VoigtProfile();
            v.mass().setValue(p[0]);
            v.width().setValue(p[1]);
            v.sigma().setValue(p[2]);
            return v;
        }),
        new Special("breitwigner", 2, "breitwigner mass width x (relativistic)", false, p -> {
            final RelativisticBWDistribution b = new RelativisticBWDistribution();
            b.mass().setValue(p[0]);
            b.width().setValue(p[1]);
            return b;
        }));

    private static void special(Args a) {
        if (a.plain.isEmpty()) {
            AppLogger.result("Special functions of GenericFunctions (GSL's marked *, from this machine's " + Gsl.describe() + "):");
            for (Special s : SPECIALS) AppLogger.raw("  " + (s.gsl ? "* " : "  ") + s.usage);
            AppLogger.raw("  ':clhep special <name> <parameters> <x> [--plot a b]'");
            return;
        }
        Special s = null;
        for (Special c : SPECIALS) {
            if (c.name.equalsIgnoreCase(a.plain.get(0))) s = c;
        }
        if (s == null) throw new IllegalArgumentException("no special function '" + a.plain.get(0) + "' (':clhep special' lists them)");
        final boolean plotted = a.has("plot");
        final int needed = s.params + (plotted ? 0 : 1);
        if (a.plain.size() - 1 < needed) {
            Handlers.usage(":clhep special " + s.usage + "   [--plot a b in place of x]");
            return;
        }
        final double[] p = new double[s.params];
        for (int k = 0; k < s.params; k++) p[k] = value(a.plain.get(1 + k));
        if (s.gsl) ClhepCitations.used("GSL");
        final AbsFunction fn = s.make.apply(p);
        if (plotted) {
            final List<String> range = tokens(a.opt("plot", ""));
            if (range.size() < 2) throw new IllegalArgumentException("--plot takes the range: a b");
            curve(s.name + paramText(p), "x", fn, value(range.get(0)), value(range.get(1)), 400);
            return;
        }
        final double x = value(a.plain.get(1 + s.params));
        AppLogger.result(s.name + paramText(p) + "(" + num(x) + ") = " + num(fn.call(x)));
    }

    private static String paramText(double[] p) {
        if (p.length == 0) return "";
        final List<String> s = new ArrayList<>();
        for (double v : p) s.add(num(v));
        return "[" + String.join(", ", s) + "]";
    }

    /* ------------------------------------------------------------------ */
    /* Trust                                                               */
    /* ------------------------------------------------------------------ */

    private static void validate(Args a) {
        final String filter = a.word(0, "");
        final List<String> todo = ClhepValidation.matching(filter);
        if (todo.isEmpty()) {
            AppLogger.error("Nothing matches '" + filter + "': " + String.join(", ", ClhepValidation.names()) + ".");
            return;
        }
        if (!ClhepValidation.referencesAvailable()) {
            AppLogger.error("The C++ outputs are not in the jar (refdata/clhep-refs.zip); -Dclhep.refdir=<folder> points to them.");
            return;
        }
        AppLogger.info(f("Running %d of the reference programs and CLHEP %s's tests, ported, against the C++ outputs (math: %s):",
            todo.size(), Clhep.VERSION, NativeMath.describe()));
        int ok = 0;
        final long t0 = System.nanoTime();
        for (String id : todo) {
            final ClhepValidation.Outcome r = ClhepValidation.run(id);
            if (r.passed()) ok++;
            AppLogger.raw("  " + r.line());
        }
        final String summary = f("%d of %d agree with the C++ to the bit, in %.1f s.", ok, todo.size(), (System.nanoTime() - t0) / 1e9);
        if (ok == todo.size()) AppLogger.success(summary);
        else AppLogger.warn(summary);
        ClhepValidation.leftOut(filter).forEach((id, why) ->
            AppLogger.raw(f("  %s was left out (%s): ':clhep validate %s' or ':clhep validate all' runs it.", id, why, id)));
    }

    private static void bib(Args a) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":clhep bib <file.bib> [--all]");
            return;
        }
        final boolean all = a.has("all");
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        Files.writeString(out, ClhepCitations.bibtex(all), StandardCharsets.UTF_8);
        AppLogger.result(f("%d references written to %s.", ClhepCitations.bibliography(all).size(), out));
    }

    /* ------------------------------------------------------------------ */
    /* What ':clhep diag' adds                                             */
    /* ------------------------------------------------------------------ */

    /** The rows ':clhep diag' shows beside the probe. */
    static void diagRows() {
        EngineCommands.row("packages", "Units, Vector, Geometry, Matrix, Random, RandomObjects, Evaluator, GenericFunctions, Fields");
        EngineCommands.row("units", Quantities.fields().size() + " units and constants; dimensions found by evaluating in other systems of units");
        EngineCommands.row("engines", ENGINES.size() + " (MixMaxRng the default), 20 distributions; ':clhep engines'");
        EngineCommands.row("math", NativeMath.describe());
        EngineCommands.row("GSL", Gsl.describe() + " (for GenericFunctions' special functions)");
        final boolean refs = ClhepValidation.referencesAvailable();
        EngineCommands.row("references", ClhepValidation.names().size() + " reference programs and tests; C++ outputs "
            + (refs ? "in the jar" : "absent (-Dclhep.refdir)"));
        if (refs) {
            final ClhepValidation.Outcome r = ClhepValidation.run("evaluator");
            EngineCommands.row("versus C++", r.line() + "; all of them: ':clhep validate'");
        }
        EngineCommands.row("citations", "console menu > Citations > Citation CLHEP; ':clhep bib <file.bib>'");
    }
}
