package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.Axis;
import com.sphere.components.rootview.RootScene.AxisStyle;
import com.sphere.components.rootview.RootScene.Cloud3D;
import com.sphere.components.rootview.RootScene.Entry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Graph2D;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootScene.Pave;
import com.sphere.components.rootview.RootScene.Segment;
import com.sphere.components.rootview.RootScene.Shape;
import com.sphere.components.rootview.RootScene.Text;

import java.awt.Color;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * A canvas written as the ROOT macro that draws it again, as
 * TCanvas::SaveAs(".C") writes one: the canvas, its pads, each object built
 * from its numbers (a histogram's bins, a graph's points, a function's
 * formula and parameters), its attributes, the option it was drawn with,
 * and what Sphere's views changed (zoom, log scales, grid, angles).
 *
 * The same code rebuilds a canvas in the ROOT engine, where any function of
 * a context menu Sphere does not do itself can then be called on the very
 * object the user pointed at.
 */
final class RootMacro {

    /** The macro, and the C++ variable of each object, pad and canvas written. */
    record Result(String code, Map<Object, String> names, String canvas) {
    }

    private final StringBuilder b = new StringBuilder(8192);
    private final Map<Object, String> names = new IdentityHashMap<>();
    private final Function<Pad, View> views;
    private final RootScene scene;
    private int counter;

    private RootMacro(RootScene scene, Function<Pad, View> views) {
        this.scene = scene;
        this.views = views;
    }

    /**
     * The statements that draw the scene again (no braces around them): a
     * canvas named canvasName, every object created with new and owned by its
     * pad (kCanDelete), so that deleting the canvas frees all.
     */
    static Result canvas(RootScene scene, Function<Pad, View> views, String canvasName) {
        final RootMacro m = new RootMacro(scene, views);
        m.write(canvasName);
        return new Result(m.b.toString(), m.names, "c_sphere");
    }

    /** A whole macro file, as SaveAs(".C") writes: a function named after the file. */
    static String file(RootScene scene, Function<Pad, View> views, String function) {
        final Result r = canvas(scene, views, scene.name == null || scene.name.isBlank() ? "c1" : scene.name);
        return "// Written by Sphere from the canvas " + (scene.name == null ? "" : scene.name) + "\n"
            + "// Run it with: root " + function + ".C\n\n"
            + "#include \"TCanvas.h\"\n#include \"TH1.h\"\n#include \"TH2.h\"\n#include \"TH3.h\"\n#include \"TProfile.h\"\n"
            + "#include \"TProfile2D.h\"\n#include \"TF1.h\"\n#include \"TF2.h\"\n#include \"TGraph.h\"\n#include \"TGraphErrors.h\"\n"
            + "#include \"TGraphAsymmErrors.h\"\n#include \"TGraph2D.h\"\n#include \"THStack.h\"\n#include \"TMultiGraph.h\"\n"
            + "#include \"TLatex.h\"\n#include \"TLegend.h\"\n#include \"TPaveText.h\"\n#include \"TPaveLabel.h\"\n#include \"TLine.h\"\n"
            + "#include \"TArrow.h\"\n#include \"TBox.h\"\n#include \"TWbox.h\"\n#include \"TEllipse.h\"\n#include \"TMarker.h\"\n"
            + "#include \"TPolyLine.h\"\n#include \"TPolyMarker.h\"\n#include \"TPolyMarker3D.h\"\n#include \"TPolyLine3D.h\"\n"
            + "#include \"TColor.h\"\n#include \"TStyle.h\"\n#include \"TDirectory.h\"\n#include \"TMath.h\"\n"
            + "#include \"TLegendEntry.h\"\n\n"
            + "void " + function + "()\n{\n" + indent(r.code()) + "}\n";
    }

