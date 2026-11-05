package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfPage;
import com.sphere.components.spdf.edit.FontResolver;
import com.sphere.components.spdf.edit.RichText;
import com.sphere.components.spdf.edit.TextComposer;
import com.sphere.components.spdf.edit.TextEdit;
import com.sphere.components.spdf.font.SystemFonts;
import com.sphere.components.spdf.render.PageRenderer;
import com.sphere.components.spdf.render.TextGlyph;
import com.sphere.components.spdf.text.TextBlock;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Editing text right on the page. The paragraph's glyphs are hidden from the
 * page picture and the text is drawn from its own layout with the real
 * glyph outlines of the fonts it will be written with, so what is typed looks
 * exactly as it will in the PDF. Caret, selection, typing, deleting, word
 * moves, clipboard, styles on the selection, undo within the session; the
 * box moves by its handle and its width changes by its edge.
 */
final class TextEditor {

    final SpdfDoc doc;
    final int pageIndex;
    final PdfPage page;
    final TextBlock block;
    final List<TextGlyph> originals;
    RichText text;
    AffineTransform frame;
    float width;
    final FontResolver fonts;
    TextComposer.Composed layout;
    int caret;
    int anchor;
    private RichText.Style typing;
    private final Deque<State> undo = new ArrayDeque<>();
    private final Deque<State> redo = new ArrayDeque<>();
    boolean dirty;
    final Set<Long> hidden = new HashSet<>();
    final int hideKey;
    private final Runnable repaint;
    Consumer<TextEditor> onChange = e -> { };
    private float originalHeight;

    private record State(RichText text, int caret, int anchor, AffineTransform frame, float width) {
    }

    TextEditor(SpdfDoc doc, int pageIndex, TextBlock block, RichText text, AffineTransform frame, float width,
               Runnable repaint) {
        this.doc = doc;
        this.pageIndex = pageIndex;
        this.page = doc.page(pageIndex);
        this.block = block;
        this.originals = block == null ? List.of() : block.glyphs();
        this.text = text;
        this.frame = frame;
        this.width = Math.max(20, width);
        this.repaint = repaint;
        this.fonts = new FontResolver(doc.cache, doc.embedder, page);
        for (TextGlyph g : originals) {
            hidden.add(key(g.streamId, g.opIndex, g.glyphIndex));
        }
        hideKey = hidden.isEmpty() ? 0 : hidden.hashCode() * 31 + System.identityHashCode(this);
        relayout();
        originalHeight = layout.height();
    }

    static long key(int streamId, int op, int glyph) {
        return ((long) (streamId + 16) << 44) | ((long) op << 20) | (glyph & 0xFFFFF);
    }

    PageRenderer.GlyphFilter filter() {
        return hidden.isEmpty() ? null : (s, o, gi) -> hidden.contains(key(s, o, gi));
    }

    void relayout() {
        layout = TextComposer.compose(text, width, fonts);
        onChange.accept(this);
        repaint.run();
    }

    /* ------------------------------------------------------------- geometry */

    AffineTransform localToDisplay() {
        AffineTransform t = page.userToDisplay();
        t.concatenate(frame);
        return t;
    }

    Point2D toLocal(Point2D display) {
        try {
            return localToDisplay().inverseTransform(display, null);
        } catch (NoninvertibleTransformException e) {
            return new Point2D.Double();
        }
    }

    /** The box in layout coordinates, with a little margin. */
    Rectangle2D localBox() {
        float top = Math.max(layout.top, 8);
        float bottom = Math.min(layout.bottom, -3);
        return new Rectangle2D.Double(-3, bottom - 2, width + 6, top - bottom + 4);
    }

    Shape displayBox() {
        return localToDisplay().createTransformedShape(localBox());
    }

    boolean hitBox(Point2D display) {
        Point2D l = toLocal(display);
        Rectangle2D b = localBox();
        return new Rectangle2D.Double(b.getX() - 4, b.getY() - 4, b.getWidth() + 8, b.getHeight() + 8).contains(l);
    }

    /** The resize handle sits on the middle of the right edge. */
    boolean hitResize(Point2D display, double zoom) {
        Rectangle2D b = localBox();
        Point2D h = localToDisplay().transform(new Point2D.Double(b.getMaxX(), b.getCenterY()), null);
        return h.distance(display) < 7 / zoom;
    }

