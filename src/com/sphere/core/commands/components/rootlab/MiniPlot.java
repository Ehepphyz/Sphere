package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.core.rootlab.hist.LabHist;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;

/**
 * A small drawing of a one-dimensional histogram for the inspector: the
 * contents as filled bars, the error bars when they are kept, the range of the
 * axis underneath, and the under- and overflow counted at the sides. Enough to
 * see a distribution at a glance in a panel 300 pixels wide; "Plot" sends it to
 * the full plot on the right.
 */
public final class MiniPlot extends JComponent {

    private LabHist hist;
    private String caption = "";

    public MiniPlot() {
        setPreferredSize(new Dimension(260, 100));
        setMinimumSize(new Dimension(120, 80));
        setOpaque(false);
    }

    public void show(LabHist h, String caption) {
        this.hist = h;
        this.caption = caption == null ? "" : caption;
        setVisible(h != null);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (hist == null) {
            return;
        }
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            final int w = getWidth();
            final int h = getHeight();
            g.setColor(ImagingTheme.canvasGround());
            g.fillRoundRect(0, 0, w, h, 8, 8);
            final Font font = ImagingTheme.uiFont(Font.PLAIN, 10f);
            g.setFont(font);
            final FontMetrics fm = g.getFontMetrics();
            final int left = 6;
            final int right = w - 6;
            final int top = 6 + fm.getHeight();
            final int bottom = h - 6 - fm.getHeight();
            final int n = hist.xaxis.nbins;
            double max = 0;
            for (int i = 1; i <= n; i++) {
                max = Math.max(max, hist.content(i) + (hist.sumw2.length > 0 ? hist.error(i) : 0));
            }
            g.setColor(ImagingTheme.subduedText());
            g.drawString(caption, left, 4 + fm.getAscent());
            if (max <= 0 || n <= 0) {
                g.drawString("empty", left, (top + bottom) / 2);
                return;
            }
            final double bw = (right - left) / (double) n;
            final Color accent = ImagingTheme.accent();
            final Color fill = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120);
            for (int i = 1; i <= n; i++) {
                final double v = hist.content(i);
                final int x0 = (int) Math.round(left + (i - 1) * bw);
                final int x1 = (int) Math.round(left + i * bw);
                final int y = (int) Math.round(bottom - (bottom - top) * v / max);
                g.setColor(fill);
                g.fillRect(x0, y, Math.max(1, x1 - x0), bottom - y);
                g.setColor(accent);
                g.drawLine(x0, y, x1, y);
                if (hist.sumw2.length > 0 && bw >= 4) {
                    final double e = hist.error(i);
                    final int yHi = (int) Math.round(bottom - (bottom - top) * (v + e) / max);
                    final int yLo = (int) Math.round(bottom - (bottom - top) * Math.max(0, v - e) / max);
                    final int xc = (x0 + x1) / 2;
                    g.setColor(ImagingTheme.text());
                    g.setStroke(new BasicStroke(0.8f));
                    g.drawLine(xc, yHi, xc, yLo);
                }
            }
            g.setColor(ImagingTheme.border());
            g.drawLine(left, bottom, right, bottom);
            g.setColor(ImagingTheme.subduedText());
            final String lo = String.format(Locale.ROOT, "%.4g", hist.xaxis.xmin);
            final String hi = String.format(Locale.ROOT, "%.4g", hist.xaxis.xmax);
            g.drawString(lo, left, h - 4);
            g.drawString(hi, right - fm.stringWidth(hi), h - 4);
            final double under = hist.content(0);
            final double over = hist.content(n + 1);
            if (under > 0 || over > 0) {
                final String uo = String.format(Locale.ROOT, "under %.0f  over %.0f", under, over);
                g.drawString(uo, (w - fm.stringWidth(uo)) / 2, h - 4);
            }
        } finally {
            g.dispose();
        }
    }
}
