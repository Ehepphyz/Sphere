package com.sphere.core.python.jupyterlab;

import javax.swing.JTextArea;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.Segment;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.WrappedPlainView;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

/**
 * Terminal text: a plain text area whose view paints each run of text in the
 * color the escapes gave it, from a list of runs kept beside the document.
 *
 * A styled text pane gives every line a paragraph view and every run a glyph
 * view, and measures them all again to wrap them: 2,000 lines arriving at once
 * held the event thread for over a second. A plain document wraps with the
 * font's metrics alone, and coloring costs a paint, as in SphereCoder's editor.
 */
final class ConsoleArea extends JTextArea {

    private static final long serialVersionUID = 1L;

    /** Colors of a run; null is the area's own. */
    record Style(Color fg, Color bg) {
        static final Style PLAIN = new Style(null, null);

        boolean isPlain() {
            return fg == null && bg == null;
        }
    }

    /** Colored runs, sorted, never overlapping; text outside any run is plain. */
    static final class Runs {
        int[] start = new int[64];
        int[] end = new int[64];
        Style[] style = new Style[64];
        int count;

        void add(int s, int e, Style st) {
            if (e <= s || st == null || st.isPlain()) {
                return;
            }
            if (count > 0 && end[count - 1] == s && style[count - 1].equals(st)) {
                end[count - 1] = e;
                return;
            }
            if (count == start.length) {
                start = Arrays.copyOf(start, count * 2);
                end = Arrays.copyOf(end, count * 2);
                style = Arrays.copyOf(style, count * 2);
            }
            start[count] = s;
            end[count] = e;
            style[count] = st;
            count++;
        }

        /** The text from {@code at} on was removed. */
        void truncate(int at) {
            while (count > 0 && start[count - 1] >= at) {
                count--;
            }
            if (count > 0 && end[count - 1] > at) {
                end[count - 1] = at;
            }
        }

        /** The first {@code removed} characters were removed. */
        void dropHead(int removed) {
            int keep = 0;
            for (int i = 0; i < count; i++) {
                int s = start[i] - removed;
                int e = end[i] - removed;
                if (e <= 0) {
                    continue;
                }
                start[keep] = Math.max(0, s);
                end[keep] = e;
                style[keep] = style[i];
                keep++;
            }
            count = keep;
        }

        /** Index of the first run ending after offset. */
        int firstEndingAfter(int offset) {
            int lo = 0;
            int hi = count;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (end[mid] <= offset) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    private transient Runs runs;

    ConsoleArea() {
        setEditable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(false);
        setBorder(null);
    }

    Runs runs() {
        if (runs == null) {
            runs = new Runs();
        }
        return runs;
    }

    @Override
    public void updateUI() {
        setUI(new ConsoleUI());
    }

    private static final class ConsoleUI extends BasicTextAreaUI {
        @Override
        public View create(Element elem) {
            if (getComponent() instanceof ConsoleArea area) {
                return new ConsoleView(elem, area);
            }
            return super.create(elem);
        }

        @Override
        protected void paintBackground(Graphics g) {
            // transparent: the output panel paints the surface
        }
    }

    private static final class ConsoleView extends WrappedPlainView {
        private final ConsoleArea area;

        ConsoleView(Element elem, ConsoleArea area) {
            super(elem, true);
            this.area = area;
        }

        @Override
        protected float drawUnselectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintRuns(g, x, y, p0, p1);
        }

        @Override
        protected float drawSelectedText(Graphics2D g, float x, float y, int p0, int p1)
                throws BadLocationException {
            return paintRuns(g, x, y, p0, p1);
        }

        private float paintRuns(Graphics2D g, float x, float y, int p0, int p1) throws BadLocationException {
            Document doc = getDocument();
            Segment seg = getLineBuffer();
            FontMetrics fm = area.getFontMetrics(area.getFont());
            Runs r = area.runs();
            int i = r.firstEndingAfter(p0);
            int pos = p0;
            while (pos < p1) {
                Style st;
                int segEnd;
                if (i < r.count && r.start[i] <= pos) {
                    st = r.style[i];
                    segEnd = Math.min(r.end[i], p1);
                } else {
                    st = Style.PLAIN;
                    segEnd = i < r.count ? Math.min(r.start[i], p1) : p1;
                }
                if (segEnd <= pos) {
                    segEnd = p1;
                }
                doc.getText(pos, segEnd - pos, seg);
                if (st.bg() != null) {
                    float w = Utilities.getTabbedTextWidth(seg, fm, x, this, pos);
                    g.setColor(st.bg());
                    g.fill(new Rectangle2D.Float(x, y - fm.getAscent(), w, fm.getHeight()));
                }
                g.setColor(st.fg() != null ? st.fg() : area.getForeground());
                x = Utilities.drawTabbedText(seg, x, y, g, this, pos);
                pos = segEnd;
                if (i < r.count && r.end[i] <= pos) {
                    i++;
                }
            }
            return x;
        }
    }
}
