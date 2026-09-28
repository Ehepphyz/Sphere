package com.sphere.core.fastjet.tools;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.CompositeJetStructure;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Recombiner;
import com.sphere.core.fastjet.Selector;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtering and trimming, fastjet::Filter: recluster the jet into subjets
 * (C/A of radius Rfilt, or any definition), optionally subtract them, and
 * keep those a selector accepts. Filtering keeps SelectorNHardest(n);
 * trimming keeps SelectorPtFractionMin(f), whose reference is the jet.
 */
public class Filter implements Transformer {

    static {
        Citations.use("filter"); // listed in the console's Citations menu once used
    }

    /** The filtered jet's structure: its kept pieces and the rejected ones. */
    public static final class FilterStructure extends CompositeJetStructure {
        List<PseudoJet> rejected = new ArrayList<>();

        FilterStructure(List<PseudoJet> pieces, Recombiner rec) {
            super(pieces, rec);
        }

        @Override
        public String description() {
            return "Filtered PseudoJet";
        }

        public List<PseudoJet> rejected() {
            return rejected;
        }
    }

    private JetDefinition subjetDef;
    private FunctionOfPseudoJet<Double> rfiltFunc;
    private double rfilt = -1;
    private Selector selector;
    private double rho;
    private FunctionOfPseudoJet<PseudoJet> subtractor;
    private final boolean initialised;

    public Filter() {
        initialised = false;
    }

    public Filter(JetDefinition subjetDef, Selector selector, double rho) {
        this.subjetDef = subjetDef;
        this.selector = selector;
        this.rho = rho;
        this.initialised = true;
    }

    public Filter(JetDefinition subjetDef, Selector selector) {
        this(subjetDef, selector, 0.0);
    }

    public Filter(double rfilt, Selector selector, double rho) {
        if (rfilt < 0) {
            throw new FastJetException("Attempt to create a Filter with a negative filtering radius");
        }
        this.rfilt = rfilt;
        this.selector = selector;
        this.rho = rho;
        this.initialised = true;
    }

    public Filter(double rfilt, Selector selector) {
        this(rfilt, selector, 0.0);
    }

    public Filter(FunctionOfPseudoJet<Double> rfiltFunc, Selector selector, double rho) {
        this.rfiltFunc = rfiltFunc;
        this.selector = selector;
        this.rho = rho;
        this.initialised = true;
    }

    /** Trimming: C/A subjets of radius rtrim, kept if they carry a fraction ptfrac of the jet's pt. */
    public static Filter trimmer(double rtrim, double ptfrac) {
        Citations.use("trimming");
        return new Filter(rtrim, Selector.ptFractionMin(ptfrac));
    }

    /** Filtering: C/A subjets of radius rfilt, the n hardest kept. */
    public static Filter filter(double rfilt, int nHardest) {
        return new Filter(rfilt, Selector.nHardest(nHardest));
    }

    public void setSubtractor(FunctionOfPseudoJet<PseudoJet> s) {
        this.subtractor = s;
    }

    public FunctionOfPseudoJet<PseudoJet> subtractor() {
        return subtractor;
    }

    @Override
    public String description() {
        if (!initialised) {
            return "uninitialised Filter";
        }
        final StringBuilder o = new StringBuilder("Filter with subjet_def = ");
        if (rfiltFunc != null) {
            o.append("Cambridge/Aachen algorithm with dynamic Rfilt (recomb. scheme deduced from jet, or E-scheme if not unique)");
        } else if (rfilt > 0) {
            o.append("Cambridge/Aachen algorithm with Rfilt = ").append(com.sphere.core.fastjet.Fmt.g(rfilt))
             .append(" (recomb. scheme deduced from jet, or E-scheme if not unique)");
        } else {
            o.append(subjetDef.description());
        }
        o.append(", selection ").append(selector.description());
        if (subtractor != null) {
            o.append(", subtractor: ").append(subtractor.description());
        } else if (rho != 0) {
            o.append(", subtracting with rho = ").append(com.sphere.core.fastjet.Fmt.g(rho));
        }
        return o.toString();
    }

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (!initialised) {
            throw new FastJetException("uninitialised Filter");
        }
        List<PseudoJet> subjets = new ArrayList<>();
        final boolean caOptimised = setFilteredElements(jet, subjets);
        if (subtractor != null) {
            subjets = subtractor.apply(subjets);
        } else if (rho != 0) {
            final Subtractor s = new Subtractor(rho);
            final List<PseudoJet> sub = new ArrayList<>(subjets.size());
            for (PseudoJet p : subjets) {
                sub.add(s.result(p));
            }
            subjets = sub;
        }
        final List<PseudoJet> kept = new ArrayList<>();
        final List<PseudoJet> rejected = new ArrayList<>();
        Selector sel = new Selector(selector.worker());
        if (sel.takesReference()) {
            sel.setReference(jet);
        }
        sel.sift(subjets, kept, rejected);
        return finalise(kept, rejected, caOptimised);
    }

    private boolean setFilteredElements(PseudoJet jet, List<PseudoJet> filtered) {
        final Recluster recluster;
        if (rfilt >= 0 || rfiltFunc != null) {
            recluster = new Recluster(JetAlgorithm.CAMBRIDGE, rfiltFunc != null ? rfiltFunc.result(jet) : rfilt,
                                      Recluster.Keep.KEEP_ALL);
        } else {
            recluster = new Recluster(subjetDef, false, Recluster.Keep.KEEP_ALL);
        }
        return recluster.getNewJetsAndDef(jet, filtered);
    }

    private PseudoJet finalise(List<PseudoJet> kept, List<PseudoJet> rejected, boolean caOptimisationUsed) {
        PseudoJet filtered;
        FilterStructure fs;
        if (kept.size() + rejected.size() > 0) {
            final Recombiner rec = !kept.isEmpty()
                ? kept.get(0).associatedClusterSequence().jetDef().recombiner()
                : rejected.get(0).associatedClusterSequence().jetDef().recombiner();
            filtered = PseudoJet.join(kept, rec);
            fs = new FilterStructure(kept, rec);
        } else {
            filtered = PseudoJet.join(kept);
            fs = new FilterStructure(kept, null);
        }
        filtered.setStructure(fs);
        fs.rejected = rejected;
        if (caOptimisationUsed && kept.size() + rejected.size() > 0) {
            final PseudoJet first = !kept.isEmpty() ? kept.get(0) : rejected.get(0);
            if (first.hasArea() && !first.validatedCsab().hasExplicitGhosts()) {
                fs.discardArea();
            }
        }
        return filtered;
    }
}
