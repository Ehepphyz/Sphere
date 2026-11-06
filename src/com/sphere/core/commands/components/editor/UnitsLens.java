package com.sphere.components.editor;

import com.sphere.core.clhep.Quantities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Units Lens: what the physical quantities of a file are worth.
 *
 * In C++ that uses CLHEP's or Geant4's units, and in Python that imports hepunits,
 * a line defining a quantity ("const double Ecm = 13.6*TeV;", "B = 3.8*tesla")
 * gets its value past its end, in the unit that reads best ("= 13.6 TeV",
 * "= 38 kG"), computed by CLHEP's own Evaluator with CLHEP's own units; a
 * variable defined once is carried to the lines after it, so "V = L*L*L" reads
 * as a volume. An expression adding quantities of different dimensions (a length
 * to an energy) is marked. In any C++ file, a division of two integers that
 * truncates where a real number is meant ("double f = 1/3;") is marked too: it
 * gives 0, the classic bug of physics code.
 *
 * Every rule is chosen to be right when it speaks. A name is a unit only where
 * the file brings the units in, never where it declares a variable of that name,
 * and the one- and two-letter units (m, s, g, ns...) only right after a number
 * ("10*m"), since "m" is a mass as often as a metre; anything not understood
 * leaves the line without a lens.
 */
public final class UnitsLens {

    /** The values to show (by 1-based line) and the findings. */
    public record Result(Map<Integer, String> inlays, List<EditorDiagnostic> diagnostics) {
        public static final Result EMPTY = new Result(Map.of(), List.of());
    }

    public static final String SOURCE = "Units";

    private static final Map<String, String> FUNCTIONS = Map.ofEntries(
        Map.entry("sqrt", "sqrt"), Map.entry("pow", "pow"), Map.entry("exp", "exp"), Map.entry("log", "log"),
        Map.entry("log10", "log10"), Map.entry("sin", "sin"), Map.entry("cos", "cos"), Map.entry("tan", "tan"),
        Map.entry("asin", "asin"), Map.entry("acos", "acos"), Map.entry("atan", "atan"), Map.entry("atan2", "atan2"),
        Map.entry("sinh", "sinh"), Map.entry("cosh", "cosh"), Map.entry("tanh", "tanh"), Map.entry("abs", "abs"),
        Map.entry("fabs", "abs"), Map.entry("min", "min"), Map.entry("max", "max"), Map.entry("fmin", "min"),
        Map.entry("fmax", "max"), Map.entry("arcsin", "asin"), Map.entry("arccos", "acos"),
        Map.entry("arctan", "atan"), Map.entry("arctan2", "atan2"), Map.entry("power", "pow"));
    private static final Map<String, String> TMATH = Map.ofEntries(
        Map.entry("Pi", "pi"), Map.entry("TwoPi", "twopi"), Map.entry("PiOver2", "halfpi"),
        Map.entry("Sqrt", "sqrt"), Map.entry("Power", "pow"), Map.entry("Exp", "exp"), Map.entry("Log", "log"),
        Map.entry("Log10", "log10"), Map.entry("Sin", "sin"), Map.entry("Cos", "cos"), Map.entry("Tan", "tan"),
        Map.entry("ASin", "asin"), Map.entry("ACos", "acos"), Map.entry("ATan", "atan"), Map.entry("ATan2", "atan2"),
        Map.entry("Abs", "abs"));
    private static final Set<String> CONSTANT_CALLS = Set.of("pi", "twopi", "halfpi");
    private static final Set<String> MATH_MODULES = Set.of("math", "np", "numpy", "cmath");
    /** Not units: the Evaluator's Euler number, and names too common as variables to ever mean a unit bare. */
    private static final Set<String> NEVER = Set.of("e", "pi2", "c");
    private static final Set<String> PUNCT = Set.of("+", "-", "*", "/", "(", ")", ",", "**", "^");

