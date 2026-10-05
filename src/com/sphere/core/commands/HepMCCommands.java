package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.InProcessEngine;
import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.BridgeHepMCCheck;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.bridge.SpxHepMC;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.io.EventIO;
import com.sphere.core.hepmc3.Attribute;
import com.sphere.core.hepmc3.Compression;
import com.sphere.core.hepmc3.FourVector;
import com.sphere.core.hepmc3.GenCrossSection;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenPdfInfo;
import com.sphere.core.hepmc3.GenRunInfo;
import com.sphere.core.hepmc3.GenVertex;
import com.sphere.core.hepmc3.HEPEVT;
import com.sphere.core.hepmc3.HepMC3;
import com.sphere.core.hepmc3.HepMC3Citations;
import com.sphere.core.hepmc3.IntAttribute;
import com.sphere.core.hepmc3.Print;
import com.sphere.core.hepmc3.Reader;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.hepmc3.ReaderAsciiHepMC2;
import com.sphere.core.hepmc3.ReaderFactory;
import com.sphere.core.hepmc3.ReaderGZ;
import com.sphere.core.hepmc3.ReaderHEPEVT;
import com.sphere.core.hepmc3.ReaderLHEF;
import com.sphere.core.hepmc3.Readerprotobuf;
import com.sphere.core.hepmc3.Units;
import com.sphere.core.hepmc3.Writer;
import com.sphere.core.hepmc3.ReaderRoot;
import com.sphere.core.hepmc3.ReaderRootTree;
import com.sphere.core.hepmc3.WriterAscii;
import com.sphere.core.hepmc3.WriterAsciiHepMC2;
import com.sphere.core.hepmc3.WriterGZ;
import com.sphere.core.hepmc3.WriterHEPEVT;
import com.sphere.core.hepmc3.WriterRoot;
import com.sphere.core.hepmc3.WriterRootTree;
import com.sphere.core.hepmc3.Writerprotobuf;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.COutput;
import com.sphere.core.hepmc3.cxx.StdStreams;
import com.sphere.core.hepmc3.search.CutExpression;
import com.sphere.core.hepmc3.search.Filter;
import com.sphere.core.hepmc3.search.Relatives;
import com.sphere.core.hepmc3.validation.References;
import com.sphere.core.hepmc3.validation.TestProgram;
import com.sphere.core.hepmc3.validation.Tests;
import com.sphere.core.hepmc3.validation.Validator;
import com.sphere.core.rootbackend.RootBackend;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The HepMC3 event record, in Java: the {@code :hepmc} commands, on Sphere's
 * own port of HepMC3 3.03.01.
 *
 * <p>Events are read from any format HepMC3 reads (Asciiv3, HepMC2,
 * HEPEVT, Les Houches, protobuf, compressed or not, and SPX from the other
 * engines) or made up ({@code :hepmc toy}); one of them is current. They can
 * be printed as HepMC3 prints them, searched, cut, transformed, written in any
 * format, handed to FastJet ({@code :hepmc tofjet}) and to every other engine
 * ({@code :hepmc bridge}), and the port itself is checked against the C++
 * test suite ({@code :hepmc validate}). What HepMC3 prints on cout and cerr
 * (its warnings and errors) comes to the console.
 */
public final class HepMCCommands {

    private static final List<GenEvent> EVENTS = new ArrayList<>();
    private static GenRunInfo run;
    private static String source = "";
    private static String format = "";
    private static int current;

    /** HepMC3's cout and cerr, sent to the console: warnings as warnings, errors as errors. */
    private static final StdStreams CONSOLE = new StdStreams(HepMCCommands::coutLine, HepMCCommands::cerrLine);

    private HepMCCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    private static void coutLine(String line) {
        if (line.startsWith("WARNING::")) AppLogger.warn(line);
        else if (line.startsWith("ERROR::")) AppLogger.error(line);
        else AppLogger.raw(line);
    }

