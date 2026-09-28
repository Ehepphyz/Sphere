package com.sphere.core.fastjet;

import java.util.Locale;

/** How two momenta are merged into one, with FastJet's numbering. */
public enum RecombinationScheme {
    /** Four-vector addition. */
    E_SCHEME(0, "E scheme recombination"),
    /** pt-weighted rapidity and azimuth, massless result. */
    PT_SCHEME(1, "pt scheme recombination"),
    /** pt^2-weighted rapidity and azimuth, massless result. */
    PT2_SCHEME(2, "pt2 scheme recombination"),
    /** As pt, with the particles first made massless by rescaling the three-momentum. */
    ET_SCHEME(3, "Et scheme recombination"),
    /** As pt2, with the three-momentum rescaled. */
    ET2_SCHEME(4, "Et2 scheme recombination"),
    /** Boost-invariant pt scheme, no preprocessing. */
    BIPT_SCHEME(5, "boost-invariant pt scheme recombination"),
    /** Boost-invariant pt2 scheme. */
    BIPT2_SCHEME(6, "boost-invariant pt2 scheme recombination"),
    /** Winner-takes-all in pt: the harder direction, the summed pt. */
    WTA_PT_SCHEME(7, "pt-ordered Winner-Takes-All recombination"),
    /** Winner-takes-all in |p|. */
    WTA_MODP_SCHEME(8, "|3-momentum|-ordered Winner-Takes-All recombination"),
    /** A recombiner supplied by the user. */
    EXTERNAL_SCHEME(99, "external recombination");

    public final int id;
    private final String description;

    RecombinationScheme(int id, String description) {
        this.id = id;
        this.description = description;
    }

    public String description() {
        return description;
    }

    public static RecombinationScheme parse(String text) {
        final String t = text.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (t) {
            case "e", "e_scheme", "0" -> E_SCHEME;
            case "pt", "pt_scheme", "1" -> PT_SCHEME;
            case "pt2", "pt2_scheme", "2" -> PT2_SCHEME;
            case "et", "et_scheme", "3" -> ET_SCHEME;
            case "et2", "et2_scheme", "4" -> ET2_SCHEME;
            case "bipt", "bipt_scheme", "5" -> BIPT_SCHEME;
            case "bipt2", "bipt2_scheme", "6" -> BIPT2_SCHEME;
            case "wta", "wta_pt", "wta_pt_scheme", "7" -> WTA_PT_SCHEME;
            case "wta_modp", "wta_modp_scheme", "8" -> WTA_MODP_SCHEME;
            default -> throw new FastJetException("Unknown recombination scheme '" + text
                + "' (E, pt, pt2, Et, Et2, BIpt, BIpt2, WTA_pt, WTA_modp)");
        };
    }
}