    private static final Pattern CPP_UNITS_INCLUDE = Pattern.compile(
        "#\\s*include\\s*[<\"](?:[\\w./]*/)?(?:G4SystemOfUnits\\.hh|G4PhysicalConstants\\.hh|SystemOfUnits\\.h|PhysicalConstants\\.h)[>\"]");
    private static final Pattern CPP_USING_CLHEP = Pattern.compile("\\busing\\s+namespace\\s+CLHEP\\s*;");
    private static final Pattern CPP_ASSIGN = Pattern.compile(
        "^\\s*(?:(?:static|const|constexpr|inline|volatile|thread_local|unsigned|signed|long|short|extern)\\s+)*"
        + "(?:([A-Za-z_][\\w:<>]*)\\s*[*&]?\\s+)?([A-Za-z_]\\w*)\\s*=(?!=)\\s*(.+?)\\s*;\\s*$");
    private static final Pattern CPP_DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s+(.+?)\\s*$");
    private static final Pattern PY_ASSIGN = Pattern.compile(
        "^\\s*([A-Za-z_]\\w*)\\s*(?::\\s*[\\w\\[\\], .]+)?=(?!=)\\s*(.+?)\\s*$");
    private static final Pattern ANY_ASSIGN = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*(?:[-+*/%&|^]|<<|>>|\\*\\*|//)?=(?!=)");
    private static final Pattern INCREMENT = Pattern.compile("(?:\\+\\+|--)\\s*([A-Za-z_]\\w*)|([A-Za-z_]\\w*)\\s*(?:\\+\\+|--)");
    private static final Pattern CPP_DECLARED = Pattern.compile(
        "\\b(?:double|float|int|long|short|unsigned|auto|bool|char|size_t|G4double|G4float|G4int|G4long|G4bool"
        + "|Double_t|Float_t|Int_t|Long64_t)\\b\\s*[*&]*\\s*([A-Za-z_]\\w*)");
    private static final Pattern PY_DECLARED = Pattern.compile(
        "(?m)(?:^\\s*([A-Za-z_]\\w*)\\s*=(?!=)|\\bfor\\s+([A-Za-z_]\\w*)\\s+in\\b|\\bas\\s+([A-Za-z_]\\w*)|\\blambda\\s+([A-Za-z_]\\w*))");
    private static final Pattern PY_DEF = Pattern.compile("\\bdef\\s+\\w+\\s*\\(([^)]*)\\)");
    private static final Pattern INT_DIVISION = Pattern.compile("(?<![\\w.])(\\d+)\\s*/\\s*(\\d+)(?![\\w.])");
    private static final Pattern FLOAT_CONTEXT = Pattern.compile(
        "\\b(?:double|float|G4double|G4float|Double_t|Float_t|auto)\\b|\\d\\.\\d*|\\.\\d|\\d[eE][-+]?\\d");

    private UnitsLens() {
    }

    /** Reads a file; safe off the event thread. */
    public static Result analyse(String text, LanguageSpec spec) {
        boolean cpp = spec == LanguageSpec.CPP;
        boolean python = spec == LanguageSpec.PYTHON;
        if (!cpp && !python || text.length() > 2_000_000) {
            return Result.EMPTY;
        }
        String masked = mask(text, python);
        String[] lines = masked.split("\n", -1);
        String[] raw = text.split("\n", -1);
        List<EditorDiagnostic> diagnostics = new ArrayList<>();
        Map<Integer, String> inlays = new LinkedHashMap<>();

        if (cpp) {
            integerDivisions(lines, raw, diagnostics);
        }

        // Where the units come from.
        boolean bare = false;
        Set<String> bareNames = new HashSet<>();
        Set<String> prefixes = new HashSet<>();
        if (cpp) {
            bare = CPP_UNITS_INCLUDE.matcher(text).find() || CPP_USING_CLHEP.matcher(masked).find();
            if (bare || masked.contains("CLHEP::")) {
                prefixes.add("CLHEP");
            }
        } else {
            Matcher m = Pattern.compile(
                "(?m)^[ \\t]*from[ \\t]+hepunits(?:\\.(?:units|constants))?[ \\t]+import[ \\t]+(\\*|\\([^)]*\\)|[\\w \\t,]+)")
                .matcher(masked);
            while (m.find()) {
                String what = m.group(1);
                if (what.equals("*")) {
                    bare = true;
                } else {
                    for (String n : what.replace("(", "").replace(")", "").split("[,\\n]")) {
                        String[] asName = n.strip().split("\\s+as\\s+");
                        if (asName.length == 1 && !asName[0].isBlank()) {
                            bareNames.add(asName[0].strip());
                        }
                    }
                }
            }
            Matcher imp = Pattern.compile("(?m)^\\s*import\\s+(hepunits(?:\\.(?:units|constants))?)(?:\\s+as\\s+(\\w+))?").matcher(masked);
            while (imp.find()) {
                prefixes.add(imp.group(2) != null ? imp.group(2) : imp.group(1));
                prefixes.add("hepunits");
                prefixes.add("hepunits.units");
                prefixes.add("hepunits.constants");
            }
        }
        boolean units = bare || !bareNames.isEmpty() || !prefixes.isEmpty();
        if (!units) {
            return new Result(Map.of(), diagnostics);
        }

        Quantities q = new Quantities();
        Set<String> known = new HashSet<>(q.names().keySet());
        known.removeAll(NEVER);

        // Names the file gives to its own variables: those are never units.
        Set<String> declared = new HashSet<>();
        Map<String, Integer> assignments = new HashMap<>();
        if (cpp) {
            Matcher d = CPP_DECLARED.matcher(masked);
            while (d.find()) {
                declared.add(d.group(1));
            }
        } else {
            Matcher d = PY_DECLARED.matcher(masked);
            while (d.find()) {
                for (int g = 1; g <= 4; g++) {
                    if (d.group(g) != null) {
                        declared.add(d.group(g));
                    }
                }
            }
            Matcher def = PY_DEF.matcher(masked);
            while (def.find()) {
                for (String p : def.group(1).split(",")) {
                    String n = p.split("[:=]")[0].replace("*", "").strip();
                    if (n.matches("[A-Za-z_]\\w*")) {
                        declared.add(n);
                    }
                }
            }
        }
        for (String line : lines) {
            Matcher a = ANY_ASSIGN.matcher(line);
            while (a.find()) {
                assignments.merge(a.group(1), 1, Integer::sum);
            }
            Matcher inc = INCREMENT.matcher(line);
            while (inc.find()) {
                assignments.merge(inc.group(1) != null ? inc.group(1) : inc.group(2), 2, Integer::sum);
            }
        }

        Context ctx = new Context(cpp, bare, bareNames, prefixes, known, declared, new HashSet<>());
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String name;
            String rhs;
            int rhsAt;
            Matcher m = cpp ? CPP_DEFINE.matcher(line) : null;
            if (m != null && m.matches()) {
                name = m.group(1);
                rhs = m.group(2);
                rhsAt = m.start(2);
            } else {
                m = (cpp ? CPP_ASSIGN : PY_ASSIGN).matcher(line);
                if (!m.matches()) {
                    continue;
                }
                name = m.group(cpp ? 2 : 1);
                rhs = m.group(cpp ? 3 : 2);
                rhsAt = m.start(cpp ? 3 : 2);
                if (cpp && m.group(1) != null && Set.of("return", "case", "goto", "delete", "throw").contains(m.group(1))) {
                    continue;
                }
            }
            if (python && (rhs.endsWith(":") || rhs.endsWith("\\"))) {
                continue;
            }
            Translation t = translate(rhs, ctx);
            if (t == null || !t.usesUnits || t.integerDivision) {
                continue;
            }
            Quantities.Value v = q.value(t.expression);
            if (!v.ok()) {
                continue;
            }
            Quantities.Dimension d = q.dimension(t.expression);
            if (d != null && !Quantities.consistent(d)) {
                diagnostics.add(new EditorDiagnostic(li + 1, rhsAt + 1, rhs.length(), EditorDiagnostic.Severity.WARNING,
                                                     "units", inconsistency(q, t.expression), SOURCE));
                continue;
            }
            if (d == null) {
                continue;
            }
            String shown = d.dimensionless() ? short6(v.value()) : shorten(q.readable(v.value(), d));
            if (shown.isEmpty()) {
                continue;
            }
            String kind = d.dimensionless() ? "" : d.name();
            if (!redundant(raw.length > li ? raw[li].substring(Math.min(rhsAt, raw[li].length())) : rhs, shown)) {
                inlays.put(li + 1, "= " + shown + (kind.isEmpty() || shown.contains("/c") ? "" : "  · " + kind));
            }
            if (assignments.getOrDefault(name, 0) == 1 && !name.equals("_")) {
                if (q.let(name, t.expression) == null) {
                    ctx.resolved.add(name);
                }
            }
        }
        return new Result(inlays, diagnostics);
    }

