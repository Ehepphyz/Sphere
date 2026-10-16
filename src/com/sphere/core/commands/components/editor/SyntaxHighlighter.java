package com.sphere.components.editor;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tokenizes the buffer off the event thread and keeps the result as arrays the
 * view paints from. Nothing is written into the document: colors used to be
 * character attributes, and every one of them was an undoable edit, which is why
 * Undo needed several presses to undo one keystroke.
 *
 * Between a keystroke and the rescan that follows it, the spans are shifted
 * with the text so colors never jump while typing.
 */
public final class SyntaxHighlighter implements DocumentListener {

    /** Beyond this the file is left uncolored rather than freezing the editor. */
    private static final int MAX_HIGHLIGHT_CHARS = 4 * 1024 * 1024;

    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sphere-syntax");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final int[] NO_INTS = new int[0];
    private static final TokenKind[] NO_KINDS = new TokenKind[0];

    /** Receives each scan once it is in place. */
    public interface Listener {
        void highlighted(Snapshot snapshot);
    }

    /**
     * What one scan found, for the services that read the code: the text it
     * read, its spans and its brackets. Immutable.
     */
    public static final class Snapshot {
        public final long revision;
        public final LanguageSpec spec;
        public final String text;
        public final int[] starts;
        public final int[] ends;
        public final TokenKind[] kinds;
        public final boolean[] unterminated;
        public final int count;
        /** Bracket offsets, ascending. */
        public final int[] bracketPos;
        public final char[] bracketChar;
        /** Index of the partner in bracketPos, or -1 when there is none. */
        public final int[] bracketPartner;
        public final int[] bracketDepth;
        public final int bracketCount;

        Snapshot(long revision, LanguageSpec spec, String text, int[] starts, int[] ends,
                 TokenKind[] kinds, boolean[] unterminated, int count, int[] bracketPos,
                 char[] bracketChar, int[] bracketPartner, int[] bracketDepth, int bracketCount) {
            this.revision = revision;
            this.spec = spec;
            this.text = text;
            this.starts = starts;
            this.ends = ends;
            this.kinds = kinds;
            this.unterminated = unterminated;
            this.count = count;
            this.bracketPos = bracketPos;
            this.bracketChar = bracketChar;
            this.bracketPartner = bracketPartner;
            this.bracketDepth = bracketDepth;
            this.bracketCount = bracketCount;
        }

        static Snapshot empty(LanguageSpec spec, String text) {
            return new Snapshot(0, spec, text, NO_INTS, NO_INTS, NO_KINDS, new boolean[0], 0,
                                NO_INTS, new char[0], NO_INTS, NO_INTS, 0);
        }

