package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.ClusterSequenceArea;
import com.sphere.core.fastjet.FastJet;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fastjet.contrib.DetectorRobustness;
import com.sphere.core.fastjet.contrib.EnergyCorrelator;
import com.sphere.core.fastjet.contrib.EventBatch;
import com.sphere.core.fastjet.contrib.IrcSafetyCheck;
import com.sphere.core.fastjet.contrib.JetShapes;
import com.sphere.core.fastjet.contrib.LundPlane;
import com.sphere.core.fastjet.contrib.Nsubjettiness;
import com.sphere.core.fastjet.contrib.PrecisionAudit;
import com.sphere.core.fastjet.contrib.SoftDrop;
import com.sphere.core.fastjet.io.EventIO;
import com.sphere.core.fastjet.io.ToyEvents;
import com.sphere.core.fastjet.plugins.JetSpecs;
import com.sphere.core.fastjet.tools.Filter;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Pruner;
import com.sphere.core.fastjet.tools.Subtractor;
import com.sphere.core.fastjet.tools.Transformer;
import com.sphere.utils.AppLogger;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jet finding, in Java, with Sphere's own port of FastJet 3.5.2: the
 * {@code :fjet} commands.
 *
 * Events are read from a file (FastJet text, LHE, HepMC, CSV) or made up
 * ({@code :fjet toy}); jet definitions are named and one of them is active;
 * the current event is clustered with it on demand and the result kept, so
 * that the commands that look at jets (jets, areas, grooming, substructure)
 * work on the same clustering. Everything runs in the JVM, at the precision
 * chosen ({@code :fjet precision}): double, bit for bit FastJet's, or
 * double-double, 106 bits.
 */
public final class FastJetCommands {

    private static final List<List<PseudoJet>> EVENTS = new ArrayList<>();
    private static final List<Double> WEIGHTS = new ArrayList<>();
    /** The incoming partons of each event, null where the file did not give them. */
    private static final List<EventIO.Incoming> INCOMING = new ArrayList<>();
    private static String source = "";
    private static int current;
    private static final Map<String, JetDefinition> DEFS = new LinkedHashMap<>();
    private static String active = "default";
    private static ClusterSequence last;
    private static String lastKey = "";

    static {
        DEFS.put("default", new JetDefinition(JetAlgorithm.ANTIKT, 0.4));
        ClusterSequence.setBannerSink(AppLogger::raw);
        LimitedWarning.setSink(AppLogger::warn);
    }

    private FastJetCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                        */
    /* ------------------------------------------------------------------ */

