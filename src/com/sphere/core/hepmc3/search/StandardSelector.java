package com.sphere.core.hepmc3.search;

import com.sphere.core.hepmc3.GenParticle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The selectors HepMC3 defines: status, PDG id, pT, energy, rapidity,
 * pseudorapidity, phi, transverse energy and mass. They combine into cuts,
 * e.g. {@code PT.gt(15.).and(RAPIDITY.abs().lt(2.5))}.
 */
public final class StandardSelector {

    private StandardSelector() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static final Selector STATUS = Selector.ofInt(GenParticle::status);
    public static final Selector PDG_ID = Selector.ofInt(GenParticle::pdgId);
    public static final Selector PT = Selector.ofDouble(p -> p.momentum().pt());
    public static final Selector ENERGY = Selector.ofDouble(p -> p.momentum().e());
    public static final Selector RAPIDITY = Selector.ofDouble(p -> p.momentum().rap());
    public static final Selector ETA = Selector.ofDouble(p -> p.momentum().eta());
    public static final Selector PHI = Selector.ofDouble(p -> p.momentum().phi());
    public static final Selector ET = Selector.ofDouble(p -> p.momentum().e() * (p.momentum().pt() / p.momentum().p3mod()));
    public static final Selector MASS = Selector.ofDouble(p -> p.momentum().m());

    /** The selectors by the names a cut expression uses ("pt", "eta", "status"...). */
    public static Map<String, Selector> byName() {
        final Map<String, Selector> m = new LinkedHashMap<>();
        m.put("status", STATUS);
        m.put("pid", PDG_ID);
        m.put("pdg_id", PDG_ID);
        m.put("pt", PT);
        m.put("e", ENERGY);
        m.put("energy", ENERGY);
        m.put("rap", RAPIDITY);
        m.put("rapidity", RAPIDITY);
        m.put("y", RAPIDITY);
        m.put("eta", ETA);
        m.put("phi", PHI);
        m.put("et", ET);
        m.put("m", MASS);
        m.put("mass", MASS);
        return m;
    }
}
