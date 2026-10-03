package com.sphere.core.commands;

import com.sphere.core.InProcessEngine;
import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.BridgeCrossCheck;
import com.sphere.core.bridge.BridgeOutbox;
import com.sphere.core.bridge.Spx;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.contrib.EventBatch;
import com.sphere.core.fastjet.io.EventIO;
import com.sphere.core.rootbackend.PdfUncertainty;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.core.rootbackend.RootPdfGrid;
import com.sphere.core.rootbackend.RootPdfSet;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The engines, talking: the {@code :bridge} commands, and {@code :fjet pdfband}
 * which needs two of them at once.
 *
 * Sphere runs Java, Julia, Fortran, C++, ROOT and Python side by side, and each
 * used to be an island. The bridge gives them one exchange format, SPX, which
 * each reads natively and without a copy: a PDF set opened by {@code :lpdf}
 * becomes a Julia object, a Fortran program's evolvePDF, a C++ or ROOT
 * spx::PDF; the events and jets of {@code :fjet} become Julia arrays or a ROOT
 * tree; and whatever any of them publishes comes back to the Plots tab by
 * itself. {@code :bridge crosscheck} proves the PDF readers agree with Java to
 * the bit, and times them.
 */
public final class BridgeCommands {

    private BridgeCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void register() {
        CommandDefinitions.register(":bridge help", "How the engines exchange PDFs, events and results", BridgeCommands::help);
        CommandDefinitions.register(":bridge engines", "Which engines are there to talk to, and where", BridgeCommands::engines);
        CommandDefinitions.register(":bridge pdf", "Hand a PDF handle to every engine. Usage: :bridge pdf <handle> [as <name>]", BridgeCommands::pdf);
        CommandDefinitions.register(":bridge events", "Hand the :fjet events and their jets to every engine. Usage: :bridge events [--ptmin GeV] [--nojets] [as <name>]", BridgeCommands::events);
        CommandDefinitions.register(":bridge julia", "Bind a PDF or the events in the Julia session. Usage: :bridge julia <pdf <handle>|events|<file.spx>> [as <var>]", BridgeCommands::julia);
        CommandDefinitions.register(":bridge root", "Make ROOT objects of a PDF, the events or a table. Usage: :bridge root <pdf <handle> [pid Q]|events|<file.spx>> [as <name>]", BridgeCommands::root);
        CommandDefinitions.register(":bridge show", "What an SPX file holds. Usage: :bridge show <file.spx|name>", BridgeCommands::show);
        CommandDefinitions.register(":bridge plot", "Draw a table an engine wrote. Usage: :bridge plot <file.spx|name>", BridgeCommands::plot);
        CommandDefinitions.register(":bridge libs", "Write the readers for C++/ROOT, Julia, Fortran and Python. Usage: :bridge libs [folder]", BridgeCommands::libs);
        CommandDefinitions.register(":bridge outbox", "Watch the folder engines publish results into", BridgeCommands::outbox);
        CommandDefinitions.register(":bridge crosscheck", "Every engine against Java on the same PDF points: bits and speed. Usage: :bridge crosscheck <handle> [--points n] [--seed s] [--engines java,c++,fortran,julia,python,root]", BridgeCommands::crosscheck);
        CommandDefinitions.register(":fjet pdfband", "The PDF uncertainty of a jet observable, from LHE events reweighted member by member. Usage: :fjet pdfband <observable> <from-handle> <to-handle> [--bins n] [--njets n] [--ptmin GeV] [--lo v] [--hi v] [--cl 68.27]", BridgeCommands::pdfband);
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** Positional words and --options, as the other command families read them. */
    private record Args(List<String> plain, Map<String, String> opts, String as) {
        static Args of(String input, String command) {
            final String[] w = Handlers.words(Handlers.args(input, command));
            final List<String> plain = new ArrayList<>();
            final Map<String, String> opts = new LinkedHashMap<>();
            String as = null;
            for (int k = 0; k < w.length; k++) {
                if (w[k].startsWith("--")) {
                    final String key = w[k].substring(2).toLowerCase(Locale.ROOT);
                    if (k + 1 < w.length && !w[k + 1].startsWith("--")) opts.put(key, w[++k]);
                    else opts.put(key, "");
                } else if (w[k].equalsIgnoreCase("as") && k + 1 < w.length) {
                    as = w[++k];
                } else {
                    plain.add(w[k]);
                }
            }
            return new Args(plain, opts, as);
        }

        double num(String key, double fallback) {
            try {
                return opts.containsKey(key) ? Double.parseDouble(opts.get(key)) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        String word(int k) {
            return k < plain.size() ? plain.get(k) : null;
        }
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    /** A file, or a bare name looked for in the bridge folder. */
    private static Path spxFile(String nameOrPath) {
        final Path given = Handlers.resolve(nameOrPath).toPath();
        if (Files.isRegularFile(given)) return given;
        for (Path dir : List.of(Bridge.folder(), Bridge.outbox())) {
            final Path named = dir.resolve(nameOrPath.endsWith(".spx") ? nameOrPath : nameOrPath + ".spx");
            if (Files.isRegularFile(named)) return named;
        }
        return given;
    }

    /** Exports an open PDF handle, every member it holds. */
    private static Path exportPdf(String handle, String as) throws IOException {
        final RootPdfSet set = LhapdfCommands.handleSet(handle);
        if (set == null) return null;
        if (!LhapdfCommands.handleWhole(handle)) {
            AppLogger.warn(handle + " holds member(s) up to the one opened; ':lpdf mkpdfs' opens every member.");
        }
        final String name = SpxExport.safeName(as != null ? as : set.name());
        final Path out = SpxExport.pdf(set, Bridge.folder().resolve(name + ".spx"));
        AppLogger.result(f("%s -> %s  (%d members, %.1f MB)", handle, out, set.memberCount(),
            Files.size(out) / 1048576.0));
        return out;
    }

    /** Exports the :fjet events, and their jets with the active definition unless told not to. */
    private static Path exportEvents(Args a) throws IOException {
        final List<List<PseudoJet>> events = FastJetCommands.loadedEvents();
        if (events.isEmpty()) {
            AppLogger.error("No events: ':fjet read <file>' or ':fjet toy' first.");
            return null;
        }
        final JetDefinition def = a.opts().containsKey("nojets") ? null : FastJetCommands.activeDefinition();
        final double ptmin = a.num("ptmin", 5.0);
        final String name = SpxExport.safeName(a.as() != null ? a.as() : "events");
        final Path out = SpxExport.events(events, FastJetCommands.loadedWeights(), FastJetCommands.loadedIncoming(),
            def, ptmin, FastJetCommands.loadedSource(), Runtime.getRuntime().availableProcessors(),
            Bridge.folder().resolve(name + ".spx"));
        AppLogger.result(f("%d events -> %s%s", events.size(), out,
            def == null ? "" : f(" (jets: %s above %g GeV)", FastJetCommands.activeName(), ptmin)));
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Commands                                                            */
    /* ------------------------------------------------------------------ */

    public static void help(String i, CommandExecutionContext c) {
        AppLogger.result("The bridge: one binary format (SPX) that Java, Julia, Fortran, C++, ROOT and Python read in place.");
        AppLogger.raw("  hand over   :bridge pdf <handle>        a PDF set, every member, for any engine");
        AppLogger.raw("              :bridge events              the :fjet events with their jets and incoming partons");
        AppLogger.raw("  bind        :bridge julia pdf ct as ct   ct is then a SphereSPX.PDFSet in the Julia session");
        AppLogger.raw("              :bridge root events          a TTree of vectors, jets included; pdf <h> gives a TGraph");
        AppLogger.raw("  in code     C++/ROOT  #include \"sphere_spx.hpp\"   spx::PDF::open(\"CT18NLO\").xfxQ2(21, x, Q2)");
        AppLogger.raw("              Julia     SphereSPX.pdf(\"CT18NLO\")        (loaded in the session already)");
        AppLogger.raw("              Fortran   call InitPDFsetByName('CT18NLO'); call evolvePDF(x, Q, f)   -- LHAPDF's own calls,");
        AppLogger.raw("                        linked and the set exported by Sphere: legacy code runs unchanged, no LHAPDF needed");
        AppLogger.raw("              Python    import sphere_spx; sphere_spx.pdf(\"CT18NLO\")");
        AppLogger.raw("  results     publish(\"name\", edges, values[, err_plus, err_minus]) in any of them -> the Plots tab");
        AppLogger.raw("  proof       :bridge crosscheck <handle>  every engine against Java, bit for bit, and their speed");
        AppLogger.raw("  physics     :fjet pdfband pt nnpdf ct18   PDF uncertainty band of a jet observable from LHE events");
        AppLogger.raw("              :fjco bridge tau21 d2 as obs   the fjcontrib observables of every jet, as a table for all");
        AppLogger.raw("  files       :bridge show | plot <file>, :bridge libs [folder], :bridge engines, :bridge outbox");
    }

    public static void engines(String i, CommandExecutionContext c) {
        final SettingsManager s = new SettingsManager();
        final RootBackend root = RootBackend.getInstance();
        AppLogger.result("Engines the bridge can reach (exchange folder " + Bridge.folder() + "):");
        AppLogger.raw(f("  %-8s %s", "java", "this Sphere, " + Runtime.version()));
        AppLogger.raw(f("  %-8s %s", "c++", orMissing(Bridge.cppCompiler(s), "GPP_DIR")));
        AppLogger.raw(f("  %-8s %s", "fortran", orMissing(Bridge.fortranCompiler(s), "ENV_FC or FORTRAN_DIR")));
        AppLogger.raw(f("  %-8s %s", "julia", orMissing(Bridge.julia(s), "JULIA_DIR")
            + (Handlers.julia().isRunning() ? "  (session open, SphereSPX loaded)" : "")));
        AppLogger.raw(f("  %-8s %s", "python", orMissing(Bridge.python(s), "PYTHON_EXEC")));
        AppLogger.raw(f("  %-8s %s", "root", root != null && root.isAvailable() ? "engine running" : "not running"));
        // The two that run in this JVM hand their events and tables to all the others.
        for (InProcessEngine engine : InProcessEngine.values()) {
            final InProcessEngine.Native cpp = engine.findNative();
            AppLogger.raw(f("  %-8s %s", engine.key(), engine.description()
                + (cpp == null ? "" : "; C++ too, at " + cpp.prefix())));
        }
    }

    private static String orMissing(String tool, String key) {
        return tool != null ? tool : "not found (set " + key + " in settings.conf)";
    }

    public static void pdf(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge pdf");
        if (a.word(0) == null) {
            Handlers.usage(":bridge pdf <handle> [as <name>]   (open one with ':lpdf mkpdfs <set> as <handle>')");
            return;
        }
        try {
            final Path out = exportPdf(a.word(0), a.as());
            if (out == null) return;
            final String stem = out.getFileName().toString().replace(".spx", "");
            AppLogger.raw("  C++/ROOT  spx::PDF::open(\"" + stem + "\")      Julia  SphereSPX.pdf(\"" + stem + "\")");
            AppLogger.raw("  Fortran   call InitPDFsetByName('" + stem + "')   Python sphere_spx.pdf(\"" + stem + "\")");
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Export failed: " + e.getMessage());
        }
    }

    public static void events(String i, CommandExecutionContext c) {
        try {
            exportEvents(Args.of(i, ":bridge events"));
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Export failed: " + e.getMessage());
        }
    }

    public static void julia(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge julia");
        final String what = a.word(0);
        if (what == null) {
            Handlers.usage(":bridge julia <pdf <handle>|events|<file.spx>> [as <var>]");
            return;
        }
        try {
            final Path file;
            final String var;
            final String kind;
            if (what.equalsIgnoreCase("pdf")) {
                if (a.word(1) == null) {
                    Handlers.usage(":bridge julia pdf <handle> [as <var>]");
                    return;
                }
                file = exportPdf(a.word(1), null);
                var = a.as() != null ? a.as() : a.word(1);
                kind = "PDFSet";
            } else if (what.equalsIgnoreCase("events")) {
                file = exportEvents(new Args(a.plain(), a.opts(), null));
                var = a.as() != null ? a.as() : "events";
                kind = "Events";
            } else {
                file = spxFile(what);
                var = a.as() != null ? a.as()
                    : file.getFileName().toString().replace(".spx", "").replaceAll("[^A-Za-z0-9_]", "_");
                kind = "load";
            }
            if (file == null) return;
            if (!var.matches("[A-Za-z_][A-Za-z0-9_!]*")) {
                AppLogger.error(var + " is not a Julia variable name.");
                return;
            }
            final var session = Handlers.julia();
            if (!session.isRunning()) session.start();
            final String path = "raw\"" + slash(file) + "\"";
            session.run(kind.equals("load") ? var + " = SphereSPX.load(" + path + ")"
                : var + " = SphereSPX." + kind + "(SphereSPX.SPXFile(" + path + "))");
            AppLogger.result(var + " is bound in the Julia session"
                + (kind.equals("PDFSet") ? f(": SphereSPX.xfxQ2(%s, 21, 1e-3, 1e4), SphereSPX.uncertainty(%s, 21, 1e-3, 1e4)",
                    var, var) : kind.equals("Events") ? f(": SphereSPX.particles(%s, 1), SphereSPX.jets(%s, 1)", var, var)
                    : "."));
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void root(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge root");
        final String what = a.word(0);
        if (what == null) {
            Handlers.usage(":bridge root <pdf <handle> [pid Q]|events|<file.spx>> [as <name>]");
            return;
        }
        final RootBackend root = RootBackend.getInstance();
        if (root == null || !root.isAvailable()) {
            AppLogger.error("ROOT is not running. The same files open in a ROOT macro with #include \"sphere_spx.hpp\".");
            return;
        }
        try {
            final String header = slash(Bridge.libraries().resolve("sphere_spx.hpp"));
            Handlers.clingAnswer(c, "gInterpreter->Declare(\"#include \\\"" + header + "\\\"\")");
            if (what.equalsIgnoreCase("pdf")) {
                final String handle = a.word(1);
                if (handle == null) {
                    Handlers.usage(":bridge root pdf <handle> [pid Q] [as <name>]");
                    return;
                }
                final Path file = exportPdf(handle, null);
                if (file == null) return;
                final int pid = a.word(2) == null ? 21 : LhapdfCommands.pid(a.word(2));
                final double q = a.word(3) == null ? 100.0 : Double.parseDouble(a.word(3));
                final String name = a.as() != null ? a.as() : handle + "_" + pid;
                Handlers.cling(c, Handlers.keep(name, "TGraph", "spx::root::pdfCurve(spx::PDF::open(\"" + slash(file)
                    + "\"), " + pid + ", " + q + ")"));
                AppLogger.raw("  In a macro: spx::PDF p = spx::PDF::open(\"" + slash(file) + "\"); p.xfxQ2(21, x, Q2);");
            } else if (what.equalsIgnoreCase("events")) {
                final Path file = exportEvents(new Args(a.plain(), a.opts(), null));
                if (file == null) return;
                final String name = a.as() != null ? a.as() : "events";
                Handlers.cling(c, Handlers.keep(name, "TTree", "spx::root::eventsTree(spx::Events::open(\"" + slash(file)
                    + "\"), \"" + name + "\")"));
            } else {
                final Path file = spxFile(what);
                final String name = a.as() != null ? a.as()
                    : file.getFileName().toString().replace(".spx", "").replaceAll("[^A-Za-z0-9_]", "_");
                boolean band;
                try (Spx.Reader r = new Spx.Reader(file)) {
                    if (!r.has("edges")) {
                        // a table of columns (':fjco bridge', ':fjco export x.spx'): a flat tree
                        Handlers.cling(c, Handlers.keep(name, "TTree", "spx::root::tableTree(\"" + slash(file)
                            + "\", \"" + name + "\")"));
                        return;
                    }
                    band = r.has("err_plus");
                }
                Handlers.cling(c, band
                    ? Handlers.keep(name, "TGraphAsymmErrors", "spx::root::band(\"" + slash(file) + "\")")
                    : Handlers.keep(name, "TH1D", "spx::root::histogram(\"" + slash(file) + "\", \"" + name + "\")"));
            }
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void show(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge show");
        if (a.word(0) == null) {
            try (var files = Files.list(Bridge.folder())) {
                AppLogger.result("SPX files in " + Bridge.folder() + ":");
                files.filter(p -> p.toString().endsWith(".spx")).sorted().forEach(p -> {
                    try {
                        AppLogger.raw(f("  %-40s %10.1f kB", p.getFileName(), Files.size(p) / 1024.0));
                    } catch (IOException ignored) {
                        // Gone since the listing.
                    }
                });
            } catch (IOException e) {
                AppLogger.result("Nothing exchanged yet.");
            }
            return;
        }
        final Path file = spxFile(a.word(0));
        try (Spx.Reader r = new Spx.Reader(file)) {
            AppLogger.result(f("%s: %s \"%s\", %.1f kB", file.getFileName(), r.kind(), r.title(), Files.size(file) / 1024.0));
            for (Spx.Section s : r.sections()) {
                AppLogger.raw(f("  %-24s %-8s %12d  at %d", s.name(), s.typeName(), s.count(), s.offset()));
            }
            for (Map.Entry<String, String> e : r.meta().entrySet()) {
                AppLogger.raw("  " + e.getKey() + ": " + e.getValue());
            }
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void plot(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge plot");
        if (a.word(0) == null) {
            Handlers.usage(":bridge plot <file.spx|name>");
            return;
        }
        final Path file = spxFile(a.word(0));
        try (Spx.Reader r = new Spx.Reader(file)) {
            BridgeOutbox.draw(r, file);
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void libs(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge libs");
        try {
            final Path lib = Bridge.libraries();
            Path into = lib;
            if (a.word(0) != null) {
                into = Handlers.resolve(a.word(0)).toPath();
                Files.createDirectories(into);
                for (String name : Bridge.LIBRARIES) {
                    Files.copy(lib.resolve(name), into.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            AppLogger.result("The bridge readers are in " + into + ":");
            AppLogger.raw("  sphere_spx.hpp       C++17 and ROOT, one header      SphereSPX.jl   Julia, Mmap, no package");
            AppLogger.raw("  sphere_spx.f90       Fortran 2008 module             sphere_lhaglue.f90  LHAPDF's Fortran calls");
            AppLogger.raw("  sphere_spx.py        Python, numpy optional");
            AppLogger.raw("  Programs Sphere starts find them already (SPHERE_BRIDGE_LIB, CPLUS_INCLUDE_PATH, PYTHONPATH).");
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void outbox(String i, CommandExecutionContext c) {
        BridgeOutbox.start();
        AppLogger.result((BridgeOutbox.running() ? "Watching " : "Not watching ") + Bridge.outbox()
            + ": what an engine publishes there is drawn in the Plots tab.");
    }

    public static void crosscheck(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":bridge crosscheck");
        if (a.word(0) == null) {
            Handlers.usage(":bridge crosscheck <handle> [--points n] [--seed s] [--engines java,c++,fortran,julia,python,root]");
            return;
        }
        final RootPdfSet set = LhapdfCommands.handleSet(a.word(0));
        if (set == null) return;
        final List<String> engines = a.opts().containsKey("engines")
            ? Arrays.asList(a.opts().get("engines").toLowerCase(Locale.ROOT).split(","))
            : BridgeCrossCheck.ENGINES;
        final RootBackend root = RootBackend.getInstance();
        final Function<String, String> cling = root != null && root.isAvailable()
            ? expression -> root.executeClingAwait(expression, 60000) : null;
        AppLogger.info(f("Cross-checking %s (%d members) on %d points in %s...", set.name(), set.memberCount(),
            (int) a.num("points", 2000), String.join(", ", engines)));
        try {
            final List<BridgeCrossCheck.Verdict> verdicts = BridgeCrossCheck.run(set, (int) a.num("points", 2000),
                (long) a.num("seed", 1), engines, new SettingsManager(), cling);
            AppLogger.raw(f("  %-8s %-18s %14s %12s %14s %12s", "engine", "", "xf identical", "max rel", "alpha_s ident.",
                "ns / eval"));
            for (BridgeCrossCheck.Verdict v : verdicts) {
                if (!v.ran()) {
                    AppLogger.raw(f("  %-8s not run: %s", v.engine(), v.note()));
                    continue;
                }
                AppLogger.raw(f("  %-8s %-18s %8d/%-5d %12.2e %8d/%-5d %12.1f", v.engine(),
                    v.note().length() > 18 ? v.note().substring(0, 18) : v.note(), v.identical(), v.points(),
                    v.maxRelative(), v.asIdentical(), v.asPoints(), v.nsPerEval()));
            }
            final double worst = verdicts.stream().filter(BridgeCrossCheck.Verdict::ran)
                .mapToDouble(v -> Math.max(v.maxRelative(), v.asMaxRelative())).max().orElse(0);
            if (worst == 0) {
                AppLogger.result("Every engine that ran gives Java's numbers, bit for bit.");
            } else if (worst < 1e-14) {
                AppLogger.result(f("Every engine agrees with Java to %.1e: the few values not bit-identical differ by the "
                    + "last digit of a logarithm, which the system's math library rounds its own way.", worst));
            } else {
                AppLogger.warn(f("An engine differs from Java by up to %.3g relative. For a coupling tabulated by Sphere "
                    + "(analytic or solved sets) that is the table's resolution; for x f it is a bug worth reporting.", worst));
            }
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Cross-check failed: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* FastJet x LHAPDF                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The PDF uncertainty of any jet observable, from one event sample.
     *
     * Each Les Houches event carries its two partons, their momentum fractions
     * and the scale; the ratio of the target member's parton luminosity to the
     * one the sample was made with reweights it to that member. Clustering once
     * and filling one histogram per member gives the observable under every
     * member of the set, and LHAPDF's uncertainty rule then gives the band bin
     * by bin: no event is generated twice. The band is drawn in the Plots tab
     * and exported, so any engine can take it further.
     */
    public static void pdfband(String i, CommandExecutionContext c) {
        final Args a = Args.of(i, ":fjet pdfband");
        if (a.plain().size() < 3) {
            Handlers.usage(":fjet pdfband <observable> <from-handle> <to-handle> [--bins n] [--njets n] [--ptmin GeV] "
                + "[--lo v] [--hi v] [--cl 68.27]");
            AppLogger.raw("  from: the PDF the events were generated with (':lpdf mkpdf'); to: the set whose band is "
                + "wanted (':lpdf mkpdfs'). Observables as for ':fjet hist'.");
            return;
        }
        final String what = a.word(0).toLowerCase(Locale.ROOT);
        final RootPdfGrid from = LhapdfCommands.handlePdf(a.word(1));
        final RootPdfSet to = LhapdfCommands.handleSet(a.word(2));
        if (from == null || to == null) return;
        if (!LhapdfCommands.handleWhole(a.word(2))) {
            AppLogger.error(a.word(2) + " holds one member; ':lpdf mkpdfs <set> as " + a.word(2) + "' opens every member.");
            return;
        }
        final List<List<PseudoJet>> events = FastJetCommands.loadedEvents();
        final List<Double> weights = FastJetCommands.loadedWeights();
        final List<EventIO.Incoming> incoming = FastJetCommands.loadedIncoming();
        final List<Integer> usable = new ArrayList<>();
        for (int e = 0; e < events.size(); e++) {
            if (e < incoming.size() && incoming.get(e) != null) usable.add(e);
        }
        if (usable.isEmpty()) {
            AppLogger.error("No event says which partons made it: read a Les Houches file (':fjet read events.lhe').");
            return;
        }
        final int njets = (int) a.num("njets", 1);
        final double ptmin = a.num("ptmin", 20);
        final int bins = Math.max(1, (int) a.num("bins", 20));
        final int nm = to.memberCount();
        final JetDefinition def = FastJetCommands.activeDefinition();

        // Cluster once; keep, per event, the observable values and the member weights.
        final List<List<PseudoJet>> sample = new ArrayList<>(usable.size());
        for (int e : usable) sample.add(events.get(e));
        final List<ClusterSequence> clustered = EventBatch.cluster(sample, def, Runtime.getRuntime().availableProcessors());
        final List<double[]> values = new ArrayList<>(usable.size());
        final double[][] memberWeight = new double[usable.size()][];
        int outside = 0;
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        try {
            for (int k = 0; k < usable.size(); k++) {
                final int e = usable.get(k);
                final ClusterSequence cs = clustered.get(k);
                final double[] v;
                if (what.equals("njets")) {
                    v = new double[]{cs.inclusiveJets(ptmin).size()};
                } else {
                    final List<PseudoJet> jets = FastJetCommands.hardest(cs, njets, ptmin);
                    v = new double[jets.size()];
                    for (int j = 0; j < v.length; j++) v[j] = FastJetCommands.jetObservable(what, jets.get(j));
                }
                values.add(v);
                for (double x : v) {
                    lo = Math.min(lo, x);
                    hi = Math.max(hi, x);
                }
                final EventIO.Incoming in = incoming.get(e);
                final double q2 = in.scale() * in.scale();
                if (!from.inRange(in.x1(), q2) || !from.inRange(in.x2(), q2)) outside++;
                final int id1 = LhapdfCommands.pdg(in.id1());
                final int id2 = LhapdfCommands.pdg(in.id2());
                final double old = from.xfxQ2(id1, in.x1(), q2) * from.xfxQ2(id2, in.x2(), q2);
                final double w = weights.get(e);
                final double[] mw = new double[nm];
                for (int m = 0; m < nm; m++) {
                    final RootPdfGrid g = to.member(m);
                    mw[m] = old == 0 ? 0 : w * g.xfxQ2(id1, in.x1(), q2) * g.xfxQ2(id2, in.x2(), q2) / old;
                }
                memberWeight[k] = mw;
            }
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        if (!(lo <= hi)) {
            AppLogger.warn("No jet passed the selection.");
            return;
        }
        lo = a.num("lo", lo);
        hi = a.num("hi", hi == lo ? lo + 1 : hi);
        final double[] edges = new double[bins + 1];
        for (int b = 0; b <= bins; b++) edges[b] = lo + (hi - lo) * b / bins;

        final double[][] sums = new double[bins][nm];
        final double[] nominal = new double[bins];
        for (int k = 0; k < values.size(); k++) {
            final double w = weights.get(usable.get(k));
            for (double x : values.get(k)) {
                int b = (int) Math.floor((x - lo) / (hi - lo) * bins);
                if (x == hi) b = bins - 1;
                if (b < 0 || b >= bins) continue;
                nominal[b] += w;
                for (int m = 0; m < nm; m++) sums[b][m] += memberWeight[k][m];
            }
        }
        final double cl = a.num("cl", to.confidenceLevel());
        final double[] central = new double[bins];
        final double[] up = new double[bins];
        final double[] down = new double[bins];
        final double[] members = new double[bins * nm];
        int widest = 0;
        for (int b = 0; b < bins; b++) {
            final PdfUncertainty.Result u = PdfUncertainty.uncertainty(sums[b], to.errorType(), to.confidenceLevel(),
                cl, false);
            central[b] = u.central();
            up[b] = u.errplus();
            down[b] = u.errminus();
            System.arraycopy(sums[b], 0, members, b * nm, nm);
            if (rel(up[b] + down[b], central[b]) > rel(up[widest] + down[widest], central[widest])) widest = b;
        }

        AppLogger.result(f("%s of the %d leading jets above %g GeV, %d LHE events reweighted from %s to %s (%d members, "
            + "%s, %.4g%% CL):", what, njets, ptmin, usable.size(), a.word(1), to.name(), nm, to.errorType(), cl));
        AppLogger.raw(f("  %12s %12s %14s %14s %12s %12s", "from", "to", "nominal", "central", "+err", "-err"));
        for (int b = 0; b < bins; b++) {
            AppLogger.raw(f("  %12.5g %12.5g %14.6e %14.6e %11.2f%% %11.2f%%", edges[b], edges[b + 1], nominal[b],
                central[b], 100 * rel(up[b], central[b]), 100 * rel(down[b], central[b])));
        }
        AppLogger.raw(f("  The PDF uncertainty is widest in [%g, %g]: +%.2f%% -%.2f%%.", edges[widest], edges[widest + 1],
            100 * rel(up[widest], central[widest]), 100 * rel(down[widest], central[widest])));
        if (outside > 0) AppLogger.warn(outside + " events have an x or Q outside the source grid; their weights are extrapolated.");

        try {
            final Map<String, double[]> extra = new LinkedHashMap<>();
            extra.put("nominal", nominal);
            extra.put("members", members);
            final String title = "pdfband_" + what;
            final Path out = SpxExport.histogram(title + " (" + to.name() + ")", what, edges, central, up, down, extra,
                Bridge.folder().resolve(SpxExport.safeName(title) + ".spx"));
            try (Spx.Reader r = new Spx.Reader(out)) {
                BridgeOutbox.draw(r, out);
            }
            AppLogger.raw("  Exported to " + out + ": ':bridge julia " + out.getFileName() + "' or ':bridge root "
                + out.getFileName() + "' takes it further.");
        } catch (IOException | RuntimeException e) {
            AppLogger.error("The band could not be exported: " + e.getMessage());
        }
    }

    private static double rel(double err, double central) {
        return central == 0 ? 0 : err / Math.abs(central);
    }
}