    // ---- Expressions ----------------------------------------------------------------

    private record Context(boolean cpp, boolean bare, Set<String> bareNames, Set<String> prefixes,
                           Set<String> known, Set<String> declared, Set<String> resolved) {
    }

    private record Translation(String expression, boolean usesUnits, boolean integerDivision) {
    }

    /** The right-hand side as the Evaluator reads it, or null when a part of it is not understood. */
    private static Translation translate(String rhs, Context ctx) {
        List<EditEcho.Tok> toks = EditEcho.tokenize(rhs);
        if (toks.isEmpty() || toks.size() > 120) {
            return null;
        }
        StringBuilder expr = new StringBuilder();
        boolean usesUnits = false;
        boolean intDivision = false;
        for (int i = 0; i < toks.size(); i++) {
            EditEcho.Tok t = toks.get(i);
            switch (t.kind()) {
                case STRING -> {
                    return null;
                }
                case NUMBER -> {
                    String n = number(t.text(), ctx.cpp);
                    if (n == null) {
                        return null;
                    }
                    if (ctx.cpp && isInteger(t.text()) && i >= 2 && toks.get(i - 1).text().equals("/")
                            && toks.get(i - 2).kind() == EditEcho.Kind.NUMBER && isInteger(toks.get(i - 2).text())) {
                        intDivision = true;
                    }
                    expr.append(n);
                }
                case PUNCT -> {
                    String p = t.text();
                    if (!PUNCT.contains(p) || p.equals("^") && ctx.cpp) {
                        return null;
                    }
                    expr.append(p.equals("**") ? "^" : p);
                }
                case IDENT -> {
                    List<String> parts = new ArrayList<>();
                    parts.add(t.text());
                    int j = i;
                    String sep = null;
                    while (j + 2 < toks.size() && toks.get(j + 2).kind() == EditEcho.Kind.IDENT
                           && (toks.get(j + 1).text().equals("::") || toks.get(j + 1).text().equals("."))) {
                        sep = toks.get(j + 1).text();
                        parts.add(toks.get(j + 2).text());
                        j += 2;
                    }
                    boolean call = j + 1 < toks.size() && toks.get(j + 1).text().equals("(");
                    String mapped = name(parts, sep, call, toks, i, ctx);
                    if (mapped == null) {
                        return null;
                    }
                    if (!call && !mapped.equals("pi") && !mapped.equals("twopi") && !mapped.equals("halfpi")
                            && (ctx.known.contains(mapped) || ctx.resolved.contains(mapped))) {
                        usesUnits = true;
                    }
                    expr.append(mapped);
                    i = j;
                    if (call && CONSTANT_CALLS.contains(mapped)) {
                        if (i + 2 < toks.size() && toks.get(i + 2).text().equals(")")) {
                            i += 2;                     // TMath::Pi(): a constant
                        } else {
                            return null;
                        }
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        return new Translation(expr.toString(), usesUnits, intDivision);
    }

    private static String name(List<String> parts, String sep, boolean call, List<EditEcho.Tok> toks, int at,
                               Context ctx) {
        String last = parts.get(parts.size() - 1);
        if (parts.size() > 1) {
            String prefix = String.join(sep == null ? "." : sep, parts.subList(0, parts.size() - 1));
            if (ctx.cpp) {
                if (prefix.equals("CLHEP") && !call && ctx.known.contains(last) && ctx.prefixes.contains("CLHEP")) {
                    return last;
                }
                if (prefix.equals("std") && call) {
                    return FUNCTIONS.get(last);
                }
                if (prefix.equals("TMath")) {
                    String m = TMATH.get(last);
                    return m != null && call ? m : null;
                }
                return null;
            }
            if (MATH_MODULES.contains(prefix)) {
                if (call) {
                    return FUNCTIONS.get(last);
                }
                return last.equals("pi") ? "pi" : null;
            }
            if (ctx.prefixes.contains(prefix) && !call && ctx.known.contains(last)) {
                return last;
            }
            return null;
        }
        if (call) {
            return FUNCTIONS.get(last);
        }
        if (ctx.cpp && last.equals("M_PI")) {
            return "pi";
        }
        if (ctx.resolved.contains(last)) {
            return last;
        }
        boolean allowed = ctx.bare || ctx.bareNames.contains(last);
        if (!allowed || !ctx.known.contains(last) || ctx.declared.contains(last)) {
            return null;
        }
        if (last.length() <= 2 && !afterQuantity(toks, at, ctx)) {
            return null;                                // "m" is a mass as often as a metre
        }
        return last;
    }

    /** Right after "<number> *" or "<number> /": the place of a unit. */
    private static boolean afterQuantity(List<EditEcho.Tok> toks, int at, Context ctx) {
        if (at < 2) {
            return false;
        }
        String op = toks.get(at - 1).text();
        if (!op.equals("*") && !op.equals("/")) {
            return false;
        }
        EditEcho.Tok before = toks.get(at - 2);
        return before.kind() == EditEcho.Kind.NUMBER
            || before.text().equals(")")
            || before.kind() == EditEcho.Kind.IDENT && before.text().length() > 2
               && (ctx.known.contains(before.text()) || ctx.resolved.contains(before.text()));
    }

    private static final Pattern NUMBER = Pattern.compile("^((?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)([fFlLuU]*)$");

    private static String number(String text, boolean cpp) {
        String t = cpp ? text.replace("'", "") : text.replace("_", "");
        Matcher m = NUMBER.matcher(t);
        if (!m.matches() || !cpp && !m.group(2).isEmpty()) {
            return null;
        }
        return m.group(1);
    }

    private static boolean isInteger(String text) {
        return text.matches("\\d+[uUlL]*");
    }

    /** What does not agree: the dimensions of the terms of the sum, or a function of a dimensioned quantity. */
    private static String inconsistency(Quantities q, String expression) {
        List<String> terms = terms(expression);
        Set<String> kinds = new LinkedHashSet<>();
        boolean broken = false;
        for (String term : terms) {
            Quantities.Dimension d = q.dimension(term);
            if (d == null) {
                continue;
            }
            if (!Quantities.consistent(d)) {
                broken = true;
                continue;
            }
            kinds.add(d.dimensionless() ? "a pure number" : d.name().isEmpty() ? "[" + d.si() + "]" : d.name());
        }
        if (kinds.size() > 1) {
            return "This adds quantities of different dimensions: " + String.join(" + ", kinds) + ".";
        }
        if (broken || terms.size() <= 1) {
            return "A function here is given a quantity with a dimension, or the terms inside it differ in dimension.";
        }
        return "The terms of this expression do not have the same dimension.";
    }

    /** The terms of a sum at the top level of an expression. */
    private static List<String> terms(String e) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if ((c == '+' || c == '-') && depth == 0 && i > start) {
                char p = e.charAt(i - 1);
                boolean binary = p != '*' && p != '/' && p != '^' && p != '(' && p != 'e' && p != 'E' && p != ',';
                if (binary || (p == 'e' || p == 'E') && (i < 2 || !Character.isDigit(e.charAt(i - 2)))) {
                    out.add(e.substring(start, i));
                    start = i + 1;
                }
            }
        }
        out.add(e.substring(start));
        out.removeIf(String::isBlank);
        return out;
    }

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[-+]?\\d+)?)(.*)$");

