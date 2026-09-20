package com.sphere.core.exec;

import java.util.List;
import java.util.Locale;

/**
 * Tells whether the lines typed so far form something the interpreter can run.
 *
 * This is what makes Enter mean "run it". A single statement goes at once; a
 * loop or a function waits until it is closed, because sending half of one would
 * only produce a syntax error. Each language says "closed" in its own way.
 */
public final class LineGrammar {

    private LineGrammar() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The words that open a block in Julia. */
    private static final List<String> JULIA_OPENERS = List.of(
        "function", "for", "while", "if", "begin", "let", "do", "struct",
        "mutable", "module", "quote", "try", "macro");

    /** The words that open a block in Fortran. */
    private static final List<String> FORTRAN_OPENERS = List.of(
        "do", "select", "subroutine", "function", "module", "program",
        "interface", "associate", "block", "where", "forall", "type");

    /**
     * Whether what has been typed is ready to run.
     *
     * An empty last line always closes: it is how a person says "that is all",
     * and every one of these languages leaves some case where nothing else can.
     */
    public static boolean isComplete(String mode, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        if (lines.size() > 1 && lines.get(lines.size() - 1).isBlank()) {
            return true;
        }
        return switch (mode == null ? "" : mode) {
            case "py" -> pythonComplete(lines);
            case "julia" -> juliaComplete(lines);
            case "cpp" -> cppComplete(lines);
            case "fortran" -> fortranComplete(lines);
            default -> true;
        };
    }

    // ---- Python --------------------------------------------------------------

    private static boolean pythonComplete(List<String> lines) {
        final String joined = String.join("\n", lines);
        Scan scan = scan(joined, '#');
        if (scan.inString || scan.depth > 0) {
            return false;
        }
        final String last = lines.get(lines.size() - 1);
        if (last.endsWith("\\")) {
            return false;
        }
        // A colon opens a suite, and only a blank line ends one: the body may hold
        // blank-looking constructs and further colons of its own.
        for (String line : lines) {
            if (codeOf(line, '#').stripTrailing().endsWith(":")) {
                return false;
            }
        }
        return true;
    }

    // ---- Julia ---------------------------------------------------------------

    private static boolean juliaComplete(List<String> lines) {
        Scan scan = scan(String.join("\n", lines), '#');
        if (scan.inString || scan.depth > 0) {
            return false;
        }
        int open = 0;
        for (String line : lines) {
            final String code = codeOf(line, '#').strip().toLowerCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            final String head = firstWord(code);
            if (JULIA_OPENERS.contains(head)) {
                open++;
            }
            if (code.equals("end") || code.startsWith("end ")) {
                open--;
            }
        }
        return open <= 0;
    }

    // ---- C++ -----------------------------------------------------------------

    private static boolean cppComplete(List<String> lines) {
        StringBuilder code = new StringBuilder();
        for (String line : lines) {
            final int slashes = line.indexOf("//");
            code.append(slashes < 0 ? line : line.substring(0, slashes)).append('\n');
        }
        final String joined = code.toString();
        Scan scan = scan(joined, '\0');
        if (scan.inString || scan.depth > 0 || scan.braces > 0) {
            return false;
        }
        final String last = lines.get(lines.size() - 1).strip();
        if (last.startsWith("#")) {
            return true;
        }
        return last.endsWith(";") || last.endsWith("}");
    }

    // ---- Fortran -------------------------------------------------------------

    private static boolean fortranComplete(List<String> lines) {
        int open = 0;
        for (String line : lines) {
            final String code = codeOf(line, '!').strip().toLowerCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            if (code.startsWith("end")) {
                open--;
                continue;
            }
            final String head = firstWord(code);
            if (!FORTRAN_OPENERS.contains(head)) {
                continue;
            }
            // "if (x) y = 1" acts on one line; only "then" opens a block. The same
            // holds for "type(kind) :: x", which declares rather than opens.
            if ("type".equals(head) && !code.startsWith("type ::")
                && !code.matches("type\\s*,.*")) {
                continue;
            }
            open++;
        }
        for (String line : lines) {
            final String code = codeOf(line, '!').strip().toLowerCase(Locale.ROOT);
            if (code.startsWith("if ") && code.endsWith("then")) {
                open++;
            }
        }
        return open <= 0;
    }

    // ---- reading the text ----------------------------------------------------

    /** What is left of a line once its comment is removed. */
    private static String codeOf(String line, char commentMark) {
        if (commentMark == '\0') {
            return line;
        }
        boolean single = false;
        boolean doubled = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '\'' && !doubled) {
                single = !single;
            } else if (c == '"' && !single) {
                doubled = !doubled;
            } else if (c == commentMark && !single && !doubled) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String firstWord(String code) {
        int at = 0;
        while (at < code.length() && Character.isLetter(code.charAt(at))) {
            at++;
        }
        return code.substring(0, at);
    }

    private record Scan(int depth, int braces, boolean inString) {}

    /**
     * Counts what is still open across the whole text: brackets, braces and
     * quotes, triple quotes included, so that a string holding a bracket does not
     * make the block look unfinished.
     */
    private static Scan scan(String text, char commentMark) {
        int depth = 0;
        int braces = 0;
        char quote = 0;
        boolean triple = false;
        int i = 0;
        while (i < text.length()) {
            final char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (triple && c == quote && i + 2 < text.length()
                    && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                    quote = 0;
                    triple = false;
                    i += 3;
                    continue;
                }
                if (!triple && c == quote) {
                    quote = 0;
                }
                if (!triple && c == '\n') {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == commentMark && commentMark != '\0') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                triple = i + 2 < text.length() && text.charAt(i + 1) == c
                      && text.charAt(i + 2) == c;
                i += triple ? 3 : 1;
                continue;
            }
            switch (c) {
                case '(', '[' -> depth++;
                case ')', ']' -> depth--;
                case '{' -> braces++;
                case '}' -> braces--;
                default -> { }
            }
            i++;
        }
        return new Scan(Math.max(depth, 0), Math.max(braces, 0), quote != 0);
    }
}
