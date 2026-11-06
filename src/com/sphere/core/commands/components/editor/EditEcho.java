package com.sphere.components.editor;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Position;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/**
 * Edit Echo: a change made to one line is offered to the other lines it fits.
 *
 * When a line has been edited (the caret leaves it, or typing pauses), the change
 * is read as a pattern: the tokens before and after are aligned (longest common
 * subsequence), each run of changed tokens becomes a rewrite anchored on the
 * unchanged tokens around it, and those anchors are generalised the way a reader
 * would: a variable stands for any name (bound, so "x = x + 1" only fits a line
 * that repeats its own name), a called function, a member, a keyword or a
 * namespace must be the same. "h1->Fill(x)" made "h1->Fill(x, w)" then fits
 * "h2->Fill(eta)", not "sqrt(x)".
 *
 * Every rule is chosen to be right when it speaks: a change with no fixed anchor
 * (only punctuation and variables), a pattern found twice on a line, or a match
 * inside a comment or a string is not offered. Nothing is applied without the
 * user: the lines it fits show what they would become, Alt+Enter applies them all
 * as one undo step, a click on a preview applies that one, Escape dismisses them.
 */
public final class EditEcho {

    /** What the editor around it shows: a bar while there are suggestions. */
    public interface Listener {
        void suggestionsChanged(int count);
    }

    // =========================================================================
    // Tokens
    // =========================================================================

    enum Kind { IDENT, NUMBER, STRING, PUNCT }

    /** A non-blank token of a line, with its character range. */
    record Tok(Kind kind, String text, int start, int end) {
    }

    private static final String[] OPERATORS = {
        "->*", "<<=", ">>=", "**=", "//=", "...", "->", "::", "==", "!=", "<=", ">=", "&&", "||", "++", "--",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "**", "//", ":=", "=>"
    };