    /** "350.241046144 ps" read as "350.241 ps": six significant digits are what a lens needs. */
    private static String shorten(String readable) {
        Matcher m = LEADING_NUMBER.matcher(readable);
        if (!m.matches()) {
            return readable;
        }
        try {
            return short6(Double.parseDouble(m.group(1))) + m.group(2);
        } catch (NumberFormatException ex) {
            return readable;
        }
    }

    /** Whether the value merely repeats what is written ("5*GeV" is "5 GeV"). */
    private static boolean redundant(String written, String shown) {
        String w = written.replace("CLHEP::", "").replaceAll("[A-Za-z_]\\w*\\.(?=[A-Za-z_])", "")
                          .replaceAll("[\\s*;]", "").toLowerCase(Locale.ROOT);
        String s = shown.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        return w.equals(s) || w.startsWith(s) && w.length() <= s.length() + 1;
    }

    private static String short6(double v) {
        if (v == 0) {
            return "0";
        }
        String s = String.format(Locale.ROOT, "%.6g", v);
        String exp = "";
        int e = s.indexOf('e');
        if (e >= 0) {
            exp = s.substring(e);
            s = s.substring(0, e);
        }
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s + exp;
    }

    // ---- Integer division -----------------------------------------------------------

    private static void integerDivisions(String[] lines, String[] raw, List<EditorDiagnostic> out) {
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.indexOf('/') < 0 || line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher m = INT_DIVISION.matcher(line);
            while (m.find()) {
                long a;
                long b;
                try {
                    a = Long.parseLong(m.group(1));
                    b = Long.parseLong(m.group(2));
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (b == 0 || a % b == 0 || !FLOAT_CONTEXT.matcher(line).find()) {
                    continue;
                }
                String real = short6((double) a / b);
                out.add(new EditorDiagnostic(li + 1, m.start() + 1, m.end() - m.start(), EditorDiagnostic.Severity.WARNING,
                    "int-division", a + "/" + b + " divides two integers: in C++ it is " + (a / b) + ", not " + real
                    + ". Write " + a + ".0/" + b + " for a real result.", SOURCE));
            }
        }
    }

