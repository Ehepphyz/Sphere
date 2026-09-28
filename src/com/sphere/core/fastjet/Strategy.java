package com.sphere.core.fastjet;

import java.util.Locale;

/**
 * The clustering strategies, with FastJet's numbering.
 *
 * Every strategy gives the same jets; they differ in speed with the
 * multiplicity and R. The NlnN ones built on CGAL's Delaunay triangulation are
 * not available (as in a FastJet built without CGAL) and fall back to
 * N2MHTLazy25; NlnNCam, which needs no CGAL, is implemented.
 */
public enum Strategy {
    N2MHTLAZY9_ANTIKT_SEPARATE_GHOSTS(-10, "N2MHTLazy9AntiKtSeparateGhosts"),
    N2MHTLAZY9(-7, "N2MHTLazy9"),
    N2MHTLAZY25(-6, "N2MHTLazy25"),
    N2MHTLAZY9ALT(-5, "N2MHTLazy9Alt"),
    N2MINHEAPTILED(-4, "N2MinHeapTiled"),
    N2TILED(-3, "N2Tiled"),
    N2POORTILED(-2, "N2PoorTiled"),
    N2PLAIN(-1, "N2Plain"),
    N3DUMB(0, "N3Dumb"),
    BEST(1, "Best"),
    NLNN(2, "NlnN"),
    NLNN3PI(3, "NlnN3pi"),
    NLNN4PI(4, "NlnN4pi"),
    NLNNCAM4PI(14, "NlnNCam4pi"),
    NLNNCAM2PI2R(13, "NlnNCam2pi2R"),
    NLNNCAM(12, "NlnNCam"),
    BEST_FJ30(21, "BestFJ30"),
    N2PLAIN_EE_ACCURATE(31, "N2PlainEEAccurate"),
    PLUGIN_STRATEGY(999, "plugin strategy");

    public final int id;
    private final String label;

    Strategy(int id, String label) {
        this.id = id;
        this.label = label;
    }

    /** The name FastJet prints for it. */
    public String label() {
        return label;
    }

    public static Strategy byId(int id) {
        for (Strategy s : values()) {
            if (s.id == id) return s;
        }
        throw new FastJetException("Unrecognised value for strategy: " + id);
    }

    public static Strategy parse(String text) {
        final String t = text.trim();
        try {
            return byId(Integer.parseInt(t));
        } catch (NumberFormatException notANumber) {
            // a name
        }
        final String bare = t.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        for (Strategy s : values()) {
            if (s.label.toLowerCase(Locale.ROOT).replace(" ", "").equals(bare)
                    || s.name().toLowerCase(Locale.ROOT).replace("_", "").equals(bare)) {
                return s;
            }
        }
        throw new FastJetException("Unknown strategy '" + text + "'");
    }
}
