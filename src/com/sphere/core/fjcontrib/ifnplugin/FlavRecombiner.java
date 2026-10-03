package com.sphere.core.fjcontrib.ifnplugin;

import com.sphere.core.fastjet.DefaultRecombiner;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;

/**
 * The E-scheme (or any default scheme) recombination that also adds
 * flavours, fastjet::contrib::FlavRecombiner: each input gets a FlavHistory,
 * and each merged jet the sum of its parents' current flavours, summed as
 * the chosen {@link FlavSummation} says.
 *
 * <p>As in the C++, preprocessing replaces the default scheme's own (which
 * only matters for the pt and Et schemes, whose inputs it makes massless).
 */
public class FlavRecombiner extends DefaultRecombiner {

    /** How flavours add up. */
    public enum FlavSummation {
        /** b = +1, bbar = -1, b + bbar = 0, b + b = +2. */
        NET("net_flav"),
        /** b = bbar = 1, b + bbar = b + b = 0. */
        MODULO_2("mod2_flav"),
        /** 1 as soon as any b or bbar is there. */
        ANY_ABS("any_flav");

        private final String label;

        FlavSummation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final FlavSummation flavSummation;
    private boolean flavourFromUserIndex;

    public FlavRecombiner() {
        this(FlavSummation.NET);
    }

    public FlavRecombiner(FlavSummation flavSummation) {
        super();
        this.flavSummation = flavSummation;
    }

    /** With another momentum scheme than E. */
    public FlavRecombiner(RecombinationScheme scheme, FlavSummation flavSummation) {
        super(scheme);
        this.flavSummation = flavSummation;
    }

    public FlavSummation flavSummation() {
        return flavSummation;
    }

    /**
     * Inputs that carry no flavour information take that of the PDG code in
     * their user index, instead of being refused (Sphere's addition, for
     * events read from LHE or HepMC files).
     */
    public FlavRecombiner setFlavourFromUserIndex(boolean value) {
        flavourFromUserIndex = value;
        return this;
    }

    @Override
    public void preprocess(PseudoJet p) {
        FlavInfo flav;
        if (p.hasUserInfo(FlavInfo.class)) {
            flav = p.userInfo(FlavInfo.class);
        } else if (p.hasUserInfo(FlavHistory.class)) {
            // as in the C++: right when reclustering the constituents of
            // another clustering, arguable when clustering its jets
            flav = p.userInfo(FlavHistory.class).initialFlavour();
        } else if (flavourFromUserIndex) {
            flav = FlavInfo.fromUserIndex(p);
        } else {
            throw new FastJetException("Could not identify FlavInfo or FlavHistory");
        }
        p.setUserInfo(new FlavHistory(applySummationChoice(flav)));
    }

    @Override
    public void recombine(PseudoJet pa, PseudoJet pb, PseudoJet pab) {
        super.recombine(pa, pb, pab);
        final FlavInfo flav = FlavHistory.currentFlavourOf(pa).plus(FlavHistory.currentFlavourOf(pb));
        pab.setUserInfo(new FlavHistory(applySummationChoice(flav), pab.clusterHistIndex()));
    }

    /** The flavour made consistent with the summation choice. */
    public FlavInfo applySummationChoice(FlavInfo flav) {
        return switch (flavSummation) {
            case MODULO_2 -> flav.modulo2();
            case ANY_ABS -> flav.anyAbs();
            case NET -> flav;
        };
    }

    @Override
    public String description() {
        return super.description() + " and " + flavSummation.label() + " flavour recombination ";
    }
}
