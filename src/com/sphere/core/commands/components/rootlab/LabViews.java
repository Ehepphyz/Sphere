package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.rootview.RootAxis;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.core.rootlab.hist.LabAxis;
import com.sphere.core.rootlab.hist.LabHist;

import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.util.Locale;

/** What the two views of the ROOT Lab share: HTML in the theme's colours, and histogram conversions. */
final class LabViews {

    private LabViews() {
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** An HTML pane that follows the theme: its colours are written into its style sheet. */
    static JEditorPane htmlPane() {
        final JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        final HTMLEditorKit kit = new HTMLEditorKit();
        final StyleSheet css = new StyleSheet();
        css.addStyleSheet(kit.getStyleSheet());
        final String text = hex(ImagingTheme.text());
        final String sub = hex(ImagingTheme.subduedText());
        final String accent = hex(ImagingTheme.accent());
        css.addRule("body { font-family: SansSerif; font-size: 10px; color: " + text + "; margin: 6px; }");
        css.addRule("h1 { font-size: 12px; margin: 0 0 4px 0; color: " + text + "; }");
        css.addRule("h2 { font-size: 11px; margin: 8px 0 3px 0; color: " + accent + "; }");
        css.addRule("td { font-size: 10px; padding: 0 6px 1px 0; vertical-align: top; }");
        css.addRule("td.k { color: " + sub + "; white-space: nowrap; }");
        css.addRule(".sub { color: " + sub + "; }");
        css.addRule(".ok { color: #3fb950; }");
        css.addRule(".bad { color: #f85149; }");
        css.addRule(".warn { color: #d29922; }");
        css.addRule("pre { font-family: Monospaced; font-size: 10px; margin: 2px 0; }");
        css.addRule(".mono { font-family: Monospaced; font-size: 9px; }");
        css.addRule("ul { margin-left: 12px; margin-top: 2px; }");
        kit.setStyleSheet(css);
        pane.setEditorKit(kit);
        pane.setBackground(ImagingTheme.surface());
        pane.setOpaque(true);
        return pane;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A two-column table of names and values, the values already HTML. */
    static String table(String... pairs) {
        final StringBuilder b = new StringBuilder("<table cellspacing=0 cellpadding=0>");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] == null || pairs[i].isEmpty()) {
                continue;
            }
            // Swing's HTML ignores cell padding: the gap after the name is written out.
            b.append("<tr><td class=k nowrap>").append(esc(pairs[i])).append("&nbsp;&nbsp;</td><td>")
             .append(pairs[i + 1]).append("</td></tr>");
        }
        return b.append("</table>").toString();
    }

    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f kB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", n / (1024.0 * 1024 * 1024));
    }

    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%,.0f", v);
        }
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /** A histogram of any dimension seen along x: the other axes summed, their tails included. */
    static LabHist projectionX(LabHist h) {
        if (h.dimension == 1) {
            return h;
        }
        final LabAxis x = h.xaxis;
        final LabHist p = new LabHist("TH1D", h.name + "_px", h.title, x, null, null);
        p.sumw2 = new double[p.array.length];
        final int ny = h.dimension > 1 ? h.yaxis.nbins + 1 : 0;
        final int nz = h.dimension > 2 ? h.zaxis.nbins + 1 : 0;
        for (int i = 0; i <= x.nbins + 1; i++) {
            double sum = 0;
            double err2 = 0;
            for (int j = 0; j <= ny; j++) {
                for (int k = 0; k <= nz; k++) {
                    final int bin = h.bin(i, j, k);
                    sum += h.content(bin);
                    err2 += h.errorSq(bin);
                }
            }
            p.array[i] = sum;
            p.sumw2[i] = err2;
        }
        p.entries = h.entries;
        p.tsumw = h.tsumw;
        p.tsumw2 = h.tsumw2;
        p.tsumwx = h.tsumwx;
        p.tsumwx2 = h.tsumwx2;
        return p;
    }

    /** The plot on the right draws Sphere's viewer histograms: a 1D LabHist in that form. */
    static RootHistogram toRootHistogram(LabHist h) {
        final LabHist one = projectionX(h);
        final RootHistogram r = new RootHistogram();
        r.name = h.name;
        r.title = h.title;
        r.className = h.className;
        r.dimensions = 1;
        final RootAxis a = new RootAxis();
        a.name = "xaxis";
        a.title = "";
        a.bins = one.xaxis.nbins;
        a.min = one.xaxis.xmin;
        a.max = one.xaxis.xmax;
        a.edges = one.xaxis.xbins.clone();
        r.xAxis = a;
        r.contents = new double[one.array.length];
        r.sumw2 = new double[one.array.length];
        for (int i = 0; i < one.array.length; i++) {
            r.contents[i] = one.content(i);
            r.sumw2[i] = one.errorSq(i);
        }
        r.entries = one.entries;
        r.tsumw = one.tsumw;
        r.tsumw2 = one.tsumw2;
        r.tsumwx = one.tsumwx;
        r.tsumwx2 = one.tsumwx2;
        return r;
    }
}
