package com.sphere.components.editor;

import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Position;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The text of one file and what belongs to it rather than to a view of it: the
 * document, its undo history, its breakpoints, the encoding and line endings it
 * is written with, and whether it differs from the disk. Every editor showing
 * the file (one per split group) shares the buffer, so an edit, an undo or a
 * save in one of them is seen in all of them.
 */
public final class TextBuffer implements DocumentListener {

    final Document document;
    final SmartUndoManager undo;
    final BreakpointModel breakpoints = new BreakpointModel();
    private final List<CodeTab> views = new ArrayList<>();

    File file;
    boolean modified;
    boolean loading;
    Charset charset = StandardCharsets.UTF_8;
    /** The file began with a UTF-8 byte order mark, which a save writes back. */
    boolean bom;
    String lineSeparator = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";
    long knownModified = -1;
    long knownSize = -1;
    boolean diskConflictShown;
    /** The text as last loaded or saved, which the change bars compare against. */
    String savedText = "";
    /** Set when the encoding or line endings changed: the text alone no longer says "saved". */
    boolean formatDirty;
    private int lastLineCount = 1;

    TextBuffer(Document document, File file) {
        this.document = document;
        this.file = file;
        this.undo = new SmartUndoManager(document);
        document.addUndoableEditListener(undo);
        // Added before any view's listeners, so it runs after them: a view
        // reading isModified() in its own listener sees the state before the
        // keystroke, and the views are told of the change below.
        document.addDocumentListener(this);
    }

    // ---- Views -------------------------------------------------------------

    void attach(CodeTab view) {
        if (!views.contains(view)) {
            views.add(view);
        }
    }

    void detach(CodeTab view) {
        views.remove(view);
    }

    /** Every editor showing this buffer, the first opened first. */
    public List<CodeTab> views() {
        return List.copyOf(views);
    }

    /** True while two or more editors show this buffer. */
    public boolean isShared() {
        return views.size() > 1;
    }

    public File getFile() {
        return file;
    }

    public boolean isModified() {
        return modified;
    }

    public BreakpointModel getBreakpoints() {
        return breakpoints;
    }

    /** The views other than the one given. */
    List<CodeTab> others(CodeTab view) {
        List<CodeTab> out = new ArrayList<>(views);
        out.remove(view);
        return out;
    }

    void setModified(boolean value) {
        if (modified != value) {
            modified = value;
            for (CodeTab v : views()) {
                v.bufferStateChanged();
            }
        }
    }

    // ---- Loading -----------------------------------------------------------

    /** Replacing the whole text is not an edit: no undo step, no dirty flag. */
    void beginLoading() {
        loading = true;
        document.removeUndoableEditListener(undo);
    }

    /** @param replaced true when the text was replaced, which ends the old history. */
    void endLoading(boolean replaced) {
        document.addUndoableEditListener(undo);
        if (replaced) {
            undo.discardAllEdits();
        }
        lastLineCount = document.getDefaultRootElement().getElementCount();
        loading = false;
    }

    /**
     * After an undo or redo: back at the saved text means not modified, as in
     * any editor worth the name. Only compared when the lengths already agree.
     */
    void recheckSaved() {
        if (!modified || formatDirty || file == null || savedText == null
                || document.getLength() != savedText.length()) {
            return;
        }
        try {
            if (document.getText(0, document.getLength()).equals(savedText)) {
                setModified(false);
            }
        } catch (BadLocationException ignored) {
            // still modified
        }
    }

    // ---- DocumentListener --------------------------------------------------

    @Override
    public void insertUpdate(DocumentEvent e) {
        onEdit(e);
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        onEdit(e);
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        // attribute-only
    }

    /** Once per edit, whichever view made it: breakpoints follow their lines. */
    private void onEdit(DocumentEvent e) {
        int lines = document.getDefaultRootElement().getElementCount();
        int delta = lines - lastLineCount;
        lastLineCount = lines;
        if (loading) {
            return;
        }
        if (delta != 0) {
            int line = document.getDefaultRootElement().getElementIndex(e.getOffset());
            int lineStart = document.getDefaultRootElement().getElement(line).getStartOffset();
            boolean atLineStart = lineStart == e.getOffset();
            breakpoints.shift(line + (atLineStart ? 1 : 2), delta);
        }
        setModified(true);
    }

    // ---- Keeping the other views where they were ---------------------------

    /**
     * Runs a change made from one view (an undo, a reload) while the other views
     * keep their caret, selection and scroll. Swing moves every caret to the
     * place an undo touched, which in a split would yank the other side away
     * from what it shows.
     */
    void keepOtherViews(CodeTab origin, Runnable change) {
        List<CodeTab> others = others(origin);
        if (others.isEmpty()) {
            change.run();
            return;
        }
        List<Object[]> saved = new ArrayList<>();
        for (CodeTab v : others) {
            saved.add(new Object[] { v, Anchor.of(v) });
        }
        try {
            change.run();
        } finally {
            for (Object[] s : saved) {
                ((Anchor) s[1]).restore((CodeTab) s[0]);
            }
        }
    }

    /** A view's caret, selection and first visible character, tracking edits. */
    private static final class Anchor {
        private final Position dot;
        private final Position mark;
        private final Position top;
        private final int dotOffset;
        private final int markOffset;
        private final int scrollY;
        private final int scrollX;

        private Anchor(Position dot, Position mark, Position top, int dotOffset, int markOffset,
                       int scrollY, int scrollX) {
            this.dot = dot;
            this.mark = mark;
            this.top = top;
            this.dotOffset = dotOffset;
            this.markOffset = markOffset;
            this.scrollY = scrollY;
            this.scrollX = scrollX;
        }

        static Anchor of(CodeTab view) {
            CodeTextPane area = view.getEditorArea();
            Document doc = area.getDocument();
            int d = area.getCaret().getDot();
            int m = area.getCaret().getMark();
            Rectangle vis = area.getVisibleRect();
            int t = area.viewToModel2D(new Point(vis.x, vis.y));
            try {
                return new Anchor(doc.createPosition(d), doc.createPosition(m), doc.createPosition(Math.max(0, t)),
                                  d, m, vis.y, vis.x);
            } catch (BadLocationException ex) {
                return new Anchor(null, null, null, d, m, vis.y, vis.x);
            }
        }

        void restore(CodeTab view) {
            CodeTextPane area = view.getEditorArea();
            int length = area.getDocument().getLength();
            // A reload replaces every character, which collapses positions to 0;
            // the plain offsets are the better guess then.
            boolean collapsed = dot != null && dot.getOffset() == 0 && mark.getOffset() == 0 && dotOffset > 0;
            int d = dot == null || collapsed ? dotOffset : dot.getOffset();
            int m = mark == null || collapsed ? markOffset : mark.getOffset();
            area.setCaretPosition(Math.min(m, length));
            area.moveCaretPosition(Math.min(d, length));
            int y = scrollY;
            if (top != null && !collapsed) {
                try {
                    java.awt.geom.Rectangle2D r = area.modelToView2D(Math.min(top.getOffset(), length));
                    if (r != null) {
                        y = (int) r.getY();
                    }
                } catch (BadLocationException ignored) {
                    // keep the pixel offset
                }
            }
            final int targetY = y;
            Runnable scroll = () -> {
                Rectangle vis = area.getVisibleRect();
                area.scrollRectToVisible(new Rectangle(scrollX, targetY, Math.max(1, vis.width), Math.max(1, vis.height)));
            };
            scroll.run();
            // The caret's own scroll lands after this on the event queue.
            SwingUtilities.invokeLater(scroll);
        }
    }
}
