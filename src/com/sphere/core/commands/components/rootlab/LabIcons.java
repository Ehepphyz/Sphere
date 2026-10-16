package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * The ROOT Lab's icons, drawn rather than loaded: they stay sharp at any
 * display scale and take the theme's colours. One glyph per kind of thing a
 * CERN data file holds, and one per state a test can be in.
 */
public final class LabIcons implements Icon {

    public enum Glyph {
        ROOT_FILE, REMOTE_FILE, DATA_FILE, FOLDER, HISTOGRAM, PROFILE, GRAPH, TREE, BRANCH, FUNCTION, MATRIX,
        OBJECT, SUITE, PASSED, FAILED, SKIPPED, ERROR, RUNNING, PENDING, WARNING
    }

    private static final int SIZE = 16;
    private final Glyph glyph;

    private LabIcons(Glyph glyph) {
        this.glyph = glyph;
    }

    public static Icon of(Glyph glyph) {
        return new LabIcons(glyph);
    }

    /** The glyph for a ROOT class name. */
    public static Glyph forClass(String cls) {
        if (cls == null) {
            return Glyph.OBJECT;
        }
        if (cls.startsWith("TProfile")) {
            return Glyph.PROFILE;
        }
        if (cls.startsWith("TH") || cls.startsWith("THnSparse") || cls.startsWith("THn")) {
            return Glyph.HISTOGRAM;
        }
        if (cls.startsWith("TGraph") || cls.startsWith("TMultiGraph") || cls.startsWith("TEfficiency")) {
            return Glyph.GRAPH;
        }
        if (cls.equals("TTree") || cls.startsWith("TNtuple") || cls.contains("RNTuple") || cls.equals("TChain")) {
            return Glyph.TREE;
        }
        if (cls.startsWith("TF1") || cls.startsWith("TF2") || cls.startsWith("TF3") || cls.startsWith("TFormula")) {
            return Glyph.FUNCTION;
        }
        if (cls.startsWith("TMatrix") || cls.startsWith("TVector")) {
            return Glyph.MATRIX;
        }
        if (cls.startsWith("TDirectory")) {
            return Glyph.FOLDER;
        }
        return Glyph.OBJECT;
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    private static final Color GREEN = new Color(0x3FB950);
    private static final Color RED = new Color(0xF85149);
    private static final Color AMBER = new Color(0xD29922);
    private static final Color BLUE = new Color(0x58A6FF);
    private static final Color VIOLET = new Color(0xBC8CFF);
    private static final Color TEAL = new Color(0x39C5CF);
    private static final Color LEAF = new Color(0x7EE787);

    @Override
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            final Color ink = ImagingTheme.text();
            switch (glyph) {
                case ROOT_FILE, REMOTE_FILE, DATA_FILE -> file(g, glyph == Glyph.DATA_FILE ? TEAL : BLUE, glyph == Glyph.REMOTE_FILE);
                case FOLDER -> folder(g);
                case HISTOGRAM -> bars(g, BLUE);
                case PROFILE -> profile(g);
                case GRAPH -> graph(g);
                case TREE -> tree(g);
                case BRANCH -> leaf(g);
                case FUNCTION -> function(g);
                case MATRIX -> matrix(g, ink);
                case OBJECT -> object(g, ink);
                case SUITE -> suite(g, ink);
                case PASSED -> disc(g, GREEN, "check");
                case FAILED -> disc(g, RED, "cross");
                case ERROR -> disc(g, RED, "bang");
                case WARNING -> disc(g, AMBER, "bang");
                case SKIPPED -> ring(g, ImagingTheme.subduedText());
                case RUNNING -> running(g);
                case PENDING -> ring(g, new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 90));
            }
        } finally {
            g.dispose();
        }
    }

    private static void file(Graphics2D g, Color color, boolean remote) {
        final Path2D p = new Path2D.Double();
        p.moveTo(3, 1.5);
        p.lineTo(10, 1.5);
        p.lineTo(13.5, 5);
        p.lineTo(13.5, 14.5);
        p.lineTo(3, 14.5);
        p.closePath();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g.fill(p);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(p);
        g.drawLine(10, 2, 10, 5);
        g.drawLine(10, 5, 13, 5);
        if (remote) {
            g.setColor(TEAL);
            g.fillOval(8, 8, 7, 7);
            g.setColor(ImagingTheme.surface());
            g.drawLine(9, 11, 14, 11);
            g.drawArc(9, 8, 5, 7, 90, 180);
        } else {
            g.drawLine(5, 8, 11, 8);
            g.drawLine(5, 11, 11, 11);
        }
    }

    private static void folder(Graphics2D g) {
        g.setColor(AMBER);
        g.fillRoundRect(1, 4, 14, 10, 3, 3);
        g.fillRoundRect(1, 2, 6, 4, 2, 2);
    }

    private static void bars(Graphics2D g, Color color) {
        g.setColor(color);
        final int[] h = {4, 8, 12, 9, 5};
        for (int i = 0; i < h.length; i++) {
            g.fillRect(1 + i * 3, 15 - h[i], 2, h[i]);
        }
    }

    private static void profile(Graphics2D g) {
        g.setColor(VIOLET);
        g.setStroke(new BasicStroke(1.3f));
        final int[] yy = {11, 9, 6, 7, 4};
        for (int i = 0; i < yy.length; i++) {
            final int x = 2 + i * 3;
            g.drawLine(x, yy[i] - 2, x, yy[i] + 2);
            g.fillOval(x - 1, yy[i] - 1, 3, 3);
        }
    }

    private static void graph(Graphics2D g) {
        g.setColor(TEAL);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        final Path2D p = new Path2D.Double();
        p.moveTo(1, 13);
        p.lineTo(5, 8);
        p.lineTo(9, 10);
        p.lineTo(14, 3);
        g.draw(p);
    }

    private static void tree(Graphics2D g) {
        g.setColor(new Color(0x9C6B30));
        g.fillRect(7, 9, 2, 6);
        g.setColor(GREEN);
        g.fillOval(2, 1, 12, 10);
    }

    private static void leaf(Graphics2D g) {
        g.setColor(LEAF);
        final Path2D p = new Path2D.Double();
        p.moveTo(2, 14);
        p.curveTo(2, 4, 8, 2, 14, 2);
        p.curveTo(14, 9, 9, 14, 2, 14);
        g.fill(p);
        g.setColor(new Color(0x2EA043));
        g.setStroke(new BasicStroke(1f));
        g.drawLine(3, 13, 11, 5);
    }

    private static void function(Graphics2D g) {
        g.setColor(AMBER);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        final Path2D p = new Path2D.Double();
        p.moveTo(1, 13);
        for (int x = 1; x <= 15; x++) {
            p.lineTo(x, 8 - 5 * Math.exp(-(x - 8) * (x - 8) / 8.0) + 4);
        }
        g.draw(p);
    }

    private static void matrix(Graphics2D g, Color ink) {
        g.setColor(ink);
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(3, 2, 2, 2);
        g.drawLine(2, 2, 2, 14);
        g.drawLine(2, 14, 3, 14);
        g.drawLine(13, 2, 14, 2);
        g.drawLine(14, 2, 14, 14);
        g.drawLine(14, 14, 13, 14);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                g.fillRect(4 + 3 * i, 4 + 3 * j, 2, 2);
            }
        }
    }

    private static void object(Graphics2D g, Color ink) {
        g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 150));
        g.setStroke(new BasicStroke(1.2f));
        g.drawRoundRect(2, 3, 12, 10, 3, 3);
        g.drawLine(5, 7, 11, 7);
        g.drawLine(5, 10, 9, 10);
    }

    private static void suite(Graphics2D g, Color ink) {
        g.setColor(BLUE);
        g.fillRoundRect(1, 2, 14, 12, 3, 3);
        g.setColor(ImagingTheme.surface());
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(4, 6, 6, 8);
        g.drawLine(6, 8, 4, 10);
        g.drawLine(8, 10, 12, 10);
    }

    private static void disc(Graphics2D g, Color color, String mark) {
        g.setColor(color);
        g.fillOval(1, 1, 14, 14);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (mark) {
            case "check" -> {
                g.drawLine(4, 8, 7, 11);
                g.drawLine(7, 11, 12, 5);
            }
            case "cross" -> {
                g.drawLine(5, 5, 11, 11);
                g.drawLine(11, 5, 5, 11);
            }
            default -> {
                g.drawLine(8, 4, 8, 9);
                g.fillOval(7, 11, 2, 2);
            }
        }
    }

    private static void ring(Graphics2D g, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(2, 2, 12, 12);
    }

    private static void running(Graphics2D g) {
        g.setColor(BLUE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        final int phase = (int) ((System.currentTimeMillis() / 100) % 12);
        g.drawArc(2, 2, 12, 12, 90 - phase * 30, 270);
    }
}
