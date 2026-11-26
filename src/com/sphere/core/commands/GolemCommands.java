package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.bridge.Bridge;
import com.sphere.core.golem95.Golem95;
import com.sphere.core.golem95.Golem95Citations;
import com.sphere.core.golem95.GolemEngine;
import com.sphere.core.golem95.GolemNative;
import com.sphere.core.golem95.Kinematics;
import com.sphere.core.golem95.Laurent;
import com.sphere.core.golem95.LoopTools;
import com.sphere.core.golem95.Quantity;
import com.sphere.core.golem95.Reliability;
import com.sphere.core.golem95.Scan;
import com.sphere.core.golem95.Tensors;
import com.sphere.core.golem95.port.Cx;
import com.sphere.core.golem95.validation.GolemValidation;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Golem95 in Java: the {@code :golem} commands, on Sphere's translation of Golem95 1.3.4 and OneLOop 3.6.1.
 *
 * <p>A kinematics is defined once, by its invariants and masses ({@code :golem box s=200 t=-123 m3=80.4
 * w3=2.1}), by its S matrix or by four-momenta, and named; every question is then asked of the current
 * one. Every answer comes with the number of its digits that can be trusted, measured by computing it
 * again for the kinematics moved by its own rounding; {@code :golem check} says why when they are few,
 * and gives the value from points around where Golem95 is stable. A scan is computed on every processor
 * and drawn in the Plots tab, with the points that cannot be trusted said.
 */
public final class GolemCommands {

    private GolemCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* The session                                                         */
    /* ------------------------------------------------------------------ */

    /** A kinematics as it was defined: by parameters (so a scan can move one), or by its S matrix. */
    private record Definition(String name, String type, Map<String, Double> params, Kinematics fixed) {

        Kinematics build() {
            return fixed != null ? fixed : GolemCommands.build(type, params, name);
        }

        Kinematics with(String key, double value) {
            if (fixed != null) {
                throw new IllegalArgumentException("'" + name + "' was given as " + type + ": only a kinematics defined by "
                    + "its invariants and masses (box, triangle ...) can be scanned");
            }
            final Map<String, Double> p = new LinkedHashMap<>(params);
            p.put(key, value);
            return GolemCommands.build(type, p, name + " at " + key + " = " + num(value));
        }
    }

    private static final Map<String, Definition> DEFINED = new LinkedHashMap<>();
    private static String current;
    private static GolemEngine.Settings settings = GolemEngine.Settings.DEFAULT;
    private static Laurent.Convention convention = Laurent.Convention.R_GAMMA;
    private static GolemEngine engine;

    private static synchronized GolemEngine engine() {
        if (engine == null) {
            engine = new GolemEngine(settings);
        }
        return engine.configure(settings);
    }

