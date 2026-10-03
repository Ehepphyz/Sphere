package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.BridgeOutbox;
import com.sphere.core.bridge.Spx;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.io.EventIO;
import com.sphere.core.fastjet.plugins.JetSpecs;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.ContribCatalog;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ContribSpecs;
import com.sphere.core.fjcontrib.constituentsubtractor.ConstituentSubtractor;
import com.sphere.core.fjcontrib.constituentsubtractor.IterativeConstituentSubtractor;
import com.sphere.core.fjcontrib.dynamicr.DynamicR;
import com.sphere.core.fjcontrib.genericsubtractor.ExampleShapes;
import com.sphere.core.fjcontrib.genericsubtractor.GenericSubtractor;
import com.sphere.core.fjcontrib.genericsubtractor.GenericSubtractorInfo;
import com.sphere.core.fjcontrib.ifnplugin.FlavHistory;
import com.sphere.core.fjcontrib.ifnplugin.FlavInfo;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets;
import com.sphere.core.fjcontrib.lab.BoostedToys;
import com.sphere.core.fjcontrib.lab.Discrimination;
import com.sphere.core.fjcontrib.lab.FlavourLab;
import com.sphere.core.fjcontrib.lab.LundMap;
import com.sphere.core.fjcontrib.lab.Measurement;
import com.sphere.core.fjcontrib.lab.Observables;
import com.sphere.core.fjcontrib.lab.PileupArena;
import com.sphere.core.fjcontrib.lundplane.LundDeclustering;
import com.sphere.core.fjcontrib.lundplane.LundGenerator;
import com.sphere.core.fjcontrib.lundplane.LundJSON;
import com.sphere.core.fjcontrib.lundplane.LundWithSecondary;
import com.sphere.core.fjcontrib.lundplane.SecondaryLund;
import com.sphere.core.fjcontrib.qcdaware.QCDAwarePlugin;
import com.sphere.core.fjcontrib.recursivetools.BottomUpSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.IteratedSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.ModifiedMassDropTagger;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;
import com.sphere.core.fjcontrib.signalfree.SignalFreeBackgroundEstimator;
import com.sphere.core.fjcontrib.softkiller.SoftKiller;
import com.sphere.core.fjcontrib.validation.Example;
import com.sphere.core.fjcontrib.validation.Examples;
import com.sphere.core.fjcontrib.validation.Validator;
import com.sphere.core.fjcontrib.variabler.VariableRPlugin;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The FastJet contribs, in Java: the {@code :fjco} commands.
 *
 * <p>They work on what {@code :fjet} holds: the events it read or made, the
 * current event, the active jet definition. The contrib jet algorithms are
 * themselves {@code :fjet def} specs, so every {@code :fjet} command runs
 * with them; the rest (grooming, substructure, pileup, flavour) acts on the
 * jets of the active definition. Everything computed can go to the Plots
 * tab, to a file, or to every other engine through the bridge.
 *
 * <p>Beyond the translation: every contrib is checked against the C++
 * reference outputs from the console ({@code :fjco validate}); flavour
 * definitions are compared and put through infrared and collinear safety
 * tests on the user's own events; pileup methods are scored against truth;
 * observables are ranked for discriminating power between two samples; and
 * events are measured on all cores when that changes no bit of the answer.
 */
public final class FjContribCommands {

    /** A sample kept by name, to compare against another. */
    private record Sample(List<List<PseudoJet>> events, List<Double> weights, List<EventIO.Incoming> incoming,
                          String source) {
    }

    private static final Map<String, Sample> SAMPLES = new LinkedHashMap<>();

    private FjContribCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                        */
    /* ------------------------------------------------------------------ */

