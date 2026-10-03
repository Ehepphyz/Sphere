package com.sphere.core.commands;

import com.sphere.components.imaging.ImageFileIO;
import com.sphere.core.rootbackend.RootDemos.Outcome;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The pictures of a run of ROOT's demos, looked at: all of them on one sheet,
 * and one picture against the same one from an earlier run.
 */
final class RootDemoSheet {

    private static final int CELL_W = 260;
    private static final int CELL_H = 200;
    private static final int LABEL_H = 18;
    private static final int GAP = 8;
    private static final int COLUMNS = 5;

    /** A picture compared with an earlier one. */
    record Difference(double share, boolean sizeChanged, BufferedImage picture) {
    }

    private RootDemoSheet() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Every canvas of the run, one cell each, with the demo and canvas under
     * it; a demo that failed gets a red cell saying why, so the sheet shows the
     * whole run at a glance. One format per canvas: png when there is one.
     */
    /** One cell of the sheet: its caption, its picture or the words standing for it, and whether it failed. */
    private record Cell(String caption, Path picture, String message, boolean failed) {
    }

    static BufferedImage contactSheet(List<Outcome> outcomes) {
        final List<Cell> cells = new ArrayList<>();
        for (Outcome o : outcomes) {
            final List<Path> shown = onePerCanvas(o.pictures());
            if (shown.isEmpty()) {
                // A demo that ran well and drew nothing is grey, with the reason; a failure is red.
                cells.add(new Cell(o.demo().label(), null, o.ok()
                    ? "no canvas: " + (o.note() == null ? "it left none" : o.note())
                    : o.errors().isEmpty() ? "failed" : o.errors().get(0), !o.ok()));
            }
            final String prefix = "demo_" + o.demo().label().replaceAll("[^A-Za-z0-9_.-]", "_") + "_";
            for (Path p : shown) {
                final String stem = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                final String canvas = stem.startsWith(prefix) ? stem.substring(prefix.length()) : stem;
                cells.add(new Cell(o.demo().label() + " / " + canvas, p, null, !o.ok()));
            }
        }
        if (cells.isEmpty()) return null;
        final int rows = (cells.size() + COLUMNS - 1) / COLUMNS;
        final int width = GAP + COLUMNS * (CELL_W + GAP);
        final int height = GAP + rows * (CELL_H + LABEL_H + GAP);
        final BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = sheet.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xF4F4F4));
            g.fillRect(0, 0, width, height);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            for (int k = 0; k < cells.size(); k++) {
                final int x = GAP + (k % COLUMNS) * (CELL_W + GAP);
                final int y = GAP + (k / COLUMNS) * (CELL_H + LABEL_H + GAP);
                final Cell cell = cells.get(k);
                final Color ink = cell.failed() ? new Color(0xB00020) : new Color(0x606060);
                g.setColor(cell.picture() == null && !cell.failed() ? new Color(0xEBEBEB) : Color.WHITE);
                g.fillRect(x, y, CELL_W, CELL_H);
                final BufferedImage picture = cell.picture() == null ? null
                    : ImageFileIO.readPreview(cell.picture().toFile(), Math.max(CELL_W, CELL_H));
                if (picture != null) {
                    final double s = Math.min((double) CELL_W / picture.getWidth(), (double) CELL_H / picture.getHeight());
                    final int w = (int) Math.round(picture.getWidth() * s);
                    final int h = (int) Math.round(picture.getHeight() * s);
                    g.drawImage(picture, x + (CELL_W - w) / 2, y + (CELL_H - h) / 2, w, h, null);
                } else {
                    g.setColor(ink);
                    drawWrapped(g, cell.message() == null ? "unreadable picture" : cell.message(),
                        x + 8, y + 20, CELL_W - 16);
                }
                g.setColor(cell.failed() ? ink : new Color(0xC8C8C8));
                g.setStroke(new BasicStroke(cell.failed() ? 3f : 1f));
                g.drawRect(x, y, CELL_W - 1, CELL_H - 1);
                g.setColor(cell.failed() ? ink : new Color(0x303030));
                g.drawString(fit(g, cell.caption(), CELL_W), x, y + CELL_H + LABEL_H - 5);
            }
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /** Of the formats a canvas was written in, the one to show: png first, then what can be read. */
    private static List<Path> onePerCanvas(List<Path> pictures) {
        final java.util.Map<String, Path> chosen = new java.util.LinkedHashMap<>();
        for (Path p : pictures) {
            final String name = p.getFileName().toString();
            final String stem = name.replaceFirst("\\.[^.]+$", "");
            final Path had = chosen.get(stem);
            if (had == null || name.endsWith(".png")) chosen.put(stem, p);
        }
        return new ArrayList<>(chosen.values());
    }

    /**
     * The share of pixels that differ between two pictures, and a picture of
     * where: the new one faded to grey, the differing pixels in red. A picture
     * whose size changed counts as changed everywhere.
     */
    static Difference difference(Path before, Path after) throws IOException {
        final BufferedImage a = javax.imageio.ImageIO.read(before.toFile());
        final BufferedImage b = javax.imageio.ImageIO.read(after.toFile());
        if (a == null || b == null) throw new IOException("not a picture");
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return new Difference(1.0, true, b);
        }
        final BufferedImage out = new BufferedImage(b.getWidth(), b.getHeight(), BufferedImage.TYPE_INT_RGB);
        long differing = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                final int p = a.getRGB(x, y);
                final int q = b.getRGB(x, y);
                final int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)),
                    Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 24) {
                    differing++;
                    out.setRGB(x, y, 0xE00000);
                } else {
                    final int grey = 190 + ((q >> 16 & 255) + (q >> 8 & 255) + (q & 255)) / 12;
                    out.setRGB(x, y, grey << 16 | grey << 8 | grey);
                }
            }
        }
        return new Difference((double) differing / ((long) b.getWidth() * b.getHeight()), false, out);
    }

    private static String fit(Graphics2D g, String text, int width) {
        if (g.getFontMetrics().stringWidth(text) <= width) return text;
        String t = text;
        while (t.length() > 1 && g.getFontMetrics().stringWidth(t + "...") > width) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static void drawWrapped(Graphics2D g, String text, int x, int y, int width) {
        final StringBuilder line = new StringBuilder();
        int row = 0;
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && g.getFontMetrics().stringWidth(line + " " + word) > width) {
                g.drawString(line.toString(), x, y + 14 * row++);
                line.setLength(0);
                if (row > 10) return;
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) g.drawString(line.toString(), x, y + 14 * row);
    }
}
