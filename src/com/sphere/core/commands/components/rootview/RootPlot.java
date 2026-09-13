package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

/** Draws a histogram or a graph, with the axes and the probe under the cursor. */
public final class RootPlot extends JComponent {

    public enum Style { BARS, STEPS, POINTS, LINE }

    private static final int LEFT = 74;
    private static final int RIGHT = 22;
    private static final int TOP = 34;
    private static final int BOTTOM = 52;

    private RootHistogram histogram;
    private RootGraph graph;
    private RootGraph2D scatter;
    private String message = "Select an object";

    private Style style = Style.BARS;
    private boolean logY;
    private boolean logX;
    private boolean showErrors = true;
    private boolean showGrid = true;
    private int rebin = 1;

    private int hoverBin = -1;
    private double hoverX;
    private double hoverY;
    private boolean hovering;

    public RootPlot() {
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter probe = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateProbe(e.getX(), e.getY());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovering = false;
                hoverBin = -1;
                repaint();
            }
        };
        addMouseListener(probe);
        addMouseMotionListener(probe);
    }

    // ---- what to draw ------------------------------------------------------

    public void showHistogram(RootHistogram h) {
        this.histogram = h;
        this.graph = null;
        this.scatter = null;
        this.message = null;
        repaint();
    }

    public void showGraph(RootGraph g) {
        this.graph = g;
        this.histogram = null;
        this.scatter = null;
        this.message = null;
        this.style = Style.POINTS;
        repaint();
    }

    /** Points carrying a third value, drawn with that value as their colour. */
    public void showGraph2D(RootGraph2D g) {
        this.scatter = g;
        this.graph = null;
        this.histogram = null;
        this.message = null;
        this.style = Style.POINTS;
        repaint();
    }

    public RootGraph2D getGraph2D() {
        return scatter;
    }

    public void showMessage(String text) {
        this.histogram = null;
        this.graph = null;
        this.scatter = null;
        this.message = text;
        repaint();
    }

    public RootHistogram getHistogram() {
        return histogram;
    }

    public RootGraph getGraph() {
        return graph;
    }

    public boolean hasContent() {
        return histogram != null || graph != null || scatter != null;
    }

    public void setStyle(Style style) {
        this.style = style;
        repaint();
    }

    public Style getStyle() {
        return style;
    }

    public void setLogY(boolean on) {
        this.logY = on;
        repaint();
    }

    public boolean isLogY() {
        return logY;
    }

    public void setLogX(boolean on) {
        this.logX = on;
        repaint();
    }

    public boolean isLogX() {
        return logX;
    }

    public void setShowErrors(boolean on) {
        this.showErrors = on;
        repaint();
    }

    public boolean isShowErrors() {
        return showErrors;
    }

    public void setShowGrid(boolean on) {
        this.showGrid = on;
        repaint();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setRebin(int factor) {
        this.rebin = Math.max(1, factor);
        repaint();
    }

    public int getRebin() {
        return rebin;
    }

    /** The histogram actually drawn, after any grouping. */
    public RootHistogram effective() {
        return histogram == null ? null : histogram.rebin(rebin);
    }

    // ---- painting ----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                               RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                               RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(ImagingTheme.canvasGround());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (message != null) {
                g.setColor(ImagingTheme.subduedText());
                g.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
                final int width = g.getFontMetrics().stringWidth(message);
                g.drawString(message, (getWidth() - width) / 2, getHeight() / 2);
                return;
            }
            if (histogram != null && histogram.dimensions >= 2) {
                paintHistogram2D(g, histogram);
            } else if (histogram != null) {
                paintHistogram(g, effective());
            } else if (graph != null) {
                paintGraph(g, graph);
            } else if (scatter != null) {
                paintScatter(g, scatter);
            }
        } finally {
            g.dispose();
        }
    }

    private Rectangle frame() {
        return new Rectangle(LEFT, TOP,
                             Math.max(10, getWidth() - LEFT - RIGHT),
                             Math.max(10, getHeight() - TOP - BOTTOM));
    }

    private void paintHistogram(Graphics2D g, RootHistogram h) {
        final Rectangle box = frame();
        final int n = h.xAxis.bins;
        if (n <= 0) {
            return;
        }

        double top = h.maxContent();
        double bottom = Math.min(0, h.minContent());
        if (showErrors && style != Style.LINE) {
            // Room for the bar above the tallest bin, otherwise it gets cut off.
            for (int bin = 1; bin <= n; bin++) {
                if (h.content(bin) != 0) {
                    top = Math.max(top, h.content(bin) + h.error(bin));
                }
            }
        }
        if (logY) {
            bottom = positiveFloor(h);
            top = Math.max(top, bottom * 10);
        }
        if (top <= bottom) {
            top = bottom + 1;
        }
        top += (top - bottom) * 0.08;

        paintFrame(g, box, h.xAxis.min, h.xAxis.max, bottom, top, h);

        final Color fill = new Color(0x3B82F6);
        final Color line = new Color(0x93C5FD);

        for (int bin = 1; bin <= n; bin++) {
            final double value = h.content(bin);
            final double left = h.xAxis.edge(bin - 1);
            final double right = h.xAxis.edge(bin);
            final int x0 = (int) Math.round(mapX(left, h.xAxis.min, h.xAxis.max, box));
            final int x1 = (int) Math.round(mapX(right, h.xAxis.min, h.xAxis.max, box));
            final int yBase = (int) Math.round(mapY(bottom, bottom, top, box));
            final int y = (int) Math.round(mapY(value, bottom, top, box));

            if (style == Style.BARS) {
                if (value != 0) {
                    g.setColor(bin - 1 == hoverBin ? line : fill);
                    final int height = Math.abs(yBase - y);
                    g.fillRect(x0, Math.min(y, yBase), Math.max(1, x1 - x0), Math.max(1, height));
                }
            }
        }

        if (style == Style.STEPS || style == Style.LINE) {
            g.setColor(line);
            g.setStroke(new BasicStroke(1.8f));
            Path2D.Double path = new Path2D.Double();
            boolean started = false;
            for (int bin = 1; bin <= n; bin++) {
                final double value = h.content(bin);
                final double xa = mapX(h.xAxis.edge(bin - 1), h.xAxis.min, h.xAxis.max, box);
                final double xb = mapX(h.xAxis.edge(bin), h.xAxis.min, h.xAxis.max, box);
                final double y = mapY(value, bottom, top, box);
                if (!started) {
                    path.moveTo(xa, y);
                    started = true;
                } else if (style == Style.STEPS) {
                    path.lineTo(xa, y);
                }
                if (style == Style.STEPS) {
                    path.lineTo(xb, y);
                } else {
                    path.lineTo((xa + xb) / 2, y);
                }
            }
            g.draw(path);
        }

        if (showErrors && style != Style.LINE) {
            g.setColor(new Color(0xE2E8F0));
            g.setStroke(new BasicStroke(1f));
            for (int bin = 1; bin <= n; bin++) {
                final double value = h.content(bin);
                final double err = h.error(bin);
                if (err <= 0 || value == 0) {
                    continue;
                }
                final double cx = mapX(h.xAxis.center(bin - 1), h.xAxis.min, h.xAxis.max, box);
                final double ya = mapY(value - err, bottom, top, box);
                final double yb = mapY(value + err, bottom, top, box);
                g.draw(new Line2D.Double(cx, ya, cx, yb));
                g.draw(new Line2D.Double(cx - 3, ya, cx + 3, ya));
                g.draw(new Line2D.Double(cx - 3, yb, cx + 3, yb));
            }
        }

        paintTitle(g, h.title.isEmpty() ? h.name : h.title, box);
        paintStatBox(g, h, box);
        paintProbeLine(g, box);
    }

    /** The width the colour scale takes on the right of a two dimensional plot. */
    private static final int SCALE = 58;

    /**
     * Draws a two dimensional histogram as a map of coloured cells.
     *
     * Drawing it along a single axis would show a sum that nobody asked for, so
     * the second axis gets its own direction and the contents a colour.
     */
    private void paintHistogram2D(Graphics2D g, RootHistogram h) {
        final int nx = h.xAxis.bins;
        final int ny = h.yAxis.bins;
        if (nx <= 0 || ny <= 0) {
            return;
        }
        Rectangle box = frame();
        box.width = Math.max(10, box.width - SCALE);

        double top = Double.NEGATIVE_INFINITY;
        double bottom = Double.POSITIVE_INFINITY;
        for (int j = 1; j <= ny; j++) {
            for (int i = 1; i <= nx; i++) {
                final double v = h.content2D(i, j);
                top = Math.max(top, v);
                bottom = Math.min(bottom, v);
            }
        }
        if (!(top > bottom)) {
            top = bottom + 1;
        }

        paintFrame(g, box, h.xAxis.min, h.xAxis.max, h.yAxis.min, h.yAxis.max, h);

        for (int j = 1; j <= ny; j++) {
            final double y0 = mapY(h.yAxis.edge(j - 1), h.yAxis.min, h.yAxis.max, box);
            final double y1 = mapY(h.yAxis.edge(j), h.yAxis.min, h.yAxis.max, box);
            for (int i = 1; i <= nx; i++) {
                final double v = h.content2D(i, j);
                if (v == 0) {
                    continue;
                }
                final double x0 = mapX(h.xAxis.edge(i - 1), h.xAxis.min, h.xAxis.max, box);
                final double x1 = mapX(h.xAxis.edge(i), h.xAxis.min, h.xAxis.max, box);
                g.setColor(ramp((v - bottom) / (top - bottom)));
                g.fill(new Rectangle2D.Double(Math.min(x0, x1), Math.min(y0, y1),
                                              Math.abs(x1 - x0) + 1,
                                              Math.abs(y1 - y0) + 1));
            }
        }

        g.setColor(ImagingTheme.border());
        g.drawRect(box.x, box.y, box.width, box.height);
        paintColorScale(g, box, bottom, top);
        paintTitle(g, h.title.isEmpty() ? h.name : h.title, box);
    }

    /** The bar on the right that says which colour means what. */
    private void paintColorScale(Graphics2D g, Rectangle box,
                                 double bottom, double top) {
        final int x = (int) box.getMaxX() + 16;
        final int width = 14;
        for (int y = box.y; y < box.getMaxY(); y++) {
            final double at = 1.0 - (y - box.y) / (double) box.height;
            g.setColor(ramp(at));
            g.drawLine(x, y, x + width, y);
        }
        g.setColor(ImagingTheme.border());
        g.drawRect(x, box.y, width, box.height);

        g.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        g.setColor(ImagingTheme.subduedText());
        final double[] marks = ticks(bottom, top, false);
        final int decimals = decimalsFor(marks);
        for (double mark : marks) {
            final double at = (mark - bottom) / (top - bottom);
            if (at < 0 || at > 1) {
                continue;
            }
            final int y = (int) (box.getMaxY() - at * box.height);
            g.drawLine(x + width, y, x + width + 3, y);
            g.drawString(formatTick(mark, decimals), x + width + 6, y + 4);
        }
    }

    /** Dark blue through green to yellow, the way a density map usually reads. */
    private static Color ramp(double at) {
        final double t = Math.max(0, Math.min(1, at));
        final int r = (int) Math.round(255 * Math.max(0, Math.min(1, 1.6 * t - 0.45)));
        final int g = (int) Math.round(255 * Math.max(0, Math.min(1, 1.25 * t)));
        final int b = (int) Math.round(255 * Math.max(0, Math.min(1,
                          t < 0.5 ? 0.35 + 0.9 * t : 1.6 - 2.4 * t)));
        return new Color(r, g, b);
    }

    /** A TGraph2D: the points where they belong, the third value as colour. */
    private void paintScatter(Graphics2D g, RootGraph2D gr) {
        final int n = gr.size();
        if (n == 0) {
            return;
        }
        Rectangle box = frame();
        box.width = Math.max(10, box.width - SCALE);

        double xa = gr.minX();
        double xb = gr.maxX();
        double ya = gr.minY();
        double yb = gr.maxY();
        final double xPad = (xb - xa) * 0.05 + (xb == xa ? 1 : 0);
        final double yPad = (yb - ya) * 0.05 + (yb == ya ? 1 : 0);
        xa -= xPad;
        xb += xPad;
        ya -= yPad;
        yb += yPad;

        final double za = gr.minZ();
        double zb = gr.maxZ();
        if (zb <= za) {
            zb = za + 1;
        }

        paintFrame(g, box, xa, xb, ya, yb, null);

        for (int i = 0; i < n; i++) {
            final double px = mapX(gr.x[i], xa, xb, box);
            final double py = mapY(gr.y[i], ya, yb, box);
            g.setColor(ramp((gr.z[i] - za) / (zb - za)));
            g.fill(new java.awt.geom.Ellipse2D.Double(px - 3.5, py - 3.5, 7, 7));
        }

        paintColorScale(g, box, za, zb);
        paintTitle(g, gr.title.isEmpty() ? gr.name : gr.title, box);
    }

    private void paintGraph(Graphics2D g, RootGraph gr) {
        final Rectangle box = frame();
        final int n = gr.size();
        if (n == 0) {
            return;
        }
        double xLow = gr.minX();
        double xHigh = gr.maxX();
        double yLow = gr.minY();
        double yHigh = gr.maxY();
        if (showErrors && gr.hasErrors()) {
            // Room for the bars, otherwise the longest ones get cut off.
            for (int i = 0; i < n; i++) {
                yLow = Math.min(yLow, gr.y[i] - gr.errorLow(i));
                yHigh = Math.max(yHigh, gr.y[i] + gr.errorHigh(i));
            }
        }
        if (showErrors && gr.hasWidths()) {
            for (int i = 0; i < n; i++) {
                xLow = Math.min(xLow, gr.x[i] - gr.widthLow(i));
                xHigh = Math.max(xHigh, gr.x[i] + gr.widthHigh(i));
            }
        }
        final double xPad = (xHigh - xLow) * 0.05 + (xHigh == xLow ? 1 : 0);
        final double yPad = (yHigh - yLow) * 0.08 + (yHigh == yLow ? 1 : 0);
        xLow -= xPad;
        xHigh += xPad;
        yLow -= yPad;
        yHigh += yPad;
        if (logY) {
            yLow = Math.max(yLow, smallestPositive(gr.y) / 2);
        }

        paintFrame(g, box, xLow, xHigh, yLow, yHigh, null);

        g.setColor(new Color(0x34D399));
        g.setStroke(new BasicStroke(1.8f));
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < n; i++) {
            final double px = mapX(gr.x[i], xLow, xHigh, box);
            final double py = mapY(gr.y[i], yLow, yHigh, box);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        if (style == Style.LINE || style == Style.STEPS) {
            g.draw(path);
        }

        for (int i = 0; i < n; i++) {
            final double px = mapX(gr.x[i], xLow, xHigh, box);
            final double py = mapY(gr.y[i], yLow, yHigh, box);
            if (showErrors && gr.hasErrors()) {
                final double ya = mapY(gr.y[i] - gr.errorLow(i), yLow, yHigh, box);
                final double yb = mapY(gr.y[i] + gr.errorHigh(i), yLow, yHigh, box);
                g.setColor(new Color(0xA7F3D0));
                g.draw(new Line2D.Double(px, ya, px, yb));
                g.draw(new Line2D.Double(px - 3, ya, px + 3, ya));
                g.draw(new Line2D.Double(px - 3, yb, px + 3, yb));
            }
            if (showErrors && gr.hasWidths()) {
                final double xa = mapX(gr.x[i] - gr.widthLow(i), xLow, xHigh, box);
                final double xb = mapX(gr.x[i] + gr.widthHigh(i), xLow, xHigh, box);
                g.setColor(new Color(0xA7F3D0));
                g.draw(new Line2D.Double(xa, py, xb, py));
                g.draw(new Line2D.Double(xa, py - 3, xa, py + 3));
                g.draw(new Line2D.Double(xb, py - 3, xb, py + 3));
            }
            g.setColor(new Color(0x34D399));
            g.fill(new java.awt.geom.Ellipse2D.Double(px - 3, py - 3, 6, 6));
        }

        paintTitle(g, gr.title.isEmpty() ? gr.name : gr.title, box);
        paintProbeLine(g, box);
    }

    private void paintFrame(Graphics2D g, Rectangle box,
                            double xLow, double xHigh, double yLow, double yHigh,
                            RootHistogram h) {
        this.xLow = xLow;
        this.xHigh = xHigh;
        this.yLow = yLow;
        this.yHigh = yHigh;

        g.setColor(new Color(0x141618));
        g.fillRect(box.x, box.y, box.width, box.height);

        g.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        final double[] xTicks = ticks(xLow, xHigh, logX);
        final double[] yTicks = ticks(yLow, yHigh, logY);
        final int xDecimals = decimalsFor(xTicks);
        final int yDecimals = decimalsFor(yTicks);

        for (double t : xTicks) {
            final double px = mapX(t, xLow, xHigh, box);
            if (px < box.x - 1 || px > box.getMaxX() + 1) {
                continue;
            }
            if (showGrid) {
                g.setColor(new Color(0x22FFFFFF, true));
                g.draw(new Line2D.Double(px, box.y, px, box.getMaxY()));
            }
            g.setColor(ImagingTheme.subduedText());
            g.draw(new Line2D.Double(px, box.getMaxY(), px, box.getMaxY() + 5));
            final String label = formatTick(t, xDecimals);
            final int width = g.getFontMetrics().stringWidth(label);
            g.drawString(label, (int) (px - width / 2.0), (int) box.getMaxY() + 18);
        }

        for (double t : yTicks) {
            final double py = mapY(t, yLow, yHigh, box);
            if (py < box.y - 1 || py > box.getMaxY() + 1) {
                continue;
            }
            if (showGrid) {
                g.setColor(new Color(0x22FFFFFF, true));
                g.draw(new Line2D.Double(box.x, py, box.getMaxX(), py));
            }
            g.setColor(ImagingTheme.subduedText());
            g.draw(new Line2D.Double(box.x - 5, py, box.x, py));
            final String label = formatTick(t, yDecimals);
            final int width = g.getFontMetrics().stringWidth(label);
            g.drawString(label, box.x - 9 - width, (int) py + 4);
        }

        g.setColor(ImagingTheme.border());
        g.setStroke(new BasicStroke(1f));
        g.drawRect(box.x, box.y, box.width, box.height);

        if (h != null) {
            final String xTitle = h.xAxis.title.isEmpty() ? "" : h.xAxis.title;
            if (!xTitle.isEmpty()) {
                g.setColor(ImagingTheme.text());
                g.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
                final int width = g.getFontMetrics().stringWidth(xTitle);
                g.drawString(xTitle, box.x + (box.width - width) / 2,
                             (int) box.getMaxY() + 36);
            }
        }
    }

    private double xLow;
    private double xHigh;
    private double yLow;
    private double yHigh;

    private void paintTitle(Graphics2D g, String title, Rectangle box) {
        if (title == null || title.isEmpty()) {
            return;
        }
        g.setColor(ImagingTheme.text());
        g.setFont(ImagingTheme.uiFont(Font.BOLD, 12f));
        final int width = g.getFontMetrics().stringWidth(title);
        g.drawString(title, box.x + (box.width - width) / 2, box.y - 12);
    }

    /** The box ROOT puts in the corner: entries, mean, standard deviation. */
    private void paintStatBox(Graphics2D g, RootHistogram h, Rectangle box) {
        final String[] lines = {
            "entries  " + format(h.entries),
            "mean     " + format(h.mean()),
            "std dev  " + format(h.stdDev())
        };
        g.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, g.getFontMetrics().stringWidth(line));
        }
        final int w = width + 16;
        final int hgt = lines.length * 14 + 10;
        final int x = (int) box.getMaxX() - w - 8;
        final int y = box.y + 8;

        g.setColor(new Color(0xD0000000, true));
        g.fillRect(x, y, w, hgt);
        g.setColor(ImagingTheme.border());
        g.drawRect(x, y, w, hgt);
        g.setColor(ImagingTheme.text());
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], x + 8, y + 18 + i * 14);
        }
    }

    private void paintProbeLine(Graphics2D g, Rectangle box) {
        if (!hovering) {
            return;
        }
        final double px = mapX(hoverX, xLow, xHigh, box);
        if (px < box.x || px > box.getMaxX()) {
            return;
        }
        g.setColor(new Color(0x66FBBF24, true));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                    1f, new float[] {3f, 3f}, 0f));
        g.draw(new Line2D.Double(px, box.y, px, box.getMaxY()));
    }

    // ---- geometry ----------------------------------------------------------

    private double mapX(double value, double low, double high, Rectangle box) {
        if (logX && low > 0 && high > 0 && value > 0) {
            final double la = Math.log10(low);
            final double lb = Math.log10(high);
            return box.x + (Math.log10(value) - la) / (lb - la) * box.width;
        }
        if (high == low) {
            return box.x;
        }
        return box.x + (value - low) / (high - low) * box.width;
    }

    private double mapY(double value, double low, double high, Rectangle box) {
        if (logY && low > 0 && high > 0) {
            final double v = Math.max(value, low);
            final double la = Math.log10(low);
            final double lb = Math.log10(high);
            return box.getMaxY() - (Math.log10(v) - la) / (lb - la) * box.height;
        }
        if (high == low) {
            return box.getMaxY();
        }
        return box.getMaxY() - (value - low) / (high - low) * box.height;
    }

    private double unmapX(double px, Rectangle box) {
        if (logX && xLow > 0 && xHigh > 0) {
            final double la = Math.log10(xLow);
            final double lb = Math.log10(xHigh);
            return Math.pow(10, la + (px - box.x) / box.width * (lb - la));
        }
        return xLow + (px - box.x) / box.width * (xHigh - xLow);
    }

    private double unmapY(double py, Rectangle box) {
        if (logY && yLow > 0 && yHigh > 0) {
            final double la = Math.log10(yLow);
            final double lb = Math.log10(yHigh);
            return Math.pow(10, lb - (py - box.y) / box.height * (lb - la));
        }
        return yHigh - (py - box.y) / box.height * (yHigh - yLow);
    }

    private static double positiveFloor(RootHistogram h) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= h.xAxis.bins; i++) {
            final double v = h.content(i);
            if (v > 0) {
                best = Math.min(best, v);
            }
        }
        return best == Double.POSITIVE_INFINITY ? 0.1 : best / 2;
    }

    private static double smallestPositive(double[] values) {
        double best = Double.POSITIVE_INFINITY;
        for (double v : values) {
            if (v > 0) {
                best = Math.min(best, v);
            }
        }
        return best == Double.POSITIVE_INFINITY ? 1 : best;
    }

    /** Tick positions at a round step, or one per decade on a log axis. */
    private static double[] ticks(double low, double high, boolean log) {
        if (log && low > 0 && high > low) {
            java.util.List<Double> out = new java.util.ArrayList<>();
            for (int e = (int) Math.floor(Math.log10(low));
                 e <= Math.ceil(Math.log10(high)) && out.size() < 40; e++) {
                out.add(Math.pow(10, e));
            }
            double[] result = new double[out.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = out.get(i);
            }
            return result;
        }
        if (high <= low) {
            return new double[] {low};
        }
        final double raw = (high - low) / 8.0;
        final double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        final double normalized = raw / magnitude;
        final double step = magnitude * (normalized < 1.5 ? 1 : normalized < 3 ? 2
                                       : normalized < 7 ? 5 : 10);
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (double t = Math.ceil(low / step) * step; t <= high && out.size() < 40; t += step) {
            out.add(t);
        }
        double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** How many decimals the whole tick row needs, so they all read the same. */
    private static int decimalsFor(double[] values) {
        int needed = 0;
        for (double v : values) {
            for (int d = 0; d <= 6; d++) {
                final double scale = Math.pow(10, d);
                if (Math.abs(Math.rint(v * scale) - v * scale) < 1e-6) {
                    needed = Math.max(needed, d);
                    break;
                }
                if (d == 6) {
                    needed = 6;
                }
            }
        }
        return needed;
    }

    private static String formatTick(double v, int decimals) {
        final double magnitude = Math.abs(v);
        if (magnitude != 0 && (magnitude < 1e-4 || magnitude >= 1e6)) {
            return String.format(Locale.ROOT, "%.3g", v);
        }
        // -0 comes out of the rounding and reads as a mistake.
        final double shown = v == 0 ? 0 : v;
        return String.format(Locale.ROOT, "%." + decimals + "f", shown);
    }

    private static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e7) {
            return String.valueOf((long) v);
        }
        final double magnitude = Math.abs(v);
        if (magnitude != 0 && (magnitude < 1e-3 || magnitude >= 1e6)) {
            return String.format(Locale.ROOT, "%.3g", v);
        }
        return String.format(Locale.ROOT, "%.4g", v);
    }

    // ---- probe -------------------------------------------------------------

    private void updateProbe(int px, int py) {
        final Rectangle box = frame();
        hovering = box.contains(px, py);
        hoverBin = -1;
        if (!hovering) {
            repaint();
            return;
        }
        if (histogram != null && histogram.dimensions >= 2) {
            box.width = Math.max(10, box.width - SCALE);
            hovering = box.contains(px, py);
            hoverX = unmapX(px, box);
            hoverY = unmapY(py, box);
            repaint();
            return;
        }
        hoverX = unmapX(px, box);
        RootHistogram h = effective();
        if (h != null && h.xAxis.bins > 0) {
            for (int bin = 1; bin <= h.xAxis.bins; bin++) {
                if (hoverX >= h.xAxis.edge(bin - 1) && hoverX < h.xAxis.edge(bin)) {
                    hoverBin = bin - 1;
                    hoverY = h.content(bin);
                    break;
                }
            }
        }
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (histogram != null && histogram.dimensions >= 2 && hovering) {
            final int i = binOf(histogram.xAxis, hoverX);
            final int j = binOf(histogram.yAxis, hoverY);
            if (i > 0 && j > 0) {
                return String.format(Locale.ROOT,
                    "<html>cell %d, %d<br>x %s, y %s<br><b>%s</b></html>",
                    i, j, format(hoverX), format(hoverY),
                    format(histogram.content2D(i, j)));
            }
            return null;
        }
        RootHistogram h = effective();
        if (h != null && hoverBin >= 0) {
            final int bin = hoverBin + 1;
            return String.format(Locale.ROOT,
                "<html>bin %d<br>%s to %s<br><b>%s</b> &plusmn; %s</html>",
                bin, format(h.xAxis.edge(bin - 1)), format(h.xAxis.edge(bin)),
                format(h.content(bin)), format(h.error(bin)));
        }
        if (graph != null && hovering) {
            int nearest = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < graph.size(); i++) {
                final double d = Math.abs(graph.x[i] - hoverX);
                if (d < best) {
                    best = d;
                    nearest = i;
                }
            }
            if (nearest >= 0) {
                return String.format(Locale.ROOT, "point %d: x %s, y %s",
                    nearest, format(graph.x[nearest]), format(graph.y[nearest]));
            }
        }
        return null;
    }

    private static int binOf(RootAxis axis, double value) {
        for (int bin = 1; bin <= axis.bins; bin++) {
            if (value >= axis.edge(bin - 1) && value < axis.edge(bin)) {
                return bin;
            }
        }
        return -1;
    }

    /** The plot as an image, for saving it to a file. */
    public BufferedImage snapshot(int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(200, width),
                                                Math.max(150, height),
                                                BufferedImage.TYPE_INT_RGB);
        final int oldWidth = getWidth();
        final int oldHeight = getHeight();
        setSize(image.getWidth(), image.getHeight());
        Graphics2D g = image.createGraphics();
        try {
            paintComponent(g);
        } finally {
            g.dispose();
            setSize(oldWidth, oldHeight);
        }
        return image;
    }

    /** The drawn contents as CSV, for the export button. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        if (scatter != null) {
            sb.append("index,x,y,z\n");
            for (int i = 0; i < scatter.size(); i++) {
                sb.append(i).append(',').append(scatter.x[i]).append(',')
                  .append(scatter.y[i]).append(',').append(scatter.z[i]).append('\n');
            }
            return sb.toString();
        }
        if (histogram != null && histogram.dimensions >= 2) {
            sb.append("xbin,ybin,xlow,ylow,content\n");
            for (int j = 1; j <= histogram.yAxis.bins; j++) {
                for (int i = 1; i <= histogram.xAxis.bins; i++) {
                    sb.append(i).append(',').append(j).append(',')
                      .append(histogram.xAxis.edge(i - 1)).append(',')
                      .append(histogram.yAxis.edge(j - 1)).append(',')
                      .append(histogram.content2D(i, j)).append('\n');
                }
            }
            return sb.toString();
        }
        RootHistogram h = effective();
        if (h != null) {
            sb.append("bin,low,high,content,error\n");
            for (int i = 1; i <= h.xAxis.bins; i++) {
                sb.append(i).append(',')
                  .append(h.xAxis.edge(i - 1)).append(',')
                  .append(h.xAxis.edge(i)).append(',')
                  .append(h.content(i)).append(',')
                  .append(h.error(i)).append('\n');
            }
            return sb.toString();
        }
        if (graph != null) {
            sb.append("index,x,y\n");
            for (int i = 0; i < graph.size(); i++) {
                sb.append(i).append(',').append(graph.x[i]).append(',')
                  .append(graph.y[i]).append('\n');
            }
        }
        return sb.toString();
    }

    /** The drawn contents as Python lists, for the notebook. */
    public String toPython() {
        StringBuilder sb = new StringBuilder();
        RootHistogram h = effective();
        if (h != null) {
            final String name = h.name.isEmpty() ? "hist" : h.name.replaceAll("\\W", "_");
            sb.append(name).append("_edges = [");
            for (int i = 0; i <= h.xAxis.bins; i++) {
                sb.append(i == 0 ? "" : ", ").append(h.xAxis.edge(i));
            }
            sb.append("]\n").append(name).append("_counts = [");
            for (int i = 1; i <= h.xAxis.bins; i++) {
                sb.append(i == 1 ? "" : ", ").append(h.content(i));
            }
            sb.append("]\n");
            return sb.toString();
        }
        if (graph != null) {
            final String name = graph.name.isEmpty() ? "graph"
                : graph.name.replaceAll("\\W", "_");
            sb.append(name).append("_x = [");
            for (int i = 0; i < graph.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(graph.x[i]);
            }
            sb.append("]\n").append(name).append("_y = [");
            for (int i = 0; i < graph.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(graph.y[i]);
            }
            sb.append("]\n");
        }
        return sb.toString();
    }
}
