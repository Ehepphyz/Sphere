package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which occurrences of a name a rename changes, and why.
 *
 * Only the name as code is renamed: never inside a comment or a string, except in
 * the braces of a Python f-string, which are code. The scope is chosen as a reader
 * would: a name declared inside the function holding the caret (a parameter, an
 * assignment, a C++ declaration, a loop variable) is that function's own, and the
 * same name elsewhere is another variable left alone; any other name is renamed
 * in the whole file. A member reached through "." stays apart from a local of the
 * same name in Python and Julia, where they never mix; in C++ a member is also
 * used bare inside its class, so a file-wide rename takes both.
 */
public final class SmartRename {

    /**
     * @param name    the name renamed
     * @param ranges  [start, end) of each occurrence, in document order
     * @param primary the index of the occurrence holding the caret
     * @param scope   what the box says: "in loop()", "in this file", "attribute .x in this file"
     */
    public record Plan(String name, List<int[]> ranges, int primary, String scope, int scopeFrom, int scopeTo) {
    }

    private static final Set<String> NOT_TYPES = Set.of(
        "return", "delete", "throw", "case", "goto", "else", "new", "co_return", "co_yield", "co_await",
        "sizeof", "typedef", "using", "namespace", "operator", "and", "or", "not", "if", "while", "for",
        "switch", "do", "public", "private", "protected", "friend", "virtual", "default", "break", "continue");

    private SmartRename() {
    }

    /**
     * The rename of the name at the caret, or null when the caret is not on a name
     * that can be renamed (a keyword, a comment, a string, a number).
     *
     * @param kindAt the token kind painted at an offset
     */
    public static Plan plan(String text, int caret, LanguageSpec spec, List<DocSymbol> symbols,
                            IntFunction<TokenKind> kindAt) {
        int[] w = wordAt(text, caret);
        if (w == null) {
            return null;
        }
        String name = text.substring(w[0], w[1]);
        TokenKind atKind = kindAt.apply(w[0]);
        if (atKind == TokenKind.COMMENT || atKind == TokenKind.STRING && !inFStringBraces(text, w[0], spec)
                || atKind == TokenKind.CHARACTER || spec.isKeyword(name) || spec.types.contains(name)) {
            return null;
        }
        boolean caseless = spec.caseInsensitive;
        boolean qualifiedAtCaret = qualified(text, w[0]);
        boolean separateMembers = spec == LanguageSpec.PYTHON || spec == LanguageSpec.JULIA
                                  || spec == LanguageSpec.JAVASCRIPT;

        List<int[]> all = new ArrayList<>();
        String needle = caseless ? name.toLowerCase(Locale.ROOT) : name;
        String hay = caseless ? text.toLowerCase(Locale.ROOT) : text;
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            int e = i + needle.length();
            if (i > 0 && isWordChar(text.charAt(i - 1)) || e < text.length() && isWordChar(text.charAt(e))) {
                continue;
            }
            TokenKind k = kindAt.apply(i);
            if (k == TokenKind.COMMENT || k == TokenKind.CHARACTER
                    || k == TokenKind.STRING && !inFStringBraces(text, i, spec)) {
                continue;
            }
            if (k == TokenKind.PREPROCESSOR && lineOf(text, i).stripLeading().matches("#\\s*(include|import).*")) {
                continue;
            }
            if (spec == LanguageSpec.PYTHON && isKeywordArgument(text, i, e)) {
                continue;                               // f(total=1) names the callee's parameter
            }
            all.add(new int[] { i, e });
        }
        if (all.isEmpty()) {
            return null;
        }

        int caretLine = lineIndex(text, w[0]);
        DocSymbol fn = null;
        for (DocSymbol s : symbols) {
            if (s.kind.isCallable() && s.contains(caretLine) && (fn == null || s.line >= fn.line)) {
                fn = s;
            }
        }
        List<int[]> chosen = all;
        String scope = "in this file";
        int scopeFrom = 0;
        int scopeTo = text.length();
        boolean local = false;
        if (fn != null && !qualifiedAtCaret) {
            int from = lineStart(text, fn.line);
            int to = lineEnd(text, fn.endLine);
            if (isLocal(name, fn, text, spec)) {
                List<int[]> inner = new ArrayList<>();
                for (int[] r : all) {
                    if (r[0] >= from && r[1] <= to) {
                        inner.add(r);
                    }
                }
                chosen = inner;
                scope = "in " + fn.name + "()";
                scopeFrom = from;
                scopeTo = to;
                local = true;
            }
        }
        if (!local && !qualifiedAtCaret) {
            // A name of the file: the functions that declare one of their own keep it.
            List<int[]> kept = new ArrayList<>();
            List<int[]> skipped = new ArrayList<>();
            for (DocSymbol s : symbols) {
                if (s.kind.isCallable() && !s.contains(caretLine) && isLocal(name, s, text, spec)) {
                    skipped.add(new int[] { lineStart(text, s.line), lineEnd(text, s.endLine) });
                }
            }
            for (int[] r : chosen) {
                boolean inOwn = false;
                for (int[] s : skipped) {
                    inOwn |= r[0] >= s[0] && r[1] <= s[1];
                }
                if (!inOwn) {
                    kept.add(r);
                }
            }
            chosen = kept;
        }