    /** The move handle sits above the top-left corner. */
    boolean hitMove(Point2D display, double zoom) {
        Rectangle2D b = localBox();
        Point2D h = localToDisplay().transform(new Point2D.Double(b.getMinX(), b.getMaxY() + 6 / zoom), null);
        return h.distance(display) < 8 / zoom;
    }

    /* ------------------------------------------------------------- painting */

    /** Paints in display space (the graphics already map display points to the screen). */
    void paint(Graphics2D g, double zoom, boolean caretOn, Color accent) {
        AffineTransform l2d = localToDisplay();
        // selection
        int s = Math.min(caret, anchor);
        int e = Math.max(caret, anchor);
        if (s != e) {
            g.setColor(SpdfTheme.alpha(accent, 70));
            for (TextComposer.Line line : layout.lines) {
                float x0 = Float.NaN;
                float x1 = Float.NaN;
                for (TextComposer.Placed p : line.glyphs) {
                    if (p.index >= s && p.index < e) {
                        if (Float.isNaN(x0)) {
                            x0 = p.x;
                        }
                        x1 = p.x + p.width;
                    }
                }
                if (!Float.isNaN(x0)) {
                    Rectangle2D r = new Rectangle2D.Double(x0, line.baseline - line.descent, Math.max(1, x1 - x0),
                                                           line.ascent + line.descent);
                    g.fill(l2d.createTransformedShape(r));
                }
            }
        }
        // the text, glyph by glyph, in its real outlines
        for (TextComposer.Line line : layout.lines) {
            for (TextComposer.Placed p : line.glyphs) {
                if (p.space || p.style == null) {
                    continue;
                }
                AffineTransform t = new AffineTransform(l2d);
                t.translate(p.x, line.baseline + p.style.rise);
                t.scale(p.style.size, p.style.size);
                Shape glyph = p.font != null && p.code != null ? p.font.glyph(p.code) : null;
                if (glyph == null) {
                    glyph = SystemFonts.outline(SystemFonts.javaFont(p.style.family, p.style.bold, p.style.italic, false,
                                                                     false), new String(Character.toChars(p.cp)), null);
                }
                g.setColor(new Color(p.style.color));
                g.fill(t.createTransformedShape(glyph));
            }
            // underline and strike-through
            for (TextComposer.Placed p : line.glyphs) {
                if (p.style != null && (p.style.underline || p.style.strike)) {
                    float th = Math.max(0.4f, p.style.size * 0.055f);
                    g.setColor(new Color(p.style.color));
                    if (p.style.underline) {
                        g.fill(l2d.createTransformedShape(new Rectangle2D.Double(p.x, line.baseline + p.style.rise
                            - p.style.size * 0.12 - th / 2, p.width, th)));
                    }
                    if (p.style.strike) {
                        g.fill(l2d.createTransformedShape(new Rectangle2D.Double(p.x, line.baseline + p.style.rise
                            + p.style.size * 0.28 - th / 2, p.width, th)));
                    }
                }
            }
        }
        // the box, its handles, the caret
        g.setStroke(new BasicStroke((float) (1.2 / zoom), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                                    new float[] { (float) (4 / zoom), (float) (3 / zoom) }, 0));
        g.setColor(accent);
        g.draw(displayBox());
        Rectangle2D b = localBox();
        g.setStroke(new BasicStroke((float) (1.2 / zoom)));
        Point2D rh = l2d.transform(new Point2D.Double(b.getMaxX(), b.getCenterY()), null);
        double hs = 4 / zoom;
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(rh.getX() - hs, rh.getY() - hs, 2 * hs, 2 * hs));
        g.setColor(accent);
        g.draw(new Rectangle2D.Double(rh.getX() - hs, rh.getY() - hs, 2 * hs, 2 * hs));
        Point2D mh = l2d.transform(new Point2D.Double(b.getMinX(), b.getMaxY() + 6 / zoom), null);
        double r = 5 / zoom;
        g.fill(new Ellipse2D.Double(mh.getX() - r, mh.getY() - r, 2 * r, 2 * r));
        g.setColor(Color.WHITE);
        GeneralPath cross = new GeneralPath();
        cross.moveTo(mh.getX() - r * 0.55, mh.getY());
        cross.lineTo(mh.getX() + r * 0.55, mh.getY());
        cross.moveTo(mh.getX(), mh.getY() - r * 0.55);
        cross.lineTo(mh.getX(), mh.getY() + r * 0.55);
        g.draw(cross);
        if (caretOn) {
            float[] c = layout.caret(caret);
            Point2D top = l2d.transform(new Point2D.Double(c[0], c[1] + c[2]), null);
            Point2D bot = l2d.transform(new Point2D.Double(c[0], c[1] - c[3]), null);
            g.setStroke(new BasicStroke((float) Math.max(1.2 / zoom, 0.6)));
            g.setColor(currentStyle() == null ? Color.BLACK : new Color(currentStyle().color));
            g.draw(new java.awt.geom.Line2D.Double(top, bot));
        }
        if (layout.missing > 0) {
            g.setColor(new Color(0xD03030));
            g.setFont(SpdfTheme.ui(java.awt.Font.BOLD, 9).deriveFont((float) (9 / zoom)));
            Point2D at = l2d.transform(new Point2D.Double(b.getMaxX(), b.getMinY()), null);
            g.drawString("⚠ " + layout.missing, (float) at.getX(), (float) (at.getY() + 10 / zoom));
        }
    }

    /* ------------------------------------------------------------- styles */

    RichText.Style currentStyle() {
        if (caret == anchor && typing != null) {
            return typing;
        }
        if (text.length() == 0) {
            return typing;
        }
        int at = caret == anchor ? Math.max(0, caret - 1) : Math.min(caret, anchor);
        if (caret == anchor && caret == 0) {
            at = 0;
        }
        return text.styleAt(at);
    }

    RichText.Para currentPara() {
        return text.para(text.paragraphOf(caret));
    }

    void setDefaultStyle(RichText.Style st) {
        typing = st;
    }

    /** Applies a style change to the selection, or to what is typed next. */
    void apply(UnaryOperator<RichText.Style> f) {
        if (caret == anchor) {
            RichText.Style cur = currentStyle();
            if (cur != null) {
                typing = f.apply(cur);
            }
            onChange.accept(this);
            repaint.run();
            return;
        }
        push();
        text.restyle(Math.min(caret, anchor), Math.max(caret, anchor), f);
        dirty = true;
        relayout();
    }

    void applyPara(Consumer<RichText.Para> f) {
        push();
        text.restylePara(Math.min(caret, anchor), Math.max(caret, anchor), f);
        dirty = true;
        relayout();
    }

    /** Shrinks every size until the text fits the height it had when the edit began. */
    void fitToBox() {
        if (originalHeight <= 0) {
            return;
        }
        push();
        for (int i = 0; i < 12 && layout.height() > originalHeight * 1.01f; i++) {
            float k = Math.max(0.85f, Math.min(0.98f, originalHeight / layout.height()));
            text.restyle(0, text.length(), s -> s.size(Math.max(4, s.size * k)));
            layout = TextComposer.compose(text, width, fonts);
        }
        dirty = true;
        relayout();
    }

    /* ------------------------------------------------------------- editing */

    private void push() {
        undo.push(new State(text.copy(), caret, anchor, new AffineTransform(frame), width));
        if (undo.size() > 200) {
            undo.removeLast();
        }
        redo.clear();
    }

    private void insert(String s) {
        push();
        deleteSelection();
        RichText.Style st = typing != null ? typing : text.styleForInsert(caret, null);
        if (st == null) {
            st = new RichText.Style("Arial", false, false, false, false, 12, 0, 0, null);
        }
        text.insert(caret, s, st);
        caret += s.length();
        anchor = caret;
        dirty = true;
        relayout();
    }

    private void deleteSelection() {
        if (caret != anchor) {
            int s = Math.min(caret, anchor);
            int e = Math.max(caret, anchor);
            text.delete(s, e);
            caret = s;
            anchor = s;
        }
    }

    String selectedText() {
        int s = Math.min(caret, anchor);
        int e = Math.max(caret, anchor);
        return text.text().substring(s, e);
    }

    void selectAll() {
        anchor = 0;
        caret = text.length();
        onChange.accept(this);
        repaint.run();
    }

    private int wordLeft(int i) {
        String s = text.text();
        while (i > 0 && !Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private int wordRight(int i) {
        String s = text.text();
        while (i < s.length() && Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        while (i < s.length() && !Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private void moveTo(int i, boolean extend) {
        caret = Math.max(0, Math.min(text.length(), i));
        if (!extend) {
            anchor = caret;
        }
        typing = null;
        onChange.accept(this);
        repaint.run();
    }

    private int vertical(int dir) {
        float[] c = layout.caret(caret);
        TextComposer.Line cur = null;
        int idx = -1;
        for (int i = 0; i < layout.lines.size(); i++) {
            TextComposer.Line l = layout.lines.get(i);
            if (Math.abs(l.baseline - c[1]) < 0.01f) {
                cur = l;
                idx = i;
                break;
            }
        }
        if (cur == null) {
            return caret;
        }
        int target = idx + dir;
        if (target < 0) {
            return 0;
        }
        if (target >= layout.lines.size()) {
            return text.length();
        }
        TextComposer.Line l = layout.lines.get(target);
        return layout.indexAt(c[0], l.baseline);
    }

    /** Handles a key press; true when consumed. */
    boolean keyPressed(KeyEvent e) {
        boolean ctrl = (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
        boolean alt = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
        boolean shift = e.isShiftDown();
        if (ctrl && alt) {
            // AltGr: the character comes as a typed key
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT -> moveTo(ctrl ? wordLeft(caret) : caret != anchor && !shift
                ? Math.min(caret, anchor) : caret - 1, shift);
            case KeyEvent.VK_RIGHT -> moveTo(ctrl ? wordRight(caret) : caret != anchor && !shift
                ? Math.max(caret, anchor) : caret + 1, shift);
            case KeyEvent.VK_UP -> moveTo(vertical(-1), shift);
            case KeyEvent.VK_DOWN -> moveTo(vertical(1), shift);
            case KeyEvent.VK_HOME -> moveTo(ctrl ? 0 : lineStart(), shift);
            case KeyEvent.VK_END -> moveTo(ctrl ? text.length() : lineEnd(), shift);
            case KeyEvent.VK_BACK_SPACE -> {
                if (caret != anchor) {
                    push();
                    deleteSelection();
                } else if (caret > 0) {
                    push();
                    int from = ctrl ? wordLeft(caret) : caret - Character.charCount(text.text().codePointBefore(caret));
                    text.delete(from, caret);
                    caret = from;
                    anchor = caret;
                }
                dirty = true;
                relayout();
            }
            case KeyEvent.VK_DELETE -> {
                if (caret != anchor) {
                    push();
                    deleteSelection();
                } else if (caret < text.length()) {
                    push();
                    int to = ctrl ? wordRight(caret) : caret + Character.charCount(text.text().codePointAt(caret));
                    text.delete(caret, to);
                }
                dirty = true;
                relayout();
            }
            case KeyEvent.VK_ENTER -> {
                if (ctrl) {
                    return false;
                }
                insert("\n");
            }
            case KeyEvent.VK_TAB -> insert("\t");
            case KeyEvent.VK_A -> {
                if (!ctrl) {
                    return false;
                }
                selectAll();
            }
            case KeyEvent.VK_C, KeyEvent.VK_X -> {
                if (!ctrl) {
                    return false;
                }
                if (caret != anchor) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(selectedText()), null);
                    if (e.getKeyCode() == KeyEvent.VK_X) {
                        push();
                        deleteSelection();
                        dirty = true;
                        relayout();
                    }
                }
            }
            case KeyEvent.VK_V -> {
                if (!ctrl) {
                    return false;
                }
                try {
                    Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                    if (o instanceof String s && !s.isEmpty()) {
                        insert(s.replace("\r\n", "\n").replace('\r', '\n'));
                    }
                } catch (Exception ex) {
                    Toolkit.getDefaultToolkit().beep();
                }
            }
            case KeyEvent.VK_Z -> {
                if (!ctrl) {
                    return false;
                }
                if (shift) {
                    redoLocal();
                } else {
                    undoLocal();
                }
            }
            case KeyEvent.VK_Y -> {
                if (!ctrl) {
                    return false;
                }
                redoLocal();
            }
            case KeyEvent.VK_B -> {
                if (!ctrl) {
                    return false;
                }
                RichText.Style st = currentStyle();
                boolean b = st == null || !st.bold;
                apply(s -> s.bold(b));
            }
            case KeyEvent.VK_I -> {
                if (!ctrl) {
                    return false;
                }
                RichText.Style st = currentStyle();
                boolean b = st == null || !st.italic;
                apply(s -> s.italic(b));
            }
            case KeyEvent.VK_U -> {
                if (!ctrl) {
                    return false;
                }
                RichText.Style st = currentStyle();
                boolean b = st == null || !st.underline;
                apply(s -> s.underline(b));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    boolean keyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
        boolean alt = (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
        if (c == KeyEvent.CHAR_UNDEFINED || c < 32 || c == 127) {
            return false;
        }
        if (ctrl && !alt) {
            return false;
        }
        insert(String.valueOf(c));
        return true;
    }

    private int lineStart() {
        for (TextComposer.Line l : layout.lines) {
            if (caret >= l.start && caret <= l.end) {
                return l.start;
            }
        }
        return 0;
    }

    private int lineEnd() {
        for (TextComposer.Line l : layout.lines) {
            if (caret >= l.start && caret <= l.end) {
                int end = l.end;
                while (end > l.start && end <= text.length() && end - 1 < text.length() && text.charAt(end - 1) == ' ') {
                    end--;
                }
                return end;
            }
        }
        return text.length();
    }

    private void undoLocal() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(new State(text.copy(), caret, anchor, new AffineTransform(frame), width));
        State s = undo.pop();
        text = s.text;
        caret = s.caret;
        anchor = s.anchor;
        frame = s.frame;
        width = s.width;
        dirty = true;
        relayout();
    }

    private void redoLocal() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(new State(text.copy(), caret, anchor, new AffineTransform(frame), width));
        State s = redo.pop();
        text = s.text;
        caret = s.caret;
        anchor = s.anchor;
        frame = s.frame;
        width = s.width;
        dirty = true;
        relayout();
    }

    /* ------------------------------------------------------------- mouse */

    private int dragMode;
    private Point2D dragFrom;
    private AffineTransform frameFrom;
    private float widthFrom;

    void mousePressed(Point2D display, int clicks, boolean shift, double zoom) {
        if (hitResize(display, zoom)) {
            push();
            dragMode = 2;
            dragFrom = display;
            widthFrom = width;
            return;
        }
        if (hitMove(display, zoom)) {
            push();
            dragMode = 3;
            dragFrom = display;
            frameFrom = new AffineTransform(frame);
            return;
        }
        dragMode = 1;
        Point2D l = toLocal(display);
        int i = layout.indexAt((float) l.getX(), (float) l.getY());
        if (clicks == 2) {
            anchor = wordLeft(Math.min(text.length(), i + 1));
            caret = wordRight(anchor);
            while (caret > anchor && caret - 1 < text.length() && text.charAt(caret - 1) == ' ') {
                caret--;
            }
        } else if (clicks >= 3) {
            int p = text.paragraphOf(i);
            int s = 0;
            for (int k = 0; k < p; k++) {
                s = text.text().indexOf('\n', s) + 1;
            }
            int e = text.text().indexOf('\n', s);
            anchor = s;
            caret = e < 0 ? text.length() : e;
        } else {
            caret = i;
            if (!shift) {
                anchor = i;
            }
        }
        typing = null;
        onChange.accept(this);
        repaint.run();
    }

    void mouseDragged(Point2D display) {
        if (dragMode == 2) {
            Point2D a = toLocal(dragFrom);
            Point2D b = toLocal(display);
            width = (float) Math.max(20, widthFrom + (b.getX() - a.getX()));
            dirty = true;
            relayout();
        } else if (dragMode == 3) {
            AffineTransform d2u = page.displayToUser();
            Point2D ua = d2u.transform(dragFrom, null);
            Point2D ub = d2u.transform(display, null);
            AffineTransform f = AffineTransform.getTranslateInstance(ub.getX() - ua.getX(), ub.getY() - ua.getY());
            f.concatenate(frameFrom);
            frame = f;
            dirty = true;
            repaint.run();
        } else if (dragMode == 1) {
            Point2D l = toLocal(display);
            caret = layout.indexAt((float) l.getX(), (float) l.getY());
            onChange.accept(this);
            repaint.run();
        }
    }

    void mouseReleased() {
        dragMode = 0;
    }

    /* ------------------------------------------------------------- commit */

    /** Writes the edit into the document; returns false when nothing changed. */
    boolean commit() {
        if (!dirty) {
            return false;
        }
        String label = block == null ? "Add text" : "Edit text";
        TextEdit.commit(doc.history, doc.pdf, page, pageIndex, doc.renderer, fonts, originals,
                        text.text().isBlank() ? null : text, frame, width, label);
        return true;
    }
}
