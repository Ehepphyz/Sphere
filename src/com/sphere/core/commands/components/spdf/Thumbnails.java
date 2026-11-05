package com.sphere.components.spdf;

import com.sphere.components.spdf.cos.PdfPage;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * The pages as thumbnails down the side: click to go, Ctrl/Shift-click to
 * select several, drag to reorder, right-click for rotate, duplicate,
 * insert, extract, delete. Each thumbnail carries the count of search hits on
 * its page and a dot when the page was edited.
 */
final class Thumbnails extends JComponent implements Scrollable {

    private static final int THUMB_W = 132;
    private static final int PAD = 14;
    private static final int LABEL = 18;

    private final SpdfPanel panel;
    private SpdfDoc doc;
    private RenderService render;
    private int[] ys = new int[0];
    private int[] hs = new int[0];
    final TreeSet<Integer> selected = new TreeSet<>();
    int current;
    private int dragFrom = -1;
    private int dropAt = -1;
    private Point pressAt;
    Map<Integer, Integer> hitCounts = new HashMap<>();
    final java.util.Set<Integer> edited = new java.util.HashSet<>();

    Thumbnails(SpdfPanel panel) {
        this.panel = panel;
        setFocusable(true);
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int i = indexAt(e.getY());
                pressAt = e.getPoint();
                if (i < 0) {
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (!selected.contains(i)) {
                        selected.clear();
                        selected.add(i);
                    }
                    repaint();
                    menu(e);
                    return;
                }
                if (e.isControlDown()) {
                    if (!selected.remove(i)) {
                        selected.add(i);
                    }
                } else if (e.isShiftDown() && !selected.isEmpty()) {
                    int a = Math.min(selected.first(), i);
                    int b = Math.max(selected.last(), i);
                    selected.clear();
                    for (int k = a; k <= b; k++) {
                        selected.add(k);
                    }
                } else {
                    if (!selected.contains(i)) {
                        selected.clear();
                        selected.add(i);
                    }
                    dragFrom = i;
                }
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom < 0 || pressAt == null || pressAt.distance(e.getPoint()) < 6) {
                    return;
                }
                dropAt = dropIndex(e.getY());
                scrollRectToVisible(new Rectangle(0, e.getY() - 20, 10, 40));
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int i = indexAt(e.getY());
                if (dropAt >= 0 && dragFrom >= 0) {
                    List<Integer> moving = new ArrayList<>(selected);
                    int target = dropAt;
                    dropAt = -1;
                    dragFrom = -1;
                    panel.movePages(moving, target);
                } else if (i >= 0 && !e.isControlDown() && !e.isShiftDown() && SwingUtilities.isLeftMouseButton(e)) {
                    selected.clear();
                    selected.add(i);
                    panel.goToPage(i);
                }
                dragFrom = -1;
                dropAt = -1;
                repaint();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (doc == null) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DELETE -> panel.deletePages(new ArrayList<>(selected));
                    case KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT -> select(Math.min(doc.pageCount() - 1, current + 1));
                    case KeyEvent.VK_UP, KeyEvent.VK_LEFT -> select(Math.max(0, current - 1));
                    default -> { }
                }
            }
        });
    }

    private void select(int i) {
        selected.clear();
        selected.add(i);
        panel.goToPage(i);
    }

    void setDocument(SpdfDoc d, RenderService r) {
        this.doc = d;
        this.render = r;
        selected.clear();
        edited.clear();
        hitCounts = new HashMap<>();
        relayout();
    }

    void relayout() {
        if (doc == null) {
            ys = new int[0];
            hs = new int[0];
            revalidate();
            repaint();
            return;
        }
        int n = doc.pageCount();
        ys = new int[n];
        hs = new int[n];
        int y = PAD;
        for (int i = 0; i < n; i++) {
            PdfPage p = doc.page(i);
            int h = (int) Math.round(THUMB_W * p.displayHeight() / Math.max(1, p.displayWidth()));
            h = Math.min(h, THUMB_W * 2);
            ys[i] = y;
            hs[i] = h;
            y += h + LABEL + PAD;
        }
        revalidate();
        repaint();
    }

    void setCurrent(int i) {
        if (i == current) {
            return;
        }
        current = i;
        if (i >= 0 && i < ys.length) {
            Rectangle r = new Rectangle(0, ys[i] - PAD, getWidth(), hs[i] + LABEL + 2 * PAD);
            if (!getVisibleRect().contains(r)) {
                scrollRectToVisible(r);
            }
        }
        repaint();
    }

    private int indexAt(int y) {
        for (int i = 0; i < ys.length; i++) {
            if (y >= ys[i] - PAD / 2 && y <= ys[i] + hs[i] + LABEL + PAD / 2) {
                return i;
            }
        }
        return -1;
    }

    private int dropIndex(int y) {
        for (int i = 0; i < ys.length; i++) {
            if (y < ys[i] + hs[i] / 2) {
                return i;
            }
        }
        return ys.length;
    }

    @Override
    public Dimension getPreferredSize() {
        int h = ys.length == 0 ? 100 : ys[ys.length - 1] + hs[hs.length - 1] + LABEL + PAD;
        return new Dimension(THUMB_W + 2 * PAD + 8, h);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        g.setColor(SpdfTheme.chrome());
        g.fillRect(clip.x, clip.y, clip.width, clip.height);
        if (doc == null) {
            g.dispose();
            return;
        }
        double device = g.getTransform().getScaleX();
        int x = (getWidth() - THUMB_W) / 2;
        Color accent = SpdfTheme.accent();
        g.setFont(SpdfTheme.ui(Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < ys.length; i++) {
            int y = ys[i];
            int h = hs[i];
            if (y + h + LABEL < clip.y - 200 || y > clip.y + clip.height + 200) {
                continue;
            }
            boolean sel = selected.contains(i);
            boolean cur = i == current;
            if (sel || cur) {
                g.setColor(SpdfTheme.alpha(accent, sel ? 60 : 30));
                g.fill(new RoundRectangle2D.Float(x - 7, y - 7, THUMB_W + 14, h + LABEL + 10, 10, 10));
            }
            g.setColor(new Color(0, 0, 0, 40));
            g.fillRect(x + 2, y + 3, THUMB_W, h);
            g.setColor(Color.WHITE);
            g.fillRect(x, y, THUMB_W, h);
            double scale = THUMB_W * device / doc.page(i).displayWidth();
            BufferedImage img = render.get(i, scale, null, 0, panel.view.night, this::repaint);
            if (img == null) {
                img = render.any(i, scale, panel.view.night);
            }
            if (img != null) {
                g.drawImage(img, x, y, THUMB_W, h, null);
            }
            g.setColor(cur ? accent : SpdfTheme.border());
            g.setStroke(new BasicStroke(cur ? 2f : 1f));
            g.drawRect(x, y, THUMB_W, h);
            // the number printed on the page; its place in the file beside it when that differs
            String name = doc.pageName(i);
            String label = name.equals(String.valueOf(i + 1)) ? name : name + "  (" + (i + 1) + ")";
            g.setColor(cur ? SpdfTheme.fg() : SpdfTheme.muted());
            g.drawString(label, x + (THUMB_W - fm.stringWidth(label)) / 2, y + h + 14);
            Integer hits = hitCounts.get(i);
            if (hits != null && hits > 0) {
                String s = String.valueOf(hits);
                int w = fm.stringWidth(s) + 10;
                g.setColor(new Color(255, 160, 0));
                g.fill(new RoundRectangle2D.Float(x + THUMB_W - w - 4, y + 4, w, 16, 16, 16));
                g.setColor(Color.BLACK);
                g.drawString(s, x + THUMB_W - w + 1, y + 16);
            }
            if (edited.contains(i)) {
                g.setColor(accent);
                g.fillOval(x + 5, y + 5, 9, 9);
                g.setColor(Color.WHITE);
                g.drawOval(x + 5, y + 5, 9, 9);
            }
        }
        if (dropAt >= 0) {
            int y = dropAt < ys.length ? ys[dropAt] - PAD / 2 - 1 : ys[ys.length - 1] + hs[hs.length - 1] + LABEL + 2;
            g.setColor(accent);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x - 4, y, x + THUMB_W + 4, y);
            g.fillOval(x - 8, y - 4, 8, 8);
        }
        g.dispose();
    }

    private void menu(MouseEvent e) {
        List<Integer> sel = new ArrayList<>(selected);
        int first = sel.isEmpty() ? 0 : sel.get(0);
        JPopupMenu m = new JPopupMenu();
        String n = sel.size() > 1 ? sel.size() + " Pages" : "Page " + (first + 1);
        m.add(item("Rotate Right", "rotate", () -> panel.rotatePages(sel, 90)));
        m.add(item("Rotate Left", "rotateleft", () -> panel.rotatePages(sel, -90)));
        m.addSeparator();
        m.add(item("Duplicate " + n, "duplicate", () -> panel.duplicatePages(sel)));
        m.add(item("Insert Blank Page After", "insert", () -> panel.insertBlank(sel.get(sel.size() - 1) + 1)));
        m.add(item("Insert PDF After…", "merge", () -> panel.importPdf(sel.get(sel.size() - 1) + 1)));
        m.addSeparator();
        m.add(item("Extract " + n + " to PDF…", "extract", () -> panel.extractPages(sel)));
        m.add(item("Export as PNG…", "export", () -> panel.exportPng(sel)));
        m.addSeparator();
        m.add(item("Delete " + n, "trash", () -> panel.deletePages(sel)));
        m.show(this, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(label, SpdfTheme.icon(icon, 16));
        i.addActionListener(e -> r.run());
        return i;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 30;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return visibleRect.height - 30;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
