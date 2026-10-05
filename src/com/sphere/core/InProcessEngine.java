package com.sphere.core;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.JetSpecs;
import com.sphere.core.fjcontrib.ContribCatalog;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ContribSpecs;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;
import com.sphere.core.hepmc3.FourVector;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.GenVertex;
import com.sphere.core.hepmc3.HepMC3;
import com.sphere.core.hepmc3.IntAttribute;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.hepmc3.ReaderAsciiHepMC2;
import com.sphere.core.hepmc3.Units;
import com.sphere.core.hepmc3.WriterAscii;
import com.sphere.core.hepmc3.WriterAsciiHepMC2;
import com.sphere.core.hepmc3.cxx.StdStreams;
import com.sphere.core.hepmc3.search.Relatives;
import com.sphere.utils.SettingsManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The engines that run inside Sphere's own JVM: FastJet, the fjcontrib
 * contribs and HepMC3, translated to Java.
 *
 * <p>They have no executable to find and no process to start, so what finds
 * Python, g++ or ROOT cannot see them. This is how the rest of Sphere does:
 * the backend list, the backend diagnostics, the bridge's engine table, the
 * startup check, and each engine's own {@code ping}, {@code diag} and
 * {@code mode}.
 *
 * <p>A probe does real work and checks the answer against what the physics
 * requires, not against numbers recorded from an earlier run: particles close
 * together end in one jet and far apart in two, the four-momentum of the jets
 * is that of the particles, SoftDrop keeps a splitting above its cut and drops
 * it below, an event HepMC3 writes and reads back is the event written. "PONG"
 * therefore means that the engine computes correctly, not only that its
 * classes load.
 */
public enum InProcessEngine {

    FASTJET("fjet", "FastJet") {
        @Override
        public String version() {
            return FastJet.FASTJET_VERSION;
        }

        @Override
        public String description() {
            return "FastJet " + FastJet.FASTJET_VERSION + " (Java port " + FastJet.JAVA_PORT_VERSION + ", in process)";
        }

        @Override
        protected String check() {
            final List<PseudoJet> event = probeEvent();
            final List<PseudoJet> akt = cluster(event, "antikt:0.4");
            require(akt.size() == 2, "anti-kt R=0.4 made " + akt.size() + " jets of the probe event, 2 expected");
            requireConserved(event, akt, "anti-kt");
            final List<PseudoJet> kt = cluster(event, "kt:0.4");
            require(kt.size() == 2 && Math.abs(kt.get(0).pt() - akt.get(0).pt()) <= 1e-9 * akt.get(0).pt(),
                "kt and anti-kt disagree on two well separated jets");
            return "anti-kt and kt: 2 jets of 3 particles, four-momentum conserved";
        }
    },

    FJCONTRIB("fjco", "fjcontrib") {
        @Override
        public String version() {
            return ContribCitations.RELEASE;
        }

        @Override
        public String description() {
            return "fjcontrib " + ContribCitations.RELEASE + " (Java, " + ContribCatalog.size()
                + " contribs, on FastJet " + FastJet.FASTJET_VERSION + ", in process)";
        }

        @Override
        protected String check() {
            final List<String> missing = missingSpecs();
            require(missing.isEmpty(), "contrib algorithms missing from ':fjet def': " + String.join(", ", missing));
            final List<PseudoJet> event = probeEvent();
            final List<PseudoJet> vr = cluster(event, "variabler:rho=600");
            require(vr.size() == 2, "variable-R made " + vr.size() + " jets of the probe event, 2 expected");
            requireConserved(event, vr, "variable-R");
            // The leading jet holds 100 and 50 GeV: z = 1/3, kept by zcut 0.1, dropped by zcut 0.4.
            final PseudoJet jet = cluster(event, "antikt:0.8").get(0);
            final PseudoJet kept = new SoftDrop(0.0, 0.1).result(jet);
            final PseudoJet dropped = new SoftDrop(0.0, 0.4).result(jet);
            require(kept != null && Math.abs(kept.pt() - jet.pt()) <= 1e-9 * jet.pt(),
                "SoftDrop zcut=0.1 removed a splitting with z = 1/3");
            require(dropped != null && Math.abs(dropped.pt() - 100.0) <= 1e-9 * 100.0,
                "SoftDrop zcut=0.4 kept a splitting with z = 1/3");
            return ContribCatalog.size() + " contribs, " + contribSpecs().size()
                + " algorithms in ':fjet def', variable-R conserves four-momentum, SoftDrop cuts at z";
        }
    },