    private static void cerrLine(String line) {
        // HepMC3's errors say so; the rest of cerr (LHEF's "opening file 0") is information
        if (line.startsWith("ERROR::")) AppLogger.error(line);
        else if (line.startsWith("WARNING::")) AppLogger.warn(line);
        else AppLogger.info(line);
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                        */
    /* ------------------------------------------------------------------ */

    /** A command body, run with HepMC3's streams on the console. */
    @FunctionalInterface
    private interface Body {
        void run(Args a, CommandExecutionContext c) throws Exception;
    }

    private static void reg(String sub, String description, Body body) {
        final String name = ":hepmc " + sub;
        CommandDefinitions.register(name, description, (i, c) -> {
            final Args a = new Args(i, name);
            try {
                StdStreams.with(CONSOLE, () -> {
                    body.run(a, c);
                    return null;
                });
            } catch (Exception e) {
                AppLogger.error(name + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    static void register() {
        reg("help", "What the HepMC3 commands do, in the order an analysis uses them", HepMCCommands::help);
        reg("version", "The version (HepMC3 Java Sphere); references under Citations in the console menu", (a, c) ->
            AppLogger.raw(HepMC3.VERSION_LINE));
        reg("read", "Read events. Usage: :hepmc read <file> [--format auto|asciiv3|hepmc2|hepevt|lhef|protobuf|root|rootkeys|spx] [--max N] [--skip N]",
            HepMCCommands::read);
        reg("write", "Write the events. Usage: :hepmc write <file> [--format asciiv3|hepmc2|hepevt|protobuf|root|rootkeys|spx] [--gz] [--append] [--precision p] [--float g]",
            HepMCCommands::write);
        reg("convert", "Convert a file event by event, without holding it. Usage: :hepmc convert <in> <out> [--from fmt] [--format fmt] [--gz] [--max N]",
            HepMCCommands::convert);
        reg("toy", "Make toy e+e- -> Z -> hadrons events with a full vertex graph. Usage: :hepmc toy [events] [--seed s] [--sqrts GeV]",
            HepMCCommands::toy);
        reg("info", "The sample: where from, how many events, the run information", HepMCCommands::info);
        reg("runinfo", "The run information as HepMC3 prints it: tools, weight names, attributes", HepMCCommands::runinfo);
        reg("event", "Show or choose the current event. Usage: :hepmc event [index]", HepMCCommands::event);
        reg("listing", "The event as HepMC3's Print::listing shows it. Usage: :hepmc listing [index] [--precision p]",
            HepMCCommands::listing);
        reg("content", "Everything the event holds, as HepMC3's Print::content shows it. Usage: :hepmc content [index]",
            HepMCCommands::content);
        reg("particles", "The particles of the current event, optionally cut. Usage: :hepmc particles [cut] [--max n]",
            HepMCCommands::particles);
        reg("vertices", "The vertices of the current event, with their position and particles", HepMCCommands::vertices);
        reg("tree", "The decay tree below a particle (the beams by default). Usage: :hepmc tree [particle id] [--depth d]",
            HepMCCommands::tree);
        reg("relatives", "HepMC3's relatives of a particle. Usage: :hepmc relatives <particle id> <parents|children|ancestors|descendants>",
            HepMCCommands::relatives);
        reg("select", "The particles passing a cut, with HepMC3's search library. Usage: :hepmc select <cut> [--all] (e.g. status==1 && pt>10 && abs(eta)<2.5)",
            HepMCCommands::select);
        reg("filter", "Keep the events with at least n particles passing a cut. Usage: :hepmc filter <cut> [--min n]",
            HepMCCommands::filter);
        reg("stats", "Multiplicities, statuses, particle species, weights and cross-section over the sample",
            HepMCCommands::stats);
        reg("weights", "The weights of an event with their names. Usage: :hepmc weights [index]", HepMCCommands::weights);
        reg("xsec", "The cross-section the events carry (GenCrossSection), first and last", HepMCCommands::xsec);
        reg("attributes", "The attributes of an event, its particles and vertices. Usage: :hepmc attributes [index]",
            HepMCCommands::attributes);
        reg("boost", "Boost the event(s) by a velocity. Usage: :hepmc boost <bx> <by> <bz> [--all]", HepMCCommands::boost);
        reg("rotate", "Rotate the event(s) by angles about x, y, z. Usage: :hepmc rotate <ax> <ay> <az> [--all]",
            HepMCCommands::rotate);
        reg("reflect", "Reflect the event(s) along an axis. Usage: :hepmc reflect <x|y|z|t> [--all]", HepMCCommands::reflect);
        reg("shift", "Shift the event(s) position. Usage: :hepmc shift <x> <y> <z> <t> [--all]", HepMCCommands::shift);
        reg("units", "Change the units of the event(s). Usage: :hepmc units <GEV|MEV> <MM|CM> [--all]", HepMCCommands::units);
        reg("hist", "Histogram a particle quantity over all events in the Plots tab. Usage: :hepmc hist <pt|eta|rap|phi|e|m|pid|mult> [--cut expr] [--bins b]",
            HepMCCommands::hist);
        reg("dot", "Write the current event's graph for Graphviz. Usage: :hepmc dot <file.dot> [index]", HepMCCommands::dot);
        reg("tofjet", "Hand the final-state particles of every event to :fjet, with weights and incoming partons. Usage: :hepmc tofjet [--cut expr]",
            HepMCCommands::tofjet);
        reg("bridge", "Hand the events to every engine as SPX (Python, Julia, C++, Fortran HEPEVT, ROOT). Usage: :hepmc bridge [julia|root] [as <name>]",
            HepMCCommands::bridge);
        reg("crosscheck", "Every engine reads the events back; checksums compared with Java bit for bit (and the C++ HepMC3 library's text if installed). Usage: :hepmc crosscheck [--engines python,julia,c++,fortran,root,hepmc3]",
            HepMCCommands::crosscheck);
        reg("validate", "Run HepMC3's own test programs, ported, against the C++ outputs. Usage: :hepmc validate [test]",
            HepMCCommands::validate);
        reg("selftest", "Write and read back the events in every format, checking nothing changes", HepMCCommands::selftest);
        reg("bench", "Time writing and reading the events in each format. Usage: :hepmc bench [--repeat n]", HepMCCommands::bench);
        reg("bib", "Write the BibTeX of HepMC3 (and of the formats used). Usage: :hepmc bib <file.bib> [--all]", HepMCCommands::bib);
        reg("clear", "Forget the events", (a, c) -> {
            EVENTS.clear();
            run = null;
            source = "";
            format = "";
            current = 0;
            AppLogger.result("Events forgotten.");
        });
    }

    /* ------------------------------------------------------------------ */
    /* Shared                                                              */
    /* ------------------------------------------------------------------ */

    /** The words after the command, its --options, and an "as name". */
    private static final class Args {
        final List<String> plain = new ArrayList<>();
        final Map<String, String> opts = new LinkedHashMap<>();
        String as;

        Args(String input, String command) {
            final String[] w = Handlers.words(Handlers.args(input, command));
            for (int k = 0; k < w.length; k++) {
                if (w[k].startsWith("--")) {
                    final String key = w[k].substring(2).toLowerCase(Locale.ROOT);
                    if (k + 1 < w.length && !w[k + 1].startsWith("--")) opts.put(key, w[++k]);
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

        boolean has(String key) {
            return opts.containsKey(key);
        }

        /** The plain words from k on, as one text (a cut expression). */
        String rest(int k) {
            return k >= plain.size() ? "" : String.join(" ", plain.subList(k, plain.size()));
        }
    }

    private static String f(String pattern, Object... values) {
        return String.format(Locale.ROOT, pattern, values);
    }

    private static boolean haveEvents() {
        if (EVENTS.isEmpty()) {
            AppLogger.error("No events yet: ':hepmc read <file>' or ':hepmc toy' makes some.");
            return false;
        }
        return true;
    }

    /** The event a word names, or the current one. */
    private static GenEvent event(Args a, int k) {
        if (!haveEvents()) return null;
        final int i = (int) a.num(k, current);
        if (i < 0 || i >= EVENTS.size()) {
            AppLogger.error("No event " + i + ": there are " + EVENTS.size() + " (0 to " + (EVENTS.size() - 1) + ").");
            return null;
        }
        return EVENTS.get(i);
    }

    /** The events a transformation applies to: all with --all, else the current one. */
    private static List<GenEvent> targets(Args a) {
        if (!haveEvents()) return List.of();
        return a.has("all") ? EVENTS : List.of(EVENTS.get(current));
    }

    /** The particles of an event a cut keeps. */
    private static List<GenParticle> cut(GenEvent e, Filter filter) {
        return Filter.applyFilter(filter, e.particles());
    }

    /* ------------------------------------------------------------------ */
    /* Particle names                                                      */
    /* ------------------------------------------------------------------ */

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final java.util.Set<Integer> SELF_CONJUGATE = java.util.Set.of(21, 22, 23, 25, 111, 113, 130, 221,
        223, 310, 331, 333, 443, 553, 91, 92);

    static {
        final Object[] table = {1, "d", 2, "u", 3, "s", 4, "c", 5, "b", 6, "t", 11, "e-", 12, "nu_e", 13, "mu-",
            14, "nu_mu", 15, "tau-", 16, "nu_tau", 21, "g", 22, "gamma", 23, "Z0", 24, "W+", 25, "h0", 91, "cluster",
            92, "string", 111, "pi0", 211, "pi+", 113, "rho0", 213, "rho+", 221, "eta", 223, "omega", 331, "eta'",
            333, "phi", 130, "K_L0", 310, "K_S0", 311, "K0", 321, "K+", 313, "K*0", 323, "K*+", 411, "D+", 421, "D0",
            431, "D_s+", 443, "J/psi", 511, "B0", 521, "B+", 531, "B_s0", 553, "Upsilon", 2212, "p+", 2112, "n0",
            3122, "Lambda0", 3222, "Sigma+", 3212, "Sigma0", 3112, "Sigma-", 3322, "Xi0", 3312, "Xi-", 3334, "Omega-",
            2224, "Delta++", 2214, "Delta+", 2114, "Delta0", 1114, "Delta-", 4122, "Lambda_c+", 5122, "Lambda_b0"};
        for (int k = 0; k < table.length; k += 2) NAMES.put((Integer) table[k], (String) table[k + 1]);
    }

    /** A readable name for a PDG code ("pi+", "anti-p-"...), the number when unknown. */
    static String pdgName(int pid) {
        final String n = NAMES.get(Math.abs(pid));
        if (n == null) return Integer.toString(pid);
        if (pid > 0 || SELF_CONJUGATE.contains(-pid)) return n;
        if (n.endsWith("+") && !n.endsWith("++")) return n.substring(0, n.length() - 1) + "-";
        if (n.endsWith("-")) return n.substring(0, n.length() - 1) + "+";
        if (n.endsWith("++")) return "anti-" + n.substring(0, n.length() - 2) + "--";
        return "anti-" + n;
    }

    /* ------------------------------------------------------------------ */
    /* Formats                                                             */
    /* ------------------------------------------------------------------ */

    /** The reader of a format, on a file. */
    private static Reader openReader(Path file, String fmt) throws IOException {
        final Compression c = Compression.detect(file);
        if (fmt.equals("auto") && isRootFile(file)) {
            // deduce_reader tries the tree only; a file of event objects (WriterRoot's) is read by ReaderRoot
            return hasHepMCTree(file) ? new ReaderRootTree(file.toString()) : new ReaderRoot(file.toString());
        }
        if (fmt.equals("auto")) {
            final Reader r = ReaderFactory.deduceReader(file.toString());
            if (r == null) throw new IOException("HepMC3 recognises none of its formats in " + file.getFileName());
            return r;
        }
        if (fmt.equals("root") || fmt.equals("roottree")) return new ReaderRootTree(file.toString());
        if (fmt.equals("rootkeys") || fmt.equals("rootobjects")) return new ReaderRoot(file.toString());
        final Function<com.sphere.core.hepmc3.cxx.CInput, Reader> onStream = switch (fmt) {
            case "asciiv3", "hepmc3" -> ReaderAscii::new;
            case "hepmc2", "iogenevent" -> ReaderAsciiHepMC2::new;
            case "hepevt" -> ReaderHEPEVT::new;
            case "lhef", "lhe" -> ReaderLHEF::new;
            case "protobuf", "pb" -> null;
            default -> throw new IOException("unknown format '" + fmt + "' (auto, asciiv3, hepmc2, hepevt, lhef, protobuf, root, rootkeys, spx)");
        };
        if (onStream == null) return new Readerprotobuf(file);
        if (c != Compression.PLAINTEXT) return new ReaderGZ(file, onStream);
        return switch (fmt) {
            case "asciiv3", "hepmc3" -> new ReaderAscii(file);
            case "hepmc2", "iogenevent" -> new ReaderAsciiHepMC2(file);
            case "hepevt" -> new ReaderHEPEVT(file);
            default -> new ReaderLHEF(file);
        };
    }

    /** True for a file that starts as ROOT files do. */
    private static boolean isRootFile(Path file) {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            final byte[] h = in.readNBytes(4);
            return h.length == 4 && h[0] == 'r' && h[1] == 'o' && h[2] == 'o' && h[3] == 't';
        } catch (IOException e) {
            return false;
        }
    }

    /** True when the ROOT file holds HepMC3's tree, as WriterRootTree writes it. */
    private static boolean hasHepMCTree(Path file) {
        try (com.sphere.core.rootio.RootIO io = com.sphere.core.rootio.RootIO.open(file)) {
            final com.sphere.components.rootview.RootKey k = io.key("hepmc3_tree");
            return k != null && k.className.equals("TTree");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** HepMC3's name of the format a reader reads. */
    private static String formatOf(Reader r) {
        if (r instanceof ReaderRootTree) return "ROOT tree";
        if (r instanceof ReaderRoot) return "ROOT";
        if (r instanceof ReaderGZ gz) return formatOf(gz.reader()) + ", compressed";
        if (r instanceof ReaderAscii) return "Asciiv3";
        if (r instanceof ReaderAsciiHepMC2) return "HepMC2";
        if (r instanceof ReaderLHEF) return "LHEF";
        if (r instanceof ReaderHEPEVT) return "HEPEVT";
        if (r instanceof Readerprotobuf) return "protobuf";
        return r.getClass().getSimpleName();
    }

    /** The format a file name suggests, and whether it ends in .gz. */
    private static String formatFor(Path file, Args a) {
        if (a.has("format")) return a.opts.get("format").toLowerCase(Locale.ROOT);
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".gz")) n = n.substring(0, n.length() - 3);
        if (n.endsWith(".spx")) return "spx";
        if (n.endsWith(".hepmc2")) return "hepmc2";
        if (n.endsWith(".hepevt")) return "hepevt";
        if (n.endsWith(".pb") || n.endsWith(".proto") || n.endsWith(".hepmc3pb")) return "protobuf";
        if (n.endsWith(".root")) return "root";
        return "asciiv3";
    }

    /** A writer of a format, on a file, gzip'ed when asked. */
    private static Writer openWriter(Path file, String fmt, boolean gz, GenRunInfo r) throws IOException {
        return openWriter(file, fmt, gz, r, false);
    }

    /** The same, a ROOT tree added to when asked (TFile's "UPDATE"). */
    private static Writer openWriter(Path file, String fmt, boolean gz, GenRunInfo r, boolean append) throws IOException {
        if (fmt.equals("root") || fmt.equals("roottree") || fmt.equals("rootkeys") || fmt.equals("rootobjects")) {
            if (gz) throw new IOException("a ROOT file compresses its own records: --gz does not apply");
            if (fmt.startsWith("rootk") || fmt.startsWith("rooto")) return new WriterRoot(file.toString(), r);
            return new WriterRootTree(file.toString(), r, append);
        }
        final BiFunction<COutput, GenRunInfo, Writer> inner = switch (fmt) {
            case "asciiv3", "hepmc3" -> WriterAscii::new;
            case "hepmc2", "iogenevent" -> WriterAsciiHepMC2::new;
            case "hepevt" -> WriterHEPEVT::new;
            case "protobuf", "pb" -> (o, ri) -> new Writerprotobuf(o.asStream(), ri);
            default -> throw new IOException("cannot write '" + fmt + "' (asciiv3, hepmc2, hepevt, protobuf, root, rootkeys, spx)");
        };
        if (gz) return new WriterGZ(file, inner, r, Compression.Z);
        if (fmt.startsWith("proto") || fmt.equals("pb")) return new Writerprotobuf(file, r);
        return inner.apply(COutput.create(file), r);
    }

    private static String label(String fmt) {
        return switch (fmt) {
            case "asciiv3", "hepmc3" -> "Asciiv3";
            case "hepmc2", "iogenevent" -> "HepMC2";
            case "hepevt" -> "HEPEVT";
            case "protobuf", "pb" -> "protobuf";
            case "lhef", "lhe" -> "LHEF";
            case "spx" -> "SPX";
            case "root", "roottree" -> "ROOT tree";
            case "rootkeys", "rootobjects" -> "ROOT";
            default -> fmt;
        };
    }

    private static void options(Writer w, Args a) {
        if (a.has("precision")) {
            if (w instanceof WriterAscii wa) wa.setPrecision((int) a.opt("precision", 16));
            if (w instanceof WriterAsciiHepMC2 w2) w2.setPrecision((int) a.opt("precision", 16));
        }
        if (a.has("float")) {
            final Map<String, String> o = new HashMap<>(w.getOptions());
            o.put("float_printf_specifier", a.opts.get("float"));
            w.setOptions(o);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Commands: events in and out                                         */
    /* ------------------------------------------------------------------ */

    private static void help(Args a, CommandExecutionContext c) {
        AppLogger.result("The HepMC3 event record in Java (HepMC3 " + HepMC3.VERSION + " ported). The order of an analysis:");
        AppLogger.raw("  events     :hepmc read <file> | :hepmc toy [n]          then :hepmc event [i], :hepmc info");
        AppLogger.raw("             formats: Asciiv3, HepMC2, HEPEVT, LHEF, protobuf, gzip/zstd, ROOT (tree or objects, no ROOT needed), SPX (from the other engines)");
        AppLogger.raw("  look       :hepmc listing | content | particles [cut] | vertices | tree [id] | relatives <id> ancestors");
        AppLogger.raw("  search     :hepmc select status==1 && pt>10 && abs(eta)<2.5 [--all] | filter <cut> --min 2");
        AppLogger.raw("  sample     :hepmc stats | weights | xsec | attributes | runinfo | hist pt --cut final");
        AppLogger.raw("  transform  :hepmc boost bx by bz | rotate | reflect z | shift x y z t | units MEV CM   [--all]");
        AppLogger.raw("  out        :hepmc write <file> [--format hepmc2|root|rootkeys] [--gz] [--append] | convert <in> <out.root> | dot <file.dot>");
        AppLogger.raw("  engines    :hepmc tofjet (jets: :fjet jets) | bridge [julia|root] | crosscheck");
        AppLogger.raw("  trust      :hepmc validate | selftest | bench | ping | diag");
        AppLogger.raw("  ':help hepmc' gives the full usage of each; references: Citations > Citation HepMC3.");
    }

    private static void read(Args a, CommandExecutionContext c) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":hepmc read <file> [--format auto|asciiv3|hepmc2|hepevt|lhef|protobuf|root|rootkeys|spx] [--max N] [--skip N]");
            return;
        }
        final Path file = Handlers.resolve(a.plain.get(0)).toPath();
        if (!Files.isRegularFile(file)) {
            AppLogger.error("No file " + file);
            return;
        }
        final String fmt = a.has("format") ? a.opts.get("format").toLowerCase(Locale.ROOT)
            : file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".spx") ? "spx" : "auto";
        final long t0 = System.nanoTime();
        final int max = (int) a.opt("max", Integer.MAX_VALUE);
        final List<GenEvent> read = new ArrayList<>();
        GenRunInfo info;
        String what;
        if (fmt.equals("spx")) {
            final SpxHepMC.Sample s = SpxHepMC.read(file);
            for (int k = (int) a.opt("skip", 0); k < s.events().size() && read.size() < max; k++) read.add(s.events().get(k));
            info = s.runInfo();
            what = s.complete() ? "SPX, the whole HepMC3 record" : "SPX, particles only (final state)";
        } else {
            final Reader r = openReader(file, fmt);
            try {
                if (a.has("skip")) r.skip((int) a.opt("skip", 0));
                while (!r.failed() && read.size() < max) {
                    final GenEvent e = new GenEvent();
                    r.readEvent(e);
                    if (r.failed()) break;
                    read.add(e);
                }
                info = r.runInfo();
                what = formatOf(r);
                final Compression packed = Compression.detect(file);
                if (packed != Compression.PLAINTEXT && !what.contains("compressed")) {
                    what += ", " + packed.label() + " compressed";
                }
            } finally {
                r.close();
            }
        }
        EVENTS.clear();
        EVENTS.addAll(read);
        run = info;
        source = file.toString();
        format = what;
        current = 0;
        HepMC3Citations.used(what.startsWith("SPX") ? "SPX" : what.replace(", compressed", ""));
        long np = 0;
        long nv = 0;
        for (GenEvent e : read) {
            np += e.particles().size();
            nv += e.vertices().size();
        }
        final double n = Math.max(1, read.size());
        AppLogger.result(f("%d events read from %s (%s) in %.0f ms: %.1f particles and %.1f vertices per event.",
            read.size(), file.getFileName(), what, (System.nanoTime() - t0) / 1e6, np / n, nv / n));
        if (read.isEmpty()) AppLogger.warn("No event: the file is empty, or not of the format read.");
    }

    private static void write(Args a, CommandExecutionContext c) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":hepmc write <file> [--format asciiv3|hepmc2|hepevt|protobuf|root|rootkeys|spx] [--gz] [--append] [--precision p] [--float g]");
            return;
        }
        if (!haveEvents()) return;
        final Path file = Handlers.resolve(a.plain.get(0)).toPath();
        final String fmt = formatFor(file, a);
        final boolean gz = a.has("gz") || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        final long t0 = System.nanoTime();
        if (fmt.equals("spx")) {
            final Path out = SpxHepMC.export(EVENTS, run, source, file);
            AppLogger.result(f("%d events -> %s (SPX, %.1f kB)", EVENTS.size(), out, Files.size(out) / 1024.0));
            return;
        }
        final Writer w = openWriter(file, fmt, gz, run, a.has("append"));
        if (w.failed()) {
            AppLogger.error("Cannot write " + file);
            return;
        }
        options(w, a);
        for (GenEvent e : EVENTS) w.writeEvent(e);
        w.close();
        HepMC3Citations.used(label(fmt));
        AppLogger.result(f("%d events -> %s (%s%s, %.1f kB, %.0f ms)", EVENTS.size(), file, label(fmt), gz ? ", gzip" : "",
            Files.size(file) / 1024.0, (System.nanoTime() - t0) / 1e6));
    }

    private static void convert(Args a, CommandExecutionContext c) throws IOException {
        if (a.plain.size() < 2) {
            Handlers.usage(":hepmc convert <in> <out> [--from fmt] [--format fmt] [--gz] [--max N]");
            return;
        }
        final Path in = Handlers.resolve(a.plain.get(0)).toPath();
        final Path out = Handlers.resolve(a.plain.get(1)).toPath();
        final String to = formatFor(out, a);
        final boolean gz = a.has("gz") || out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        final int max = (int) a.opt("max", Integer.MAX_VALUE);
        final long t0 = System.nanoTime();
        final Reader r = openReader(in, a.has("from") ? a.opts.get("from").toLowerCase(Locale.ROOT) : "auto");
        int n = 0;
        if (to.equals("spx")) {
            final List<GenEvent> all = new ArrayList<>();
            while (!r.failed() && n < max) {
                final GenEvent e = new GenEvent();
                r.readEvent(e);
                if (r.failed()) break;
                all.add(e);
                n++;
            }
            SpxHepMC.export(all, r.runInfo(), in.toString(), out);
        } else {
            Writer w = null;
            while (!r.failed() && n < max) {
                final GenEvent e = new GenEvent();
                r.readEvent(e);
                if (r.failed()) break;
                if (w == null) {
                    // the run information is known once the header has been read
                    w = openWriter(out, to, gz, r.runInfo());
                    options(w, a);
                }
                w.writeEvent(e);
                n++;
            }
            if (w == null) w = openWriter(out, to, gz, r.runInfo());
            w.close();
        }
        final String from = formatOf(r);
        r.close();
        HepMC3Citations.used(from.replace(", compressed", ""));
        HepMC3Citations.used(label(to));
        AppLogger.result(f("%d events: %s (%s) -> %s (%s%s) in %.0f ms.", n, in.getFileName(), from, out.getFileName(),
            label(to), gz ? ", gzip" : "", (System.nanoTime() - t0) / 1e6));
    }

    /* ------------------------------------------------------------------ */
    /* Toy events                                                          */
    /* ------------------------------------------------------------------ */

    /** Two-body decay of a parent into masses m1, m2, isotropic in its rest frame. */
    private static FourVector[] twoBody(FourVector parent, double m1, double m2, Random rnd) {
        final double mass = parent.m();
        final double pstar = Math.sqrt(Math.max(0, (mass * mass - (m1 + m2) * (m1 + m2)) * (mass * mass - (m1 - m2) * (m1 - m2))))
            / (2 * mass);
        final double cos = 2 * rnd.nextDouble() - 1;
        final double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
        final double phi = 2 * Math.PI * rnd.nextDouble();
        final double px = pstar * sin * Math.cos(phi);
        final double py = pstar * sin * Math.sin(phi);
        final double pz = pstar * cos;
        final FourVector a = new FourVector(px, py, pz, Math.sqrt(pstar * pstar + m1 * m1));
        final FourVector b = new FourVector(-px, -py, -pz, Math.sqrt(pstar * pstar + m2 * m2));
        return new FourVector[]{boost(a, parent), boost(b, parent)};
    }

    /** A four-vector given in the rest frame of frame, seen in the frame frame is given in. */
    private static FourVector boost(FourVector v, FourVector frame) {
        final double bx = frame.px() / frame.e();
        final double by = frame.py() / frame.e();
        final double bz = frame.pz() / frame.e();
        final double b2 = bx * bx + by * by + bz * bz;
        if (b2 <= 0) return v.copy();
        final double gamma = 1 / Math.sqrt(1 - b2);
        final double bp = bx * v.px() + by * v.py() + bz * v.pz();
        final double g2 = (gamma - 1) / b2;
        return new FourVector(v.px() + g2 * bp * bx + gamma * bx * v.e(), v.py() + g2 * bp * by + gamma * by * v.e(),
            v.pz() + g2 * bp * bz + gamma * bz * v.e(), gamma * (v.e() + bp));
    }

    private static final double MPI = 0.13957039;
    private static final double MPI0 = 0.1349768;
    private static final double MK = 0.493677;

    /** A cluster (status 2) split in two until it is light, then into two hadrons; pi0 into two photons. */
    private static void cascade(GenEvent evt, GenParticle p, Random rnd, int depth) {
        final double mass = p.momentum().m();
        final GenVertex v = new GenVertex();
        v.addParticleIn(p);
        if (mass > 2.0 && depth < 12) {
            final double m1 = mass * (0.12 + 0.33 * rnd.nextDouble());
            final double m2 = mass * (0.12 + 0.33 * rnd.nextDouble());
            final FourVector[] d = twoBody(p.momentum(), m1, m2, rnd);
            final GenParticle c1 = new GenParticle(d[0], 91, 2);
            final GenParticle c2 = new GenParticle(d[1], 91, 2);
            v.addParticleOut(c1);
            v.addParticleOut(c2);
            evt.addVertex(v);
            cascade(evt, c1, rnd, depth + 1);
            cascade(evt, c2, rnd, depth + 1);
            return;
        }
        final double u = rnd.nextDouble();
        final int[] ids;
        final double[] ms;
        if (mass > 2 * MK + 0.05 && u < 0.15) {
            ids = new int[]{321, -321};
            ms = new double[]{MK, MK};
        } else if (u < 0.45) {
            ids = new int[]{111, 111};
            ms = new double[]{MPI0, MPI0};
        } else {
            ids = new int[]{211, -211};
            ms = new double[]{MPI, MPI};
        }
        if (mass <= ms[0] + ms[1]) {
            ids[0] = 22;
            ids[1] = 22;
            ms[0] = 0;
            ms[1] = 0;
        }
        final FourVector[] d = twoBody(p.momentum(), ms[0], ms[1], rnd);
        final GenParticle h1 = new GenParticle(d[0], ids[0], ids[0] == 111 ? 2 : 1);
        final GenParticle h2 = new GenParticle(d[1], ids[1], ids[1] == 111 ? 2 : 1);
        if (ms[0] > 0) h1.setGeneratedMass(ms[0]);
        if (ms[1] > 0) h2.setGeneratedMass(ms[1]);
        v.addParticleOut(h1);
        v.addParticleOut(h2);
        evt.addVertex(v);
        for (GenParticle h : new GenParticle[]{h1, h2}) {
            if (h.pid() != 111) continue;
            final FourVector[] g = twoBody(h.momentum(), 0, 0, rnd);
            final GenVertex dv = new GenVertex();
            dv.addParticleIn(h);
            dv.addParticleOut(new GenParticle(g[0], 22, 1));
            dv.addParticleOut(new GenParticle(g[1], 22, 1));
            evt.addVertex(dv);
        }
    }

    /** One toy event: e+ e- -> Z -> q qbar, each quark a cluster cascading into hadrons. */
    static GenEvent toyEvent(Random rnd, int number, double sqrts, GenRunInfo info) {
        final GenEvent evt = new GenEvent(Units.MomentumUnit.GEV, Units.LengthUnit.MM);
        evt.setRunInfo(info);
        evt.setEventNumber(number);
        final double half = sqrts / 2;
        final GenParticle em = new GenParticle(new FourVector(0, 0, half, half), 11, 4);
        final GenParticle ep = new GenParticle(new FourVector(0, 0, -half, half), -11, 4);
        final GenParticle z = new GenParticle(new FourVector(0, 0, 0, sqrts), 23, 2);
        z.setGeneratedMass(sqrts);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(em);
        v1.addParticleIn(ep);
        v1.addParticleOut(z);
        evt.addVertex(v1);
        final int[] flavours = {1, 2, 3, 4, 5};
        final double[] masses = {0.33, 0.33, 0.5, 1.5, 4.8};
        final int q = rnd.nextInt(flavours.length);
        final FourVector[] qq = twoBody(z.momentum(), masses[q], masses[q], rnd);
        final GenParticle quark = new GenParticle(qq[0], flavours[q], 2);
        final GenParticle anti = new GenParticle(qq[1], -flavours[q], 2);
        quark.setGeneratedMass(masses[q]);
        anti.setGeneratedMass(masses[q]);
        final GenVertex v2 = new GenVertex();
        v2.addParticleIn(z);
        v2.addParticleOut(quark);
        v2.addParticleOut(anti);
        evt.addVertex(v2);
        // each quark is dressed into a cluster carrying a share of the energy left
        for (GenParticle parton : new GenParticle[]{quark, anti}) {
            final double m = Math.min(parton.momentum().e() * 0.6, 3 + 12 * rnd.nextDouble());
            final FourVector p = parton.momentum();
            final double pp = Math.sqrt(Math.max(0, p.e() * p.e() - m * m));
            final double scale = pp / p.p3mod();
            final GenParticle cluster = new GenParticle(new FourVector(p.px() * scale, p.py() * scale, p.pz() * scale, p.e()), 91, 2);
            final GenVertex vc = new GenVertex();
            vc.addParticleIn(parton);
            vc.addParticleOut(cluster);
            evt.addVertex(vc);
            cascade(evt, cluster, rnd, 0);
        }
        // the run information names one weight: setRunInfo made it, 1
        if (evt.weights().isEmpty()) evt.weights().add(1.0);
        final GenCrossSection cs = new GenCrossSection();
        cs.setCrossSection(30340.0, 0.0, number + 1, number + 1);
        evt.setCrossSection(cs);
        evt.addAttribute("signal_process_id", new IntAttribute(23));
        return evt;
    }

    private static void toy(Args a, CommandExecutionContext c) {
        final int n = (int) a.num(0, 100);
        final long seed = (long) a.opt("seed", 1);
        final double sqrts = a.opt("sqrts", 91.1876);
        final Random rnd = new Random(seed);
        final GenRunInfo info = new GenRunInfo();
        info.tools().add(new GenRunInfo.ToolInfo("Sphere toy", HepMC3.JAVA_PORT_VERSION, "e+e- -> Z -> q qbar -> clusters -> hadrons"));
        info.setWeightNames(List.of("Default"));
        EVENTS.clear();
        for (int k = 0; k < n; k++) EVENTS.add(toyEvent(rnd, k, sqrts, info));
        run = info;
        source = "toy";
        format = "Sphere toy generator";
        current = 0;
        long np = 0;
        for (GenEvent e : EVENTS) np += e.particles().size();
        AppLogger.result(f("%d toy events at sqrt(s) = %g GeV, seed %d: %.1f particles per event, every vertex conserving momentum.",
            n, sqrts, seed, np / Math.max(1.0, n)));
    }

    /* ------------------------------------------------------------------ */
    /* Commands: looking at events                                         */
    /* ------------------------------------------------------------------ */

    private static void info(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        AppLogger.result(f("%d events from %s (%s); current event %d.", EVENTS.size(), source, format, current));
        final GenEvent e = EVENTS.get(current);
        AppLogger.raw(f("  units        %s %s", Units.name(e.momentumUnit()), Units.name(e.lengthUnit())));
        if (run != null) {
            AppLogger.raw(f("  weights      %d: %s", run.weightNames().size(), String.join(", ", run.weightNames())));
            for (GenRunInfo.ToolInfo t : run.tools()) AppLogger.raw(f("  tool         %s %s %s", t.name, t.version, t.description));
            if (!run.attributeNames().isEmpty()) AppLogger.raw("  run attributes " + String.join(", ", run.attributeNames()));
        } else {
            AppLogger.raw("  run info     none");
        }
    }

    private static void runinfo(Args a, CommandExecutionContext c) {
        if (run == null) {
            AppLogger.error("The sample has no run information.");
            return;
        }
        final COStream os = Print.cout();
        Print.listing(os, run, 2);
    }

    private static void event(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 0);
        if (e == null) return;
        if (!a.plain.isEmpty()) current = EVENTS.indexOf(e);
        AppLogger.result("Event " + current + " of " + EVENTS.size() + ":");
        Print.printLine(e, true);
        final GenCrossSection cs = SpxHepMC.crossSectionOf(e);
        if (cs != null) Print.printLine(cs);
        final GenPdfInfo pi = SpxHepMC.pdfInfoOf(e);
        if (pi != null) Print.printLine(pi);
        if (e.heavyIon() != null) Print.printLine(e.heavyIon());
    }

    private static void listing(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 0);
        if (e == null) return;
        Print.listing(e, (int) a.opt("precision", 2));
    }

    private static void content(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 0);
        if (e == null) return;
        Print.content(e);
    }

