package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Arithmetic expressions with CLHEP's grammar: what an attribute of a GDML file
 * or a field of the editor may contain.
 *
 * The grammar is that of HepTool::Evaluator (+ - * / ^, unary signs,
 * parentheses, comparisons, && ||, the standard functions) with "**" accepted
 * as a synonym of "^". Names resolve first against the document's variables,
 * then against the units and constants G4GDMLEvaluator defines. An expression
 * is parsed once into a tree and evaluated as often as its variables change.
 */
public final class Expr {

    /** Resolves a variable name to its value; null when unknown. */
    public interface Scope extends Function<String, Double> {
    }

    public static final Scope NO_VARIABLES = name -> null;

    private final String source;
    private final Node root;

    private Expr(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /**
     * Expressions already parsed, by text. Every rebuild evaluates every parameter of the document, and
     * writing or reading GDML parses them again: the same texts ("0*mm", "40*mm", a define's name) come
     * back thousands of times, and an Expr never changes once made. Emptied when it grows too large.
     */
    private static final java.util.Map<String, Expr> PARSED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_PARSED = 1 << 16;

    public static Expr parse(String text) throws GeometryException {
        if (text == null || text.isBlank()) {
            throw new GeometryException("empty expression");
        }
        final Expr known = PARSED.get(text);
        if (known != null) {
            return known;
        }
        final Parser p = new Parser(text);
        final Node n = p.expression();
        p.skipSpaces();
        if (p.pos < text.length()) {
            throw new GeometryException("unexpected '" + text.charAt(p.pos) + "' at "
                + (p.pos + 1) + " in \"" + text + "\"");
        }
        final Expr e = new Expr(text.trim(), n);
        if (PARSED.size() >= MAX_PARSED) {
            PARSED.clear();
        }
        PARSED.put(text, e);
        return e;
    }

    /** Evaluates a one-off expression; throws when it does not parse or names something unknown. */
    public static double eval(String text, Scope scope) throws GeometryException {
        return parse(text).eval(scope);
    }

    /** Evaluates, falling back to a default instead of throwing. */
    public static double evalOr(String text, Scope scope, double fallback) {
        try {
            return eval(text, scope);
        } catch (GeometryException e) {
            return fallback;
        }
    }

    public double eval(Scope scope) throws GeometryException {
        return root.eval(scope == null ? NO_VARIABLES : scope);
    }

    public String source() {
        return source;
    }

    /** The user variables the expression reads: units and constants are not listed. */
    public Set<String> variables() {
        final Set<String> out = new LinkedHashSet<>();
        root.collect(out);
        return out;
    }

    /** True when the expression is a plain number with no name in it. */
    public boolean isLiteral() {
        return root instanceof Num || (root instanceof Neg neg && neg.a instanceof Num);
    }

    /** True when the expression names a unit or constant of the evaluator. */
    public boolean mentionsUnit() {
        final Set<String> all = new LinkedHashSet<>();
        root.collectAll(all);
        for (String n : all) {
            if (Units.isReserved(n)) {
                return true;
            }
        }
        return false;
    }

    /** C++ for generated Geant4 code: CLHEP's names are kept, "^" becomes std::pow. */
    public String toCpp() {
        return root.cpp(0);
    }

    /** Replaces a variable name everywhere, keeping the rest of the text as written. */
    public String rename(String from, String to) {
        final StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            final char c = source.charAt(i);
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < source.length() && (Character.isLetterOrDigit(source.charAt(j))
                        || source.charAt(j) == '_')) {
                    j++;
                }
                final String id = source.substring(i, j);
                b.append(id.equals(from) ? to : id);
                i = j;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return source;
    }

    // ------------------------------------------------------------------
    // Tree
    // ------------------------------------------------------------------

    private interface Node {
        double eval(Scope s) throws GeometryException;

        default void collect(Set<String> out) {
        }

        default void collectAll(Set<String> out) {
            collect(out);
        }

        String cpp(int parentPrec);
    }

    private record Num(double v, String text) implements Node {
        public double eval(Scope s) {
            return v;
        }

        public String cpp(int p) {
            return text.contains(".") || text.contains("e") || text.contains("E") ? text : text + ".";
        }
    }

    private record Var(String name) implements Node {
        public double eval(Scope s) throws GeometryException {
            final Double user = s.apply(name);
            if (user != null) {
                return user;
            }
            final Double unit = Units.symbol(name);
            if (unit != null) {
                return unit;
            }
            throw new GeometryException("unknown name '" + name + "'");
        }

        public void collect(Set<String> out) {
            if (!Units.isReserved(name)) {
                out.add(name);
            }
        }

        public void collectAll(Set<String> out) {
            out.add(name);
        }

        public String cpp(int p) {
            return switch (name) {
                case "pi" -> "CLHEP::pi";
                case "e" -> "std::exp(1.0)";
                case "metre" -> "m";
                case "gamma" -> "0.577215664901532861";
                default -> name;
            };
        }
    }

