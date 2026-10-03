package com.sphere.core;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJet;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.JetSpecs;
import com.sphere.core.fjcontrib.ContribCatalog;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.ContribSpecs;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;
import com.sphere.utils.SettingsManager;

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
 * The engines that run inside Sphere's own JVM: FastJet and the fjcontrib
 * contribs, translated to Java.
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
 * it below. "PONG" therefore means that the engine computes correctly, not
 * only that its classes load.
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
