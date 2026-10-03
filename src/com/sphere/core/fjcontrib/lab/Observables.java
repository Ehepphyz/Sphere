package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.AreaDefinition;
import com.sphere.core.fastjet.GhostedAreaSpec;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators;
import com.sphere.core.fjcontrib.jetffmoments.JetFFMoments;
import com.sphere.core.fjcontrib.jetswithoutjets.JetsWithoutJets;
import com.sphere.core.fjcontrib.lundplane.LundDeclustering;
import com.sphere.core.fjcontrib.lundplane.LundGenerator;
import com.sphere.core.fjcontrib.nsubjettiness.AxesDefinition;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition;
import com.sphere.core.fjcontrib.nsubjettiness.Nsubjettiness;
import com.sphere.core.fjcontrib.nsubjettiness.NsubjettinessRatio;
import com.sphere.core.fjcontrib.recursivetools.BottomUpSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.IteratedSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.ModifiedMassDropTagger;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSoftDrop;
import com.sphere.core.fjcontrib.recursivetools.RecursiveSymmetryCutBase;
import com.sphere.core.fjcontrib.recursivetools.SoftDrop;
import com.sphere.core.fjcontrib.signalfree.SignalFreeBackgroundEstimator;
import com.sphere.core.fjcontrib.softkiller.SoftKiller;
import com.sphere.core.fjcontrib.subjetcounting.SubjetCountingCA;
import com.sphere.core.fjcontrib.subjetcounting.SubjetCountingKt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Every jet or event observable the contribs provide, under one short name
 * with its parameters: "tau21", "d2:beta=2", "sdmass:beta=0,zcut=0.1",
 * "nsd", "njww:rjet=0.4,ptcut=25". One catalogue feeds the table, the
 * histograms, the exports to the other engines and the discrimination
 * ranking, so that an observable is written once and named the same way
 * everywhere.
 *
 * <p>The contrib tools keep state between calls (the axes N-subjettiness
 * found last, for instance), so an {@link Observable} hands out a fresh
 * {@link Evaluator} to each thread that asks: events can be measured in
 * parallel, each one by tools of its own.
 */
public final class Observables {

    /** Whether the value belongs to a jet or to the whole event. */
    public enum Scope { JET, EVENT }

    /** What an evaluation may need besides the jet: the event and the radius of the jets. */
    public record Context(List<PseudoJet> event, double jetR) {
    }

    /** The parameters written after the colon, typed on demand. */
    public static final class Args {
        private final Map<String, String> kv;

        Args(Map<String, String> kv) {
            this.kv = kv;
        }

        public double d(String key, double fallback) {
            final String v = kv.get(key);
            if (v == null || v.equals("auto")) return fallback;
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e) {
                throw new FastJetException("parameter " + key + "=" + v + " is not a number");
            }
        }

        public int i(String key, int fallback) {
            return (int) d(key, fallback);
        }

        public String s(String key, String fallback) {
            final String v = kv.get(key);
            return v == null ? fallback : v.toLowerCase(Locale.ROOT);
        }

        public boolean auto(String key) {
            return !kv.containsKey(key) || "auto".equals(kv.get(key));
        }

