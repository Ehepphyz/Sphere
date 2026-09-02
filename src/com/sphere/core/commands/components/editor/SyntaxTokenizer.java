package com.sphere.components.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-pass lexer over a text region. Emits only the spans that are not plain
 * text, so the highlighter has little to apply.
 */
public final class SyntaxTokenizer {

    /** One coloured region, offsets relative to the text handed to tokenize(). */
    public static final class Span {
        public final int start;
        public final int end;
        public final TokenKind kind;

        Span(int start, int end, TokenKind kind) {
            this.start = start;
            this.end = end;
            this.kind = kind;
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

        final int n = text.length();
        int i = 0;
        boolean inBlockComment = insideComment;
        boolean atLineStart = true;

        while (i < n) {
            char c = text.charAt(i);

            if (inBlockComment) {
                int close = spec.blockCommentClose == null
                        ? -1 : text.indexOf(spec.blockCommentClose, i);
                if (close < 0) {
                    out.add(new Span(i, n, TokenKind.COMMENT));
                    return true;
                }
                int end = close + spec.blockCommentClose.length();
                out.add(new Span(i, end, TokenKind.COMMENT));
                i = end;
                inBlockComment = false;
                continue;
            }

            if (c == '\n') {
                atLineStart = true;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }

            // Block comment opener.
            if (spec.blockCommentOpen != null && text.startsWith(spec.blockCommentOpen, i)) {
                inBlockComment = true;
                continue;
            }

            // Line comment.
            if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                int eol = text.indexOf('\n', i);
                int end = eol < 0 ? n : eol;
                out.add(new Span(i, end, TokenKind.COMMENT));
                i = end;
                continue;
            }

            // Preprocessor line, only when it opens the line.
            if (spec.hasPreprocessor && c == '#' && atLineStart) {
                int eol = text.indexOf('\n', i);
                int end = eol < 0 ? n : eol;
                out.add(new Span(i, end, TokenKind.PREPROCESSOR));
                i = end;
                continue;
            }

            atLineStart = false;

            // Decorator or macro call.
            if (spec.hasDecorators && c == '@') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j))
                        || text.charAt(j) == '_' || text.charAt(j) == '.')) {
                    j++;
                }
                out.add(new Span(i, j, TokenKind.DECORATOR));
                i = j;
                continue;
            }

            // Strings and characters, with escapes and triple quotes.
            if (c == '"' || c == '\'') {
                i = scanQuoted(text, i, c, out);
                continue;
            }

            // Numbers, including hex, binary and exponents.
            if (Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j))
                        || text.charAt(j) == '.' || text.charAt(j) == '_'
                        || ((text.charAt(j) == '+' || text.charAt(j) == '-')
                            && j > i && isExponent(text.charAt(j - 1))))) {
                    j++;
                }
                out.add(new Span(i, j, TokenKind.NUMBER));
                i = j;
                continue;
            }

            // Identifiers.
            if (Character.isJavaIdentifierStart(c)) {
                int j = i;
                while (j < n && Character.isJavaIdentifierPart(text.charAt(j))) {
                    j++;
                }
                String word = text.substring(i, j);
                if (spec.keywords.contains(word)) {
                    out.add(new Span(i, j, TokenKind.KEYWORD));
                } else if (spec.types.contains(word)) {
                    out.add(new Span(i, j, TokenKind.TYPE));
                } else if (isCallSite(text, j, n)) {
                    out.add(new Span(i, j, TokenKind.FUNCTION));
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
        return inBlockComment;
    }

    private static boolean isExponent(char c) {
        return c == 'e' || c == 'E' || c == 'p' || c == 'P';
    }

    private static boolean isOperator(char c) {
        return "+-*/%=<>!&|^~?:;,.".indexOf(c) >= 0;
    }

    /** An identifier immediately followed by '(' reads as a call. */
    private static boolean isCallSite(String text, int from, int n) {
        int k = from;
        while (k < n && (text.charAt(k) == ' ' || text.charAt(k) == '\t')) {
            k++;
        }
        return k < n && text.charAt(k) == '(';
    }

    private int scanQuoted(String text, int start, char quote, List<Span> out) {
        final int n = text.length();
        TokenKind kind = (quote == '"' || spec.singleQuoteIsString)
                ? TokenKind.STRING : TokenKind.CHARACTER;

        // Triple quotes swallow newlines; a single quote never does.
        boolean triple = start + 2 < n
                && text.charAt(start + 1) == quote && text.charAt(start + 2) == quote;
        int i = start + (triple ? 3 : 1);

        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (!triple && c == '\n') {
                break; // unterminated: stop at the line so one typo cannot recolour the file
            }
            if (c == quote) {
                if (!triple) {
                    i++;
                    break;
                }
                if (i + 2 < n && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                    i += 3;
                    break;
                }
            }
            i++;
        }
        int end = Math.min(i, n);
        out.add(new Span(start, end, kind));
        return end;
    }

    /** Convenience for a whole buffer. */
    public List<Span> tokenizeAll(String text) {
        List<Span> spans = new ArrayList<>();
        tokenize(text, false, spans);
        return spans;
    }
}
