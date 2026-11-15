package com.sphere.components.editor;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Python as its own tokenizer reads it, and the lines IPython adds to it.
 *
 * Measured against Python 3.14's tokenize on the 739 code cells of 55 real
 * notebooks, the generic tokenizer got f-strings wrong (PEP 701 made the
 * expressions inside them code, nested quotes included) and knew nothing of
 * magics or shell escapes; the notebook's former highlighter colored the modulo
 * operator as a magic and left stale colors behind after a docstring was
 * closed. This lexer follows the language's own grammar for literals:
 *
 *   - f-strings and t-strings (PEP 701, PEP 750): the literal parts are strings,
 *     the replacement fields code, with conversions, debug '=' and format specs,
 *     nested fields and nested strings of any quote;
 *   - every string prefix, escapes, named escapes, triple quotes, line continuation;
 *   - numbers with underscores, hex, octal, binary, exponents, imaginary parts;
 *   - soft keywords (match, case, type) only where they are keywords;
 *   - IPython: %magic and %%cell magic lines, !shell escapes, x = !cmd and
 *     x = %magic assignments, ? and ?? help.
 *
 * Names get a role from what can be told without running anything (definitions,
 * imports, calls, builtins, CamelCase, ALL_CAPS), or from a live kernel through
 * {@code names}, which knows that np is a module and that model is an object.
 *
 * Brackets are never covered by a span: the highlighter pairs and colors them.
 */
public final class PythonLexer {

    static final Set<String> KEYWORDS = Set.of(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
        "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
        "with", "yield");

    static final Set<String> CONSTANTS = Set.of(
        "True", "False", "None", "Ellipsis", "NotImplemented", "__debug__", "__name__", "__file__",
        "__doc__", "__all__", "__spec__", "__loader__", "__package__", "__builtins__", "__annotations__");

    static final Set<String> BUILTIN_FUNCTIONS = Set.of(
        "__build_class__", "__import__", "abs", "aiter", "all", "anext", "any", "ascii", "bin",
        "breakpoint", "callable", "chr", "compile", "copyright", "credits", "delattr", "dir", "divmod",
        "eval", "exec", "exit", "format", "getattr", "globals", "hasattr", "hash", "help", "hex", "id",
        "input", "isinstance", "issubclass", "iter", "len", "license", "locals", "max", "min", "next",
        "oct", "open", "ord", "pow", "print", "quit", "repr", "round", "setattr", "sorted", "sum", "vars",
        "display", "get_ipython");

    static final Set<String> BUILTIN_TYPES = Set.of(
        "ArithmeticError", "AssertionError", "AttributeError", "BaseException", "BaseExceptionGroup",
        "BlockingIOError", "BrokenPipeError", "BufferError", "BytesWarning", "ChildProcessError",
        "ConnectionAbortedError", "ConnectionError", "ConnectionRefusedError", "ConnectionResetError",
        "DeprecationWarning", "EOFError", "EncodingWarning", "EnvironmentError", "Exception",
        "ExceptionGroup", "FileExistsError", "FileNotFoundError", "FloatingPointError", "FutureWarning",
        "GeneratorExit", "IOError", "ImportError", "ImportWarning", "IndentationError", "IndexError",
        "InterruptedError", "IsADirectoryError", "KeyError", "KeyboardInterrupt", "LookupError",
        "MemoryError", "ModuleNotFoundError", "NameError", "NotADirectoryError", "NotImplementedError",
        "OSError", "OverflowError", "PendingDeprecationWarning", "PermissionError", "ProcessLookupError",
        "PythonFinalizationError", "RecursionError", "ReferenceError", "ResourceWarning", "RuntimeError",
        "RuntimeWarning", "StopAsyncIteration", "StopIteration", "SyntaxError", "SyntaxWarning",
        "SystemError", "SystemExit", "TabError", "TimeoutError", "TypeError", "UnboundLocalError",
        "UnicodeDecodeError", "UnicodeEncodeError", "UnicodeError", "UnicodeTranslateError",
        "UnicodeWarning", "UserWarning", "ValueError", "Warning", "WindowsError", "ZeroDivisionError",
        "bool", "bytearray", "bytes", "classmethod", "complex", "dict", "enumerate", "filter", "float",
        "frozenset", "int", "list", "map", "memoryview", "object", "property", "range", "reversed", "set",
        "slice", "staticmethod", "str", "super", "tuple", "type", "zip");