    private record Neg(Node a) implements Node {
        public double eval(Scope s) throws GeometryException {
            return -a.eval(s);
        }

        public void collect(Set<String> out) {
            a.collect(out);
        }

        public void collectAll(Set<String> out) {
            a.collectAll(out);
        }

        public String cpp(int p) {
            return "-" + a.cpp(5);
        }
    }

    private record Not(Node a) implements Node {
        public double eval(Scope s) throws GeometryException {
            return a.eval(s) == 0 ? 1 : 0;
        }

        public void collect(Set<String> out) {
            a.collect(out);
        }

        public void collectAll(Set<String> out) {
            a.collectAll(out);
        }

        public String cpp(int p) {
            return "!" + a.cpp(5);
        }
    }

    private record Bin(char op, Node a, Node b) implements Node {
        static int prec(char op) {
            return switch (op) {
                case '|' -> 0;
                case '&' -> 1;
                case '=', '!', '<', '>', 'l', 'g' -> 2;
                case '+', '-' -> 3;
                case '*', '/' -> 4;
                case '^' -> 6;
                default -> 0;
            };
        }

        public double eval(Scope s) throws GeometryException {
            final double x = a.eval(s), y = b.eval(s);
            return switch (op) {
                case '+' -> x + y;
                case '-' -> x - y;
                case '*' -> x * y;
                case '/' -> {
                    if (y == 0) {
                        throw new GeometryException("division by zero");
                    }
                    yield x / y;
                }
                case '^' -> Math.pow(x, y);
                case '<' -> x < y ? 1 : 0;
                case '>' -> x > y ? 1 : 0;
                case 'l' -> x <= y ? 1 : 0;
                case 'g' -> x >= y ? 1 : 0;
                case '=' -> x == y ? 1 : 0;
                case '!' -> x != y ? 1 : 0;
                case '&' -> (x != 0 && y != 0) ? 1 : 0;
                case '|' -> (x != 0 || y != 0) ? 1 : 0;
                default -> throw new GeometryException("operator " + op);
            };
        }

        public void collect(Set<String> out) {
            a.collect(out);
            b.collect(out);
        }

        public void collectAll(Set<String> out) {
            a.collectAll(out);
            b.collectAll(out);
        }

        public String cpp(int p) {
            if (op == '^') {
                return "std::pow(" + a.cpp(0) + ", " + b.cpp(0) + ")";
            }
            final int me = prec(op);
            final String sym = switch (op) {
                case 'l' -> "<=";
                case 'g' -> ">=";
                case '=' -> "==";
                case '!' -> "!=";
                case '&' -> "&&";
                case '|' -> "||";
                default -> String.valueOf(op);
            };
            // Right operand of - and / needs its own parentheses at equal precedence.
            final String text = a.cpp(me) + " " + sym + " " + b.cpp(me + 1);
            return me < p ? "(" + text + ")" : text;
        }
    }

    private record Call(String fn, List<Node> args) implements Node {
        public double eval(Scope s) throws GeometryException {
            final double[] v = new double[args.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = args.get(i).eval(s);
            }
            return switch (fn) {
                case "abs" -> one(v, Math::abs);
                case "sqrt" -> one(v, Math::sqrt);
                case "sin" -> one(v, Math::sin);
                case "cos" -> one(v, Math::cos);
                case "tan" -> one(v, Math::tan);
                case "asin" -> one(v, Math::asin);
                case "acos" -> one(v, Math::acos);
                case "atan" -> one(v, Math::atan);
                case "sinh" -> one(v, Math::sinh);
                case "cosh" -> one(v, Math::cosh);
                case "tanh" -> one(v, Math::tanh);
                case "exp" -> one(v, Math::exp);
                case "log" -> one(v, Math::log);
                case "log10" -> one(v, Math::log10);
                case "floor" -> one(v, Math::floor);
                case "ceil" -> one(v, Math::ceil);
                case "pow" -> two(v, Math::pow);
                case "atan2" -> two(v, Math::atan2);
                case "min" -> {
                    need(v, 1);
                    double m = v[0];
                    for (double x : v) {
                        m = Math.min(m, x);
                    }
                    yield m;
                }
                case "max" -> {
                    need(v, 1);
                    double m = v[0];
                    for (double x : v) {
                        m = Math.max(m, x);
                    }
                    yield m;
                }
                default -> throw new GeometryException("unknown function '" + fn + "'");
            };
        }

        private double one(double[] v, java.util.function.DoubleUnaryOperator f) throws GeometryException {
            if (v.length != 1) {
                throw new GeometryException(fn + " takes one argument");
            }
            return f.applyAsDouble(v[0]);
        }

        private double two(double[] v, java.util.function.DoubleBinaryOperator f) throws GeometryException {
            if (v.length != 2) {
                throw new GeometryException(fn + " takes two arguments");
            }
            return f.applyAsDouble(v[0], v[1]);
        }

        private void need(double[] v, int n) throws GeometryException {
            if (v.length < n) {
                throw new GeometryException(fn + " needs an argument");
            }
        }

