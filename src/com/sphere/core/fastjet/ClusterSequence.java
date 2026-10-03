package com.sphere.core.fastjet;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The clustering of a set of particles, and everything that can be asked of
 * it afterwards: inclusive and exclusive jets, subjets, constituents and the
 * whole history. The counterpart of fastjet::ClusterSequence.
 *
 * The history is kept exactly as FastJet keeps it: one element per initial
 * particle, then one per recombination, each naming its parents, its child,
 * the jet it produced and the distance d_ij at which it happened. In
 * {@link Precision#DD} mode those distances are held to 106 bits as well, so
 * dcut and ycut comparisons are made at that precision.
 */
public class ClusterSequence {

    /** History markers, as in the C++. */
    public static final int INVALID = -3;
    public static final int INEXISTENT_PARENT = -2;
    public static final int BEAM_JET = -1;

    static final double PI = Math.PI;
    static final double TWOPI = 2.0 * Math.PI;

    /** One step of the clustering, fastjet::ClusterSequence::history_element. */
    public static final class HistoryElement {
        int parent1;
        int parent2;
        int child;
        int jetpIndex;
        double dij, dijL;
        double maxDijSoFar, maxDijSoFarL;

        HistoryElement() {
        }

        HistoryElement(HistoryElement o) {
            parent1 = o.parent1;
            parent2 = o.parent2;
            child = o.child;
            jetpIndex = o.jetpIndex;
            dij = o.dij;
            dijL = o.dijL;
            maxDijSoFar = o.maxDijSoFar;
            maxDijSoFarL = o.maxDijSoFarL;
        }

        /** The history index of the first parent, or INEXISTENT_PARENT. */
        public int parent1() { return parent1; }
        /** The second parent, BEAM_JET for a recombination with the beam. */
        public int parent2() { return parent2; }
        /** The step that used this one, or INVALID. */
        public int child() { return child; }
        /** Where the jet this step produced sits in {@link #jets()}, or INVALID. */
        public int jetpIndex() { return jetpIndex; }
        /** The distance at which the step happened. */
        public double dij() { return dij; }
        public DD dijDD() { return new DD(dij, dijL); }
        /** The largest distance up to and including this step. */
        public double maxDijSoFar() { return maxDijSoFar; }
        public DD maxDijSoFarDD() { return new DD(maxDijSoFar, maxDijSoFarL); }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%d, %d) -> jet %d, child %d, dij %.10g",
                parent1, parent2, jetpIndex, child, dij);
        }
    }

    private static final LimitedWarning EXCLUSIVE_WARNINGS = new LimitedWarning();
    private static final LimitedWarning CHANGED_STRATEGY_WARNING = new LimitedWarning();
    private static final LimitedWarning NO_CGAL_WARNING = new LimitedWarning(1);
    private static volatile boolean bannerPrinted;
    private static volatile Consumer<String> bannerSink = System.out::println;
    /** Set on the thread of a check Sphere makes of itself, which is not the user's first clustering. */
    private static final ThreadLocal<Boolean> SELF_CHECK = ThreadLocal.withInitial(() -> false);

    protected JetDefinition jetDef;
    protected boolean writeoutCombinations;
    protected final ArrayList<PseudoJet> jets = new ArrayList<>();
    protected final ArrayList<HistoryElement> history = new ArrayList<>();
    protected int initialN;
    protected double rParam;
    protected double r2, r2L;
    protected double invR2, invR2L;
    protected double qtot, qtotL;
    protected Strategy strategy;
    protected JetAlgorithm jetAlgorithm;
    protected boolean pluginActivated;
    protected boolean dd;
    protected ClusterSequenceStructure structure;
    protected Object extras;

    /* ------------------------------------------------------------------ */
    /* Construction                                                        */
    /* ------------------------------------------------------------------ */

    /** For subclasses that fill the sequence themselves. */
    protected ClusterSequence() {
    }

    /** Clusters the particles with the definition given. */
    public ClusterSequence(List<? extends PseudoJet> particles, JetDefinition jetDef) {
        this(particles, jetDef, false);
    }

    public ClusterSequence(List<? extends PseudoJet> particles, JetDefinition jetDef,
                           boolean writeoutCombinations) {
        this.jetDef = new JetDefinition(jetDef);
        this.writeoutCombinations = writeoutCombinations;
        this.structure = new ClusterSequenceStructure(this);
        transferInputJets(particles);
        decantOptionsPartial();
        initialiseAndRunNoDecant();
    }

    /** Copies the input into the sequence, in the sequence's precision. */
    protected void transferInputJets(List<? extends PseudoJet> particles) {
        final Precision p = jetDef.precision();
        jets.ensureCapacity(particles.size() * 2);
        for (PseudoJet particle : particles) {
            PseudoJet copy = particle.copy();
            copy.setPrecision(p);
            jets.add(copy);
        }
    }

    protected void initialiseAndRun(JetDefinition jetDefIn, boolean writeout) {
        decantOptions(jetDefIn, writeout);
        initialiseAndRunNoDecant();
    }

    protected void decantOptions(JetDefinition jetDefIn, boolean writeout) {
        this.jetDef = new JetDefinition(jetDefIn);
        this.writeoutCombinations = writeout;
        this.structure = new ClusterSequenceStructure(this);
        decantOptionsPartial();
    }

    protected void decantOptionsPartial() {
        printBanner();
        jetAlgorithm = jetDef.jetAlgorithm();
        switch (jetAlgorithm) {
            case KT -> Citations.use("kt");
            case CAMBRIDGE, CAMBRIDGE_FOR_PASSIVE -> Citations.use("cambridge");
            case ANTIKT -> Citations.use("antikt");
            case GENKT, GENKT_FOR_PASSIVE -> Citations.use("genkt");
            case EE_KT -> Citations.use("eekt");
            case EE_GENKT -> Citations.use("eegenkt");
            default -> { } // plugins note their own credit
        }
        rParam = jetDef.R();
        dd = jetDef.precision() == Precision.DD;
        setR2(rParam);
        strategy = jetDef.strategy();
        pluginActivated = false;
    }

    private void setR2(double R) {
        r2 = R * R;
        if (dd) {
            r2L = DD.twoProdErr(R, R, r2);
            final DD inv = DD.ONE.div(new DD(r2, r2L));
            invR2 = inv.hi;
            invR2L = inv.lo;
        } else {
            r2L = 0.0;
            invR2 = 1.0 / r2;
            invR2L = 0.0;
        }
    }

    protected void initialiseAndRunNoDecant() {
        fillInitialHistory();
        if (nParticles() == 0) {
            return;
        }

        if (jetAlgorithm == JetAlgorithm.PLUGIN) {
            pluginActivated = true;
            jetDef.plugin().runClustering(this);
            pluginActivated = false;
            return;
        } else if (jetAlgorithm == JetAlgorithm.EE_KT || jetAlgorithm == JetAlgorithm.EE_GENKT) {
            if (jetAlgorithm == JetAlgorithm.EE_KT) {
                if (!(rParam > 2.0)) {
                    throw new FastJetException("ee_kt needs the fictional R > 2 that JetDefinition sets");
                }
                invR2 = 1.0;
                invR2L = 0.0;
            } else {
                if (dd) {
                    final DD R = new DD(rParam);
                    final DD rr = rParam > PI
                        ? R.cos().add(3.0).mulPow2(2.0)
                        : R.mulPow2(0.5).sin().sqr().mulPow2(4.0);
                    r2 = rr.hi;
                    r2L = rr.lo;
                    final DD inv = DD.ONE.div(rr);
                    invR2 = inv.hi;
                    invR2L = inv.lo;
                } else {
                    r2 = rParam > PI ? 2 * (3.0 + CRMath.cos(rParam)) : 2 * (1.0 - CRMath.cos(rParam));
                    invR2 = 1.0 / r2;
                }
            }
            if (strategy == Strategy.N2PLAIN_EE_ACCURATE) {
                new PlainN2Engine(this, true, true).run();
            } else {
                strategy = Strategy.N2PLAIN;
                new PlainN2Engine(this, true, false).run();
            }
            return;
        } else if (jetAlgorithm == JetAlgorithm.UNDEFINED) {
            throw new FastJetException("A ClusterSequence cannot be created with an uninitialised JetDefinition");
        }

        if (strategy == Strategy.BEST) {
            strategy = bestStrategy();
            // As a FastJet built without CGAL does.
            if (strategy == Strategy.NLNN) {
                strategy = Strategy.N2MHTLAZY25;
            }
        } else if (strategy == Strategy.BEST_FJ30) {
            final int n = jets.size();
            if (Math.min(1.0, Math.max(0.1, rParam) * 3.3) * n <= 30) {
                strategy = Strategy.N2PLAIN;
            } else if (n > 6200 / Math.pow(rParam, 2.0) && jetDef.jetAlgorithm() == JetAlgorithm.CAMBRIDGE) {
                strategy = Strategy.NLNNCAM;
            } else if (n <= 450) {
                strategy = Strategy.N2TILED;
            } else {
                strategy = Strategy.N2MINHEAPTILED;
            }
        }

        if (rParam >= TWOPI) {
            if (strategy == Strategy.NLNN || strategy == Strategy.NLNN3PI || strategy == Strategy.NLNNCAM
                    || strategy == Strategy.NLNNCAM2PI2R || strategy == Strategy.NLNNCAM4PI) {
                strategy = Strategy.N2MINHEAPTILED;
            }
            if (jetDef.strategy() != Strategy.BEST && strategy != jetDef.strategy()) {
                CHANGED_STRATEGY_WARNING.warn("Cluster strategy " + jetDef.strategy().label()
                    + " automatically changed to " + strategy.label()
                    + " because the former is not supported for R = " + Fmt.g(rParam) + " >= 2pi");
            }
        }

        if (strategy == Strategy.NLNN || strategy == Strategy.NLNN3PI || strategy == Strategy.NLNN4PI) {
            NO_CGAL_WARNING.warn("The " + strategy.label() + " strategy needs CGAL's Delaunay triangulation,"
                + " which the Java port does not carry; using N2MHTLazy25, which gives the same jets.");
            strategy = Strategy.N2MHTLAZY25;
        }

        switch (strategy) {
            case N2PLAIN -> new PlainN2Engine(this, false, false).run();
            case N2TILED -> new TiledEngine(this).fasterTiled();
            case N2MINHEAPTILED -> new TiledEngine(this).minheapFasterTiled();
            case N2POORTILED -> new TiledEngine(this).poorTiled();
            case N2MHTLAZY9 -> {
                pluginActivated = true;
                new LazyTiling9(this).run();
                pluginActivated = false;
            }
            case N2MHTLAZY9ALT -> {
                pluginActivated = true;
                new LazyTiling9Alt(this).run();
                pluginActivated = false;
            }
            case N2MHTLAZY25 -> {
                pluginActivated = true;
                new LazyTiling25(this).run();
                pluginActivated = false;
            }
            case N2MHTLAZY9_ANTIKT_SEPARATE_GHOSTS -> {
                pluginActivated = true;
                new LazyTiling9SeparateGhosts(this).run();
                pluginActivated = false;
            }
            case NLNNCAM -> new CP2DChanEngine(this).cluster2piMultD();
            case NLNNCAM4PI -> new CP2DChanEngine(this).cluster();
            case NLNNCAM2PI2R -> new CP2DChanEngine(this).cluster2pi2R();
            case N3DUMB -> reallyDumbCluster();
            default -> throw new FastJetException("Unrecognised value for strategy: " + strategy.id);
        }
    }

    /** The strategy FastJet 3.5 would pick for this multiplicity, R and algorithm. */
    Strategy bestStrategy() {
        final int n = jets.size();
        final double boundedR = Math.max(rParam, 0.1);
        if (n <= 30 || n <= 39.0 / (boundedR + 0.6)) {
            return Strategy.N2PLAIN;
        }
        JetAlgorithm alg;
        if (jetAlgorithm == JetAlgorithm.GENKT) {
            alg = jetDef.extraParam() < 0.0 ? JetAlgorithm.ANTIKT : JetAlgorithm.KT;
        } else if (jetAlgorithm == JetAlgorithm.CAMBRIDGE_FOR_PASSIVE) {
            alg = JetAlgorithm.KT;
        } else {
            alg = jetAlgorithm;
        }
        final double r = boundedR;
        if (r < 0.65) {
            if (n < parabola(-45.4947, 54.3528, 44.6283, r)) return Strategy.N2TILED;
            final double logN = Math.log(n);
            if (logN < parabola(0.677807, -1.05006, 10.6994, r)) return Strategy.N2MINHEAPTILED;
            if (alg == JetAlgorithm.ANTIKT) {
                if (logN < parabola(0.169967, -0.512589, 12.1572, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.0472051, -0.22043, 15.9196, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else if (alg == JetAlgorithm.KT) {
                if (logN < parabola(0.16237, -0.484612, 12.3373, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.118609, -0.326811, 14.8287, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else {
                if (logN < parabola(0.16237, -0.484612, 12.3373, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.10119, -0.295748, 14.3924, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNNCAM;
            }
        } else if (r < 0.5 * PI) {
            final double logN = Math.log(n);
            if (logN < -1.31304 * r + 7.29621) return Strategy.N2TILED;
            if (alg == JetAlgorithm.ANTIKT) {
                if (logN < parabola(0.169967, -0.512589, 12.1572, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.0472051, -0.22043, 15.9196, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else if (alg == JetAlgorithm.KT) {
                if (logN < parabola(0.16237, -0.484612, 12.3373, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.118609, -0.326811, 14.8287, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else {
                if (logN < parabola(0.16237, -0.484612, 12.3373, r)) return Strategy.N2MHTLAZY9;
                if (logN < parabola(0.10119, -0.295748, 14.3924, r)) return Strategy.N2MHTLAZY25;
                return Strategy.NLNNCAM;
            }
        } else {
            if (n < 75) return Strategy.N2PLAIN;
            if (alg == JetAlgorithm.ANTIKT) {
                if (n < 700) return Strategy.N2MHTLAZY9;
                if (n < 100000) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else if (alg == JetAlgorithm.KT) {
                if (n < 1000) return Strategy.N2MHTLAZY9;
                if (n < 40000) return Strategy.N2MHTLAZY25;
                return Strategy.NLNN;
            } else {
                if (n < 1000) return Strategy.N2MHTLAZY9;
                if (n < 15000) return Strategy.N2MHTLAZY25;
                return Strategy.NLNNCAM;
            }
        }
    }

    /** c (a R^2 + b R + 1), FastJet's _Parabola. */
    private static double parabola(double a, double b, double c, double R) {
        return c * (a * R * R + b * R + 1);
    }

    /** Sets up the history entries of the initial particles. */
    protected void fillInitialHistory() {
        jets.ensureCapacity(jets.size() * 2);
        history.ensureCapacity(jets.size() * 2);
        qtot = 0;
        qtotL = 0;
        for (int i = 0; i < jets.size(); i++) {
            final HistoryElement element = new HistoryElement();
            element.parent1 = INEXISTENT_PARENT;
            element.parent2 = INEXISTENT_PARENT;
            element.child = INVALID;
            element.jetpIndex = i;
            element.dij = 0.0;
            element.maxDijSoFar = 0.0;
            history.add(element);

            final PseudoJet jet = jets.get(i);
            jetDef.recombiner().preprocess(jet);
            jet.clusterHistIndex = i;
            jet.structure = structure;

            if (dd) {
                final double s = qtot + jet.e;
                final double err = DD.twoSumErr(qtot, jet.e, s) + qtotL + jet.eL;
                qtot = s + err;
                qtotL = err - (qtot - s);
            } else {
                qtot += jet.e;
            }
        }
        initialN = jets.size();
    }

    /* ------------------------------------------------------------------ */
    /* Banner                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Where the version line goes. FastJet's banner and the plugins' credits
     * are not printed: they are kept by Citations, for the console's menu.
     */
    public static void setBannerSink(Consumer<String> sink) {
        bannerSink = sink == null ? System.out::println : sink;
    }

    /**
     * Runs a check of the engine on this thread without spending the version
     * line: a probe at startup or from ':fjet ping' leaves it for the user's
     * own first clustering.
     */
    public static <T> T selfCheck(java.util.concurrent.Callable<T> check) throws Exception {
        final boolean outer = SELF_CHECK.get();
        SELF_CHECK.set(true);
        try {
            return check.call();
        } finally {
            SELF_CHECK.set(outer);
        }
    }

    /** Prints the version line, once per session. */
    public static void printBanner() {
        if (bannerPrinted || SELF_CHECK.get()) {
            return;
        }
        synchronized (ClusterSequence.class) {
            if (bannerPrinted) {
                return;
            }
            bannerPrinted = true;
        }
        bannerSink.accept(FastJet.banner());
    }

    /* ------------------------------------------------------------------ */
    /* Scales                                                              */
    /* ------------------------------------------------------------------ */

    /** The per-jet factor of the distance, kt^2p in the generalised form. */
    public double jetScaleForAlgorithm(PseudoJet jet) {
        if (jetAlgorithm == JetAlgorithm.KT) {
            return jet.kt2();
        } else if (jetAlgorithm == JetAlgorithm.CAMBRIDGE) {
            return 1.0;
        } else if (jetAlgorithm == JetAlgorithm.ANTIKT) {
            final double kt2 = jet.kt2();
            return kt2 > 1e-300 ? 1.0 / kt2 : 1e300;
        } else if (jetAlgorithm == JetAlgorithm.GENKT) {
            double kt2 = jet.kt2();
            final double p = jetDef.extraParam();
            if (p <= 0 && kt2 < 1e-300) kt2 = 1e-300;
            return CRMath.pow(kt2, p);
        } else if (jetAlgorithm == JetAlgorithm.CAMBRIDGE_FOR_PASSIVE) {
            final double kt2 = jet.kt2();
            final double lim = jetDef.extraParam();
            if (kt2 < lim * lim && kt2 != 0.0) {
                return 1.0 / kt2;
            }
            return 1.0;
        }
        throw new FastJetException("Unrecognised jet algorithm");
    }

    /** The same to 106 bits. */
    public DD jetScaleForAlgorithmDD(PseudoJet jet) {
        final DD kt2 = jet.kt2DD();
        switch (jetAlgorithm) {
            case KT:
                return kt2;
            case CAMBRIDGE:
                return DD.ONE;
            case ANTIKT:
                return kt2.gt(1e-300) ? DD.ONE.div(kt2) : new DD(1e300);
            case GENKT: {
                DD k = kt2;
                final double p = jetDef.extraParam();
                if (p <= 0 && k.lt(1e-300)) k = new DD(1e-300);
                return k.pow(p);
            }
            case CAMBRIDGE_FOR_PASSIVE: {
                final double lim = jetDef.extraParam();
                if (kt2.lt(DD.prod(lim, lim)) && !kt2.isZero()) {
                    return DD.ONE.div(kt2);
                }
                return DD.ONE;
            }
            default:
                throw new FastJetException("Unrecognised jet algorithm");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Inclusive and exclusive jets                                        */
    /* ------------------------------------------------------------------ */

    public List<PseudoJet> inclusiveJets() {
        return inclusiveJets(0.0);
    }

    /** Every inclusive jet with pt >= ptmin, in the order the history holds them. */
    public List<PseudoJet> inclusiveJets(double ptmin) {
        final double dcut = ptmin * ptmin;
        int i = history.size() - 1;
        final List<PseudoJet> out = new ArrayList<>();
        if (jetAlgorithm == JetAlgorithm.KT) {
            while (i >= 0) {
                final HistoryElement h = history.get(i);
                if (lt(h.maxDijSoFar, h.maxDijSoFarL, dcut)) {
                    break;
                }
                if (h.parent2 == BEAM_JET && ge(h.dij, h.dijL, dcut)) {
                    out.add(jets.get(history.get(h.parent1).jetpIndex).copy());
                }
                i--;
            }
        } else if (jetAlgorithm == JetAlgorithm.CAMBRIDGE) {
            while (i >= 0) {
                final HistoryElement h = history.get(i);
                if (h.parent2 != BEAM_JET) {
                    break;
                }
                final PseudoJet jet = jets.get(history.get(h.parent1).jetpIndex);
                if (ge(jet.kt2, jet.kt2L, dcut)) {
                    out.add(jet.copy());
                }
                i--;
            }
        } else if (jetAlgorithm == JetAlgorithm.PLUGIN || jetAlgorithm == JetAlgorithm.EE_KT
                || jetAlgorithm == JetAlgorithm.ANTIKT || jetAlgorithm == JetAlgorithm.GENKT
                || jetAlgorithm == JetAlgorithm.EE_GENKT || jetAlgorithm == JetAlgorithm.CAMBRIDGE_FOR_PASSIVE
                || jetAlgorithm == JetAlgorithm.GENKT_FOR_PASSIVE) {
            while (i >= 0) {
                final HistoryElement h = history.get(i);
                if (h.parent2 == BEAM_JET) {
                    final PseudoJet jet = jets.get(history.get(h.parent1).jetpIndex);
                    if (ge(jet.kt2, jet.kt2L, dcut)) {
                        out.add(jet.copy());
                    }
                }
                i--;
            }
        } else {
            throw new FastJetException("cs::inclusive_jets(...): Unrecognized jet algorithm");
        }
        return out;
    }

    /** The number of exclusive jets a clustering stopped at dcut would give. */
    public int nExclusiveJets(double dcut) {
        int i = history.size() - 1;
        while (i >= 0) {
            final HistoryElement h = history.get(i);
            if (le(h.maxDijSoFar, h.maxDijSoFarL, dcut)) {
                break;
            }
            i--;
        }
        final int stopPoint = i + 1;
        return 2 * initialN - stopPoint;
    }

    public List<PseudoJet> exclusiveJets(double dcut) {
        return exclusiveJets(nExclusiveJets(dcut));
    }

    /** The jets of a clustering stopped at n jets. */
    public List<PseudoJet> exclusiveJets(int njets) {
        if (njets > initialN) {
            throw new FastJetException("Requested " + njets + " exclusive jets, but there were only "
                + initialN + " particles in the event");
        }
        return exclusiveJetsUpTo(njets);
    }

    /** The same, or every particle if there are fewer than n. */
    public List<PseudoJet> exclusiveJetsUpTo(int njets) {
        final JetAlgorithm alg = jetDef.jetAlgorithm();
        if (alg != JetAlgorithm.KT && alg != JetAlgorithm.CAMBRIDGE && alg != JetAlgorithm.EE_KT
                && ((alg != JetAlgorithm.GENKT && alg != JetAlgorithm.EE_GENKT) || jetDef.extraParam() < 0)
                && (alg != JetAlgorithm.PLUGIN || !jetDef.plugin().exclusiveSequenceMeaningful())) {
            EXCLUSIVE_WARNINGS.warn("dcut and exclusive jets for jet-finders other than kt, C/A or genkt with p>=0 should be interpreted with care.");
        }
        int stopPoint = 2 * initialN - njets;
        if (stopPoint < initialN) {
            stopPoint = initialN;
        }
        if (2 * initialN != history.size()) {
            throw new FastJetException("2*_initial_n != _history.size() -- this endangers internal assumptions!\n");
        }
        final List<PseudoJet> out = new ArrayList<>();
        for (int i = stopPoint; i < history.size(); i++) {
            final int parent1 = history.get(i).parent1;
            if (parent1 < stopPoint) {
                out.add(jets.get(history.get(parent1).jetpIndex).copy());
            }
            final int parent2 = history.get(i).parent2;
            if (parent2 < stopPoint && parent2 > 0) {
                out.add(jets.get(history.get(parent2).jetpIndex).copy());
            }
        }
        if (out.size() != Math.min(initialN, njets)) {
            throw new FastJetException("ClusterSequence::exclusive_jets: size of returned vector ("
                + out.size() + ") does not coincide with requested number of jets (" + njets + ")");
        }
        return out;
    }

    /** The d_min of the recombination that went from n+1 to n jets. */
    public double exclusiveDmerge(int njets) {
        if (njets < 0) throw new FastJetException("exclusive_dmerge: negative number of jets");
        if (njets >= initialN) return 0.0;
        return history.get(2 * initialN - njets - 1).dij;
    }

    public DD exclusiveDmergeDD(int njets) {
        if (njets < 0) throw new FastJetException("exclusive_dmerge: negative number of jets");
        if (njets >= initialN) return DD.ZERO;
        return history.get(2 * initialN - njets - 1).dijDD();
    }

    /** The largest d_min up to the n+1 -> n recombination. */
    public double exclusiveDmergeMax(int njets) {
        if (njets < 0) throw new FastJetException("exclusive_dmerge_max: negative number of jets");
        if (njets >= initialN) return 0.0;
        return history.get(2 * initialN - njets - 1).maxDijSoFar;
    }

    public double exclusiveYmerge(int njets) {
        return dd ? exclusiveDmergeDD(njets).div(q2DD()).hi : exclusiveDmerge(njets) / Q2();
    }

    public double exclusiveYmergeMax(int njets) {
        if (dd) {
            if (njets >= initialN) return 0.0;
            return history.get(2 * initialN - njets - 1).maxDijSoFarDD().div(q2DD()).hi;
        }
        return exclusiveDmergeMax(njets) / Q2();
    }

    public int nExclusiveJetsYcut(double ycut) {
        return nExclusiveJets(ycut * Q2());
    }

    public List<PseudoJet> exclusiveJetsYcut(double ycut) {
        return exclusiveJets(nExclusiveJetsYcut(ycut));
    }

    /* ------------------------------------------------------------------ */
    /* Subjets                                                             */
    /* ------------------------------------------------------------------ */

    /** The subjets of a jet resolved at dcut. */
    public List<PseudoJet> exclusiveSubjets(PseudoJet jet, double dcut) {
        return jetsOf(subhistSet(jet, dcut, 0));
    }

    public int nExclusiveSubjets(PseudoJet jet, double dcut) {
        return subhistSet(jet, dcut, 0).size();
    }

    /** The jet undone into nsub subjets; an error if it has fewer constituents. */
    public List<PseudoJet> exclusiveSubjets(PseudoJet jet, int nsub) {
        final List<PseudoJet> subjets = exclusiveSubjetsUpTo(jet, nsub);
        if (subjets.size() < nsub) {
            throw new FastJetException("Requested " + nsub + " exclusive subjets, but there were only "
                + subjets.size() + " particles in the jet");
        }
        return subjets;
    }

    public List<PseudoJet> exclusiveSubjetsUpTo(PseudoJet jet, int nsub) {
        if (nsub < 0) throw new FastJetException("Requested a negative number of subjets. This is nonsensical.");
        if (nsub == 0) return new ArrayList<>();
        return jetsOf(subhistSet(jet, -1.0, nsub));
    }

    /** The d_ij of the nsub+1 -> nsub merging inside the jet. */
    public double exclusiveSubdmerge(PseudoJet jet, int nsub) {
        final TreeSet<Integer> sub = subhistSet(jet, -1.0, nsub);
        return history.get(sub.last()).dij;
    }

    public double exclusiveSubdmergeMax(PseudoJet jet, int nsub) {
        final TreeSet<Integer> sub = subhistSet(jet, -1.0, nsub);
        return history.get(sub.last()).maxDijSoFar;
    }

    private List<PseudoJet> jetsOf(TreeSet<Integer> subhist) {
        final List<PseudoJet> out = new ArrayList<>(subhist.size());
        for (int h : subhist) {
            out.add(jets.get(history.get(h).jetpIndex).copy());
        }
        return out;
    }

    /**
     * The history elements of the subjets: the latest element is undone into its
     * parents until there is none, maxjet are found, or it is not resolved at dcut.
     */
    TreeSet<Integer> subhistSet(PseudoJet jet, double dcut, int maxjet) {
        if (!contains(jet)) {
            throw new FastJetException("The jet does not belong to this ClusterSequence");
        }
        final TreeSet<Integer> subhist = new TreeSet<>();
        subhist.add(jet.clusterHistIndex);
        int njet = 1;
        while (true) {
            final int highest = subhist.last();
            final HistoryElement elem = history.get(highest);
            if (njet == maxjet) break;
            if (elem.parent1 < 0) break;
            if (le(elem.maxDijSoFar, elem.maxDijSoFarL, dcut)) break;
            subhist.pollLast();
            subhist.add(elem.parent1);
            subhist.add(elem.parent2);
            njet++;
        }
        return subhist;
    }

    /* ------------------------------------------------------------------ */
    /* Relations between jets                                              */
    /* ------------------------------------------------------------------ */

    /** True if object is part of jet. */
    public boolean objectInJet(PseudoJet object, PseudoJet jet) {
        if (!contains(object) || !contains(jet)) {
            throw new FastJetException("object_in_jet: jets not from this ClusterSequence");
        }
        int h = object.clusterHistIndex;
        while (true) {
            if (h == jet.clusterHistIndex) {
                return true;
            }
            final int child = history.get(h).child;
            if (child >= 0 && history.get(child).jetpIndex >= 0) {
                h = jets.get(history.get(child).jetpIndex).clusterHistIndex;
            } else {
                return false;
            }
        }
    }

    /** The two parents, harder first, or null for an initial particle. */
    public PseudoJet[] parents(PseudoJet jet) {
        final HistoryElement hist = history.get(jet.clusterHistIndex);
        if (hist.parent1 < 0) {
            return null;
        }
        PseudoJet p1 = jets.get(history.get(hist.parent1).jetpIndex).copy();
        PseudoJet p2 = jets.get(history.get(hist.parent2).jetpIndex).copy();
        if (p1.kt2DD().lt(p2.kt2DD())) {
            final PseudoJet t = p1;
            p1 = p2;
            p2 = t;
        }
        return new PseudoJet[]{p1, p2};
    }

    /** What the jet was merged into, or null. */
    public PseudoJet child(PseudoJet jet) {
        final HistoryElement hist = history.get(jet.clusterHistIndex);
        if (hist.child >= 0 && history.get(hist.child).jetpIndex >= 0) {
            return jets.get(history.get(hist.child).jetpIndex).copy();
        }
        return null;
    }

    /** What the jet was merged with, or null. */
    public PseudoJet partner(PseudoJet jet) {
        final HistoryElement hist = history.get(jet.clusterHistIndex);
        if (hist.child >= 0 && history.get(hist.child).parent2 >= 0) {
            final HistoryElement childHist = history.get(hist.child);
            if (childHist.parent1 == jet.clusterHistIndex) {
                return jets.get(history.get(childHist.parent2).jetpIndex).copy();
            }
            return jets.get(history.get(childHist.parent1).jetpIndex).copy();
        }
        return null;
    }

    /** The initial particles the jet is made of, in the order the history reaches them. */
    public List<PseudoJet> constituents(PseudoJet jet) {
        final List<PseudoJet> out = new ArrayList<>();
        addConstituents(jet, out);
        return out;
    }

    /** Appends the jet's constituents; iterative, so deep ghosted trees are safe. */
    public void addConstituents(PseudoJet jet, List<PseudoJet> out) {
        final ArrayList<Integer> stack = new ArrayList<>();
        stack.add(jet.clusterHistIndex);
        while (!stack.isEmpty()) {
            final int i = stack.remove(stack.size() - 1);
            final HistoryElement h = history.get(i);
            if (h.parent1 == INEXISTENT_PARENT) {
                out.add(jets.get(i).copy());
                continue;
            }
            if (h.parent2 != BEAM_JET) {
                stack.add(jets.get(history.get(h.parent2).jetpIndex).clusterHistIndex);
            }
            stack.add(jets.get(history.get(h.parent1).jetpIndex).clusterHistIndex);
        }
    }

    /** For each initial particle, the index of the jet it belongs to, or -1. */
    public int[] particleJetIndices(List<PseudoJet> jetsIn) {
        final int[] indices = new int[nParticles()];
        java.util.Arrays.fill(indices, -1);
        for (int ijet = 0; ijet < jetsIn.size(); ijet++) {
            for (PseudoJet c : constituents(jetsIn.get(ijet))) {
                indices[history.get(c.clusterHistIndex).jetpIndex] = ijet;
            }
        }
        return indices;
    }

    /** Initial particles never clustered, which only a plugin may leave. */
    public List<PseudoJet> unclusteredParticles() {
        final List<PseudoJet> out = new ArrayList<>();
        for (int i = 0; i < nParticles(); i++) {
            if (history.get(i).child == INVALID) {
                out.add(jets.get(history.get(i).jetpIndex).copy());
            }
        }
        return out;
    }

    /** The pseudojets without children that are not inclusive jets. */
    public List<PseudoJet> childlessPseudojets() {
        final List<PseudoJet> out = new ArrayList<>();
        for (HistoryElement h : history) {
            if (h.child == INVALID && h.parent2 != BEAM_JET) {
                out.add(jets.get(h.jetpIndex).copy());
            }
        }
        return out;
    }

    /** True if the jet was produced by this clustering. */
    public boolean contains(PseudoJet jet) {
        return jet.clusterHistIndex >= 0 && jet.clusterHistIndex < history.size()
            && jet.hasValidClusterSequence() && jet.associatedClusterSequence() == this;
    }

    /* ------------------------------------------------------------------ */
    /* The history in a canonical order                                    */
    /* ------------------------------------------------------------------ */

    /**
     * An order in which to read the history such that two equivalent
     * histories list the same sets of particles at the same positions.
     */
    public int[] uniqueHistoryOrder() {
        final int histN = history.size();
        final int[] lowest = new int[histN];
        java.util.Arrays.fill(lowest, histN);
        for (int i = 0; i < histN; i++) {
            lowest[i] = Math.min(lowest[i], i);
            final int c = history.get(i).child;
            if (c > 0) {
                lowest[c] = Math.min(lowest[c], lowest[i]);
            }
        }
        final boolean[] extracted = new boolean[histN];
        final int[] tree = new int[histN];
        int n = 0;
        for (int i = 0; i < nParticles(); i++) {
            if (!extracted[i]) {
                tree[n++] = i;
                extracted[i] = true;
                n = extractTreeChildren(i, extracted, lowest, tree, n);
            }
        }
        return n == histN ? tree : java.util.Arrays.copyOf(tree, n);
    }

    private int extractTreeChildren(int position, boolean[] extracted, int[] lowest, int[] tree, int n) {
        int pos = position;
        while (true) {
            if (!extracted[pos]) {
                n = extractTreeParents(pos, extracted, lowest, tree, n);
            }
            final int child = history.get(pos).child;
            if (child < 0) {
                return n;
            }
            pos = child;
        }
    }

    private int extractTreeParents(int position, boolean[] extracted, int[] lowest, int[] tree, int n) {
        // Post-order walk, the recursion of the C++ made explicit.
        int[] posStack = new int[16];
        int[] stateStack = new int[16];
        int[] secondStack = new int[16];
        int top = 0;
        posStack[0] = position;
        stateStack[0] = 0;
        while (top >= 0) {
            final int pos = posStack[top];
            if (stateStack[top] == 0) {
                if (extracted[pos]) {
                    top--;
                    continue;
                }
                int p1 = history.get(pos).parent1;
                int p2 = history.get(pos).parent2;
                if (p1 >= 0 && p2 >= 0 && lowest[p1] > lowest[p2]) {
                    final int t = p1;
                    p1 = p2;
                    p2 = t;
                }
                secondStack[top] = p2;
                stateStack[top] = 1;
                if (p1 >= 0 && !extracted[p1]) {
                    top++;
                    if (top == posStack.length) {
                        posStack = java.util.Arrays.copyOf(posStack, top * 2);
                        stateStack = java.util.Arrays.copyOf(stateStack, top * 2);
                        secondStack = java.util.Arrays.copyOf(secondStack, top * 2);
                    }
                    posStack[top] = p1;
                    stateStack[top] = 0;
                }
            } else if (stateStack[top] == 1) {
                stateStack[top] = 2;
                final int p2 = secondStack[top];
                if (p2 >= 0 && !extracted[p2]) {
                    top++;
                    if (top == posStack.length) {
                        posStack = java.util.Arrays.copyOf(posStack, top * 2);
                        stateStack = java.util.Arrays.copyOf(stateStack, top * 2);
                        secondStack = java.util.Arrays.copyOf(secondStack, top * 2);
                    }
                    posStack[top] = p2;
                    stateStack[top] = 0;
                }
            } else {
                tree[n++] = pos;
                extracted[pos] = true;
                top--;
            }
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* Access                                                              */
    /* ------------------------------------------------------------------ */

    /** Every pseudojet of the clustering, copies. */
    public List<PseudoJet> jets() {
        final List<PseudoJet> out = new ArrayList<>(jets.size());
        for (PseudoJet j : jets) out.add(j.copy());
        return out;
    }

    /** The pseudojet at an index of {@link #jets()}, a copy. */
    public PseudoJet jet(int index) {
        return jets.get(index).copy();
    }

    public int nJets() {
        return jets.size();
    }

    /** The history, read-only. */
    public List<HistoryElement> history() {
        return Collections.unmodifiableList(history);
    }

    public int nParticles() {
        return initialN;
    }

    /** The total energy of the event. */
    public double Q() {
        return qtot;
    }

    public double Q2() {
        return qtot * qtot;
    }

    /** The squared total energy to 106 bits. */
    public DD q2DD() {
        return new DD(qtot, qtotL).sqr();
    }

    public Strategy strategyUsed() {
        return strategy;
    }

    public String strategyString() {
        return strategy.label();
    }

    public static String strategyString(Strategy s) {
        return s.label();
    }

    public JetDefinition jetDef() {
        return jetDef;
    }

    public Precision precision() {
        return dd ? Precision.DD : Precision.DOUBLE;
    }

    public ClusterSequenceStructure structure() {
        return structure;
    }

    /** Kept for API familiarity: memory is the garbage collector's business here. */
    public void deleteSelfWhenUnused() {
    }

    public boolean willDeleteSelfWhenUnused() {
        return false;
    }

    /** The extra information a plugin attached, or null. */
    public Object extras() {
        return extras;
    }

    /* ------------------------------------------------------------------ */
    /* The plugin interface                                                */
    /* ------------------------------------------------------------------ */

    public boolean pluginActivated() {
        return pluginActivated;
    }

    /** Records that jets i and j (indices into jets()) merged at dij; returns the new jet's index. */
    public int pluginRecordIJRecombination(int jetI, int jetJ, double dij) {
        requirePlugin();
        return doIJRecombinationStep(jetI, jetJ, dij, 0.0);
    }

    /** The same, the new jet's momentum being given rather than computed. */
    public int pluginRecordIJRecombination(int jetI, int jetJ, double dij, PseudoJet newjet) {
        final int newjetK = pluginRecordIJRecombination(jetI, jetJ, dij);
        final PseudoJet placed = newjet.copy();
        placed.setPrecision(precision());
        placed.clusterHistIndex = jets.get(newjetK).clusterHistIndex;
        placed.structure = structure;
        jets.set(newjetK, placed);
        return newjetK;
    }

    /** Records that jet i (an index into jets()) merged with the beam. */
    public void pluginRecordIBRecombination(int jetI, double diB) {
        requirePlugin();
        doIBRecombinationStep(jetI, diB, 0.0);
    }

    /**
     * Records that jets i and j merged at a distance known to 106 bits; under
     * {@link Precision#DOUBLE} only its high word is kept, as FastJet would.
     */
    public int pluginRecordIJRecombination(int jetI, int jetJ, DD dij) {
        requirePlugin();
        return doIJRecombinationStep(jetI, jetJ, dij.hi, dd ? dij.lo : 0.0);
    }

    /** The DD distance, the new jet's momentum being given rather than computed. */
    public int pluginRecordIJRecombination(int jetI, int jetJ, DD dij, PseudoJet newjet) {
        final int newjetK = pluginRecordIJRecombination(jetI, jetJ, dij);
        final PseudoJet placed = newjet.copy();
        placed.setPrecision(precision());
        placed.clusterHistIndex = jets.get(newjetK).clusterHistIndex;
        placed.structure = structure;
        jets.set(newjetK, placed);
        return newjetK;
    }

    /** Records a beam recombination at a distance known to 106 bits. */
    public void pluginRecordIBRecombination(int jetI, DD diB) {
        requirePlugin();
        doIBRecombinationStep(jetI, diB.hi, dd ? diB.lo : 0.0);
    }

    public void pluginAssociateExtras(Object extrasIn) {
        this.extras = extrasIn;
    }

    /**
     * The jet itself, not a copy, for a plugin to change, e.g. its user
     * information, fastjet::ClusterSequence::plugin_non_const_jet.
     */
    public PseudoJet pluginNonConstJet(int index) {
        requirePlugin();
        return jets.get(index);
    }

    private void requirePlugin() {
        if (!pluginActivated) {
            throw new FastJetException("plugin_record_... called outside a plugin's run_clustering");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Recording steps, used by every strategy                             */
    /* ------------------------------------------------------------------ */

    int doIJRecombinationStep(int jetI, int jetJ, double dijH, double dijL) {
        final PseudoJet newjet = new PseudoJet(0, 0, 0, 0, precision());
        jetDef.recombiner().recombine(jets.get(jetI), jets.get(jetJ), newjet);
        if (newjet.dd != dd) {
            newjet.setPrecision(precision());
        }
        jets.add(newjet);
        final int newjetK = jets.size() - 1;
        final int newstepK = history.size();
        newjet.clusterHistIndex = newstepK;
        final int histI = jets.get(jetI).clusterHistIndex;
        final int histJ = jets.get(jetJ).clusterHistIndex;
        addStepToHistory(Math.min(histI, histJ), Math.max(histI, histJ), newjetK, dijH, dijL);
        return newjetK;
    }

    void doIBRecombinationStep(int jetI, double diBH, double diBL) {
        addStepToHistory(jets.get(jetI).clusterHistIndex, BEAM_JET, INVALID, diBH, diBL);
    }

    void addStepToHistory(int parent1, int parent2, int jetpIndex, double dijH, double dijL) {
        final HistoryElement element = new HistoryElement();
        element.parent1 = parent1;
        element.parent2 = parent2;
        element.jetpIndex = jetpIndex;
        element.child = INVALID;
        element.dij = dijH;
        element.dijL = dijL;
        final HistoryElement last = history.get(history.size() - 1);
        if (lt(dijH, dijL, last.maxDijSoFar, last.maxDijSoFarL)) {
            element.maxDijSoFar = last.maxDijSoFar;
            element.maxDijSoFarL = last.maxDijSoFarL;
        } else {
            element.maxDijSoFar = dijH;
            element.maxDijSoFarL = dijL;
        }
        history.add(element);
        final int localStep = history.size() - 1;

        if (parent1 < 0) {
            throw new FastJetException.Internal("recombination step with an invalid first parent");
        }
        if (history.get(parent1).child != INVALID) {
            throw new FastJetException.Internal("trying to recombine an object that has previously been recombined");
        }
        history.get(parent1).child = localStep;
        if (parent2 >= 0) {
            if (history.get(parent2).child != INVALID) {
                throw new FastJetException.Internal("trying to recombine an object that has previously been recombined");
            }
            history.get(parent2).child = localStep;
        }
        if (jetpIndex != INVALID) {
            final PseudoJet j = jets.get(jetpIndex);
            j.clusterHistIndex = localStep;
            j.structure = structure;
        }
        if (writeoutCombinations) {
            System.out.println(localStep + ": " + parent1 + " with " + parent2 + "; y = " + Fmt.g(dijH));
        }
    }

    /** The jets a strategy works on; engines read and extend it directly. */
    final ArrayList<PseudoJet> internalJets() {
        return jets;
    }

    /* ------------------------------------------------------------------ */
    /* N3Dumb                                                              */
    /* ------------------------------------------------------------------ */

    /** The simplest algorithm, every pair every time: a reference for the others. */
    void reallyDumbCluster() {
        final int size = jets.size();
        final int[] jetsp = new int[size];
        for (int i = 0; i < size; i++) jetsp[i] = i;
        for (int n = size; n > 0; n--) {
            int ii = 0;
            int jj = -2;
            DD ymin = scaleOf(jetsp[0]);
            for (int i = 0; i < n; i++) {
                final DD yiB = scaleOf(jetsp[i]);
                if (yiB.lt(ymin)) {
                    ymin = yiB;
                    ii = i;
                    jj = -2;
                }
            }
            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 1; j < n; j++) {
                    final DD y = pairDistance(jetsp[i], jetsp[j]);
                    if (y.lt(ymin)) {
                        ymin = y;
                        ii = i;
                        jj = j;
                    }
                }
            }
            if (jj >= 0) {
                final int nn = doIJRecombinationStep(jetsp[ii], jetsp[jj], ymin.hi, ymin.lo);
                jetsp[ii] = nn;
                jetsp[jj] = jetsp[n - 1];
            } else {
                doIBRecombinationStep(jetsp[ii], ymin.hi, ymin.lo);
                jetsp[ii] = jetsp[n - 1];
            }
        }
    }

    private DD scaleOf(int jetIndex) {
        return dd ? jetScaleForAlgorithmDD(jets.get(jetIndex)) : new DD(jetScaleForAlgorithm(jets.get(jetIndex)));
    }

    private DD pairDistance(int a, int b) {
        final PseudoJet ja = jets.get(a);
        final PseudoJet jb = jets.get(b);
        if (dd) {
            return DD.min(jetScaleForAlgorithmDD(ja), jetScaleForAlgorithmDD(jb))
                .mul(ja.squaredDistanceDD(jb)).mul(new DD(invR2, invR2L));
        }
        return new DD(Math.min(jetScaleForAlgorithm(ja), jetScaleForAlgorithm(jb))
            * ja.plainDistance(jb) * invR2);
    }

    /* ------------------------------------------------------------------ */
    /* Output for ROOT                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The jets and their constituents in the text form FastJet's ROOT scripts
     * read: a line per jet (index px py pz E), a line per constituent
     * (index rap phi pt), and #END after each jet.
     */
    public void printJetsForRoot(List<PseudoJet> jetsIn, PrintStream out) {
        final PrintWriter w = new PrintWriter(out, true, StandardCharsets.UTF_8);
        writeJetsForRoot(jetsIn, w);
        w.flush();
    }

    public void printJetsForRoot(List<PseudoJet> jetsIn, Path file, String comment) throws IOException {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (comment != null && !comment.isEmpty()) {
                w.println("# " + comment);
            }
            writeJetsForRoot(jetsIn, w);
        }
    }

    private void writeJetsForRoot(List<PseudoJet> jetsIn, PrintWriter w) {
        for (int i = 0; i < jetsIn.size(); i++) {
            final PseudoJet j = jetsIn.get(i);
            w.println(i + " " + Fmt.g(j.px()) + " " + Fmt.g(j.py()) + " " + Fmt.g(j.pz()) + " " + Fmt.g(j.E()));
            final List<PseudoJet> cst = constituents(j);
            for (int k = 0; k < cst.size(); k++) {
                final PseudoJet c = cst.get(k);
                w.println(" " + k + " " + Fmt.g(c.rap()) + " " + Fmt.g(c.phi()) + " " + Fmt.g(c.perp()));
            }
            w.println("#END");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Copying                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * A copy of a clustering with every pseudojet transformed, for instance
     * boosted back to the lab; the history is the same. As transfer_from_sequence
     * with an action.
     */
    public static ClusterSequence transformedCopy(ClusterSequence from, FunctionOfPseudoJet<PseudoJet> action) {
        final ClusterSequence cs = new ClusterSequence();
        cs.transferFromSequence(from);
        if (action != null) {
            for (int i = 0; i < cs.jets.size(); i++) {
                final PseudoJet t = action.result(from.jets.get(i)).copy();
                t.clusterHistIndex = from.jets.get(i).clusterHistIndex;
                t.structure = cs.structure;
                cs.jets.set(i, t);
            }
        }
        return cs;
    }

    /** Takes over the whole state of another sequence; its jets now point here. */
    protected void transferFromSequence(ClusterSequence from) {
        jetDef = new JetDefinition(from.jetDef);
        writeoutCombinations = from.writeoutCombinations;
        initialN = from.initialN;
        rParam = from.rParam;
        r2 = from.r2;
        r2L = from.r2L;
        invR2 = from.invR2;
        invR2L = from.invR2L;
        strategy = from.strategy;
        jetAlgorithm = from.jetAlgorithm;
        pluginActivated = from.pluginActivated;
        dd = from.dd;
        qtot = from.qtot;
        qtotL = from.qtotL;
        extras = from.extras;
        structure = new ClusterSequenceStructure(this);
        jets.clear();
        for (PseudoJet j : from.jets) {
            final PseudoJet c = j.copy();
            c.structure = structure;
            jets.add(c);
        }
        history.clear();
        for (HistoryElement h : from.history) {
            history.add(new HistoryElement(h));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Comparisons of pairs against doubles                                */
    /* ------------------------------------------------------------------ */

    static boolean lt(double h, double l, double x) {
        return h < x || (h == x && l < 0.0);
    }

    static boolean le(double h, double l, double x) {
        return h < x || (h == x && l <= 0.0);
    }

    static boolean ge(double h, double l, double x) {
        return h > x || (h == x && l >= 0.0);
    }

    static boolean lt(double ah, double al, double bh, double bl) {
        return ah < bh || (ah == bh && al < bl);
    }
}