    HEPMC3("hepmc", "HepMC3") {
        @Override
        public String version() {
            return HepMC3.VERSION;
        }

        @Override
        public String description() {
            return "HepMC3 " + HepMC3.VERSION + " (Java port " + HepMC3.JAVA_PORT_VERSION + ", in process)";
        }

        @Override
        protected String check() throws Exception {
            // the readers speak on cout and cerr; a probe keeps quiet
            return StdStreams.with(StdStreams.NONE, InProcessEngine::checkHepMC3);
        }

        @Override
        public Native findNative() {
            return findHepMC3();
        }

        @Override
        public String nativeHint() {
            return "no installation found (HepMC3-config not on the PATH, HEPMC3_DIR unset)";
        }

        @Override
        public String modeExamples() {
            return "'read <file>', 'listing', 'select status==1 && pt>10', 'tofjet'";
        }
    },

    MINUIT2("minuit2", "Minuit2") {
        @Override
        public String version() {
            return com.sphere.Sphere.MINUIT2_SOURCES_VERSION;
        }

        @Override
        public String description() {
            return "Minuit2 of ROOT " + com.sphere.Sphere.MINUIT2_SOURCES_VERSION + " (Java port "
                + com.sphere.Sphere.MINUIT2_JAVA_PORT_VERSION + ", bit for bit the C++, in process)";
        }

        @Override
        protected String check() {
            return checkMinuit2();
        }

        @Override
        public Native findNative() {
            return findRootMinuit2();
        }

        @Override
        public String nativeHint() {
            return "no ROOT installation found (root-config not on the PATH, ROOTSYS unset)";
        }

        @Override
        public String modeExamples() {
            return "'minimize \"([0]-1)^2+([1]+2)^2\" 0 0 --minos', 'contour 0 1', 'validate'";
        }
    };

    /** What a probe found, and how long it took. */
    public record Probe(boolean ok, String detail, double millis) {
    }

    /**
     * A C++ installation found on this machine.
     *
     * @param prefix  where it is installed
     * @param version its version, when a header says it
     * @param detail  what was found there
     */
    public record Native(Path prefix, String version, String detail) {
    }

    private final String key;
    private final String label;