    // ---- Masking ----------------------------------------------------------------------

    /** The text with comments and the inside of strings blanked, lines and columns kept. */
    static String mask(String text, boolean python) {
        char[] c = text.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (python && ch == '#' || !python && ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (!python && ch == '/' && i + 1 < n && c[i + 1] == '*') {
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    c[i] = ' ';
                    if (i + 1 < n) {
                        c[i + 1] = ' ';
                    }
                    i += 2;
                }
            } else if (ch == '"' || ch == '\'') {
                if (!python && ch == '\'' && i > 0 && Character.isDigit(c[i - 1])) {
                    i++;                                // a digit separator, 1'000
                    continue;
                }
                boolean triple = python && i + 2 < n && c[i + 1] == ch && c[i + 2] == ch;
                int q = triple ? 3 : 1;
                i += q;
                while (i < n) {
                    if (c[i] == '\\') {
                        c[i] = ' ';
                        if (i + 1 < n && c[i + 1] != '\n') {
                            c[i + 1] = ' ';
                        }
                        i += 2;
                        continue;
                    }
                    if (c[i] == ch && (!triple || i + 2 < n && c[i + 1] == ch && c[i + 2] == ch)) {
                        i += q;
                        break;
                    }
                    if (c[i] == '\n' && !triple) {
                        break;
                    }
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }
}