    private static void particleTable(List<GenParticle> ps, int max) {
        AppLogger.raw(f("%6s %-10s %6s %12s %12s %12s %12s %10s %9s %7s %5s %5s", "id", "particle", "status", "px", "py", "pz",
            "E", "pt", "eta", "phi", "prod", "end"));
        int shown = 0;
        for (GenParticle p : ps) {
            if (shown++ >= max) {
                AppLogger.raw(f("  ... %d more (--max n shows more)", ps.size() - max));
                break;
            }
            final FourVector m = p.momentum();
            final GenVertex pv = p.productionVertex();
            final GenVertex ev = p.endVertex();
            final double eta = m.pt() == 0 ? (m.pz() >= 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY) : m.eta();
            AppLogger.raw(f("%6d %-10s %6d %12.5g %12.5g %12.5g %12.5g %10.4g %9.4f %7.4f %5s %5s", p.id(), pdgName(p.pid()),
                p.status(), m.px(), m.py(), m.pz(), m.e(), m.pt(), eta, m.phi(),
                pv == null || pv.id() == 0 ? "-" : Integer.toString(pv.id()), ev == null ? "-" : Integer.toString(ev.id())));
        }
    }

    private static void particles(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 99);
        if (e == null) return;
        final Filter filter = CutExpression.parse(a.rest(0));
        final List<GenParticle> ps = cut(e, filter);
        AppLogger.result(f("Event %d: %d of %d particles%s.", current, ps.size(), e.particles().size(),
            a.plain.isEmpty() ? "" : " pass " + a.rest(0)));
        particleTable(ps, (int) a.opt("max", 200));
    }

    private static void vertices(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 99);
        if (e == null) return;
        AppLogger.result(f("Event %d: %d vertices.", current, e.vertices().size()));
        AppLogger.raw(f("%6s %6s %4s %4s %13s %13s %13s %13s   %s", "id", "status", "in", "out", "x", "y", "z", "t", "in -> out"));
        int shown = 0;
        for (GenVertex v : e.vertices()) {
            if (shown++ >= (int) a.opt("max", 200)) {
                AppLogger.raw("  ... (--max n shows more)");
                break;
            }
            final FourVector x = v.position();
            final List<String> in = new ArrayList<>();
            final List<String> out = new ArrayList<>();
            for (GenParticle p : v.particlesIn()) in.add(pdgName(p.pid()) + "[" + p.id() + "]");
            for (GenParticle p : v.particlesOut()) out.add(pdgName(p.pid()) + "[" + p.id() + "]");
            AppLogger.raw(f("%6d %6d %4d %4d %13.6g %13.6g %13.6g %13.6g   %s -> %s", v.id(), v.status(), in.size(), out.size(),
                x.x(), x.y(), x.z(), x.t(), String.join(" ", in), String.join(" ", out)));
        }
    }

    private static GenParticle particle(GenEvent e, String word) {
        try {
            final int id = Integer.parseInt(word);
            if (id >= 1 && id <= e.particles().size()) return e.particles().get(id - 1);
        } catch (NumberFormatException ignored) {
            // said below
        }
        AppLogger.error("No particle " + word + " in this event (ids 1 to " + e.particles().size() + ").");
        return null;
    }

    private static void treeLine(GenParticle p, String indent, int depth, int maxDepth, IdentityHashMap<GenVertex, Boolean> seen) {
        final FourVector m = p.momentum();
        AppLogger.raw(f("%s%s [%d] status %d, E %.4g, pt %.4g, m %.4g", indent, pdgName(p.pid()), p.id(), p.status(), m.e(),
            m.pt(), m.m()));
        final GenVertex end = p.endVertex();
        if (end == null) return;
        if (depth >= maxDepth) {
            AppLogger.raw(indent + "    ... (--depth shows deeper)");
            return;
        }
        if (seen.put(end, Boolean.TRUE) != null) {
            AppLogger.raw(indent + "    (vertex " + end.id() + " shown above)");
            return;
        }
        for (GenParticle d : end.particlesOut()) treeLine(d, indent + "    ", depth + 1, maxDepth, seen);
    }

    private static void tree(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 99);
        if (e == null) return;
        final int depth = (int) a.opt("depth", 12);
        final IdentityHashMap<GenVertex, Boolean> seen = new IdentityHashMap<>();
        if (!a.plain.isEmpty()) {
            final GenParticle p = particle(e, a.plain.get(0));
            if (p != null) treeLine(p, "", 0, depth, seen);
            return;
        }
        final List<GenParticle> roots = new ArrayList<>();
        for (GenParticle p : e.particles()) {
            final GenVertex pv = p.productionVertex();
            if (pv == null || pv.id() == 0) roots.add(p);
        }
        AppLogger.result(f("Event %d, from its %d particle(s) without a parent:", current, roots.size()));
        for (GenParticle p : roots) treeLine(p, "", 0, depth, seen);
    }

    private static void relatives(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 99);
        if (e == null) return;
        if (a.plain.size() < 2) {
            Handlers.usage(":hepmc relatives <particle id> <parents|children|ancestors|descendants>");
            return;
        }
        final GenParticle p = particle(e, a.plain.get(0));
        if (p == null) return;
        final Relatives r = switch (a.plain.get(1).toLowerCase(Locale.ROOT)) {
            case "parents" -> Relatives.PARENTS;
            case "children" -> Relatives.CHILDREN;
            case "ancestors" -> Relatives.ANCESTORS;
            case "descendants" -> Relatives.DESCENDANTS;
            default -> null;
        };
        if (r == null) {
            AppLogger.error("parents, children, ancestors or descendants");
            return;
        }
        final List<GenParticle> found = r.apply(p);
        AppLogger.result(f("%d %s of %s [%d]:", found.size(), a.plain.get(1), pdgName(p.pid()), p.id()));
        particleTable(found, (int) a.opt("max", 200));
    }

    private static void select(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Filter filter;
        try {
            filter = CutExpression.parse(a.rest(0));
        } catch (IllegalArgumentException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        if (a.has("all")) {
            long passed = 0;
            long total = 0;
            int events = 0;
            for (GenEvent e : EVENTS) {
                final int n = cut(e, filter).size();
                passed += n;
                total += e.particles().size();
                if (n > 0) events++;
            }
            AppLogger.result(f("%s: %d of %d particles (%.2f per event); %d of %d events have one or more.", a.rest(0), passed,
                total, passed / (double) EVENTS.size(), events, EVENTS.size()));
            return;
        }
        final GenEvent e = EVENTS.get(current);
        final List<GenParticle> ps = cut(e, filter);
        AppLogger.result(f("Event %d: %d of %d particles pass %s.", current, ps.size(), e.particles().size(), a.rest(0)));
        particleTable(ps, (int) a.opt("max", 200));
    }

    private static void filter(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        if (a.plain.isEmpty()) {
            Handlers.usage(":hepmc filter <cut> [--min n]");
            return;
        }
        final Filter filter = CutExpression.parse(a.rest(0));
        final int min = (int) a.opt("min", 1);
        final int before = EVENTS.size();
        EVENTS.removeIf(e -> cut(e, filter).size() < min);
        current = 0;
        AppLogger.result(f("%d of %d events have at least %d particle(s) passing %s; the others are dropped.", EVENTS.size(),
            before, min, a.rest(0)));
    }

    private static void stats(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final TreeMap<Integer, Long> statuses = new TreeMap<>();
        final Map<Integer, Long> finals = new HashMap<>();
        long np = 0;
        long nv = 0;
        long nf = 0;
        double sumw = 0;
        double sumw2 = 0;
        double worst = 0;
        for (GenEvent e : EVENTS) {
            np += e.particles().size();
            nv += e.vertices().size();
            for (GenParticle p : e.particles()) {
                statuses.merge(p.status(), 1L, Long::sum);
                if (p.status() == 1) {
                    nf++;
                    finals.merge(p.pid(), 1L, Long::sum);
                }
            }
            final double w = e.weights().isEmpty() ? 1.0 : e.weights().get(0);
            sumw += w;
            sumw2 += w * w;
            for (GenVertex v : e.vertices()) {
                if (v.particlesIn().isEmpty() || v.particlesOut().isEmpty()) continue;
                // a beam turning into a parton does not balance, by design
                if (v.particlesIn().stream().anyMatch(p -> p.status() == 4)) continue;
                double ein = 0;
                double eout = 0;
                for (GenParticle p : v.particlesIn()) ein += p.momentum().e();
                for (GenParticle p : v.particlesOut()) eout += p.momentum().e();
                if (ein > 0) worst = Math.max(worst, Math.abs(ein - eout) / ein);
            }
        }
        final double n = EVENTS.size();
        AppLogger.result(f("%d events from %s:", EVENTS.size(), source));
        AppLogger.raw(f("  per event      %.2f particles, %.2f vertices, %.2f in the final state (status 1)", np / n, nv / n, nf / n));
        final List<String> st = new ArrayList<>();
        for (Map.Entry<Integer, Long> s : statuses.entrySet()) st.add(s.getKey() + ": " + s.getValue());
        AppLogger.raw("  statuses       " + String.join(", ", st));
        final List<Map.Entry<Integer, Long>> species = new ArrayList<>(finals.entrySet());
        species.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        final List<String> sp = new ArrayList<>();
        for (int k = 0; k < Math.min(12, species.size()); k++) {
            sp.add(f("%s %.2f", pdgName(species.get(k).getKey()), species.get(k).getValue() / n));
        }
        AppLogger.raw("  final state    " + String.join(", ", sp) + " (per event)");
        AppLogger.raw(f("  weights        sum %.6g, sum of squares %.6g, effective events %.1f", sumw, sumw2,
            sumw2 > 0 ? sumw * sumw / sumw2 : 0));
        final GenCrossSection cs = SpxHepMC.crossSectionOf(EVENTS.get(EVENTS.size() - 1));
        if (cs != null) AppLogger.raw(f("  cross-section  %.6g +- %.3g pb (the last event's)", cs.xsec(), cs.xsecErr()));
        final List<GenParticle> beams = EVENTS.get(0).beams();
        if (beams.size() == 2) {
            final FourVector s = beams.get(0).momentum().plus(beams.get(1).momentum());
            AppLogger.raw(f("  beams          %s %s, sqrt(s) = %.6g %s", pdgName(beams.get(0).pid()), pdgName(beams.get(1).pid()),
                s.m(), Units.name(EVENTS.get(0).momentumUnit())));
        }
        AppLogger.raw(f("  energy balance worst relative imbalance at a vertex (beams aside): %.2e", worst));
    }

    private static void weights(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 0);
        if (e == null) return;
        final List<String> names = run == null ? List.of() : run.weightNames();
        AppLogger.result(f("Event %d: %d weight(s).", EVENTS.indexOf(e), e.weights().size()));
        for (int k = 0; k < e.weights().size(); k++) {
            AppLogger.raw(f("  %3d %-30s %.10g", k, k < names.size() ? names.get(k) : "", e.weights().get(k)));
        }
    }

    private static void xsec(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final GenCrossSection first = SpxHepMC.crossSectionOf(EVENTS.get(0));
        final GenCrossSection last = SpxHepMC.crossSectionOf(EVENTS.get(EVENTS.size() - 1));
        if (first == null && last == null) {
            AppLogger.warn("The events carry no GenCrossSection.");
            return;
        }
        if (first != null) AppLogger.raw("  first event  " + Print.line(first));
        if (last != null) AppLogger.raw("  last event   " + Print.line(last));
        if (last != null) AppLogger.result(f("sigma = %.6g +- %.3g pb (%d accepted of %d attempted)", last.xsec(), last.xsecErr(),
            last.getAcceptedEvents(), last.getAttemptedEvents()));
    }

    private static void attributes(Args a, CommandExecutionContext c) {
        final GenEvent e = event(a, 0);
        if (e == null) return;
        int n = 0;
        AppLogger.result(f("Event %d: attributes by name, then by id (0 the event, > 0 a particle, < 0 a vertex):", EVENTS.indexOf(e)));
        for (Map.Entry<String, TreeMap<Integer, Attribute>> byName : e.attributes().entrySet()) {
            for (Map.Entry<Integer, Attribute> byId : byName.getValue().entrySet()) {
                if (n++ >= (int) a.opt("max", 100)) {
                    AppLogger.raw("  ... (--max n shows more)");
                    return;
                }
                final String text = byId.getValue().serialize();
                AppLogger.raw(f("  %-24s %6d  %s", byName.getKey(), byId.getKey(), text == null ? "(unserialisable)" : text));
            }
        }
        if (n == 0) AppLogger.raw("  none");
    }

    /* ------------------------------------------------------------------ */
    /* Commands: transformations                                           */
    /* ------------------------------------------------------------------ */

    private static void boost(Args a, CommandExecutionContext c) {
        if (a.plain.size() < 3) {
            Handlers.usage(":hepmc boost <bx> <by> <bz> [--all]");
            return;
        }
        final FourVector beta = new FourVector(a.num(0, 0), a.num(1, 0), a.num(2, 0), 0);
        int done = 0;
        for (GenEvent e : targets(a)) if (e.boost(beta)) done++;
        AppLogger.result(f("%d event(s) boosted by (%g, %g, %g).", done, beta.x(), beta.y(), beta.z()));
    }

    private static void rotate(Args a, CommandExecutionContext c) {
        if (a.plain.size() < 3) {
            Handlers.usage(":hepmc rotate <ax> <ay> <az> [--all]");
            return;
        }
        final FourVector angles = new FourVector(a.num(0, 0), a.num(1, 0), a.num(2, 0), 0);
        int done = 0;
        for (GenEvent e : targets(a)) if (e.rotate(angles)) done++;
        AppLogger.result(f("%d event(s) rotated by (%g, %g, %g) rad.", done, angles.x(), angles.y(), angles.z()));
    }

    private static void reflect(Args a, CommandExecutionContext c) {
        final int axis = switch (a.word(0, "").toLowerCase(Locale.ROOT)) {
            case "x", "0" -> 0;
            case "y", "1" -> 1;
            case "z", "2" -> 2;
            case "t", "3" -> 3;
            default -> -1;
        };
        if (axis < 0) {
            Handlers.usage(":hepmc reflect <x|y|z|t> [--all]");
            return;
        }
        int done = 0;
        for (GenEvent e : targets(a)) if (e.reflect(axis)) done++;
        AppLogger.result(done + " event(s) reflected along " + a.word(0, "") + ".");
    }

    private static void shift(Args a, CommandExecutionContext c) {
        if (a.plain.size() < 4) {
            Handlers.usage(":hepmc shift <x> <y> <z> <t> [--all]");
            return;
        }
        final FourVector d = new FourVector(a.num(0, 0), a.num(1, 0), a.num(2, 0), a.num(3, 0));
        final List<GenEvent> t = targets(a);
        for (GenEvent e : t) e.shiftPositionBy(d);
        AppLogger.result(t.size() + " event(s) shifted.");
    }

    private static void units(Args a, CommandExecutionContext c) {
        if (a.plain.size() < 2) {
            Handlers.usage(":hepmc units <GEV|MEV> <MM|CM> [--all]");
            return;
        }
        final Units.MomentumUnit mu = Units.momentumUnit(a.plain.get(0).toUpperCase(Locale.ROOT));
        final Units.LengthUnit lu = Units.lengthUnit(a.plain.get(1).toUpperCase(Locale.ROOT));
        final List<GenEvent> t = targets(a);
        for (GenEvent e : t) e.setUnits(mu, lu);
        AppLogger.result(t.size() + " event(s) now in " + Units.name(mu) + " " + Units.name(lu) + ".");
    }

    /* ------------------------------------------------------------------ */
    /* Commands: plots and files                                           */
    /* ------------------------------------------------------------------ */

    private static void hist(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final String what = a.word(0, "pt").toLowerCase(Locale.ROOT);
        final Filter filter = CutExpression.parse(a.has("cut") ? a.opts.get("cut") : "final");
        final List<Double> values = new ArrayList<>();
        for (GenEvent e : EVENTS) {
            final List<GenParticle> ps = cut(e, filter);
            if (what.equals("mult")) {
                values.add((double) ps.size());
                continue;
            }
            for (GenParticle p : ps) {
                final FourVector m = p.momentum();
                final double v = switch (what) {
                    case "pt" -> m.pt();
                    case "eta" -> m.pt() == 0 ? Double.NaN : m.eta();
                    case "rap", "y" -> m.rap();
                    case "phi" -> m.phi();
                    case "e" -> m.e();
                    case "m" -> m.m();
                    case "pid" -> p.pid();
                    case "status" -> p.status();
                    default -> Double.NaN;
                };
                if (Double.isFinite(v)) values.add(v);
            }
        }
        if (values.isEmpty()) {
            AppLogger.warn("Nothing to histogram: no particle passes the cut, or '" + what + "' is not pt, eta, rap, phi, e, m, pid, status or mult.");
            return;
        }
        final double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        AppLogger.result(f("%s of the particles passing %s: %d entries, mean %.6g", what, a.has("cut") ? a.opts.get("cut") : "final",
            v.length, mean));
        RootPlotsPanel.instance().showColumn("hepmc " + what, v);
    }

    private static void dot(Args a, CommandExecutionContext c) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":hepmc dot <file.dot> [index]");
            return;
        }
        final GenEvent e = event(a, 1);
        if (e == null) return;
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("digraph event_" + e.eventNumber() + " {");
            w.println("  rankdir=LR; node [shape=point]; edge [fontsize=9];");
            for (GenVertex v : e.vertices()) w.println("  v" + (-v.id()) + " [shape=circle, label=\"" + v.id() + "\", fontsize=8];");
            for (GenParticle p : e.particles()) {
                final GenVertex pv = p.productionVertex();
                final GenVertex ev = p.endVertex();
                final String from = pv == null || pv.id() == 0 ? "in" + p.id() : "v" + (-pv.id());
                final String to = ev == null ? "out" + p.id() : "v" + (-ev.id());
                if (pv == null || pv.id() == 0) w.println("  " + from + ";");
                if (ev == null) w.println("  " + to + ";");
                final String style = p.status() == 1 ? "" : ", style=dashed";
                w.println(f("  %s -> %s [label=\"%s %.3g\"%s];", from, to, pdgName(p.pid()).replace("\"", "'"), p.momentum().e(), style));
            }
            w.println("}");
        }
        AppLogger.result("Event " + EVENTS.indexOf(e) + " -> " + out + " (dot -Tsvg " + out.getFileName() + " -o event.svg)");
    }

    /* ------------------------------------------------------------------ */
    /* Commands: the other engines                                         */
    /* ------------------------------------------------------------------ */

    private static void tofjet(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final Filter filter = CutExpression.parse(a.has("cut") ? a.opts.get("cut") : "final");
        final List<List<PseudoJet>> events = new ArrayList<>(EVENTS.size());
        final List<Double> weights = new ArrayList<>(EVENTS.size());
        final List<EventIO.Incoming> incoming = new ArrayList<>(EVENTS.size());
        long n = 0;
        for (GenEvent e : EVENTS) {
            final double scale = e.momentumUnit() == Units.MomentumUnit.MEV ? 1e-3 : 1.0;
            final List<PseudoJet> ps = new ArrayList<>();
            for (GenParticle p : cut(e, filter)) {
                final FourVector m = p.momentum();
                ps.add(new PseudoJet(m.px() * scale, m.py() * scale, m.pz() * scale, m.e() * scale).setUserIndex(p.pid()));
            }
            n += ps.size();
            events.add(ps);
            weights.add(e.weights().isEmpty() ? 1.0 : e.weights().get(0));
            final GenPdfInfo pi = SpxHepMC.pdfInfoOf(e);
            incoming.add(pi == null ? null : new EventIO.Incoming(pi.partonId[0], pi.partonId[1], pi.x[0], pi.x[1], pi.scale * scale));
        }
        FastJetCommands.adoptEvents(events, weights, incoming, "HepMC3: " + source);
        AppLogger.result(f("%d events, %.1f particles each (%s, in GeV), handed to :fjet: ':fjet jets' clusters the first.",
            events.size(), n / (double) Math.max(1, events.size()), a.has("cut") ? a.opts.get("cut") : "final state"));
    }

    /** Writes the events for the other engines; answers the file. */
    private static Path exportForEngines(String name) throws IOException {
        final Path out = SpxHepMC.export(EVENTS, run, source, Bridge.folder().resolve(SpxExport.safeName(name) + ".spx"));
        AppLogger.result(f("%d events -> %s (%.1f kB)", EVENTS.size(), out, Files.size(out) / 1024.0));
        return out;
    }

    private static void bridge(Args a, CommandExecutionContext c) throws IOException {
        if (!haveEvents()) return;
        final String name = a.as != null && a.plain.isEmpty() ? a.as : "hepmc";
        final String engine = a.word(0, "").toLowerCase(Locale.ROOT);
        final Path out = exportForEngines(engine.isEmpty() ? name : "hepmc");
        final String stem = out.getFileName().toString().replace(".spx", "");
        switch (engine) {
            case "julia" -> BridgeCommands.julia(":bridge julia " + stem + " as " + (a.as != null ? a.as : "hepmc"), c);
            case "root" -> BridgeCommands.root(":bridge root " + stem + " as " + (a.as != null ? a.as : "hepmc"), c);
            case "" -> {
                AppLogger.raw("  Python   import sphere_spx as spx; ev = spx.events(\"" + stem + "\"); ev.final_state(0); ev.to_pyhepmc(0)");
                AppLogger.raw("  Julia    ev = SphereSPX.events(\"" + stem + "\"); finalstate(ev, 1); vertices(ev, 1)   (':hepmc bridge julia')");
                AppLogger.raw("  C++      auto ev = spx::Events::open(\"" + stem + "\"); ev.status(0, i); ev.vertex(0, k);");
                AppLogger.raw("           with HepMC3's headers first: spx::hepmc3::fill(ev, e, genEvent) -> a HepMC3::GenEvent");
                AppLogger.raw("  ROOT     spx::root::hepmcTree(spx::Events::open(\"" + stem + "\"))   (':hepmc bridge root')");
                AppLogger.raw("  Fortran  call spx_open_events(ev, '" + stem + "'); call spx_hepevt(ev, 0)   -> COMMON /HEPEVT/");
                AppLogger.raw("  Java     :hepmc read " + out.getFileName() + "   (the same events, ids and attributes included)");
            }
            default -> AppLogger.error("julia or root (or nothing: the file, and how each engine reads it).");
        }
    }

    private static void crosscheck(Args a, CommandExecutionContext c) throws IOException {
        if (!haveEvents()) return;
        final List<String> engines = a.has("engines")
            ? Arrays.asList(a.opts.get("engines").toLowerCase(Locale.ROOT).split(","))
            : BridgeHepMCCheck.ENGINES;
        final Path file = exportForEngines("hepmc");
        final RootBackend root = RootBackend.getInstance();
        final Function<String, String> cling = root != null && root.isAvailable()
            ? expression -> root.executeClingAwait(expression, 60000) : null;
        final InProcessEngine.Native cpp = InProcessEngine.HEPMC3.findNative();
        AppLogger.info("Every engine reads " + file.getFileName() + " back: " + String.join(", ", engines) + "...");
        final List<BridgeHepMCCheck.Verdict> verdicts = BridgeHepMCCheck.run(file, EVENTS, run, cpp == null ? null : cpp.prefix(),
            engines, new SettingsManager(), cling);
        boolean all = true;
        for (Map.Entry<String, String> line : BridgeHepMCCheck.describe(verdicts).entrySet()) {
            AppLogger.raw(f("  %-8s %s", line.getKey(), line.getValue()));
        }
        for (BridgeHepMCCheck.Verdict v : verdicts) if (v.ran() && v.identical() != v.events()) all = false;
        if (all) AppLogger.success("Every engine that ran reads the events Java wrote, bit for bit.");
        else AppLogger.warn("An engine reads something else than Java wrote: see the first difference above.");
    }

    /* ------------------------------------------------------------------ */
    /* Commands: trust                                                     */
    /* ------------------------------------------------------------------ */

    private static void validate(Args a, CommandExecutionContext c) {
        final List<TestProgram> todo = Tests.matching(a.word(0, ""));
        if (todo.isEmpty()) {
            AppLogger.error("No test matches '" + a.word(0, "") + "'.");
            return;
        }
        if (!References.available()) {
            AppLogger.error("The C++ outputs of HepMC3's tests are not in the jar (refdata/hepmc3-tests.zip); "
                + "-Dhepmc3.refdir=<folder with refs/ and inputs/> points to them.");
            return;
        }
        AppLogger.info(f("Running %d of HepMC3 %s's test programs, ported, against the C++ outputs:", todo.size(), HepMC3.VERSION));
        int ok = 0;
        final long t0 = System.nanoTime();
        for (TestProgram t : todo) {
            final Validator.Outcome r = Validator.run(t);
            if (r.passed()) ok++;
            for (String line : Tests.line(r).split("\n")) AppLogger.raw("  " + line);
        }
        final String summary = f("%d of %d agree with the C++ (exit code, cout, cerr and every file written), in %.1f s.", ok,
            todo.size(), (System.nanoTime() - t0) / 1e9);
        if (ok == todo.size()) AppLogger.success(summary);
        else AppLogger.warn(summary);
    }

    private static String asciiv3(List<GenEvent> events, GenRunInfo r) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final WriterAscii w = new WriterAscii(out, r);
        for (GenEvent e : events) w.writeEvent(e);
        w.close();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static List<GenEvent> readAll(Reader r) {
        final List<GenEvent> out = new ArrayList<>();
        while (!r.failed()) {
            final GenEvent e = new GenEvent();
            r.readEvent(e);
            if (r.failed()) break;
            out.add(e);
        }
        r.close();
        return out;
    }

    private static void selftest(Args a, CommandExecutionContext c) throws IOException {
        final List<GenEvent> events;
        final GenRunInfo r;
        if (EVENTS.isEmpty()) {
            final GenRunInfo info = new GenRunInfo();
            info.setWeightNames(List.of("Default"));
            final Random rnd = new Random(1);
            events = new ArrayList<>();
            for (int k = 0; k < 20; k++) events.add(toyEvent(rnd, k, 91.1876, info));
            r = info;
            AppLogger.info("No events loaded: 20 toy events are used.");
        } else {
            events = EVENTS;
            r = run;
        }
        int failures = 0;
        // every text is written as a writer given no run information writes it: with the first event's
        final String reference = asciiv3(events, null);
        final byte[] refBytes = reference.getBytes(StandardCharsets.ISO_8859_1);

        final List<GenEvent> v3 = readAll(new ReaderAscii(new ByteArrayInputStream(refBytes)));
        failures += report(asciiv3(v3, null).equals(reference), f("Asciiv3: %d events written and read back to the character", v3.size()));

        // HepMC2 numbers vertices by barcode, and HepMC3 reads them back in the order it builds
        // the event: a graph not in HepMC2's own order is renumbered once, then stays as it is
        final byte[] pass1 = hepmc2(events);
        final List<GenEvent> h2 = readAll(new ReaderAsciiHepMC2(new ByteArrayInputStream(pass1)));
        final byte[] pass2 = hepmc2(h2);
        if (Arrays.equals(pass1, pass2)) {
            failures += report(true, f("HepMC2: %d events written, read and written again to the character", h2.size()));
        } else {
            final byte[] pass3 = hepmc2(readAll(new ReaderAsciiHepMC2(new ByteArrayInputStream(pass2))));
            failures += report(Arrays.equals(pass2, pass3) && h2.size() == events.size(), f("HepMC2: %d events written and "
                + "read back; the vertices renumbered in HepMC2's order, the same from the second pass on", h2.size()));
        }

        final ByteArrayOutputStream pb = new ByteArrayOutputStream();
        final Writerprotobuf wp = new Writerprotobuf(pb, r);
        for (GenEvent e : events) wp.writeEvent(e);
        wp.close();
        final List<GenEvent> fromPb = readAll(new Readerprotobuf(new ByteArrayInputStream(pb.toByteArray())));
        failures += report(asciiv3(fromPb, null).equals(reference),
            f("protobuf: %d events (%.1f kB) read back as the same events", fromPb.size(), pb.size() / 1024.0));

        final ByteArrayOutputStream gz = new ByteArrayOutputStream();
        final WriterGZ wg = new WriterGZ(gz, WriterAscii::new, r, Compression.Z);
        for (GenEvent e : events) wg.writeEvent(e);
        wg.close();
        final List<GenEvent> fromGz = readAll(new ReaderGZ(new ByteArrayInputStream(gz.toByteArray()), ReaderAscii::new));
        failures += report(asciiv3(fromGz, null).equals(reference),
            f("gzip: %d events (%.1f kB, %.0f%% of the text) read back as the same events", fromGz.size(), gz.size() / 1024.0,
                100.0 * gz.size() / Math.max(1, refBytes.length)));

        final Path spx = Files.createTempFile("hepmc-selftest", ".spx");
        try {
            SpxHepMC.export(events, r, "selftest", spx);
            final SpxHepMC.Sample s = SpxHepMC.read(spx);
            failures += report(asciiv3(s.events(), null).equals(reference),
                f("SPX (the bridge to the engines): %d events read back as the same events", s.events().size()));
        } finally {
            Files.deleteIfExists(spx);
        }

        final Path rootTree = Files.createTempFile("hepmc-selftest", ".root");
        final Path rootKeys = Files.createTempFile("hepmc-selftest-keys", ".root");
        try {
            final WriterRootTree wt = new WriterRootTree(rootTree.toString(), r);
            for (GenEvent e : events) wt.writeEvent(e);
            wt.close();
            final List<GenEvent> fromTree = readAll(new ReaderRootTree(rootTree.toString()));
            failures += report(asciiv3(fromTree, null).equals(reference),
                f("ROOT tree (Sphere's own ROOT writer and reader): %d events (%.1f kB) read back as the same events",
                    fromTree.size(), Files.size(rootTree) / 1024.0));
            final WriterRoot wk = new WriterRoot(rootKeys.toString(), r);
            for (GenEvent e : events) wk.writeEvent(e);
            wk.close();
            final List<GenEvent> fromKeys = readAll(new ReaderRoot(rootKeys.toString()));
            failures += report(asciiv3(fromKeys, null).equals(reference),
                f("ROOT objects: %d events (%.1f kB) read back as the same events", fromKeys.size(), Files.size(rootKeys) / 1024.0));
        } finally {
            Files.deleteIfExists(rootTree);
            Files.deleteIfExists(rootKeys);
        }

        int hepevtSame = 0;
        int hepevtTried = 0;
        for (GenEvent e : events) {
            if (e.particles().size() > HEPEVT.NMXHEP) continue;
            hepevtTried++;
            final HEPEVT block = new HEPEVT();
            block.fromGenEvent(e);
            final GenEvent back = new GenEvent();
            block.toGenEvent(back);
            if (back.particles().size() == e.particles().size() && sameFinalState(e, back)) hepevtSame++;
        }
        failures += report(hepevtSame == hepevtTried,
            f("HEPEVT common block: %d of %d events keep their particles and final-state momentum", hepevtSame, hepevtTried));
        AppLogger.result(failures == 0 ? "All checks passed." : failures + " check(s) failed.");
    }

    /** The events in HepMC2, with the first event's run information, as a writer given none does. */
    private static byte[] hepmc2(List<GenEvent> events) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final WriterAsciiHepMC2 w = new WriterAsciiHepMC2(out, null);
        for (GenEvent e : events) w.writeEvent(e);
        w.close();
        return out.toByteArray();
    }

    private static boolean sameFinalState(GenEvent a, GenEvent b) {
        final double[] x = new double[4];
        final double[] y = new double[4];
        for (GenParticle p : a.particles()) if (p.status() == 1) add(x, p.momentum());
        for (GenParticle p : b.particles()) if (p.status() == 1) add(y, p.momentum());
        for (int k = 0; k < 4; k++) if (Math.abs(x[k] - y[k]) > 1e-9 * Math.max(1, Math.abs(x[3]))) return false;
        return true;
    }

    private static void add(double[] s, FourVector p) {
        s[0] += p.px();
        s[1] += p.py();
        s[2] += p.pz();
        s[3] += p.e();
    }

    private static int report(boolean ok, String what) {
        AppLogger.raw((ok ? "  ok    " : "  FAIL  ") + what);
        return ok ? 0 : 1;
    }

    private static void bench(Args a, CommandExecutionContext c) {
        if (!haveEvents()) return;
        final int repeat = (int) a.opt("repeat", 3);
        AppLogger.result(f("%d events, best of %d, in memory:", EVENTS.size(), repeat));
        AppLogger.raw(f("  %-10s %12s %12s %12s", "format", "write ev/s", "read ev/s", "size kB"));
        for (String fmt : List.of("asciiv3", "hepmc2", "protobuf")) {
            double bestW = Double.MAX_VALUE;
            double bestR = Double.MAX_VALUE;
            byte[] bytes = new byte[0];
            for (int k = 0; k < repeat; k++) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                long t0 = System.nanoTime();
                final Writer w = switch (fmt) {
                    case "asciiv3" -> new WriterAscii(out, run);
                    case "hepmc2" -> new WriterAsciiHepMC2(out, run);
                    default -> new Writerprotobuf(out, run);
                };
                for (GenEvent e : EVENTS) w.writeEvent(e);
                w.close();
                bestW = Math.min(bestW, (System.nanoTime() - t0) / 1e9);
                bytes = out.toByteArray();
                t0 = System.nanoTime();
                final Reader r = switch (fmt) {
                    case "asciiv3" -> new ReaderAscii(new ByteArrayInputStream(bytes));
                    case "hepmc2" -> new ReaderAsciiHepMC2(new ByteArrayInputStream(bytes));
                    default -> new Readerprotobuf(new ByteArrayInputStream(bytes));
                };
                readAll(r);
                bestR = Math.min(bestR, (System.nanoTime() - t0) / 1e9);
            }
            AppLogger.raw(f("  %-10s %12.0f %12.0f %12.1f", label(fmt), EVENTS.size() / bestW, EVENTS.size() / bestR,
                bytes.length / 1024.0));
        }
    }

    private static void bib(Args a, CommandExecutionContext c) throws IOException {
        if (a.plain.isEmpty()) {
            Handlers.usage(":hepmc bib <file.bib> [--all]");
            return;
        }
        final boolean all = a.has("all");
        final Path out = Handlers.resolve(a.plain.get(0)).toPath();
        Files.writeString(out, HepMC3Citations.bibtex(all), StandardCharsets.UTF_8);
        AppLogger.result(f("%d references written to %s.", HepMC3Citations.bibliography(all).size(), out));
    }

    /* ------------------------------------------------------------------ */
    /* What ':hepmc diag' adds                                             */
    /* ------------------------------------------------------------------ */

    /** The rows ':hepmc diag' shows beside the probe. */
    static void diagRows() {
        EngineCommands.row("events", EVENTS.isEmpty() ? "none (':hepmc read <file>' or ':hepmc toy')"
            : EVENTS.size() + " from " + source + " (" + format + ")");
        EngineCommands.row("formats", "read: Asciiv3, HepMC2, HEPEVT, LHEF, protobuf, gzip/zstd, SPX; write: Asciiv3, HepMC2, HEPEVT, protobuf, gzip, SPX");
        final boolean refs = References.available();
        EngineCommands.row("references", Tests.all().size() + " test programs of HepMC3; C++ outputs "
            + (refs ? "in the jar" : "absent (-Dhepmc3.refdir)"));
        if (refs) {
            final List<TestProgram> pick = Tests.matching("testIO1");
            if (!pick.isEmpty()) {
                final Validator.Outcome r = Validator.run(pick.get(0));
                EngineCommands.row("versus C++", r.test().id() + (r.passed() ? " identical (exit code, cout, cerr, files)"
                    : " differs: " + String.valueOf(r.firstDifference()).replace("\n", " | "))
                    + f(" (%.0f ms); all of them: ':hepmc validate'", r.millis()));
            }
        }
        EngineCommands.row("engines", "':hepmc tofjet' (FastJet), ':hepmc bridge' (Python, Julia, C++, Fortran, ROOT), ':hepmc crosscheck'");
        EngineCommands.row("citations", "console menu > Citations > Citation HepMC3; ':hepmc bib <file.bib>'");
    }

    /* ------------------------------------------------------------------ */
    /* For the other command families                                      */
    /* ------------------------------------------------------------------ */

    static List<GenEvent> loadedEvents() {
        return EVENTS;
    }

    static GenRunInfo loadedRunInfo() {
        return run;
    }

    static String loadedSource() {
        return source;
    }
}
