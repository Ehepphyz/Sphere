package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.nsubjettiness.MeasureDefinition.NormalizedCutoffMeasure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * N-jettiness as an exclusive jet algorithm, NjettinessPlugin: N axes are
 * found, each particle goes to its region, and a jet is the sum of a
 * region. The clustering tree is a made-up one (distances -1), and the
 * clustering carries {@link NjettinessExtras}.
 *
 * {@link XConePlugin} has the recommended settings.
 */
public class NjettinessPlugin implements JetDefinition.Plugin {

    private static final LimitedWarning OLD_CONSTRUCTOR_WARNING = new LimitedWarning();

    private final Njettiness njettinessFinder;
    private final int n;

    public NjettinessPlugin(int n, AxesDefinition axesDef, MeasureDefinition measureDef) {
        this.njettinessFinder = new Njettiness(axesDef, measureDef);
        this.n = n;
    }

    @Override
    public String description() {
        return "N-jettiness jet finder";
    }

    /** As in the C++, R has no meaning here. */
    @Override
    public double R() {
        return -1.0;
    }

    public void setAxes(List<PseudoJet> myAxes) {
        njettinessFinder.setAxes(myAxes);
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final List<PseudoJet> particles = cs.jets();
        // area information removed, in case this runs inside a ClusterSequenceArea
        for (PseudoJet p : particles) p.setStructure(null);
        final TauComponents tauComponents;
        final TauPartition tauPartition;
        synchronized (njettinessFinder) {
            tauComponents = njettinessFinder.getTauComponents(n, particles);
            tauPartition = njettinessFinder.currentPartition();
        }
        final List<List<Integer>> partition = tauPartition.jetsList();
        final List<Integer> jetIndicesForExtras = new ArrayList<>();
        for (int i0 = 0; i0 < partition.size(); ++i0) {
            final int i = partition.size() - 1 - i0;
            final List<Integer> indices = partition.get(i);
            if (indices.isEmpty()) continue;
            while (indices.size() > 1) {
                final int mergeI = indices.remove(indices.size() - 1);
                final int mergeJ = indices.remove(indices.size() - 1);
                final int newIndex = cs.pluginRecordIJRecombination(mergeI, mergeJ, -1.0);
                indices.add(newIndex);
            }
            final int finalJet = indices.get(indices.size() - 1);
            cs.pluginRecordIBRecombination(finalJet, -1.0);
            jetIndicesForExtras.add(cs.jet(finalJet).clusterHistIndex());
        }
        Collections.reverse(jetIndicesForExtras);
        cs.pluginAssociateExtras(new NjettinessExtras(tauComponents, jetIndicesForExtras));
    }

    /* ---- the v1 constructors, deprecated in the C++ ---- */

    private static void warnOld() {
        OLD_CONSTRUCTOR_WARNING.warn("NjettinessPlugin:  You are using the old style constructor.  This is deprecated as of v2.1 and will be removed in v3.0.  Please use the NjettinessPlugin constructor based on AxesDefinition and MeasureDefinition instead.");
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 0, Double.NaN, Double.NaN, Double.NaN);
        this.n = n;
        warnOld();
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 1, para1, Double.NaN, Double.NaN);
        this.n = n;
        warnOld();
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1,
                            double para2) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 2, para1, para2, Double.NaN);
        this.n = n;
        warnOld();
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode axesMode, Njettiness.MeasureMode measureMode, double para1,
                            double para2, double para3) {
        this.njettinessFinder = new Njettiness(axesMode, measureMode, 3, para1, para2, para3);
        this.n = n;
        warnOld();
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode mode, double beta, double r0, double rcutoff) {
        this.njettinessFinder = new Njettiness(mode, new NormalizedCutoffMeasure(beta, r0, rcutoff));
        this.n = n;
        warnOld();
    }

    public NjettinessPlugin(int n, Njettiness.AxesMode mode, double beta, double r0) {
        this(n, mode, beta, r0, Double.MAX_VALUE);
    }
}