        /** Index of the span covering offset, or -1. */
        public int spanAt(int offset) {
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (ends[mid] <= offset) {
                    lo = mid + 1;
                } else if (starts[mid] > offset) {
                    hi = mid - 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }

        public TokenKind kindAt(int offset) {
            int i = spanAt(offset);
            return i < 0 ? TokenKind.DEFAULT : kinds[i];
        }

        /** True outside strings, comments and directives. */
        public boolean isCodeAt(int offset) {
            return !kindAt(offset).isLiteralOrComment();
        }

        /** Index in bracketPos of the bracket at offset, or -1. */
        public int bracketAt(int offset) {
            int i = Arrays.binarySearch(bracketPos, 0, bracketCount, offset);
            return i >= 0 ? i : -1;
        }

        /** The innermost bracket pair around offset as {open, close} offsets, or null. */
        public int[] enclosingPair(int offset) {
            int best = -1;
            int lo = 0;
            int hi = bracketCount - 1;
            int last = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (bracketPos[mid] < offset) {
                    last = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            int depthNeeded = 0;
            for (int i = last; i >= 0; i--) {
                char c = bracketChar[i];
                if (c == ')' || c == ']' || c == '}') {
                    if (bracketPartner[i] >= 0) {
                        depthNeeded++;
                    }
                } else if (bracketPartner[i] >= 0) {
                    if (depthNeeded == 0) {
                        best = i;
                        break;
                    }
                    depthNeeded--;
                }
            }
            if (best < 0) {
                return null;
            }
            return new int[] { bracketPos[best], bracketPos[bracketPartner[best]] };
        }
    }

    private final JTextComponent pane;
    private final Document doc;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final Timer debounce;

    private LanguageSpec spec;
    private SyntaxTokenizer tokenizer;
    private boolean enabled = true;
    private boolean suspended;
    private boolean pending;

    // Live spans, event thread only.
    private int[] starts = NO_INTS;
    private int[] ends = NO_INTS;
    private TokenKind[] kinds = NO_KINDS;
    private int count;

    private volatile Snapshot snapshot;

    public SyntaxHighlighter(JTextComponent pane, LanguageSpec spec) {
        this.pane = pane;
        this.doc = pane.getDocument();
        this.spec = spec == null ? LanguageSpec.NONE : spec;
        this.tokenizer = new SyntaxTokenizer(this.spec);
        this.snapshot = Snapshot.empty(this.spec, "");
        this.debounce = new Timer(25, e -> scan());
        this.debounce.setRepeats(false);
        doc.addDocumentListener(this);
        // The code surface paints from this highlighter; callers that only build
        // one (the ROOT pipeline window) get their colors without wiring it.
        if (pane instanceof CodeTextPane p) {
            p.setSyntax(this);
            p.setLanguage(this.spec);
        }
        rehighlightAll();
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setLanguage(LanguageSpec newSpec) {
        this.spec = newSpec == null ? LanguageSpec.NONE : newSpec;
        this.tokenizer = new SyntaxTokenizer(this.spec);
        if (pane instanceof CodeTextPane p) {
            p.setLanguage(this.spec);
        }
        rehighlightAll();
    }

    public LanguageSpec getLanguage() {
        return spec;
    }

    /** Colors are read at paint time, so a theme change only needs a repaint. */
    public void refreshTheme() {
        pane.repaint();
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (value) {
            rehighlightAll();
        } else {
            count = 0;
            pane.repaint();
        }
    }

    /**
     * Holds scans back while a batch of edits runs (Replace All), then scans once.
     */
    public void setSuspended(boolean value) {
        this.suspended = value;
        if (!value && pending) {
            pending = false;
            rehighlightAll();
        }
    }

    public void rehighlightAll() {
        debounce.stop();
        scan();
    }

    /** The last scan, possibly a keystroke behind the buffer. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** True when the last scan read the buffer as it is now. */
    public boolean isCurrent() {
        Snapshot s = snapshot;
        return s != null && s.revision == revision.get() && s.text.length() == doc.getLength();
    }

    // ---- Painting access (event thread) -----------------------------------

    public int spanCount() {
        return count;
    }

    public int spanStart(int i) {
        return starts[i];
    }

    public int spanEnd(int i) {
        return ends[i];
    }

    public TokenKind spanKind(int i) {
        return kinds[i];
    }

    /** Index of the first span ending after offset (count when none). */
    public int firstSpanAfter(int offset) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] <= offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The token kind painted at offset right now. */
    public TokenKind liveKindAt(int offset) {
        int i = firstSpanAfter(offset);
        if (i < count && starts[i] <= offset && ends[i] > offset) {
            return kinds[i];
        }
        return TokenKind.DEFAULT;
    }

    // ---- DocumentListener --------------------------------------------------

    @Override
    public void insertUpdate(DocumentEvent e) {
        shiftInsert(e.getOffset(), e.getLength());
        schedule();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        shiftRemove(e.getOffset(), e.getLength());
        schedule();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        // attribute-only
    }

    private void shiftInsert(int offset, int length) {
        for (int i = firstSpanAfter(offset - 1 < 0 ? 0 : offset - 1); i < count; i++) {
            if (starts[i] >= offset) {
                starts[i] += length;
                ends[i] += length;
            } else if (ends[i] > offset) {
                ends[i] += length;
            }
        }
    }

    private void shiftRemove(int offset, int length) {
        int end = offset + length;
        for (int i = firstSpanAfter(offset); i < count; i++) {
            int s = starts[i];
            int e = ends[i];
            starts[i] = s >= end ? s - length : Math.min(s, offset);
            ends[i] = e >= end ? e - length : Math.min(e, offset);
            if (ends[i] < starts[i]) {
                ends[i] = starts[i];
            }
        }
    }

    private void schedule() {
        revision.incrementAndGet();
        if (suspended) {
            pending = true;
            return;
        }
        int length = doc.getLength();
        debounce.setInitialDelay(length < 100_000 ? 20 : length < 1_000_000 ? 80 : 200);
        debounce.restart();
    }

    // ---- Scan --------------------------------------------------------------

    private void scan() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::scan);
            return;
        }
        final int length = doc.getLength();
        final String text;
        try {
            text = doc.getText(0, length);
        } catch (BadLocationException ex) {
            return;
        }
        final long stamp = revision.get();
        final LanguageSpec scanSpec = spec;
        final SyntaxTokenizer scanTokenizer = tokenizer;
        final boolean colorize = enabled && length <= MAX_HIGHLIGHT_CHARS;