        public void collect(Set<String> out) {
            for (Node a : args) {
                a.collect(out);
            }
        }

        public void collectAll(Set<String> out) {
            for (Node a : args) {
                a.collectAll(out);
            }
        }

        public String cpp(int p) {
            final StringBuilder b = new StringBuilder();
            final String name = switch (fn) {
                case "abs" -> "std::fabs";
                case "min" -> "std::min";
                case "max" -> "std::max";
                default -> "std::" + fn;
            };
            if ((fn.equals("min") || fn.equals("max")) && args.size() > 2) {
                b.append(name).append("({");
                for (int i = 0; i < args.size(); i++) {
                    b.append(i > 0 ? ", " : "").append(args.get(i).cpp(0));
                }
                return b.append("})").toString();
            }
            b.append(name).append('(');
            for (int i = 0; i < args.size(); i++) {
                b.append(i > 0 ? ", " : "").append(args.get(i).cpp(0));
            }
            return b.append(')').toString();
        }
    }

    // ------------------------------------------------------------------
    // Recursive-descent parser
    // ------------------------------------------------------------------

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean eat(String token) {
            skipSpaces();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        Node expression() throws GeometryException {
            Node n = andExpr();
            while (eat("||")) {
                n = new Bin('|', n, andExpr());
            }
            return n;
        }

        Node andExpr() throws GeometryException {
            Node n = comparison();
            while (eat("&&")) {
                n = new Bin('&', n, comparison());
            }
            return n;
        }

        Node comparison() throws GeometryException {
            Node n = sum();
            while (true) {
                if (eat("<=")) {
                    n = new Bin('l', n, sum());
                } else if (eat(">=")) {
                    n = new Bin('g', n, sum());
                } else if (eat("==")) {
                    n = new Bin('=', n, sum());
                } else if (eat("!=")) {
                    n = new Bin('!', n, sum());
                } else if (eat("<")) {
                    n = new Bin('<', n, sum());
                } else if (eat(">")) {
                    n = new Bin('>', n, sum());
                } else {
                    return n;
                }
            }
        }

        Node sum() throws GeometryException {
            Node n = product();
            while (true) {
                if (eat("+")) {
                    n = new Bin('+', n, product());
                } else if (eat("-")) {
                    n = new Bin('-', n, product());
                } else {
                    return n;
                }
            }
        }

        Node product() throws GeometryException {
            Node n = unary();
            while (true) {
                skipSpaces();
                if (s.startsWith("**", pos)) {
                    return n; // handled in power
                }
                if (eat("*")) {
                    n = new Bin('*', n, unary());
                } else if (eat("/")) {
                    n = new Bin('/', n, unary());
                } else {
                    return n;
                }
            }
        }

        Node unary() throws GeometryException {
            if (eat("-")) {
                return new Neg(unary());
            }
            if (eat("+")) {
                return unary();
            }
            if (eat("!")) {
                return new Not(unary());
            }
            return power();
        }

        Node power() throws GeometryException {
            final Node base = primary();
            if (eat("^") || eat("**")) {
                // Right associative, and binds tighter than a leading sign of the exponent.
                return new Bin('^', base, unary());
            }
            return base;
        }

        Node primary() throws GeometryException {
            skipSpaces();
            if (pos >= s.length()) {
                throw new GeometryException("expression ends too early: \"" + s + "\"");
            }
            final char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                final Node n = expression();
                if (!eat(")")) {
                    throw new GeometryException("missing ')' in \"" + s + "\"");
                }
                return n;
            }
            if (Character.isDigit(c) || c == '.') {
                final int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                    pos++;
                }
                if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                    final int save = pos;
                    pos++;
                    if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                        pos++;
                    }
                    if (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                            pos++;
                        }
                    } else {
                        pos = save; // "2e" is 2 times e, as in CLHEP? keep the number only
                    }
                }
                final String text = s.substring(start, pos);
                try {
                    return new Num(Double.parseDouble(text), text);
                } catch (NumberFormatException e) {
                    throw new GeometryException("bad number '" + text + "'");
                }
            }
            if (Character.isLetter(c) || c == '_') {
                final int start = pos;
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos))
                        || s.charAt(pos) == '_' || s.charAt(pos) == ':')) {
                    pos++;
                }
                String id = s.substring(start, pos);
                if (id.startsWith("CLHEP::")) {
                    id = id.substring(7);
                }
                skipSpaces();
                if (pos < s.length() && s.charAt(pos) == '(') {
                    pos++;
                    final List<Node> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(expression());
                        } while (eat(","));
                        if (!eat(")")) {
                            throw new GeometryException("missing ')' after arguments of " + id);
                        }
                    }
                    return new Call(id.toLowerCase(Locale.ROOT), args);
                }
                return new Var(id);
            }
            throw new GeometryException("unexpected '" + c + "' at " + (pos + 1) + " in \"" + s + "\"");
        }
    }
}