    private static synchronized Definition currentDefinition() {
        if (current == null || !DEFINED.containsKey(current)) {
            throw new IllegalStateException("no kinematics yet: define one, e.g. ':golem box s=200 t=-123' "
                + "(':golem help' shows how)");
        }
        return DEFINED.get(current);
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                        */
    /* ------------------------------------------------------------------ */

    @FunctionalInterface
    private interface Body {
        void run(Args a) throws Exception;
    }

    private static void reg(String sub, String description, Body body) {
        final String name = sub.isEmpty() ? ":golem" : ":golem " + sub;
        CommandDefinitions.register(name, description, (i, c) -> {
            final Args a = new Args(Handlers.args(i, name));
            try {
                body.run(a);
            } catch (Exception e) {
                AppLogger.error(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    static void register() {
        reg("", "Golem95 in Java: one-loop tensor integrals (form factors, up to six points, complex masses), "
            + "each answer with the digits it can be trusted to", a -> help());
        reg("help", "What the Golem95 commands do", a -> help());
        reg("version", "The version (Golem95, OneLOop, Java port); references under Citations in the console menu",
            a -> AppLogger.raw(Golem95.versionLine()));
        for (String type : new String[]{"tadpole", "bubble", "triangle", "box", "pentagon", "hexagon"}) {
            reg(type, "Define a " + type + " by its invariants (squared) and masses, and make it the current kinematics. "
                + "Usage: :golem " + type + " " + usageOf(type) + " [as <name>]", a -> define(type, a));
        }
        reg("smatrix", "Define a kinematics by Golem95's S matrix, S_ij = (q_i - q_j)^2 - m_i^2 - m_j^2. Usage: "
            + ":golem smatrix \"<row 1>; <row 2>; ...\" [as <name>]  (entries a, a+bi or (a,b))", GolemCommands::smatrix);
        reg("momenta", "Define a kinematics by incoming four-momenta (E px py pz, adding up to zero) and masses; invariants "
            + "zero but for rounding are made 0. Usage: :golem momenta \"<E px py pz>; ...\" [m1=..] [w1=..] [as <name>] [--exact]",
            GolemCommands::momenta);
        reg("propagators", "Define a kinematics by the momenta of its propagators, (k + r_i)^2 - m_i^2 with r_N = 0, as "
            + "Golem95's tensor integrals take it. Usage: :golem propagators \"<r_1: E px py pz>; ...; <r_N-1>\" [m1=..] "
            + "[w1=..] [as <name>]", GolemCommands::propagators);
        reg("tensor", "A tensor integral, every component or one, built from Golem95's form factors (any rank up to 7), "
            + "with its correct digits; needs momenta. --check holds it to exact identities with the tensors of lower rank. "
            + "Usage: :golem tensor <rank> [mu1 .. mur] [/ <pinched>] [--csv file] [--check]",
            GolemCommands::tensor);
        reg("lt", "A coefficient by its LoopTools name (dd0012 = B^{4,4}(1,2): a pair of zeros per g^{mu nu}, then the "
            + "momenta's indices), with its correct digits. Usage: :golem lt <aa0 | bb1 | cc00 | dd0012 | ee00123 ...>",
            a -> value("", a));
        reg("snap", "Make the entries of the current S matrix that are zero but for rounding (under 1e-12 of the "
            + "largest) exactly zero: legs meant to be on shell. Usage: :golem snap", a -> snap());
        reg("use", "Make a defined kinematics the current one. Usage: :golem use <name>", GolemCommands::use);
        reg("list", "The kinematics defined in this session", a -> list());
        reg("forget", "Forget a kinematics. Usage: :golem forget <name>|--all", GolemCommands::forget);
        reg("show", "What a kinematics is: legs, masses, infrared structure, thresholds, det S, B, conditioning, and "
            + "its S matrix. Usage: :golem show [name]", GolemCommands::show);
        reg("ff", "A form factor A, B, C or D of the current kinematics, with its correct digits. Usage: :golem ff "
            + "<a40 | a43 1 2 3 | b42 | c44 | a45 1 1 2 3 4 | d46 | dd0012 ...> [/ <pinched propagators>]", a -> value("", a));
        reg("int", "A basis integral I_N^{n+2g}(z_l ...) of the current kinematics. Usage: :golem int [n|n+2|n+4 ...] "
            + "[labels] [/ <pinched>]", a -> value("int ", a));
        reg("scalar", "The scalar integral by OneLOop (1 to 4 points), independent of Golem95's reduction. Usage: "
            + ":golem scalar [/ <pinched>]", a -> value("scalar ", a));
        reg("check", "How far an answer can be trusted, and why: noise, conditioning, exact properties (units, mu^2, "
            + "renamed propagators, labels reordered above rank N, OneLOop), and a rescue where Golem95 is unstable. Usage: "
            + ":golem check <quantity>",
            GolemCommands::check);
        reg("scan", "A quantity along one parameter of the current kinematics, computed in parallel, drawn in the Plots "
            + "tab. Usage: :golem scan <quantity> <parameter> <from> <to> [--points n] [--coef a|b|c] [--part re|im|abs] "
            + "[--csv file] [--even]", GolemCommands::scan);
        reg("set", "Golem95's settings. Usage: :golem set [mu2=..] [rational=on|off] [tolerance=..] [accuracy=..] "
            + "[cut=..]  (alone: the settings)", GolemCommands::set);
        reg("convention", "The normalisation answers are printed in: r_gamma (Golem95's, OneLOop's), gamma "
            + "(Gamma(1+eps)) or msbar. Usage: :golem convention [r_gamma|gamma|msbar]", GolemCommands::convention);
        reg("table", "The form factors Golem95 has for N points, by rank. Usage: :golem table [N]", GolemCommands::table);
        reg("b", "Golem95's b_i = sum_j (S^-1)_ij and B for the current kinematics, and whether B is only rounding",
            a -> bInfo());
        reg("validate", "The Java Golem95 against the Fortran (gfortran): 300 kinematics, every kind and rank of form "
            + "factor, judged against the Fortran's own spread. Usage: :golem validate", a -> validate());
        reg("native", "The Fortran Golem95: found, built from the sources Sphere carries, or tested against the Java. "
            + "Usage: :golem native [status|build|test]", GolemCommands::nativeCommand);
        reg("bib", "Write the BibTeX of Golem95 and OneLOop. Usage: :golem bib <file.bib> [--all]", GolemCommands::bib);
    }

    private static String usageOf(String type) {
        return switch (type) {
            case "tadpole" -> "m=<mass> [w=<width>]";
            case "bubble" -> "p=<p^2> m1=.. m2=..";
            case "triangle" -> "p1=.. p2=.. p3=.. m1=.. m2=.. m3=..";
            case "box" -> "s=.. t=.. [p1=..p4=..] [m1=..m4=..] [w1=..]";
            case "pentagon" -> "s12=.. s23=.. s34=.. s45=.. s51=.. [p1=..p5=..] [m1=..m5=..]";
            default -> "s12=.. s23=.. s34=.. s45=.. s56=.. s61=.. s123=.. s234=.. s345=.. [p1=..p6=..] [m1=..m6=..]";
        };
    }

    /* ------------------------------------------------------------------ */
    /* Arguments                                                           */
    /* ------------------------------------------------------------------ */

    /** The words after the command (a quoted text is one word), then --options with the words after each. */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final Map<String, List<String>> ops = new LinkedHashMap<>();

        Args(String text) {
            List<String> currentOp = null;
            for (String w : ClhepCommands.tokens(text)) {
                if (w.startsWith("--") && w.length() > 2 && Character.isLetter(w.charAt(2))) {
                    currentOp = new ArrayList<>();
                    ops.put(w.substring(2).toLowerCase(Locale.ROOT), currentOp);
                } else if (currentOp != null) {
                    currentOp.add(w);
                } else {
                    plain.add(w);
                }
            }
        }

        boolean has(String name) {
            return ops.containsKey(name);
        }

        String opt(String name, String fallback) {
            final List<String> v = ops.get(name);
            return v == null || v.isEmpty() ? fallback : String.join(" ", v);
        }
    }

    /** A number, or an expression of CLHEP's Evaluator (80.4^2, 2*91.1876, a variable of ':clhep let'). */
    private static double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notPlain) {
            final com.sphere.core.clhep.Quantities.Value v = ClhepCommands.quantities().value(text);
            if (!v.ok()) {
                throw new IllegalArgumentException("'" + text + "' is not a number: " + v.error());
            }
            return v.value();
        }
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    /* ------------------------------------------------------------------ */
    /* Kinematics                                                          */
    /* ------------------------------------------------------------------ */

    private static int points(String type) {
        return switch (type) {
            case "tadpole" -> 1;
            case "bubble" -> 2;
            case "triangle" -> 3;
            case "box" -> 4;
            case "pentagon" -> 5;
            default -> 6;
        };
    }

    private static final Pattern KEY = Pattern.compile("(p|m|w|msq)([1-6])|m([1-6])sq|s|t|p|m|w|s[1-6]{2,3}");

    /**
     * The kinematics of a type from its parameters: p_i the leg invariants p_i^2 (leg i between propagators
     * i-1 and i), s, t, s_ij... the channel invariants, m_i the masses (or msq_i their squares), w_i widths
     * (m^2 - i m w).
     */
    static Kinematics build(String type, Map<String, Double> v, String name) {
        final int n = points(type);
        final double[] legs = new double[n];
        final Cx[] m2 = new Cx[n];
        final Map<String, Double> channels = new HashMap<>();
        for (Map.Entry<String, Double> e : v.entrySet()) {
            final String k = e.getKey();
            if (!KEY.matcher(k).matches() || k.chars().anyMatch(ch -> Character.isDigit(ch) && ch - '0' > n)
                || (k.equals("t") || k.equals("s")) && n != 4 && n != 2 || n < 3 && k.matches("s[1-6]{2,3}")) {
                throw new IllegalArgumentException("'" + k + "' is not a parameter of a " + type + ": " + usageOf(type));
            }
            if ((k.equals("s") || k.equals("t") || k.matches("s[1-6]{2,3}")) && n >= 3) {
                channels.put(k, e.getValue());
            }
        }
        for (int i = 1; i <= n; i++) {
            Double leg = v.get("p" + i);
            if (leg == null && n == 2) {
                leg = v.containsKey("p") ? v.get("p") : v.get("s");
            }
            legs[i - 1] = leg == null ? 0 : leg;
            Double mass = v.get("m" + i);
            Double width = v.get("w" + i);
            Double square = v.containsKey("msq" + i) ? v.get("msq" + i) : v.get("m" + i + "sq");
            if (n == 1) {
                mass = mass == null ? v.get("m") : mass;
                width = width == null ? v.get("w") : width;
            }
            final double re = square != null ? square : mass == null ? 0 : mass * mass;
            final double im = width == null ? 0 : -Math.sqrt(Math.max(re, 0)) * width;
            m2[i - 1] = new Cx(re, im);
        }
        if (n == 1) {
            return Kinematics.tadpole(m2[0]);
        }
        return Kinematics.fromInvariants(n, legs, channels, m2, name);
    }

    private static void define(String type, Args a) {
        final Map<String, Double> params = new LinkedHashMap<>();
        String name = null;
        for (int i = 0; i < a.plain.size(); i++) {
            final String w = a.plain.get(i);
            if (w.equalsIgnoreCase("as") && i + 1 < a.plain.size()) {
                name = a.plain.get(++i);
                continue;
            }
            final int eq = w.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("'" + w + "' is not a parameter (key=value): " + usageOf(type));
            }
            params.put(w.substring(0, eq).trim().toLowerCase(Locale.ROOT), number(w.substring(eq + 1).trim()));
        }
        name = name == null ? type + (count(type) + 1) : name;
        final Kinematics k = build(type, params, name);
        keep(new Definition(name, type, params, null), k);
    }

    private static synchronized int count(String type) {
        int c = 0;
        for (Definition d : DEFINED.values()) {
            if (d.type().equals(type)) {
                c++;
            }
        }
        return c;
    }

    private static synchronized void keep(Definition d, Kinematics k) {
        DEFINED.put(d.name(), d);
        current = d.name();
        AppLogger.result(d.name() + " defined, and current:");
        for (String l : k.describe()) {
            AppLogger.raw("  " + l);
        }
    }

    /** a, bi, a+bi, a-bi or (a,b): an entry of an S matrix. */
    private static Cx complex(String text) {
        final String t = text.replace(" ", "");
        try {
            if (t.startsWith("(") && t.endsWith(")")) {
                final String[] p = t.substring(1, t.length() - 1).split(",");
                if (p.length != 2) {
                    throw new NumberFormatException(t);
                }
                return new Cx(Double.parseDouble(p[0]), Double.parseDouble(p[1]));
            }
            if (!t.endsWith("i") && !t.endsWith("j")) {
                return Cx.of(Double.parseDouble(t));
            }
            final String body = t.substring(0, t.length() - 1);
            // the sign that starts the imaginary part: the last + or - not at the start nor after an exponent's e
            int cut = -1;
            for (int i = body.length() - 1; i > 0; i--) {
                final char ch = body.charAt(i);
                final char before = body.charAt(i - 1);
                if ((ch == '+' || ch == '-') && before != 'e' && before != 'E') {
                    cut = i;
                    break;
                }
            }
            final String re = cut < 0 ? "0" : body.substring(0, cut);
            final String im = cut < 0 ? body : body.substring(cut);
            final double imv = im.isEmpty() || im.equals("+") ? 1 : im.equals("-") ? -1 : Double.parseDouble(im);
            return new Cx(Double.parseDouble(re), imv);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a number (a, a+bi or (a,b))");
        }
    }

    private static void smatrix(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem smatrix \"0 0 -123 0; 0 0 0 200; -123 0 0 0; 0 200 0 0\" [as <name>]");
            return;
        }
        final String[] rows = a.plain.get(0).split(";");
        final int n = rows.length;
        final Cx[][] s = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            final List<String> cells = cells(rows[i]);
            if (cells.size() != n) {
                throw new IllegalArgumentException("row " + (i + 1) + " has " + cells.size() + " entries, " + n + " expected");
            }
            for (int j = 0; j < n; j++) {
                s[i][j] = complex(cells.get(j));
            }
        }
        final String name = named(a, "smatrix" + (count("smatrix") + 1));
        keep(new Definition(name, "smatrix", Map.of(), Kinematics.ofS(s, name)), Kinematics.ofS(s, name));
    }

