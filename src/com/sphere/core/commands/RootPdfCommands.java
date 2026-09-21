package com.sphere.core.commands;

import com.sphere.core.rootbackend.RootPdfAlphaS;
import com.sphere.core.rootbackend.RootPdfCatalog;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.core.rootbackend.RootToolchain;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parton distributions, read by Sphere and not by LHAPDF.
 *
 * These commands run in Java. The grids are plain text and the interpolation is
 * arithmetic, so nothing has to be installed and nothing crosses to the engine
 * unless a plot is asked for, in which case only the points do. A set stays
 * open under the name it was given until it is closed, because reading one is
 * seconds of work and the next command would otherwise pay for it again.
 */
public final class RootPdfCommands {

    /** The sets currently open, by the name they were opened under. */
    private static final Map<String, RootPdfSet> OPEN = new LinkedHashMap<>();

    /** The scale used when a command does not name one. */
    private static final double DEFAULT_Q = 100.0;

    private RootPdfCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* Opening and closing                                                 */
    /* ------------------------------------------------------------------ */

    public static void rootPdfOpen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf open"));
        if (w.length < 2) {
            Handlers.usage(":root pdf open <name> <folder> [--all] [--weighted]");
            return;
        }
        final Path folder = Handlers.resolve(w[1]).toPath();
        boolean everyMember = false;
        RootPdfGrid.Accuracy accuracy = RootPdfGrid.Accuracy.LHAPDF;
        for (int a = 2; a < w.length; a++) {
            if (w[a].equalsIgnoreCase("--all")) everyMember = true;
            if (w[a].equalsIgnoreCase("--weighted")) accuracy = RootPdfGrid.Accuracy.WEIGHTED;
        }
        try {
            RootPdfSet set = RootPdfSet.open(folder,
                everyMember ? Integer.MAX_VALUE : 1, accuracy);
            OPEN.put(w[0], set);
            AppLogger.info(w[0] + " -> " + set);
            report(set.check(), "  ");
        } catch (IOException cannotRead) {
            AppLogger.error(cannotRead.getMessage());
        }
    }

    public static void rootPdfClose(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root pdf close");
        if (a.isEmpty()) {
            final int had = OPEN.size();
            OPEN.clear();
            AppLogger.info("Closed " + had + " set(s).");
            return;
        }
        AppLogger.info(OPEN.remove(a) == null ? "No set named " + a : "Closed " + a);
    }

    public static void rootPdfList(String i, CommandExecutionContext c) {
        if (OPEN.isEmpty()) {
            AppLogger.raw("No set open. ':root pdf open <name> <folder>' opens one.");
            return;
        }
        StringBuilder text = new StringBuilder("Sets open:");
        for (Map.Entry<String, RootPdfSet> one : OPEN.entrySet()) {
            text.append(String.format(Locale.ROOT, "%n  %-14s %s", one.getKey(), one.getValue()));
        }
        AppLogger.raw(text.toString());
    }

    public static void rootPdfInfo(String i, CommandExecutionContext c) {
        final RootPdfSet set = named(Handlers.args(i, ":root pdf info"));
        if (set == null) {
            return;
        }
        final RootPdfGrid one = set.centralMember();
        StringBuilder text = new StringBuilder();
        text.append(set.name()).append(" in ").append(set.folder());
        text.append(String.format(Locale.ROOT, "%n  members      %d, %s",
            set.memberCount(), set.errorType()));
        text.append(String.format(Locale.ROOT, "%n  x            %.6g to %.6g, %d knots",
            one.xMin(), one.xMax(), one.xKnots()));
        text.append(String.format(Locale.ROOT, "%n  Q            %.6g to %.6g GeV, %d knots",
            one.qMin(), one.qMax(), one.q2Knots()));
        text.append(String.format(Locale.ROOT, "%n  slopes       %s", one.accuracy()));
        StringBuilder codes = new StringBuilder();
        for (int pid : one.flavors()) {
            codes.append(codes.length() == 0 ? "" : " ").append(pid);
        }
        text.append(String.format(Locale.ROOT, "%n  flavors      %s", codes));
        List<Double> seams = one.seams();
        if (!seams.isEmpty()) {
            StringBuilder at = new StringBuilder();
            for (double q : seams) {
                at.append(at.length() == 0 ? "" : ", ")
                  .append(String.format(Locale.ROOT, "%.4g", q));
            }
            text.append(String.format(Locale.ROOT, "%n  seams        %s GeV", at));
        }
        for (String key : new String[]{"SetDesc", "OrderQCD", "AlphaS_MZ", "MZ", "Particle"}) {
            final String said = set.meta(key, "");
            if (!said.isEmpty()) {
                text.append(String.format(Locale.ROOT, "%n  %-12s %s", key, said));
            }
        }
        AppLogger.raw(text.toString());
    }

    public static void rootPdfCheck(String i, CommandExecutionContext c) {
        final RootPdfSet set = named(Handlers.args(i, ":root pdf check"));
        if (set == null) {
            return;
        }
        List<RootPdfGrid.Finding> found = set.check();
        if (found.isEmpty()) {
            AppLogger.info(set.name() + ": nothing to report.");
        } else {
            AppLogger.raw(set.name() + ":");
            report(found, "  ");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Values                                                              */
    /* ------------------------------------------------------------------ */

    public static void rootPdfValue(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf value"));
        if (w.length < 4) {
            Handlers.usage(":root pdf value <name> <pid> <x> <Q>");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final int pid = particle(w[1]);
        final double x = number(w[2]);
        final double q = number(w[3]);
        if (Double.isNaN(x) || Double.isNaN(q)) {
            AppLogger.error("x and Q must be numbers.");
            return;
        }
        if (!set.centralMember().carries(pid)) {
            AppLogger.error("The set carries no flavor " + pid + ".");
            return;
        }
        final double value = set.xfxQ(pid, x, q);
        AppLogger.info(String.format(Locale.ROOT,
            "xf(%d, x=%.6g, Q=%.6g) = %.8g       f = %.8g",
            pid, x, q, value, value / x));
    }

    public static void rootPdfBand(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf band"));
        if (w.length < 4) {
            Handlers.usage(":root pdf band <name> <pid> <x> <Q>");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        if (set.memberCount() < 2) {
            AppLogger.warn("Only the central member is open. Reopen with --all for a band.");
            return;
        }
        final double q = number(w[3]);
        RootPdfSet.Band band = set.band(particle(w[1]), number(w[2]), q * q);
        AppLogger.info(String.format(Locale.ROOT,
            "%s   +-%.2f%%   from %d members, %s",
            band, 100.0 * band.relative(), set.memberCount(), set.errorType()));
    }

    public static void rootPdfSumrules(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf sumrules"));
        if (w.length < 1) {
            Handlers.usage(":root pdf sumrules <name> [Q]");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final double q = w.length > 1 ? number(w[1]) : DEFAULT_Q;
        AppLogger.raw(set.sumRuleReport(q * q).stripTrailing());
    }

    public static void rootPdfScan(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf scan"));
        if (w.length < 2) {
            Handlers.usage(":root pdf scan <name> <pid> [Q] [points]");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final int pid = particle(w[1]);
        final double q = w.length > 2 ? number(w[2]) : DEFAULT_Q;
        final int points = w.length > 3 ? Handlers.asInt(w[3], 20) : 20;
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "flavor %d at Q = %.4g GeV%n        x            xf", pid, q));
        for (double x : logSteps(set.centralMember().xMin(), 0.9, points)) {
            text.append(String.format(Locale.ROOT, "%n  %.6e   %.8g",
                x, set.xfxQ(pid, x, q)));
        }
        AppLogger.raw(text.toString());
    }

    public static void rootPdfGrid(String i, CommandExecutionContext c) {
        final RootPdfSet set = named(Handlers.args(i, ":root pdf grid"));
        if (set == null) {
            return;
        }
        final RootPdfGrid one = set.centralMember();
        AppLogger.raw(String.format(Locale.ROOT,
            "%d x knots from %.6g to %.6g%n%d Q knots from %.6g to %.6g GeV%n"
            + "first x knots: %s%nfirst Q knots: %s",
            one.xKnots(), one.xMin(), one.xMax(),
            one.q2Knots(), one.qMin(), one.qMax(),
            few(one.xKnotValues(), false), few(one.q2KnotValues(), true)));
    }

    public static void rootPdfSeams(String i, CommandExecutionContext c) {
        final RootPdfSet set = named(Handlers.args(i, ":root pdf seams"));
        if (set == null) {
            return;
        }
        List<Double> seams = set.centralMember().seams();
        if (seams.isEmpty()) {
            AppLogger.info("One subgrid, no seam.");
            return;
        }
        StringBuilder text = new StringBuilder("Subgrids meet at:");
        for (double q : seams) {
            // A seam is where a heavy quark enters, so the jump across it is
            // physics rather than an error, and the two sides are both right.
            final double below = set.xfxQ(21, 0.01, q * (1.0 - 1e-6));
            final double above = set.xfxQ(21, 0.01, q * (1.0 + 1e-6));
            text.append(String.format(Locale.ROOT,
                "%n  Q = %.6g GeV    gluon at x=0.01 goes %.8g -> %.8g  (%+.3g%%)",
                q, below, above, 100.0 * (above - below) / below));
        }
        AppLogger.raw(text.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Luminosity                                                          */
    /* ------------------------------------------------------------------ */

    public static void rootPdfLumi(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf lumi"));
        if (w.length < 2) {
            Handlers.usage(":root pdf lumi <name> <channel> [mass] [collider-GeV]"
                + "   channel: gg, qq, gq, uu, dd, cc, bb");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final double collider = w.length > 3 ? number(w[3]) : 13600.0;
        if (w.length > 2) {
            final double mass = number(w[2]);
            AppLogger.info(String.format(Locale.ROOT,
                "dL/dM for %s at M = %.6g GeV, sqrt(s) = %.6g GeV:  %.8g",
                w[1], mass, collider, lumi(set, w[1], mass, collider)));
            return;
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "%s at sqrt(s) = %.6g GeV%n      M (GeV)        dL/dM", w[1], collider));
        for (double mass : new double[]{50, 100, 125, 200, 500, 1000, 2000, 4000}) {
            if (mass >= collider) {
                break;
            }
            text.append(String.format(Locale.ROOT, "%n   %10.1f    %.8g",
                mass, lumi(set, w[1], mass, collider)));
        }
        AppLogger.raw(text.toString());
    }

    private static double lumi(RootPdfSet set, String channel, double mass, double collider) {
        final double q2 = mass * mass;
        final String bare = channel.toLowerCase(Locale.ROOT);
        return switch (bare) {
            case "gg" -> set.luminosityAtMass(RootPdfSet.gluonGluon(), mass, collider, q2);
            case "qq" -> set.quarkAntiquark(mass * mass / (collider * collider), q2)
                         * 2.0 * mass / (collider * collider);
            case "gq" -> set.luminosityAtMass(new RootPdfSet.Channel(21, 2), mass, collider, q2)
                       + set.luminosityAtMass(new RootPdfSet.Channel(21, 1), mass, collider, q2);
            case "uu" -> set.luminosityAtMass(new RootPdfSet.Channel(2, -2), mass, collider, q2);
            case "dd" -> set.luminosityAtMass(new RootPdfSet.Channel(1, -1), mass, collider, q2);
            case "cc" -> set.luminosityAtMass(new RootPdfSet.Channel(4, -4), mass, collider, q2);
            case "bb" -> set.luminosityAtMass(new RootPdfSet.Channel(5, -5), mass, collider, q2);
            default -> Double.NaN;
        };
    }

    /* ------------------------------------------------------------------ */
    /* Comparing                                                           */
    /* ------------------------------------------------------------------ */

    public static void rootPdfCompare(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf compare"));
        if (w.length < 3) {
            Handlers.usage(":root pdf compare <name-a> <name-b> <pid> [Q] [points]");
            return;
        }
        final RootPdfSet a = named(w[0]);
        final RootPdfSet b = named(w[1]);
        if (a == null || b == null) {
            return;
        }
        final int pid = particle(w[2]);
        final double q = w.length > 3 ? number(w[3]) : DEFAULT_Q;
        final int points = w.length > 4 ? Handlers.asInt(w[4], 12) : 12;

        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "flavor %d at Q = %.4g GeV%n        x            %-12s %-12s ratio     beyond the bands",
            pid, q, a.name(), b.name()));
        int beyond = 0;
        for (RootPdfSet.Difference d : a.compare(b, pid, q * q, points)) {
            if (d.significant()) {
                beyond++;
            }
            text.append(String.format(Locale.ROOT, "%n  %.6e   %-12.6g %-12.6g %-9.5f %s",
                d.x(), d.mine(), d.theirs(), d.ratio(), d.significant() ? "yes" : ""));
        }
        text.append(String.format(Locale.ROOT,
            "%n%d of %d points differ by more than the two uncertainties together.",
            beyond, points));
        AppLogger.raw(text.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Into ROOT                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Fills a TGraph in the engine with the curve, under a name.
     *
     * The grid stays in Java and only the points cross, which is the whole
     * arrangement: the engine never has to know what a PDF file looks like, and
     * the graph it ends up with is an ordinary one that every other command
     * already works on.
     */
    public static void rootPdfGraph(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf graph"));
        if (w.length < 3) {
            Handlers.usage(":root pdf graph <graph-name> <set> <pid> [Q] [points]");
            return;
        }
        final RootPdfSet set = named(w[1]);
        if (set == null) {
            return;
        }
        final int pid = particle(w[2]);
        final double q = w.length > 3 ? number(w[3]) : DEFAULT_Q;
        final int points = w.length > 4 ? Handlers.asInt(w[4], 120) : 120;

        StringBuilder xs = new StringBuilder();
        StringBuilder ys = new StringBuilder();
        for (double x : logSteps(set.centralMember().xMin(), 0.9, points)) {
            xs.append(xs.length() == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.10g", x));
            ys.append(ys.length() == 0 ? "" : ", ")
              .append(String.format(Locale.ROOT, "%.10g", set.xfxQ(pid, x, q)));
        }
        Handlers.cling(c, "[]{ const double xs[] = {" + xs + "}; const double ys[] = {" + ys
            + "}; TGraph *g = new TGraph(" + points + ", xs, ys);"
            + " g->SetName(\"" + w[0] + "\");"
            + " g->SetTitle(\"" + set.name() + "  flavor " + pid
            + String.format(Locale.ROOT, "  Q = %.4g GeV;x;xf(x)", q) + "\");"
            + " g->SetLineWidth(2); return "
            + Handlers.keep(w[0], "TGraph", "g") + "; }()");
    }

    /** Draws every flavor the set carries, on one canvas. */
    public static void rootPdfPlot(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf plot"));
        if (w.length < 1) {
            Handlers.usage(":root pdf plot <set> [Q] [points]");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final double q = w.length > 1 ? number(w[1]) : DEFAULT_Q;
        final int points = w.length > 2 ? Handlers.asInt(w[2], 120) : 120;
        final double[] xs = logSteps(set.centralMember().xMin(), 0.9, points);

        StringBuilder xText = new StringBuilder();
        for (double x : xs) {
            xText.append(xText.length() == 0 ? "" : ", ")
                 .append(String.format(Locale.ROOT, "%.10g", x));
        }

        StringBuilder code = new StringBuilder("[]{ const double xs[] = {" + xText + "};"
            + " TCanvas *canvas = new TCanvas(\"pdf_" + set.name()
            + "\", \"" + set.name() + "\", 900, 650); canvas->SetLogx(); canvas->SetLogy();"
            + " TMultiGraph *all = new TMultiGraph();"
            + " TLegend *key = new TLegend(0.72, 0.60, 0.98, 0.98);");

        int color = 1;
        for (int pid : set.flavors()) {
            StringBuilder ys = new StringBuilder();
            boolean anyPositive = false;
            for (double x : xs) {
                final double value = set.xfxQ(pid, x, q);
                anyPositive |= value > 0.0;
                ys.append(ys.length() == 0 ? "" : ", ")
                  .append(String.format(Locale.ROOT, "%.10g", Math.max(value, 1e-12)));
            }
            if (!anyPositive) {
                continue;
            }
            final String local = "g" + (pid < 0 ? "m" + (-pid) : String.valueOf(pid));
            code.append(" const double ").append(local).append("y[] = {").append(ys).append("};")
                .append(" TGraph *").append(local).append(" = new TGraph(").append(points)
                .append(", xs, ").append(local).append("y);")
                .append(' ').append(local).append("->SetLineColor(").append(color).append(");")
                .append(' ').append(local).append("->SetLineWidth(2);")
                .append(" all->Add(").append(local).append(");")
                .append(" key->AddEntry(").append(local).append(", \"")
                .append(RootPdfSet.particleName(pid))
                .append("\", \"l\");");
            color = color == 9 ? 11 : color + 1;
        }
        code.append(" all->SetTitle(\"").append(set.name())
            .append(String.format(Locale.ROOT, "  Q = %.4g GeV;x;xf(x)\");", q))
            .append(" all->Draw(\"AL\"); key->Draw(); canvas->Update();")
            .append(" return std::string(\"drew ").append(set.name()).append("\"); }()");
        Handlers.cling(c, code.toString());
    }

    /* ------------------------------------------------------------------ */
    /* Out to a file                                                       */
    /* ------------------------------------------------------------------ */

    public static void rootPdfExport(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf export"));
        if (w.length < 3) {
            Handlers.usage(":root pdf export <set> <file> <pid> [Q] [points]");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final int pid = particle(w[2]);
        final double q = w.length > 3 ? number(w[3]) : DEFAULT_Q;
        final int points = w.length > 4 ? Handlers.asInt(w[4], 200) : 200;

        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
            "# %s  flavor %d  Q = %.6g GeV%n# x  xf  f%n", set.name(), pid, q));
        for (double x : logSteps(set.centralMember().xMin(), 0.9, points)) {
            final double value = set.xfxQ(pid, x, q);
            text.append(String.format(Locale.ROOT, "%.10e %.10e %.10e%n", x, value, value / x));
        }
        final Path out = Handlers.resolve(w[1]).toPath();
        try {
            Files.writeString(out, text.toString(), StandardCharsets.UTF_8);
            AppLogger.info(points + " points written to " + out);
        } catch (IOException cannotWrite) {
            AppLogger.error("Could not write " + out + ": " + cannotWrite.getMessage());
        }
    }

    /**
     * Writes the reader as one C++ header, so a pipeline can use it.
     *
     * A pipeline compiles to a shared library the engine loads, and it cannot
     * call back into Java. The header carries the same format reader and the
     * same interpolation, in about four hundred lines with no dependency beyond
     * the standard library, which is what makes a pipeline that uses a PDF
     * still build on a machine where LHAPDF was never installed.
     */
    public static void rootPdfHeader(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root pdf header");
        final Path out = (a.isEmpty() ? Handlers.resolve("sphere_pdf.h")
                                      : Handlers.resolve(a)).toPath();
        try {
            Files.writeString(out, com.sphere.core.rootbackend.RootPdfHeader.source(),
                              StandardCharsets.UTF_8);
            AppLogger.info("Wrote " + out + ". Include it and call "
                + "sphere::Pdf::open(\"<folder>\") then set.xfxQ2(pid, x, q2).");
        } catch (IOException cannotWrite) {
            AppLogger.error("Could not write " + out + ": " + cannotWrite.getMessage());
        }
    }


    /* ------------------------------------------------------------------ */
    /* The strong coupling                                                 */
    /* ------------------------------------------------------------------ */

    public static void rootPdfAlphas(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf alphas"));
        if (w.length < 1) {
            Handlers.usage(":root pdf alphas <set> [Q]   (no Q prints a table)");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final RootPdfAlphaS as = set.alphaS();
        if (as == null) {
            AppLogger.warn(set.name() + " does not say how its alpha_s is computed. "
                + "':root pdf alphas-set " + w[0] + " analytic <Lambda5>' gives it one.");
            return;
        }
        if (w.length > 1) {
            final double q = number(w[1]);
            AppLogger.info(String.format(Locale.ROOT, "alpha_s(%.6g GeV) = %.8f   [%s]",
                q, as.alphasQ2(q * q), as.type()));
            return;
        }
        StringBuilder text = new StringBuilder(as.toString());
        text.append(String.format(Locale.ROOT, "%n  thresholds at %s GeV",
            as.thresholdScales()));
        text.append(String.format(Locale.ROOT,
            "%n  LHAPDF holds it constant above %.6g GeV%n%n      Q (GeV)     alpha_s",
            as.frozenAbove()));
        for (double q : new double[]{1.0, 1.5, 2.0, 5.0, 10.0, 30.0, 91.1876,
                                     200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0}) {
            final double value = as.alphasQ2(q * q);
            text.append(String.format(Locale.ROOT, "%n   %10.4f    %s", q,
                value >= RootPdfAlphaS.DIVERGENT ? "diverges" 
                    : String.format(Locale.ROOT, "%.8f", value)));
        }
        AppLogger.raw(text.toString());
    }

    /** Answers what LHAPDF would, including where it is wrong, or does not. */
    public static void rootPdfAlphasMatch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf alphas-match"));
        if (w.length < 1) {
            Handlers.usage(":root pdf alphas-match <set> [on|off]");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        final RootPdfAlphaS as = set.alphaS();
        if (as == null) {
            AppLogger.warn(set.name() + " declares no alpha_s.");
            return;
        }
        if (w.length > 1) {
            as.matchingLhapdf(!w[1].equalsIgnoreCase("off"));
        }
        AppLogger.info(as.matchesLhapdf()
            ? "Answering exactly what LHAPDF would, so it is held constant above "
              + String.format(Locale.ROOT, "%.6g", as.frozenAbove()) + " GeV."
            : "Continuing the running above the table, where LHAPDF holds it constant.");
    }

    /* ------------------------------------------------------------------ */
    /* The catalog                                                         */
    /* ------------------------------------------------------------------ */

    public static void rootPdfWhere(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf where"));
        if (w.length >= 2 && w[0].equalsIgnoreCase("add")) {
            final Path folder = Handlers.resolve(Handlers.join(w, 1)).toPath();
            AppLogger.info(RootPdfCatalog.addPath(folder)
                ? "Looking in " + folder + " as well."
                : (Files.isDirectory(folder) ? folder + " was already listed."
                                             : "Not a folder: " + folder));
            return;
        }
        if (w.length >= 2 && w[0].equalsIgnoreCase("drop")) {
            final Path folder = Handlers.resolve(Handlers.join(w, 1)).toPath();
            AppLogger.info(RootPdfCatalog.dropPath(folder)
                ? "No longer looking in " + folder
                : folder + " was not one Sphere was told about.");
            return;
        }
        AppLogger.raw(RootPdfCatalog.report());
    }

    public static void rootPdfInstalled(String i, CommandExecutionContext c) {
        final List<String> found = RootPdfCatalog.installed();
        if (found.isEmpty()) {
            AppLogger.raw("No set on disk. ':root pdf where' shows where Sphere looked, "
                + "':root pdf fetch <name>' downloads one.");
            return;
        }
        StringBuilder text = new StringBuilder(found.size() + " set(s) on disk:");
        for (String one : found) {
            final RootPdfCatalog.Entry said = RootPdfCatalog.describe(one);
            text.append(String.format(Locale.ROOT, "%n  %-40s %s", one,
                said == null ? "" : "id " + said.id()));
        }
        AppLogger.raw(text.toString());
    }

    public static void rootPdfSearch(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root pdf search");
        if (a.isEmpty()) {
            Handlers.usage(":root pdf search <text|id>");
            return;
        }
        final int asNumber = Handlers.asInt(a, -1);
        if (asNumber > 0) {
            final RootPdfCatalog.Entry one = RootPdfCatalog.describe(asNumber);
            AppLogger.info(one == null ? "No set carries the number " + asNumber
                                       : one.toString());
            return;
        }
        final List<RootPdfCatalog.Entry> found = RootPdfCatalog.search(a);
        if (found.isEmpty()) {
            AppLogger.info(RootPdfCatalog.index().isEmpty()
                ? "No pdfsets.index in any declared folder, so there is nothing to search."
                : "Nothing published is named like " + a);
            return;
        }
        StringBuilder text = new StringBuilder(found.size() + " match(es):");
        final int shown = Math.min(found.size(), 40);
        for (int k = 0; k < shown; k++) {
            text.append(System.lineSeparator()).append("  ").append(found.get(k));
        }
        if (shown < found.size()) {
            text.append(System.lineSeparator()).append("  ... and ")
                .append(found.size() - shown).append(" more");
        }
        AppLogger.raw(text.toString());
    }

    /**
     * Downloads a set that is not on disk.
     *
     * A published set is tens of megabytes and the transfer is not instant, so
     * the command says where it went and how much came down rather than
     * finishing silently.
     */
    public static void rootPdfFetch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf fetch"));
        if (w.length < 1) {
            Handlers.usage(":root pdf fetch <name> [folder]");
            return;
        }
        final Path into = w.length > 1 ? Handlers.resolve(w[1]).toPath() : null;
        AppLogger.info("Fetching " + w[0] + " from " + RootPdfCatalog.downloadBase());
        final RootPdfCatalog.Fetched done = RootPdfCatalog.fetch(w[0], into);
        if (done.succeeded()) {
            AppLogger.info(done.message());
        } else {
            AppLogger.error(done.message());
        }
    }

    /** Where sets are fetched from, for a laboratory that keeps a mirror. */
    public static void rootPdfMirror(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root pdf mirror");
        if (!a.isEmpty()) {
            RootPdfCatalog.downloadFrom(a);
        }
        AppLogger.info("Sets are fetched from " + RootPdfCatalog.downloadBase());
    }

    /** Unpacks an archive already on disk. */
    public static void rootPdfInstall(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf install"));
        if (w.length < 1) {
            Handlers.usage(":root pdf install <file.tar.gz> [folder]");
            return;
        }
        final Path archive = Handlers.resolve(w[0]).toPath();
        final Path into = w.length > 1 ? Handlers.resolve(w[1]).toPath() : null;
        final RootPdfCatalog.Fetched done = RootPdfCatalog.install(archive, into);
        if (done.succeeded()) {
            AppLogger.info(done.message());
        } else {
            AppLogger.error(done.message());
        }
    }

    /** Opens a set by its published name rather than by a path. */
    public static void rootPdfUse(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf use"));
        if (w.length < 1) {
            Handlers.usage(":root pdf use <name> [as <handle>] [--all] [--weighted] "
                + "[--ipol <rule>] [--xpol <rule>]");
            return;
        }
        String handle = w[0];
        boolean everyMember = false;
        RootPdfGrid.Accuracy accuracy = RootPdfGrid.Accuracy.LHAPDF;
        for (int a = 1; a < w.length; a++) {
            if (w[a].equalsIgnoreCase("as") && a + 1 < w.length) {
                handle = w[++a];
            } else if (w[a].equalsIgnoreCase("--all")) {
                everyMember = true;
            } else if (w[a].equalsIgnoreCase("--weighted")) {
                accuracy = RootPdfGrid.Accuracy.WEIGHTED;
            }
        }
        try {
            RootPdfSet set = RootPdfCatalog.open(w[0],
                everyMember ? Integer.MAX_VALUE : 1, accuracy);
            OPEN.put(handle, set);
            AppLogger.info(handle + " -> " + set);
            report(set.check(), "  ");
        } catch (IOException cannotRead) {
            AppLogger.error(cannotRead.getMessage());
        }
    }

    /** Reopens a set with a different interpolator or extrapolator. */
    public static void rootPdfRule(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root pdf rule"));
        if (w.length < 1) {
            Handlers.usage(":root pdf rule <set> [<interpolation>] [<extrapolation>]"
                + "   interpolation: log_bicubic bicubic log_bilinear bilinear"
                + "   extrapolation: continuation nearest error sphere");
            return;
        }
        final RootPdfSet set = named(w[0]);
        if (set == null) {
            return;
        }
        if (w.length == 1) {
            AppLogger.info(set.name() + ": " + set.centralMember().interpolation()
                + " between the knots, " + set.centralMember().extrapolation()
                + " outside them, slopes " + set.centralMember().accuracy());
            return;
        }
        RootPdfGrid.Interpolation ipol = set.centralMember().interpolation();
        RootPdfGrid.Extrapolation xpol = set.centralMember().extrapolation();
        for (int a = 1; a < w.length; a++) {
            final String said = w[a].toUpperCase(Locale.ROOT);
            try {
                ipol = RootPdfGrid.Interpolation.valueOf(said);
                continue;
            } catch (IllegalArgumentException notThat) {
                // Not an interpolation, so try the other list.
            }
            try {
                xpol = RootPdfGrid.Extrapolation.valueOf(said);
            } catch (IllegalArgumentException notThatEither) {
                AppLogger.error("No rule called " + w[a] + ".");
                return;
            }
        }
        try {
            RootPdfSet again = RootPdfSet.open(set.folder(), set.memberCount(),
                set.centralMember().accuracy(), ipol, xpol);
            for (Map.Entry<String, RootPdfSet> one : OPEN.entrySet()) {
                if (one.getValue() == set) {
                    OPEN.put(one.getKey(), again);
                    break;
                }
            }
            AppLogger.info(again.name() + " reread: " + ipol + " between the knots, "
                + xpol + " outside them.");
        } catch (IOException cannotRead) {
            AppLogger.error(cannotRead.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Making it visible to the rest of the toolchain                      */
    /* ------------------------------------------------------------------ */

    /**
     * Asks the engine one of the keys root-config answers.
     *
     * The engine is the authority for ROOT: it is the installation actually
     * loaded, rather than whichever one is first on the path.
     */
    private static java.util.function.Function<String, String> engine(
            CommandExecutionContext c) {
        return key -> {
            final com.sphere.core.rootbackend.RootBackend backend = Handlers.backend(c);
            if (backend == null) {
                return "";
            }
            final String answer = backend.sendAwait(
                com.sphere.core.rootbackend.RootBackend.CMD_SYS_METRICS, 0,
                key.getBytes(StandardCharsets.UTF_8), 10000);
            return answer == null ? "" : answer;
        };
    }

    public static void rootEnvDetect(String i, CommandExecutionContext c) {
        AppLogger.raw(RootToolchain.report(engine(c)));
    }

    /**
     * Writes what another program needs to find what Sphere is using.
     *
     * A profile to source, and a stand-in for whichever of root-config and
     * lhapdf-config the machine is missing. Nothing is installed and nothing
     * already on the path is shadowed: a real installation always wins.
     */
    public static void rootEnvWrite(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root env write");
        if (a.isEmpty()) {
            Handlers.usage(":root env write <folder>");
            return;
        }
        final Path folder = Handlers.resolve(a).toPath();
        try {
            final List<Path> written = RootToolchain.write(folder, engine(c));
            StringBuilder text = new StringBuilder("Written into " + folder + ":");
            for (Path one : written) {
                text.append(String.format(Locale.ROOT, "%n  %s", one.getFileName()));
            }
            text.append(String.format(Locale.ROOT,
                "%n%nStart the other program from a shell that has run:"
                + "%n  . %s%n%nThat is all it takes: the variables are set and "
                + "%s is on the path ahead of anything else.",
                folder.resolve("sphere-env.sh"), folder));
            AppLogger.raw(text.toString());
        } catch (IOException cannotWrite) {
            AppLogger.error("Could not write into " + folder + ": " + cannotWrite.getMessage());
        }
    }

    /** The variables themselves, for a script that wants to set them its own way. */
    public static void rootEnvShow(String i, CommandExecutionContext c) {
        final Map<String, String> env = RootToolchain.environment(engine(c));
        if (env.isEmpty()) {
            AppLogger.warn("Nothing to publish: no ROOT answered and no PDF folder is named.");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> one : env.entrySet()) {
            text.append(text.length() == 0 ? "" : System.lineSeparator())
                .append("export ").append(one.getKey()).append("=\"")
                .append(one.getValue()).append("\"");
        }
        AppLogger.raw(text.toString());
    }
    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** The set a name stands for, with a message rather than null in the log. */
    private static RootPdfSet named(String name) {
        if (name == null || name.isBlank()) {
            Handlers.usage(":root pdf <command> <set-name> ...  (':root pdf list' shows them)");
            return null;
        }
        RootPdfSet set = OPEN.get(name.trim());
        if (set == null) {
            AppLogger.error("No set named " + name.trim()
                + ". ':root pdf open " + name.trim() + " <folder>' opens one.");
        }
        return set;
    }

    /** A particle code, by number or by the name it usually goes by. */
    private static int particle(String word) {
        final String bare = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        return switch (bare) {
            case "g", "gluon" -> 21;
            case "gamma", "photon" -> 22;
            case "d" -> 1;
            case "dbar", "-d" -> -1;
            case "u" -> 2;
            case "ubar", "-u" -> -2;
            case "s" -> 3;
            case "sbar", "-s" -> -3;
            case "c", "charm" -> 4;
            case "cbar", "-c" -> -4;
            case "b", "bottom" -> 5;
            case "bbar", "-b" -> -5;
            case "t", "top" -> 6;
            case "tbar", "-t" -> -6;
            default -> Handlers.asInt(bare, 21);
        };
    }

    private static double number(String word) {
        try {
            return Double.parseDouble(word.trim());
        } catch (RuntimeException notANumber) {
            return Double.NaN;
        }
    }

    private static double[] logSteps(double from, double to, int count) {
        double[] steps = new double[Math.max(count, 2)];
        final double low = Math.log(Math.max(from, 1e-12));
        final double high = Math.log(to);
        for (int k = 0; k < steps.length; k++) {
            steps[k] = Math.exp(low + (high - low) * k / (steps.length - 1.0));
        }
        return steps;
    }

    private static String few(double[] values, boolean root) {
        StringBuilder text = new StringBuilder();
        for (int k = 0; k < Math.min(6, values.length); k++) {
            text.append(k == 0 ? "" : "  ").append(String.format(Locale.ROOT, "%.6g",
                root ? Math.sqrt(values[k]) : values[k]));
        }
        return text.append(values.length > 6 ? "  ..." : "").toString();
    }

    private static void report(List<RootPdfGrid.Finding> found, String indent) {
        for (RootPdfGrid.Finding one : found) {
            if (one.fatal()) {
                AppLogger.error(indent + one.message());
            } else {
                AppLogger.warn(indent + one.message());
            }
        }
    }

    /** The names a set is open under, for whatever wants to offer a choice. */
    public static List<String> openNames() {
        return new ArrayList<>(OPEN.keySet());
    }

    /** A set by the name it was opened under, for the rest of Sphere. */
    public static RootPdfSet opened(String name) {
        return OPEN.get(name);
    }
}
