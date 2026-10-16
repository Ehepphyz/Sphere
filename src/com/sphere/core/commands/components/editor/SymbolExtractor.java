package com.sphere.components.editor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the outline of a file: its classes, functions, targets or headings.
 * Works on a masked copy of the text where strings and comments are blanked,
 * so a "def" in a docstring or a "class" in a comment is never taken for one.
 */
final class SymbolExtractor {

    private SymbolExtractor() {
    }

    // ---- C-like ------------------------------------------------------------

    static final Pattern CLASS_HEADER = Pattern.compile(
            "\\b(class|struct|union|interface|record|enum(?:\\s+class|\\s+struct)?)\\s+"
            + "((?:[\\w:]+\\s+)*?)([A-Za-z_]\\w*)\\s*(?:<[^{};]*>)?\\s*(?:final\\s*)?"
            + "(?:(?::(?!:)|\\bextends\\b|\\bimplements\\b)[^{};]*)?$");
    private static final Pattern NAMESPACE_HEADER = Pattern.compile("\\bnamespace\\s*([\\w:]+)?\\s*$");
    private static final Pattern QUALIFIERS = Pattern.compile(
            "^(?:\\s|const\\b|volatile\\b|noexcept(?:\\s*\\([^)]*\\))?|override\\b|final\\b|mutable\\b"
            + "|&&?|->\\s*[\\w:<>,\\s*&\\[\\]]+|throws\\s+[\\w.,\\s]+|try\\b|requires\\b[^{;]*)*$");
    private static final Pattern LAMBDA_VAR = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*=\\s*\\[[^\\]]*\\]\\s*\\([^)]*\\)[^{;]*$");
    private static final Pattern ARROW_VAR = Pattern.compile(
            "\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>\\s*$");
    private static final Set<String> NOT_FUNCTIONS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "sizeof", "do", "else", "try",
            "case", "new", "delete", "throw", "__attribute__", "alignas", "alignof", "decltype",
            "static_assert", "synchronized", "function", "foreach", "using", "typeof", "await",
            "defined", "noexcept", "requires", "operator");

    static List<DocSymbol> braces(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        String m = new String(masked);
        String text = s.text;
        final boolean cpp = s.spec == LanguageSpec.CPP;

        // Containers open at a given point: the symbol and where its brace closes.
        Deque<Object[]> open = new ArrayDeque<>();

        for (int b = 0; b < s.bracketCount; b++) {
            if (s.bracketChar[b] != '{') {
                continue;
            }
            int brace = s.bracketPos[b];
            int partner = s.bracketPartner[b];
            int close = partner >= 0 ? s.bracketPos[partner] : text.length() - 1;
            while (!open.isEmpty() && (int) open.peek()[1] < brace) {
                open.pop();
            }
            int hs = headerStart(m, brace);
            String header = m.substring(hs, brace);
            if (header.isBlank()) {
                continue;
            }
            DocSymbol parent = open.isEmpty() ? null : (DocSymbol) open.peek()[0];
            int depth = open.size();
            DocSymbol sym = classify(header, hs, text, lineStarts, close, parent, depth, cpp, s);
            if (sym != null) {
                out.add(sym);
                open.push(new Object[] { sym, close });
            }
        }

        if (cpp) {
            macros(text, lineStarts, out);
        }
        out.sort((a, c) -> Integer.compare(a.offset, c.offset));
        return out;
    }

    /** Where the statement before a brace begins: after ; { } or a directive line. */
    private static int headerStart(String m, int brace) {
        int k = brace - 1;
        int paren = 0;
        int limit = Math.max(0, brace - 800);
        while (k >= limit) {
            char c = m.charAt(k);
            if (c == ')') {
                paren++;
            } else if (c == '(') {
                if (paren == 0) {
                    break;
                }
                paren--;
            } else if (paren == 0 && (c == ';' || c == '{' || c == '}')) {
                break;
            }
            k--;
        }
        return k + 1;
    }

    private static DocSymbol classify(String header, int hs, String text, int[] lineStarts, int close,
                                      DocSymbol parent, int depth, boolean cpp,
                                      SyntaxHighlighter.Snapshot s) {
        String trimmedTail = header.stripTrailing();
        String container = parent == null ? null : parent.name;
        int endLine = lineOf(lineStarts, close);

        Matcher ns = NAMESPACE_HEADER.matcher(trimmedTail);
        if (trimmedTail.contains("namespace") && ns.find()) {
            String name = ns.group(1) == null ? "(anonymous)" : ns.group(1);
            int off = hs + (ns.group(1) == null ? ns.start() : ns.start(1));
            return new DocSymbol(name, DocSymbol.Kind.NAMESPACE, "", lineOf(lineStarts, off), endLine,
                                 off, depth, container, null);
        }

        Matcher cls = CLASS_HEADER.matcher(trimmedTail);
        if (hasTypeKeyword(trimmedTail) && cls.find()) {
            String keyword = cls.group(1);
            String name = cls.group(3);
            int off = hs + cls.start(3);
            DocSymbol.Kind kind = keyword.startsWith("enum") ? DocSymbol.Kind.ENUM
                    : keyword.equals("struct") || keyword.equals("union") || keyword.equals("record")
                      ? DocSymbol.Kind.STRUCT
                    : keyword.equals("interface") ? DocSymbol.Kind.INTERFACE : DocSymbol.Kind.CLASS;
            String detail = keyword;
            int colon = trimmedTail.indexOf(':', cls.end(3));
            if (colon > 0 && colon + 1 < trimmedTail.length() && trimmedTail.charAt(colon + 1) != ':') {
                detail = keyword + " :" + collapse(trimmedTail.substring(colon + 1), 60);
            }
            int line = lineOf(lineStarts, off);
            return new DocSymbol(name, kind, detail, line, endLine, off, depth, container,
                                 docAbove(text, lineStarts, lineOf(lineStarts, hs + firstNonSpace(header))));
        }

        // A function: a call-shaped name, then only qualifiers until the brace.
        // The first candidates can be the tail of a directive or a macro above.
        for (int p = firstTopLevelParen(header, 0); p >= 0; p = firstTopLevelParen(header, p + 1)) {
            int nameEnd = p;
            while (nameEnd > 0 && Character.isWhitespace(header.charAt(nameEnd - 1))) {
                nameEnd--;
            }
            int nameStart = nameEnd;
            while (nameStart > 0 && isQualifiedNameChar(header.charAt(nameStart - 1))) {
                nameStart--;
            }
            String qualified = header.substring(nameStart, nameEnd);
            if (qualified.startsWith("::")) {
                qualified = qualified.substring(2);
                nameStart += 2;
            }
            int closeParen = matchParen(header, p);
            if (closeParen < 0) {
                return null;
            }
            String tail = header.substring(closeParen + 1);
            boolean initList = cpp && tail.trim().startsWith(":") && !tail.trim().startsWith("::");
            String last = qualified.contains("::") ? qualified.substring(qualified.lastIndexOf("::") + 2) : qualified;
            String before = wordBefore(header, nameStart);
            boolean validName = !last.isEmpty()
                    && (Character.isJavaIdentifierStart(last.charAt(0)) || last.charAt(0) == '~')
                    && !NOT_FUNCTIONS.contains(last)
                    && !before.equals("new") && !before.equals("return") && !before.equals("throw")
                    && !before.equals("else") && !before.equals("=") && !before.equals(".");
            if (validName && (initList || QUALIFIERS.matcher(tail).matches())) {
                int off = hs + nameStart + (qualified.length() - last.length());
                String owner = qualified.contains("::") ? qualified.substring(0, qualified.lastIndexOf("::")) : container;
                boolean inType = parent != null && parent.kind.isType();
                DocSymbol.Kind kind;
                String ownerLast = owner == null ? null
                        : owner.contains("::") ? owner.substring(owner.lastIndexOf("::") + 2) : owner;
                if (ownerLast != null && (last.equals(ownerLast) || last.equals("~" + ownerLast))) {
                    kind = last.startsWith("~") ? DocSymbol.Kind.METHOD : DocSymbol.Kind.CONSTRUCTOR;
                } else if (inType || qualified.contains("::")) {
                    kind = DocSymbol.Kind.METHOD;
                } else {
                    kind = DocSymbol.Kind.FUNCTION;
                }
                String params = paramsOf(text, hs + p, hs + closeParen);
                String detail = params + qualifierSuffix(tail);
                int line = lineOf(lineStarts, off);
                return new DocSymbol(last, kind, detail, line, endLine, off, depth, owner,
                                     docAbove(text, lineStarts, lineOf(lineStarts, off)));
            }
            if (initList || !validName && QUALIFIERS.matcher(tail).matches()) {
                break; // the last paren of the header was not a function's
            }
        }

        Matcher lambda = LAMBDA_VAR.matcher(trimmedTail);
        if (trimmedTail.indexOf('[') >= 0 && lambda.find()) {
            int off = hs + lambda.start(1);
            return new DocSymbol(lambda.group(1), DocSymbol.Kind.FUNCTION, "lambda", lineOf(lineStarts, off),
                                 endLine, off, depth, container, null);
        }
        Matcher arrow = ARROW_VAR.matcher(trimmedTail);
        if (trimmedTail.endsWith("=>") && arrow.find()) {
            int off = hs + arrow.start(1);
            return new DocSymbol(arrow.group(1), DocSymbol.Kind.FUNCTION, "=>", lineOf(lineStarts, off),
                                 endLine, off, depth, container, null);
        }
        return null;
    }

    /** Cheap test before the class pattern, which is costly on long headers. */
    static boolean hasTypeKeyword(String header) {
        return header.contains("class") || header.contains("struct") || header.contains("union")
            || header.contains("enum") || header.contains("interface") || header.contains("record");
    }

    private static boolean isQualifiedNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '~' || c == '$';
    }

    /** The next '(' at nesting zero from {@code from}, skipping template arguments. */
    private static int firstTopLevelParen(String header, int from) {
        int depthAngle = 0;
        int depthParen = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '<' && depthParen == 0) {
                depthAngle++;
            } else if (c == '>' && depthAngle > 0 && depthParen == 0
                       && (i == 0 || header.charAt(i - 1) != '-')) {
                depthAngle--;
            } else if (c == '(') {
                if (depthAngle == 0 && depthParen == 0 && i >= from) {
                    return i;
                }
                depthParen++;
            } else if (c == ')') {
                depthParen = Math.max(0, depthParen - 1);
            }
        }
        return -1;
    }

    /** The word or symbol just before a position, skipping spaces. */
    private static String wordBefore(String s, int pos) {
        int e = pos;
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        if (e == 0) {
            return "";
        }
        char c = s.charAt(e - 1);
        if (!Character.isLetterOrDigit(c) && c != '_') {
            return String.valueOf(c);
        }
        int b = e;
        while (b > 0 && (Character.isLetterOrDigit(s.charAt(b - 1)) || s.charAt(b - 1) == '_')) {
            b--;
        }
        return s.substring(b, e);
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String qualifierSuffix(String tail) {
        String t = tail.trim();
        if (t.startsWith("const")) {
            return " const";
        }
        int arrow = t.indexOf("->");
        if (arrow >= 0) {
            return " → " + collapse(t.substring(arrow + 2), 30);
        }
        return "";
    }

    private static void macros(String text, int[] lineStarts, List<DocSymbol> out) {
        Matcher m = Pattern.compile("(?m)^[ \\t]*#[ \\t]*define[ \\t]+([A-Za-z_]\\w*)(\\([^)]*\\))?").matcher(text);
        int found = 0;
        while (m.find() && found++ < 400) {
            int off = m.start(1);
            out.add(new DocSymbol(m.group(1), DocSymbol.Kind.MACRO, m.group(2) == null ? "" : m.group(2),
                                  lineOf(lineStarts, off), lineOf(lineStarts, off), off, 0, null, null));
        }
    }

    // ---- Python ------------------------------------------------------------

    private static final Pattern PY_DEF = Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern PY_CLASS = Pattern.compile("^(\\s*)class\\s+([A-Za-z_]\\w*)\\s*(\\(([^)]*)\\))?");
    private static final Pattern PY_VAR = Pattern.compile("^([A-Za-z_]\\w*)\\s*(?::\\s*[^=]+)?=(?!=)");

    static List<DocSymbol> python(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        String text = s.text;
        int lines = lineStarts.length;
        // Open blocks: {indent, index in out}
        Deque<int[]> stack = new ArrayDeque<>();
        Set<String> seenVars = new HashSet<>();
        int lastNonBlank = 0;
        for (int line = 0; line < lines; line++) {
            String ml = lineString(masked, lineStarts, line);
            int indent = CodeTextPane.indentColumns(ml, 4);
            if (indent < 0) {
                continue;
            }
            while (!stack.isEmpty() && stack.peek()[0] >= indent) {
                int[] top = stack.pop();
                out.set(top[1], withEnd(out.get(top[1]), lastNonBlank));
            }
            lastNonBlank = line;
            DocSymbol parent = null;
            if (!stack.isEmpty()) {
                parent = out.get(stack.peek()[1]);
            }
            Matcher def = PY_DEF.matcher(ml);
            Matcher cls = PY_CLASS.matcher(ml);
            if (def.find()) {
                String name = def.group(2);
                int off = lineStarts[line] + def.start(2);
                int paren = lineStarts[line] + def.end() - 1;
                int bi = s.bracketAt(paren);
                String params = "()";
                if (bi >= 0 && s.bracketPartner[bi] >= 0) {
                    params = paramsOf(text, paren, s.bracketPos[s.bracketPartner[bi]]);
                }
                DocSymbol.Kind kind = parent != null && parent.kind == DocSymbol.Kind.CLASS
                        ? (name.equals("__init__") ? DocSymbol.Kind.CONSTRUCTOR : DocSymbol.Kind.METHOD)
                        : DocSymbol.Kind.FUNCTION;
                String doc = pyDocstring(text, lineStarts, line);
                if (doc == null) {
                    doc = hashCommentsAbove(text, lineStarts, line);
                }
                out.add(new DocSymbol(name, kind, params, line, line, off, stack.size(),
                                      parent == null ? null : parent.name, doc));
                stack.push(new int[] { indent, out.size() - 1 });
            } else if (cls.find()) {
                String name = cls.group(2);
                int off = lineStarts[line] + cls.start(2);
                String bases = cls.group(4) == null ? "" : "(" + collapse(cls.group(4), 50) + ")";
                String doc = pyDocstring(text, lineStarts, line);
                out.add(new DocSymbol(name, DocSymbol.Kind.CLASS, bases, line, line, off, stack.size(),
                                      parent == null ? null : parent.name, doc));
                stack.push(new int[] { indent, out.size() - 1 });
            } else if (indent == 0) {
                Matcher v = PY_VAR.matcher(ml);
                if (v.find() && seenVars.add(v.group(1))) {
                    String name = v.group(1);
                    int off = lineStarts[line];
                    boolean constant = name.equals(name.toUpperCase(Locale.ROOT)) && name.length() > 1;
                    out.add(new DocSymbol(name, constant ? DocSymbol.Kind.CONSTANT : DocSymbol.Kind.VARIABLE,
                                          "", line, line, off, 0, null, null));
                }
            }
        }
        while (!stack.isEmpty()) {
            int[] top = stack.pop();
            out.set(top[1], withEnd(out.get(top[1]), lastNonBlank));
        }
        return out;
    }

    private static String pyDocstring(String text, int[] lineStarts, int defLine) {
        for (int l = defLine + 1; l < Math.min(lineStarts.length, defLine + 4); l++) {
            String t = rawLine(text, lineStarts, l).trim();
            if (t.isEmpty()) {
                continue;
            }
            String q = t.startsWith("\"\"\"") ? "\"\"\"" : t.startsWith("'''") ? "'''" : null;
            if (q == null) {
                return null;
            }
            int start = lineStarts[l] + rawLine(text, lineStarts, l).indexOf(q) + 3;
            int end = text.indexOf(q, start);
            if (end < 0) {
                return null;
            }
            return cleanDoc(text.substring(start, Math.min(end, start + 600)));
        }
        return null;
    }

    private static String hashCommentsAbove(String text, int[] lineStarts, int line) {
        StringBuilder sb = new StringBuilder();
        for (int l = line - 1; l >= Math.max(0, line - 8); l--) {
            String t = rawLine(text, lineStarts, l).trim();
            if (!t.startsWith("#") || t.startsWith("#!")) {
                break;
            }
            sb.insert(0, t.substring(1).trim() + "\n");
        }
        return sb.length() == 0 ? null : cleanDoc(sb.toString());
    }

    // ---- Julia -------------------------------------------------------------

    private static final Pattern JL_FUNCTION = Pattern.compile("\\bfunction\\s+([A-Za-z_][\\w.!]*)");
    private static final Pattern JL_MACRO = Pattern.compile("\\bmacro\\s+([A-Za-z_]\\w*)");
    private static final Pattern JL_STRUCT = Pattern.compile("\\bstruct\\s+([A-Za-z_]\\w*)");
    private static final Pattern JL_ABSTRACT = Pattern.compile("\\b(?:abstract|primitive)\\s+type\\s+([A-Za-z_]\\w*)");
    private static final Pattern JL_MODULE = Pattern.compile("\\b(?:bare)?module\\s+([A-Za-z_]\\w*)");
    private static final Pattern JL_SHORT = Pattern.compile(
            "^\\s*([A-Za-z_][\\w.!]*)\\s*\\(([^()]*)\\)\\s*(?:::\\s*[\\w{}]+\\s*)?(?:where\\s+[^=]+)?=(?![=>])");

    /** Julia's blocks, matched keyword to "end" at bracket depth zero. */
    static final class JuliaBlock {
        final String keyword;
        final int offset;
        int endOffset = -1;

        JuliaBlock(String keyword, int offset) {
            this.keyword = keyword;
            this.offset = offset;
        }
    }

    static final Set<String> JULIA_OPENERS = Set.of(
            "function", "macro", "module", "baremodule", "struct", "quote", "begin", "let",
            "if", "for", "while", "try", "do", "abstract", "primitive");

    /** Openers paired with their end; unclosed ones keep endOffset -1, stray ends are listed apart. */
    static List<JuliaBlock> juliaBlocks(char[] masked, List<Integer> strayEnds) {
        List<JuliaBlock> all = new ArrayList<>();
        Deque<JuliaBlock> stack = new ArrayDeque<>();
        int depth = 0;
        int n = masked.length;
        int i = 0;
        while (i < n) {
            char c = masked[i];
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(masked[j]) || masked[j] == '_' || masked[j] == '!')) {
                    j++;
                }
                boolean afterDot = i > 0 && (masked[i - 1] == '.' || masked[i - 1] == ':' || masked[i - 1] == '@');
                if (depth == 0 && !afterDot) {
                    String w = new String(masked, i, j - i);
                    if (w.equals("end")) {
                        if (stack.isEmpty()) {
                            strayEnds.add(i);
                        } else {
                            stack.pop().endOffset = i;
                        }
                    } else if (JULIA_OPENERS.contains(w)) {
                        boolean opens = true;
                        if (w.equals("abstract") || w.equals("primitive")) {
                            int k = j;
                            while (k < n && masked[k] == ' ') {
                                k++;
                            }
                            opens = k + 4 <= n && new String(masked, k, 4).equals("type");
                        }
                        if (opens) {
                            JuliaBlock b = new JuliaBlock(w, i);
                            all.add(b);
                            stack.push(b);
                        }
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
        return all;
    }

    static List<DocSymbol> julia(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        String text = s.text;
        List<JuliaBlock> blocks = juliaBlocks(masked, new ArrayList<>());
        String m = new String(masked);
        for (JuliaBlock b : blocks) {
            Pattern p;
            DocSymbol.Kind kind;
            switch (b.keyword) {
                case "function": p = JL_FUNCTION; kind = DocSymbol.Kind.FUNCTION; break;
                case "macro":    p = JL_MACRO;    kind = DocSymbol.Kind.MACRO; break;
                case "struct":   p = JL_STRUCT;   kind = DocSymbol.Kind.STRUCT; break;
                case "abstract":
                case "primitive": p = JL_ABSTRACT; kind = DocSymbol.Kind.CLASS; break;
                case "module":
                case "baremodule": p = JL_MODULE; kind = DocSymbol.Kind.MODULE; break;
                default: continue;
            }
            int lineEnd = m.indexOf('\n', b.offset);
            Matcher mm = p.matcher(m.substring(b.offset, lineEnd < 0 ? m.length() : lineEnd));
            if (!mm.find()) {
                continue;
            }
            int off = b.offset + mm.start(1);
            int line = lineOf(lineStarts, off);
            int end = b.endOffset < 0 ? lineStarts.length - 1 : lineOf(lineStarts, b.endOffset);
            String detail = "";
            if (kind == DocSymbol.Kind.FUNCTION) {
                int paren = m.indexOf('(', off);
                int bi = paren < 0 ? -1 : s.bracketAt(paren);
                if (bi >= 0 && s.bracketPartner[bi] >= 0 && lineOf(lineStarts, paren) == line) {
                    detail = paramsOf(text, paren, s.bracketPos[s.bracketPartner[bi]]);
                }
            }
            out.add(new DocSymbol(mm.group(1), kind, detail, line, end, off, 0, null,
                                  juliaDocAbove(text, lineStarts, line)));
        }
        // Short-form functions: f(x) = x^2
        for (int line = 0; line < lineStarts.length; line++) {
            String ml = lineString(masked, lineStarts, line);
            Matcher sm = JL_SHORT.matcher(ml);
            if (sm.find()) {
                int off = lineStarts[line] + sm.start(1);
                out.add(new DocSymbol(sm.group(1), DocSymbol.Kind.FUNCTION,
                                      "(" + collapse(rawLine(text, lineStarts, line)
                                              .substring(Math.min(sm.start(2), rawLine(text, lineStarts, line).length()),
                                                         Math.min(sm.end(2), rawLine(text, lineStarts, line).length())), 50) + ")",
                                      line, line, off, 0, null, null));
            }
        }
        nest(out);
        return out;
    }

    private static String juliaDocAbove(String text, int[] lineStarts, int line) {
        int l = line - 1;
        if (l < 0) {
            return null;
        }
        String t = rawLine(text, lineStarts, l).trim();
        if (!t.endsWith("\"\"\"")) {
            return hashCommentsAbove(text, lineStarts, line);
        }
        int end = lineStarts[l] + rawLine(text, lineStarts, l).lastIndexOf("\"\"\"");
        int start = text.lastIndexOf("\"\"\"", end - 1);
        if (start < 0 || start == end) {
            return null;
        }
        return cleanDoc(text.substring(start + 3, end));
    }

    // ---- CMake -------------------------------------------------------------

    static final Pattern CMAKE_COMMAND = Pattern.compile("(?m)^[ \\t]*([A-Za-z_]\\w*)[ \\t]*\\(");

    /** One command of a CMake file: its name in lower case, offsets and first argument. */
    static final class CMakeCommand {
        final String name;
        final int offset;
        final int argsStart;
        final int argsEnd;
        final String firstArg;
        final String args;

        CMakeCommand(String name, int offset, int argsStart, int argsEnd, String firstArg, String args) {
            this.name = name;
            this.offset = offset;
            this.argsStart = argsStart;
            this.argsEnd = argsEnd;
            this.firstArg = firstArg;
            this.args = args;
        }
    }

    static List<CMakeCommand> cmakeCommands(SyntaxHighlighter.Snapshot s, char[] masked) {
        List<CMakeCommand> out = new ArrayList<>();
        Matcher m = CMAKE_COMMAND.matcher(new String(masked));
        String text = s.text;
        while (m.find()) {
            int paren = m.end() - 1;
            int bi = s.bracketAt(paren);
            int close = bi >= 0 && s.bracketPartner[bi] >= 0 ? s.bracketPos[s.bracketPartner[bi]] : -1;
            String args = close > paren ? text.substring(paren + 1, close) : "";
            String first = firstToken(args);
            out.add(new CMakeCommand(m.group(1).toLowerCase(Locale.ROOT), m.start(1), paren + 1,
                                     close < 0 ? paren + 1 : close, first, args));
        }
        return out;
    }

    private static String firstToken(String args) {
        String t = args.strip();
        if (t.isEmpty()) {
            return "";
        }
        if (t.startsWith("\"")) {
            int e = t.indexOf('"', 1);
            return e > 0 ? t.substring(1, e) : t.substring(1);
        }
        int e = 0;
        while (e < t.length() && !Character.isWhitespace(t.charAt(e)) && t.charAt(e) != ')') {
            e++;
        }
        return t.substring(0, e);
    }

    static List<DocSymbol> cmake(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        Set<String> seenVars = new HashSet<>();
        Deque<int[]> blocks = new ArrayDeque<>(); // {index in out, or -1}
        for (CMakeCommand c : cmakeCommands(s, masked)) {
            int line = lineOf(lineStarts, c.offset);
            String arg = c.firstArg;
            if (arg.startsWith("$") && !c.name.equals("endfunction") && !c.name.equals("endmacro")) {
                // add_executable(${name} ...) inside a function names no target of its own.
                continue;
            }
            int argOff = c.argsStart + Math.max(0, s.text.substring(c.argsStart, Math.min(c.argsEnd, s.text.length()))
                                                     .indexOf(arg.isEmpty() ? " " : arg));
            switch (c.name) {
                case "project":
                    if (!arg.isEmpty()) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.MODULE, "project", line, line, argOff, 0, null, null));
                    }
                    break;
                case "add_executable":
                    if (!arg.isEmpty()) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.TARGET, "executable", line, line, argOff, 0, null,
                                              hashCommentsAbove(s.text, lineStarts, line)));
                    }
                    break;
                case "add_library": {
                    if (!arg.isEmpty()) {
                        String type = "library";
                        String up = c.args.toUpperCase(Locale.ROOT);
                        if (up.contains("SHARED")) {
                            type = "shared library";
                        } else if (up.contains("STATIC")) {
                            type = "static library";
                        } else if (up.contains("INTERFACE")) {
                            type = "interface library";
                        }
                        out.add(new DocSymbol(arg, DocSymbol.Kind.TARGET, type, line, line, argOff, 0, null,
                                              hashCommentsAbove(s.text, lineStarts, line)));
                    }
                    break;
                }
                case "add_custom_target":
                    if (!arg.isEmpty()) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.TARGET, "custom target", line, line, argOff, 0,
                                              null, null));
                    }
                    break;
                case "find_package":
                    if (!arg.isEmpty()) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.PACKAGE, "package", line, line, argOff, 0, null, null));
                    }
                    break;
                case "option":
                    if (!arg.isEmpty() && seenVars.add(arg)) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.VARIABLE, "option", line, line, argOff, 0, null, null));
                    }
                    break;
                case "set":
                    if (!arg.isEmpty() && !arg.startsWith("$") && seenVars.add(arg)) {
                        out.add(new DocSymbol(arg, DocSymbol.Kind.VARIABLE, "variable", line, line, argOff, 0, null, null));
                    }
                    break;
                case "function":
                case "macro": {
                    if (!arg.isEmpty()) {
                        String rest = c.args.strip();
                        int sp = rest.indexOf(' ');
                        String params = sp < 0 ? "()" : "(" + collapse(rest.substring(sp + 1), 50) + ")";
                        out.add(new DocSymbol(arg, c.name.equals("function") ? DocSymbol.Kind.FUNCTION
                                                                              : DocSymbol.Kind.MACRO,
                                              params, line, line, argOff, 0, null,
                                              hashCommentsAbove(s.text, lineStarts, line)));
                        blocks.push(new int[] { out.size() - 1 });
                    }
                    break;
                }
                case "endfunction":
                case "endmacro":
                    if (!blocks.isEmpty()) {
                        int idx = blocks.pop()[0];
                        out.set(idx, withEnd(out.get(idx), line));
                    }
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    // ---- Fortran -----------------------------------------------------------

    private static final Pattern F_UNIT = Pattern.compile(
            "^\\s*(?:(?:recursive|pure|elemental|impure|module)\\s+)*"
            + "(?:(?:integer|real|double\\s+precision|complex|logical|character)(?:\\s*\\([^)]*\\))?\\s+)?"
            + "(program|module|submodule|function|subroutine)\\s+([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F_TYPE = Pattern.compile(
            "^\\s*type\\s*(?:,\\s*[\\w()\\s]+)?(?:::)?\\s*([A-Za-z_]\\w*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern F_END = Pattern.compile(
            "^\\s*end\\s*(program|module|submodule|function|subroutine|type|interface)?\\b(.*)$", Pattern.CASE_INSENSITIVE);

    static List<DocSymbol> fortran(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int line = 0; line < lineStarts.length; line++) {
            String ml = lineString(masked, lineStarts, line);
            Matcher u = F_UNIT.matcher(ml);
            if (u.find() && !ml.trim().toLowerCase(Locale.ROOT).startsWith("end")
                    && !ml.toLowerCase(Locale.ROOT).contains("module procedure")) {
                String unit = u.group(1).toLowerCase(Locale.ROOT);
                DocSymbol.Kind kind = unit.equals("function") || unit.equals("subroutine")
                        ? DocSymbol.Kind.FUNCTION : DocSymbol.Kind.MODULE;
                int off = lineStarts[line] + u.start(2);
                String parent = stack.isEmpty() ? null : out.get(stack.peek()).name;
                out.add(new DocSymbol(u.group(2), kind, unit, line, line, off, stack.size(), parent, null));
                stack.push(out.size() - 1);
                continue;
            }
            Matcher t = F_TYPE.matcher(ml);
            if (t.find() && !ml.matches("(?i)^\\s*type\\s*\\(.*")) {
                int off = lineStarts[line] + t.start(1);
                String parent = stack.isEmpty() ? null : out.get(stack.peek()).name;
                out.add(new DocSymbol(t.group(1), DocSymbol.Kind.STRUCT, "type", line, line, off, stack.size(),
                                      parent, null));
                stack.push(out.size() - 1);
                continue;
            }
            Matcher e = F_END.matcher(ml);
            if (e.find()) {
                String what = e.group(1);
                String rest = e.group(2) == null ? "" : e.group(2).trim().toLowerCase(Locale.ROOT);
                boolean closesUnit = what != null || rest.isEmpty() || rest.startsWith("!");
                if (what == null && !rest.isEmpty() && Character.isLetter(rest.charAt(0))) {
                    closesUnit = false; // "end if", "end do"
                }
                if (closesUnit && !stack.isEmpty()) {
                    int idx = stack.pop();
                    out.set(idx, withEnd(out.get(idx), line));
                }
            }
        }
        return out;
    }

    // ---- Markdown, LaTeX ---------------------------------------------------

    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    static List<DocSymbol> markdown(SyntaxHighlighter.Snapshot s, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        String text = s.text;
        for (int line = 0; line < lineStarts.length; line++) {
            int ls = lineStarts[line];
            if (s.kindAt(ls) == TokenKind.STRING) {
                continue; // inside a code fence
            }
            String raw = rawLine(text, lineStarts, line);
            Matcher h = MD_HEADING.matcher(raw);
            int level = -1;
            String title = null;
            int off = ls;
            if (h.find()) {
                level = h.group(1).length();
                title = h.group(2);
                off = ls + h.start(2);
            } else if (line + 1 < lineStarts.length && !raw.isBlank() && !raw.startsWith("-") && !raw.startsWith("*")
                       && !raw.startsWith(">") && !raw.startsWith("|")) {
                String next = rawLine(text, lineStarts, line + 1).trim();
                if (next.length() >= 3 && next.chars().allMatch(c -> c == '=')) {
                    level = 1;
                } else if (next.length() >= 3 && next.chars().allMatch(c -> c == '-')) {
                    level = 2;
                }
                if (level > 0) {
                    title = raw.trim();
                    off = ls + raw.indexOf(title);
                }
            }
            if (level > 0 && title != null && !title.isEmpty()) {
                out.add(new DocSymbol(title, DocSymbol.Kind.HEADING, "H" + level, line, line, off, level - 1, null, null));
                levels.add(level);
            }
        }
        closeByLevel(out, levels, lineStarts.length - 1);
        return out;
    }

    private static final Pattern TEX_SECTION = Pattern.compile(
            "\\\\(part|chapter|section|subsection|subsubsection|paragraph)\\*?\\s*(?:\\[[^\\]]*\\])?\\{([^}]*)\\}");
    private static final Pattern TEX_COMMAND = Pattern.compile("\\\\(?:re)?newcommand\\*?\\s*\\{?\\\\([A-Za-z@]+)");
    private static final Pattern TEX_LABEL = Pattern.compile("\\\\label\\{([^}]+)\\}");
    private static final List<String> TEX_LEVELS = List.of(
            "part", "chapter", "section", "subsection", "subsubsection", "paragraph");

    static List<DocSymbol> latex(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        String m = new String(masked);
        Matcher sec = TEX_SECTION.matcher(m);
        while (sec.find()) {
            int level = TEX_LEVELS.indexOf(sec.group(1)) + 1;
            int off = sec.start(2);
            int line = lineOf(lineStarts, off);
            out.add(new DocSymbol(sec.group(2).trim(), DocSymbol.Kind.SECTION, sec.group(1), line, line, off,
                                  level - 1, null, null));
            levels.add(level);
        }
        closeByLevel(out, levels, lineStarts.length - 1);
        Matcher cmd = TEX_COMMAND.matcher(m);
        while (cmd.find()) {
            int off = cmd.start(1);
            int line = lineOf(lineStarts, off);
            out.add(new DocSymbol("\\" + cmd.group(1), DocSymbol.Kind.MACRO, "command", line, line, off, 0, null, null));
        }
        Matcher label = TEX_LABEL.matcher(m);
        while (label.find()) {
            int off = label.start(1);
            int line = lineOf(lineStarts, off);
            out.add(new DocSymbol(label.group(1), DocSymbol.Kind.CONSTANT, "label", line, line, off, 0, null, null));
        }
        out.sort((a, b) -> Integer.compare(a.offset, b.offset));
        return out;
    }

    /** A heading runs until the next heading of its level or above. */
    private static void closeByLevel(List<DocSymbol> out, List<Integer> levels, int lastLine) {
        for (int i = 0; i < out.size(); i++) {
            int end = lastLine;
            for (int j = i + 1; j < out.size(); j++) {
                if (levels.get(j) <= levels.get(i)) {
                    end = Math.max(out.get(i).line, out.get(j).line - 1);
                    break;
                }
            }
            String container = null;
            for (int j = i - 1; j >= 0; j--) {
                if (levels.get(j) < levels.get(i)) {
                    container = out.get(j).name;
                    break;
                }
            }
            DocSymbol d = out.get(i);
            out.set(i, new DocSymbol(d.name, d.kind, d.detail, d.line, end, d.offset, d.depth, container, d.doc));
        }
    }

    // ---- Shell, config, JSON -----------------------------------------------

    private static final Pattern SH_FUNCTION = Pattern.compile(
            "^\\s*(?:function\\s+([A-Za-z_][\\w.:-]*)|([A-Za-z_][\\w.:-]*)\\s*\\(\\s*\\))");

    static List<DocSymbol> shell(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        for (int line = 0; line < lineStarts.length; line++) {
            String ml = lineString(masked, lineStarts, line);
            Matcher f = SH_FUNCTION.matcher(ml);
            if (f.find()) {
                String name = f.group(1) != null ? f.group(1) : f.group(2);
                int off = lineStarts[line] + (f.group(1) != null ? f.start(1) : f.start(2));
                int end = line;
                int brace = ml.indexOf('{');
                if (brace >= 0) {
                    int bi = s.bracketAt(lineStarts[line] + brace);
                    if (bi >= 0 && s.bracketPartner[bi] >= 0) {
                        end = lineOf(lineStarts, s.bracketPos[s.bracketPartner[bi]]);
                    }
                }
                out.add(new DocSymbol(name, DocSymbol.Kind.FUNCTION, "()", line, end, off, 0, null,
                                      hashCommentsAbove(s.text, lineStarts, line)));
            }
        }
        return out;
    }

    static List<DocSymbol> config(SyntaxHighlighter.Snapshot s, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        int section = -1;
        for (int i = 0; i < s.count; i++) {
            TokenKind kind = s.kinds[i];
            if (kind != TokenKind.TYPE && kind != TokenKind.VARIABLE) {
                continue;
            }
            int off = s.starts[i];
            int line = lineOf(lineStarts, off);
            String name = s.text.substring(off, s.ends[i]).trim();
            if (kind == TokenKind.TYPE) {
                if (section >= 0) {
                    DocSymbol d = out.get(section);
                    out.set(section, withEnd(d, Math.max(d.line, line - 1)));
                }
                String title = name.replaceAll("^\\[+|\\]+$", "");
                out.add(new DocSymbol(title, DocSymbol.Kind.NAMESPACE, "section", line, lineStarts.length - 1,
                                      off, 0, null, null));
                section = out.size() - 1;
            } else if (lineStarts[line] + firstNonSpace(rawLine(s.text, lineStarts, line)) == off) {
                String parent = section >= 0 ? out.get(section).name : null;
                String value = rawLine(s.text, lineStarts, line);
                int eq = Math.max(value.indexOf('='), value.indexOf(':'));
                String detail = eq >= 0 ? collapse(value.substring(eq + 1), 40) : "";
                out.add(new DocSymbol(name, DocSymbol.Kind.PROPERTY, detail, line, line, off,
                                      section >= 0 ? 1 : 0, parent, null));
            }
        }
        return out;
    }

    static List<DocSymbol> json(SyntaxHighlighter.Snapshot s, int[] lineStarts) {
        List<DocSymbol> out = new ArrayList<>();
        int bi = 0;
        int depth = 0;
        for (int i = 0; i < s.count && out.size() < 500; i++) {
            if (s.kinds[i] != TokenKind.VARIABLE) {
                continue;
            }
            int off = s.starts[i];
            while (bi < s.bracketCount && s.bracketPos[bi] < off) {
                char c = s.bracketChar[bi];
                depth += (c == '{' || c == '[') ? 1 : -1;
                bi++;
            }
            if (depth == 1) {
                String name = s.text.substring(off, s.ends[i]);
                if (name.length() >= 2 && name.startsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                int line = lineOf(lineStarts, off);
                out.add(new DocSymbol(name, DocSymbol.Kind.PROPERTY, "", line, line, off, 0, null, null));
            }
        }
        return out;
    }

    // ---- Shared ------------------------------------------------------------

    /** Sets container and depth from line ranges, for languages read flat. */
    private static void nest(List<DocSymbol> out) {
        out.sort((a, b) -> Integer.compare(a.offset, b.offset));
        for (int i = 0; i < out.size(); i++) {
            DocSymbol d = out.get(i);
            String container = null;
            int depth = 0;
            for (int j = i - 1; j >= 0; j--) {
                DocSymbol o = out.get(j);
                if (o.line < d.line && o.endLine >= d.endLine && o != d) {
                    if (container == null) {
                        container = o.name;
                    }
                    depth++;
                }
            }
            if (container != null || depth != d.depth) {
                out.set(i, new DocSymbol(d.name, d.kind, d.detail, d.line, d.endLine, d.offset, depth,
                                         container, d.doc));
            }
        }
    }

    static DocSymbol withEnd(DocSymbol d, int endLine) {
        return new DocSymbol(d.name, d.kind, d.detail, d.line, endLine, d.offset, d.depth, d.container, d.doc);
    }

    /** The comment block directly above a line, cleaned of its markers. */
    static String docAbove(String text, int[] lineStarts, int line) {
        List<String> parts = new ArrayList<>();
        boolean inBlock = false;
        for (int l = line - 1; l >= Math.max(0, line - 16); l--) {
            String t = rawLine(text, lineStarts, l).trim();
            if (t.isEmpty()) {
                break;
            }
            if (t.endsWith("*/")) {
                inBlock = !t.startsWith("/*") || t.length() <= 2;
                parts.add(0, t);
                if (t.startsWith("/*")) {
                    inBlock = false;
                }
                continue;
            }
            if (inBlock) {
                parts.add(0, t);
                if (t.startsWith("/*")) {
                    inBlock = false;
                }
                continue;
            }
            if (t.startsWith("//")) {
                parts.add(0, t);
                continue;
            }
            break;
        }
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String c = p.replaceFirst("^/\\*\\*?!?", "").replaceFirst("\\*/$", "")
                        .replaceFirst("^//[/!]?<?", "").replaceFirst("^\\*\\s?", "").trim();
            if (!c.isEmpty()) {
                sb.append(c).append('\n');
            }
        }
        return sb.length() == 0 ? null : cleanDoc(sb.toString());
    }

    static String cleanDoc(String raw) {
        String t = raw.strip();
        if (t.length() > 500) {
            t = t.substring(0, 500) + "…";
        }
        return t.isEmpty() ? null : t;
    }

    static String paramsOf(String text, int open, int close) {
        if (open < 0 || close <= open || close > text.length()) {
            return "()";
        }
        return "(" + collapse(text.substring(open + 1, close), 70) + ")";
    }

    static String collapse(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    private static int firstNonSpace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    static int lineOf(int[] lineStarts, int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    static String lineString(char[] chars, int[] lineStarts, int line) {
        int s = lineStarts[line];
        int e = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : chars.length;
        if (e > s && chars[e - 1] == '\r') {
            e--;
        }
        return new String(chars, s, Math.max(0, e - s));
    }

    static String rawLine(String text, int[] lineStarts, int line) {
        if (line < 0 || line >= lineStarts.length) {
            return "";
        }
        int s = lineStarts[line];
        int e = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
        return text.substring(s, Math.max(s, e));
    }
}
