package com.sphere.components.editor;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Findings the editor can make on its own while the file is typed: brackets that
 * never close, strings left open, a Python block with no body, a CMake if()
 * without endif(), a misspelled CMake command. Every rule is chosen to be
 * right when it speaks; a rule that would guess stays silent instead.
 */
final class LiveLinter {

    private static final int MAX_FINDINGS = 200;

    private LiveLinter() {
    }

    private static final class Sink {
        final List<EditorDiagnostic> out = new ArrayList<>();
        final int[] lineStarts;

        Sink(int[] lineStarts) {
            this.lineStarts = lineStarts;
        }

        void add(int offset, int length, EditorDiagnostic.Severity sev, String code, String message) {
            if (out.size() >= MAX_FINDINGS) {
                return;
            }
            int line = SymbolExtractor.lineOf(lineStarts, offset);
            int col = offset - lineStarts[line] + 1;
            out.add(new EditorDiagnostic(line + 1, col, length, sev, code, message,
                                         EditorDiagnostic.SOURCE_INSIGHT));
        }
    }

    static List<EditorDiagnostic> lint(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts,
                                       int[] depthAtLine, File file) {
        Sink sink = new Sink(lineStarts);
        LanguageSpec spec = s.spec;
        if (spec.isPlain() || s.text.length() > 2_000_000) {
            return sink.out;
        }
        if (spec.bracketLint) {
            brackets(s, sink);
        }
        unterminated(s, sink);
        todos(s, sink);

        if (spec == LanguageSpec.CPP) {
            cppClassSemicolons(s, masked, sink);
            cppDuplicateIncludes(s, lineStarts, sink);
            assignmentInCondition(masked, sink);
        } else if (spec == LanguageSpec.JAVA || spec == LanguageSpec.JAVASCRIPT) {
            assignmentInCondition(masked, sink);
        } else if (spec == LanguageSpec.PYTHON) {
            pythonBlocks(s, masked, lineStarts, depthAtLine, sink);
            pythonIndentation(s.text, lineStarts, masked, sink);
            pythonPrint(s, lineStarts, sink);
            pythonUnusedImports(s, masked, lineStarts, file, sink);
        } else if (spec == LanguageSpec.CMAKE) {
            cmake(s, masked, file, sink);
        } else if (spec == LanguageSpec.JULIA) {
            julia(masked, sink);
        } else if (spec == LanguageSpec.JSON) {
            jsonTrailingCommas(masked, sink);
        }
        sink.out.sort((a, b) -> a.getLine() != b.getLine() ? Integer.compare(a.getLine(), b.getLine())
                                                            : Integer.compare(a.getColumn(), b.getColumn()));
        return sink.out;
    }

    // ---- Every language ----------------------------------------------------

    private static void brackets(SyntaxHighlighter.Snapshot s, Sink sink) {
        int reported = 0;
        for (int i = 0; i < s.bracketCount && reported < 12; i++) {
            if (s.bracketPartner[i] >= 0) {
                continue;
            }
            char c = s.bracketChar[i];
            boolean opener = c == '(' || c == '[' || c == '{';
            if (opener) {
                sink.add(s.bracketPos[i], 1, EditorDiagnostic.Severity.ERROR, "bracket",
                         "'" + c + "' is never closed; '" + closerOf(c) + "' expected");
            } else {
                String hint = "";
                for (int k = i - 1; k >= 0; k--) {
                    char o = s.bracketChar[k];
                    if ((o == '(' || o == '[' || o == '{') && s.bracketPartner[k] < 0) {
                        hint = " — '" + o + "' opened earlier expects '" + closerOf(o) + "'";
                        break;
                    }
                }
                sink.add(s.bracketPos[i], 1, EditorDiagnostic.Severity.ERROR, "bracket",
                         "Unmatched '" + c + "'" + hint);
            }
            reported++;
        }
    }