    static void register() {
        CommandDefinitions.register(":fjet help", "What the jet commands do, in the order an analysis uses them", FastJetCommands::help);
        CommandDefinitions.register(":fjet version", "The version (FastJet Java Sphere); references under Citations in the console menu", FastJetCommands::version);
        CommandDefinitions.register(":fjet read", "Read events. Usage: :fjet read <file> [--format fastjet|lhe|hepmc|csv] [--max N]", FastJetCommands::read);
        CommandDefinitions.register(":fjet toy", "Make toy events. Usage: :fjet toy [events] [--jets k] [--pt GeV] [--soft n] [--seed s]", FastJetCommands::toy);
        CommandDefinitions.register(":fjet event", "Show or choose the current event. Usage: :fjet event [index]", FastJetCommands::event);
        CommandDefinitions.register(":fjet write", "Write the events in FastJet's text format. Usage: :fjet write <file>", FastJetCommands::write);
        CommandDefinitions.register(":fjet def", "Name a jet definition and make it active. Usage: :fjet def <name> <spec> (e.g. antikt:0.4 or siscone:0.7,f=0.75)", FastJetCommands::def);
        CommandDefinitions.register(":fjet defs", "The jet definitions named so far", FastJetCommands::defs);
        CommandDefinitions.register(":fjet use", "Make a named jet definition active. Usage: :fjet use <name>", FastJetCommands::use);
        CommandDefinitions.register(":fjet algorithms", "The algorithms and plugins, with how to write them", FastJetCommands::algorithms);
        CommandDefinitions.register(":fjet strategies", "The clustering strategies and what they cost", FastJetCommands::strategies);
        CommandDefinitions.register(":fjet precision", "Double (FastJet's own results) or double-double (106 bits). Usage: :fjet precision [double|dd]", FastJetCommands::precision);
        CommandDefinitions.register(":fjet cluster", "Cluster the current event, or all. Usage: :fjet cluster [def] [--all] [--threads n]", FastJetCommands::cluster);
        CommandDefinitions.register(":fjet jets", "The inclusive jets. Usage: :fjet jets [ptmin] [--const]", FastJetCommands::jets);
        CommandDefinitions.register(":fjet exclusive", "Exclusive jets. Usage: :fjet exclusive <n> | d=<dcut> | y=<ycut>", FastJetCommands::exclusive);
        CommandDefinitions.register(":fjet dmerge", "The distances at which n jets become n-1. Usage: :fjet dmerge [nmax]", FastJetCommands::dmerge);
        CommandDefinitions.register(":fjet constituents", "The particles of a jet. Usage: :fjet constituents <jet index>", FastJetCommands::constituents);
        CommandDefinitions.register(":fjet history", "The clustering history, step by step. Usage: :fjet history [steps]", FastJetCommands::history);
        CommandDefinitions.register(":fjet area", "Jets with their areas. Usage: :fjet area [active|explicit|passive|voronoi] [--ghost-area a] [--maxrap y] [ptmin]", FastJetCommands::area);
        CommandDefinitions.register(":fjet rho", "The event's background density. Usage: :fjet rho [grid|jetmedian] [--rapmax y]", FastJetCommands::rho);
        CommandDefinitions.register(":fjet subtract", "Jets with the background subtracted (rho x area). Usage: :fjet subtract [ptmin]", FastJetCommands::subtract);
        CommandDefinitions.register(":fjet groom", "Groom the leading jets. Usage: :fjet groom <softdrop beta zcut|mmdt zcut|trim rtrim ptfrac|filter r n|prune zcut rcut> [--njets n]", FastJetCommands::groom);
        CommandDefinitions.register(":fjet nsub", "N-subjettiness and its ratios. Usage: :fjet nsub [N] [beta] [kt|wta_kt|ca|wta_ca|onepass_kt|onepass_wta_kt] [--njets n]", FastJetCommands::nsub);
        CommandDefinitions.register(":fjet ecf", "Energy correlators C2, D2, N2, M2. Usage: :fjet ecf [beta] [--njets n]", FastJetCommands::ecf);
        CommandDefinitions.register(":fjet lund", "The Lund-plane declusterings of a jet. Usage: :fjet lund [jet index] [--all]", FastJetCommands::lund);
        CommandDefinitions.register(":fjet shapes", "pTD, girth, angularities and pull. Usage: :fjet shapes [R] [--njets n]", FastJetCommands::shapes);
        CommandDefinitions.register(":fjet irc", "Infrared and collinear safety, on the event and on textbook configurations. Usage: :fjet irc [def] [--trials n] [--ptmin GeV]", FastJetCommands::irc);
        CommandDefinitions.register(":fjet robust", "Detector-level robustness: cells, noise, thresholds, pileup, odd inputs. Usage: :fjet robust [def] [--ptmin GeV] [--rho GeV]", FastJetCommands::robust);
        CommandDefinitions.register(":fjet audit", "What double precision changes on this event against 106 bits. Usage: :fjet audit [def] [ptmin]", FastJetCommands::audit);
        CommandDefinitions.register(":fjet bench", "Time the clustering over all events. Usage: :fjet bench [def] [--repeat n] [--threads n]", FastJetCommands::bench);
        CommandDefinitions.register(":fjet tune", "Time every strategy on the loaded events and adopt the fastest if it gives the same jets to the bit. Usage: :fjet tune [--repeat rounds]", FastJetCommands::tune);
        CommandDefinitions.register(":fjet hist", "Histogram a jet observable over all events in the Plots tab. Usage: :fjet hist <pt|m|rap|phi|nconst|njets|tau21|tau32|c2|d2|sdmass> [--njets n] [--ptmin GeV] [--bins b] [--root name]", FastJetCommands::hist);
        CommandDefinitions.register(":fjet display", "Draw the current event and its jets in (y, phi). Usage: :fjet display [--png file]", FastJetCommands::display);
        CommandDefinitions.register(":fjet export", "Write every event's jets to a CSV file. Usage: :fjet export <file.csv> [ptmin]", FastJetCommands::export);
        CommandDefinitions.register(":fjet selftest", "Check the port against itself: strategies, precisions, safety", FastJetCommands::selftest);
        CommandDefinitions.register(":fjet clear", "Forget the events and the clusterings", FastJetCommands::clear);
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** The words after the command, and the value of an option in them. */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final Map<String, String> opts = new LinkedHashMap<>();

        Args(String input, String command) {
            final String[] w = Handlers.words(Handlers.args(input, command));
            for (int k = 0; k < w.length; k++) {
                if (w[k].startsWith("--")) {
                    final String key = w[k].substring(2).toLowerCase(Locale.ROOT);
                    if (k + 1 < w.length && !w[k + 1].startsWith("--")) {
                        opts.put(key, w[++k]);
                    } else {
                        opts.put(key, "true");
                    }
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

        boolean has(String key) {
            return opts.containsKey(key);
        }
    }

    private static boolean haveEvents() {
        if (EVENTS.isEmpty()) {
            AppLogger.error("No events yet: ':fjet read <file>' or ':fjet toy' makes some.");
            return false;
        }
        return true;
    }

    /** A named definition, or a spec written in place. */
    private static JetDefinition definition(String nameOrSpec) {
        if (nameOrSpec == null) return DEFS.get(active);
        final JetDefinition named = DEFS.get(nameOrSpec);
        if (named != null) return named;
        try {
            return JetSpecs.parse(nameOrSpec);
        } catch (RuntimeException e) {
            AppLogger.error("Neither a named definition nor a spec: " + nameOrSpec + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** The clustering of the current event with the active definition, made once. */
    private static ClusterSequence clustering() {
        if (!haveEvents()) return null;
        final JetDefinition def = DEFS.get(active);
        final String key = active + "@" + current + "@" + def.description() + "@" + def.precision();
        if (last == null || !key.equals(lastKey)) {
            last = new ClusterSequence(EVENTS.get(current), def);
            lastKey = key;
        }
        return last;
    }

    private static List<PseudoJet> leading(ClusterSequence cs, int n, double ptmin) {
        final List<PseudoJet> all = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
        return all.subList(0, Math.min(n, all.size()));
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static void table(List<PseudoJet> jets, boolean withArea, boolean constituents) {
        AppLogger.raw(f("%5s %14s %14s %14s %12s %6s%s", "jet", "rapidity", "phi", "pt", "mass", "n",
            withArea ? f(" %12s", "area") : ""));
        for (int i = 0; i < jets.size(); i++) {
            final PseudoJet j = jets.get(i);
            final int n = j.hasConstituents() ? j.constituents().size() : 1;
            AppLogger.raw(f("%5d %14.8f %14.8f %14.8f %12.6f %6d%s", i, j.rap(), j.phi(), j.perp(), j.m(), n,
                withArea && j.hasArea() ? f(" %12.6f", j.area()) : ""));
            if (constituents && j.hasConstituents()) {
                for (PseudoJet c : PseudoJet.sortedByPt(j.constituents())) {
                    AppLogger.raw(f("        %14.8f %14.8f %14.8f   #%d", c.rap(), c.phi(), c.perp(), c.clusterHistIndex()));
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Commands: events and definitions                                    */
    /* ------------------------------------------------------------------ */

    public static void help(String i, CommandExecutionContext c) {
        AppLogger.result("Jet finding in Java (FastJet " + FastJet.FASTJET_VERSION + " ported, precision "
            + Precision.defaultPrecision() + "). The order of an analysis:");
        AppLogger.raw("  events       :fjet read <file> | :fjet toy [n]      then :fjet event [i]");
        AppLogger.raw("  definition   :fjet def <name> <spec>   specs: antikt:0.4  kt:0.6,scheme=pt  siscone:0.7,f=0.75 ...");
        AppLogger.raw("               :fjet algorithms lists them all, :fjet use <name> switches");
        AppLogger.raw("  jets         :fjet cluster | jets [ptmin] | exclusive <n> | dmerge | history | constituents <i>");
        AppLogger.raw("  areas, pileup :fjet area | rho | subtract");
        AppLogger.raw("  substructure :fjet groom softdrop 0 0.1 | nsub | ecf | lund | shapes");
        AppLogger.raw("  robustness   :fjet irc | robust | audit | selftest | bench | tune");
        AppLogger.raw("  output       :fjet hist <observable> | display | export <file.csv> | write <file>");
        AppLogger.raw("  ':help fjet' gives the full usage of each.");
    }

    /** The version only; the references are under Citations in the console's context menu. */
    public static void version(String i, CommandExecutionContext c) {
        AppLogger.raw(FastJet.VERSION_LINE);
    }

    public static void read(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjet read");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjet read <file> [--format fastjet|lhe|hepmc|csv] [--max N]");
            return;
        }
        final Path file = Handlers.resolve(a.plain.get(0)).toPath();
        try {
            final EventIO.Format format = a.has("format")
                ? EventIO.Format.valueOf(a.opts.get("format").toUpperCase(Locale.ROOT)) : EventIO.guess(file);
            final List<EventIO.Event> events = EventIO.read(file, format, (int) a.opt("max", Integer.MAX_VALUE));
            EVENTS.clear();
            WEIGHTS.clear();
            INCOMING.clear();
            long n = 0;
            for (EventIO.Event e : events) {
                final List<PseudoJet> ps = new ArrayList<>(e.particles().size());
                for (PseudoJet p : e.particles()) ps.add(p.copy().setPrecision(Precision.defaultPrecision()));
                EVENTS.add(ps);
                WEIGHTS.add(e.weight());
                INCOMING.add(e.incoming());
                n += ps.size();
            }
            source = file.toString();
            current = 0;
            last = null;
            AppLogger.result(f("%d events read from %s (%s), %.1f particles per event.", EVENTS.size(),
                file.getFileName(), format, EVENTS.isEmpty() ? 0.0 : (double) n / EVENTS.size()));
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Cannot read " + file + ": " + e.getMessage());
        }
    }

    public static void toy(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjet toy");
        final int n = (int) a.num(0, 10);
        final List<List<PseudoJet>> evs = ToyEvents.generate(n, (int) a.opt("jets", 2), a.opt("pt", 500),
            (int) a.opt("soft", 200), (long) a.opt("seed", 1));
        EVENTS.clear();
        WEIGHTS.clear();
        INCOMING.clear();
        for (List<PseudoJet> e : evs) {
            final List<PseudoJet> ps = new ArrayList<>(e.size());
            for (PseudoJet p : e) ps.add(p.setPrecision(Precision.defaultPrecision()));
            EVENTS.add(ps);
            WEIGHTS.add(1.0);
            INCOMING.add(null);
        }
        source = "toy";
        current = 0;
        last = null;
        AppLogger.result(n + " toy events made (" + (int) a.opt("jets", 2) + " hard partons of about "
            + a.opt("pt", 500) + " GeV each, " + (int) a.opt("soft", 200) + " soft particles).");
    }

    public static void event(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet event");
        if (!a.plain.isEmpty()) {
            final int k = (int) a.num(0, current);
            if (k < 0 || k >= EVENTS.size()) {
                AppLogger.error("There are " + EVENTS.size() + " events (0 to " + (EVENTS.size() - 1) + ").");
                return;
            }
            current = k;
        }
        final List<PseudoJet> ev = EVENTS.get(current);
        double sumPt = 0;
        double sumE = 0;
        double ymin = Double.MAX_VALUE;
        double ymax = -Double.MAX_VALUE;
        for (PseudoJet p : ev) {
            sumPt += p.pt();
            sumE += p.E();
            if (p.pt() > 0) {
                ymin = Math.min(ymin, p.rap());
                ymax = Math.max(ymax, p.rap());
            }
        }
        AppLogger.result(f("event %d of %d (%s): %d particles, sum pt %.3f GeV, sum E %.3f GeV, y in [%.3f, %.3f], weight %g",
            current, EVENTS.size(), source, ev.size(), sumPt, sumE, ymin, ymax, WEIGHTS.get(current)));
    }

    public static void write(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet write");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjet write <file>");
            return;
        }
        try {
            final Path out = Handlers.resolve(a.plain.get(0)).toPath();
            EventIO.writeFastJet(out, EVENTS);
            AppLogger.result(EVENTS.size() + " events written to " + out);
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void def(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjet def");
        if (a.plain.size() < 2) {
            Handlers.usage(":fjet def <name> <spec>    e.g. :fjet def ak4 antikt:0.4   :fjet def sis siscone:0.7,f=0.75");
            return;
        }
        final String spec = String.join(",", a.plain.subList(1, a.plain.size()));
        try {
            final JetDefinition d = JetSpecs.parse(spec);
            DEFS.put(a.plain.get(0), d);
            active = a.plain.get(0);
            AppLogger.result(active + " (active): " + d.description() + " [" + d.precision() + "]");
        } catch (RuntimeException e) {
            AppLogger.error("Cannot read the spec '" + spec + "': " + e.getMessage());
        }
    }

    public static void defs(String i, CommandExecutionContext c) {
        for (Map.Entry<String, JetDefinition> e : DEFS.entrySet()) {
            AppLogger.raw(f("%s %-12s %s [%s]", e.getKey().equals(active) ? "*" : " ", e.getKey(),
                e.getValue().description(), e.getValue().precision()));
        }
    }

    public static void use(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjet use");
        if (a.plain.isEmpty() || !DEFS.containsKey(a.plain.get(0))) {
            Handlers.usage(":fjet use <name>   (':fjet defs' lists them)");
            return;
        }
        active = a.plain.get(0);
        AppLogger.result(active + ": " + DEFS.get(active).description());
    }

    public static void algorithms(String i, CommandExecutionContext c) {
        AppLogger.result("Native algorithms (spec: name:R[,p=...][,scheme=E|pt|pt2|Et|Et2|BIpt|BIpt2|WTA_pt|WTA_modp][,strategy=...][,precision=double|dd]):");
        for (JetAlgorithm alg : JetAlgorithm.values()) {
            if (alg == JetAlgorithm.UNDEFINED || alg == JetAlgorithm.PLUGIN) continue;
            AppLogger.raw("  " + alg.name().toLowerCase(Locale.ROOT) + "  " + alg.description());
        }
        AppLogger.result("Plugins:");
        for (Map.Entry<String, String> e : JetSpecs.plugins().entrySet()) AppLogger.raw("  " + e.getValue());
    }

    public static void strategies(String i, CommandExecutionContext c) {
        AppLogger.result("Strategies (strategy=<label> in a spec; Best picks one from N and R as FastJet 3.5 does):");
        for (Strategy s : Strategy.values()) AppLogger.raw(f("  %4d  %s", s.id, s.label()));
    }

    public static void precision(String i, CommandExecutionContext c) {
        final Args a = new Args(i, ":fjet precision");
        if (a.plain.isEmpty()) {
            AppLogger.result("Precision: " + Precision.defaultPrecision()
                + ". 'double' gives FastJet's results bit for bit; 'dd' carries 106 bits (angles to 2^-106).");
            return;
        }
        final Precision p = a.plain.get(0).toLowerCase(Locale.ROOT).startsWith("d") && !a.plain.get(0).equalsIgnoreCase("dd")
            ? Precision.DOUBLE : Precision.DD;
        Precision.setDefault(p);
        for (JetDefinition d : DEFS.values()) d.setPrecision(p);
        for (List<PseudoJet> ev : EVENTS) for (PseudoJet q : ev) q.setPrecision(p);
        last = null;
        AppLogger.result("Precision set to " + p + " for the definitions and the events.");
    }

    /* ------------------------------------------------------------------ */
    /* Commands: clustering                                                */
    /* ------------------------------------------------------------------ */

    public static void cluster(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet cluster");
        if (!a.plain.isEmpty()) {
            final JetDefinition d = definition(a.plain.get(0));
            if (d == null) return;
            if (!DEFS.containsKey(a.plain.get(0))) DEFS.put("adhoc", d);
            active = DEFS.containsKey(a.plain.get(0)) ? a.plain.get(0) : "adhoc";
        }
        final JetDefinition def = DEFS.get(active);
        try {
            if (a.has("all")) {
                final int threads = (int) a.opt("threads", Runtime.getRuntime().availableProcessors());
                final long t0 = System.nanoTime();
                final List<ClusterSequence> all = EventBatch.cluster(EVENTS, def, threads);
                final double ms = (System.nanoTime() - t0) / 1e6;
                int njets = 0;
                for (ClusterSequence cs : all) njets += cs.inclusiveJets(5.0).size();
                AppLogger.result(f("%d events clustered with %s in %.1f ms (%d thread%s), %.2f jets above 5 GeV per event.",
                    all.size(), def.description(), ms, def.plugin() == null ? threads : 1,
                    def.plugin() == null && threads > 1 ? "s" : "", (double) njets / all.size()));
                return;
            }
            final long t0 = System.nanoTime();
            last = null;
            final ClusterSequence cs = clustering();
            final double ms = (System.nanoTime() - t0) / 1e6;
            AppLogger.result(f("event %d: %d particles clustered in %.2f ms, strategy %s; %d jets above 5 GeV.",
                current, cs.nParticles(), ms, cs.strategyString(), cs.inclusiveJets(5.0).size()));
            AppLogger.raw("  " + def.description());
        } catch (RuntimeException e) {
            AppLogger.error("The clustering failed: " + e.getMessage());
        }
    }

    public static void jets(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet jets");
        final double ptmin = a.num(0, 5.0);
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(ptmin));
        AppLogger.result(f("%d jets above %g GeV (event %d, %s):", jets.size(), ptmin, current, active));
        table(jets, false, a.has("const"));
    }

    public static void exclusive(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet exclusive");
        final String w = a.word(0, "2");
        try {
            final List<PseudoJet> jets;
            if (w.startsWith("d=")) jets = cs.exclusiveJets(Double.parseDouble(w.substring(2)));
            else if (w.startsWith("y=")) jets = cs.exclusiveJetsYcut(Double.parseDouble(w.substring(2)));
            else jets = cs.exclusiveJets(Integer.parseInt(w));
            AppLogger.result(jets.size() + " exclusive jets (" + w + "):");
            table(PseudoJet.sortedByPt(jets), false, false);
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void dmerge(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet dmerge");
        final int nmax = Math.min((int) a.num(0, 10), cs.nParticles());
        AppLogger.raw(f("%6s %20s %20s", "n", "d(n -> n-1)", "y(n -> n-1)"));
        for (int n = 1; n <= nmax; n++) {
            AppLogger.raw(f("%6d %20.10e %20.10e", n, cs.exclusiveDmerge(n), cs.exclusiveYmerge(n)));
        }
    }

    public static void constituents(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet constituents");
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(0));
        final int k = (int) a.num(0, 0);
        if (k < 0 || k >= jets.size()) {
            AppLogger.error("There are " + jets.size() + " jets.");
            return;
        }
        table(List.of(jets.get(k)), false, true);
    }

    public static void history(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet history");
        final int steps = (int) a.num(0, 30);
        final List<ClusterSequence.HistoryElement> h = cs.history();
        AppLogger.raw(f("%6s %8s %8s %8s %24s", "step", "parent1", "parent2", "child", "dij"));
        for (int k = cs.nParticles(); k < h.size() && k < cs.nParticles() + steps; k++) {
            final ClusterSequence.HistoryElement e = h.get(k);
            AppLogger.raw(f("%6d %8d %8s %8d %24s", k, e.parent1(),
                e.parent2() == ClusterSequence.BEAM_JET ? "beam" : String.valueOf(e.parent2()), e.child(),
                e.dijDD().toString(20)));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Commands: areas and background                                      */
    /* ------------------------------------------------------------------ */

    private static AreaDefinition areaDefinition(String kind, double ghostArea, double maxrap) {
        final GhostedAreaSpec spec = new GhostedAreaSpec(maxrap, 1, ghostArea);
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "explicit" -> new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, spec);
            case "passive" -> new AreaDefinition(AreaDefinition.AreaType.PASSIVE, spec);
            case "voronoi" -> new AreaDefinition(new AreaDefinition.VoronoiAreaSpec(1.0));
            default -> new AreaDefinition(AreaDefinition.AreaType.ACTIVE, spec);
        };
    }

    public static void area(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet area");
        final String kind = a.plain.isEmpty() || isNumber(a.plain.get(0)) ? "active" : a.plain.get(0);
        final double ptmin = a.num(a.plain.isEmpty() || isNumber(a.plain.get(0)) ? 0 : 1, 5.0);
        try {
            final ClusterSequenceArea csa = new ClusterSequenceArea(EVENTS.get(current), DEFS.get(active),
                areaDefinition(kind, a.opt("ghost-area", 0.01), a.opt("maxrap", 5.0)));
            final List<PseudoJet> jets = PseudoJet.sortedByPt(csa.inclusiveJets(ptmin));
            AppLogger.result(jets.size() + " jets above " + ptmin + " GeV with " + kind + " areas:");
            table(jets, true, false);
        } catch (RuntimeException e) {
            AppLogger.error("Areas failed: " + e.getMessage());
        }
    }

    public static void rho(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet rho");
        final double rapmax = a.opt("rapmax", 4.5);
        try {
            if (a.word(0, "grid").equalsIgnoreCase("jetmedian")) {
                final JetMedianBackgroundEstimator b = new JetMedianBackgroundEstimator(Selector.absRapMax(rapmax - 0.4),
                    new JetDefinition(JetAlgorithm.KT, 0.4),
                    new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(rapmax, 1, 0.01)));
                b.setParticles(EVENTS.get(current));
                AppLogger.result(f("rho = %.6g GeV per unit area, sigma = %.6g (kt 0.4 jets, median, |y| < %.2f)",
                    b.rho(), b.sigma(), rapmax - 0.4));
            } else {
                final GridMedianBackgroundEstimator b = new GridMedianBackgroundEstimator(rapmax, 0.55);
                b.setParticles(EVENTS.get(current));
                AppLogger.result(f("rho = %.6g GeV per unit area, sigma = %.6g (grid of 0.55 cells, |y| < %.2f)",
                    b.rho(), b.sigma(), rapmax));
            }
        } catch (RuntimeException e) {
            AppLogger.error("Background estimation failed: " + e.getMessage());
        }
    }

    public static void subtract(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet subtract");
        final double ptmin = a.num(0, 10.0);
        try {
            final GridMedianBackgroundEstimator bge = new GridMedianBackgroundEstimator(4.5, 0.55);
            bge.setParticles(EVENTS.get(current));
            final ClusterSequenceArea csa = new ClusterSequenceArea(EVENTS.get(current), DEFS.get(active),
                areaDefinition("active", 0.01, 5.0));
            final List<PseudoJet> jets = PseudoJet.sortedByPt(csa.inclusiveJets(ptmin));
            final Subtractor sub = new Subtractor(bge);
            AppLogger.result(f("rho = %.4g GeV per unit area; jets above %g GeV before and after subtraction:", bge.rho(), ptmin));
            AppLogger.raw(f("%5s %12s %12s %12s %12s %12s", "jet", "rapidity", "phi", "pt", "area", "pt subtr."));
            for (int k = 0; k < jets.size(); k++) {
                final PseudoJet j = jets.get(k);
                final PseudoJet s = sub.result(j);
                AppLogger.raw(f("%5d %12.6f %12.6f %12.4f %12.4f %12.4f", k, j.rap(), j.phi(), j.pt(), j.area(), s.pt()));
            }
        } catch (RuntimeException e) {
            AppLogger.error("Subtraction failed: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Commands: substructure                                              */
    /* ------------------------------------------------------------------ */

    public static void groom(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet groom");
        final String what = a.word(0, "softdrop").toLowerCase(Locale.ROOT);
        final Transformer t = switch (what) {
            case "softdrop", "sd" -> new SoftDrop(a.num(1, 0.0), a.num(2, 0.1));
            case "mmdt" -> SoftDrop.modifiedMassDrop(a.num(1, 0.1));
            case "trim", "trimmer" -> Filter.trimmer(a.num(1, 0.2), a.num(2, 0.03));
            case "filter" -> Filter.filter(a.num(1, 0.3), (int) a.num(2, 3));
            case "prune", "pruner" -> new Pruner(JetAlgorithm.CAMBRIDGE, a.num(1, 0.1), a.num(2, 0.5));
            default -> null;
        };
        if (t == null) {
            Handlers.usage(":fjet groom <softdrop beta zcut|mmdt zcut|trim rtrim ptfrac|filter r n|prune zcut rcut> [--njets n]");
            return;
        }
        AppLogger.result(t.description());
        AppLogger.raw(f("%5s %12s %12s %12s %12s %8s %8s", "jet", "pt", "mass", "pt groomed", "m groomed", "n", "n gr."));
        final List<PseudoJet> jets = leading(cs, (int) a.opt("njets", 2), 0);
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            try {
                final PseudoJet g = t.result(j);
                AppLogger.raw(f("%5d %12.4f %12.4f %12.4f %12.4f %8d %8d", k, j.pt(), j.m(), g.pt(), g.m(),
                    j.constituents().size(), g.E() == 0 ? 0 : g.constituents().size()));
            } catch (RuntimeException e) {
                AppLogger.error("jet " + k + ": " + e.getMessage());
            }
        }
    }

    public static void nsub(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet nsub");
        final int nmax = Math.max(2, (int) a.num(0, 3));
        final double beta = a.num(1, 1.0);
        final Nsubjettiness.Axes axes = Nsubjettiness.Axes.valueOf(a.word(2, "wta_kt").toUpperCase(Locale.ROOT));
        final StringBuilder head = new StringBuilder(f("%5s %10s", "jet", "pt"));
        for (int n = 1; n <= nmax; n++) head.append(f(" %10s", "tau" + n));
        for (int n = 2; n <= nmax; n++) head.append(f(" %9s", "tau" + n + (n - 1)));
        AppLogger.result(f("N-subjettiness, beta = %g, %s axes, normalised:", beta, axes));
        AppLogger.raw(head.toString());
        final List<PseudoJet> jets = leading(cs, (int) a.opt("njets", 2), 0);
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            final double[] tau = new double[nmax + 1];
            final StringBuilder line = new StringBuilder(f("%5d %10.3f", k, j.pt()));
            for (int n = 1; n <= nmax; n++) {
                tau[n] = new Nsubjettiness(n, beta, 1.0, axes, true).result(j);
                line.append(f(" %10.5f", tau[n]));
            }
            for (int n = 2; n <= nmax; n++) line.append(f(" %9.4f", tau[n - 1] == 0 ? 0 : tau[n] / tau[n - 1]));
            AppLogger.raw(line.toString());
        }
    }

    public static void ecf(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet ecf");
        final double beta = a.num(0, 2.0);
        final EnergyCorrelator ec = new EnergyCorrelator(beta);
        AppLogger.result(f("Energy correlators, beta = %g (sums to 106 bits):", beta));
        AppLogger.raw(f("%5s %10s %12s %12s %10s %10s %10s %10s", "jet", "pt", "e2", "e3", "C2", "D2", "N2", "M2"));
        final List<PseudoJet> jets = leading(cs, (int) a.opt("njets", 2), 0);
        for (int k = 0; k < jets.size(); k++) {
            final EnergyCorrelator.Values v = ec.compute(jets.get(k));
            AppLogger.raw(f("%5d %10.3f %12.5e %12.5e %10.5f %10.4f %10.5f %10.5f", k, jets.get(k).pt(), v.e2(), v.e3(),
                v.c2(), v.d2(), v.n2(), v.m2()));
        }
    }

    public static void lund(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet lund");
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(0));
        final int k = (int) a.num(0, 0);
        if (k >= jets.size()) {
            AppLogger.error("There are " + jets.size() + " jets.");
            return;
        }
        final LundPlane lp = new LundPlane(DEFS.get(active).isSpherical());
        final List<LundPlane.Declustering> ds = a.has("all") ? lp.all(jets.get(k)) : lp.primary(jets.get(k));
        AppLogger.result(ds.size() + (a.has("all") ? " declusterings (primary and secondary)" : " primary declusterings")
            + " of jet " + k + f(" (pt %.3f GeV):", jets.get(k).pt()));
        AppLogger.raw(f("%5s %6s %11s %11s %11s %11s %11s %9s", "step", "depth", "ln(1/D)", "ln kt", "z", "Delta", "kt", "psi"));
        for (int s = 0; s < ds.size(); s++) {
            final LundPlane.Declustering d = ds.get(s);
            AppLogger.raw(f("%5d %6d %11.5f %11.5f %11.6f %11.6f %11.5f %9.4f", s, d.depth(), d.lnOneOverDelta(), d.lnKt(),
                d.z(), d.delta(), d.kt(), d.psi()));
        }
    }

    public static void shapes(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet shapes");
        final double r = a.num(0, DEFS.get(active).R());
        AppLogger.raw(f("%5s %10s %6s %9s %9s %9s %9s %12s %12s", "jet", "pt", "n", "pTD", "LHA", "width", "thrust",
            "pull y", "pull phi"));
        final List<PseudoJet> jets = leading(cs, (int) a.opt("njets", 2), 0);
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            final double[] pull = JetShapes.pull(j);
            AppLogger.raw(f("%5d %10.3f %6d %9.5f %9.5f %9.5f %9.5f %12.4e %12.4e", k, j.pt(), j.constituents().size(),
                JetShapes.ptD(j), JetShapes.lesHouchesAngularity(j, r), JetShapes.angularity(j, 1, 1, r),
                JetShapes.thrustLike(j, r), pull[0], pull[1]));
        }
        if (jets.size() >= 2) {
            AppLogger.result(f("pull angle of jet 0 towards jet 1: %.4f rad", JetShapes.pullAngle(jets.get(0), jets.get(1))));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Commands: safety, robustness, precision, speed                      */
    /* ------------------------------------------------------------------ */

    public static void irc(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet irc");
        final JetDefinition def = definition(a.plain.isEmpty() ? null : a.plain.get(0));
        if (def == null) return;
        try {
            AppLogger.result("On the current event:");
            AppLogger.raw(IrcSafetyCheck.check(EVENTS.get(current), def, a.opt("ptmin", 20), (int) a.opt("trials", 10), 1).toString());
            AppLogger.result("On the textbook configurations:");
            for (IrcSafetyCheck.Probe p : IrcSafetyCheck.probes(def)) AppLogger.raw(p.toString());
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void robust(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet robust");
        final JetDefinition def = definition(a.plain.isEmpty() ? null : a.plain.get(0));
        if (def == null) return;
        final DetectorRobustness.Detector base = DetectorRobustness.Detector.lhcLike();
        final DetectorRobustness.Detector d = new DetectorRobustness.Detector(a.opt("cell", base.cellEta()),
            base.cellPhi(), base.etaMax(), a.opt("noise", base.noiseSigma()), a.opt("threshold", base.threshold()),
            base.stochastic(), base.constant(), a.opt("rho", base.pileupRho()), base.pileupMeanPt(),
            base.pileupRapMax(), (long) a.opt("seed", base.seed()));
        AppLogger.result(def.description());
        AppLogger.result("Hard jets above " + a.opt("ptmin", 20) + " GeV, particle level against the detector-level situations:");
        for (DetectorRobustness.Result r : DetectorRobustness.run(EVENTS.get(current), def, a.opt("ptmin", 20), d)) {
            AppLogger.raw(r.toString());
        }
    }

    public static void audit(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet audit");
        final JetDefinition def = definition(a.plain.isEmpty() || isNumber(a.plain.get(0)) ? null : a.plain.get(0));
        if (def == null) return;
        try {
            AppLogger.raw(PrecisionAudit.audit(EVENTS.get(current), def, a.num(a.plain.isEmpty() || isNumber(a.plain.get(0)) ? 0 : 1, 5.0)).toString());
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    public static void bench(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet bench");
        final JetDefinition def = definition(a.plain.isEmpty() ? null : a.plain.get(0));
        if (def == null) return;
        final int repeat = (int) a.opt("repeat", 3);
        final int threads = (int) a.opt("threads", 1);
        EventBatch.cluster(EVENTS, def, threads); // warm up the JIT
        final long t0 = System.nanoTime();
        for (int r = 0; r < repeat; r++) EventBatch.cluster(EVENTS, def, threads);
        final double us = (System.nanoTime() - t0) / 1e3 / repeat / EVENTS.size();
        AppLogger.result(f("%s: %.1f microseconds per event (%d events x %d repeats, %d thread%s, precision %s)",
            def.description(), us, EVENTS.size(), repeat, threads, threads > 1 ? "s" : "", def.precision()));
    }

    /**
     * The strategy chosen by measurement: FastJet's Best picks one from the
     * multiplicity and R with parabolas fitted on its authors' machines; here
     * each strategy is timed on the events actually loaded, on this machine.
     * Strategies differ only in how they find the smallest distance, but when
     * distances tie exactly (calorimeter cells on a lattice, duplicated
     * tracks) they may merge in another order; so the fastest strategy is
     * adopted only if it gives the same jets, to the bit, on every event.
     */
    public static void tune(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet tune");
        final JetDefinition def = DEFS.get(active);
        if (def.plugin() != null || def.isSpherical()) {
            AppLogger.result("Plugins and e+e- algorithms have a single strategy; nothing to tune.");
            return;
        }
        final int repeat = Math.max(1, (int) a.opt("repeat", 3));
        final Strategy[] candidates = {Strategy.N2PLAIN, Strategy.N2TILED, Strategy.N2MINHEAPTILED, Strategy.N2MHTLAZY9,
            Strategy.N2MHTLAZY25, Strategy.N2MHTLAZY9ALT, Strategy.NLNNCAM, Strategy.BEST};
        final List<JetDefinition> defs = new ArrayList<>();
        for (Strategy s : candidates) {
            if (s == Strategy.NLNNCAM && def.jetAlgorithm() != JetAlgorithm.CAMBRIDGE) continue;
            final JetDefinition d = new JetDefinition(def);
            d.setStrategy(s);
            defs.add(d);
        }
        // Everything warmed up by the JIT first, then interleaved rounds keeping
        // each strategy's fastest pass, so that neither the order of measurement
        // nor a pause of the machine favours one strategy.
        AppLogger.info(f("Timing %d events, %d rounds, with %s:", EVENTS.size(), repeat, def.description()));
        for (int w = 0; w < 2; w++) for (JetDefinition d : defs) passTime(d);
        final double[] us = new double[defs.size()];
        Arrays.fill(us, Double.MAX_VALUE);
        for (int r = 0; r < repeat; r++) {
            for (int k = 0; k < defs.size(); k++) us[k] = Math.min(us[k], passTime(defs.get(k)) / EVENTS.size());
        }
        for (int k = 0; k < defs.size(); k++) {
            final Strategy s = defs.get(k).strategy();
            AppLogger.raw(f("  %-16s %10.1f us per event%s", s.label(), us[k],
                (s == def.strategy() ? "   (current)" : "") + (s == Strategy.BEST ? "   (FastJet's choice from N and R)" : "")));
        }
        final List<Double> times = new ArrayList<>();
        for (double t : us) times.add(t);

        final Integer[] order = new Integer[defs.size()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, (x, y) -> Double.compare(times.get(x), times.get(y)));
        final List<List<PseudoJet>> reference = new ArrayList<>();
        for (List<PseudoJet> ev : EVENTS) reference.add(PseudoJet.sortedByPt(new ClusterSequence(ev, def).inclusiveJets(0)));
        double current = Double.NaN;
        for (int k = 0; k < defs.size(); k++) if (defs.get(k).strategy() == def.strategy()) current = times.get(k);
        for (int k : order) {
            final JetDefinition d = defs.get(k);
            if (times.get(k) > 0.95 * current) {
                AppLogger.result(f("%s keeps %s: no strategy is more than 5%% faster, which is within timing noise.",
                    active, def.strategy().label()));
                return;
            }
            if (d.strategy() == def.strategy()) {
                AppLogger.result(f("%s keeps %s, already the fastest here (%.1f us per event).", active,
                    def.strategy().label(), times.get(k)));
                return;
            }
            int differing = 0, firstDiffering = -1;
            for (int e = 0; e < EVENTS.size(); e++) {
                if (!sameJets(PseudoJet.sortedByPt(new ClusterSequence(EVENTS.get(e), d).inclusiveJets(0)), reference.get(e))) {
                    if (differing++ == 0) firstDiffering = e;
                }
            }
            if (differing > 0) {
                // Strategies differ only in the order they search; other jets come
                // either from exact ties or from a defect of one of the two.
                final int tied = equidistantNeighbours(EVENTS.get(firstDiffering));
                AppLogger.warn(f("%s would be faster but gives other jets on %d of %d events. Kept aside.",
                    d.strategy().label(), differing, EVENTS.size()));
                AppLogger.raw(tied > 0
                    ? f("      Event %d has %d particles with two nearest neighbours at exactly the same distance"
                        + " (cells on a lattice, duplicated inputs): the strategies break these ties in another order.",
                        firstDiffering, tied)
                    : f("      Event %d has no exact tie among its distances, so one of the two strategies is at fault:"
                        + " compare with N2Plain (:fjet def x %s,strategy=N2Plain) and report the event.",
                        firstDiffering, specOf(def)));
                continue;
            }
            DEFS.put(active, d);
            last = null;
            AppLogger.result(f("%s now uses %s (%.1f us per event, identical jets on all %d events).",
                active, d.strategy().label(), times.get(k), EVENTS.size()));
            return;
        }
        AppLogger.result(active + " keeps " + def.strategy().label() + ": no faster strategy gives the same jets.");
    }

    /** Microseconds to cluster all the loaded events once. */
    private static double passTime(JetDefinition d) {
        final long t0 = System.nanoTime();
        for (List<PseudoJet> ev : EVENTS) new ClusterSequence(ev, d);
        return (System.nanoTime() - t0) / 1e3;
    }

    /**
     * 2000 particles spread by golden-ratio sequences over |y| &lt; 3, the first
     * 20 with py = 0 and px &gt; 0, as tracks or cells on the x axis.
     */
    private static List<PseudoJet> phiZeroEvent() {
        final List<PseudoJet> ev = new ArrayList<>();
        for (int k = 0; k < 2000; k++) {
            final double pt = 0.5 + 10 * frac(k * 0.6180339887);
            final double y = -3 + 6 * frac(k * 0.7548776662);
            final double phi = 2 * Math.PI * frac(k * 0.5698402910);
            final double px = k < 20 ? pt : pt * Math.cos(phi), py = k < 20 ? 0.0 : pt * Math.sin(phi);
            ev.add(new PseudoJet(px, py, pt * Math.sinh(y), pt * Math.cosh(y)).setPrecision(Precision.DOUBLE));
        }
        return ev;
    }

    private static double frac(double x) {
        return x - Math.floor(x);
    }

    /** The :fjet def spec of a native definition, without strategy. */
    private static String specOf(JetDefinition d) {
        final String head = d.jetAlgorithm().name().toLowerCase(Locale.ROOT) + ":" + d.R()
            + (d.jetAlgorithm().nParameters() == 2 ? ":" + d.extraParam() : "");
        return head + ",precision=" + (d.precision() == Precision.DOUBLE ? "double" : "dd");
    }

    /**
     * The particles whose nearest neighbour in (y, phi) is not unique: two
     * or more at exactly the same distance, the ties that make strategies
     * diverge. Quadratic, so only the first 4000 particles are looked at.
     */
    private static int equidistantNeighbours(List<PseudoJet> ev) {
        final int n = Math.min(ev.size(), 4000);
        final double[] y = new double[n], phi = new double[n];
        for (int k = 0; k < n; k++) {
            y[k] = ev.get(k).rap();
            phi[k] = ev.get(k).phi();
        }
        int tied = 0;
        for (int i = 0; i < n; i++) {
            double best = Double.MAX_VALUE;
            int count = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                double dphi = Math.abs(phi[i] - phi[j]);
                if (dphi > Math.PI) dphi = 2 * Math.PI - dphi;
                final double dy = y[i] - y[j];
                final double d = dphi * dphi + dy * dy;
                if (d < best) {
                    best = d;
                    count = 1;
                } else if (d == best) {
                    count++;
                }
            }
            if (count > 1) tied++;
        }
        return tied;
    }

    /** Same jets, momentum components equal to the bit, both lists sorted by pt. */
    private static boolean sameJets(List<PseudoJet> a, List<PseudoJet> b) {
        if (a.size() != b.size()) return false;
        for (int k = 0; k < a.size(); k++) {
            final PseudoJet x = a.get(k), y = b.get(k);
            if (x.px() != y.px() || x.py() != y.py() || x.pz() != y.pz() || x.E() != y.E()) return false;
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Commands: output                                                    */
    /* ------------------------------------------------------------------ */

    public static void hist(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet hist");
        final String what = a.word(0, "pt").toLowerCase(Locale.ROOT);
        final int njets = (int) a.opt("njets", 2);
        final double ptmin = a.opt("ptmin", 20);
        final JetDefinition def = DEFS.get(active);
        final List<Double> values = new ArrayList<>();
        try {
            for (List<PseudoJet> ev : EVENTS) {
                final ClusterSequence cs = new ClusterSequence(ev, def);
                final List<PseudoJet> jets = leading(cs, njets, ptmin);
                if (what.equals("njets")) {
                    values.add((double) cs.inclusiveJets(ptmin).size());
                    continue;
                }
                for (PseudoJet j : jets) values.add(observable(what, j));
            }
        } catch (RuntimeException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        final double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        if (v.length == 0) {
            AppLogger.warn("No jet passed the selection.");
            return;
        }
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        AppLogger.result(f("%s of the %d leading jets above %g GeV: %d entries, mean %.6g", what, njets, ptmin, v.length, mean));
        RootPlotsPanel.instance().showColumn(what + " (" + active + ")", v);
        if (a.has("root")) {
            final String name = a.opts.get("root");
            final int bins = (int) a.opt("bins", 50);
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            for (double x : v) {
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            if (hi <= lo) hi = lo + 1;
            final StringBuilder list = new StringBuilder();
            for (double x : v) list.append(list.length() == 0 ? "" : ", ").append(f("%.10g", x));
            Handlers.cling(c, "[]{ const double v[] = {" + list + "}; TH1D *h = new TH1D(\"" + name + "\", \"" + what
                + ";" + what + ";jets\", " + bins + ", " + f("%.10g", lo) + ", " + f("%.10g", hi + 1e-9 * (hi - lo))
                + "); for (double x : v) h->Fill(x); return " + Handlers.keep(name, "TH1D", "h") + "; }()");
        }
    }

    private static double observable(String what, PseudoJet j) {
        return switch (what) {
            case "pt" -> j.pt();
            case "m", "mass" -> j.m();
            case "rap", "y" -> j.rap();
            case "phi" -> j.phi();
            case "eta" -> j.eta();
            case "nconst", "n" -> j.constituents().size();
            case "tau21" -> Nsubjettiness.ratio(2, 1.0, Nsubjettiness.Axes.WTA_KT).result(j);
            case "tau32" -> Nsubjettiness.ratio(3, 1.0, Nsubjettiness.Axes.WTA_KT).result(j);
            case "c2" -> new EnergyCorrelator(1.0).compute(j).c2();
            case "d2" -> new EnergyCorrelator(1.0).compute(j).d2();
            case "sdmass" -> new SoftDrop(0.0, 0.1).result(j).m();
            case "ptd" -> JetShapes.ptD(j);
            case "girth" -> JetShapes.girth(j);
            default -> throw new IllegalArgumentException("unknown observable " + what
                + " (pt, m, rap, eta, phi, nconst, njets, tau21, tau32, c2, d2, sdmass, ptd, girth)");
        };
    }

    public static void display(String i, CommandExecutionContext c) {
        final ClusterSequence cs = clustering();
        if (cs == null) return;
        final Args a = new Args(i, ":fjet display");
        try {
            final File out;
            if (a.has("png")) {
                out = Handlers.resolve(a.opts.get("png"));
            } else {
                out = RootPlotsPanel.instance().outputFolder().resolve("fjet-event-" + current + "-" + active + ".png").toFile();
            }
            render(cs, out);
            RootPlotsPanel.instance().showImage(out);
            AppLogger.result("Event " + current + " drawn in " + out);
        } catch (IOException | RuntimeException e) {
            AppLogger.error("Cannot draw the event: " + e.getMessage());
        }
    }

    /** The event in the (rapidity, phi) plane: particles by jet, area by pt, jet axes and radii. */
    private static void render(ClusterSequence cs, File out) throws IOException {
        final int w = 1100;
        final int h = 620;
        final int left = 60;
        final int top = 40;
        final int pw = w - left - 30;
        final int ph = h - top - 60;
        final double ymin = -5;
        final double ymax = 5;
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(230, 230, 230));
        for (int k = -5; k <= 5; k++) {
            final int x = left + (int) ((k - ymin) / (ymax - ymin) * pw);
            g.drawLine(x, top, x, top + ph);
        }
        for (int k = 0; k <= 6; k++) {
            final int y = top + ph - (int) (k / (2 * Math.PI) * ph);
            g.drawLine(left, y, left + pw, y);
        }
        g.setColor(Color.DARK_GRAY);
        g.drawRect(left, top, pw, ph);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int k = -5; k <= 5; k++) g.drawString(String.valueOf(k), left + (int) ((k - ymin) / (ymax - ymin) * pw) - 4, top + ph + 16);
        for (int k = 0; k <= 6; k++) g.drawString(String.valueOf(k), left - 18, top + ph - (int) (k / (2 * Math.PI) * ph) + 4);
        g.drawString("rapidity y", left + pw / 2 - 30, h - 12);
        g.drawString("phi", 14, top + ph / 2);
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(5.0));
        final Color[] palette = {new Color(0x2b6cb0), new Color(0xc53030), new Color(0x2f855a), new Color(0xb7791f),
            new Color(0x6b46c1), new Color(0x0e7490), new Color(0xb83280), new Color(0x4a5568)};
        final java.util.Set<Integer> placed = new java.util.HashSet<>();
        double ptmax = 1e-9;
        for (PseudoJet p : cs.jets().subList(0, cs.nParticles())) ptmax = Math.max(ptmax, p.pt());
        for (int k = 0; k < jets.size(); k++) {
            final Color col = palette[k % palette.length];
            for (PseudoJet p : jets.get(k).constituents()) {
                placed.add(p.clusterHistIndex());
                dot(g, p, col, ptmax, left, top, pw, ph, ymin, ymax);
            }
        }
        for (int k = 0; k < cs.nParticles(); k++) {
            final PseudoJet p = cs.jet(k);
            if (!placed.contains(p.clusterHistIndex())) dot(g, p, new Color(170, 170, 170), ptmax, left, top, pw, ph, ymin, ymax);
        }
        final double r = cs.jetDef().R();
        g.setStroke(new BasicStroke(1.5f));
        for (int k = 0; k < jets.size(); k++) {
            final PseudoJet j = jets.get(k);
            g.setColor(palette[k % palette.length]);
            final int cx = left + (int) ((j.rap() - ymin) / (ymax - ymin) * pw);
            final int cy = top + ph - (int) (j.phi() / (2 * Math.PI) * ph);
            final int rx = (int) (r / (ymax - ymin) * pw);
            final int ry = (int) (r / (2 * Math.PI) * ph);
            g.drawOval(cx - rx, cy - ry, 2 * rx, 2 * ry);
            g.drawString(f("%d: %.1f GeV", k, j.pt()), cx + rx + 2, cy);
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString(f("event %d, %s, %d jets above 5 GeV", current, cs.jetDef().description(), jets.size()), left, 24);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", out);
    }

    private static void dot(Graphics2D g, PseudoJet p, Color col, double ptmax, int left, int top, int pw, int ph,
                            double ymin, double ymax) {
        if (!(p.pt() > 0) || p.rap() < ymin || p.rap() > ymax) return;
        final int x = left + (int) ((p.rap() - ymin) / (ymax - ymin) * pw);
        final int y = top + ph - (int) (p.phi() / (2 * Math.PI) * ph);
        final int d = 2 + (int) (16 * Math.sqrt(p.pt() / ptmax));
        g.setColor(col);
        g.fillOval(x - d / 2, y - d / 2, d, d);
    }

    public static void export(String i, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Args a = new Args(i, ":fjet export");
        if (a.plain.isEmpty()) {
            Handlers.usage(":fjet export <file.csv> [ptmin]");
            return;
        }
        final double ptmin = a.num(1, 5.0);
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        final JetDefinition def = DEFS.get(active);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("event,jet,pt,rapidity,phi,mass,E,px,py,pz,nconst,weight");
            int n = 0;
            for (int e = 0; e < EVENTS.size(); e++) {
                final List<PseudoJet> jets = PseudoJet.sortedByPt(new ClusterSequence(EVENTS.get(e), def).inclusiveJets(ptmin));
                for (int k = 0; k < jets.size(); k++) {
                    final PseudoJet j = jets.get(k);
                    w.println(f("%d,%d,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%.12g,%d,%.10g", e, k, j.pt(), j.rap(), j.phi(),
                        j.m(), j.E(), j.px(), j.py(), j.pz(), j.constituents().size(), WEIGHTS.get(e)));
                    n++;
                }
            }
            AppLogger.result(n + " jets of " + EVENTS.size() + " events written to " + out);
        } catch (IOException | RuntimeException e) {
            AppLogger.error(e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Self test and housekeeping                                          */
    /* ------------------------------------------------------------------ */

    public static void selftest(String i, CommandExecutionContext c) {
        List<PseudoJet> ev = EVENTS.isEmpty() ? ToyEvents.generate(1, 3, 300, 150, 7).get(0) : EVENTS.get(current);
        int failures = 0;
        AppLogger.result("Self test on " + (EVENTS.isEmpty() ? "a toy event" : "event " + current) + " (" + ev.size() + " particles):");
        // every strategy finds the same jets, in double precision. The histories
        // themselves may list steps in a different order (C/A's beam distances are
        // all equal, FastJet breaks those ties per strategy) and NlnNCam computes
        // the distances another way, so what is compared is the physics: the
        // inclusive jets bit for bit, the exclusive d_merge to 1e-10.
        final Strategy[] strategies = {Strategy.N3DUMB, Strategy.N2PLAIN, Strategy.N2TILED, Strategy.N2MINHEAPTILED,
            Strategy.N2MHTLAZY9, Strategy.N2MHTLAZY25, Strategy.N2MHTLAZY9ALT, Strategy.NLNNCAM};
        for (JetAlgorithm alg : new JetAlgorithm[]{JetAlgorithm.KT, JetAlgorithm.CAMBRIDGE, JetAlgorithm.ANTIKT}) {
            List<PseudoJet> refJets = null;
            double[] refD = null;
            boolean same = true;
            String where = "";
            for (Strategy s : strategies) {
                if (s == Strategy.NLNNCAM && alg != JetAlgorithm.CAMBRIDGE) continue;
                final JetDefinition d = new JetDefinition(alg, 0.6, com.sphere.core.fastjet.RecombinationScheme.E_SCHEME, s);
                d.setPrecision(Precision.DOUBLE);
                final ClusterSequence cs = new ClusterSequence(ev, d);
                final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(0));
                final int nd = Math.min(20, cs.nParticles());
                final double[] dm = new double[nd];
                for (int n = 1; n <= nd; n++) dm[n - 1] = cs.exclusiveDmerge(n);
                if (refJets == null) {
                    refJets = jets;
                    refD = dm;
                    continue;
                }
                boolean ok = sameJets(jets, refJets);
                for (int k = 0; ok && k < nd; k++) ok = Math.abs(dm[k] - refD[k]) <= 1e-10 * Math.abs(refD[k]);
                if (!ok) {
                    same = false;
                    where = " (" + s.label() + " differs from N3Dumb)";
                }
            }
            AppLogger.raw((same ? "  ok    " : "  FAIL  ") + alg.name().toLowerCase(Locale.ROOT)
                + ": all strategies find the same jets and merging scales" + where);
            if (!same) failures++;
        }
        // Particles at phi = 0 exactly with R = 0.25 (25 tiles in phi): the lazy
        // tilings of FastJet 3.5.2 put them 2 pi away and miss neighbours there.
        {
            final List<PseudoJet> pz = phiZeroEvent();
            List<PseudoJet> refJets = null;
            String where = "";
            for (Strategy s : new Strategy[]{Strategy.N2PLAIN, Strategy.N2MHTLAZY9, Strategy.N2MHTLAZY25, Strategy.N2MHTLAZY9ALT}) {
                final JetDefinition d = new JetDefinition(JetAlgorithm.ANTIKT, 0.25, com.sphere.core.fastjet.RecombinationScheme.E_SCHEME, s);
                d.setPrecision(Precision.DOUBLE);
                List<PseudoJet> jets;
                try {
                    jets = PseudoJet.sortedByPt(new ClusterSequence(pz, d).inclusiveJets(0));
                } catch (RuntimeException e) {
                    jets = List.of();
                }
                if (refJets == null) {
                    refJets = jets;
                } else if (!sameJets(jets, refJets)) {
                    where += " " + s.label();
                }
            }
            AppLogger.raw((where.isEmpty() ? "  ok    " : "  FAIL  ")
                + "particles at phi = 0 with R = 0.25: the lazy tilings agree with N2Plain"
                + (where.isEmpty() ? " (FastJet " + FastJet.FASTJET_VERSION + " does not)" : " except" + where));
            if (!where.isEmpty()) failures++;
        }
        // double and double-double take the same decisions on an ordinary event
        final PrecisionAudit.Report audit = PrecisionAudit.audit(ev, new JetDefinition(JetAlgorithm.ANTIKT, 0.4), 5);
        AppLogger.raw((audit.identicalDecisions() ? "  ok    " : "  NOTE  ")
            + "double and double-double histories: " + audit.divergentSteps() + " decisions differ");
        // the kt family and SISCone pass the textbook IRC configurations, seeded cones do not
        for (String spec : new String[]{"kt:0.4", "antikt:0.4", "cambridge:0.4", "siscone:0.4,f=0.75"}) {
            boolean safe = true;
            for (IrcSafetyCheck.Probe p : IrcSafetyCheck.probes(JetSpecs.parse(spec))) safe &= p.passed();
            AppLogger.raw((safe ? "  ok    " : "  FAIL  ") + spec + " is IRC safe on the textbook configurations");
            if (!safe) failures++;
        }
        boolean caught = false;
        for (IrcSafetyCheck.Probe p : IrcSafetyCheck.probes(JetSpecs.parse("cmscone:0.4,seed=0"))) caught |= !p.passed();
        AppLogger.raw((caught ? "  ok    " : "  FAIL  ") + "the CMS iterative cone is caught collinear unsafe");
        if (!caught) failures++;
        // a 4-momentum is conserved by the E scheme
        final ClusterSequence cs = new ClusterSequence(ev, new JetDefinition(JetAlgorithm.KT, 0.4));
        PseudoJet sum = new PseudoJet(0, 0, 0, 0);
        for (PseudoJet j : cs.inclusiveJets(0)) sum = sum.plus(j);
        PseudoJet tot = new PseudoJet(0, 0, 0, 0);
        for (PseudoJet p : ev) tot = tot.plus(p);
        final boolean conserved = Math.abs(sum.E() - tot.E()) <= 1e-9 * tot.E();
        AppLogger.raw((conserved ? "  ok    " : "  FAIL  ") + "the jets carry the event's energy (E scheme)");
        if (!conserved) failures++;
        AppLogger.result(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
    }

    /* ------------------------------------------------------------------ */
    /* What the bridge to the other engines reads                          */
    /* ------------------------------------------------------------------ */

    static List<List<PseudoJet>> loadedEvents() { return EVENTS; }
    static List<Double> loadedWeights() { return WEIGHTS; }
    static List<EventIO.Incoming> loadedIncoming() { return INCOMING; }
    static String loadedSource() { return source; }
    static String activeName() { return active; }
    static JetDefinition activeDefinition() { return DEFS.get(active); }
    static int currentIndex() { return current; }

    /** The clustering of the current event with the active definition, the one ':fjet jets' shows. */
    static ClusterSequence currentClustering() {
        return clustering();
    }

    /**
     * Replaces the events, as ':fjco toy' and ':fjco sample use' do: every
     * :fjet command then works on them. The definitions are kept.
     */
    static void adoptEvents(List<List<PseudoJet>> events, List<Double> weights, List<EventIO.Incoming> incoming,
                            String from) {
        EVENTS.clear();
        WEIGHTS.clear();
        INCOMING.clear();
        for (int e = 0; e < events.size(); e++) {
            final List<PseudoJet> ev = events.get(e);
            for (PseudoJet p : ev) p.setPrecision(Precision.defaultPrecision());
            EVENTS.add(ev);
            WEIGHTS.add(weights != null && e < weights.size() ? weights.get(e) : 1.0);
            INCOMING.add(incoming != null && e < incoming.size() ? incoming.get(e) : null);
        }
        source = from;
        current = 0;
        last = null;
    }

    /** A jet observable by the name :fjet hist knows it by. */
    static double jetObservable(String what, PseudoJet j) {
        return observable(what, j);
    }

    /** The n hardest jets above ptmin. */
    static List<PseudoJet> hardest(ClusterSequence cs, int n, double ptmin) {
        return leading(cs, n, ptmin);
    }

    public static void clear(String i, CommandExecutionContext c) {
        EVENTS.clear();
        WEIGHTS.clear();
        INCOMING.clear();
        last = null;
        source = "";
        current = 0;
        AppLogger.result("Events and clusterings forgotten; the definitions are kept.");
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
