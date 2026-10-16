package com.sphere.components.editor;

import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.ScrollPaneLayout;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A miniature of the whole file beside the code, colored by token, with the
 * visible part outlined. Dragging the outline scrolls; clicking jumps. Its right
 * edge marks errors, warnings, search matches and changed lines over the whole
 * file, wherever they are.
 */
public final class Minimap extends JComponent implements SyntaxHighlighter.Listener {

    public static final int WIDTH = 92;
    private static final int LINE_H = 3;
    private static final int MARGIN = 6;
    private static final int RULER = 4;
    private static final int MAX_LINES = 150_000;
    private static final int MAX_COLUMNS = 140;

    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sphere-minimap");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** Runs of non-blank characters, per line. */
    private static final class Model {
        final int lines;
        final int[] firstRun;
        final short[] col;
        final short[] len;
        final byte[] kind;

        Model(int lines, int[] firstRun, short[] col, short[] len, byte[] kind) {
            this.lines = lines;
            this.firstRun = firstRun;
            this.col = col;
            this.len = len;
            this.kind = kind;
        }
    }

    private final CodeTextPane pane;
    private final JScrollPane scroll;
    private final AtomicLong generation = new AtomicLong();
    private volatile Model model;
    private List<EditorDiagnostic> diagnostics = List.of();
    private byte[] changes = new byte[0];
    private boolean hover;
    private int dragOffset = -1;
    private Color[] colors;
    private Object colorsKey;

