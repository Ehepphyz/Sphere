package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.Fmt;

import java.util.List;

/**
 * The declusterings as JSON, LundJSON.hh: one object per declustering with
 * p_pt, p_m, h_pt, s_pt, z, Delta, kt, psi, written with the six
 * significant digits of a default C++ stream, as the contrib does.
 */
public final class LundJSON {

    private LundJSON() {
    }

    public static String toJson(LundDeclustering d) {
        return "{" + elements(d) + "}";
    }

    public static String toJson(List<LundDeclustering> ds) {
        final StringBuilder o = new StringBuilder("[");
        for (int i = 0; i < ds.size(); i++) {
            if (i != 0) o.append(',');
            o.append(toJson(ds.get(i)));
        }
        return o.append(']').toString();
    }

    private static String elements(LundDeclustering d) {
        return "\"p_pt\":" + Fmt.g(d.pair().pt()) + ","
            + "\"p_m\":" + Fmt.g(d.pair().m()) + ","
            + "\"h_pt\":" + Fmt.g(d.harder().pt()) + ","
            + "\"s_pt\":" + Fmt.g(d.softer().pt()) + ","
            + "\"z\":" + Fmt.g(d.z()) + ","
            + "\"Delta\":" + Fmt.g(d.Delta()) + ","
            + "\"kt\":" + Fmt.g(d.kt()) + ","
            + "\"psi\":" + Fmt.g(d.psi());
    }
}