    InProcessEngine(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The console prefix without its colon: "fjet", "fjco". */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** The release translated, as ':root version' gives ROOT's. */
    public abstract String version();

    /** One line: release, port, where it runs. */
    public abstract String description();

    /** Does the probe's work; returns what was checked, throws on a wrong answer. */
    protected abstract String check() throws Exception;

    /** What diag says when no C++ installation is found. */
    public String nativeHint() {
        return "no installation found (fastjet-config not on the PATH, FASTJET unset)";
    }

    /** A few commands to type once in the engine's mode. */
    public String modeExamples() {
        return this == FASTJET ? "'read <file>', 'jets 20', 'def ak4 antikt:0.4'" : "'obs tau21', 'groom sd 0 0.1', 'flavour'";
    }

    /** Runs the probe. Never throws: a failure is the probe's answer. */
    public Probe probe() {
        final long t0 = System.nanoTime();
        try {
            final String detail = ClusterSequence.selfCheck(this::check);
            return new Probe(true, detail, (System.nanoTime() - t0) / 1e6);
        } catch (Throwable t) {
            final String why = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return new Probe(false, why, (System.nanoTime() - t0) / 1e6);
        }
    }

    /** The engine a console prefix names, with or without its colon. */
    public static InProcessEngine find(String key) {
        if (key == null) return null;
        final String k = key.trim().toLowerCase(Locale.ROOT).replaceFirst("^:", "");
        for (InProcessEngine e : values()) {
            if (e.key.equals(k) || e.label.toLowerCase(Locale.ROOT).equals(k)) return e;
        }
        return switch (k) {
            case "fastjet", "jets" -> FASTJET;
            case "contrib", "contribs", "fastjet-contrib" -> FJCONTRIB;
            case "hepmc3", "hepmc2", "events" -> HEPMC3;
            case "minuit", "minimizer", "migrad" -> MINUIT2;
            default -> null;
        };
    }

    /* ------------------------------------------------------------------ */
    /* The C++ installations beside the port                               */
    /* ------------------------------------------------------------------ */

    /**
     * The C++ FastJet, and the fjcontrib installed into it, if this machine
     * has them: from fastjet-config on the PATH (or FASTJET_CONFIG in
     * settings.conf), or the FASTJET, FASTJET_DIR, FASTJET_ROOT variables.
     * Read from the headers, so nothing is run and it works on every system.
     */
    public Native findNative() {
        for (Path prefix : nativePrefixes()) {
            final Path include = prefix.resolve("include").resolve("fastjet");
            if (!Files.isRegularFile(include.resolve("ClusterSequence.hh"))) continue;
            if (this == FASTJET) {
                return new Native(prefix, headerVersion(include), "headers in " + include);
            }
            final Path contrib = include.resolve("contrib");
            if (!Files.isDirectory(contrib)) continue;
            final int headers = count(contrib, ".hh");
            final String lib = library(prefix, "fastjetcontribfragile");
            return new Native(prefix, null, headers + " contrib headers in " + contrib
                + (lib == null ? "" : ", library " + lib));
        }
        return null;
    }

    /**
     * An unpacked fjcontrib release, whose example outputs ':fjco validate'
     * can compare against: the folder given to it, or FJCONTRIB_DIR.
     */
    public static Path fjcontribRelease() {
        for (String dir : new String[]{System.getProperty("fjcontrib.dir"), System.getenv("FJCONTRIB_DIR")}) {
            if (dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir, "data"))) return Path.of(dir);
        }
        return null;
    }

    private static Set<Path> nativePrefixes() {
        final Set<Path> out = new LinkedHashSet<>();
        final String config = new SettingsManager().resolveTool("FASTJET_CONFIG", "fastjet-config");
        if (config != null) {
            final Path bin = Path.of(config).toAbsolutePath().getParent();
            if (bin != null && bin.getParent() != null) out.add(bin.getParent());
        }
        for (String var : new String[]{"FASTJET", "FASTJET_DIR", "FASTJET_ROOT", "FASTJETDIR"}) {
            final String v = System.getenv(var);
            if (v != null && !v.isBlank()) out.add(Path.of(v.trim()));
        }
        return out;
    }

    private static final Pattern VERSION_DEFINE = Pattern.compile("#define\\s+FASTJET_PACKAGE_VERSION\\s+\"([^\"]+)\"");

    private static String headerVersion(Path include) {
        for (String header : new String[]{"config_auto.h", "config_win.h", "config_raw.h"}) {
            try {
                final Matcher m = VERSION_DEFINE.matcher(Files.readString(include.resolve(header)));
                if (m.find()) return m.group(1);
            } catch (IOException | RuntimeException absent) {
                // the next header, or none
            }
        }
        return null;
    }

    private static int count(Path dir, String suffix) {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String library(Path prefix, String name) {
        for (String lib : new String[]{"lib", "lib64", "bin"}) {
            for (String file : new String[]{"lib" + name + ".so", "lib" + name + ".a", "lib" + name + ".dylib",
                                            name + ".dll", "lib" + name + ".dll", name + ".lib"}) {
                final Path p = prefix.resolve(lib).resolve(file);
                if (Files.isRegularFile(p)) return p.toString();
            }
        }
        return null;
    }

    /**
     * The C++ HepMC3, if this machine has it: from HepMC3-config on the PATH
     * (or HEPMC3_CONFIG in settings.conf), or the HEPMC3_DIR, HEPMC3_ROOT,
     * HEPMC3 variables. Read from Version.h, so nothing is run.
     */
    private static Native findHepMC3() {
        final Set<Path> prefixes = new LinkedHashSet<>();
        final String config = new SettingsManager().resolveTool("HEPMC3_CONFIG", "HepMC3-config");
        if (config != null) {
            final Path bin = Path.of(config).toAbsolutePath().getParent();
            if (bin != null && bin.getParent() != null) prefixes.add(bin.getParent());
        }
        for (String var : new String[]{"HEPMC3_DIR", "HEPMC3_ROOT", "HEPMC3", "HEPMC3_ROOT_DIR"}) {
            final String v = System.getenv(var);
            if (v != null && !v.isBlank()) prefixes.add(Path.of(v.trim()));
        }
        for (Path prefix : prefixes) {
            final Path header = prefix.resolve("include").resolve("HepMC3").resolve("Version.h");
            if (!Files.isRegularFile(header)) continue;
            String version = null;
            try {
                final Matcher m = HEPMC3_VERSION_DEFINE.matcher(Files.readString(header));
                if (m.find()) version = m.group(1);
            } catch (IOException | RuntimeException unreadable) {
                // the version stays unknown
            }
            final String lib = library(prefix, "HepMC3");
            return new Native(prefix, version, "headers in " + header.getParent() + (lib == null ? "" : ", library " + lib));
        }
        return null;
    }

    /**
     * Minuit2's probe: Migrad on a correlated parabola must find its minimum
     * and the covariance twice the inverse of its second derivatives, Minos
     * its parabolic errors, and a limit must hold a parameter whose free
     * minimum is beyond it.
     */
    private static String checkMinuit2() {
        final int prev = com.sphere.core.minuit2.MnPrint.setGlobalLevel(-1);
        try {
            final com.sphere.core.minuit2.FCNBase parabola = new com.sphere.core.minuit2.FCNBase() {
                @Override
                public double value(double[] p) {
                    final double u = p[0] - 1;
                    final double v = p[1] + 2;
                    return 4 * u * u + 0.25 * v * v + 0.6 * u * v;
                }

                @Override
                public double up() {
                    return 1;
                }
            };
            final com.sphere.core.minuit2.MnUserParameters up = new com.sphere.core.minuit2.MnUserParameters();
            up.add("x", 0, 0.1);
            up.add("y", 0, 0.1);
            final com.sphere.core.minuit2.FunctionMinimum min = new com.sphere.core.minuit2.MnMigrad(parabola, up).minimize();
            require(min.isValid(), "Migrad did not converge on a parabola");
            require(Math.abs(min.userState().value(0) - 1) < 1e-4 && Math.abs(min.userState().value(1) + 2) < 1e-3,
                "Migrad's minimum of the parabola is wrong: " + min.userState().value(0) + ", " + min.userState().value(1));
            // second derivatives [[8, 0.6], [0.6, 0.5]]: covariance = 2 H^-1
            final double det = 8 * 0.5 - 0.6 * 0.6;
            final double vxx = 2 * 0.5 / det;
            final double vyy = 2 * 8 / det;
            final double vxy = -2 * 0.6 / det;
            final com.sphere.core.minuit2.MnUserCovariance c = min.userCovariance();
            require(Math.abs(c.get(0, 0) / vxx - 1) < 1e-3 && Math.abs(c.get(1, 1) / vyy - 1) < 1e-3
                && Math.abs(c.get(0, 1) / vxy - 1) < 1e-2, "the covariance of the parabola is not 2 H^-1");
            final com.sphere.core.minuit2.MinosError me = new com.sphere.core.minuit2.MnMinos(parabola, min).minos(0);
            require(me.isValid() && Math.abs(me.upper() / Math.sqrt(vxx) - 1) < 1e-2
                && Math.abs(-me.lower() / Math.sqrt(vxx) - 1) < 1e-2, "Minos errors of a parabola are not the parabolic ones");
            final com.sphere.core.minuit2.FCNBase beyond = new com.sphere.core.minuit2.FCNBase() {
                @Override
                public double value(double[] p) {
                    return (p[0] - 3) * (p[0] - 3);
                }

                @Override
                public double up() {
                    return 1;
                }
            };
            final com.sphere.core.minuit2.MnUserParameters ul = new com.sphere.core.minuit2.MnUserParameters();
            ul.add("z", 1, 0.1, 0, 2);
            final double z = new com.sphere.core.minuit2.MnMigrad(beyond, ul).minimize().userState().value(0);
            require(z <= 2 && z > 1.99, "a parameter limited to [0, 2] ended at " + z + " for a minimum at 3");
            return "Migrad: a correlated parabola's minimum and covariance (2 H^-1), Minos its parabolic errors, a limit holds";
        } finally {
            com.sphere.core.minuit2.MnPrint.setGlobalLevel(prev);
        }
    }

    /** ROOT's Minuit2, if this machine has ROOT: from root-config on the PATH or ROOTSYS; read from the headers. */
    private static Native findRootMinuit2() {
        final Set<Path> prefixes = new LinkedHashSet<>();
        final String config = new SettingsManager().resolveTool("ROOT_CONFIG", "root-config");
        if (config != null) {
            final Path bin = Path.of(config).toAbsolutePath().getParent();
            if (bin != null && bin.getParent() != null) prefixes.add(bin.getParent());
        }
        for (String var : new String[]{"ROOTSYS", "ROOT_DIR"}) {
            final String v = System.getenv(var);
            if (v != null && !v.isBlank()) prefixes.add(Path.of(v.trim()));
        }
        for (Path prefix : prefixes) {
            for (String inc : new String[]{"include", "include/root"}) {
                final Path header = prefix.resolve(inc).resolve("Minuit2").resolve("MnMigrad.h");
                if (!Files.isRegularFile(header)) continue;
                String version = null;
                try {
                    final Path rv = prefix.resolve(inc).resolve("RVersion.h");
                    if (Files.isRegularFile(rv)) {
                        final Matcher m = ROOT_RELEASE_DEFINE.matcher(Files.readString(rv));
                        if (m.find()) version = m.group(1);
                    }
                } catch (IOException | RuntimeException unreadable) {
                    // the version stays unknown
                }
                final String lib = library(prefix, "Minuit2");
                return new Native(prefix, version, "headers in " + header.getParent() + (lib == null ? "" : ", library " + lib)
                    + "; ':root minuit2 crosscheck' compares it with Sphere's");
            }
        }
        return null;
    }

    private static final Pattern ROOT_RELEASE_DEFINE = Pattern.compile("#define\\s+ROOT_RELEASE\\s+\"([^\"]+)\"");

    private static final Pattern HEPMC3_VERSION_DEFINE = Pattern.compile("#define\\s+HEPMC3_VERSION\\s+\"([^\"]+)\"");

    /** The probe event: two beams, a pair, one of which splits in two; momentum conserved exactly at each vertex. */
    private static GenEvent probeHepMC3Event() {
        final GenEvent evt = new GenEvent(Units.MomentumUnit.GEV, Units.LengthUnit.MM);
        evt.setEventNumber(7);
        final GenParticle b1 = new GenParticle(new FourVector(0, 0, 50, 50), 11, 4);
        final GenParticle b2 = new GenParticle(new FourVector(0, 0, -50, 50), -11, 4);
        final GenParticle a = new GenParticle(new FourVector(30, 40, 0, 50), 23, 2);
        final GenParticle b = new GenParticle(new FourVector(-30, -40, 0, 50), 22, 1);
        final GenParticle c = new GenParticle(new FourVector(15, 20, 0, 25), 13, 1);
        final GenParticle d = new GenParticle(new FourVector(15, 20, 0, 25), -13, 1);
        final GenVertex v1 = new GenVertex();
        v1.addParticleIn(b1);
        v1.addParticleIn(b2);
        v1.addParticleOut(a);
        v1.addParticleOut(b);
        evt.addVertex(v1);
        final GenVertex v2 = new GenVertex(new FourVector(0.5, 0.25, 0, 1));
        v2.addParticleIn(a);
        v2.addParticleOut(c);
        v2.addParticleOut(d);
        evt.addVertex(v2);
        evt.addAttribute("signal_process_id", new IntAttribute(23));
        evt.weights().add(1.0);
        return evt;
    }

    private static String asciiv3(GenEvent evt) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final WriterAscii w = new WriterAscii(out, null);
        w.writeEvent(evt);
        w.close();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static String hepmc2(GenEvent evt) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final WriterAsciiHepMC2 w = new WriterAsciiHepMC2(out, null);
        w.writeEvent(evt);
        w.close();
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static String checkHepMC3() {
        final GenEvent evt = probeHepMC3Event();
        for (GenVertex v : evt.vertices()) {
            final double[] in = new double[4];
            final double[] out = new double[4];
            for (GenParticle p : v.particlesIn()) addHepMC(in, p.momentum());
            for (GenParticle p : v.particlesOut()) addHepMC(out, p.momentum());
            for (int k = 0; k < 4; k++) require(in[k] == out[k], "momentum not conserved at vertex " + v.id());
        }
        final String text = asciiv3(evt);
        final ReaderAscii r = new ReaderAscii(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
        final GenEvent back = new GenEvent();
        require(r.readEvent(back) && !r.failed(), "the Asciiv3 reader did not read the event the writer wrote");
        require(asciiv3(back).equals(text), "an event written in Asciiv3 and read back is not the same event");
        require(back.particles().size() == 6 && back.vertices().size() == 2, "the event read back lost particles or vertices");
        final String two = hepmc2(evt);
        final ReaderAsciiHepMC2 r2 = new ReaderAsciiHepMC2(new ByteArrayInputStream(two.getBytes(StandardCharsets.ISO_8859_1)));
        final GenEvent back2 = new GenEvent();
        r2.readEvent(back2);
        require(!r2.failed() && hepmc2(back2).equals(two), "an event written in HepMC2 and read back is not the same event");
        final GenEvent boosted = new GenEvent(evt);
        require(boosted.boost(new FourVector(0.1, -0.2, 0.3, 0)) && boosted.boost(new FourVector(-0.1, 0.2, -0.3, 0)),
            "a boost below the speed of light was refused");
        for (int i = 0; i < evt.particles().size(); i++) {
            final FourVector a = evt.particles().get(i).momentum();
            final FourVector b = boosted.particles().get(i).momentum();
            require(Math.abs(a.e() - b.e()) <= 1e-10 && Math.abs(a.pz() - b.pz()) <= 1e-10,
                "a boost and its inverse moved particle " + (i + 1));
        }
        final GenParticle muon = evt.particles().get(4);
        final List<GenParticle> ancestors = Relatives.ANCESTORS.apply(muon);
        require(ancestors.contains(evt.particles().get(0)) && ancestors.contains(evt.particles().get(1)),
            "the ancestors of a muon do not include the beams");
        return "Asciiv3 and HepMC2 written and read back to the character, momentum conserved at every vertex, "
            + "boost reversible, ancestors found";
    }

    private static void addHepMC(double[] sum, FourVector p) {
        sum[0] += p.px();
        sum[1] += p.py();
        sum[2] += p.pz();
        sum[3] += p.e();
    }

    /* ------------------------------------------------------------------ */
    /* The probe                                                           */
    /* ------------------------------------------------------------------ */

    /** The contrib jet algorithms ':fjet def' should know. */
    public static List<String> contribSpecs() {
        final List<String> specs = new ArrayList<>();
        for (ContribCatalog.Contrib k : ContribCatalog.all()) specs.addAll(k.specs());
        return specs;
    }

    /** Those it does not know, which is none unless the registration broke. */
    public static List<String> missingSpecs() {
        ContribSpecs.register();
        final Set<String> known = JetSpecs.extensions().keySet();
        final List<String> missing = new ArrayList<>();
        for (String s : contribSpecs()) if (!known.contains(s)) missing.add(s);
        return missing;
    }

    /** Two particles 0.14 apart, 100 and 50 GeV, and a third across the detector. */
    private static List<PseudoJet> probeEvent() {
        return List.of(PseudoJet.ptYPhiM(100.0, 0.0, 0.0),
                       PseudoJet.ptYPhiM(50.0, 0.1, 0.1),
                       PseudoJet.ptYPhiM(30.0, 2.0, 3.0));
    }

    private static List<PseudoJet> cluster(List<PseudoJet> event, String spec) {
        return PseudoJet.sortedByPt(new ClusterSequence(event, JetSpecs.parse(spec)).inclusiveJets(0.0));
    }

    private static void requireConserved(List<PseudoJet> event, List<PseudoJet> jets, String algorithm) {
        final double[] in = new double[4];
        final double[] out = new double[4];
        for (PseudoJet p : event) add(in, p);
        for (PseudoJet j : jets) add(out, j);
        for (int k = 0; k < 4; k++) {
            require(Math.abs(in[k] - out[k]) <= 1e-9 * Math.max(1.0, in[3]),
                algorithm + " lost four-momentum: component " + k + " is " + out[k] + " for " + in[k]);
        }
    }

    private static void add(double[] sum, PseudoJet p) {
        sum[0] += p.px();
        sum[1] += p.py();
        sum[2] += p.pz();
        sum[3] += p.E();
    }

    private static void require(boolean condition, String otherwise) {
        if (!condition) throw new IllegalStateException(otherwise);
    }
}
