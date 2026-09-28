package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * fastjet::NestedDefsPlugin: the jet definitions applied one after the
 * other, the inclusive jets of each being the input of the next. Only the
 * last one's distances are recorded; the earlier mergings get 0.
 *
 * Every step clusters at the precision of the ClusterSequence the plugin
 * runs in.
 */
public final class NestedDefsPlugin implements JetDefinition.Plugin {

    private final List<JetDefinition> defs;

    public NestedDefsPlugin(List<JetDefinition> defs) {
        this.defs = List.copyOf(defs);
    }

    public List<JetDefinition> definitions() {
        return defs;
    }

    @Override
    public String description() {
        final StringBuilder desc = new StringBuilder("NestedDefs: successive application of ");
        int i = 1;
        for (JetDefinition d : defs) {
            desc.append("Definition ").append(i++).append(" [").append(d.description()).append("] - ");
        }
        return desc.toString();
    }

    @Override
    public double R() {
        return defs.get(defs.size() - 1).R();
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        List<PseudoJet> momenta = cs.jets();
        int stepN = momenta.size();
        final int[] conversion = new int[Math.max(2 * stepN, 1)];
        for (int i = 0; i < stepN; i++) conversion[i] = i;
        for (int d = 0; d < defs.size(); d++) {
            final boolean lastDef = d == defs.size() - 1;
            final ClusterSequence step = new ClusterSequence(momenta, defs.get(d).withPrecision(cs.precision()));
            final List<ClusterSequence.HistoryElement> history = step.history();
            final List<PseudoJet> next = new ArrayList<>();
            final List<Integer> nextConversion = new ArrayList<>();
            for (int h = stepN; h < history.size(); h++) {
                final ClusterSequence.HistoryElement hist = history.get(h);
                final int j1 = history.get(hist.parent1()).jetpIndex();
                if (hist.parent2() == ClusterSequence.BEAM_JET) {
                    // kept for the next definition, or recorded after the last
                    if (lastDef) {
                        cs.pluginRecordIBRecombination(conversion[j1], hist.dijDD());
                    } else {
                        next.add(step.jet(j1));
                        nextConversion.add(conversion[j1]);
                    }
                } else {
                    final int j2 = history.get(hist.parent2()).jetpIndex();
                    final PseudoJet newjet = step.jet(hist.jetpIndex());
                    final int k = lastDef
                        ? cs.pluginRecordIJRecombination(conversion[j1], conversion[j2], hist.dijDD(), newjet)
                        : cs.pluginRecordIJRecombination(conversion[j1], conversion[j2], 0.0, newjet);
                    conversion[hist.jetpIndex()] = k;
                }
            }
            momenta = next;
            stepN = momenta.size();
            for (int i = 0; i < stepN; i++) conversion[i] = nextConversion.get(i);
        }
    }
}
