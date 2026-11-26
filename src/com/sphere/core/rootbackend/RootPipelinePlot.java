package com.sphere.core.rootbackend;

import com.sphere.theme.ThemePalette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A small picture of what a pipeline computes, drawn where it is being made.
 *
 * Numbers in a text area say what a function is at a few points; a curve says
 * what it does: where a turn-on rises, where a peak sits, where it stops being
 * a number at all. Two pictures are drawn. A curve, of one input across the
 * range it lives in, with the places that give NaN or infinity marked in red
 * along the axis, a second quantity drawn faintly against its own scale on the
 * right (the formula behind its derivative), and the typical value marked. And
 * bars, for what adds up to a whole: an error budget, the values a call named.
 *
 * Nothing here needs ROOT or a compiler: the formula dialog feeds it values
 * computed in Java as the formula is typed.
 */
public final class RootPipelinePlot extends JComponent {

    /** One curve: its name, its values at the plot's x, and its color. */
    public record Series(String name, double[] ys, Color color) { }

    /** One bar: what it is, its value, and its share of the whole (0 to 1). */
    public record Bar(String label, double value, double share) { }

    private enum Mode { MESSAGE, CURVES, BARS }

    private final ThemePalette palette;
    private Mode mode = Mode.MESSAGE;
    private String title = "";
    private String message = "";
    private String xLabel = "";
    private double[] xs = new double[0];
    private Series primary;
    private Series secondary;
    private double mark = Double.NaN;
    private double markValue = Double.NaN;
    private String markLabel = "";
    private List<Bar> bars = List.of();
    private boolean percent = true;

    /** The primary's exact value at any x, when it can be computed rather than read off a sample. */
    private java.util.function.DoubleUnaryOperator exact;

    /** Where the pointer is over the curve, in pixels; -1 when it is not. */
    private int hover = -1;

    /** The frame of the last curve painted, to turn the pointer's pixel back into an x. */
    private int frameLeft;
    private int frameRight;
    private int frameTop;
    private int frameBottom;

