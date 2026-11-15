package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.SemanticStyle;
import com.sphere.fonts.FontLoader;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The notebook's dataflow, drawn: one box per code cell, in notebook order,
 * and an arc for each variable a cell takes from another, labelled with its
 * names. Colors say what needs running again: amber for a stale or edited
 * cell and the arcs that make it so, red for a cell that cannot run (two homes
 * for one variable, cells feeding on one another) and, in a linear notebook,
 * dashed red for a cell taking a name from a cell below it.
 *
 * Live while cells run: the running cell pulses, the queued ones wait with
 * their place in line, the variables flow along the arcs leaving the running
 * cell, and each cell that finishes flashes green (red when it failed).
 *
 * Hover a cell to see only its arcs; click it to go to it; double-click it to
 * run it and every cell that takes from it.
 */
final class DataflowMap extends JComponent implements Scrollable {

    private static final long serialVersionUID = 1L;
    private static final int TOP = 52;
    private static final int ROW = 44;
    private static final int ARCS = 118;
    private static final long FLASH_MS = 1400;

    /** What a click on the map does: implemented by the notebook editor. */
    interface Actions {
        void reveal(int codeIndex);

        void runDownstream(int codeIndex);
    }

    private final transient Actions actions;
    private transient NotebookDataflow.Graph graph = NotebookDataflow.Graph.EMPTY;
    private transient List<String> labels = List.of();
    private int hover = -1;
    private String progress = "";
    /** Cells that just finished: when, and whether they failed. */
    private final transient Map<Integer, long[]> flashes = new HashMap<>();
    private final Timer animation;
    private float phase;

