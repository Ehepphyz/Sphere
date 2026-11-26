package com.sphere.core.rootbackend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * One line of mathematics, read the way a compiler reads it.
 *
 * A formula is parsed once into a tree, and everything else is a walk over
 * that tree: the C++ it becomes, its value at typical physics values (so a
 * mistake shows before anything is compiled), the places where it stops being
 * a number, and its exact derivatives -- which is what turns a formula into
 * its own uncertainty by linear propagation.
 *
 * What a translation by regular expressions got wrong, and this does not:
 * <ul>
 *   <li>a power of anything: (a+(b))^2, -x^2 = -(x^2), a^b^c = a^(b^c), x**2;</li>
 *   <li>1/2 is 0.5: every integer is written as a double, where C++ says 0;</li>
 *   <li>a % b is std::fmod, which is what % means for numbers;</li>
 *   <li>0 < x < 1 is 0 < x && x < 1, not (0 < x) < 1;</li>
 *   <li>a mistake is named where it is, with a caret, and "did you mean sqrt?".</li>
 * </ul>
 */
public final class RootFormula {

    private RootFormula() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ================================================================== */
    /* What went wrong, and where                                          */
    /* ================================================================== */

    /** A formula that cannot be read, or cannot be taken where it was asked to go. */
    public static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;
        private final int at;

        Problem(String message, int at) {
            super(message);
            this.at = at;
        }

        /** Where in the text, or -1 for the formula as a whole. */
        public int at() {
            return at;
        }