    public RootPipelinePlot(ThemePalette palette) {
        this.palette = palette;
        setOpaque(true);
        setPreferredSize(new Dimension(560, 210));
        setMinimumSize(new Dimension(240, 120));
        // Pointing at the curve reads it: x, and the value there.
        final java.awt.event.MouseAdapter reader = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                final int was = hover;
                hover = mode == Mode.CURVES && e.getX() >= frameLeft && e.getX() <= frameRight
                        && e.getY() >= frameTop && e.getY() <= frameBottom ? e.getX() : -1;
                if (hover != was) {
                    repaint();
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (hover >= 0) {
                    hover = -1;
                    repaint();
                }
            }
        };
        addMouseMotionListener(reader);
        addMouseListener(reader);
    }

    /**
     * What gives the primary's exact value at any x, for reading the curve
     * under the pointer; null for a curve known only by its samples.
     */
    public void setExact(java.util.function.DoubleUnaryOperator exact) {
        this.exact = exact;
    }

    /** Words instead of a picture: before anything is typed, or when there is nothing to draw. */
    public void showMessage(String text) {
        mode = Mode.MESSAGE;
        message = text == null ? "" : text;
        repaint();
    }

    /**
     * A curve against x, a second one faintly on its own scale, a marked x.
     *
     * @param secondary drawn behind the primary against a right-hand axis; may be null
     * @param mark      an x worth pointing at (the typical value), NaN for none
     */
    public void showCurves(String title, String xLabel, double[] xs, Series primary, Series secondary,
                           double mark, String markLabel) {
        showCurves(title, xLabel, xs, primary, secondary, mark, Double.NaN, markLabel);
    }

    /**
     * The same, with the primary's exact value at the marked x: read off the
     * nearest sample instead, it would disagree in the third digit with the
     * value the notes give for the same point.
     */
    public void showCurves(String title, String xLabel, double[] xs, Series primary, Series secondary,
                           double mark, double markValue, String markLabel) {
        this.markValue = markValue;
        this.mode = Mode.CURVES;
        this.title = title == null ? "" : title;
        this.xLabel = xLabel == null ? "" : xLabel;
        this.xs = xs == null ? new double[0] : xs.clone();
        this.primary = primary;
        this.secondary = secondary;
        this.mark = mark;
        this.markLabel = markLabel == null ? "" : markLabel;
        repaint();
    }

    /** Bars, largest share first, each with its share of the whole. */
    public void showBars(String title, List<Bar> bars) {
        showBars(title, bars, true);
    }

    /**
     * Bars, largest first.
     *
     * @param percent false when the bars are not parts of a whole, only sizes
     *                against the largest: no percentage is written then
     */
    public void showBars(String title, List<Bar> bars, boolean percent) {
        this.percent = percent;
        this.mode = Mode.BARS;
        this.title = title == null ? "" : title;
        final List<Bar> sorted = new ArrayList<>(bars == null ? List.of() : bars);
        sorted.sort(Comparator.comparingDouble((Bar b) -> -b.share()));
        this.bars = sorted;
        repaint();
    }

    /* ------------------------------------------------------------------ */
    /* Painting                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    protected void paintComponent(Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(palette.getTerminalBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 11)
                                        : getFont().deriveFont(11f));
            switch (mode) {
                case CURVES -> paintCurves(g);
                case BARS -> paintBars(g);
                default -> paintMessage(g);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintMessage(Graphics2D g) {
        paintMessage(g, message);
    }

    private void paintMessage(Graphics2D g, String text) {
        g.setColor(palette.getTextSecondary());
        final FontMetrics fm = g.getFontMetrics();
        final String[] lines = text.split("\n");
        int y = (getHeight() - lines.length * fm.getHeight()) / 2 + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, Math.max(8, (getWidth() - fm.stringWidth(line)) / 2), y);
            y += fm.getHeight();
        }
    }

    private void paintTitle(Graphics2D g, int left) {
        g.setColor(palette.getTextSecondary());
        g.drawString(title, left, 14);
    }

    private void paintCurves(Graphics2D g) {
        final FontMetrics fm = g.getFontMetrics();
        if (primary == null || xs.length < 2) {
            paintMessage(g, "Nothing to draw yet.");
            return;
        }
        final double[] yRange = finiteRange(primary.ys());
        if (yRange == null) {
            g.setColor(palette.getLogWarnText());
            final String all = "It is not a number anywhere across " + RootFormula.shortNumber(xs[0])
                + " to " + RootFormula.shortNumber(xs[xs.length - 1]) + ".";
            g.drawString(all, Math.max(8, (getWidth() - fm.stringWidth(all)) / 2), getHeight() / 2);
            paintTitle(g, 8);
            return;
        }
        final double[] yTicks = ticks(yRange[0], yRange[1]);
        int labelWidth = 0;
        for (double t : yTicks) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(RootFormula.shortNumber(t)));
        }
        final double[] y2Range = secondary == null ? null : finiteRange(secondary.ys());
        final double[] y2Ticks = y2Range == null ? new double[0] : ticks(y2Range[0], y2Range[1]);
        int rightWidth = 10;
        for (double t : y2Ticks) {
            rightWidth = Math.max(rightWidth, fm.stringWidth(RootFormula.shortNumber(t)) + 8);
        }

        final int left = labelWidth + 14;
        final int right = getWidth() - rightWidth;
        final int top = 24;
        final int bottom = getHeight() - fm.getHeight() - 16;
        if (right - left < 40 || bottom - top < 30) {
            return;
        }
        frameLeft = left;
        frameRight = right;
        frameTop = top;
        frameBottom = bottom;
        final double x0 = xs[0];
        final double x1 = xs[xs.length - 1];
        final double ylo = yTicks[0];
        final double yhi = yTicks[yTicks.length - 1];

        // The grid and the axes.
        final Color grid = withAlpha(palette.getBorder(), 90);
        g.setStroke(new BasicStroke(1f));
        for (double t : yTicks) {
            final int y = (int) Math.round(bottom - (t - ylo) / (yhi - ylo) * (bottom - top));
            g.setColor(grid);
            g.drawLine(left, y, right, y);
            g.setColor(palette.getTextSecondary());
            final String text = RootFormula.shortNumber(t);
            g.drawString(text, left - 6 - fm.stringWidth(text), y + fm.getAscent() / 2 - 1);
        }
        for (double t : ticks(x0, x1)) {
            if (t < x0 - 1e-12 * Math.abs(x1 - x0) || t > x1 + 1e-12 * Math.abs(x1 - x0)) {
                continue;
            }
            final int x = (int) Math.round(left + (t - x0) / (x1 - x0) * (right - left));
            g.setColor(grid);
            g.drawLine(x, top, x, bottom);
            g.setColor(palette.getTextSecondary());
            final String text = RootFormula.shortNumber(t);
            g.drawString(text, x - fm.stringWidth(text) / 2, bottom + fm.getAscent() + 3);
        }
        g.setColor(palette.getTextSecondary());
        g.drawString(xLabel, right - fm.stringWidth(xLabel), bottom + fm.getAscent() + fm.getHeight() + 1);

        // The secondary curve first, faint, on its own scale at the right.
        if (secondary != null && y2Range != null) {
            final double lo2 = y2Ticks[0];
            final double hi2 = y2Ticks[y2Ticks.length - 1];
            g.setColor(withAlpha(secondary.color(), 120));
            for (double t : y2Ticks) {
                final int y = (int) Math.round(bottom - (t - lo2) / (hi2 - lo2) * (bottom - top));
                g.drawString(RootFormula.shortNumber(t), right + 6, y + fm.getAscent() / 2 - 1);
            }
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10f, new float[]{5f, 4f}, 0f));
            final java.awt.Shape was = g.getClip();
            g.clipRect(left, top - 1, right - left + 1, bottom - top + 2);
            g.draw(path(secondary.ys(), left, right, top, bottom, x0, x1, lo2, hi2));
            g.setClip(was);
        }

        // The primary curve, broken where it is not a number, kept inside the frame.
        g.setColor(primary.color());
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        final java.awt.Shape was = g.getClip();
        g.clipRect(left, top - 1, right - left + 1, bottom - top + 2);
        g.draw(path(primary.ys(), left, right, top, bottom, x0, x1, ylo, yhi));
        g.setClip(was);

        // Where it is not a number: a red band on the axis, and where.
        int bad = 0;
        double firstBad = Double.NaN;
        double lastBad = Double.NaN;
        final Color nan = palette.getError();
        for (int i = 0; i < xs.length; i++) {
            final double y = primary.ys()[i];
            if (Double.isFinite(y)) {
                continue;
            }
            bad++;
            if (Double.isNaN(firstBad)) {
                firstBad = xs[i];
            }
            lastBad = xs[i];
            final int x = (int) Math.round(left + (xs[i] - x0) / (x1 - x0) * (right - left));
            g.setColor(withAlpha(nan, 70));
            g.fillRect(x - 1, top, 3, bottom - top);
            g.setColor(nan);
            g.fillRect(x - 1, bottom - 4, 3, 5);
        }

        // The typical value, and what the curve gives there.
        if (Double.isFinite(mark) && mark >= Math.min(x0, x1) && mark <= Math.max(x0, x1)) {
            final int x = (int) Math.round(left + (mark - x0) / (x1 - x0) * (right - left));
            g.setColor(withAlpha(palette.getTextSecondary(), 160));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{3f, 3f}, 0f));
            g.drawLine(x, top, x, bottom);
            final double at = Double.isFinite(markValue) ? markValue : valueAt(primary.ys(), mark);
            if (Double.isFinite(at)) {
                final int y = (int) Math.round(bottom - (at - ylo) / (yhi - ylo) * (bottom - top));
                g.setColor(primary.color());
                g.fillOval(x - 4, y - 4, 8, 8);
                g.setColor(palette.getTextPrimary());
                final String said = markLabel + "  " + RootFormula.shortNumber(at);
                final int textX = x + 8 + fm.stringWidth(said) > right ? x - 8 - fm.stringWidth(said) : x + 8;
                g.drawString(said, textX, Math.max(top + fm.getAscent(), y - 6));
            }
        }

        // The title, the legend, and what was found.
        paintTitle(g, left);
        int legendX = right;
        final List<Series> shown = new ArrayList<>();
        shown.add(primary);
        if (secondary != null) {
            shown.add(secondary);
        }
        for (int k = shown.size() - 1; k >= 0; k--) {
            final Series s = shown.get(k);
            final String name = s.name() + (k == 1 ? " (right)" : "");
            legendX -= fm.stringWidth(name) + 22;
            g.setColor(k == 1 ? withAlpha(s.color(), 150) : s.color());
            g.fillRoundRect(legendX, 6, 12, 4, 2, 2);
            g.setColor(palette.getTextSecondary());
            g.drawString(name, legendX + 16, 14);
        }
        // The pointer's reading: a crosshair, and x with the value there --
        // exact when the curve can be computed, else the nearest sample.
        if (hover >= left && hover <= right) {
            final double x = x0 + (double) (hover - left) / (right - left) * (x1 - x0);
            double y;
            if (exact != null) {
                try {
                    y = exact.applyAsDouble(x);
                } catch (RuntimeException cannot) {
                    y = Double.NaN;
                }
            } else {
                y = valueAt(primary.ys(), x);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(withAlpha(palette.getTextPrimary(), 110));
            g.drawLine(hover, top, hover, bottom);
            final String said = xLabel + " = " + RootFormula.shortNumber(x) + "    "
                + primary.name() + " = " + (Double.isFinite(y) ? RootFormula.shortNumber(y) : "not a number");
            if (Double.isFinite(y) && y >= ylo && y <= yhi) {
                final int py = (int) Math.round(bottom - (y - ylo) / (yhi - ylo) * (bottom - top));
                g.setColor(withAlpha(palette.getTextPrimary(), 110));
                g.drawLine(left, py, right, py);
                g.setColor(primary.color());
                g.fillOval(hover - 4, py - 4, 8, 8);
            }
            final int boxW = fm.stringWidth(said) + 12;
            final int boxX = hover + 10 + boxW > right ? hover - 10 - boxW : hover + 10;
            final int boxY = bottom - fm.getHeight() - 10;
            g.setColor(withAlpha(palette.getBackgroundSurface(), 235));
            g.fillRoundRect(boxX, boxY, boxW, fm.getHeight() + 6, 8, 8);
            g.setColor(withAlpha(palette.getBorder(), 200));
            g.drawRoundRect(boxX, boxY, boxW, fm.getHeight() + 6, 8, 8);
            g.setColor(Double.isFinite(y) ? palette.getTextPrimary() : palette.getError());
            g.drawString(said, boxX + 6, boxY + fm.getAscent() + 3);
        }

        if (bad > 0) {
            g.setColor(nan);
            // One point says where; a stretch says how much and from where to where.
            final String said = bad == 1
                ? "not a number at " + xLabel + " = " + RootFormula.shortNumber(firstBad)
                : String.format(Locale.ROOT, "not a number on %s of the range, between %s and %s",
                    100.0 * bad / xs.length < 1 ? bad + " points" : String.format(Locale.ROOT, "%.0f%%", 100.0 * bad / xs.length),
                    RootFormula.shortNumber(firstBad), RootFormula.shortNumber(lastBad));
            g.drawString(said, left + 6, top + fm.getAscent() + 2);
        }
    }

    private void paintBars(Graphics2D g) {
        final FontMetrics fm = g.getFontMetrics();
        paintTitle(g, 8);
        if (bars.isEmpty()) {
            g.setColor(palette.getTextSecondary());
            g.drawString("Nothing to show.", 8, 34);
            return;
        }
        int labelWidth = 0;
        for (Bar b : bars) {
            labelWidth = Math.max(labelWidth, fm.stringWidth(b.label()));
        }
        final int left = 8 + labelWidth + 8;
        final int top = 24;
        final int rowHeight = Math.max(16, Math.min(26, (getHeight() - top - 6) / Math.max(1, bars.size())));
        final int textRoom = 150;
        final int width = Math.max(30, getWidth() - left - textRoom);
        for (int k = 0; k < bars.size(); k++) {
            final Bar b = bars.get(k);
            final int y = top + k * rowHeight;
            if (y + rowHeight > getHeight()) {
                g.setColor(palette.getTextSecondary());
                g.drawString("... " + (bars.size() - k) + " more", 8, y + fm.getAscent());
                break;
            }
            final int h = Math.max(8, rowHeight - 8);
            final int length = (int) Math.round(Math.max(0, Math.min(1, b.share())) * width);
            g.setColor(withAlpha(palette.getBorder(), 80));
            g.fillRoundRect(left, y + (rowHeight - h) / 2, width, h, 6, 6);
            g.setColor(k == 0 ? palette.getAccent() : withAlpha(palette.getAccent(), 150));
            g.fillRoundRect(left, y + (rowHeight - h) / 2, Math.max(2, length), h, 6, 6);
            g.setColor(palette.getTextPrimary());
            g.drawString(b.label(), left - 8 - fm.stringWidth(b.label()), y + rowHeight / 2 + fm.getAscent() / 2 - 1);
            final String said = RootFormula.shortNumber(b.value())
                + (percent ? String.format(Locale.ROOT, "   %.0f%%", 100 * b.share()) : "");
            g.setColor(palette.getTextSecondary());
            g.drawString(said, left + width + 8, y + rowHeight / 2 + fm.getAscent() / 2 - 1);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Geometry                                                            */
    /* ------------------------------------------------------------------ */

    /** The polyline of a series, broken where it is not a number. */
    private Path2D path(double[] ys, int left, int right, int top, int bottom,
                        double x0, double x1, double lo, double hi) {
        final Path2D.Double out = new Path2D.Double();
        boolean drawing = false;
        final double span = hi - lo;
        for (int i = 0; i < xs.length && i < ys.length; i++) {
            if (!Double.isFinite(ys[i])) {
                drawing = false;
                continue;
            }
            final double x = left + (xs[i] - x0) / (x1 - x0) * (right - left);
            // Clipped a little past the frame, so a spike leaves it rather than wraps.
            final double clipped = Math.max(lo - span, Math.min(hi + span, ys[i]));
            final double y = bottom - (clipped - lo) / span * (bottom - top);
            if (drawing) {
                out.lineTo(x, y);
            } else {
                out.moveTo(x, y);
                drawing = true;
            }
        }
        return out;
    }

    /** The series' value at x, read off the nearest sample. */
    private double valueAt(double[] ys, double x) {
        int best = 0;
        for (int i = 1; i < xs.length; i++) {
            if (Math.abs(xs[i] - x) < Math.abs(xs[best] - x)) {
                best = i;
            }
        }
        return best < ys.length ? ys[best] : Double.NaN;
    }

    /**
     * The finite range of a series, or null when nothing in it is finite.
     *
     * The extremes are cut at the 1st and 99th percentiles when a few values
     * dwarf the rest: a pole at one end would otherwise flatten the whole
     * curve into a line, which hides the shape the picture is for.
     */
    static double[] finiteRange(double[] ys) {
        final List<Double> finite = new ArrayList<>();
        for (double y : ys) {
            if (Double.isFinite(y)) {
                finite.add(y);
            }
        }
        if (finite.isEmpty()) {
            return null;
        }
        finite.sort(Double::compare);
        double lo = finite.get(0);
        double hi = finite.get(finite.size() - 1);
        if (finite.size() > 20) {
            final double p1 = finite.get((int) (0.01 * (finite.size() - 1)));
            final double p99 = finite.get((int) Math.ceil(0.99 * (finite.size() - 1)));
            final double core = p99 - p1;
            if (core > 0 && (hi - lo) > 20 * core) {
                lo = p1;
                hi = p99;
            }
        }
        if (hi - lo < 1e-300 || hi - lo < 1e-12 * Math.max(Math.abs(lo), Math.abs(hi))) {
            final double pad = Math.abs(lo) > 0 ? 0.1 * Math.abs(lo) : 1.0;
            return new double[]{lo - pad, hi + pad};
        }
        return new double[]{lo, hi};
    }

    /** Round numbers covering [lo, hi]: 1, 2 or 5 times a power of ten apart. */
    static double[] ticks(double lo, double hi) {
        if (!(hi > lo)) {
            return new double[]{lo, lo + 1};
        }
        final double raw = (hi - lo) / 4;
        final double power = Math.pow(10, Math.floor(Math.log10(raw)));
        final double fraction = raw / power;
        final double step = (fraction < 1.5 ? 1 : fraction < 3.5 ? 2 : fraction < 7.5 ? 5 : 10) * power;
        final double first = Math.floor(lo / step) * step;
        final double last = Math.ceil(hi / step) * step;
        final int count = (int) Math.round((last - first) / step) + 1;
        final double[] out = new double[Math.max(2, Math.min(count, 12))];
        for (int k = 0; k < out.length; k++) {
            final double t = first + k * step;
            out[k] = Math.abs(t) < step * 1e-9 ? 0 : t;
        }
        return out;
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