    /** Cell magics whose body is not Python. */
    private static final Set<String> FOREIGN_CELL_MAGICS = Set.of(
        "bash", "sh", "cmd", "powershell", "pwsh", "script", "perl", "ruby", "html", "javascript", "js",
        "latex", "markdown", "svg", "sx", "system", "md", "cpp", "c++");

    /** The language a foreign cell magic's body is colored in (%%cpp is C++, %%bash a shell...). */
    private static final java.util.Map<String, LanguageSpec> CELL_MAGIC_LANGUAGES = java.util.Map.ofEntries(
        java.util.Map.entry("cpp", LanguageSpec.CPP), java.util.Map.entry("c++", LanguageSpec.CPP),
        java.util.Map.entry("bash", LanguageSpec.SHELL), java.util.Map.entry("sh", LanguageSpec.SHELL),
        java.util.Map.entry("powershell", LanguageSpec.POWERSHELL), java.util.Map.entry("pwsh", LanguageSpec.POWERSHELL),
        java.util.Map.entry("cmd", LanguageSpec.BATCH), java.util.Map.entry("javascript", LanguageSpec.JAVASCRIPT),
        java.util.Map.entry("js", LanguageSpec.JAVASCRIPT), java.util.Map.entry("markdown", LanguageSpec.MARKDOWN),
        java.util.Map.entry("md", LanguageSpec.MARKDOWN), java.util.Map.entry("latex", LanguageSpec.LATEX),
        java.util.Map.entry("html", LanguageSpec.MARKUP), java.util.Map.entry("svg", LanguageSpec.MARKUP));

    private static final Set<String> STRING_PREFIXES = Set.of(
        "r", "u", "b", "f", "t", "br", "rb", "fr", "rf", "tr", "rt");

    /** Where the expression of an f-string replacement field stops. */
    private record Field(char quote, boolean triple) {
    }

    private final String text;
    private final int n;
    private final List<SyntaxTokenizer.Span> out;
    private final Function<String, TokenKind> names;

    private int depth;
    /** Indentation of the line whose bracket opened the current depth; see PySemantics.statementFollows. */
    private int openerIndent;
    private boolean lineStart = true;
    private boolean continued;
    private char lastChar;
    private String lastWord = "";
    /** 0 none, 1 after import (names are modules), 2 after from (module path), 3 names imported from it. */
    private int importMode;

    private PythonLexer(String text, List<SyntaxTokenizer.Span> out, Function<String, TokenKind> names) {
        this.text = text;
        this.n = text.length();
        this.out = out;
        this.names = names;
    }

    /** Appends the spans of {@code text}, sorted and never overlapping. */
    public static void lex(String text, List<SyntaxTokenizer.Span> out, Function<String, TokenKind> names) {
        lex(text, out, names, null, false);
    }