    private static String indent(String code) {
        final StringBuilder out = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (!line.isEmpty()) out.append("   ").append(line);
            out.append('\n');
        }
        return out.toString().stripTrailing() + "\n";
    }

    /* ------------------------------------------------------------------ */

    private void line(String s) {
        b.append(s).append('\n');
    }

    private String var(String prefix) {
        return prefix + "_" + (++counter);
    }

    private View view(Pad p) {
        return views == null ? null : views.apply(p);
    }

    private void write(String canvasName) {
        line("TDirectory::TContext sphere_context(nullptr); /* the objects belong to their pads, not to a file */");
        line("gStyle->SetOptStat(" + scene.optStat + ");");
        line("gStyle->SetOptTitle(" + scene.optTitle + ");");
        line("gStyle->SetOptFit(" + scene.optFit + ");");
        line("gStyle->SetStatFormat(" + q(scene.statFormat) + ");");
        line("gStyle->SetFitFormat(" + q(scene.fitFormat) + ");");
        line("TCanvas *c_sphere = new TCanvas(" + q(canvasName) + ", " + q(scene.title) + ", " + scene.width + ", "
            + scene.height + ");");
        if (scene.grayscale) line("c_sphere->SetGrayscale(kTRUE);");
        if (scene.fixedAspect) line("c_sphere->SetFixedAspectRatio(kTRUE);");
        names.put(scene, "c_sphere");
        names.put(scene.pad, "c_sphere");
        pad(scene.pad, "c_sphere");
        line("c_sphere->cd();");
        line("c_sphere->Update();");
    }

    private void pad(Pad p, String v) {
        final View view = view(p);
        if (p.fill != null) line(v + "->SetFillColor(" + color(p.fill) + ");");
        line(String.format(Locale.ROOT, "%s->SetMargin(%s, %s, %s, %s);", v, n(p.lm), n(p.rm), n(p.bm), n(p.tm)));
        final boolean logx = view != null && view.logx != null ? view.logx : p.logx;
        final boolean logy = view != null && view.logy != null ? view.logy : p.logy;
        final boolean logz = view != null && view.logz != null ? view.logz : p.logz;
        final boolean gridx = view != null && view.gridx != null ? view.gridx : p.gridx;
        final boolean gridy = view != null && view.gridy != null ? view.gridy : p.gridy;
        if (logx) line(v + "->SetLogx(1);");
        if (logy) line(v + "->SetLogy(1);");
        if (logz) line(v + "->SetLogz(1);");
        if (gridx) line(v + "->SetGridx(1);");
        if (gridy) line(v + "->SetGridy(1);");
        if (p.tickx != 0) line(v + "->SetTickx(" + p.tickx + ");");
        if (p.ticky != 0) line(v + "->SetTicky(" + p.ticky + ");");
        if (p.borderMode != 0) line(v + "->SetBorderMode(" + p.borderMode + ");");
        if (p.borderSize != 2) line(v + "->SetBorderSize(" + p.borderSize + ");");
        if (p.crosshair) line(v + "->SetCrosshair(1);");
        if (p.fixedAspect) line(v + "->SetFixedAspectRatio(kTRUE);");
        final double theta = view != null && Double.isFinite(view.theta) ? view.theta : p.theta;
        final double phi = view != null && Double.isFinite(view.phi) ? view.phi : p.phi;
        if (theta != 30) line(v + "->SetTheta(" + n(theta) + ");");
        if (phi != 30) line(v + "->SetPhi(" + n(phi) + ");");
        if (p.main() == null && p.hasUserRange()) {
            line(v + "->Range(" + n(p.ux1) + ", " + n(p.uy1) + ", " + n(p.ux2) + ", " + n(p.uy2) + ");");
        }
        final Item main = p.main();
        boolean first = true;
        for (Item i : p.items) {
            String option = i.option == null ? "" : i.option;
            if (i == main && view != null && view.option != null) option = view.option;
            if (i instanceof Pave pv && pv.isTitle()) continue;
            final String iv = item(i);
            if (iv == null) continue;
            line(v + "->cd();");
            if (i instanceof Graph && first && !option.toUpperCase(Locale.ROOT).contains("A")) option = "A" + option;
            if (i instanceof Group g && "mgraph".equals(g.kind) && first && !option.toUpperCase(Locale.ROOT).contains("A")) {
                option = "A" + option;
            }
            line(iv + "->Draw(" + q(first || i instanceof Pave || i instanceof Text || i instanceof Segment
                || i instanceof Shape || option.toUpperCase(Locale.ROOT).contains("SAME") ? option : option + " SAME") + ");");
            if (i == main) {
                after(i, iv, p, view);
                first = false;
            }
        }
        for (Pad sub : p.pads) {
            final String sv = var("pad");
            names.put(sub, sv);
            line(v + "->cd();");
            line("TPad *" + sv + " = new TPad(" + q(sub.name) + ", " + q(sub.title) + ", " + n(sub.px) + ", " + n(sub.py)
                + ", " + n(sub.px + sub.pw) + ", " + n(sub.py + sub.ph) + ");");
            line(sv + "->SetBit(kCanDelete);");
            line(sv + "->Draw();");
            line(sv + "->cd();");
            pad(sub, sv);
        }
    }

    /** What is set on the axes once the main object is drawn: their style, Sphere's zoom and titles. */
    private void after(Item main, String v, Pad p, View view) {
        final String[] axes = {"GetXaxis()", "GetYaxis()", "GetZaxis()"};
        final boolean hasZ = main instanceof Hist h && h.dim >= 2 || main instanceof Graph2D;
        for (int a = 0; a < 3; a++) {
            if (a == 2 && !hasZ) continue;
            style(v + "->" + axes[a], p.axes[a]);
        }
        if (view == null) return;
        if (view.xr != null) {
            if (main instanceof Graph || main instanceof Group g && "mgraph".equals(g.kind)) {
                line(v + "->GetXaxis()->SetLimits(" + n(view.xr[0]) + ", " + n(view.xr[1]) + ");");
            } else {
                line(v + "->GetXaxis()->SetRangeUser(" + n(view.xr[0]) + ", " + n(view.xr[1]) + ");");
            }
        }
        if (view.yr != null) {
            if (main instanceof Hist h && h.dim >= 2) {
                line(v + "->GetYaxis()->SetRangeUser(" + n(view.yr[0]) + ", " + n(view.yr[1]) + ");");
            } else {
                line(v + "->SetMinimum(" + n(view.yr[0]) + ");");
                line(v + "->SetMaximum(" + n(view.yr[1]) + ");");
            }
        }
        if (view.zr != null && hasZ) {
            line(v + "->SetMinimum(" + n(view.zr[0]) + ");");
            line(v + "->SetMaximum(" + n(view.zr[1]) + ");");
        }
        if (view.xTitle != null) line(v + "->GetXaxis()->SetTitle(" + q(view.xTitle) + ");");
        if (view.yTitle != null) line(v + "->GetYaxis()->SetTitle(" + q(view.yTitle) + ");");
        if (view.zTitle != null && hasZ) line(v + "->GetZaxis()->SetTitle(" + q(view.zTitle) + ");");
        if (view.statsOff && main instanceof Hist) line(v + "->SetStats(0);");
    }

    /** An axis's style, only where it differs from ROOT's defaults. */
    private void style(String a, AxisStyle s) {
        final AxisStyle d = new AxisStyle();
        if (s.ndivisions != d.ndivisions || s.optimize != d.optimize) {
            line(a + "->SetNdivisions(" + s.ndivisions + ", " + (s.optimize ? "kTRUE" : "kFALSE") + ");");
        }
        if (s.axisColor != null) line(a + "->SetAxisColor(" + color(s.axisColor) + ");");
        if (s.labelColor != null) line(a + "->SetLabelColor(" + color(s.labelColor) + ");");
        if (s.labelFont != d.labelFont) line(a + "->SetLabelFont(" + s.labelFont + ");");
        if (s.labelOffset != d.labelOffset) line(a + "->SetLabelOffset(" + n(s.labelOffset) + ");");
        if (s.labelSize != d.labelSize) line(a + "->SetLabelSize(" + n(s.labelSize) + ");");
        if (s.tickLength != d.tickLength) line(a + "->SetTickLength(" + n(s.tickLength) + ");");
        if (s.titleOffset != d.titleOffset) line(a + "->SetTitleOffset(" + n(s.titleOffset) + ");");
        if (s.titleSize != d.titleSize) line(a + "->SetTitleSize(" + n(s.titleSize) + ");");
        if (s.titleColor != null) line(a + "->SetTitleColor(" + color(s.titleColor) + ");");
        if (s.titleFont != d.titleFont) line(a + "->SetTitleFont(" + s.titleFont + ");");
        if (s.maxDigits != d.maxDigits) line(a + "->SetMaxDigits(" + s.maxDigits + ");");
        if (s.moreLogLabels) line(a + "->SetMoreLogLabels(kTRUE);");
        if (s.noExponent) line(a + "->SetNoExponent(kTRUE);");
        if (s.decimals) line(a + "->SetDecimals(kTRUE);");
        if (s.timeDisplay) line(a + "->SetTimeDisplay(1);");
        if (s.timeFormat != null && !s.timeFormat.isBlank()) line(a + "->SetTimeFormat(" + q(s.timeFormat) + ");");
        if (s.rotateTitle) line(a + "->RotateTitle(kTRUE);");
        if (s.centerTitle) line(a + "->CenterTitle(kTRUE);");
        if (s.centerLabels) line(a + "->CenterLabels(kTRUE);");
        if (!"+".equals(s.ticks)) line(a + "->SetTicks(" + q(s.ticks) + ");");
        if (!"h".equals(s.labelsOption)) line(a + "->LabelsOption(" + q(s.labelsOption) + ");");
    }

    /* ------------------------------------------------------------------ */
    /* Objects                                                             */
    /* ------------------------------------------------------------------ */

    /** Creates an object; answers its variable, or null for what ROOT cannot be given back from numbers. */
    private String item(Item i) {
        final String v;
        if (i instanceof Hist h) v = h.isFunction() && canFormula(h) ? function(h) : hist(h);
        else if (i instanceof Graph g) v = graph(g);
        else if (i instanceof Graph2D g) v = graph2D(g);
        else if (i instanceof Group g) v = group(g);
        else if (i instanceof Text t) v = text(t);
        else if (i instanceof Pave p) v = pave(p);
        else if (i instanceof Segment s) v = segment(s);
        else if (i instanceof Shape s) v = shape(s);
        else if (i instanceof Cloud3D c) v = cloud(c);
        else {
            line("/* " + i.className + " " + i.name + ": not written, Sphere only names it */");
            return null;
        }
        if (v == null) return null;
        names.put(i, v);
        line(v + "->SetBit(kCanDelete);");
        return v;
    }

    private void attributes(String v, Item i, boolean line, boolean fill, boolean marker) {
        if (line) {
            if (i.line != null) line(v + "->SetLineColor(" + color(i.line) + ");");
            if (i.lineWidth != 1) line(v + "->SetLineWidth(" + n(i.lineWidth) + ");");
            if (i.lineStyle != 1) line(v + "->SetLineStyle(" + i.lineStyle + ");");
        }
        if (fill) {
            if (i.fill != null) line(v + "->SetFillColor(" + color(i.fill) + ");");
            if (i.fillStyle != 0) line(v + "->SetFillStyle(" + i.fillStyle + ");");
        }
        if (marker) {
            if (i.marker != null) line(v + "->SetMarkerColor(" + color(i.marker) + ");");
            if (i.markerStyle != 1) line(v + "->SetMarkerStyle(" + i.markerStyle + ");");
            if (i.markerSize != 1) line(v + "->SetMarkerSize(" + n(i.markerSize) + ");");
        }
    }

    private static boolean canFormula(Hist f) {
        if (f.formula == null || f.formula.isBlank()) return false;
        try {
            final RootFormula rf = RootFormula.parse(f.formula);
            return rf.parameters() == 0 || f.parameters != null && f.parameters.length >= rf.parameters();
        } catch (RuntimeException e) {
            return f.parameters != null;
        }
    }

    private String function(Hist f) {
        final String v = var("f");
        final String cls = f.dim == 2 ? "TF2" : "TF1";
        if (f.dim == 2) {
            line(cls + " *" + v + " = new TF2(" + q(f.name) + ", " + q(f.formula) + ", " + n(f.x.lo) + ", " + n(f.x.hi) + ", "
                + n(f.y.lo) + ", " + n(f.y.hi) + ");");
            line(v + "->SetNpy(" + f.ny() + ");");
        } else {
            line(cls + " *" + v + " = new TF1(" + q(f.name) + ", " + q(f.formula) + ", " + n(f.x.lo) + ", " + n(f.x.hi) + ");");
        }
        line(v + "->SetNpx(" + Math.max(4, f.nx()) + ");");
        if (f.parameters != null) {
            for (int k = 0; k < f.parameters.length; k++) line(v + "->SetParameter(" + k + ", " + n(f.parameters[k]) + ");");
        }
        if (f.title != null && !f.title.isBlank() && !f.title.equals(f.formula)) line(v + "->SetTitle(" + q(f.title) + ");");
        if (!Double.isNaN(f.min)) line(v + "->SetMinimum(" + n(f.min) + ");");
        if (!Double.isNaN(f.max)) line(v + "->SetMaximum(" + n(f.max) + ");");
        attributes(v, f, true, true, true);
        return v;
    }

    private String hist(Hist h) {
        final String v = var("h");
        final boolean profile = h.className.startsWith("TProfile");
        final String cls = profile ? (h.dim == 1 ? "TProfile" : h.dim == 2 ? "TProfile2D" : "TProfile3D")
            : h.dim == 1 ? "TH1D" : h.dim == 2 ? "TH2D" : "TH3D";
        final StringBuilder ctor = new StringBuilder(cls + " *" + v + " = new " + cls + "(" + q(h.name) + ", " + q(title(h)));
        final Axis[] axes = {h.x, h.y, h.z};
        for (int a = 0; a < h.dim; a++) {
            final Axis ax = axes[a];
            if (ax.edges != null && ax.edges.length == ax.n + 1) {
                final String e = v + "_e" + "xyz".charAt(a);
                line("Double_t " + e + "[] = " + array(ax.edges) + ";");
                ctor.append(", ").append(ax.n).append(", ").append(e);
            } else {
                ctor.append(", ").append(ax.n).append(", ").append(n(ax.lo)).append(", ").append(n(ax.hi));
            }
        }
        if (profile && h.dim == 1 && h.x.edges != null) ctor.append(", \"").append(h.errorOption).append('"');
        line(ctor + ");");
        line("{");
        line("   Double_t v[] = " + array(h.v) + ";");
        if (h.err != null) line("   Double_t e[] = " + array(h.err) + ";");
        if (profile && h.binEntries != null) line("   Double_t w[] = " + array(h.binEntries) + ";");
        final String loop = h.dim == 1 ? "for (Int_t i = 0; i < " + h.nx() + "; ++i) { const Int_t k = i, bin = i + 1;"
            : h.dim == 2 ? "for (Int_t j = 0; j < " + h.ny() + "; ++j) for (Int_t i = 0; i < " + h.nx()
                + "; ++i) { const Int_t k = j * " + h.nx() + " + i, bin = " + v + "->GetBin(i + 1, j + 1);"
            : "for (Int_t l = 0; l < " + h.nz() + "; ++l) for (Int_t j = 0; j < " + h.ny() + "; ++j) for (Int_t i = 0; i < "
                + h.nx() + "; ++i) { const Int_t k = (l * " + h.ny() + " + j) * " + h.nx() + " + i, bin = " + v
                + "->GetBin(i + 1, j + 1, l + 1);";
        line("   " + loop);
        if (profile) {
            // A profile keeps sums: the entries, the sum of the values, the sum of their squares.
            line("      const Double_t n = " + (h.binEntries != null ? "w[k]" : "1") + ";");
            line("      " + v + "->SetBinEntries(bin, n);");
            line("      " + v + "->SetBinContent(bin, v[k] * n);");
            if (h.err != null) {
                final String spread = h.errorOption != null && h.errorOption.contains("s") ? "e[k]" : "e[k] * TMath::Sqrt(n)";
                line("      (*" + v + "->GetSumw2())[bin] = n * (" + spread + " * " + spread + " + v[k] * v[k]);");
            }
        } else {
            line("      " + v + "->SetBinContent(bin, v[k]);");
            if (h.err != null) line("      " + v + "->SetBinError(bin, e[k]);");
        }
        line("   }");
        line("}");
        if (h.dim == 1 && !profile) {
            if (h.underflow != 0) line(v + "->SetBinContent(0, " + n(h.underflow) + ");");
            if (h.overflow != 0) line(v + "->SetBinContent(" + (h.nx() + 1) + ", " + n(h.overflow) + ");");
        }
        if (h.entries > 0) line(v + "->SetEntries(" + n(h.entries) + ");");
        if (profile && h.errorOption != null && !h.errorOption.isBlank()) line(v + "->SetErrorOption(" + q(h.errorOption) + ");");
        for (int a = 0; a < h.dim; a++) {
            final Axis ax = axes[a];
            final String get = v + "->Get" + "XYZ".charAt(a) + "axis()";
            if (ax.labels != null) {
                for (int k = 0; k < ax.labels.length && k < ax.n; k++) {
                    if (ax.labels[k] != null && !ax.labels[k].isEmpty()) line(get + "->SetBinLabel(" + (k + 1) + ", " + q(ax.labels[k]) + ");");
                }
            }
            if (ax.first > 0) line(get + "->SetRange(" + ax.first + ", " + ax.last + ");");
        }
        if (!Double.isNaN(h.min)) line(v + "->SetMinimum(" + n(h.min) + ");");
        if (!Double.isNaN(h.max)) line(v + "->SetMaximum(" + n(h.max) + ");");
        if (!h.stats) line(v + "->SetStats(0);");
        attributes(v, h, true, true, true);
        for (Hist f : h.fits) {
            if (!canFormula(f)) continue;
            final String fv = function(f);
            line(v + "->GetListOfFunctions()->Add(" + fv + ");");
            names.put(f, fv);
        }
        return v;
    }

    private static String title(Hist h) {
        final String t = h.title == null ? "" : h.title;
        final String yt = h.dim == 1 ? h.yTitle : h.y == null ? "" : h.y.title;
        final String zt = h.dim == 3 && h.z != null ? h.z.title : h.zTitle;
        return t + ";" + (h.x == null ? "" : h.x.title) + ";" + (yt == null ? "" : yt) + ";" + (zt == null ? "" : zt);
    }

    private String graph(Graph g) {
        final String v = var("g");
        final int n = g.x.length;
        final String x = v + "_x";
        final String y = v + "_y";
        line("Double_t " + x + "[] = " + array(g.x) + ";");
        line("Double_t " + y + "[] = " + array(g.y) + ";");
        final boolean asym = g.className.contains("Asymm") || g.exl != null && g.exh != null && !same(g.exl, g.exh)
            || g.eyl != null && g.eyh != null && !same(g.eyl, g.eyh);
        if (asym) {
            final String[] e = {v + "_exl", v + "_exh", v + "_eyl", v + "_eyh"};
            final double[][] d = {g.exl, g.exh, g.eyl, g.eyh};
            for (int k = 0; k < 4; k++) line("Double_t " + e[k] + "[] = " + array(d[k] == null ? new double[n] : d[k]) + ";");
            line("TGraphAsymmErrors *" + v + " = new TGraphAsymmErrors(" + n + ", " + x + ", " + y + ", " + e[0] + ", " + e[1]
                + ", " + e[2] + ", " + e[3] + ");");
        } else if (g.eyl != null || g.exl != null) {
            line("Double_t " + v + "_ex[] = " + array(g.exl == null ? new double[n] : g.exl) + ";");
            line("Double_t " + v + "_ey[] = " + array(g.eyl == null ? new double[n] : g.eyl) + ";");
            line("TGraphErrors *" + v + " = new TGraphErrors(" + n + ", " + x + ", " + y + ", " + v + "_ex, " + v + "_ey);");
        } else {
            line("TGraph *" + v + " = new TGraph(" + n + ", " + x + ", " + y + ");");
        }
        line(v + "->SetName(" + q(g.name) + ");");
        line(v + "->SetTitle(" + q((g.title == null ? "" : g.title) + ";" + g.xTitle + ";" + g.yTitle) + ");");
        if (!Double.isNaN(g.min)) line(v + "->SetMinimum(" + n(g.min) + ");");
        if (!Double.isNaN(g.max)) line(v + "->SetMaximum(" + n(g.max) + ");");
        if (g.editable) line(v + "->SetEditable(kTRUE);");
        attributes(v, g, true, true, true);
        for (Hist f : g.fits) {
            if (!canFormula(f)) continue;
            final String fv = function(f);
            line(v + "->GetListOfFunctions()->Add(" + fv + ");");
            names.put(f, fv);
        }
        return v;
    }

    private static boolean same(double[] a, double[] b) {
        return java.util.Arrays.equals(a, b);
    }

    private String graph2D(Graph2D g) {
        final String v = var("g2");
        line("Double_t " + v + "_x[] = " + array(g.x) + ";");
        line("Double_t " + v + "_y[] = " + array(g.y) + ";");
        line("Double_t " + v + "_z[] = " + array(g.z) + ";");
        line("TGraph2D *" + v + " = new TGraph2D(" + g.x.length + ", " + v + "_x, " + v + "_y, " + v + "_z);");
        line(v + "->SetName(" + q(g.name) + ");");
        line(v + "->SetTitle(" + q((g.title == null ? "" : g.title) + ";" + g.xTitle + ";" + g.yTitle + ";" + g.zTitle) + ");");
        line(v + "->SetNpx(" + g.npx + ");");
        line(v + "->SetNpy(" + g.npy + ");");
        if (g.margin != 0.1) line(v + "->SetMargin(" + n(g.margin) + ");");
        if (g.marginZ != 0) line(v + "->SetMarginBinsContent(" + n(g.marginZ) + ");");
        if (!Double.isNaN(g.min)) line(v + "->SetMinimum(" + n(g.min) + ");");
        if (!Double.isNaN(g.max)) line(v + "->SetMaximum(" + n(g.max) + ");");
        attributes(v, g, true, true, true);
        return v;
    }

    private String group(Group g) {
        final boolean stack = "stack".equals(g.kind);
        final String v = var(stack ? "hs" : "mg");
        line((stack ? "THStack *" : "TMultiGraph *") + v + " = new " + (stack ? "THStack" : "TMultiGraph") + "(" + q(g.name)
            + ", " + q(g.title) + ");");
        for (Item m : g.items) {
            final String mv = m instanceof Hist h ? hist(h) : m instanceof Graph gr ? graph(gr) : null;
            if (mv == null) continue;
            names.put(m, mv);
            line(v + "->Add(" + mv + (m.option == null || m.option.isBlank() ? "" : ", " + q(m.option)) + ");");
        }
        if (!Double.isNaN(g.min)) line(v + "->SetMinimum(" + n(g.min) + ");");
        if (!Double.isNaN(g.max)) line(v + "->SetMaximum(" + n(g.max) + ");");
        return v;
    }

    private String text(Text t) {
        final String v = var("tex");
        line("TLatex *" + v + " = new TLatex(" + n(t.x) + ", " + n(t.y) + ", " + q(t.text) + ");");
        if (t.ndc) line(v + "->SetNDC();");
        line(v + "->SetTextSize(" + n(t.size) + ");");
        if (t.color != null) line(v + "->SetTextColor(" + color(t.color) + ");");
        if (t.align != 11) line(v + "->SetTextAlign(" + t.align + ");");
        if (t.angle != 0) line(v + "->SetTextAngle(" + n(t.angle) + ");");
        if (t.font != 42) line(v + "->SetTextFont(" + t.font + ");");
        return v;
    }

    private String pave(Pave p) {
        final String corners = n(p.x1) + ", " + n(p.y1) + ", " + n(p.x2) + ", " + n(p.y2);
        final String v;
        if (p.isLegend()) {
            v = var("leg");
            line("TLegend *" + v + " = new TLegend(" + corners + ");");
            if (p.header != null && !p.header.isBlank()) line(v + "->SetHeader(" + q(p.header) + ", " + q(p.headerOption) + ");");
            if (p.nColumns > 1) line(v + "->SetNColumns(" + p.nColumns + ");");
            if (p.margin != 0.05) line(v + "->SetMargin(" + n(p.margin) + ");");
            for (Entry e : p.lines) {
                final String ev = var("entry");
                line("TLegendEntry *" + ev + " = " + v + "->AddEntry((TObject *)nullptr, " + q(e.text) + ", " + q(e.option) + ");");
                if (e.line != null) line(ev + "->SetLineColor(" + color(e.line) + ");");
                if (e.fill != null) line(ev + "->SetFillColor(" + color(e.fill) + ");");
                if (e.fillStyle != 0) line(ev + "->SetFillStyle(" + e.fillStyle + ");");
                if (e.marker != null) line(ev + "->SetMarkerColor(" + color(e.marker) + ");");
                if (e.markerStyle != 1) line(ev + "->SetMarkerStyle(" + e.markerStyle + ");");
                if (e.color != null) line(ev + "->SetTextColor(" + color(e.color) + ");");
            }
        } else if (p.isLabel()) {
            v = var("pl");
            line("TPaveLabel *" + v + " = new TPaveLabel(" + corners + ", " + q(p.lines.isEmpty() ? "" : p.lines.get(0).text)
                + ", \"NDC\");");
        } else {
            v = var("pt");
            line("TPaveText *" + v + " = new TPaveText(" + corners + ", \"NDC\");");
            if (p.label != null && !p.label.isBlank()) line(v + "->SetLabel(" + q(p.label) + ");");
            if (p.margin != 0.05) line(v + "->SetMargin(" + n(p.margin) + ");");
            for (Entry e : p.lines) {
                if (e.separator) {
                    line(v + "->AddLine();");
                    continue;
                }
                final String tv = var("t");
                line("TText *" + tv + " = " + v + "->AddText(" + q(e.text) + ");");
                if (e.color != null) line(tv + "->SetTextColor(" + color(e.color) + ");");
                if (e.font != 0) line(tv + "->SetTextFont(" + e.font + ");");
                if (e.size != 0) line(tv + "->SetTextSize(" + n(e.size) + ");");
                if (e.align != 0) line(tv + "->SetTextAlign(" + e.align + ");");
            }
        }
        line(v + "->SetBorderSize(" + p.border + ");");
        if (p.cornerRadius != 0) line(v + "->SetCornerRadius(" + n(p.cornerRadius) + ");");
        if (p.shadowColor != null) line(v + "->SetShadowColor(" + color(p.shadowColor) + ");");
        if (p.textFont != 42) line(v + "->SetTextFont(" + p.textFont + ");");
        if (p.textSize != 0) line(v + "->SetTextSize(" + n(p.textSize) + ");");
        if (p.textAlign != (p.isLegend() ? 12 : 22)) line(v + "->SetTextAlign(" + p.textAlign + ");");
        line(v + "->SetName(" + q(p.name) + ");");
        attributes(v, p, true, true, false);
        return v;
    }

    private String segment(Segment s) {
        final String v = var("line");
        line("TLine *" + v + " = new TLine(" + n(s.x1) + ", " + n(s.y1) + ", " + n(s.x2) + ", " + n(s.y2) + ");");
        if (s.ndc) line(v + "->SetNDC();");
        attributes(v, s, true, false, false);
        return v;
    }

    private String shape(Shape s) {
        final String v;
        switch (s.kind) {
            case "box" -> {
                final boolean wbox = "TWbox".equals(s.className);
                v = var(wbox ? "wbox" : "box");
                line((wbox ? "TWbox *" : "TBox *") + v + " = new " + (wbox ? "TWbox" : "TBox") + "(" + n(s.x1) + ", " + n(s.y1)
                    + ", " + n(s.x2) + ", " + n(s.y2) + ");");
                if (wbox) {
                    line(v + "->SetBorderMode(" + s.borderMode + ");");
                    line(v + "->SetBorderSize(" + s.borderSize + ");");
                }
            }
            case "ellipse" -> {
                v = var("ell");
                line("TEllipse *" + v + " = new TEllipse(" + n(s.x1) + ", " + n(s.y1) + ", " + n(s.r1) + ", " + n(s.r2) + ", "
                    + n(s.phimin) + ", " + n(s.phimax) + ", " + n(s.theta) + ");");
                if (s.noEdges) line(v + "->SetNoEdges(kTRUE);");
            }
            case "arrow" -> {
                v = var("arrow");
                line("TArrow *" + v + " = new TArrow(" + n(s.x1) + ", " + n(s.y1) + ", " + n(s.x2) + ", " + n(s.y2) + ", "
                    + n(s.arrowSize) + ", " + q(s.arrowOption) + ");");
                if (s.arrowAngle != 60) line(v + "->SetAngle(" + n(s.arrowAngle) + ");");
            }
            case "marker" -> {
                v = var("mk");
                line("TMarker *" + v + " = new TMarker(" + n(s.x1) + ", " + n(s.y1) + ", " + s.markerStyle + ");");
            }
            case "polyline", "pm" -> {
                final boolean marks = "pm".equals(s.kind);
                v = var(marks ? "pm" : "pl");
                line("Double_t " + v + "_x[] = " + array(s.xs) + ";");
                line("Double_t " + v + "_y[] = " + array(s.ys) + ";");
                line((marks ? "TPolyMarker *" : "TPolyLine *") + v + " = new " + (marks ? "TPolyMarker" : "TPolyLine") + "("
                    + Math.min(s.xs.length, s.ys.length) + ", " + v + "_x, " + v + "_y);");
            }
            default -> {
                line("/* " + s.className + " " + s.name + ": not written */");
                return null;
            }
        }
        if (s.ndc && !"ellipse".equals(s.kind) && !"pm".equals(s.kind) && !"polyline".equals(s.kind)) {
            line("/* drawn in NDC in the original */");
        }
        attributes(v, s, true, true, true);
        return v;
    }

    private String cloud(Cloud3D c) {
        final String v = var(c.isLine() ? "pl3" : "pm3");
        final double[] p = new double[c.p.length];
        for (int k = 0; k < p.length; k++) p[k] = c.p[k];
        line("Double_t " + v + "_p[] = " + array(p) + ";");
        line((c.isLine() ? "TPolyLine3D *" : "TPolyMarker3D *") + v + " = new " + (c.isLine() ? "TPolyLine3D" : "TPolyMarker3D")
            + "(" + c.size() + ", " + v + "_p);");
        attributes(v, c, true, false, true);
        return v;
    }

    /* ------------------------------------------------------------------ */
    /* C++ literals                                                        */
    /* ------------------------------------------------------------------ */

    static String q(String s) {
        if (s == null) return "\"\"";
        final StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\x%02x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    static String n(double v) {
        if (Double.isNaN(v)) return "TMath::QuietNaN()";
        if (Double.isInfinite(v)) return v > 0 ? "TMath::Infinity()" : "-TMath::Infinity()";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.17g", v).replaceAll("0+e", "e").replaceAll("(\\.\\d*?)0+$", "$1");
    }

    static String array(double[] a) {
        if (a == null || a.length == 0) return "{0}";
        final StringBuilder b = new StringBuilder(a.length * 10 + 2).append('{');
        for (int k = 0; k < a.length; k++) {
            if (k > 0) b.append(k % 12 == 0 ? ",\n      " : ", ");
            b.append(n(a[k]));
        }
        return b.append('}').toString();
    }

    /** A colour as ROOT knows it: its index when it is one of ROOT's, else TColor::GetColor. */
    static String color(Color c) {
        if (c == null) return "1";
        final int index = RootColors.index(c);
        final String base = index >= 0 && RootColors.defined(index) && RootColors.color(index).getRGB() == (c.getRGB() | 0xFF000000)
            ? Integer.toString(index) : String.format("TColor::GetColor(\"#%06x\")", c.getRGB() & 0xFFFFFF);
        return c.getAlpha() == 255 ? base : "TColor::GetColorTransparent(" + base + ", " + n(c.getAlpha() / 255.0) + ")";
    }
}
