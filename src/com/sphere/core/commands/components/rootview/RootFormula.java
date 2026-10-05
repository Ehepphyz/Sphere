package com.sphere.components.rootview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A formula as TFormula reads it: the variables x, y, z and t, parameters
 * [0] or [name], the operators + - * / ^ ** and the comparisons and logic
 * TFormula allows, the functions of the C library and of TMath, pi and e,
 * and ROOT's predefined shapes:
 *
 *   gaus, gausn, expo, landau, landaun, polN, chebyshevN, breitwigner,
 *   crystalball, xygaus, bigaus (as xygaus)
 *
 * written alone, added together ("gaus+pol1"), or with the first parameter
 * they take ("gaus(0)+expo(3)"). A sum of them numbers its parameters one
 * after the other, as ROOT does.
 */
public final class RootFormula {

    private final String text;
    private final Node root;
    private final List<String> names = new ArrayList<>();
    /** The predefined pieces of the formula, with their first parameter, for the first guess of a fit. */
    public final List<Piece> pieces = new ArrayList<>();

    /** A predefined shape used in the formula: its name ("gaus", "pol2") and its first parameter. */
    public record Piece(String name, int first, int count) {
    }

    private RootFormula(String text) {
        this.text = text;
        final String expanded = expand(text.strip());
        final Parser p = new Parser(expanded);
        this.root = p.expression();
        p.skip();
        if (p.pos < p.s.length()) throw new IllegalArgumentException("unexpected '" + p.s.substring(p.pos) + "' in " + text);
    }