    DataflowMap(Actions actions) {
        this.actions = actions;
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        animation = new Timer(40, e -> {
            phase = (phase + 0.04f) % 1f;
            long now = System.currentTimeMillis();
            flashes.values().removeIf(f -> now - f[0] > FLASH_MS);
            if (!animating()) {
                ((Timer) e.getSource()).stop();
            }
            if (isShowing()) {
                repaint();
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int at = rowAt(e.getY());
                if (at != hover) {
                    hover = at;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int at = rowAt(e.getY());
                if (at < 0) {
                    return;
                }
                if (e.getClickCount() >= 2) {
                    actions.runDownstream(at);
                } else {
                    actions.reveal(at);
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setGraph(NotebookDataflow.Graph graph, List<String> labels) {
        NotebookDataflow.Graph before = this.graph;
        this.graph = graph == null ? NotebookDataflow.Graph.EMPTY : graph;
        this.labels = labels == null ? List.of() : labels;
        // a cell that was running and no longer is just finished: it flashes
        long now = System.currentTimeMillis();
        for (NotebookDataflow.Node old : before.nodes()) {
            NotebookDataflow.Node n = this.graph.node(old.index());
            if (old.status() == NotebookDataflow.Status.RUNNING && n != null && n.status() != NotebookDataflow.Status.RUNNING) {
                flashes.put(n.index(), new long[] { now, failed(n) ? 1 : 0 });
            }
        }
        if (animating() && !animation.isRunning()) {
            animation.start();
        }
        revalidate();
        repaint();
    }

    /** "running 2 of 5", shown under the title while a reactive run goes on; empty otherwise. */
    void setProgress(String text) {
        String now = text == null ? "" : text;
        if (!now.equals(progress)) {
            progress = now;
            repaint();
        }
    }

    private static boolean failed(NotebookDataflow.Node n) {
        return n.status() == NotebookDataflow.Status.FAILED;
    }

    private boolean animating() {
        if (!flashes.isEmpty()) {
            return true;
        }
        for (NotebookDataflow.Node n : graph.nodes()) {
            if (n.status() == NotebookDataflow.Status.RUNNING || n.status() == NotebookDataflow.Status.QUEUED) {
                return true;
            }
        }
        return false;
    }

    private int rowAt(int y) {
        int i = (y - TOP) / ROW;
        return y >= TOP && i < graph.nodes().size() ? i : -1;
    }

    private int centerY(int i) {
        return TOP + i * ROW + ROW / 2 - 2;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(340, TOP + Math.max(1, graph.nodes().size()) * ROW + 16);
    }

    private String cellName(int i) {
        NotebookDataflow.Node n = graph.node(i);
        return n == null || n.count() == null ? "cell " + (i + 1) : "[" + n.count() + "]";
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int at = rowAt(e.getY());
        NotebookDataflow.Node n = graph.node(at);
        if (n == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(n.count() == null ? "cell (not run)" : "cell [" + n.count() + "]")
            .append("</b> · ").append(n.status().name().toLowerCase().replace('_', ' ')).append(": ").append(escape(n.why()));
        if (graph.reactive()) {
            sb.append("<br>runs #").append(n.rank() + 1).append(" in the notebook's order");
        }
        if (!n.binds().isEmpty()) {
            sb.append("<br>defines: ").append(escape(String.join(", ", n.binds())));
        }
        if (!n.changes().isEmpty()) {
            List<String> ch = new ArrayList<>();
            n.changes().forEach((name, maker) -> ch.add(name + " (from " + cellName(maker) + ")"));
            sb.append("<br>changes in place: ").append(escape(String.join(", ", ch)));
        }
        StringBuilder takes = new StringBuilder();
        for (NotebookDataflow.Edge edge : graph.edges()) {
            if (edge.to() == at) {
                takes.append(takes.length() > 0 ? "; " : "").append(String.join(", ", edge.names()))
                     .append(edge.mutation() ? " as changed by " : " from ").append(cellName(edge.from()))
                     .append(edge.backward() ? " (below!)" : "");
            }
        }
        if (takes.length() > 0) {
            sb.append("<br>takes: ").append(escape(takes.toString()));
        }
        sb.append("<br><i>click: go to the cell · double-click: run it and what depends on it</i></html>");
        return sb.toString();
    }

    private Color statusColor(NotebookDataflow.Status s) {
        return switch (s) {
            case FRESH -> EditorTheme.added();
            case STALE, EDITED -> SemanticStyle.staleColor();
            case RUNNING, QUEUED -> EditorTheme.focus();
            case CONFLICT, FAILED -> EditorTheme.errorSquiggle();
            case BLOCKED -> EditorTheme.alpha(EditorTheme.errorSquiggle(), 150);
            default -> EditorTheme.textFaint();
        };
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(EditorTheme.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            Font ui = FontLoader.getGlobalFont(Font.PLAIN, 12);
            Font code = FontLoader.getTerminalFont(Font.PLAIN, 12);
            Font small = ui.deriveFont(10.5f);
            FontMetrics um = g.getFontMetrics(ui);
            FontMetrics cm = g.getFontMetrics(code);
            FontMetrics sm = g.getFontMetrics(small);

            // Title and counts.
            int stale = graph.count(NotebookDataflow.Status.STALE) + graph.count(NotebookDataflow.Status.EDITED);
            int notRun = graph.count(NotebookDataflow.Status.NOT_RUN);
            int broken = graph.count(NotebookDataflow.Status.CONFLICT) + graph.count(NotebookDataflow.Status.BLOCKED);
            int failedCells = graph.count(NotebookDataflow.Status.FAILED);
            g.setFont(ui.deriveFont(Font.BOLD));
            g.setColor(EditorTheme.foreground());
            String head = (graph.reactive() ? "Reactive · " : "") + graph.nodes().size() + " cells";
            g.drawString(head, 10, 20);
            int hx = 10 + g.getFontMetrics().stringWidth(head) + 10;
            g.setFont(ui);
            if (broken > 0) {
                hx = chip(g, um, hx, broken + " cannot run", EditorTheme.errorSquiggle());
            }
            if (failedCells > 0) {
                hx = chip(g, um, hx, failedCells + " failed", EditorTheme.errorSquiggle());
            }
            hx = chip(g, um, hx, stale + " stale", stale > 0 ? SemanticStyle.staleColor() : EditorTheme.textFaint());
            chip(g, um, hx, notRun + " not run", EditorTheme.textFaint());
            g.setFont(small);
            g.setColor(progress.isEmpty() ? EditorTheme.textMuted() : EditorTheme.focus());
            g.drawString(fit(progress.isEmpty() ? (graph.reactive() ? "cells run in the graph's order, wherever they sit"
                                                                      : "cells run top to bottom") : progress,
                             sm, getWidth() - 20), 10, 40);

            if (graph.nodes().isEmpty()) {
                g.setColor(EditorTheme.textMuted());
                g.setFont(ui);
                g.drawString("No code cell yet.", 12, TOP + 20);
                return;
            }

            // The queue, in the order it will run.
            Map<Integer, Integer> place = new HashMap<>();
            List<NotebookDataflow.Node> waiting = new ArrayList<>();
            int running = -1;
            for (NotebookDataflow.Node n : graph.nodes()) {
                if (n.status() == NotebookDataflow.Status.QUEUED) {
                    waiting.add(n);
                } else if (n.status() == NotebookDataflow.Status.RUNNING) {
                    running = n.index();
                }
            }
            waiting.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
            for (int k = 0; k < waiting.size(); k++) {
                place.put(waiting.get(k).index(), k + 1);
            }

            // Arcs first, under the boxes.
            int x0 = ARCS;
            List<Rectangle> placedLabels = new ArrayList<>();
            for (NotebookDataflow.Edge e : graph.edges()) {
                boolean mine = hover < 0 || e.from() == hover || e.to() == hover;
                boolean flowing = e.from() == running && place.containsKey(e.to());
                int y1 = centerY(e.from());
                int y2 = centerY(e.to());
                int span = Math.abs(e.to() - e.from());
                float bulge = Math.min(ARCS - 22, 16 + 13 * span);
                NotebookDataflow.Node to = graph.node(e.to());
                Color c = e.backward() ? EditorTheme.errorSquiggle()
                        : to != null && to.broken() ? EditorTheme.alpha(EditorTheme.errorSquiggle(), 170)
                        : flowing ? EditorTheme.focus()
                        : e.stale() ? SemanticStyle.staleColor() : EditorTheme.textMuted();
                c = EditorTheme.alpha(c, mine || flowing ? 230 : 60);
                g.setColor(c);
                float width = flowing ? 2.4f : mine && hover >= 0 ? 2.2f : 1.4f;
                if (flowing) {
                    // the variables on their way: dashes marching from the running cell to the cells waiting on it
                    g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                                                new float[] { 6f, 5f }, (1f - phase) * 22f));
                } else if (e.backward()) {
                    g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 3f }, 0f));
                } else if (e.mutation()) {
                    g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f,
                                                new float[] { 1.5f, 3.5f }, 0f));
                } else {
                    g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                }
                Path2D curve = new Path2D.Float();
                curve.moveTo(x0, y1);
                curve.curveTo(x0 - bulge, y1, x0 - bulge, y2, x0, y2);
                g.draw(curve);
                // the arrowhead, into the cell that takes
                GeneralPath head2 = new GeneralPath();
                head2.moveTo(x0, y2);
                head2.lineTo(x0 - 6, y2 - 3.5f);
                head2.lineTo(x0 - 6, y2 + 3.5f);
                head2.closePath();
                g.fill(head2);
                if (mine || flowing) {
                    String label = String.join(", ", e.names()) + (e.mutation() ? "*" : "");
                    if (label.length() > 16) {
                        label = label.substring(0, 15) + "…";
                    }
                    float lx = x0 - 0.75f * bulge;
                    float ly = (y1 + y2) / 2f;
                    int lw = sm.stringWidth(label) + 8;
                    float bx = Math.max(2, lx - lw / 2f);
                    // labels of arcs meeting at one height go one under the other rather than over each other
                    for (int tries = 0; tries < 6; tries++) {
                        Rectangle here = new Rectangle(Math.round(bx), Math.round(ly - 8), lw, 15);
                        boolean clash = false;
                        for (Rectangle r : placedLabels) {
                            clash |= r.intersects(here);
                        }
                        if (!clash) {
                            placedLabels.add(here);
                            break;
                        }
                        ly += 15;
                    }
                    g.setColor(EditorTheme.alpha(EditorTheme.background(), 235));
                    g.fill(new RoundRectangle2D.Float(bx, ly - 8, lw, 15, 7, 7));
                    g.setFont(small);
                    g.setColor(e.stale() || e.backward() || flowing ? c : EditorTheme.foreground());
                    g.drawString(label, bx + 4, ly + sm.getAscent() / 2f - 1);
                }
            }

            // The cells.
            long now = System.currentTimeMillis();
            for (NotebookDataflow.Node n : graph.nodes()) {
                int i = n.index();
                int top = TOP + i * ROW + 3;
                int w = getWidth() - x0 - 8;
                boolean hot = i == hover;
                Color sc = statusColor(n.status());
                RoundRectangle2D box = new RoundRectangle2D.Float(x0, top, w, ROW - 8, 9, 9);
                g.setColor(hot ? EditorTheme.widgetHover() : EditorTheme.widgetBackground());
                g.fill(box);
                long[] flash = flashes.get(i);
                if (flash != null) {
                    float t = 1f - Math.min(1f, (now - flash[0]) / (float) FLASH_MS);
                    g.setColor(EditorTheme.alpha(flash[1] == 1 ? EditorTheme.errorSquiggle() : EditorTheme.added(),
                                                 Math.round(90 * t)));
                    g.fill(box);
                }
                boolean emphasize = n.outdated() || n.broken() || n.status() == NotebookDataflow.Status.RUNNING;
                g.setColor(emphasize ? sc : n.status() == NotebookDataflow.Status.QUEUED ? EditorTheme.alpha(sc, 170)
                                                                                        : EditorTheme.widgetBorder());
                if (n.status() == NotebookDataflow.Status.QUEUED) {
                    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 3f }, 0f));
                } else {
                    g.setStroke(new BasicStroke(emphasize ? 1.6f : 1f));
                }
                g.draw(new RoundRectangle2D.Float(x0 + 0.5f, top + 0.5f, w - 1, ROW - 9, 9, 9));
                if (n.status() == NotebookDataflow.Status.RUNNING) {
                    // a halo breathing around the running cell, and a spinner in place of its dot
                    float pulse = (float) (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
                    g.setColor(EditorTheme.alpha(sc, Math.round(40 + 80 * pulse)));
                    g.setStroke(new BasicStroke(3f));
                    g.draw(new RoundRectangle2D.Float(x0 - 1.5f, top - 1.5f, w + 3, ROW - 5, 11, 11));
                    g.setColor(sc);
                    g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Arc2D.Float(x0 + 8, top + 8, 10, 10, -phase * 360f, 260, Arc2D.OPEN));
                } else if (n.status() == NotebookDataflow.Status.QUEUED) {
                    g.setColor(sc);
                    g.setStroke(new BasicStroke(1.4f));
                    g.draw(new Ellipse2D.Float(x0 + 9, top + 9, 8, 8));
                } else {
                    g.setColor(sc);
                    g.fill(new Ellipse2D.Float(x0 + 9, top + 9, 8, 8));
                }
                g.setFont(ui.deriveFont(Font.BOLD));
                g.setColor(n.outdated() || n.broken() ? sc : EditorTheme.foreground());
                String count = n.status() == NotebookDataflow.Status.RUNNING ? "[*]"
                             : n.count() == null ? "[ ]" : "[" + n.count() + "]";
                g.drawString(count, x0 + 22, top + 16);
                int cx = x0 + 22 + g.getFontMetrics().stringWidth(count) + 6;
                Integer queuedAt = place.get(i);
                if (queuedAt != null) {
                    String badge = "#" + queuedAt;
                    int bw = sm.stringWidth(badge) + 8;
                    g.setColor(EditorTheme.alpha(EditorTheme.focus(), 50));
                    g.fill(new RoundRectangle2D.Float(x0 + w - bw - 6, top + 5, bw, 14, 7, 7));
                    g.setFont(small);
                    g.setColor(EditorTheme.focus());
                    g.drawString(badge, x0 + w - bw - 2, top + 16);
                }
                g.setFont(code);
                g.setColor(EditorTheme.foreground());
                String label = i < labels.size() ? labels.get(i) : "";
                g.drawString(fit(label, cm, x0 + w - cx - 6 - (queuedAt != null ? 34 : 0)), cx, top + 16);
                g.setFont(small);
                g.setColor(EditorTheme.textMuted());
                String sub = n.binds().isEmpty() ? "defines nothing" : "defines " + String.join(", ", n.binds());
                if (n.outdated() || n.broken() || n.status() == NotebookDataflow.Status.QUEUED
                    || n.status() == NotebookDataflow.Status.RUNNING) {
                    g.setColor(sc);
                    sub = n.status() == NotebookDataflow.Status.EDITED ? "edited since it ran" : n.why();
                }
                g.drawString(fit(sub, sm, w - 30), x0 + 22, top + 30);
            }
        } finally {
            g.dispose();
        }
    }

    private static int chip(Graphics2D g, FontMetrics fm, int x, String text, Color c) {
        int w = fm.stringWidth(text) + 12;
        g.setColor(EditorTheme.alpha(c, 40));
        g.fill(new RoundRectangle2D.Float(x, 8, w, 17, 9, 9));
        g.setColor(c);
        g.drawString(text, x + 6, 20);
        return x + w + 6;
    }

    private static String fit(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        String cut = s;
        while (!cut.isEmpty() && fm.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Scrollable: follows the width of its scroll pane ---------------------------

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? ROW / 2 : 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visibleRect.height - ROW : visibleRect.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }
}