    /**
     * Appends the spans of {@code text}, then, when {@code semantic} is set, resolves
     * every name scope by scope (PySemantics) and gives its span a role: parameter,
     * local, global, attribute, module... with how it is used (defined here,
     * reassigned, never read, shadowing a builtin, stale in the kernel). Returns
     * that analysis, or null when it did not run.
     */
    public static PySemantics.Result lex(String text, List<SyntaxTokenizer.Span> out, Function<String, TokenKind> names,
                                         PySemantics.Context context, boolean semantic) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int base = out.size();
        boolean python = new PythonLexer(text, out, names).run();
        if (!semantic || !python || text.length() > PySemantics.MAX_CHARS) {
            return null;
        }
        PySemantics.Result result = PySemantics.analyze(text, out, base, names, context);
        if (result != null) {
            result.applyTo(out, base);
        }
        return result;
    }

    /** Lexes the text; false when a cell magic whose body is not Python owns it. */
    private boolean run() {
        int i = 0;
        // A cell magic owns the whole cell: its first line is the directive, and
        // the body is Python only for the magics that run Python (%%time, %%capture).
        int first = skipBlankLines(0);
        if (text.startsWith("%%", first)) {
            int eol = eol(first);
            emit(first, eol, TokenKind.PREPROCESSOR);
            int nameEnd = first + 2;
            while (nameEnd < eol && isIdentifierPart(text.charAt(nameEnd))) {
                nameEnd++;
            }
            String magic = text.substring(first + 2, nameEnd);
            if (FOREIGN_CELL_MAGICS.contains(magic)
                || magic.equals("writefile") && !text.substring(nameEnd, eol).strip().endsWith(".py")) {
                foreignBody(magic, eol);
                return false;
            }
            i = eol;
        }
        code(i, null);
        return true;
    }

    /** The body of a %%cpp, %%bash... cell, colored as its own language; left plain when Sphere knows none. */
    private void foreignBody(String magic, int eol) {
        LanguageSpec spec = CELL_MAGIC_LANGUAGES.get(magic);
        if (spec == null && magic.equals("writefile")) {
            // %%writefile analysis.cpp: the body is written in the language of the file it goes to
            String[] words = text.substring(0, eol).strip().split("\\s+");
            String file = words[words.length - 1].replace("\"", "").replace("'", "");
            try {
                spec = LanguageSpec.forFile(new java.io.File(file));
            } catch (RuntimeException e) {
                spec = null;
            }
        }
        int bodyStart = Math.min(n, eol + 1);
        if (spec == null || spec == LanguageSpec.PYTHON || bodyStart >= n) {
            return;
        }
        List<SyntaxTokenizer.Span> body = new java.util.ArrayList<>();
        new SyntaxTokenizer(spec).tokenize(text.substring(bodyStart), false, body);
        for (SyntaxTokenizer.Span s : body) {
            out.add(new SyntaxTokenizer.Span(s.start + bodyStart, s.end + bodyStart, s.kind, s.unterminated));
        }
    }

    // ---- Code --------------------------------------------------------------

    /**
     * Lexes code from i. At top level ({@code field} null) it runs to the end;
     * inside an f-string replacement field it returns at the character that
     * ends the expression: '}', the ':' of a format spec, the '!' of a
     * conversion, a debug '=', or a line end in a single-quoted f-string.
     */
    private int code(int i, Field field) {
        int local = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (field != null && local == 0) {
                if (c == '}' || c == ':' && !(i + 1 < n && text.charAt(i + 1) == '=')) {
                    return i;
                }
                if (c == '!' && i + 1 < n && "rsa".indexOf(text.charAt(i + 1)) >= 0
                        && (i + 2 >= n || text.charAt(i + 2) == '}' || text.charAt(i + 2) == ':')) {
                    return i;
                }
                if (c == '=' && isDebugEquals(i)) {
                    return i;
                }
            }
            if (c == '\n') {
                if (field != null && !field.triple()) {
                    return i;
                }
                if (field == null && depth > 0 && !continued && PySemantics.statementFollows(text, i + 1, openerIndent)) {
                    depth = 0; // a bracket left open while typing must not swallow the code below
                }
                if (field == null && depth == 0 && !continued) {
                    lineStart = true;
                    importMode = 0;
                    lastChar = '\n';
                    lastWord = "";
                }
                continued = false;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            if (c == '\\' && (i + 1 >= n || text.charAt(i + 1) == '\n' || text.charAt(i + 1) == '\r')) {
                emit(i, i + 1, TokenKind.OPERATOR);
                continued = true;
                i++;
                continue;
            }
            continued = false;
            if (c == '#' && (field == null || field.triple())) {
                int eol = eol(i);
                emit(i, eol, TokenKind.COMMENT);
                i = eol;
                continue;
            }
            boolean atStatement = lineStart && field == null && depth == 0;
            lineStart = false;
            if (atStatement && (c == '%' || c == '!' || c == '?')) {
                // %magic, !shell, ?help: IPython's, never Python's, at the head of a line
                int eol = eol(i);
                emit(i, eol, TokenKind.PREPROCESSOR);
                i = eol;
                continue;
            }
            if (atStatement && c == '@') {
                int j = i + 1;
                while (j < n && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                    j++;
                }
                int k = j;
                while (k < n && (isIdentifierPart(text.charAt(k)) || text.charAt(k) == '.')) {
                    k++;
                }
                if (k > j) {
                    emit(i, k, TokenKind.DECORATOR);
                    lastChar = 'a';
                    lastWord = "";
                    i = k;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                i = string(i, i);
                lastChar = '"';
                continue;
            }
            if (isDigit(c) || c == '.' && i + 1 < n && isDigit(text.charAt(i + 1)) && !afterOperand(i)) {
                i = number(i);
                lastChar = '0';
                continue;
            }
            if (isIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && isIdentifierPart(text.charAt(j))) {
                    j++;
                }
                if (j < n && (text.charAt(j) == '"' || text.charAt(j) == '\'')
                        && STRING_PREFIXES.contains(text.substring(i, j).toLowerCase(Locale.ROOT))) {
                    i = string(i, j);
                    lastChar = '"';
                    continue;
                }
                word(i, j, atStatement);
                i = j;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                if (field == null) {
                    if (depth == 0) {
                        openerIndent = PySemantics.indentAt(text, i);
                    }
                    depth++;
                } else {
                    local++;
                }
                lastChar = c;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                if (field == null) {
                    depth = Math.max(0, depth - 1);
                } else {
                    local = Math.max(0, local - 1);
                }
                lastChar = c;
                i++;
                continue;
            }
            if (c == '?') {
                emit(i, i + 1, TokenKind.PREPROCESSOR); // obj? and obj?? ask IPython for help
                i++;
                continue;
            }
            if (isOperator(c)) {
                int j = i + 1;
                while (j < n && isOperator(text.charAt(j)) && !(field != null && local == 0 && stopsField(j))
                       && !(text.charAt(j) == '.' && j + 1 < n && isDigit(text.charAt(j + 1)))) {
                    j++; // a run stops before ".5": -.05 and =.3 are a sign and a float
                }
                if (field == null && depth == 0 && j == i + 1 && c == '=') {
                    // files = !ls, t = %timeit -o f(): the rest of the line is IPython's
                    int k = j;
                    while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
                        k++;
                    }
                    if (k < n && (text.charAt(k) == '!' || text.charAt(k) == '%')) {
                        emit(i, j, TokenKind.OPERATOR);
                        int eol = eol(k);
                        emit(k, eol, TokenKind.PREPROCESSOR);
                        i = eol;
                        continue;
                    }
                }
                emit(i, j, TokenKind.OPERATOR);
                lastChar = text.charAt(j - 1);
                lastWord = "";
                i = j;
                continue;
            }
            i++;
        }
        return i;
    }

    /** Inside a field, an operator run must not swallow the '!' or '=' that ends it. */
    private boolean stopsField(int j) {
        char c = text.charAt(j);
        if (c == '!' && j + 1 < n && "rsa".indexOf(text.charAt(j + 1)) >= 0
                && (j + 2 >= n || text.charAt(j + 2) == '}' || text.charAt(j + 2) == ':')) {
            return true;
        }
        return c == '=' && isDebugEquals(j) || c == ':' && !(j + 1 < n && text.charAt(j + 1) == '=');
    }

    /** The '=' of f"{x=}": not part of ==, !=, <=, >=, :=, followed by '}', '!' or ':'. */
    private boolean isDebugEquals(int i) {
        char before = i > 0 ? text.charAt(i - 1) : ' ';
        if ("=!<>:".indexOf(before) >= 0 || i + 1 < n && text.charAt(i + 1) == '=') {
            return false;
        }
        int k = i + 1;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        return k < n && (text.charAt(k) == '}' || text.charAt(k) == '!' || text.charAt(k) == ':');
    }

    private void word(int i, int j, boolean atStatement) {
        String w = text.substring(i, j);
        boolean attribute = lastChar == '.';
        TokenKind kind = null;
        if (!attribute && KEYWORDS.contains(w)) {
            kind = TokenKind.KEYWORD;
            if (w.equals("import")) {
                importMode = importMode == 2 ? 3 : 1;
            } else if (w.equals("from") && atStatement) {
                importMode = 2;
            }
        } else if (!attribute && (w.equals("True") || w.equals("False") || w.equals("None"))) {
            kind = TokenKind.CONSTANT;
        } else if (!attribute && atStatement && isSoftKeyword(w, j)) {
            kind = TokenKind.KEYWORD;
        } else if (importMode == 1 || importMode == 2) {
            kind = TokenKind.NAMESPACE; // import numpy.linalg as la, from os import ...
        } else if (lastWord.equals("def")) {
            kind = TokenKind.FUNCTION;
        } else if (lastWord.equals("class")) {
            kind = TokenKind.TYPE;
        } else if (attribute) {
            kind = isCall(j) ? TokenKind.FUNCTION : null;
        } else {
            TokenKind live = names == null ? null : names.apply(w);
            if (live != null) {
                kind = live == TokenKind.DEFAULT ? null : live;
            } else if (CONSTANTS.contains(w)) {
                kind = TokenKind.CONSTANT;
            } else if (BUILTIN_TYPES.contains(w)) {
                kind = TokenKind.TYPE;
            } else if (BUILTIN_FUNCTIONS.contains(w)) {
                kind = TokenKind.FUNCTION;
            } else if (w.equals("self") || w.equals("cls")) {
                kind = TokenKind.VARIABLE;
            } else if (isAllCaps(w)) {
                kind = TokenKind.CONSTANT;
            } else if (isCamelCase(w)) {
                kind = TokenKind.TYPE;
            } else if (isCall(j)) {
                kind = TokenKind.FUNCTION;
            }
        }
        if (kind != null) {
            emit(i, j, kind);
        }
        lastWord = w;
        lastChar = 'a';
    }

    /**
     * match and case open a statement only when their line ends in ':' and
     * what follows them is not an operator; type when "type Name = ..." follows.
     */
    private boolean isSoftKeyword(String w, int j) {
        if (!w.equals("match") && !w.equals("case") && !w.equals("type")) {
            return false;
        }
        int k = j;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        if (k >= n || k == j && text.charAt(k) != '(' && text.charAt(k) != '[') {
            return false;
        }
        char next = text.charAt(k);
        if ("=.,):;\n".indexOf(next) >= 0) {
            return false; // match = 3, match.group(), case: ... are names
        }
        if (w.equals("type")) {
            int e = k;
            while (e < n && isIdentifierPart(text.charAt(e))) {
                e++;
            }
            if (e == k) {
                return false;
            }
            String rest = text.substring(e, eol(e)).strip();
            return rest.startsWith("=") && !rest.startsWith("==") || rest.startsWith("[");
        }
        String line = text.substring(k, eol(k));
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash);
        }
        return line.strip().endsWith(":");
    }

    // ---- Literals ----------------------------------------------------------

    /** A string whose prefix starts at start and whose quote sits at quoteAt; returns its end. */
    private int string(int start, int quoteAt) {
        String prefix = text.substring(start, quoteAt).toLowerCase(Locale.ROOT);
        boolean raw = prefix.indexOf('r') >= 0;
        boolean template = prefix.indexOf('f') >= 0 || prefix.indexOf('t') >= 0;
        char q = text.charAt(quoteAt);
        boolean triple = quoteAt + 2 < n && text.charAt(quoteAt + 1) == q && text.charAt(quoteAt + 2) == q;
        if (template) {
            return fstring(start, quoteAt, q, triple, raw);
        }
        int i = quoteAt + (triple ? 3 : 1);
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2; // even in a raw string a backslash keeps the next quote from closing it
                continue;
            }
            if (c == q && (!triple || i + 2 < n && text.charAt(i + 1) == q && text.charAt(i + 2) == q)) {
                int end = i + (triple ? 3 : 1);
                emit(start, end, TokenKind.STRING);
                return end;
            }
            if (c == '\n' && !triple) {
                emitUnterminated(start, i);
                return i;
            }
            i++;
        }
        emitUnterminated(start, n);
        return n;
    }

    private int fstring(int start, int quoteAt, char q, boolean triple, boolean raw) {
        int i = quoteAt + (triple ? 3 : 1);
        int literal = start;
        Field field = new Field(q, triple);
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (!raw && i + 2 < n && text.charAt(i + 1) == 'N' && text.charAt(i + 2) == '{') {
                    int close = text.indexOf('}', i + 3);
                    i = close < 0 ? n : close + 1; // \N{GREEK SMALL LETTER MU}: a name, not a field
                    continue;
                }
                if (i + 1 < n && (text.charAt(i + 1) == q || text.charAt(i + 1) == '\\' || text.charAt(i + 1) == '\n')) {
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '\n' && !triple) {
                emitUnterminated(literal, i);
                return i;
            }
            if (c == q && (!triple || i + 2 < n && text.charAt(i + 1) == q && text.charAt(i + 2) == q)) {
                int end = i + (triple ? 3 : 1);
                emit(literal, end, TokenKind.STRING);
                return end;
            }
            if (c == '{' || c == '}') {
                if (i + 1 < n && text.charAt(i + 1) == c) {
                    i += 2; // {{ and }} are the braces themselves
                    continue;
                }
                if (c == '}') {
                    i++;
                    continue;
                }
                emit(literal, i, TokenKind.STRING);
                i = replacementField(i, field);
                literal = i;
                continue;
            }
            i++;
        }
        emitUnterminated(literal, n);
        return n;
    }

    /** From the '{' of a replacement field to just past its '}'. */
    private int replacementField(int open, Field field) {
        int j = code(open + 1, field);
        if (j >= n) {
            return n;
        }
        if (text.charAt(j) == '=') {
            emit(j, j + 1, TokenKind.OPERATOR);
            j = skipSpaces(j + 1);
        }
        if (j < n && text.charAt(j) == '!') {
            emit(j, j + 1, TokenKind.OPERATOR);
            j = Math.min(n, j + 2);
        }
        if (j < n && text.charAt(j) == ':') {
            emit(j, j + 1, TokenKind.OPERATOR);
            j = formatSpec(j + 1, field);
        }
        if (j < n && text.charAt(j) == '}') {
            return j + 1;
        }
        return j;
    }

    /** A format spec is literal text, with fields of its own: f"{x:{width}.{precision}f}". */
    private int formatSpec(int i, Field field) {
        int literal = i;
        char q = field.quote();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '{') {
                emit(literal, i, TokenKind.STRING);
                i = replacementField(i, field);
                literal = i;
                continue;
            }
            if (c == '}') {
                emit(literal, i, TokenKind.STRING);
                return i;
            }
            if (c == '\n' && !field.triple()
                || c == q && (!field.triple() || i + 2 < n && text.charAt(i + 1) == q && text.charAt(i + 2) == q)) {
                emit(literal, i, TokenKind.STRING);
                return i;
            }
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            i++;
        }
        emit(literal, n, TokenKind.STRING);
        return n;
    }

    private int number(int i) {
        int j = i;
        if (text.charAt(i) == '0' && i + 1 < n && "xXoObB".indexOf(text.charAt(i + 1)) >= 0) {
            j = i + 2;
            while (j < n && (Character.digit(text.charAt(j), 16) >= 0 || text.charAt(j) == '_')) {
                j++;
            }
        } else {
            while (j < n && (isDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                j++;
            }
            if (j < n && text.charAt(j) == '.' && !(j + 1 < n && text.charAt(j + 1) == '.')) {
                j++;
                while (j < n && (isDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
            }
            if (j < n && (text.charAt(j) == 'e' || text.charAt(j) == 'E')) {
                int k = j + 1;
                if (k < n && (text.charAt(k) == '+' || text.charAt(k) == '-')) {
                    k++;
                }
                if (k < n && isDigit(text.charAt(k))) {
                    j = k;
                    while (j < n && (isDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                        j++;
                    }
                }
            }
            if (j < n && (text.charAt(j) == 'j' || text.charAt(j) == 'J')) {
                j++;
            }
        }
        emit(i, j, TokenKind.NUMBER);
        return j;
    }

    // ---- Helpers -----------------------------------------------------------

    private void emit(int start, int end, TokenKind kind) {
        if (end > start) {
            out.add(new SyntaxTokenizer.Span(start, end, kind));
        }
    }

    private void emitUnterminated(int start, int end) {
        if (end > start) {
            out.add(new SyntaxTokenizer.Span(start, end, TokenKind.STRING, true));
        }
    }

    /** A '.' after a name, a number or a closer is an attribute dot, not a float. */
    private boolean afterOperand(int i) {
        return lastChar == 'a' || lastChar == '0' || lastChar == ')' || lastChar == ']' || lastChar == '}'
               || lastChar == '"';
    }

    private boolean isCall(int j) {
        int k = j;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        return k < n && text.charAt(k) == '(';
    }

    private int eol(int from) {
        int e = text.indexOf('\n', from);
        return e < 0 ? n : e;
    }

    private int skipSpaces(int i) {
        while (i < n && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private int skipBlankLines(int i) {
        int k = i;
        while (k < n) {
            int e = eol(k);
            if (!text.substring(k, e).isBlank()) {
                return k + (text.substring(k, e).length() - text.substring(k, e).stripLeading().length());
            }
            k = e + 1;
        }
        return n;
    }

    private static boolean isOperator(char c) {
        return "+-*/%@&|^~<>=!:.,;".indexOf(c) >= 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isIdentifierStart(char c) {
        return c == '_' || Character.isUnicodeIdentifierStart(c);
    }

    static boolean isIdentifierPart(char c) {
        return c == '_' || Character.isUnicodeIdentifierPart(c) && !Character.isIdentifierIgnorable(c);
    }

    static boolean isAllCaps(String w) {
        int letters = 0;
        for (int k = 0; k < w.length(); k++) {
            char ch = w.charAt(k);
            if (Character.isLowerCase(ch)) {
                return false;
            }
            if (Character.isLetter(ch)) {
                letters++;
            }
        }
        return letters >= 2;
    }

    static boolean isCamelCase(String w) {
        if (w.isEmpty() || !Character.isUpperCase(w.charAt(0))) {
            return false;
        }
        for (int k = 1; k < w.length(); k++) {
            if (Character.isLowerCase(w.charAt(k))) {
                return true;
            }
        }
        return false;
    }
}
