package com.sphere.components.editor;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Colours a JTextPane from a LanguageSpec. Only the lines touched by an edit are
 * rescanned, and the scan runs off the event thread so a large file does not
 * stall typing.
 */
public final class SyntaxHighlighter implements DocumentListener {

    /** Beyond this the file is left uncoloured rather than freezing the editor. */
    private static final int MAX_HIGHLIGHT_CHARS = 4 * 1024 * 1024;

    private static final ExecutorService SCANNER =
        Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sphere-syntax");
                t.setDaemon(true);
                return t;
            }
        });

    private final JTextPane pane;
    private final StyledDocument doc;
    private final Map<TokenKind, AttributeSet> styles = new EnumMap<>(TokenKind.class);
    private final AtomicLong revision = new AtomicLong();

    private LanguageSpec spec;
    private SyntaxTokenizer tokenizer;
    private boolean enabled = true;

    public SyntaxHighlighter(JTextPane pane, LanguageSpec spec) {
        this.pane = pane;
        this.doc = pane.getStyledDocument();
        this.spec = spec == null ? LanguageSpec.NONE : spec;
        this.tokenizer = new SyntaxTokenizer(this.spec);
        buildStyles();
        doc.addDocumentListener(this);
    }

    private void buildStyles() {
        styles.clear();
        for (TokenKind kind : TokenKind.values()) {
            SimpleAttributeSet set = new SimpleAttributeSet();
            StyleConstants.setForeground(set, EditorTheme.token(kind));
            if (kind == TokenKind.KEYWORD || kind == TokenKind.PREPROCESSOR) {
                StyleConstants.setBold(set, true);
            }
            if (kind == TokenKind.COMMENT) {
                StyleConstants.setItalic(set, true);
            }
            styles.put(kind, set);
        }
    }

    public void setLanguage(LanguageSpec newSpec) {
        this.spec = newSpec == null ? LanguageSpec.NONE : newSpec;
        this.tokenizer = new SyntaxTokenizer(this.spec);
        rehighlightAll();
    }

    public LanguageSpec getLanguage() {
        return spec;
    }

    /** Rebuilds the styles after a theme change and repaints. */
    public void refreshTheme() {
        buildStyles();
        rehighlightAll();
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
        if (value) {
            rehighlightAll();
        } else {
            applyClear(0, doc.getLength());
        }
    }

    public void rehighlightAll() {
        scheduleScan(0, doc.getLength());
    }

    // ---- DocumentListener --------------------------------------------------

    @Override public void insertUpdate(DocumentEvent e) { scheduleAround(e); }
    @Override public void removeUpdate(DocumentEvent e) { scheduleAround(e); }
    @Override public void changedUpdate(DocumentEvent e) { /* attribute-only */ }

    private void scheduleAround(DocumentEvent e) {
        int from = e.getOffset();
        int to = e.getOffset() + e.getLength();
        scheduleScan(from, to);
    }

    /**
     * Widens the range to whole lines, and back to the last block-comment opener
     * so an edit inside a comment cannot leave the rest of the file miscoloured.
     */
    private void scheduleScan(int from, int to) {
        if (!enabled || spec.isPlain()) {
            return;
        }
        final int length = doc.getLength();
        if (length > MAX_HIGHLIGHT_CHARS) {
            return;
        }

        final String text;
        try {
            text = doc.getText(0, length);
        } catch (BadLocationException ex) {
            return;
        }

        int start = lineStart(text, Math.max(0, Math.min(from, length)));
        int end = lineEnd(text, Math.max(0, Math.min(to, length)));
        if (spec.blockCommentOpen != null) {
            int opener = text.lastIndexOf(spec.blockCommentOpen, start);
            if (opener >= 0) {
                start = lineStart(text, opener);
            }
        }

        final int scanStart = start;
        final int scanEnd = Math.max(start, end);
        final long stamp = revision.incrementAndGet();
        final String region = text.substring(scanStart, scanEnd);

        SCANNER.execute(() -> {
            List<SyntaxTokenizer.Span> spans = new ArrayList<>();
            tokenizer.tokenize(region, false, spans);
            if (stamp != revision.get()) {
                return; // a newer edit already superseded this scan
            }
            SwingUtilities.invokeLater(() -> {
                if (stamp != revision.get() || scanEnd > doc.getLength()) {
                    return;
                }
                applyClear(scanStart, scanEnd - scanStart);
                for (SyntaxTokenizer.Span span : spans) {
                    AttributeSet style = styles.get(span.kind);
                    if (style == null) {
                        continue;
                    }
                    int s = scanStart + span.start;
                    int len = span.end - span.start;
                    if (s >= 0 && len > 0 && s + len <= doc.getLength()) {
                        doc.setCharacterAttributes(s, len, style, false);
                    }
                }
            });
        });
    }

    private void applyClear(int offset, int length) {
        if (length <= 0) {
            return;
        }
        AttributeSet plain = styles.get(TokenKind.DEFAULT);
        if (plain != null) {
            doc.setCharacterAttributes(offset, length, plain, true);
        }
    }

    private static int lineStart(String text, int offset) {
        int i = text.lastIndexOf('\n', Math.max(0, offset - 1));
        return i < 0 ? 0 : i + 1;
    }

    private static int lineEnd(String text, int offset) {
        int i = text.indexOf('\n', Math.min(offset, text.length()));
        return i < 0 ? text.length() : i + 1;
    }

    public void dispose() {
        doc.removeDocumentListener(this);
    }

    JTextPane pane() {
        return pane;
    }
}