    private static char closerOf(char opener) {
        return opener == '(' ? ')' : opener == '[' ? ']' : '}';
    }

    private static void unterminated(SyntaxHighlighter.Snapshot s, Sink sink) {
        LanguageSpec spec = s.spec;
        boolean strings = spec == LanguageSpec.CPP || spec == LanguageSpec.JAVA || spec == LanguageSpec.JAVASCRIPT
                          || spec == LanguageSpec.JSON || spec == LanguageSpec.PYTHON;
        int reported = 0;
        for (int i = 0; i < s.count && reported < 10; i++) {
            if (!s.unterminated[i]) {
                continue;
            }
            TokenKind k = s.kinds[i];
            if ((k == TokenKind.STRING || k == TokenKind.CHARACTER) && strings) {
                sink.add(s.starts[i], Math.min(s.ends[i] - s.starts[i], 200), EditorDiagnostic.Severity.ERROR,
                         "string", k == TokenKind.CHARACTER ? "Unterminated character literal"
                                                            : "Unterminated string literal");
                reported++;
            } else if (k == TokenKind.COMMENT && spec.blockCommentOpen != null
                       && s.text.startsWith(spec.blockCommentOpen, s.starts[i])) {
                sink.add(s.starts[i], spec.blockCommentOpen.length(), EditorDiagnostic.Severity.ERROR, "comment",
                         "Comment opened with '" + spec.blockCommentOpen + "' is never closed");
                reported++;
            }
        }
    }

    private static final Pattern TODO = Pattern.compile("\\b(TODO|FIXME|HACK|XXX|BUG)\\b[:\\s]*(.*)");

    private static void todos(SyntaxHighlighter.Snapshot s, Sink sink) {
        if (!s.spec.isCode() && s.spec.dialect != LanguageSpec.Dialect.CONFIG
                && s.spec.dialect != LanguageSpec.Dialect.LATEX) {
            return;
        }
        int found = 0;
        for (int i = 0; i < s.count && found < 60; i++) {
            if (s.kinds[i] != TokenKind.COMMENT) {
                continue;
            }
            String c = s.text.substring(s.starts[i], Math.min(s.ends[i], s.starts[i] + 2000));
            Matcher m = TODO.matcher(c);
            while (m.find() && found < 60) {
                int eol = c.indexOf('\n', m.start());
                String rest = (eol < 0 ? c.substring(m.start(2)) : c.substring(m.start(2), Math.max(m.start(2), eol)))
                              .replace("*/", "").trim();
                sink.add(s.starts[i] + m.start(1), m.group(1).length(), EditorDiagnostic.Severity.INFO, "TODO",
                         m.group(1) + (rest.isEmpty() ? "" : ": " + rest));
                found++;
            }
        }
    }

    // ---- C-like ------------------------------------------------------------

