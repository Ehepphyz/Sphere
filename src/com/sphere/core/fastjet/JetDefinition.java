package com.sphere.core.fastjet;

import java.util.List;
import java.util.Locale;

/**
 * An algorithm with its parameters, recombination and strategy: everything a
 * clustering needs besides the particles. The counterpart of
 * fastjet::JetDefinition, with one addition, the {@link Precision} the
 * clustering is carried out in.
 */
public class JetDefinition {

    /** The largest R accepted, beyond which the tiling would make no sense. */
    public static final double MAX_ALLOWABLE_R = 1000.0;

    private JetAlgorithm jetAlgorithm;
    private double rParam;
    private double extraParam;
    private Strategy strategy;
    private Recombiner recombiner;
    /** True when the recombiner was supplied rather than chosen by scheme. */
    private boolean externalRecombiner;
    private Plugin plugin;
    private Precision precision;

    /**
     * A jet algorithm supplied from outside, fastjet::JetDefinition::Plugin.
     *
     * It is given the ClusterSequence and records its clustering into it
     * through {@link ClusterSequence#pluginRecordIJRecombination} and
     * {@link ClusterSequence#pluginRecordIBRecombination}.
     */
    public interface Plugin {
        String description();

        void runClustering(ClusterSequence cs);

        double R();

        default boolean supportsGhostedPassiveAreas() {
            return false;
        }

        default void setGhostSeparationScale(double scale) {
            throw new FastJetException("set_ghost_separation_scale not supported");
        }

        default double ghostSeparationScale() {
            return 0.0;
        }

        default boolean exclusiveSequenceMeaningful() {
            return false;
        }

