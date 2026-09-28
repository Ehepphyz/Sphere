package com.sphere.core.fastjet;

import java.util.Locale;

/** The native jet algorithms, with the numbers FastJet gives them. */
public enum JetAlgorithm {
    /** The longitudinally invariant kt algorithm. */
    KT(0, "Longitudinally invariant kt algorithm", 1),
    /** The longitudinally invariant Cambridge/Aachen algorithm. */
    CAMBRIDGE(1, "Longitudinally invariant Cambridge/Aachen algorithm", 1),
    /** The anti-kt algorithm. */
    ANTIKT(2, "Longitudinally invariant anti-kt algorithm", 1),
    /** The generalised kt algorithm, d_ij = min(kt_i^2p, kt_j^2p) dR^2/R^2. */
    GENKT(3, "Longitudinally invariant generalised kt algorithm", 2),
    /** Cambridge with particles below a pt treated as passive ghosts, for passive areas. */
    CAMBRIDGE_FOR_PASSIVE(11, "Longitudinally invariant Cambridge/Aachen algorithm", 2),
    /** Generalised kt for passive areas. */
    GENKT_FOR_PASSIVE(13, "Longitudinally invariant generalised kt algorithm", 2),
    /** The e+e- kt (Durham) algorithm. */
    EE_KT(50, "e+e- kt (Durham) algorithm (NB: no R)", 0),
    /** The e+e- generalised kt algorithm. */
    EE_GENKT(53, "e+e- generalised kt algorithm", 2),
    /** A plugin. */
    PLUGIN(99, "plugin algorithm", 1),
    /** Not yet set. */
    UNDEFINED(999, "undefined jet algorithm", 1);

    public final int id;
    private final String description;
    private final int nParameters;

    JetAlgorithm(int id, String description, int nParameters) {
        this.id = id;
        this.description = description;
        this.nParameters = nParameters;
    }

    public String description() {
        return description;
    }

    /** How many parameters the algorithm takes: R, and p for the generalised ones. */
    public int nParameters() {
        return nParameters;
    }

    public boolean isSpherical() {
        return this == EE_KT || this == EE_GENKT;
    }

    public static JetAlgorithm byId(int id) {
        for (JetAlgorithm a : values()) {
            if (a.id == id) return a;
        }
        throw new FastJetException("Unrecognised jet algorithm " + id);
    }

    /**
     * An algorithm from the names physicists write:
     * kt, cam, ca, cambridge, aachen, antikt, akt, genkt, eekt, durham, eegenkt.
     */
    public static JetAlgorithm parse(String text) {
        final String t = text.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace("/", "");
        return switch (t) {
            case "kt", "0" -> KT;
            case "cam", "ca", "cambridge", "aachen", "cambridgeaachen", "1" -> CAMBRIDGE;
            case "antikt", "akt", "2" -> ANTIKT;
            case "genkt", "3" -> GENKT;
            case "cambridgeforpassive", "11" -> CAMBRIDGE_FOR_PASSIVE;
            case "genktforpassive", "13" -> GENKT_FOR_PASSIVE;
            case "eekt", "durham", "50" -> EE_KT;
            case "eegenkt", "53" -> EE_GENKT;
            default -> throw new FastJetException("Unknown jet algorithm '" + text
                + "' (kt, cam, antikt, genkt, eekt, eegenkt)");
        };
    }
}