    private static void cppClassSemicolons(SyntaxHighlighter.Snapshot s, char[] masked, Sink sink) {
        String m = new String(masked);
        for (int b = 0; b < s.bracketCount; b++) {
            if (s.bracketChar[b] != '{' || s.bracketPartner[b] < 0) {
                continue;
            }
            int brace = s.bracketPos[b];
            int hs = brace - 1;
            int paren = 0;
            while (hs >= 0 && brace - hs < 600) {
                char c = m.charAt(hs);
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
                hs--;
            }
            String header = m.substring(hs + 1, brace).stripTrailing();
            if (!SymbolExtractor.hasTypeKeyword(header)) {
                continue;
            }
            Matcher cls = SymbolExtractor.CLASS_HEADER.matcher(header);
            if (!cls.find() || header.contains("(") && !header.trim().startsWith("template")) {
                continue;
            }
            // "typedef struct X {...} Y;" and "struct X {...} x;" are fine; so is "};".
            int close = s.bracketPos[s.bracketPartner[b]];
            int k = close + 1;
            while (k < m.length() && Character.isWhitespace(m.charAt(k))) {
                k++;
            }
            char next = k < m.length() ? m.charAt(k) : 0;
            if (next == ';' || Character.isJavaIdentifierPart(next) || next == '*' || next == '&'
                    || next == ',' || next == ')' || next == '[') {
                continue;
            }
            sink.add(close, 1, EditorDiagnostic.Severity.ERROR, "semicolon",
                     "Expected ';' after the definition of " + cls.group(1) + " " + cls.group(3));
        }
    }

    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]*([<\"][^>\"\\n]+[>\"])");

    private static void cppDuplicateIncludes(SyntaxHighlighter.Snapshot s, int[] lineStarts, Sink sink) {
        Map<String, Integer> seen = new HashMap<>();
        Matcher m = INCLUDE.matcher(s.text);
        while (m.find()) {
            String target = m.group(1);
            // An include inside an #if branch may legitimately repeat another branch's.
            Integer first = seen.putIfAbsent(target, SymbolExtractor.lineOf(lineStarts, m.start()));
            if (first != null && !conditionalBetween(s.text, lineStarts[first], m.start())) {
                sink.add(m.start(1), target.length(), EditorDiagnostic.Severity.WARNING, "include",
                         "Duplicate #include " + target + " (already on line " + (first + 1) + ")");
            }
        }
    }

    private static boolean conditionalBetween(String text, int from, int to) {
        String between = text.substring(from, to);
        return between.contains("#if") || between.contains("#else") || between.contains("#elif");
    }

    private static final Pattern ASSIGN_IN_CONDITION = Pattern.compile(
            "\\b(if|while)\\s*\\(\\s*([A-Za-z_][\\w.\\->\\[\\]]*)\\s*=(?!=)");

    private static void assignmentInCondition(char[] masked, Sink sink) {
        Matcher m = ASSIGN_IN_CONDITION.matcher(new String(masked));
        int found = 0;
        while (m.find() && found++ < 20) {
            int eq = m.end() - 1;
            sink.add(eq, 1, EditorDiagnostic.Severity.WARNING, "assign",
                     "Assignment in a condition: did you mean '=='? (wrap it in extra parentheses if intended)");
        }
    }

    // ---- Python ------------------------------------------------------------

    private static final Pattern PY_OPENER = Pattern.compile(
            "^\\s*(if|elif|else|for|while|try|except|finally|with|def|class|async\\s+def|async\\s+for"
            + "|async\\s+with|match|case)\\b.*:\\s*$");

    private static void pythonBlocks(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts,
                                     int[] depthAtLine, Sink sink) {
        int lines = lineStarts.length;
        for (int line = 0; line < lines; line++) {
            String ml = SymbolExtractor.lineString(masked, lineStarts, line);
            if (!ml.trim().endsWith(":")) {
                continue;
            }
            int nextLineStart = line + 1 < lines ? line + 1 : line;
            if (depthAtLine[line] > 0 || (line + 1 < lines && depthAtLine[nextLineStart] > 0)) {
                continue; // a dict or a slice spread over lines
            }
            Matcher m = PY_OPENER.matcher(ml);
            if (!m.find()) {
                continue;
            }
            int indent = CodeTextPane.indentColumns(ml, 8);
            int next = line + 1;
            while (next < lines && CodeTextPane.indentColumns(SymbolExtractor.lineString(masked, lineStarts, next), 8) < 0) {
                next++;
            }
            if (next >= lines) {
                // The block is being written right now at the end of the file; say nothing yet.
                continue;
            }
            int nextIndent = CodeTextPane.indentColumns(SymbolExtractor.lineString(masked, lineStarts, next), 8);
            if (nextIndent <= indent) {
                int colon = lineStarts[line] + ml.lastIndexOf(':');
                String keyword = m.group(1).replaceAll("\\s+", " ");
                sink.add(colon, 1, EditorDiagnostic.Severity.ERROR, "indent",
                         "Expected an indented block after '" + keyword + "'");
            }
        }
    }

    private static void pythonIndentation(String text, int[] lineStarts, char[] masked, Sink sink) {
        int tabs = 0;
        int spaces = 0;
        int firstTab = -1;
        int firstSpace = -1;
        for (int line = 0; line < lineStarts.length; line++) {
            String ml = SymbolExtractor.lineString(masked, lineStarts, line);
            if (ml.isBlank() || ml.isEmpty()) {
                continue;
            }
            char c = ml.charAt(0);
            if (c == '\t') {
                tabs++;
                if (firstTab < 0) {
                    firstTab = line;
                }
            } else if (c == ' ') {
                spaces++;
                if (firstSpace < 0) {
                    firstSpace = line;
                }
            }
        }
        if (tabs > 0 && spaces > 0) {
            int line = tabs < spaces ? firstTab : firstSpace;
            sink.add(lineStarts[line], 1, EditorDiagnostic.Severity.WARNING, "tabs",
                     "Inconsistent use of tabs and spaces in indentation (" + tabs + " lines with tabs, "
                     + spaces + " with spaces)");
        }
    }

    private static final Pattern PY2_PRINT = Pattern.compile("(?m)^[ \\t]*(print)[ \\t]+(?=[\"'A-Za-z_0-9])(?![ \\t]*=)");

    private static void pythonPrint(SyntaxHighlighter.Snapshot s, int[] lineStarts, Sink sink) {
        Matcher m = PY2_PRINT.matcher(s.text);
        int found = 0;
        while (m.find() && found++ < 10) {
            if (!s.isCodeAt(m.start(1))) {
                continue;
            }
            sink.add(m.start(1), 5, EditorDiagnostic.Severity.ERROR, "print",
                     "Missing parentheses in call to 'print' (Python 3): print(...)");
        }
    }

    private static final Pattern PY_IMPORT = Pattern.compile("^import\\s+(.+)$");
    private static final Pattern PY_FROM = Pattern.compile("^from\\s+([\\w.]+)\\s+import\\s+([^()\\\\]+)$");

    private static void pythonUnusedImports(SyntaxHighlighter.Snapshot s, char[] masked, int[] lineStarts,
                                            File file, Sink sink) {
        if (file != null && file.getName().equals("__init__.py")) {
            return;
        }
        if (s.text.contains("__all__")) {
            return;
        }
        String m = new String(masked);
        // Names bound by import lines, with where they are written.
        List<Object[]> bound = new ArrayList<>();
        Set<Integer> importLines = new HashSet<>();
        for (int line = 0; line < lineStarts.length; line++) {
            String ml = SymbolExtractor.lineString(masked, lineStarts, line).stripTrailing();
            if (ml.isEmpty() || Character.isWhitespace(ml.charAt(0))) {
                continue; // only module-level imports
            }
            Matcher im = PY_IMPORT.matcher(ml);
            Matcher fm = PY_FROM.matcher(ml);
            if (im.find()) {
                importLines.add(line);
                for (String part : im.group(1).split(",")) {
                    String p = part.trim();
                    String name;
                    if (p.contains(" as ")) {
                        name = p.substring(p.indexOf(" as ") + 4).trim();
                    } else {
                        name = p.contains(".") ? p.substring(0, p.indexOf('.')) : p;
                    }
                    if (!name.isEmpty()) {
                        bound.add(new Object[] { name, lineStarts[line] + ml.indexOf(name, 6) });
                    }
                }
            } else if (fm.find()) {
                if (fm.group(1).equals("__future__")) {
                    continue;
                }
                importLines.add(line);
                int base = lineStarts[line] + fm.start(2);
                for (String part : fm.group(2).split(",")) {
                    String p = part.trim();
                    if (p.equals("*") || p.isEmpty()) {
                        continue;
                    }
                    String name = p.contains(" as ") ? p.substring(p.indexOf(" as ") + 4).trim() : p;
                    int at = ml.indexOf(name, fm.start(2));
                    bound.add(new Object[] { name, at < 0 ? base : lineStarts[line] + at });
                }
            }
        }
        if (bound.isEmpty()) {
            return;
        }
        // Count uses outside import lines.
        StringBuilder rest = new StringBuilder(m.length());
        for (int line = 0; line < lineStarts.length; line++) {
            if (!importLines.contains(line)) {
                rest.append(SymbolExtractor.lineString(masked, lineStarts, line)).append('\n');
            }
        }
        String body = rest.toString();
        // Names used inside f-strings and string annotations are blanked; look at the raw text too.
        for (Object[] b : bound) {
            String name = (String) b[0];
            if (!name.matches("[A-Za-z_]\\w*")) {
                continue;
            }
            Pattern use = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b");
            if (use.matcher(body).find()) {
                continue;
            }
            if (Pattern.compile("[{\\s(\\[]" + Pattern.quote(name) + "[.\\[(]").matcher(s.text).find()
                    && countRaw(s.text, name) > 1) {
                continue;
            }
            sink.add((int) b[1], name.length(), EditorDiagnostic.Severity.WARNING, "unused",
                     "'" + name + "' is imported but never used");
        }
    }

    private static int countRaw(String text, String name) {
        int count = 0;
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text);
        while (m.find()) {
            count++;
        }
        return count;
    }

    // ---- CMake -------------------------------------------------------------

    private static final Map<String, String> CMAKE_ENDS = Map.of(
            "if", "endif", "foreach", "endforeach", "while", "endwhile",
            "function", "endfunction", "macro", "endmacro", "block", "endblock");

    private static void cmake(SyntaxHighlighter.Snapshot s, char[] masked, File file, Sink sink) {
        List<SymbolExtractor.CMakeCommand> commands = SymbolExtractor.cmakeCommands(s, masked);
        Deque<SymbolExtractor.CMakeCommand> stack = new ArrayDeque<>();
        Set<String> userDefined = new HashSet<>();
        for (SymbolExtractor.CMakeCommand c : commands) {
            if ((c.name.equals("function") || c.name.equals("macro")) && !c.firstArg.isEmpty()) {
                userDefined.add(c.firstArg.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> ends = new HashSet<>(CMAKE_ENDS.values());
        int minimumAt = -1;
        int projectAt = -1;
        int index = 0;
        for (SymbolExtractor.CMakeCommand c : commands) {
            String n = c.name;
            if (n.equals("cmake_minimum_required") && minimumAt < 0) {
                minimumAt = index;
            }
            if (n.equals("project") && projectAt < 0) {
                projectAt = index;
            }
            index++;
            if (CMAKE_ENDS.containsKey(n)) {
                stack.push(c);
                continue;
            }
            if (n.equals("else") || n.equals("elseif")) {
                if (stack.isEmpty() || !stack.peek().name.equals("if")) {
                    sink.add(c.offset, n.length(), EditorDiagnostic.Severity.ERROR, "block",
                             "'" + n + "()' outside of an if() block");
                }
                continue;
            }
            if (ends.contains(n)) {
                if (stack.isEmpty()) {
                    sink.add(c.offset, n.length(), EditorDiagnostic.Severity.ERROR, "block",
                             "'" + n + "()' without a matching '" + opener(n) + "()'");
                    continue;
                }
                SymbolExtractor.CMakeCommand top = stack.peek();
                String expected = CMAKE_ENDS.get(top.name);
                if (expected.equals(n)) {
                    stack.pop();
                } else {
                    int line = SymbolExtractor.lineOf(sink.lineStarts, top.offset) + 1;
                    sink.add(c.offset, n.length(), EditorDiagnostic.Severity.ERROR, "block",
                             "Expected '" + expected + "()' to close '" + top.name + "()' from line " + line
                             + ", found '" + n + "()'");
                    // Recover: drop the block this end belongs to if it is open further down.
                    for (SymbolExtractor.CMakeCommand o : stack) {
                        if (CMAKE_ENDS.get(o.name).equals(n)) {
                            while (!stack.isEmpty() && stack.peek() != o) {
                                stack.pop();
                            }
                            stack.pop();
                            break;
                        }
                    }
                }
                continue;
            }
            if (!s.spec.isBuiltin(n) && !s.spec.isKeyword(n) && !userDefined.contains(n) && n.length() >= 5) {
                String suggestion = closest(n, s.spec.builtins);
                if (suggestion != null) {
                    sink.add(c.offset, n.length(), EditorDiagnostic.Severity.WARNING, "command",
                             "Unknown CMake command '" + n + "'. Did you mean '" + suggestion + "'?");
                }
            }
        }
        for (SymbolExtractor.CMakeCommand open : stack) {
            sink.add(open.offset, open.name.length(), EditorDiagnostic.Severity.ERROR, "block",
                     "'" + open.name + "()' is never closed by '" + CMAKE_ENDS.get(open.name) + "()'");
        }
        boolean isLists = file != null && file.getName().equalsIgnoreCase("CMakeLists.txt");
        if (isLists && !commands.isEmpty()) {
            if (minimumAt < 0 && projectAt >= 0) {
                SymbolExtractor.CMakeCommand p = commands.get(projectAt);
                sink.add(p.offset, 7, EditorDiagnostic.Severity.WARNING, "minimum",
                         "No cmake_minimum_required(VERSION ...) before project(): CMake will warn about it");
            } else if (minimumAt > projectAt && projectAt >= 0) {
                SymbolExtractor.CMakeCommand m = commands.get(minimumAt);
                sink.add(m.offset, m.name.length(), EditorDiagnostic.Severity.WARNING, "minimum",
                         "cmake_minimum_required() should come before project()");
            }
        }
    }

    private static String opener(String end) {
        for (Map.Entry<String, String> e : CMAKE_ENDS.entrySet()) {
            if (e.getValue().equals(end)) {
                return e.getKey();
            }
        }
        return end.substring(3);
    }

    /**
     * The known name closest to a word, within two edits (three for a long name,
     * where "target_link_library" is still a typo), when one is clearly best.
     */
    static String closest(String word, Set<String> known) {
        int limit = word.length() >= 12 ? 3 : 2;
        String best = null;
        int bestDistance = limit + 1;
        for (String k : known) {
            if (Math.abs(k.length() - word.length()) > limit) {
                continue;
            }
            int d = distance(word, k, bestDistance);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        return bestDistance <= limit ? best : null;
    }

    /** Levenshtein distance, giving up past a bound. */
    static int distance(String a, String b, int bound) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin >= bound) {
                return bound;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---- Julia, JSON -------------------------------------------------------

    private static void julia(char[] masked, Sink sink) {
        List<Integer> stray = new ArrayList<>();
        List<SymbolExtractor.JuliaBlock> blocks = SymbolExtractor.juliaBlocks(masked, stray);
        for (int off : stray) {
            sink.add(off, 3, EditorDiagnostic.Severity.WARNING, "end", "'end' without a block to close");
        }
        int unclosed = 0;
        for (SymbolExtractor.JuliaBlock b : blocks) {
            if (b.endOffset < 0 && unclosed++ < 5) {
                sink.add(b.offset, b.keyword.length(), EditorDiagnostic.Severity.WARNING, "end",
                         "'" + b.keyword + "' block is never closed by 'end'");
            }
        }
    }

    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*)[}\\]]");

    private static void jsonTrailingCommas(char[] masked, Sink sink) {
        Matcher m = TRAILING_COMMA.matcher(new String(masked));
        int found = 0;
        while (m.find() && found++ < 20) {
            sink.add(m.start(), 1, EditorDiagnostic.Severity.WARNING, "comma",
                     "Trailing comma: strict JSON readers reject it");
        }
    }
}