        /**
         * The reason, then the formula and a caret under the place. The two
         * lines below the reason share one indentation, so the caret stays
         * aligned whatever prefix the first line is printed with.
         */
        public String explain(String text) {
            if (at < 0 || text == null || text.isEmpty()) {
                return getMessage();
            }
            final int column = Math.min(at, text.length());
            return getMessage() + "\n    " + text + "\n    " + " ".repeat(column) + "^";
        }
    }

    /** A value Sphere cannot compute itself: a function only ROOT knows. */
    public static final class NotEvaluable extends Exception {
        private static final long serialVersionUID = 1L;

        NotEvaluable(String what) {
            super(what);
        }
    }

    /* ================================================================== */
    /* The tree                                                            */
    /* ================================================================== */

    /** One node. from and to say where it was written; -1 for one Sphere made. */
    public sealed interface Node permits Num, Var, Neg, Not, Bin, Call, Cond { }

    /** A number. text is its C++ spelling (2.5, TMath::Pi(), true), or null for a computed one. */
    public record Num(double value, String text, int from, int to) implements Node { }

    public record Var(String name, int from, int to) implements Node { }

    public record Neg(Node arg, int from, int to) implements Node { }

    public record Not(Node arg, int from, int to) implements Node { }

    /** + - * / % ^, the comparisons, && and ||. */
    public record Bin(String op, Node left, Node right, int from, int to) implements Node { }

    /** fn is Sphere's own name for a function it knows; null for one handed to C++ as spelled. */
    public record Call(String fn, String spelled, List<Node> args, int from, int to) implements Node { }

    public record Cond(Node test, Node yes, Node no, int from, int to) implements Node { }

    /** A formula once read: the tree, the inputs in order of appearance, what reading it noticed. */
    public record Parsed(String text, Node root, List<String> inputs, boolean condition,
                         List<String> notes) { }

    /* ================================================================== */
    /* The functions Sphere knows                                          */
    /* ================================================================== */

    private record Fn(String name, int least, int most, String cpp) {
        boolean accepts(int count) {
            return count >= least && count <= most;
        }

        String arity() {
            if (least == most) {
                return least == 1 ? "one argument" : least + " arguments";
            }
            return least + " or " + most + " arguments";
        }
    }

    private static final Map<String, Fn> FUNCTIONS = new LinkedHashMap<>();

    static {
        for (String one : new String[]{"sqrt", "cbrt", "exp", "expm1", "log", "log10", "log2",
                "log1p", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
                "asinh", "acosh", "atanh", "abs", "floor", "ceil", "round", "trunc", "erf", "erfc",
                "tgamma", "lgamma"}) {
            FUNCTIONS.put(one, new Fn(one, 1, 1, "std::" + one));
        }
        FUNCTIONS.put("atan2", new Fn("atan2", 2, 2, "std::atan2"));
        FUNCTIONS.put("pow", new Fn("pow", 2, 2, "std::pow"));
        FUNCTIONS.put("min", new Fn("min", 2, 2, "std::min"));
        FUNCTIONS.put("max", new Fn("max", 2, 2, "std::max"));
        FUNCTIONS.put("fmod", new Fn("fmod", 2, 2, "std::fmod"));
        FUNCTIONS.put("hypot", new Fn("hypot", 2, 3, "std::hypot"));
    }

    /** Other spellings of the same functions. */
    private static final Map<String, String> ALIASES = Map.of(
        "ln", "log", "fabs", "abs", "arcsin", "asin", "arccos", "acos", "arctan", "atan");

    /** TMath's spellings, read as the standard functions they compute. */
    private static final Map<String, String> TMATH = Map.ofEntries(
        Map.entry("Sqrt", "sqrt"), Map.entry("Exp", "exp"), Map.entry("Log", "log"),
        Map.entry("Log10", "log10"), Map.entry("Log2", "log2"), Map.entry("Sin", "sin"),
        Map.entry("Cos", "cos"), Map.entry("Tan", "tan"), Map.entry("ASin", "asin"),
        Map.entry("ACos", "acos"), Map.entry("ATan", "atan"), Map.entry("ATan2", "atan2"),
        Map.entry("SinH", "sinh"), Map.entry("CosH", "cosh"), Map.entry("TanH", "tanh"),
        Map.entry("ASinH", "asinh"), Map.entry("ACosH", "acosh"), Map.entry("ATanH", "atanh"),
        Map.entry("Abs", "abs"), Map.entry("Min", "min"), Map.entry("Max", "max"),
        Map.entry("Power", "pow"), Map.entry("Hypot", "hypot"), Map.entry("Floor", "floor"),
        Map.entry("Ceil", "ceil"), Map.entry("Erf", "erf"), Map.entry("Erfc", "erfc"),
        Map.entry("Gamma", "tgamma"), Map.entry("LnGamma", "lgamma"));

    /** TMath's constants, which are functions of nothing in ROOT. */
    private static final Map<String, Double> TMATH_CONSTANTS = Map.ofEntries(
        Map.entry("Pi", Math.PI), Map.entry("TwoPi", 2 * Math.PI),
        Map.entry("PiOver2", Math.PI / 2), Map.entry("PiOver4", Math.PI / 4),
        Map.entry("InvPi", 1 / Math.PI), Map.entry("E", Math.E),
        Map.entry("Ln10", Math.log(10)), Map.entry("LogE", Math.log10(Math.E)),
        Map.entry("Sqrt2", Math.sqrt(2)), Map.entry("DegToRad", Math.PI / 180),
        Map.entry("RadToDeg", 180 / Math.PI), Map.entry("C", 299792458.0),
        Map.entry("H", 6.62607015e-34), Map.entry("Hbar", 1.054571817e-34),
        Map.entry("K", 1.380649e-23), Map.entry("Na", 6.02214076e23),
        Map.entry("Qe", 1.602176634e-19), Map.entry("G", 6.67430e-11),
        Map.entry("Gn", 9.80665));

    /** TMath's other functions, which are handed to ROOT as written. */
    private static final Set<String> TMATH_NAMES = Set.copyOf(new LinkedHashSet<>(List.of(
        "Gaus", "BreitWigner", "Landau", "Poisson", "PoissonI", "Binomial", "Prob",
        "ErfInverse", "ErfcInverse", "Beta", "BesselI0", "BesselI1", "BesselJ0", "BesselJ1",
        "BesselK0", "BesselK1", "BesselY0", "BesselY1", "Voigt", "CauchyDist", "LogNormal",
        "StudentI", "Student", "Freq", "NormQuantile", "ChisquareQuantile", "KolmogorovProb",
        "Sign", "Nint", "Factorial", "Sq")));

    private static final Set<String> KEYWORDS = Set.of(
        "alignas", "alignof", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break", "case",
        "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept", "const",
        "consteval", "constexpr", "constinit", "const_cast", "continue", "co_await", "co_return",
        "co_yield", "decltype", "default", "delete", "do", "double", "dynamic_cast", "else", "enum",
        "explicit", "export", "extern", "float", "for", "friend", "goto", "if", "inline", "int",
        "long", "mutable", "namespace", "new", "noexcept", "not_eq", "nullptr", "operator", "or_eq",
        "private", "protected", "public", "register", "reinterpret_cast", "requires", "return",
        "short", "signed", "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
        "template", "this", "thread_local", "throw", "try", "typedef", "typeid", "typename", "union",
        "unsigned", "using", "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq");

    /* ================================================================== */
    /* Reading                                                             */
    /* ================================================================== */

    /** Reads a formula, or says precisely why it cannot. */
    public static Parsed parse(String text) throws Problem {
        if (text == null || text.isBlank()) {
            throw new Problem("the formula is empty", -1);
        }
        final Reader reader = new Reader(text, lex(text));
        final Node root = reader.formula();
        final LinkedHashSet<String> inputs = new LinkedHashSet<>();
        variables(root, inputs);
        return new Parsed(text, root, List.copyOf(inputs), isCondition(root),
                          List.copyOf(new LinkedHashSet<>(reader.notes)));
    }

    /** The inputs a formula reads, or nothing when it cannot be read. */
    public static List<String> inputsOf(String text) {
        try {
            return parse(text).inputs();
        } catch (Problem unreadable) {
            return List.of();
        }
    }

    private enum T { NUM, ID, OP, END }

    private record Tok(T type, String text, int from, int to) { }

    private static boolean idStart(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_';
    }

    private static boolean idPart(char ch) {
        return idStart(ch) || (ch >= '0' && ch <= '9');
    }

    private static List<Tok> lex(String s) throws Problem {
        final List<Tok> out = new ArrayList<>();
        final int n = s.length();
        int k = 0;
        while (k < n) {
            final char ch = s.charAt(k);
            if (Character.isWhitespace(ch)) {
                k++;
                continue;
            }
            if (Character.isDigit(ch) || (ch == '.' && k + 1 < n && Character.isDigit(s.charAt(k + 1)))) {
                final int start = k;
                while (k < n && Character.isDigit(s.charAt(k))) {
                    k++;
                }
                if (k < n && s.charAt(k) == '.') {
                    k++;
                    while (k < n && Character.isDigit(s.charAt(k))) {
                        k++;
                    }
                }
                if (k < n && (s.charAt(k) == 'e' || s.charAt(k) == 'E')) {
                    int e = k + 1;
                    if (e < n && (s.charAt(e) == '+' || s.charAt(e) == '-')) {
                        e++;
                    }
                    if (e < n && Character.isDigit(s.charAt(e))) {
                        while (e < n && Character.isDigit(s.charAt(e))) {
                            e++;
                        }
                        k = e;
                    }
                }
                final String spelled = s.substring(start, k);
                // C++'s float and long suffixes say nothing here: all of it is double.
                if (k < n && "fFlL".indexOf(s.charAt(k)) >= 0 && (k + 1 >= n || !idPart(s.charAt(k + 1)))) {
                    k++;
                }
                out.add(new Tok(T.NUM, spelled, start, k));
                continue;
            }
            if (idStart(ch)) {
                final int start = k++;
                while (k < n && idPart(s.charAt(k))) {
                    k++;
                }
                while (k + 2 < n && s.charAt(k) == ':' && s.charAt(k + 1) == ':' && idStart(s.charAt(k + 2))) {
                    k += 3;
                    while (k < n && idPart(s.charAt(k))) {
                        k++;
                    }
                }
                out.add(new Tok(T.ID, s.substring(start, k), start, k));
                continue;
            }
            if (ch == 'π') {
                out.add(new Tok(T.ID, "pi", k, k + 1));
                k++;
                continue;
            }
            final String two = k + 1 < n ? s.substring(k, k + 2) : "";
            if (two.equals("**") || two.equals("<=") || two.equals(">=") || two.equals("==")
                    || two.equals("!=") || two.equals("&&") || two.equals("||")) {
                out.add(new Tok(T.OP, two.equals("**") ? "^" : two, k, k + 2));
                k += 2;
                continue;
            }
            if ("+-*/%^()<>!?:,".indexOf(ch) >= 0) {
                out.add(new Tok(T.OP, String.valueOf(ch), k, k + 1));
                k++;
                continue;
            }
            if (ch == '=') {
                throw new Problem("a single = assigns in C++: compare with ==", k);
            }
            if (ch == '&' || ch == '|') {
                throw new Problem("a single " + ch + " works on bits: write " + ch + ch, k);
            }
            if (ch == '[' || ch == ']') {
                throw new Problem("a formula takes one number per input: a collection needs a "
                    + "pipeline written by hand", k);
            }
            if (ch == ';' || ch == '{' || ch == '}' || ch == '"' || ch == '#') {
                throw new Problem("a formula is one expression: '" + ch + "' has no place in it", k);
            }
            if (Character.isLetter(ch)) {
                throw new Problem("'" + ch + "' cannot be part of a C++ name here: use ASCII letters", k);
            }
            throw new Problem("'" + ch + "' has no meaning in a formula", k);
        }
        out.add(new Tok(T.END, "", n, n));
        return out;
    }

    /** Recursive descent, one method per level of C++'s precedence. */
    private static final class Reader {
        private final String text;
        private final List<Tok> toks;
        private final List<String> notes = new ArrayList<>();
        private int k;

        Reader(String text, List<Tok> toks) {
            this.text = text;
            this.toks = toks;
        }

        private Tok peek() {
            return toks.get(k);
        }

        private Tok next() {
            return toks.get(k++);
        }

        private boolean op(String o) {
            final Tok t = peek();
            return t.type() == T.OP && t.text().equals(o);
        }

        private boolean word(String w) {
            final Tok t = peek();
            return t.type() == T.ID && t.text().equals(w);
        }

        private String slice(int from, int to) {
            if (from < 0 || to > text.length() || from >= to) {
                return "";
            }
            return text.substring(from, to).trim();
        }

        Node formula() throws Problem {
            if (peek().type() == T.END) {
                throw new Problem("the formula is empty", -1);
            }
            final Node root = cond();
            final Tok t = peek();
            if (t.type() != T.END) {
                if (t.type() == T.OP && t.text().equals(")")) {
                    throw new Problem("this ) closes nothing", t.from());
                }
                if (t.type() == T.OP && t.text().equals(",")) {
                    throw new Problem("a comma only separates the arguments of a function", t.from());
                }
                if (t.type() == T.OP && t.text().equals(":")) {
                    throw new Problem("this : has no ? before it", t.from());
                }
                throw new Problem("'" + t.text() + "' follows a complete expression: an operator is "
                    + "missing before it", t.from());
            }
            return root;
        }

        private Node cond() throws Problem {
            final Node test = or();
            if (!op("?")) {
                return test;
            }
            final Tok q = next();
            final Node yes = cond();
            if (!op(":")) {
                throw new Problem("this ? needs its : and the value for the other case", q.from());
            }
            next();
            final Node no = cond();
            return new Cond(test, yes, no, from(test), to(no));
        }

        private Node or() throws Problem {
            Node left = and();
            while (op("||") || word("or")) {
                next();
                final Node right = and();
                left = new Bin("||", left, right, from(left), to(right));
            }
            return left;
        }

        private Node and() throws Problem {
            Node left = negation();
            while (op("&&") || word("and")) {
                next();
                final Node right = negation();
                left = new Bin("&&", left, right, from(left), to(right));
            }
            return left;
        }

        /**
         * The word not reads as a word does: not b < 2 is not (b < 2). The
         * symbol ! keeps C++'s place, just above the multiplications.
         */
        private Node negation() throws Problem {
            if (word("not")) {
                final Tok t = next();
                final Node arg = negation();
                return new Not(arg, t.from(), to(arg));
            }
            return equality();
        }

        private Node equality() throws Problem {
            Node left = relation();
            while (op("==") || op("!=")) {
                final String o = next().text();
                final Node right = relation();
                left = new Bin(o, left, right, from(left), to(right));
            }
            return left;
        }

        private String relational() {
            for (String o : new String[]{"<", "<=", ">", ">="}) {
                if (op(o)) {
                    return o;
                }
            }
            return null;
        }

        /** a < b < c is read as mathematics reads it: a < b && b < c. */
        private Node relation() throws Problem {
            final Node first = sum();
            final String o = relational();
            if (o == null) {
                return first;
            }
            if (first instanceof Not) {
                notes.add(slice(from(first), to(first)) + " " + o + " ... compares the 0 or 1 of "
                    + slice(from(first), to(first)) + ": write !(... " + o + " ...) or not ... to negate "
                    + "the comparison itself.");
            }
            next();
            Node middle = sum();
            Node out = new Bin(o, first, middle, from(first), to(middle));
            String again = relational();
            while (again != null) {
                next();
                final Node last = sum();
                notes.add(slice(from(first), to(last)) + " is read as in mathematics, both comparisons "
                    + "at once (C++ alone would compare the 0 or 1 of the first one).");
                out = new Bin("&&", out, new Bin(again, middle, last, from(middle), to(last)),
                              from(first), to(last));
                middle = last;
                again = relational();
            }
            return out;
        }

        private Node sum() throws Problem {
            Node left = product();
            while (op("+") || op("-")) {
                final String o = next().text();
                final Node right = product();
                left = new Bin(o, left, right, from(left), to(right));
            }
            return left;
        }

        private Node product() throws Problem {
            Node left = unary();
            while (op("*") || op("/") || op("%")) {
                final String o = next().text();
                final Node right = unary();
                if (o.equals("/") && integerLiteral(left) && integerLiteral(right)
                        && ((Num) right).value() != 0) {
                    notes.add(slice(from(left), to(right)) + " is "
                        + shortNumber(((Num) left).value() / ((Num) right).value())
                        + " here: C++ would divide the two integers as integers.");
                }
                if (o.equals("%")) {
                    notes.add(slice(from(left), to(right)) + " is the remainder std::fmod, which is "
                        + "what % means for numbers.");
                }
                left = new Bin(o, left, right, from(left), to(right));
            }
            return left;
        }

        private Node unary() throws Problem {
            if (op("-")) {
                final Tok t = next();
                final Node arg = unary();
                return new Neg(arg, t.from(), to(arg));
            }
            if (op("+")) {
                next();
                return unary();
            }
            if (op("!")) {
                final Tok t = next();
                final Node arg = unary();
                return new Not(arg, t.from(), to(arg));
            }
            return power();
        }

        /** Right-associative, and above the unary minus: -x^2 is -(x^2). */
        private Node power() throws Problem {
            final Node base = primary();
            if (!op("^")) {
                return base;
            }
            next();
            final Node exponent = unary();
            return new Bin("^", base, exponent, from(base), to(exponent));
        }

        private Node primary() throws Problem {
            final Tok t = peek();
            switch (t.type()) {
                case NUM -> {
                    next();
                    final Num num = new Num(Double.parseDouble(t.text()), t.text(), t.from(), t.to());
                    noJuxtaposition(t.text());
                    return num;
                }
                case ID -> {
                    next();
                    if (op("(")) {
                        return call(t);
                    }
                    return name(t);
                }
                case OP -> {
                    if (t.text().equals("(")) {
                        next();
                        if (op(")")) {
                            throw new Problem("empty parentheses: put an expression in them", t.from());
                        }
                        final Node inside = cond();
                        if (!op(")")) {
                            if (peek().type() == T.END) {
                                throw new Problem("this ( is never closed", t.from());
                            }
                            throw new Problem("expected ) to close the ( of column " + (t.from() + 1),
                                              peek().from());
                        }
                        final Tok close = next();
                        noJuxtaposition(")");
                        // The parentheses belong to what is quoted: (e+pz)/(e-pz), not e+pz)/(e-pz.
                        return respan(inside, t.from(), close.to());
                    }
                    if (t.text().equals(")")) {
                        throw new Problem("nothing comes before this )", t.from());
                    }
                    throw new Problem("a number, a name or ( was expected here, not '" + t.text() + "'",
                                      t.from());
                }
                default -> throw new Problem("the formula ends where a number or a name was expected",
                                             t.from());
            }
        }

        /** 2x, 2(x+1), (a)(b), a b: nothing is multiplied without a *. */
        private void noJuxtaposition(String before) throws Problem {
            final Tok t = peek();
            final boolean operand = t.type() == T.NUM
                || (t.type() == T.ID && !t.text().equals("and") && !t.text().equals("or")
                    && !t.text().equals("not"))
                || (t.type() == T.OP && t.text().equals("("));
            if (operand) {
                throw new Problem("write " + before + " * " + t.text()
                    + ": nothing is multiplied without a *", t.from());
            }
        }

        private Node call(Tok name) throws Problem {
            final Tok open = next();
            final List<Node> args = new ArrayList<>();
            if (!op(")")) {
                while (true) {
                    args.add(cond());
                    if (op(",")) {
                        next();
                        continue;
                    }
                    break;
                }
            }
            if (!op(")")) {
                if (peek().type() == T.END) {
                    throw new Problem("the ( of " + name.text() + " is never closed", open.from());
                }
                throw new Problem("expected , or ) between the arguments of " + name.text(), peek().from());
            }
            final Tok close = next();
            final Node made = resolve(name.text(), List.copyOf(args), name.from(), close.to());
            noJuxtaposition(")");
            return made;
        }

        private Node name(Tok t) throws Problem {
            final String w = t.text();
            switch (w) {
                case "pi", "M_PI" -> {
                    noJuxtaposition(w);
                    return new Num(Math.PI, "3.141592653589793", t.from(), t.to());
                }
                case "true", "false" -> {
                    noJuxtaposition(w);
                    return new Num(w.equals("true") ? 1 : 0, w, t.from(), t.to());
                }
                case "and", "or" -> throw new Problem("'" + w + "' needs a condition on its left", t.from());
                case "not" -> throw new Problem("'not' negates a whole condition: put it before the "
                    + "condition, or write !x inside arithmetic", t.from());
                default -> { }
            }
            if (w.contains("::")) {
                final String member = w.startsWith("TMath::") ? w.substring(7) : "";
                if (TMATH_CONSTANTS.containsKey(member)) {
                    throw new Problem("write " + w + "(): in ROOT it is a function", t.to());
                }
                throw new Problem(w + " is not a number: only a call can be qualified", t.from());
            }
            if (KEYWORDS.contains(w)) {
                throw new Problem(w + " is a C++ keyword and cannot name an input", t.from());
            }
            final boolean function = FUNCTIONS.containsKey(w) || ALIASES.containsKey(w);
            final Tok after = peek();
            if (function && (after.type() == T.NUM || after.type() == T.ID)) {
                throw new Problem(w + " is a function: write " + w + "(" + after.text() + ")", t.from());
            }
            noJuxtaposition(w);
            return new Var(w, t.from(), t.to());
        }

        private Node resolve(String spelled, List<Node> args, int from, int to) throws Problem {
            String name = spelled.startsWith("std::") ? spelled.substring(5) : spelled;
            if (name.startsWith("TMath::")) {
                final String member = name.substring(7);
                final Double constant = TMATH_CONSTANTS.get(member);
                if (constant != null) {
                    if (!args.isEmpty()) {
                        throw new Problem(spelled + "() takes no argument", from);
                    }
                    return new Num(constant, spelled + "()", from, to);
                }
                if (member.equals("Sq") && args.size() == 1) {
                    return new Bin("^", args.get(0), new Num(2, "2", -1, -1), from, to);
                }
                final String mine = TMATH.get(member);
                if (mine != null && FUNCTIONS.get(mine).accepts(args.size())) {
                    return new Call(mine, spelled, args, from, to);
                }
                return new Call(null, spelled, args, from, to);
            }
            if (name.contains("::")) {
                return new Call(null, spelled, args, from, to);
            }
            name = ALIASES.getOrDefault(name, name);
            final Fn f = FUNCTIONS.get(name);
            if (f == null) {
                final String close = closest(name);
                throw new Problem("unknown function " + spelled
                    + (close == null ? "" : ": did you mean " + close + "?"), from);
            }
            if (!f.accepts(args.size())) {
                throw new Problem(f.name() + " takes " + f.arity() + ", not " + args.size(), from);
            }
            return new Call(f.name(), spelled, args, from, to);
        }
    }

    private static boolean integerLiteral(Node n) {
        return n instanceof Num num && num.text() != null && !num.text().isEmpty()
            && num.text().chars().allMatch(Character::isDigit);
    }

    /* ================================================================== */
    /* Walking the tree                                                    */
    /* ================================================================== */

    static int from(Node n) {
        return switch (n) {
            case Num x -> x.from();
            case Var x -> x.from();
            case Neg x -> x.from();
            case Not x -> x.from();
            case Bin x -> x.from();
            case Call x -> x.from();
            case Cond x -> x.from();
        };
    }

    static int to(Node n) {
        return switch (n) {
            case Num x -> x.to();
            case Var x -> x.to();
            case Neg x -> x.to();
            case Not x -> x.to();
            case Bin x -> x.to();
            case Call x -> x.to();
            case Cond x -> x.to();
        };
    }

    /** The same node, said to be written between other offsets. */
    static Node respan(Node n, int from, int to) {
        return switch (n) {
            case Num x -> new Num(x.value(), x.text(), from, to);
            case Var x -> new Var(x.name(), from, to);
            case Neg x -> new Neg(x.arg(), from, to);
            case Not x -> new Not(x.arg(), from, to);
            case Bin x -> new Bin(x.op(), x.left(), x.right(), from, to);
            case Call x -> new Call(x.fn(), x.spelled(), x.args(), from, to);
            case Cond x -> new Cond(x.test(), x.yes(), x.no(), from, to);
        };
    }

    static List<Node> children(Node n) {
        return switch (n) {
            case Num x -> List.of();
            case Var x -> List.of();
            case Neg x -> List.of(x.arg());
            case Not x -> List.of(x.arg());
            case Bin x -> List.of(x.left(), x.right());
            case Call x -> x.args();
            case Cond x -> List.of(x.test(), x.yes(), x.no());
        };
    }

    /** The same node with every child replaced by what f makes of it. */
    static Node map(Node n, UnaryOperator<Node> f) {
        return switch (n) {
            case Num x -> x;
            case Var x -> x;
            case Neg x -> new Neg(f.apply(x.arg()), -1, -1);
            case Not x -> new Not(f.apply(x.arg()), -1, -1);
            case Bin x -> new Bin(x.op(), f.apply(x.left()), f.apply(x.right()), -1, -1);
            case Call x -> {
                final List<Node> args = new ArrayList<>(x.args().size());
                for (Node one : x.args()) {
                    args.add(f.apply(one));
                }
                yield new Call(x.fn(), x.spelled(), List.copyOf(args), -1, -1);
            }
            case Cond x -> new Cond(f.apply(x.test()), f.apply(x.yes()), f.apply(x.no()), -1, -1);
        };
    }

    private static void variables(Node n, Set<String> into) {
        if (n instanceof Var v) {
            into.add(v.name());
            return;
        }
        for (Node child : children(n)) {
            variables(child, into);
        }
    }

    /** True for a comparison, a logical operation, or a ? : that answers true or false. */
    public static boolean isCondition(Node n) {
        return switch (n) {
            case Bin b -> switch (b.op()) {
                case "<", "<=", ">", ">=", "==", "!=", "&&", "||" -> true;
                default -> false;
            };
            case Not x -> true;
            case Cond c -> isCondition(c.yes()) && isCondition(c.no());
            case Num num -> "true".equals(num.text()) || "false".equals(num.text());
            default -> false;
        };
    }

    static int size(Node n) {
        int total = 1;
        for (Node child : children(n)) {
            total += size(child);
        }
        return total;
    }

    /** False when the formula has a kink or a step: abs, min, max, rounding, %, ? :. */
    static boolean smooth(Node n) {
        if (n instanceof Cond) {
            return false;
        }
        if (n instanceof Bin b && b.op().equals("%")) {
            return false;
        }
        if (n instanceof Call c && c.fn() != null) {
            switch (c.fn()) {
                case "abs", "min", "max", "floor", "ceil", "round", "trunc", "fmod" -> {
                    return false;
                }
                default -> { }
            }
        }
        for (Node child : children(n)) {
            if (!smooth(child)) {
                return false;
            }
        }
        return true;
    }

    static Node substitute(Node n, String name, Node with) {
        if (n instanceof Var v && v.name().equals(name)) {
            return with;
        }
        if (children(n).isEmpty()) {
            return n;
        }
        return map(n, child -> substitute(child, name, with));
    }

    /** True when the formula needs std::min or std::max, which live in <algorithm>. */
    public static boolean usesMinMax(Node n) {
        if (n instanceof Call c && ("min".equals(c.fn()) || "max".equals(c.fn()))
                && !c.spelled().startsWith("TMath::")) {
            return true;
        }
        for (Node child : children(n)) {
            if (usesMinMax(child)) {
                return true;
            }
        }
        return false;
    }

    /** The first call Sphere cannot evaluate itself, or null. */
    public static String foreignCall(Node n) {
        if (n instanceof Call c && c.fn() == null) {
            return c.spelled();
        }
        for (Node child : children(n)) {
            final String found = foreignCall(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /* ================================================================== */
    /* Writing C++                                                         */
    /* ================================================================== */

    private static final int COND = 1;
    private static final int OR = 2;
    private static final int AND = 3;
    private static final int EQUAL = 4;
    private static final int RELATION = 5;
    private static final int ADD = 6;
    private static final int MUL = 7;
    private static final int UNARY = 8;
    private static final int ATOM = 9;

    private record Out(String text, int own) { }

    private static int precedence(String op) {
        return switch (op) {
            case "||" -> OR;
            case "&&" -> AND;
            case "==", "!=" -> EQUAL;
            case "<", "<=", ">", ">=" -> RELATION;
            case "+", "-" -> ADD;
            default -> MUL;
        };
    }

    /**
     * The C++ for a node.
     *
     * The grouping the user wrote is kept exactly: a + (b + c) keeps its
     * parentheses, because floating point is not associative and the user
     * may have meant it. Integer literals are written as doubles.
     */
    public static String cpp(Node n) {
        return emit(n, 0);
    }

    private static String emit(Node n, int outer) {
        final Out out = switch (n) {
            case Num num -> {
                final String text = literal(num);
                yield new Out(text, text.startsWith("-") ? UNARY : ATOM);
            }
            case Var v -> new Out(v.name(), ATOM);
            case Neg g -> {
                final String a = emit(g.arg(), UNARY);
                yield new Out("-" + (a.startsWith("-") ? "(" + a + ")" : a), UNARY);
            }
            case Not x -> new Out("!" + emit(x.arg(), UNARY), UNARY);
            case Bin b when b.op().equals("%") ->
                new Out("std::fmod(" + emit(b.left(), 0) + ", " + emit(b.right(), 0) + ")", ATOM);
            case Bin b when b.op().equals("^") -> power(b);
            case Bin b -> {
                final int p = precedence(b.op());
                yield new Out(emit(b.left(), p) + " " + b.op() + " " + emit(b.right(), p + 1), p);
            }
            case Call c -> {
                final String name = c.fn() == null || c.spelled().startsWith("TMath::")
                    ? c.spelled() : FUNCTIONS.get(c.fn()).cpp();
                final StringBuilder s = new StringBuilder(name).append('(');
                for (int i = 0; i < c.args().size(); i++) {
                    if (i > 0) {
                        s.append(", ");
                    }
                    s.append(emit(c.args().get(i), 0));
                }
                yield new Out(s.append(')').toString(), ATOM);
            }
            case Cond c -> new Out(emit(c.test(), OR) + " ? " + emit(c.yes(), 0) + " : "
                                   + emit(c.no(), COND), COND);
        };
        return out.own() < outer ? "(" + out.text() + ")" : out.text();
    }

    /** x^2 as x * x for a plain base, an integer power as std::pow(u, n), anything else std::pow. */
    private static Out power(Bin b) {
        final Node base = b.left();
        final Node exponent = b.right();
        if (exponent instanceof Num e && e.value() == Math.rint(e.value()) && Math.abs(e.value()) <= 64) {
            final int k = (int) e.value();
            final boolean plain = base instanceof Var || (base instanceof Num bn && bn.value() >= 0);
            if (plain && (k == 2 || k == 3)) {
                final String a = emit(base, ATOM);
                return new Out(k == 2 ? a + " * " + a : a + " * " + a + " * " + a, MUL);
            }
            return new Out("std::pow(" + emit(base, 0) + ", " + k + ")", ATOM);
        }
        return new Out("std::pow(" + emit(base, 0) + ", " + emit(exponent, 0) + ")", ATOM);
    }

    private static String literal(Num n) {
        final String t = n.text();
        if (t == null) {
            return number(n.value());
        }
        if (!t.isEmpty() && t.chars().allMatch(Character::isDigit)) {
            return t + ".0";
        }
        return t;
    }

    /** A computed number as a C++ double literal. */
    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v) + ".0";
        }
        return Double.toString(v);
    }

    /** A number for people: six significant digits, no trailing zeros. */
    public static String shortNumber(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+inf" : "-inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e9) {
            return Long.toString((long) v);
        }
        final String s = String.format(Locale.ROOT, "%.6g", v);
        final int e = s.indexOf('e');
        if (e >= 0) {
            String mantissa = s.substring(0, e);
            if (mantissa.contains(".")) {
                mantissa = mantissa.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return mantissa + "e" + Integer.parseInt(s.substring(e + 1));
        }
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /* ================================================================== */
    /* Evaluating, so a formula is seen working before it is compiled       */
    /* ================================================================== */

    public static double eval(Node n, Map<String, Double> at) throws NotEvaluable {
        return switch (n) {
            case Num num -> num.value();
            case Var v -> {
                final Double value = at.get(v.name());
                if (value == null) {
                    throw new NotEvaluable(v.name());
                }
                yield value;
            }
            case Neg g -> -eval(g.arg(), at);
            case Not x -> eval(x.arg(), at) != 0 ? 0 : 1;
            case Cond c -> eval(c.test(), at) != 0 ? eval(c.yes(), at) : eval(c.no(), at);
            case Bin b -> {
                final double l = eval(b.left(), at);
                if (b.op().equals("&&")) {
                    yield l != 0 && eval(b.right(), at) != 0 ? 1 : 0;
                }
                if (b.op().equals("||")) {
                    yield l != 0 || eval(b.right(), at) != 0 ? 1 : 0;
                }
                final double r = eval(b.right(), at);
                yield switch (b.op()) {
                    case "+" -> l + r;
                    case "-" -> l - r;
                    case "*" -> l * r;
                    case "/" -> l / r;
                    case "%" -> l % r;
                    case "^" -> Math.pow(l, r);
                    case "<" -> l < r ? 1 : 0;
                    case "<=" -> l <= r ? 1 : 0;
                    case ">" -> l > r ? 1 : 0;
                    case ">=" -> l >= r ? 1 : 0;
                    case "==" -> l == r ? 1 : 0;
                    case "!=" -> l != r ? 1 : 0;
                    default -> throw new NotEvaluable(b.op());
                };
            }
            case Call c -> {
                if (c.fn() == null) {
                    throw new NotEvaluable(c.spelled());
                }
                final double[] a = new double[c.args().size()];
                for (int i = 0; i < a.length; i++) {
                    a[i] = eval(c.args().get(i), at);
                }
                yield apply(c.fn(), a);
            }
        };
    }

    private static double apply(String fn, double[] a) {
        return switch (fn) {
            case "sqrt" -> Math.sqrt(a[0]);
            case "cbrt" -> Math.cbrt(a[0]);
            case "exp" -> Math.exp(a[0]);
            case "expm1" -> Math.expm1(a[0]);
            case "log" -> Math.log(a[0]);
            case "log10" -> Math.log10(a[0]);
            case "log2" -> Math.log(a[0]) / Math.log(2.0);
            case "log1p" -> Math.log1p(a[0]);
            case "sin" -> Math.sin(a[0]);
            case "cos" -> Math.cos(a[0]);
            case "tan" -> Math.tan(a[0]);
            case "asin" -> Math.asin(a[0]);
            case "acos" -> Math.acos(a[0]);
            case "atan" -> Math.atan(a[0]);
            case "atan2" -> Math.atan2(a[0], a[1]);
            case "sinh" -> Math.sinh(a[0]);
            case "cosh" -> Math.cosh(a[0]);
            case "tanh" -> Math.tanh(a[0]);
            case "asinh" -> asinh(a[0]);
            case "acosh" -> a[0] < 1 ? Double.NaN : Math.log(a[0] + Math.sqrt(a[0] * a[0] - 1));
            case "atanh" -> Math.abs(a[0]) > 1 ? Double.NaN : 0.5 * Math.log1p(2 * a[0] / (1 - a[0]));
            case "abs" -> Math.abs(a[0]);
            case "floor" -> Math.floor(a[0]);
            case "ceil" -> Math.ceil(a[0]);
            case "round" -> a[0] < 0 ? -Math.floor(-a[0] + 0.5) : Math.floor(a[0] + 0.5);
            case "trunc" -> a[0] < 0 ? Math.ceil(a[0]) : Math.floor(a[0]);
            case "erf" -> erf(a[0]);
            case "erfc" -> erfc(a[0]);
            case "tgamma" -> gamma(a[0]);
            case "lgamma" -> lnGamma(a[0]);
            case "pow" -> Math.pow(a[0], a[1]);
            // std::min and std::max's own rule, NaN included: (b < a) ? b : a.
            case "min" -> a[1] < a[0] ? a[1] : a[0];
            case "max" -> a[0] < a[1] ? a[1] : a[0];
            case "fmod" -> a[0] % a[1];
            case "hypot" -> a.length == 3 ? Math.hypot(Math.hypot(a[0], a[1]), a[2])
                                          : Math.hypot(a[0], a[1]);
            default -> Double.NaN;
        };
    }

    private static double asinh(double x) {
        final double ax = Math.abs(x);
        final double r = ax > 1e8 ? Math.log(2 * ax) : Math.log(ax + Math.sqrt(ax * ax + 1));
        return x < 0 ? -r : r;
    }

    /** erf by its series near zero and erfc's continued fraction beyond, both to ~1e-14. */
    static double erf(double x) {
        if (Double.isNaN(x)) {
            return x;
        }
        final double ax = Math.abs(x);
        double r;
        if (ax < 2.5) {
            final double x2 = ax * ax;
            double term = ax;
            double sum = ax;
            for (int n = 1; n < 200; n++) {
                term *= -x2 / n;
                final double add = term / (2 * n + 1);
                sum += add;
                if (Math.abs(add) < 1e-17 * Math.abs(sum)) {
                    break;
                }
            }
            r = 2 / Math.sqrt(Math.PI) * sum;
        } else {
            r = 1 - erfcTail(ax);
        }
        return x < 0 ? -r : r;
    }

    static double erfc(double x) {
        return x < 2.5 ? 1 - erf(x) : erfcTail(x);
    }

    private static double erfcTail(double x) {
        double t = x;
        for (int k = 80; k >= 1; k--) {
            t = x + (k / 2.0) / t;
        }
        return Math.exp(-x * x) / (Math.sqrt(Math.PI) * t);
    }

    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851,
        -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    static double gamma(double x) {
        if (x < 0.5) {
            return Math.PI / (Math.sin(Math.PI * x) * gamma(1 - x));
        }
        final double z = x - 1;
        double a = LANCZOS[0];
        final double t = z + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (z + i);
        }
        return Math.sqrt(2 * Math.PI) * Math.pow(t, z + 0.5) * Math.exp(-t) * a;
    }

    static double lnGamma(double x) {
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        final double z = x - 1;
        double a = LANCZOS[0];
        final double t = z + 7.5;
        for (int i = 1; i < LANCZOS.length; i++) {
            a += LANCZOS[i] / (z + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /* ================================================================== */
    /* Derivatives                                                         */
    /* ================================================================== */

    /**
     * The exact derivative of a formula in one of its inputs, simplified.
     *
     * Every rule is the textbook one; what makes the result readable is that
     * it is built through constructors that fold constants and drop the zeros
     * and ones as they appear, so d/dx of x^2 is 2 * x and not 2 * x^1 * 1.
     */
    public static Node derivative(Node n, String x) throws Problem {
        return switch (n) {
            case Num num -> num(0);
            case Var v -> num(v.name().equals(x) ? 1 : 0);
            case Neg g -> neg(derivative(g.arg(), x));
            case Not no -> throw new Problem("a condition has no derivative", from(no));
            case Cond c -> cond(c.test(), derivative(c.yes(), x), derivative(c.no(), x));
            case Bin b -> binary(b, x);
            case Call c -> call(c, x);
        };
    }

    private static Node binary(Bin b, String x) throws Problem {
        final Node u = b.left();
        final Node v = b.right();
        switch (b.op()) {
            case "+" -> {
                return add(derivative(u, x), derivative(v, x));
            }
            case "-" -> {
                return sub(derivative(u, x), derivative(v, x));
            }
            case "*" -> {
                return add(mul(derivative(u, x), v), mul(u, derivative(v, x)));
            }
            case "/" -> {
                final Node du = derivative(u, x);
                final Node dv = derivative(v, x);
                if (zero(dv)) {
                    return div(du, v);
                }
                // u / (1 + u) is 1 - 1 / (1 + u), whose slope is u' / (1 + u)^2.
                // Written by the quotient rule, e^x / (1 + e^x) would become
                // e^x (1 + e^x) - e^x e^x: inf - inf once e^x overflows.
                if (v instanceof Bin s && s.op().equals("+")
                        && ((one(s.left()) && same(s.right(), u)) || (one(s.right()) && same(s.left(), u)))) {
                    return div(du, pow(v, num(2)));
                }
                return div(sub(mul(du, v), mul(u, dv)), pow(v, num(2)));
            }
            case "%" -> {
                return sub(derivative(u, x),
                           mul(fn("trunc", div(u, v)), derivative(v, x)));
            }
            case "^" -> {
                return powerRule(u, v, x);
            }
            default -> throw new Problem("a condition has no derivative", from(b));
        }
    }

    private static Node powerRule(Node u, Node v, String x) throws Problem {
        final Node du = derivative(u, x);
        final Node dv = derivative(v, x);
        if (zero(dv)) {
            if (zero(du)) {
                return num(0);
            }
            return mul(mul(v, pow(u, sub(v, num(1)))), du);
        }
        return mul(pow(u, v), add(mul(dv, fn("log", u)), div(mul(v, du), u)));
    }

    private static Node call(Call c, String x) throws Problem {
        if (c.fn() == null) {
            throw new Problem("Sphere does not know the derivative of " + c.spelled(), from(c));
        }
        final Node u = c.args().get(0);
        final Node du = derivative(u, x);
        switch (c.fn()) {
            case "pow" -> {
                return powerRule(u, c.args().get(1), x);
            }
            case "atan2" -> {
                final Node y = u;
                final Node w = c.args().get(1);
                final Node dw = derivative(w, x);
                return div(sub(mul(w, du), mul(y, dw)), add(pow(w, num(2)), pow(y, num(2))));
            }
            case "min", "max" -> {
                final Node w = c.args().get(1);
                return cond(new Bin(c.fn().equals("min") ? "<=" : ">=", u, w, -1, -1),
                            du, derivative(w, x));
            }
            case "hypot" -> {
                Node top = num(0);
                for (Node arg : c.args()) {
                    top = add(top, mul(arg, derivative(arg, x)));
                }
                return div(top, c);
            }
            case "fmod" -> {
                final Node w = c.args().get(1);
                return sub(du, mul(fn("trunc", div(u, w)), derivative(w, x)));
            }
            default -> { }
        }
        if (zero(du)) {
            return num(0);
        }
        return switch (c.fn()) {
            case "sqrt" -> div(du, mul(num(2), c));
            case "cbrt" -> div(du, mul(num(3), pow(c, num(2))));
            case "exp" -> mul(c, du);
            case "expm1" -> mul(fn("exp", u), du);
            case "log" -> div(du, u);
            case "log10" -> div(du, mul(u, num(Math.log(10))));
            case "log2" -> div(du, mul(u, num(Math.log(2))));
            case "log1p" -> div(du, add(num(1), u));
            case "sin" -> mul(fn("cos", u), du);
            case "cos" -> neg(mul(fn("sin", u), du));
            case "tan" -> div(du, pow(fn("cos", u), num(2)));
            case "asin" -> div(du, fn("sqrt", sub(num(1), pow(u, num(2)))));
            case "acos" -> neg(div(du, fn("sqrt", sub(num(1), pow(u, num(2))))));
            case "atan" -> div(du, add(num(1), pow(u, num(2))));
            case "sinh" -> mul(fn("cosh", u), du);
            case "cosh" -> mul(fn("sinh", u), du);
            case "tanh" -> div(du, pow(fn("cosh", u), num(2)));
            case "asinh" -> div(du, fn("sqrt", add(pow(u, num(2)), num(1))));
            case "acosh" -> div(du, fn("sqrt", sub(pow(u, num(2)), num(1))));
            case "atanh" -> div(du, sub(num(1), pow(u, num(2))));
            case "abs" -> cond(new Bin(">=", u, num(0), -1, -1), du, neg(du));
            case "floor", "ceil", "round", "trunc" -> num(0);
            case "erf" -> mul(mul(num(2 / Math.sqrt(Math.PI)), fn("exp", neg(pow(u, num(2))))), du);
            case "erfc" -> neg(mul(mul(num(2 / Math.sqrt(Math.PI)), fn("exp", neg(pow(u, num(2))))), du));
            case "tgamma", "lgamma" -> throw new Problem("the derivative of " + c.fn()
                + " needs the digamma function, which C++ does not have", from(c));
            default -> throw new Problem("Sphere does not know the derivative of " + c.spelled(), from(c));
        };
    }

    // ---- constructors that simplify as they build ----------------------------

    static Node num(double v) {
        return new Num(v, null, -1, -1);
    }

    /** a * b, simplified, for code that builds on derivatives. */
    public static Node product(Node a, Node b) {
        return mul(a, b);
    }

    public static Node variable(String name) {
        return new Var(name, -1, -1);
    }

    private static boolean zero(Node n) {
        return n instanceof Num x && x.value() == 0 && !"false".equals(x.text());
    }

    private static boolean one(Node n) {
        return n instanceof Num x && x.value() == 1 && !"true".equals(x.text());
    }

    private static boolean same(Node a, Node b) {
        return cpp(a).equals(cpp(b));
    }

    private static Node folded(double value, Node otherwise) {
        return Double.isFinite(value) ? num(value) : otherwise;
    }

    private static Node add(Node a, Node b) {
        if (zero(a)) {
            return b;
        }
        if (zero(b)) {
            return a;
        }
        if (a instanceof Num x && b instanceof Num y) {
            return folded(x.value() + y.value(), new Bin("+", a, b, -1, -1));
        }
        if (b instanceof Neg nb) {
            return sub(a, nb.arg());
        }
        if (a instanceof Neg na) {
            return sub(b, na.arg());
        }
        if (b instanceof Num y && y.value() < 0) {
            return sub(a, num(-y.value()));
        }
        return new Bin("+", a, b, -1, -1);
    }

    private static Node sub(Node a, Node b) {
        if (zero(b)) {
            return a;
        }
        if (zero(a)) {
            return neg(b);
        }
        if (a instanceof Num x && b instanceof Num y) {
            return folded(x.value() - y.value(), new Bin("-", a, b, -1, -1));
        }
        if (b instanceof Neg nb) {
            return add(a, nb.arg());
        }
        if (same(a, b)) {
            return num(0);
        }
        return new Bin("-", a, b, -1, -1);
    }

    private static Node mul(Node a, Node b) {
        if (zero(a) || zero(b)) {
            return num(0);
        }
        if (one(a)) {
            return b;
        }
        if (one(b)) {
            return a;
        }
        if (a instanceof Num x && x.value() == -1) {
            return neg(b);
        }
        if (b instanceof Num y && y.value() == -1) {
            return neg(a);
        }
        if (a instanceof Num x && b instanceof Num y) {
            return folded(x.value() * y.value(), new Bin("*", a, b, -1, -1));
        }
        if (a instanceof Neg na) {
            return neg(mul(na.arg(), b));
        }
        if (b instanceof Neg nb) {
            return neg(mul(a, nb.arg()));
        }
        // Constants first, and gathered: 2 * (3 * x) is 6 * x.
        if (b instanceof Num && !(a instanceof Num)) {
            return mul(b, a);
        }
        if (a instanceof Num x && b instanceof Bin bb && bb.op().equals("*")
                && bb.left() instanceof Num y) {
            return mul(num(x.value() * y.value()), bb.right());
        }
        if (same(a, b)) {
            return pow(a, num(2));
        }
        return new Bin("*", a, b, -1, -1);
    }

    private static Node div(Node a, Node b) {
        if (zero(a)) {
            return num(0);
        }
        if (one(b)) {
            return a;
        }
        if (a instanceof Num x && b instanceof Num y && y.value() != 0) {
            return folded(x.value() / y.value(), new Bin("/", a, b, -1, -1));
        }
        if (a instanceof Neg na) {
            return neg(div(na.arg(), b));
        }
        if (b instanceof Neg nb) {
            return neg(div(a, nb.arg()));
        }
        if (same(a, b)) {
            return num(1);
        }
        final Node logistic = logisticSlope(a, b);
        if (logistic != null) {
            return logistic;
        }
        return new Bin("/", a, b, -1, -1);
    }

    /**
     * k e^z / (1 + e^z)^2 written as k / (4 cosh(z/2)^2), or null when a / b
     * is not that.
     *
     * It is the slope of every logistic -- a turn-on, a Fermi function, a
     * sigmoid -- and the quotient rule writes it the first way. Once e^z
     * overflows, which a turn-on of width 1 reaches 709 units from its
     * threshold, that is inf / inf: a NaN where the true value is 0. The
     * second way is the same number and only ever underflows to 0.
     */
    private static Node logisticSlope(Node a, Node b) {
        if (!(b instanceof Bin p) || !p.op().equals("^")
                || !(p.right() instanceof Num two) || two.value() != 2
                || !(p.left() instanceof Bin s) || !s.op().equals("+")) {
            return null;
        }
        final Node e;
        if (one(s.left()) && isExp(s.right())) {
            e = s.right();
        } else if (one(s.right()) && isExp(s.left())) {
            e = s.left();
        } else {
            return null;
        }
        final Node rest = without(a, e);
        if (rest == null) {
            return null;
        }
        final Node z = ((Call) e).args().get(0);
        return div(rest, mul(num(4), pow(fn("cosh", div(z, num(2))), num(2))));
    }

    private static boolean isExp(Node n) {
        return n instanceof Call c && "exp".equals(c.fn()) && c.args().size() == 1;
    }

    /** n with one factor f taken out (f alone leaves 1), or null when f is not a factor of n. */
    private static Node without(Node n, Node f) {
        if (same(n, f)) {
            return num(1);
        }
        if (n instanceof Neg g) {
            final Node r = without(g.arg(), f);
            return r == null ? null : neg(r);
        }
        if (n instanceof Bin m && m.op().equals("*")) {
            final Node l = without(m.left(), f);
            if (l != null) {
                return mul(l, m.right());
            }
            final Node r = without(m.right(), f);
            if (r != null) {
                return mul(m.left(), r);
            }
        }
        return null;
    }

    private static Node neg(Node a) {
        if (a instanceof Num x) {
            return num(x.value() == 0 ? 0 : -x.value());
        }
        if (a instanceof Neg n) {
            return n.arg();
        }
        if (a instanceof Bin b && b.op().equals("-")) {
            return sub(b.right(), b.left());
        }
        // -(-1/w / t) is 1/w / t: a leading constant takes the sign.
        if (a instanceof Bin b && (b.op().equals("/") || b.op().equals("*"))
                && b.left() instanceof Bin inner && leadingNegative(inner)) {
            final Node left = neg(inner);
            return b.op().equals("/") ? div(left, b.right()) : mul(left, b.right());
        }
        if (a instanceof Bin b && (b.op().equals("/") || b.op().equals("*"))
                && b.left() instanceof Num x && x.value() < 0) {
            return b.op().equals("/") ? div(num(-x.value()), b.right()) : mul(num(-x.value()), b.right());
        }
        return new Neg(a, -1, -1);
    }

    /** -1 / w, -2 * x, (-1 / w) / t: a product or quotient whose first factor is a negative constant. */
    private static boolean leadingNegative(Node n) {
        if (n instanceof Num x) {
            return x.value() < 0;
        }
        return n instanceof Bin b && (b.op().equals("/") || b.op().equals("*")) && leadingNegative(b.left());
    }

    private static Node pow(Node a, Node b) {
        if (b instanceof Num y) {
            if (y.value() == 0) {
                return num(1);
            }
            if (y.value() == 1) {
                return a;
            }
            if (a instanceof Num x) {
                return folded(Math.pow(x.value(), y.value()), new Bin("^", a, b, -1, -1));
            }
        }
        return new Bin("^", a, b, -1, -1);
    }

    private static Node fn(String name, Node... args) {
        return new Call(name, name, List.of(args), -1, -1);
    }

    private static Node cond(Node test, Node yes, Node no) {
        if (same(yes, no)) {
            return yes;
        }
        return new Cond(test, yes, no, -1, -1);
    }

    /* ================================================================== */
    /* Sharing what is computed twice                                      */
    /* ================================================================== */

    /** A temporary: const double t1 = ...; (const bool for a condition). */
    public record Def(String name, Node value, boolean condition) { }

    /** The temporaries, in an order where each is defined before it is read, and the outputs. */
    public record Shared(List<Def> defs, List<Node> outputs) { }

    /**
     * Writes each subexpression computed more than once a single time.
     *
     * Derivatives repeat their pieces -- the formula's own square root comes
     * back in every partial -- and so does a formula like
     * sqrt(px^2+py^2)/sqrt(px^2+py^2+pz^2). The shared piece is computed
     * once, in exactly the order of operations it had, so the result is the
     * same to the last bit. A temporary that ends up read only once is put
     * back where it is read: a name is only worth it when it saves work.
     */
    public static Shared share(List<Node> outputs, Set<String> taken) {
        final Map<String, Integer> uses = new HashMap<>();
        for (Node one : outputs) {
            count(one, uses);
        }
        final Map<String, String> named = new HashMap<>();
        final List<Def> defs = new ArrayList<>();
        final List<Node> out = new ArrayList<>();
        final int[] serial = {0};
        for (Node one : outputs) {
            out.add(hoist(one, uses, named, defs, serial));
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            final Map<String, Integer> reads = new HashMap<>();
            for (Def d : defs) {
                countReads(d.value(), reads);
            }
            for (Node o : out) {
                countReads(o, reads);
            }
            for (int i = 0; i < defs.size(); i++) {
                final Def d = defs.get(i);
                if (reads.getOrDefault(d.name(), 0) <= 1) {
                    defs.remove(i);
                    for (int j = 0; j < defs.size(); j++) {
                        final Def other = defs.get(j);
                        defs.set(j, new Def(other.name(), substitute(other.value(), d.name(), d.value()),
                                            other.condition()));
                    }
                    for (int j = 0; j < out.size(); j++) {
                        out.set(j, substitute(out.get(j), d.name(), d.value()));
                    }
                    changed = true;
                    break;
                }
            }
        }
        // The provisional names cannot clash with anything; the final ones are t1, t2, ...
        int serialName = 0;
        final List<Def> finalDefs = new ArrayList<>();
        final Map<String, String> renamed = new LinkedHashMap<>();
        for (Def d : defs) {
            String name;
            do {
                name = "t" + (++serialName);
            } while (taken.contains(name));
            renamed.put(d.name(), name);
        }
        for (Def d : defs) {
            Node value = d.value();
            for (Map.Entry<String, String> r : renamed.entrySet()) {
                value = substitute(value, r.getKey(), variable(r.getValue()));
            }
            finalDefs.add(new Def(renamed.get(d.name()), value, d.condition()));
        }
        final List<Node> finalOut = new ArrayList<>();
        for (Node o : out) {
            Node value = o;
            for (Map.Entry<String, String> r : renamed.entrySet()) {
                value = substitute(value, r.getKey(), variable(r.getValue()));
            }
            finalOut.add(value);
        }
        return new Shared(List.copyOf(finalDefs), List.copyOf(finalOut));
    }

    private static boolean worth(Node n) {
        return !(n instanceof Num) && !(n instanceof Var) && (n instanceof Call || size(n) >= 4);
    }

    private static void count(Node n, Map<String, Integer> uses) {
        if (worth(n)) {
            uses.merge(cpp(n), 1, Integer::sum);
        }
        for (Node child : children(n)) {
            count(child, uses);
        }
    }

    private static void countReads(Node n, Map<String, Integer> reads) {
        if (n instanceof Var v) {
            reads.merge(v.name(), 1, Integer::sum);
        }
        for (Node child : children(n)) {
            countReads(child, reads);
        }
    }

    private static Node hoist(Node n, Map<String, Integer> uses, Map<String, String> named,
                              List<Def> defs, int[] serial) {
        final String key = cpp(n);
        final String already = named.get(key);
        if (already != null) {
            return variable(already);
        }
        final Node inner = children(n).isEmpty() ? n
            : map(n, child -> hoist(child, uses, named, defs, serial));
        if (worth(n) && uses.getOrDefault(key, 0) >= 2) {
            final String name = "\u0001" + (++serial[0]);
            defs.add(new Def(name, inner, isCondition(n)));
            named.put(key, name);
            return variable(name);
        }
        return inner;
    }

    /* ================================================================== */
    /* Checking                                                            */
    /* ================================================================== */

    /**
     * The derivatives against central finite differences, at the typical
     * values and five points around them.
     *
     * The generated code is not trusted because the rules are right: it is
     * trusted because it was measured to agree before it was handed over.
     */
    public static String verify(Node f, List<String> inputs, Map<String, Node> derivatives) {
        final Map<String, Double> base = typical(inputs);
        final Random random = new Random(20261122L);
        final boolean kinked = !smooth(f);
        int checked = 0;
        int onKinks = 0;
        double worst = 0;
        String worstInput = null;
        try {
            for (int trial = 0; trial < 6; trial++) {
                final Map<String, Double> at = new HashMap<>(base);
                if (trial > 0) {
                    for (String x : inputs) {
                        final double v = base.get(x);
                        at.put(x, v * (1 + 0.4 * (random.nextDouble() - 0.5))
                                  + 0.1 * (random.nextDouble() - 0.5));
                    }
                }
                for (Map.Entry<String, Node> e : derivatives.entrySet()) {
                    final String x = e.getKey();
                    final double exact = eval(e.getValue(), at);
                    final double x0 = at.get(x);
                    final double h = 1e-6 * Math.max(1.0, Math.abs(x0));
                    at.put(x, x0 + h);
                    final double up = eval(f, at);
                    at.put(x, x0 - h);
                    final double down = eval(f, at);
                    at.put(x, x0);
                    final double estimate = (up - down) / (2 * h);
                    if (!Double.isFinite(exact) || !Double.isFinite(estimate)) {
                        continue;
                    }
                    final double allowed = 1e-5 * Math.max(Math.abs(exact), Math.abs(estimate))
                        + 1e-11 * (Math.abs(up) + Math.abs(down)) / h + 1e-300;
                    final double off = Math.abs(exact - estimate) / allowed;
                    checked++;
                    if (off > 1 && kinked) {
                        onKinks++;
                        continue;
                    }
                    if (off > worst) {
                        worst = off;
                        worstInput = x;
                    }
                }
            }
        } catch (NotEvaluable cannot) {
            return "Derivatives not checked: " + cannot.getMessage() + " is computed by ROOT only.";
        }
        if (checked == 0) {
            return "Derivatives not checked: the formula is not finite near the typical values.";
        }
        if (worst <= 1) {
            return "Derivatives checked against finite differences at " + checked + " points: they agree."
                + (onKinks > 0 ? " (" + onKinks + " fell across a kink or a step, where none exists.)" : "");
        }
        return "WARNING: the derivative in " + worstInput + " disagrees with finite differences. "
            + "This is a bug in Sphere: please report the formula.";
    }

    /** What the formula gives at typical values, said in one sentence. */
    public static String preview(Parsed p) {
        final Map<String, Double> at = typical(p.inputs());
        try {
            final double v = eval(p.root(), at);
            final String where = p.inputs().isEmpty() ? "" : "At " + describe(at) + " it ";
            final String lead = where.isEmpty() ? "It " : where;
            if (p.condition()) {
                return lead + (v != 0 ? "keeps the entry." : "drops the entry.");
            }
            if (Double.isNaN(v)) {
                return lead + "gives NaN, at values that are not unusual: read the notes on its domain.";
            }
            if (Double.isInfinite(v)) {
                return lead + "gives " + shortNumber(v) + ": read the notes on its domain.";
            }
            return lead + (p.inputs().isEmpty() ? "is the constant " : "gives ") + shortNumber(v) + ".";
        } catch (NotEvaluable e) {
            return "Not previewed: " + e.getMessage() + " is computed by ROOT only.";
        }
    }

    /** "pt=40, met=25, dphi=1.2", the first six. */
    public static String describe(Map<String, Double> at) {
        final List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Double> e : at.entrySet()) {
            if (parts.size() == 6) {
                parts.add("...");
                break;
            }
            parts.add(e.getKey() + "=" + shortNumber(e.getValue()));
        }
        return String.join(", ", parts);
    }

    private static final Map<String, double[]> TYPICAL = Map.ofEntries(
        Map.entry("energy", new double[]{40, 25, 60, 15, 80}),
        Map.entry("mass", new double[]{91.19, 80.38, 125.1, 172.5}),
        Map.entry("eta", new double[]{0.5, -1.1, 1.8, -0.3}),
        Map.entry("phi", new double[]{0.3, 2.1, -1.2, -2.8}),
        Map.entry("dphi", new double[]{1.2, 0.4, 2.9}),
        Map.entry("deta", new double[]{0.8, 1.5}),
        Map.entry("theta", new double[]{0.8, 2.2}),
        Map.entry("dr", new double[]{0.4, 0.8}),
        Map.entry("count", new double[]{3, 5, 2}),
        Map.entry("fraction", new double[]{0.8, 0.6, 0.9}),
        Map.entry("weight", new double[]{1.0, 0.9}),
        Map.entry("signal", new double[]{10, 25}),
        Map.entry("background", new double[]{100, 50}),
        Map.entry("lumi", new double[]{140}),
        Map.entry("error", new double[]{0.1, 0.2}),
        Map.entry("cosine", new double[]{0.5, -0.3}),
        Map.entry("other", new double[]{1.3, 0.7, 2.1, 0.4, 1.7, 0.9}));

    /**
     * f along one input, the others held: what a picture of it draws.
     *
     * @return f at each x, NaN where it is not a number there
     * @throws NotEvaluable when f calls what only the compiled pipeline knows
     */
    public static double[] sample(Node f, String x, double[] xs, Map<String, Double> held) throws NotEvaluable {
        final Map<String, Double> at = new HashMap<>(held);
        final double[] ys = new double[xs.length];
        NotEvaluable foreign = null;
        int evaluated = 0;
        for (int i = 0; i < xs.length; i++) {
            at.put(x, xs[i]);
            try {
                ys[i] = eval(f, at);
                evaluated++;
            } catch (NotEvaluable cannot) {
                ys[i] = Double.NaN;
                foreign = cannot;
            }
        }
        if (evaluated == 0 && foreign != null) {
            throw foreign;
        }
        return ys;
    }

    /**
     * n points from lo to hi, both ends included. Written as a weighted mean
     * of the two ends, the middle of [-pi, pi] is 0 exactly, not -4.4e-16.
     */
    public static double[] grid(double lo, double hi, int n) {
        final double[] out = new double[Math.max(2, n)];
        for (int i = 0; i < out.length; i++) {
            final double t = (double) i / (out.length - 1);
            out[i] = lo * (1 - t) + hi * t;
        }
        return out;
    }

    /**
     * Where the derivatives stop being numbers, at points a run reaches: each
     * input at zero and at the ends of the range its name suggests, the others
     * at typical values. A slope taken through sqrt(0) divides by zero -- the
     * uncertainty of mT is NaN where dphi = 0 -- and saying so before anything
     * is built is what a note is for.
     */
    public static List<String> breaks(Map<String, Node> derivatives, List<String> inputs) {
        final Map<String, Double> typical = typical(inputs);
        // What breaks, and every point where that same set breaks: one
        // sentence per set rather than one per point.
        final Map<String, List<String>> where = new LinkedHashMap<>();
        for (String x : inputs) {
            final double[] lives = range(x);
            for (double v : new double[]{0, lives[0], lives[1]}) {
                if (v < lives[0] || v > lives[1]) {
                    continue;
                }
                final Map<String, Double> at = new HashMap<>(typical);
                at.put(x, v);
                final List<String> broken = new ArrayList<>();
                for (Map.Entry<String, Node> d : derivatives.entrySet()) {
                    try {
                        if (!Double.isFinite(eval(d.getValue(), at))) {
                            broken.add("d/d" + d.getKey());
                        }
                    } catch (NotEvaluable cannot) {
                        // verify() says why it cannot be evaluated.
                    }
                }
                if (!broken.isEmpty()) {
                    final List<String> points = where.computeIfAbsent(
                        String.join(", ", broken) + (broken.size() == 1 ? " is" : " are"), k -> new ArrayList<>());
                    final String point = x + " = " + shortNumber(v);
                    if (!points.contains(point)) {
                        points.add(point);
                    }
                }
            }
        }
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> one : where.entrySet()) {
            final List<String> points = one.getValue();
            final String listed = points.size() == 1 ? points.get(0)
                : String.join(", ", points.subList(0, points.size() - 1)) + " or " + points.get(points.size() - 1);
            out.add(one.getKey() + " not a number where " + listed + ", the others at typical values.");
            if (out.size() >= 3) {
                break;
            }
        }
        return out;
    }

    /** What each input brings to the uncertainty of f at a point: df/dx * sigma_x, and its share of the variance. */
    public record Share(String input, double contribution, double fraction) { }

    /**
     * The error budget of f at a point, each input taken with a relative
     * uncertainty: the contributions linear propagation adds in quadrature,
     * largest first.
     */
    public static List<Share> budget(Node f, List<String> inputs, Map<String, Double> at, double relative)
            throws Problem, NotEvaluable {
        final double[] c = new double[inputs.size()];
        double total = 0;
        for (int k = 0; k < c.length; k++) {
            final String x = inputs.get(k);
            final double value = at.get(x);
            final double sigma = relative * (value == 0 ? 1 : Math.abs(value));
            c[k] = eval(derivative(f, x), at) * sigma;
            total += c[k] * c[k];
        }
        final List<Share> out = new ArrayList<>();
        for (int k = 0; k < c.length; k++) {
            out.add(new Share(inputs.get(k), c[k], total > 0 ? c[k] * c[k] / total : 0));
        }
        out.sort((a, b) -> Double.compare(b.fraction(), a.fraction()));
        return out;
    }

    /**
     * Values a physicist would recognize, chosen from the names: pt is tens
     * of GeV, a mass sits on a known peak, an angle is an angle. Two inputs of
     * the same kind get different values, so x - y does not vanish by accident.
     */
    public static Map<String, Double> typical(List<String> inputs) {
        final Map<String, Double> out = new LinkedHashMap<>();
        final Map<String, Integer> seen = new HashMap<>();
        for (String name : inputs) {
            final String family = family(name);
            final double[] values = TYPICAL.get(family);
            final int k = seen.merge(family, 1, Integer::sum) - 1;
            out.put(name, values[k % values.length]);
        }
        return out;
    }

    private static final Set<String> ERROR_WORDS = Set.of("err", "error", "unc", "uncertainty", "sigma");

    private static final double[] ENERGY_RANGE = {0, 1000};

    /** Words that alone say where a quantity lives, wherever they stand in its name. */
    private static final Map<String, double[]> WORD_RANGES = Map.ofEntries(
        Map.entry("eta", new double[]{-5, 5}), Map.entry("rapidity", new double[]{-5, 5}),
        Map.entry("y", new double[]{-5, 5}), Map.entry("abseta", new double[]{0, 5}),
        Map.entry("phi", new double[]{-Math.PI, Math.PI}), Map.entry("dphi", new double[]{-Math.PI, Math.PI}),
        Map.entry("deltaphi", new double[]{-Math.PI, Math.PI}), Map.entry("deta", new double[]{-5, 5}),
        Map.entry("dr", new double[]{0, 6}), Map.entry("deltar", new double[]{0, 6}),
        Map.entry("theta", new double[]{0, Math.PI}), Map.entry("cos", new double[]{-1, 1}),
        Map.entry("costheta", new double[]{-1, 1}),
        Map.entry("eff", new double[]{0, 1}), Map.entry("efficiency", new double[]{0, 1}),
        Map.entry("acc", new double[]{0, 1}), Map.entry("acceptance", new double[]{0, 1}),
        Map.entry("prob", new double[]{0, 1}), Map.entry("probability", new double[]{0, 1}),
        Map.entry("frac", new double[]{0, 1}), Map.entry("fraction", new double[]{0, 1}),
        Map.entry("purity", new double[]{0, 1}), Map.entry("pvalue", new double[]{0, 1}),
        Map.entry("pval", new double[]{0, 1}), Map.entry("iso", new double[]{0, 2}),
        Map.entry("reliso", new double[]{0, 2}),
        Map.entry("nf", new double[]{3, 6}), Map.entry("lambda", new double[]{0.1, 0.5}),
        Map.entry("lambdaqcd", new double[]{0.1, 0.5}),
        Map.entry("q", new double[]{1, 1000}), Map.entry("scale", new double[]{1, 1000}),
        Map.entry("mu", new double[]{1, 1000}), Map.entry("sqrts", new double[]{100, 14000}),
        Map.entry("chi", new double[]{0, 100}), Map.entry("chisq", new double[]{0, 100}),
        Map.entry("ndf", new double[]{1, 100}),
        Map.entry("pt", ENERGY_RANGE), Map.entry("et", ENERGY_RANGE), Map.entry("energy", ENERGY_RANGE),
        Map.entry("momentum", ENERGY_RANGE), Map.entry("met", ENERGY_RANGE), Map.entry("ht", ENERGY_RANGE),
        Map.entry("mass", ENERGY_RANGE), Map.entry("lumi", new double[]{0, 3000}),
        Map.entry("luminosity", new double[]{0, 3000}),
        Map.entry("px", new double[]{-1000, 1000}), Map.entry("py", new double[]{-1000, 1000}),
        Map.entry("pz", new double[]{-1000, 1000}),
        Map.entry("flavor", new double[]{-6, 7}), Map.entry("flavour", new double[]{-6, 7}),
        Map.entry("slope", new double[]{-1, 1}));

    /**
     * Where an input of this name lives: the range a sweep should try, the way
     * a run would feed it. An angle stays an angle, eta stays in the detector,
     * an efficiency in [0, 1]. A name that says nothing keeps [0, 100], what
     * every input used to be swept over -- which sent theta to 100 radians and
     * reported the NaN that came back as the pipeline's fault.
     */
    public static double[] range(String name) {
        final List<String> words = nameWords(name);
        // An uncertainty of something: from zero to a tenth of that thing's span.
        if (words.size() > 1 && ERROR_WORDS.contains(words.get(words.size() - 1))) {
            final double[] of = range(String.join("_", words.subList(0, words.size() - 1)));
            return new double[]{0, 0.1 * (of[1] - of[0])};
        }
        // The first word that says what it is: minPt is a pt, maxEta an eta,
        // dataEfficiency an efficiency, whatever qualifies them.
        for (String word : words) {
            final double[] known = WORD_RANGES.get(word);
            if (known != null) {
                return known.clone();
            }
        }
        return switch (family(name)) {
            case "dphi", "phi" -> new double[]{-Math.PI, Math.PI};
            case "deta", "eta" -> new double[]{-5, 5};
            case "dr" -> new double[]{0, 6};
            case "theta" -> new double[]{0, Math.PI};
            case "cosine" -> new double[]{-1, 1};
            case "mass", "energy", "signal" -> new double[]{0, 1000};
            case "background" -> new double[]{0, 10000};
            case "lumi" -> new double[]{0, 3000};
            case "count" -> new double[]{0, 20};
            case "fraction" -> new double[]{0, 1};
            case "weight" -> new double[]{0, 2};
            case "error" -> new double[]{0, 10};
            default -> new double[]{0, 100};
        };
    }

    private static String family(String name) {
        final String whole = compact(name);
        final List<String> w = nameWords(name);
        final String head = w.isEmpty() ? whole : w.get(0);
        if (Set.of("dphi", "deltaphi").contains(whole) || head.equals("dphi")) {
            return "dphi";
        }
        if (Set.of("deta", "deltaeta").contains(whole) || head.equals("deta")) {
            return "deta";
        }
        if (Set.of("dr", "deltar").contains(whole) || head.equals("dr")) {
            return "dr";
        }
        if (Set.of("eta", "y", "rapidity").contains(whole) || head.equals("eta")) {
            return "eta";
        }
        if (head.equals("phi")) {
            return "phi";
        }
        if (head.equals("theta")) {
            return "theta";
        }
        if (Set.of("cos", "costheta", "cth", "costh").contains(whole) || head.equals("cos")) {
            return "cosine";
        }
        if (Set.of("m", "mass", "mjj", "mll", "minv", "mz", "mw", "mh", "mt", "mtw", "mee", "mmumu",
                   "mgg", "mbb", "mtop").contains(whole) || head.equals("m") || head.equals("mass")) {
            return "mass";
        }
        if (Set.of("n", "count", "num", "njets", "nj", "nb", "nlep", "nevents").contains(whole)
                || head.equals("n")) {
            return "count";
        }
        if (Set.of("eff", "efficiency", "epsilon", "acc", "acceptance").contains(whole)
                || head.equals("eff")) {
            return "fraction";
        }
        if (Set.of("w", "weight").contains(whole) || head.equals("weight")) {
            return "weight";
        }
        if (Set.of("s", "nsig", "sig", "signal").contains(whole)) {
            return "signal";
        }
        if (Set.of("b", "nbkg", "bkg", "background").contains(whole)) {
            return "background";
        }
        if (Set.of("lumi", "luminosity").contains(whole)) {
            return "lumi";
        }
        if (Set.of("sigma", "err", "error", "unc").contains(whole)) {
            return "error";
        }
        if (looksPositive(name)) {
            return "energy";
        }
        return "other";
    }

    /* ================================================================== */
    /* Where a formula stops being a number                                */
    /* ================================================================== */

    private static final Set<String> POSITIVE = Set.of("pt", "et", "e", "en", "energy", "p", "mom",
        "momentum", "met", "ht", "mht", "st", "sumet", "lumi", "luminosity", "xs", "xsec", "sqrts",
        "ptsum", "sumpt", "width", "gamma");

    private static final Set<String> NON_NEGATIVE = Set.of("m", "mass", "mjj", "mll", "minv", "mee",
        "mmumu", "mgg", "mbb", "mt", "mtw", "mz", "mw", "mh", "n", "count", "num", "njets", "nj", "nb",
        "nlep", "nevents", "events", "yield", "nsig", "nbkg", "s", "b", "sig", "bkg", "signal",
        "background", "sigma", "err", "error", "unc", "r", "dr", "deltar", "iso", "reliso", "chi",
        "chisq", "ndf", "lxy", "ctau", "dist", "distance", "w", "weight", "eff", "efficiency", "rate",
        "abseta", "absy", "radius", "length", "tau", "lifetime", "ptrel", "rho", "prob", "pvalue",
        "pval");

    private static final Set<String> SIGNED = Set.of("d", "delta", "diff", "eta", "phi", "y", "px",
        "py", "pz", "x", "z", "dz", "dxy", "dphi", "deta", "dy", "charge", "asym", "cos", "sin", "theta",
        "pull", "resid", "residual", "shift", "offset", "log", "ln", "slope");

    private static List<String> nameWords(String name) {
        final String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2")
                                  .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        final List<String> out = new ArrayList<>();
        for (String one : spaced.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!one.isEmpty()) {
                out.add(one);
            }
        }
        return out;
    }

    private static String compact(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    /** pt, pt_lead, ptErr, met, energy: a quantity that is positive for anything that exists. */
    public static boolean looksPositive(String name) {
        final String whole = compact(name);
        if (POSITIVE.contains(whole)) {
            return true;
        }
        final List<String> w = nameWords(name);
        if (w.isEmpty()) {
            return false;
        }
        for (String one : w) {
            if (SIGNED.contains(one)) {
                return false;
            }
        }
        return POSITIVE.contains(w.get(0));
    }

    /** m, mass_W, n_jets, eff, chi2, deltaR: zero or more, never below. */
    public static boolean looksNonNegative(String name) {
        if (looksPositive(name)) {
            return true;
        }
        final String whole = compact(name);
        if (NON_NEGATIVE.contains(whole)) {
            return true;
        }
        final List<String> w = nameWords(name);
        if (w.isEmpty()) {
            return false;
        }
        for (String one : w) {
            if (SIGNED.contains(one)) {
                return false;
            }
        }
        return NON_NEGATIVE.contains(w.get(0));
    }

    /**
     * The places where the formula can give NaN or an infinity: a square
     * root of what can be negative, a logarithm of what can be zero, an
     * arc-cosine of a ratio rounding can push past 1, a division by what can
     * vanish. A sign analysis proves most of them safe (1 - cos x is never
     * negative, pt is positive for anything that exists), and only what it
     * cannot prove is said.
     */
    public static List<String> domain(Parsed parsed) {
        final Signs signs = new Signs(parsed.text());
        signs.walk(parsed.root());
        final List<String> out = new ArrayList<>(new LinkedHashSet<>(signs.warnings));
        if (out.size() > 5) {
            final int more = out.size() - 5;
            out.subList(5, out.size()).clear();
            out.add("... and " + more + " more of the same kind.");
        }
        if (!signs.positive.isEmpty()) {
            out.add("Taken as positive from their names: " + String.join(", ", signs.positive) + ".");
        }
        if (!signs.nonNegative.isEmpty()) {
            out.add("Taken as zero or more from their names: " + String.join(", ", signs.nonNegative) + ".");
        }
        return out;
    }

    private static final class Signs {
        private final String text;
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> positive = new LinkedHashSet<>();
        private final Set<String> nonNegative = new LinkedHashSet<>();
        private final Set<String> leanPositive = new LinkedHashSet<>();
        private final Set<String> leanNonNegative = new LinkedHashSet<>();

        Signs(String text) {
            this.text = text;
        }

        /** One check. The names it leaned on are reported only when it passed thanks to them. */
        private boolean holds(BooleanSupplier check) {
            leanPositive.clear();
            leanNonNegative.clear();
            final boolean ok = check.getAsBoolean();
            if (ok) {
                positive.addAll(leanPositive);
                nonNegative.addAll(leanNonNegative);
            }
            return ok;
        }

        private String quote(Node n) {
            final int a = from(n);
            final int b = to(n);
            String s = a >= 0 && b <= text.length() && a < b ? text.substring(a, b).trim() : cpp(n);
            if (s.length() > 40) {
                s = s.substring(0, 39) + "...";
            }
            return s;
        }

        void walk(Node n) {
            inspect(n);
            for (Node child : children(n)) {
                walk(child);
            }
        }

        private void inspect(Node n) {
            if (n instanceof Call c && c.fn() != null) {
                final Node a = c.args().get(0);
                final String q = quote(a);
                switch (c.fn()) {
                    case "sqrt" -> {
                        if (!holds(() -> nonNeg(a))) {
                            warnings.add("sqrt(" + q + ") is NaN where " + q + " < 0"
                                + (a instanceof Bin b && b.op().equals("-")
                                   ? ", which a difference reaches by rounding alone" : "")
                                + ": max(0, ...) inside keeps it a number.");
                        }
                    }
                    case "log", "log10", "log2" -> {
                        if (!holds(() -> pos(a))) {
                            warnings.add(c.fn() + "(" + q + ") needs " + q + " > 0: at 0 it is -inf, below NaN.");
                        }
                    }
                    case "log1p" -> {
                        if (!holds(() -> nonNeg(a))) {
                            warnings.add("log1p(" + q + ") needs " + q + " > -1.");
                        }
                    }
                    case "asin", "acos" -> {
                        if (!holds(() -> atMostOne(a) && atLeastMinusOne(a))) {
                            warnings.add(c.fn() + "(" + q + ") is NaN outside [-1, 1], and a ratio such as "
                                + "pz/p lands just past 1 by rounding: min(max(" + q + ", -1), 1) clamps it.");
                        }
                    }
                    case "atanh" -> {
                        if (!holds(() -> atMostOne(a) && atLeastMinusOne(a))) {
                            warnings.add("atanh(" + q + ") is infinite at -1 and 1, NaN beyond.");
                        }
                    }
                    case "acosh" -> {
                        if (!holds(() -> atLeastOne(a))) {
                            warnings.add("acosh(" + q + ") is NaN below 1.");
                        }
                    }
                    case "tgamma", "lgamma" -> {
                        if (!holds(() -> pos(a))) {
                            warnings.add(c.fn() + "(" + q + ") has poles at 0 and at the negative integers.");
                        }
                    }
                    case "pow" -> power(a, c.args().get(1));
                    case "fmod" -> {
                        final Node b = c.args().get(1);
                        if (!holds(() -> pos(b) || nonZeroNumber(b))) {
                            warnings.add("fmod(" + q + ", " + quote(b) + ") is NaN where " + quote(b) + " = 0.");
                        }
                    }
                    default -> { }
                }
            } else if (n instanceof Bin b) {
                switch (b.op()) {
                    case "/" -> {
                        if (!holds(() -> pos(b.right()) || nonZeroNumber(b.right()))) {
                            warnings.add(quote(b) + " is inf or NaN where " + quote(b.right()) + " = 0.");
                        }
                    }
                    case "%" -> {
                        if (!holds(() -> pos(b.right()) || nonZeroNumber(b.right()))) {
                            warnings.add(quote(b) + " is NaN where " + quote(b.right()) + " = 0.");
                        }
                    }
                    case "^" -> power(b.left(), b.right());
                    default -> { }
                }
            }
        }

        private void power(Node base, Node exponent) {
            if (exponent instanceof Num e && e.value() == Math.rint(e.value())) {
                if (e.value() < 0 && !holds(() -> pos(base) || nonZeroNumber(base))) {
                    warnings.add(quote(base) + " to a negative power is infinite where " + quote(base) + " = 0.");
                }
                return;
            }
            if (!holds(() -> nonNeg(base))) {
                warnings.add(quote(base) + " to the power " + quote(exponent) + " is NaN where "
                    + quote(base) + " < 0: a fractional power needs a base of zero or more.");
            }
        }

        private static boolean nonZeroNumber(Node n) {
            return n instanceof Num x && x.value() != 0;
        }

        private static boolean evenInteger(Node n) {
            return n instanceof Num x && x.value() == Math.rint(x.value()) && Math.rint(x.value()) % 2 == 0;
        }

        private static boolean numberAtLeast(Node n, double v) {
            return n instanceof Num x && x.value() >= v;
        }

        private boolean nonNeg(Node n) {
            return switch (n) {
                case Num x -> x.value() >= 0;
                case Var v -> {
                    if (looksPositive(v.name())) {
                        leanPositive.add(v.name());
                        yield true;
                    }
                    if (looksNonNegative(v.name())) {
                        leanNonNegative.add(v.name());
                        yield true;
                    }
                    yield false;
                }
                case Neg g -> nonPos(g.arg());
                case Not x -> true;
                case Cond c -> nonNeg(c.yes()) && nonNeg(c.no());
                case Call c -> switch (c.fn() == null ? "" : c.fn()) {
                    case "sqrt", "abs", "exp", "cosh", "hypot", "erfc", "acos" -> true;
                    case "floor", "ceil", "round", "trunc", "cbrt", "sinh", "tanh", "atan", "asinh",
                         "expm1", "log1p", "fmod", "asin", "erf" -> nonNeg(c.args().get(0));
                    case "pow" -> nonNeg(c.args().get(0)) || evenInteger(c.args().get(1));
                    case "min" -> nonNeg(c.args().get(0)) && nonNeg(c.args().get(1));
                    case "max" -> nonNeg(c.args().get(0)) || nonNeg(c.args().get(1));
                    default -> false;
                };
                case Bin b -> switch (b.op()) {
                    case "+" -> (nonNeg(b.left()) && nonNeg(b.right()))
                        || (numberAtLeast(b.left(), 1) && atLeastMinusOne(b.right()))
                        || (numberAtLeast(b.right(), 1) && atLeastMinusOne(b.left()));
                    // 1 - cos(x) is never negative, nor is cosh(x) - 1.
                    case "-" -> (numberAtLeast(b.left(), 1) && atMostOne(b.right()))
                        || (b.left() instanceof Call l && "cosh".equals(l.fn())
                            && b.right() instanceof Num r && r.value() <= 1)
                        || (nonNeg(b.left()) && nonPos(b.right()));
                    case "*" -> (nonNeg(b.left()) && nonNeg(b.right())) || same(b.left(), b.right())
                        || (nonPos(b.left()) && nonPos(b.right()));
                    case "/" -> (nonNeg(b.left()) && nonNeg(b.right()))
                        || (nonPos(b.left()) && nonPos(b.right()));
                    case "^" -> evenInteger(b.right()) || nonNeg(b.left());
                    case "%" -> nonNeg(b.left());
                    default -> true;
                };
            };
        }

        private boolean nonPos(Node n) {
            if (n instanceof Num x) {
                return x.value() <= 0;
            }
            if (n instanceof Neg g) {
                return nonNeg(g.arg());
            }
            return false;
        }

        private boolean pos(Node n) {
            return switch (n) {
                case Num x -> x.value() > 0;
                case Var v -> {
                    if (looksPositive(v.name())) {
                        leanPositive.add(v.name());
                        yield true;
                    }
                    yield false;
                }
                case Cond c -> pos(c.yes()) && pos(c.no());
                case Call c -> switch (c.fn() == null ? "" : c.fn()) {
                    case "exp", "cosh", "erfc" -> true;
                    case "sqrt", "abs", "cbrt" -> pos(c.args().get(0));
                    case "hypot", "max" -> c.args().stream().anyMatch(this::pos);
                    case "min" -> c.args().stream().allMatch(this::pos);
                    case "pow" -> pos(c.args().get(0));
                    default -> false;
                };
                case Bin b -> switch (b.op()) {
                    case "+" -> (pos(b.left()) && nonNeg(b.right())) || (nonNeg(b.left()) && pos(b.right()));
                    case "-" -> b.left() instanceof Num l && l.value() > 1 && atMostOne(b.right());
                    case "*", "/" -> pos(b.left()) && pos(b.right());
                    case "^" -> pos(b.left());
                    default -> false;
                };
                default -> false;
            };
        }

        private boolean atMostOne(Node n) {
            return switch (n) {
                case Num x -> x.value() <= 1;
                case Neg g -> atLeastMinusOne(g.arg());
                case Cond c -> atMostOne(c.yes()) && atMostOne(c.no());
                case Call c -> switch (c.fn() == null ? "" : c.fn()) {
                    case "cos", "sin", "tanh", "erf" -> true;
                    case "min" -> atMostOne(c.args().get(0)) || atMostOne(c.args().get(1));
                    case "max" -> atMostOne(c.args().get(0)) && atMostOne(c.args().get(1));
                    default -> false;
                };
                case Bin b -> b.op().equals("*") && unit(b.left()) && unit(b.right());
                default -> false;
            };
        }

        private boolean atLeastMinusOne(Node n) {
            return switch (n) {
                case Num x -> x.value() >= -1;
                case Neg g -> atMostOne(g.arg());
                case Cond c -> atLeastMinusOne(c.yes()) && atLeastMinusOne(c.no());
                case Call c when c.fn() != null && List.of("cos", "sin", "tanh", "erf").contains(c.fn()) -> true;
                case Call c when "max".equals(c.fn()) ->
                    atLeastMinusOne(c.args().get(0)) || atLeastMinusOne(c.args().get(1));
                case Call c when "min".equals(c.fn()) ->
                    atLeastMinusOne(c.args().get(0)) && atLeastMinusOne(c.args().get(1));
                case Bin b when b.op().equals("*") && unit(b.left()) && unit(b.right()) -> true;
                default -> nonNeg(n);
            };
        }

        private boolean unit(Node n) {
            return atMostOne(n) && atLeastMinusOne(n);
        }

        private boolean atLeastOne(Node n) {
            return switch (n) {
                case Num x -> x.value() >= 1;
                case Call c when "cosh".equals(c.fn()) -> true;
                case Call c when "max".equals(c.fn()) ->
                    atLeastOne(c.args().get(0)) || atLeastOne(c.args().get(1));
                case Bin b when b.op().equals("+") ->
                    (atLeastOne(b.left()) && nonNeg(b.right())) || (nonNeg(b.left()) && atLeastOne(b.right()));
                default -> false;
            };
        }
    }

    /* ================================================================== */
    /* Suggestions                                                         */
    /* ================================================================== */

    /** The function a misspelled name most probably meant, or null. */
    static String closest(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (FUNCTIONS.containsKey(lower)) {
            return lower + (TMATH.containsKey(name) ? " or TMath::" + name : "");
        }
        for (String one : TMATH_NAMES) {
            if (one.equalsIgnoreCase(name)) {
                return "TMath::" + one;
            }
        }
        for (String one : TMATH.keySet()) {
            if (one.equalsIgnoreCase(name)) {
                return "TMath::" + one;
            }
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        final List<String> candidates = new ArrayList<>(FUNCTIONS.keySet());
        candidates.addAll(ALIASES.keySet());
        for (String one : candidates) {
            final int d = distance(lower, one);
            if (d < bestDistance) {
                bestDistance = d;
                best = one;
            }
        }
        final int allowed = name.length() <= 3 ? 1 : 2;
        return bestDistance <= allowed ? best : null;
    }

    /** Edits between two words, a swap of neighbours counting as one. */
    static int distance(String a, String b) {
        final int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2)
                        && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