    static void register() {
        ContribSpecs.register();
        CommandDefinitions.register(":fjco help", "What the fjcontrib commands do, in the order an analysis uses them", FjContribCommands::help);
        CommandDefinitions.register(":fjco version", "The fjcontrib release translated, and how it is checked; references under Citations in the console menu", FjContribCommands::version);
        CommandDefinitions.register(":fjco list", "The 26 contribs, by what they are for. Usage: :fjco list [clustering|flavour|grooming|substructure|pileup|shapes]", FjContribCommands::list);
        CommandDefinitions.register(":fjco info", "What a contrib does, how to reach it, what to cite. Usage: :fjco info <contrib>", FjContribCommands::info);
        CommandDefinitions.register(":fjco validate", "Run the contribs' own example programs and compare with the C++ outputs, to the character. Usage: :fjco validate [contrib] [--dir fjcontrib-folder]", FjContribCommands::validate);
        CommandDefinitions.register(":fjco bib", "Write the BibTeX of the contribs used (or all). Usage: :fjco bib <file.bib> [--all]", FjContribCommands::bib);
        CommandDefinitions.register(":fjco algorithms", "The contrib jet algorithms, as ':fjet def' specs", FjContribCommands::algorithms);
        CommandDefinitions.register(":fjco cluster", "The current event with a contrib algorithm, matched to the active :fjet jets. Usage: :fjco cluster <spec> [ptmin]", FjContribCommands::cluster);
        CommandDefinitions.register(":fjco observables", "Every jet and event observable, with its parameters", FjContribCommands::observables);
        CommandDefinitions.register(":fjco obs", "Observables of the leading jets, of the current event or averaged over all. Usage: :fjco obs [obs[:k=v,...] ...] [--njets n] [--ptmin GeV] [--all]", FjContribCommands::obs);
        CommandDefinitions.register(":fjco groom", "Groomers on the leading jets, with how close each decision came to the cut. Usage: :fjco groom <sd beta zcut|mmdt zcut|rsd beta zcut n|busd beta zcut|isd beta zcut theta> [--njets n]", FjContribCommands::groom);
        CommandDefinitions.register(":fjco lund", "The Lund declusterings of a jet, primary or secondary. Usage: :fjco lund [jet] [--secondary] [--json file]", FjContribCommands::lund);
        CommandDefinitions.register(":fjco lundmap", "The average primary Lund plane of all events, as an image and a table. Usage: :fjco lundmap [--njets n] [--ptmin GeV] [--bins n] [--png file]", FjContribCommands::lundmap);
        CommandDefinitions.register(":fjco shapes", "Jet-like event shapes without jets, beside their jet counterparts. Usage: :fjco shapes [--rjet R] [--ptcut GeV]", FjContribCommands::shapes);
        CommandDefinitions.register(":fjco rho", "Pileup density of the current event by every estimator. Usage: :fjco rho [--rapmax y]", FjContribCommands::rho);
        CommandDefinitions.register(":fjco subtract", "Subtract pileup from the current event and compare the jets. Usage: :fjco subtract <cs|ics|softkiller|generic> [param] [--ptmin GeV]", FjContribCommands::subtract);
        CommandDefinitions.register(":fjco arena", "Overlay pileup on the events and score every method against the truth. Usage: :fjco arena [--mu n] [--events n] [--ptmin GeV] [--charged f] [--seed s]", FjContribCommands::arena);
        CommandDefinitions.register(":fjco flavour", "Jet flavour by net anti-kt, IFN, CMP, GHS and SDF; compare, IRC-test, FlavorCone. Usage: :fjco flavour [compare|irc|cone] [--only b] [--njets n] [--ptmin GeV] [--trials n] [--eps e]", FjContribCommands::flavour);
        CommandDefinitions.register(":fjco toy", "Toy events with a boosted object, parton level with PDG codes. Usage: :fjco toy <qcd|quark|gluon|w|z|higgs|top> [events] [--pt GeV] [--soft n] [--seed s] [--recoil] [as <sample>]", FjContribCommands::toy);
        CommandDefinitions.register(":fjco sample", "Keep, reload, drop and list named event samples. Usage: :fjco sample <save|use|drop|list> [name]", FjContribCommands::sample);
        CommandDefinitions.register(":fjco rank", "Rank observables by how well they separate two samples (AUC, rejection). Usage: :fjco rank <signal> <background> [obs...] [--njets n] [--ptmin GeV]", FjContribCommands::rank);
        CommandDefinitions.register(":fjco hist", "Histogram an observable over all events in the Plots tab. Usage: :fjco hist <obs[:k=v]> [--njets n] [--ptmin GeV] [--bins b] [--root name]", FjContribCommands::hist);
        CommandDefinitions.register(":fjco export", "Every jet's observables to a CSV or SPX file. Usage: :fjco export <file.csv|file.spx> [obs...] [--njets n] [--ptmin GeV]", FjContribCommands::export);
        CommandDefinitions.register(":fjco bridge", "Hand the observable table to every engine: ROOT tree, Julia, Python, C++, Fortran. Usage: :fjco bridge [obs...] [as name] [--njets n] [--ptmin GeV]", FjContribCommands::bridge);
        CommandDefinitions.register(":fjco bench", "Time each observable per jet, and the table serial against parallel. Usage: :fjco bench [obs...]", FjContribCommands::bench);
        CommandDefinitions.register(":fjco selftest", "Physics invariants the translations must respect, checked on toy events", FjContribCommands::selftest);
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** Positional words, --options, and "as name". */
    private static final class Args {
        /** Options that take no value, so that the word after them stays a word. */
        private static final java.util.Set<String> FLAGS = java.util.Set.of("all", "secondary", "recoil");

        final List<String> plain = new ArrayList<>();
        final Map<String, String> opts = new LinkedHashMap<>();
        String as;

        Args(String input, String command) {
            final String[] w = Handlers.words(Handlers.args(input, command));
            for (int k = 0; k < w.length; k++) {
                if (w[k].startsWith("--")) {
                    final String key = w[k].substring(2).toLowerCase(Locale.ROOT);
                    final boolean valued = !FLAGS.contains(key) && k + 1 < w.length && !w[k + 1].startsWith("--")
                        && !w[k + 1].equalsIgnoreCase("as");
                    if (valued) opts.put(key, w[++k]);
                    else opts.put(key, "true");
                } else if (w[k].equalsIgnoreCase("as") && k + 1 < w.length) {
                    as = w[++k];
                } else {
                    plain.add(w[k]);
                }
            }
        }

        String word(int k, String fallback) {
            return k < plain.size() ? plain.get(k) : fallback;
        }

        double num(int k, double fallback) {
            try {
                return k < plain.size() ? Double.parseDouble(plain.get(k)) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        double opt(String key, double fallback) {
            try {
                return opts.containsKey(key) ? Double.parseDouble(opts.get(key)) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        String sopt(String key, String fallback) {
            return opts.getOrDefault(key, fallback);
        }

        boolean has(String key) {
            return opts.containsKey(key);
        }

        List<String> from(int k) {
            return k < plain.size() ? plain.subList(k, plain.size()) : List.of();
        }
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static List<List<PseudoJet>> events() {
        return FastJetCommands.loadedEvents();
    }

    private static boolean haveEvents() {
        if (events().isEmpty()) {
            AppLogger.error("No events: ':fjet read <file>', ':fjet toy' or ':fjco toy top' makes some.");
            return false;
        }
        return true;
    }

    private static JetDefinition activeDef() {
        return FastJetCommands.activeDefinition();
    }

    private static int threads(Args a) {
        return Math.max(1, (int) a.opt("threads", Runtime.getRuntime().availableProcessors()));
    }

    /** The n hardest jets above ptmin of the current event, with the active definition. */
    private static List<PseudoJet> leadingJets(int n, double ptmin) {
        final ClusterSequence cs = FastJetCommands.currentClustering();
        if (cs == null) return null;
        return FastJetCommands.hardest(cs, n, ptmin);
    }

    private static List<PseudoJet> currentEvent() {
        return events().get(FastJetCommands.currentIndex());
    }

    private static String label(FlavInfo f) {
        return f == null ? "-" : f.label();
    }

    /** A column name every engine accepts: letters, digits and underscores, at most 24 characters. */
    private static String columnName(String label) {
        String s = label.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+$", "");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) s = "o_" + s;
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    /** Observables from words, with the error said in plain words rather than thrown. */
    private static List<Observables.Observable> observablesOf(List<String> words) {
        try {
            return Observables.parseAll(words);
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
            return null;
        }
    }

    private static Measurement.Table measureAll(List<Observables.Observable> obs, Args a) {
        final Measurement.Table t = Measurement.measure(events(), FastJetCommands.loadedWeights(), activeDef(), obs,
            (int) a.opt("njets", 2), a.opt("ptmin", 20), threads(a));
        if (t.failures() > 0) {
            AppLogger.warn(t.failures() + " value(s) could not be computed (left out); the first: " + t.firstFailure());
        }
        return t;
    }

    /* ------------------------------------------------------------------ */
    /* Overview                                                            */
    /* ------------------------------------------------------------------ */

    public static void help(String i, CommandExecutionContext c) {
        AppLogger.result("The FastJet contribs in Java (fjcontrib " + ContribCitations.RELEASE + ", " + ContribCatalog.size()
            + " contribs). They act on the :fjet events and the active :fjet definition.");
        AppLogger.raw("  discover     :fjco list [category] | info <contrib> | algorithms | observables");
        AppLogger.raw("  events       :fjet read <file> | :fjco toy <qcd|w|top|...> [n] as <name> | :fjco sample save|use <name>");
        AppLogger.raw("  algorithms   :fjet def vr variabler:rho=600   dynamicr:0.5   ifn:0.4   cmp:0.4   xcone:0.4,n=3   qcdaware:0.4");
        AppLogger.raw("               :fjco cluster <spec> compares one with the active jets");
        AppLogger.raw("  substructure :fjco obs tau21 d2 nsd ... | groom sd 0 0.1 | lund [jet] | lundmap");
        AppLogger.raw("  pileup       :fjco rho | subtract cs|ics|softkiller|generic | arena --mu 60");
        AppLogger.raw("  flavour      :fjco flavour | flavour compare | flavour irc | flavour cone");
        AppLogger.raw("  decide       :fjco rank <signal> <background> [obs...]   AUC and rejection of each observable");
        AppLogger.raw("  engines      :fjco hist <obs> [--root name] | export <f.csv|f.spx> | bridge [obs...] as <name>");
        AppLogger.raw("  trust        :fjco validate [--dir <fjcontrib>] | selftest | bench");
        AppLogger.raw("  ':help fjco' lists every command with its usage; references: Citations > Citation fjcontrib.");
    }

    public static void version(String i, CommandExecutionContext c) {
        final List<Example> ex = Examples.all();
        AppLogger.raw("fjcontrib " + ContribCitations.RELEASE + " (Java, Sphere): " + ContribCatalog.size() + " contribs, "
            + ex.size() + " example programs of the release reproduced to the character (':fjco validate').");
    }

    public static void list(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco list");
        final String want = a.word(0, "").toLowerCase(Locale.ROOT);
        final Map<String, Integer> checked = new LinkedHashMap<>();
        for (Example e : Examples.all()) checked.merge(e.contrib().toLowerCase(Locale.ROOT), 1, Integer::sum);
        for (ContribCatalog.Category cat : ContribCatalog.Category.values()) {
            if (!want.isEmpty() && !cat.name().toLowerCase(Locale.ROOT).startsWith(want)
                    && !cat.label().startsWith(want)) continue;
            AppLogger.result(cat.label() + ":");
            for (ContribCatalog.Contrib k : ContribCatalog.of(cat)) {
                final int n = checked.getOrDefault(k.name().toLowerCase(Locale.ROOT), 0);
                AppLogger.raw(f("  %-30s %-7s %s", k.name(), k.version(), k.summary()));
                AppLogger.raw(f("  %-30s %-7s %s%s", "", "", k.usage().get(0),
                    n > 0 ? f("   [%d example%s checked]", n, n > 1 ? "s" : "") : ""));
            }
        }
        AppLogger.raw("  ':fjco info <contrib>' for the details and the references.");
    }

    public static void info(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco info");
        final ContribCatalog.Contrib k = ContribCatalog.find(a.word(0, null));
        if (k == null) {
            Handlers.usage(":fjco info <contrib>   (':fjco list' names them; softdrop, xcone, ifn... are understood)");
            return;
        }
        AppLogger.result(k.name() + " " + k.version() + " (" + k.category().label() + "): " + k.summary());
        AppLogger.raw("  " + k.details());
        AppLogger.raw("  In Sphere:");
        for (String u : k.usage()) AppLogger.raw("    " + u);
        if (!k.specs().isEmpty()) AppLogger.raw("  As a jet definition: " + String.join(", ", k.specs()) + " (':fjco algorithms')");
        final List<String> checks = new ArrayList<>();
        for (Example e : Examples.all()) if (e.contrib().equalsIgnoreCase(k.name())) checks.add(e.name());
        if (!checks.isEmpty()) AppLogger.raw("  Checked against the C++: " + String.join(", ", checks) + " (':fjco validate " + k.name() + "')");
        AppLogger.raw("  To cite:");
        for (var r : ContribCitations.referencesOf(k.name())) AppLogger.raw("    " + r.text());
    }

    /* ------------------------------------------------------------------ */
    /* Trust                                                               */
    /* ------------------------------------------------------------------ */

    /** Where the release's reference outputs and data are, if anywhere. */
    private static Path fjcontribFolder(Args a) {
        if (a.has("dir")) {
            final Path d = Handlers.resolve(a.opts.get("dir")).toPath();
            if (Files.isDirectory(d.resolve("data"))) {
                System.setProperty("fjcontrib.dir", d.toString());
                return d;
            }
            AppLogger.error(d + " is not an fjcontrib folder (no data/ inside).");
            return null;
        }
        final String set = System.getProperty("fjcontrib.dir");
        if (set != null && Files.isDirectory(Path.of(set, "data"))) return Path.of(set);
        final String env = System.getenv("FJCONTRIB_DIR");
        if (env != null && Files.isDirectory(Path.of(env, "data"))) {
            System.setProperty("fjcontrib.dir", env);
            return Path.of(env);
        }
        return null;
    }

    public static void validate(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco validate");
        final Path dir = fjcontribFolder(a);
        final List<Example> todo = Examples.matching(a.word(0, ""));
        if (todo.isEmpty()) {
            AppLogger.error("No example matches '" + a.word(0, "") + "'.");
            return;
        }
        if (dir == null && !Validator.haveReference(todo.get(0))) {
            AppLogger.error("The C++ reference outputs are not in the jar: point to an unpacked fjcontrib release, "
                + "':fjco validate --dir <folder>' (or set FJCONTRIB_DIR); the folder is remembered for the session.");
            return;
        }
        AppLogger.info(f("Running %d example program%s against the C++ outputs of fjcontrib %s%s:", todo.size(),
            todo.size() > 1 ? "s" : "", ContribCitations.RELEASE, dir == null ? "" : " in " + dir));
        LimitedWarning.setSink(message -> { });
        int identical = 0;
        int rounding = 0;
        final long t0 = System.nanoTime();
        try {
            for (Example e : todo) {
                final Validator.Outcome r = Validator.run(e);
                final String status = r.error() != null ? "ERROR    " : r.identical() ? "IDENTICAL"
                    : r.agrees(1e-5) ? "ROUNDING " : "DIFFERS  ";
                if (r.error() == null && r.identical()) identical++;
                else if (r.error() == null && r.agrees(1e-5)) rounding++;
                AppLogger.raw(f("  %s %-52s %5d lines %6d numbers %8.0f ms", status, e.id(), r.lines(), r.numbers(), r.millis()));
                if (r.error() != null) AppLogger.raw("            " + r.error());
                else if (!r.identical()) AppLogger.raw("            first difference: " + r.firstDifference().replace("\n", " | "));
            }
        } finally {
            LimitedWarning.setSink(AppLogger::warn);
        }
        final String summary = f("%d of %d identical to the character%s, in %.1f s.", identical, todo.size(),
            rounding > 0 ? f(", %d within rounding", rounding) : "", (System.nanoTime() - t0) / 1e9);
        if (identical == todo.size()) AppLogger.success(summary);
        else AppLogger.warn(summary);
    }

    public static void bib(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco bib");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjco bib <file.bib> [--all]");
            return;
        }
        final boolean all = a.has("all") || ContribCitations.used().isEmpty();
        try {
            final Path out = Handlers.resolve(a.plain.get(0)).toPath();
            Files.writeString(out, ContribCitations.bibtex(all), StandardCharsets.UTF_8);
            AppLogger.result(f("%d references written to %s (%s).", ContribCitations.bibliography(all).size(), out,
                all ? "every contrib" : "the contribs used this session"));
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Algorithms                                                          */
    /* ------------------------------------------------------------------ */

    public static void algorithms(String i, CommandExecutionContext c) {
        AppLogger.result("fjcontrib jet algorithms, usable wherever :fjet takes a definition (':fjet def <name> <spec>'):");
        for (Map.Entry<String, String> e : JetSpecs.extensions().entrySet()) {
            AppLogger.raw("  " + e.getValue().replace("[fjcontrib] ", ""));
        }
        AppLogger.raw("  ifn and cmp read each particle's flavour from its PDG code (LHE, HepMC, ':fjco toy');");
        AppLogger.raw("  qcdaware reads it too, and labels each jet with the flavour of its history.");
    }

    public static void cluster(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco cluster");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjco cluster <spec> [ptmin]   e.g. dynamicr:0.5   variabler:rho=300   ifn:0.4   qcdaware:0.4,dm=kt");
            return;
        }
        final JetDefinition def;
        try {
            def = JetSpecs.parse(a.plain.get(0));
        } catch (RuntimeException e) {
            AppLogger.error("Cannot read the spec '" + a.plain.get(0) + "': " + e.getMessage());
            return;
        }
        final double ptmin = a.num(1, 5.0);
        final List<PseudoJet> event = currentEvent();
        final long t0 = System.nanoTime();
        final List<PseudoJet> jets;
        try {
            jets = PseudoJet.sortedByPt(new ClusterSequence(event, def).inclusiveJets(ptmin));
        } catch (RuntimeException e) {
            AppLogger.error("The clustering failed: " + e.getMessage());
            return;
        }
        final double ms = (System.nanoTime() - t0) / 1e6;
        final List<PseudoJet> ref = PseudoJet.sortedByPt(FastJetCommands.currentClustering().inclusiveJets(ptmin));
        final String extra = extraColumn(def);
        AppLogger.result(f("%s: %d jets above %g GeV in %.2f ms (event %d); matched to %s (%s):", def.description(),
            jets.size(), ptmin, ms, FastJetCommands.currentIndex(), FastJetCommands.activeName(), activeDef().description()));
        AppLogger.raw(f("%5s %11s %10s %10s %10s %6s %10s %12s", "jet", "pt", "rap", "phi", "mass", "n", extra, "vs active"));
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            PseudoJet match = null;
            double best = 0.25 * Math.max(def.R(), 0.4) * Math.max(def.R(), 0.4);
            for (PseudoJet r : ref) {
                final double d = r.squaredDistance(j);
                if (d < best) {
                    best = d;
                    match = r;
                }
            }
            AppLogger.raw(f("%5d %11.4f %10.5f %10.5f %10.4f %6d %10s %12s", k, j.pt(), j.rap(), j.phi(), j.m(),
                j.constituents().size(), extraValue(def, j),
                match == null ? "unmatched" : f("%+.2f%% pt", 100 * (j.pt() - match.pt()) / match.pt())));
        }
    }

    /** What an algorithm tells about each jet beyond its momentum, as a column title. */
    private static String extraColumn(JetDefinition def) {
        final Object p = def.plugin();
        if (p instanceof DynamicR) return "R_d";
        if (p instanceof VariableRPlugin) return "R_eff";
        if (p instanceof QCDAwarePlugin) return "label";
        if (p instanceof com.sphere.core.fjcontrib.ifnplugin.IFNPlugin
            || p instanceof com.sphere.core.fjcontrib.cmpplugin.CMPPlugin) return "flavour";
        return "";
    }

    private static String extraValue(JetDefinition def, PseudoJet j) {
        final Object p = def.plugin();
        if (p instanceof DynamicR dr) return f("%.4f", dr.dynamicRadius(j));
        if (p instanceof VariableRPlugin vr) return f("%.4f", vr.effectiveRadius(j.pt()));
        if (p instanceof QCDAwarePlugin) return pdgName(QCDAwarePlugin.pid(j));
        if (j.hasUserInfo(FlavHistory.class) || j.hasUserInfo(FlavInfo.class)) return FlavHistory.currentFlavourOf(j).label();
        return "";
    }

    private static String pdgName(int pdg) {
        final String[] q = {"", "d", "u", "s", "c", "b", "t"};
        final int a = Math.abs(pdg);
        if (a >= 1 && a <= 6) return q[a] + (pdg < 0 ? "bar" : "");
        return switch (a) {
            case 21 -> "g";
            case 22 -> "gamma";
            case 11 -> pdg > 0 ? "e-" : "e+";
            case 13 -> pdg > 0 ? "mu-" : "mu+";
            case 15 -> pdg > 0 ? "tau-" : "tau+";
            case 999 -> "forbidden";
            default -> String.valueOf(pdg);
        };
    }

    /* ------------------------------------------------------------------ */
    /* Observables                                                         */
    /* ------------------------------------------------------------------ */

    public static void observables(String i, CommandExecutionContext c) {
        AppLogger.result("Observables, 'name' or 'name:key=value,...' (R is the radius of the active definition):");
        String contrib = "";
        for (Observables.Definition d : Observables.all()) {
            if (!d.contrib().equals(contrib)) {
                contrib = d.contrib();
                AppLogger.raw("  " + contrib);
            }
            AppLogger.raw(f("    %-10s %-5s %-44s %s", d.name(), d.scope() == Observables.Scope.EVENT ? "event" : "jet",
                d.params().isEmpty() ? "" : d.params(), d.summary()));
        }
        AppLogger.raw("  Default set of ':fjco obs': " + String.join(" ", Observables.defaultSet()));
    }

    public static void obs(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco obs");
        final List<Observables.Observable> obs = observablesOf(a.plain);
        if (obs == null) return;
        if (a.has("all")) {
            final Measurement.Table t = measureAll(obs, a);
            AppLogger.result(f("%d events, the %d leading jets above %g GeV of %s (%d rows, %.0f ms on %d thread%s):",
                t.events(), t.njets(), t.ptmin(), FastJetCommands.activeName(), t.rows().size(), t.millis(), t.threads(),
                t.threads() > 1 ? "s" : ""));
            AppLogger.raw(f("  %-24s %12s %12s %12s %12s %8s", "observable", "mean", "rms", "min", "max", "entries"));
            for (int k = 0; k < obs.size(); k++) {
                final double[] v = t.column(k);
                final double[] w = t.weights(k);
                AppLogger.raw(f("  %-24s %12.5g %12.5g %12.5g %12.5g %8d", obs.get(k).label(), mean(v, w), rms(v, w),
                    v.length == 0 ? Double.NaN : java.util.Arrays.stream(v).min().getAsDouble(),
                    v.length == 0 ? Double.NaN : java.util.Arrays.stream(v).max().getAsDouble(), v.length));
            }
            return;
        }
        final List<PseudoJet> jets = leadingJets((int) a.opt("njets", 2), a.opt("ptmin", 0));
        if (jets == null) return;
        final Observables.Context ctx = new Observables.Context(currentEvent(), activeDef().R());
        final StringBuilder head = new StringBuilder(f("%5s %10s", "jet", "pt"));
        for (Observables.Observable o : obs) head.append(f(" %12s", shortLabel(o.label())));
        AppLogger.result(f("Event %d, %s:", FastJetCommands.currentIndex(), activeDef().description()));
        AppLogger.raw(head.toString());
        final List<Observables.Evaluator> ev = new ArrayList<>();
        for (Observables.Observable o : obs) ev.add(o.evaluator());
        for (int k = 0; k < jets.size(); k++) {
            final StringBuilder line = new StringBuilder(f("%5d %10.3f", k, jets.get(k).pt()));
            for (int o = 0; o < obs.size(); o++) {
                String cell;
                try {
                    final double v = obs.get(o).scope() == Observables.Scope.EVENT ? ev.get(o).event(ctx) : ev.get(o).jet(jets.get(k), ctx);
                    cell = f("%12.5g", v);
                } catch (RuntimeException e) {
                    cell = f("%12s", "n/a");
                }
                line.append(' ').append(cell);
            }
            AppLogger.raw(line.toString());
        }
    }

    private static String shortLabel(String s) {
        return s.length() <= 12 ? s : s.substring(0, 11) + "~";
    }

    private static double mean(double[] v, double[] w) {
        double s = 0;
        double sw = 0;
        for (int k = 0; k < v.length; k++) {
            s += w[k] * v[k];
            sw += w[k];
        }
        return sw == 0 ? Double.NaN : s / sw;
    }

    private static double rms(double[] v, double[] w) {
        final double m = mean(v, w);
        double s = 0;
        double sw = 0;
        for (int k = 0; k < v.length; k++) {
            s += w[k] * (v[k] - m) * (v[k] - m);
            sw += w[k];
        }
        return sw == 0 ? Double.NaN : Math.sqrt(s / sw);
    }

    /* ------------------------------------------------------------------ */
    /* Grooming and the Lund plane                                         */
    /* ------------------------------------------------------------------ */

    public static void groom(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco groom");
        final String kind = a.word(0, "sd").toLowerCase(Locale.ROOT);
        final List<PseudoJet> jets = leadingJets((int) a.opt("njets", 2), 0);
        if (jets == null) return;
        final double r = activeDef().R();
        try {
            switch (kind) {
                case "sd", "softdrop", "mmdt" -> {
                    final RecursiveSymmetryCutBase t;
                    if (kind.equals("mmdt")) {
                        t = new ModifiedMassDropTagger(a.num(1, 0.1));
                        t.setGroomingMode(true);
                    } else {
                        t = new SoftDrop(a.num(1, 0.0), a.num(2, 0.1), a.num(3, r));
                    }
                    t.setVerboseStructure(true);
                    AppLogger.result(t.description());
                    AppLogger.raw(f("%5s %10s %10s %10s %10s %8s %8s %9s %9s %8s", "jet", "pt", "mass", "m groomed",
                        "pt groomed", "z_g", "R_g", "margin", "closest", "dropped"));
                    for (int k = 0; k < jets.size(); k++) {
                        final PseudoJet j = jets.get(k);
                        final PseudoJet g = t.result(j);
                        final RecursiveSymmetryCutBase.StructureType st =
                            g.structure() instanceof RecursiveSymmetryCutBase.StructureType s ? s : null;
                        final boolean fragile = st != null && st.isFragile(0.05);
                        AppLogger.raw(f("%5d %10.3f %10.3f %10.3f %10.3f %8.4f %8.4f %9s %9s %8d%s", k, j.pt(), j.m(),
                            g.m(), g.pt(), st == null ? -1 : st.symmetry(), st == null ? -1 : st.deltaR(),
                            st == null || Double.isNaN(st.decisionMargin()) ? "-" : f("%+.3f", st.decisionMargin()),
                            st == null || Double.isInfinite(st.closestDroppedMargin()) ? "-" : f("%+.3f", st.closestDroppedMargin()),
                            st == null ? 0 : st.droppedCount(), fragile ? "   fragile: within 5% of the cut" : ""));
                    }
                    AppLogger.raw("  margin: (z - cut)/cut of the splitting kept; closest: the dropped branch nearest to passing.");
                }
                case "rsd" -> {
                    final RecursiveSoftDrop t = new RecursiveSoftDrop(a.num(1, 1.0), a.num(2, 0.1), (int) a.num(3, -1), r);
                    AppLogger.result(t.description());
                    AppLogger.raw(f("%5s %10s %10s %10s %8s %8s", "jet", "pt", "mass", "m groomed", "prongs", "n const"));
                    for (int k = 0; k < jets.size(); k++) {
                        final PseudoJet j = jets.get(k);
                        final PseudoJet g = t.result(j);
                        AppLogger.raw(f("%5d %10.3f %10.3f %10.3f %8d %8d", k, j.pt(), j.m(), g.m(),
                            RecursiveSoftDrop.recursiveSoftDropProngs(g).size(), g.constituents().size()));
                    }
                }
                case "busd", "bottomup" -> {
                    final BottomUpSoftDrop t = new BottomUpSoftDrop(a.num(1, 1.0), a.num(2, 0.1), r);
                    AppLogger.result(t.description());
                    AppLogger.raw(f("%5s %10s %10s %10s %10s %8s", "jet", "pt", "mass", "m groomed", "pt groomed", "n const"));
                    for (int k = 0; k < jets.size(); k++) {
                        final PseudoJet j = jets.get(k);
                        final PseudoJet g = t.result(j);
                        AppLogger.raw(f("%5d %10.3f %10.3f %10.3f %10.3f %8d", k, j.pt(), j.m(), g.m(), g.pt(), g.constituents().size()));
                    }
                }
                case "isd", "iterated" -> {
                    final double beta = a.num(1, -1.0);
                    AppLogger.result(f("Iterated Soft Drop, beta = %g, theta_cut = %g%s", beta, a.num(3, 0.0),
                        a.plain.size() > 2 ? f(", zcut = %g", a.num(2, 0.005)) : ", zcut = 1 GeV/(pt R) per jet (1704.06266)"));
                    AppLogger.raw(f("%5s %10s %6s   %s", "jet", "pt", "n_SD", "(z_g, theta_g) of the emissions kept, hardest angle first"));
                    for (int k = 0; k < jets.size(); k++) {
                        final PseudoJet j = jets.get(k);
                        final double zcut = a.plain.size() > 2 ? a.num(2, 0.005) : 1.0 / (j.pt() * r);
                        final IteratedSoftDrop.Info info = new IteratedSoftDrop(beta, zcut, a.num(3, 0.0), r).result(j);
                        final StringBuilder s = new StringBuilder();
                        for (int e = 0; e < Math.min(6, info.size()); e++) {
                            s.append(f(" (%.3f, %.3f)", info.get(e)[0], info.get(e)[1]));
                        }
                        if (info.size() > 6) s.append(" ...");
                        AppLogger.raw(f("%5d %10.3f %6d  %s", k, j.pt(), info.multiplicity(), s));
                    }
                }
                default -> Handlers.usage(":fjco groom <sd beta zcut [R0]|mmdt zcut|rsd beta zcut n|busd beta zcut|isd beta [zcut] [theta]> [--njets n]");
            }
        } catch (RuntimeException e) {
            AppLogger.error("Grooming failed: " + e.getMessage());
        }
    }

    public static void lund(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco lund");
        final List<PseudoJet> jets = leadingJets(Integer.MAX_VALUE, 0);
        if (jets == null) return;
        final int k = (int) a.num(0, 0);
        if (k < 0 || k >= jets.size()) {
            AppLogger.error("There are " + jets.size() + " jets.");
            return;
        }
        final PseudoJet jet = jets.get(k);
        final List<LundDeclustering> ds;
        final String what;
        if (a.has("secondary")) {
            final LundWithSecondary lws = new LundWithSecondary(new SecondaryLund.SecondaryLund_mMDT(a.opt("zcut", 0.025)));
            final List<LundDeclustering> primary = lws.primary(jet);
            ds = lws.secondary(primary);
            final int lead = primary.isEmpty() ? -1 : lws.secondaryIndex(primary);
            what = f("secondary declusterings (from primary splitting %d, the first passing mMDT z > %g)", lead,
                a.opt("zcut", 0.025));
        } else {
            ds = new LundGenerator().result(jet);
            what = "primary declusterings (C/A)";
        }
        AppLogger.result(f("%d %s of jet %d (pt %.3f GeV):", ds.size(), what, k, jet.pt()));
        AppLogger.raw(f("%5s %11s %11s %10s %10s %11s %11s %9s %10s", "step", "ln(1/D)", "ln kt", "z", "Delta", "kt",
            "kappa", "psi", "m"));
        for (int s = 0; s < ds.size(); s++) {
            final LundDeclustering d = ds.get(s);
            AppLogger.raw(f("%5d %11.5f %11.5f %10.6f %10.6f %11.5f %11.6f %9.4f %10.4f", s, Math.log(1 / d.Delta()),
                Math.log(d.kt()), d.z(), d.Delta(), d.kt(), d.kappa(), d.psi(), d.m()));
        }
        if (a.has("json")) {
            try {
                final Path out = Handlers.resolve(a.opts.get("json")).toPath();
                Files.writeString(out, LundJSON.toJson(ds), StandardCharsets.UTF_8);
                AppLogger.result("Written in LundPlane's JSON to " + out);
            } catch (IOException e) {
                AppLogger.error(e.getMessage());
            }
        }
    }

    public static void lundmap(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco lundmap");
        final int bins = Math.max(4, (int) a.opt("bins", 24));
        final LundMap.Map2D m = LundMap.fill(events(), activeDef(), (int) a.opt("njets", 1), a.opt("ptmin", 20), bins,
            bins, a.opt("xmax", 6.0), a.opt("ymin", -3.0), a.opt("ymax", 7.0), threads(a));
        if (m.jets() == 0) {
            AppLogger.warn("No jet above " + a.opt("ptmin", 20) + " GeV.");
            return;
        }
        AppLogger.result(f("Primary Lund plane of the %d leading jet(s) above %g GeV, %d jets, %d declusterings (%s).",
            (int) a.opt("njets", 1), a.opt("ptmin", 20), m.jets(), m.declusterings(), m.definition()));
        AppLogger.raw(f("  mean density over the filled cells %.3f per unit area; at leading log 2 C_R alpha_s/pi = %.3f "
            + "(quark) and %.3f (gluon) for alpha_s = 0.12.", m.plateau(), LundMap.leadingLogLevel(4.0 / 3, 0.12),
            LundMap.leadingLogLevel(3, 0.12)));
        try {
            final File png = a.has("png") ? Handlers.resolve(a.opts.get("png"))
                : RootPlotsPanel.instance().outputFolder().resolve("fjco-lundmap-" + FastJetCommands.activeName() + ".png").toFile();
            javax.imageio.ImageIO.write(LundMap.render(m, "Primary Lund plane, " + FastJetCommands.activeName()), "png", png);
            RootPlotsPanel.instance().showImage(png);
            final Map<String, String> meta = new LinkedHashMap<>();
            meta.put("Title", "lundmap");
            meta.put("Kind", "map2d");
            meta.put("Engine", "Sphere");
            meta.put("JetDefinition", m.definition());
            meta.put("Jets", Integer.toString(m.jets()));
            final Path spx = new Spx.Writer(Spx.Kind.TABLE, "lundmap").meta(meta).f64("x_edges", m.xEdges())
                .f64("y_edges", m.yEdges()).f64("density", m.flat()).write(Bridge.folder().resolve("lundmap.spx"));
            AppLogger.raw("  Image " + png + "; the table in " + spx + " (density row by row in x) for any engine.");
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Cannot draw the plane: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Event shapes and pileup                                             */
    /* ------------------------------------------------------------------ */

    public static void shapes(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco shapes");
        final double rjet = a.opt("rjet", 0.4);
        final double ptcut = a.opt("ptcut", 25);
        final List<PseudoJet> ev = currentEvent();
        final List<PseudoJet> jets = Selector.ptMin(ptcut).apply(new JetDefinition(JetAlgorithm.ANTIKT, rjet).cluster(ev));
        double ht = 0;
        double msum = 0;
        PseudoJet mht = new PseudoJet(0, 0, 0, 0);
        for (PseudoJet j : jets) {
            ht += j.pt();
            msum += j.m();
            mht = mht.plus(j);
        }
        AppLogger.result(f("Event %d: jet-like event shapes without jets (Rjet = %g, pT cut = %g GeV) against anti-kt jets:",
            FastJetCommands.currentIndex(), rjet, ptcut));
        AppLogger.raw(f("  %-22s %14s %14s", "", "without jets", "anti-kt jets"));
        AppLogger.raw(f("  %-22s %14.4f %14d", "multiplicity", new JetsWithoutJets.ShapeJetMultiplicity(rjet, ptcut).result(ev), jets.size()));
        AppLogger.raw(f("  %-22s %14.4f %14.4f", "HT (GeV)", new JetsWithoutJets.ShapeScalarPt(rjet, ptcut).result(ev), ht));
        AppLogger.raw(f("  %-22s %14.4f %14.4f", "missing HT (GeV)", new JetsWithoutJets.ShapeMissingPt(rjet, ptcut).result(ev), mht.pt()));
        AppLogger.raw(f("  %-22s %14.4f %14.4f", "summed mass (GeV)", new JetsWithoutJets.ShapeSummedMass(rjet, ptcut).result(ev), msum));
        AppLogger.raw("  The shapes vary smoothly with R and the cut, where jet counts jump (arXiv:1310.7584).");
    }

    public static void rho(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco rho");
        final double rapmax = a.opt("rapmax", 4.0);
        final List<PseudoJet> ev = Selector.absRapMax(rapmax).apply(currentEvent());
        AppLogger.result(f("Event %d, |y| < %g: pileup density by each estimator (GeV per unit area):",
            FastJetCommands.currentIndex(), rapmax));
        try {
            long t0 = System.nanoTime();
            final GridMedianBackgroundEstimator grid = new GridMedianBackgroundEstimator(rapmax, 0.55);
            grid.setParticles(ev);
            AppLogger.raw(f("  %-40s rho = %9.4f  sigma = %8.4f  (%.1f ms)", "grid median, 0.55 cells", grid.rho(),
                grid.sigma(), (System.nanoTime() - t0) / 1e6));
            t0 = System.nanoTime();
            final JetMedianBackgroundEstimator jm = new JetMedianBackgroundEstimator(Selector.absRapMax(rapmax - 0.4),
                new JetDefinition(JetAlgorithm.KT, 0.4),
                new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(rapmax, 1, 0.01)));
            jm.setParticles(ev);
            AppLogger.raw(f("  %-40s rho = %9.4f  sigma = %8.4f  (%.1f ms)", "kt 0.4 jet median, with areas", jm.rho(),
                jm.sigma(), (System.nanoTime() - t0) / 1e6));
            t0 = System.nanoTime();
            final SignalFreeBackgroundEstimator sf = new SignalFreeBackgroundEstimator(rapmax, 0.55);
            final List<PseudoJet> seeds = Selector.ptMin(a.opt("seedpt", 20)).apply(new JetDefinition(JetAlgorithm.ANTIKT, 0.4).cluster(ev));
            sf.addSeedsFromUser(seeds);
            sf.setParticles(ev);
            AppLogger.raw(f("  %-40s rho = %9.4f  %.0f%% of the area cut out around %d seed(s)  (%.1f ms)",
                "signal-free (seeds: anti-kt 0.4 jets)", sf.rho(), 100 * sf.lastExcludedFraction(), seeds.size(),
                (System.nanoTime() - t0) / 1e6));
            final SoftKiller.Result sk = new SoftKiller(rapmax, 0.4).apply(ev);
            AppLogger.raw(f("  %-40s pt cut = %.4f GeV, keeps %d of %d particles", "SoftKiller, 0.4 grid", sk.ptThreshold(),
                sk.reducedEvent().size(), ev.size()));
            AppLogger.raw("  Signal-free is the one that does not grow with the number of hard jets in the event.");
        } catch (RuntimeException e) {
            AppLogger.error("Estimation failed: " + e.getMessage());
        }
    }

    public static void subtract(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco subtract");
        final String method = a.word(0, "");
        final double ptmin = a.opt("ptmin", 20);
        final double rapmax = a.opt("rapmax", 4.0);
        final List<PseudoJet> ev = Selector.absRapMax(rapmax).apply(currentEvent());
        final JetDefinition def = activeDef();
        try {
            final GridMedianBackgroundEstimator grid = new GridMedianBackgroundEstimator(rapmax, 0.55);
            grid.setParticles(ev);
            if (method.equalsIgnoreCase("generic")) {
                genericSubtraction(ev, def, grid, a.word(1, "mass"), ptmin);
                return;
            }
            final List<PseudoJet> corrected;
            final String how;
            switch (method.toLowerCase(Locale.ROOT)) {
                case "cs" -> {
                    final ConstituentSubtractor cs = new ConstituentSubtractor();
                    cs.setBackgroundEstimator(grid);
                    cs.setMaxEta(rapmax);
                    cs.setMaxDistance(a.num(1, 0.3));
                    cs.setAlpha(a.num(2, 1.0));
                    cs.initialize();
                    corrected = cs.subtractEvent(ev);
                    how = f("ConstituentSubtractor, event-wide, max distance %g, alpha %g", a.num(1, 0.3), a.num(2, 1.0));
                }
                case "ics" -> {
                    final IterativeConstituentSubtractor ics = new IterativeConstituentSubtractor();
                    ics.setMaxEta(rapmax);
                    ics.setParameters(List.of(0.2, 0.1), List.of(1.0, 1.0));
                    ics.setGhostRemoval(true);
                    ics.setBackgroundEstimator(grid);
                    ics.initialize();
                    corrected = ics.subtractEvent(ev);
                    how = "IterativeConstituentSubtractor, max distances 0.2 then 0.1";
                }
                case "softkiller", "sk" -> {
                    final SoftKiller.Result r = new SoftKiller(rapmax, a.num(1, 0.4)).apply(ev);
                    corrected = r.reducedEvent();
                    how = f("SoftKiller, %.2f grid: particles below %.4f GeV removed", a.num(1, 0.4), r.ptThreshold());
                }
                default -> {
                    Handlers.usage(":fjco subtract <cs [maxdist] [alpha]|ics|softkiller [grid]|generic [mass|pt|tau21]> [--ptmin GeV]");
                    return;
                }
            }
            final List<PseudoJet> before = PseudoJet.sortedByPt(new ClusterSequence(ev, def).inclusiveJets(ptmin));
            final List<PseudoJet> after = PseudoJet.sortedByPt(new ClusterSequence(corrected, def).inclusiveJets(ptmin * 0.5));
            AppLogger.result(f("%s; rho = %.4g GeV per unit area; %d particles -> %d:", how, grid.rho(), ev.size(), corrected.size()));
            AppLogger.raw(f("%5s %10s %10s %10s %12s %12s", "jet", "rap", "phi", "pt", "pt after", "m / after"));
            for (int k = 0; k < before.size(); k++) {
                final PseudoJet j = before.get(k);
                PseudoJet m = null;
                double best = 0.25 * def.R() * def.R();
                for (PseudoJet x : after) {
                    final double d = x.squaredDistance(j);
                    if (d < best) {
                        best = d;
                        m = x;
                    }
                }
                AppLogger.raw(f("%5d %10.4f %10.4f %10.3f %12s %12s", k, j.rap(), j.phi(), j.pt(),
                    m == null ? "lost" : f("%.3f", m.pt()), m == null ? "" : f("%.2f/%.2f", j.m(), m.m())));
            }
        } catch (RuntimeException e) {
            AppLogger.error("Subtraction failed: " + e.getMessage());
        }
    }

    private static void genericSubtraction(List<PseudoJet> ev, JetDefinition active, GridMedianBackgroundEstimator grid,
                                           String shapeName, double ptmin) {
        // areas need thousands of ghosts clustered with the jets: a plugin (CMP is cubic in the number of
        // particles, like its C++) would take hours, so anti-kt of the same radius finds the jets instead
        JetDefinition def = active;
        if (active.plugin() != null) {
            final double r = active.R() > 0 && active.R() < 2 ? active.R() : 0.4;
            def = new JetDefinition(JetAlgorithm.ANTIKT, r);
            AppLogger.warn(f("Jet areas need ghosts, which %s is not made for: the jets are anti-kt R = %g here.",
                active.plugin().getClass().getSimpleName(), r));
        }
        final com.sphere.core.fastjet.FunctionOfPseudoJet<Double> shape = switch (shapeName.toLowerCase(Locale.ROOT)) {
            case "pt" -> new ExampleShapes.Pt();
            case "tau21" -> {
                final Observables.Evaluator e = Observables.parse("tau21").evaluator();
                final Observables.Context ctx = new Observables.Context(ev, def.R());
                yield com.sphere.core.fastjet.FunctionOfPseudoJet.of("tau21", j -> e.jet(j, ctx));
            }
            default -> new ExampleShapes.Mass();
        };
        final ClusterSequenceArea csa = new ClusterSequenceArea(ev, def,
            new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(4.5, 1, 0.01)));
        final GenericSubtractor gs = new GenericSubtractor(grid);
        AppLogger.result(f("GenericSubtractor on %s (%s), rho = %.4g:", shapeName, gs.description(), grid.rho()));
        AppLogger.raw(f("%5s %10s %12s %12s %12s %12s %12s", "jet", "pt", "unsubtr.", "1st order", "2nd order", "3rd order",
            "trunc. err"));
        int k = 0;
        for (PseudoJet j : PseudoJet.sortedByPt(csa.inclusiveJets(ptmin))) {
            final GenericSubtractorInfo info = new GenericSubtractorInfo();
            gs.apply(shape, j, info);
            AppLogger.raw(f("%5d %10.3f %12.5g %12.5g %12.5g %12.5g %12.3g", k++, j.pt(), info.unsubtracted(),
                info.firstOrderSubtracted(), info.secondOrderSubtracted(), info.thirdOrderSubtracted(), info.truncationError()));
        }
    }

    public static void arena(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco arena");
        final PileupArena.Options d = PileupArena.Options.defaults();
        final PileupArena.Options o = new PileupArena.Options((int) a.opt("mu", d.mu()), a.opt("pervertex", d.perVertex()),
            a.opt("meanpt", d.meanPt()), a.opt("charged", d.charged()), a.opt("r", activeDef().R() > 0 ? activeDef().R() : 0.4),
            a.opt("ptmin", d.ptmin()), (int) a.opt("njets", d.njets()), (long) a.opt("seed", d.seed()));
        final int n = Math.min(events().size(), (int) a.opt("events", 20));
        AppLogger.info(f("Pileup arena: %d events of %s, mu = %d (%.0f particles per vertex, %.0f%% charged), anti-kt R = %g, "
            + "the %d leading true jets above %g GeV...", n, FastJetCommands.loadedSource(), o.mu(), o.perVertex(),
            100 * o.charged(), o.r(), o.njets(), o.ptmin()));
        final long t0 = System.nanoTime();
        final PileupArena.Result r;
        try {
            r = PileupArena.run(events().subList(0, n), o);
        } catch (RuntimeException e) {
            AppLogger.error("The arena failed: " + e.getMessage());
            return;
        }
        final List<PileupArena.Score> sorted = new ArrayList<>(r.scores());
        sorted.sort(Comparator.comparingDouble(s -> Double.isNaN(s.ptSpread()) ? Double.MAX_VALUE : s.ptSpread()));
        AppLogger.raw(f("  %-52s %8s %9s %9s %10s %10s %9s", "method (best pt resolution first)", "found", "pt bias",
            "pt rms", "m bias", "m rms", "ms/event"));
        for (PileupArena.Score s : sorted) {
            AppLogger.raw(f("  %-52s %7.0f%% %+8.2f%% %8.2f%% %+9.2f %10.2f %9.1f", s.method().label(), 100 * s.efficiency(),
                100 * s.ptBias(), 100 * s.ptSpread(), s.massBias(), s.massSpread(), s.millisPerEvent()));
        }
        AppLogger.raw(f("  true pileup density %.3f GeV per unit area; estimators, as a fraction of it:", r.trueRho()));
        for (PileupArena.RhoScore s : r.rho()) {
            AppLogger.raw(f("    %-14s %.3f +- %.3f", s.estimator(), s.meanRatio(), s.spreadRatio()));
        }
        final PileupArena.Score bestMass = r.scores().stream().filter(s -> s.method() != PileupArena.Method.NONE)
            .min(Comparator.comparingDouble(s -> Math.abs(s.massBias()) + s.massSpread())).orElse(null);
        // jets of a handful of particles (parton level) often carry no track at all, which the track-based methods need
        double perJet = 0;
        int jets = 0;
        for (List<PseudoJet> ev : events().subList(0, n)) {
            for (PseudoJet j : FastJetCommands.hardest(new ClusterSequence(ev, new JetDefinition(JetAlgorithm.ANTIKT, o.r())),
                    o.njets(), o.ptmin())) {
                perJet += j.constituents().size();
                jets++;
            }
        }
        if (jets > 0 && perJet / jets < 10) {
            AppLogger.warn(f("The true jets have %.1f particles each (parton level?): a jet of a few partons may carry no "
                + "charged track, which JetCleanser and charged-hadron subtraction rely on. Hadron-level events judge them fairly.",
                perJet / jets));
        }
        AppLogger.result(f("Best pt resolution: %s; best mass: %s (%.1f s).", sorted.get(0).method().label(),
            bestMass == null ? "-" : bestMass.method().label(), (System.nanoTime() - t0) / 1e9));
    }

    /* ------------------------------------------------------------------ */
    /* Flavour                                                             */
    /* ------------------------------------------------------------------ */

    private static int flavourCode(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "d" -> 1;
            case "u" -> 2;
            case "s" -> 3;
            case "c" -> 4;
            case "b" -> 5;
            case "t" -> 6;
            case "all", "0" -> 0;
            default -> Integer.parseInt(s);
        };
    }

    public static void flavour(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco flavour");
        final String sub = a.word(0, "").toLowerCase(Locale.ROOT);
        final FlavourLab.Options o = FlavourLab.Options.defaults()
            .withOnly(flavourCode(a.sopt("only", "0")))
            .withJets((int) a.opt("njets", 2), a.opt("ptmin", 20));
        if (!FlavourLab.hasPdgCodes(currentEvent())) {
            AppLogger.warn("The events carry no PDG codes, so every particle is flavourless: read an LHE or HepMC file, "
                + "or make events with ':fjco toy'.");
        }
        try {
            switch (sub) {
                case "" -> {
                    final List<FlavourLab.Labelled> ls = FlavourLab.label(currentEvent(), o);
                    AppLogger.result(f("Event %d, the %d leading anti-kt R = %g jets above %g GeV%s:", FastJetCommands.currentIndex(),
                        o.njets(), o.r(), o.ptmin(), o.only() > 0 ? ", flavour " + o.only() + " only" : ""));
                    final StringBuilder head = new StringBuilder(f("%5s %10s %8s", "jet", "pt", "rap"));
                    for (FlavourLab.Algo al : FlavourLab.Algo.values()) head.append(f(" %14s", al.label()));
                    AppLogger.raw(head.toString());
                    for (int k = 0; k < ls.size(); k++) {
                        final StringBuilder line = new StringBuilder(f("%5d %10.3f %8.4f", k, ls.get(k).jet().pt(), ls.get(k).jet().rap()));
                        for (FlavourLab.Algo al : FlavourLab.Algo.values()) line.append(f(" %14s", label(ls.get(k).label(al))));
                        AppLogger.raw(line.toString());
                    }
                }
                case "compare" -> {
                    final FlavourLab.Comparison cmp = FlavourLab.compare(events(), o, threads(a));
                    AppLogger.result(f("%d leading jets of %d events: how often each definition flavours a jet, and agrees with each other one (%.0f ms):",
                        cmp.jets(), cmp.events(), cmp.millis()));
                    final StringBuilder head = new StringBuilder(f("  %-12s %10s", "", "flavoured"));
                    for (FlavourLab.Algo b : FlavourLab.Algo.values()) head.append(f(" %11s", b.label()));
                    AppLogger.raw(head.toString());
                    for (FlavourLab.Algo x : FlavourLab.Algo.values()) {
                        final StringBuilder line = new StringBuilder(f("  %-12s %9.1f%%", x.label(),
                            cmp.jets() == 0 ? 0 : 100.0 * cmp.flavoured()[x.ordinal()] / cmp.jets()));
                        for (FlavourLab.Algo y : FlavourLab.Algo.values()) line.append(f(" %10.1f%%", 100 * cmp.agreement(x, y)));
                        AppLogger.raw(line.toString());
                    }
                }
                case "irc" -> {
                    final int trials = (int) a.opt("trials", 3);
                    final double eps = a.opt("eps", 1e-5);
                    final double[] scales = {eps, eps * 1e-4};
                    final int n = Math.min(events().size(), (int) a.opt("events", 50));
                    AppLogger.result(f("IRC test on %d events, %d trial(s) each: a soft quark and antiquark of epsilon x the "
                        + "leading jet's pt at random places, and the hardest gluon split into a quark pair epsilon apart; "
                        + "each trial at epsilon = %g, then %g at the same places.", n, trials, scales[0], scales[1]));
                    final FlavourLab.IrcResult r = FlavourLab.ircTest(events().subList(0, n), o, trials, scales,
                        (long) a.opt("seed", 1), threads(a));
                    AppLogger.raw(f("  %-12s %11s %11s %11s %11s   %s", "", "soft", "soft", "collinear", "collinear", ""));
                    AppLogger.raw(f("  %-12s %11s %11s %11s %11s   %s", "definition", f("%.0e", scales[0]), f("%.0e", scales[1]),
                        f("%.0e", scales[0]), f("%.0e", scales[1]), "verdict (labels changed, % of trials)"));
                    for (FlavourLab.Algo al : FlavourLab.Algo.values()) {
                        final String verdict = r.safe(al)
                            ? (r.vanishing(al) ? "safe: the changes vanish with epsilon" : "safe")
                            : al == FlavourLab.Algo.SDF ? "changes persist as epsilon -> 0 (proven safe through NNLO only)"
                            : "NOT safe: changes persist as epsilon -> 0";
                        AppLogger.raw(f("  %-12s %10.1f%% %10.1f%% %10.1f%% %10.1f%%   %s", al.label(), 100 * r.softRate(0, al),
                            100 * r.softRate(1, al), 100 * r.collinearRate(0, al), 100 * r.collinearRate(1, al), verdict));
                    }
                    AppLogger.raw(f("  (%d trials, %.1f s) Net flavour is expected to fail. SDF is proven safe through NNLO "
                        + "(arXiv:2205.01109); a soft pair placed at random, each quark on its own, goes beyond that.",
                        r.trials(), r.millis() / 1000));
                    AppLogger.raw("  IFN, CMP (with its default correction) and GHS v2 are built safe at all orders (arXiv:2306.07314).");
                }
                case "cone" -> {
                    final int flav = flavourCode(a.sopt("flav", "5"));
                    final List<PseudoJet> jets = FlavourLab.flavorCone(currentEvent(), flav, a.opt("seedpt", 5), a.opt("rcut", 0.5));
                    AppLogger.result(f("FlavorCone jets around flavour-%d seeds above %g GeV, rcut = %g: %d", flav,
                        a.opt("seedpt", 5), a.opt("rcut", 0.5), jets.size()));
                    for (int k = 0; k < jets.size(); k++) {
                        final PseudoJet j = jets.get(k);
                        AppLogger.raw(f("  %3d pt %10.3f rap %8.4f phi %8.4f m %9.3f n %4d", k, j.pt(), j.rap(), j.phi(), j.m(),
                            j.constituents().size()));
                    }
                }
                default -> Handlers.usage(":fjco flavour [compare|irc|cone] [--only b] [--njets n] [--ptmin GeV] [--trials n] [--eps e]");
            }
        } catch (RuntimeException e) {
            AppLogger.error("Flavour analysis failed: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Samples and discrimination                                          */
    /* ------------------------------------------------------------------ */

    public static void toy(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco toy");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjco toy <qcd|quark|gluon|w|z|higgs|top> [events] [--pt GeV] [--soft n] [--seed s] [--alphas a] [as <sample>]");
            return;
        }
        final BoostedToys.Kind kind;
        try {
            kind = BoostedToys.Kind.parse(a.plain.get(0));
        } catch (IllegalArgumentException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        final BoostedToys.Options d = BoostedToys.Options.defaults((int) a.num(1, 200));
        final BoostedToys.Options o = new BoostedToys.Options(d.events(), a.opt("pt", d.pt()), (int) a.opt("soft", d.soft()),
            (long) a.opt("seed", d.seed()), a.opt("alphas", d.alphaS()), a.opt("ktmin", d.ktMin()), a.opt("gqq", d.gToQQ()),
            a.has("recoil"));
        final List<List<PseudoJet>> evs = BoostedToys.generate(kind, o);
        final String name = a.as != null ? a.as : kind.name().toLowerCase(Locale.ROOT);
        FastJetCommands.adoptEvents(evs, null, null, "fjco toy " + name);
        SAMPLES.put(name, new Sample(new ArrayList<>(evs), null, null, "fjco toy " + name));
        long n = 0;
        for (List<PseudoJet> e : evs) n += e.size();
        AppLogger.result(f("%d toy events with a boosted %s of about %g GeV, %.1f partons per event, loaded into :fjet and kept "
            + "as sample '%s'.", evs.size(), kind.name().toLowerCase(Locale.ROOT), o.pt(), (double) n / evs.size(), name));
        if (kind == BoostedToys.Kind.TOP || kind == BoostedToys.Kind.W || kind == BoostedToys.Kind.HIGGS
                || kind == BoostedToys.Kind.Z) {
            AppLogger.raw("  A radius of 0.8 to 1.0 catches the whole decay: ':fjet def fat antikt:1.0'.");
        }
    }

    public static void sample(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco sample");
        final String what = a.word(0, "list").toLowerCase(Locale.ROOT);
        final String name = a.word(1, a.as);
        switch (what) {
            case "save" -> {
                if (!haveEvents() || name == null) {
                    if (name == null) Handlers.usage(":fjco sample save <name>");
                    return;
                }
                SAMPLES.put(name, new Sample(new ArrayList<>(events()), new ArrayList<>(FastJetCommands.loadedWeights()),
                    new ArrayList<>(FastJetCommands.loadedIncoming()), FastJetCommands.loadedSource()));
                AppLogger.result(f("%d events of %s kept as '%s'.", events().size(), FastJetCommands.loadedSource(), name));
            }
            case "use" -> {
                final Sample s = name == null ? null : SAMPLES.get(name);
                if (s == null) {
                    AppLogger.error("No sample '" + name + "' (':fjco sample list').");
                    return;
                }
                FastJetCommands.adoptEvents(s.events(), s.weights(), s.incoming(), s.source());
                AppLogger.result(f("'%s' loaded into :fjet: %d events of %s.", name, s.events().size(), s.source()));
            }
            case "drop" -> AppLogger.result(SAMPLES.remove(name) != null ? "'" + name + "' dropped." : "No sample '" + name + "'.");
            default -> {
                if (SAMPLES.isEmpty()) {
                    AppLogger.result("No sample kept: ':fjco sample save <name>' keeps the :fjet events, ':fjco toy ... as <name>' makes one.");
                    return;
                }
                AppLogger.result("Samples:");
                for (Map.Entry<String, Sample> e : SAMPLES.entrySet()) {
                    AppLogger.raw(f("  %-16s %6d events   %s", e.getKey(), e.getValue().events().size(), e.getValue().source()));
                }
            }
        }
    }

    public static void rank(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjco rank");
        if (a.plain.size() < 2) {
            Handlers.usage(":fjco rank <signal> <background> [obs...] [--njets n] [--ptmin GeV]   (samples from ':fjco toy ... as <name>' or ':fjco sample save')");
            return;
        }
        final Sample sig = SAMPLES.get(a.plain.get(0));
        final Sample bkg = SAMPLES.get(a.plain.get(1));
        if (sig == null || bkg == null) {
            AppLogger.error("Unknown sample " + (sig == null ? a.plain.get(0) : a.plain.get(1)) + " (':fjco sample list').");
            return;
        }
        final List<String> names = a.from(2).isEmpty()
            ? List.of("m", "tau21", "tau32", "c2", "d2", "n2", "m2", "sdmass", "zg", "nsd", "nlund", "u1", "nkt", "ffm")
            : a.from(2);
        final List<Observables.Observable> obs = observablesOf(names);
        if (obs == null) return;
        final int njets = (int) a.opt("njets", 1);
        final double ptmin = a.opt("ptmin", 100);
        final JetDefinition def = activeDef();
        final long t0 = System.nanoTime();
        final Measurement.Table ts = Measurement.measure(sig.events(), sig.weights(), def, obs, njets, ptmin, threads(a));
        final Measurement.Table tb = Measurement.measure(bkg.events(), bkg.weights(), def, obs, njets, ptmin, threads(a));
        final List<Discrimination.Result> results = new ArrayList<>();
        for (int k = 0; k < obs.size(); k++) {
            results.add(Discrimination.evaluate(obs.get(k).label(), ts.column(k), ts.weights(k), tb.column(k), tb.weights(k)));
        }
        results.sort(Comparator.comparingDouble((Discrimination.Result r) -> Double.isNaN(r.auc()) ? -1 : r.auc()).reversed());
        AppLogger.result(f("'%s' against '%s', the %d leading jet(s) above %g GeV of %s (%.1f s):", a.plain.get(0),
            a.plain.get(1), njets, ptmin, def.description(), (System.nanoTime() - t0) / 1e9));
        AppLogger.raw(f("  %-22s %7s %-7s %14s %14s %16s", "observable", "AUC", "signal", "1/eff_B @ 50%", "best cut",
            "eff_S / eff_B"));
        for (Discrimination.Result r : results) {
            AppLogger.raw(f("  %-22s %7.4f %-7s %14.2f %14.5g %16s", r.observable(), r.auc(), r.signalBelow() ? "below" : "above",
                r.rejection50(), r.bestCut(), f("%.2f / %.2f", r.bestSignalEff(), r.bestBackgroundEff())));
        }
        if (!results.isEmpty() && results.get(0).effS().length > 1) {
            final Discrimination.Result best = results.get(0);
            RootPlotsPanel.instance().showCurve("signal efficiency (" + best.observable() + ")", "background efficiency",
                best.effS(), best.effB());
            AppLogger.result(best.observable() + " separates best; its ROC curve is in the Plots tab.");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Output and the other engines                                        */
    /* ------------------------------------------------------------------ */

    public static void hist(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco hist");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjco hist <obs[:k=v,...]> [--njets n] [--ptmin GeV] [--bins b] [--root name]   (':fjco observables')");
            return;
        }
        final List<Observables.Observable> obs = observablesOf(List.of(a.plain.get(0)));
        if (obs == null) return;
        final Measurement.Table t = measureAll(obs, a);
        final double[] v = t.column(0);
        final double[] w = t.weights(0);
        if (v.length == 0) {
            AppLogger.warn("No value: no jet passed the selection.");
            return;
        }
        double lo = a.opt("lo", java.util.Arrays.stream(v).min().getAsDouble());
        double hi = a.opt("hi", java.util.Arrays.stream(v).max().getAsDouble());
        if (hi <= lo) hi = lo + 1;
        final int bins = Math.max(1, (int) a.opt("bins", 40));
        final double[] edges = new double[bins + 1];
        for (int b = 0; b <= bins; b++) edges[b] = lo + (hi - lo) * b / bins;
        final double[] counts = new double[bins];
        for (int k = 0; k < v.length; k++) {
            int b = (int) Math.floor((v[k] - lo) / (hi - lo) * bins);
            if (v[k] == hi) b = bins - 1;
            if (b >= 0 && b < bins) counts[b] += w[k];
        }
        final String label = obs.get(0).label();
        RootPlotsPanel.instance().showHistogram(BridgeOutbox.histogram(label + " (" + FastJetCommands.activeName() + ")",
            label, edges, counts), label);
        AppLogger.result(f("%s: %d entries, mean %.6g, rms %.6g (%s, %.0f ms on %d thread%s).", label, v.length, mean(v, w),
            rms(v, w), obs.get(0).contrib(), t.millis(), t.threads(), t.threads() > 1 ? "s" : ""));
        if (a.has("root")) {
            final String name = a.opts.get("root");
            final StringBuilder list = new StringBuilder();
            final StringBuilder weights = new StringBuilder();
            for (int k = 0; k < v.length; k++) {
                list.append(k == 0 ? "" : ", ").append(f("%.17g", v[k]));
                weights.append(k == 0 ? "" : ", ").append(f("%.17g", w[k]));
            }
            Handlers.cling(c, "[]{ const double v[] = {" + list + "}; const double w[] = {" + weights + "}; TH1D *h = new TH1D(\""
                + name + "\", \"" + label + ";" + label + ";entries\", " + bins + ", " + f("%.17g", lo) + ", "
                + f("%.17g", hi + 1e-9 * (hi - lo)) + "); for (size_t k = 0; k < sizeof(v)/sizeof(v[0]); ++k) h->Fill(v[k], w[k]); return "
                + Handlers.keep(name, "TH1D", "h") + "; }()");
        }
    }

    /** The table of every jet, as SPX sections: what export and bridge write. */
    private static Path writeSpx(Measurement.Table t, Path file, String title) throws IOException {
        final int n = t.rows().size();
        final long[] event = new long[n];
        final long[] jet = new long[n];
        final double[] weight = new double[n];
        final double[] pt = new double[n];
        final double[] rap = new double[n];
        final double[] phi = new double[n];
        for (int k = 0; k < n; k++) {
            final Measurement.Row r = t.rows().get(k);
            event[k] = r.event();
            jet[k] = r.jet();
            weight[k] = r.weight();
            pt[k] = r.pt();
            rap[k] = r.rap();
            phi[k] = r.phi();
        }
        final Map<String, String> meta = new LinkedHashMap<>();
        meta.put("Title", title);
        meta.put("Kind", "table");
        meta.put("Engine", "Sphere fjcontrib " + ContribCitations.RELEASE);
        meta.put("Source", FastJetCommands.loadedSource());
        meta.put("JetDefinition", t.definition().replace('\n', ' '));
        meta.put("Jets", "the " + t.njets() + " leading above " + t.ptmin() + " GeV; jet = -1 for an event without jets");
        final StringBuilder columns = new StringBuilder();
        final Spx.Writer w = new Spx.Writer(Spx.Kind.TABLE, title);
        w.i64("event", event).i64("jet", jet).f64("weight", weight).f64("pt", pt).f64("rap", rap).f64("phi", phi);
        final java.util.Set<String> used = new java.util.HashSet<>(List.of("event", "jet", "weight", "pt", "rap", "phi", "meta"));
        for (int k = 0; k < t.observables().size(); k++) {
            String col = columnName(t.observables().get(k).label());
            while (!used.add(col)) col = columnName(col + "_" + k);
            final double[] v = new double[n];
            for (int r = 0; r < n; r++) v[r] = t.rows().get(r).values()[k];
            w.f64(col, v);
            columns.append(col).append(" = ").append(t.observables().get(k).label()).append(" (")
                .append(t.observables().get(k).contrib()).append("); ");
        }
        meta.put("Columns", columns.toString());
        w.meta(meta);
        return w.write(file);
    }

    public static void export(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco export");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjco export <file.csv|file.spx> [obs...] [--njets n] [--ptmin GeV]");
            return;
        }
        final List<Observables.Observable> obs = observablesOf(a.from(1));
        if (obs == null) return;
        final Measurement.Table t = measureAll(obs, a);
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        try {
            if (out.toString().toLowerCase(Locale.ROOT).endsWith(".spx")) {
                final Path written = writeSpx(t, out, SpxExport.safeName(out.getFileName().toString().replace(".spx", "")));
                AppLogger.result(f("%d rows x %d observables written to %s (SPX: every engine reads it; ':bridge root %s').",
                    t.rows().size(), obs.size(), written, written.getFileName()));
                return;
            }
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                final StringBuilder head = new StringBuilder("event,jet,weight,pt,rapidity,phi");
                for (Observables.Observable o : obs) head.append(',').append(columnName(o.label()));
                w.println("# fjcontrib " + ContribCitations.RELEASE + " (Sphere); " + t.definition().replace('\n', ' '));
                w.println(head);
                for (Measurement.Row r : t.rows()) {
                    final StringBuilder line = new StringBuilder(f("%d,%d,%.10g,%.12g,%.12g,%.12g", r.event(), r.jet(),
                        r.weight(), r.pt(), r.rap(), r.phi()));
                    for (double v : r.values()) line.append(',').append(Double.isNaN(v) ? "" : f("%.12g", v));
                    w.println(line);
                }
            }
            AppLogger.result(f("%d rows x %d observables of %d events written to %s.", t.rows().size(), obs.size(), t.events(), out));
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void bridge(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco bridge");
        final List<Observables.Observable> obs = observablesOf(a.plain);
        if (obs == null) return;
        final String name = SpxExport.safeName(a.as != null ? a.as : "fjco");
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            AppLogger.error(name + " is not a name every engine accepts (letters, digits, underscores).");
            return;
        }
        final Measurement.Table t = measureAll(obs, a);
        final Path file;
        try {
            file = writeSpx(t, Bridge.folder().resolve(name + ".spx"), name);
        } catch (IOException e) {
            AppLogger.error("Export failed: " + e.getMessage());
            return;
        }
        final String path = file.toAbsolutePath().toString().replace('\\', '/');
        AppLogger.result(f("%d jets x %d observables -> %s", t.rows().size(), obs.size(), file));
        // ROOT, if it runs: a tree now
        final var root = com.sphere.core.rootbackend.RootBackend.getInstance();
        if (root != null && root.isAvailable()) {
            try {
                final String header = Bridge.libraries().resolve("sphere_spx.hpp").toAbsolutePath().toString().replace('\\', '/');
                Handlers.clingAnswer(c, "gInterpreter->Declare(\"#include \\\"" + header + "\\\"\")");
                Handlers.cling(c, Handlers.keep(name, "TTree", "spx::root::tableTree(\"" + path + "\", \"" + name + "\")"));
                AppLogger.raw("  ROOT      " + name + " is a TTree: :root tree draw " + name + " <column>");
            } catch (IOException | RuntimeException e) {
                AppLogger.warn("ROOT did not take it: " + e.getMessage());
            }
        } else {
            AppLogger.raw("  ROOT      spx::root::tableTree(\"" + name + "\")   (with #include \"sphere_spx.hpp\")");
        }
        // Julia, if its session is open: a Dict of columns now
        final var julia = Handlers.julia();
        if (julia.isRunning()) {
            julia.run(name + " = SphereSPX.load(raw\"" + path + "\")");
            AppLogger.raw("  Julia     " + name + " is bound: " + name + "[\"" + columnName(obs.get(0).label()) + "\"]");
        } else {
            AppLogger.raw("  Julia     " + name + " = SphereSPX.load(\"" + name + "\")");
        }
        final String first = columnName(obs.get(0).label());
        AppLogger.raw("  Python    t = sphere_spx.load(\"" + name + "\"); t[\"" + first + "\"]");
        AppLogger.raw("  C++       spx::File f(spx::resolve(\"" + name + "\")); const double* v = f.f64(\"" + first + "\");");
        AppLogger.raw("  Fortran   call spx_column('" + name + "', '" + first + "', values, ok)");
        AppLogger.raw("  Columns: event, jet, weight, pt, rap, phi, " + String.join(", ",
            obs.stream().map(o -> columnName(o.label())).toList()));
    }

    /* ------------------------------------------------------------------ */
    /* Speed and self-checks                                               */
    /* ------------------------------------------------------------------ */

    public static void bench(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjco bench");
        final List<Observables.Observable> obs = observablesOf(a.plain);
        if (obs == null) return;
        final List<PseudoJet> jets = new ArrayList<>();
        final List<List<PseudoJet>> evs = events();
        for (int e = 0; e < Math.min(evs.size(), 100); e++) {
            jets.addAll(FastJetCommands.hardest(new ClusterSequence(evs.get(e), activeDef()), 2, 20));
        }
        if (jets.isEmpty()) {
            AppLogger.warn("No jet above 20 GeV to time.");
            return;
        }
        final Observables.Context ctx = new Observables.Context(evs.get(0), activeDef().R());
        AppLogger.result(f("Time per jet over %d jets (the 2 leading above 20 GeV of up to 100 events):", jets.size()));
        for (Observables.Observable o : obs) {
            final Observables.Evaluator ev = o.evaluator();
            double best = Double.MAX_VALUE;
            for (int round = 0; round < 3; round++) {
                final long t0 = System.nanoTime();
                for (PseudoJet j : jets) {
                    try {
                        if (o.scope() == Observables.Scope.JET) ev.jet(j, ctx);
                        else ev.event(ctx);
                    } catch (RuntimeException ignored) {
                        // timed all the same
                    }
                }
                best = Math.min(best, (System.nanoTime() - t0) / 1e3 / jets.size());
            }
            AppLogger.raw(f("  %-22s %10.1f us %s", o.label(), best, o.scope() == Observables.Scope.EVENT ? "per event" : ""));
        }
        final int cores = Runtime.getRuntime().availableProcessors();
        final Measurement.Table serial = Measurement.measure(evs, FastJetCommands.loadedWeights(), activeDef(), obs, 2, 20, 1);
        final Measurement.Table parallel = Measurement.measure(evs, FastJetCommands.loadedWeights(), activeDef(), obs, 2, 20, cores);
        final boolean same = sameTable(serial, parallel);
        AppLogger.result(f("The whole table over %d events: %.0f ms on 1 thread, %.0f ms on %d (x%.1f), results %s.",
            evs.size(), serial.millis(), parallel.millis(), parallel.threads(), serial.millis() / parallel.millis(),
            same ? "identical to the bit" : "DIFFERENT"));
    }

    private static boolean sameTable(Measurement.Table x, Measurement.Table y) {
        if (x.rows().size() != y.rows().size()) return false;
        for (int k = 0; k < x.rows().size(); k++) {
            final double[] a = x.rows().get(k).values();
            final double[] b = y.rows().get(k).values();
            for (int o = 0; o < a.length; o++) {
                if (Double.doubleToLongBits(a[o]) != Double.doubleToLongBits(b[o])) return false;
            }
        }
        return true;
    }

    public static void selftest(String i, CommandExecutionContext c) {
        AppLogger.result("fjcontrib self test, on toy events with flavour (the :fjet events are not touched):");
        int failures = 0;
        final List<List<PseudoJet>> tops = BoostedToys.generate(BoostedToys.Kind.TOP, new BoostedToys.Options(8, 500, 20, 7, 0.12, 1.0, 0.1, true));
        final List<List<PseudoJet>> qcd = BoostedToys.generate(BoostedToys.Kind.QCD, new BoostedToys.Options(8, 500, 20, 8, 0.12, 1.0, 0.1, true));
        final List<List<PseudoJet>> sample = new ArrayList<>(tops);
        sample.addAll(qcd);
        failures += check(ircOfIfnKinematics(sample), "IFN jets have exactly anti-kt's momenta, to the bit");
        failures += check(flavourConserved(sample), "IFN conserves the event's net flavour over all its jets");
        failures += check(ghsKeepsJets(sample), "GHS dresses the hard anti-kt jets without touching their momenta");
        failures += check(cmpSmallA(sample), "CMP with a -> 0 gives anti-kt's jets");
        failures += check(softDropZeroCut(sample), "Soft Drop with zcut = 0 returns the whole jet");
        failures += check(csNoRho(sample), "ConstituentSubtractor with rho = 0 leaves the event's momentum unchanged");
        failures += check(softKillerCut(sample), "SoftKiller keeps exactly the particles above its threshold");
        failures += check(qcdAwareConserves(sample), "QCD-aware jets conserve the net quark number of the partons");
        failures += check(dynamicRadius(sample), "DynamicR jets of one particle have R_d = R0, larger ones R0 + sigma");
        failures += check(parallelEqualsSerial(sample), "the observable table is the same to the bit on 1 thread and on all");
        failures += check(ircSafeFlavours(sample), "IFN, CMP and GHS keep their labels under soft quark pairs; net flavour does not");
        AppLogger.result(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
    }

    private static int check(boolean ok, String what) {
        AppLogger.raw((ok ? "  ok    " : "  FAIL  ") + what);
        return ok ? 0 : 1;
    }

    private static boolean ircOfIfnKinematics(List<List<PseudoJet>> sample) {
        final JetDefinition akt = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        final JetDefinition ifn = JetSpecs.parse("ifn:0.4");
        for (List<PseudoJet> ev : sample) {
            final List<PseudoJet> a = akt.cluster(ev);
            final List<PseudoJet> b = ifn.cluster(ev);
            if (a.size() != b.size()) return false;
            for (int k = 0; k < a.size(); k++) {
                if (a.get(k).px() != b.get(k).px() || a.get(k).E() != b.get(k).E()) return false;
            }
        }
        return true;
    }

    private static boolean flavourConserved(List<List<PseudoJet>> sample) {
        final JetDefinition ifn = JetSpecs.parse("ifn:0.4");
        for (List<PseudoJet> ev : sample) {
            FlavInfo total = new FlavInfo();
            for (PseudoJet p : ev) total = total.plus(FlavInfo.fromUserIndex(p));
            FlavInfo jets = new FlavInfo();
            for (PseudoJet j : new ClusterSequence(ev, ifn).inclusiveJets()) jets = jets.plus(FlavHistory.currentFlavourOf(j));
            for (int f = 1; f <= 6; f++) if (total.get(f) != jets.get(f)) return false;
        }
        return true;
    }

    private static boolean ghsKeepsJets(List<List<PseudoJet>> sample) {
        final com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner fr = new com.sphere.core.fjcontrib.ifnplugin.FlavRecombiner();
        final JetDefinition base = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        base.setRecombiner(fr);
        for (List<PseudoJet> ev : sample) {
            final List<PseudoJet> jets = base.cluster(FlavourLab.flavoured(ev, 0));
            final List<PseudoJet> dressed = com.sphere.core.fjcontrib.ghsalgo.GHSAlgo.runGHS(jets, 20, 1, 2, fr);
            final List<PseudoJet> hard = Selector.ptMin(20).apply(jets);
            if (dressed.size() != hard.size()) return false;
            for (int k = 0; k < hard.size(); k++) if (dressed.get(k).E() != hard.get(k).E()) return false;
        }
        return true;
    }

    private static boolean cmpSmallA(List<List<PseudoJet>> sample) {
        final JetDefinition akt = new JetDefinition(JetAlgorithm.ANTIKT, 0.4);
        final JetDefinition cmp = JetSpecs.parse("cmp:0.4,a=1e-12");
        for (List<PseudoJet> ev : sample) {
            final List<PseudoJet> a = Selector.ptMin(20).apply(akt.cluster(ev));
            final List<PseudoJet> b = Selector.ptMin(20).apply(cmp.cluster(ev));
            if (a.size() != b.size()) return false;
            for (int k = 0; k < a.size(); k++) if (Math.abs(a.get(k).E() - b.get(k).E()) > 1e-9 * a.get(k).E()) return false;
        }
        return true;
    }

    private static boolean softDropZeroCut(List<List<PseudoJet>> sample) {
        final SoftDrop sd = new SoftDrop(0.0, 0.0);
        for (List<PseudoJet> ev : sample) {
            for (PseudoJet j : Selector.ptMin(50).apply(new JetDefinition(JetAlgorithm.ANTIKT, 0.8).cluster(ev))) {
                if (Math.abs(sd.result(j).E() - j.E()) > 1e-9 * j.E()) return false;
            }
        }
        return true;
    }

    private static boolean csNoRho(List<List<PseudoJet>> sample) {
        for (List<PseudoJet> ev : sample) {
            final List<PseudoJet> in = Selector.absRapMax(4).apply(ev);
            final ConstituentSubtractor cs = new ConstituentSubtractor(0.0);
            cs.setMaxEta(4);
            cs.initialize();
            PseudoJet a = new PseudoJet(0, 0, 0, 0);
            PseudoJet b = new PseudoJet(0, 0, 0, 0);
            for (PseudoJet p : in) a = a.plus(p);
            for (PseudoJet p : cs.subtractEvent(in)) b = b.plus(p);
            if (Math.abs(a.E() - b.E()) > 1e-9 * a.E() || Math.abs(a.pt() - b.pt()) > 1e-9 * a.pt()) return false;
        }
        return true;
    }

    private static boolean softKillerCut(List<List<PseudoJet>> sample) {
        for (List<PseudoJet> ev : sample) {
            final List<PseudoJet> in = Selector.absRapMax(4).apply(ev);
            final SoftKiller.Result r = new SoftKiller(4, 0.4).apply(in);
            int above = 0;
            for (PseudoJet p : in) if (p.pt() >= r.ptThreshold()) above++;
            for (PseudoJet p : r.reducedEvent()) if (p.pt() < r.ptThreshold()) return false;
            if (above != r.reducedEvent().size()) return false;
        }
        return true;
    }

    private static boolean qcdAwareConserves(List<List<PseudoJet>> sample) {
        final JetDefinition qa = JetSpecs.parse("qcdaware:0.4,dm=kt");
        for (List<PseudoJet> ev : sample) {
            int in = 0;
            for (PseudoJet p : ev) {
                final int id = p.userIndex();
                if (Math.abs(id) >= 1 && Math.abs(id) <= 6) in += Integer.signum(id);
            }
            int out = 0;
            for (PseudoJet j : new ClusterSequence(ev, qa).inclusiveJets()) {
                final int id = QCDAwarePlugin.pid(j);
                if (Math.abs(id) >= 1 && Math.abs(id) <= 6) out += Integer.signum(id);
            }
            if (in != out) return false;
        }
        return true;
    }

    private static boolean dynamicRadius(List<List<PseudoJet>> sample) {
        final DynamicR plugin = DynamicR.drak(0.5);
        final JetDefinition def = new JetDefinition(plugin);
        for (List<PseudoJet> ev : sample) {
            for (PseudoJet j : new ClusterSequence(ev, def).inclusiveJets()) {
                final double rd = plugin.dynamicRadius(j);
                if (j.constituents().size() == 1 && rd != 0.5) return false;
                if (j.hasUserInfo(DynamicR.JetInfo.class)) {
                    final DynamicR.JetInfo info = j.userInfo(DynamicR.JetInfo.class);
                    if (Math.abs(rd - (0.5 + info.sigma())) > 1e-12) return false;
                }
            }
        }
        return true;
    }

    private static boolean parallelEqualsSerial(List<List<PseudoJet>> sample) {
        final List<Observables.Observable> obs = Observables.parseAll(List.of());
        final JetDefinition def = new JetDefinition(JetAlgorithm.ANTIKT, 0.8);
        final Measurement.Table a = Measurement.measure(sample, null, def, obs, 2, 50, 1);
        final Measurement.Table b = Measurement.measure(sample, null, def, obs, 2, 50, Runtime.getRuntime().availableProcessors());
        return sameTable(a, b);
    }

    private static boolean ircSafeFlavours(List<List<PseudoJet>> sample) {
        final FlavourLab.IrcResult r = FlavourLab.ircTest(sample, FlavourLab.Options.defaults(), 3,
            new double[]{1e-5, 1e-9}, 3, Runtime.getRuntime().availableProcessors());
        // SDF is proven safe through NNLO only; this test goes beyond, so it is not held to it
        for (FlavourLab.Algo a : new FlavourLab.Algo[]{FlavourLab.Algo.IFN, FlavourLab.Algo.CMP, FlavourLab.Algo.GHS}) {
            if (!r.safe(a)) return false;
        }
        return r.softRate(FlavourLab.Algo.NET) > 0;
    }
}