        // Members and plain names.
        List<int[]> ranges = new ArrayList<>();
        for (int[] r : chosen) {
            boolean q = qualified(text, r[0]);
            if (qualifiedAtCaret) {
                if (q || !separateMembers) {
                    ranges.add(r);
                }
            } else if (!q || (!separateMembers && !local)) {
                ranges.add(r);
            }
        }
        if (qualifiedAtCaret && separateMembers) {
            scope = "attribute ." + name + " in this file";
        }
        int primary = -1;
        for (int k = 0; k < ranges.size(); k++) {
            if (ranges.get(k)[0] == w[0]) {
                primary = k;
            }
        }
        if (primary < 0) {
            return null;
        }
        return new Plan(name, ranges, primary, scope, scopeFrom, scopeTo);
    }

    /** Whether a new name may stand where the old one was. */
    public static String problemWith(String newName, LanguageSpec spec) {
        if (newName.isEmpty()) {
            return "Type the new name";
        }
        if (!Character.isLetter(newName.charAt(0)) && newName.charAt(0) != '_') {
            return "A name starts with a letter or _";
        }
        for (int i = 1; i < newName.length(); i++) {
            if (!isWordChar(newName.charAt(i))) {
                return "A name holds only letters, digits and _";
            }
        }
        if (spec.isKeyword(newName) || spec.types.contains(newName)) {
            return "'" + newName + "' is a keyword of " + spec.name;
        }
        return null;
    }

    // ---- Is the name the function's own? -------------------------------------------

    static boolean isLocal(String name, DocSymbol fn, String text, LanguageSpec spec) {
        if (parameters(fn.detail, spec).contains(name)) {
            return true;
        }
        // The body, after the signature line: the function's own name is not one of its locals.
        int from = fn.endLine > fn.line ? lineStart(text, fn.line + 1) : lineStart(text, fn.line);
        int to = lineEnd(text, fn.endLine);
        String q = Pattern.quote(name);
        String body = text.substring(Math.min(from, text.length()), Math.min(Math.max(from, to), text.length()));
        if (spec == LanguageSpec.PYTHON) {
            if (Pattern.compile("(?m)^\\s*(global|nonlocal)\\b[^\\n]*\\b" + q + "\\b").matcher(body).find()) {
                return false;
            }
            return Pattern.compile("(?m)^\\s*(?:[\\w\\s,*()\\[\\]]*?[\\s,(\\[*])?" + q
                                   + "\\b[\\w\\s,*()\\[\\]]*(?::[^=\\n]*)?(?:[-+*/%&|^@]|//|\\*\\*)?=(?!=)").matcher(body).find()
                || Pattern.compile("\\bfor\\s+[\\w\\s,()]*\\b" + q + "\\b[\\w\\s,()]*\\s+in\\b").matcher(body).find()
                || Pattern.compile("\\bas\\s+" + q + "\\b").matcher(body).find()
                || Pattern.compile("(?m)^\\s*(?:def|class)\\s+" + q + "\\b").matcher(body).find()
                || Pattern.compile("(?m)^\\s*(?:from\\s+\\S+\\s+)?import\\s+[^\\n]*\\b" + q + "\\b").matcher(body).find();
        }
        if (spec == LanguageSpec.JULIA) {
            if (Pattern.compile("\\bglobal\\s+[^\\n]*\\b" + q + "\\b").matcher(body).find()) {
                return false;
            }
            return Pattern.compile("(?m)^\\s*(?:local\\s+)?" + q + "\\s*(?:::[^=\\n]+)?=(?!=)").matcher(body).find()
                || Pattern.compile("\\bfor\\s+" + q + "\\b").matcher(body).find()
                || Pattern.compile("\\blocal\\s+" + q + "\\b").matcher(body).find();
        }
        if (spec == LanguageSpec.FORTRAN) {
            return Pattern.compile("(?im)::[^\\n]*\\b" + q + "\\b").matcher(body).find();
        }
        if (spec == LanguageSpec.CPP || spec == LanguageSpec.JAVA || spec == LanguageSpec.JAVASCRIPT) {
            return declaredIn(name, body, spec);
        }
        return false;
    }

    /** A C-family declaration of the name: a type (or let, var, auto) just before it, a declarator just after. */
    private static boolean declaredIn(String name, String body, LanguageSpec spec) {
        for (String line : body.split("\n")) {
            if (!line.contains(name)) {
                continue;
            }
            int comment = line.indexOf("//");
            String code = comment >= 0 ? line.substring(0, comment) : line;
            List<EditEcho.Tok> toks = EditEcho.tokenize(code);
            for (int i = 0; i < toks.size(); i++) {
                EditEcho.Tok t = toks.get(i);
                if (t.kind() != EditEcho.Kind.IDENT || !t.text().equals(name)) {
                    continue;
                }
                String next = i + 1 < toks.size() ? toks.get(i + 1).text() : ";";
                if (!Set.of("=", ";", "(", "{", "[", ",", ":", ")").contains(next)) {
                    continue;
                }
                int p = i - 1;
                boolean pointer = false;
                while (p >= 0 && Set.of("*", "&", "&&", "const", "volatile").contains(toks.get(p).text())) {
                    pointer |= !toks.get(p).text().startsWith("c") && !toks.get(p).text().startsWith("v");
                    p--;
                }
                if (p < 0) {
                    continue;
                }
                EditEcho.Tok before = toks.get(p);
                boolean typeWord = spec.types.contains(before.text())
                                   || Set.of("auto", "let", "var", "const").contains(before.text());
                if (!before.text().equals(">") && !(before.kind() == EditEcho.Kind.IDENT
                        && !NOT_TYPES.contains(before.text()) && (!spec.isKeyword(before.text()) || typeWord))) {
                    continue;
                }
                // A declaration opens a statement (or a for, a parameter): "return y * scale;" is a product.
                int s = typeStart(toks, p);
                String opener = s <= 0 ? ";" : toks.get(s - 1).text();
                if (Set.of(";", "{", "}").contains(opener)) {
                    // "TH1F* h = ...": a pointer to a class is a declaration only with an initialiser or an end.
                    if (!pointer || typeWord || Set.of("=", ";", "(", "{", "[").contains(next)) {
                        return true;
                    }
                } else if (Set.of("(", ",").contains(opener) && (typeWord || before.text().equals(">"))) {
                    return true;                        // for (auto& p : v), for (int i = 0; ...)
                }
            }
        }
        return false;
    }

    /** The first token of the type ending at index p: "std::vector<double>", "const unsigned int". */
    private static int typeStart(List<EditEcho.Tok> toks, int p) {
        int s = p;
        if (toks.get(s).text().equals(">")) {
            int depth = 0;
            while (s >= 0) {
                String t = toks.get(s).text();
                if (t.equals(">")) {
                    depth++;
                } else if (t.equals(">>")) {
                    depth += 2;
                } else if (t.equals("<")) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                s--;
            }
            s--;                                        // the template's name
            if (s < 0) {
                return 0;
            }
        }
        while (s - 2 >= 0 && toks.get(s - 1).text().equals("::") && toks.get(s - 2).kind() == EditEcho.Kind.IDENT) {
            s -= 2;
        }
        while (s - 1 >= 0 && Set.of("const", "static", "constexpr", "inline", "extern", "volatile", "mutable",
                                     "register", "thread_local", "unsigned", "signed", "long", "short", "struct",
                                     "class", "typename", "final")
                                .contains(toks.get(s - 1).text())) {
            s--;
        }
        return s;
    }

    /** The parameter names of a signature "(int n, const TH1F& h = nullptr)" or "(self, x: float = 1)". */
    static List<String> parameters(String detail, LanguageSpec spec) {
        List<String> out = new ArrayList<>();
        if (detail == null) {
            return out;
        }
        int open = detail.indexOf('(');
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int close = -1;
        for (int i = open; i < detail.length(); i++) {
            char c = detail.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth--;
                if (depth == 0 && c == ')') {
                    close = i;
                    break;
                }
            }
        }
        String inside = detail.substring(open + 1, close < 0 ? detail.length() : close);
        depth = 0;
        int start = 0;
        List<String> params = new ArrayList<>();
        for (int i = 0; i <= inside.length(); i++) {
            char c = i < inside.length() ? inside.charAt(i) : ',';
            if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                params.add(inside.substring(start, i));
                start = i + 1;
            }
        }
        boolean python = spec == LanguageSpec.PYTHON || spec == LanguageSpec.JULIA;
        for (String p : params) {
            String s = p;
            int eq = s.indexOf('=');
            if (eq >= 0) {
                s = s.substring(0, eq);
            }
            if (python) {
                int colon = s.indexOf(':');
                if (colon >= 0) {
                    s = s.substring(0, colon);
                }
                s = s.replace("*", "").strip();
                if (s.matches("[A-Za-z_]\\w*")) {
                    out.add(s);
                }
            } else {
                Matcher m = Pattern.compile("([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\]\\s*)*$").matcher(s.strip());
                if (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** "f(total=1)": the name of the called function's parameter, not the variable; "def f(total=1)" is the parameter. */
    private static boolean isKeywordArgument(String text, int s, int e) {
        int p = e;
        while (p < text.length() && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
            p++;
        }
        if (p >= text.length() || text.charAt(p) != '=' || p + 1 < text.length() && text.charAt(p + 1) == '=') {
            return false;
        }
        int b = s - 1;
        while (b >= 0 && (text.charAt(b) == ' ' || text.charAt(b) == '\t')) {
            b--;
        }
        if (b < 0 || text.charAt(b) != '(' && text.charAt(b) != ',') {
            return false;
        }
        String line = lineOf(text, s).stripLeading();
        return !(line.startsWith("def ") || line.startsWith("async def ") || line.contains("lambda"));
    }

    // ---- Text helpers -----------------------------------------------------------------

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int[] wordAt(String text, int offset) {
        int o = Math.max(0, Math.min(offset, text.length()));
        int s = o;
        while (s > 0 && isWordChar(text.charAt(s - 1))) {
            s--;
        }
        int e = o;
        while (e < text.length() && isWordChar(text.charAt(e))) {
            e++;
        }
        if (e <= s || Character.isDigit(text.charAt(s))) {
            return null;
        }
        return new int[] { s, e };
    }

    /** Whether the occurrence follows ".", "->" or "::" (blanks allowed between). */
    private static boolean qualified(String text, int at) {
        int p = at - 1;
        while (p >= 0 && (text.charAt(p) == ' ' || text.charAt(p) == '\t')) {
            p--;
        }
        if (p < 0) {
            return false;
        }
        char c = text.charAt(p);
        return c == '.' && (p == 0 || text.charAt(p - 1) != '.')
            || c == '>' && p > 0 && text.charAt(p - 1) == '-'
            || c == ':' && p > 0 && text.charAt(p - 1) == ':';
    }

    /** Inside the braces of a Python f-string: code, though painted as a string. */
    private static boolean inFStringBraces(String text, int at, LanguageSpec spec) {
        if (spec != LanguageSpec.PYTHON) {
            return false;
        }
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        int open = -1;
        int depth = 0;
        for (int i = at - 1; i >= lineStart; i--) {
            char c = text.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            } else if (c == '"' || c == '\'') {
                if (depth == 0 && open < 0) {
                    return false;                       // the quote closes before any brace: plain text
                }
            }
        }
        if (open < 0) {
            return false;
        }
        for (int i = open - 1; i >= lineStart; i--) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int p = i - 1;
                while (p >= lineStart && Character.isLetter(text.charAt(p))) {
                    p--;
                }
                String prefix = text.substring(p + 1, i).toLowerCase(Locale.ROOT);
                return prefix.contains("f");
            }
        }
        return false;
    }

    private static int lineIndex(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static int lineStart(String text, int line) {
        int start = 0;
        for (int l = 0; l < line; l++) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                return text.length();
            }
            start = nl + 1;
        }
        return start;
    }

    private static int lineEnd(String text, int line) {
        int start = lineStart(text, line);
        int nl = text.indexOf('\n', start);
        return nl < 0 ? text.length() : nl;
    }

    private static String lineOf(String text, int at) {
        int s = text.lastIndexOf('\n', at - 1) + 1;
        int e = text.indexOf('\n', at);
        return text.substring(s, e < 0 ? text.length() : e);
    }
}
