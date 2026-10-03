package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.lundplane.LundDeclustering;
import com.sphere.core.fjcontrib.lundplane.LundGenerator;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * The primary Lund plane of a sample: the density of declusterings in
 * (ln 1/Delta, ln kt) per jet, as in the figures of arXiv:1807.04758.
 *
 * <p>At leading logarithm the density is flat, 2 C_R alpha_s / pi: about 0.3
 * for gluon jets at the LHC, 0.13 for quark jets, higher at low kt where
 * alpha_s runs, and an island of hard two-prong splittings for a boosted W
 * or top. {@link #leadingLogLevel} gives the flat value for comparison.
 */
public final class LundMap {

    /** The filled plane: density[ix][iy] per unit area of the plane, per jet. */
    public record Map2D(double xmin, double xmax, double ymin, double ymax, int nx, int ny, double[][] density,
                        int jets, long declusterings, String definition) {

        /** x edges then y edges, and the density row by row in x: what the other engines read. */
        public double[] flat() {
            final double[] out = new double[nx * ny];
            for (int ix = 0; ix < nx; ix++) System.arraycopy(density[ix], 0, out, ix * ny, ny);
            return out;
        }

        public double[] xEdges() {
            final double[] e = new double[nx + 1];
            for (int k = 0; k <= nx; k++) e[k] = xmin + (xmax - xmin) * k / nx;
            return e;
        }

        public double[] yEdges() {
            final double[] e = new double[ny + 1];
            for (int k = 0; k <= ny; k++) e[k] = ymin + (ymax - ymin) * k / ny;
            return e;
        }

        /** The mean density over the cells that are well inside the kinematic region. */
        public double plateau() {
            double s = 0;
            int n = 0;
            for (int ix = 0; ix < nx; ix++) {
                for (int iy = 0; iy < ny; iy++) {
                    if (density[ix][iy] > 0) {
                        s += density[ix][iy];
                        n++;
                    }
                }
            }
            return n == 0 ? 0 : s / n;
        }
    }

    private LundMap() {
    }

    /** 2 C_R alpha_s / pi, the flat density at leading logarithm. */
    public static double leadingLogLevel(double cr, double alphaS) {
        return 2 * cr * alphaS / Math.PI;
    }

    public static Map2D fill(List<List<PseudoJet>> events, JetDefinition def, int njets, double ptmin, int nx, int ny,
                             double xmax, double ymin, double ymax, int threads) {
        final double xmin = 0;
        final List<double[][]> parts = Parallel.map(events.size(), e -> {
            final double[][] h = new double[nx][ny];
            int jets = 0;
            long decl = 0;
            final LundGenerator gen = new LundGenerator();
            final List<PseudoJet> all = PseudoJet.sortedByPt(new ClusterSequence(events.get(e), def).inclusiveJets(ptmin));
            for (int j = 0; j < Math.min(njets, all.size()); j++) {
                jets++;
                for (LundDeclustering d : gen.result(all.get(j))) {
                    final double x = Math.log(1.0 / d.Delta());
                    final double y = Math.log(d.kt());
                    final int ix = (int) Math.floor((x - xmin) / (xmax - xmin) * nx);
                    final int iy = (int) Math.floor((y - ymin) / (ymax - ymin) * ny);
                    if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) continue;
                    h[ix][iy] += 1;
                    decl++;
                }
            }
            final double[][] out = new double[nx + 1][];
            System.arraycopy(h, 0, out, 0, nx);
            out[nx] = new double[]{jets, decl};
            return out;
        }, def.plugin() == null ? threads : 1);
        final double[][] sum = new double[nx][ny];
        int jets = 0;
        long decl = 0;
        for (double[][] p : parts) {
            for (int ix = 0; ix < nx; ix++) for (int iy = 0; iy < ny; iy++) sum[ix][iy] += p[ix][iy];
            jets += (int) p[nx][0];
            decl += (long) p[nx][1];
        }
        final double cell = (xmax - xmin) / nx * (ymax - ymin) / ny;
        if (jets > 0) for (double[] row : sum) for (int iy = 0; iy < ny; iy++) row[iy] /= jets * cell;
        return new Map2D(xmin, xmax, ymin, ymax, nx, ny, sum, jets, decl, def.description());
    }

    /** The plane as a heat map, with its colour scale and the leading-log levels marked on it. */
    public static BufferedImage render(Map2D m, String title) {
        final int w = 900;
        final int h = 640;
        final int left = 70;
        final int top = 50;
        final int pw = 640;
        final int ph = 520;
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        double max = 0;
        for (double[] row : m.density()) for (double v : row) max = Math.max(max, v);
        if (max <= 0) max = 1;
        final double cw = (double) pw / m.nx();
        final double ch = (double) ph / m.ny();
        for (int ix = 0; ix < m.nx(); ix++) {
            for (int iy = 0; iy < m.ny(); iy++) {
                final double v = m.density()[ix][iy];
                if (v <= 0) continue;
                g.setColor(colour(v / max));
                g.fillRect(left + (int) (ix * cw), top + ph - (int) ((iy + 1) * ch), (int) Math.ceil(cw), (int) Math.ceil(ch));
            }
        }
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(left, top, pw, ph);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int k = 0; k <= 6; k++) {
            final double x = m.xmin() + (m.xmax() - m.xmin()) * k / 6;
            g.drawString(String.format(Locale.ROOT, "%.1f", x), left + (int) (pw * k / 6.0) - 8, top + ph + 16);
        }
        for (int k = 0; k <= 5; k++) {
            final double y = m.ymin() + (m.ymax() - m.ymin()) * k / 5;
            g.drawString(String.format(Locale.ROOT, "%.1f", y), left - 36, top + ph - (int) (ph * k / 5.0) + 4);
        }
        g.drawString("ln(1/Δ)", left + pw / 2 - 20, h - 18);
        g.drawString("ln(kt/GeV)", 6, top - 10);
        // the colour scale
        final int sx = left + pw + 40;
        for (int k = 0; k < ph; k++) {
            g.setColor(colour(1.0 - (double) k / ph));
            g.fillRect(sx, top + k, 22, 1);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawRect(sx, top, 22, ph);
        for (int k = 0; k <= 4; k++) {
            g.drawString(String.format(Locale.ROOT, "%.3f", max * (1 - k / 4.0)), sx + 28, top + (int) (ph * k / 4.0) + 4);
        }
        // where the leading-log plateaus of quark and gluon jets sit on the scale
        for (double[] level : new double[][]{{leadingLogLevel(4.0 / 3.0, 0.12), 0}, {leadingLogLevel(3.0, 0.12), 1}}) {
            if (level[0] > max) continue;
            final int yy = top + (int) (ph * (1 - level[0] / max));
            g.setColor(Color.BLACK);
            g.drawLine(sx - 6, yy, sx + 28, yy);
            g.drawString(level[1] == 0 ? "q LL" : "g LL", sx + 64, yy + 4);
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString(title, left, 24);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString(String.format(Locale.ROOT, "%d jets, %d primary declusterings; density per jet per unit area",
            m.jets(), m.declusterings()), left, 40);
        g.dispose();
        return img;
    }

    /** A perceptual blue-to-yellow scale. */
    private static Color colour(double t) {
        final double u = Math.max(0, Math.min(1, t));
        final double[][] stops = {{0.267, 0.005, 0.329}, {0.229, 0.322, 0.546}, {0.128, 0.567, 0.551},
            {0.369, 0.789, 0.383}, {0.993, 0.906, 0.144}};
        final double pos = u * (stops.length - 1);
        final int i = Math.min(stops.length - 2, (int) pos);
        final double f = pos - i;
        return new Color((float) (stops[i][0] + f * (stops[i + 1][0] - stops[i][0])),
            (float) (stops[i][1] + f * (stops[i + 1][1] - stops[i][1])),
            (float) (stops[i][2] + f * (stops[i + 1][2] - stops[i][2])));
    }
}
