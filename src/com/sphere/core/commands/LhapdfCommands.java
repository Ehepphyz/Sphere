package com.sphere.core.commands;

import com.sphere.core.rootbackend.LhapdfCitation;
import com.sphere.core.rootbackend.PdfUncertainty;
import com.sphere.core.rootbackend.RootPdfCatalog;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.utils.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parton distributions the LHAPDF 6 way, read by Sphere's own Java reader:
 * the {@code :lpdf} commands.
 *
 * The names follow LHAPDF's API and tools (mkPDF, mkPDFs, xfxQ, xfxQ2,
 * alphasQ, inRangeXQ, PDFSet::uncertainty, PDFSet::correlation, "lhapdf
 * list / show / install"), the grids are the same lhagrid1 files, and the
 * uncertainties follow LHAPDF's definitions, confidence-level rescaling
 * included. A handle opened here is also known to the {@code :root pdf}
 * commands under the same name.
 *
 * Beyond LHAPDF: {@code :lpdf reweight} reweights a Les Houches event file
 * from one set to another (or to every member of a set, giving the PDF
 * uncertainty of the sample's cross section), with the statistics that say
 * whether the reweighting can be trusted.
 */
public final class LhapdfCommands {

    /** A PDF (one member) or a set (every member) opened under a name. */
    private record Handle(RootPdfSet set, int member, boolean wholeSet) {
        RootPdfGrid pdf() {
            return set.member(member);
        }
    }

    private static final Map<String, Handle> HANDLES = new LinkedHashMap<>();

    private LhapdfCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void register() {
        CommandDefinitions.register(":lpdf help", "What the PDF commands do, with LHAPDF's names", LhapdfCommands::help);
        CommandDefinitions.register(":lpdf version", "The version (LHAPDF Java Sphere); references under Citations in the console menu", LhapdfCommands::version);
        CommandDefinitions.register(":lpdf paths", "Where sets are looked for. Usage: :lpdf paths [add|drop <folder>]", LhapdfCommands::paths);
        CommandDefinitions.register(":lpdf list", "The sets installed, as 'lhapdf list --installed'", LhapdfCommands::list);
        CommandDefinitions.register(":lpdf search", "Look a published set up. Usage: :lpdf search <text|id>", LhapdfCommands::search);
        CommandDefinitions.register(":lpdf install", "Download and install a set, as 'lhapdf install'. Usage: :lpdf install <name>", LhapdfCommands::install);
        CommandDefinitions.register(":lpdf show", "A set's metadata, as 'lhapdf show'. Usage: :lpdf show <set>", LhapdfCommands::show);
        CommandDefinitions.register(":lpdf mkpdf", "Open one member, LHAPDF::mkPDF. Usage: :lpdf mkpdf <set>[/member] [as <handle>]", LhapdfCommands::mkpdf);
        CommandDefinitions.register(":lpdf mkpdfs", "Open every member, LHAPDF::mkPDFs. Usage: :lpdf mkpdfs <set> [as <handle>]", LhapdfCommands::mkpdfs);
        CommandDefinitions.register(":lpdf pdfs", "The handles open", LhapdfCommands::pdfs);
        CommandDefinitions.register(":lpdf xfxq", "x f(x, Q). Usage: :lpdf xfxq <handle> <pid|all> <x> <Q>", LhapdfCommands::xfxq);
        CommandDefinitions.register(":lpdf xfxq2", "x f(x, Q^2). Usage: :lpdf xfxq2 <handle> <pid|all> <x> <Q2>", LhapdfCommands::xfxq2);
        CommandDefinitions.register(":lpdf alphasq", "alpha_s(Q). Usage: :lpdf alphasq <handle> <Q>", LhapdfCommands::alphasq);
        CommandDefinitions.register(":lpdf alphasq2", "alpha_s(Q^2). Usage: :lpdf alphasq2 <handle> <Q2>", LhapdfCommands::alphasq2);
        CommandDefinitions.register(":lpdf inrange", "Whether (x, Q) is inside the grid, inRangeXQ. Usage: :lpdf inrange <handle> <x> <Q>", LhapdfCommands::inrange);
        CommandDefinitions.register(":lpdf uncertainty", "PDFSet::uncertainty. Usage: :lpdf uncertainty <handle> <pid> <x> <Q> [--cl 68.27] [--alternative]", LhapdfCommands::uncertainty);
        CommandDefinitions.register(":lpdf correlation", "PDFSet::correlation. Usage: :lpdf correlation <handle> <pidA> <xA> <QA> <pidB> <xB> <QB>", LhapdfCommands::correlation);
        CommandDefinitions.register(":lpdf members", "Every member's value at a point. Usage: :lpdf members <handle> <pid> <x> <Q>", LhapdfCommands::members);
        CommandDefinitions.register(":lpdf table", "x f for every flavour across x. Usage: :lpdf table <handle> [Q] [--points n]", LhapdfCommands::table);
        CommandDefinitions.register(":lpdf reweight", "Reweight an LHE file to another set. Usage: :lpdf reweight <file.lhe> <from> <to> [--members] [--write out.lhe] [--cl 68.27]", LhapdfCommands::reweight);
        CommandDefinitions.register(":lpdf close", "Close a handle, or all. Usage: :lpdf close [handle]", LhapdfCommands::close);
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    private static String[] words(String i, String command) {
        return Handlers.words(Handlers.args(i, command));
    }

    private static Handle handle(String name) {
        final Handle h = HANDLES.get(name);
        if (h == null) {
            AppLogger.error("No handle named " + name + ". ':lpdf mkpdf <set> as " + name + "' opens one.");
        }
        return h;
    }

    private static int pid(String w) {
        return switch (w.toLowerCase(Locale.ROOT)) {
            case "g", "gluon", "0" -> 21;
            case "d" -> 1;
            case "dbar" -> -1;
            case "u" -> 2;
            case "ubar" -> -2;
            case "s" -> 3;
            case "sbar" -> -3;
            case "c" -> 4;
            case "cbar" -> -4;
            case "b" -> 5;
            case "bbar" -> -5;
            case "t" -> 6;
            case "tbar" -> -6;
            case "photon", "gamma" -> 22;
            default -> Integer.parseInt(w);
        };
    }

    private static double num(String w) {
        return Double.parseDouble(w);
    }

    private static double opt(String[] w, String key, double fallback) {
        for (int k = 0; k + 1 < w.length; k++) {
            if (w[k].equalsIgnoreCase("--" + key)) return Double.parseDouble(w[k + 1]);
        }
        return fallback;
    }

    private static boolean flag(String[] w, String key) {
        for (String s : w) if (s.equalsIgnoreCase("--" + key)) return true;
        return false;
    }

    private static String optString(String[] w, String key) {
        for (int k = 0; k + 1 < w.length; k++) {
            if (w[k].equalsIgnoreCase("--" + key)) return w[k + 1];
        }
        return null;
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    /* ------------------------------------------------------------------ */
    /* Sets on disk                                                        */
    /* ------------------------------------------------------------------ */

    public static void help(String i, CommandExecutionContext c) {
        AppLogger.info("Parton distributions, LHAPDF 6 style, read in Java (no LHAPDF needed):");
        AppLogger.raw("  find        :lpdf list | search <text> | install <name> | show <set> | paths");
        AppLogger.raw("  open        :lpdf mkpdf CT18NLO/0 as ct   (one member)   :lpdf mkpdfs CT18NLO as cts   (every member)");
        AppLogger.raw("  evaluate    :lpdf xfxq ct g 0.01 100 | xfxq2 | alphasq ct 91.1876 | inrange | table ct 100");
        AppLogger.raw("  uncertainty :lpdf uncertainty cts g 0.01 100 --cl 90 | correlation | members");
        AppLogger.raw("  events      :lpdf reweight events.lhe nnpdf ct18 --members --write reweighted.lhe");
        AppLogger.raw("  Handles are shared with ':root pdf' (plots, luminosities, sum rules).");
    }

    /** The version only; the references are under Citations in the console's context menu. */
    public static void version(String i, CommandExecutionContext c) {
        AppLogger.raw(LhapdfCitation.VERSION_LINE);
    }

    public static void paths(String i, CommandExecutionContext c) {
        RootPdfCommands.rootPdfWhere(":root pdf where " + Handlers.args(i, ":lpdf paths"), c);
    }

    public static void list(String i, CommandExecutionContext c) {
        RootPdfCommands.rootPdfInstalled(":root pdf installed", c);
    }

    public static void search(String i, CommandExecutionContext c) {
        RootPdfCommands.rootPdfSearch(":root pdf search " + Handlers.args(i, ":lpdf search"), c);
    }

    public static void install(String i, CommandExecutionContext c) {
        RootPdfCommands.rootPdfFetch(":root pdf fetch " + Handlers.args(i, ":lpdf install"), c);
    }

    public static void show(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf show");
        if (w.length < 1) {
            Handlers.usage(":lpdf show <set>");
            return;
        }
        final Path folder = RootPdfCatalog.find(w[0]);
        if (folder == null) {
            AppLogger.error("No set " + w[0] + " in the PDF paths (':lpdf list', ':lpdf install " + w[0] + "').");
            return;
        }
        final Path info = folder.resolve(folder.getFileName() + ".info");
        AppLogger.info(folder.getFileName() + "  (" + folder + ")");
        try {
            if (Files.isRegularFile(info)) {
                for (String line : Files.readAllLines(info, StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.startsWith("#")) AppLogger.raw("  " + line);
                }
            } else {
                AppLogger.warn("No info file: the set has no metadata and no uncertainty definition.");
            }
            long members;
            try (var files = Files.list(folder)) {
                members = files.filter(p -> p.getFileName().toString().matches(".*_\\d{4}\\.dat")).count();
            }
            AppLogger.info(members + " member file(s) on disk.");
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Opening                                                             */
    /* ------------------------------------------------------------------ */

    public static void mkpdf(String i, CommandExecutionContext c) {
        open(words(i, ":lpdf mkpdf"), false);
    }

    public static void mkpdfs(String i, CommandExecutionContext c) {
        open(words(i, ":lpdf mkpdfs"), true);
    }

    private static void open(String[] w, boolean whole) {
        if (w.length < 1) {
            Handlers.usage(whole ? ":lpdf mkpdfs <set> [as <handle>]" : ":lpdf mkpdf <set>[/member] [as <handle>]");
            return;
        }
        String name = w[0];
        int member = 0;
        final int slash = name.lastIndexOf('/');
        if (!whole && slash > 0 && name.substring(slash + 1).matches("\\d+") && RootPdfCatalog.find(name) == null) {
            member = Integer.parseInt(name.substring(slash + 1));
            name = name.substring(0, slash);
        }
        String as = name.replaceAll(".*[/\\\\]", "") + (whole ? "" : (member == 0 ? "" : "_" + member));
        for (int k = 1; k + 1 < w.length; k++) {
            if (w[k].equalsIgnoreCase("as")) as = w[k + 1];
        }
        try {
            final RootPdfSet set = RootPdfCatalog.open(name, whole ? Integer.MAX_VALUE : member + 1,
                RootPdfGrid.Accuracy.LHAPDF);
            if (member >= set.memberCount()) {
                AppLogger.error(name + " has " + set.memberCount() + " member(s); member " + member + " does not exist.");
                return;
            }
            HANDLES.put(as, new Handle(set, member, whole));
            RootPdfCommands.adopt(as, set);
            if (LhapdfCitation.firstUse()) AppLogger.raw(LhapdfCitation.VERSION_LINE);
            AppLogger.info(as + " -> " + set.name() + (whole ? f(" (%d members, %s errors at %.4g%% CL)",
                set.memberCount(), set.errorType(), set.confidenceLevel()) : "/" + member));
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void pdfs(String i, CommandExecutionContext c) {
        if (HANDLES.isEmpty()) {
            AppLogger.info("No handle open.");
            return;
        }
        for (Map.Entry<String, Handle> e : HANDLES.entrySet()) {
            final Handle h = e.getValue();
            AppLogger.raw(f("  %-14s %s%s", e.getKey(), h.set().name(),
                h.wholeSet() ? f(" (%d members, %s)", h.set().memberCount(), h.set().errorType()) : "/" + h.member()));
        }
    }

    public static void close(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":lpdf close");
        if (a.isEmpty()) {
            for (String name : HANDLES.keySet()) RootPdfCommands.forget(name);
            AppLogger.info("Closed " + HANDLES.size() + " handle(s).");
            HANDLES.clear();
            return;
        }
        if (HANDLES.remove(a) != null) {
            RootPdfCommands.forget(a);
            AppLogger.info("Closed " + a);
        } else {
            AppLogger.error("No handle named " + a);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Evaluation                                                          */
    /* ------------------------------------------------------------------ */

    public static void xfxq(String i, CommandExecutionContext c) {
        evaluate(words(i, ":lpdf xfxq"), false);
    }

    public static void xfxq2(String i, CommandExecutionContext c) {
        evaluate(words(i, ":lpdf xfxq2"), true);
    }

    private static void evaluate(String[] w, boolean squared) {
        if (w.length < 4) {
            Handlers.usage(squared ? ":lpdf xfxq2 <handle> <pid|all> <x> <Q2>" : ":lpdf xfxq <handle> <pid|all> <x> <Q>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        try {
            final double x = num(w[2]);
            final double q2 = squared ? num(w[3]) : num(w[3]) * num(w[3]);
            if (!h.pdf().inRange(x, q2)) AppLogger.warn("(x, Q) outside the grid: the value is extrapolated.");
            if (w[1].equalsIgnoreCase("all")) {
                for (int p : h.set().flavors()) {
                    AppLogger.raw(f("  %4d  %.12e", p, h.pdf().xfxQ2(p, x, q2)));
                }
            } else {
                final int p = pid(w[1]);
                AppLogger.info(f("xf(%d, x=%g, Q2=%g) = %.12e", p, x, q2, h.pdf().xfxQ2(p, x, q2)));
            }
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void alphasq(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf alphasq");
        if (w.length < 2) {
            Handlers.usage(":lpdf alphasq <handle> <Q>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final double a = h.set().alphasQ(num(w[1]));
        AppLogger.info(Double.isNaN(a) ? "The set defines no alpha_s." : f("alpha_s(Q = %g GeV) = %.12f", num(w[1]), a));
    }

    public static void alphasq2(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf alphasq2");
        if (w.length < 2) {
            Handlers.usage(":lpdf alphasq2 <handle> <Q2>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final double a = h.set().alphasQ(Math.sqrt(num(w[1])));
        AppLogger.info(Double.isNaN(a) ? "The set defines no alpha_s." : f("alpha_s(Q2 = %g GeV^2) = %.12f", num(w[1]), a));
    }

    public static void inrange(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf inrange");
        if (w.length < 3) {
            Handlers.usage(":lpdf inrange <handle> <x> <Q>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final RootPdfGrid g = h.pdf();
        final boolean in = g.inRange(num(w[1]), num(w[2]) * num(w[2]));
        AppLogger.info(f("%s: x in [%g, %g], Q in [%g, %g] GeV", in ? "inside" : "OUTSIDE", g.xMin(), g.xMax(), g.qMin(), g.qMax()));
    }

    public static void members(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf members");
        if (w.length < 4) {
            Handlers.usage(":lpdf members <handle> <pid> <x> <Q>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final double[] v = h.set().members(pid(w[1]), num(w[2]), num(w[3]) * num(w[3]));
        for (int m = 0; m < v.length; m++) AppLogger.raw(f("  %4d  %.12e", m, v[m]));
    }

    public static void uncertainty(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf uncertainty");
        if (w.length < 4) {
            Handlers.usage(":lpdf uncertainty <handle> <pid> <x> <Q> [--cl 68.27] [--alternative]");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        if (!h.wholeSet()) AppLogger.warn("This handle holds one member; ':lpdf mkpdfs' opens the whole set.");
        final RootPdfSet set = h.set();
        final double cl = opt(w, "cl", set.confidenceLevel());
        final double[] v = set.members(pid(w[1]), num(w[2]), num(w[3]) * num(w[3]));
        final PdfUncertainty.Result r = PdfUncertainty.uncertainty(v, set.errorType(), set.confidenceLevel(), cl,
            flag(w, "alternative"));
        AppLogger.info(f("%s, %d members, %s errors (set at %.4g%% CL, given at %.4g%%%s):", set.name(), v.length,
            set.errorType(), set.confidenceLevel(), cl, r.scale() != 1 ? f(", scaled by %.6f", r.scale()) : ""));
        AppLogger.raw(f("  central %.10e   +%.6e  -%.6e   symmetric %.6e   (%.3f%%)", r.central(), r.errplus(),
            r.errminus(), r.errsymm(), r.central() == 0 ? 0 : 100 * r.errsymm() / Math.abs(r.central())));
    }

    public static void correlation(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf correlation");
        if (w.length < 7) {
            Handlers.usage(":lpdf correlation <handle> <pidA> <xA> <QA> <pidB> <xB> <QB>");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final RootPdfSet set = h.set();
        final double[] a = set.members(pid(w[1]), num(w[2]), num(w[3]) * num(w[3]));
        final double[] b = set.members(pid(w[4]), num(w[5]), num(w[6]) * num(w[6]));
        AppLogger.info(f("correlation = %.8f  (%s, %d members, %s)", PdfUncertainty.correlation(a, b, set.errorType()),
            set.name(), a.length, set.errorType()));
    }

    public static void table(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf table");
        if (w.length < 1) {
            Handlers.usage(":lpdf table <handle> [Q] [--points n]");
            return;
        }
        final Handle h = handle(w[0]);
        if (h == null) return;
        final double q = w.length > 1 && !w[1].startsWith("--") ? num(w[1]) : 100.0;
        final int n = (int) opt(w, "points", 12);
        final RootPdfGrid g = h.pdf();
        final int[] fl = h.set().flavors();
        final StringBuilder head = new StringBuilder(f("%12s", "x"));
        for (int p : fl) head.append(f(" %12s", "xf(" + p + ")"));
        AppLogger.info(h.set().name() + f(" at Q = %g GeV:", q));
        AppLogger.raw(head.toString());
        final double lo = Math.log(Math.max(g.xMin(), 1e-7));
        for (int k = 0; k < n; k++) {
            final double x = Math.exp(lo + (Math.log(0.95) - lo) * k / (n - 1.0));
            final StringBuilder line = new StringBuilder(f("%12.5e", x));
            for (int p : fl) line.append(f(" %12.5e", g.xfxQ2(p, x, q * q)));
            AppLogger.raw(line.toString());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Reweighting of Les Houches events                                   */
    /* ------------------------------------------------------------------ */

    public static void reweight(String i, CommandExecutionContext c) {
        final String[] w = words(i, ":lpdf reweight");
        if (w.length < 3) {
            Handlers.usage(":lpdf reweight <file.lhe> <from-handle> <to-handle> [--members] [--write out.lhe] [--cl 68.27]");
            return;
        }
        final Handle from = handle(w[1]);
        final Handle to = handle(w[2]);
        if (from == null || to == null) return;
        final Path file = Handlers.resolve(w[0]).toPath();
        final boolean allMembers = flag(w, "members");
        if (allMembers && !to.wholeSet()) {
            AppLogger.error("--members needs the target opened with every member (':lpdf mkpdfs').");
            return;
        }
        final String writeTo = optString(w, "write");
        try {
            reweight(file, from, to, allMembers, writeTo == null ? null : Handlers.resolve(writeTo).toPath(),
                opt(w, "cl", to.set().confidenceLevel()));
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Reweighting failed: " + e.getMessage());
        }
    }

    private static void reweight(Path file, Handle from, Handle to, boolean allMembers, Path out, double cl)
            throws IOException {
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        double eb1 = Double.NaN;
        double eb2 = Double.NaN;
        final int nm = allMembers ? to.set().memberCount() : 1;
        final double[] sumNew = new double[nm];
        double sumOld = 0;
        double sumRatio = 0;
        double sumW = 0;
        double sumW2 = 0;
        double rmin = Double.MAX_VALUE;
        double rmax = 0;
        int nev = 0;
        int extreme = 0;
        int outside = 0;
        final List<String> rewritten = out == null ? null : new ArrayList<>(lines.size());
        for (int k = 0; k < lines.size(); k++) {
            final String line = lines.get(k);
            if (line.trim().startsWith("<init") && Double.isNaN(eb1)) {
                if (rewritten != null) rewritten.add(line);
                final String[] v = lines.get(++k).trim().split("\\s+");
                eb1 = Double.parseDouble(v[2]);
                eb2 = Double.parseDouble(v[3]);
                if (rewritten != null) rewritten.add(lines.get(k));
                continue;
            }
            if (!line.trim().startsWith("<event")) {
                if (rewritten != null) rewritten.add(line);
                continue;
            }
            if (Double.isNaN(eb1)) throw new IOException("no <init> block before the first event");
            if (rewritten != null) rewritten.add(line);
            final String[] head = lines.get(++k).trim().split("\\s+");
            final int nup = Integer.parseInt(head[0]);
            final double wOld = Double.parseDouble(head[2]);
            final double scale = Double.parseDouble(head[3]);
            int id1 = 0;
            int id2 = 0;
            double x1 = 0;
            double x2 = 0;
            final List<String> particleLines = new ArrayList<>(nup);
            for (int p = 0; p < nup; p++) {
                final String pl = lines.get(++k);
                particleLines.add(pl);
                final String[] v = pl.trim().split("\\s+");
                if (Integer.parseInt(v[1]) != -1) continue;
                final double pz = Double.parseDouble(v[8]);
                final double e = Double.parseDouble(v[9]);
                if (pz >= 0 && id1 == 0) {
                    id1 = Integer.parseInt(v[0]);
                    x1 = (e + Math.abs(pz)) / (2 * eb1);
                } else {
                    id2 = Integer.parseInt(v[0]);
                    x2 = (e + Math.abs(pz)) / (2 * eb2);
                }
            }
            final double q2 = scale * scale;
            if (!from.pdf().inRange(x1, q2) || !from.pdf().inRange(x2, q2)) outside++;
            final double fOld = from.pdf().xfxQ2(pdg(id1), x1, q2) * from.pdf().xfxQ2(pdg(id2), x2, q2);
            final double[] ratio = new double[nm];
            for (int m = 0; m < nm; m++) {
                final RootPdfGrid g = allMembers ? to.set().member(m) : to.pdf();
                ratio[m] = fOld == 0 ? 0 : g.xfxQ2(pdg(id1), x1, q2) * g.xfxQ2(pdg(id2), x2, q2) / fOld;
                sumNew[m] += wOld * ratio[m];
            }
            final double r = ratio[0];
            sumOld += wOld;
            sumRatio += r;
            final double wNew = wOld * r;
            sumW += wNew;
            sumW2 += wNew * wNew;
            rmin = Math.min(rmin, r);
            rmax = Math.max(rmax, r);
            if (r > 3 || r < 1.0 / 3) extreme++;
            nev++;
            if (rewritten != null) {
                final StringBuilder h = new StringBuilder();
                for (int q = 0; q < head.length; q++) {
                    h.append(q == 0 ? " " : " ").append(q == 2 ? f("%.10e", wNew) : head[q]);
                }
                rewritten.add(h.toString());
                rewritten.addAll(particleLines);
                if (allMembers) {
                    final StringBuilder r2 = new StringBuilder("<rwgt>");
                    for (int m = 0; m < nm; m++) r2.append(f(" <wgt id='pdf%d'>%.8e</wgt>", m, wOld * ratio[m]));
                    rewritten.add(r2.append(" </rwgt>").toString());
                }
            }
        }
        if (nev == 0) {
            AppLogger.error("No event in " + file);
            return;
        }
        AppLogger.info(f("%d events reweighted from %s to %s (beams %.1f and %.1f GeV, scale = SCALUP).",
            nev, from.set().name(), to.set().name(), eb1, eb2));
        AppLogger.raw(f("  sum of weights: %.8e -> %.8e   (ratio %.6f)", sumOld, sumNew[0], sumNew[0] / sumOld));
        AppLogger.raw(f("  event weight ratio: mean %.6f, min %.6f, max %.6f; %d beyond a factor 3", sumRatio / nev, rmin,
            rmax, extreme));
        AppLogger.raw(f("  effective sample size after reweighting: %.1f of %d (%.1f%%)", sumW * sumW / sumW2, nev,
            100 * sumW * sumW / sumW2 / nev));
        if (outside > 0) AppLogger.warn(outside + " events have an x or Q outside the source set's grid.");
        if (allMembers) {
            final PdfUncertainty.Result u = PdfUncertainty.uncertainty(sumNew, to.set().errorType(),
                to.set().confidenceLevel(), cl, false);
            AppLogger.raw(f("  PDF uncertainty of the sum of weights (%s, %d members, %.4g%% CL): %.8e +%.4e -%.4e  (+%.3f%% -%.3f%%)",
                to.set().errorType(), nm, cl, u.central(), u.errplus(), u.errminus(),
                100 * u.errplus() / u.central(), 100 * u.errminus() / u.central()));
        }
        if (rewritten != null) {
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                for (String s : rewritten) pw.println(s);
            }
            AppLogger.info("Reweighted events written to " + out + (allMembers ? " (one <wgt> per member)" : ""));
        }
    }

    /** The PDG code of an incoming parton as a PDF flavour (21 for a gluon given as 0 or 9). */
    private static int pdg(int id) {
        return (id == 0 || id == 9) ? 21 : id;
    }

    /** For tests: reads an LHE file's number of events. */
    static int countEvents(Path file) throws IOException {
        int n = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) if (line.trim().startsWith("<event")) n++;
        }
        return n;
    }
}