        Map<String, String> map() {
            return kv;
        }
    }

    /** Measures one jet, or one event; not shared between threads. */
    public interface Evaluator {
        double jet(PseudoJet jet, Context ctx);

        default double event(Context ctx) {
            throw new FastJetException("not an event observable");
        }
    }

    /**
     * An entry of the catalogue.
     *
     * @param deterministic false when the value draws random numbers (ghosts, multi-pass axes):
     *                      such observables are measured on one thread, in event order
     */
    public record Definition(String name, Scope scope, String contrib, String summary, String params,
                             boolean deterministic, Function<Args, Evaluator> factory) {
    }

    /** An observable with its parameters, ready to hand out evaluators. */
    public static final class Observable {
        private final Definition def;
        private final Args args;
        private final String label;

        Observable(Definition def, Args args, String label) {
            this.def = def;
            this.args = args;
            this.label = label;
        }

        public String label() { return label; }
        public String name() { return def.name(); }
        public Scope scope() { return def.scope(); }
        public String contrib() { return def.contrib(); }
        public boolean deterministic() {
            return def.deterministic() && !"min".equals(args.s("axes", ""));
        }
        public Definition definition() { return def; }

        /** A new evaluator, for one thread. */
        public Evaluator evaluator() {
            return def.factory().apply(args);
        }
    }

    private static final Map<String, Definition> CATALOGUE = new LinkedHashMap<>();

    private static void jet(String name, String contrib, String summary, String params,
                            Function<Args, Evaluator> factory) {
        CATALOGUE.put(name, new Definition(name, Scope.JET, contrib, summary, params, true, factory));
    }

    private static void event(String name, String contrib, String summary, String params, boolean deterministic,
                              Function<Args, Evaluator> factory) {
        CATALOGUE.put(name, new Definition(name, Scope.EVENT, contrib, summary, params, deterministic, factory));
    }

    /** A jet evaluator from a function of the jet and its radius. */
    private interface JetFn {
        double apply(PseudoJet jet, Context ctx);
    }

    private static Evaluator perJet(JetFn f) {
        return f::apply;
    }

    private static Evaluator perEvent(Function<Context, Double> f) {
        return new Evaluator() {
            @Override
            public double jet(PseudoJet jet, Context ctx) {
                return f.apply(ctx);
            }

            @Override
            public double event(Context ctx) {
                return f.apply(ctx);
            }
        };
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    static AxesDefinition axes(String name) {
        return switch (name) {
            case "wta_kt" -> new AxesDefinition.WTA_KT_Axes();
            case "kt" -> new AxesDefinition.KT_Axes();
            case "ca" -> new AxesDefinition.CA_Axes();
            case "wta_ca" -> new AxesDefinition.WTA_CA_Axes();
            case "halfkt" -> new AxesDefinition.HalfKT_Axes();
            case "wta_halfkt" -> new AxesDefinition.WTA_HalfKT_Axes();
            case "onepass_kt" -> new AxesDefinition.OnePass_KT_Axes();
            case "onepass_wta_kt" -> new AxesDefinition.OnePass_WTA_KT_Axes();
            case "onepass_ca" -> new AxesDefinition.OnePass_CA_Axes();
            case "onepass_wta_ca" -> new AxesDefinition.OnePass_WTA_CA_Axes();
            case "antikt" -> new AxesDefinition.AntiKT_Axes(0.2);
            case "min" -> new AxesDefinition.MultiPass_Axes(100);
            default -> throw new FastJetException("unknown axes " + name
                + " (wta_kt, kt, ca, wta_ca, halfkt, wta_halfkt, onepass_kt, onepass_wta_kt, onepass_ca, onepass_wta_ca, antikt, min)");
        };
    }

    private static MeasureDefinition measure(Args a, double jetR) {
        final double beta = a.d("beta", 1.0);
        return "unnormalized".equals(a.s("measure", "normalized"))
            ? new MeasureDefinition.UnnormalizedMeasure(beta)
            : new MeasureDefinition.NormalizedMeasure(beta, a.d("r0", jetR));
    }

    private static EnergyCorrelator.Measure ecfMeasure(Args a) {
        return switch (a.s("measure", "pt_r")) {
            case "e_theta" -> EnergyCorrelator.Measure.E_theta;
            case "e_inv" -> EnergyCorrelator.Measure.E_inv;
            default -> EnergyCorrelator.Measure.pt_R;
        };
    }

    /** The structure a groomer left on its result, or null. */
    static RecursiveSymmetryCutBase.StructureType structure(PseudoJet groomed) {
        return groomed != null && groomed.structure() instanceof RecursiveSymmetryCutBase.StructureType st ? st : null;
    }

    private static SoftDrop softDrop(Args a, double jetR) {
        return new SoftDrop(a.d("beta", 0.0), a.d("zcut", 0.1), a.d("r0", jetR));
    }

    /* ------------------------------------------------------------------ */
    /* The catalogue                                                       */
    /* ------------------------------------------------------------------ */

    static {
        // --- kinematics ---------------------------------------------------
        jet("pt", "fastjet", "transverse momentum (GeV)", "", a -> perJet((j, c) -> j.pt()));
        jet("m", "fastjet", "mass (GeV)", "", a -> perJet((j, c) -> j.m()));
        jet("rap", "fastjet", "rapidity", "", a -> perJet((j, c) -> j.rap()));
        jet("eta", "fastjet", "pseudorapidity", "", a -> perJet((j, c) -> j.eta()));
        jet("phi", "fastjet", "azimuth", "", a -> perJet((j, c) -> j.phi()));
        jet("nconst", "fastjet", "number of constituents", "", a -> perJet((j, c) -> j.constituents().size()));

        // --- N-subjettiness -------------------------------------------------
        for (int n = 1; n <= 5; n++) {
            final int nn = n;
            jet("tau" + n, "Nsubjettiness", "N-subjettiness tau_" + n, "beta=1,axes=wta_kt,measure=normalized|unnormalized,r0=R",
                a -> {
                    final Nsubjettiness[] tool = {null};
                    return perJet((j, c) -> {
                        if (tool[0] == null) tool[0] = new Nsubjettiness(nn, axes(a.s("axes", "wta_kt")), measure(a, c.jetR()));
                        return tool[0].result(j);
                    });
                });
        }
        for (int n = 2; n <= 5; n++) {
            final int nn = n;
            jet("tau" + n + (n - 1), "Nsubjettiness", "tau_" + n + "/tau_" + (n - 1) + " (" + (n == 2 ? "W, Z, H" : n == 3 ? "top" : "multi-prong") + " tagging)",
                "beta=1,axes=wta_kt,measure=normalized|unnormalized",
                a -> {
                    final NsubjettinessRatio[] tool = {null};
                    return perJet((j, c) -> {
                        if (tool[0] == null) tool[0] = new NsubjettinessRatio(nn, nn - 1, axes(a.s("axes", "wta_kt")), measure(a, c.jetR()));
                        return tool[0].result(j);
                    });
                });
        }

        // --- energy correlators --------------------------------------------
        jet("ecf", "EnergyCorrelator", "energy correlation function ECF(N, beta)", "n=2,beta=1,measure=pt_r|e_theta|e_inv",
            a -> {
                final EnergyCorrelator t = new EnergyCorrelator(a.i("n", 2), a.d("beta", 1.0), ecfMeasure(a));
                return perJet((j, c) -> t.result(j));
            });
        jet("c1", "EnergyCorrelator", "C1 (a jet-mass-like ratio)", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorC1(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("c2", "EnergyCorrelator", "C2, two-prong discriminant", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorC2(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("d2", "EnergyCorrelator", "D2, the power-counted two-prong discriminant", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorD2(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("d2g", "EnergyCorrelator", "generalised D2^(alpha, beta)", "alpha=1,beta=2",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorGeneralizedD2(a.d("alpha", 1.0), a.d("beta", 2.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("n2", "EnergyCorrelator", "N2 from generalised correlators, two-prong", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorN2(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("n3", "EnergyCorrelator", "N3, three-prong", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorN3(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("m2", "EnergyCorrelator", "M2, two-prong with groomed jets", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorM2(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("u1", "EnergyCorrelator", "U1, quark/gluon discriminant", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorU1(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("u2", "EnergyCorrelator", "U2", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorU2(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });
        jet("u3", "EnergyCorrelator", "U3", "beta=1",
            a -> { final var t = new EnergyCorrelators.EnergyCorrelatorU3(a.d("beta", 1.0), ecfMeasure(a)); return perJet((j, c) -> t.result(j)); });

        // --- grooming -------------------------------------------------------
        jet("sdmass", "RecursiveTools", "Soft Drop groomed mass (beta=0: mMDT)", "beta=0,zcut=0.1,r0=R",
            a -> perJet((j, c) -> softDrop(a, c.jetR()).result(j).m()));
        jet("sdpt", "RecursiveTools", "Soft Drop groomed pt", "beta=0,zcut=0.1,r0=R",
            a -> perJet((j, c) -> softDrop(a, c.jetR()).result(j).pt()));
        jet("zg", "RecursiveTools", "momentum sharing z_g of the splitting Soft Drop kept (none: left out)", "beta=0,zcut=0.1,r0=R",
            a -> perJet((j, c) -> {
                final var st = structure(softDrop(a, c.jetR()).result(j));
                return st == null || !st.hasSubstructure() ? Double.NaN : st.symmetry();
            }));
        jet("rg", "RecursiveTools", "opening angle R_g of the splitting Soft Drop kept (none: left out)", "beta=0,zcut=0.1,r0=R",
            a -> perJet((j, c) -> {
                final var st = structure(softDrop(a, c.jetR()).result(j));
                return st == null || !st.hasSubstructure() ? Double.NaN : st.deltaR();
            }));
        jet("sdmargin", "RecursiveTools", "(z - cut)/cut of the kept splitting: how close Soft Drop came to deciding otherwise", "beta=0,zcut=0.1,r0=R",
            a -> perJet((j, c) -> {
                final SoftDrop sd = softDrop(a, c.jetR());
                sd.setVerboseStructure(true);
                final var st = structure(sd.result(j));
                return st == null ? Double.NaN : st.decisionMargin();
            }));
        jet("mmdt", "RecursiveTools", "modified Mass Drop Tagger mass", "zcut=0.1",
            a -> perJet((j, c) -> {
                final ModifiedMassDropTagger t = new ModifiedMassDropTagger(a.d("zcut", 0.1));
                t.setGroomingMode(true);
                return t.result(j).m();
            }));
        jet("rsdmass", "RecursiveTools", "Recursive Soft Drop mass (n=-1: all prongs)", "beta=1,zcut=0.1,n=-1,r0=R",
            a -> perJet((j, c) -> new RecursiveSoftDrop(a.d("beta", 1.0), a.d("zcut", 0.1), a.i("n", -1), a.d("r0", c.jetR())).result(j).m()));
        jet("busdmass", "RecursiveTools", "Bottom-up Soft Drop mass", "beta=1,zcut=0.1,r0=R",
            a -> perJet((j, c) -> new BottomUpSoftDrop(a.d("beta", 1.0), a.d("zcut", 0.1), a.d("r0", c.jetR())).result(j).m()));
        jet("nsd", "RecursiveTools",
            "Iterated Soft Drop multiplicity n_SD (quark/gluon); zcut auto = 1 GeV/(pt R), as 1704.06266 advises",
            "beta=-1,zcut=auto,theta=0",
            a -> perJet((j, c) -> {
                final double zcut = a.auto("zcut") ? 1.0 / (j.pt() * c.jetR()) : a.d("zcut", 0.005);
                return new IteratedSoftDrop(a.d("beta", -1.0), zcut, a.d("theta", 0.0), c.jetR()).multiplicity(j);
            }));

        // --- Lund plane -----------------------------------------------------
        jet("nlund", "LundPlane", "primary Lund declusterings with kt above ktcut (GeV)", "ktcut=1",
            a -> {
                final LundGenerator g = new LundGenerator();
                return perJet((j, c) -> {
                    int n = 0;
                    for (LundDeclustering d : g.result(j)) if (d.kt() > a.d("ktcut", 1.0)) n++;
                    return n;
                });
            });
        jet("lnktmax", "LundPlane", "ln kt of the hardest primary declustering", "",
            a -> {
                final LundGenerator g = new LundGenerator();
                return perJet((j, c) -> {
                    double best = Double.NEGATIVE_INFINITY;
                    for (LundDeclustering d : g.result(j)) best = Math.max(best, Math.log(d.kt()));
                    return best;
                });
            });

        // --- subjet counting, fragmentation moments --------------------------
        jet("nkt", "SubjetCounting", "n_Kt: exclusive kt subjets above fkt x pt", "fkt=0.06,ptcut=40",
            a -> { final SubjetCountingKt t = new SubjetCountingKt(a.d("fkt", 0.06), a.d("ptcut", 40)); return perJet((j, c) -> t.result(j)); });
        jet("nca", "SubjetCounting", "n_CA: C/A subjets passing the mass and ycut conditions", "mcut=30,ycut=0.1,rmin=0.15,ptcut=40",
            a -> { final SubjetCountingCA t = new SubjetCountingCA(a.d("mcut", 30), a.d("ycut", 0.1), a.d("rmin", 0.15), a.d("ptcut", 40)); return perJet((j, c) -> t.result(j)); });
        jet("ffm", "JetFFMoments", "fragmentation-function moment M_N = sum z^N", "n=2",
            a -> { final JetFFMoments t = new JetFFMoments(new double[]{a.d("n", 2.0)}); return perJet((j, c) -> t.result(j)[0]); });

        // --- events ----------------------------------------------------------
        event("nparticles", "fastjet", "number of particles in the event", "", true,
            a -> perEvent(c -> (double) c.event().size()));
        event("njww", "JetsWithoutJets", "jet multiplicity without jets", "rjet=0.4,ptcut=25", true,
            a -> { final var t = new JetsWithoutJets.ShapeJetMultiplicity(a.d("rjet", 0.4), a.d("ptcut", 25)); return perEvent(c -> t.result(c.event())); });
        event("htww", "JetsWithoutJets", "HT without jets (GeV)", "rjet=0.4,ptcut=25", true,
            a -> { final var t = new JetsWithoutJets.ShapeScalarPt(a.d("rjet", 0.4), a.d("ptcut", 25)); return perEvent(c -> t.result(c.event())); });
        event("mhtww", "JetsWithoutJets", "missing HT without jets (GeV)", "rjet=0.4,ptcut=25", true,
            a -> { final var t = new JetsWithoutJets.ShapeMissingPt(a.d("rjet", 0.4), a.d("ptcut", 25)); return perEvent(c -> t.result(c.event())); });
        event("msumww", "JetsWithoutJets", "summed jet mass without jets (GeV)", "rjet=0.4,ptcut=25", true,
            a -> { final var t = new JetsWithoutJets.ShapeSummedMass(a.d("rjet", 0.4), a.d("ptcut", 25)); return perEvent(c -> t.result(c.event())); });
        event("rhogrid", "fastjet", "pileup density rho, grid median (GeV per unit area)", "rapmax=4.5,cell=0.55", true,
            a -> perEvent(c -> {
                final GridMedianBackgroundEstimator b = new GridMedianBackgroundEstimator(a.d("rapmax", 4.5), a.d("cell", 0.55));
                b.setParticles(c.event());
                return b.rho();
            }));
        event("rhojm", "fastjet", "pileup density rho, kt-jet median with areas", "rapmax=4.5,r=0.4", false,
            a -> perEvent(c -> {
                final double rapmax = a.d("rapmax", 4.5);
                final JetMedianBackgroundEstimator b = new JetMedianBackgroundEstimator(Selector.absRapMax(rapmax - a.d("r", 0.4)),
                    new JetDefinition(JetAlgorithm.KT, a.d("r", 0.4)),
                    new AreaDefinition(AreaDefinition.AreaType.ACTIVE_EXPLICIT_GHOSTS, new GhostedAreaSpec(rapmax, 1, 0.01)));
                b.setParticles(c.event());
                return b.rho();
            }));
        event("rhosf", "SignalFreeBackgroundEstimator", "pileup density rho, signal-free (seeds from the event's own hard jets)",
            "rapmax=4,cell=0.55,seedpt=20", false,
            a -> perEvent(c -> {
                final SignalFreeBackgroundEstimator b = new SignalFreeBackgroundEstimator(a.d("rapmax", 4.0), a.d("cell", 0.55));
                final List<PseudoJet> seeds = new ArrayList<>();
                for (PseudoJet j : new JetDefinition(JetAlgorithm.ANTIKT, 0.4).cluster(c.event())) {
                    if (j.pt() > a.d("seedpt", 20)) seeds.add(j);
                }
                b.addSeedsFromUser(seeds);
                b.setParticles(c.event());
                return b.rho();
            }));
        event("skcut", "SoftKiller", "the pt threshold SoftKiller applies (GeV)", "grid=0.4,rapmax=5", true,
            a -> perEvent(c -> new SoftKiller(a.d("rapmax", 5.0), a.d("grid", 0.4)).apply(c.event()).ptThreshold()));
    }

    private Observables() {
    }

    /** The catalogue, in its order. */
    public static List<Definition> all() {
        return new ArrayList<>(CATALOGUE.values());
    }

    public static Definition definition(String name) {
        return CATALOGUE.get(name.toLowerCase(Locale.ROOT));
    }

    /** The observables shown when none is named. */
    public static List<String> defaultSet() {
        return List.of("m", "tau21", "tau32", "c2", "d2", "n2", "sdmass", "zg", "rg", "nsd", "nlund");
    }

    /**
     * "name" or "name:key=value,key=value". The label keeps the parameters
     * that differ from the defaults, so that two versions of an observable
     * can sit side by side in a table.
     */
    public static Observable parse(String text) {
        final String t = text.trim();
        final int colon = t.indexOf(':');
        final String name = (colon < 0 ? t : t.substring(0, colon)).toLowerCase(Locale.ROOT);
        final Definition def = CATALOGUE.get(name);
        if (def == null) {
            throw new FastJetException("unknown observable '" + name + "'; ':fjco observables' lists them");
        }
        final Map<String, String> kv = new LinkedHashMap<>();
        if (colon >= 0) {
            for (String part : t.substring(colon + 1).split(",")) {
                final String p = part.trim();
                if (p.isEmpty()) continue;
                final int eq = p.indexOf('=');
                if (eq < 0) kv.put(p.toLowerCase(Locale.ROOT), "true");
                else kv.put(p.substring(0, eq).trim().toLowerCase(Locale.ROOT), p.substring(eq + 1).trim());
            }
        }
        final Args args = new Args(kv);
        // build once to refuse bad parameters now rather than in the middle of a sample
        args.s("axes", "wta_kt");
        if (kv.containsKey("axes")) axes(args.s("axes", "wta_kt"));
        return new Observable(def, args, kv.isEmpty() ? name : name + "(" + String.join(",", kv.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).toList()) + ")");
    }

    /** Several at once; an empty list gives the default set. */
    public static List<Observable> parseAll(List<String> texts) {
        final List<Observable> out = new ArrayList<>();
        for (String s : texts.isEmpty() ? defaultSet() : texts) out.add(parse(s));
        return out;
    }
}
