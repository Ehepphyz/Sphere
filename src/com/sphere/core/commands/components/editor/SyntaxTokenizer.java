package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-pass lexer over a text region. Emits only the spans that are not plain
 * text, sorted and never overlapping, so the highlighter has little to apply
 * and a span can be found by binary search.
 */
public final class SyntaxTokenizer {

    /** One colored region, offsets relative to the text handed to tokenize(). */
    public static final class Span {
        public final int start;
        public final int end;
        public final TokenKind kind;
        /** A string or comment that reached the end of its line or file unclosed. */
        public final boolean unterminated;

        public Span(int start, int end, TokenKind kind) {
            this(start, end, kind, false);
        }

        public Span(int start, int end, TokenKind kind, boolean unterminated) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.unterminated = unterminated;
        }

        public boolean sameAs(Span o, int shift) {
            return o != null && start == o.start + shift && end == o.end + shift && kind == o.kind;
        }
    }

    private final LanguageSpec spec;

    public SyntaxTokenizer(LanguageSpec spec) {
        this.spec = spec == null ? LanguageSpec.NONE : spec;
    }

    /**
     * @param text          the region to scan
     * @param insideComment true when the region starts inside a block comment
     * @param out           receives the spans found
     * @return true when the region ends inside a block comment
     */
    public boolean tokenize(String text, boolean insideComment, List<Span> out) {
        if (spec.isPlain() || text == null || text.isEmpty()) {
            return insideComment;
        }
        switch (spec.dialect) {
            case MARKDOWN: markdown(text, out); return false;
            case LATEX:    latex(text, out);    return false;
            case CONFIG:   config(text, out);   return false;
            case MARKUP:   markup(text, out);   return false;
            case PLAIN:    return insideComment;
            default:       return code(text, insideComment, out);
        }
    }

    /** Convenience for a whole buffer. */
    public List<Span> tokenizeAll(String text) {
        List<Span> spans = new ArrayList<>();
        tokenize(text, false, spans);
        return spans;
    }

    // =======================================================================
    // Programming languages
    // =======================================================================

    private boolean code(String text, boolean insideComment, List<Span> out) {
        final int n = text.length();
        int i = 0;
        boolean atLineStart = true;

        if (insideComment && spec.blockCommentClose != null) {
            int close = text.indexOf(spec.blockCommentClose);
            if (close < 0) {
                out.add(new Span(0, n, TokenKind.COMMENT));
                return true;
            }
            i = close + spec.blockCommentClose.length();
            out.add(new Span(0, i, TokenKind.COMMENT));
            atLineStart = false;
        }

        while (i < n) {
            char c = text.charAt(i);

            if (c == '\n') {
                atLineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }

            // Block comment.
            if (spec.blockCommentOpen != null && text.startsWith(spec.blockCommentOpen, i)) {
                int from = i + spec.blockCommentOpen.length();
                int close = text.indexOf(spec.blockCommentClose, from);
                if (close < 0) {
                    out.add(new Span(i, n, TokenKind.COMMENT, true));
                    return true;
                }
                int end = close + spec.blockCommentClose.length();
                out.add(new Span(i, end, TokenKind.COMMENT));
                i = end;
                atLineStart = false;
                continue;
            }

            // Line comment.
            int lc = lineCommentAt(text, i, atLineStart);
            if (lc > 0) {
                int end = eol(text, i);
                out.add(new Span(i, end, TokenKind.COMMENT));
                i = end;
                continue;
            }

            // Preprocessor directive: the directive itself, then the rest of the
            // line read as code, so a macro body keeps its colors.
            if (spec.hasPreprocessor && c == '#' && atLineStart) {
                int j = i + 1;
                while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                    j++;
                }
                int w = j;
                while (w < n && Character.isLetter(text.charAt(w))) {
                    w++;
                }
                out.add(new Span(i, w, TokenKind.PREPROCESSOR));
                String directive = text.substring(j, w);
                i = w;
                atLineStart = false;
                if (directive.equals("include") || directive.equals("import")) {
                    int k = i;
                    while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                        k++;
                    }
                    if (k < n && (text.charAt(k) == '<' || text.charAt(k) == '"')) {
                        char close = text.charAt(k) == '<' ? '>' : '"';
                        int e = text.indexOf(close, k + 1);
                        int lineEnd = eol(text, k);
                        if (e < 0 || e > lineEnd) {
                            e = lineEnd - 1;
                        }
                        out.add(new Span(k, e + 1, TokenKind.STRING));
                        i = e + 1;
                    }
                } else if (directive.equals("define")) {
                    int k = i;
                    while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                        k++;
                    }
                    int e = k;
                    while (e < n && Character.isJavaIdentifierPart(text.charAt(e))) {
                        e++;
                    }
                    if (e > k) {
                        out.add(new Span(k, e, TokenKind.FUNCTION));
                        i = e;
                    }
                }
                continue;
            }

            boolean lineStart = atLineStart;
            atLineStart = false;

            // ${VAR}, $ENV{VAR}, $<genex>, $VAR
            if (spec.dollarVariables && c == '$') {
                int end = dollarEnd(text, i);
                if (end > i + 1) {
                    String word = text.substring(i, end);
                    TokenKind kind = spec.constants.contains(word.toLowerCase(Locale.ROOT))
                            || spec.constants.contains(word)
                            ? TokenKind.CONSTANT
                            : (i + 1 < n && text.charAt(i + 1) == '<') ? TokenKind.PREPROCESSOR
                            : TokenKind.VARIABLE;
                    out.add(new Span(i, end, kind));
                    i = end;
                    continue;
                }
                i++;
                continue;
            }

            // %VAR% and !VAR! of cmd.exe.
            if (spec.percentVariables && (c == '%' || c == '!')) {
                int end = percentEnd(text, i);
                if (end > i + 1) {
                    out.add(new Span(i, end, TokenKind.VARIABLE));
                    i = end;
                    continue;
                }
            }

            // Labels of cmd.exe (":label" opening the line).
            if (spec.remComments && c == ':' && lineStart) {
                int end = eol(text, i);
                out.add(new Span(i, end, TokenKind.FUNCTION));
                i = end;
                continue;
            }

            // Decorator or macro call.
            if (spec.hasDecorators && c == '@') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j))
                        || text.charAt(j) == '_' || text.charAt(j) == '.' || text.charAt(j) == '!')) {
                    j++;
                }
                if (j > i + 1) {
                    out.add(new Span(i, j, TokenKind.DECORATOR));
                    i = j;
                } else {
                    out.add(new Span(i, i + 1, TokenKind.OPERATOR));
                    i++;
                }
                continue;
            }

            // Strings and characters, with escapes and triple quotes.
            if (c == '"' || c == '\'' || (c == '`' && spec.backtickStrings)) {
                if (c == '\'' && !spec.singleQuoteIsString && isPostfixQuote(text, i)) {
                    // Julia's transpose, C++'s digit separator: not a literal.
                    out.add(new Span(i, i + 1, TokenKind.OPERATOR));
                    i++;
                    continue;
                }
                i = scanQuoted(text, i, i, c, out);
                continue;
            }

            // Numbers, including hex, binary, exponents and separators.
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1))
                    && (i == 0 || !Character.isJavaIdentifierPart(text.charAt(i - 1))))) {
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_') {
                        j++;
                    } else if ((d == '+' || d == '-') && isExponent(text.charAt(j - 1))
                               && !isHexLiteral(text, i)) {
                        j++;
                    } else if (d == '\'' && spec == LanguageSpec.CPP && j + 1 < n
                               && Character.isLetterOrDigit(text.charAt(j + 1))) {
                        j++;
                    } else {
                        break;
                    }
                }
                // "1..10" is a range, not a number with two points.
                for (int d = i + 1; d + 1 < j; d++) {
                    if (text.charAt(d) == '.' && text.charAt(d + 1) == '.') {
                        j = d;
                        break;
                    }
                }
                out.add(new Span(i, j, TokenKind.NUMBER));
                i = j;
                continue;
            }

            // Identifiers.
            if (Character.isJavaIdentifierStart(c) && c != '$') {
                int j = i + 1;
                while (j < n && isIdentifierPart(text.charAt(j))) {
                    j++;
                }
                // Julia's mutating functions end in '!'.
                if (spec == LanguageSpec.JULIA && j < n && text.charAt(j) == '!'
                        && (j + 1 >= n || text.charAt(j + 1) != '=')) {
                    j++;
                }
                String word = text.substring(i, j);

                // "rem" opens a comment in cmd.exe.
                if (spec.remComments && word.equalsIgnoreCase("rem")
                        && (j >= n || Character.isWhitespace(text.charAt(j)))) {
                    int end = eol(text, i);
                    out.add(new Span(i, end, TokenKind.COMMENT));
                    i = end;
                    continue;
                }

                // String prefixes: f"..", r'..', u8"..", L'..', and C++ raw strings.
                if (j < n && (text.charAt(j) == '"' || text.charAt(j) == '\'')) {
                    if (spec.rawStrings && text.charAt(j) == '"'
                            && (word.equals("R") || word.equals("LR") || word.equals("uR")
                                || word.equals("UR") || word.equals("u8R"))) {
                        i = scanRawString(text, i, j, out);
                        continue;
                    }
                    if (isStringPrefix(word)) {
                        i = scanQuoted(text, i, j, text.charAt(j), out);
                        continue;
                    }
                }

                Span classified = classify(word, text, i, j);
                if (classified != null) {
                    out.add(classified);
                }
                i = j;
                continue;
            }

            if (isOperator(c)) {
                int j = i;
                while (j < n && isOperator(text.charAt(j))) {
                    j++;
                }
                out.add(new Span(i, j, TokenKind.OPERATOR));
                i = j;
                continue;
            }

            i++;
        }
        return false;
    }

    /** Length of the line comment opening at i, or 0. */
    private int lineCommentAt(String text, int i, boolean atLineStart) {
        if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
            // A shell only opens a comment at a word boundary: "a#b" and "${#a}" are not comments.
            if (spec.dollarVariables && spec.lineComment.equals("#") && spec != LanguageSpec.CMAKE
                    && i > 0 && !Character.isWhitespace(text.charAt(i - 1))
                    && text.charAt(i - 1) != ';' && text.charAt(i - 1) != '(') {
                return 0;
            }
            // "::" is a comment in cmd.exe only when it opens the line.
            if (spec.remComments && !atLineStart) {
                return 0;
            }
            return spec.lineComment.length();
        }
        for (String extra : spec.extraLineComments) {
            if (text.startsWith(extra, i)) {
                return extra.length();
            }
        }
        return 0;
    }

    private Span classify(String word, String text, int i, int j) {
        if (spec.isKeyword(word)) {
            return new Span(i, j, TokenKind.KEYWORD);
        }
        if (spec.constants.contains(word)) {
            return new Span(i, j, TokenKind.CONSTANT);
        }
        if (spec.types.contains(word)) {
            return new Span(i, j, TokenKind.TYPE);
        }
        if (spec.isBuiltin(word)) {
            return new Span(i, j, TokenKind.FUNCTION);
        }
        if (isCallSite(text, j, text.length())) {
            return new Span(i, j, TokenKind.FUNCTION);
        }
        if (spec == LanguageSpec.CMAKE) {
            // In CMake an all-caps word is a variable: ROOT_FOUND, MY_SOURCES.
            if (isAllCaps(word) && word.length() > 1) {
                return new Span(i, j, TokenKind.VARIABLE);
            }
            return null;
        }
        if ((spec == LanguageSpec.CPP || spec == LanguageSpec.JAVA || spec == LanguageSpec.JAVASCRIPT
                || spec == LanguageSpec.PYTHON || spec == LanguageSpec.JULIA)
                && word.length() > 1 && isAllCaps(word) && hasLetter(word)) {
            // Macros and constants: M_PI, MAX_EVENTS.
            return new Span(i, j, TokenKind.CONSTANT);
        }
        return null;
    }

    private static boolean isAllCaps(String word) {
        for (int k = 0; k < word.length(); k++) {
            char ch = word.charAt(k);
            if (Character.isLowerCase(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLetter(String word) {
        int letters = 0;
        for (int k = 0; k < word.length(); k++) {
            if (Character.isLetter(word.charAt(k))) {
                letters++;
            }
        }
        return letters >= 2;
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isJavaIdentifierPart(ch) && ch != '$';
    }

    private boolean isStringPrefix(String word) {
        if (word.length() > 3) {
            return false;
        }
        if (spec == LanguageSpec.PYTHON) {
            String w = word.toLowerCase(Locale.ROOT);
            return w.equals("r") || w.equals("b") || w.equals("f") || w.equals("u")
                || w.equals("rb") || w.equals("br") || w.equals("fr") || w.equals("rf");
        }
        if (spec == LanguageSpec.CPP) {
            return word.equals("L") || word.equals("u") || word.equals("U") || word.equals("u8");
        }
        if (spec == LanguageSpec.JULIA) {
            return word.equals("r") || word.equals("raw") || word.equals("b") || word.equals("L");
        }
        return false;
    }

    /** A quote right after an identifier or a closer is an operator. */
    private static boolean isPostfixQuote(String text, int i) {
        if (i == 0) {
            return false;
        }
        char p = text.charAt(i - 1);
        return Character.isLetterOrDigit(p) || p == '_' || p == ')' || p == ']' || p == '}'
               || p == '\'' || p == '.';
    }

    private static boolean isExponent(char c) {
        return c == 'e' || c == 'E' || c == 'p' || c == 'P' || c == 'd' || c == 'D';
    }

    private static boolean isHexLiteral(String text, int start) {
        return start + 1 < text.length() && text.charAt(start) == '0'
               && (text.charAt(start + 1) == 'x' || text.charAt(start + 1) == 'X');
    }

    private static boolean isOperator(char c) {
        return "+-*/%=<>!&|^~?:;,.\\".indexOf(c) >= 0;
    }

    /** An identifier immediately followed by '(' reads as a call. */
    private static boolean isCallSite(String text, int from, int n) {
        int k = from;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        return k < n && text.charAt(k) == '(';
    }

    private static int eol(String text, int from) {
        int e = text.indexOf('\n', from);
        return e < 0 ? text.length() : e;
    }

    /** End of ${..} (nested), $ENV{..}, $<..> (nested), $name, $1, $@, or i+1. */
    private int dollarEnd(String text, int i) {
        final int n = text.length();
        if (i + 1 >= n) {
            return i + 1;
        }
        char next = text.charAt(i + 1);
        if (next == '{') {
            return matchNested(text, i + 1, '{', '}');
        }
        if (next == '<' && spec == LanguageSpec.CMAKE) {
            return matchNested(text, i + 1, '<', '>');
        }
        if (Character.isLetter(next) || next == '_') {
            int j = i + 1;
            while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_'
                    || (spec == LanguageSpec.POWERSHELL && text.charAt(j) == ':'))) {
                j++;
            }
            // $ENV{PATH} and $CACHE{X}
            if (j < n && text.charAt(j) == '{') {
                return matchNested(text, j, '{', '}');
            }
            return j;
        }
        if (Character.isDigit(next) || "@#?$!*-".indexOf(next) >= 0) {
            return i + 2;
        }
        return i + 1;
    }

    /** Offset after the closer matching the opener at {@code at}, bounded by the line. */
    private static int matchNested(String text, int at, char open, char close) {
        final int n = text.length();
        int depth = 0;
        for (int k = at; k < n; k++) {
            char ch = text.charAt(k);
            if (ch == '\n') {
                return k;
            }
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return k + 1;
                }
            }
        }
        return n;
    }

    private static int percentEnd(String text, int i) {
        final int n = text.length();
        char c = text.charAt(i);
        if (c == '%' && i + 1 < n) {
            char next = text.charAt(i + 1);
            if (Character.isDigit(next) || next == '*') {
                return i + 2;
            }
            if (next == '~') {
                int j = i + 2;
                while (j < n && Character.isLetter(text.charAt(j))) {
                    j++;
                }
                if (j < n && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                return j;
            }
            if (next == '%') {
                // %%i of a for loop
                int j = i + 2;
                while (j < n && Character.isLetter(text.charAt(j))) {
                    j++;
                }
                return j;
            }
        }
        int close = text.indexOf(c, i + 1);
        int lineEnd = eol(text, i);
        if (close > i + 1 && close < lineEnd) {
            for (int k = i + 1; k < close; k++) {
                char ch = text.charAt(k);
                if (ch == ' ' && c == '!') {
                    return i + 1;
                }
            }
            return close + 1;
        }
        return i + 1;
    }

    /**
     * Scans a quoted literal whose quote sits at {@code quoteAt}; the span starts
     * at {@code spanStart}, earlier when a prefix (f, r, u8) leads it.
     */
    private int scanQuoted(String text, int spanStart, int quoteAt, char quote, List<Span> out) {
        final int n = text.length();
        TokenKind kind = (quote == '"' || quote == '`' || spec.singleQuoteIsString)
                ? TokenKind.STRING : TokenKind.CHARACTER;

        boolean triple = (spec.tripleQuotes || quote == '`')
                && quoteAt + 2 < n
                && text.charAt(quoteAt + 1) == quote && text.charAt(quoteAt + 2) == quote;
        boolean multiline = triple || quote == '`' || spec.multilineStrings;
        boolean escapes = !(spec == LanguageSpec.SHELL && quote == '\'')
                          && !(spec == LanguageSpec.POWERSHELL && quote == '\'');
        int i = quoteAt + (triple ? 3 : 1);
        boolean closed = false;
        // Variables inside a double-quoted CMake or shell string keep their own color.
        boolean interpolate = spec.dollarVariables && quote == '"';
        int pieceStart = spanStart;

        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && escapes) {
                i += 2;
                continue;
            }
            if (!multiline && c == '\n') {
                break; // unterminated: stop at the line so one typo cannot recolor the file
            }
            if (interpolate && c == '$') {
                int end = dollarEnd(text, i);
                if (end > i + 1) {
                    if (i > pieceStart) {
                        out.add(new Span(pieceStart, i, kind));
                    }
                    out.add(new Span(i, end, TokenKind.VARIABLE));
                    i = end;
                    pieceStart = i;
                    continue;
                }
            }
            if (c == quote) {
                if (!triple) {
                    i++;
                    closed = true;
                    break;
                }
                if (i + 2 < n && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                    i += 3;
                    closed = true;
                    break;
                }
            }
            i++;
        }
        int end = Math.min(i, n);
        if (end > pieceStart) {
            out.add(new Span(pieceStart, end, kind, !closed));
        } else if (!closed && !out.isEmpty()) {
            // The string ended on a variable; keep the unterminated mark somewhere.
            Span last = out.remove(out.size() - 1);
            out.add(new Span(last.start, last.end, last.kind, true));
        }

        // A JSON key is the string before a colon.
        if (spec.jsonKeys && closed && kind == TokenKind.STRING) {
            int k = end;
            while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                k++;
            }
            if (k < n && text.charAt(k) == ':') {
                Span last = out.remove(out.size() - 1);
                out.add(new Span(last.start, last.end, TokenKind.VARIABLE));
            }
        }
        return end;
    }

    /** R"delim( ... )delim" with its prefix starting at spanStart. */
    private int scanRawString(String text, int spanStart, int quoteAt, List<Span> out) {
        final int n = text.length();
        int paren = text.indexOf('(', quoteAt + 1);
        if (paren < 0 || paren - quoteAt > 17) {
            return scanQuoted(text, spanStart, quoteAt, '"', out);
        }
        String delim = text.substring(quoteAt + 1, paren);
        if (delim.indexOf(' ') >= 0 || delim.indexOf('\n') >= 0 || delim.indexOf('\\') >= 0) {
            return scanQuoted(text, spanStart, quoteAt, '"', out);
        }
        String closer = ")" + delim + "\"";
        int close = text.indexOf(closer, paren + 1);
        int end = close < 0 ? n : close + closer.length();
        out.add(new Span(spanStart, end, TokenKind.STRING, close < 0));
        return end;
    }

    // =======================================================================
    // Markdown
    // =======================================================================

    private void markdown(String text, List<Span> out) {
        final int n = text.length();
        int i = 0;
        boolean inFence = false;
        String fence = null;
        int fenceStart = -1;
        boolean previousBlank = true;

        while (i < n) {
            int eol = eol(text, i);
            int k = i;
            while (k < eol && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                k++;
            }
            boolean blank = k >= eol || (k == eol - 1 && text.charAt(k) == '\r');

            if (inFence) {
                if (text.startsWith(fence, k)) {
                    out.add(new Span(fenceStart, eol, TokenKind.STRING));
                    inFence = false;
                }
                i = eol + 1;
                previousBlank = blank;
                continue;
            }
            if (text.startsWith("```", k) || text.startsWith("~~~", k)) {
                inFence = true;
                fence = text.substring(k, k + 3);
                fenceStart = i;
                i = eol + 1;
                previousBlank = false;
                continue;
            }
            if (blank) {
                i = eol + 1;
                previousBlank = true;
                continue;
            }

            char c = text.charAt(k);
            // Headings.
            if (c == '#') {
                int h = k;
                while (h < eol && text.charAt(h) == '#') {
                    h++;
                }
                if (h - k <= 6 && (h >= eol || text.charAt(h) == ' ' || text.charAt(h) == '\t')) {
                    out.add(new Span(k, eol, TokenKind.KEYWORD));
                    i = eol + 1;
                    previousBlank = false;
                    continue;
                }
            }
            // Block quotes.
            if (c == '>') {
                out.add(new Span(k, eol, TokenKind.COMMENT));
                i = eol + 1;
                previousBlank = false;
                continue;
            }
            // Rules and setext underlines.
            if (isRule(text, k, eol)) {
                out.add(new Span(k, eol, previousBlank ? TokenKind.OPERATOR : TokenKind.KEYWORD));
                i = eol + 1;
                previousBlank = false;
                continue;
            }
            // List markers and task boxes.
            int j = k;
            if ((c == '-' || c == '*' || c == '+') && k + 1 < eol && text.charAt(k + 1) == ' ') {
                out.add(new Span(k, k + 1, TokenKind.OPERATOR));
                j = k + 2;
                if (j + 2 < eol && text.charAt(j) == '[' && text.charAt(j + 2) == ']'
                        && " xX".indexOf(text.charAt(j + 1)) >= 0) {
                    out.add(new Span(j, j + 3, TokenKind.CONSTANT));
                    j += 3;
                }
            } else if (Character.isDigit(c)) {
                int d = k;
                while (d < eol && Character.isDigit(text.charAt(d))) {
                    d++;
                }
                if (d < eol && d - k < 10 && (text.charAt(d) == '.' || text.charAt(d) == ')')
                        && d + 1 < eol && text.charAt(d + 1) == ' ') {
                    out.add(new Span(k, d + 1, TokenKind.OPERATOR));
                    j = d + 2;
                }
            }
            inlineMarkdown(text, j, eol, out);
            i = eol + 1;
            previousBlank = false;
        }
        if (inFence) {
            out.add(new Span(fenceStart, n, TokenKind.STRING, true));
        }
    }

    private static boolean isRule(String text, int from, int eol) {
        char c = text.charAt(from);
        if (c != '-' && c != '*' && c != '_' && c != '=') {
            return false;
        }
        int count = 0;
        for (int k = from; k < eol; k++) {
            char ch = text.charAt(k);
            if (ch == c) {
                count++;
            } else if (ch != ' ' && ch != '\t' && ch != '\r') {
                return false;
            }
        }
        return count >= 3;
    }

    private static void inlineMarkdown(String text, int from, int to, List<Span> out) {
        int k = from;
        while (k < to) {
            char c = text.charAt(k);
            if (c == '\\' && k + 1 < to) {
                k += 2;
                continue;
            }
            if (c == '`') {
                int run = 0;
                while (k + run < to && text.charAt(k + run) == '`') {
                    run++;
                }
                String ticks = "`".repeat(run);
                int close = text.indexOf(ticks, k + run);
                if (close > 0 && close < to) {
                    out.add(new Span(k, close + run, TokenKind.STRING));
                    k = close + run;
                } else {
                    k += run;
                }
                continue;
            }
            if (c == '*' || c == '_') {
                if (c == '_' && k > from && Character.isLetterOrDigit(text.charAt(k - 1))) {
                    k++;
                    continue;
                }
                int run = (k + 1 < to && text.charAt(k + 1) == c) ? 2 : 1;
                String delim = run == 2 ? "" + c + c : "" + c;
                int close = text.indexOf(delim, k + run);
                if (close > k + run && close < to && !Character.isWhitespace(text.charAt(k + run))) {
                    out.add(new Span(k, close + run, run == 2 ? TokenKind.TYPE : TokenKind.DECORATOR));
                    k = close + run;
                    continue;
                }
                k += run;
                continue;
            }
            if (c == '[' || (c == '!' && k + 1 < to && text.charAt(k + 1) == '[')) {
                int open = c == '!' ? k + 1 : k;
                int closeB = text.indexOf(']', open + 1);
                if (closeB > 0 && closeB + 1 < to && text.charAt(closeB + 1) == '(') {
                    int closeP = text.indexOf(')', closeB + 2);
                    if (closeP > 0 && closeP < to) {
                        out.add(new Span(k, closeB + 1, TokenKind.FUNCTION));
                        out.add(new Span(closeB + 1, closeP + 1, TokenKind.STRING));
                        k = closeP + 1;
                        continue;
                    }
                }
                k++;
                continue;
            }
            if (c == '<') {
                int gt = text.indexOf('>', k + 1);
                if (gt > k + 1 && gt < to && gt - k < 200
                        && (Character.isLetter(text.charAt(k + 1)) || text.charAt(k + 1) == '/')) {
                    out.add(new Span(k, gt + 1, TokenKind.PREPROCESSOR));
                    k = gt + 1;
                    continue;
                }
            }
            if (c == '$') {
                int close = text.indexOf('$', k + 1);
                if (close > k + 1 && close < to) {
                    out.add(new Span(k, close + 1, TokenKind.NUMBER));
                    k = close + 1;
                    continue;
                }
            }
            if (c == '|') {
                out.add(new Span(k, k + 1, TokenKind.OPERATOR));
            }
            k++;
        }
    }

    // =======================================================================
    // LaTeX
    // =======================================================================

    private void latex(String text, List<Span> out) {
        final int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '%') {
                int end = eol(text, i);
                out.add(new Span(i, end, TokenKind.COMMENT));
                i = end;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                char next = text.charAt(i + 1);
                if (next == '[' || next == '(') {
                    String close = next == '[' ? "\\]" : "\\)";
                    int e = text.indexOf(close, i + 2);
                    int end = e < 0 ? n : e + 2;
                    out.add(new Span(i, end, TokenKind.NUMBER, e < 0));
                    i = end;
                    continue;
                }
                if (Character.isLetter(next) || next == '@') {
                    int j = i + 1;
                    while (j < n && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '@')) {
                        j++;
                    }
                    if (j < n && text.charAt(j) == '*') {
                        j++;
                    }
                    String cmd = text.substring(i + 1, j);
                    out.add(new Span(i, j, TokenKind.KEYWORD));
                    i = j;
                    if ((cmd.equals("begin") || cmd.equals("end")) && i < n && text.charAt(i) == '{') {
                        int close = text.indexOf('}', i + 1);
                        if (close > i + 1 && close - i < 64) {
                            // The braces stay brackets; only the name is colored.
                            out.add(new Span(i + 1, close, TokenKind.TYPE));
                            i = close;
                        }
                    }
                    continue;
                }
                out.add(new Span(i, i + 2, TokenKind.OPERATOR));
                i += 2;
                continue;
            }
            if (c == '$') {
                boolean display = i + 1 < n && text.charAt(i + 1) == '$';
                String close = display ? "$$" : "$";
                int from = i + close.length();
                int e = text.indexOf(close, from);
                // An inline formula never crosses a blank line.
                int para = text.indexOf("\n\n", from);
                if (e < 0 || (!display && para >= 0 && para < e)) {
                    out.add(new Span(i, i + close.length(), TokenKind.NUMBER));
                    i += close.length();
                    continue;
                }
                int end = e + close.length();
                out.add(new Span(i, end, TokenKind.NUMBER));
                i = end;
                continue;
            }
            if (c == '&' || c == '~' || c == '^' || c == '_') {
                out.add(new Span(i, i + 1, TokenKind.OPERATOR));
                i++;
                continue;
            }
            i++;
        }
    }

    // =======================================================================
    // INI / TOML / YAML / properties / .conf
    // =======================================================================

    private void config(String text, List<Span> out) {
        final int n = text.length();
        int i = 0;
        while (i < n) {
            int eol = eol(text, i);
            int k = i;
            while (k < eol && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                k++;
            }
            if (k >= eol) {
                i = eol + 1;
                continue;
            }
            char c = text.charAt(k);
            if (c == '#' || c == ';' || (c == '/' && k + 1 < eol && text.charAt(k + 1) == '/')) {
                out.add(new Span(k, eol, TokenKind.COMMENT));
                i = eol + 1;
                continue;
            }
            int lineEnd = eol;
            if (lineEnd > k && text.charAt(lineEnd - 1) == '\r') {
                lineEnd--;
            }
            if (c == '[') {
                int close = text.lastIndexOf(']', lineEnd - 1);
                if (close > k) {
                    out.add(new Span(k, close + 1, TokenKind.TYPE));
                    configValue(text, close + 1, eol, out);
                    i = eol + 1;
                    continue;
                }
            }
            if (c == '-' && k + 1 < eol && text.charAt(k + 1) == ' ') {
                out.add(new Span(k, k + 1, TokenKind.OPERATOR));
                k += 2;
                while (k < eol && text.charAt(k) == ' ') {
                    k++;
                }
            }
            int sep = keySeparator(text, k, eol);
            if (sep > k) {
                int keyEnd = sep;
                while (keyEnd > k && (text.charAt(keyEnd - 1) == ' ' || text.charAt(keyEnd - 1) == '\t')) {
                    keyEnd--;
                }
                if (keyEnd > k) {
                    out.add(new Span(k, keyEnd, TokenKind.VARIABLE));
                }
                out.add(new Span(sep, sep + 1, TokenKind.OPERATOR));
                configValue(text, sep + 1, eol, out);
            } else {
                configValue(text, k, eol, out);
            }
            i = eol + 1;
        }
    }

    /** The '=' or ':' that ends a key, outside quotes and brackets, or -1. */
    private static int keySeparator(String text, int from, int eol) {
        for (int k = from; k < eol; k++) {
            char ch = text.charAt(k);
            if (ch == '"' || ch == '\'' || ch == '[' || ch == '{' || ch == '(' || ch == '$' || ch == '%') {
                return -1;
            }
            if (ch == '=') {
                return k;
            }
            if (ch == ':' && (k + 1 >= eol || text.charAt(k + 1) == ' ' || text.charAt(k + 1) == '\t'
                              || text.charAt(k + 1) == '\r')) {
                return k;
            }
        }
        return -1;
    }

    private void configValue(String text, int from, int eol, List<Span> out) {
        int k = from;
        while (k < eol) {
            char ch = text.charAt(k);
            if (ch == ' ' || ch == '\t' || ch == '\r') {
                k++;
                continue;
            }
            if (ch == '#' && k > from && (text.charAt(k - 1) == ' ' || text.charAt(k - 1) == '\t')) {
                out.add(new Span(k, eol, TokenKind.COMMENT));
                return;
            }
            if (ch == '"' || ch == '\'') {
                int close = text.indexOf(ch, k + 1);
                int end = close < 0 || close > eol ? eol : close + 1;
                out.add(new Span(k, end, TokenKind.STRING, close < 0 || close > eol));
                k = end;
                continue;
            }
            if (ch == '$' || ch == '%') {
                int end = ch == '$' ? dollarEndConfig(text, k, eol) : percentEnd(text, k);
                if (end > k + 1 && end <= eol) {
                    out.add(new Span(k, end, TokenKind.VARIABLE));
                    k = end;
                    continue;
                }
                k++;
                continue;
            }
            if (Character.isDigit(ch) || (ch == '-' && k + 1 < eol && Character.isDigit(text.charAt(k + 1)))) {
                int j = k + 1;
                while (j < eol && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '.'
                        || text.charAt(j) == '_' || text.charAt(j) == ':' || text.charAt(j) == '-')) {
                    j++;
                }
                String token = text.substring(k, j);
                boolean numeric = token.matches("-?[0-9][0-9_]*(\\.[0-9_]+)?([eE][-+]?[0-9]+)?");
                if (numeric && (j >= eol || !Character.isLetter(text.charAt(j)))) {
                    out.add(new Span(k, j, TokenKind.NUMBER));
                }
                k = j;
                continue;
            }
            if (Character.isLetter(ch)) {
                int j = k + 1;
                while (j < eol && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                String word = text.substring(k, j);
                boolean alone = (j >= eol || " \t\r,]}#".indexOf(text.charAt(j)) >= 0)
                                && (k == from || " \t=:[,{".indexOf(text.charAt(k - 1)) >= 0);
                if (alone && spec.constants.contains(word)) {
                    out.add(new Span(k, j, TokenKind.CONSTANT));
                }
                k = j;
                continue;
            }
            k++;
        }
    }

    private static int dollarEndConfig(String text, int i, int eol) {
        if (i + 1 >= eol) {
            return i + 1;
        }
        char next = text.charAt(i + 1);
        if (next == '{' || next == '(') {
            char close = next == '{' ? '}' : ')';
            int e = text.indexOf(close, i + 2);
            return e < 0 || e > eol ? i + 1 : e + 1;
        }
        int j = i + 1;
        while (j < eol && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
            j++;
        }
        return j;
    }

    // =======================================================================
    // XML / HTML
    // =======================================================================

    private void markup(String text, List<Span> out) {
        final int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '<') {
                if (text.startsWith("<!--", i)) {
                    int e = text.indexOf("-->", i + 4);
                    int end = e < 0 ? n : e + 3;
                    out.add(new Span(i, end, TokenKind.COMMENT, e < 0));
                    i = end;
                    continue;
                }
                if (text.startsWith("<![CDATA[", i)) {
                    int e = text.indexOf("]]>", i + 9);
                    int end = e < 0 ? n : e + 3;
                    out.add(new Span(i, end, TokenKind.STRING, e < 0));
                    i = end;
                    continue;
                }
                if (text.startsWith("<?", i) || text.startsWith("<!", i)) {
                    int e = text.indexOf('>', i + 2);
                    int end = e < 0 ? n : e + 1;
                    out.add(new Span(i, end, TokenKind.PREPROCESSOR));
                    i = end;
                    continue;
                }
                int j = i + 1;
                if (j < n && text.charAt(j) == '/') {
                    j++;
                }
                if (j < n && (Character.isLetter(text.charAt(j)) || text.charAt(j) == '_')) {
                    out.add(new Span(i, j, TokenKind.OPERATOR));
                    int nameEnd = j;
                    while (nameEnd < n && (Character.isLetterOrDigit(text.charAt(nameEnd))
                            || ":-_.".indexOf(text.charAt(nameEnd)) >= 0)) {
                        nameEnd++;
                    }
                    out.add(new Span(j, nameEnd, TokenKind.KEYWORD));
                    i = tagAttributes(text, nameEnd, out);
                    continue;
                }
                i++;
                continue;
            }
            if (c == '&') {
                int semi = text.indexOf(';', i + 1);
                if (semi > i + 1 && semi - i < 12) {
                    boolean ok = true;
                    for (int k = i + 1; k < semi; k++) {
                        char ch = text.charAt(k);
                        if (!Character.isLetterOrDigit(ch) && ch != '#') {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        out.add(new Span(i, semi + 1, TokenKind.CONSTANT));
                        i = semi + 1;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    private static int tagAttributes(String text, int from, List<Span> out) {
        final int n = text.length();
        int k = from;
        while (k < n) {
            char ch = text.charAt(k);
            if (ch == '>') {
                out.add(new Span(k, k + 1, TokenKind.OPERATOR));
                return k + 1;
            }
            if (ch == '/' && k + 1 < n && text.charAt(k + 1) == '>') {
                out.add(new Span(k, k + 2, TokenKind.OPERATOR));
                return k + 2;
            }
            if (ch == '"' || ch == '\'') {
                int close = text.indexOf(ch, k + 1);
                int end = close < 0 ? n : close + 1;
                out.add(new Span(k, end, TokenKind.STRING, close < 0));
                k = end;
                continue;
            }
            if (ch == '=') {
                out.add(new Span(k, k + 1, TokenKind.OPERATOR));
                k++;
                continue;
            }
            if (Character.isLetter(ch) || ch == '_' || ch == ':' || ch == '@') {
                int j = k + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || ":-_.".indexOf(text.charAt(j)) >= 0)) {
                    j++;
                }
                out.add(new Span(k, j, TokenKind.VARIABLE));
                k = j;
                continue;
            }
            if (ch == '<') {
                return k; // a new tag: this one was never closed
            }
            k++;
        }
        return n;
    }
}