    /** The tokens of a line, blanks skipped: names, numbers, strings, operators. */
    static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Tok(Kind.IDENT, s.substring(start, i), start, i));
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    char p = s.charAt(i - 1);
                    if (Character.isLetterOrDigit(d) || d == '.' || d == '_' || d == '\'') {
                        i++;
                    } else if ((d == '+' || d == '-') && (p == 'e' || p == 'E')
                               && !s.startsWith("0x", start) && !s.startsWith("0X", start)) {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Tok(Kind.NUMBER, s.substring(start, i), start, i));
                continue;
            }
            if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i = Math.min(n, i + 1);
                out.add(new Tok(Kind.STRING, s.substring(start, i), start, i));
                continue;
            }
            String op = null;
            for (String o : OPERATORS) {
                if (s.startsWith(o, i)) {
                    op = o;
                    break;
                }
            }
            if (op == null) {
                op = String.valueOf(c);
            }
            i += op.length();
            out.add(new Tok(Kind.PUNCT, op, start, i));
        }
        return out;
    }

    private static boolean same(Tok a, Tok b) {
        return a.kind == b.kind && a.text.equals(b.text);
    }

    /** Pairs {i, j} of equal tokens, in order, by longest common subsequence. */
    static List<int[]> align(List<Tok> a, List<Tok> b) {
        int n = a.size();
        int m = b.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = same(a.get(i), b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<int[]> pairs = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (same(a.get(i), b.get(j)) && dp[i][j] == dp[i + 1][j + 1] + 1) {
                pairs.add(new int[] { i, j });
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }

    // =========================================================================
    // Patterns
    // =========================================================================

    /** One token a match must find: exact text, a variable to bind, or a variable already bound. */
    record Slot(Kind kind, String text, int var, boolean backref) {

        boolean exact() {
            return var < 0;
        }

        boolean matches(Tok t, String[] bound) {
            if (var < 0) {
                return t.kind == kind && t.text.equals(text);
            }
            if (t.kind != kind) {
                return false;
            }
            return bound[var] == null ? !backref : bound[var].equals(t.text);
        }
    }

    /**
     * One rewrite: the tokens before the change (context, then the unchanged token
     * just before it, null at the start of the line), the tokens it removes, the
     * unchanged token just after it (null at the end of the line) and the context
     * after; what stands between the two unchanged tokens is replaced by the text
     * the edited line has there.
     */
    static final class Part {
        final List<Slot> left;
        final Slot l;
        final List<Slot> removed;
        final Slot r;
        final List<Slot> right;
        final String replacement;

        Part(List<Slot> left, Slot l, List<Slot> removed, Slot r, List<Slot> right, String replacement) {
            this.left = left;
            this.l = l;
            this.removed = removed;
            this.r = r;
            this.right = right;
            this.replacement = replacement;
        }

        int length() {
            return left.size() + (l == null ? 0 : 1) + removed.size() + (r == null ? 0 : 1) + right.size();
        }
    }

    /** A change read from one line, ready to be tried on others. */
    public static final class Pattern {
        final List<Part> parts;
        final String[] originals;
        final Set<String> required;

        Pattern(List<Part> parts, String[] originals, Set<String> required) {
            this.parts = parts;
            this.originals = originals;
            this.required = required;
        }

        /**
         * The line with the change made, or null when the pattern does not fit it
         * exactly once. {@code code} says whether a character of the line is code
         * (not a comment nor inside a string).
         */
        public String apply(String line, IntPredicate code) {
            for (String r : required) {
                if (!line.contains(r)) {
                    return null;
                }
            }
            List<Tok> toks = tokenize(line);
            if (toks.isEmpty()) {
                return null;
            }
            String[] bound = new String[originals.length];
            int from = 0;
            List<int[]> regions = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (Part p : parts) {
                int found = -1;
                String[] foundBound = null;
                // Context may reach back over the previous change; its own tokens may not.
                for (int s = Math.max(0, from - p.left.size()); s + p.length() <= toks.size(); s++) {
                    if (s + p.left.size() < from) {
                        continue;
                    }
                    String[] b = bound.clone();
                    if (matchAt(p, toks, s, b)) {
                        if (found >= 0) {
                            return null;                    // twice on the line: which one is meant?
                        }
                        found = s;
                        foundBound = b;
                    }
                }
                if (found < 0) {
                    return null;
                }
                bound = foundBound;
                int lIndex = found + p.left.size();
                int rIndex = lIndex + (p.l == null ? 0 : 1) + p.removed.size();
                for (int k = found; k < found + p.length(); k++) {
                    Tok t = toks.get(k);
                    if (t.kind != Kind.STRING && !code.test(t.start)) {
                        return null;                        // in a comment or a string
                    }
                }
                int start = p.l == null ? indentEnd(line) : toks.get(lIndex).end;
                int end = p.r == null ? contentEnd(line) : toks.get(rIndex).start;
                if (end < start) {
                    return null;
                }
                regions.add(new int[] { start, end });
                texts.add(substitute(p.replacement, bound));
                from = p.r == null ? toks.size() : rIndex;
            }
            StringBuilder out = new StringBuilder(line);
            for (int k = regions.size() - 1; k >= 0; k--) {
                out.replace(regions.get(k)[0], regions.get(k)[1], texts.get(k));
            }
            return out.toString();
        }

        private static boolean matchAt(Part p, List<Tok> toks, int s, String[] bound) {
            int k = s;
            if (p.l == null && s != 0) {
                return false;                               // anchored at the start of the line
            }
            for (Slot slot : p.left) {
                if (!bind(slot, toks.get(k++), bound)) {
                    return false;
                }
            }
            if (p.l != null && !bind(p.l, toks.get(k++), bound)) {
                return false;
            }
            for (Slot slot : p.removed) {
                if (!bind(slot, toks.get(k++), bound)) {
                    return false;
                }
            }
            if (p.r != null && !bind(p.r, toks.get(k++), bound)) {
                return false;
            }
            for (Slot slot : p.right) {
                if (!bind(slot, toks.get(k++), bound)) {
                    return false;
                }
            }
            return p.r != null || k == toks.size();         // anchored at the end of the line
        }

        private static boolean bind(Slot slot, Tok t, String[] bound) {
            if (!slot.matches(t, bound)) {
                return false;
            }
            if (slot.var >= 0 && bound[slot.var] == null) {
                bound[slot.var] = t.text;
            }
            return true;
        }

        /** The edited line's text, its variables renamed to what this line has in their place. */
        private String substitute(String text, String[] bound) {
            List<Tok> toks = tokenize(text);
            StringBuilder out = new StringBuilder();
            int at = 0;
            for (Tok t : toks) {
                for (int v = 0; v < originals.length; v++) {
                    if (bound[v] != null && t.kind != Kind.PUNCT && t.text.equals(originals[v])) {
                        out.append(text, at, t.start).append(bound[v]);
                        at = t.end;
                        break;
                    }
                }
            }
            return out.append(text.substring(at)).toString();
        }
    }

    private static int indentEnd(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int contentEnd(String line) {
        int i = line.length();
        while (i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * Reads the change from one line to its edited version as a pattern, or null
     * when it is not one to offer elsewhere: no change but blanks, more than three
     * separate changes, a rewrite of most of the line, or a change with nothing
     * fixed to recognise it by.
     */
    public static Pattern infer(String before, String after, LanguageSpec spec) {
        if (before.equals(after) || before.isBlank() || after.isBlank()) {
            return null;
        }
        List<Tok> a = tokenize(before);
        List<Tok> b = tokenize(after);
        if (a.isEmpty() || b.isEmpty() || a.size() > 160 || b.size() > 160) {
            return null;
        }
        List<int[]> pairs = align(a, b);
        List<int[]> anchors = new ArrayList<>();
        anchors.add(new int[] { -1, -1 });
        anchors.addAll(pairs);
        anchors.add(new int[] { a.size(), b.size() });
        // Runs of changed tokens between two unchanged ones.
        List<int[]> hunks = new ArrayList<>();
        for (int k = 0; k + 1 < anchors.size(); k++) {
            int[] p = anchors.get(k);
            int[] q = anchors.get(k + 1);
            if (q[0] - p[0] > 1 || q[1] - p[1] > 1) {
                hunks.add(new int[] { p[0], q[0], p[1], q[1] });
            }
        }
        if (hunks.isEmpty() || hunks.size() > 3) {
            return null;
        }
        int changed = 0;
        for (int[] h : hunks) {
            changed += (h[1] - h[0] - 1) + (h[3] - h[2] - 1);
        }
        if (changed > Math.max(6, (a.size() + b.size()) * 0.7)) {
            return null;                                    // a rewrite, not an edit to repeat
        }

        Map<String, Integer> vars = new HashMap<>();
        List<String> originals = new ArrayList<>();
        Set<String> required = new LinkedHashSet<>();
        List<Part> parts = new ArrayList<>();
        boolean fixedAnywhere = false;
        for (int[] h : hunks) {
            int li = h[0];
            int ri = h[1];
            // The unchanged neighbours first, so a removed token repeating one of them
            // is read as that same variable ("x = x + 1").
            Slot l = li >= 0 ? context(a, li, spec, vars, originals) : null;
            Slot r = ri < a.size() ? context(a, ri, spec, vars, originals) : null;
            List<Slot> removed = new ArrayList<>();
            boolean anchored = false;
            for (int k = li + 1; k < ri; k++) {
                Tok t = a.get(k);
                Integer v = t.kind == Kind.PUNCT ? null : vars.get(t.text);
                if (v != null) {
                    removed.add(new Slot(t.kind, t.text, v, true));
                    anchored = true;
                } else {
                    removed.add(new Slot(t.kind, t.text, -1, false));
                    if (t.kind != Kind.PUNCT) {
                        anchored = true;
                        fixedAnywhere = true;
                    }
                }
            }
            anchored |= isFixedName(l) || isFixedName(r);
            List<Slot> left = new ArrayList<>();
            List<Slot> right = new ArrayList<>();
            // Widened, a token at a time on each side, until something fixed is in view.
            for (int w = 1; w <= 3 && !anchored; w++) {
                if (li - w >= 0) {
                    Slot s = context(a, li - w, spec, vars, originals);
                    left.add(0, s);
                    anchored |= isFixedName(s);
                }
                if (!anchored && ri + w < a.size()) {
                    Slot s = context(a, ri + w, spec, vars, originals);
                    right.add(s);
                    anchored |= isFixedName(s);
                }
            }
            if (!anchored) {
                return null;
            }
            for (Slot s : left) {
                fixedAnywhere |= isFixedName(s);
            }
            fixedAnywhere |= isFixedName(l) || isFixedName(r);
            for (Slot s : right) {
                fixedAnywhere |= isFixedName(s);
            }
            // What the edited line has between the two unchanged tokens.
            int from = h[2] >= 0 ? b.get(h[2]).end : indentEnd(after);
            int to = h[3] < b.size() ? b.get(h[3]).start : contentEnd(after);
            String replacement = to >= from ? after.substring(from, to) : "";
            if (replacement.length() > 160) {
                return null;
            }
            parts.add(new Part(left, l, removed, r, right, replacement));
            for (Slot s : left) {
                requireExact(s, required);
            }
            requireExact(l, required);
            for (Slot s : removed) {
                requireExact(s, required);
            }
            requireExact(r, required);
            for (Slot s : right) {
                requireExact(s, required);
            }
        }
        if (!fixedAnywhere && !hasBackref(parts)) {
            return null;
        }
        return new Pattern(parts, originals.toArray(new String[0]), required);
    }

    private static boolean hasBackref(List<Part> parts) {
        for (Part p : parts) {
            for (Slot s : p.removed) {
                if (s.backref) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void requireExact(Slot s, Set<String> required) {
        if (s != null && s.exact() && s.kind != Kind.PUNCT) {
            required.add(s.text);
        }
    }

    private static boolean isFixedName(Slot s) {
        return s != null && s.exact() && s.kind == Kind.IDENT;
    }

    /**
     * A context token as a reader sees it: punctuation, keywords, builtins, a called
     * function, a member, a namespace are themselves; any other name, number or
     * string is a variable, the same original text being the same variable.
     */
    private static Slot context(List<Tok> line, int index, LanguageSpec spec, Map<String, Integer> vars,
                                List<String> originals) {
        Tok t = line.get(index);
        boolean fixed;
        if (t.kind == Kind.PUNCT) {
            fixed = true;
        } else if (t.kind == Kind.IDENT) {
            String next = index + 1 < line.size() ? line.get(index + 1).text : "";
            String prev = index > 0 ? line.get(index - 1).text : "";
            fixed = spec.isKeyword(t.text) || spec.types.contains(t.text) || spec.isBuiltin(t.text)
                    || spec.constants.contains(t.text)
                    || next.equals("(") || next.equals("::")
                    || prev.equals(".") || prev.equals("->") || prev.equals("::");
        } else {
            fixed = false;
        }
        if (fixed) {
            return new Slot(t.kind, t.text, -1, false);
        }
        Integer v = vars.get(t.text);
        if (v == null) {
            v = originals.size();
            originals.add(t.text);
            vars.put(t.text, v);
        }
        return new Slot(t.kind, t.text, v, false);
    }

    // =========================================================================
    // In the editor
    // =========================================================================

    /** A line the pattern fits: where it starts, what it reads, what it would read. */
    private record Suggestion(Position start, String before, String after, int from, int to) {
    }

    private final CodeTextPane pane;
    private final Supplier<LanguageSpec> language;
    private final SyntaxHighlighter highlighter;
    private Listener listener;
    private boolean enabled = true;
    /** Whether an edit is this view's own (typed in it), not another view's of the same buffer. */
    java.util.function.BooleanSupplier ownEdit;

    /** The text when the last edit settled: the line as it was before the change. */
    private String baseline;
    private int burstLine = -1;
    private boolean burstValid;
    private boolean applying;
    private boolean suspended;
    private final Timer pause;
    private final List<Suggestion> suggestions = new ArrayList<>();

    public EditEcho(CodeTextPane pane, SyntaxHighlighter highlighter, Supplier<LanguageSpec> language) {
        this.pane = pane;
        this.highlighter = highlighter;
        this.language = language;
        this.ownEdit = pane::isFocusOwner;
        this.pause = new Timer(650, e -> evaluate(false));
        this.pause.setRepeats(false);
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { edited(e, true); }
            @Override public void removeUpdate(DocumentEvent e) { edited(e, false); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        pane.addCaretListener(new CaretListener() {
            @Override
            public void caretUpdate(CaretEvent e) {
                if (burstLine != -1 && pane.lineAt(e.getDot()) != burstLine) {
                    // Deferred, so the edit that moved the caret is in the text read.
                    SwingUtilities.invokeLater(() -> {
                        if (burstLine != -1 && pane.lineAt(pane.getCaretPosition()) != burstLine) {
                            evaluate(true);
                        }
                    });
                }
            }
        });
        pane.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (burstLine == -1) {
                    settle();
                }
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            clear();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Edits that are not the user's own typing (loading, undo, a replace): no echo, a new start. */
    public void suspend(Runnable edits) {
        suspended = true;
        try {
            edits.run();
        } finally {
            suspended = false;
        }
        clear();
        settle();
    }

    /** The text as it is becomes the reference the next edit is read against. */
    public void settle() {
        baseline = pane.getText();
        burstLine = -1;
        burstValid = false;
    }

    private void edited(DocumentEvent e, boolean insert) {
        if (applying || suspended || !enabled) {
            return;
        }
        if (!ownEdit.getAsBoolean()) {
            // made in another view of the buffer: that view reads it
            baseline = null;
            burstLine = -1;
            clear();
            return;
        }
        int line = pane.lineAt(e.getOffset());
        boolean multiLine = false;
        if (insert) {
            try {
                multiLine = pane.getDocument().getText(e.getOffset(), e.getLength()).indexOf('\n') >= 0;
            } catch (BadLocationException ex) {
                multiLine = true;
            }
        }
        if (burstLine == -1) {
            burstLine = line;
            burstValid = baseline != null;
            if (!suggestions.isEmpty()) {
                clear();
            }
        } else if (line != burstLine) {
            burstValid = false;
        }
        if (multiLine) {
            burstValid = false;
        }
        pause.restart();
    }

    /** Reads the edit of the line, offers it to the lines it fits; final once the caret left the line. */
    private void evaluate(boolean last) {
        pause.stop();
        try {
            if (burstLine < 0 || !burstValid || baseline == null || !enabled) {
                return;
            }
            String now = pane.getText();
            int[] range = lineRange(baseline, burstLine);
            if (range == null) {
                return;
            }
            int suffix = baseline.length() - range[1];
            int nowEnd = now.length() - suffix;
            if (nowEnd < range[0] || !now.regionMatches(0, baseline, 0, range[0])
                    || !now.regionMatches(nowEnd, baseline, range[1], suffix)) {
                return;                                     // more than this line changed
            }
            String before = baseline.substring(range[0], range[1]);
            String after = now.substring(range[0], nowEnd);
            if (after.indexOf('\n') >= 0 || before.equals(after)) {
                clear();
                return;
            }
            int lineStart = range[0];
            int firstChange = 0;
            while (firstChange < before.length() && firstChange < after.length()
                   && before.charAt(firstChange) == after.charAt(firstChange)) {
                firstChange++;
            }
            if (!isCode(lineStart + Math.min(firstChange, Math.max(0, after.length() - 1)))) {
                clear();
                return;                                     // a comment or a string: prose, not code
            }
            Pattern pattern = infer(before, after, language.get());
            if (pattern == null) {
                clear();
                return;
            }
            offer(pattern, now, burstLine);
        } finally {
            if (last) {
                settle();
            }
        }
    }

    private boolean isCode(int offset) {
        TokenKind k = highlighter.liveKindAt(offset);
        return k != TokenKind.COMMENT && k != TokenKind.STRING && k != TokenKind.CHARACTER;
    }

    private static int[] lineRange(String text, int line) {
        int start = 0;
        for (int l = 0; l < line; l++) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                return null;
            }
            start = nl + 1;
        }
        int end = text.indexOf('\n', start);
        return new int[] { start, end < 0 ? text.length() : end };
    }

    private void offer(Pattern pattern, String text, int editedLine) {
        suggestions.clear();
        Document doc = pane.getDocument();
        int lineStart = 0;
        int line = 0;
        while (lineStart <= text.length() && suggestions.size() < 300) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl;
            if (line != editedLine && lineEnd - lineStart <= 600) {
                String before = text.substring(lineStart, lineEnd);
                final int base = lineStart;
                String after = pattern.apply(before, at -> isCode(base + at));
                if (after != null && !after.equals(before)) {
                    int from = 0;
                    while (from < before.length() && from < after.length() && before.charAt(from) == after.charAt(from)) {
                        from++;
                    }
                    int toB = before.length();
                    int toA = after.length();
                    while (toB > from && toA > from && before.charAt(toB - 1) == after.charAt(toA - 1)) {
                        toB--;
                        toA--;
                    }
                    try {
                        suggestions.add(new Suggestion(doc.createPosition(lineStart), before, after, from, toB));
                    } catch (BadLocationException ignored) {
                        // the line moved; it is not offered
                    }
                }
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
            line++;
        }
        repaint();
    }

    /** True while lines are offered the last edit. */
    public boolean hasSuggestions() {
        return !suggestions.isEmpty();
    }

    public int count() {
        return suggestions.size();
    }

    /** Takes the suggestions away (Escape, a new edit elsewhere). */
    public void clear() {
        if (!suggestions.isEmpty()) {
            suggestions.clear();
            repaint();
        }
    }

    /** Whether a suggestion still reads as it did when it was made (the line was not edited since). */
    private boolean valid(Suggestion s) {
        try {
            int start = s.start.getOffset();
            Document doc = pane.getDocument();
            if (start + s.before.length() > doc.getLength()) {
                return false;
            }
            return doc.getText(start, s.before.length()).equals(s.before)
                && (start + s.before.length() == doc.getLength()
                    || doc.getText(start + s.before.length(), 1).equals("\n"))
                && (start == 0 || doc.getText(start - 1, 1).equals("\n"));
        } catch (BadLocationException ex) {
            return false;
        }
    }

    /** Applies the edit to every line offered, as one undo step; the number of lines changed. */
    public int applyAll() {
        List<Suggestion> todo = new ArrayList<>();
        for (Suggestion s : suggestions) {
            if (valid(s)) {
                todo.add(s);
            }
        }
        todo.sort((x, y) -> Integer.compare(y.start.getOffset(), x.start.getOffset()));
        int caret = pane.getCaretPosition();
        applying = true;
        try {
            pane.atomic(() -> {
                for (Suggestion s : todo) {
                    replace(s);
                }
            });
        } finally {
            applying = false;
        }
        pane.setCaretPosition(Math.min(caret, pane.getDocument().getLength()));
        suggestions.clear();
        settle();
        repaint();
        return todo.size();
    }

    /** Applies the edit to the suggestion on a line (a click on its preview); true when one was there. */
    public boolean applyAt(int line) {
        for (Suggestion s : suggestions) {
            if (pane.lineAt(s.start.getOffset()) == line) {
                boolean ok = valid(s);
                if (ok) {
                    applying = true;
                    try {
                        pane.atomic(() -> replace(s));
                    } finally {
                        applying = false;
                    }
                }
                suggestions.remove(s);
                if (burstLine == -1) {
                    baseline = pane.getText();
                }
                repaint();
                return ok;
            }
        }
        return false;
    }

    private void replace(Suggestion s) {
        Document doc = pane.getDocument();
        int start = s.start.getOffset();
        String text = s.after.substring(s.from, s.after.length() - (s.before.length() - s.to));
        try {
            doc.remove(start + s.from, s.to - s.from);
            doc.insertString(start + s.from, text, null);
        } catch (BadLocationException ignored) {
            // the line changed under it; left as it is
        }
    }

    /** Moves the caret to the next (or previous) line offered the edit; false when there is none. */
    public boolean go(int direction) {
        List<Integer> lines = new ArrayList<>();
        for (Suggestion s : suggestions) {
            if (valid(s)) {
                lines.add(pane.lineAt(s.start.getOffset()));
            }
        }
        if (lines.isEmpty()) {
            return false;
        }
        lines.sort(Integer::compare);
        int caretLine = pane.lineAt(pane.getCaretPosition());
        int target = direction > 0 ? lines.get(0) : lines.get(lines.size() - 1);
        for (int k = 0; k < lines.size(); k++) {
            int l = direction > 0 ? lines.get(k) : lines.get(lines.size() - 1 - k);
            if (direction > 0 ? l > caretLine : l < caretLine) {
                target = l;
                break;
            }
        }
        int offset = pane.lineStartOffset(target);
        String text = pane.lineText(target);
        int lead = 0;
        while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) {
            lead++;
        }
        // Leaving the edited line ends the edit; the offer stays, it is not an edit.
        pane.setCaretPosition(offset + lead);
        return true;
    }

    /** The previews painted over the code: the part that changes, and the line as it would read. */
    private void repaint() {
        List<CodeTextPane.Ghost> ghosts = new ArrayList<>();
        for (Suggestion s : suggestions) {
            if (!valid(s)) {
                continue;
            }
            int start = s.start.getOffset();
            ghosts.add(new CodeTextPane.Ghost(start + s.from, start + s.to, s.after.strip()));
        }
        pane.setGhosts(ghosts);
        if (listener != null) {
            listener.suggestionsChanged(ghosts.size());
        }
    }
}