    public Minimap(CodeTextPane pane, JScrollPane scroll) {
        this.pane = pane;
        this.scroll = scroll;
        setOpaque(true);
        setToolTipText("");
        if (pane.getSyntax() != null) {
            pane.getSyntax().addListener(this);
        }
        scroll.getViewport().addChangeListener(e -> repaint());
        pane.addCaretListener(e -> repaint());

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                Rectangle slider = sliderBounds();
                if (slider != null && e.getY() >= slider.y && e.getY() <= slider.y + slider.height) {
                    dragOffset = e.getY() - slider.y;
                } else {
                    jumpTo(e.getY());
                    Rectangle after = sliderBounds();
                    dragOffset = after == null ? 0 : Math.max(0, e.getY() - after.y);
                }
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragOffset = -1;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset >= 0) {
                    dragTo(e.getY() - dragOffset);
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // The wheel scrolls the code, as it would over the text itself.
                scroll.dispatchEvent(SwingUtilities.convertMouseEvent(Minimap.this, e, scroll));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, 100);
    }

    public void setDiagnostics(List<EditorDiagnostic> list) {
        this.diagnostics = list == null ? List.of() : List.copyOf(list);
        repaint();
    }

    public void setChanges(byte[] marks) {
        this.changes = marks == null ? new byte[0] : marks;
        repaint();
    }

    // ---- Model -------------------------------------------------------------

    @Override
    public void highlighted(SyntaxHighlighter.Snapshot s) {
        final long gen = generation.incrementAndGet();
        final int tab = Math.max(1, pane.getTabSize());
        BUILDER.execute(() -> {
            Model m = build(s, tab);
            SwingUtilities.invokeLater(() -> {
                if (gen == generation.get()) {
                    model = m;
                    repaint();
                }
            });
        });
    }

    private static Model build(SyntaxHighlighter.Snapshot s, int tab) {
        String text = s.text;
        int n = text.length();
        int lines = 1;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (lines > MAX_LINES) {
            return null;
        }
        int[] firstRun = new int[lines + 1];
        int cap = Math.max(64, n / 3);
        short[] col = new short[cap];
        short[] len = new short[cap];
        byte[] kind = new byte[cap];
        int runs = 0;
        int line = 0;
        int c = 0;
        int si = 0;
        int runStart = -1;
        int runKind = -1;
        firstRun[0] = 0;
        for (int i = 0; i <= n; i++) {
            char ch = i < n ? text.charAt(i) : '\n';
            int k = 0;
            if (ch != '\n' && ch != ' ' && ch != '\t' && ch != '\r') {
                while (si < s.count && s.ends[si] <= i) {
                    si++;
                }
                if (si < s.count && s.starts[si] <= i) {
                    k = s.kinds[si].ordinal();
                }
            }
            boolean blank = ch == '\n' || ch == ' ' || ch == '\t' || ch == '\r';
            if (runStart >= 0 && (blank || k != runKind || c >= MAX_COLUMNS)) {
                if (runs == col.length) {
                    int grow = col.length * 2;
                    col = java.util.Arrays.copyOf(col, grow);
                    len = java.util.Arrays.copyOf(len, grow);
                    kind = java.util.Arrays.copyOf(kind, grow);
                }
                col[runs] = (short) runStart;
                len[runs] = (short) Math.max(1, c - runStart);
                kind[runs] = (byte) runKind;
                runs++;
                runStart = -1;
            }
            if (ch == '\n') {
                line++;
                if (line <= lines) {
                    firstRun[line] = runs;
                }
                c = 0;
                continue;
            }
            if (ch == '\t') {
                c += tab - (c % tab);
                continue;
            }
            if (blank) {
                c++;
                continue;
            }
            if (c < MAX_COLUMNS && runStart < 0) {
                runStart = c;
                runKind = k;
            }
            c++;
        }
        for (int l = line + 1; l <= lines; l++) {
            firstRun[l] = runs;
        }
        return new Model(lines, firstRun, col, len, kind);
    }

    // ---- Geometry ----------------------------------------------------------

    private int rowHeight() {
        return Math.max(1, pane.lineHeight());
    }

    private int totalLines() {
        Model m = model;
        return m != null ? m.lines : pane.lineCountTotal();
    }

    /** Pixel offset of the miniature's first drawn row. */
    private int mapTop() {
        int content = totalLines() * LINE_H;
        int h = getHeight();
        if (content <= h) {
            return 0;
        }
        JViewport vp = scroll.getViewport();
        int maxView = Math.max(1, pane.getHeight() - vp.getHeight());
        double ratio = Math.min(1.0, Math.max(0.0, vp.getViewPosition().y / (double) maxView));
        return (int) Math.round(ratio * (content - h));
    }

    private Rectangle sliderBounds() {
        JViewport vp = scroll.getViewport();
        int rh = rowHeight();
        int firstLine = vp.getViewPosition().y / rh;
        int visible = Math.max(1, vp.getHeight() / rh);
        int y = firstLine * LINE_H - mapTop();
        return new Rectangle(0, y, getWidth() - RULER, visible * LINE_H);
    }

    private void jumpTo(int y) {
        int line = (y + mapTop()) / LINE_H;
        JViewport vp = scroll.getViewport();
        int target = line * rowHeight() - vp.getHeight() / 2;
        scrollTo(target);
    }

    private void dragTo(int sliderTop) {
        JViewport vp = scroll.getViewport();
        int content = totalLines() * LINE_H;
        Rectangle slider = sliderBounds();
        int maxView = Math.max(0, pane.getHeight() - vp.getHeight());
        if (content <= getHeight()) {
            scrollTo(sliderTop / LINE_H * rowHeight());
        } else {
            int travel = Math.max(1, getHeight() - slider.height);
            scrollTo((int) Math.round(Math.max(0, Math.min(1, sliderTop / (double) travel)) * maxView));
        }
    }

    private void scrollTo(int y) {
        JScrollBar bar = scroll.getVerticalScrollBar();
        int max = bar.getMaximum() - bar.getVisibleAmount();
        bar.setValue(Math.max(0, Math.min(max, y)));
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int line = (e.getY() + mapTop()) / LINE_H + 1;
        return line <= totalLines() ? "Line " + line : null;
    }

    // ---- Painting ----------------------------------------------------------

    private Color[] colors() {
        Object key = com.sphere.theme.ThemeManager.getCurrentPalette();
        if (colors == null || colorsKey != key) {
            TokenKind[] kinds = TokenKind.values();
            colors = new Color[kinds.length];
            for (TokenKind k : kinds) {
                Color c = EditorTheme.token(k);
                colors[k.ordinal()] = EditorTheme.alpha(c, k == TokenKind.DEFAULT ? 120 : 170);
            }
            colorsKey = key;
        }
        return colors;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth();
        int h = getHeight();
        g2.setColor(EditorTheme.background());
        g2.fillRect(0, 0, w, h);
        // A soft edge against the code.
        g2.setPaint(new GradientPaint(0, 0, EditorTheme.alpha(Color.BLACK, EditorTheme.isDarkSurface() ? 40 : 14),
                                      5, 0, EditorTheme.alpha(Color.BLACK, 0)));
        g2.fillRect(0, 0, 5, h);

        Model m = model;
        int top = mapTop();
        if (m != null) {
            Color[] palette = colors();
            int first = Math.max(0, top / LINE_H);
            int last = Math.min(m.lines - 1, (top + h) / LINE_H + 1);
            int maxW = w - MARGIN - RULER - 2;
            for (int line = first; line <= last; line++) {
                int y = line * LINE_H - top;
                int from = m.firstRun[line];
                int to = m.firstRun[line + 1];
                for (int r = from; r < to; r++) {
                    int x = MARGIN + m.col[r] * 2 / 3;
                    if (x - MARGIN > maxW) {
                        break;
                    }
                    int rw = Math.max(1, Math.min(m.len[r] * 2 / 3, maxW - (x - MARGIN)));
                    g2.setColor(palette[m.kind[r] & 0xFF]);
                    g2.fillRect(x, y, rw, LINE_H - 1);
                }
            }
        }

        // The visible part.
        Rectangle slider = sliderBounds();
        g2.setColor(EditorTheme.minimapSlider(dragOffset >= 0 ? 2 : hover ? 1 : 0));
        g2.fillRect(slider.x, slider.y, slider.width, slider.height);

        // Caret line.
        int caretLine = pane.lineAt(pane.getCaretPosition());
        int cy = caretLine * LINE_H - top;
        g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 70));
        g2.fillRect(0, cy, w - RULER, LINE_H - 1);

        paintRuler(g2, w, h);
        g2.dispose();
    }

    /** Markers over the whole file, at the right edge. */
    private void paintRuler(Graphics2D g2, int w, int h) {
        int lines = Math.max(1, totalLines());
        int x = w - RULER;
        g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 10));
        g2.fillRect(x, 0, RULER, h);
        double scale = h / (double) lines;
        for (int i = 0; i < changes.length && i < lines; i++) {
            int kind = changes[i] & 3;
            if (kind == 0) {
                continue;
            }
            g2.setColor(kind == LineDiff.ADDED ? EditorTheme.added() : EditorTheme.modified());
            g2.fillRect(x, (int) (i * scale), 2, Math.max(2, (int) scale));
        }
        int[] starts = pane.findStarts();
        int count = pane.findCount();
        if (count > 0) {
            g2.setColor(EditorTheme.findMatchBorder());
            int lastY = -10;
            for (int i = 0; i < count; i++) {
                int y = (int) (pane.lineAt(starts[i]) * scale);
                if (y - lastY >= 2) {
                    g2.fillRect(x + 1, y, RULER - 1, 2);
                    lastY = y;
                }
            }
        }
        for (EditorDiagnostic d : diagnostics) {
            if (d.getSeverity() == EditorDiagnostic.Severity.INFO) {
                continue;
            }
            g2.setColor(CodeTextPane.severityColor(d.getSeverity()));
            int y = (int) ((d.getLine() - 1) * scale);
            g2.fillRect(x, y, RULER, 3);
        }
    }

    // ---- Layout ------------------------------------------------------------

    /**
     * Lays the minimap between the code and the vertical scroll bar, the way
     * VS Code does, by narrowing the viewport after the usual layout.
     */
    public static final class ScrollLayout extends ScrollPaneLayout {
        private final Minimap minimap;

        public ScrollLayout(Minimap minimap) {
            this.minimap = minimap;
        }

        @Override
        public void layoutContainer(Container parent) {
            super.layoutContainer(parent);
            if (minimap == null || viewport == null) {
                return;
            }
            Rectangle vp = viewport.getBounds();
            if (!minimap.isVisible() || vp.width < WIDTH * 3) {
                minimap.setBounds(0, 0, 0, 0);
                return;
            }
            viewport.setBounds(vp.x, vp.y, vp.width - WIDTH, vp.height);
            int extra = hsb != null && hsb.isVisible() ? hsb.getHeight() : 0;
            minimap.setBounds(vp.x + vp.width - WIDTH, vp.y, WIDTH, vp.height + extra);
            if (hsb != null && hsb.isVisible()) {
                Rectangle hb = hsb.getBounds();
                hsb.setBounds(hb.x, hb.y, Math.max(0, hb.width - WIDTH), hb.height);
            }
            if (colHead != null && colHead.isVisible()) {
                Rectangle ch = colHead.getBounds();
                colHead.setBounds(ch.x, ch.y, Math.max(0, ch.width - WIDTH), ch.height);
            }
        }
    }
}