        default boolean isSpherical() {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Construction                                                        */
    /* ------------------------------------------------------------------ */

    /** An undefined definition, which no clustering accepts. */
    public JetDefinition() {
        this.jetAlgorithm = JetAlgorithm.UNDEFINED;
        this.rParam = 1.0;
        this.strategy = Strategy.BEST;
        this.recombiner = new DefaultRecombiner(RecombinationScheme.E_SCHEME);
        this.precision = Precision.defaultPrecision();
    }

    /** An algorithm of one parameter, R. */
    public JetDefinition(JetAlgorithm algorithm, double R) {
        this(algorithm, R, RecombinationScheme.E_SCHEME, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, RecombinationScheme scheme) {
        this(algorithm, R, scheme, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, RecombinationScheme scheme, Strategy strategy) {
        this(algorithm, R, 0.0, scheme, strategy, 1);
    }

    /** An algorithm without parameter, ee_kt. */
    public JetDefinition(JetAlgorithm algorithm) {
        this(algorithm, RecombinationScheme.E_SCHEME, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, RecombinationScheme scheme, Strategy strategy) {
        this(algorithm, 0.0, 0.0, scheme, strategy, 0);
    }

    /** An algorithm of two parameters, R and p (genkt, ee_genkt). */
    public JetDefinition(JetAlgorithm algorithm, double R, double extra) {
        this(algorithm, R, extra, RecombinationScheme.E_SCHEME, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, double extra, RecombinationScheme scheme) {
        this(algorithm, R, extra, scheme, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, double extra,
                         RecombinationScheme scheme, Strategy strategy) {
        this(algorithm, R, extra, scheme, strategy, 2);
    }

    /** With a recombiner of one's own. */
    public JetDefinition(JetAlgorithm algorithm, double R, Recombiner recombiner) {
        this(algorithm, R, recombiner, Strategy.BEST);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, Recombiner recombiner, Strategy strategy) {
        this(algorithm, R, 0.0, RecombinationScheme.E_SCHEME, strategy,
             algorithm.nParameters() == 2 ? 2 : 1);
        setRecombiner(recombiner);
    }

    public JetDefinition(JetAlgorithm algorithm, double R, double extra, Recombiner recombiner,
                         Strategy strategy) {
        this(algorithm, R, extra, RecombinationScheme.E_SCHEME, strategy, 2);
        setRecombiner(recombiner);
    }

    /** A plugin algorithm. */
    public JetDefinition(Plugin plugin) {
        this.jetAlgorithm = JetAlgorithm.PLUGIN;
        this.plugin = plugin;
        this.strategy = Strategy.PLUGIN_STRATEGY;
        this.rParam = plugin.R();
        this.recombiner = new DefaultRecombiner(RecombinationScheme.E_SCHEME);
        this.precision = Precision.defaultPrecision();
    }

    private JetDefinition(JetAlgorithm algorithm, double R, double extra,
                          RecombinationScheme scheme, Strategy strategy, int nparameters) {
        this.jetAlgorithm = algorithm;
        this.rParam = R;
        this.strategy = strategy;
        if (algorithm == JetAlgorithm.EE_KT) {
            // A fictional R so that the beam is used only for the last particle.
            this.rParam = 4.0;
        } else if (R > MAX_ALLOWABLE_R) {
            throw new FastJetException("Requested R = " + Fmt.g(R)
                + " for jet definition is larger than max_allowable_R = " + Fmt.g(MAX_ALLOWABLE_R));
        }
        final int expected = algorithm.nParameters();
        if (nparameters != expected) {
            throw new FastJetException("The jet algorithm you requested (" + algorithm.id
                + ") should be constructed with " + expected + " parameter(s) but was called with "
                + nparameters + " parameter(s)\n");
        }
        if (strategy == Strategy.PLUGIN_STRATEGY) {
            throw new FastJetException("plugin_strategy is reserved for plugins");
        }
        this.recombiner = new DefaultRecombiner(scheme);
        this.extraParam = extra;
        this.precision = Precision.defaultPrecision();
    }

    /** A copy. */
    public JetDefinition(JetDefinition other) {
        this.jetAlgorithm = other.jetAlgorithm;
        this.rParam = other.rParam;
        this.extraParam = other.extraParam;
        this.strategy = other.strategy;
        this.recombiner = other.recombiner;
        this.externalRecombiner = other.externalRecombiner;
        this.plugin = other.plugin;
        this.precision = other.precision;
    }

    /**
     * A definition from a short text: "antikt:0.4", "kt 1.0", "genkt:0.7:-0.5",
     * "eekt", "eegenkt:0.4:1", optionally followed by ",scheme=pt", ",strategy=N2Tiled",
     * ",precision=double".
     */
    public static JetDefinition parse(String text) {
        final String[] parts = text.trim().split("[,;]");
        final String[] head = parts[0].trim().split("[:\\s]+");
        final JetAlgorithm alg = JetAlgorithm.parse(head[0]);
        RecombinationScheme scheme = RecombinationScheme.E_SCHEME;
        Strategy strategy = Strategy.BEST;
        Precision precision = Precision.defaultPrecision();
        for (int i = 1; i < parts.length; i++) {
            final String[] kv = parts[i].trim().split("=", 2);
            if (kv.length < 2) continue;
            final String key = kv[0].trim().toLowerCase(Locale.ROOT);
            switch (key) {
                case "scheme", "recomb", "recombination" -> scheme = RecombinationScheme.parse(kv[1]);
                case "strategy" -> strategy = Strategy.parse(kv[1]);
                case "precision" -> precision = kv[1].trim().toLowerCase(Locale.ROOT).startsWith("d")
                    && !kv[1].trim().equalsIgnoreCase("dd") ? Precision.DOUBLE : Precision.DD;
                default -> throw new FastJetException("Unknown jet definition option '" + kv[0] + "'");
            }
        }
        JetDefinition def;
        switch (alg.nParameters()) {
            case 0 -> def = new JetDefinition(alg, scheme, strategy);
            case 1 -> {
                final double R = head.length > 1 ? Double.parseDouble(head[1]) : 0.4;
                def = new JetDefinition(alg, R, scheme, strategy);
            }
            default -> {
                final double R = head.length > 1 ? Double.parseDouble(head[1]) : 0.4;
                final double p = head.length > 2 ? Double.parseDouble(head[2]) : -1.0;
                def = new JetDefinition(alg, R, p, scheme, strategy);
            }
        }
        def.setPrecision(precision);
        return def;
    }

    /* ------------------------------------------------------------------ */
    /* Access                                                              */
    /* ------------------------------------------------------------------ */

    public JetAlgorithm jetAlgorithm() { return jetAlgorithm; }
    public JetAlgorithm jetFinder() { return jetAlgorithm; }
    public double R() { return rParam; }
    public double extraParam() { return extraParam; }
    public Strategy strategy() { return strategy; }
    public Plugin plugin() { return plugin; }
    public Recombiner recombiner() { return recombiner; }
    public Precision precision() { return precision; }

    /** The scheme, or EXTERNAL_SCHEME when the recombiner was supplied, as in FastJet. */
    public RecombinationScheme recombinationScheme() {
        return externalRecombiner ? RecombinationScheme.EXTERNAL_SCHEME : recombiner.scheme();
    }

    public void setJetAlgorithm(JetAlgorithm alg) { this.jetAlgorithm = alg; }
    public void setExtraParam(double p) { this.extraParam = p; }
    public void setStrategy(Strategy s) { this.strategy = s; }

    public JetDefinition setPrecision(Precision p) {
        this.precision = p == null ? Precision.defaultPrecision() : p;
        return this;
    }

    /** A copy under another precision. */
    public JetDefinition withPrecision(Precision p) {
        return new JetDefinition(this).setPrecision(p);
    }

    public void setRecombinationScheme(RecombinationScheme scheme) {
        this.recombiner = new DefaultRecombiner(scheme);
        this.externalRecombiner = false;
    }

    /** A recombiner of one's own; definitions then share it only if they hold the same object. */
    public void setRecombiner(Recombiner r) {
        if (r == null) {
            setRecombinationScheme(RecombinationScheme.E_SCHEME);
            return;
        }
        this.recombiner = r;
        this.externalRecombiner = true;
    }

    /** Takes the recombiner of another definition. */
    public void setRecombiner(JetDefinition other) {
        this.recombiner = other.recombiner;
        this.externalRecombiner = other.externalRecombiner;
    }

    public boolean hasSameRecombiner(JetDefinition other) {
        final RecombinationScheme scheme = recombinationScheme();
        if (other.recombinationScheme() != scheme) return false;
        return scheme != RecombinationScheme.EXTERNAL_SCHEME || recombiner == other.recombiner;
    }

    public boolean isSpherical() {
        if (jetAlgorithm == JetAlgorithm.PLUGIN) {
            return plugin.isSpherical();
        }
        return jetAlgorithm == JetAlgorithm.EE_KT || jetAlgorithm == JetAlgorithm.EE_GENKT;
    }

    /* ------------------------------------------------------------------ */
    /* Description                                                         */
    /* ------------------------------------------------------------------ */

    public String description() {
        final StringBuilder name = new StringBuilder(descriptionNoRecombiner());
        if (jetAlgorithm == JetAlgorithm.PLUGIN || jetAlgorithm == JetAlgorithm.UNDEFINED) {
            return name.toString();
        }
        name.append(jetAlgorithm.nParameters() == 0 ? " with " : " and ");
        name.append(recombiner.description());
        return name.toString();
    }

    public String descriptionNoRecombiner() {
        if (jetAlgorithm == JetAlgorithm.PLUGIN) {
            return plugin.description();
        } else if (jetAlgorithm == JetAlgorithm.UNDEFINED) {
            return "uninitialised JetDefinition (jet_algorithm=undefined_jet_algorithm)";
        }
        final StringBuilder name = new StringBuilder(jetAlgorithm.description());
        switch (jetAlgorithm.nParameters()) {
            case 0 -> name.append(" (NB: no R)");
            case 1 -> name.append(" with R = ").append(Fmt.g(R()));
            default -> {
                name.append(" with R = ").append(Fmt.g(R()));
                if (jetAlgorithm == JetAlgorithm.CAMBRIDGE_FOR_PASSIVE) {
                    name.append("and a special hack whereby particles with kt < ")
                        .append(Fmt.g(extraParam)).append("are treated as passive ghosts");
                } else {
                    name.append(", p = ").append(Fmt.g(extraParam));
                }
            }
        }
        return name.toString();
    }

    public static String algorithmDescription(JetAlgorithm alg) {
        return alg.description();
    }

    public static int nParametersForAlgorithm(JetAlgorithm alg) {
        return alg.nParameters();
    }

    @Override
    public String toString() {
        return description();
    }

    /* ------------------------------------------------------------------ */
    /* Clustering                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * The inclusive jets of the particles, the C++ operator(): sorted by
     * decreasing energy for a spherical algorithm, by decreasing pt otherwise.
     */
    public List<PseudoJet> cluster(List<PseudoJet> particles) {
        final ClusterSequence cs = new ClusterSequence(particles, this);
        return isSpherical() ? PseudoJet.sortedByE(cs.inclusiveJets())
                             : PseudoJet.sortedByPt(cs.inclusiveJets());
    }
}