    /** The entries of a row: separated by spaces or commas outside parentheses. */
    private static List<String> cells(String row) {
        final List<String> out = new ArrayList<>();
        final StringBuilder w = new StringBuilder();
        int depth = 0;
        for (char ch : row.trim().toCharArray()) {
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if ((Character.isWhitespace(ch) || ch == ',') && depth == 0) {
                if (w.length() > 0) {
                    out.add(w.toString());
                    w.setLength(0);
                }
            } else {
                w.append(ch);
            }
        }
        if (w.length() > 0) {
            out.add(w.toString());
        }
        return out;
    }

    private static String named(Args a, String fallback) {
        for (int i = 0; i + 1 < a.plain.size(); i++) {
            if (a.plain.get(i).equalsIgnoreCase("as")) {
                return a.plain.get(i + 1);
            }
        }
        return fallback;
    }

    private static void momenta(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem momenta \"10 0 0 10; 10 0 0 -10; -10 0 6 -8; -10 0 -6 8\" [m1=..] [as <name>]");
            return;
        }
        final String[] rows = a.plain.get(0).split(";");
        final int n = rows.length;
        final double[][] p = new double[n][4];
        for (int i = 0; i < n; i++) {
            final List<String> c = cells(rows[i]);
            if (c.size() != 4) {
                throw new IllegalArgumentException("momentum " + (i + 1) + " has " + c.size() + " components, 4 expected (E px py pz)");
            }
            for (int k = 0; k < 4; k++) {
                p[i][k] = number(c.get(k));
            }
        }
        final Map<String, Double> masses = new LinkedHashMap<>();
        for (String w : a.plain.subList(1, a.plain.size())) {
            final int eq = w.indexOf('=');
            if (eq > 0) {
                masses.put(w.substring(0, eq).toLowerCase(Locale.ROOT), number(w.substring(eq + 1)));
            }
        }
        final Cx[] m2 = new Cx[n];
        for (int i = 1; i <= n; i++) {
            final Double m = masses.get("m" + i);
            final Double w = masses.get("w" + i);
            final double re = m == null ? 0 : m * m;
            m2[i - 1] = new Cx(re, w == null ? 0 : -Math.sqrt(re) * w);
        }
        final String name = named(a, "momenta" + (count("momenta") + 1));
        final Kinematics computed = Kinematics.fromMomenta(p, m2, name);
        // invariants computed from momenta carry the rounding of the subtractions: a massless leg is not exactly
        // on shell, which Golem95 takes as given; snapped unless asked otherwise
        final Kinematics k = a.has("exact") ? computed : computed.snapped(Kinematics.SNAP);
        if (k != computed) {
            AppLogger.info(computed.roundingZeros(Kinematics.SNAP).size() + " entries of S zero but for the rounding of the "
                + "momenta made exactly 0 (--exact keeps them).");
        }
        keep(new Definition(name, "momenta", Map.of(), k), k);
    }

    /** The masses m1=.., w1=.. among the words, squared: m^2 - i m w. */
    private static Cx[] masses(List<String> words, int n) {
        final Map<String, Double> v = new LinkedHashMap<>();
        for (String w : words) {
            final int eq = w.indexOf('=');
            if (eq > 0) {
                v.put(w.substring(0, eq).toLowerCase(Locale.ROOT), number(w.substring(eq + 1)));
            }
        }
        final Cx[] m2 = new Cx[n];
        for (int i = 1; i <= n; i++) {
            final Double m = v.get("m" + i);
            final Double w = v.get("w" + i);
            final double re = m == null ? 0 : m * m;
            m2[i - 1] = new Cx(re, w == null ? 0 : -Math.sqrt(re) * w);
        }
        return m2;
    }

    private static void propagators(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem propagators \"10 0 0 10; 4 3 0 2; 1 0 -1 0\" [m1=..] [w1=..] [as <name>]   "
                + "(r_1 .. r_N-1; r_N = 0)");
            return;
        }
        final String[] rows = a.plain.get(0).split(";");
        final int n = rows.length + 1;
        if (n > 6) {
            throw new IllegalArgumentException("Golem95 computes up to six-point integrals: at most five momenta r_1 .. r_5");
        }
        final double[][] r = new double[n - 1][4];
        for (int i = 0; i < n - 1; i++) {
            final List<String> c = cells(rows[i]);
            if (c.size() != 4) {
                throw new IllegalArgumentException("r_" + (i + 1) + " has " + c.size() + " components, 4 expected (E px py pz)");
            }
            for (int k = 0; k < 4; k++) {
                r[i][k] = number(c.get(k));
            }
        }
        final String name = named(a, "propagators" + (count("propagators") + 1));
        final Kinematics computed = Kinematics.fromPropagatorMomenta(r, masses(a.plain.subList(1, a.plain.size()), n), name);
        final Kinematics k = a.has("exact") ? computed : computed.snapped(Kinematics.SNAP);
        if (k != computed) {
            AppLogger.info(computed.roundingZeros(Kinematics.SNAP).size() + " entries of S zero but for the rounding of the "
                + "momenta made exactly 0 (--exact keeps them).");
        }
        keep(new Definition(name, "propagators", Map.of(), k), k);
    }

    private static void tensor(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem tensor <rank> [mu1 .. mur] [/ <pinched>] [--csv file] [--check]   (the kinematics from "
                + "':golem momenta' or ':golem propagators'; indices 0 = energy .. 3)");
            return;
        }
        final Definition d = currentDefinition();
        final Kinematics k = d.build();
        final String[] parts = String.join(" ", a.plain).split("/", 2);
        final String[] w = parts[0].trim().split("[\\s,]+");
        final int rank;
        try {
            rank = Integer.parseInt(w[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + w[0] + "' is not a rank");
        }
        final int[] mu = new int[w.length - 1];
        for (int i = 1; i < w.length; i++) {
            mu[i - 1] = Integer.parseInt(w[i]);
        }
        final int[] pinched = parts.length > 1 && !parts[1].isBlank()
            ? java.util.Arrays.stream(parts[1].trim().split("[\\s,]+")).mapToInt(Integer::parseInt).toArray() : new int[0];
        if (mu.length != 0 && mu.length != rank) {
            throw new IllegalArgumentException("a rank-" + rank + " tensor's component has " + rank + " indices, not " + mu.length);
        }
        final Tensors.Tensor t = engine().set(k).tensor(rank, pinched);
        final double noise = Tensors.noise(k, settings, rank, pinched, t, 0x601E95L);
        final double largest = t.largest();
        if (rank == 0) {
            AppLogger.result(header(t.what() + " (the scalar integral)", d));
            print(t.get());
        } else if (mu.length == rank) {
            AppLogger.result(header(f("I^{%s}, %s", indices(mu), t.what()), d));
            print(t.get(mu));
        } else {
            AppLogger.result(header(t.what(), d));
            int zero = 0;
            int at = -1;
            double best = -1;
            for (int f = 0; f < t.size(); f++) {
                final Laurent l = t.get(digits(f, rank));
                final double s = l.size();
                if (s <= 1e-14 * largest) {
                    zero++;
                }
                if (s > best) {
                    best = s;
                    at = f;
                }
            }
            if (rank <= 2) {
                for (int f = 0; f < t.size(); f++) {
                    final int[] m = digits(f, rank);
                    final Laurent l = t.get(m).in(convention);
                    if (l.size() > 1e-14 * largest) {
                        AppLogger.raw(f("  I^{%s}   %s%s%s", rank == 0 ? "" : indices(m), cx(l.c()),
                            Cx.abs(l.b()) == 0 ? "" : "   1/eps " + cx(l.b()), Cx.abs(l.a()) == 0 ? "" : "   1/eps^2 " + cx(l.a())));
                    }
                }
            } else {
                AppLogger.raw(f("  %d components, %d of them zero; the largest, I^{%s}:", t.size(), zero, indices(digits(at, rank))));
                print(t.get(digits(at, rank)));
                AppLogger.raw("  one component: ':golem tensor " + rank + " 0 1 ...'; all of them: --csv <file>");
            }
        }
        // the error is relative to the largest component: one component alone has it relative to its own size
        final double error = Math.max(noise, t.orderSpread());
        final double own = mu.length == rank && rank > 0 ? t.get(mu).size() : largest;
        final boolean alone = own > 0 && own < largest;
        final int digits = Reliability.digitsOf(alone ? error * largest / own : error);
        AppLogger.raw(f("  about %d correct digit%s ", digits, digits == 1 ? "" : "s") + (alone
            ? f("(the components move by %.0e of the largest, %.0e of this one, when the momenta move by 1e-15)", noise,
            noise * largest / own)
            : f("(the components move by %.0e of the largest when the momenta move by 1e-15)", noise)));
        if (t.orderSpread() > 0) {
            final String order = f("  Golem95's %d-point form factors of rank %d depend on the order of their labels: one order "
                + "instead of the mean over them moves the tensor by %.0e (Sphere takes the mean: the integral is symmetric in "
                + "them)", t.points(), rank, t.orderSpread());
            if (t.orderSpread() > Math.max(noise, 1e-10)) {
                AppLogger.warn(order);
            } else {
                AppLogger.raw(order);
            }
        }
        AppLogger.raw("  in the frame of the momenta given: index 0 the energy, metric (+,-,-,-), propagators (k + r_i)^2 - m_i^2");
        if (a.has("check")) {
            identities(t, pinched, error);
        }
        if (a.has("csv")) {
            final Path out = Handlers.resolve(a.opt("csv", "golem_tensor.csv")).toPath();
            final StringBuilder csv = new StringBuilder();
            for (int s = 1; s <= rank; s++) {
                csv.append("mu").append(s).append(',');
            }
            csv.append("re_a,im_a,re_b,im_b,re_c,im_c\n");
            for (int f = 0; f < t.size(); f++) {
                final int[] m = digits(f, rank);
                final Laurent l = t.get(m).in(convention);
                for (int x : m) {
                    csv.append(x).append(',');
                }
                csv.append(f("%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n", l.a().re, l.a().im, l.b().re, l.b().im, l.c().re, l.c().im));
            }
            try {
                Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
                AppLogger.raw("  all " + t.size() + " components written to " + out);
            } catch (IOException e) {
                AppLogger.error("Could not write " + out + ": " + e.getMessage());
            }
        }
    }

    /** The indices of a flat component number (first index fastest). */
    private static int[] digits(int flat, int rank) {
        final int[] m = new int[rank];
        for (int s = 0; s < rank; s++) {
            m[s] = (flat >> (2 * s)) & 3;
        }
        return m;
    }

    private static String indices(int[] mu) {
        final StringBuilder b = new StringBuilder();
        for (int x : mu) {
            b.append(x);
        }
        return b.toString();
    }

    private static synchronized void snap() {
        final Definition d = currentDefinition();
        final Kinematics k = d.build();
        final List<int[]> zeros = k.roundingZeros(Kinematics.SNAP);
        if (zeros.isEmpty()) {
            AppLogger.result("No entry of " + d.name() + "'s S matrix is zero but for rounding: nothing to snap.");
            return;
        }
        if (d.fixed() == null) {
            AppLogger.warn(d.name() + " is defined by its invariants: give the ones that are zero as 0 ("
                + zeros.size() + " entries of S are under 1e-12 of the largest).");
            return;
        }
        final Kinematics snapped = k.snapped(Kinematics.SNAP);
        DEFINED.put(d.name(), new Definition(d.name(), d.type(), d.params(), snapped));
        AppLogger.result(zeros.size() + " entr" + (zeros.size() == 1 ? "y" : "ies") + " of " + d.name()
            + "'s S matrix made exactly 0:");
        for (String l : snapped.describe()) {
            AppLogger.raw("  " + l);
        }
    }

    private static synchronized void use(Args a) {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem use <name>   (':golem list' names them)");
            return;
        }
        final String name = a.plain.get(0);
        if (!DEFINED.containsKey(name)) {
            AppLogger.error("No kinematics '" + name + "' (':golem list' names them).");
            return;
        }
        current = name;
        AppLogger.result(name + " is the current kinematics.");
    }

    private static synchronized void list() {
        if (DEFINED.isEmpty()) {
            AppLogger.info("No kinematics yet: ':golem box s=200 t=-123' defines one.");
            return;
        }
        AppLogger.result(DEFINED.size() + " kinematics:");
        for (Definition d : DEFINED.values()) {
            final StringBuilder p = new StringBuilder();
            for (Map.Entry<String, Double> e : d.params().entrySet()) {
                p.append(' ').append(e.getKey()).append('=').append(num(e.getValue()));
            }
            AppLogger.raw(f("  %s %-12s %-9s%s", d.name().equals(current) ? "*" : " ", d.name(), d.type(), p));
        }
    }

    private static synchronized void forget(Args a) {
        if (a.has("all")) {
            DEFINED.clear();
            current = null;
            AppLogger.result("Every kinematics forgotten.");
            return;
        }
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem forget <name>|--all");
            return;
        }
        for (String n : a.plain) {
            if (DEFINED.remove(n) != null) {
                AppLogger.result(n + " forgotten.");
                if (n.equals(current)) {
                    current = DEFINED.isEmpty() ? null : DEFINED.keySet().iterator().next();
                }
            } else {
                AppLogger.warn("No kinematics " + n + ".");
            }
        }
    }

    private static void show(Args a) {
        final Definition d;
        synchronized (GolemCommands.class) {
            d = a.plain.isEmpty() ? currentDefinition() : DEFINED.get(a.plain.get(0));
        }
        if (d == null) {
            AppLogger.error("No kinematics '" + a.plain.get(0) + "'.");
            return;
        }
        final Kinematics k = d.build();
        AppLogger.result(d.name() + ":");
        for (String l : k.describe()) {
            AppLogger.raw("  " + l);
        }
        AppLogger.raw("  S matrix (Golem95's, S_ij = (q_i - q_j)^2 - m_i^2 - m_j^2):");
        for (int i = 1; i <= k.n(); i++) {
            final StringBuilder row = new StringBuilder("    ");
            for (int j = 1; j <= k.n(); j++) {
                row.append(f("%-24s", cx(k.s(i, j))));
            }
            AppLogger.raw(row.toString().stripTrailing());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Values                                                              */
    /* ------------------------------------------------------------------ */

    private static String num(double v) {
        if (v == 0) {
            return "0";
        }
        final double a = Math.abs(v);
        return a >= 1e-4 && a < 1e7 ? f("%.15g", v).replaceFirst("\\.?0+$", "") : f("%.15e", v);
    }

    private static String cx(Cx z) {
        if (z.im == 0) {
            return num(z.re);
        }
        if (z.re == 0) {
            return num(z.im) + "i";
        }
        return num(z.re) + (z.im < 0 ? " - " : " + ") + num(Math.abs(z.im)) + "i";
    }

    private static void print(Laurent l) {
        final Laurent c = l.in(convention);
        AppLogger.raw("  1/eps^2   " + cx(c.a()));
        AppLogger.raw("  1/eps     " + cx(c.b()));
        AppLogger.raw("  finite    " + cx(c.c()));
    }

    private static String header(String what, Definition d) {
        return f("%s, %s (mu^2 = %s%s):", what, d.name(), num(settings.mu2()),
            convention == Laurent.Convention.R_GAMMA ? "" : ", normalised by " + convention.text());
    }

    /**
     * The tensor held to the identities it satisfies exactly (its contractions with r_j - r_a and its trace are
     * tensors of lower rank, propagators pinched), against the error stated for it.
     */
    private static void identities(Tensors.Tensor t, int[] pinched, double stated) {
        final Tensors.Identities id = Tensors.identities(engine(), t, pinched);
        final int rank = t.rank();
        final String line = f("  exact identities (%d tensors more, %.1f s): contracted with r_j - r_a it is %.0e from those of rank "
            + "%d with j or a pinched%s", id.tensors(), id.nanos() / 1e9, id.contraction(), rank - 1, Double.isNaN(id.trace())
            ? "" : f(", its trace %.0e from those of rank %d", id.trace(), rank - 2));
        if (id.worst() > 10 * Math.max(stated, 1e-13)) {
            AppLogger.warn(line);
            final int digits = Reliability.digitsOf(id.worst());
            AppLogger.warn(f("  that is beyond the error stated (%.0e): by the identities, about %d correct digit%s", stated,
                digits, digits == 1 ? "" : "s"));
        } else {
            AppLogger.raw(line);
            AppLogger.raw("  within the error stated: the identities confirm the digits");
        }
    }

    /** What was asked for, a LoopTools name shown with the Golem95 form factor it is. */
    private static String shown(String prefix, Args a, String what) {
        if (prefix.isEmpty() && !a.plain.isEmpty() && LoopTools.is(a.plain.get(0))) {
            return a.plain.get(0).toLowerCase(Locale.ROOT) + " = " + what;
        }
        return what;
    }

    private static Quantity quantity(String prefix, Args a, int n) {
        final String text = (prefix + String.join(" ", a.plain)).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("what? ':golem ff a40', ':golem ff b42', ':golem ff a43 1 2 3'");
        }
        return Quantity.parse(text, n);
    }

    /** A value with the digits it can be trusted to: computed again for the kinematics moved by its rounding. */
    private static void value(String prefix, Args a) {
        final Definition d = currentDefinition();
        final Kinematics k = d.build();
        final Quantity q = quantity(prefix, a, k.n());
        final Reliability.Report r = Reliability.assess(q, k, settings, Reliability.Options.QUICK);
        if (r.verdict() == Reliability.Verdict.FAILED) {
            if (r.value() != null) {
                AppLogger.result(header(shown(prefix, a, r.value().what()), d));
                print(r.value().laurent());
            }
            AppLogger.error(String.join(" ", r.findings()));
            return;
        }
        AppLogger.result(header(shown(prefix, a, r.value().what()), d));
        print(r.value().laurent());
        final String how = f("  about %d correct digit%s", r.digits(), r.digits() == 1 ? "" : "s");
        switch (r.verdict()) {
            case RELIABLE -> AppLogger.raw(how);
            case ILL_CONDITIONED -> AppLogger.warn(how + ": the integral itself is this sensitive here (':golem check "
                + String.join(" ", a.plain) + "' says why)");
            case INCONSISTENT -> {
                final Reliability.Check c = r.checks().stream().filter(x -> !x.passed()).findFirst().orElseThrow();
                AppLogger.warn(how + (c.name().startsWith("labels")
                    ? f(": Golem95's answer depends on the order of the labels here (%s gives %.0e from it), where the "
                    + "integral does not", c.name(), c.deviation())
                    : f(": an exact property fails (%s, off by %.0e)", c.name(), c.deviation()))
                    + " (':golem check " + String.join(" ", a.plain) + "' says more)");
            }
            default -> AppLogger.warn(how + ": Golem95's evaluation is unstable here; ':golem check "
                + String.join(" ", a.plain) + "' says why and rescues it");
        }
        if (r.value().accuracyWarning()) {
            AppLogger.warn("  Golem95 itself warned that it could not reach the accuracy it was asked for.");
        }
        for (String said : r.value().said()) {
            AppLogger.raw("  Golem95: " + said);
        }
    }

    private static void check(Args a) {
        final Definition d = currentDefinition();
        final Kinematics k = d.build();
        final Quantity q = quantity("", a, k.n());
        final Reliability.Report r = Reliability.assess(q, k, settings, Reliability.Options.DEFAULT);
        final List<String> lines = r.lines();
        AppLogger.result(lines.get(0));
        for (String l : lines.subList(1, lines.size())) {
            if (l.trim().startsWith("FAILED")) {
                AppLogger.warn(l);
            } else {
                AppLogger.raw(l);
            }
        }
        if (convention != Laurent.Convention.R_GAMMA) {
            AppLogger.raw("  (values in Golem95's r_Gamma normalisation; the best one in " + convention.text() + ":)");
            print(r.best());
        }
    }

    private static void scan(Args a) {
        if (a.plain.size() < 4) {
            Handlers.usage(":golem scan <quantity> <parameter> <from> <to> [--points n] [--coef a|b|c] [--part re|im|abs] "
                + "[--csv file] [--even]   (e.g. :golem scan a40 s 50 500)");
            return;
        }
        final Definition d = currentDefinition();
        final int m = a.plain.size();
        final String variable = a.plain.get(m - 3).toLowerCase(Locale.ROOT);
        final double from = number(a.plain.get(m - 2));
        final double to = number(a.plain.get(m - 1));
        final String qText = String.join(" ", a.plain.subList(0, m - 3));
        final Kinematics k0 = d.build();
        final Quantity q = Quantity.parse(qText, k0.n());
        d.with(variable, from);
        final int count = Integer.parseInt(a.opt("points", "101"));
        final char coef = a.opt("coef", "c").toLowerCase(Locale.ROOT).charAt(0);
        final String part = a.opt("part", "re").toLowerCase(Locale.ROOT);
        AppLogger.info(f("Scanning %s of %s over %s = %s .. %s, %d points on %d workers...", q.label(k0.n()), d.name(), variable,
            num(from), num(to), count, Golem95.workers()));
        final Scan.Result r = Scan.run(q, x -> d.with(variable, x), variable, from, to, count, settings, !a.has("even"));
        final List<Scan.Point> good = r.good();
        final double[] xs = new double[good.size()];
        final double[] ys = new double[good.size()];
        for (int i = 0; i < good.size(); i++) {
            final Laurent l = good.get(i).value().in(convention);
            final Cx z = coef == 'a' ? l.a() : coef == 'b' ? l.b() : l.c();
            xs[i] = good.get(i).x();
            ys[i] = part.startsWith("im") ? z.im : part.startsWith("abs") ? Cx.abs(z) : z.re;
        }
        final String coefName = coef == 'a' ? "1/eps^2" : coef == 'b' ? "1/eps" : "finite part";
        final String label = f("%s %s of %s", part.startsWith("im") ? "Im" : part.startsWith("abs") ? "|.|" : "Re", coefName,
            q.label(k0.n()));
        if (xs.length > 0) {
            RootPlotsPanel.instance().showCurve(variable, label, xs, ys);
        }
        AppLogger.result(f("%s over %s = %s .. %s: %d points in %.2f s, in the Plots tab.", label, variable, num(from),
            num(to), r.points().size(), r.nanos() / 1e9));
        for (String n : r.notes()) {
            AppLogger.raw("  " + n);
        }
        if (a.has("csv")) {
            final Path out = Handlers.resolve(a.opt("csv", "golem_scan.csv")).toPath();
            final StringBuilder csv = new StringBuilder(variable + ",re_a,im_a,re_b,im_b,re_c,im_c,noise\n");
            for (Scan.Point p : r.points()) {
                if (!p.ok()) {
                    csv.append(p.x()).append(",,,,,,,\n");
                    continue;
                }
                final Laurent l = p.value().in(convention);
                csv.append(f("%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.3g%n", p.x(), l.a().re, l.a().im, l.b().re, l.b().im,
                    l.c().re, l.c().im, p.noise()));
            }
            try {
                Files.writeString(out, csv.toString(), StandardCharsets.UTF_8);
                AppLogger.raw("  written to " + out + " (every point, the 1/eps^2, 1/eps and finite parts and the noise)");
            } catch (IOException e) {
                AppLogger.error("Could not write " + out + ": " + e.getMessage());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Settings                                                            */
    /* ------------------------------------------------------------------ */

    private static synchronized void set(Args a) {
        GolemEngine.Settings s = settings;
        for (String w : a.plain) {
            final int eq = w.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("'" + w + "': settings are key=value (mu2, rational, tolerance, accuracy, cut)");
            }
            final String k = w.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            final String v = w.substring(eq + 1).trim();
            s = switch (k) {
                case "mu2", "mu^2", "scale" -> s.withMu2(number(v));
                case "mu" -> s.withMu2(number(v) * number(v));
                case "rational", "rat" -> s.withRationalOnly(v.matches("(?i)on|true|yes|1|rat"));
                case "tolerance" -> s.withTolerance(number(v));
                case "accuracy" -> s.withAccuracy(number(v));
                case "cut", "coupure" -> s.withCut(number(v));
                default -> throw new IllegalArgumentException("no setting '" + k + "' (mu2, rational, tolerance, accuracy, cut)");
            };
        }
        settings = s;
        AppLogger.result(a.plain.isEmpty() ? "Golem95's settings:" : "Golem95's settings are now:");
        for (String l : settings.describe()) {
            AppLogger.raw("  " + l);
        }
        AppLogger.raw("  answers   normalised by " + convention.text() + "  (':golem convention')");
    }

    private static synchronized void convention(Args a) {
        if (!a.plain.isEmpty()) {
            convention = Laurent.Convention.parse(a.plain.get(0));
        }
        AppLogger.result("Answers are normalised by " + convention.text() + ".");
        AppLogger.raw("  r_gamma   Golem95's and OneLOop's: r_Gamma = Gamma(1+eps) Gamma(1-eps)^2 / Gamma(1-2eps)");
        AppLogger.raw("  gamma     Gamma(1+eps): the finite part changes by -pi^2/6 times the 1/eps^2 coefficient");
        AppLogger.raw("  msbar     exp(-gamma_E eps): the finite part changes by -pi^2/12 times the 1/eps^2 coefficient");
    }

    private static void table(Args a) {
        int n;
        if (!a.plain.isEmpty()) {
            n = Integer.parseInt(a.plain.get(0));
        } else {
            synchronized (GolemCommands.class) {
                n = current != null && DEFINED.containsKey(current) ? DEFINED.get(current).build().n() : 4;
            }
        }
        if (n < 1 || n > 6) {
            throw new IllegalArgumentException("Golem95 computes 1- to 6-point integrals");
        }
        AppLogger.result(f("Golem95's form factors for %d point%s (A: rank labels; B: rank-2 labels, with g^{mu nu}; "
            + "C: rank-4 labels, with two; D: rank-6, with three):", n, n == 1 ? "" : "s"));
        for (int r = 0; r <= GolemEngine.maxRank(n); r++) {
            final List<String> have = new ArrayList<>();
            for (char t : new char[]{'a', 'b', 'c', 'd'}) {
                if (GolemEngine.missing(t, n, r) == null) {
                    final int labels = r - 2 * (t - 'a');
                    have.add(t + "" + n + r + (labels == 0 ? "" : " " + "l ".repeat(labels).trim()));
                }
            }
            AppLogger.raw(f("  rank %-2d %s%s", r, String.join(",  ", have), r > n ? "   (higher rank)" : ""));
        }
        AppLogger.raw("  labels are propagators 1.." + n + "; '/ 2 4' pinches propagators (the integral without them).");
    }

    private static void bInfo() {
        final Definition d = currentDefinition();
        final Kinematics k = d.build();
        final Cx[] b = engine().set(k).b();
        final Kinematics.Analysis an = k.analyse();
        AppLogger.result("Golem95's b_i = sum_j (S^-1)_ij for " + d.name() + ":");
        for (int i = 0; i < k.n(); i++) {
            AppLogger.raw(f("  b%d = %s", i + 1, cx(b[i])));
        }
        AppLogger.raw(f("  B  = %s   (Sphere's own inversion: %s; |B| max|S| = %.3g)", cx(b[k.n()]), cx(an.sumB()),
            an.normalizedB()));
        if (an.bIsNoise()) {
            AppLogger.warn(f("  B is only rounding here (%.1e of its terms): Golem95's higher-rank reduction divides by it; "
                + "':golem check' rescues what it computes.", an.relativeB()));
        } else if (k.n() >= 3 && k.n() <= 4 && an.normalizedB() <= settings.cut()) {
            AppLogger.raw("  |B| max|S| is under the cut: Golem95 integrates the basis integrals numerically.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Trust, the Fortran, citations                                       */
    /* ------------------------------------------------------------------ */

    private static void validate() throws IOException {
        if (!GolemValidation.referencesAvailable()) {
            AppLogger.error("The Fortran's reference outputs are missing from this build of Sphere.");
            return;
        }
        AppLogger.info("The Java Golem95 against gfortran's, on " + Golem95.workers() + " workers...");
        final GolemValidation.Outcome o = GolemValidation.run();
        if (o.passed()) {
            AppLogger.success(o.line());
        } else {
            AppLogger.warn(o.line());
            for (GolemValidation.Miss m : o.misses().subList(0, Math.min(10, o.misses().size()))) {
                AppLogger.raw(f("  case %d, %s: %.1e from the Fortran (whose own spread there is %.1e)", m.caseNumber(),
                    m.formFactor(), m.deviation(), m.spread()));
            }
        }
        AppLogger.info("The interfaces (LoopTools-style functions, tensors) against gfortran's on 36 kinematics, and the "
            + "tensors against exact identities...");
        final long t0 = System.nanoTime();
        for (GolemValidation.Part p : GolemValidation.interfaces()) {
            if (p.passed()) {
                AppLogger.success(p.line());
            } else {
                AppLogger.warn(p.line());
                for (String m : p.misses().subList(0, Math.min(10, p.misses().size()))) {
                    AppLogger.raw("  " + m);
                }
            }
        }
        AppLogger.raw(f("  (%.1f s) Golem95's own ti* serve no one in Sphere: in 1.3.4 their init_smat cannot work and the "
            + "rank N+1 of N = 2..5 is wrong or stops; Sphere's tensors are built from the form factors.",
            (System.nanoTime() - t0) / 1e9));
    }

    private static String compiler() {
        return Bridge.fortranCompiler(new SettingsManager());
    }

    private static void nativeCommand(Args a) {
        final String what = a.plain.isEmpty() ? "status" : a.plain.get(0).toLowerCase(Locale.ROOT);
        switch (what) {
            case "status" -> {
                final GolemNative.Library l = GolemNative.find();
                AppLogger.result("The Fortran Golem95:");
                AppLogger.raw("  found     " + (l == null ? "none (GOLEM95_DIR unset, no golem95.pc, not built yet)" : l.origin()
                    + (l.version() == null ? "" : ", version " + l.version())));
                AppLogger.raw("  sources   " + (GolemNative.sourcesCarried() ? "Golem95 " + Golem95.VERSION + " and OneLOop "
                    + Golem95.ONELOOP_VERSION + ", carried by this Sphere" : "missing from this build"));
                final String fc = compiler();
                AppLogger.raw("  compiler  " + (fc == null ? "none (ENV_FC or FORTRAN_DIR in settings.conf)" : fc
                    + (GolemNative.built(fc) != null ? ", Golem95 built for it" : ", ':golem native build' builds Golem95 for it")));
                AppLogger.raw("  programs  a Fortran program run from Sphere that uses matrice_s, form_factor_4p ... is linked "
                    + "against it without asking");
            }
            case "build" -> {
                final String fc = compiler();
                if (fc == null) {
                    AppLogger.error("No Fortran compiler: set ENV_FC or FORTRAN_DIR in settings.conf.");
                    return;
                }
                final Thread t = new Thread(() -> {
                    final long t0 = System.nanoTime();
                    try {
                        AppLogger.info("Building Golem95 " + Golem95.VERSION + " and OneLOop " + Golem95.ONELOOP_VERSION
                            + " with " + fc + " (a few minutes)...");
                        final GolemNative.Library l = GolemNative.build(fc, AppLogger::raw);
                        AppLogger.success(f("Golem95 built in %.0f s: %s (modules in %s).", (System.nanoTime() - t0) / 1e9,
                            l.archive(), l.include()));
                    } catch (IOException | RuntimeException e) {
                        AppLogger.error(e.getMessage());
                    }
                }, "golem95-build");
                t.setDaemon(true);
                t.start();
            }
            case "test" -> nativeTest();
            default -> Handlers.usage(":golem native [status|build|test]");
        }
    }

    /** The test program: the same integrals by the Fortran Golem95, printed to the bit. */
    private static final String TEST = String.join("\n",
        "program sphere_golem_test",
        "  use precision_golem",
        "  use matrice_s",
        "  use form_factor_type",
        "  use form_factor_4p",
        "  use form_factor_higher_ranks",
        "  use parametre",
        "  implicit none",
        "  real(ki), dimension(4,4) :: sr",
        "  integer :: i, j",
        "  sr = reshape((/ -0.6_ki, 0.7_ki, -5.4_ki, 1.0_ki, 0.7_ki, -1.4_ki, 0.2_ki, 17.0_ki, &",
        "                  -5.4_ki, 0.2_ki, -2.2_ki, 0.1_ki, 1.0_ki, 17.0_ki, 0.1_ki, -3.8_ki /), (/ 4, 4 /))",
        "  call initgolem95(4)",
        "  do i = 1, 4",
        "     do j = 1, 4",
        "        s_mat(i,j) = cmplx(sr(i,j), 0._ki, ki)",
        "     end do",
        "  end do",
        "  call preparesmatrix()",
        "  call out(a40(s_null)); call out(a42(1,3,s_null)); call out(b43(2,s_null)); call out(c44(s_null))",
        "  call out(a45(1,1,2,3,4,s_null))",
        "  call exitgolem95()",
        "contains",
        "  subroutine out(r)",
        "    type(form_factor), intent(in) :: r",
        "    write(*,'(6ES26.17E3)') real(r%a,ki), aimag(r%a), real(r%b,ki), aimag(r%b), real(r%c,ki), aimag(r%c)",
        "  end subroutine out",
        "end program sphere_golem_test",
        "");

    private static final String[] TESTED = {"a40", "a42 1 3", "b43 2", "c44", "a45 1 1 2 3 4"};

    private static void nativeTest() {
        final String fc = compiler();
        if (fc == null) {
            AppLogger.error("No Fortran compiler: set ENV_FC or FORTRAN_DIR in settings.conf.");
            return;
        }
        final Thread t = new Thread(() -> {
            try {
                GolemNative.Library l = GolemNative.built(fc);
                if (l == null) {
                    AppLogger.info("Golem95 is not built for " + fc + " yet: building it first (a few minutes)...");
                    l = GolemNative.build(fc, AppLogger::raw);
                }
                final Path dir = Bridge.buildFolder().resolve("golem95-test");
                Files.createDirectories(dir);
                final Path src = dir.resolve("sphere_golem_test.f90");
                Files.writeString(src, TEST, StandardCharsets.UTF_8);
                final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
                final Path exe = dir.resolve(windows ? "sphere_golem_test.exe" : "sphere_golem_test");
                final String toolDir = new java.io.File(fc).getAbsoluteFile().getParent();
                final List<String> cmd = new ArrayList<>(List.of(fc, "-O2"));
                cmd.addAll(l.compileFlags());
                cmd.add(src.toString());
                cmd.addAll(l.linkFlags());
                cmd.addAll(List.of("-o", exe.toString()));
                final Bridge.Outcome built = Bridge.run(cmd, dir, 300, toolDir);
                if (!built.ok()) {
                    AppLogger.error("The test program does not build:\n" + built.output());
                    return;
                }
                final Bridge.Outcome ran = Bridge.run(List.of(exe.toString()), dir, 120, toolDir);
                final List<String> lines = ran.output().lines().filter(s -> s.matches("\\s*[-+0-9].*")).toList();
                if (!ran.ok() || lines.size() < TESTED.length) {
                    AppLogger.error("The test program failed:\n" + ran.output());
                    return;
                }
                final double[][] s = {{-0.6, 0.7, -5.4, 1.0}, {0.7, -1.4, 0.2, 17.0}, {-5.4, 0.2, -2.2, 0.1}, {1.0, 17.0, 0.1, -3.8}};
                final Cx[][] m = new Cx[4][4];
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        m[i][j] = Cx.of(s[i][j]);
                    }
                }
                final Kinematics box = Kinematics.ofS(m, "test");
                AppLogger.result("The Fortran Golem95 (" + l.archive() + ") and the Java one, same box:");
                int same = 0;
                int agree = 0;
                for (int i = 0; i < TESTED.length; i++) {
                    final String[] w = lines.get(i).trim().split("\\s+");
                    final double[] fv = new double[6];
                    for (int c = 0; c < 6; c++) {
                        fv[c] = Double.parseDouble(w[c]);
                    }
                    // the Java's value with its own noise: what the last digits of either can be trusted to
                    final Reliability.Report r = Reliability.assess(Quantity.parse(TESTED[i], 4), box,
                        GolemEngine.Settings.DEFAULT, Reliability.Options.QUICK);
                    final Laurent j = r.value().laurent();
                    final double[] jv = {j.a().re, j.a().im, j.b().re, j.b().im, j.c().re, j.c().im};
                    double d = 0;
                    double scale = 0;
                    boolean bits = true;
                    for (int c = 0; c < 6; c++) {
                        d = Math.max(d, Math.abs(fv[c] - jv[c]));
                        scale = Math.max(scale, Math.abs(fv[c]));
                        bits &= fv[c] == jv[c];
                    }
                    d /= scale == 0 ? 1 : scale;
                    // a difference within ten times the value's own noise (or a few ulps) is a difference of rounding
                    final boolean rounding = d <= Math.max(10 * r.noise(), 1e-14);
                    same += bits ? 1 : 0;
                    agree += bits || rounding ? 1 : 0;
                    AppLogger.raw(f("  %-14s %s", TESTED[i], bits ? "identical to the bit"
                        : f("%.1e apart (relative), %s its rounding noise %.1e", d, rounding ? "within" : "BEYOND", r.noise())));
                }
                if (same == TESTED.length) {
                    AppLogger.success("The Java Golem95 is the Fortran's to the bit on this box.");
                } else if (agree == TESTED.length) {
                    AppLogger.success("The Java Golem95 gives the Fortran's values to their rounding on this box: where they "
                        + "differ, it is in the last bits, within each value's own noise.");
                } else {
                    AppLogger.warn((TESTED.length - agree) + " of " + TESTED.length + " values differ beyond their rounding: "
                        + "':golem validate' compares 3939 of them.");
                }
            } catch (IOException | RuntimeException failed) {
                AppLogger.error(failed.getMessage());
            }
        }, "golem95-native-test");
        t.setDaemon(true);
        t.start();
    }

    private static void bib(Args a) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":golem bib <file.bib> [--all]");
            return;
        }
        final boolean all = a.has("all");
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        Files.writeString(out, Golem95Citations.bibtex(all), StandardCharsets.UTF_8);
        AppLogger.result(f("%d references written to %s.", Golem95Citations.bibliography(all).size(), out));
    }

    /* ------------------------------------------------------------------ */
    /* help and diag                                                       */
    /* ------------------------------------------------------------------ */

    private static void help() {
        AppLogger.result("Golem95 in Java (Golem95 " + Golem95.VERSION + " and OneLOop " + Golem95.ONELOOP_VERSION
            + " translated, checked against gfortran). One-loop integrals, each answer with its correct digits:");
        AppLogger.raw("  kinematics  :golem box s=200 t=-123 | box s=4e4 t=-1.2e4 p3=6400 m3=80.4 w3=2.1 as wbox | triangle p1=..");
        AppLogger.raw("              smatrix \"0 0 -123 0; 0 0 0 200; ...\" | momenta \"E px py pz; ...\" | use | list | show");
        AppLogger.raw("              legs p_i are invariants p_i^2 (leg i enters between propagators i-1 and i), s = (p1+p2)^2,");
        AppLogger.raw("              t = (p2+p3)^2, s123 ...; masses m_i (or msq_i), widths w_i: m^2 - i m w");
        AppLogger.raw("  answers     :golem ff a40 | ff a43 1 2 3 | ff b42 | ff c44 | ff a45 1 1 2 3 4 | int n+2 1 2 | scalar | ff a30 / 4");
        AppLogger.raw("              LoopTools' names: :golem lt dd0012 | lt cc001 | lt ee00123   (D form factors too: ff d46)");
        AppLogger.raw("  tensors     :golem propagators \"<r_1>; <r_2>; ...\" m1=.. | tensor 3 | tensor 2 0 1 | tensor 5 --csv t.csv");
        AppLogger.raw("              (any rank up to 7, built from the form factors; Golem95's own ti* are broken in 1.3.4)");
        AppLogger.raw("  trust       :golem check a43 1 2 3   (noise, conditioning, units, mu^2, renamed propagators, label order, "
            + "OneLOop, rescue)");
        AppLogger.raw("  scans       :golem scan a40 s 50 500 --points 200 [--coef b] [--part im] [--csv out.csv]");
        AppLogger.raw("  settings    :golem set mu2=91.19^2 | set rational=on | convention msbar | table 5 | b");
        AppLogger.raw("  Fortran     :golem validate | native status | native build | native test   (a Fortran program that");
        AppLogger.raw("              uses matrice_s, form_factor_4p ... and is run from Sphere is linked against Golem95)");
        AppLogger.raw("  engine      :golem ping | diag | mode      references: Citations > Citation Golem95, :golem bib <file.bib>");
    }

    /** The rows ':golem diag' shows beside the probe. */
    static void diagRows() {
        EngineCommands.row("port", "Golem95 " + Golem95.VERSION + " and OneLOop " + Golem95.ONELOOP_VERSION
            + ", every module translated by Sphere's Fortran translator; engines independent, " + Golem95.workers() + " in parallel");
        EngineCommands.row("trust", "every answer with its correct digits; ':golem check' (noise, conditioning, exact checks, rescue)");
        if (GolemValidation.referencesAvailable()) {
            try {
                final GolemValidation.Outcome o = GolemValidation.run(12);
                EngineCommands.row("versus F90", o.line() + "; all 300: ':golem validate'");
            } catch (IOException | RuntimeException e) {
                EngineCommands.row("versus F90", "could not run: " + e.getMessage());
            }
        } else {
            EngineCommands.row("references", "the Fortran's outputs are missing from this build");
        }
        final String fc = compiler();
        EngineCommands.row("sources", (GolemNative.sourcesCarried() ? "carried" : "missing") + "; compiler "
            + (fc == null ? "none" : fc) + "; ':golem native build | test'");
        EngineCommands.row("citations", "console menu > Citations > Citation Golem95; ':golem bib <file.bib>'");
    }
}