    public static RootFormula parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("an empty formula");
        return new RootFormula(text);
    }

    public String text() {
        return text;
    }

    /** How many parameters the formula takes. */
    public int parameters() {
        return names.size();
    }

    public String parameterName(int i) {
        return i < names.size() ? names.get(i) : "p" + i;
    }

    public List<String> parameterNames() {
        return List.copyOf(names);
    }

    public double eval(double x, double[] p) {
        return root.eval(new double[]{x, 0, 0, 0}, p);
    }

    public double eval(double x, double y, double[] p) {
        return root.eval(new double[]{x, y, 0, 0}, p);
    }

    public double eval(double[] vars, double[] p) {
        return root.eval(vars, p);
    }

    /**
     * The value at vars and its gradient with respect to the parameters into
     * grad, by forward-mode automatic differentiation of the formula's tree:
     * the derivative of each operation and C-library function exactly, a
     * central difference only for the functions given by approximations
     * (erf, landau, crystalball...), so that a fit can hand Minuit2 a true
     * gradient instead of finite differences of the whole function.
     */
    public double evalGradient(double[] vars, double[] p, double[] grad) {
        final Dual d = root.dual(vars, p, grad.length);
        java.util.Arrays.fill(grad, 0);
        if (d.d != null) System.arraycopy(d.d, 0, grad, 0, Math.min(grad.length, d.d.length));
        return d.v;
    }

    /** A value with its derivatives with respect to the parameters (null: all zero). */
    private record Dual(double v, double[] d) {
        static Dual of(double v) {
            return new Dual(v, null);
        }
    }

    private static double[] lin(double a, double[] da, double b, double[] db, int np) {
        if (da == null && db == null) return null;
        final double[] r = new double[np];
        if (da != null && a != 0) for (int i = 0; i < np; i++) r[i] += a * da[i];
        if (db != null && b != 0) for (int i = 0; i < np; i++) r[i] += b * db[i];
        return r;
    }

    /** The derivative of a call with respect to its argument k: exact where known, else a central difference. */
    private static double partial(String name, double[] a, int k, double value) {
        final double x = a.length > 0 ? a[0] : 0;
        if (k == 0) {
            switch (name) {
                case "sin": return Math.cos(x);
                case "cos": return -Math.sin(x);
                case "tan": {
                    final double c = Math.cos(x);
                    return 1 / (c * c);
                }
                case "asin": return 1 / Math.sqrt(1 - x * x);
                case "acos": return -1 / Math.sqrt(1 - x * x);
                case "atan": return 1 / (1 + x * x);
                case "sinh": return Math.cosh(x);
                case "cosh": return Math.sinh(x);
                case "tanh": return 1 - value * value;
                case "asinh": return 1 / Math.sqrt(x * x + 1);
                case "acosh": return 1 / Math.sqrt(x * x - 1);
                case "atanh": return 1 / (1 - x * x);
                case "exp": return value;
                case "log": case "ln": return 1 / x;
                case "log10": return 1 / (x * Math.log(10));
                case "log2": return 1 / (x * Math.log(2));
                case "sqrt": return 0.5 / value;
                case "cbrt": return 1 / (3 * value * value);
                case "abs": return x > 0 ? 1 : x < 0 ? -1 : 0;
                case "sign": case "floor": case "ceil": case "nint": case "round": return 0;
                case "sq": return 2 * x;
                case "pow": return a[1] * Math.pow(x, a[1] - 1);
                case "min": return x <= a[1] ? 1 : 0;
                case "max": return x >= a[1] ? 1 : 0;
                case "atan2": return a[1] / (x * x + a[1] * a[1]);
                default: break;
            }
        } else if (k == 1) {
            switch (name) {
                case "pow": return value * Math.log(x);
                case "min": return x <= a[1] ? 0 : 1;
                case "max": return x >= a[1] ? 0 : 1;
                case "atan2": return -x / (x * x + a[1] * a[1]);
                default: break;
            }
        }
        // a central difference on the argument, for the functions given by approximations
        final double h = 1e-6 * Math.max(Math.abs(a[k]), 1e-3);
        final double[] up = a.clone();
        final double[] dn = a.clone();
        up[k] += h;
        dn[k] -= h;
        return (call(name, up) - call(name, dn)) / (2 * h);
    }

    /** Whether the formula uses y: a TF2. */
    public boolean usesY() {
        return root.uses(1);
    }

    /* ------------------------------------------------------------------ */
    /* Predefined shapes                                                   */
    /* ------------------------------------------------------------------ */

    private int next;

    /** The predefined names replaced by their expressions, numbered from the parameter they start at. */
    private String expand(String f) {
        final StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < f.length()) {
            final char c = f.charAt(i);
            if (Character.isLetter(c) && (i == 0 || !Character.isLetterOrDigit(f.charAt(i - 1)) && f.charAt(i - 1) != '_'
                && f.charAt(i - 1) != ':' && f.charAt(i - 1) != '[')) {
                int j = i;
                while (j < f.length() && (Character.isLetterOrDigit(f.charAt(j)) || f.charAt(j) == '_')) j++;
                final String word = f.substring(i, j);
                Integer offset = null;
                int end = j;
                if (j < f.length() && f.charAt(j) == '(') {
                    final int close = f.indexOf(')', j);
                    if (close > j && f.substring(j + 1, close).strip().matches("\\d+")) {
                        offset = Integer.parseInt(f.substring(j + 1, close).strip());
                        end = close + 1;
                    }
                }
                final String shape = shape(word, offset);
                if (shape != null && (offset != null || j >= f.length() || f.charAt(j) != '(')) {
                    out.append('(').append(shape).append(')');
                    i = end;
                    continue;
                }
                out.append(word);
                i = j;
                continue;
            }
            if (c == '[') {
                final int close = f.indexOf(']', i);
                if (close > i) {
                    final String inside = f.substring(i + 1, close).strip();
                    final int index;
                    if (inside.matches("\\d+")) {
                        index = Integer.parseInt(inside);
                        while (names.size() <= index) names.add("p" + names.size());
                    } else {
                        final int at = names.indexOf(inside);
                        if (at >= 0) {
                            index = at;
                        } else {
                            index = names.size();
                            names.add(inside);
                        }
                    }
                    next = Math.max(next, index + 1);
                    out.append("[").append(index).append("]");
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private String p(int k, String name) {
        while (names.size() <= k) names.add("p" + names.size());
        if (names.get(k).startsWith("p") && names.get(k).substring(1).matches("\\d+")) names.set(k, name);
        next = Math.max(next, k + 1);
        return "[" + k + "]";
    }

    /** The expression of a predefined shape, or null when the word is not one. */
    private String shape(String word, Integer offset) {
        final String w = word.toLowerCase(Locale.ROOT);
        final int o = offset == null ? next : offset;
        final String x = "x";
        switch (w) {
            case "gaus", "gausn", "xgaus" -> {
                pieces.add(new Piece(w, o, 3));
                final String a = p(o, "Constant");
                final String m = p(o + 1, "Mean");
                final String s = p(o + 2, "Sigma");
                return w.equals("gausn")
                    ? a + "*exp(-0.5*((" + x + "-" + m + ")/" + s + ")^2)/(sqrt(2*pi)*" + s + ")"
                    : a + "*exp(-0.5*((" + x + "-" + m + ")/" + s + ")^2)";
            }
            case "ygaus" -> {
                pieces.add(new Piece(w, o, 3));
                return p(o, "Constant") + "*exp(-0.5*((y-" + p(o + 1, "MeanY") + ")/" + p(o + 2, "SigmaY") + ")^2)";
            }
            case "xygaus", "bigaus" -> {
                pieces.add(new Piece(w, o, 5));
                return p(o, "Constant") + "*exp(-0.5*((x-" + p(o + 1, "MeanX") + ")/" + p(o + 2, "SigmaX")
                    + ")^2-0.5*((y-" + p(o + 3, "MeanY") + ")/" + p(o + 4, "SigmaY") + ")^2)";
            }
            case "expo", "xexpo" -> {
                pieces.add(new Piece("expo", o, 2));
                return "exp(" + p(o, "Constant") + "+" + p(o + 1, "Slope") + "*" + x + ")";
            }
            case "landau", "landaun" -> {
                pieces.add(new Piece(w, o, 3));
                return p(o, "Constant") + "*landau(" + x + "," + p(o + 1, "MPV") + "," + p(o + 2, "Sigma")
                    + (w.equals("landaun") ? ",1" : ",0") + ")";
            }
            case "breitwigner" -> {
                pieces.add(new Piece(w, o, 3));
                return p(o, "Constant") + "*breitwigner(" + x + "," + p(o + 1, "Mean") + "," + p(o + 2, "Gamma") + ")";
            }
            case "crystalball" -> {
                pieces.add(new Piece(w, o, 5));
                return p(o, "Constant") + "*crystalball(" + x + "," + p(o + 1, "Mean") + "," + p(o + 2, "Sigma") + ","
                    + p(o + 3, "Alpha") + "," + p(o + 4, "N") + ")";
            }
            default -> {
            }
        }
        if (w.matches("pol\\d+")) {
            final int n = Integer.parseInt(w.substring(3));
            pieces.add(new Piece("pol" + n, o, n + 1));
            final StringBuilder b = new StringBuilder();
            for (int k = 0; k <= n; k++) {
                if (k > 0) b.append('+');
                b.append(p(o + k, "p" + k));
                if (k == 1) b.append("*x");
                if (k > 1) b.append("*x^").append(k);
            }
            return b.toString();
        }
        if (w.matches("chebyshev\\d+") || w.matches("cheb\\d+")) {
            final int n = Integer.parseInt(w.replaceAll("\\D", ""));
            pieces.add(new Piece("cheb" + n, o, n + 1));
            final StringBuilder b = new StringBuilder();
            for (int k = 0; k <= n; k++) {
                if (k > 0) b.append('+');
                b.append(p(o + k, "c" + k)).append("*cheb(").append(k).append(",x)");
            }
            return b.toString();
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* The tree                                                            */
    /* ------------------------------------------------------------------ */

    private interface Node {
        double eval(double[] v, double[] p);

        /** The value and its derivatives with respect to the np parameters. */
        Dual dual(double[] v, double[] p, int np);

        default boolean uses(int var) {
            return false;
        }
    }

    private record Num(double value) implements Node {
        public double eval(double[] v, double[] p) {
            return value;
        }

        public Dual dual(double[] v, double[] p, int np) {
            return Dual.of(value);
        }
    }

    private record Var(int index) implements Node {
        public double eval(double[] v, double[] p) {
            return index < v.length ? v[index] : 0;
        }

        public Dual dual(double[] v, double[] p, int np) {
            return Dual.of(eval(v, p));
        }

        public boolean uses(int var) {
            return var == index;
        }
    }

    private record Par(int index) implements Node {
        public double eval(double[] v, double[] p) {
            return p != null && index < p.length ? p[index] : 0;
        }

        public Dual dual(double[] v, double[] p, int np) {
            if (index >= np) return Dual.of(eval(v, p));
            final double[] d = new double[np];
            d[index] = 1;
            return new Dual(eval(v, p), d);
        }
    }

    private record Bin(char op, Node a, Node b) implements Node {
        public double eval(double[] v, double[] p) {
            final double x = a.eval(v, p);
            final double y = b.eval(v, p);
            return switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> x / y;
                case '^' -> com.sphere.core.minuit2.Cxx.pow(x, y);
                case '<' -> x < y ? 1 : 0;
                case '>' -> x > y ? 1 : 0;
                case 'l' -> x <= y ? 1 : 0;
                case 'g' -> x >= y ? 1 : 0;
                case '=' -> x == y ? 1 : 0;
                case '!' -> x != y ? 1 : 0;
                case '&' -> x != 0 && y != 0 ? 1 : 0;
                case '|' -> x != 0 || y != 0 ? 1 : 0;
                case '%' -> x % y;
                default -> Double.NaN;
            };
        }

        public Dual dual(double[] v, double[] p, int np) {
            final Dual x = a.dual(v, p, np);
            final Dual y = b.dual(v, p, np);
            final double xv = x.v();
            final double yv = y.v();
            switch (op) {
                case '+':
                    return new Dual(xv + yv, lin(1, x.d(), 1, y.d(), np));
                case '-':
                    return new Dual(xv - yv, lin(1, x.d(), -1, y.d(), np));
                case '*':
                    return new Dual(xv * yv, lin(yv, x.d(), xv, y.d(), np));
                case '/': {
                    final double q = xv / yv;
                    return new Dual(q, lin(1 / yv, x.d(), -q / yv, y.d(), np));
                }
                case '^': {
                    final double w = Math.pow(xv, yv);
                    final double dx = x.d() == null ? 0 : yv * Math.pow(xv, yv - 1);
                    final double dy = y.d() == null ? 0 : w * Math.log(xv);
                    return new Dual(w, lin(dx, x.d(), dy, y.d(), np));
                }
                case '%':
                    return new Dual(xv % yv, lin(1, x.d(), -(double) (long) (xv / yv), y.d(), np));
                default:
                    return Dual.of(eval(v, p));
            }
        }

        public boolean uses(int var) {
            return a.uses(var) || b.uses(var);
        }
    }

    private record Neg(Node a, boolean not) implements Node {
        public double eval(double[] v, double[] p) {
            final double x = a.eval(v, p);
            return not ? (x == 0 ? 1 : 0) : -x;
        }

        public Dual dual(double[] v, double[] p, int np) {
            if (not) return Dual.of(eval(v, p));
            final Dual x = a.dual(v, p, np);
            return new Dual(-x.v(), lin(-1, x.d(), 0, null, np));
        }

        public boolean uses(int var) {
            return a.uses(var);
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        public double eval(double[] v, double[] p) {
            final double[] a = new double[args.size()];
            for (int i = 0; i < a.length; i++) a[i] = args.get(i).eval(v, p);
            return call(name, a);
        }

        public Dual dual(double[] v, double[] p, int np) {
            final double[] a = new double[args.size()];
            final double[][] da = new double[a.length][];
            boolean any = false;
            for (int i = 0; i < a.length; i++) {
                final Dual d = args.get(i).dual(v, p, np);
                a[i] = d.v();
                da[i] = d.d();
                any |= d.d() != null;
            }
            final double value = call(name, a);
            if (!any) return Dual.of(value);
            final double[] g = new double[np];
            for (int k = 0; k < a.length; k++) {
                if (da[k] == null) continue;
                final double dk = partial(name, a, k, value);
                if (dk == 0) continue;
                for (int i = 0; i < np; i++) g[i] += dk * da[k][i];
            }
            return new Dual(value, g);
        }

        public boolean uses(int var) {
            for (Node n : args) if (n.uses(var)) return true;
            return false;
        }
    }

    static double call(String name, double[] a) {
        final double x = a.length > 0 ? a[0] : 0;
        return switch (name) {
            case "sin" -> com.sphere.core.minuit2.Cxx.sin(x);
            case "cos" -> com.sphere.core.minuit2.Cxx.cos(x);
            case "tan" -> Math.tan(x);
            case "asin" -> Math.asin(x);
            case "acos" -> Math.acos(x);
            case "atan" -> com.sphere.core.minuit2.Cxx.atan(x);
            case "atan2" -> Math.atan2(x, a[1]);
            case "sinh" -> Math.sinh(x);
            case "cosh" -> Math.cosh(x);
            case "tanh" -> Math.tanh(x);
            case "asinh" -> Math.log(x + Math.sqrt(x * x + 1));
            case "acosh" -> Math.log(x + Math.sqrt(x * x - 1));
            case "atanh" -> 0.5 * Math.log((1 + x) / (1 - x));
            case "exp" -> com.sphere.core.minuit2.Cxx.exp(x);
            case "log", "ln" -> com.sphere.core.minuit2.Cxx.log(x);
            case "log10" -> com.sphere.core.minuit2.Cxx.log10(x);
            case "log2" -> Math.log(x) / Math.log(2);
            case "sqrt" -> Math.sqrt(x);
            case "cbrt" -> Math.cbrt(x);
            case "abs", "fabs" -> Math.abs(x);
            case "sign" -> Math.signum(x);
            case "floor" -> Math.floor(x);
            case "ceil" -> Math.ceil(x);
            case "nint", "round" -> Math.rint(x);
            case "pow", "power" -> com.sphere.core.minuit2.Cxx.pow(x, a[1]);
            case "sq" -> x * x;
            case "min" -> Math.min(x, a[1]);
            case "max" -> Math.max(x, a[1]);
            case "erf" -> erf(x);
            case "erfc" -> 1 - erf(x);
            case "gaus" -> a.length >= 3 ? com.sphere.core.minuit2.Cxx.exp(-0.5 * Math.pow((x - a[1]) / a[2], 2))
                * (a.length >= 4 && a[3] != 0 ? 1 / (Math.sqrt(2 * Math.PI) * a[2]) : 1) : Math.exp(-0.5 * x * x);
            case "landau" -> landau(x, a.length > 1 ? a[1] : 0, a.length > 2 ? a[2] : 1, a.length > 3 && a[3] != 0);
            case "breitwigner" -> {
                final double g = a.length > 2 ? a[2] : 1;
                final double m = a.length > 1 ? a[1] : 0;
                yield 0.5 * g / Math.PI / ((x - m) * (x - m) + 0.25 * g * g);
            }
            case "crystalball" -> crystalBall(x, a[1], a[2], a[3], a[4]);
            case "cheb" -> chebyshev((int) Math.rint(x), a[1]);
            case "pi" -> Math.PI;
            case "e" -> Math.E;
            default -> throw new IllegalArgumentException("unknown function " + name);
        };
    }

    static double erf(double x) {
        final double t = 1 / (1 + 0.5 * Math.abs(x));
        final double y = 1 - t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223
            + t * 0.17087277)))))))));
        return x >= 0 ? y : -y;
    }

    /** TMath::Landau, by CERNLIB's DENLAN rational approximations; normalised when asked. */
    static double landau(double x, double mpv, double sigma, boolean norm) {
        if (sigma <= 0) return 0;
        final double v = (x - mpv) / sigma;
        final double[] p1 = {0.4259894875, -0.1249762550, 0.03984243700, -0.006298287635, 0.001511162253};
        final double[] q1 = {1.0, -0.3388260629, 0.09594393323, -0.01608042283, 0.003778942063};
        final double[] p2 = {0.1788541609, 0.1173957403, 0.01488850518, -0.001394989411, 0.0001283617211};
        final double[] q2 = {1.0, 0.7428795082, 0.3153932961, 0.06694219548, 0.008790609714};
        final double[] p3 = {0.1788544503, 0.09359161662, 0.006325387654, 0.00006611667319, -0.000002031049101};
        final double[] q3 = {1.0, 0.6097809921, 0.2560616665, 0.04746722384, 0.006957301675};
        final double[] p4 = {0.9874054407, 118.6723273, 849.2794360, -743.7792444, 427.0262186};
        final double[] q4 = {1.0, 106.8615961, 337.6496214, 2016.712389, 1597.063511};
        final double[] p5 = {1.003675074, 167.5702434, 4789.711289, 21217.86767, -22324.94910};
        final double[] q5 = {1.0, 156.9424537, 3745.310488, 9834.698876, 66924.28357};
        final double[] p6 = {1.000827619, 664.9143136, 62972.92665, 475554.6998, -5743609.109};
        final double[] q6 = {1.0, 651.4101098, 56974.73333, 165917.4725, -2815759.939};
        final double[] a1 = {0.04166666667, -0.01996527778, 0.02709538966};
        final double[] a2 = {-1.845568670, -4.284640743};
        double den;
        if (v < -5.5) {
            final double u = Math.exp(v + 1.0);
            if (u < 1e-10) return 0.0;
            final double ue = Math.exp(-1 / u);
            final double us = Math.sqrt(u);
            den = 0.3989422803 * (ue / us) * (1 + (a1[0] + (a1[1] + a1[2] * u) * u) * u);
        } else if (v < -1) {
            final double u = Math.exp(-v - 1);
            den = Math.exp(-u) * Math.sqrt(u) * (p1[0] + (p1[1] + (p1[2] + (p1[3] + p1[4] * v) * v) * v) * v)
                / (q1[0] + (q1[1] + (q1[2] + (q1[3] + q1[4] * v) * v) * v) * v);
        } else if (v < 1) {
            den = (p2[0] + (p2[1] + (p2[2] + (p2[3] + p2[4] * v) * v) * v) * v)
                / (q2[0] + (q2[1] + (q2[2] + (q2[3] + q2[4] * v) * v) * v) * v);
        } else if (v < 5) {
            den = (p3[0] + (p3[1] + (p3[2] + (p3[3] + p3[4] * v) * v) * v) * v)
                / (q3[0] + (q3[1] + (q3[2] + (q3[3] + q3[4] * v) * v) * v) * v);
        } else if (v < 12) {
            final double u = 1 / v;
            den = u * u * (p4[0] + (p4[1] + (p4[2] + (p4[3] + p4[4] * u) * u) * u) * u)
                / (q4[0] + (q4[1] + (q4[2] + (q4[3] + q4[4] * u) * u) * u) * u);
        } else if (v < 50) {
            final double u = 1 / v;
            den = u * u * (p5[0] + (p5[1] + (p5[2] + (p5[3] + p5[4] * u) * u) * u) * u)
                / (q5[0] + (q5[1] + (q5[2] + (q5[3] + q5[4] * u) * u) * u) * u);
        } else if (v < 300) {
            final double u = 1 / v;
            den = u * u * (p6[0] + (p6[1] + (p6[2] + (p6[3] + p6[4] * u) * u) * u) * u)
                / (q6[0] + (q6[1] + (q6[2] + (q6[3] + q6[4] * u) * u) * u) * u);
        } else {
            final double u = 1 / (v - v * Math.log(v) / (v + 1));
            den = u * u * (1 + (a2[0] + a2[1] * u) * u);
        }
        return norm ? den / sigma : den;
    }

    static double crystalBall(double x, double mean, double sigma, double alpha, double n) {
        final double t = (x - mean) / Math.abs(sigma);
        final double al = Math.abs(alpha);
        if (t > -al) return Math.exp(-0.5 * t * t);
        final double nn = Math.max(1.0001, n);
        final double a = Math.pow(nn / al, nn) * Math.exp(-0.5 * al * al);
        final double b = nn / al - al;
        return a * Math.pow(b - t, -nn);
    }

    static double chebyshev(int n, double x) {
        if (n == 0) return 1;
        if (n == 1) return x;
        double t0 = 1;
        double t1 = x;
        for (int k = 2; k <= n; k++) {
            final double t2 = 2 * x * t1 - t0;
            t0 = t1;
            t1 = t2;
        }
        return t1;
    }

    /* ------------------------------------------------------------------ */
    /* Parsing: precedence || && comparison + - * / % unary ^              */
    /* ------------------------------------------------------------------ */

    private static final Map<String, Integer> VARS = new LinkedHashMap<>();

    static {
        VARS.put("x", 0);
        VARS.put("y", 1);
        VARS.put("z", 2);
        VARS.put("t", 3);
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skip() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        boolean eat(String token) {
            skip();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        Node expression() {
            Node a = and();
            while (eat("||")) a = new Bin('|', a, and());
            return a;
        }

        Node and() {
            Node a = comparison();
            while (eat("&&")) a = new Bin('&', a, comparison());
            return a;
        }

        Node comparison() {
            Node a = sum();
            while (true) {
                if (eat("<=")) a = new Bin('l', a, sum());
                else if (eat(">=")) a = new Bin('g', a, sum());
                else if (eat("==")) a = new Bin('=', a, sum());
                else if (eat("!=")) a = new Bin('!', a, sum());
                else if (eat("<")) a = new Bin('<', a, sum());
                else if (eat(">")) a = new Bin('>', a, sum());
                else return a;
            }
        }

        Node sum() {
            Node a = product();
            while (true) {
                if (eat("+")) a = new Bin('+', a, product());
                else if (eat("-")) a = new Bin('-', a, product());
                else return a;
            }
        }

        Node product() {
            Node a = unary();
            while (true) {
                skip();
                if (s.startsWith("**", pos)) return a;
                if (eat("*")) a = new Bin('*', a, unary());
                else if (eat("/")) a = new Bin('/', a, unary());
                else if (eat("%")) a = new Bin('%', a, unary());
                else return a;
            }
        }

        Node unary() {
            if (eat("-")) return new Neg(unary(), false);
            if (eat("+")) return unary();
            if (eat("!")) return new Neg(unary(), true);
            return power();
        }

        Node power() {
            final Node base = atom();
            if (eat("^") || eat("**")) return new Bin('^', base, unary());
            return base;
        }

        Node atom() {
            skip();
            if (pos >= s.length()) throw new IllegalArgumentException("the formula ends too soon");
            final char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                final Node n = expression();
                if (!eat(")")) throw new IllegalArgumentException("a ')' is missing");
                return n;
            }
            if (c == '[') {
                final int close = s.indexOf(']', pos);
                final int index = Integer.parseInt(s.substring(pos + 1, close).strip());
                pos = close + 1;
                return new Par(index);
            }
            if (Character.isDigit(c) || c == '.') {
                int j = pos;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < s.length() && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                    if (k < s.length() && Character.isDigit(s.charAt(k))) {
                        j = k;
                        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                    }
                }
                final double v = Double.parseDouble(s.substring(pos, j));
                pos = j;
                return new Num(v);
            }
            if (Character.isLetter(c) || c == '_') {
                int j = pos;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == ':')) {
                    j++;
                }
                String word = s.substring(pos, j);
                pos = j;
                if (word.startsWith("TMath::")) word = word.substring(7);
                if (word.startsWith("std::")) word = word.substring(5);
                final String lower = word.toLowerCase(Locale.ROOT);
                if (eat("(")) {
                    final List<Node> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(expression());
                        } while (eat(","));
                        if (!eat(")")) throw new IllegalArgumentException("a ')' is missing after " + word);
                    }
                    if (lower.equals("pi") || lower.equals("e") || lower.equals("twopi") || lower.equals("sqrt2")) {
                        return new Num(lower.equals("pi") ? Math.PI : lower.equals("twopi") ? 2 * Math.PI
                            : lower.equals("sqrt2") ? Math.sqrt(2) : Math.E);
                    }
                    final String fn = switch (lower) {
                        case "power" -> "pow";
                        case "abs", "fabs" -> "abs";
                        case "exp", "log", "log10", "sqrt", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sinh",
                            "cosh", "tanh", "erf", "erfc", "floor", "ceil", "min", "max", "pow", "sign", "nint", "gaus",
                            "landau", "breitwigner", "crystalball", "cheb", "cbrt", "sq", "log2", "asinh", "acosh", "atanh",
                            "ln", "round" -> lower;
                        case "asinh2" -> "asinh";
                        default -> {
                            try {
                                call(lower, new double[]{0, 1, 1, 1, 1});
                            } catch (IllegalArgumentException unknown) {
                                throw new IllegalArgumentException("unknown function " + word);
                            } catch (RuntimeException fine) {
                                // known, merely out of its domain at the probe
                            }
                            yield lower;
                        }
                    };
                    return new Call(fn, args);
                }
                if (VARS.containsKey(word)) return new Var(VARS.get(word));
                if (lower.equals("pi")) return new Num(Math.PI);
                if (lower.equals("e")) return new Num(Math.E);
                throw new IllegalArgumentException("unknown name " + word);
            }
            throw new IllegalArgumentException("unexpected '" + c + "'");
        }
    }
}