        SCANNER.execute(() -> {
            Snapshot result;
            try {
                result = analyze(stamp, scanSpec, scanTokenizer, text, colorize);
            } catch (RuntimeException ex) {
                result = Snapshot.empty(scanSpec, text);
            }
            final Snapshot done = result;
            SwingUtilities.invokeLater(() -> install(done));
        });
    }

    private void install(Snapshot s) {
        if (s.revision != revision.get() || s.text.length() != doc.getLength()) {
            return; // a newer edit superseded this scan; its own scan is on the way
        }
        starts = Arrays.copyOf(s.starts, s.count);
        ends = Arrays.copyOf(s.ends, s.count);
        kinds = Arrays.copyOf(s.kinds, s.count);
        count = s.count;
        snapshot = s;
        pane.repaint();
        for (Listener l : listeners) {
            try {
                l.highlighted(s);
            } catch (RuntimeException ex) {
                // one failing listener must not keep the others from the result
            }
        }
    }

    /** Tokens, then brackets in the code between them, merged into one sorted list. */
    static Snapshot analyze(long stamp, LanguageSpec spec, SyntaxTokenizer tokenizer,
                            String text, boolean colorize) {
        List<SyntaxTokenizer.Span> spans = new ArrayList<>();
        if (colorize) {
            tokenizer.tokenize(text, false, spans);
        }

        // Brackets.
        final boolean bracesOnly = spec == LanguageSpec.LATEX;
        final boolean rainbow = colorize && (spec.dialect == LanguageSpec.Dialect.CODE
                                             || spec.dialect == LanguageSpec.Dialect.LATEX);
        final boolean skipDirectives = spec.hasPreprocessor;
        int cap = Math.max(16, text.length() / 8);
        int[] bPos = new int[Math.min(cap, 1 << 16)];
        char[] bChar = new char[bPos.length];
        int[] bPartner = new int[bPos.length];
        int[] bDepth = new int[bPos.length];
        int bCount = 0;
        int[] stack = new int[256];
        int sp = 0;

        final int n = text.length();
        int si = 0;
        boolean lineIsDirective = false;
        boolean atLineStart = true;
        for (int p = 0; p < n; p++) {
            // Skip literals and comments.
            while (si < spans.size() && spans.get(si).end <= p) {
                si++;
            }
            if (si < spans.size()) {
                SyntaxTokenizer.Span s = spans.get(si);
                if (s.start <= p && s.kind.isLiteralOrComment()) {
                    if (s.kind == TokenKind.PREPROCESSOR && skipDirectives && atLineStart) {
                        lineIsDirective = true;
                    }
                    p = s.end - 1;
                    atLineStart = false;
                    continue;
                }
            }
            char c = text.charAt(p);
            if (c == '\n') {
                lineIsDirective = false;
                atLineStart = true;
                continue;
            }
            if (c != ' ' && c != '\t' && c != '\r') {
                atLineStart = false;
            }
            if (lineIsDirective) {
                continue;
            }
            boolean opener = bracesOnly ? c == '{' : (c == '(' || c == '[' || c == '{');
            boolean closer = bracesOnly ? c == '}' : (c == ')' || c == ']' || c == '}');
            if (!opener && !closer) {
                continue;
            }
            if (bCount == bPos.length) {
                int grow = bPos.length * 2;
                bPos = Arrays.copyOf(bPos, grow);
                bChar = Arrays.copyOf(bChar, grow);
                bPartner = Arrays.copyOf(bPartner, grow);
                bDepth = Arrays.copyOf(bDepth, grow);
            }
            int idx = bCount++;
            bPos[idx] = p;
            bChar[idx] = c;
            bPartner[idx] = -1;
            if (opener) {
                bDepth[idx] = sp;
                if (sp == stack.length) {
                    stack = Arrays.copyOf(stack, sp * 2);
                }
                stack[sp++] = idx;
            } else {
                if (sp > 0 && matches(bChar[stack[sp - 1]], c)) {
                    int open = stack[--sp];
                    bPartner[idx] = open;
                    bPartner[open] = idx;
                    bDepth[idx] = sp;
                } else {
                    bDepth[idx] = sp;
                }
            }
        }

        // Merge bracket spans into the token spans.
        int tokenCount = spans.size();
        int total = tokenCount + (rainbow ? bCount : 0);
        int[] starts = new int[total];
        int[] ends = new int[total];
        TokenKind[] kinds = new TokenKind[total];
        boolean[] unterminated = new boolean[total];
        int out = 0;
        int ti = 0;
        int bi = 0;
        while (ti < tokenCount || (rainbow && bi < bCount)) {
            boolean takeBracket = rainbow && bi < bCount
                    && (ti >= tokenCount || bPos[bi] < spans.get(ti).start);
            if (takeBracket) {
                starts[out] = bPos[bi];
                ends[out] = bPos[bi] + 1;
                kinds[out] = bracketKind(bPartner[bi] < 0, bDepth[bi], spec);
                out++;
                bi++;
            } else {
                SyntaxTokenizer.Span s = spans.get(ti++);
                if (s.end <= s.start) {
                    continue;
                }
                starts[out] = s.start;
                ends[out] = s.end;
                kinds[out] = s.kind;
                unterminated[out] = s.unterminated;
                out++;
            }
        }
        return new Snapshot(stamp, spec, text, starts, ends, kinds, unterminated, out,
                            Arrays.copyOf(bPos, bCount), Arrays.copyOf(bChar, bCount),
                            Arrays.copyOf(bPartner, bCount), Arrays.copyOf(bDepth, bCount), bCount);
    }

    private static TokenKind bracketKind(boolean unmatched, int depth, LanguageSpec spec) {
        if (unmatched) {
            return spec.bracketLint ? TokenKind.BRACKET_BAD : TokenKind.OPERATOR;
        }
        switch (depth % 3) {
            case 0:  return TokenKind.BRACKET_1;
            case 1:  return TokenKind.BRACKET_2;
            default: return TokenKind.BRACKET_3;
        }
    }

    static boolean matches(char open, char close) {
        return (open == '(' && close == ')') || (open == '[' && close == ']')
            || (open == '{' && close == '}');
    }

    public void dispose() {
        debounce.stop();
        doc.removeDocumentListener(this);
        listeners.clear();
    }
}
